package org.snurkle.badgeturntable.ring;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class MarkerFilterTest {
    private static final double STEP = 8;  // the JAYEGT ring: 45 markers

    /** Markers on the front of a ring seen from slightly above: x along sin, y along a shallow arc. */
    private static double[][] band(double thetaDeg, int... ids) {
        double[][] m = new double[4][ids.length];  // alpha, x, y, size
        for (int i = 0; i < ids.length; i++) {
            double a = ids[i] * STEP, phi = Math.toRadians(a - thetaDeg);
            m[0][i] = a;
            m[1][i] = 800 + 300 * Math.sin(phi);
            m[2][i] = 900 + 40 * Math.cos(phi);
            m[3][i] = 22 * Math.max(0.3, Math.cos(phi));
        }
        return m;
    }

    private static boolean[] run(double[][] m) {
        return MarkerFilter.filter(m[0], m[1], m[2], m[3], STEP);
    }

    private static boolean[] all(int n) {
        boolean[] k = new boolean[n];
        java.util.Arrays.fill(k, true);
        return k;
    }

    @Test
    public void keepsAGoodBand() {
        double[][] m = band(200, 21, 22, 23, 24, 25, 26, 27, 28, 29);
        assertArrayEquals(all(9), run(m));
    }

    @Test
    public void keepsSparseButConsistentMarkers() {
        // From a real frame in poor light: 200 208 224 232 248 272 (a 3-marker gap at the end).
        double[][] m = band(230, 25, 26, 28, 29, 31, 34);
        assertArrayEquals(all(6), run(m));
    }

    @Test
    public void keepsTheLastMarkerPastAGapWhereTheBandCurvesUp() {
        // As on the bench: 184..248, then 272 far out on the right, where the ellipse rises.
        double[][] m = band(214, 23, 24, 25, 26, 27, 28, 29, 30, 31, 34);
        for (int i = 0; i < m[2].length; i++) m[2][i] = 900 + 90 * Math.cos(Math.toRadians(m[0][i] - 214));
        assertArrayEquals(all(10), run(m));
    }

    @Test
    public void dropsADetectionOffTheBand() {
        double[][] m = band(200, 21, 22, 23, 24, 25, 26, 27);
        m[2][4] -= 60;  // id 25, decoded from the platter's top print, well above the band
        boolean[] k = run(m);
        assertFalse(k[4]);
        for (int i = 0; i < k.length; i++) if (i != 4) assertTrue("marker " + i, k[i]);
    }

    @Test
    public void dropsAStrayAtTheEndWithoutItsNeighbour() {
        double[][] m = band(200, 21, 22, 23, 24, 25, 26, 27);
        m[2][6] -= 60;  // the last one, off the band
        boolean[] k = run(m);
        assertFalse(k[6]);
        for (int i = 0; i < 6; i++) assertTrue("marker " + i, k[i]);
    }

    @Test
    public void dropsAMisreadIdAmongTheRightOnes() {
        double[][] m = band(200, 21, 22, 23, 24, 25, 26, 27);
        m[0][3] = 30 * STEP;  // id 24's position, read as id 30
        boolean[] k = run(m);
        assertFalse(k[3]);
        int kept = 0;
        for (boolean b : k) if (b) kept++;
        assertEquals(6, kept);
    }

    @Test
    public void dropsAFarIdAtTheEnd() {
        // A detection just past the end of the band with an ID 15 markers on: in order, on the
        // band, but far too big a jump for its pixel gap.
        double[][] m = band(200, 21, 22, 23, 24, 25, 26);
        double[][] wide = new double[4][7];
        for (int r = 0; r < 4; r++) System.arraycopy(m[r], 0, wide[r], 0, 6);
        wide[0][6] = 41 * STEP;
        wide[1][6] = m[1][5] + (m[1][5] - m[1][4]);
        wide[2][6] = m[2][5];
        wide[3][6] = m[3][5];
        boolean[] k = run(wide);
        assertFalse(k[6]);
        for (int i = 0; i < 6; i++) assertTrue("marker " + i, k[i]);
    }

    @Test
    public void worksAcrossZero() {
        double[][] m = band(0, 42, 43, 44, 0, 1, 2, 3);
        assertArrayEquals(all(7), run(m));
    }

    @Test
    public void leavesTinySetsAlone() {
        double[][] m = band(200, 24, 25);
        assertArrayEquals(all(2), run(m));
    }
}
