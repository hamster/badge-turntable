package org.snurkle.badgeturntable.ui;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class EvTextTest {
    @Test
    public void stopsWithASign() {
        assertEquals("0", CaptureActivity.evText(0, 1 / 6.0));
        assertEquals("+0.2", CaptureActivity.evText(1, 1 / 6.0));
        assertEquals("+0.3", CaptureActivity.evText(2, 1 / 6.0));
        assertEquals("−1.0", CaptureActivity.evText(-3, 1 / 3.0));
        assertEquals("+2.0", CaptureActivity.evText(12, 1 / 6.0));
    }

    @Test
    public void stepsBeforeTheCameraReportsItsStep() {
        assertEquals("+2", CaptureActivity.evText(2, 0));
        assertEquals("−1", CaptureActivity.evText(-1, 0));
    }
}
