package org.snurkle.badgeturntable.ring;

/**
 * Table angle from the horizontal positions of visible ring markers.
 *
 * A marker at ring angle a sits at x = xAxis + R sin(a - theta) in the image, where
 * theta is the ring angle facing the camera. Expanding the sine makes that linear:
 * x = xAxis + A sin a + B cos a, with A = R cos theta and B = -R sin theta. So a
 * weighted least-squares fit over three or more markers gives theta, the axis column
 * and the radius in one step. See apps/encoder-ring/README.md.
 *
 * That model assumes a distant camera. In a light box the camera is only a few rim
 * radii away, so markers nearer the camera look further apart, and the linear fit is
 * off by up to 3-4 degrees. With five or more markers the fit is refined with the
 * perspective model x = xAxis + s sin(a - theta) / (1 - k cos(a - theta)), where
 * k = rim radius / camera distance, by a few Gauss-Newton steps from the linear fit.
 * In simulation (146 mm rim, camera 300-600 mm away) that cut the error from
 * 1-2 degrees rms to 0.05 degrees.
 *
 * Pure Java so it can be unit tested on the JVM.
 */
public final class AngleFit {

    /** Fit result. {@code thetaDeg} is in [0, 360). */
    public static final class Result {
        public final double thetaDeg;
        public final double xAxis;
        public final double radius;
        public final double rmsPx;
        public final int markers;
        /** Rim radius / camera distance from the perspective fit; 0 if the linear fit was used. */
        public final double depthRatio;

        Result(double thetaDeg, double xAxis, double radius, double rmsPx, int markers, double depthRatio) {
            this.thetaDeg = thetaDeg;
            this.xAxis = xAxis;
            this.radius = radius;
            this.rmsPx = rmsPx;
            this.markers = markers;
            this.depthRatio = depthRatio;
        }

        @Override
        public String toString() {
            return String.format(java.util.Locale.ROOT, "theta=%.2f xAxis=%.1f R=%.1f rms=%.2fpx n=%d k=%.3f",
                    thetaDeg, xAxis, radius, rmsPx, markers, depthRatio);
        }
    }

    private AngleFit() {}

    /**
     * @param alphaDeg ring angle of each detected marker (id * step, sign-corrected)
     * @param x        horizontal image position of each marker's centre, in pixels
     * @return the fit, or null with fewer than 3 usable markers
     */
    public static Result fit(double[] alphaDeg, double[] x) {
        int n = alphaDeg.length;
        if (n != x.length) throw new IllegalArgumentException("length mismatch");
        if (n < 3) return null;

        boolean[] use = new boolean[n];
        java.util.Arrays.fill(use, true);
        double[] w = new double[n];
        java.util.Arrays.fill(w, 1.0);

        // First pass unweighted, then weight by cos(a - theta): markers near the
        // edges are foreshortened and their centres are less precise.
        double[] p = solve(alphaDeg, x, w, use);
        if (p == null) return null;
        for (int pass = 0; pass < 2; pass++) {
            double theta = thetaOf(p);
            for (int i = 0; i < n; i++) {
                w[i] = Math.max(0.05, Math.cos(Math.toRadians(alphaDeg[i] - theta)));
            }
            double[] next = solve(alphaDeg, x, w, use);
            if (next == null) break;
            p = next;
        }

        // Drop up to two clear outliers (misread IDs). An outlier drags a plain fit
        // towards itself, so each marker is judged against a fit made without it.
        double rms = rms(alphaDeg, x, p, use);
        // A misread id puts a marker at least one marker spacing from where it should be.
        // Smaller misfits are the linear model's perspective error, so keep those markers.
        double minOutlierPx = 0.33 * Math.hypot(p[1], p[2]) * Math.toRadians(minSpacingDeg(alphaDeg));
        for (int round = 0; round < 2 && count(use) > 4; round++) {
            int worst = -1;
            double worstScore = 0;
            double[] worstFit = null;
            double worstRms = 0;
            for (int i = 0; i < n; i++) {
                if (!use[i]) continue;
                use[i] = false;
                double[] without = solve(alphaDeg, x, w, use);
                if (without != null) {
                    double rest = Math.max(rms(alphaDeg, x, without, use), 0.5);
                    double miss = Math.abs(x[i] - predict(without, alphaDeg[i]));
                    double score = miss < minOutlierPx ? 0 : miss / rest;
                    if (score > worstScore) {
                        worstScore = score;
                        worst = i;
                        worstFit = without;
                        worstRms = rms(alphaDeg, x, without, use);
                    }
                }
                use[i] = true;
            }
            if (worst < 0 || worstScore < 6) break;
            use[worst] = false;
            p = worstFit;
            rms = worstRms;
        }

        Result linear = new Result(thetaOf(p), p[0], Math.hypot(p[1], p[2]), rms, count(use), 0);
        if (linear.markers < 5) return linear;
        Result persp = refinePerspective(alphaDeg, x, w, use, linear);
        return persp != null ? persp : linear;
    }

    /**
     * Table angle only, with the ring's image geometry already known (from earlier frames
     * with plenty of markers; it holds while the camera doesn't move).
     *
     * With few markers on a short arc, {@link #fit} has to find the axis column and radius
     * as well, and that is badly conditioned: on the test bench, frames with 3-6 markers
     * gave radii from 205 to 350 px (true 225) and angles off by 10-160 degrees. With the
     * geometry fixed, every marker gives the angle on its own, so two agreeing markers are
     * enough. Each marker proposes an angle, the proposal most markers agree with wins
     * (misread ids land at least a marker spacing away and are dropped), and the angle is
     * then refined over the agreeing markers.
     *
     * @param xAxis    axis column, pixels
     * @param scale    rim radius in pixels ({@link Result#radius})
     * @param k        depth ratio ({@link Result#depthRatio}), 0 for the linear model
     * @param stepDeg  angle between neighbouring markers on the ring
     * @return the fit, or null unless at least two markers, and at least half of them, agree
     */
    public static Result fitAngle(double[] alphaDeg, double[] x, double xAxis, double scale, double k, double stepDeg) {
        int n = alphaDeg.length;
        if (n != x.length) throw new IllegalArgumentException("length mismatch");
        if (n < 2 || !(scale > 0)) return null;
        // Noise is well under a pixel; a misread id is off by a spacing (times cos phi).
        double tolPx = Math.max(2.0, 0.2 * scale * Math.toRadians(stepDeg));

        double best = Double.NaN;
        int bestCount = 0;
        double bestCost = Double.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            double ph = phaseOf((x[i] - xAxis) / scale, k);
            if (Double.isNaN(ph)) continue;
            double theta = alphaDeg[i] - Math.toDegrees(ph);
            int count = 0;
            double cost = 0;
            for (int j = 0; j < n; j++) {
                double e = residual(alphaDeg[j], x[j], theta, xAxis, scale, k);
                if (Math.abs(e) < tolPx) {
                    count++;
                    cost += e * e;
                }
            }
            if (count > bestCount || (count == bestCount && cost < bestCost)) {
                best = theta;
                bestCount = count;
                bestCost = cost;
            }
        }
        if (bestCount < 2 || 2 * bestCount < n) return null;

        // Gauss-Newton on theta over the agreeing markers; then re-pick them once.
        double th = Math.toRadians(best);
        boolean[] use = new boolean[n];
        for (int round = 0; round < 2; round++) {
            int count = 0;
            for (int j = 0; j < n; j++) {
                use[j] = Math.abs(residual(alphaDeg[j], x[j], Math.toDegrees(th), xAxis, scale, k)) < tolPx;
                if (use[j]) count++;
            }
            if (count < 2 || 2 * count < n) return null;
            for (int iter = 0; iter < 10; iter++) {
                double num = 0, den = 0;
                for (int j = 0; j < n; j++) {
                    if (!use[j]) continue;
                    double ph = Math.toRadians(alphaDeg[j]) - th;
                    double cos = Math.cos(ph), d = 1 - k * cos;
                    double r = x[j] - (xAxis + scale * Math.sin(ph) / d);
                    double jac = -scale * (cos - k) / (d * d);  // d(predicted x)/d(theta)
                    double w = Math.max(0.05, cos);
                    num += w * jac * r;
                    den += w * jac * jac;
                }
                if (den <= 0) return null;
                double step = num / den;
                th += step;
                if (Math.abs(step) < 1e-10) break;
            }
        }
        double sum = 0;
        int used = 0;
        for (int j = 0; j < n; j++) {
            if (!use[j]) continue;
            double e = residual(alphaDeg[j], x[j], Math.toDegrees(th), xAxis, scale, k);
            sum += e * e;
            used++;
        }
        double t = Math.toDegrees(th) % 360;
        if (t < 0) t += 360;
        return new Result(t, xAxis, scale, Math.sqrt(sum / used), used, k);
    }

    /** Image residual of one marker for a given table angle and fixed geometry; huge if it'd be on the back. */
    private static double residual(double alphaDeg, double x, double thetaDeg, double xAxis, double scale, double k) {
        double ph = Math.toRadians(alphaDeg - thetaDeg);
        double cos = Math.cos(ph);
        if (cos <= 0) return Double.MAX_VALUE;  // on the far side of the ring: can't be seen
        return x - (xAxis + scale * Math.sin(ph) / (1 - k * cos));
    }

    /** Front-side phase phi with sin(phi) / (1 - k cos(phi)) = u, or NaN if there is none. */
    private static double phaseOf(double u, double k) {
        if (Math.abs(u) > 1.2) return Double.NaN;
        double ph = Math.asin(Math.max(-1, Math.min(1, u)));
        for (int i = 0; i < 8; i++) {
            double g = Math.sin(ph) - u * (1 - k * Math.cos(ph));
            double dg = Math.cos(ph) - u * k * Math.sin(ph);
            if (Math.abs(dg) < 1e-12) break;
            ph -= g / dg;
        }
        return Math.abs(ph) < Math.PI / 2 && Double.isFinite(ph) ? ph : Double.NaN;
    }

    private static final double MAX_DEPTH_RATIO = 0.6;  // camera closer than 1.7 rim radii: not plausible

    /**
     * Gauss-Newton on [xAxis, s, theta, k] for x = xAxis + s sin(phi) / (1 - k cos(phi)),
     * phi = a - theta. Starts from the linear fit (k = 0). Null if it doesn't converge.
     */
    private static Result refinePerspective(double[] alphaDeg, double[] x, double[] w, boolean[] use, Result start) {
        double x0 = start.xAxis, s = start.radius, th = Math.toRadians(start.thetaDeg), k = 0;
        for (int iter = 0; iter < 30; iter++) {
            double[][] m = new double[4][5];
            for (int i = 0; i < x.length; i++) {
                if (!use[i]) continue;
                double ph = Math.toRadians(alphaDeg[i]) - th;
                double sin = Math.sin(ph), cos = Math.cos(ph), den = 1 - k * cos;
                double r = x[i] - (x0 + s * sin / den);
                double[] j = {1, sin / den, -s * (cos - k) / (den * den), s * sin * cos / (den * den)};
                for (int a = 0; a < 4; a++) {
                    for (int b = 0; b < 4; b++) m[a][b] += w[i] * j[a] * j[b];
                    m[a][4] += w[i] * j[a] * r;
                }
            }
            double[] d = gauss(m, 4);
            if (d == null) return null;
            x0 += d[0];
            s += d[1];
            th += d[2];
            k = Math.min(Math.max(k + d[3], 0), MAX_DEPTH_RATIO);
            if (!Double.isFinite(x0 + s + th + k)) return null;
            if (Math.abs(d[2]) < 1e-9 && Math.abs(d[0]) < 1e-6) {
                double sum = 0;
                int n = 0;
                for (int i = 0; i < x.length; i++) {
                    if (!use[i]) continue;
                    double ph = Math.toRadians(alphaDeg[i]) - th;
                    double e = x[i] - (x0 + s * Math.sin(ph) / (1 - k * Math.cos(ph)));
                    sum += e * e;
                    n++;
                }
                double t = Math.toDegrees(th) % 360;
                if (t < 0) t += 360;
                return new Result(t, x0, s, Math.sqrt(sum / n), n, k);
            }
        }
        return null;
    }

    /** Smallest gap between distinct marker angles, i.e. the ring's step (at most 90). */
    private static double minSpacingDeg(double[] alphaDeg) {
        double[] a = alphaDeg.clone();
        java.util.Arrays.sort(a);
        double min = 90;
        for (int i = 1; i < a.length; i++) {
            double d = a[i] - a[i - 1];
            if (d > 1e-6 && d < min) min = d;
        }
        return min;
    }

    private static int count(boolean[] use) {
        int k = 0;
        for (boolean u : use) if (u) k++;
        return k;
    }

    private static double thetaOf(double[] p) {
        double t = Math.toDegrees(Math.atan2(-p[2], p[1]));
        return t < 0 ? t + 360 : t;
    }

    private static double predict(double[] p, double alphaDeg) {
        double a = Math.toRadians(alphaDeg);
        return p[0] + p[1] * Math.sin(a) + p[2] * Math.cos(a);
    }

    private static double rms(double[] alphaDeg, double[] x, double[] p, boolean[] use) {
        double s = 0;
        int k = 0;
        for (int i = 0; i < x.length; i++) {
            if (!use[i]) continue;
            double e = x[i] - predict(p, alphaDeg[i]);
            s += e * e;
            k++;
        }
        return k == 0 ? 0 : Math.sqrt(s / k);
    }

    /** Weighted normal equations for [xAxis, A, B]; null if singular. */
    private static double[] solve(double[] alphaDeg, double[] x, double[] w, boolean[] use) {
        double[][] m = new double[3][4];
        for (int i = 0; i < x.length; i++) {
            if (!use[i]) continue;
            double a = Math.toRadians(alphaDeg[i]);
            double[] row = {1, Math.sin(a), Math.cos(a)};
            for (int r = 0; r < 3; r++) {
                for (int c = 0; c < 3; c++) m[r][c] += w[i] * row[r] * row[c];
                m[r][3] += w[i] * row[r] * x[i];
            }
        }
        return gauss(m, 3);
    }

    /** Solves the n x (n+1) augmented system in place (Gauss-Jordan, partial pivoting); null if singular. */
    private static double[] gauss(double[][] m, int n) {
        for (int col = 0; col < n; col++) {
            int piv = col;
            for (int r = col + 1; r < n; r++) if (Math.abs(m[r][col]) > Math.abs(m[piv][col])) piv = r;
            if (Math.abs(m[piv][col]) < 1e-9) return null;
            double[] tmp = m[col]; m[col] = m[piv]; m[piv] = tmp;
            for (int r = 0; r < n; r++) {
                if (r == col) continue;
                double f = m[r][col] / m[col][col];
                for (int c = col; c <= n; c++) m[r][c] -= f * m[col][c];
            }
        }
        double[] out = new double[n];
        for (int i = 0; i < n; i++) out[i] = m[i][n] / m[i][i];
        return out;
    }
}
