package org.snurkle.badgeturntable.media;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SideBarsTest {
    private static final int W = 400, H = 300;

    /** Background whose brightness falls from top to bottom (as the light box's does), with dark junk at both edges. */
    private static int[] scene() {
        int[] px = new int[W * H];
        for (int y = 0; y < H; y++) {
            int v = 240 - y * 100 / H;
            for (int x = 0; x < W; x++) {
                boolean junk = x < 30 || x >= W - 25;  // the light box's edges
                int c = junk ? 20 : v;
                px[y * W + x] = 0xFF000000 | c << 16 | c << 8 | c;
            }
        }
        return px;
    }

    private static int grey(int argb) {
        return argb & 0xFF;
    }

    @Test
    public void offOrZeroChangesNothing() {
        int[] before = scene();
        int[] px = before.clone();
        new SideBars(false, 0.2f, 0.2f).apply(px, W, H);
        assertArrayEquals(before, px);
        new SideBars(true, 0, 0).apply(px, W, H);
        assertArrayEquals(before, px);
        assertFalse(new SideBars(true, 0, 0).active());
        assertTrue(new SideBars(true, 0.1f, 0).active());
    }

    @Test
    public void paintsPastTheBarsWithTheBackgroundOfEachRow() {
        int[] px = scene();
        new SideBars(true, 0.1f, 0.1f).apply(px, W, H);  // bars at x = 40 and 360
        for (int y = 0; y < H; y += 37) {
            int expected = 240 - y * 100 / H;
            // The junk is gone from both edges, replaced by that row's background (within the row spread).
            for (int x : new int[]{0, 10, 29, 39, 360, 380, W - 1}) {
                assertEquals("x " + x + " y " + y, expected, grey(px[y * W + x]), 2);
            }
        }
    }

    @Test
    public void leavesTheMiddleAlone() {
        int[] before = scene();
        int[] px = before.clone();
        SideBars b = new SideBars(true, 0.1f, 0.1f);
        b.apply(px, W, H);
        int feather = Math.max(2, Math.round(SideBars.FEATHER * W));
        for (int y = 0; y < H; y++) {
            for (int x = 40 + feather; x < 360 - feather; x++) {
                assertEquals(before[y * W + x], px[y * W + x]);
            }
        }
    }

    @Test
    public void featherBlendsWithoutASeam() {
        int[] px = new int[W * H];
        java.util.Arrays.fill(px, 0xFF808080);
        for (int y = 0; y < H; y++) px[y * W + 41] = 0xFF000000;  // a dark line just inside the bar
        new SideBars(true, 0.1f, 0).apply(px, W, H);
        // Left of the bar: the fill (the line pulls the strip's mean down a little, no more).
        assertEquals(0x80, grey(px[10]), 0x80 / 6 + 1);
        // On the line, in the feather: partly blended towards the fill, not left black.
        assertTrue(grey(px[41]) > 0);
    }

    @Test
    public void clampsPositions() {
        SideBars b = new SideBars(true, 0.9f, -1f);
        assertEquals(SideBars.MAX, b.left, 0);
        assertEquals(0f, b.right, 0);
        assertEquals(0f, new SideBars(true, Float.NaN, 0).left, 0);
    }
}
