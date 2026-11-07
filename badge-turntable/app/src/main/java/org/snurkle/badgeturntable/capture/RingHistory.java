package org.snurkle.badgeturntable.capture;

import android.os.SystemClock;

import androidx.annotation.NonNull;

import org.snurkle.badgeturntable.motion.MoveFailedException;
import org.snurkle.badgeturntable.ring.RingDetector;
import org.snurkle.badgeturntable.ring.RingReading;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The last few seconds of ring readings, for shooting while the table turns: the current
 * rate, when a target angle will come round, and the angle at any sensor timestamp.
 *
 * Readings carry the camera's sensor timestamps, which on the phones tested are on the
 * elapsedRealtimeNanos clock (SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME), so the angle at a
 * still's exposure can be interpolated from the analysis frames either side of it.
 */
final class RingHistory implements RingDetector.Listener {
    private static final long KEEP_NS = 10_000_000_000L;
    private static final long RATE_WINDOW_NS = 400_000_000L;
    /** Slower than this counts as not turning. */
    static final double MIN_RATE = 3;

    /** {sensor timestamp ns, angle deg}, oldest first. */
    private final List<double[]> readings = new ArrayList<>();

    @Override
    public void onReading(@NonNull RingReading r) {
        if (!r.ok()) return;
        synchronized (readings) {
            readings.add(new double[]{r.timestampNs, r.angleDeg});
            while (!readings.isEmpty() && r.timestampNs - readings.get(0)[0] > KEEP_NS) readings.remove(0);
        }
    }

    /** Signed deg/s over the last 0.4 s of readings, or NaN if there are too few. */
    double rate() {
        synchronized (readings) {
            int n = readings.size();
            if (n < 2) return Double.NaN;
            double[] last = readings.get(n - 1), prev = last;
            double travel = 0;
            for (int i = n - 2; i >= 0 && last[0] - readings.get(i)[0] <= RATE_WINDOW_NS; i--) {
                travel += RingReading.delta(readings.get(i)[1], prev[1]);
                prev = readings.get(i);
            }
            double dt = (last[0] - prev[0]) / 1e9;
            return dt > 0.1 ? travel / dt : Double.NaN;
        }
    }

    /** Waits until the angle stays within tolDeg for stableMs; returns it, or NaN on timeout. */
    double waitForStill(long timeoutMs, double tolDeg, long stableMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            synchronized (readings) {
                int n = readings.size();
                if (n > 1) {
                    double[] last = readings.get(n - 1);
                    boolean still = last[0] - readings.get(0)[0] >= stableMs * 1e6;
                    for (int i = n - 2; i >= 0 && still && last[0] - readings.get(i)[0] <= stableMs * 1e6; i--) {
                        if (Math.abs(RingReading.delta(readings.get(i)[1], last[1])) > tolDeg) still = false;
                    }
                    if (still) return last[1];
                }
            }
            Thread.sleep(50);
        }
        return Double.NaN;
    }

    /**
     * Waits until the table has turned one way, faster than MIN_RATE, for steadyMs; returns
     * the signed rate. One reading isn't enough: on the bench the JAYEGT once moved ~100 deg,
     * paused for 5 s and then turned the other way.
     */
    double waitForSteadyRotation(long steadyMs, long timeoutMs) throws InterruptedException, MoveFailedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        long since = 0;
        double sign = 0;
        while (System.currentTimeMillis() < deadline) {
            double r = rate();
            long now = System.currentTimeMillis();
            if (!Double.isNaN(r) && Math.abs(r) > MIN_RATE) {
                if (Math.signum(r) != sign) {
                    sign = Math.signum(r);
                    since = now;
                } else if (now - since >= steadyMs) {
                    return r;
                }
            } else {
                sign = 0;
            }
            Thread.sleep(50);
        }
        throw new MoveFailedException(MoveFailedException.Reason.NO_MOVEMENT, "the table didn't start turning steadily");
    }

    /**
     * Returns (as elapsedRealtimeNanos) the moment to trigger a still so that, lagNs later,
     * the table is at {@code target}. Readings arrive ~100 ms late, so the current angle is
     * extrapolated from the latest one at the current rate. A target missed by more than
     * 5 deg (say the camera was still busy) is caught on the next revolution.
     */
    long waitToFire(double target, double sign, long lagNs, long timeoutMs)
            throws InterruptedException, MoveFailedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            double[] last;
            synchronized (readings) {
                last = readings.isEmpty() ? null : readings.get(readings.size() - 1);
            }
            double r = rate();
            if (last != null && !Double.isNaN(r)) {
                if (Math.abs(r) > MIN_RATE && Math.signum(r) != sign) {
                    throw new MoveFailedException(MoveFailedException.Reason.REVERSED,
                            String.format(Locale.ROOT, "the table reversed (now %.1f deg/s)", r));
                }
                if (Math.abs(r) <= MIN_RATE) {
                    throw new MoveFailedException(MoveFailedException.Reason.NO_MOVEMENT, "the table stopped turning");
                }
                long now = SystemClock.elapsedRealtimeNanos();
                double predicted = last[1] + r * (now + lagNs - last[0]) / 1e9;
                double ahead = sign * RingReading.delta(predicted, target);  // > 0: still to come
                if (ahead <= 0 && ahead > -5) return now;
            }
            Thread.sleep(2);
        }
        throw new MoveFailedException(MoveFailedException.Reason.TIMEOUT,
                String.format(Locale.ROOT, "%.0f deg didn't come round in time", target));
    }

    /** Ring angle at a sensor timestamp, interpolated; waits up to waitMs for readings past it. NaN if none. */
    double angleAt(long tNs, long waitMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + waitMs;
        while (true) {
            synchronized (readings) {
                for (int i = readings.size() - 1; i > 0; i--) {
                    double[] b = readings.get(i), a = readings.get(i - 1);
                    if (a[0] <= tNs && tNs <= b[0]) {
                        double f = (tNs - a[0]) / (b[0] - a[0]);
                        double ang = a[1] + f * RingReading.delta(a[1], b[1]);
                        return (ang % 360 + 360) % 360;
                    }
                }
            }
            if (System.currentTimeMillis() > deadline) return Double.NaN;
            Thread.sleep(20);
        }
    }
}
