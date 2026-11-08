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

    /**
     * Where the ring should sit, as fractions of the upright frame. The Capture screen draws
     * this box, and markers are only searched for in it (plus SEARCH_MARGIN): about a quarter
     * of the frame, so detection takes about a quarter of the time.
     */
    public static final float ZONE_LEFT = 0.12f, ZONE_TOP = 0.72f, ZONE_RIGHT = 0.88f, ZONE_BOTTOM = 1.0f;
    /** How far (fraction of the frame) beyond the zone markers are still searched for. */
    static final float SEARCH_MARGIN = 0.05f;
    /**
     * While the zone holds too few markers to fit, the whole frame is scanned this often, so
     * the Capture screen can still show a ring that's outside the box ("move it into the box").
     */
    static final long FULL_SCAN_EVERY_NS = 500_000_000L;
    // Analyzer thread only.
    private int lastFound;
    private long lastFullScanNs;

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
        // Nov 7, black band under room light (15 frames from the phone, replayed offline): the
        // default constant 7 thresholded most tiles away, 2.3 markers a frame, never 8. At 2,
        // 11 a frame, every frame 8+, and no misread ids. 1 found a little more at twice the time.
        params.set_adaptiveThreshConstant(2);
        // ArUco3's faster candidate search: same markers there, about 25% less time.
        params.set_useAruco3Detection(true);
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
            // A failed read is a frame with no reading, not a lost frame: listeners and the
            // remote preview still get it, so a detector fault can't freeze the preview.
            RingReading reading;
            try {
                reading = read(image);
            } catch (RuntimeException e) {
                warn("ring read failed", e);
                reading = RingReading.none(image.getImageInfo().getTimestamp());
            }
            latest = reading;
            for (Listener l : listeners) {
                try {
                    l.onReading(reading);
                } catch (RuntimeException e) {
                    warn("ring listener failed", e);
                }
            }
            FrameTap tap = frameTap;
            if (tap != null) {
                try {
                    tap.onFrame(image, reading);
                } catch (RuntimeException e) {
                    warn("frame tap failed", e);
                }
            }
        } finally {
            image.close();
        }
    }

    // Analyzer thread only. A fault tends to repeat every frame, so log it at most every few seconds.
    private static final long WARN_EVERY_NS = 5_000_000_000L;
    private long lastWarnNs;
    private int suppressedWarnings;

    private void warn(String what, RuntimeException e) {
        long now = System.nanoTime();
        if (lastWarnNs != 0 && now - lastWarnNs < WARN_EVERY_NS) {
            suppressedWarnings++;
            return;
        }
        String more = suppressedWarnings > 0 ? " (" + suppressedWarnings + " more since the last report)" : "";
        Log.w(TAG, what + more, e);
        lastWarnNs = now;
        suppressedWarnings = 0;
    }

    private RingReading read(ImageProxy image) {
        long startNs = System.nanoTime();
        long ts = image.getImageInfo().getTimestamp();
        ImageProxy.PlaneProxy y = image.getPlanes()[0];
        ByteBuffer buf = y.getBuffer();
        Mat gray = new Mat(image.getHeight(), image.getWidth(), CvType.CV_8UC1, buf, y.getRowStride());
        Mat upright = rotate(gray, image.getImageInfo().getRotationDegrees());

        int frameW = upright.cols(), frameH = upright.rows();
        // Search only the ring zone, unless the last frame couldn't fit and a full scan is due.
        // (adb shell setprop log.tag.RingFullFrame VERBOSE turns the crop off, for comparison.)
        boolean fullScan = (lastFound < 3 && startNs - lastFullScanNs >= FULL_SCAN_EVERY_NS)
                || Log.isLoggable("RingFullFrame", Log.VERBOSE);
        int ox = 0, oy = 0;
        Mat search = upright;
        if (fullScan) {
            lastFullScanNs = startNs;
        } else {
            ox = clamp((int) ((ZONE_LEFT - SEARCH_MARGIN) * frameW), 0, frameW - 1);
            oy = clamp((int) ((ZONE_TOP - SEARCH_MARGIN) * frameH), 0, frameH - 1);
            int ex = clamp((int) Math.ceil((ZONE_RIGHT + SEARCH_MARGIN) * frameW), ox + 1, frameW);
            int ey = clamp((int) Math.ceil((ZONE_BOTTOM + SEARCH_MARGIN) * frameH), oy + 1, frameH);
            // Copied out of the frame, not used as a view: OpenCV 5.0's ArUco finds nothing in a
            // non-continuous submat (4.x is fine). The copy is about 0.5 MB, well under a ms.
            Mat view = upright.submat(oy, ey, ox, ex);
            search = view.clone();
            view.release();
        }

        List<Mat> corners = new ArrayList<>();
        Mat ids = new Mat();
        detector.detectMarkers(search, corners, ids);
        if (search != upright) search.release();

        RingConfig cfg = config;
        // OpenCV 4 gave ids as an Nx1 column; OpenCV 5 gives a 1xN row (vectors became 1-D
        // Mats), so ids.get(i, 0) is null for i > 0. Read both layouts by element count.
        int[] idList = readInts(ids);
        List<double[]> detected = new ArrayList<>();  // {alpha, cx, cy, id, side}
        int nc = Math.min(corners.size(), idList.length);
        if (nc != corners.size() || nc != idList.length) {
            Log.w(TAG, corners.size() + " marker corner sets but " + idList.length + " ids; using " + nc);
        }
        for (int i = 0; i < nc; i++) {
            int id = idList[i];
            float[] p = readFloats(corners.get(i));  // 4 points, x,y interleaved
            if (p.length != 8) continue;
            double cx = 0, cy = 0, side = 0;
            for (int k = 0; k < 4; k++) {
                p[2 * k] += ox;  // back to whole-frame coordinates
                p[2 * k + 1] += oy;
            }
            for (int k = 0; k < 4; k++) {
                cx += p[2 * k];
                cy += p[2 * k + 1];
                int n = (k + 1) % 4;
                side += Math.hypot(p[2 * n] - p[2 * k], p[2 * n + 1] - p[2 * k + 1]);
            }
            double alpha = cfg.angleOf(id);
            if (Double.isNaN(alpha)) continue;
            detected.add(new double[]{alpha, cx / 4, cy / 4, id, side / 4});
        }
        for (Mat c : corners) c.release();
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
        lastFound = found.size();
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

    /**
     * All of an int Mat's elements in order, whatever its shape (Nx1, 1xN or 1-D). Detector
     * outputs are continuous, so reshaping to a single-channel column is a free view.
     */
    static int[] readInts(Mat m) {
        int n = (int) (m.total() * m.channels());
        int[] out = new int[n];
        if (n == 0) return out;
        Mat src = m.isContinuous() ? m : m.clone();
        Mat col = src.reshape(1, n);
        if (col.depth() != CvType.CV_32S) {
            Mat conv = new Mat();
            col.convertTo(conv, CvType.CV_32S);
            col.release();
            col = conv;
        }
        col.get(0, 0, out);
        col.release();
        if (src != m) src.release();
        return out;
    }

    /** All of a float Mat's channel values in order (x0, y0, x1, y1, ... for points), whatever its shape. */
    static float[] readFloats(Mat m) {
        int n = (int) (m.total() * m.channels());
        float[] out = new float[n];
        if (n == 0) return out;
        Mat src = m.isContinuous() ? m : m.clone();
        Mat col = src.reshape(1, n);
        if (col.depth() != CvType.CV_32F) {
            Mat conv = new Mat();
            col.convertTo(conv, CvType.CV_32F);
            col.release();
            col = conv;
        }
        col.get(0, 0, out);
        col.release();
        if (src != m) src.release();
        return out;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
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
