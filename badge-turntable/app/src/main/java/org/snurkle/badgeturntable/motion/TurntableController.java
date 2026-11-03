package org.snurkle.badgeturntable.motion;

import org.snurkle.badgeturntable.ir.IrCode;
import org.snurkle.badgeturntable.ir.IrTransmitter;
import org.snurkle.badgeturntable.ir.TurntableProfile;
import org.snurkle.badgeturntable.ir.TurntableProfile.Role;
import org.snurkle.badgeturntable.ring.RingReading;

import java.util.function.Supplier;

/**
 * Closed-loop turntable moves: send IR, then watch the encoder ring to confirm
 * the table moved and see where it stopped. All methods block; call them from
 * the capture thread, never the main thread.
 */
public final class TurntableController {
    private static final long POLL_MS = 30;
    private static final double MOVED_DEG = 2.0;
    private static final long PRESS_GAP_MS = 150;

    private final IrTransmitter ir;
    private final TurntableProfile profile;
    private final Supplier<RingReading> ring;
    private Calibration cal;

    public TurntableController(IrTransmitter ir, TurntableProfile profile, Supplier<RingReading> ring, Calibration cal) {
        this.ir = ir;
        this.profile = profile;
        this.ring = ring;
        this.cal = cal;
    }

    public void setCalibration(Calibration cal) {
        this.cal = cal;
    }

    public TurntableProfile profile() {
        return profile;
    }

    public void press(Role role) throws MoveFailedException, InterruptedException {
        IrCode code = profile.code(role);
        if (code == null) throw new MoveFailedException(MoveFailedException.Reason.NO_REMOTE, "no code for " + role);
        ir.send(code);
        Thread.sleep(PRESS_GAP_MS);
    }

    /**
     * Waits until the angle has stayed within tolDeg for stableMs.
     * @return the settled angle, or NaN if the ring wasn't readable or it never settled
     */
    public double waitForStable(long timeoutMs, double tolDeg, long stableMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        double anchor = Double.NaN;
        long anchorAt = 0;
        while (System.currentTimeMillis() < deadline) {
            RingReading r = ring.get();
            long now = System.currentTimeMillis();
            if (r.ok()) {
                if (Double.isNaN(anchor) || Math.abs(RingReading.delta(anchor, r.angleDeg)) > tolDeg) {
                    anchor = r.angleDeg;
                    anchorAt = now;
                } else if (now - anchorAt >= stableMs) {
                    return r.angleDeg;
                }
            }
            Thread.sleep(POLL_MS);
        }
        return Double.NaN;
    }

    /**
     * Turns the table forward by about {@code degrees} and returns the settled angle.
     * Uses a step button when one matches, otherwise continuous rotation stopped early
     * by the calibrated overshoot.
     */
    public double moveBy(double degrees) throws MoveFailedException, InterruptedException {
        double start = waitForStable(3000, 0.5, 300);
        if (Double.isNaN(start)) throw new MoveFailedException(MoveFailedException.Reason.RING_LOST, "ring not readable before move");

        Role step = profile.stepFor((int) Math.round(degrees));
        for (int attempt = 0; attempt < 2; attempt++) {
            if (step != null) {
                press(step);
            } else {
                press(Role.START_STOP);
            }
            if (awaitMovement(start)) {
                if (step == null) stopAfter(start, degrees - cal.stopOvershootDeg);
                double end = waitForStable(timeForMs(degrees) + 5000, 0.5, 300);
                if (Double.isNaN(end)) throw new MoveFailedException(MoveFailedException.Reason.RING_LOST, "ring lost during move");
                return end;
            }
            // Nothing happened: the press was probably missed. Try once more.
        }
        throw new MoveFailedException(MoveFailedException.Reason.NO_MOVEMENT, "no movement after two presses");
    }

    /** Back to the calibrated zero, by RETURN_TO_ORIGIN if the remote has it. */
    public double home() throws MoveFailedException, InterruptedException {
        if (profile.has(Role.RETURN_TO_ORIGIN)) {
            double start = waitForStable(3000, 0.5, 300);
            press(Role.RETURN_TO_ORIGIN);
            if (!Double.isNaN(start) && Math.abs(RingReading.delta(start, cal.zeroAngleDeg)) > MOVED_DEG) awaitMovement(start);
            return waitForStable(timeForMs(360) + 5000, 0.5, 300);
        }
        double now = waitForStable(3000, 0.5, 300);
        if (Double.isNaN(now)) throw new MoveFailedException(MoveFailedException.Reason.RING_LOST, "ring not readable");
        double togo = cal.forward(now, cal.zeroAngleDeg);
        return togo < 1 ? now : moveBy(togo);
    }

    /** True once the angle has moved by MOVED_DEG within the expected start time. */
    private boolean awaitMovement(double start) throws InterruptedException {
        long deadline = System.currentTimeMillis() + Math.max(cal.startLatencyMs, 500) + 1500;
        while (System.currentTimeMillis() < deadline) {
            RingReading r = ring.get();
            if (r.ok() && Math.abs(RingReading.delta(start, r.angleDeg)) >= MOVED_DEG) return true;
            Thread.sleep(POLL_MS);
        }
        return false;
    }

    /** Continuous rotation: send START_STOP once forward travel reaches {@code stopAt} degrees. */
    private void stopAfter(double start, double stopAt) throws MoveFailedException, InterruptedException {
        long deadline = System.currentTimeMillis() + timeForMs(stopAt) + 5000;
        double travelled = 0, last = start;
        while (System.currentTimeMillis() < deadline) {
            RingReading r = ring.get();
            if (r.ok()) {
                double step = RingReading.delta(last, r.angleDeg) * cal.directionSign;
                if (step > -5) travelled += Math.max(step, 0);  // ignore jitter backwards
                last = r.angleDeg;
                if (travelled >= stopAt) {
                    press(Role.START_STOP);
                    return;
                }
            }
            Thread.sleep(POLL_MS / 2);
        }
        press(Role.START_STOP);
        throw new MoveFailedException(MoveFailedException.Reason.TIMEOUT, "didn't reach " + stopAt + "° in time");
    }

    private long timeForMs(double degrees) {
        double speed = cal.degPerSec > 0 ? cal.degPerSec : 12;  // 30 s/rev worst case
        return (long) (Math.abs(degrees) / speed * 1000);
    }
}
