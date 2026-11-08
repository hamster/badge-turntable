package org.snurkle.badgeturntable.remote;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.camera.core.ImageProxy;
import androidx.preference.PreferenceManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.snurkle.badgeturntable.camera.CameraSettings;
import org.snurkle.badgeturntable.jobs.Job;
import org.snurkle.badgeturntable.jobs.JobRunner;
import org.snurkle.badgeturntable.jobs.Json;
import org.snurkle.badgeturntable.rig.Rig;
import org.snurkle.badgeturntable.rig.Station;
import org.snurkle.badgeturntable.session.Session;
import org.snurkle.badgeturntable.session.SessionStore;
import org.snurkle.badgeturntable.ring.RingDetector;
import org.snurkle.badgeturntable.ring.RingReading;

import java.io.File;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongConsumer;

/**
 * Remote control from the web editor (Spec.md, Remote control; wire format in
 * Remote-API.md): the HTTP API, the /api/live WebSocket with status, ring readings, job,
 * shot, session and target events and preview frames, pairing, and the mDNS name. The
 * {@link Station} runs it while the app is in the foreground and Settings → Remote control
 * is on. Ring readings, preview frames and camera events flow while the Capture screen's
 * {@link Rig} is attached.
 */
public final class RemoteServer {
    private static final String TAG = "RemoteServer";
    public static final int API_VERSION = 1;
    public static final String DEFAULT_NAME = "badge-turntable";
    public static final int DEFAULT_PORT = 8737;
    private static final int MAX_CLIENTS = 4;
    private static final long AUTH_TIMEOUT_MS = 5000;
    private static final long RING_EVERY_MS = 200;

    /** One editor tab's WebSocket. */
    static final class Client {
        final WebSocket ws;
        final long openedAtMs = System.currentTimeMillis();
        volatile Pairing.Client paired;
        volatile boolean previewEnabled = true;
        volatile int fps = 8, width = 640;
        long nextFrameAtMs;

        Client(WebSocket ws) {
            this.ws = ws;
        }
    }

    final Context context;
    final Station station;
    /** The Capture screen's rig while it's attached. */
    private volatile Rig rig;
    final SharedPreferences prefs;
    final Pairing pairing;
    final String deviceId;
    final String name;
    final int port;
    final List<Client> clients = new CopyOnWriteArrayList<>();
    private final PreviewEncoder encoder = new PreviewEncoder();
    private ScheduledExecutorService timer;
    private HttpServer http;
    /** The WebRTC transport, or null if it isn't running (Info and Pair then say webrtc: null). */
    private volatile WebRtcTransport webrtc;
    private MdnsResponder mdns;
    private WifiManager.MulticastLock multicastLock;
    private long lastRingSentMs;
    private JSONObject lastAxis;
    private volatile String error;

    private final JobRunner.Listener jobListener = this::onJobChanged;
    private final RemoteShots.Listener shotListener = new RemoteShots.Listener() {
        @Override
        public void onShot(JSONObject shot) {
            broadcast(Json.obj("type", "shot", "shot", shot));
        }

        @Override
        public void onShotRemoved(String id) {
            broadcast(Json.obj("type", "shot_removed", "id", id));
        }
    };
    private final SessionStore.Listener sessionListener = new SessionStore.Listener() {
        @Override
        public void onSessionChanged(Session s) {
            broadcastLater(() -> broadcast(Json.obj("type", "session", "session", sessionSummary(s))));
        }

        @Override
        public void onSessionRemoved(String id) {
            broadcast(Json.obj("type", "session_removed", "id", id));
        }
    };
    private final Consumer<CameraSettings> cameraListener = this::onCameraChanged;
    private final LongConsumer countdownListener = ms -> broadcast(Json.obj("type", "countdown", "remainingMs", ms));
    private final RingDetector.Listener ringListener = this::onRing;
    private final RingDetector.FrameTap frameTap = this::onFrame;

    public RemoteServer(Station station) {
        this.context = station.context;
        this.station = station;
        this.prefs = PreferenceManager.getDefaultSharedPreferences(this.context);
        this.pairing = new Pairing(new PrefsStore(prefs), SystemClock::elapsedRealtime);
        this.deviceId = deviceId(prefs);
        this.name = hostName(prefs.getString("remote_name", DEFAULT_NAME));
        this.port = parsePort(prefs.getString("remote_port", String.valueOf(DEFAULT_PORT)));
    }

    public static boolean enabled(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context).getBoolean("remote_enabled", false);
    }

    public void start() {
        try {
            http = new HttpServer(port, new RemoteApi(this), RemoteServer::lanOrLoopback);
            http.start();
        } catch (IOException e) {
            error = "Can't listen on port " + port + ": " + e.getMessage();
            Log.e(TAG, error, e);
            http = null;
            notifyUi();
            return;
        }
        error = null;
        // After the HTTP server, which the bridge relays to over loopback.
        webrtc = WebRtcTransport.start(addresses(), port, new File(context.getFilesDir(), "webrtc-cert.pem"));
        pairing.setCodeListener(code -> {
            broadcast(Json.obj("type", "notice", "level", "info", "text", "The pairing code on the phone changed."));
            notifyUi();
        });
        station.jobs.addListener(jobListener);
        station.shots.addListener(shotListener);
        station.sessions.addListener(sessionListener);
        timer = Executors.newSingleThreadScheduledExecutor();
        timer.scheduleWithFixedDelay(this::tick, 500, 500, TimeUnit.MILLISECONDS);
        startMdns();
        Log.i(TAG, "listening on " + addresses() + ":" + port + " as " + name + ".local");
        notifyUi();
    }

    public void stop() {
        if (timer != null) timer.shutdownNow();
        timer = null;
        station.jobs.removeListener(jobListener);
        station.shots.removeListener(shotListener);
        station.sessions.removeListener(sessionListener);
        Rig r = rig;
        if (r != null) detachRig(r);
        pairing.setCodeListener(null);
        for (Client c : clients) c.ws.close(1001, "The phone stopped remote control");
        // After the WebSockets: the bridge relays their 1001, and sends it itself on any live
        // channel it hasn't reached yet, before closing the connections.
        WebRtcTransport w = webrtc;
        webrtc = null;
        if (w != null) w.stop();
        if (mdns != null) mdns.stop();
        mdns = null;
        if (multicastLock != null && multicastLock.isHeld()) multicastLock.release();
        multicastLock = null;
        if (http != null) http.stop(300);  // a moment for the close frames to go out
        http = null;
        clients.clear();
    }

    /** The Capture screen opened: stream its camera, ring and preview. Main thread. */
    public void attachRig(Rig r) {
        Rig old = rig;
        if (old == r) return;
        if (old != null) detachRig(old);
        rig = r;
        r.camera.addSettingsListener(cameraListener);
        r.addCountdownListener(countdownListener);
        r.ring.addListener(ringListener);
        r.ring.setFrameTap(frameTap);
        broadcastLater(() -> broadcast(statusMessage()));
    }

    /** The Capture screen closed. Main thread. */
    public void detachRig(Rig r) {
        if (rig != r) return;
        r.camera.removeSettingsListener(cameraListener);
        r.removeCountdownListener(countdownListener);
        r.ring.removeListener(ringListener);
        r.ring.setFrameTap(null);
        rig = null;
        lastAxis = null;
        broadcastLater(() -> broadcast(statusMessage()));
    }

    /** The capture target changed: tell every tab, with who changed it. */
    public void targetChanged(Station.Target prev, Station.Target next, String by) {
        broadcastLater(() -> {
            broadcast(Json.obj("type", "target", "target", next.toJson(), "by", by,
                    "previous", prev.toJson()));
            broadcast(statusMessage());
        });
    }

    // ---- For the screens -------------------------------------------------------

    public boolean running() {
        return http != null;
    }

    /** Why the server isn't running, or null. */
    public String error() {
        return error;
    }

    public String name() {
        return name;
    }

    public int port() {
        return port;
    }

    public String code() {
        return pairing.code();
    }

    /** Names of the editor tabs connected now. */
    public List<String> clientNames() {
        List<String> out = new ArrayList<>();
        for (Client c : clients) if (c.paired != null && !out.contains(c.paired.clientName)) out.add(c.paired.clientName);
        return out;
    }

    private void notifyUi() {
        station.fire();
    }

    // ---- State the API reports ---------------------------------------------------

    JSONObject info(Boolean paired) {
        return Json.obj("api", API_VERSION, "app", "Badge Turntable", "appVersion", appVersion(),
                "deviceId", deviceId, "name", name, "model", model(), "port", port,
                "addresses", Json.arr(addresses()), "paired", paired, "busy", station.jobs.current() != null,
                "webrtc", webrtcJson());
    }

    /** {@code webrtc} in Info and Pair: the transport's UDP port and fingerprint, or null. */
    Object webrtcJson() {
        WebRtcTransport w = webrtc;
        return w == null ? null : w.toJson();
    }

    JSONObject status() {
        JSONArray cl = new JSONArray();
        for (Client c : clients) {
            if (c.paired != null) cl.put(Json.obj("clientName", c.paired.clientName, "since", Json.time(c.openedAtMs)));
        }
        Job job = station.jobs.current();
        Rig open = station.cameraOpen() ? station.rig() : null;
        Rig info = station.rigOrIdle();
        long[] sessions = station.sessions.totals();
        return Json.obj(
                "clients", cl,
                "cameraOpen", open != null,
                "target", station.target().toJson(),
                "sessions", Json.obj("count", sessions[0], "bytes", sessions[1]),
                "turntable", Json.obj("profileId", info.profile.id, "profileName", info.profile.name,
                        "irAvailable", info.irAvailable(), "roles", info.rolesJson(),
                        "frontRingDeg", Json.deg(info.frontRingDeg())),
                "calibration", info.calibrationJson(),
                "ring", ringJson(open == null ? null : open.ring.latest()),
                "camera", open == null ? null : open.cameraSettingsJson(),
                "job", job == null ? null : job.toJson(),
                "shots", Json.obj("count", station.shots.count(), "bytes", station.shots.bytes()),
                "storage", Json.obj("freeBytes", info.freeBytes()));
    }

    /** @param r null when the Capture screen is closed */
    JSONObject ringJson(RingReading r) {
        if (r == null) {
            return Json.obj("ok", false, "ringDeg", null, "tableDeg", null, "frontDeg", null,
                    "markers", 0, "rmsPx", null, "frameUs", null);
        }
        Rig rig = station.rigOrIdle();
        boolean ok = r.ok();
        return Json.obj("ok", ok, "ringDeg", ok ? Json.deg(r.angleDeg) : null,
                "tableDeg", ok ? Json.deg(rig.tableDeg(r.angleDeg)) : null,
                "frontDeg", ok ? Json.deg(rig.fromFront(r.angleDeg)) : null,
                "markers", r.markers, "rmsPx", ok ? Json.deg(r.rmsPx) : null, "frameUs", r.timestampNs / 1000);
    }

    JSONObject sessionJson() {
        List<JSONObject> jobs = new ArrayList<>(station.shots.jobRecords());
        Job current = station.jobs.current();
        if (current != null && !current.shotIds().isEmpty()) jobs.add(current.recordJson());
        JSONArray shots = new JSONArray();
        for (JSONObject s : station.shots.list()) shots.put(s);
        Rig rig = station.rigOrIdle();
        String[] cal = rig.calibrationState();
        return Json.obj("api", API_VERSION,
                "device", Json.obj("deviceId", deviceId, "model", model(), "appVersion", appVersion()),
                "turntable", Json.obj("profileId", rig.profile.id, "markerCount", rig.ring.config().markerCount),
                "calibration", "missing".equals(cal[0]) ? null : rig.calibrationJson(),
                "jobs", Json.arr(jobs),
                "shots", shots);
    }

    // ---- Sessions as the API shows them -------------------------------------------

    JSONObject sessionSummary(Session s) {
        Station.Target t = station.target();
        Job job = station.jobs.current();
        return Json.obj("id", s.id, "name", s.name,
                "createdAt", Json.time(s.createdAtMs), "updatedAt", Json.time(s.updatedAtMs),
                "open", t.isSession() && s.id.equals(t.sessionId),
                "busy", job != null && s.id.equals(job.targetSessionId()),
                "hasNotes", !s.notes.trim().isEmpty(),
                "mediaCount", s.media.size(), "bytes", station.sessions.bytes(s), "cover", s.cover());
    }

    JSONObject sessionDetail(Session s) {
        JSONObject o = sessionSummary(s);
        Json.put(o, "notes", s.notes);
        JSONArray media = new JSONArray();
        for (Session.Media m : SessionMedia.ordered(s)) media.put(SessionMedia.toJson(station.sessions, s, m));
        Json.put(o, "media", media);
        return o;
    }

    // ---- WebSocket ---------------------------------------------------------------

    WebSocket.Listener webSocketListener() {
        return new WebSocket.Listener() {
            @Override
            public void onOpen(WebSocket ws) {
                Client c = new Client(ws);
                ws.attachment = c;
                clients.add(c);
                ScheduledExecutorService t = timer;
                if (t != null) t.schedule(() -> {
                    if (c.paired == null) ws.close(4408, "No auth message");
                }, AUTH_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            }

            @Override
            public void onText(WebSocket ws, String text) {
                Client c = (Client) ws.attachment;
                JSONObject msg;
                try {
                    msg = new JSONObject(text);
                } catch (JSONException e) {
                    return;  // unknown messages are ignored
                }
                String type = msg.optString("type");
                if (c.paired == null) {
                    Pairing.Client who = "auth".equals(type) ? pairing.check(msg.optString("token")) : null;
                    if (who == null) {
                        ws.close(4401, "Not paired");
                        return;
                    }
                    if (authedClients() >= MAX_CLIENTS) {
                        ws.close(4409, "Too many clients");
                        return;
                    }
                    c.paired = who;
                    ws.sendText(Json.obj("type", "hello", "api", API_VERSION, "deviceId", deviceId, "name", name).toString());
                    ws.sendText(statusMessage().toString());
                    notifyUi();
                    return;
                }
                if ("preview".equals(type)) {
                    if (msg.has("enabled")) c.previewEnabled = msg.optBoolean("enabled", true);
                    if (msg.has("fps")) c.fps = (int) Math.max(1, Math.min(15, msg.optDouble("fps", 8)));
                    if (msg.has("width")) c.width = Math.max(160, Math.min(1280, msg.optInt("width", 640)));
                }
            }

            @Override
            public void onClose(WebSocket ws) {
                Client c = (Client) ws.attachment;
                if (c != null) clients.remove(c);
                notifyUi();
            }
        };
    }

    private int authedClients() {
        int n = 0;
        for (Client c : clients) if (c.paired != null) n++;
        return n;
    }

    /** Drops sockets whose pairing was revoked (DELETE /api/pair). */
    void closeRevoked() {
        for (Client c : clients) {
            if (c.paired != null && !stillPaired(c.paired)) c.ws.close(4401, "Pairing revoked");
        }
    }

    private boolean stillPaired(Pairing.Client p) {
        for (Pairing.Client c : pairing.clients()) if (c.tokenHash.equals(p.tokenHash)) return true;
        return false;
    }

    private JSONObject statusMessage() {
        JSONObject s = status();
        Json.put(s, "type", "status");
        return s;
    }

    void broadcast(JSONObject msg) {
        String text = null;
        for (Client c : clients) {
            if (c.paired == null) continue;
            if (text == null) text = msg.toString();
            c.ws.sendText(text);
        }
    }

    private void broadcastLater(Runnable r) {
        ScheduledExecutorService t = timer;
        if (t != null) t.execute(r);
    }

    private void tick() {
        try {
            pairing.code();  // rotates the code when it's due
            if (authedClients() > 0) broadcast(statusMessage());
        } catch (RuntimeException e) {
            Log.w(TAG, "status tick failed", e);
        }
    }

    private void onCameraChanged(CameraSettings ignored) {
        broadcastLater(() -> {
            Rig r = rig;
            if (r != null) broadcast(Json.obj("type", "camera", "settings", r.cameraSettingsJson()));
        });
    }

    private void onJobChanged(Job job) {
        broadcast(Json.obj("type", "job", "job", job.toJson()));
        if (job.finished()) broadcastLater(() -> broadcast(statusMessage()));
    }

    /** Ring readings, at most 5 a second, with marker positions for the editor's overlay. */
    private void onRing(@NonNull RingReading r) {
        long now = SystemClock.uptimeMillis();
        if (now - lastRingSentMs < RING_EVERY_MS || authedClients() == 0 || rig == null) return;
        lastRingSentMs = now;
        JSONArray markers = new JSONArray();
        if (r.frameWidth > 0 && r.frameHeight > 0) {
            for (int i = 0; i < r.markerIds.length; i++) {
                markers.put(Json.obj("id", r.markerIds[i],
                        "x", round3(r.markerXY[2 * i] / r.frameWidth), "y", round3(r.markerXY[2 * i + 1] / r.frameHeight)));
            }
            if (r.ok()) {
                double x = round3(r.xAxis / r.frameWidth);
                lastAxis = Json.obj("x1", x, "y1", 0.0, "x2", x, "y2", 1.0);
            }
        }
        JSONObject msg = ringJson(r);
        Json.put(msg, "type", "ring");
        Json.put(msg, "markers", r.ok() ? markers : new JSONArray());
        Json.put(msg, "axis", lastAxis);
        broadcast(msg);
    }

    private static double round3(double v) {
        return Math.round(v * 1000) / 1000.0;
    }

    /** Preview frames for each tab that wants one now and has sent its last one. Analysis thread. */
    private void onFrame(@NonNull ImageProxy image, @NonNull RingReading r) {
        long now = SystemClock.uptimeMillis();
        Map<Integer, byte[]> encoded = null;
        for (Client c : clients) {
            if (c.paired == null || !c.previewEnabled || now < c.nextFrameAtMs || c.ws.binaryPending()) continue;
            c.nextFrameAtMs = now + 1000 / Math.max(1, c.fps);
            if (encoded == null) encoded = new HashMap<>();
            byte[] frame = encoded.get(c.width);
            if (frame == null) {
                byte[] jpeg = encoder.encode(image, c.width);
                if (jpeg == null) return;
                ByteBuffer b = ByteBuffer.allocate(16 + jpeg.length);
                b.put(new byte[]{'B', 'T', 'P', 'V', 1, 0, 0, 0});  // magic, version, rotation 0 (already upright)
                b.putLong(image.getImageInfo().getTimestamp() / 1000);
                b.put(jpeg);
                frame = b.array();
                encoded.put(c.width, frame);
            }
            c.ws.offerBinary(frame);
        }
    }

    // ---- Setup -------------------------------------------------------------------

    private void startMdns() {
        Inet4Address wifi = null;
        for (InetAddress a : interfaceAddresses()) {
            if (a instanceof Inet4Address && a.isSiteLocalAddress()) {
                wifi = (Inet4Address) a;
                break;
            }
        }
        if (wifi == null) {
            Log.w(TAG, "no LAN address, so no mDNS name");
            return;
        }
        try {
            WifiManager wm = context.getSystemService(WifiManager.class);
            if (wm != null) {
                multicastLock = wm.createMulticastLock("badge-turntable-mdns");
                multicastLock.setReferenceCounted(false);
                multicastLock.acquire();
            }
            mdns = new MdnsResponder(name, wifi, port,
                    Arrays.asList("api=" + API_VERSION, "id=" + deviceId, "path=/api/"));
            mdns.start();
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "mDNS failed; the phone can still be added by address", e);
            mdns = null;
        }
    }

    /** The phone's IPv4 addresses on the LAN (Wi-Fi), for the editor and the Capture screen. */
    public static List<String> addresses() {
        List<String> out = new ArrayList<>();
        for (InetAddress a : interfaceAddresses()) {
            if (a instanceof Inet4Address && a.isSiteLocalAddress()) out.add(a.getHostAddress());
        }
        return out;
    }

    private static List<InetAddress> interfaceAddresses() {
        List<InetAddress> out = new ArrayList<>();
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp() || nif.isLoopback()) continue;
                out.addAll(Collections.list(nif.getInetAddresses()));
            }
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "can't list addresses", e);
        }
        return out;
    }

    /** Accept connections on LAN and loopback addresses only, not on mobile data. */
    static boolean lanOrLoopback(InetAddress local) {
        return local.isLoopbackAddress() || local.isSiteLocalAddress() || local.isLinkLocalAddress();
    }

    private String appVersion() {
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    private static String model() {
        return Build.MANUFACTURER.substring(0, 1).toUpperCase(Locale.ROOT) + Build.MANUFACTURER.substring(1)
                + " " + Build.MODEL;
    }

    private static String deviceId(SharedPreferences prefs) {
        String id = prefs.getString("remote_device_id", null);
        if (id == null) {
            byte[] b = new byte[8];
            new SecureRandom().nextBytes(b);
            StringBuilder sb = new StringBuilder();
            for (byte x : b) sb.append(String.format(Locale.ROOT, "%02x", x));
            id = sb.toString();
            prefs.edit().putString("remote_device_id", id).apply();
        }
        return id;
    }

    /** A valid mDNS label: lower case letters, digits and hyphens. */
    static String hostName(String s) {
        String n = s == null ? "" : s.trim().toLowerCase(Locale.ROOT).replaceAll("\\.local$", "")
                .replaceAll("[^a-z0-9-]+", "-").replaceAll("^-+|-+$", "");
        return n.isEmpty() ? DEFAULT_NAME : n.length() > 63 ? n.substring(0, 63) : n;
    }

    private static int parsePort(String s) {
        try {
            int p = Integer.parseInt(s.trim());
            return p > 0 && p < 65536 ? p : DEFAULT_PORT;
        } catch (RuntimeException e) {
            return DEFAULT_PORT;
        }
    }

    // ---- Paired PCs, for Settings ----------------------------------------------------

    public static List<Pairing.Client> pairedClients(Context context) {
        return new PrefsStore(PreferenceManager.getDefaultSharedPreferences(context)).load();
    }

    public static void revoke(Context context, String tokenHash) {
        // The running server holds the paired list too: revoke there, and drop that PC's sockets.
        RemoteServer live = org.snurkle.badgeturntable.App.station(context).server();
        if (live != null) {
            live.pairing.revoke(tokenHash);
            live.closeRevoked();
            return;
        }
        PrefsStore store = new PrefsStore(PreferenceManager.getDefaultSharedPreferences(context));
        List<Pairing.Client> list = store.load();
        for (Iterator<Pairing.Client> it = list.iterator(); it.hasNext(); ) {
            if (it.next().tokenHash.equals(tokenHash)) it.remove();
        }
        store.save(list);
    }

    /** Paired PCs in the default preferences, as JSON: [{tokenHash, clientName, pairedAtMs}]. */
    static final class PrefsStore implements Pairing.Store {
        private final SharedPreferences prefs;

        PrefsStore(SharedPreferences prefs) {
            this.prefs = prefs;
        }

        @Override
        public List<Pairing.Client> load() {
            List<Pairing.Client> out = new ArrayList<>();
            try {
                JSONArray a = new JSONArray(prefs.getString("remote_clients", "[]"));
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.getJSONObject(i);
                    out.add(new Pairing.Client(o.getString("tokenHash"), o.optString("clientName"), o.optLong("pairedAtMs")));
                }
            } catch (JSONException e) {
                Log.w(TAG, "paired PCs unreadable; starting over", e);
            }
            return out;
        }

        @Override
        public void save(List<Pairing.Client> clients) {
            JSONArray a = new JSONArray();
            for (Pairing.Client c : clients) {
                a.put(Json.obj("tokenHash", c.tokenHash, "clientName", c.clientName, "pairedAtMs", c.pairedAtMs));
            }
            prefs.edit().putString("remote_clients", a.toString()).apply();
        }
    }
}
