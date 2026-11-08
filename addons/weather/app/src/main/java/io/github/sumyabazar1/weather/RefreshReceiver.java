package io.github.sumyabazar1.weather;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * The launcher's one question: the top bar's numbers have gone stale, fetch them once. This is the page's own
 * refresh, not a background poll — one fetch per request, on a short-lived thread, and nothing runs unless the
 * launcher (or anyone else holding this exact action) asks.
 */
public final class RefreshReceiver extends BroadcastReceiver {
    static final String ACTION = "io.github.sumyabazar1.weather.action.REFRESH";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!ACTION.equals(intent.getAction())) return;
        final PendingResult pending = goAsync();
        new Thread(() -> {
            try {
                Snapshot.refreshOnce(context);
            } finally {
                pending.finish();
            }
        }, "weather-refresh").start();
    }
}
