package org.snurkle.badgeturntable.remote;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.snurkle.badgeturntable.camera.CameraController;
import org.snurkle.badgeturntable.camera.CameraSettings;
import org.snurkle.badgeturntable.jobs.ApiException;
import org.snurkle.badgeturntable.jobs.Job;
import org.snurkle.badgeturntable.jobs.Json;
import org.snurkle.badgeturntable.rig.Rig;
import org.snurkle.badgeturntable.rig.Station;
import org.snurkle.badgeturntable.session.Session;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The /api/ routes (Remote-API.md, Endpoints). One call per request, on the connection's thread. */
final class RemoteApi implements HttpServer.Handler {
    private static final String TAG = "RemoteApi";
    private static final Pattern SHOT = Pattern.compile("/api/shots/([^/]+)");
    private static final Pattern SESSION = Pattern.compile("/api/sessions/([^/]+)");
    private static final Pattern SESSION_FILE = Pattern.compile("/api/sessions/([^/]+)/media/([^/]+)");
    private static final List<String> MODES = Arrays.asList("auto", "manual");

    private final RemoteServer server;
    private final Station station;

    RemoteApi(RemoteServer server) {
        this.server = server;
        this.station = server.station;
    }

    @Override
    public HttpServer.Response handle(HttpServer.Request req) {
        // Any page may call: the pairing code and token are what keep others out, and an
        // allow-list of editor origins only got in the way (a local copy of the editor, say).
        String origin = req.header("origin");
        HttpServer.Response resp;
        if ("OPTIONS".equals(req.method)) {
            resp = HttpServer.Response.empty(204);
            if ("true".equalsIgnoreCase(req.header("access-control-request-private-network"))) {
                resp.header("Access-Control-Allow-Private-Network", "true");
            }
        } else {
            try {
                resp = route(req);
            } catch (ApiException e) {
                resp = json(e.status, e.body());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                resp = json(500, Json.obj("error", "internal", "message", "interrupted"));
            } catch (Exception e) {
                Log.e(TAG, req.method + " " + req.path + " failed", e);
                resp = json(500, Json.obj("error", "internal", "message", String.valueOf(e.getMessage())));
            }
        }
        if (resp.upgrade != null) return resp;
        if (origin != null) {
            resp.header("Access-Control-Allow-Origin", origin)
                    .header("Vary", "Origin")
                    .header("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS")
                    .header("Access-Control-Allow-Headers", "Authorization, Content-Type")
                    .header("Access-Control-Expose-Headers", "Content-Disposition, X-Shot-Filename")
                    .header("Access-Control-Max-Age", "600");
        }
        return resp.header("Cache-Control", "no-store");
    }

    private HttpServer.Response route(HttpServer.Request req) throws Exception {
        String path = req.path.length() > 1 && req.path.endsWith("/") ? req.path.substring(0, req.path.length() - 1) : req.path;
        String m = req.method;
        if ("HEAD".equals(m)) m = "GET";

        if (path.equals("/api/live")) {
            if (!req.isWebSocketUpgrade()) throw ApiException.badRequest(null, "WebSocket upgrade expected.");
            return HttpServer.Response.webSocket(server.webSocketListener());
        }
        if (req.bodyTooLarge) throw ApiException.badRequest(null, "Request body too large.");
        JSONObject body = "POST".equals(m) ? body(req) : new JSONObject();

        if (path.equals("/api/info") && m.equals("GET")) {
            String token = token(req);
            return ok(server.info(token == null ? null : server.pairing.check(token) != null));
        }
        if (path.equals("/api/pair")) {
            if (m.equals("POST")) {
                String token = server.pairing.pair(body.optString("code"), body.optString("clientName"));
                return ok(Json.obj("token", token, "deviceId", server.deviceId, "name", server.name));
            }
            if (m.equals("DELETE")) {
                server.pairing.forget(requireAuth(req));
                server.closeRevoked();
                return HttpServer.Response.empty(204);
            }
            throw methodNotAllowed(m);
        }
        if (!path.startsWith("/api/")) throw notFound("No such endpoint.");
        requireAuth(req);

        Matcher shot = SHOT.matcher(path);
        Matcher sessionFile = SESSION_FILE.matcher(path);
        Matcher session = SESSION.matcher(path);
        switch (path) {
            case "/api/status":
                if (m.equals("GET")) return ok(server.status());
                break;
            case "/api/camera":
                if (m.equals("GET")) {
                    Rig rig = station.requireRig();
                    return ok(Json.obj("capabilities", rig.cameraCapabilitiesJson(), "settings", rig.cameraSettingsJson()));
                }
                if (m.equals("POST")) return ok(setCamera(station.requireRig(), body));
                break;
            case "/api/camera/open":
                if (m.equals("POST")) {
                    station.openCamera();
                    return ok(Json.obj("cameraOpen", true));
                }
                break;
            case "/api/camera/focus":
                if (m.equals("POST")) return ok(focus(station.requireRig(), body));
                break;
            case "/api/turntable/press":
                if (m.equals("POST")) {
                    String role = body.optString("role", "");
                    station.rigOrIdle().press(role);
                    return ok(Json.obj("role", role));
                }
                break;
            case "/api/turntable/move":
                if (m.equals("POST")) return accepted(move(station.requireRig(), body));
                break;
            case "/api/turntable/front":
                if (m.equals("POST")) return ok(Json.obj("frontRingDeg", Json.deg(station.requireRig().setFront())));
                break;
            case "/api/calibrate":
                if (m.equals("POST")) return accepted(station.requireRig().startCalibrate(Job.EDITOR));
                break;
            case "/api/capture":
                if (m.equals("POST")) {
                    Rig rig = station.requireRig();
                    boolean stills = !Boolean.FALSE.equals(body.opt("stills"));
                    boolean video = !Boolean.FALSE.equals(body.opt("video"));
                    boolean auto = !Boolean.FALSE.equals(body.opt("auto"));
                    String setLabel = body.isNull("setLabel") ? null : body.optString("setLabel", "").trim();
                    return accepted(rig.startCapture(Job.EDITOR, auto, stills, video,
                            setLabel == null || setLabel.isEmpty() ? null : setLabel));
                }
                break;
            case "/api/extra":
                if (m.equals("POST")) return accepted(extra(station.requireRig(), body));
                break;
            case "/api/target":
                if (m.equals("GET")) return ok(Json.obj("target", station.target().toJson()));
                if (m.equals("POST")) return ok(Json.obj("target", setTarget(body).toJson()));
                break;
            case "/api/jobs/current":
                if (m.equals("GET")) {
                    Job j = station.jobs.currentOrLast();
                    return ok(Json.obj("job", j == null ? null : j.toJson()));
                }
                if (m.equals("DELETE")) return ok(Json.obj("job", station.jobs.abort(Job.EDITOR).toJson()));
                break;
            case "/api/jobs/current/answer":
                if (m.equals("POST")) {
                    Job j = station.jobs.answer(body.optString("promptId"), body.optString("answer"), Job.EDITOR);
                    return ok(Json.obj("job", j.toJson()));
                }
                break;
            case "/api/shots":
                if (m.equals("GET")) return ok(Json.obj("shots", Json.arr(station.shots.list())));
                if (m.equals("DELETE")) {
                    Job running = station.jobs.current();
                    if (running != null) throw ApiException.busy(running);
                    return ok(Json.obj("deleted", station.shots.deleteAll()));
                }
                break;
            case "/api/session.json":
                if (m.equals("GET")) return ok(server.sessionJson());
                break;
            case "/api/sessions":
                if (m.equals("GET")) {
                    JSONArray list = new JSONArray();
                    for (Session s : station.sessions.list()) list.put(server.sessionSummary(s));
                    return ok(Json.obj("sessions", list));
                }
                break;
            default:
                if (shot.matches()) return shotRoute(m, shot.group(1), req.query.get("variant"));
                if (sessionFile.matches()) {
                    return sessionFileRoute(m, sessionFile.group(1), decode(sessionFile.group(2)), req.query.get("variant"));
                }
                if (session.matches()) return sessionRoute(m, session.group(1));
                throw notFound("No such endpoint.");
        }
        throw methodNotAllowed(m);
    }

    // ---- Target and sessions ---------------------------------------------------------

    /** {"session": id} joins that session; {"shots": true} starts a new unnamed set. */
    private Station.Target setTarget(JSONObject body) throws ApiException {
        if (body.has("session") && !body.isNull("session")) return station.openSession(body.optString("session"), Job.EDITOR);
        if (Boolean.TRUE.equals(body.opt("shots"))) return station.openShots(Job.EDITOR);
        throw ApiException.badRequest("session", "Give session (an ID to join) or shots: true (a new set).");
    }

    private Session requireSession(String id) throws ApiException {
        Session s = station.sessions.get(id);
        if (s == null) throw notFound("No such session.");
        return s;
    }

    /** 409 busy if the running job writes into this session. */
    private void checkNotWriting(String sessionId) throws ApiException {
        Job running = station.jobs.current();
        if (running != null && sessionId.equals(running.targetSessionId())) throw ApiException.busy(running);
    }

    private HttpServer.Response sessionRoute(String method, String id) throws ApiException {
        Session s = requireSession(id);
        if (method.equals("GET")) return ok(server.sessionDetail(s));
        if (method.equals("DELETE")) {
            checkNotWriting(id);
            Station.Target t = station.target();
            if (t.isSession() && id.equals(t.sessionId)) {
                throw new ApiException(409, "session_open", s.name + " is open on the phone. Close it there first.")
                        .with("sessionId", id);
            }
            station.sessions.delete(s);
            return HttpServer.Response.empty(204);
        }
        throw methodNotAllowed(method);
    }

    private HttpServer.Response sessionFileRoute(String method, String id, String filename, String variantParam)
            throws ApiException, IOException {
        Session s = requireSession(id);
        if (s.find(filename) == null) throw notFound("That session has no such file.");
        if (method.equals("DELETE")) {
            checkNotWriting(id);
            if (!station.sessions.deleteFile(id, filename)) throw notFound("That session has no such file.");
            return HttpServer.Response.empty(204);
        }
        if (!method.equals("GET")) throw methodNotAllowed(method);
        String variant = variant(variantParam);
        File f;
        String mime, name;
        if (variant.equals("thumb")) {
            f = station.sessions.thumb(s, filename);
            mime = "image/jpeg";
            name = stem(filename) + "-thumb.jpg";
        } else {
            f = station.sessions.file(s, filename);
            mime = filename.endsWith(".mp4") ? "video/mp4" : "image/jpeg";
            name = filename;
        }
        if (f == null || !f.isFile()) throw notFound("That file is missing on the phone.");
        return HttpServer.Response.stream(200, mime, new FileInputStream(f), f.length())
                .header("Content-Disposition", "attachment; filename=\"" + name + "\"")
                .header("X-Shot-Filename", name);
    }

    /** "web" (default) or "thumb"; "original" is 404, since the phone keeps only web copies. */
    private static String variant(String param) throws ApiException {
        String v = param == null ? "web" : param;
        if (v.equals("original")) throw notFound("The phone keeps only the web copy.");
        if (!v.equals("web") && !v.equals("thumb")) throw ApiException.badRequest("variant", "variant is web or thumb.");
        return v;
    }

    private static String stem(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? filename : filename.substring(0, dot);
    }

    private static String decode(String s) throws ApiException {
        try {
            return java.net.URLDecoder.decode(s.replace("+", "%2B"), "UTF-8");
        } catch (Exception e) {
            throw ApiException.badRequest(null, "Bad file name.");
        }
    }

    // ---- Routes with more to them --------------------------------------------------

    private JSONObject setCamera(Rig rig, JSONObject body) throws Exception {
        Job running = rig.jobs.current();
        if (running != null && running.kind != Job.Kind.MOVE) {
            throw new ApiException(409, "busy", "The camera is locked while " + running.kindLabel().toLowerCase(Locale.ROOT)
                    + " runs, so all the shots match.").with("jobId", running.id);
        }
        CameraController.Capabilities caps = rig.camera.capabilities();
        // Check everything first: nothing is applied if any field is wrong.
        String lens = null;
        if (body.has("lens")) {
            lens = body.optString("lens");
            boolean known = false;
            for (CameraController.Lens l : caps.lenses) known |= l.id.equals(lens);
            if (!known) throw ApiException.unsupported("lens", "No such lens.");
        }
        Float zoom = body.has("zoom") ? (float) number(body, "zoom", caps.zoomMin, caps.zoomMax) : null;
        Integer ev = body.has("ev") ? (int) Math.round(number(body, "ev", caps.evMin, caps.evMax)) : null;
        Integer iso = null;
        Long shutter = null;
        Float diopters = null;
        if (body.has("iso")) {
            if (caps.iso == null) throw ApiException.unsupported("iso", "This lens has no manual ISO.");
            iso = (int) Math.round(number(body, "iso", caps.iso.getLower(), caps.iso.getUpper()));
        }
        if (body.has("shutterNs")) {
            if (caps.shutterNs == null) throw ApiException.unsupported("shutterNs", "This lens has no manual shutter.");
            shutter = Math.round(number(body, "shutterNs", caps.shutterNs.getLower(), caps.shutterNs.getUpper()));
        }
        if (body.has("focusDiopters")) {
            if (caps.maxFocusDiopters == null) throw ApiException.unsupported("focusDiopters", "This lens has no manual focus.");
            diopters = (float) number(body, "focusDiopters", 0, caps.maxFocusDiopters);
        }
        String exposureMode = choice(body, "exposureMode", MODES);
        if ("manual".equals(exposureMode) && caps.iso == null) {
            throw ApiException.unsupported("exposureMode", "This lens has no manual exposure.");
        }
        if ("manual".equals(exposureMode) && !rig.camera.settings().manualExposure()) {
            // Start manual from what auto exposure is using (and the editor shows), so the picture
            // doesn't jump to the last manual values.
            if (iso == null && rig.camera.autoIso() > 0) iso = rig.camera.autoIso();
            if (shutter == null && rig.camera.autoExposureNs() > 0) shutter = rig.camera.autoExposureNs();
        }
        String focusMode = choice(body, "focusMode", MODES);
        if ("manual".equals(focusMode) && caps.maxFocusDiopters == null) {
            throw ApiException.unsupported("focusMode", "This lens has no manual focus.");
        }
        String wb = null;
        if (body.has("whiteBalance")) {
            wb = body.optString("whiteBalance");
            if ("kelvin".equals(wb)) throw ApiException.unsupported("whiteBalance", "Kelvin white balance isn't supported yet.");
            if (!caps.whiteBalance.contains(wb)) throw ApiException.badRequest("whiteBalance",
                    "whiteBalance must be one of " + String.join(", ", caps.whiteBalance) + ".");
        }
        Integer kelvin = body.has("kelvin") ? (int) Math.round(number(body, "kelvin", 2000, 10000)) : null;
        Boolean aeLock = bool(body, "aeLock"), awbLock = bool(body, "awbLock"), afLock = bool(body, "afLock");

        String fLens = lens, fExposure = exposureMode, fFocus = focusMode, fWb = wb;
        Integer fIso = iso, fEv = ev, fKelvin = kelvin;
        Long fShutter = shutter;
        Float fZoom = zoom, fDiopters = diopters;
        Consumer<CameraSettings> change = s -> {
            if (fLens != null) s.lens = fLens;
            if (fZoom != null) s.zoom = fZoom;
            if (fEv != null) s.ev = fEv;
            if (fIso != null) s.iso = fIso;
            if (fShutter != null) s.shutterNs = fShutter;
            if (fDiopters != null) s.focusDiopters = fDiopters;
            if (fExposure != null) s.exposureMode = fExposure;
            if (fFocus != null) s.focusMode = fFocus;
            if (fWb != null) s.whiteBalance = fWb;
            if (fKelvin != null) s.kelvin = fKelvin;
            if (aeLock != null) s.aeLock = aeLock;
            if (awbLock != null) s.awbLock = awbLock;
            if (afLock != null) s.afLock = afLock;
        };
        await(rig.camera.update(change));
        if (lens != null) Thread.sleep(800);  // let the new lens open, so the reply's values are its own
        return Json.obj("settings", rig.cameraSettingsJson());
    }

    /** Focus (only) held at a clicked point, or {"clear": true} to go back to continuous AF. */
    private JSONObject focus(Rig rig, JSONObject body) throws Exception {
        boolean clear = Boolean.TRUE.equals(body.opt("clear"));
        double x = clear ? 0 : number(body, "x", 0, 1), y = clear ? 0 : number(body, "y", 0, 1);
        Job running = rig.jobs.current();
        if (running != null && running.kind != Job.Kind.MOVE) {
            throw new ApiException(409, "busy", "Focus is locked while " + running.kindLabel().toLowerCase(Locale.ROOT)
                    + " runs.").with("jobId", running.id);
        }
        if (!rig.camera.isOpen()) throw new ApiException(503, "camera_unavailable", "The camera isn't open yet.");
        if (clear) {
            await(rig.camera.clearFocusPoint());
        } else {
            await(rig.camera.update(s -> {
                s.afLock = false;
                if (s.manualFocus()) s.focusMode = "auto";
            }));
            await(rig.camera.focusAtNormalized((float) x, (float) y));
        }
        return Json.obj("settings", rig.cameraSettingsJson());
    }

    private Job move(Rig rig, JSONObject body) throws ApiException {
        if (body.optBoolean("home", false)) return rig.startMove(Job.EDITOR, null, null, true);
        if (body.has("to")) return rig.startMove(Job.EDITOR, null, number(body, "to", 0, 360), false);
        if (body.has("by")) return rig.startMove(Job.EDITOR, number(body, "by", -360, 360), null, false);
        throw ApiException.badRequest(null, "Give by, to or home.");
    }

    private Job extra(Rig rig, JSONObject body) throws ApiException {
        String label = body.optString("label", "").trim();
        if (label.isEmpty() || label.length() > 60) {
            throw ApiException.badRequest("label", "Give the extra shot a label (up to 60 characters).");
        }
        long delay = body.has("delayMs")
                ? Math.round(number(body, "delayMs", 0, 10000))
                : Rig.parseInt(station.prefs.getString("manual_delay_ms", "2000"), 2000);
        return rig.startExtra(Job.EDITOR, label, delay);
    }

    private HttpServer.Response shotRoute(String method, String id, String variantParam) throws ApiException, IOException {
        if (method.equals("DELETE")) {
            Job running = station.jobs.current();
            if (running != null && running.shotIds().contains(id)) {
                throw new ApiException(409, "busy", "That shot belongs to the running job.").with("jobId", running.id);
            }
            if (!station.shots.delete(id)) throw notFound("No such shot.");
            return HttpServer.Response.empty(204);
        }
        if (!method.equals("GET")) throw methodNotAllowed(method);
        if (station.shots.json(id) == null) throw notFound("No such shot.");
        String variant = variant(variantParam);
        File f = station.shots.file(id, variant);
        if (f == null) throw notFound("No such shot.");
        if (!variant.equals("thumb") && !station.shots.ready(id)) {
            throw new ApiException(409, "not_ready", "The spin video is still being encoded.");
        }
        if (!f.isFile()) throw notFound("That file is missing on the phone.");
        String[] mimeName = station.shots.mimeAndName(id, variant);
        if (mimeName == null) throw notFound("No such shot.");
        return HttpServer.Response.stream(200, mimeName[0], new FileInputStream(f), f.length())
                .header("Content-Disposition", "attachment; filename=\"" + mimeName[1] + "\"")
                .header("X-Shot-Filename", mimeName[1]);
    }

    // ---- Helpers -------------------------------------------------------------------

    private String requireAuth(HttpServer.Request req) throws ApiException {
        String token = token(req);
        if (token == null || server.pairing.check(token) == null) {
            throw new ApiException(401, "unauthorized", "This browser isn't paired with the phone, or its pairing was revoked.");
        }
        return token;
    }

    private static String token(HttpServer.Request req) {
        String auth = req.header("authorization");
        if (auth == null) return null;
        Matcher m = Pattern.compile("Bearer\\s+(\\S+)\\s*", Pattern.CASE_INSENSITIVE).matcher(auth);
        return m.matches() ? m.group(1) : null;
    }

    private static JSONObject body(HttpServer.Request req) throws ApiException {
        String text = new String(req.body, StandardCharsets.UTF_8).trim();
        if (text.isEmpty()) return new JSONObject();
        try {
            Object v = new org.json.JSONTokener(text).nextValue();
            if (!(v instanceof JSONObject)) throw ApiException.badRequest(null, "The request body must be a JSON object.");
            return (JSONObject) v;
        } catch (JSONException e) {
            throw ApiException.badRequest(null, "The request body isn't valid JSON.");
        }
    }

    private static double number(JSONObject o, String field, double lo, double hi) throws ApiException {
        Object v = o.opt(field);
        if (!(v instanceof Number)) throw ApiException.badRequest(field, field + " must be a number.");
        double d = ((Number) v).doubleValue();
        if (!(d >= lo - 1e-9 && d <= hi + 1e-9)) {
            throw ApiException.badRequest(field, String.format(Locale.ROOT, "%s must be from %s to %s.", field, fmt(lo), fmt(hi)));
        }
        return d;
    }

    private static String fmt(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
    }

    private static String choice(JSONObject o, String field, List<String> allowed) throws ApiException {
        if (!o.has(field)) return null;
        String v = o.optString(field);
        if (!allowed.contains(v)) throw ApiException.badRequest(field, field + " must be one of " + String.join(", ", allowed) + ".");
        return v;
    }

    private static Boolean bool(JSONObject o, String field) throws ApiException {
        if (!o.has(field)) return null;
        Object v = o.opt(field);
        if (!(v instanceof Boolean)) throw ApiException.badRequest(field, field + " must be true or false.");
        return (Boolean) v;
    }

    private static void await(java.util.concurrent.Future<?> f) throws Exception {
        try {
            f.get(5, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            throw new ApiException(500, "internal", String.valueOf(e.getCause().getMessage()));
        } catch (TimeoutException e) {
            throw new ApiException(503, "camera_unavailable", "The camera didn't respond.");
        }
    }

    private static ApiException notFound(String message) {
        return new ApiException(404, "not_found", message);
    }

    private static ApiException methodNotAllowed(String method) {
        return new ApiException(405, "method_not_allowed", method + " isn't allowed here.");
    }

    private static HttpServer.Response ok(JSONObject o) {
        return json(200, o);
    }

    private static HttpServer.Response accepted(Job job) {
        return json(202, Json.obj("job", job.toJson()));
    }

    private static HttpServer.Response json(int status, JSONObject o) {
        return HttpServer.Response.json(status, o.toString());
    }

}
