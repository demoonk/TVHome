package ru.pda.tvhome;
import org.junit.Test;
import static org.junit.Assert.*;
public class VoiceTvCommandTest {
    @Test public void deviceCommandsAreExact() {
        assertEquals(VoiceTvCommand.Action.OFF, VoiceTvCommand.parse("Выключить ТВ"));
        assertEquals(VoiceTvCommand.Action.ON, VoiceTvCommand.parse("Включи телевизор!"));
        assertEquals(VoiceTvCommand.Action.SETTINGS, VoiceTvCommand.parse("Настройки"));
        assertEquals(VoiceTvCommand.Action.TIMER, VoiceTvCommand.parse("Сон"));
        assertEquals(VoiceTvCommand.Action.NONE, VoiceTvCommand.parse("Выключи таймер"));
        assertEquals(VoiceTvCommand.Action.NONE, VoiceTvCommand.parse("Найди фильм Сон"));
    }
    @Test public void sleepIsTimerAlias() {
        assertEquals(Integer.valueOf(15), VoiceTimerCommand.minutes("Сон 15 минут"));
        assertEquals(Integer.valueOf(1), VoiceTimerCommand.minutes("Сон на минуту"));
        assertEquals(Integer.valueOf(0), VoiceTimerCommand.minutes("Выключи сон"));
    }
}
