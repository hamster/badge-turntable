package org.snurkle.badgeturntable.jobs;

import org.json.JSONObject;

import java.util.Iterator;

/**
 * An error carrying one of the remote API's codes (Remote-API.md, Errors). The server
 * sends it as {status, {"error", "message", …extra}}; the phone shows the message.
 */
public final class ApiException extends Exception {
    public final int status;
    public final String code;
    private final JSONObject extra = new JSONObject();

    public ApiException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    /** Adds an extra field, such as "field" or "jobId". */
    public ApiException with(String key, Object value) {
        Json.put(extra, key, value);
        return this;
    }

    public JSONObject body() {
        JSONObject o = Json.obj("error", code, "message", getMessage());
        for (Iterator<String> it = extra.keys(); it.hasNext(); ) {
            String k = it.next();
            Json.put(o, k, extra.opt(k));
        }
        return o;
    }

    public static ApiException badRequest(String field, String message) {
        ApiException e = new ApiException(400, "bad_request", message);
        return field == null ? e : e.with("field", field);
    }

    public static ApiException unsupported(String field, String message) {
        ApiException e = new ApiException(422, "unsupported", message);
        return field == null ? e : e.with("field", field);
    }

    public static ApiException busy(Job running) {
        return new ApiException(409, "busy", running.kindLabel() + " is running. Stop it first.").with("jobId", running.id);
    }

    public static ApiException notCalibrated() {
        return new ApiException(409, "not_calibrated",
                "Calibrate the turntable first, or capture in manual mode.");
    }
}
