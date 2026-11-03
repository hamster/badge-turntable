package org.snurkle.badgeturntable.ir;

import java.util.Arrays;

/**
 * One IR button press, in the form ConsumerIrManager.transmit() takes:
 * a carrier frequency and alternating on/off durations in microseconds,
 * starting with "on". Patterns are capped at 2 s by Android.
 */
public final class IrCode {
    public final int carrierHz;
    private final int[] pattern;

    public IrCode(int carrierHz, int[] pattern) {
        if (pattern.length == 0) throw new IllegalArgumentException("empty pattern");
        this.carrierHz = carrierHz;
        this.pattern = pattern.clone();
    }

    public int[] pattern() {
        return pattern.clone();
    }

    public long durationUs() {
        long total = 0;
        for (int p : pattern) total += p;
        return total;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof IrCode)) return false;
        IrCode other = (IrCode) o;
        return carrierHz == other.carrierHz && Arrays.equals(pattern, other.pattern);
    }

    @Override
    public int hashCode() {
        return 31 * carrierHz + Arrays.hashCode(pattern);
    }

    /** "38000:9000,4500,562,..." for storing in JSON. */
    public String serialize() {
        StringBuilder sb = new StringBuilder().append(carrierHz).append(':');
        for (int i = 0; i < pattern.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(pattern[i]);
        }
        return sb.toString();
    }

    public static IrCode parse(String s) {
        int colon = s.indexOf(':');
        if (colon < 0) throw new IllegalArgumentException("bad IR code: " + s);
        String[] parts = s.substring(colon + 1).split(",");
        int[] p = new int[parts.length];
        for (int i = 0; i < parts.length; i++) p[i] = Integer.parseInt(parts[i].trim());
        return new IrCode(Integer.parseInt(s.substring(0, colon).trim()), p);
    }
}
