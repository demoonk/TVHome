package ru.pda.tvhome;

import android.content.Intent;
import org.junit.Test;
import static org.junit.Assert.*;

public class SearchNavigationTest {
    private static final int OWN_TASK_ON_HOME = Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK
            | Intent.FLAG_ACTIVITY_TASK_ON_HOME;

    @Test public void resultCannotClearTheSearchTask() {
        int destructive = Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_NEW_DOCUMENT;
        assertEquals(OWN_TASK_ON_HOME, SearchNavigation.flags(destructive | OWN_TASK_ON_HOME));
    }
    @Test public void resultRetainsUriAccessAndItsOwnTask() {
        int access = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
        assertEquals(access | OWN_TASK_ON_HOME, SearchNavigation.flags(access | Intent.FLAG_ACTIVITY_NEW_TASK));
    }
    @Test public void resultTaskSitsOnHomeSoBackComesBackToTheLauncher() {
        assertEquals(Intent.FLAG_ACTIVITY_TASK_ON_HOME,
                SearchNavigation.flags(Intent.FLAG_ACTIVITY_NEW_TASK) & Intent.FLAG_ACTIVITY_TASK_ON_HOME);
    }
}
