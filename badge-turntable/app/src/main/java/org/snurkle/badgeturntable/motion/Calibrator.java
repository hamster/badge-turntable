package org.snurkle.badgeturntable.motion;

import org.snurkle.badgeturntable.ir.TurntableProfile;
import org.snurkle.badgeturntable.ir.TurntableProfile.Role;
import org.snurkle.badgeturntable.ring.RingReading;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The calibration routine from the spec: spin, measure speed, direction and stop
 * overshoot, measure the 90° step if there is one, then home to zero. Blocking.
 */
public final class Calibrator {
    private final TurntableController table;
    private final Supplier<RingReading> ring;
    private final Consumer<String> status;

    public Calibrator(TurntableController table, Supplier<RingReading> ring, Consumer<String> status) {
        this.table = table;
        this.ring = ring;
        this.status = status;
    }

    public Calibration run(int markerCount) throws MoveFailedException, InterruptedException {
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
        table.press(Role.START_STOP);
        long movedAt = waitForMovement(start, 3000);
        if (movedAt < 0) {
            table.press(Role.START_STOP);
            movedAt = waitForMovement(start, 3000);
            if (movedAt < 0) throw new MoveFailedException(MoveFailedException.Reason.NO_MOVEMENT, "no movement");
        }
        cal.startLatencyMs = movedAt - sent;
        double[] turn = trackTurn(360, 120_000);  // {signedTravel, seconds}
        cal.directionSign = turn[0] >= 0 ? 1 : -1;
        cal.degPerSec = Math.abs(turn[0]) / turn[1];

        // 2. Stop and measure overshoot, a few times.
        double overshoot = 0;
        int runs = 3;
        for (int i = 0; i < runs; i++) {
            status.accept("Measuring stop distance (" + (i + 1) + "/" + runs + ")…");
            RingReading atStop = ring.get();
            table.press(Role.START_STOP);
            double settled = table.waitForStable(5000, 0.5, 300);
            if (atStop.ok() && !Double.isNaN(settled)) overshoot += cal.forward(atStop.angleDeg, settled);
            if (i < runs - 1) {
                table.press(Role.START_STOP);
                Thread.sleep(1500);
            }
        }
        cal.stopOvershootDeg = overshoot / runs;

        // 3. Step size, if the remote has a 90° step.
        if (profile.has(Role.STEP_90)) {
            status.accept("Measuring the 90° step…");
            double before = table.waitForStable(3000, 0.5, 300);
            table.press(Role.STEP_90);
            double after = table.waitForStable(15_000, 0.5, 400);
            if (!Double.isNaN(before) && !Double.isNaN(after)) cal.step90Deg = cal.forward(before, after);
        }

        // 4. Home to marker 0 and make it the table's own origin too.
        status.accept("Homing to zero…");
        cal.zeroAngleDeg = 0;
        table.setCalibration(cal);
        double now = table.waitForStable(3000, 0.5, 300);
        double togo = cal.forward(now, 0);
        if (togo > 1 && togo < 359) table.moveBy(togo);
        if (profile.has(Role.SET_ORIGIN)) table.press(Role.SET_ORIGIN);

        cal.calibratedAtMs = System.currentTimeMillis();
        status.accept(String.format("Calibrated: %.1f°/s, stop overshoot %.1f°", cal.degPerSec, cal.stopOvershootDeg));
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
