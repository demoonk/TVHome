package com.gathin.tvhome;

import org.junit.Test;
import static org.junit.Assert.*;

public final class SiteSearchRulesTest {
    @Test public void acceptsContentPagesAcrossTheThreeSites(){
        assertTrue(SiteSearchRules.content("vk","https://vkvideo.ru/video-123_456"));
        assertTrue(SiteSearchRules.content("vk","https://vk.com/video123_456?list=test"));
        assertTrue(SiteSearchRules.content("rutube","https://rutube.ru/video/1b5db47d7a3c6157d9114a1e92b1925a/"));
        assertTrue(SiteSearchRules.content("kinopoisk","https://www.kinopoisk.ru/film/301/"));
        assertTrue(SiteSearchRules.content("kinopoisk","https://www.kinopoisk.ru/series/123/"));
    }
    @Test public void rejectsNavigationAndUntrustedLinks(){
        assertFalse(SiteSearchRules.content("vk","https://vkvideo.ru/results?q=test"));
        assertFalse(SiteSearchRules.content("kinopoisk","https://www.kinopoisk.ru/index.php?kp_query=test"));
        assertFalse(SiteSearchRules.content("kinopoisk","https://www.kinopoisk.ru.evil.example/film/301/"));
        assertFalse(SiteSearchRules.content("kinopoisk","https://evil@www.kinopoisk.ru/film/301/"));
        assertFalse(SiteSearchRules.content("vk","javascript:alert(1)"));
        assertFalse(SiteSearchRules.content("rutube","http://rutube.ru/video/1b5db47d7a3c6157d9114a1e92b1925a/"));
        assertFalse(SiteSearchRules.content("unknown","https://www.kinopoisk.ru/film/301/"));
    }
}
