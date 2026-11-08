package org.snurkle.badgeturntable.camera;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.os.SystemClock;
import android.util.Log;
import android.util.Range;
import android.util.Size;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.camera.camera2.interop.Camera2CameraControl;
import androidx.camera.camera2.interop.Camera2CameraInfo;
import androidx.camera.camera2.interop.Camera2Interop;
import androidx.camera.camera2.interop.CaptureRequestOptions;
import androidx.camera.camera2.interop.ExperimentalCamera2Interop;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraInfo;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ExposureState;
import androidx.camera.core.FocusMeteringAction;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.MeteringPoint;
import androidx.camera.core.MeteringPointFactory;
import androidx.camera.core.Preview;
import androidx.camera.core.SurfaceOrientedMeteringPointFactory;
import androidx.camera.core.ZoomState;
import androidx.camera.core.resolutionselector.AspectRatioStrategy;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.core.AspectRatio;
import androidx.camera.video.FallbackStrategy;
import androidx.camera.video.FileOutputOptions;
import androidx.camera.video.Quality;
import androidx.camera.video.QualitySelector;
import androidx.camera.video.Recorder;
import androidx.camera.video.Recording;
import androidx.camera.video.VideoCapture;
import androidx.camera.video.VideoRecordEvent;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * Owns the CameraX session: preview, high-resolution stills, the ring analyzer
 * and (when needed) video. Stills and video use separate bindings, because
 * many phones can't run maximum-quality capture, analysis and recording at once.
 *
 * Settings ({@link CameraSettings}) go through Camera2 interop and are saved as the
 * "light box profile", since the light box doesn't change between badges. They can be
 * changed from the phone or from the editor ({@link #update}).
 *
 * The blocking methods (takePicture, stopRecording, setFlyingStills) must not be called on
 * the main thread; the job runner has its own.
 */
@OptIn(markerClass = ExperimentalCamera2Interop.class)
public final class CameraController {
    private static final String TAG = "CameraController";
    private static final String PREFS = "lightbox_profile";

    public enum WhiteBalance {
        AUTO(CaptureRequest.CONTROL_AWB_MODE_AUTO), DAYLIGHT(CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT),
        CLOUDY(CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT), INCANDESCENT(CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT),
        FLUORESCENT(CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT);

        final int mode;

        WhiteBalance(int mode) {
            this.mode = mode;
        }

        /** "auto", "daylight", … as in the API and the profile. */
        public String apiName() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static WhiteBalance fromApi(String name) {
            for (WhiteBalance wb : values()) if (wb.apiName().equals(name)) return wb;
            return AUTO;
        }
    }

    /** A back camera the app can bind. */
    public static final class Lens {
        public final String id;
        public final String label;
        public final float focalLengthMm;
        /** Closest focus in cm, or NaN for fixed focus. */
        public final float minFocusCm;

        Lens(String id, String label, float focalLengthMm, float minFocusCm) {
            this.id = id;
            this.label = label;
            this.focalLengthMm = focalLengthMm;
            this.minFocusCm = minFocusCm;
        }
    }

    /** What the bound lens offers. Ranges are null when the lens can't be set by hand. */
    public static final class Capabilities {
        public List<Lens> lenses = new ArrayList<>();
        public float zoomMin = 1f, zoomMax = 1f;
        public int evMin, evMax;
        public double evStep;
        public Range<Integer> iso;
        public Range<Long> shutterNs;
        /** Closest focus in diopters, for the manual focus range [0, this]; null without manual focus. */
        public Float maxFocusDiopters;
        public List<String> whiteBalance = new ArrayList<>();
    }

    private final Context context;
    private final Executor main;
    private final ExecutorService analysisExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService stillExecutor = Executors.newSingleThreadExecutor();
    private final SharedPreferences prefs;
    private final List<Consumer<CameraSettings>> settingsListeners = new CopyOnWriteArrayList<>();

    private ProcessCameraProvider provider;
    private LifecycleOwner owner;
    private PreviewView previewView;
    private ImageAnalysis.Analyzer analyzer;

    private Preview preview;
    private ImageCapture imageCapture;
    private ImageAnalysis analysis;
    private VideoCapture<Recorder> videoCapture;
    private volatile Camera camera;
    private Recording recording;
    private CompletableFuture<Void> recordingDone;
    /** Replaced, never changed in place, so other threads can read it. */
    private volatile CameraSettings settings;
    /** The editor's held focus point, {x, y} upright and normalised, or null. Written on the main thread. */
    private volatile float[] focusPoint;
    /** Stills taken while the table turns: lowest shutter lag, no multi-frame merging. */
    private boolean flyingStills;
    /** What auto exposure and AF chose for the latest preview frame. */
    private volatile long lastExposureNs;
    private volatile int lastIso;
    private volatile float lastFocusDiopters = -1f;
    /** Rotation of the latest analysis frame, to map upright points back to the sensor. */
    private volatile int analysisRotation;

    // The capture lock (lock3A to unlock3A). A capture rebinds the camera (flying stills, video),
    // and a new session starts auto exposure, AWB and AF over; locking straight away froze AE
    // before it had converged, so the picture went dark and lost the user's EV. So the lock is
    // kept here and reapplied to each binding: exposure (and focus) are replayed by hand, and
    // AWB is locked again once it has settled. Main thread unless marked.
    private boolean locked;
    /** What auto exposure chose when the lock was taken, replayed by hand; 0 to use AE lock instead. */
    private int lockIso;
    private long lockExposureNs;
    /** Focus distance carried across a rebind, or -1 (the centre AF lock or the profile holds focus). */
    private float lockFocusDiopters = -1f;
    /** Whether the AE / AWB locks are applied: false after a rebind, until the new session settles. */
    private boolean aeLockOn, awbLockOn;
    /** Exposure for stills on the move (overrideExposure), this binding only; 0 when none. */
    private int overrideIso;
    private long overrideExposureNs;
    /** Set after a rebind while locked; the preview's capture callback clears it once AE/AWB converge. */
    private volatile boolean settling, settleAe;
    private volatile long settleDeadlineNs;
    /** Bumped for each preview built, so results from a closed session are ignored. */
    private volatile int previewGeneration;
    private static final long SETTLE_TIMEOUT_NS = 1_500_000_000L;

    public CameraController(Context context) {
        this.context = context.getApplicationContext();
        this.main = ContextCompat.getMainExecutor(context);
        this.prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.settings = CameraSettings.load(prefs);
    }

    /** Binds preview, stills and analysis. Call from the main thread. */
    public void start(LifecycleOwner owner, PreviewView previewView, ImageAnalysis.Analyzer analyzer) {
        this.owner = owner;
        this.previewView = previewView;
        this.analyzer = analyzer;
        ProcessCameraProvider.getInstance(context).addListener(() -> {
            try {
                provider = ProcessCameraProvider.getInstance(context).get();
                bindStills();
            } catch (ExecutionException | InterruptedException | RuntimeException e) {
                Log.e(TAG, "camera start failed", e);
            }
        }, main);
    }

    /**
     * The display turned end over end (landscape to reverse landscape). That doesn't restart
     * the screen, so tell the bound use cases, keeping stills, video and analysis upright.
     * New bindings pick up the display's rotation themselves.
     */
    public void setTargetRotation(int surfaceRotation) {
        main.execute(() -> {
            if (imageCapture != null) imageCapture.setTargetRotation(surfaceRotation);
            if (analysis != null) analysis.setTargetRotation(surfaceRotation);
            if (videoCapture != null) videoCapture.setTargetRotation(surfaceRotation);
        });
    }

    /** True once the camera is bound. */
    public boolean isOpen() {
        return camera != null;
    }

    private Preview buildPreview() {
        // 4:3 like the stills and the analysis frames, so the preview shows what will be shot
        // and the framing overlay's marker positions line up with it.
        Preview.Builder b = new Preview.Builder().setResolutionSelector(new ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY).build());
        int generation = ++previewGeneration;
        new Camera2Interop.Extender<>(b).setSessionCaptureCallback(new CameraCaptureSession.CaptureCallback() {
            @Override
            public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request,
                                           @NonNull TotalCaptureResult result) {
                Long e = result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
                Integer iso = result.get(CaptureResult.SENSOR_SENSITIVITY);
                Float focus = result.get(CaptureResult.LENS_FOCUS_DISTANCE);
                if (e != null) lastExposureNs = e;
                if (iso != null) lastIso = iso;
                if (focus != null) lastFocusDiopters = focus;
                if (settling && generation == previewGeneration) checkSettled(result, generation);
            }
        });
        Preview p = b.build();
        p.setSurfaceProvider(previewView.getSurfaceProvider());
        return p;
    }

    private CameraSelector selector() {
        String id = settings.lens;
        if (id == null || provider == null) return CameraSelector.DEFAULT_BACK_CAMERA;
        boolean known = false;
        for (CameraInfo info : provider.getAvailableCameraInfos()) {
            if (id.equals(Camera2CameraInfo.from(info).getCameraId())) known = true;
        }
        if (!known) return CameraSelector.DEFAULT_BACK_CAMERA;
        return new CameraSelector.Builder().addCameraFilter(infos -> {
            List<CameraInfo> out = new ArrayList<>();
            for (CameraInfo info : infos) if (id.equals(Camera2CameraInfo.from(info).getCameraId())) out.add(info);
            return out;
        }).build();
    }

    private void bindStills() {
        carryLockAcrossRebind();
        provider.unbindAll();
        preview = buildPreview();
        imageCapture = new ImageCapture.Builder()
                // Maximum quality can merge several frames, which ghosts a moving badge.
                .setCaptureMode(flyingStills ? ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
                        : ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setJpegQuality(95)
                .setResolutionSelector(new ResolutionSelector.Builder()
                        .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY).build())
                .build();
        analysis = buildAnalysis();
        camera = provider.bindToLifecycle(owner, selector(), preview, imageCapture, analysis);
        focusPoint = null;  // a new camera session drops any held focus
        startSettling();
        applyProfile();
        // A (re)bind can change the lens and its ranges: let the screen refresh its controls.
        notifySettings();
    }

    private void bindVideo() {
        carryLockAcrossRebind();
        provider.unbindAll();
        preview = buildPreview();
        // 4:3 like the preview and the stills (FHD at 4:3 is 1440x1080 on most phones), so the
        // preview keeps its shape when the camera switches to video for the spin.
        Recorder recorder = new Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.FHD,
                        FallbackStrategy.lowerQualityOrHigherThan(Quality.FHD)))
                .setAspectRatio(AspectRatio.RATIO_4_3)
                .build();
        videoCapture = VideoCapture.withOutput(recorder);
        analysis = buildAnalysis();
        camera = provider.bindToLifecycle(owner, selector(), preview, videoCapture, analysis);
        startSettling();
        applyProfile();
    }

    /**
     * Before a rebind: the spin-still override is for one binding only, and while locked, focus
     * is held where it is now (the new session would start AF over) and the AE / AWB locks wait
     * for the new session to settle.
     */
    private void carryLockAcrossRebind() {
        overrideIso = 0;
        overrideExposureNs = 0;
        if (!locked) return;
        CameraSettings s = settings;
        if (!s.manualFocus() && !s.afLock && lastFocusDiopters >= 0 && manualSensor()) {
            lockFocusDiopters = lastFocusDiopters;
        }
        aeLockOn = false;
        awbLockOn = false;
    }

    /** After a rebind while locked: watch the new session's results until AE (if not replayed) and AWB converge. */
    private void startSettling() {
        if (!locked) return;
        settleAe = lockIso <= 0 && !settings.manualExposure();
        settleDeadlineNs = SystemClock.elapsedRealtimeNanos() + SETTLE_TIMEOUT_NS;
        settling = true;
    }

    /** Camera thread. Once the new session has settled (or given up waiting), put the locks back on. */
    private void checkSettled(TotalCaptureResult result, int generation) {
        Integer ae = result.get(CaptureResult.CONTROL_AE_STATE);
        Integer awb = result.get(CaptureResult.CONTROL_AWB_STATE);
        boolean aeDone = !settleAe || ae == null || ae == CaptureResult.CONTROL_AE_STATE_CONVERGED
                || ae == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED || ae == CaptureResult.CONTROL_AE_STATE_LOCKED;
        boolean awbDone = awb == null || awb == CaptureResult.CONTROL_AWB_STATE_CONVERGED
                || awb == CaptureResult.CONTROL_AWB_STATE_LOCKED;
        if (!(aeDone && awbDone) && SystemClock.elapsedRealtimeNanos() < settleDeadlineNs) return;
        settling = false;
        main.execute(() -> {
            if (!locked || generation != previewGeneration) return;
            aeLockOn = true;
            awbLockOn = true;
            applyRequestOptions();
        });
    }

    private boolean manualSensor() {
        Camera c = camera;
        return c != null && hasCapability(Camera2CameraInfo.from(c.getCameraInfo()),
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR);
    }

    private ImageAnalysis buildAnalysis() {
        ImageAnalysis a = new ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setResolutionSelector(new ResolutionSelector.Builder()
                        // 4:3 like the preview, so the overlay's marker positions line up with it.
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                        // About 2 MP: at 720p the JAYEGT ring was only ~3.1 px/mm on the test bench,
                        // and several marker IDs were rarely decoded. 1600x1200 (the full sensor
                        // width) raised it to ~3.8 px/mm; detection takes ~28 ms.
                        .setResolutionStrategy(new ResolutionStrategy(new Size(1600, 1200),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)).build())
                .build();
        if (analyzer != null) {
            ImageAnalysis.Analyzer inner = analyzer;
            a.setAnalyzer(analysisExecutor, image -> {
                analysisRotation = image.getImageInfo().getRotationDegrees();
                inner.analyze(image);
            });
        }
        return a;
    }

    // ---- Settings (saved to the light box profile) -----------------------------

    /** The current settings (a copy). */
    public CameraSettings settings() {
        return settings.copy();
    }

    /** Called on the main thread after any change, from the phone or the editor. */
    public void addSettingsListener(Consumer<CameraSettings> l) {
        settingsListeners.add(l);
    }

    public void removeSettingsListener(Consumer<CameraSettings> l) {
        settingsListeners.remove(l);
    }

    /**
     * Changes settings on the main thread, saves them and applies them. A lens change
     * rebinds the camera. Completes with the new settings.
     */
    public CompletableFuture<CameraSettings> update(Consumer<CameraSettings> change) {
        CompletableFuture<CameraSettings> f = new CompletableFuture<>();
        main.execute(() -> {
            try {
                CameraSettings next = settings.copy();
                change.accept(next);
                if (next.afLock && !settings.afLock) next.afLockDiopters = lastFocusDiopters;
                boolean rebind = !Objects.equals(next.lens, settings.lens);
                boolean zoomOnly = onlyZoomDiffers(settings, next);
                // A focus point only lasts while autofocus is free to use it.
                if (next.manualFocus() || (next.afLock && !settings.afLock) || rebind) focusPoint = null;
                CameraSettings prev = settings;
                settings = next;
                next.save(prefs);
                if (rebind && provider != null) {
                    if (recording != null) throw new IllegalStateException("can't change lens while recording");
                    bindStills();  // tells the listeners itself
                    f.complete(next.copy());
                    return;
                }
                if (zoomOnly && camera != null) {
                    camera.getCameraControl().setZoomRatio(next.zoom);  // pinching: keep it light
                } else if (next.onlyBarsDiffer(prev)) {
                    // Side bars only: nothing for the camera to do.
                } else {
                    applyProfile();
                }
                notifySettings();
                f.complete(next.copy());
            } catch (RuntimeException e) {
                Log.w(TAG, "settings change failed", e);
                f.completeExceptionally(e);
            }
        });
        return f;
    }

    /** Zoom ratio, clamped to the lens's range (pinch and the zoom buttons). Saved like any setting. */
    public void setZoom(float ratio) {
        update(s -> {
            ZoomState z = camera == null ? null : camera.getCameraInfo().getZoomState().getValue();
            s.zoom = z == null ? ratio : Math.max(z.getMinZoomRatio(), Math.min(z.getMaxZoomRatio(), ratio));
        });
    }

    /** Tells the listeners about the settings now. Main thread. */
    private void notifySettings() {
        for (Consumer<CameraSettings> l : settingsListeners) l.accept(settings.copy());
    }

    /**
     * The point the editor clicked to focus on, {x, y} in the upright analysis frame, while
     * autofocus is held there; null otherwise. Not part of the light box profile.
     */
    public float[] focusPoint() {
        float[] p = focusPoint;
        return p == null ? null : p.clone();
    }

    /** True if b is a with only the zoom changed (a pinch), which needs no other request options. */
    private static boolean onlyZoomDiffers(CameraSettings a, CameraSettings b) {
        return a.zoom != b.zoom && a.sameCamera(b);
    }

    /**
     * Tap to focus at a point of the on-screen preview, for a look: focus only (metering
     * exposure too made the picture jump), and back to continuous AF after a few seconds.
     * Holding focus on a tap left the ring blurred, and unread, after a tap on the background.
     * Captures lock focus on the centre themselves (lock3A).
     */
    public void focusAt(float viewX, float viewY) {
        if (camera == null || analysis == null) return;
        if (focusPoint != null) {
            focusPoint = null;  // the tap replaces the editor's held point
            notifySettings();
        }
        if (settings.afLock || settings.manualFocus()) update(s -> {
            s.afLock = false;
            s.focusMode = "auto";
        });
        camera.getCameraControl().startFocusAndMetering(new FocusMeteringAction.Builder(
                previewView.getMeteringPointFactory().createPoint(viewX, viewY), FocusMeteringAction.FLAG_AF)
                .setAutoCancelDuration(TAP_FOCUS_SECONDS, TimeUnit.SECONDS).build());
    }

    private static final long TAP_FOCUS_SECONDS = 3;

    /** A point of the upright analysis frame (normalised) as a metering point on the sensor-oriented buffer. */
    private MeteringPoint uprightPoint(float x, float y, float size) {
        float sx, sy;
        switch (analysisRotation) {
            case 90: sx = y; sy = 1 - x; break;
            case 180: sx = 1 - x; sy = 1 - y; break;
            case 270: sx = 1 - y; sy = x; break;
            default: sx = x; sy = y;
        }
        return new SurfaceOrientedMeteringPointFactory(1f, 1f, analysis).createPoint(sx, sy, size);
    }

    /**
     * The editor's click (Remote-API.md, POST /api/camera/focus): focus at a point of the
     * upright analysis frame (what the editor's preview shows), normalised with (0, 0) at
     * the top left, and hold it there until cleared. Focus only, like the phone's tap:
     * metering exposure there too made the picture jump. Completes once the request is made.
     */
    public CompletableFuture<Void> focusAtNormalized(float x, float y) {
        CompletableFuture<Void> f = new CompletableFuture<>();
        main.execute(() -> {
            if (camera == null || analysis == null) {
                f.completeExceptionally(new IllegalStateException("camera not open"));
                return;
            }
            if (settings.afLock || settings.manualFocus()) update(s -> {
                s.afLock = false;
                s.focusMode = "auto";
            });
            MeteringPoint p = uprightPoint(x, y, MeteringPointFactory.getDefaultPointSize());
            camera.getCameraControl().startFocusAndMetering(
                    new FocusMeteringAction.Builder(p, FocusMeteringAction.FLAG_AF).disableAutoCancel().build());
            focusPoint = new float[]{x, y};
            notifySettings();
            f.complete(null);
        });
        return f;
    }

    /** Lets go of the editor's focus point: continuous autofocus returns. */
    public CompletableFuture<Void> clearFocusPoint() {
        CompletableFuture<Void> f = new CompletableFuture<>();
        main.execute(() -> {
            if (camera != null) camera.getCameraControl().cancelFocusAndMetering();
            focusPoint = null;
            notifySettings();
            f.complete(null);
        });
        return f;
    }

    private void applyProfile() {
        Camera c = camera;
        if (c == null) return;
        CameraSettings s = settings;
        ZoomState zoom = c.getCameraInfo().getZoomState().getValue();
        float ratio = zoom == null ? s.zoom : Math.max(zoom.getMinZoomRatio(), Math.min(zoom.getMaxZoomRatio(), s.zoom));
        c.getCameraControl().setZoomRatio(ratio);
        Range<Integer> evRange = c.getCameraInfo().getExposureState().getExposureCompensationRange();
        c.getCameraControl().setExposureCompensationIndex(
                Math.max(evRange.getLower(), Math.min(evRange.getUpper(), s.manualExposure() ? 0 : s.ev)));
        applyRequestOptions();
    }

    /** The profile's Camera2 options, plus the capture lock while it's held. Replaces any set before. */
    private void applyRequestOptions() {
        Camera c = camera;
        if (c == null) return;
        CameraSettings s = settings;
        CaptureRequestOptions.Builder b = new CaptureRequestOptions.Builder()
                .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, WhiteBalance.fromApi(s.whiteBalance).mode);
        if (s.awbLock) b.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, true);
        if (s.manualExposure()) {
            b.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                    .setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, s.iso)
                    .setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, s.shutterNs);
        } else if (s.aeLock) {
            b.setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, true);
        }
        float hold = s.manualFocus() ? s.focusDiopters : s.afLock ? s.afLockDiopters : -1f;
        if (hold < 0 && locked) hold = lockFocusDiopters;
        if (hold >= 0) {
            b.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                    .setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, hold);
        }
        if (locked) {
            int iso = overrideIso > 0 ? overrideIso : s.manualExposure() ? 0 : lockIso;
            long ns = overrideIso > 0 ? overrideExposureNs : lockExposureNs;
            if (iso > 0 && ns > 0) {
                b.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                        .setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, iso)
                        .setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, ns);
            } else if (aeLockOn) {
                b.setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, true);
            }
            if (awbLockOn) b.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, true);
        }
        Camera2CameraControl.from(c.getCameraControl()).setCaptureRequestOptions(b.build());
    }

    /** Lenses, the bound one's ranges, and the white balance modes it offers. */
    public Capabilities capabilities() {
        Capabilities caps = new Capabilities();
        Camera c = camera;
        ProcessCameraProvider p = provider;
        if (p != null) caps.lenses = lenses(p);
        if (c == null) return caps;
        ZoomState zoom = c.getCameraInfo().getZoomState().getValue();
        if (zoom != null) {
            caps.zoomMin = zoom.getMinZoomRatio();
            caps.zoomMax = zoom.getMaxZoomRatio();
        }
        ExposureState ev = c.getCameraInfo().getExposureState();
        caps.evMin = ev.getExposureCompensationRange().getLower();
        caps.evMax = ev.getExposureCompensationRange().getUpper();
        caps.evStep = ev.getExposureCompensationStep().doubleValue();
        Camera2CameraInfo info = Camera2CameraInfo.from(c.getCameraInfo());
        if (hasCapability(info, CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)) {
            caps.iso = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
            caps.shutterNs = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
            Float minFocus = info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
            if (minFocus != null && minFocus > 0) caps.maxFocusDiopters = minFocus;
        }
        int[] awb = info.getCameraCharacteristic(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES);
        for (WhiteBalance wb : WhiteBalance.values()) {
            boolean offered = awb == null ? wb == WhiteBalance.AUTO : contains(awb, wb.mode);
            if (offered) caps.whiteBalance.add(wb.apiName());
        }
        return caps;
    }

    /** Camera2 ID of the bound lens, or null before the camera opens. */
    public String boundLensId() {
        Camera c = camera;
        return c == null ? null : Camera2CameraInfo.from(c.getCameraInfo()).getCameraId();
    }

    /** Back cameras CameraX can bind, labelled by focal length relative to each other. */
    private static List<Lens> lenses(ProcessCameraProvider p) {
        List<Camera2CameraInfo> back = new ArrayList<>();
        for (CameraInfo i : p.getAvailableCameraInfos()) {
            if (i.getLensFacing() == CameraSelector.LENS_FACING_BACK) back.add(Camera2CameraInfo.from(i));
        }
        float minF = Float.MAX_VALUE, maxF = 0;
        int single = 0;
        for (Camera2CameraInfo i : back) {
            if (isLogical(i)) continue;
            float f = focalLength(i);
            minF = Math.min(minF, f);
            maxF = Math.max(maxF, f);
            single++;
        }
        List<Lens> out = new ArrayList<>();
        for (Camera2CameraInfo i : back) {
            float f = focalLength(i);
            String label;
            if (isLogical(i)) label = "Auto";  // the phone picks the lens as it zooms
            else if (single > 1 && f == minF) label = "Ultrawide";
            else if (single > 1 && f == maxF) label = "Telephoto";
            else label = "Main";
            Float minFocus = i.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
            float cm = minFocus != null && minFocus > 0 ? Math.round(1000f / minFocus) / 10f : Float.NaN;
            out.add(new Lens(i.getCameraId(), label, f, cm));
        }
        return out;
    }

    private static boolean isLogical(Camera2CameraInfo i) {
        return hasCapability(i, CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA);
    }

    private static float focalLength(Camera2CameraInfo i) {
        float[] f = i.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
        return f == null || f.length == 0 ? 0 : f[0];
    }

    private static boolean hasCapability(Camera2CameraInfo i, int capability) {
        int[] caps = i.getCameraCharacteristic(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
        return caps != null && contains(caps, capability);
    }

    private static boolean contains(int[] values, int v) {
        for (int x : values) if (x == v) return true;
        return false;
    }

    /**
     * Locks exposure, white balance and focus (a centre AF lock) so every view, and the spin
     * video, match what the preview showed when the capture started. Exposure is taken from
     * the last preview frame (so it includes the user's EV) and replayed by hand where the
     * lens allows it; the lock holds across rebinds until unlock3A. Call it before a rebind
     * (setFlyingStills, startRecording), while the preview has settled.
     */
    public void lock3A() {
        main.execute(() -> {
            if (camera == null) return;
            CameraSettings s = settings;
            if (focusPoint != null) {
                focusPoint = null;  // the centre lock below replaces it
                notifySettings();
            }
            if (!s.manualFocus() && !s.afLock) {
                MeteringPoint centre = previewView.getMeteringPointFactory()
                        .createPoint(previewView.getWidth() / 2f, previewView.getHeight() / 2f);
                camera.getCameraControl().startFocusAndMetering(
                        new FocusMeteringAction.Builder(centre, FocusMeteringAction.FLAG_AF).disableAutoCancel().build());
            }
            boolean replay = !s.manualExposure() && lastIso > 0 && lastExposureNs > 0 && manualSensor();
            locked = true;
            lockIso = replay ? lastIso : 0;
            lockExposureNs = replay ? lastExposureNs : 0;
            lockFocusDiopters = -1f;
            overrideIso = 0;
            overrideExposureNs = 0;
            aeLockOn = true;
            awbLockOn = true;
            settling = false;
            Log.i(TAG, replay ? String.format(Locale.ROOT, "lock: 1/%.0f s at ISO %d", 1e9 / lastExposureNs, lastIso)
                    : "lock: AE lock");
            applyRequestOptions();
        });
    }

    public void unlock3A() {
        main.execute(() -> {
            locked = false;
            settling = false;
            lockIso = 0;
            lockExposureNs = 0;
            lockFocusDiopters = -1f;
            overrideIso = 0;
            overrideExposureNs = 0;
            aeLockOn = false;
            awbLockOn = false;
            if (camera == null) return;
            camera.getCameraControl().cancelFocusAndMetering();
            applyRequestOptions();  // drops the lock options
        });
    }

    // ---- Stills while the table turns ------------------------------------------

    /** Rebinds stills for capture on the move (true) or back to maximum quality (false). Blocking. */
    public void setFlyingStills(boolean on) throws Exception {
        CompletableFuture<Void> f = new CompletableFuture<>();
        main.execute(() -> {
            try {
                flyingStills = on;
                if (provider != null) bindStills();
                f.complete(null);
            } catch (RuntimeException e) {
                f.completeExceptionally(e);
            }
        });
        f.get(10, TimeUnit.SECONDS);
    }

    /** True if the light box profile sets ISO and shutter by hand (so spins shouldn't change them). */
    public boolean hasManualExposure() {
        return settings.manualExposure();
    }

    /** Exposure time and ISO auto exposure chose for the latest preview frame; 0 if unknown. */
    public long autoExposureNs() {
        return lastExposureNs;
    }

    public int autoIso() {
        return lastIso;
    }

    /** The sensor's ISO range, or null before the camera is bound. */
    public Range<Integer> isoRange() {
        Camera c = camera;
        return c == null ? null : Camera2CameraInfo.from(c.getCameraInfo())
                .getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
    }

    /**
     * Manual exposure for this binding only (not saved to the profile), while locked: the
     * spin stills. The video's rebind drops it, going back to the exposure locked at the start.
     */
    public void overrideExposure(int iso, long exposureNs) {
        main.execute(() -> {
            overrideIso = iso;
            overrideExposureNs = exposureNs;
            applyRequestOptions();
        });
    }

    /** A still and the sensor timestamp of its exposure (elapsedRealtimeNanos clock). */
    public static final class TimedStill {
        public final long sensorTimestampNs;
        public final int rotationDegrees;
        public final byte[] jpeg;

        TimedStill(long sensorTimestampNs, int rotationDegrees, byte[] jpeg) {
            this.sensorTimestampNs = sensorTimestampNs;
            this.rotationDegrees = rotationDegrees;
            this.jpeg = jpeg;
        }
    }

    /** Takes a still right away and returns it in memory, with its sensor timestamp. Non-blocking. */
    public CompletableFuture<TimedStill> captureNow() {
        CompletableFuture<TimedStill> f = new CompletableFuture<>();
        main.execute(() -> imageCapture.takePicture(stillExecutor, new ImageCapture.OnImageCapturedCallback() {
            @Override
            public void onCaptureSuccess(@NonNull ImageProxy image) {
                try {
                    ByteBuffer b = image.getPlanes()[0].getBuffer();
                    byte[] jpeg = new byte[b.remaining()];
                    b.get(jpeg);
                    f.complete(new TimedStill(image.getImageInfo().getTimestamp(),
                            image.getImageInfo().getRotationDegrees(), jpeg));
                } finally {
                    image.close();
                }
            }

            @Override
            public void onError(@NonNull ImageCaptureException e) {
                f.completeExceptionally(e);
            }
        }));
        return f;
    }

    // ---- Capture (blocking; call off the main thread) --------------------------

    public void takePicture(File dest) throws Exception {
        CompletableFuture<Void> done = new CompletableFuture<>();
        main.execute(() -> imageCapture.takePicture(
                new ImageCapture.OutputFileOptions.Builder(dest).build(), main,
                new ImageCapture.OnImageSavedCallback() {
                    @Override
                    public void onImageSaved(@NonNull ImageCapture.OutputFileResults r) {
                        done.complete(null);
                    }

                    @Override
                    public void onError(@NonNull ImageCaptureException e) {
                        done.completeExceptionally(e);
                    }
                }));
        done.get(15, TimeUnit.SECONDS);
    }

    /** Switches to the video binding and starts recording; returns the start time (elapsedRealtimeNanos). */
    @SuppressLint("MissingPermission")  // no audio is recorded
    public long startRecording(File dest) throws Exception {
        CompletableFuture<Long> started = new CompletableFuture<>();
        recordingDone = new CompletableFuture<>();
        main.execute(() -> {
            bindVideo();  // keeps the capture's lock (lock3A), if it holds one
            recording = videoCapture.getOutput()
                    .prepareRecording(context, new FileOutputOptions.Builder(dest).build())
                    .start(main, event -> {
                        if (event instanceof VideoRecordEvent.Start) {
                            // Image timestamps use the same clock on most phones (SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME).
                            started.complete(SystemClock.elapsedRealtimeNanos());
                        } else if (event instanceof VideoRecordEvent.Finalize) {
                            VideoRecordEvent.Finalize f = (VideoRecordEvent.Finalize) event;
                            if (f.hasError()) recordingDone.completeExceptionally(new Exception("recording error " + f.getError()));
                            else recordingDone.complete(null);
                            bindStills();
                        }
                    });
        });
        return started.get(10, TimeUnit.SECONDS);
    }

    public void stopRecording() throws InterruptedException, ExecutionException, TimeoutException {
        main.execute(() -> {
            if (recording != null) recording.stop();
            recording = null;
        });
        recordingDone.get(30, TimeUnit.SECONDS);
    }

    public void shutdown() {
        analysisExecutor.shutdown();
        stillExecutor.shutdown();
    }
}
