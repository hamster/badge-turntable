package org.snurkle.badgeturntable.capture;

import android.content.Context;
import android.util.Log;

import org.snurkle.badgeturntable.camera.CameraController;
import org.snurkle.badgeturntable.media.AngleLog;
import org.snurkle.badgeturntable.media.SpinTranscoder;
import org.snurkle.badgeturntable.media.WebImages;
import org.snurkle.badgeturntable.motion.MoveFailedException;
import org.snurkle.badgeturntable.motion.TurntableController;
import org.snurkle.badgeturntable.ir.TurntableProfile.Role;
import org.snurkle.badgeturntable.ring.RingDetector;
import org.snurkle.badgeturntable.ring.RingReading;
import org.snurkle.badgeturntable.session.Session;
import org.snurkle.badgeturntable.session.SessionStore;
import org.snurkle.badgeturntable.session.Slugs;
import org.snurkle.badgeturntable.sync.SyncWorker;

import java.io.File;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Runs one capture on a background thread: the four views (auto or manual), the
 * optional spin video, then index.md, homing and the sync queue. The UI answers
 * questions (move failed, manual positioning) by completing futures handed to
 * the {@link Listener}. Prechecks (calibration, Samba) happen before run() is called.
 */
public final class CaptureSequencer {
    private static final String TAG = "CaptureSequencer";

    public enum Decision { RETRY, MANUAL, STOP }

    public interface Listener {
        void onStatus(String text);

        /** The table didn't move or the ring was lost; ask what to do. */
        CompletableFuture<Decision> onMoveFailed(View view, MoveFailedException e);

        /** Manual mode: show the instruction (and dial, if targetAngle isn't NaN); complete on Capture. */
        CompletableFuture<Void> awaitManualPosition(String instruction, double targetRingAngle);

        void onFinished(Session session, boolean complete);

        void onError(Exception e);
    }

    public static final class Options {
        public boolean auto = true;
        public boolean video = true;
        public String setLabel;           // null for the main set
        public long manualDelayMs = 2000;
        public double videoOverlapDeg = 15;
    }

    private final Context context;
    private final CameraController camera;
    private final RingDetector ring;
    private final TurntableController table;  // null when the profile has no remote
    private final SessionStore store;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    public CaptureSequencer(Context context, CameraController camera, RingDetector ring,
                            TurntableController table, SessionStore store) {
        this.context = context.getApplicationContext();
        this.camera = camera;
        this.ring = ring;
        this.table = table;
        this.store = store;
    }

    public void run(Session s, Options o, Listener l) {
        worker.execute(() -> {
            boolean complete = false;
            try {
                complete = runViews(s, o, l);
                if (complete && o.video) recordSpin(s, o, l);
                store.writeIndex(s);
                if (s.syncToSamba) {
                    s.syncState = Session.SyncState.PENDING;
                    SyncWorker.enqueue(context);
                }
                store.save(s);
                if (o.auto && table != null) {
                    l.onStatus("Returning to zero…");
                    table.home();
                }
                l.onFinished(s, complete);
            } catch (Exception e) {
                Log.e(TAG, "capture failed", e);
                l.onError(e);
            } finally {
                camera.unlock3A();
            }
        });
    }

    /** @return true if all four views were captured */
    private boolean runViews(Session s, Options o, Listener l) throws Exception {
        String prefix = Slugs.setPrefix(o.setLabel);
        boolean auto = o.auto && table != null;
        camera.lock3A();
        Thread.sleep(800);  // let the locks settle

        RingReading startReading = ring.latest();
        double start = startReading.ok() ? startReading.angleDeg : Double.NaN;

        for (View view : View.values()) {
            double target = Double.isNaN(start) ? Double.NaN : (start + view.angle) % 360;
            if (view != View.FRONT) {
                if (auto) {
                    Decision d = moveWithRetry(view, l);
                    if (d == Decision.STOP) return false;
                    if (d == Decision.MANUAL) auto = false;
                }
                if (!auto) {
                    l.awaitManualPosition("Turn the table to " + view.angle + "° (" + view.caption + ")", target).get();
                    Thread.sleep(o.manualDelayMs);
                    if (table != null) table.waitForStable(3000, 0.5, 300);
                }
            }
            l.onStatus("Capturing " + view.caption + "…");
            captureStill(s, view.filename(prefix), view.caption(o.setLabel), view.angle, o.setLabel);
        }
        return true;
    }

    private Decision moveWithRetry(View view, Listener l) throws Exception {
        while (true) {
            try {
                l.onStatus("Turning to " + view.angle + "°…");
                table.moveBy(90);
                return Decision.RETRY;  // moved fine; carry on
            } catch (MoveFailedException e) {
                Decision d = l.onMoveFailed(view, e).get();
                if (d != Decision.RETRY) return d;
            }
        }
    }

    private void captureStill(Session s, String filename, String caption, double nominal, String setLabel) throws Exception {
        File original = new File(store.originalsDir(s), filename);
        mkdirs(original.getParentFile());
        camera.takePicture(original);
        WebImages.writeWebCopy(original, new File(store.badgeDir(s), filename));
        Session.Media m = new Session.Media(filename, caption, Session.Media.Kind.IMAGE);
        m.angleNominal = nominal;
        RingReading r = ring.latest();
        if (r.ok()) m.angleActual = r.angleDeg;
        m.setLabel = setLabel;
        s.put(m);
        store.save(s);
    }

    private void recordSpin(Session s, Options o, Listener l) throws Exception {
        String prefix = Slugs.setPrefix(o.setLabel);
        File source = new File(store.originalsDir(s), prefix + "spin-source.mp4");
        File web = new File(store.badgeDir(s), prefix + "spin.mp4");
        mkdirs(source.getParentFile());

        AngleLog log = new AngleLog();
        RingDetector.Listener logger = r -> {
            if (r.ok()) log.add(r.timestampNs, r.angleDeg);
        };
        ring.addListener(logger);
        l.onStatus("Recording the spin…");
        long recStartNs = camera.startRecording(source);
        try {
            if (o.auto && table != null) {
                table.start();
                long deadline = System.currentTimeMillis() + 120_000;
                while (log.travelled() < 360 + o.videoOverlapDeg && System.currentTimeMillis() < deadline) {
                    Thread.sleep(50);
                }
                table.stop();
            } else {
                l.awaitManualPosition("Start the table spinning, then tap Capture when it has gone all the way round", Double.NaN).get();
            }
        } finally {
            camera.stopRecording();
            ring.removeListener(logger);
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
        l.onStatus("Encoding the spin video…");
        SpinTranscoder.transcode(context, source, web, startUs, endUs);
        Session.Media m = new Session.Media(web.getName(), o.setLabel == null ? "" : o.setLabel, Session.Media.Kind.VIDEO);
        m.setLabel = o.setLabel;
        s.put(m);
    }

    /** A single extra still (lanyard, add-ons, detail…). The table doesn't move. */
    public void captureExtra(Session s, String label, long delayMs, Listener l) {
        worker.execute(() -> {
            try {
                Set<String> taken = new HashSet<>();
                for (Session.Media m : s.media) taken.add(m.filename);
                String name = Slugs.extraShotName(label, taken);
                l.onStatus("Capturing " + label + "…");
                Thread.sleep(delayMs);
                captureStill(s, name, label, Double.NaN, null);
                store.writeIndex(s);
                if (s.syncToSamba) {
                    s.syncState = Session.SyncState.PENDING;
                    SyncWorker.enqueue(context);
                }
                store.save(s);
                l.onFinished(s, true);
            } catch (Exception e) {
                l.onError(e);
            }
        });
    }

    public void shutdown() {
        worker.shutdownNow();
    }

    private static void mkdirs(File dir) throws java.io.IOException {
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new java.io.IOException("can't create " + dir);
    }
}
