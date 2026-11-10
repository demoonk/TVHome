package ru.pda.tvhome;

import org.junit.Test;
import static org.junit.Assert.*;

public class VoiceLaunchTest {
    @Test public void windowCancellationWithPhrasesIsStillAccepted(){
        java.util.ArrayList<String> words = new java.util.ArrayList<>();
        words.add("матрица");
        assertTrue(VoiceLaunchActivity.acceptsSpokenResult(words));
        assertFalse(VoiceLaunchActivity.acceptsSpokenResult(null));
        assertFalse(VoiceLaunchActivity.acceptsSpokenResult(new java.util.ArrayList<>()));
    }
    @Test public void spokenCommandMatchesDisplayedName() {
        assertEquals("smarttube", VoiceLaunchActivity.normalize("Запусти приложение Smart Tube!"));
        assertEquals("виму", VoiceLaunchActivity.normalize("Открой Виму"));
    }

    @Test public void letterYoAndPunctuationDoNotBlockMatch() {
        assertEquals("решалатв", VoiceLaunchActivity.normalize("РЕШАЛА-ТВ"));
        assertEquals("елка", VoiceLaunchActivity.normalize("Ёлка"));
    }

    @Test public void appNameContainingCommandIsNotStripped() {
        assertEquals("открытие", VoiceLaunchActivity.normalize("Открытие"));
    }
    @Test public void youtubeLaunchUsesSmartTubePackage() {
        assertEquals("org.smarttube.stable", VoiceLaunchActivity.alias(
                VoiceLaunchActivity.normalize("Открой Ютуб")));
        assertEquals("org.smarttube.stable", VoiceLaunchActivity.alias(
                VoiceLaunchActivity.normalize("Запусти YouTube")));
    }
    @Test public void noGoogleWindowAfterOurOwnSession(){
        assertFalse(VoiceLaunchActivity.engineWindowAllowed(true));
        assertTrue(VoiceLaunchActivity.engineWindowAllowed(false));
    }
}
