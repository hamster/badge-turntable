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

        Result(double thetaDeg, double xAxis, double radius, double rmsPx, int markers) {
            this.thetaDeg = thetaDeg;
            this.xAxis = xAxis;
            this.radius = radius;
            this.rmsPx = rmsPx;
            this.markers = markers;
        }

        @Override
        public String toString() {
            return String.format("theta=%.2f xAxis=%.1f R=%.1f rms=%.2fpx n=%d",
                    thetaDeg, xAxis, radius, rmsPx, markers);
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
                    double score = Math.abs(x[i] - predict(without, alphaDeg[i])) / rest;
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

        return new Result(thetaOf(p), p[0], Math.hypot(p[1], p[2]), rms, count(use));
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
        // Gaussian elimination with partial pivoting.
        for (int col = 0; col < 3; col++) {
            int piv = col;
            for (int r = col + 1; r < 3; r++) if (Math.abs(m[r][col]) > Math.abs(m[piv][col])) piv = r;
            if (Math.abs(m[piv][col]) < 1e-9) return null;
            double[] tmp = m[col]; m[col] = m[piv]; m[piv] = tmp;
            for (int r = 0; r < 3; r++) {
                if (r == col) continue;
                double f = m[r][col] / m[col][col];
                for (int c = col; c < 4; c++) m[r][c] -= f * m[col][c];
            }
        }
        return new double[]{m[0][3] / m[0][0], m[1][3] / m[1][1], m[2][3] / m[2][2]};
    }
}
