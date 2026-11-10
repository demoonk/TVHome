package ru.pda.tvhome;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Rows and hosts below are the actual results these apps returned on the living-room TV (192.168.1.7)
 * on 2026-11-09, checked with `content query` and `cmd package query-activities`, so the mapping is
 * verified against real data instead of an assumed column layout.
 */
public final class SearchCardRulesTest {
    @Test public void providersAreQueriedOnlyAtThePathTheyAnswerOn(){
        assertEquals("ru.yourok.num.atvsearch", SearchCardRules.authority("num"));
        assertEquals("/search/search_suggest_query", SearchCardRules.path("num"));
        // Reshala answers without the /search prefix and returns nothing with it.
        assertEquals("reshala.tv.app.poisk", SearchCardRules.authority("reshala"));
        assertEquals("/search_suggest_query", SearchCardRules.path("reshala"));
        // Кинопоиск rejects the standard suggest path with "Unknown uri", VK needs GLOBAL_SEARCH.
        assertNull(SearchCardRules.authority("kinopoisk"));
        assertNull(SearchCardRules.authority("vk"));
        assertNull(SearchCardRules.authority("kion"));
        assertNull(SearchCardRules.authority("vimu"));
    }
    @Test public void authorityAndPathAreAlwaysPaired(){
        for (MediaVoiceApps.App app : MediaVoiceApps.ALL) {
            if (SearchCardRules.authority(app.id) != null)
                assertTrue(app.id + " has an authority without a path", SearchCardRules.path(app.id) != null);
        }
    }
    @Test public void everyRowGetsTheTargetTheAppItselfDeclared(){
        // NUM: id 808 plus its type, app links registered for themoviedb.org.
        assertEquals("https://www.themoviedb.org/movie/808", SearchCardRules.cardUrl("num", null, "808", "movie"));
        assertEquals("https://www.themoviedb.org/tv/1152", SearchCardRules.cardUrl("num", null, "1152", "tv"));
        // Reshala row carries the app's own deeplink, so it is used unchanged.
        assertEquals("reshala://open?screen=card%3Atmdb%3A%2F%2Fmovie%2F808",
                SearchCardRules.cardUrl("reshala", "reshala://open?screen=card%3Atmdb%3A%2F%2Fmovie%2F808", "0", null));
        // SmartTube rows carry no intent data; only a row with an actual video id can be opened.
        assertEquals("https://www.youtube.com/watch?v=Swiz1XyfhcI",
                SearchCardRules.cardUrl("smarttube", null, "Swiz1XyfhcI", null));
        assertNull(SearchCardRules.cardUrl("smarttube", null, "490779098", null));
        // Кинопоиск registers app links only for hd.kinopoisk.ru/film; VK only for vk.com/video*.
        assertEquals("https://hd.kinopoisk.ru/film/3401/", SearchCardRules.cardUrl("kinopoisk", null, "3401", null));
        assertEquals("https://vk.com/video-111180788_456249130",
                SearchCardRules.cardUrl("vk", null, "111180788_456249130", null));
    }
    @Test public void nothingIsGuessedForRowsWithoutAVerifiedTarget(){
        // KION answers with no rows and declares no content host, so its click must fall back to its own search activity.
        assertNull(SearchCardRules.cardUrl("kion", null, "1234", null));
        assertNull(SearchCardRules.cardUrl("num", null, "808", null));
        assertNull(SearchCardRules.cardUrl("num", null, "808", "person"));
        assertNull(SearchCardRules.cardUrl("smarttube", null, "tooshort", null));
        assertNull(SearchCardRules.cardUrl("vk", null, "111180788", null));
        assertNull(SearchCardRules.cardUrl("kinopoisk", null, "series/3401", null));
        assertNull(SearchCardRules.cardUrl("reshala", null, "0", null));
        assertNull(SearchCardRules.cardUrl("lift", null, "12", null));
    }
    /** How each row can be opened, as verified on the TV. An app with no card path can only be opened. */
    @Test public void everyRowHasOneVerifiedWayToOpenIt(){
        for (MediaVoiceApps.App app : MediaVoiceApps.ALL) {
            switch (app.id) {
                case "num": case "reshala":
                    assertNotNull(app.id + " must be queryable", SearchCardRules.authority(app.id));
                    break;
                case "smarttube": case "tizentube": case "youtube": case "vk": case "boosty":
                    assertNotNull(app.id + " needs a reachable entry", AppSearchEntry.base(app.id));
                    break;
                case "kinopoisk": case "rutube":
                    assertTrue(app.id + " is reached through its result page", SiteSearchRules.content(app.id,
                            "kinopoisk".equals(app.id) ? "https://kinopoisk.ru/film/3401/"
                                    : "https://rutube.ru/video/0123456789abcdef0123456789abcdef/"));
                    break;
                default:
                    // kion, vimu, lift, mpv: only the app's own exported search activity, never an invented link.
                    assertNull(app.id + " must not invent a card target", SearchCardRules.cardUrl(app.id, null, "1", null));
                    assertNull(app.id + " must not invent an entry", AppSearchEntry.base(app.id));
                    break;
            }
        }
    }
    @Test public void cardTargetsStayOnTheHostTheAppDeclared(){
        assertEquals("https://www.themoviedb.org/movie/808", SearchCardRules.cardUrl("num", null, "808", "movie"));
        assertEquals("https://www.youtube.com/watch?v=Swiz1XyfhcI", SearchCardRules.cardUrl("smarttube", null, "Swiz1XyfhcI", null));
        assertEquals("https://hd.kinopoisk.ru/film/3401/", SearchCardRules.cardUrl("kinopoisk", null, "3401", null));
        assertEquals("https://vk.com/video-1_2", SearchCardRules.cardUrl("vk", null, "1_2", null));
        assertEquals("reshala://open?screen=card:tmdb://movie/808",
                SearchCardRules.cardUrl("reshala", "reshala://open?screen=card:tmdb://movie/808", "0", null));
    }
}
