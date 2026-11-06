package com.gathin.tvhome;
import org.junit.Test;
import static org.junit.Assert.*;

public class WallpaperBrowseModeTest {
    @Test public void homeShortcutReturnsToOpaqueOnArrowsAndOtherButtons() {
        WallpaperBrowseMode state=new WallpaperBrowseMode();
        try {
            assertFalse(state.acceptsStep(true,true));
            state.enter(false);
            assertTrue(state.acceptsStep(true,true));
            assertFalse(state.acceptsStep(true,false));
            assertFalse(state.acceptsStep(false,false));
            state.reset();
            WallpaperBrowseMode.reserveArrowRelease();
            assertTrue(WallpaperBrowseMode.arrowsReserved());
            assertFalse(state.acceptsStep(true,false));
            WallpaperBrowseMode.releaseArrowRelease();
            assertFalse(WallpaperBrowseMode.arrowsReserved());
        }finally{state.reset();}
    }
    @Test public void settingsPickerKeepsArrowsAndProgramKeysForWallpaperSteps() {
        WallpaperBrowseMode state=new WallpaperBrowseMode();
        try {
            state.enter(false);state.enter(true);
            assertTrue(state.acceptsStep(true,false));
            assertTrue(state.acceptsStep(true,true));
            assertFalse(state.acceptsStep(false,false));
            state.reset();assertFalse(state.acceptsStep(true,true));
        }finally{state.reset();}
    }
    @Test public void repeatedChannelPressesDoNotRestartTheFade() {
        WallpaperBrowseMode state = new WallpaperBrowseMode();
        assertTrue(state.enter(false));
        assertFalse(state.enter(false));
        assertTrue(state.active());
        assertFalse(state.explicit());
        assertEquals(500L, state.exitDuration(false));
    }
    @Test public void explicitModeExitsInOneSecondButOtherKeysInHalfASecond() {
        WallpaperBrowseMode state = new WallpaperBrowseMode();
        assertTrue(state.enter(true));
        assertEquals(1000L, state.exitDuration(true));
        assertEquals(500L, state.exitDuration(false));
        assertFalse(state.enter(false));
        assertTrue(state.explicit());
    }
    @Test public void resetRestoresNormalModeAndNextEntryFadesAgain() {
        WallpaperBrowseMode state = new WallpaperBrowseMode();
        state.enter(true);
        state.reset();
        assertFalse(state.active());
        assertFalse(state.explicit());
        assertTrue(state.enter(false));
        assertEquals(.05f, WallpaperBrowseMode.TILE_ALPHA, .0001f);
        assertEquals(1000L, WallpaperBrowseMode.ENTER_MS);
    }
}
