package org.snurkle.badgeturntable.motion;

import android.util.Log;

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
    private static final String TAG = "Turntable";
    private static final long POLL_MS = 30;
    private static final double MOVED_DEG = 2.0;
    private static final double HOME_TOL_DEG = 2.0;  // matches the spec's positioning tolerance
    private static final long PRESS_GAP_MS = 150;
    /**
     * How long a stopped table may take to come to rest before stop() presses again. The
     * JAYEGT coasts about 0.35 s at its fastest speed (14.6 deg at 42.5 deg/s), and the
     * ring adds ~0.1 s of latency; much longer and a second press would restart it.
     */
    private static final long STOP_CONFIRM_MS = 1200;
    private static final long STILL_WINDOW_MS = 200;
    private static final long SPEED_MEASURE_MS = 1200;
    /** Time for the motor to reach a new speed before measuring it. */
    private static final long SPEED_SETTLE_MS = 800;
    private static final int MAX_SPEED_PRESSES = 4;
    private static final double STILL_DEG = 0.8;

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

    public Calibration calibration() {
        return cal;
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
     * Starts continuous rotation from rest. Prefers TURN_RIGHT: on the JAYEGT it always
     * gives continuous rotation in a known direction, while START_STOP is a toggle whose
     * result depends on the mode (from intermittent it switches to continuous).
     */
    public void start() throws MoveFailedException, InterruptedException {
        press(profile.has(Role.TURN_RIGHT) ? Role.TURN_RIGHT : Role.START_STOP);
    }

    /**
     * Stops a moving table and checks with the ring that it did. IR is one-way and the
     * JAYEGT's receiver faces away from the phone, so a press is sometimes missed (on the
     * test bench, one On/Pause in about six). START_STOP toggles, so it's only pressed again
     * once the table has clearly kept going for longer than any real coast.
     */
    public void stop() throws MoveFailedException, InterruptedException {
        for (int attempt = 1; attempt <= 2; attempt++) {
            press(Role.START_STOP);
            if (awaitStill(STOP_CONFIRM_MS)) return;
            Log.w(TAG, "still turning after stop press " + attempt);
        }
        throw new MoveFailedException(MoveFailedException.Reason.NO_MOVEMENT, "the turntable didn't stop");
    }

    /**
     * Signed rotation rate in deg/s, measured on the ring over windowMs; NaN if the ring
     * gave fewer than two readings.
     */
    public double measureRate(long windowMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + windowMs;
        double travel = 0, last = Double.NaN;
        long firstTs = 0, lastTs = 0;
        while (System.currentTimeMillis() < deadline) {
            RingReading r = ring.get();
            if (r.ok() && r.timestampNs != lastTs) {
                if (Double.isNaN(last)) firstTs = r.timestampNs;
                else travel += RingReading.delta(last, r.angleDeg);
                last = r.angleDeg;
                lastTs = r.timestampNs;
            }
            Thread.sleep(POLL_MS);
        }
        double dt = (lastTs - firstTs) / 1e9;
        return dt > 0.2 ? travel / dt : Double.NaN;
    }

    /**
     * Changes the speed of a turning table to about targetSecondsPerRev (within 20%), with
     * the +Speed / -Speed buttons, checking each press on the ring. The JAYEGT has three
     * speeds (8, 15 and 30 s/rev). Presses can be missed, and whether the buttons stop or
     * wrap at the ends isn't known, so it just keeps pressing towards the target, a few
     * times at most. Returns the seconds per revolution it ended up at.
     */
    public double setSpeed(double targetSecondsPerRev) throws MoveFailedException, InterruptedException {
        double spr = Double.NaN;
        for (int press = 0; ; press++) {
            double r = measureRate(SPEED_MEASURE_MS);
            if (Double.isNaN(r) || Math.abs(r) < 1) {
                throw new MoveFailedException(MoveFailedException.Reason.NO_MOVEMENT, "not turning while setting the speed");
            }
            spr = 360 / Math.abs(r);
            if (Math.abs(spr - targetSecondsPerRev) <= 0.2 * targetSecondsPerRev) break;
            Role button = spr < targetSecondsPerRev ? Role.SPEED_DOWN : Role.SPEED_UP;
            if (press >= MAX_SPEED_PRESSES || !profile.has(button)) break;
            Log.i(TAG, String.format("setSpeed: %.1f s/rev, want %.0f: pressing %s", spr, targetSecondsPerRev, button));
            press(button);
            Thread.sleep(SPEED_SETTLE_MS);
        }
        Log.i(TAG, String.format("setSpeed: at %.1f s/rev (wanted %.0f)", spr, targetSecondsPerRev));
        return spr;
    }

    /**
     * True once two readings STILL_WINDOW_MS apart differ by less than STILL_DEG, within
     * timeoutMs. Readings the ring couldn't make are skipped.
     */
    private boolean awaitStill(long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        double anchor = Double.NaN;
        long anchorAt = 0;
        while (System.currentTimeMillis() < deadline) {
            RingReading r = ring.get();
            long now = System.currentTimeMillis();
            if (r.ok()) {
                if (Double.isNaN(anchor) || Math.abs(RingReading.delta(anchor, r.angleDeg)) > STILL_DEG) {
                    anchor = r.angleDeg;
                    anchorAt = now;
                } else if (now - anchorAt >= STILL_WINDOW_MS) {
                    return true;
                }
            }
            Thread.sleep(POLL_MS);
        }
        return false;
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
                start();
            }
            if (awaitMovement(start)) {
                if (step == null) stopAfter(start, degrees - cal.stopOvershootDeg);
                double end = waitForStable(timeForMs(degrees) + 5000, 0.5, 300);
                if (Double.isNaN(end)) throw new MoveFailedException(MoveFailedException.Reason.RING_LOST, "ring lost during move");
                Log.i(TAG, String.format("moveBy %.2f from %.2f (%s): ended at %.2f, travelled %.2f",
                        degrees, start, step != null ? step : "continuous", end, cal.forward(start, end)));
                return end;
            }
            // Nothing happened: the press was probably missed. Try once more.
        }
        throw new MoveFailedException(MoveFailedException.Reason.NO_MOVEMENT, "no movement after two presses");
    }

    /**
     * Back to the calibrated zero, checked against the ring. Tries RETURN_TO_ORIGIN first
     * if the remote has it. That only works once the table's own origin has been set
     * (SET_ORIGIN), and the table loses it if its battery runs flat. So if it
     * doesn't land on zero, this homes by the ring instead and sets the origin again.
     */
    public double home() throws MoveFailedException, InterruptedException {
        double now = waitForStable(3000, 0.5, 300);
        if (Double.isNaN(now)) throw new MoveFailedException(MoveFailedException.Reason.RING_LOST, "ring not readable");
        if (atZero(now)) return now;

        if (profile.has(Role.RETURN_TO_ORIGIN)) {
            press(Role.RETURN_TO_ORIGIN);
            awaitMovement(now);
            double end = waitForStable(timeForMs(360) + 5000, 0.5, 300);
            if (!Double.isNaN(end) && atZero(end)) return end;
            now = waitForStable(3000, 0.5, 300);
            if (Double.isNaN(now)) throw new MoveFailedException(MoveFailedException.Reason.RING_LOST, "ring lost while homing");
        }

        double togo = cal.forward(now, cal.zeroAngleDeg);
        double end = (togo < 1 || togo > 359) ? now : moveBy(togo);
        if (profile.has(Role.SET_ORIGIN) && atZero(end)) press(Role.SET_ORIGIN);
        return end;
    }

    private boolean atZero(double angle) {
        return Math.abs(RingReading.delta(angle, cal.zeroAngleDeg)) <= HOME_TOL_DEG;
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

    /** Continuous rotation: stop once forward travel reaches {@code stopAt} degrees. */
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
                    long ageMs = (android.os.SystemClock.elapsedRealtimeNanos() - r.timestampNs) / 1_000_000;
                    stop();
                    Log.i(TAG, String.format("stopAfter: target %.2f, stop sent at travel %.2f (reading %d ms old)",
                            stopAt, travelled, ageMs));
                    return;
                }
            }
            Thread.sleep(POLL_MS / 2);
        }
        stop();
        throw new MoveFailedException(MoveFailedException.Reason.TIMEOUT, "didn't reach " + stopAt + "° in time");
    }

    private long timeForMs(double degrees) {
        double speed = cal.degPerSec > 0 ? cal.degPerSec : 12;  // 30 s/rev worst case
        return (long) (Math.abs(degrees) / speed * 1000);
    }
}
