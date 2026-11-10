package ru.pda.tvhome;

import org.junit.Test;
import static org.junit.Assert.*;

public final class MovieSearchViewTest {
    @Test public void theRemoteOkIsTheKeyCardsAndTheSearchFieldAccept(){
        assertTrue(MovieSearchView.isOkKey(23)); // KEYCODE_DPAD_CENTER
        assertTrue(MovieSearchView.isOkKey(66)); // KEYCODE_ENTER
        assertTrue(MovieSearchView.isOkKey(90)); // KEYCODE_NUMPAD_ENTER
        assertTrue(MovieSearchView.isOkKey(98)); // KEYCODE_BUTTON_A
        assertFalse(MovieSearchView.isOkKey(20)); // KEYCODE_DPAD_DOWN navigates instead
    }

    @Test public void theHeaderContourIsFourPixelsTighterThanTheCardContourOnThisTv(){
        float density = 2f; // 320 dpi, checked with `wm density`
        // The outline sits pad on each side, so the whole contour shrinks by 2 * pad.
        assertEquals(4f, 2f * (MovieSearchView.CARD_PAD_DP - MovieSearchView.HEADER_PAD_DP) * density, 0.01f);
    }
}
