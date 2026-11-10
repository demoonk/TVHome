package ru.pda.tvhome;

import org.junit.Test;
import static org.junit.Assert.*;

public class VoiceActionCommandTest {
    @Test public void volumeWordsMapToTheSystemVolume() {
        assertEquals(VoiceActionCommand.Action.VOLUME_UP, VoiceActionCommand.parse("громче").action());
        assertEquals(VoiceActionCommand.Action.VOLUME_UP, VoiceActionCommand.parse("прибавь звук").action());
        assertEquals(VoiceActionCommand.Action.VOLUME_DOWN, VoiceActionCommand.parse("тише").action());
        assertEquals(VoiceActionCommand.Action.VOLUME_DOWN, VoiceActionCommand.parse("убавь звук").action());
    }

    @Test public void volumeNumberAndEndsAreClamped() {
        assertEquals(20, VoiceActionCommand.parse("громкость 20").value());
        assertEquals(100, VoiceActionCommand.parse("громкость на максимум").value());
        assertEquals(0, VoiceActionCommand.parse("громкость минимум").value());
        assertEquals(100, VoiceActionCommand.parse("громкость 250").value());
    }

    @Test public void muteWorksBothWays() {
        assertEquals(VoiceActionCommand.Action.MUTE_ON, VoiceActionCommand.parse("без звука").action());
        assertEquals(VoiceActionCommand.Action.MUTE_OFF, VoiceActionCommand.parse("включи звук").action());
    }

    @Test public void brightnessEndsInThePictureMenu() {
        assertEquals(VoiceActionCommand.Action.PICTURE_SETTINGS, VoiceActionCommand.parse("яркость 50").action());
        assertEquals(50, VoiceActionCommand.parse("яркость 50").value());
        assertEquals(40, VoiceActionCommand.parse("подсветка 40").value());
    }

    @Test public void clockWeatherAndInputs() {
        assertEquals(VoiceActionCommand.Action.CLOCK, VoiceActionCommand.parse("который час").action());
        assertEquals(VoiceActionCommand.Action.WEATHER, VoiceActionCommand.parse("какая погода").action());
        assertEquals(VoiceActionCommand.Action.INPUTS, VoiceActionCommand.parse("антенна").action());
        assertEquals(2, VoiceActionCommand.parse("вход HDMI 2").value());
    }

    @Test public void aPhraseWithTheSearchVerbStaysWithSearch() {
        assertEquals(VoiceActionCommand.Action.NONE, VoiceActionCommand.parse("найди погода в рио").action());
        assertEquals(VoiceActionCommand.Action.NONE, VoiceActionCommand.parse("матрица").action());
    }
}
