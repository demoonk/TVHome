package ru.pda.tvhome;

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
    private static final String PREFS = "assistant_choice";
    /** The user's decision, kept across reboots: true for Home's own assistant, false for the system one. */
    private static final String CHOSEN_LAUNCHER = "chosen_launcher";
    static final String RECOGNIZER =
            "com.google.android.tts/com.google.android.apps.speech.tts.googletts.service.GoogleTTSRecognitionService";

    /**
     * The shell-side guard answers only while it is running, so after every reboot or framework restart the
     * settings are left empty or pointing at another assistant while the recorded choice is still Home's own.
     * Home starts by itself at boot and holds WRITE_SECURE_SETTINGS from adb, so it can put the three settings
     * back without waiting for the guard. The recorded choice wins over any guess: without the permission, or when
     * the recorded choice is the system assistant, nothing is written. A refusal from the settings provider is not
     * an error; the choice stays as it was, as before.
     */
    static void restore(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String own = own(context).flattenToString();
        boolean permission = context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
        String current = value(context, KEY);
        if (recordDecision(permission, prefs.getBoolean(CHOSEN_LAUNCHER, false), current, own)) {
            prefs.edit().putBoolean(CHOSEN_LAUNCHER, true).apply();
        }
        String recognizer = restoreDecision(permission, prefs.getBoolean(CHOSEN_LAUNCHER, false), current, own);
        if (recognizer == null) return;
        try {
            android.content.ContentResolver cr = context.getContentResolver();
            Settings.Secure.putString(cr, "assistant", own);
            Settings.Secure.putString(cr, KEY, own);
            Settings.Secure.putString(cr, "voice_recognition_service", recognizer);
        } catch (RuntimeException ignored) {
            // The settings are the system's; a refusal leaves the choice as it was, as before.
        }
    }

    /**
     * The choice applied over ADB by the installer is never recorded here, so a boot that empties the settings
     * cannot be repaired. When the settings already point at Home's own service while the permission is held,
     * that observation is the record. A decision alone, so a plain JVM test can call it.
     */
    static boolean recordDecision(boolean permissionGranted, boolean alreadyRecorded, String current, String ownComponent) {
        return permissionGranted && !alreadyRecorded && ownComponent.equals(current);
    }

    /** The decision alone, so a plain JVM test can call it: the recognizer to write, or null for nothing to write. */
    static String restoreDecision(boolean permissionGranted, boolean chosenLauncher, String current, String ownComponent) {
        if (!permissionGranted) return null;
        if (!chosenLauncher) return null;            // The recorded choice wins over any guess.
        if (ownComponent.equals(current)) return null; // Already Home's own: nothing to write.
        return RECOGNIZER;
    }

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
        if (!request(context, "check", "")) return false;
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
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
            if (!launcher && !request(context, "system", "")) return false;
            if (!Settings.Secure.putString(context.getContentResolver(), "assistant", assistant)
                    || !Settings.Secure.putString(context.getContentResolver(), KEY, target)
                    || !target.equals(value(context, KEY))) throw new IllegalStateException("Selection refused");
            if (launcher) {
                Settings.Secure.putString(context.getContentResolver(), "voice_recognition_service", RECOGNIZER);
                if (!request(context, "launcher", "")) throw new IllegalStateException("Package guard unavailable");
            }
            // Recorded only after the settings were applied: a rollback keeps the previous decision.
            prefs.edit().putBoolean(CHOSEN_LAUNCHER, launcher).apply();
            return true;
        } catch (RuntimeException refused) {
            try {
                Settings.Secure.putString(context.getContentResolver(), "assistant", previousAssistant);
                Settings.Secure.putString(context.getContentResolver(), KEY, previous);
            } catch (RuntimeException ignored) { /* Permission absent: nothing was changed. */ }
            return false;
        }
    }

    /** One request through the settings handshake: the guard answers only after doing its part. */
    static boolean request(Context context, String mode, String arg) {
        return guard(context, HelperProtocol.format(mode, arg, java.util.UUID.randomUUID().toString()));
    }

    /** The permissions the launcher declared but cannot grant to itself; the helper runs as shell. */
    static boolean grant(Context context) { return request(context, "grant", ""); }

    /** One package from the launcher's own safe list; the helper rechecks its own protected mirror. */
    static boolean freeze(Context context, String pkg) { return request(context, "freeze", pkg); }

    static boolean unfreeze(Context context, String pkg) { return request(context, "unfreeze", pkg); }

    private static boolean guard(Context context, String request) {
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
