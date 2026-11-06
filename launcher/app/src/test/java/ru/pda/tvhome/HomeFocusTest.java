package ru.pda.tvhome;

import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.assertEquals;

public class HomeFocusTest {
    @Test public void coldStartIgnoresAnyOldSlot() {
        assertEquals(0, HomeFocus.position(null, key -> { throw new AssertionError("No saved app on cold start"); }));
    }
    @Test public void appKeepsFocusAfterItsPositionChanges() {
        assertEquals(2, HomeFocus.position("player", Arrays.asList("video", "music", "player")::indexOf));
        assertEquals(1, HomeFocus.position("player", Arrays.asList("video", "player", "music")::indexOf));
    }
    @Test public void removedAppFallsBackToFirstRatherThanItsFormerSlot() {
        assertEquals(0, HomeFocus.position("removed", Arrays.asList("video", "music")::indexOf));
    }
}
