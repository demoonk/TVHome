package ru.pda.tvhome;

import org.junit.Test;
import static org.junit.Assert.*;

public class SettingsNavigationTest {
    @Test
    public void onlySystemSectionTakesOkToTheTvSettings() {
        assertTrue("OK on «Система» opens the TV settings", SettingsColumns.okOpensSystemSettings(11));
        for (int group : new int[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10})
            assertFalse("OK on section " + group + " should stay inside the launcher", SettingsColumns.okOpensSystemSettings(group));
    }
}
