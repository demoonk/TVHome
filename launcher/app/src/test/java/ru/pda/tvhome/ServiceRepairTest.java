package ru.pda.tvhome;

import org.junit.Test;
import static org.junit.Assert.*;

public class ServiceRepairTest {
    @Test public void redirectWatcherStaysOnTheListEvenWhenItWasNeverRecorded() {
        assertTrue(ServiceRepair.keeps("ru.pda.tvhome.HomeRedirectService", false, false));
    }

    @Test public void keyFilterHelperIsKeptOnlyWhileAPictureKeyIsChosen() {
        assertTrue(ServiceRepair.keeps("ru.pda.tvhome.HomeKeyService", true, false));
        assertFalse(ServiceRepair.keeps("ru.pda.tvhome.HomeKeyService", false, false));
    }

    // The microphone button is answered through the system voice intent, so the key helper is
    // only wanted when a picture key or the explicit voice key is on.
    @Test public void voiceIntentDoesNotNeedTheKeyHelper() {
        assertFalse(ServiceRepair.keeps("ru.pda.tvhome.HomeKeyService",
                ServiceRepair.voiceSearchNeedsKeyFilter(false, false), false));
        assertTrue(ServiceRepair.keeps("ru.pda.tvhome.HomeKeyService",
                ServiceRepair.voiceSearchNeedsKeyFilter(false, true), false));
    }

    @Test public void otherHelpersAreKeptOnlyWhenTheyWereRecorded() {
        assertTrue(ServiceRepair.keeps("com.gathin.tvhome.InputKeyService", false, true));
        assertFalse(ServiceRepair.keeps("com.gathin.tvhome.InputKeyService", false, false));
    }
}
