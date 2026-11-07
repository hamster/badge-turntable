package org.snurkle.badgeturntable.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import org.snurkle.badgeturntable.ring.RingReading;

/**
 * Drawn over the preview (which shows the whole 4:3 frame, as the stills are): a thirds grid
 * and centre line for framing the badge, the box the encoder ring should sit in, a small box
 * on each ring marker being read, and the turntable axis the ring fit found. When the axis
 * sits on the centre line, the camera is centred on the table.
 *
 * Marker and axis positions come from the analysis frame, which has the same 4:3 field of
 * view as the preview.
 */
public final class FramingOverlay extends View {
    /** Where the ring should sit, as fractions of the frame: left, top, right, bottom. */
    static final RectF RING_ZONE = new RectF(0.12f, 0.68f, 0.88f, 0.96f);
    /** Enough markers for a strong fit (RingDetector.STRONG_MARKERS). */
    private static final int GOOD_MARKERS = 8;
    /** The axis counts as centred within this fraction of the frame width. */
    private static final float CENTRED = 0.02f;
    private static final int GREEN = 0xFF4CD964, AMBER = 0xFFF2B134, CYAN = 0xFF5AC8FA, GUIDE = 0x66FFFFFF;

    private final float dp = getResources().getDisplayMetrics().density;
    private final Paint guide = stroke(GUIDE, 1);
    private final Paint centre = stroke(0x99FFFFFF, 1.5f);
    private final Paint zone = stroke(GUIDE, 2);
    private final Paint marker = stroke(GREEN, 2);
    private final Paint axis = stroke(CYAN, 2);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF image = new RectF();
    private final RectF box = new RectF();
    private final Paint focus = stroke(Color.WHITE, 2);
    private final Paint ignored = stroke(0xFFFF453A, 2);
    /**
     * The axis line, smoothed: with few markers each frame's fit moves it a little. 0.15 a
     * frame follows a real move (the camera nudged) in about half a second.
     */
    private static final float AXIS_SMOOTHING = 0.15f;
    private float axisX = Float.NaN;
    private volatile RingReading reading;
    private boolean guides = true;
    /**
     * The marker count changes from frame to frame (5, 8, 6…), so the box's state only changes
     * once the new one has held for STEADY_MS, and the count shown is the best of the last second.
     */
    private static final long STEADY_MS = 600, BEST_OF_MS = 1000;
    private int shownState = -1, pendingState = -1;
    private long pendingSince;
    private final long[] countAt = new long[64];
    private final int[] counts = new int[64];
    private int countN;
    private float focusX, focusY;
    private long focusUntilMs;
    private static final long FOCUS_SHOW_MS = 1500;

    public FramingOverlay(Context context, AttributeSet attrs) {
        super(context, attrs);
        centre.setPathEffect(new DashPathEffect(new float[]{8 * dp, 6 * dp}, 0));
        label.setTextSize(13 * dp);
        label.setShadowLayer(3 * dp, 0, 0, Color.BLACK);
    }

    private Paint stroke(int color, float widthDp) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setColor(color);
        p.setStrokeWidth(widthDp * getResources().getDisplayMetrics().density);
        return p;
    }

    /** The latest ring reading; any thread. */
    public void setReading(RingReading r) {
        reading = r;
        postInvalidateOnAnimation();
    }

    /** Marks where a tap focused, for a moment. View coordinates. */
    public void showFocus(float x, float y) {
        focusX = x;
        focusY = y;
        focusUntilMs = android.os.SystemClock.uptimeMillis() + FOCUS_SHOW_MS;
        invalidate();
        postInvalidateDelayed(FOCUS_SHOW_MS + 50);
    }

    /** Grid, centre line and ring zone on or off. Markers and the axis always show. */
    public void setGuidesVisible(boolean on) {
        guides = on;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        RingReading r = reading;
        float aspect = r != null && r.frameWidth > 0 && r.frameHeight > 0 ? (float) r.frameWidth / r.frameHeight : 4f / 3;
        float w = getWidth(), h = getHeight();
        float iw = Math.min(w, h * aspect), ih = iw / aspect;
        image.set((w - iw) / 2, (h - ih) / 2, (w + iw) / 2, (h + ih) / 2);
        box.set(x(RING_ZONE.left), y(RING_ZONE.top), x(RING_ZONE.right), y(RING_ZONE.bottom));

        boolean ok = r != null && r.ok();
        int inside = 0, seen = r == null ? 0 : r.markerIds.length;
        if (r != null && r.frameWidth > 0) {
            for (int i = 0; i < seen; i++) {
                if (box.contains(x(r.markerXY[2 * i] / r.frameWidth), y(r.markerXY[2 * i + 1] / r.frameHeight))) inside++;
            }
        }
        boolean good = ok && r.markers >= GOOD_MARKERS && inside == seen;
        long now = android.os.SystemClock.uptimeMillis();
        int best = bestRecent(now, ok ? r.markers : seen);
        int state = best == 0 ? 0 : inside < seen ? 1 : good || best >= GOOD_MARKERS ? 3 : 2;
        if (state != pendingState) {
            pendingState = state;
            pendingSince = now;
        }
        if (shownState < 0 || now - pendingSince >= STEADY_MS) shownState = pendingState;
        else postInvalidateDelayed(STEADY_MS);

        if (guides) {
            for (int i = 1; i <= 2; i++) {
                c.drawLine(x(i / 3f), image.top, x(i / 3f), image.bottom, guide);
                c.drawLine(image.left, y(i / 3f), image.right, y(i / 3f), guide);
            }
            c.drawLine(x(0.5f), image.top, x(0.5f), image.bottom, centre);
            zone.setColor(shownState == 0 ? GUIDE : shownState == 3 ? GREEN : AMBER);
            float corner = 6 * dp;
            c.drawRoundRect(box, corner, corner, zone);
            String text;
            if (shownState == 0) text = "Ring here";
            else if (shownState == 1) text = "Ring: move it into the box";
            else if (shownState == 2) text = "Ring: " + Math.max(best, 1) + " markers, want " + GOOD_MARKERS + "+";
            else text = "Ring: " + best + " markers";
            label.setColor(zone.getColor() == GUIDE ? Color.WHITE : zone.getColor());
            c.drawText(text, box.left + 8 * dp, box.top - 6 * dp, label);
        }

        if (android.os.SystemClock.uptimeMillis() < focusUntilMs) {
            c.drawCircle(focusX, focusY, 28 * dp, focus);
        }

        if (r == null || r.frameWidth <= 0) return;
        marker.setColor(ok ? GREEN : AMBER);
        float half = Math.max(5 * dp, image.width() * 0.008f);
        for (int i = 0; i < seen; i++) {
            float mx = x(r.markerXY[2 * i] / r.frameWidth), my = y(r.markerXY[2 * i + 1] / r.frameHeight);
            c.drawRect(mx - half, my - half, mx + half, my + half, marker);
        }
        // Detections the filter left out (not on the band, or out of order): a small red cross.
        float cross = half * 0.8f;
        for (int i = 0; i + 1 < r.ignoredXY.length; i += 2) {
            float ix = x(r.ignoredXY[i] / r.frameWidth), iy = y(r.ignoredXY[i + 1] / r.frameHeight);
            c.drawLine(ix - cross, iy - cross, ix + cross, iy + cross, ignored);
            c.drawLine(ix - cross, iy + cross, ix + cross, iy - cross, ignored);
        }
        if (ok) {
            float raw = (float) (r.xAxis / r.frameWidth);
            axisX = Float.isNaN(axisX) ? raw : axisX + AXIS_SMOOTHING * (raw - axisX);
            float fx = axisX;
            boolean centred = Math.abs(fx - 0.5f) <= CENTRED;
            axis.setColor(centred ? GREEN : CYAN);
            c.drawLine(x(fx), image.top, x(fx), box.bottom, axis);
            label.setColor(axis.getColor());
            c.drawText(centred ? "Axis centred" : "Axis", x(fx) + 6 * dp, image.top + 18 * dp, label);
        }
    }

    /** Records this frame's count and returns the most seen in the last BEST_OF_MS. */
    private int bestRecent(long now, int count) {
        if (countN == counts.length) {
            System.arraycopy(counts, 1, counts, 0, countN - 1);
            System.arraycopy(countAt, 1, countAt, 0, countN - 1);
            countN--;
        }
        counts[countN] = count;
        countAt[countN++] = now;
        int best = 0;
        for (int i = 0; i < countN; i++) if (now - countAt[i] <= BEST_OF_MS) best = Math.max(best, counts[i]);
        return best;
    }

    private float x(float fraction) {
        return image.left + fraction * image.width();
    }

    private float y(float fraction) {
        return image.top + fraction * image.height();
    }
}
