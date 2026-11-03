package org.snurkle.badgeturntable.ring;

/** One frame's view of the encoder ring. */
public final class RingReading {
    /** Ring angle facing the camera, degrees in [0, 360), or NaN when the ring wasn't read. */
    public final double angleDeg;
    public final int markers;
    public final double rmsPx;
    /** Frame timestamp, same clock as CameraX image timestamps (nanoseconds). */
    public final long timestampNs;
    /** Axis column and rim radius in analysis-frame pixels, for the preview overlay. */
    public final double xAxis;
    public final double radiusPx;

    public RingReading(double angleDeg, int markers, double rmsPx, long timestampNs, double xAxis, double radiusPx) {
        this.angleDeg = angleDeg;
        this.markers = markers;
        this.rmsPx = rmsPx;
        this.timestampNs = timestampNs;
        this.xAxis = xAxis;
        this.radiusPx = radiusPx;
    }

    public static RingReading none(long timestampNs) {
        return new RingReading(Double.NaN, 0, 0, timestampNs, 0, 0);
    }

    public boolean ok() {
        return !Double.isNaN(angleDeg);
    }

    /** Signed shortest difference b - a in degrees, in (-180, 180]. */
    public static double delta(double a, double b) {
        double d = (b - a) % 360;
        if (d > 180) d -= 360;
        if (d <= -180) d += 360;
        return d;
    }
}
