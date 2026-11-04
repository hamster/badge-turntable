package org.snurkle.badgeturntable.motion;

import android.content.Context;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * What calibration learned about the turntable. Stored in SharedPreferences as JSON.
 *
 * "Forward" is the way the table turns for TurntableController.start() and the step buttons.
 * {@code directionSign} is +1 if the ring angle increases going forward, -1 if it
 * decreases (the table turning the other way, or the strips applied upside down;
 * either is fine as long as it's known).
 */
public final class Calibration {
    private static final String PREFS = "calibration";

    public double degPerSec;          // continuous rotation speed
    public int directionSign = 1;
    public double stopOvershootDeg;   // how far the table coasts after stop()
    public long startLatencyMs;       // time from start() to visible movement
    public double zeroAngleDeg;       // ring angle that counts as "home"
    public double step90Deg = Double.NaN;  // measured size of a STEP_90 press, if the remote has one
    public String profileFingerprint = "";
    public int markerCount = 36;
    public long calibratedAtMs;

    /** Forward travel from ring angle a to b, in [0, 360). */
    public double forward(double a, double b) {
        double d = (b - a) * directionSign % 360;
        return d < 0 ? d + 360 : d;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject()
                .put("degPerSec", degPerSec).put("directionSign", directionSign)
                .put("stopOvershootDeg", stopOvershootDeg).put("startLatencyMs", startLatencyMs)
                .put("zeroAngleDeg", zeroAngleDeg).put("profileFingerprint", profileFingerprint)
                .put("markerCount", markerCount).put("calibratedAtMs", calibratedAtMs);
        if (!Double.isNaN(step90Deg)) o.put("step90Deg", step90Deg);
        return o;
    }

    public static Calibration fromJson(JSONObject o) {
        Calibration c = new Calibration();
        c.degPerSec = o.optDouble("degPerSec");
        c.directionSign = o.optInt("directionSign", 1);
        c.stopOvershootDeg = o.optDouble("stopOvershootDeg");
        c.startLatencyMs = o.optLong("startLatencyMs");
        c.zeroAngleDeg = o.optDouble("zeroAngleDeg");
        c.step90Deg = o.optDouble("step90Deg", Double.NaN);
        c.profileFingerprint = o.optString("profileFingerprint");
        c.markerCount = o.optInt("markerCount", 36);
        c.calibratedAtMs = o.optLong("calibratedAtMs");
        return c;
    }

    public static Calibration load(Context context) {
        String json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("current", null);
        if (json == null) return null;
        try {
            return fromJson(new JSONObject(json));
        } catch (JSONException e) {
            return null;
        }
    }

    public void save(Context context) throws JSONException {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("current", toJson().toString()).apply();
    }

    public static void clear(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("current").apply();
    }
}
