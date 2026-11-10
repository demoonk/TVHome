package ru.pda.tvhome;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class SearchMemoryTest {
    @Test public void cardSurvivesTheRoundTrip() {
        List<SearchMemory.Card> cards = List.of(new SearchMemory.Card("num", "Матрица",
                "2018 · IMDb 8.1", "https://image.tmdb.org/t/p/w200/x.jpg",
                "android.intent.action.VIEW", "ru.yourok.num/.activity.details.DetailsActivity",
                "https://www.themoviedb.org/movie/523296"));
        assertEquals(cards, SearchMemory.decode(SearchMemory.encode(cards)));
    }

    @Test public void separatorsInsideATitleDoNotBreakTheStoredLine() {
        List<SearchMemory.Card> cards = List.of(
                new SearchMemory.Card("num", "назад\tвперёд\nновая строка", "a\\b", "", "", "", ""));
        assertEquals(cards, SearchMemory.decode(SearchMemory.encode(cards)));
    }

    @Test public void cardsOfOneAppKeepTheirOrder() {
        List<SearchMemory.Card> cards = List.of(
                new SearchMemory.Card("num", "первый", "", "", "", "", ""),
                new SearchMemory.Card("cinemoid", "другое приложение", "", "", "", "", ""),
                new SearchMemory.Card("num", "второй", "", "", "", "", ""));
        assertEquals(List.of(cards.get(0), cards.get(2)), SearchMemory.of(cards, "num"));
        assertEquals(List.of("num", "cinemoid"), SearchMemory.apps(cards));
    }

    @Test public void emptyMemoryIsNoCards() {
        assertTrue(SearchMemory.decode("").isEmpty());
        assertTrue(SearchMemory.decode(null).isEmpty());
        assertTrue(SearchMemory.decode("num\tназад").isEmpty());
    }
}
