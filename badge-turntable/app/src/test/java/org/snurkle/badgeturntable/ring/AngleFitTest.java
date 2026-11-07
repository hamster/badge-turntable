package org.snurkle.badgeturntable.ring;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class AngleFitTest {

    /** Simulates the visible front of a 36-marker ring at table angle theta. */
    private static double[][] markers(double theta, double xAxis, double r, double noisePx, long seed) {
        Random rnd = new Random(seed);
        List<double[]> out = new ArrayList<>();
        for (int k = 0; k < 36; k++) {
            double alpha = k * 10.0;
            double rel = Math.toRadians(alpha - theta);
            if (Math.cos(rel) < Math.cos(Math.toRadians(70))) continue;  // hidden or too oblique
            out.add(new double[]{alpha, xAxis + r * Math.sin(rel) + noisePx * rnd.nextGaussian()});
        }
        double[] a = new double[out.size()], x = new double[out.size()];
        for (int i = 0; i < out.size(); i++) { a[i] = out.get(i)[0]; x[i] = out.get(i)[1]; }
        return new double[][]{a, x};
    }

    private static double angleError(double a, double b) {
        return Math.abs(RingReading.delta(a, b));
    }

    @Test
    public void recoversAngleWithoutNoise() {
        for (double theta : new double[]{0, 4.2, 37, 123.4, 200, 271.5, 359}) {
            double[][] m = markers(theta, 640, 500, 0, 1);
            AngleFit.Result r = AngleFit.fit(m[0], m[1]);
            assertNotNull(r);
            assertEquals("theta " + theta, 0, angleError(r.thetaDeg, theta), 1e-6);
            assertEquals(640, r.xAxis, 1e-6);
            assertEquals(500, r.radius, 1e-6);
        }
    }

    @Test
    public void toleratesHalfPixelNoise() {
        for (int seed = 0; seed < 20; seed++) {
            double theta = seed * 17.3;
            double[][] m = markers(theta, 600, 450, 0.5, seed);
            AngleFit.Result r = AngleFit.fit(m[0], m[1]);
            assertEquals(0, angleError(r.thetaDeg, theta), 0.25);
        }
    }

    @Test
    public void dropsOneMisreadMarker() {
        double[][] m = markers(90, 640, 500, 0.2, 3);
        m[1][2] += 120;  // a marker reported far from where its id says it should be
        AngleFit.Result r = AngleFit.fit(m[0], m[1]);
        assertEquals(0, angleError(r.thetaDeg, 90), 0.3);
        assertEquals(m[0].length - 1, r.markers);
    }

    /**
     * A pinhole camera 400 mm from the axis of a 146 mm rim (k = R/D = 0.18), with
     * 45 markers at 8 degrees. The linear model alone is off by about 2-4 degrees here.
     */
    @Test
    public void correctsCloseCameraPerspective() {
        double k = 73.0 / 400.0, f = 900;  // f: focal length * R / D, in pixels
        for (int seed = 0; seed < 20; seed++) {
            double theta = seed * 19.7;
            Random rnd = new Random(seed);
            List<double[]> pts = new ArrayList<>();
            for (int i = 0; i < 45; i++) {
                double alpha = i * 8.0, ph = Math.toRadians(alpha - theta);
                if (Math.cos(ph) < Math.cos(Math.toRadians(65))) continue;
                pts.add(new double[]{alpha, 500 + f * Math.sin(ph) / (1 - k * Math.cos(ph)) + 0.3 * rnd.nextGaussian()});
            }
            double[] a = new double[pts.size()], x = new double[pts.size()];
            for (int i = 0; i < pts.size(); i++) { a[i] = pts.get(i)[0]; x[i] = pts.get(i)[1]; }
            AngleFit.Result r = AngleFit.fit(a, x);
            assertEquals("theta " + theta, 0, angleError(r.thetaDeg, theta), 0.1);
            assertEquals(k, r.depthRatio, 0.02);
            assertEquals(500, r.xAxis, 1);
        }
    }

    @Test
    public void needsThreeMarkers() {
        assertNull(AngleFit.fit(new double[]{0, 10}, new double[]{600, 680}));
    }

    // ---- fitAngle: known geometry, few markers --------------------------------

    /** Perspective model for a 45-marker ring; returns markers within maxPhi of the front. */
    private static double[][] perspectiveMarkers(double theta, double x0, double s, double k, double maxPhi,
                                                 double noisePx, long seed) {
        Random rnd = new Random(seed);
        List<double[]> pts = new ArrayList<>();
        for (int i = 0; i < 45; i++) {
            double alpha = i * 8.0, ph = Math.toRadians(alpha - theta);
            if (Math.cos(ph) < Math.cos(Math.toRadians(maxPhi))) continue;
            pts.add(new double[]{alpha, x0 + s * Math.sin(ph) / (1 - k * Math.cos(ph)) + noisePx * rnd.nextGaussian()});
        }
        double[] a = new double[pts.size()], x = new double[pts.size()];
        for (int i = 0; i < pts.size(); i++) { a[i] = pts.get(i)[0]; x[i] = pts.get(i)[1]; }
        return new double[][]{a, x};
    }

    @Test
    public void fitAngleWorksWithTwoOrThreeMarkers() {
        for (int seed = 0; seed < 30; seed++) {
            double theta = seed * 12.1;
            // Only markers near the front: a narrow arc, where the full fit is unreliable.
            double[][] m = perspectiveMarkers(theta, 677, 225, 0.18, seed % 2 == 0 ? 9 : 13, 0.3, seed);
            AngleFit.Result r = AngleFit.fitAngle(m[0], m[1], 677, 225, 0.18, 8);
            assertNotNull("theta " + theta + " with " + m[0].length + " markers", r);
            assertEquals("theta " + theta, 0, angleError(r.thetaDeg, theta), 0.3);
        }
    }

    @Test
    public void fitAngleDropsMisreadIds() {
        double[][] m = perspectiveMarkers(100, 677, 225, 0.18, 45, 0.3, 7);
        m[0][1] += 8;    // one id read as its neighbour
        m[0][3] += 120;  // one read as a marker on the far side
        AngleFit.Result r = AngleFit.fitAngle(m[0], m[1], 677, 225, 0.18, 8);
        assertNotNull(r);
        assertEquals(0, angleError(r.thetaDeg, 100), 0.3);
        assertEquals(m[0].length - 2, r.markers);
    }

    @Test
    public void fitAngleRefusesWhenMarkersDisagree() {
        // Two markers whose positions can't both be right for this geometry.
        assertNull(AngleFit.fitAngle(new double[]{0, 8}, new double[]{677, 600}, 677, 225, 0.18, 8));
        assertNull(AngleFit.fitAngle(new double[]{0}, new double[]{677}, 677, 225, 0.18, 8));
    }

    /** "alpha:x alpha:x ..." as logged by RingDetector's RingTrace raw lines. */
    private static double[][] raw(String s) {
        String[] parts = s.trim().split("\\s+");
        double[] a = new double[parts.length], x = new double[parts.length];
        for (int i = 0; i < parts.length; i++) {
            String[] p = parts[i].split(":");
            a[i] = Double.parseDouble(p[0]);
            x[i] = Double.parseDouble(p[1]);
        }
        return new double[][]{a, x};
    }

    /**
     * Real frames from the JAYEGT on the test bench (Nov 4, 2026; 1280x720 analysis, table
     * turning at about 43 deg/s). The full fit got these few-marker frames badly wrong; the
     * true angles come from the 8-12 marker frames either side.
     */
    @Test
    public void fitAngleFixesRealFewMarkerFrames() {
        AngleFit.Result ref = AngleFit.fit(raw("152:727 144:687 160:767 128:604 120:567 112:534 176:834 104:505 184:861 96:481")[0],
                raw("152:727 144:687 160:767 128:604 120:567 112:534 176:834 104:505 184:861 96:481")[1]);
        assertNotNull(ref);
        assertEquals(142.2, ref.thetaDeg, 0.1);
        String[][] cases = {
                // raw detections, true angle, what the full fit said
                {"144:665 136:623 120:549 104:492 96:471", "146.2", "158.9"},
                {"144:627 136:587 176:787 128:551 120:520 112:494", "154.6", "172.4"},
                {"144:619 176:780 120:515 112:489 104:469 96:455", "155.6", "173.4"},
                {"152:645 144:605 136:567 128:533 192:835 120:505 112:482", "158.2", "25.8"},
        };
        for (String[] c : cases) {
            double[][] m = raw(c[0]);
            AngleFit.Result r = AngleFit.fitAngle(m[0], m[1], ref.xAxis, ref.radius, ref.depthRatio, 8);
            assertNotNull(c[0], r);
            assertEquals(c[0] + " (full fit said " + c[2] + ")", Double.parseDouble(c[1]), r.thetaDeg, 1.0);
        }
    }
}
