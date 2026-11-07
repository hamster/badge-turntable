package org.snurkle.badgeturntable.rig;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.preference.PreferenceManager;

import org.json.JSONObject;
import org.snurkle.badgeturntable.jobs.ApiException;
import org.snurkle.badgeturntable.jobs.Job;
import org.snurkle.badgeturntable.jobs.JobRunner;
import org.snurkle.badgeturntable.jobs.Json;
import org.snurkle.badgeturntable.remote.RemoteServer;
import org.snurkle.badgeturntable.remote.RemoteShots;
import org.snurkle.badgeturntable.session.Session;
import org.snurkle.badgeturntable.session.SessionStore;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * What lives as long as the app: the job runner, the sessions, the shots held for the
 * editor, the capture target, and the remote-control server, which runs whenever the app is
 * in the foreground (Spec.md, On the phone). The Capture screen attaches its {@link Rig}
 * (camera, ring, turntable) while it's open; without one, camera endpoints answer 503.
 */
public final class Station {
    private static final String TAG = "Station";

    /**
     * Where captures go (Spec.md, Phone and editor at once): a session, the unnamed set of
     * shots for the editor, or nothing yet. Shared by the phone and the editor. Immutable.
     */
    public static final class Target {
        public static final String NONE = "none", SESSION = "session", SHOTS = "shots";

        public final String kind;
        public final String sessionId, name;
        /** Who chose it: {@link Job#PHONE} or {@link Job#EDITOR}; null for none. */
        public final String setBy;
        /** The editor chose it, or joined it: its captures may go there. */
        public final boolean editorJoined;

        Target(String kind, String sessionId, String name, String setBy, boolean editorJoined) {
            this.kind = kind;
            this.sessionId = sessionId;
            this.name = name;
            this.setBy = setBy;
            this.editorJoined = editorJoined;
        }

        public boolean isSession() {
            return SESSION.equals(kind);
        }

        public boolean isShots() {
            return SHOTS.equals(kind);
        }

        public boolean isNone() {
            return NONE.equals(kind);
        }

        /** As a job's target: {kind, sessionId, name}. */
        public JSONObject jobJson() {
            return Json.obj("kind", kind, "sessionId", sessionId, "name", name);
        }

        /** As status reports it. */
        public JSONObject toJson() {
            return Json.obj("kind", kind, "sessionId", sessionId, "name", name, "setBy", setBy,
                    "editorJoined", editorJoined);
        }
    }

    public interface Listener {
        /** The target changed, a Rig attached or detached, or the server started, stopped or gained a client. Any thread. */
        void onStationChanged();
    }

    public final Context context;
    public final SharedPreferences prefs;
    public final JobRunner jobs = new JobRunner();
    public final SessionStore sessions;
    public final RemoteShots shots;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile Target target = new Target(Target.NONE, null, null, null, false);
    private volatile Rig rig;
    /** A rig with no camera, for IR presses and turntable status while the Capture screen is closed. */
    private Rig idleRig;
    private volatile RemoteServer server;
    private boolean foreground;
    private Runnable cameraLauncher;

    private final SharedPreferences.OnSharedPreferenceChangeListener prefListener = (p, key) -> {
        if ("turntable_model".equals(key) || "ring_markers".equals(key)) {
            synchronized (this) {
                idleRig = null;  // made again with the new profile when next needed
            }
        }
        if ("remote_enabled".equals(key) || "remote_name".equals(key) || "remote_port".equals(key)) {
            main.post(() -> {
                stopServer();
                updateServer();
            });
        }
    };

    public Station(Context context) {
        this.context = context.getApplicationContext();
        prefs = PreferenceManager.getDefaultSharedPreferences(this.context);
        sessions = new SessionStore(this.context);
        sessions.clearScratch();
        shots = new RemoteShots(this.context.getFilesDir());
        jobs.addListener(job -> {
            if (job.finished()) shots.recordJob(job);
        });
        sessions.addListener(new SessionStore.Listener() {
            @Override
            public void onSessionChanged(Session s) {
                Target t = target;
                if (t.isSession() && s.id.equals(t.sessionId) && !Objects.equals(s.name, t.name)) {
                    target = new Target(t.kind, t.sessionId, s.name, t.setBy, t.editorJoined);
                    fire();
                }
            }

            @Override
            public void onSessionRemoved(String id) {
                Target t = target;
                if (t.isSession() && id.equals(t.sessionId)) {
                    target = new Target(Target.NONE, null, null, null, false);
                    fire();
                }
            }
        });
        prefs.registerOnSharedPreferenceChangeListener(prefListener);
    }

    public void addListener(Listener l) {
        listeners.add(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    public void fire() {
        for (Listener l : listeners) {
            try {
                l.onStationChanged();
            } catch (RuntimeException e) {
                Log.w(TAG, "listener failed", e);
            }
        }
    }

    // ---- The Capture screen's rig ----------------------------------------------------

    /** The Capture screen's rig while it's open, else null. */
    public Rig rig() {
        return rig;
    }

    /**
     * The Capture screen's rig, or one with no camera for the turntable profile, IR presses
     * and calibration state.
     */
    public Rig rigOrIdle() {
        Rig r = rig;
        if (r != null) return r;
        synchronized (this) {
            if (idleRig == null) idleRig = new Rig(context, this);
            return idleRig;
        }
    }

    /** The rig, or 503 camera_unavailable. */
    public Rig requireRig() throws ApiException {
        Rig r = rig;
        if (r == null) throw ApiException.cameraClosed();
        return r;
    }

    /** True while the Capture screen is open with its camera bound. */
    public boolean cameraOpen() {
        Rig r = rig;
        return r != null && r.camera.isOpen();
    }

    /** The Capture screen started (main thread). */
    public void attach(Rig r) {
        rig = r;
        RemoteServer s = server;
        if (s != null) s.attachRig(r);
        fire();
    }

    /** The Capture screen stopped (main thread): stops any job, which needs the camera. */
    public void detach(Rig r) {
        if (rig != r) return;
        if (jobs.current() != null) {
            try {
                jobs.abort(Job.PHONE);
            } catch (ApiException ignored) {
            }
        }
        RemoteServer s = server;
        if (s != null) s.detachRig(r);
        rig = null;
        fire();
    }

    /** How to open the Capture screen; set by the App. */
    public void setCameraLauncher(Runnable launcher) {
        cameraLauncher = launcher;
    }

    /**
     * Opens the Capture screen (the editor's POST /api/camera/open) and waits up to 10 s for
     * its camera. Any thread but main.
     */
    public void openCamera() throws ApiException, InterruptedException {
        if (cameraOpen()) return;
        Runnable launch = cameraLauncher;
        if (launch == null) throw ApiException.cameraClosed();
        main.post(launch);
        long deadline = System.currentTimeMillis() + 10_000;
        while (!cameraOpen()) {
            if (System.currentTimeMillis() > deadline) {
                throw new ApiException(503, "camera_unavailable",
                        "The Camera screen didn't open. Check the phone: it may be asking for camera access.");
            }
            //noinspection BusyWait
            Thread.sleep(100);
        }
    }

    // ---- The capture target -----------------------------------------------------------

    public Target target() {
        return target;
    }

    /**
     * Opens a session for captures (the phone's picker, Open camera, or the editor joining it).
     * @param by {@link Job#PHONE} or {@link Job#EDITOR}
     */
    public Target openSession(String id, String by) throws ApiException {
        Session s = sessions.get(id);
        if (s == null) throw new ApiException(404, "not_found", "No such session.");
        return setTarget(new Target(Target.SESSION, s.id, s.name, by, Job.EDITOR.equals(by)), by);
    }

    /**
     * A new unnamed set of shots for the editor (the editor's "start new"). Whatever session
     * was open is already saved, and stays on the phone.
     */
    public Target openShots(String by) throws ApiException {
        return setTarget(new Target(Target.SHOTS, null, null, by, Job.EDITOR.equals(by)), by);
    }

    /** No target: the phone's next Capture asks for a session. */
    public Target closeTarget(String by) throws ApiException {
        return setTarget(new Target(Target.NONE, null, null, null, false), by);
    }

    private Target setTarget(Target next, String by) throws ApiException {
        Job running = jobs.current();
        if (running != null) throw ApiException.busy(running);
        Target prev = target;
        target = next;
        Log.i(TAG, "target " + next.kind + " " + next.name + " by " + by);
        RemoteServer s = server;
        if (s != null) s.targetChanged(prev, next, by);
        fire();
        return next;
    }

    /**
     * The target for a job a side is about to start. The editor's captures need a target it
     * chose or joined: with none open, it gets a new set of shots; with a session the phone
     * opened, 409 session_open until it joins or starts a new set. The phone's need a target.
     */
    public Target targetFor(String source) throws ApiException {
        Target t = target;
        if (Job.EDITOR.equals(source)) {
            if (t.isNone()) return openShots(Job.EDITOR);
            if (t.isSession() && !t.editorJoined) {
                throw new ApiException(409, "session_open", "The phone has " + t.name
                        + " open. Join it, or start a new set, first.").with("sessionId", t.sessionId);
            }
            return t;
        }
        if (t.isNone()) throw new ApiException(409, "no_target", "Pick a session first.");
        return t;
    }

    // ---- The server ---------------------------------------------------------------------

    public RemoteServer server() {
        return server;
    }

    /** The app came to the foreground or went to the background (main thread). */
    public void setForeground(boolean on) {
        foreground = on;
        updateServer();
    }

    private void updateServer() {
        boolean want = foreground && RemoteServer.enabled(context);
        if (want && server == null) {
            RemoteServer s = new RemoteServer(this);
            server = s;
            s.start();
            Rig r = rig;
            if (r != null) s.attachRig(r);
        } else if (!want) {
            stopServer();
        }
        fire();
    }

    private void stopServer() {
        RemoteServer s = server;
        server = null;
        if (s != null) s.stop();
    }
}
