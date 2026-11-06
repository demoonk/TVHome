package com.gathin.tvhome;

import org.junit.Test;
import static org.junit.Assert.*;

public class VoiceSearchCommandTest {
    @Test public void targetedSearchPreservesMovieName() {
        VoiceSearchCommand command = VoiceSearchCommand.parse("Найди Интерстеллар в Нум");
        assertEquals("Интерстеллар", command.query);
        assertEquals("num", command.target);
        assertEquals("smarttube", VoiceSearchCommand.parse("Найди фильм Дюна в Смарттубе").target);
    }
    @Test public void untargetedSearchKeepsPrepositionInTitle() {
        VoiceSearchCommand command = VoiceSearchCommand.parse("Найди В джазе только девушки");
        assertEquals("В джазе только девушки", command.query);
        assertEquals("", command.target);
    }
    @Test public void launchAndEmptyCommandsAreNotSearch() {
        assertNull(VoiceSearchCommand.parse("Открой Виму"));
        assertNull(VoiceSearchCommand.parse("Найди "));
    }
    @Test public void spokenSmartubeWithSingleTIsRecognized() {
        for (String name : new String[]{"смартубе", "смарттубе", "смартюбе", "смарт тубе", "SmartTube"}) {
            VoiceSearchCommand command = VoiceSearchCommand.parse("найди шарий в " + name);
            assertEquals(name, "smarttube", command.target);
            assertEquals(name, "шарий", command.query);
        }
    }
    @Test public void recognizerMayJoinPrepositionToAppName() {
        for (String suffix : new String[]{"внум", "в нум", "в Нуме", "вnum"}) {
            VoiceSearchCommand command = VoiceSearchCommand.parse("Найди Интерстеллар " + suffix);
            assertEquals(suffix, "num", command.target);
            assertEquals(suffix, "Интерстеллар", command.query);
        }
    }
    @Test public void titleEndingWithSimilarWordIsNotAnAppTarget() {
        VoiceSearchCommand command = VoiceSearchCommand.parse("Найди фильм Внутри");
        assertEquals("", command.target);
        assertEquals("Внутри", command.query);
    }
    @Test public void youtubeNamesRouteToSmartTube() {
        for (String name : new String[]{"ютуб", "ютубе", "YouTube", "ютюбе"}) {
            VoiceSearchCommand command = VoiceSearchCommand.parse("Найди Шарий в " + name);
            assertEquals(name, "smarttube", command.target);
            assertEquals(name, "Шарий", command.query);
        }
    }
    @Test public void installedMovieAppNamesAndCasesAreRecognized() {
        String[][] cases = {{"Кинопоиске", "kinopoisk"}, {"Кионе", "kion"}, {"МТС ТВ", "kion"},
                {"ВК Видео", "vk"}, {"Рутубе", "rutube"}, {"Решале", "reshala"}, {"Лифте", "lift"},
                {"Vimu", "vimu"}, {"mpv", "mpv"}, {"TizenTube", "tizentube"}};
        for (String[] sample : cases) {
            VoiceSearchCommand command = VoiceSearchCommand.parse("Найди Назад в будущее в " + sample[0]);
            assertEquals(sample[0], sample[1], command.target);
            assertEquals(sample[0], "Назад в будущее", command.query);
        }
    }
    @Test public void contentWordsAreEquivalentSearchCommands() {
        for (String prefix : new String[]{"Фильм", "Найди", "Сериал", "Мульт", "Мультфильм"}) {
            VoiceSearchCommand command = VoiceSearchCommand.parse(prefix + " Шрек");
            assertNotNull(prefix, command);
            assertEquals(prefix, "Шрек", command.query);
            assertEquals(prefix, "", command.target);
        }
    }
    @Test public void commandWordsCanBeCombinedWithAnAppTarget() {
        VoiceSearchCommand command = VoiceSearchCommand.parse("Найди мультфильм Шрек вютубе");
        assertEquals("Шрек", command.query);
        assertEquals("smarttube", command.target);
        command = VoiceSearchCommand.parse("Сериал Тьма внум");
        assertEquals("Тьма", command.query);
        assertEquals("num", command.target);
    }
    @Test public void contentWordsInsideTitlesArePreserved() {
        assertEquals("Хороший фильм", VoiceSearchCommand.parse("Найди Хороший фильм").query);
        assertNull(VoiceSearchCommand.parse("Мультимания Шрек"));
        assertNull(VoiceSearchCommand.parse("Найди фильм"));
        assertNull(VoiceSearchCommand.parse("Сериал"));
    }
}
