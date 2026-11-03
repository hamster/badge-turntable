package org.snurkle.badgeturntable.ir;

import android.content.Context;
import android.util.Log;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Map;

/** Built-in turntable presets. */
public final class Presets {
    private static final String TAG = "Presets";

    public static final String JAYEGT_574 = "jayegt-5.74";
    public static final String NONE = "none";

    private Presets() {}

    /**
     * JAYEGT 5.74 in motorized display stand (remote-control version).
     * Codes come from assets/profiles/jayegt-5.74.ir once the remote has been
     * captured; until then the profile has no codes and auto mode is unavailable.
     * Button names in that file are matched to roles by {@link ButtonNames}.
     */
    public static TurntableProfile jayegt574(Context context) {
        Map<TurntableProfile.Role, IrCode> codes = new EnumMap<>(TurntableProfile.Role.class);
        try (Reader r = new InputStreamReader(context.getAssets().open("profiles/jayegt-5.74.ir"), StandardCharsets.UTF_8)) {
            codes.putAll(ButtonNames.map(FlipperIrParser.parse(r)));
        } catch (IOException e) {
            Log.i(TAG, "No JAYEGT codes bundled yet (assets/profiles/jayegt-5.74.ir)");
        }
        return new TurntableProfile(JAYEGT_574, "JAYEGT 5.74 in display stand",
                TurntableProfile.ControlType.CONTINUOUS_AND_STEPS, codes, new double[]{8, 15, 30});
    }

    public static TurntableProfile none() {
        return new TurntableProfile(NONE, "No remote (manual only)",
                TurntableProfile.ControlType.NONE, new EnumMap<>(TurntableProfile.Role.class), new double[0]);
    }

    public static TurntableProfile byId(Context context, String id) {
        if (JAYEGT_574.equals(id)) return jayegt574(context);
        return none();
    }
}
