package org.snurkle.badgeturntable.media;

/**
 * Side bars: for badges narrower than the light box, everything left of {@code left} and
 * right of {@code 1 - right} (fractions of the upright frame's width) is painted over with
 * the background just inside the bar, so the light box's edges don't show.
 *
 * The fill is worked out row by row, so it follows the light box's fall-off from top to
 * bottom: the average of a small grid of samples in a strip just inside the bar (STRIP wide,
 * NX across, NY rows spread over ROW_SPREAD), with a short FEATHER inside the bar blending
 * back into the picture so there's no seam. {@link SideBarsEffect} does the same on the GPU
 * for the spin video, so the stills and the video match.
 *
 * Immutable. Applied to the views and the spin, not extra shots (which may be reframed or
 * hand-held outside the light box).
 */
public final class SideBars {
    /** Neither bar may cover more than this fraction of the width. */
    public static final float MAX = 0.4f;
    /** Where the bars start when first turned on with no positions set. */
    public static final float DEFAULT = 0.08f;

    // Fractions of the frame width (STRIP, FEATHER) or height (ROW_SPREAD). Shared with the shader.
    static final float STRIP = 0.012f;
    static final float FEATHER = 0.006f;
    // ROW_SPREAD: 0.008 copied each line of a cutting mat's grid across as a streak (Nov 7
    // spin test); 0.04 blurs them into an even band and only softens the wall-floor edge.
    static final float ROW_SPREAD = 0.04f;
    static final int NX = 6, NY = 9;

    public static final SideBars NONE = new SideBars(false, 0, 0);

    public final boolean enabled;
    public final float left, right;

    public SideBars(boolean enabled, float left, float right) {
        this.enabled = enabled;
        this.left = clamp(left);
        this.right = clamp(right);
    }

    /** Whether anything is painted over. */
    public boolean active() {
        return enabled && (left > 0 || right > 0);
    }

    public static float clamp(float f) {
        return Float.isNaN(f) ? 0 : Math.max(0, Math.min(MAX, f));
    }

    /**
     * Paints the bars over an ARGB image (row-major, width w, height h, as Bitmap.getPixels
     * gives it), in place. The fills come from the unchanged picture.
     */
    public void apply(int[] px, int w, int h) {
        if (!active() || w < 8 || h < 1) return;
        int strip = Math.max(NX, Math.round(STRIP * w));
        int feather = Math.max(2, Math.round(FEATHER * w));
        int spread = Math.max(0, Math.round(ROW_SPREAD * h / 2));
        int l = Math.round(left * w);
        int r = w - Math.round(right * w);  // first column of the right bar
        int[] fillL = l > 0 ? fills(px, w, h, l, strip, spread, +1) : null;
        int[] fillR = r < w ? fills(px, w, h, r - 1, strip, spread, -1) : null;
        for (int y = 0; y < h; y++) {
            int row = y * w;
            if (fillL != null) {
                int f = fillL[y];
                for (int x = 0; x < l; x++) px[row + x] = f;
                for (int x = l; x < Math.min(l + feather, r); x++) {
                    px[row + x] = mix(f, px[row + x], (x - l + 0.5f) / feather);
                }
            }
            if (fillR != null) {
                int f = fillR[y];
                for (int x = r; x < w; x++) px[row + x] = f;
                for (int x = Math.max(r - feather, l); x < r; x++) {
                    px[row + x] = mix(f, px[row + x], (r - x - 0.5f) / feather);
                }
            }
        }
    }

    /** The fill for each row: the mean of NX x NY samples in the strip from column edge, inwards (dir). */
    private static int[] fills(int[] px, int w, int h, int edge, int strip, int spread, int dir) {
        int[] xs = new int[NX];
        for (int i = 0; i < NX; i++) {
            xs[i] = Math.max(0, Math.min(w - 1, edge + dir * (int) ((i + 0.5f) * strip / NX)));
        }
        int[] out = new int[h];
        for (int y = 0; y < h; y++) {
            long a = 0, rr = 0, g = 0, b = 0;
            int n = 0;
            for (int j = 0; j < NY; j++) {
                int sy = NY == 1 ? y : y - spread + Math.round(j * 2f * spread / (NY - 1));
                sy = Math.max(0, Math.min(h - 1, sy));
                for (int x : xs) {
                    int c = px[sy * w + x];
                    a += c >>> 24;
                    rr += (c >> 16) & 0xFF;
                    g += (c >> 8) & 0xFF;
                    b += c & 0xFF;
                    n++;
                }
            }
            out[y] = (int) ((a / n) << 24 | (rr / n) << 16 | (g / n) << 8 | (b / n));
        }
        return out;
    }

    /** t = 0 gives a, t = 1 gives b. */
    private static int mix(int a, int b, float t) {
        int out = 0;
        for (int shift = 0; shift <= 24; shift += 8) {
            int ca = (a >>> shift) & 0xFF, cb = (b >>> shift) & 0xFF;
            out |= Math.round(ca + (cb - ca) * t) << shift;
        }
        return out;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof SideBars)) return false;
        SideBars s = (SideBars) o;
        return enabled == s.enabled && left == s.left && right == s.right;
    }

    @Override
    public int hashCode() {
        return (enabled ? 1 : 0) + 31 * Float.floatToIntBits(left) + 961 * Float.floatToIntBits(right);
    }

    @Override
    public String toString() {
        return "SideBars{" + (enabled ? "on" : "off") + " " + left + ", " + right + "}";
    }
}
