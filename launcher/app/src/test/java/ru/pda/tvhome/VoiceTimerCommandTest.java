package ru.pda.tvhome;

import org.junit.Test;
import static org.junit.Assert.*;

public class VoiceTimerCommandTest {
    @Test public void oneMinuteDoesNotNeedAnExplicitNumber() {
        for (String phrase : new String[]{"Таймер минута", "Таймер на минуту",
                "Включи таймер на минуту!", "Таймер одну минуту", "Таймер один минута"}) {
            assertEquals(phrase, Integer.valueOf(1), VoiceTimerCommand.minutes(phrase));
        }
        assertNull(VoiceTimerCommand.minutes("Таймер минут"));
        assertNull(VoiceTimerCommand.minutes("Найди фильм Таймер минута"));
    }
    @Test public void requestedTimerPhraseArmsFifteenMinutes() {
        assertEquals(Integer.valueOf(15), VoiceTimerCommand.minutes("Таймер 15 минут"));
        assertEquals(Integer.valueOf(15), VoiceTimerCommand.minutes("Поставь таймер на пятнадцать минут"));
        assertEquals(Integer.valueOf(1), VoiceTimerCommand.minutes("Таймер на одну минуту"));
    }
    @Test public void cancellationIsExplicit() {
        assertEquals(Integer.valueOf(0), VoiceTimerCommand.minutes("Выключи таймер"));
        assertNull(VoiceTimerCommand.minutes("Найди Таймер 15 минут в ютубе"));
    }
    @Test public void invalidDurationsCannotArmTimer() {
        assertNull(VoiceTimerCommand.minutes("Таймер 0 минут"));
        assertNull(VoiceTimerCommand.minutes("Таймер 9999 минут"));
        assertNull(VoiceTimerCommand.minutes("Таймер -1 минут"));
    }
}
