package com.gathin.tvhome;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ResolveInfo;
import android.provider.Settings;
import android.service.voice.VoiceInteractionService;

/** Changes the selected voice entry point; leaves Google's speech recognizer untouched. */
final class AssistantChoice {
    private static final String KEY = "voice_interaction_service";
    private AssistantChoice() {}

    static boolean isLauncher(Context context) {
        ComponentName selected = ComponentName.unflattenFromString(value(context, KEY));
        return own(context).equals(selected);
    }

    private static ComponentName own(Context context) {
        return new ComponentName(context, LauncherAssistantService.class);
    }

    private static String value(Context context, String key) {
        String result = Settings.Secure.getString(context.getContentResolver(), key);
        return result == null ? "" : result;
    }

    static boolean select(Context context, boolean launcher) {
        // Called off the UI thread: the shell guard acknowledges package operations.
        if (!guard(context, "check")) return false;
        SharedPreferences prefs = context.getSharedPreferences("assistant_choice", Context.MODE_PRIVATE);
        String previous = value(context, KEY);
        String previousAssistant = value(context, "assistant");
        String target;
        String assistant;
        if (launcher) {
            target = own(context).flattenToString();
            assistant = target;
            if (!isLauncher(context) && !prefs.edit().putString("system_service", previous)
                    .putString("system_assistant", previousAssistant).commit()) return false;
        } else {
            target = prefs.getString("system_service", "");
            assistant = prefs.getString("system_assistant", target);
            // Older builds selected us externally, before a previous selection could be saved.
            if (target.isEmpty()) {
                for (ResolveInfo info : context.getPackageManager().queryIntentServices(
                        new Intent(VoiceInteractionService.SERVICE_INTERFACE), android.content.pm.PackageManager.MATCH_DISABLED_COMPONENTS)) {
                    if (context.getPackageName().equals(info.serviceInfo.packageName)) continue;
                    if ((info.serviceInfo.applicationInfo.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0) continue;
                    if (!target.isEmpty()) return false; // Ambiguous: use the system chooser instead.
                    target = new ComponentName(info.serviceInfo.packageName, info.serviceInfo.name).flattenToString();
                }
                assistant = target;
            }
            ComponentName component = ComponentName.unflattenFromString(target);
            if (component == null) return false;
            try { context.getPackageManager().getServiceInfo(component, android.content.pm.PackageManager.MATCH_DISABLED_COMPONENTS); }
            catch (android.content.pm.PackageManager.NameNotFoundException missing) { return false; }
        }
        try {
            if (!launcher && !guard(context, "system")) return false;
            if (!Settings.Secure.putString(context.getContentResolver(), "assistant", assistant)
                    || !Settings.Secure.putString(context.getContentResolver(), KEY, target)
                    || !target.equals(value(context, KEY))) throw new IllegalStateException("Selection refused");
            if (launcher) {
                Settings.Secure.putString(context.getContentResolver(), "voice_recognition_service",
                        "com.google.android.tts/com.google.android.apps.speech.tts.googletts.service.GoogleTTSRecognitionService");
                if (!guard(context, "launcher")) throw new IllegalStateException("Package guard unavailable");
            }
            return true;
        } catch (RuntimeException refused) {
            try {
                Settings.Secure.putString(context.getContentResolver(), "assistant", previousAssistant);
                Settings.Secure.putString(context.getContentResolver(), KEY, previous);
            } catch (RuntimeException ignored) { /* Permission absent: nothing was changed. */ }
            return false;
        }
    }

    private static boolean guard(Context context, String mode) {
        String request = mode + ":" + java.util.UUID.randomUUID();
        try {
            if (!Settings.Secure.putString(context.getContentResolver(),
                    "ru_pda_tvhome_assistant_request", request)) return false;
            long until = android.os.SystemClock.elapsedRealtime() + 5000;
            while (android.os.SystemClock.elapsedRealtime() < until) {
                if (request.equals(value(context, "ru_pda_tvhome_assistant_ack"))) return true;
                Thread.sleep(100);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException refused) { return false; }
        return false;
    }
}
