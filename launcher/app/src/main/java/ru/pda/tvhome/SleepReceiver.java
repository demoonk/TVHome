package ru.pda.tvhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

/**
 * The sleep timer's alarms (SleepTimer). The heads-up one, 15 s early, only brings Home up so the
 * countdown pill can say what is about to happen; the deadline one is over the moment it fires, so the
 * deadline is cleared first: the moon button shows its plain icon again even while the screen stays
 * dimmed. exported=false: only this app's own PendingIntents can reach it.
 */
public class SleepReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent.getBooleanExtra(SleepTimer.EXTRA_WARN, false)) {
            if (SleepTimer.deadline(context) <= SleepTimer.NONE) return;   // cancelled in the meantime
            Intent show = new Intent(context, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(MainActivity.EXTRA_SLEEP_WARN, true);
            try {
                context.startActivity(show);
            } catch (Exception blocked) {
                // A background start the TV refuses is not an error worth a message: the deadline's own
                // alarm still dims the screen, just without the countdown.
            }
            return;
        }
        SleepTimer.clearFired(context);
        if (!SleepTimer.fireNow(context)) {
            // Nothing could dim the screen (the accessibility helper is off and goToSleep is denied).
            Toast.makeText(context, R.string.sleep_failed, Toast.LENGTH_LONG).show();
        }
    }
}
