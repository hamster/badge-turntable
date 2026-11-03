package org.snurkle.badgeturntable.camera;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.camera2.CaptureRequest;
import android.os.SystemClock;
import android.util.Log;
import android.util.Size;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.camera.camera2.interop.Camera2CameraControl;
import androidx.camera.camera2.interop.CaptureRequestOptions;
import androidx.camera.camera2.interop.ExperimentalCamera2Interop;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.FocusMeteringAction;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.MeteringPoint;
import androidx.camera.core.Preview;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;
import androidx.camera.lifecycle.ProcessCameraProvider;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Owns the CameraX session: preview, high-resolution stills, the ring analyzer
 * and (when needed) video. Stills and video use separate bindings, because
 * many phones can't run maximum-quality capture, analysis and recording at once.
 *
 * Manual controls go through Camera2 interop and are saved as the "light box
 * profile", since the light box doesn't change between badges.
 *
 * The blocking methods (takePicture, stopRecording) must not be called on the
 * main thread; the capture sequencer runs on its own thread.
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
    }

    private final Context context;
    private final Executor main;
    private final ExecutorService analysisExecutor = Executors.newSingleThreadExecutor();
    private final SharedPreferences prefs;

    private ProcessCameraProvider provider;
    private LifecycleOwner owner;
    private PreviewView previewView;
    private ImageAnalysis.Analyzer analyzer;

    private Preview preview;
    private ImageCapture imageCapture;
    private ImageAnalysis analysis;
    private VideoCapture<Recorder> videoCapture;
    private Camera camera;
    private Recording recording;
    private CompletableFuture<Void> recordingDone;
    private volatile long recordingStartNs;

    public CameraController(Context context) {
        this.context = context.getApplicationContext();
        this.main = ContextCompat.getMainExecutor(context);
        this.prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
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
            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "camera provider failed", e);
            }
        }, main);
    }

    private void bindStills() {
        provider.unbindAll();
        preview = new Preview.Builder().build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());
        imageCapture = new ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setJpegQuality(95)
                .setResolutionSelector(new ResolutionSelector.Builder()
                        .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY).build())
                .build();
        analysis = buildAnalysis();
        camera = provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture, analysis);
        applyProfile();
    }

    private void bindVideo() {
        provider.unbindAll();
        preview = new Preview.Builder().build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());
        Recorder recorder = new Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.FHD))
                .build();
        videoCapture = VideoCapture.withOutput(recorder);
        analysis = buildAnalysis();
        camera = provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, videoCapture, analysis);
        applyProfile();
    }

    private ImageAnalysis buildAnalysis() {
        ImageAnalysis a = new ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setResolutionSelector(new ResolutionSelector.Builder()
                        .setResolutionStrategy(new ResolutionStrategy(new Size(1280, 720),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)).build())
                .build();
        if (analyzer != null) a.setAnalyzer(analysisExecutor, analyzer);
        return a;
    }

    // ---- Controls (saved to the light box profile) -----------------------------

    public void focusAt(float viewX, float viewY) {
        if (camera == null) return;
        MeteringPoint p = previewView.getMeteringPointFactory().createPoint(viewX, viewY);
        camera.getCameraControl().startFocusAndMetering(
                new FocusMeteringAction.Builder(p).disableAutoCancel().build());
    }

    public void setExposureCompensation(int index) {
        prefs.edit().putInt("ev", index).apply();
        if (camera != null) camera.getCameraControl().setExposureCompensationIndex(index);
    }

    public int exposureCompensation() {
        return prefs.getInt("ev", 0);
    }

    public void setZoomRatio(float ratio) {
        prefs.edit().putFloat("zoom", ratio).apply();
        if (camera != null) camera.getCameraControl().setZoomRatio(ratio);
    }

    public void setWhiteBalance(WhiteBalance wb) {
        prefs.edit().putString("wb", wb.name()).apply();
        applyProfile();
    }

    public WhiteBalance whiteBalance() {
        return WhiteBalance.valueOf(prefs.getString("wb", WhiteBalance.AUTO.name()));
    }

    /** Manual ISO and shutter; pass iso <= 0 to return to auto exposure. */
    public void setManualExposure(int iso, long exposureNs) {
        prefs.edit().putInt("iso", iso).putLong("shutterNs", exposureNs).apply();
        applyProfile();
    }

    /** Manual focus distance in diopters; pass a negative value to return to autofocus. */
    public void setFocusDistance(float diopters) {
        prefs.edit().putFloat("focus", diopters).apply();
        applyProfile();
    }

    private void applyProfile() {
        if (camera == null) return;
        camera.getCameraControl().setZoomRatio(prefs.getFloat("zoom", 1f));
        camera.getCameraControl().setExposureCompensationIndex(prefs.getInt("ev", 0));
        CaptureRequestOptions.Builder b = new CaptureRequestOptions.Builder()
                .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, whiteBalance().mode);
        int iso = prefs.getInt("iso", 0);
        if (iso > 0) {
            b.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                    .setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, iso)
                    .setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, prefs.getLong("shutterNs", 10_000_000L));
        }
        float focus = prefs.getFloat("focus", -1f);
        if (focus >= 0) {
            b.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                    .setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, focus);
        }
        Camera2CameraControl.from(camera.getCameraControl()).setCaptureRequestOptions(b.build());
    }

    /** Locks AE and AWB (and focus, via a centre metering lock) so every view matches. */
    public void lock3A() {
        main.execute(() -> {
            if (camera == null) return;
            if (prefs.getFloat("focus", -1f) < 0) {
                MeteringPoint centre = previewView.getMeteringPointFactory()
                        .createPoint(previewView.getWidth() / 2f, previewView.getHeight() / 2f);
                camera.getCameraControl().startFocusAndMetering(
                        new FocusMeteringAction.Builder(centre, FocusMeteringAction.FLAG_AF).disableAutoCancel().build());
            }
            Camera2CameraControl.from(camera.getCameraControl()).addCaptureRequestOptions(
                    new CaptureRequestOptions.Builder()
                            .setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, true)
                            .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, true)
                            .build());
        });
    }

    public void unlock3A() {
        main.execute(() -> {
            if (camera == null) return;
            camera.getCameraControl().cancelFocusAndMetering();
            applyProfile();  // replaces the lock options
        });
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
            bindVideo();
            lock3A();
            recording = videoCapture.getOutput()
                    .prepareRecording(context, new FileOutputOptions.Builder(dest).build())
                    .start(main, event -> {
                        if (event instanceof VideoRecordEvent.Start) {
                            // Image timestamps use the same clock on most phones (SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME).
                            recordingStartNs = SystemClock.elapsedRealtimeNanos();
                            started.complete(recordingStartNs);
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
    }
}
