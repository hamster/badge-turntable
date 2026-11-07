package org.snurkle.badgeturntable.ring;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Drops detections that can't be part of the ring band before the angle fit. With few
 * markers read, one bad detection drags the whole fit (the axis line jumps about), and the
 * fit's own outlier rejection can't tell which one is wrong.
 *
 * Three checks, in order:
 * 1. **On the band:** the band's markers lie along a smooth curve in the image (the bottom
 *    of an ellipse, close to a parabola over the visible arc). The worst detection more than
 *    {@link #OFF_BAND} marker heights (more beyond the ends, see {@link #EXTRAPOLATION_SLACK})
 *    from a parabola fitted through the others is something
 *    else in the scene decoded as a marker (the platter's top print did this); it's dropped
 *    and the curve fitted again. Needs five or more: with four, the curve through any three
 *    passes exactly through them, so any of the four could look like the odd one out.
 * 2. **In order:** going across the image, marker angles run one way. A detection out of
 *    order (a misread ID among the right ones) is dropped, keeping the longest ordered run.
 * 3. **Spacing:** the angle step between neighbours should match their pixel spacing, using
 *    the median degrees-per-pixel of the band. A step more than {@link #SPACING_SLACK} times
 *    the expected one (plus two markers, for foreshortening at the sides) splits the band,
 *    and the biggest part is kept.
 *
 * Gaps alone aren't a reason to drop a marker: in poor light only some markers decode, and
 * a marker five positions from its neighbour is often right where it should be.
 */
public final class MarkerFilter {
    static final double OFF_BAND = 1.5;
    /**
     * Extra slack per pixel a marker lies beyond the others: the curve is extrapolated there,
     * and the band rises faster at the ends than a parabola does. On the bench the last marker
     * past a gap (about 125 px out) was sometimes dropped without it; strays are hundreds of
     * pixels off, so they're still caught.
     */
    static final double EXTRAPOLATION_SLACK = 0.25;
    static final double SPACING_SLACK = 2.0;

    private MarkerFilter() {}

    /**
     * @param alphaDeg ring angle of each marker's ID
     * @param x        centre in the upright frame, pixels
     * @param y        centre, pixels
     * @param size     marker side length, pixels
     * @param stepDeg  angle between adjacent markers
     * @return which detections to keep
     */
    public static boolean[] filter(double[] alphaDeg, double[] x, double[] y, double[] size, double stepDeg) {
        int n = x.length;
        boolean[] keep = new boolean[n];
        Arrays.fill(keep, true);
        if (n < 3) return keep;
        double height = median(size);

        // 1. Off the band: each marker against the curve through the others (so a stray at
        //    the end can't bend the curve towards itself); drop the worst, then look again.
        while (count(keep) >= 5) {
            int worst = -1;
            double worstExcess = 0;
            for (int i = 0; i < n; i++) {
                if (!keep[i]) continue;
                keep[i] = false;
                double[] curve = parabola(x, y, keep);
                double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
                for (int j = 0; j < n; j++) if (keep[j]) {
                    lo = Math.min(lo, x[j]);
                    hi = Math.max(hi, x[j]);
                }
                keep[i] = true;
                if (curve == null) continue;
                double u = (x[i] - curve[3]) / curve[4];
                double dev = Math.abs(y[i] - (curve[0] + curve[1] * u + curve[2] * u * u));
                double outside = Math.max(0, Math.max(lo - x[i], x[i] - hi));
                double excess = dev - (OFF_BAND * height + EXTRAPOLATION_SLACK * outside);
                if (excess > worstExcess) {
                    worstExcess = excess;
                    worst = i;
                }
            }
            if (worst < 0) break;
            keep[worst] = false;
        }

        // 2. In order: the longest run of angles that rises (or falls) across the image.
        List<Integer> order = byX(x, keep);
        if (order.size() < 3) return keep;
        double ref = alphaDeg[order.get(order.size() / 2)];
        double[] a = new double[n];
        for (int i : order) a[i] = ref + RingReading.delta(ref, alphaDeg[i]);
        int rising = 0;
        for (int k = 1; k < order.size(); k++) rising += Double.compare(a[order.get(k)], a[order.get(k - 1)]);
        double dir = rising >= 0 ? 1 : -1;
        List<Integer> run = longestRising(order, a, dir);
        for (int i : order) keep[i] = false;
        for (int i : run) keep[i] = true;
        if (run.size() < 3) return keep;

        // 3. Spacing: split where the angle jump is far bigger than the pixel gap allows.
        double[] degPerPx = new double[run.size() - 1];
        for (int k = 1; k < run.size(); k++) {
            int i = run.get(k - 1), j = run.get(k);
            degPerPx[k - 1] = Math.abs(a[j] - a[i]) / Math.max(1e-6, Math.abs(x[j] - x[i]));
        }
        double typical = median(degPerPx);
        List<List<Integer>> parts = new ArrayList<>();
        List<Integer> part = new ArrayList<>();
        part.add(run.get(0));
        for (int k = 1; k < run.size(); k++) {
            int i = run.get(k - 1), j = run.get(k);
            double expected = typical * Math.abs(x[j] - x[i]);
            if (Math.abs(a[j] - a[i]) > SPACING_SLACK * expected + 2 * stepDeg) {
                parts.add(part);
                part = new ArrayList<>();
            }
            part.add(j);
        }
        parts.add(part);
        List<Integer> best = parts.get(0);
        for (List<Integer> p : parts) if (p.size() > best.size()) best = p;
        for (int i : run) keep[i] = false;
        for (int i : best) keep[i] = true;
        return keep;
    }

    /**
     * Least-squares y = c0 + c1 u + c2 u² with u = (x - mean) / spread, through the kept
     * markers. Returns {c0, c1, c2, mean, spread}, or null if the x values are all the same.
     */
    private static double[] parabola(double[] x, double[] y, boolean[] keep) {
        double mean = 0;
        int n = 0;
        for (int i = 0; i < x.length; i++) if (keep[i]) {
            mean += x[i];
            n++;
        }
        mean /= n;
        double spread = 0;
        for (int i = 0; i < x.length; i++) if (keep[i]) spread = Math.max(spread, Math.abs(x[i] - mean));
        if (spread < 1e-6) return null;
        double[][] a = new double[3][4];  // normal equations, augmented
        for (int i = 0; i < x.length; i++) {
            if (!keep[i]) continue;
            double u = (x[i] - mean) / spread;
            double[] p = {1, u, u * u};
            for (int r = 0; r < 3; r++) {
                for (int c = 0; c < 3; c++) a[r][c] += p[r] * p[c];
                a[r][3] += p[r] * y[i];
            }
        }
        for (int col = 0; col < 3; col++) {  // Gaussian elimination with partial pivoting
            int pivot = col;
            for (int r = col + 1; r < 3; r++) if (Math.abs(a[r][col]) > Math.abs(a[pivot][col])) pivot = r;
            double[] t = a[col];
            a[col] = a[pivot];
            a[pivot] = t;
            if (Math.abs(a[col][col]) < 1e-12) return null;
            for (int r = 0; r < 3; r++) {
                if (r == col) continue;
                double f = a[r][col] / a[col][col];
                for (int c = col; c < 4; c++) a[r][c] -= f * a[col][c];
            }
        }
        return new double[]{a[0][3] / a[0][0], a[1][3] / a[1][1], a[2][3] / a[2][2], mean, spread};
    }

    private static List<Integer> byX(double[] x, boolean[] keep) {
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < x.length; i++) if (keep[i]) order.add(i);
        order.sort((p, q) -> Double.compare(x[p], x[q]));
        return order;
    }

    /** Longest subsequence of order whose dir * a strictly rises. */
    private static List<Integer> longestRising(List<Integer> order, double[] a, double dir) {
        int m = order.size();
        int[] len = new int[m], prev = new int[m];
        int bestEnd = 0;
        for (int k = 0; k < m; k++) {
            len[k] = 1;
            prev[k] = -1;
            for (int j = 0; j < k; j++) {
                if (dir * a[order.get(j)] < dir * a[order.get(k)] && len[j] + 1 > len[k]) {
                    len[k] = len[j] + 1;
                    prev[k] = j;
                }
            }
            if (len[k] > len[bestEnd]) bestEnd = k;
        }
        List<Integer> run = new ArrayList<>();
        for (int k = bestEnd; k >= 0; k = prev[k]) run.add(0, order.get(k));
        return run;
    }

    private static int count(boolean[] keep) {
        int c = 0;
        for (boolean k : keep) if (k) c++;
        return c;
    }

    private static double median(double[] v) {
        if (v.length == 0) return 0;
        double[] s = v.clone();
        Arrays.sort(s);
        return s.length % 2 == 1 ? s[s.length / 2] : (s[s.length / 2 - 1] + s[s.length / 2]) / 2;
    }
}
