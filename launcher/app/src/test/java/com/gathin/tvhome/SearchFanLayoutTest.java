package com.gathin.tvhome;

import org.junit.Test;
import static org.junit.Assert.*;

public class SearchFanLayoutTest {
    @Test public void horizontalRowHasEqualHeightAndSymmetricEdges() {
        float[][] p = SearchFanLayout.positions(5, 960, 150, 400, 1);
        assertEquals(480, p[2][0], .01);
        for(float[] point:p) assertEquals(p[0][1],point[1],.01);
        assertEquals(p[0][1], p[4][1], .01);
        assertEquals(960, p[0][0] + p[4][0], .01);
        assertEquals(750, SearchFanLayout.DURATION_MS);
    }
    @Test public void changingCountsStayInsideTheirContentArea() {
        for (int count = 1; count <= 21; count++) {
            float[][] points = SearchFanLayout.positions(count, 960, 150, 440, 1);
            assertEquals(count, points.length);
            for (float[] point : points) {
                assertTrue(point[0] >= 60 && point[0] <= 900);
                assertTrue(point[1] >= 150 && point[1] <= 440);
            }
        }
    }
    @Test public void emptyAndSingleProviderAreHandled() {
        assertEquals(0, SearchFanLayout.positions(0, 960, 150, 400, 1).length);
        float[][] single = SearchFanLayout.positions(1, 960, 150, 400, 1);
        assertEquals(480, single[0][0], .01);
        assertEquals(275, single[0][1], .01);
    }
}
