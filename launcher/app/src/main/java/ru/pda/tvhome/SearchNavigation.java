package ru.pda.tvhome;

import android.content.Intent;

/** Launches a card result and closes the search window, so the result is what the user sees. */
final class SearchNavigation {
    static void open(android.app.Activity activity, Intent source) {
        Intent intent = fromSearch(source);
        SearchReturn.arm(activity, intent);
        try {
            activity.startActivity(intent);
            // Our window is a task of type "assistant", and on this firmware it stays above the launched app
            // even after moveTaskToBack, so it is closed: that is what hiding it means here.
            android.util.Log.w("tvsearch", "search window closed");
            SearchReturn.markPending(activity);
            activity.finish();
        } catch (RuntimeException unavailable) { SearchReturn.clear(activity); throw unavailable; }
    }
    static Intent fromSearch(Intent source) {
        Intent intent = new Intent(source);
        intent.setFlags(flags(intent.getFlags()));
        return intent;
    }
    static int flags(int original) {
        // A card always targets another app. On this firmware a start into an existing task of that app is
        // canceled, so the card goes into its own task instead of being delivered to the old instance. That task
        // is put on Home, so Back from the app returns to the launcher, and the launcher reopens the results.
        return (original & ~(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_NEW_DOCUMENT))
                | Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK | Intent.FLAG_ACTIVITY_TASK_ON_HOME;
    }
    private SearchNavigation() {}
}
