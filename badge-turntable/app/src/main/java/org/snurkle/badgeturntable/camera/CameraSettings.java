package org.snurkle.badgeturntable.camera;

import android.content.SharedPreferences;

/**
 * The light box profile: every camera setting the phone or the editor can change, saved so
 * it's restored on launch (the light box doesn't change between badges). Field names and
 * values match Remote-API.md's camera settings. Instances are copied, never shared.
 */
public final class CameraSettings {
    /** Camera2 ID of the lens, or null for the phone's default back camera. */
    public String lens;
    public float zoom = 1f;
    public String exposureMode = "auto";  // "auto" uses ev; "manual" uses iso and shutterNs
    public int ev;                        // exposure compensation index
    public int iso = 200;
    public long shutterNs = 16_666_666L;
    public String focusMode = "auto";     // "auto" is continuous AF; "manual" uses focusDiopters
    public float focusDiopters;           // 0 is infinity
    public String whiteBalance = "auto";  // see CameraController.WhiteBalance
    public int kelvin = 5000;
    public boolean aeLock, awbLock, afLock;
    /** The focus distance held while afLock is on. Negative if unknown. */
    public float afLockDiopters = -1f;

    public boolean manualExposure() {
        return "manual".equals(exposureMode);
    }

    public boolean manualFocus() {
        return "manual".equals(focusMode);
    }

    public CameraSettings copy() {
        CameraSettings c = new CameraSettings();
        c.lens = lens;
        c.zoom = zoom;
        c.exposureMode = exposureMode;
        c.ev = ev;
        c.iso = iso;
        c.shutterNs = shutterNs;
        c.focusMode = focusMode;
        c.focusDiopters = focusDiopters;
        c.whiteBalance = whiteBalance;
        c.kelvin = kelvin;
        c.aeLock = aeLock;
        c.awbLock = awbLock;
        c.afLock = afLock;
        c.afLockDiopters = afLockDiopters;
        return c;
    }

    /** Reads the profile, including the older keys (iso 0 meant auto, focus -1 meant AF). */
    public static CameraSettings load(SharedPreferences p) {
        CameraSettings s = new CameraSettings();
        s.lens = p.getString("lens", null);
        s.zoom = p.getFloat("zoom", 1f);
        s.ev = p.getInt("ev", 0);
        int iso = p.getInt("iso", 0);
        s.exposureMode = p.getString("exposureMode", iso > 0 ? "manual" : "auto");
        s.iso = iso > 0 ? iso : 200;
        s.shutterNs = p.getLong("shutterNs", 16_666_666L);
        float focus = p.getFloat("focus", -1f);
        s.focusMode = p.getString("focusMode", focus >= 0 ? "manual" : "auto");
        s.focusDiopters = Math.max(0f, focus);
        String legacyWb = p.getString("wb", "AUTO");
        s.whiteBalance = p.getString("whiteBalance", legacyWb.toLowerCase(java.util.Locale.ROOT));
        s.kelvin = p.getInt("kelvin", 5000);
        s.aeLock = p.getBoolean("aeLock", false);
        s.awbLock = p.getBoolean("awbLock", false);
        s.afLock = p.getBoolean("afLock", false);
        s.afLockDiopters = p.getFloat("afLockDiopters", -1f);
        return s;
    }

    public void save(SharedPreferences p) {
        SharedPreferences.Editor e = p.edit()
                .putFloat("zoom", zoom)
                .putInt("ev", ev)
                .putString("exposureMode", exposureMode)
                .putInt("iso", iso)
                .putLong("shutterNs", shutterNs)
                .putString("focusMode", focusMode)
                .putFloat("focus", focusDiopters)
                .putString("whiteBalance", whiteBalance)
                .putInt("kelvin", kelvin)
                .putBoolean("aeLock", aeLock)
                .putBoolean("awbLock", awbLock)
                .putBoolean("afLock", afLock)
                .putFloat("afLockDiopters", afLockDiopters)
                .remove("wb");
        if (lens == null) e.remove("lens");
        else e.putString("lens", lens);
        e.apply();
    }
}
