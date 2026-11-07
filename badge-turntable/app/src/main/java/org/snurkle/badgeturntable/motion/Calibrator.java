package org.snurkle.badgeturntable.motion;

import android.util.Log;

import org.snurkle.badgeturntable.ir.TurntableProfile;
import org.snurkle.badgeturntable.ir.TurntableProfile.Role;
import org.snurkle.badgeturntable.ring.RingReading;

import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The calibration routine from the spec: spin, measure speed, direction and stop
 * overshoot, measure the 90° step if there is one, then home to zero. Blocking.
 */
public final class Calibrator {
    private static final String TAG = "Calibrator";
    /** Positioning tolerance for zero, as in the spec. */
    private static final double ZERO_TOL_DEG = 2.0;
    private static final int HOMING_ATTEMPTS = 3;

    private final TurntableController table;
    private final Supplier<RingReading> ring;
    private final Consumer<String> status;

    public Calibrator(TurntableController table, Supplier<RingReading> ring, Consumer<String> status) {
        this.table = table;
        this.ring = ring;
        this.status = status;
    }

    /** @param zeroDeg the ring angle to home to: the front, if one is set, else marker 0 */
    public Calibration run(int markerCount, double zeroDeg) throws MoveFailedException, InterruptedException {
        TurntableProfile profile = table.profile();
        Calibration cal = new Calibration();
        cal.markerCount = markerCount;
        cal.profileFingerprint = profile.fingerprint();
        table.setCalibration(cal);

        status.accept("Checking the encoder ring…");
        double start = table.waitForStable(3000, 0.5, 300);
        if (Double.isNaN(start)) {
            throw new MoveFailedException(MoveFailedException.Reason.RING_LOST, "ring not visible");
        }

        // 1. Spin one full turn: speed, direction, start latency.
        status.accept("Spinning one full turn…");
        long sent = System.currentTimeMillis();
        table.start();
        long movedAt = waitForMovement(start, 3000);
        if (movedAt < 0) {
            table.start();
            movedAt = waitForMovement(start, 3000);
            if (movedAt < 0) throw new MoveFailedException(MoveFailedException.Reason.NO_MOVEMENT, "no movement");
        }
        cal.startLatencyMs = movedAt - sent;
        double[] turn = trackTurn(360, 120_000);  // {signedTravel, seconds}
        cal.directionSign = turn[0] >= 0 ? 1 : -1;
        cal.degPerSec = Math.abs(turn[0]) / turn[1];

        // 2. Stop and measure overshoot, a few times.
        double overshoot = 0, minOver = Double.MAX_VALUE, maxOver = -Double.MAX_VALUE;
        int runs = 3, measured = 0;
        for (int i = 0; i < runs; i++) {
            status.accept("Measuring stop distance (" + (i + 1) + "/" + runs + ")…");
            RingReading atStop = ring.get();
            table.stop();
            double settled = table.waitForStable(5000, 0.5, 300);
            if (atStop.ok() && !Double.isNaN(settled)) {
                double o = cal.forward(atStop.angleDeg, settled);
                overshoot += o;
                measured++;
                minOver = Math.min(minOver, o);
                maxOver = Math.max(maxOver, o);
                Log.i(TAG, String.format("stop %d: at %.2f, settled %.2f, overshoot %.2f", i + 1, atStop.angleDeg, settled, o));
            }
            if (i < runs - 1) {
                table.start();
                Thread.sleep(1500);
            }
        }
        cal.stopOvershootDeg = measured > 0 ? overshoot / measured : 0;

        // 3. Step size, if the remote has a 90° step.
        if (profile.has(Role.STEP_90)) {
            status.accept("Measuring the 90° step…");
            double before = table.waitForStable(3000, 0.5, 300);
            if (Double.isNaN(before)) {
                // Pressing a step while the table is still turning measures nothing useful.
                throw new MoveFailedException(MoveFailedException.Reason.RING_LOST, "table not still before the step test");
            }
            table.press(Role.STEP_90);
            if (waitForMovement(before, 2000) < 0) {
                Log.w(TAG, "step press missed; pressing again");
                table.press(Role.STEP_90);
            }
            double after = table.waitForStable(15_000, 0.5, 400);
            if (!Double.isNaN(before) && !Double.isNaN(after)) {
                cal.step90Deg = cal.forward(before, after);
                // Catches a profile whose Step_90 button isn't really a 90° step (e.g. a swing
                // button, which is what the JAYEGT's 45/90/180 buttons turned out to be).
                if (Math.abs(cal.step90Deg - 90) > 15) {
                    throw new MoveFailedException(MoveFailedException.Reason.STEP_SIZE, String.format(
                            "The 90° step button turned the table %.0f°, not 90°. Check which button the turntable "
                            + "profile uses for the 90° step.", cal.step90Deg));
                }
            }
        }

        // 4. Home to zero (the front, or marker 0) and make it the table's own origin too.
        status.accept("Homing to zero…");
        cal.zeroAngleDeg = zeroDeg;
        table.setCalibration(cal);
        // Continuous moves only go forward, so a near miss past zero costs another lap.
        // Set Origin only once the table is really at zero, or its own homing would be off.
        double now = table.waitForStable(3000, 0.5, 300);
        for (int attempt = 1; ; attempt++) {
            if (Double.isNaN(now)) throw new MoveFailedException(MoveFailedException.Reason.RING_LOST, "ring lost while homing");
            double off = RingReading.delta(zeroDeg, now);
            Log.i(TAG, String.format("homing attempt %d: at %.2f (%.2f from zero)", attempt, now, off));
            if (Math.abs(off) <= ZERO_TOL_DEG) break;
            if (attempt > HOMING_ATTEMPTS) {
                throw new MoveFailedException(MoveFailedException.Reason.TIMEOUT, String.format(
                        "couldn't stop within %.0f° of zero in %d tries (last %.1f° off)", ZERO_TOL_DEG, HOMING_ATTEMPTS, off));
            }
            now = table.moveBy(cal.forward(now, zeroDeg));
        }
        if (profile.has(Role.SET_ORIGIN)) table.press(Role.SET_ORIGIN);

        cal.calibratedAtMs = System.currentTimeMillis();
        status.accept(String.format(Locale.US, "Calibrated: %.1f°/s, stop overshoot %.1f° (%.1f–%.1f)",
                cal.degPerSec, cal.stopOvershootDeg, minOver, maxOver));
        return cal;
    }

    private long waitForMovement(double start, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            RingReading r = ring.get();
            if (r.ok() && Math.abs(RingReading.delta(start, r.angleDeg)) >= 2) return System.currentTimeMillis();
            Thread.sleep(30);
        }
        return -1;
    }

    /** Unwraps readings until |travel| >= degrees; returns {signed travel, seconds}. */
    private double[] trackTurn(double degrees, long timeoutMs) throws MoveFailedException, InterruptedException {
        long t0 = System.currentTimeMillis();
        double travel = 0, last = Double.NaN;
        while (System.currentTimeMillis() - t0 < timeoutMs) {
            RingReading r = ring.get();
            if (r.ok()) {
                if (!Double.isNaN(last)) travel += RingReading.delta(last, r.angleDeg);
                last = r.angleDeg;
                if (Math.abs(travel) >= degrees) return new double[]{travel, (System.currentTimeMillis() - t0) / 1000.0};
            }
            Thread.sleep(30);
        }
        throw new MoveFailedException(MoveFailedException.Reason.TIMEOUT, "no full turn within " + timeoutMs / 1000 + " s");
    }
}
