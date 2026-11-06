package com.gathin.tvhome;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class PlateSettingsTest {
    @Test public void aQuietPlateStaysPut() {
        assertEquals(7, Config.chosenPlateOpacity(7, 7));
        assertEquals(30, Config.chosenPlateOpacity(30, 7));
        assertEquals(1, Config.chosenPlateOpacity(1, 7));
        assertEquals(50, Config.chosenPlateOpacity(50, 7));
    }

    @Test public void fullOpacityRangeAndInvalidValues() {
        // Both endpoints are supported; invalid persisted values use the default.
        assertEquals(0, Config.chosenPlateOpacity(0, 7));
        assertEquals(100, Config.chosenPlateOpacity(100, 7));
        assertEquals(7, Config.chosenPlateOpacity(101, 7));
        assertEquals(7, Config.chosenPlateOpacity(-1, 7));
    }

    @Test public void blurRangeIncludesBothEndpoints() {
        assertEquals(0, Config.chosenPlateBlur(0, 5));
        assertEquals(5, Config.chosenPlateBlur(5, 5));
        assertEquals(10, Config.chosenPlateBlur(10, 5));
    }

    @Test public void anOutOfRangeSoftEdgeIsIgnored() {
        // The slider supports zero through ten.
        assertEquals(5, Config.chosenPlateBlur(-1, 5));
        assertEquals(5, Config.chosenPlateBlur(11, 5));
    }
}
