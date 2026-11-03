package org.snurkle.badgeturntable.ring;

/**
 * How the printed ring is laid out. Matches the generator in apps/encoder-ring:
 * marker k (ArUco DICT_4X4_50 id k) is centred at k * 360 / markerCount degrees.
 */
public final class RingConfig {
    public final int markerCount;
    /** True if the strips were applied upside down, so ids increase right to left. */
    public final boolean reversed;

    public RingConfig(int markerCount, boolean reversed) {
        if (markerCount < 4 || markerCount > 50) throw new IllegalArgumentException("markerCount " + markerCount);
        this.markerCount = markerCount;
        this.reversed = reversed;
    }

    public double stepDeg() {
        return 360.0 / markerCount;
    }

    /** Ring angle of a marker id, with the direction applied; NaN for ids not on this ring. */
    public double angleOf(int id) {
        if (id < 0 || id >= markerCount) return Double.NaN;
        double a = id * stepDeg();
        return reversed ? (360 - a) % 360 : a;
    }

    public RingConfig withReversed(boolean r) {
        return new RingConfig(markerCount, r);
    }
}
