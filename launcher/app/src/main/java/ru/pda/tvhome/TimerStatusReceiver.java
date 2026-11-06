package ru.pda.tvhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import org.json.JSONObject;

/** Read-only ordered-broadcast API. Does not touch or reset the countdown. */
public final class TimerStatusReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !"ru.pda.tvhome.action.TIMER_STATUS".equals(intent.getAction())) return;
        try {
            AudioManager audio = context.getSystemService(AudioManager.class);
            boolean enabled = SleepTimer.armed(context);
            boolean held = enabled && audio != null && audio.isMusicActive();
            JSONObject data = new JSONObject();
            data.put("version", 1);
            data.put("enabled", enabled);
            data.put("selected_minutes", SleepTimer.selectedMinutes(context));
            data.put("remaining_ms", enabled ? SleepTimer.remaining(context) : 0);
            data.put("deadline_epoch_ms", enabled ? SleepTimer.deadline(context) : 0);
            data.put("held_by_audio", held);
            data.put("warning_visible", SleepWarningOverlay.isVisible());
            data.put("state", !enabled ? "off" : held ? "held_audio" : "counting");
            data.put("sampled_at_epoch_ms", System.currentTimeMillis());
            setResultCode(-1);
            setResultData(data.toString());
        } catch (Exception error) { setResultCode(0); setResultData("{\"error\":\"status_unavailable\"}"); }
    }
}
