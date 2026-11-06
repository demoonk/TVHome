package ru.pda.tvhome;

import android.content.Intent;

/** Retain the search task so Back from a result can return to its query and cards. */
final class SearchNavigation {
    static void open(android.app.Activity activity, Intent source) {
        Intent intent = fromSearch(source);
        SearchReturn.arm(activity, intent);
        try { activity.startActivity(intent); }
        catch (RuntimeException unavailable) { SearchReturn.clear(activity); throw unavailable; }
    }
    static Intent fromSearch(Intent source) {
        Intent intent = new Intent(source);
        intent.setFlags(flags(intent.getFlags()));
        return intent;
    }
    static int flags(int original) {
        return original & ~(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_DOCUMENT
                | Intent.FLAG_ACTIVITY_MULTIPLE_TASK | Intent.FLAG_ACTIVITY_TASK_ON_HOME);
    }
    private SearchNavigation() {}
}
