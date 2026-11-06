package ru.pda.tvhome;

import org.junit.Test;
import static org.junit.Assert.*;

public class PackageUpgradeTest {
    @Test public void migrateOnlyPreviouslySelectedOwnComponents() {
        assertEquals("other.app/.Service:ru.pda.tvhome/ru.pda.tvhome.HomeRedirectService",
                PackageUpgrade.componentNames("other.app/.Service:ru.pda.tvhome/com.gathin.tvhome.HomeRedirectService"));
    }
    @Test public void emptySelectionStaysEmpty() {
        assertNull(PackageUpgrade.componentNames(null));
        assertEquals("", PackageUpgrade.componentNames(""));
        assertEquals("null", PackageUpgrade.componentNames("null"));
    }
    @Test public void migrationIsIdempotentAndLeavesSystemAssistantAlone() {
        String own = "ru.pda.tvhome/ru.pda.tvhome.LauncherAssistantService";
        assertEquals(own, PackageUpgrade.componentNames(own));
        assertEquals("com.google.android.katniss/.Service", PackageUpgrade.componentNames("com.google.android.katniss/.Service"));
    }
}
