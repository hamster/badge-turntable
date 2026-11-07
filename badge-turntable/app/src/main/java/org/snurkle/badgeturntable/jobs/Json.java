package org.snurkle.badgeturntable.jobs;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Collection;

/** Small org.json helpers, so callers don't have to catch JSONException for every put. */
public final class Json {
    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private Json() {}

    /** {"k1": v1, "k2": v2, …}. Null values become JSON null. */
    public static JSONObject obj(Object... keysAndValues) {
        JSONObject o = new JSONObject();
        for (int i = 0; i + 1 < keysAndValues.length; i += 2) put(o, (String) keysAndValues[i], keysAndValues[i + 1]);
        return o;
    }

    public static JSONObject put(JSONObject o, String key, Object value) {
        try {
            if (value instanceof Double && !Double.isFinite((Double) value)) value = null;
            o.put(key, value == null ? JSONObject.NULL : value);
        } catch (JSONException e) {
            throw new IllegalArgumentException(e);
        }
        return o;
    }

    public static JSONArray arr(Collection<?> items) {
        JSONArray a = new JSONArray();
        for (Object item : items) a.put(item == null ? JSONObject.NULL : item);
        return a;
    }

    /** A degree value rounded to 0.01, or null for NaN. */
    public static Object deg(double d) {
        return Double.isFinite(d) ? Math.round(d * 100) / 100.0 : null;
    }

    /** ISO 8601 UTC with milliseconds, as the API uses; null for 0. */
    public static Object time(long epochMs) {
        return epochMs <= 0 ? null : ISO.format(Instant.ofEpochMilli(epochMs));
    }

    /** A shallow copy, so a snapshot doesn't change under the caller. */
    public static JSONObject copy(JSONObject o) {
        try {
            return new JSONObject(o.toString());
        } catch (JSONException e) {
            throw new IllegalArgumentException(e);
        }
    }
}
