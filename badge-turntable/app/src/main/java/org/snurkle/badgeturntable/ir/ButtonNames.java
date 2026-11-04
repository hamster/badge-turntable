package org.snurkle.badgeturntable.ir;

import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Maps button names in a Flipper .ir file to turntable roles. Name the buttons
 * like this when capturing a remote, and an imported file sets itself up.
 */
public final class ButtonNames {
    private ButtonNames() {}

    public static Map<TurntableProfile.Role, IrCode> map(List<FlipperIrParser.Signal> signals) {
        Map<TurntableProfile.Role, IrCode> codes = new EnumMap<>(TurntableProfile.Role.class);
        for (FlipperIrParser.Signal s : signals) {
            TurntableProfile.Role role = roleFor(s.name);
            if (role != null && s.code != null) codes.put(role, s.code);
        }
        return codes;
    }

    public static TurntableProfile.Role roleFor(String name) {
        String n = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        switch (n) {
            case "onpause": case "startstop": case "power": case "on": return TurntableProfile.Role.START_STOP;
            case "turnleft": case "left": return TurntableProfile.Role.TURN_LEFT;
            case "turnright": case "right": return TurntableProfile.Role.TURN_RIGHT;
            case "speedup": case "speed": return TurntableProfile.Role.SPEED_UP;
            case "speeddown": case "speedminus": return TurntableProfile.Role.SPEED_DOWN;
            case "continuous": case "resumecontinuousrotation": return TurntableProfile.Role.CONTINUOUS_MODE;
            case "intermittent": case "intermittentrotation": return TurntableProfile.Role.INTERMITTENT_MODE;
            // Step roles need an explicit "Step_" name. A bare "90" is ambiguous: on the
            // JAYEGT the 45/90/180 buttons set the swing of intermittent mode (named
            // Swing_45 etc., deliberately unmapped), and its unlabeled button is the step.
            case "step45": return TurntableProfile.Role.STEP_45;
            case "step90": return TurntableProfile.Role.STEP_90;
            case "step180": return TurntableProfile.Role.STEP_180;
            case "setorigin": case "setoriginposition": return TurntableProfile.Role.SET_ORIGIN;
            case "backtoset": case "backtosetposition": case "origin": case "home": return TurntableProfile.Role.RETURN_TO_ORIGIN;
            default: return null;
        }
    }
}
