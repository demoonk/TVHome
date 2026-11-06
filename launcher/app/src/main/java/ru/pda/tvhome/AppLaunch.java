package ru.pda.tvhome;

import android.app.Activity;
import android.content.Intent;
import android.widget.Toast;

/** Opens an application immediately, without a return-to-tile animation. */
final class AppLaunch {
    static boolean open(Activity activity, Intent intent) {
        try {
            activity.startActivity(intent);
            activity.overridePendingTransition(0, 0);
            return true;
        } catch (RuntimeException unavailable) {
            android.util.Log.w("AppLaunch", "Application launch failed", unavailable);
            Toast.makeText(activity, "Не удалось открыть приложение", Toast.LENGTH_LONG).show();
            return false;
        }
    }
    private AppLaunch() {}
}
