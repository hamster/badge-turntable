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

    private static final String TAG = "RingDetector";

    private final ArucoDetector detector;
    private volatile RingConfig config;
    private volatile RingReading latest = RingReading.none(0);
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    public RingDetector(RingConfig config) {
        this.config = config;
        DetectorParameters params = new DetectorParameters();
        // Markers on the far sides are foreshortened; let thin ones through.
        params.set_minMarkerPerimeterRate(0.01);
        detector = new ArucoDetector(Objdetect.getPredefinedDictionary(Objdetect.DICT_4X4_50), params);
    }

    public void setConfig(RingConfig config) {
        this.config = config;
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

    @Override
    public void analyze(@NonNull ImageProxy image) {
        try {
            RingReading reading = read(image);
            latest = reading;
            for (Listener l : listeners) l.onReading(reading);
        } catch (RuntimeException e) {
            Log.w(TAG, "analysis failed", e);
        } finally {
            image.close();
        }
    }

    private RingReading read(ImageProxy image) {
        long ts = image.getImageInfo().getTimestamp();
        ImageProxy.PlaneProxy y = image.getPlanes()[0];
        ByteBuffer buf = y.getBuffer();
        Mat gray = new Mat(image.getHeight(), image.getWidth(), CvType.CV_8UC1, buf, y.getRowStride());
        Mat upright = rotate(gray, image.getImageInfo().getRotationDegrees());

        List<Mat> corners = new ArrayList<>();
        Mat ids = new Mat();
        detector.detectMarkers(upright, corners, ids);

        RingConfig cfg = config;
        List<double[]> found = new ArrayList<>();
        for (int i = 0; i < corners.size(); i++) {
            int id = (int) ids.get(i, 0)[0];
            double alpha = cfg.angleOf(id);
            if (Double.isNaN(alpha)) continue;
            Mat c = corners.get(i);  // 1x4, CV_32FC2
            double cx = 0;
            for (int k = 0; k < 4; k++) cx += c.get(0, k)[0];
            found.add(new double[]{alpha, cx / 4});
            c.release();
        }
        ids.release();
        if (upright != gray) upright.release();
        gray.release();

        if (found.size() < 3) return RingReading.none(ts);
        double[] alpha = new double[found.size()];
        double[] x = new double[found.size()];
        for (int i = 0; i < found.size(); i++) {
            alpha[i] = found.get(i)[0];
            x[i] = found.get(i)[1];
        }
        AngleFit.Result r = AngleFit.fit(alpha, x);
        if (r == null) return RingReading.none(ts);
        return new RingReading(r.thetaDeg, r.markers, r.rmsPx, ts, r.xAxis, r.radius);
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
