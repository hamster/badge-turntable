package org.snurkle.badgeturntable.ring;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;

import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.objdetect.ArucoDetector;
import org.opencv.objdetect.DetectorParameters;
import org.opencv.objdetect.Objdetect;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * CameraX analyzer that reads the encoder ring from each frame's luma plane.
 *
 * Frames are rotated upright first (so image x runs across the turntable),
 * markers are found with OpenCV's ArUco detector, and {@link AngleFit} turns
 * their positions into the table angle. The latest reading is kept for polling,
 * and listeners get every reading (used for the spin video's angle log).
 */
public final class RingDetector implements ImageAnalysis.Analyzer {

    public interface Listener {
        void onReading(@NonNull RingReading reading);
    }

    /**
     * Sees each analysis frame after it's been read, before it's closed, on the analysis
     * thread (the remote preview encodes from here). Keep it quick.
     */
    public interface FrameTap {
        void onFrame(@NonNull ImageProxy image, @NonNull RingReading reading);
    }

    private static final String TAG = "RingDetector";

    private final ArucoDetector detector;
    private volatile RingConfig config;
    private volatile RingReading latest = RingReading.none(0);
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private volatile FrameTap frameTap;

    // Image geometry of the ring (axis column, radius, depth ratio). It only changes when the
    // camera moves or zooms, so it is learnt from frames with plenty of markers, and frames
    // with few markers solve for the angle alone (AngleFit.fitAngle). Analyzer thread only.
    /** A fit with this many markers and at most this rms is trusted to measure the geometry. */
    static final int STRONG_MARKERS = 8;
    static final double STRONG_RMS_PX = 0.8;
    /** A strong fit this far (fraction of the radius) from the reference doesn't update it... */
    static final double GEOMETRY_TOLERANCE = 0.08;
    /** ...unless this many in a row disagree: then the camera has moved, so start again. */
    static final int RESEED_AFTER = 15;
    private boolean haveGeometry;
    private double refX, refScale, refK;
    private int disagreeing;
    private volatile boolean resetGeometry;

    public RingDetector(RingConfig config) {
        this.config = config;
        DetectorParameters params = new DetectorParameters();
        // Markers on the far sides are foreshortened; let thin ones through.
        params.set_minMarkerPerimeterRate(0.01);
        // Read black bands (white-bordered markers, make-ring.mjs --invert) as well as white
        // ones. In simulation this didn't change how well normal bands are read.
        params.set_detectInvertedMarker(true);
        // Tried for the dim bench (Nov 5): CLAHE before detection, more threshold window sizes
        // and a lower threshold constant. None helped clearly enough to pay for the time (up to
        // 150 ms a frame against 30-50): the limit there was light, not the detector.
        detector = new ArucoDetector(Objdetect.getPredefinedDictionary(Objdetect.DICT_4X4_50), params);
    }

    public void setConfig(RingConfig config) {
        this.config = config;
        resetGeometry = true;
    }

    /** Forget the learnt ring geometry, e.g. after a zoom or lens change. */
    public void resetGeometry() {
        resetGeometry = true;
    }

    public RingConfig config() {
        return config;
    }

    public RingReading latest() {
        return latest;
    }

    public void addListener(Listener l) {
        listeners.add(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    public void setFrameTap(FrameTap tap) {
        frameTap = tap;
    }

    @Override
    public void analyze(@NonNull ImageProxy image) {
        try {
            RingReading reading = read(image);
            latest = reading;
            for (Listener l : listeners) l.onReading(reading);
            FrameTap tap = frameTap;
            if (tap != null) tap.onFrame(image, reading);
        } catch (RuntimeException e) {
            Log.w(TAG, "analysis failed", e);
        } finally {
            image.close();
        }
    }

    private RingReading read(ImageProxy image) {
        long startNs = System.nanoTime();
        long ts = image.getImageInfo().getTimestamp();
        ImageProxy.PlaneProxy y = image.getPlanes()[0];
        ByteBuffer buf = y.getBuffer();
        Mat gray = new Mat(image.getHeight(), image.getWidth(), CvType.CV_8UC1, buf, y.getRowStride());
        Mat upright = rotate(gray, image.getImageInfo().getRotationDegrees());

        List<Mat> corners = new ArrayList<>();
        Mat ids = new Mat();
        detector.detectMarkers(upright, corners, ids);

        RingConfig cfg = config;
        int frameW = upright.cols(), frameH = upright.rows();
        List<double[]> detected = new ArrayList<>();  // {alpha, cx, cy, id, side}
        for (int i = 0; i < corners.size(); i++) {
            int id = (int) ids.get(i, 0)[0];
            Mat c = corners.get(i);  // 1x4, CV_32FC2
            double[][] p = new double[4][];
            double cx = 0, cy = 0, side = 0;
            for (int k = 0; k < 4; k++) {
                p[k] = c.get(0, k);
                cx += p[k][0];
                cy += p[k][1];
            }
            for (int k = 0; k < 4; k++) side += Math.hypot(p[(k + 1) % 4][0] - p[k][0], p[(k + 1) % 4][1] - p[k][1]);
            c.release();
            double alpha = cfg.angleOf(id);
            if (Double.isNaN(alpha)) continue;
            detected.add(new double[]{alpha, cx / 4, cy / 4, id, side / 4});
        }
        ids.release();
        if (upright != gray) upright.release();
        gray.release();

        // Drop detections that can't be on the band (strays, misread IDs) before fitting.
        int nd = detected.size();
        double[] da = new double[nd], dx = new double[nd], dy = new double[nd], ds = new double[nd];
        for (int i = 0; i < nd; i++) {
            double[] d = detected.get(i);
            da[i] = d[0];
            dx[i] = d[1];
            dy[i] = d[2];
            ds[i] = d[4];
        }
        boolean[] keep = MarkerFilter.filter(da, dx, dy, ds, cfg.stepDeg());
        List<double[]> found = new ArrayList<>();
        List<double[]> ignored = new ArrayList<>();
        for (int i = 0; i < nd; i++) (keep[i] ? found : ignored).add(detected.get(i));
        int[] markerIds = new int[found.size()];
        float[] markerXY = new float[found.size() * 2];
        for (int i = 0; i < found.size(); i++) {
            markerIds[i] = (int) found.get(i)[3];
            markerXY[2 * i] = (float) found.get(i)[1];
            markerXY[2 * i + 1] = (float) found.get(i)[2];
        }
        float[] ignoredXY = new float[ignored.size() * 2];
        for (int i = 0; i < ignored.size(); i++) {
            ignoredXY[2 * i] = (float) ignored.get(i)[1];
            ignoredXY[2 * i + 1] = (float) ignored.get(i)[2];
        }

        if (found.size() < 3) {
            if (Log.isLoggable("RingTrace", Log.VERBOSE)) {
                StringBuilder sb = new StringBuilder().append(ts / 1_000_000).append(" few ")
                        .append(image.getWidth()).append('x').append(image.getHeight()).append(" fmt ").append(image.getFormat())
                        .append(" planes ").append(image.getPlanes().length).append(" stride ").append(image.getPlanes()[0].getRowStride())
                        .append('/').append(image.getPlanes()[0].getPixelStride()).append(" rot ")
                        .append(image.getImageInfo().getRotationDegrees()).append(": ").append(corners.size())
                        .append(" detected, ").append(nd).append(" on the ring, ").append(found.size()).append(" kept:");
                for (double[] d : detected) {
                    sb.append(' ').append((int) d[3]).append('@').append((int) d[1]).append(',').append((int) d[2])
                            .append('/').append((int) d[4]);
                }
                Log.v("RingTrace", sb.toString());
            }
            return RingReading.none(ts, frameW, frameH, markerIds, markerXY, ignoredXY);
        }
        double[] alpha = new double[found.size()];
        double[] x = new double[found.size()];
        for (int i = 0; i < found.size(); i++) {
            alpha[i] = found.get(i)[0];
            x[i] = found.get(i)[1];
        }
        AngleFit.Result r = fit(alpha, x, cfg);
        if (Log.isLoggable("RingTrace", Log.VERBOSE)) {
            // Raw detections, so a bad fit can be replayed offline: "alpha:x" pairs.
            StringBuilder sb = new StringBuilder().append(ts / 1_000_000).append(" raw")
                    .append(" ").append(image.getWidth()).append('x').append(image.getHeight())
                    .append(" ").append((System.nanoTime() - startNs) / 1_000_000).append("ms");
            for (int i = 0; i < alpha.length; i++) sb.append(' ').append((int) alpha[i]).append(':').append((int) x[i]);
            for (double[] d : ignored) sb.append(" ignored ").append((int) d[0]).append(':').append((int) d[1]).append(',').append((int) d[2]);
            Log.v("RingTrace", sb.toString());
        }
        if (r == null) return RingReading.none(ts, frameW, frameH, markerIds, markerXY, ignoredXY);
        return new RingReading(r.thetaDeg, r.markers, r.rmsPx, ts, r.xAxis, r.radius,
                frameW, frameH, markerIds, markerXY, ignoredXY);
    }

    /** The full fit when it's well conditioned (and learns the geometry from it); otherwise angle only. */
    private AngleFit.Result fit(double[] alpha, double[] x, RingConfig cfg) {
        if (resetGeometry) {
            resetGeometry = false;
            haveGeometry = false;
            disagreeing = 0;
        }
        AngleFit.Result full = AngleFit.fit(alpha, x);
        boolean strong = full != null && full.markers >= STRONG_MARKERS && full.rmsPx <= STRONG_RMS_PX;
        if (strong) {
            boolean agrees = haveGeometry
                    && Math.abs(full.radius - refScale) <= GEOMETRY_TOLERANCE * refScale
                    && Math.abs(full.xAxis - refX) <= GEOMETRY_TOLERANCE * refScale;
            if (!haveGeometry || (!agrees && ++disagreeing >= RESEED_AFTER)) {
                refX = full.xAxis;
                refScale = full.radius;
                refK = full.depthRatio;
                haveGeometry = true;
                disagreeing = 0;
                return full;
            }
            if (agrees) {
                disagreeing = 0;
                refX += 0.1 * (full.xAxis - refX);
                refScale += 0.1 * (full.radius - refScale);
                refK += 0.1 * (full.depthRatio - refK);
                return full;
            }
            // Strong but off the reference: probably the camera moving. Fall through for now.
        }
        if (!haveGeometry) return full;
        return AngleFit.fitAngle(alpha, x, refX, refScale, refK, cfg.stepDeg());
    }

    private static Mat rotate(Mat src, int degrees) {
        int code;
        switch (degrees) {
            case 90: code = Core.ROTATE_90_CLOCKWISE; break;
            case 180: code = Core.ROTATE_180; break;
            case 270: code = Core.ROTATE_90_COUNTERCLOCKWISE; break;
            default: return src;
        }
        Mat dst = new Mat();
        Core.rotate(src, dst, code);
        return dst;
    }
}
