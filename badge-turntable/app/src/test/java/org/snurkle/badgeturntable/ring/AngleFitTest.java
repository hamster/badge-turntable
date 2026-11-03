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

    @Test
    public void needsThreeMarkers() {
        assertNull(AngleFit.fit(new double[]{0, 10}, new double[]{600, 680}));
    }
}
