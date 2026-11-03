package org.snurkle.badgeturntable.ui;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;

import org.snurkle.badgeturntable.R;
import org.snurkle.badgeturntable.camera.CameraController;
import org.snurkle.badgeturntable.capture.CaptureSequencer;
import org.snurkle.badgeturntable.capture.View;
import org.snurkle.badgeturntable.ir.IrTransmitter;
import org.snurkle.badgeturntable.ir.Presets;
import org.snurkle.badgeturntable.ir.TurntableProfile;
import org.snurkle.badgeturntable.motion.Calibration;
import org.snurkle.badgeturntable.motion.Calibrator;
import org.snurkle.badgeturntable.motion.MoveFailedException;
import org.snurkle.badgeturntable.motion.TurntableController;
import org.snurkle.badgeturntable.ring.RingConfig;
import org.snurkle.badgeturntable.ring.RingDetector;
import org.snurkle.badgeturntable.ring.RingReading;
import org.snurkle.badgeturntable.session.Session;
import org.snurkle.badgeturntable.session.SessionStore;
import org.snurkle.badgeturntable.sync.SmbClient;
import org.snurkle.badgeturntable.sync.SmbSettings;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Live preview, camera controls, turntable status and the Capture / Extra shot /
 * Calibrate buttons. Prechecks happen here; the sequence itself runs in
 * {@link CaptureSequencer}.
 */
public final class CaptureActivity extends AppCompatActivity implements CaptureSequencer.Listener {
    private static final long MIN_FREE_BYTES = 500L * 1024 * 1024;
    private static final String[] EXTRA_LABELS = {"Lanyard", "Add-ons installed", "Detail", "Lit up", "Packaging"};

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private SessionStore store;
    private Session session;
    private SharedPreferences prefs;

    private CameraController camera;
    private RingDetector ring;
    private IrTransmitter ir;
    private TurntableProfile profile;
    private TurntableController table;
    private CaptureSequencer sequencer;

    private PreviewView preview;
    private TextView status, ringText;
    private MaterialSwitch autoSwitch;
    private Button captureButton;
    private volatile CompletableFuture<Void> pendingManual;
    private long lastRingUiMs;
    private boolean running;

    private final ActivityResultLauncher<String> cameraPermission =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) startCamera();
                else {
                    Toast.makeText(this, R.string.camera_permission_needed, Toast.LENGTH_LONG).show();
                    finish();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_capture);
        store = new SessionStore(this);
        prefs = PreferenceManager.getDefaultSharedPreferences(this);

        preview = findViewById(R.id.preview);
        status = findViewById(R.id.status);
        ringText = findViewById(R.id.ring);
        autoSwitch = findViewById(R.id.auto);
        captureButton = findViewById(R.id.capture);

        ir = new IrTransmitter(this);
        profile = Presets.byId(this, prefs.getString("turntable_model", Presets.JAYEGT_574));
        int markers = parseInt(prefs.getString("ring_markers", "36"), 36);
        ring = new RingDetector(new RingConfig(markers, false));
        ring.addListener(this::showRing);
        Calibration cal = Calibration.load(this);
        if (profile.supportsAuto() && ir.available()) {
            table = new TurntableController(ir, profile, ring::latest, cal == null ? new Calibration() : cal);
        }
        camera = new CameraController(this);
        sequencer = new CaptureSequencer(this, camera, ring, table, store);

        captureButton.setOnClickListener(v -> onCapture());
        findViewById(R.id.extra).setOnClickListener(v -> onExtra());
        findViewById(R.id.calibrate).setOnClickListener(v -> onCalibrate());
        findViewById(R.id.ev_minus).setOnClickListener(v -> camera.setExposureCompensation(camera.exposureCompensation() - 1));
        findViewById(R.id.ev_plus).setOnClickListener(v -> camera.setExposureCompensation(camera.exposureCompensation() + 1));
        findViewById(R.id.wb).setOnClickListener(v -> cycleWhiteBalance());
        preview.setOnTouchListener(this::onPreviewTouch);

        String id = getIntent().getStringExtra(SessionEditActivity.EXTRA_ID);
        io.execute(() -> {
            Session s = store.get(id);
            runOnUiThread(() -> {
                if (s == null) {
                    finish();
                    return;
                }
                session = s;
                setTitle(s.title);
                autoSwitch.setChecked(s.autoMode && table != null);
                autoSwitch.setEnabled(table != null);
                if (table == null) status.setText(R.string.no_remote);
            });
        });

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA);
        }
    }

    private void startCamera() {
        camera.start(this, preview, ring);
    }

    @SuppressLint("ClickableViewAccessibility")
    private boolean onPreviewTouch(android.view.View v, MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_UP) camera.focusAt(e.getX(), e.getY());
        return true;
    }

    private void cycleWhiteBalance() {
        CameraController.WhiteBalance[] all = CameraController.WhiteBalance.values();
        CameraController.WhiteBalance next = all[(camera.whiteBalance().ordinal() + 1) % all.length];
        camera.setWhiteBalance(next);
        ((Button) findViewById(R.id.wb)).setText(getString(R.string.wb_label, next.name().toLowerCase()));
    }

    private void showRing(RingReading r) {
        long now = System.currentTimeMillis();
        if (now - lastRingUiMs < 200) return;
        lastRingUiMs = now;
        String text = r.ok()
                ? getString(R.string.ring_reading, r.angleDeg, r.markers)
                : getString(R.string.ring_not_visible);
        runOnUiThread(() -> ringText.setText(text));
    }

    // ---- Capture -----------------------------------------------------------

    private void onCapture() {
        CompletableFuture<Void> manual = pendingManual;
        if (manual != null) {
            pendingManual = null;
            manual.complete(null);
            return;
        }
        if (running || session == null) return;
        boolean auto = autoSwitch.isChecked() && table != null;
        session.autoMode = auto;

        if (auto && !calibrationValid()) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.calibrate_first_title)
                    .setMessage(R.string.calibrate_first_message)
                    .setPositiveButton(R.string.calibrate, (d, w) -> onCalibrate())
                    .setNeutralButton(R.string.use_manual, (d, w) -> {
                        autoSwitch.setChecked(false);
                        checkStorageAndSamba(false, null);
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return;
        }
        checkStorageAndSamba(auto, null);
    }

    private boolean calibrationValid() {
        Calibration cal = Calibration.load(this);
        return cal != null
                && cal.profileFingerprint.equals(profile.fingerprint())
                && cal.markerCount == ring.config().markerCount
                && ring.latest().ok();
    }

    /** Storage and Samba prechecks, then starts the sequence. setLabel is null for the main set. */
    private void checkStorageAndSamba(boolean auto, String setLabel) {
        if (store.base().getUsableSpace() < MIN_FREE_BYTES && store.base().getParentFile().getUsableSpace() < MIN_FREE_BYTES) {
            Toast.makeText(this, R.string.low_storage, Toast.LENGTH_LONG).show();
            return;
        }
        if (!session.syncToSamba) {
            startSequence(auto, setLabel);
            return;
        }
        io.execute(() -> {
            SmbSettings smb = SmbSettings.load(this);
            boolean ok = smb.isConfigured() && SmbClient.reachable(smb);
            runOnUiThread(() -> {
                if (ok) {
                    startSequence(auto, setLabel);
                    return;
                }
                new MaterialAlertDialogBuilder(this)
                        .setTitle(smb.isConfigured() ? R.string.samba_unreachable : R.string.samba_not_set_up)
                        .setMessage(R.string.samba_alert_message)
                        .setPositiveButton(R.string.save_locally, (d, w) -> startSequence(auto, setLabel))
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            });
        });
    }

    private void startSequence(boolean auto, String setLabel) {
        CaptureSequencer.Options o = new CaptureSequencer.Options();
        o.auto = auto;
        o.video = session.captureVideo;
        o.setLabel = setLabel;
        o.manualDelayMs = parseInt(prefs.getString("manual_delay_ms", "2000"), 2000);
        o.videoOverlapDeg = parseInt(prefs.getString("video_overlap_deg", "15"), 15);
        setRunning(true);
        sequencer.run(session, o, this);
    }

    private void setRunning(boolean r) {
        running = r;
        if (r) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        findViewById(R.id.extra).setEnabled(!r);
        findViewById(R.id.calibrate).setEnabled(!r);
        autoSwitch.setEnabled(!r && table != null);
    }

    private void onExtra() {
        if (running || session == null) return;
        String[] items = new String[EXTRA_LABELS.length + 2];
        System.arraycopy(EXTRA_LABELS, 0, items, 0, EXTRA_LABELS.length);
        items[EXTRA_LABELS.length] = getString(R.string.extra_other);
        items[EXTRA_LABELS.length + 1] = getString(R.string.extra_set);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.extra_shot)
                .setItems(items, (d, which) -> {
                    if (which < EXTRA_LABELS.length) captureExtra(EXTRA_LABELS[which]);
                    else askLabel(which == EXTRA_LABELS.length ? R.string.extra_other : R.string.extra_set,
                            which > EXTRA_LABELS.length);
                })
                .show();
    }

    private void askLabel(int title, boolean wholeSet) {
        EditText input = new EditText(this);
        input.setHint(wholeSet ? R.string.extra_set_hint : R.string.extra_label_hint);
        new MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setView(input)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String label = input.getText().toString().trim();
                    if (label.isEmpty()) return;
                    if (wholeSet) checkStorageAndSamba(autoSwitch.isChecked() && table != null, label);
                    else captureExtra(label);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void captureExtra(String label) {
        setRunning(true);
        sequencer.captureExtra(session, label, parseInt(prefs.getString("manual_delay_ms", "2000"), 2000), this);
    }

    private void onCalibrate() {
        if (table == null) {
            Toast.makeText(this, R.string.no_remote, Toast.LENGTH_LONG).show();
            return;
        }
        setRunning(true);
        io.execute(() -> {
            try {
                Calibration cal = new Calibrator(table, ring::latest, this::onStatus).run(ring.config().markerCount);
                cal.save(this);
                table.setCalibration(cal);
            } catch (MoveFailedException e) {
                onStatus(e.userMessage());
            } catch (Exception e) {
                onStatus(getString(R.string.calibration_failed, e.getMessage()));
            } finally {
                runOnUiThread(() -> setRunning(false));
            }
        });
    }

    // ---- CaptureSequencer.Listener (called on the capture thread) ------------

    @Override
    public void onStatus(String text) {
        runOnUiThread(() -> status.setText(text));
    }

    @Override
    public CompletableFuture<CaptureSequencer.Decision> onMoveFailed(View view, MoveFailedException e) {
        CompletableFuture<CaptureSequencer.Decision> f = new CompletableFuture<>();
        runOnUiThread(() -> new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.move_failed_title)
                .setMessage(e.userMessage())
                .setCancelable(false)
                .setPositiveButton(R.string.retry, (d, w) -> f.complete(CaptureSequencer.Decision.RETRY))
                .setNeutralButton(R.string.switch_to_manual, (d, w) -> f.complete(CaptureSequencer.Decision.MANUAL))
                .setNegativeButton(R.string.stop, (d, w) -> f.complete(CaptureSequencer.Decision.STOP))
                .show());
        return f;
    }

    @Override
    public CompletableFuture<Void> awaitManualPosition(String instruction, double targetRingAngle) {
        CompletableFuture<Void> f = new CompletableFuture<>();
        pendingManual = f;
        // TODO: draw the angle dial (current vs target) when targetRingAngle isn't NaN.
        runOnUiThread(() -> status.setText(instruction));
        return f;
    }

    @Override
    public void onFinished(Session s, boolean complete) {
        runOnUiThread(() -> {
            setRunning(false);
            status.setText(complete ? R.string.capture_done : R.string.capture_partial);
        });
    }

    @Override
    public void onError(Exception e) {
        runOnUiThread(() -> {
            setRunning(false);
            status.setText(getString(R.string.capture_error, e.getMessage()));
        });
    }

    private static int parseInt(String s, int fallback) {
        try {
            return Integer.parseInt(s.trim());
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    @Override
    protected void onDestroy() {
        sequencer.shutdown();
        camera.shutdown();
        io.shutdownNow();
        super.onDestroy();
    }
}
