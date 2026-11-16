package ru.pda.micprobe;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Intent;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

/**
 * Handles the remote's microphone button. On this firmware (AI PONT / CHiQ) the button arrives as an
 * unmapped key: keyCode=KEYCODE_UNKNOWN, scanCode=240, only an ACTION_DOWN. Android dispatches it to
 * nobody, so voice search never starts. This service turns that scancode into the launcher's
 * VoiceLaunchActivity. Set VOICE_SCANS to {-1} to make it a pure logger again.
 */
public class KeyProbeService extends AccessibilityService {
    /** Scancodes that mean "voice": the CHiQ remote's mic button is 240 (0xF0). */
    private static final int[] VOICE_SCANS = {240};

    private File log;
    private long lastVoiceAt = 0;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        log = new File(getExternalFilesDir(null), "keys.log");
        AccessibilityServiceInfo info = getServiceInfo();
        if (info == null) info = new AccessibilityServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;
        info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.notificationTimeout = 100;
        setServiceInfo(info);
        append("connected filter=" + ((getServiceInfo().flags
                & AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS) != 0));
        Log.w("micprobe", "voice key service connected: " + getServiceInfo());
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        int scan = event.getScanCode();
        boolean voice = false;
        for (int code : VOICE_SCANS) {
            if (scan == code) { voice = true; break; }
        }
        String line = "key keyCode=" + event.getKeyCode() + " scanCode=" + scan
                + " action=" + event.getAction() + " source=" + event.getSource()
                + " flags=" + event.getFlags() + (voice ? " -> voice" : "");
        append(line);
        Log.w("micprobe", line);

        if (voice && event.getAction() == KeyEvent.ACTION_DOWN) {
            // One launch per press: firmware sends no ACTION_UP for this button.
            if (event.getEventTime() - lastVoiceAt > 1000) {
                lastVoiceAt = event.getEventTime();
                try {
                    startActivity(new Intent(Intent.ACTION_ASSIST)
                            .setComponent(new android.content.ComponentName(
                                    "ru.pda.tvhome", "ru.pda.tvhome.VoiceLaunchActivity"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    append("voice started");
                } catch (Exception failure) {
                    append("voice failed: " + failure);
                }
            }
            return true;   // consume the unmapped key
        }
        return false;      // everything else passes through untouched
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        append("event type=" + event.getEventType() + " package=" + event.getPackageName());
    }

    private void append(String line) {
        if (log == null) return;
        try (FileWriter writer = new FileWriter(log, true)) {
            writer.write(System.currentTimeMillis() + " " + line + "\n");
        } catch (IOException ignored) {
        }
    }

    @Override
    public void onInterrupt() {
    }
}
