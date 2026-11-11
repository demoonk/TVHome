package ru.pda.tvhome;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BridgeTriggerTest {

    @Test public void homeAsksTheHelperOncePerStart() {
        BridgeTrigger trigger = new BridgeTrigger();
        assertTrue(trigger.shouldAsk());
        assertFalse(trigger.shouldAsk());
        assertFalse(trigger.shouldAsk());
    }

    @Test public void loadingIsManualUntilTheSettingsRowTurnsItOn() {
        assertFalse(BridgeTrigger.autoDefault());
        assertEquals(0, BridgeTrigger.modeIndex(false));
        assertEquals(1, BridgeTrigger.modeIndex(true));
    }

    @Test public void theRowCannotAskWithoutTheHelperOnTheTv() {
        assertFalse(BridgeTrigger.canAsk(false));
        assertTrue(BridgeTrigger.canAsk(true));
    }

    @Test public void theAskIsRecordedWhenItHappens() {
        BridgeTrigger trigger = new BridgeTrigger();
        assertFalse(trigger.asked());
        assertTrue(trigger.shouldAsk());
        assertTrue(trigger.asked());
    }

    @Test public void theActionAndThePackageMatchTheHelperOnTheTv() {
        assertEquals("io.github.sumyabazar1.bridgehelper", BridgeTrigger.HELPER_PACKAGE);
        assertEquals("io.github.sumyabazar1.bridgehelper.action.ENSURE", BridgeTrigger.ACTION);
    }

    @Test public void theAskWaitsForHomeToFinishLoading() {
        // At boot the ADB authorization dialog appeared over a Home that was still loading, so the owner could not
        // tick «Всегда разрешать» in time (living-room TV, 2026-11-11). The ask is delayed and retried until Home is
        // usable instead of being sent from the first drawn frame.
        assertTrue(BridgeTrigger.ASK_DELAY_MS >= 20000);
        assertTrue(BridgeTrigger.ASK_RETRY_MS > 0);
        assertTrue(BridgeTrigger.ASK_MAX_RETRIES > 0);
    }

    @Test public void aSecondHomeInstanceAsksAgainForItself() {
        BridgeTrigger first = new BridgeTrigger();
        BridgeTrigger second = new BridgeTrigger();
        first.shouldAsk();
        assertTrue(second.shouldAsk());
    }
}
