package ru.pda.tvhome;

import org.junit.Test;
import static org.junit.Assert.*;

public final class AppSearchEntryTest {
    @Test public void usesOnlyEntryPointsVerifiedOnTheLivingRoomTv(){
        assertEquals("vkvideo://vk.com/search?query=avatar", AppSearchEntry.searchUrl("vk", "avatar"));
        assertEquals("reshala://search?q=avatar", AppSearchEntry.searchUrl("reshala", "avatar"));
        assertEquals("https://www.youtube.com/results?search_query=avatar",
                AppSearchEntry.searchUrl("smarttube", "avatar"));
        assertEquals("https://www.youtube.com/results?search_query=avatar",
                AppSearchEntry.searchUrl("tizentube", "avatar"));
        assertEquals("vkvideo://vk.com/search?query=", AppSearchEntry.searchUrl("vk", ""));
    }
    @Test public void encodesTheQueryWithoutLeavingPlusSigns(){
        assertEquals("vkvideo://vk.com/search?query=%D0%B0%D0%B2%D0%B0%D1%82%D0%B0%D1%80%202009",
                AppSearchEntry.searchUrl("vk", "аватар 2009"));
        assertEquals("reshala://search?q=a%2Fb%3Fc", AppSearchEntry.searchUrl("reshala", "a/b?c"));
    }
    @Test public void noEntryIsInventedForAppsWithoutVerifiedSearch(){
        assertNull(AppSearchEntry.searchUrl("kinopoisk", "avatar"));
        assertNull(AppSearchEntry.searchUrl("rutube", "avatar"));
        assertNull(AppSearchEntry.searchUrl("lift", "avatar"));
        assertNull(AppSearchEntry.searchUrl("boosty", "avatar"));
        assertNull(AppSearchEntry.searchUrl("mpv", "avatar"));
    }
    @Test public void buttonPromisesSearchOnlyWhereItIsReachable(){
        assertEquals("Искать в VK Видео", AppSearchEntry.buttonLabel(new MediaVoiceApps.App("vk","VK Видео","com.vk.tv","deeplink"), false));
        assertEquals("Искать в SmartTube", AppSearchEntry.buttonLabel(new MediaVoiceApps.App("smarttube","SmartTube","org.smarttube.stable","youtube"), false));
        assertEquals("Искать в РешалаТВ", AppSearchEntry.buttonLabel(new MediaVoiceApps.App("reshala","РешалаТВ","reshala.tv.app","reshala"), false));
        assertEquals("Искать в Vimu", AppSearchEntry.buttonLabel(new MediaVoiceApps.App("vimu","Vimu","net.gtvbox.videoplayer","provider"), true));
        assertEquals("Открыть Vimu", AppSearchEntry.buttonLabel(new MediaVoiceApps.App("vimu","Vimu","net.gtvbox.videoplayer","provider"), false));
        assertEquals("Открыть Кинопоиск", AppSearchEntry.buttonLabel(new MediaVoiceApps.App("kinopoisk","Кинопоиск","ru.kinopoisk.tv","provider"), false));
        assertEquals("Открыть Lift", AppSearchEntry.buttonLabel(new MediaVoiceApps.App("lift","Lift","io.lift.app","manual"), false));
    }
}
