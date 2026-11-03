package org.snurkle.badgeturntable.media;

import java.util.ArrayList;
import java.util.List;

/**
 * Table angle over time while the spin video records, from the ring detector.
 * Used to trim the clip to exactly one revolution so it loops seamlessly.
 * Pure Java for unit tests.
 */
public final class AngleLog {

    private final List<long[]> times = new ArrayList<>();   // {timestampNs}
    private final List<Double> unwrapped = new ArrayList<>();
    private double last = Double.NaN;
    private double offset;

    /** Adds a reading; angles may wrap past 360 in either direction. */
    public synchronized void add(long timestampNs, double angleDeg) {
        if (Double.isNaN(angleDeg)) return;
        if (!Double.isNaN(last)) {
            double d = angleDeg - last;
            if (d > 180) offset -= 360;
            else if (d < -180) offset += 360;
        }
        last = angleDeg;
        times.add(new long[]{timestampNs});
        unwrapped.add(angleDeg + offset);
    }

    public synchronized int size() {
        return times.size();
    }

    /** Total rotation so far, in degrees (absolute value, either direction). */
    public synchronized double travelled() {
        if (unwrapped.isEmpty()) return 0;
        return Math.abs(unwrapped.get(unwrapped.size() - 1) - unwrapped.get(0));
    }

    /**
     * Start and end timestamps (ns) of one full revolution, beginning at the
     * first reading, interpolated between frames. Null if the log covers less
     * than a full turn.
     */
    public synchronized long[] oneRevolution() {
        if (unwrapped.size() < 2) return null;
        double a0 = unwrapped.get(0);
        double sign = Math.signum(unwrapped.get(unwrapped.size() - 1) - a0);
        if (sign == 0) return null;
        double target = a0 + sign * 360;
        for (int i = 1; i < unwrapped.size(); i++) {
            double prev = unwrapped.get(i - 1), cur = unwrapped.get(i);
            if ((cur - target) * sign >= 0) {
                double f = (target - prev) / (cur - prev);
                long t0 = times.get(i - 1)[0], t1 = times.get(i)[0];
                long end = t0 + Math.round(f * (t1 - t0));
                return new long[]{times.get(0)[0], end};
            }
        }
        return null;
    }
}
