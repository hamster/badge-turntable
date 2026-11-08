package org.snurkle.badgeturntable.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import org.snurkle.badgeturntable.capture.CaptureSequencer;
import org.snurkle.badgeturntable.media.SideBars;
import org.snurkle.badgeturntable.ring.RingDetector;
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
    /** Where the ring should sit, as fractions of the frame; the detector searches only here. */
    static final RectF RING_ZONE = new RectF(RingDetector.ZONE_LEFT, RingDetector.ZONE_TOP,
            RingDetector.ZONE_RIGHT, RingDetector.ZONE_BOTTOM);
    /** Enough markers for a strong fit (RingDetector.STRONG_MARKERS). */
    private static final int GOOD_MARKERS = 8;
    /** The axis counts as centred within this fraction of the frame width. */
    private static final float CENTRED = 0.02f;
    static final int GREEN = 0xFF4CD964;
    private static final int AMBER = 0xFFF2B134, CYAN = 0xFF5AC8FA, GUIDE = 0x66FFFFFF;

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

    /**
     * Degrees from the front (Set front), NaN if there's no front or no reading. Shown on the
     * picture when it's near: amber with the offset, green "At the front" within tolerance,
     * so the table can be turned there by hand while watching the preview.
     */
    private double frontOffset = Double.NaN;
    /** Further than this from the front, nothing is shown (the left column still has it). */
    private static final double FRONT_SHOW_WITHIN_DEG = 30;

    /** The guides on/off button, in the picture's top right corner (the columns have no room). */
    public interface GuidesListener {
        void onGuidesChanged(boolean on);
    }

    private GuidesListener guidesListener;
    private final RectF guidesButton = new RectF();
    private final Paint buttonFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint buttonLine = stroke(Color.WHITE, 1.5f);
    private boolean pressingGuides;

    public void setGuidesListener(GuidesListener l) {
        guidesListener = l;
    }

    /** A rounded square with a 3x3 grid: bright when the guides are on, dim when off. */
    private void drawGuidesButton(Canvas c) {
        float size = 36 * dp, pad = 8 * dp;
        guidesButton.set(image.right - pad - size, image.top + pad, image.right - pad, image.top + pad + size);
        buttonFill.setColor(pressingGuides ? 0xCC000000 : 0x80000000);
        float r = 6 * dp;
        c.drawRoundRect(guidesButton, r, r, buttonFill);
        buttonLine.setAlpha(guides ? 255 : 110);
        float in = 8 * dp, l = guidesButton.left + in, t = guidesButton.top + in;
        float w = guidesButton.width() - 2 * in;
        c.drawRect(l, t, l + w, t + w, buttonLine);
        for (int i = 1; i <= 2; i++) {
            c.drawLine(l + w * i / 3, t, l + w * i / 3, t + w, buttonLine);
            c.drawLine(l, t + w * i / 3, l + w, t + w * i / 3, buttonLine);
        }
    }

    /** Handles a touch on the guides button; true if it was one. */
    private boolean touchGuidesButton(MotionEvent e) {
        // A finger-sized target around the drawn square.
        float slop = 8 * dp;
        boolean inside = e.getX() >= guidesButton.left - slop && e.getX() <= guidesButton.right + slop
                && e.getY() >= guidesButton.top - slop && e.getY() <= guidesButton.bottom + slop;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (!inside || guidesButton.isEmpty()) return false;
                pressingGuides = true;
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
                if (!pressingGuides) return false;
                pressingGuides = false;
                if (inside) {
                    setGuidesVisible(!guides);
                    if (guidesListener != null) guidesListener.onGuidesChanged(guides);
                    performClick();
                }
                invalidate();
                return true;
            case MotionEvent.ACTION_CANCEL:
                if (!pressingGuides) return false;
                pressingGuides = false;
                invalidate();
                return true;
            default:
                return pressingGuides;
        }
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    /** Called when the user lets go of a side bar they dragged. Main thread. */
    public interface BarsListener {
        void onBarsMoved(float left, float right);
    }

    // Side bars: what's past them is painted over in the stills and spin, so it's shaded here.
    private SideBars bars = SideBars.NONE;
    private boolean barsEditable;
    private BarsListener barsListener;
    /** 0 none, 1 the left bar, 2 the right bar. */
    private int dragging;
    private float dragLeft, dragRight;
    private final Paint barShade = new Paint();
    private final Paint barLine = stroke(Color.WHITE, 2);
    private final Paint barHandle = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint barGrip = stroke(0xFF333333, 1.5f);
    private final RectF handleRect = new RectF();
    /** How close (each side of the line) a touch must land to grab a bar. */
    private static final float GRAB_DP = 24;

    public FramingOverlay(Context context, AttributeSet attrs) {
        super(context, attrs);
        centre.setPathEffect(new DashPathEffect(new float[]{8 * dp, 6 * dp}, 0));
        label.setTextSize(13 * dp);
        label.setShadowLayer(3 * dp, 0, 0, Color.BLACK);
        barShade.setColor(0xA6000000);
        barHandle.setColor(Color.WHITE);
    }

    /** Degrees from the front, or NaN for none. Main thread. */
    public void setFront(double offDeg) {
        if (Double.compare(offDeg, frontOffset) == 0) return;
        frontOffset = offDeg;
        invalidate();
    }

    private void drawFront(Canvas c) {
        double off = frontOffset;
        if (Double.isNaN(off) || Math.abs(off) > FRONT_SHOW_WITHIN_DEG) return;
        boolean at = Math.abs(off) <= CaptureSequencer.FRONT_TOLERANCE_DEG;
        String text = at ? "At the front" : String.format(java.util.Locale.ROOT, "Front %+.1f°", off);
        label.setColor(at ? GREEN : AMBER);
        float size = label.getTextSize();
        label.setTextSize(size * 1.3f);  // bigger than the other labels: it's read from a step back
        c.drawText(text, image.left + 10 * dp, image.top + 26 * dp, label);
        label.setTextSize(size);
    }

    /** The side bars to show (from the light box profile). Ignored while one is being dragged. */
    public void setBars(SideBars b) {
        bars = b;
        invalidate();
    }

    /** Whether the bars can be dragged (not while a capture runs). */
    public void setBarsEditable(boolean editable, BarsListener listener) {
        barsEditable = editable;
        barsListener = listener;
        if (!editable) dragging = 0;
        invalidate();
    }

    /**
     * Drags a side bar by its line. Touches anywhere else return false, so they fall through
     * to the preview underneath (tap to focus, pinch to zoom).
     */
    @Override
    @SuppressLint("ClickableViewAccessibility")
    public boolean onTouchEvent(MotionEvent e) {
        if (touchGuidesButton(e)) return true;
        if (!bars.enabled || !barsEditable || image.width() <= 0) return false;
        float x = e.getX();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                if (e.getY() < image.top || e.getY() > image.bottom) return false;
                float grab = GRAB_DP * dp;
                float dl = Math.abs(x - x(bars.left)), dr = Math.abs(x - x(1 - bars.right));
                if (Math.min(dl, dr) > grab) return false;
                dragging = dl <= dr ? 1 : 2;
                dragLeft = bars.left;
                dragRight = bars.right;
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            }
            case MotionEvent.ACTION_MOVE:
                if (dragging == 0) return false;
                float f = (x - image.left) / image.width();
                if (dragging == 1) dragLeft = SideBars.clamp(f);
                else dragRight = SideBars.clamp(1 - f);
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (dragging == 0) return false;
                dragging = 0;
                // Shown where it was let go until the profile's change comes back.
                bars = new SideBars(true, dragLeft, dragRight);
                invalidate();
                if (barsListener != null) barsListener.onBarsMoved(dragLeft, dragRight);
                return true;
            default:
                return dragging != 0;
        }
    }

    private void drawBars(Canvas c) {
        if (!bars.enabled) return;
        float left = dragging != 0 ? dragLeft : bars.left, right = dragging != 0 ? dragRight : bars.right;
        float lx = x(left), rx = x(1 - right);
        if (lx > image.left) c.drawRect(image.left, image.top, lx, image.bottom, barShade);
        if (rx < image.right) c.drawRect(rx, image.top, image.right, image.bottom, barShade);
        c.drawLine(lx, image.top, lx, image.bottom, barLine);
        c.drawLine(rx, image.top, rx, image.bottom, barLine);
        if (!barsEditable) return;
        // A grip on each line, so it reads as draggable.
        float hw = 7 * dp, hh = 26 * dp, cy = image.centerY(), r = 4 * dp;
        for (float hx : new float[]{lx, rx}) {
            handleRect.set(hx - hw, cy - hh, hx + hw, cy + hh);
            c.drawRoundRect(handleRect, r, r, barHandle);
            for (int i = -1; i <= 1; i++) c.drawLine(hx - hw / 2, cy + i * 5 * dp, hx + hw / 2, cy + i * 5 * dp, barGrip);
        }
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
        drawBars(c);  // under the guides and markers
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

        drawFront(c);
        drawGuidesButton(c);

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
