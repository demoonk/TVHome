package ru.pda.tvhome;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import java.util.List;

/** Remembers the results and the fact that the user should come back to them, plus the optional remote bridge handshake. */
final class SearchReturn {
    static final String KEY = "ru_pda_tvhome_search_return";
    static final String EXTRA = "return_to_search";
    private static final String RESULTS = "results";
    private static final String PENDING = "pending";

    static void remember(Context context, String query, String target) {
        context.getSharedPreferences("search_return", 0).edit().putString("query", query).putString("target", target).apply();
    }

    /** The cards of every app, stored together with the query they came from. */
    static void remember(Context context, String query, List<SearchMemory.Card> cards) {
        context.getSharedPreferences("search_return", 0).edit()
                .putString("query", query).putString(RESULTS, SearchMemory.encode(cards)).apply();
    }

    static List<SearchMemory.Card> cards(Context context) {
        return SearchMemory.decode(context.getSharedPreferences("search_return", 0).getString(RESULTS, ""));
    }

    /** Set when a card was opened from the results screen: Back from that app should bring the results back. */
    static void markPending(Context context) {
        context.getSharedPreferences("search_return", 0).edit().putBoolean(PENDING, true).apply();
    }

    /** Read once, so the results screen is not reopened every time the launcher is shown. */
    static boolean takePending(Context context) {
        android.content.SharedPreferences prefs = context.getSharedPreferences("search_return", 0);
        if (!prefs.getBoolean(PENDING, false)) return false;
        prefs.edit().putBoolean(PENDING, false).apply();
        return true;
    }

    static void arm(Activity activity, Intent intent) {
        android.content.ComponentName name = intent.getComponent();
        String pkg = name != null ? name.getPackageName() : intent.getPackage();
        if (pkg == null) return;
        try { Settings.Secure.putString(activity.getContentResolver(), KEY, pkg + ":" + android.os.SystemClock.elapsedRealtime()); }
        catch (RuntimeException unavailable) { android.util.Log.d("SearchReturn", "Remote bridge permission unavailable"); }
    }

    static void clear(Context context) {
        try { Settings.Secure.putString(context.getContentResolver(), KEY, ""); }
        catch (RuntimeException unavailable) { /* The ordinary Activity back stack does not need this permission. */ }
    }

    private SearchReturn() {}
}
