package ru.pda.tvhome;

import org.junit.Test;
import static org.junit.Assert.*;

public class AssistantChoiceTest {
    private static final String OWN = "ru.pda.tvhome/ru.pda.tvhome.LauncherAssistantService";
    private static final String SYSTEM = "com.google.android.katniss/.Service";

    @Test public void nothingIsWrittenWithoutTheSecureSettingsPermission() {
        assertNull(AssistantChoice.restoreDecision(false, true, "", OWN));
        assertNull(AssistantChoice.restoreDecision(false, true, SYSTEM, OWN));
    }

    @Test public void recordedSystemAssistantWinsOverTheGuess() {
        assertNull(AssistantChoice.restoreDecision(true, false, "", OWN));
        assertNull(AssistantChoice.restoreDecision(true, false, SYSTEM, OWN));
        assertNull(AssistantChoice.restoreDecision(true, false, OWN, OWN));
    }

    @Test public void settingsThatAlreadyPointAtHomeAreNotRewritten() {
        assertNull(AssistantChoice.restoreDecision(true, true, OWN, OWN));
    }

    @Test public void emptySettingsAreRestoredWithTheRecognizer() {
        assertEquals(AssistantChoice.RECOGNIZER, AssistantChoice.restoreDecision(true, true, "", OWN));
        assertEquals(AssistantChoice.RECOGNIZER, AssistantChoice.restoreDecision(true, true, null, OWN));
    }

    @Test public void anotherAssistantIsRestoredWithTheRecognizer() {
        assertEquals(AssistantChoice.RECOGNIZER, AssistantChoice.restoreDecision(true, true, SYSTEM, OWN));
    }

    @Test public void settingsAlreadyPointingAtHomeRecordTheChoiceForTheNextBoot() {
        assertTrue(AssistantChoice.recordDecision(true, false, OWN, OWN));
    }

    @Test public void theChoiceIsNotRecordedWithoutThePermissionOrForAnotherAssistant() {
        assertFalse(AssistantChoice.recordDecision(false, false, OWN, OWN));
        assertFalse(AssistantChoice.recordDecision(true, true, OWN, OWN));
        assertFalse(AssistantChoice.recordDecision(true, false, SYSTEM, OWN));
        assertFalse(AssistantChoice.recordDecision(true, false, "", OWN));
    }
}
