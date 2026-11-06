package ru.pda.tvhome;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class TileCropTest {
    @Test public void zeroPreservesCenterCrop() {
        assertEquals(1f, TileCrop.scale(320, 180, 320, 180, 0), .0001f);
        assertEquals(2f, TileCrop.scale(640, 360, 320, 180, 0), .0001f);
    }
    @Test public void fivePercentRemovesSixteenSourcePixelsPerHorizontalEdge() {
        float scale = TileCrop.scale(320, 180, 320, 180, 5);
        assertEquals(288f, 320f / scale, .001f);
        assertEquals(162f, 180f / scale, .001f);
    }
    @Test public void nonMatchingAspectRatioStillFillsCardWithoutDistortion() {
        assertEquals(2f, TileCrop.scale(320, 180, 160, 100, 0), .0001f);
        assertEquals(3f, TileCrop.scale(320, 180, 200, 60, 0), .0001f);
    }
    @Test public void unsupportedPersistedChoicesRestoreOriginalFraming() {
        assertEquals(0, TileCrop.chosen(-1));
        assertEquals(0, TileCrop.chosen(6));
        assertEquals(5, TileCrop.chosen(5));
        assertEquals(1f, TileCrop.scale(0, 180, 320, 180, 5), .0001f);
    }
}
