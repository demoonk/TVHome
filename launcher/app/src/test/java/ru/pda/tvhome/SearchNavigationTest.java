package ru.pda.tvhome;

import android.content.Intent;
import org.junit.Test;
import static org.junit.Assert.*;

public class SearchNavigationTest {
    @Test public void resultCannotClearOrReplaceSearchTask() {
        int destructive = Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_DOCUMENT
                | Intent.FLAG_ACTIVITY_MULTIPLE_TASK | Intent.FLAG_ACTIVITY_TASK_ON_HOME;
        assertEquals(0, SearchNavigation.flags(destructive));
    }
    @Test public void resultRetainsUriAccessAndOtherNonTaskFlags() {
        int access = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
        assertEquals(access, SearchNavigation.flags(access | Intent.FLAG_ACTIVITY_NEW_TASK));
    }
}
