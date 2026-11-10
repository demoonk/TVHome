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

    @Test public void otherHelpersAreKeptOnlyWhenTheyWereRecorded() {
        assertTrue(ServiceRepair.keeps("com.gathin.tvhome.InputKeyService", false, true));
        assertFalse(ServiceRepair.keeps("com.gathin.tvhome.InputKeyService", false, false));
    }
}
