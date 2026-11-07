package org.snurkle.badgeturntable.ui;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.pm.PackageManager;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.util.Log;
import android.view.Display;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;

import org.snurkle.badgeturntable.R;
import org.snurkle.badgeturntable.camera.CameraController;
import org.snurkle.badgeturntable.camera.CameraSettings;
import org.snurkle.badgeturntable.capture.CaptureTarget;
import org.snurkle.badgeturntable.capture.SessionTarget;
import org.snurkle.badgeturntable.jobs.ApiException;
import org.snurkle.badgeturntable.jobs.Job;
import org.snurkle.badgeturntable.jobs.JobRunner;
import org.snurkle.badgeturntable.remote.RemoteServer;
import org.snurkle.badgeturntable.rig.Rig;
import org.snurkle.badgeturntable.ring.RingReading;
import org.snurkle.badgeturntable.session.Session;
import org.snurkle.badgeturntable.session.SessionStore;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Live preview, camera controls, turntable status and the Capture / Extra shot / Calibrate
 * buttons. Opened for a badge session, or on its own (setup mode) to frame the light box,
 * calibrate, and take shots for the editor. Everything that uses the camera or turntable
 * is a job on the {@link Rig}, so the phone and the editor see the same progress and
 * prompts. While this screen is visible and Settings → Remote control is on, it also runs
 * the {@link RemoteServer}.
 */
public final class CaptureActivity extends AppCompatActivity implements JobRunner.Listener, RemoteServer.Listener {
    private static final String[] EXTRA_LABELS = {"Lanyard", "Add-ons installed", "Detail", "Lit up", "Packaging"};
    /** Per-frame ring trace. Off by default: adb shell setprop log.tag.RingTrace DEBUG */
    private static final String RING_TRACE = "RingTrace";

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private SessionStore store;
    /** Null in setup mode (no badge). */
    private Session session;
    private Rig rig;
    private RemoteServer remote;

    private PreviewView preview;
    private FramingOverlay overlay;
    private TextView status, ringText, remoteText;
    private MaterialSwitch autoSwitch;
    private Button captureButton, extraButton, calibrateButton, wbButton, lensButton, zoomButton;
    private ScaleGestureDetector pinch;
    private GestureDetector taps;
    private AlertDialog promptDialog;
    private String promptDialogId;
    private long lastRingUiMs;
    private int lastRotation;
    private boolean haveRotation;
    private final DisplayManager.DisplayListener displayListener = new DisplayManager.DisplayListener() {
        @Override
        public void onDisplayAdded(int displayId) {}

        @Override
        public void onDisplayRemoved(int displayId) {}

        @Override
        public void onDisplayChanged(int displayId) {
            onRotation();
        }
    };
    private final Consumer<CameraSettings> cameraListener = s -> runOnUiThread(() -> showCamera(s));

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
        rig = new Rig(this);

        preview = findViewById(R.id.preview);
        overlay = findViewById(R.id.overlay);
        MaterialSwitch guides = findViewById(R.id.guides);
        guides.setChecked(rig.prefs.getBoolean("show_guides", true));
        overlay.setGuidesVisible(guides.isChecked());
        guides.setOnCheckedChangeListener((b, on) -> {
            overlay.setGuidesVisible(on);
            rig.prefs.edit().putBoolean("show_guides", on).apply();
        });
        status = findViewById(R.id.status);
        ringText = findViewById(R.id.ring);
        remoteText = findViewById(R.id.remote);
        autoSwitch = findViewById(R.id.auto);
        captureButton = findViewById(R.id.capture);
        extraButton = findViewById(R.id.extra);
        calibrateButton = findViewById(R.id.calibrate);
        wbButton = findViewById(R.id.wb);
        lensButton = findViewById(R.id.lens);
        zoomButton = findViewById(R.id.zoom_reset);

        rig.ring.addListener(this::showRing);
        rig.jobs.addListener(this);
        rig.camera.addSettingsListener(cameraListener);

        captureButton.setOnClickListener(v -> onCapture());
        extraButton.setOnClickListener(v -> onExtraOrStop());
        calibrateButton.setOnClickListener(v -> onCalibrate());
        findViewById(R.id.set_front).setOnClickListener(v -> {
            try {
                double at = rig.setFront();
                Toast.makeText(this, getString(R.string.front_set, at), Toast.LENGTH_SHORT).show();
            } catch (ApiException e) {
                Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
        findViewById(R.id.ev_minus).setOnClickListener(v -> stepEv(-1));
        findViewById(R.id.ev_plus).setOnClickListener(v -> stepEv(1));
        wbButton.setOnClickListener(v -> cycleWhiteBalance());
        lensButton.setOnClickListener(v -> nextLens());
        findViewById(R.id.zoom_in).setOnClickListener(v -> stepZoom(ZOOM_STEP));
        findViewById(R.id.zoom_out).setOnClickListener(v -> stepZoom(1 / ZOOM_STEP));
        zoomButton.setOnClickListener(v -> rig.camera.setZoom(1f));
        pinch = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(@NonNull ScaleGestureDetector d) {
                rig.camera.setZoom(rig.camera.settings().zoom * d.getScaleFactor());
                return true;
            }
        });
        taps = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapUp(@NonNull MotionEvent e) {
                rig.camera.focusAt(e.getX(), e.getY());
                overlay.showFocus(e.getX(), e.getY());
                return true;
            }
        });
        preview.setOnTouchListener(this::onPreviewTouch);
        showCamera(rig.camera.settings());
        autoSwitch.setChecked(rig.table != null);

        String id = getIntent().getStringExtra(SessionEditActivity.EXTRA_ID);
        if (id == null) {
            setTitle(R.string.camera_setup);
            updateButtons();
        } else {
            io.execute(() -> {
                Session s = store.get(id);
                runOnUiThread(() -> {
                    if (s == null) {
                        finish();
                        return;
                    }
                    session = s;
                    setTitle(s.title);
                    autoSwitch.setChecked(s.autoMode && rig.table != null);
                    updateButtons();
                });
            });
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA);
        }
    }

    private void startCamera() {
        rig.camera.start(this, preview, rig.ring);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (RemoteServer.enabled(this)) {
            remote = new RemoteServer(this, rig, this);
            remote.start();
        }
        updateButtons();
        onRemoteChanged();
        if (rig.jobs.current() == null) status.setText(canCapture() ? "" : getString(R.string.setup_mode));
        getSystemService(DisplayManager.class).registerDisplayListener(displayListener, null);
        onRotation();
    }

    /** Landscape either way round: a flip doesn't restart the screen, so tell the camera. */
    private void onRotation() {
        Display d = getDisplay();
        if (d == null) return;
        int rotation = d.getRotation();
        if (haveRotation && rotation == lastRotation) return;
        boolean flipped = haveRotation;
        haveRotation = true;
        lastRotation = rotation;
        if (!flipped) return;
        rig.camera.setTargetRotation(rotation);
        rig.ring.resetGeometry();
    }

    @Override
    protected void onStop() {
        getSystemService(DisplayManager.class).unregisterDisplayListener(displayListener);
        // The camera needs the app in the foreground: a job can't carry on in the background.
        if (rig.jobs.current() != null) {
            try {
                rig.jobs.abort();
            } catch (ApiException ignored) {
            }
        }
        if (remote != null) remote.stop();
        remote = null;
        super.onStop();
    }

    /** Pinch to zoom; tap to focus there. */
    @SuppressLint("ClickableViewAccessibility")
    private boolean onPreviewTouch(android.view.View v, MotionEvent e) {
        pinch.onTouchEvent(e);
        if (!pinch.isInProgress()) taps.onTouchEvent(e);
        return true;
    }

    private static final float ZOOM_STEP = 1.25f;

    private void stepZoom(float factor) {
        float z = rig.camera.settings().zoom * factor;
        if (Math.abs(z - 1f) < 0.1f) z = 1f;  // land on 1x on the way past
        rig.camera.setZoom(z);
    }

    /** Cycles through the back lenses CameraX can bind. */
    private void nextLens() {
        List<CameraController.Lens> lenses = rig.camera.capabilities().lenses;
        if (lenses.size() < 2) return;
        String now = rig.camera.boundLensId();
        int i = 0;
        for (int k = 0; k < lenses.size(); k++) if (lenses.get(k).id.equals(now)) i = k;
        String next = lenses.get((i + 1) % lenses.size()).id;
        rig.camera.update(s -> {
            s.lens = next;
            s.zoom = 1f;
        });
    }

    private void stepEv(int by) {
        CameraController.Capabilities caps = rig.camera.capabilities();
        rig.camera.update(s -> s.ev = Math.max(caps.evMin, Math.min(caps.evMax, s.ev + by)));
    }

    private void cycleWhiteBalance() {
        CameraController.WhiteBalance[] all = CameraController.WhiteBalance.values();
        CameraController.WhiteBalance now = CameraController.WhiteBalance.fromApi(rig.camera.settings().whiteBalance);
        CameraController.WhiteBalance next = all[(now.ordinal() + 1) % all.length];
        rig.camera.update(s -> s.whiteBalance = next.apiName());
    }

    /** White balance, zoom and lens buttons; after any settings change or rebind. */
    private void showCamera(CameraSettings s) {
        wbButton.setText(getString(R.string.wb_label, s.whiteBalance));
        zoomButton.setText(getString(R.string.zoom_label, s.zoom));
        List<CameraController.Lens> lenses = rig.camera.capabilities().lenses;
        String bound = rig.camera.boundLensId();
        String label = null;
        for (CameraController.Lens l : lenses) if (l.id.equals(bound)) label = l.label;
        lensButton.setVisibility(lenses.size() > 1 ? android.view.View.VISIBLE : android.view.View.GONE);
        if (label != null) lensButton.setText(getString(R.string.lens_label, label));
    }

    private void showRing(RingReading r) {
        if (Log.isLoggable(RING_TRACE, Log.DEBUG)) {
            Log.d(RING_TRACE, String.format(Locale.ROOT, "%d %.2f %d %.2f %.1f %.1f",
                    r.timestampNs / 1_000_000, r.angleDeg, r.markers, r.rmsPx, r.xAxis, r.radiusPx));
        }
        overlay.setReading(r);
        long now = System.currentTimeMillis();
        if (now - lastRingUiMs < 200) return;
        lastRingUiMs = now;
        // Two fixed lines: the angle, then tags and where the table is against the front.
        String line1 = r.ok() ? getString(R.string.ring_angle, r.angleDeg) : getString(R.string.ring_not_visible);
        double off = rig.fromFront(r.angleDeg);
        String front = Double.isNaN(rig.frontRingDeg()) ? getString(R.string.front_not_set)
                : Double.isNaN(off) ? "" : Math.abs(off) <= 2 ? getString(R.string.at_front)
                : getString(R.string.front_offset, off);
        String line2 = getString(R.string.ring_tags, r.ok() ? r.markers : r.markerIds.length)
                + (front.isEmpty() ? "" : " · " + front);
        String text = line1 + "\n" + line2;
        runOnUiThread(() -> ringText.setText(text));
    }

    // ---- Buttons -------------------------------------------------------------------

    /** Where the phone's own captures go: the badge, or (setup mode) the shots for the editor. */
    private CaptureTarget target() {
        return session != null ? new SessionTarget(store, session) : null;
    }

    /**
     * The phone's own Capture and Extra shot need a badge session. Setup mode only previews and
     * calibrates; the editor can still take shots there through the remote API, and the phone
     * keeps Ready and Stop for those jobs.
     */
    private boolean canCapture() {
        return session != null;
    }

    private void onCapture() {
        Job job = rig.jobs.current();
        if (job != null) {
            Job.Prompt p = job.prompt();
            if (p != null && "position".equals(p.kind)) answer(p, "ready");
            return;
        }
        if (!canCapture()) return;
        boolean auto = autoSwitch.isChecked() && rig.table != null;
        if (session != null) session.autoMode = auto;
        if (auto && (!rig.calibrated() || !rig.ring.latest().ok())) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.calibrate_first_title)
                    .setMessage(R.string.calibrate_first_message)
                    .setPositiveButton(R.string.calibrate, (d, w) -> onCalibrate())
                    .setNeutralButton(R.string.use_manual, (d, w) -> {
                        autoSwitch.setChecked(false);
                        startCapture(false, null);
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return;
        }
        startCapture(auto, null);
    }

    /** setLabel is null for the main set. */
    private void startCapture(boolean auto, String setLabel) {
        boolean video = session == null || session.captureVideo;
        try {
            rig.startCapture(target(), auto, true, video, setLabel);
        } catch (ApiException e) {
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void onExtraOrStop() {
        Job job = rig.jobs.current();
        if (job != null) {
            Job.Prompt p = job.prompt();
            if (p != null && p.answers.contains("stop")) answer(p, "stop");
            else {
                try {
                    rig.jobs.abort();
                } catch (ApiException ignored) {
                }
            }
            return;
        }
        if (!canCapture()) return;
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
                    if (wholeSet) startCapture(autoSwitch.isChecked() && rig.table != null, label);
                    else captureExtra(label);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void captureExtra(String label) {
        try {
            rig.startExtra(target(), label, Rig.parseInt(rig.prefs.getString("manual_delay_ms", "2000"), 2000));
        } catch (ApiException e) {
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void onCalibrate() {
        try {
            rig.startCalibrate();
        } catch (ApiException e) {
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void answer(Job.Prompt p, String answer) {
        try {
            rig.jobs.answer(p.id, answer);
        } catch (ApiException e) {
            // Someone answered first (the editor); the job update will catch the screen up.
        }
    }

    // ---- Jobs (from any thread) ------------------------------------------------------

    @Override
    public void onJobChanged(Job job) {
        runOnUiThread(() -> showJob(job));
    }

    private void showJob(Job job) {
        if (isFinishing() || isDestroyed()) return;
        if (job.finished()) {
            switch (job.state()) {
                case DONE: status.setText(job.text()); break;
                case FAILED: status.setText(job.errorMessage()); break;
                default: status.setText(job.kind == Job.Kind.CAPTURE ? getString(R.string.capture_partial)
                        : getString(R.string.stopped));
            }
        } else {
            Job.Prompt p = job.prompt();
            status.setText(p != null ? p.text : job.text());
        }
        showPrompt(job.finished() ? null : job.prompt());
        updateButtons();
    }

    /** Move failures get a dialog; manual positioning uses the Ready (Capture) button. */
    private void showPrompt(Job.Prompt p) {
        String id = p != null && "move_failed".equals(p.kind) ? p.id : null;
        if (id != null && id.equals(promptDialogId)) return;
        if (promptDialog != null) promptDialog.dismiss();
        promptDialog = null;
        promptDialogId = id;
        if (id == null) return;
        MaterialAlertDialogBuilder b = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.move_failed_title)
                .setMessage(p.text)
                .setCancelable(false);
        if (p.answers.contains("retry")) b.setPositiveButton(R.string.retry, (d, w) -> answer(p, "retry"));
        if (p.answers.contains("manual")) b.setNeutralButton(R.string.switch_to_manual, (d, w) -> answer(p, "manual"));
        if (p.answers.contains("stop")) b.setNegativeButton(R.string.stop, (d, w) -> answer(p, "stop"));
        promptDialog = b.show();
    }

    private void updateButtons() {
        Job job = rig.jobs.current();
        boolean busy = job != null;
        Job.Prompt p = busy ? job.prompt() : null;
        boolean ready = p != null && "position".equals(p.kind);
        boolean capture = canCapture();

        captureButton.setText(ready ? R.string.ready : R.string.capture);
        captureButton.setEnabled(busy ? ready : capture);
        captureButton.setVisibility(capture || ready ? android.view.View.VISIBLE : android.view.View.GONE);
        extraButton.setText(busy ? R.string.stop : R.string.extra_shot);
        extraButton.setVisibility(capture || busy ? android.view.View.VISIBLE : android.view.View.GONE);
        calibrateButton.setEnabled(!busy && rig.table != null);
        findViewById(R.id.set_front).setEnabled(!busy);
        autoSwitch.setEnabled(!busy && rig.table != null);
    }

    // ---- Remote control (from any thread) ----------------------------------------------

    @Override
    public void onRemoteChanged() {
        runOnUiThread(this::showRemote);
    }

    private void showRemote() {
        RemoteServer r = remote;
        if (r == null) {
            remoteText.setVisibility(android.view.View.GONE);
            return;
        }
        remoteText.setVisibility(android.view.View.VISIBLE);
        if (!r.running()) {
            remoteText.setText(r.error());
            return;
        }
        List<String> addresses = RemoteServer.addresses();
        String code = r.code();
        StringBuilder text = new StringBuilder(getString(R.string.remote_banner, r.name(),
                addresses.isEmpty() ? getString(R.string.remote_no_wifi) : addresses.get(0) + ":" + r.port(),
                code.substring(0, 3) + " " + code.substring(3)));
        List<String> clients = r.clientNames();
        if (!clients.isEmpty()) text.append(" · ").append(getString(R.string.remote_controlled_from, String.join(", ", clients)));
        remoteText.setText(text);
        updateButtons();
    }

    @Override
    protected void onDestroy() {
        if (promptDialog != null) promptDialog.dismiss();
        rig.jobs.removeListener(this);
        rig.camera.removeSettingsListener(cameraListener);
        rig.shutdown();
        io.shutdownNow();
        super.onDestroy();
    }
}
