package ru.pda.tvhome;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;

/** Optional hardware-remote bridge handshake; Android's ordinary back stack remains intact. */
final class SearchReturn {
    static final String KEY = "ru_pda_tvhome_search_return";
    static final String EXTRA = "return_to_search";
    static void remember(Context context, String query, String target) {
        context.getSharedPreferences("search_return", 0).edit().putString("query", query).putString("target", target).apply();
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
