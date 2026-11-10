package ru.pda.tvhome;

import org.junit.Test;
import static org.junit.Assert.*;

public final class VoiceHintsTest {
    @Test public void everyHintIsOneTheAppActuallyActsOn(){
        for (String phrase : VoiceHints.examples()) {
            assertTrue("hint is not understood: " + phrase, VoiceHints.understood(phrase));
        }
        assertEquals(VoiceHints.examples().size(), VoiceHints.honestExamples().size());
    }
    @Test public void theHintLineListsSearchAndTheTvCommands(){
        String line = VoiceHints.line();
        assertTrue(line.contains("найди"));
        assertTrue(line.contains("алиса"));
        assertTrue(line.contains("настройки"));
        assertTrue(line.contains("таймер"));
    }
    @Test public void aPhraseTheRouterCannotHandleIsNotShownAsAHint(){
        assertFalse(VoiceHints.understood("поехали"));
        assertEquals(0, VoiceHints.honestExamples().stream().filter(p -> p.equals("поехали")).count());
    }
    @Test public void theHintIsThreeLinesWithEveryExampleInOneOfThem(){
        java.util.List<String> lines = VoiceHints.twoLines();
        assertEquals(3, lines.size());
        for (String line : lines) assertFalse(line.isEmpty());
        for (String phrase : VoiceHints.examples()) {
            assertTrue("example missing from the hint: " + phrase,
                    lines.get(0).contains(phrase) || lines.get(1).contains(phrase) || lines.get(2).contains(phrase));
        }
        assertTrue(lines.get(1).contains("настройки"));
    }
    @Test public void everyChipIsACommandTheLauncherCarriesOutItself(){
        java.util.List<java.util.List<String>> chips = VoiceHints.chips();
        assertEquals(3, chips.size());
        for (java.util.List<String> line : chips) {
            assertFalse(line.isEmpty());
            for (String chip : line) assertTrue("chip is not understood: " + chip, VoiceHints.understood(chip));
        }
        assertTrue(chips.get(0).contains("найди фильм"));
        assertTrue(chips.get(0).contains("алиса привет"));
        assertTrue(chips.get(1).contains("выключи ТВ"));
        assertTrue(chips.get(1).contains("поставь таймер 30 минут"));
        assertTrue(chips.get(2).contains("громче"));
        assertTrue(chips.get(2).contains("который час"));
        assertTrue(chips.get(2).contains("какая погода"));
    }
}
