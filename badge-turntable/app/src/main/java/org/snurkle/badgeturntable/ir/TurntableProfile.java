package org.snurkle.badgeturntable.ir;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * Everything the app knows about one turntable: how it's controlled and which
 * IR code does what. Built-in presets live in {@link Presets}; custom ones are
 * built in Settings and stored as JSON (see ProfileStore).
 */
public final class TurntableProfile {

    public enum ControlType {
        CONTINUOUS,             // start/stop (+ direction, speed)
        CONTINUOUS_AND_STEPS,   // plus fixed-angle step buttons
        NONE                    // no remote: manual mode only
    }

    /** Button roles. Step roles carry the angle they move. */
    public enum Role {
        START_STOP(0), TURN_LEFT(0), TURN_RIGHT(0), SPEED_UP(0), SPEED_DOWN(0),
        CONTINUOUS_MODE(0), INTERMITTENT_MODE(0),
        STEP_45(45), STEP_90(90), STEP_180(180),
        SET_ORIGIN(0), RETURN_TO_ORIGIN(0);

        public final int stepDeg;

        Role(int stepDeg) {
            this.stepDeg = stepDeg;
        }
    }

    public final String id;
    public final String name;
    public final ControlType controlType;
    private final Map<Role, IrCode> codes;
    /** Seconds per revolution at each speed setting, slowest last; may be empty. */
    public final double[] secondsPerRev;

    public TurntableProfile(String id, String name, ControlType controlType,
                            Map<Role, IrCode> codes, double[] secondsPerRev) {
        this.id = id;
        this.name = name;
        this.controlType = controlType;
        this.codes = codes.isEmpty() ? new EnumMap<>(Role.class) : new EnumMap<>(codes);
        this.secondsPerRev = secondsPerRev.clone();
    }

    public IrCode code(Role role) {
        return codes.get(role);
    }

    public boolean has(Role role) {
        return codes.containsKey(role);
    }

    public Map<Role, IrCode> codes() {
        return Collections.unmodifiableMap(codes);
    }

    /** Auto mode needs at least start/stop. */
    public boolean supportsAuto() {
        return controlType != ControlType.NONE && has(Role.START_STOP);
    }

    /** Step button that moves exactly this far, or null. */
    public Role stepFor(int degrees) {
        for (Role r : Role.values()) if (r.stepDeg == degrees && has(r)) return r;
        return null;
    }

    /** Stable hash of the codes, stored with a calibration to spot profile changes. */
    public String fingerprint() {
        StringBuilder sb = new StringBuilder(id).append('|').append(controlType);
        for (Map.Entry<Role, IrCode> e : codes.entrySet()) {
            sb.append('|').append(e.getKey()).append('=').append(e.getValue().serialize());
        }
        return Integer.toHexString(sb.toString().hashCode());
    }
}
