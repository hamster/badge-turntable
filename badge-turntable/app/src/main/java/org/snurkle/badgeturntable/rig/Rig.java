package org.snurkle.badgeturntable.rig;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import android.util.Range;

import androidx.preference.PreferenceManager;

import org.json.JSONArray;
import org.json.JSONObject;
import org.snurkle.badgeturntable.camera.CameraController;
import org.snurkle.badgeturntable.camera.CameraSettings;
import org.snurkle.badgeturntable.capture.CaptureSequencer;
import org.snurkle.badgeturntable.capture.CaptureTarget;
import org.snurkle.badgeturntable.capture.View;
import org.snurkle.badgeturntable.ir.IrCode;
import org.snurkle.badgeturntable.ir.IrTransmitter;
import org.snurkle.badgeturntable.ir.Presets;
import org.snurkle.badgeturntable.ir.TurntableProfile;
import org.snurkle.badgeturntable.ir.TurntableProfile.Role;
import org.snurkle.badgeturntable.jobs.ApiException;
import org.snurkle.badgeturntable.jobs.Job;
import org.snurkle.badgeturntable.jobs.JobRunner;
import org.snurkle.badgeturntable.jobs.Json;
import org.snurkle.badgeturntable.motion.Calibration;
import org.snurkle.badgeturntable.motion.Calibrator;
import org.snurkle.badgeturntable.motion.MoveFailedException;
import org.snurkle.badgeturntable.motion.TurntableController;
import org.snurkle.badgeturntable.remote.RemoteShots;
import org.snurkle.badgeturntable.ring.RingConfig;
import org.snurkle.badgeturntable.ring.RingDetector;
import org.snurkle.badgeturntable.ring.RingReading;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.LongConsumer;

/**
 * Everything at the light box: camera, encoder ring, IR turntable, capture sequencer, the
 * job runner and the shots held for the editor. The Capture screen owns one, and both its
 * buttons and the remote API start jobs through it, so the same prechecks and one-job-at-a-
 * time rule apply to both.
 */
public final class Rig {
    private static final String TAG = "Rig";
    public static final long MIN_FREE_BYTES = 500L * 1024 * 1024;
    public static final double TOLERANCE_DEG = 2.0;

    public final Context context;
    public final SharedPreferences prefs;
    public final CameraController camera;
    public final RingDetector ring;
    public final IrTransmitter ir;
    public final TurntableProfile profile;
    /** Null when the phone has no IR blaster or the profile has no remote. */
    public final TurntableController table;
    public final CaptureSequencer sequencer;
    public final JobRunner jobs = new JobRunner();
    public final RemoteShots shots;
    private final List<LongConsumer> countdownListeners = new CopyOnWriteArrayList<>();
    private float lastZoom;
    private String lastLens;

    public Rig(Context context) {
        this.context = context.getApplicationContext();
        prefs = PreferenceManager.getDefaultSharedPreferences(this.context);
        ir = new IrTransmitter(this.context);
        profile = Presets.byId(this.context, prefs.getString("turntable_model", Presets.JAYEGT_574));
        int markers = parseInt(prefs.getString("ring_markers", "45"), 45);  // the JAYEGT ring: 45 markers at 8°
        ring = new RingDetector(new RingConfig(markers, false));
        Calibration cal = Calibration.load(this.context);
        table = profile.supportsAuto() && ir.available()
                ? new TurntableController(ir, profile, ring::latest, cal == null ? new Calibration() : cal)
                : null;
        camera = new CameraController(this.context);
        sequencer = new CaptureSequencer(this.context, camera, ring, table);
        shots = new RemoteShots(this.context.getFilesDir());

        CameraSettings s = camera.settings();
        lastZoom = s.zoom;
        lastLens = s.lens;
        camera.addSettingsListener(next -> {
            // The ring's learnt image geometry only holds for one lens and zoom.
            if (next.zoom != lastZoom || !Objects.equals(next.lens, lastLens)) ring.resetGeometry();
            lastZoom = next.zoom;
            lastLens = next.lens;
        });
        jobs.addListener(job -> {
            if (job.finished()) shots.recordJob(job);
        });
    }

    public void addCountdownListener(LongConsumer l) {
        countdownListeners.add(l);
    }

    public void removeCountdownListener(LongConsumer l) {
        countdownListeners.remove(l);
    }

    public void shutdown() {
        jobs.shutdown();
        camera.shutdown();
    }

    // ---- Calibration -------------------------------------------------------------

    /** {"valid" | "stale" | "missing", reason or null}. */
    public String[] calibrationState() {
        Calibration c = Calibration.load(context);
        if (c == null) return new String[]{"missing", null};
        if (!c.profileFingerprint.equals(profile.fingerprint())) return new String[]{"stale", "The turntable profile changed."};
        if (c.markerCount != ring.config().markerCount) return new String[]{"stale", "The ring's marker count changed."};
        return new String[]{"valid", null};
    }

    public boolean calibrated() {
        return "valid".equals(calibrationState()[0]);
    }

    /** The table angle from the calibrated zero, going forward; NaN if not calibrated or no reading. */
    public double tableDeg(double ringDeg) {
        if (Double.isNaN(ringDeg) || !calibrated()) return Double.NaN;
        Calibration c = Calibration.load(context);
        return c == null ? Double.NaN : c.forward(c.zeroAngleDeg, ringDeg);
    }

    public JSONObject calibrationJson() {
        String[] st = calibrationState();
        Calibration c = Calibration.load(context);
        boolean has = c != null;
        return Json.obj("state", st[0], "reason", st[1],
                "calibratedAt", has ? Json.time(c.calibratedAtMs) : null,
                "degPerSec", has ? Json.deg(c.degPerSec) : null,
                "stopOvershootDeg", has ? Json.deg(c.stopOvershootDeg) : null,
                "startLatencyMs", has ? c.startLatencyMs : null,
                "step90Deg", has ? Json.deg(c.step90Deg) : null,
                "directionSign", has ? c.directionSign : null,
                "markerCount", has ? c.markerCount : null);
    }

    // ---- The front -----------------------------------------------------------------

    /**
     * The ring angle where the badge faces the camera, set with Set front, or NaN. Unset, the
     * front is wherever the table is when Capture is pressed.
     */
    public double frontRingDeg() {
        return prefs.getFloat("front_ring_deg", Float.NaN);
    }

    /**
     * Makes the table's current position the front. With a calibration it's also the zero the
     * table homes to after each capture, and the table's own origin (Set Origin) is set there,
     * so the next badge is put on facing the camera.
     * @return the ring angle
     */
    public double setFront() throws ApiException {
        Job running = jobs.current();
        if (running != null) throw ApiException.busy(running);
        RingReading r = ring.latest();
        if (!r.ok()) throw new ApiException(409, "ring_lost", "Can't see the encoder ring, so the front can't be set.");
        prefs.edit().putFloat("front_ring_deg", (float) r.angleDeg).apply();
        Calibration c = Calibration.load(context);
        if (c != null && table != null) {
            c.zeroAngleDeg = r.angleDeg;
            try {
                c.save(context);
            } catch (org.json.JSONException e) {
                throw new ApiException(500, "internal", e.getMessage());
            }
            table.setCalibration(c);
            IrCode origin = profile.code(Role.SET_ORIGIN);
            if (origin != null) ir.send(origin);
        }
        return r.angleDeg;
    }

    /** How far a ring angle is from the front, going forward, in (-180, 180]; NaN without a front. */
    public double fromFront(double ringDeg) {
        double front = frontRingDeg();
        if (Double.isNaN(front) || Double.isNaN(ringDeg)) return Double.NaN;
        Calibration c = Calibration.load(context);
        int sign = c != null ? c.directionSign : 1;
        return sign * RingReading.delta(front, ringDeg);
    }

    // ---- Turntable -----------------------------------------------------------------

    public boolean irAvailable() {
        return ir.available() && !profile.codes().isEmpty();
    }

    /** Roles with a code, in Role order; empty without IR. */
    public JSONArray rolesJson() {
        JSONArray a = new JSONArray();
        if (!irAvailable()) return a;
        for (Role r : Role.values()) if (profile.has(r)) a.put(r.name().toLowerCase(Locale.ROOT));
        return a;
    }

    /** Sends one button's code (a Test button). */
    public void press(String roleName) throws ApiException {
        Role role;
        try {
            role = Role.valueOf(roleName.toUpperCase(Locale.ROOT));
        } catch (RuntimeException e) {
            throw ApiException.badRequest("role", "Unknown role " + roleName + ".");
        }
        if (!ir.available()) throw ApiException.unsupported("role", "This phone has no IR blaster.");
        IrCode code = profile.code(role);
        if (code == null) throw ApiException.unsupported("role", "This turntable's remote has no " + roleName + " button.");
        Job running = jobs.current();
        if (running != null) throw ApiException.busy(running);
        ir.send(code);
    }

    /** A closed-loop move: {@code by} degrees forward, {@code to} a table angle, or home. */
    public Job startMove(Double by, Double to, boolean home) throws ApiException {
        if (table == null) throw ApiException.unsupported(null, "This phone can't drive the turntable (no IR blaster or no remote).");
        boolean valid = calibrated();
        if (!valid && !(home && profile.has(Role.RETURN_TO_ORIGIN))) throw ApiException.notCalibrated();
        if (by != null && by < 0) {
            throw ApiException.unsupported("by", "Moving backwards isn't supported yet. Move forward by 360 minus the angle instead.");
        }
        JSONObject params = home ? Json.obj("home", true) : to != null ? Json.obj("to", to) : Json.obj("by", by);
        return jobs.start(Job.Kind.MOVE, params, job -> {
            while (true) {
                try {
                    if (home) {
                        job.say("Homing…");
                        if (valid) {
                            table.home();
                        } else {
                            table.press(Role.RETURN_TO_ORIGIN);
                            Thread.sleep(1500);
                            table.waitForStable(30_000, 0.5, 300);
                        }
                    } else {
                        double degrees = by != null ? by : forwardTo(to);
                        job.say(by != null ? String.format(Locale.ROOT, "Moving %+.0f°…", by)
                                : String.format(Locale.ROOT, "Moving to %.0f°…", to));
                        if (degrees >= 0.5) table.moveBy(degrees);
                    }
                    break;
                } catch (MoveFailedException e) {
                    double target = to != null ? to : Double.NaN;
                    String answer = job.ask("move_failed", e.userMessage(), Arrays.asList("retry", "stop"),
                            Json.obj("view", null, "targetDeg", null, "targetTableDeg", Json.deg(target),
                                    "toleranceDeg", TOLERANCE_DEG, "reason", reason(e)));
                    if ("stop".equals(answer)) throw new InterruptedException("stopped");
                }
            }
            RingReading r = ring.latest();
            return Json.obj("tableDeg", Json.deg(tableDeg(r.angleDeg)), "ringDeg", r.ok() ? Json.deg(r.angleDeg) : null);
        });
    }

    private double forwardTo(double tableTarget) throws MoveFailedException, InterruptedException {
        double now = table.waitForStable(3000, 0.5, 300);
        if (Double.isNaN(now)) throw new MoveFailedException(MoveFailedException.Reason.RING_LOST, "ring not readable");
        double d = (tableTarget - tableDeg(now)) % 360;
        return d < 0 ? d + 360 : d;
    }

    static String reason(MoveFailedException e) {
        switch (e.reason) {
            case NO_MOVEMENT: return "no_movement";
            case RING_LOST: return "ring_lost";
            default: return "missed_target";
        }
    }

    public Job startCalibrate() throws ApiException {
        if (table == null) throw ApiException.unsupported(null, "This phone can't drive the turntable (no IR blaster or no remote).");
        return jobs.start(Job.Kind.CALIBRATE, new JSONObject(), job -> {
            String[] last = {null};
            Calibration cal;
            try {
                double front = frontRingDeg();
                cal = new Calibrator(table, ring::latest, text -> {
                    last[0] = text;
                    job.say(text);
                }).run(ring.config().markerCount, Double.isNaN(front) ? 0 : front);
            } catch (MoveFailedException e) {
                throw new ApiException(500, "calibration_failed", e.userMessage());
            }
            cal.save(context);
            table.setCalibration(cal);
            job.setDoneText(last[0]);
            return calibrationJson();
        });
    }

    // ---- Captures ------------------------------------------------------------------

    /** The capture options from Settings. */
    public CaptureSequencer.Options options(boolean auto, boolean stills, boolean video, String setLabel) {
        CaptureSequencer.Options o = new CaptureSequencer.Options();
        o.auto = auto;
        o.stills = stills;
        o.video = video;
        o.setLabel = setLabel == null || setLabel.trim().isEmpty() ? null : setLabel.trim();
        o.manualDelayMs = parseInt(prefs.getString("manual_delay_ms", "2000"), 2000);
        o.videoOverlapDeg = parseInt(prefs.getString("video_overlap_deg", "15"), 15);
        o.spin = "spin".equals(prefs.getString("capture_method", "spin"));
        o.photoSecondsPerRev = parseInt(prefs.getString("spin_photo_spr", "30"), 30);
        o.videoSecondsPerRev = parseInt(prefs.getString("spin_video_spr", "15"), 15);
        o.frontRingDeg = frontRingDeg();
        return o;
    }

    public long freeBytes() {
        File dir = context.getExternalFilesDir(null);
        return Math.max(dir == null ? 0 : dir.getUsableSpace(), context.getFilesDir().getUsableSpace());
    }

    private void precheckCamera(boolean needsCalibration) throws ApiException {
        Job running = jobs.current();
        if (running != null) throw ApiException.busy(running);
        if (needsCalibration && !calibrated()) throw ApiException.notCalibrated();
        long free = freeBytes();
        if (free < MIN_FREE_BYTES) {
            throw new ApiException(507, "storage_low", "Less than 500 MB free. Make some space before capturing.")
                    .with("freeBytes", free);
        }
        if (!camera.isOpen()) {
            throw new ApiException(503, "camera_unavailable", "The camera isn't open yet.");
        }
    }

    /**
     * A turntable set (or an extra set, with setLabel): the four views, the spin, or both.
     * @param target where the files go; null for the shots held for the editor
     */
    public Job startCapture(CaptureTarget target, boolean auto, boolean stills, boolean video, String setLabel)
            throws ApiException {
        if (!stills && !video) throw ApiException.badRequest("stills", "Ask for the stills, the spin or both.");
        if (auto && table == null) {
            throw ApiException.unsupported("auto", "This phone can't drive the turntable (no IR blaster or no remote). Use manual mode.");
        }
        precheckCamera(auto);
        CaptureSequencer.Options o = options(auto, stills, video, setLabel);
        JSONObject params = Json.obj("stills", stills, "video", video, "auto", auto, "setLabel", o.setLabel);
        return jobs.start(Job.Kind.CAPTURE, params, job -> {
            job.setCamera(cameraSettingsJson());
            CaptureTarget t = target != null ? target : shots.target(job);
            boolean complete = sequencer.capture(t, o, new JobListener(job));
            if (complete) job.setDoneText(target != null ? "Done. Saved to the badge folder." : "Done. The shots are ready for the editor.");
            else job.setDoneText("Stopped. The views taken so far are kept.");
            return Json.obj("complete", complete, "shotIds", Json.arr(job.shotIds()));
        });
    }

    /** One extra still. @param target null for the shots held for the editor */
    public Job startExtra(CaptureTarget target, String label, long delayMs) throws ApiException {
        precheckCamera(false);
        JSONObject params = Json.obj("label", label, "delayMs", delayMs);
        return jobs.start(Job.Kind.EXTRA, params, job -> {
            job.setCamera(cameraSettingsJson());
            CaptureTarget t = target != null ? target : shots.target(job);
            sequencer.captureExtra(t, label, delayMs, new JobListener(job));
            job.setDoneText(target != null ? "Done. Saved to the badge folder." : "Done. The shot is ready for the editor.");
            return Json.obj("shotIds", Json.arr(job.shotIds()));
        });
    }

    /** The sequencer's questions become job prompts, answered from the phone or the editor. */
    private final class JobListener implements CaptureSequencer.Listener {
        private final Job job;

        JobListener(Job job) {
            this.job = job;
        }

        @Override
        public void onStatus(String text) {
            job.say(text);
        }

        @Override
        public void onStep(int step, int of, String text) {
            job.step(step, of, text);
        }

        @Override
        public CaptureSequencer.Decision onMoveFailed(String viewName, View view, double targetRing,
                                                      boolean manualOffered, MoveFailedException e)
                throws InterruptedException {
            List<String> answers = manualOffered ? Arrays.asList("retry", "manual", "stop") : Arrays.asList("retry", "stop");
            String a = job.ask("move_failed", e.userMessage(), answers, Json.obj(
                    "view", viewName, "targetDeg", view.angle, "targetTableDeg", Json.deg(tableDeg(targetRing)),
                    "toleranceDeg", TOLERANCE_DEG, "reason", reason(e)));
            return "retry".equals(a) ? CaptureSequencer.Decision.RETRY
                    : "manual".equals(a) ? CaptureSequencer.Decision.MANUAL : CaptureSequencer.Decision.STOP;
        }

        @Override
        public boolean awaitManualPosition(String viewName, View view, String instruction, double targetRing)
                throws InterruptedException {
            String a = job.ask("position", instruction, Arrays.asList("ready", "stop"), Json.obj(
                    "view", view == null ? "spin" : viewName, "targetDeg", view == null ? null : view.angle,
                    "targetTableDeg", Json.deg(tableDeg(targetRing)), "toleranceDeg", TOLERANCE_DEG));
            return "ready".equals(a);
        }

        @Override
        public void onCountdown(long remainingMs) {
            for (LongConsumer l : countdownListeners) l.accept(remainingMs);
        }
    }

    // ---- Camera as the API shows it ------------------------------------------------

    public JSONObject cameraSettingsJson() {
        CameraSettings s = camera.settings();
        String lens = s.lens != null ? s.lens : camera.boundLensId();
        boolean manual = s.manualExposure();
        long autoNs = camera.autoExposureNs();
        int autoIso = camera.autoIso();
        float[] point = camera.focusPoint();
        return Json.obj("lens", lens, "zoom", (double) s.zoom, "exposureMode", s.exposureMode, "ev", s.ev,
                "iso", !manual && autoIso > 0 ? autoIso : s.iso,
                "shutterNs", !manual && autoNs > 0 ? autoNs : s.shutterNs,
                "focusMode", s.focusMode, "focusDiopters", (double) s.focusDiopters,
                "whiteBalance", s.whiteBalance, "kelvin", s.kelvin,
                "aeLock", s.aeLock, "awbLock", s.awbLock, "afLock", s.afLock,
                "focusPoint", point == null ? null
                        : Json.obj("x", Math.round(point[0] * 1000) / 1000.0, "y", Math.round(point[1] * 1000) / 1000.0));
    }

    public JSONObject cameraCapabilitiesJson() {
        CameraController.Capabilities c = camera.capabilities();
        JSONArray lenses = new JSONArray();
        for (CameraController.Lens l : c.lenses) {
            lenses.put(Json.obj("id", l.id, "label", l.label, "focalLengthMm", Json.deg(l.focalLengthMm),
                    "minFocusCm", Json.deg(l.minFocusCm)));
        }
        return Json.obj("lenses", lenses,
                "zoom", Json.obj("min", (double) c.zoomMin, "max", (double) c.zoomMax),
                "ev", Json.obj("min", c.evMin, "max", c.evMax, "stepEv", c.evStep),
                "iso", range(c.iso),
                "shutterNs", range(c.shutterNs),
                "focusDiopters", c.maxFocusDiopters == null ? null : Json.obj("min", 0.0, "max", (double) c.maxFocusDiopters),
                "whiteBalance", Json.arr(c.whiteBalance),
                "kelvin", null);
    }

    private static JSONObject range(Range<? extends Number> r) {
        return r == null ? null : Json.obj("min", r.getLower(), "max", r.getUpper());
    }

    public static int parseInt(String s, int fallback) {
        try {
            return Integer.parseInt(s.trim());
        } catch (RuntimeException e) {
            Log.w(TAG, "bad number " + s);
            return fallback;
        }
    }
}
