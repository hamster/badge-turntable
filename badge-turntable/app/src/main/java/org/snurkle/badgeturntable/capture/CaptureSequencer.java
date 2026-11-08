package org.snurkle.badgeturntable.capture;

import android.content.Context;
import android.util.Log;
import android.util.Range;

import androidx.exifinterface.media.ExifInterface;

import org.snurkle.badgeturntable.camera.CameraController;
import org.snurkle.badgeturntable.media.AngleLog;
import org.snurkle.badgeturntable.media.SideBars;
import org.snurkle.badgeturntable.media.SpinTranscoder;
import org.snurkle.badgeturntable.media.WebImages;
import org.snurkle.badgeturntable.motion.Calibration;
import org.snurkle.badgeturntable.motion.MoveFailedException;
import org.snurkle.badgeturntable.motion.TurntableController;
import org.snurkle.badgeturntable.ring.RingDetector;
import org.snurkle.badgeturntable.ring.RingReading;
import org.snurkle.badgeturntable.session.Session;
import org.snurkle.badgeturntable.session.Slugs;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.DoubleSupplier;

/**
 * Runs one capture: the four views (auto or manual), the optional spin video, then homing.
 * Blocking: it runs on the job thread, and an interrupt (Stop) ends it, stopping the table
 * and any recording and keeping what was captured. Questions (move failed, manual
 * positioning) go to the {@link Listener}, which blocks until the phone or the editor answers.
 * Prechecks (calibration, storage) happen before capture() is called.
 */
public final class CaptureSequencer {
    private static final String TAG = "CaptureSequencer";

    public enum Decision { RETRY, MANUAL, STOP }

    public interface Listener {
        /** Progress text, keeping the current step. */
        void onStatus(String text);

        /** A new step: one per view, then the spin, then homing. */
        void onStep(int step, int of, String text);

        /**
         * The table didn't move or the ring was lost; ask what to do.
         * @param viewName   the shot's file stem ("side-090", "with-saos-side-090")
         * @param targetRing the ring angle the view needed, or NaN
         * @param manualOffered whether switching to manual is an option here
         */
        Decision onMoveFailed(String viewName, View view, double targetRing, boolean manualOffered,
                              MoveFailedException e) throws InterruptedException;

        /**
         * Manual mode: show the instruction (and dial, if targetRing isn't NaN). Returns true
         * when the table is in place, false to stop.
         * @param view null for the spin
         */
        boolean awaitManualPosition(String viewName, View view, String instruction, double targetRing)
                throws InterruptedException;

        /** Before a manual still or an extra shot, once a second. 0 when it's over. */
        default void onCountdown(long remainingMs) {}
    }

    public static final class Options {
        public boolean auto = true;
        /** The four views. Without them (and with video) it's a spin-only capture. */
        public boolean stills = true;
        public boolean video = true;
        public String setLabel;           // null for the main set
        public long manualDelayMs = 2000;
        public double videoOverlapDeg = 15;
        /**
         * Spin instead of stop-and-shoot: the table turns continuously, the four stills are
         * taken as the ring passes each angle (photo spin), then the video is recorded on
         * the next revolution(s). Needs the ring, but no IR: without a remote the user starts
         * the table.
         */
        public boolean spin = true;
        /** Speeds the app sets with the remote's speed buttons, when it has them. */
        public double photoSecondsPerRev = 30;
        public double videoSecondsPerRev = 15;
        /**
         * The ring angle where the badge faces the camera (Set front), or NaN to use wherever
         * the table is when the capture starts.
         */
        public double frontRingDeg = Double.NaN;
    }

    /** Closer than this to the front counts as there: the positioning tolerance. */
    private static final double FRONT_TOLERANCE_DEG = 2.0;

    /**
     * Most motion blur allowed in a photo-spin still, at the edge of a large badge (60 mm
     * from the axis). 0.05 mm is under a pixel at typical framing (~8 px/mm on a 4000 px
     * still). At 30 s/rev it allows 1/250 s; at 8 s/rev, about 1/900 s.
     */
    private static final double BLUR_BUDGET_MM = 0.05;
    private static final double BADGE_RADIUS_MM = 60;
    /** Above this the stills get noisy and the ring reads fewer markers; underexpose instead. */
    private static final int MAX_SPIN_ISO = 1600;
    /** Targets closer than this when the spin starts are taken on the next revolution. */
    private static final double MIN_LEAD_DEG = 20;
    private static final long FIRST_LAG_GUESS_NS = 150_000_000L;

    private final Context context;
    private final CameraController camera;
    private final RingDetector ring;
    private final TurntableController table;  // null when the profile has no remote
    /**
     * The side bars for the capture running, taken when it starts so every view and the spin
     * match. NONE otherwise: extra shots don't get them (they may be reframed or hand-held).
     */
    private volatile SideBars bars = SideBars.NONE;

    public CaptureSequencer(Context context, CameraController camera, RingDetector ring, TurntableController table) {
        this.context = context.getApplicationContext();
        this.camera = camera;
        this.ring = ring;
        this.table = table;
    }

    /** Number of steps a capture with these options reports. */
    public int stepCount(Options o) {
        return (o.stills ? 4 : 0) + (o.video ? 1 : 0) + (o.auto && table != null ? 1 : 0);
    }

    /** The spin video's step number: after the four views, or first in a spin-only capture. */
    private static int videoStep(Options o) {
        return o.stills ? 5 : 1;
    }

    /** @return true if every view asked for (and the spin, if asked for) was captured */
    public boolean capture(CaptureTarget t, Options o, Listener l) throws Exception {
        boolean drive = o.auto && table != null;
        bars = camera.settings().bars();
        try {
            boolean complete;
            if (!o.stills) {
                complete = !o.video || runSpinOnly(t, o, l);
            } else if (o.spin) {
                complete = runSpin(t, o, l);
            } else {
                complete = runViews(t, o, l);
                if (complete && o.video) complete = recordSpin(t, o, l, drive, false, null);
            }
            t.finish();
            if (drive) {
                l.onStep(stepCount(o), stepCount(o), "Returning to zero…");
                table.home();
            }
            return complete;
        } catch (InterruptedException e) {
            // Stopped: keep what was captured and send the table home.
            clearInterrupt();
            try {
                t.finish();
            } catch (IOException io) {
                Log.w(TAG, "saving after stop failed", io);
            }
            if (drive) {
                try {
                    l.onStatus("Stopping: returning to zero…");
                    table.home();
                } catch (Exception home) {
                    Log.w(TAG, "homing after stop failed", home);
                }
            }
            throw e;
        } finally {
            bars = SideBars.NONE;
            camera.unlock3A();
        }
    }

    /** @return true if all four views were captured */
    private boolean runViews(CaptureTarget t, Options o, Listener l) throws Exception {
        String prefix = Slugs.setPrefix(o.setLabel);
        boolean auto = o.auto && table != null;
        Calibration cal = table == null ? null : table.calibration();
        double sign = cal != null ? cal.directionSign : 1;
        int of = stepCount(o);
        camera.lock3A();
        Thread.sleep(800);  // let the locks settle

        RingReading startReading = ring.latest();
        double start = startReading.ok() ? startReading.angleDeg : Double.NaN;
        if (!Double.isNaN(o.frontRingDeg)) {
            Decision d = turnToFront(prefix + View.FRONT.baseName, start, auto, of, o, l);
            if (d == Decision.STOP) return false;
            if (d == Decision.MANUAL) auto = false;
            start = o.frontRingDeg;
        }

        int step = 0;
        for (View view : View.values()) {
            step++;
            String name = prefix + view.baseName;
            double target = Double.isNaN(start) ? Double.NaN : norm(start + sign * view.angle);
            if (view != View.FRONT) {
                if (auto) {
                    l.onStep(step, of, "Turning to " + view.angle + "°…");
                    Decision d = moveWithRetry(name, view, target, () -> 90, l);
                    if (d == Decision.STOP) return false;
                    if (d == Decision.MANUAL) auto = false;
                }
                if (!auto) {
                    String turn = "Turn the table to " + view.angle + "°, then tap Ready";
                    l.onStep(step, of, turn);
                    boolean ready = l.awaitManualPosition(name, view, turn, target);
                    if (!ready) return false;
                    countdown(o.manualDelayMs, l);
                    if (table != null) table.waitForStable(3000, 0.5, 300);
                }
            }
            l.onStep(step, of, "Capturing " + view.caption + "…");
            File original = t.scratchFile(view.filename(prefix));
            takeStill(original);
            RingReading r = ring.latest();
            double actual = r.ok() && !Double.isNaN(start) ? fromFront(view.angle, r.angleDeg, start, sign) : Double.NaN;
            record(t, original, view.filename(prefix), view.caption(o.setLabel), view.angle, actual, o.setLabel);
        }
        return true;
    }

    /**
     * A front was set (Set front): turns there first if the table is more than the tolerance
     * away (auto), or asks the user to (manual).
     * @param start the ring angle now, or NaN if it can't be read
     * @return RETRY to carry on as asked, MANUAL to carry on by hand, STOP to stop
     */
    private Decision turnToFront(String name, double start, boolean auto, int of, Options o, Listener l)
            throws Exception {
        double front = o.frontRingDeg;
        if (!Double.isNaN(start) && Math.abs(RingReading.delta(start, front)) <= FRONT_TOLERANCE_DEG) {
            return Decision.RETRY;
        }
        Decision result = Decision.RETRY;
        boolean byHand = !auto;
        if (auto) {
            Calibration cal = table.calibration();
            l.onStep(1, of, "Turning to the front…");
            Decision d = moveWithRetry(name, View.FRONT, front, () -> {
                RingReading now = ring.latest();
                return now.ok() ? cal.forward(now.angleDeg, front) : 0;
            }, l);
            if (d == Decision.STOP) return d;
            if (d == Decision.MANUAL) {
                byHand = true;
                result = Decision.MANUAL;
            }
        }
        if (byHand) {
            String turn = "Turn the table to the front, then tap Ready";
            l.onStep(1, of, turn);
            if (!l.awaitManualPosition(name, View.FRONT, turn, front)) return Decision.STOP;
            countdown(o.manualDelayMs, l);
            if (table != null) table.waitForStable(3000, 0.5, 300);
        }
        return result;
    }

    /**
     * Spin only (no stills): turn to the front if one is set, then record the spin, the
     * same way as after stop-and-shoot views.
     * @return false if the user stopped it
     */
    private boolean runSpinOnly(CaptureTarget t, Options o, Listener l) throws Exception {
        camera.lock3A();
        Thread.sleep(800);  // let the locks settle
        boolean drive = o.auto && table != null;
        if (!Double.isNaN(o.frontRingDeg)) {
            RingReading now = ring.latest();
            Decision d = turnToFront(Slugs.setPrefix(o.setLabel) + "spin", now.ok() ? now.angleDeg : Double.NaN,
                    drive, stepCount(o), o, l);
            if (d == Decision.STOP) return false;
            if (d == Decision.MANUAL) drive = false;  // carry on by hand: the user starts the spin too
        }
        if (!drive) return recordSpin(t, o, l, false, false, null);

        // As after a photo spin: get the table turning at the video speed, then record a turn.
        l.onStep(videoStep(o), stepCount(o), "Recording the spin…");
        l.onStatus("Starting the turntable…");
        table.start();
        boolean[] turning = {true};
        Runnable stopTurning = () -> {
            if (!turning[0]) return;
            turning[0] = false;
            try {
                table.stop();
            } catch (Exception e) {
                Log.w(TAG, "stop after the spin failed", e);
            }
        };
        try {
            l.onStatus("Setting the speed for the video…");
            table.setSpeed(o.videoSecondsPerRev);
            return recordSpin(t, o, l, true, true, stopTurning);
        } finally {
            boolean interrupted = clearInterrupt();  // so stopping the table isn't cut short
            stopTurning.run();
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    /** Turns forward by degrees (worked out again on each retry), asking what to do if it fails. */
    private Decision moveWithRetry(String name, View view, double target, DoubleSupplier degrees, Listener l)
            throws Exception {
        while (true) {
            try {
                double d = degrees.getAsDouble();
                if (d >= 1 && d <= 359) table.moveBy(d);
                return Decision.RETRY;  // moved fine; carry on
            } catch (MoveFailedException e) {
                Decision d = l.onMoveFailed(name, view, target, true, e);
                if (d != Decision.RETRY) return d;
            }
        }
    }

    /** A view's measured angle from the front, close to its nominal angle (so the front is about 0, not 359.5). */
    private static double fromFront(double nominal, double ringAngle, double front, double sign) {
        double rel = norm(sign * (ringAngle - front));
        return nominal + RingReading.delta(nominal, rel);
    }

    /**
     * Photo spin, then video spin. The front is the one set with Set front, or else wherever
     * the badge faces when Capture is pressed; the other views follow in the direction the
     * table turns.
     * @return true if all four views (and the video, if asked for) were captured
     */
    private boolean runSpin(CaptureTarget t, Options o, Listener l) throws Exception {
        String prefix = Slugs.setPrefix(o.setLabel);
        boolean drive = o.auto && table != null;
        int of = stepCount(o);
        RingHistory history = new RingHistory();
        ring.addListener(history);
        boolean[] turning = {false};
        Runnable stopTurning = () -> {
            if (!turning[0]) return;
            turning[0] = false;
            if (drive) {
                try {
                    table.stop();
                } catch (Exception e) {
                    Log.w(TAG, "stop after the spin failed", e);
                }
            } else {
                l.onStatus("Done: you can stop the turntable");
            }
        };
        try {
            l.onStep(1, of, "Getting ready to spin…");
            // Lock first, while the preview shows what the user framed: the rebind for flying
            // stills starts AE over, and the lock carries the exposure across it.
            camera.lock3A();
            camera.setFlyingStills(true);
            Thread.sleep(800);  // let the locks settle
            double now = history.waitForStill(3000, 0.5, 300);
            if (Double.isNaN(now)) {
                throw new MoveFailedException(MoveFailedException.Reason.RING_LOST, "ring not readable before the spin");
            }
            double front = Double.isNaN(o.frontRingDeg) ? now : o.frontRingDeg;

            // 1. Get the table turning, slowly.
            if (drive) {
                l.onStatus("Starting the turntable…");
                table.start();
            } else {
                l.onStatus("Start the turntable (slowest speed is sharpest)");
            }
            turning[0] = true;
            double rate = history.waitForSteadyRotation(1500, drive ? 8000 : 120_000);
            if (drive) {
                l.onStatus("Slowing down for the photos…");
                table.setSpeed(o.photoSecondsPerRev);
                rate = history.waitForSteadyRotation(1000, 8000);
            }
            double sign = Math.signum(rate);
            long shutterNs = spinExposure(Math.abs(rate));

            // 2. Photo spin: each view as it comes round.
            List<View> order = arrivalOrder(front, sign, ring.latest().angleDeg);
            long lagNs = FIRST_LAG_GUESS_NS;
            long perShotTimeoutMs = (long) (1.3 * 360 / Math.abs(rate) * 1000) + 5000;
            int step = 0;
            for (View view : order) {
                step++;
                double target = norm(front + sign * view.angle);
                CameraController.TimedStill still;
                long triggerNs;
                while (true) {
                    try {
                        l.onStep(step, of, "Capturing " + view.caption + " on the move…");
                        triggerNs = history.waitToFire(target, sign, lagNs, perShotTimeoutMs);
                        still = camera.captureNow().get(5, TimeUnit.SECONDS);
                        break;
                    } catch (MoveFailedException e) {
                        Decision d = l.onMoveFailed(prefix + view.baseName, view, target, false, e);
                        if (d != Decision.RETRY) return false;
                        if (drive) table.start();
                        history.waitForSteadyRotation(1500, drive ? 8000 : 120_000);
                    }
                }
                long lag = still.sensorTimestampNs - triggerNs;
                if (lag > 0 && lag < 1_000_000_000L) lagNs = lag;
                double actualRing = history.angleAt(still.sensorTimestampNs + shutterNs / 2, 1000);
                double actual = Double.isNaN(actualRing) ? Double.NaN : fromFront(view.angle, actualRing, front, sign);
                saveStill(t, view.filename(prefix), view.caption(o.setLabel), view.angle, actual, o.setLabel, still);
                Log.i(TAG, String.format(Locale.ROOT, "%s: target %.1f, at %.2f (%+.2f), lag %d ms",
                        view.baseName, target, actualRing, RingReading.delta(target, actualRing), lag / 1_000_000));
            }

            // 3. Video spin, faster, while it keeps turning.
            if (o.video) {
                l.onStep(videoStep(o), of, "Recording the spin…");
                if (drive) {
                    l.onStatus("Speeding up for the video…");
                    table.setSpeed(o.videoSecondsPerRev);
                }
                return recordSpin(t, o, l, drive, true, stopTurning);
            }
            return true;
        } finally {
            boolean interrupted = clearInterrupt();  // so stopping the table isn't cut short
            ring.removeListener(history);
            stopTurning.run();
            camera.setFlyingStills(false);
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    /** The four views in the order they'll come round, skipping past any that are too close. */
    private static List<View> arrivalOrder(double front, double sign, double now) {
        List<View> views = new ArrayList<>(Arrays.asList(View.values()));
        double base = Double.isNaN(now) ? front : now;
        views.sort((a, b) -> Double.compare(lead(base, norm(front + sign * a.angle), sign),
                lead(base, norm(front + sign * b.angle), sign)));
        return views;
    }

    /** Degrees still to turn (in the direction of travel) from now to target, at least MIN_LEAD_DEG. */
    private static double lead(double now, double target, double sign) {
        double d = norm(sign * (target - now));
        return d < MIN_LEAD_DEG ? d + 360 : d;
    }

    private static double norm(double a) {
        return (a % 360 + 360) % 360;
    }

    /**
     * Exposure for stills on the move: the blur budget sets the longest shutter at this
     * speed. If auto exposure is already at least that fast, or the light box profile sets
     * exposure by hand, it's left alone. Otherwise the shutter is shortened and ISO raised
     * to match, up to MAX_SPIN_ISO. Returns the shutter time in use, for the angle maths.
     */
    private long spinExposure(double degPerSec) {
        long maxNs = (long) (1e9 * BLUR_BUDGET_MM / (Math.toRadians(degPerSec) * BADGE_RADIUS_MM));
        long autoNs = camera.autoExposureNs();
        int autoIso = camera.autoIso();
        Range<Integer> isoRange = camera.isoRange();
        if (camera.hasManualExposure() || autoNs <= 0 || autoIso <= 0 || isoRange == null || autoNs <= maxNs) {
            Log.i(TAG, String.format(Locale.ROOT, "spin exposure: keeping 1/%.0f s at ISO %d (limit 1/%.0f s)",
                    1e9 / Math.max(autoNs, 1), autoIso, 1e9 / maxNs));
            return autoNs > 0 ? autoNs : maxNs;
        }
        double want = autoIso * (double) autoNs / maxNs;
        int iso = (int) Math.round(Math.max(isoRange.getLower(), Math.min(Math.min(MAX_SPIN_ISO, isoRange.getUpper()), want)));
        camera.overrideExposure(iso, maxNs);
        Log.i(TAG, String.format(Locale.ROOT, "spin exposure: 1/%.0f s ISO %d -> 1/%.0f s ISO %d%s",
                1e9 / autoNs, autoIso, 1e9 / maxNs, iso,
                want > iso * 1.1 ? String.format(Locale.ROOT, " (%.1f stops dark)", Math.log(want / iso) / Math.log(2)) : ""));
        return maxNs;
    }

    /** Takes a still straight to the original's file. */
    private void takeStill(File original) throws Exception {
        mkdirs(original.getParentFile());
        camera.takePicture(original);
    }

    /** Writes a still taken in memory (original plus web copy) and records it. */
    private void saveStill(CaptureTarget t, String filename, String caption, double nominal, double actual,
                           String setLabel, CameraController.TimedStill still) throws Exception {
        File original = t.scratchFile(filename);
        mkdirs(original.getParentFile());
        try (FileOutputStream out = new FileOutputStream(original)) {
            out.write(still.jpeg);
        }
        if (still.rotationDegrees != 0) {
            ExifInterface exif = new ExifInterface(original.getPath());
            exif.setAttribute(ExifInterface.TAG_ORIENTATION, String.valueOf(exifOrientation(still.rotationDegrees)));
            exif.saveAttributes();
        }
        record(t, original, filename, caption, nominal, actual, setLabel);
    }

    /**
     * Makes the web copy of a full-size still, deletes the full-size file (only web copies are
     * kept) and records the still. Angles are from the front, NaN if unknown.
     */
    private void record(CaptureTarget t, File original, String filename, String caption, double nominal,
                        double actual, String setLabel) throws IOException {
        File web = t.webFile(filename);
        mkdirs(web.getParentFile());
        try {
            WebImages.writeWebCopy(original, web, bars);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            original.delete();
        }
        Session.Media m = new Session.Media(filename, caption, Session.Media.Kind.IMAGE);
        m.angleNominal = nominal;
        m.angleActual = actual;
        m.setLabel = setLabel;
        t.add(m);
    }

    private static int exifOrientation(int degrees) {
        switch (degrees) {
            case 90: return ExifInterface.ORIENTATION_ROTATE_90;
            case 180: return ExifInterface.ORIENTATION_ROTATE_180;
            case 270: return ExifInterface.ORIENTATION_ROTATE_270;
            default: return ExifInterface.ORIENTATION_NORMAL;
        }
    }

    /**
     * Records the spin video. turning: the table is already spinning (spin mode), so this
     * just records 360 deg plus the overlap; otherwise it starts the table (drive) or asks
     * the user to. afterRecording runs once the recording stops (to stop the table before
     * encoding).
     * @return false if the user stopped it
     */
    private boolean recordSpin(CaptureTarget t, Options o, Listener l, boolean drive, boolean turning,
                               Runnable afterRecording)
            throws Exception {
        String prefix = Slugs.setPrefix(o.setLabel);
        if (!turning) l.onStep(videoStep(o), stepCount(o), "Recording the spin…");
        File source = t.scratchFile(prefix + "spin-source.mp4");
        File web = t.webFile(prefix + "spin.mp4");
        mkdirs(source.getParentFile());
        mkdirs(web.getParentFile());

        AngleLog log = new AngleLog();
        RingDetector.Listener logger = r -> {
            if (r.ok()) log.add(r.timestampNs, r.angleDeg);
        };
        long recStartNs = camera.startRecording(source);
        // Log from the start of the recording, so the trimmed revolution lies inside the clip.
        ring.addListener(logger);
        boolean keep = true;
        try {
            if (turning) {
                waitForTurn(log, o);
            } else if (drive) {
                table.start();
                waitForTurn(log, o);
                table.stop();
            } else {
                keep = l.awaitManualPosition("spin", null,
                        "Spin the table, then tap Ready after a full turn", Double.NaN);
            }
        } finally {
            boolean interrupted = clearInterrupt();
            camera.stopRecording();
            ring.removeListener(logger);
            if (afterRecording != null) afterRecording.run();  // e.g. stop the table before encoding
            if (interrupted) Thread.currentThread().interrupt();
        }
        if (!keep) {
            //noinspection ResultOfMethodCallIgnored
            source.delete();
            return false;
        }

        long[] rev = log.oneRevolution();
        long startUs, endUs;
        if (rev != null) {
            startUs = Math.max(0, (rev[0] - recStartNs) / 1000);
            endUs = (rev[1] - recStartNs) / 1000;
        } else {
            // No ring: keep the whole clip, capped at 30 s.
            startUs = 0;
            endUs = 30_000_000;
        }
        Session.Media m = new Session.Media(web.getName(), o.setLabel == null ? "" : o.setLabel, Session.Media.Kind.VIDEO);
        m.setLabel = o.setLabel;
        t.encoding(m);
        l.onStatus("Encoding the spin video…");
        try {
            SpinTranscoder.transcode(context, source, web, startUs, endUs, bars);
            t.add(m);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            source.delete();  // only the web copy is kept
        }
        return true;
    }

    private static void waitForTurn(AngleLog log, Options o) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 120_000;
        while (log.travelled() < 360 + o.videoOverlapDeg && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
    }

    /** A single extra still (lanyard, add-ons, detail…). The table doesn't move. */
    public void captureExtra(CaptureTarget t, String label, long delayMs, Listener l) throws Exception {
        String name = Slugs.extraShotName(label, t.filenames());
        l.onStep(1, 1, "Capturing " + label + "…");
        countdown(delayMs, l);
        l.onStatus("Capturing " + label + "…");
        File original = t.scratchFile(name);
        takeStill(original);
        record(t, original, name, label, Double.NaN, Double.NaN, null);
        t.finish();
    }

    private static void countdown(long ms, Listener l) throws InterruptedException {
        for (long left = ms; left > 0; left -= 1000) {
            l.onCountdown(left);
            l.onStatus(String.format(Locale.ROOT, "Capturing in %d…", (left + 999) / 1000));
            Thread.sleep(Math.min(1000, left));
        }
        l.onCountdown(0);
    }

    /** Clears this thread's interrupt flag; returns whether it was set. */
    private static boolean clearInterrupt() {
        return Thread.interrupted();
    }

    private static void mkdirs(File dir) throws IOException {
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new IOException("can't create " + dir);
    }
}
