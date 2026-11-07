package org.snurkle.badgeturntable.ring;

/** One frame's view of the encoder ring. */
public final class RingReading {
    private static final int[] NO_IDS = new int[0];
    private static final float[] NO_XY = new float[0];

    /** Ring angle facing the camera, degrees in [0, 360), or NaN when the ring wasn't read. */
    public final double angleDeg;
    public final int markers;
    public final double rmsPx;
    /** Frame timestamp, same clock as CameraX image timestamps (nanoseconds). */
    public final long timestampNs;
    /** Axis column and rim radius in analysis-frame pixels, for the preview overlay. */
    public final double xAxis;
    public final double radiusPx;
    /** Upright analysis frame size, in pixels (0 if unknown). */
    public final int frameWidth, frameHeight;
    /** The markers used for the fit (even when it failed): IDs, and centres as x0, y0, x1, y1… in upright pixels. */
    public final int[] markerIds;
    public final float[] markerXY;
    /** Detections {@link MarkerFilter} left out (not on the band, out of order): centres, as above. */
    public final float[] ignoredXY;

    public RingReading(double angleDeg, int markers, double rmsPx, long timestampNs, double xAxis, double radiusPx) {
        this(angleDeg, markers, rmsPx, timestampNs, xAxis, radiusPx, 0, 0, NO_IDS, NO_XY, NO_XY);
    }

    public RingReading(double angleDeg, int markers, double rmsPx, long timestampNs, double xAxis, double radiusPx,
                       int frameWidth, int frameHeight, int[] markerIds, float[] markerXY, float[] ignoredXY) {
        this.angleDeg = angleDeg;
        this.markers = markers;
        this.rmsPx = rmsPx;
        this.timestampNs = timestampNs;
        this.xAxis = xAxis;
        this.radiusPx = radiusPx;
        this.frameWidth = frameWidth;
        this.frameHeight = frameHeight;
        this.markerIds = markerIds;
        this.markerXY = markerXY;
        this.ignoredXY = ignoredXY;
    }

    public static RingReading none(long timestampNs) {
        return new RingReading(Double.NaN, 0, 0, timestampNs, 0, 0);
    }

    /** No angle, but the frame size and whatever markers were seen. */
    public static RingReading none(long timestampNs, int frameWidth, int frameHeight, int[] markerIds,
                                   float[] markerXY, float[] ignoredXY) {
        return new RingReading(Double.NaN, 0, 0, timestampNs, 0, 0, frameWidth, frameHeight, markerIds, markerXY, ignoredXY);
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
