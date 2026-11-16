package ru.pda.tvhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Brings the launcher up right after the TV boots. Some firmwares ignore the saved preferred home at boot and
 * hand HOME to their own recovery stub, which then re-launches itself in a loop; starting our HOME task here
 * means the screen is ours the moment the system is ready for it.
 *
 * It also answers one maintenance action, {@link #ACTION_PICTURE_KEY_OFF}, which turns the picture-menu key
 * interception off over ADB: the filter helper is what stops this firmware from repeating the volume keys, and a
 * stuck choice has to be resettable without walking the settings on the TV.
 */
public final class BootReceiver extends BroadcastReceiver {

    /** adb shell am broadcast -a ru.pda.tvhome.action.PICTURE_KEY_OFF -n ru.pda.tvhome/ru.pda.tvhome.BootReceiver */
    public static final String ACTION_PICTURE_KEY_OFF = "ru.pda.tvhome.action.PICTURE_KEY_OFF";
    /** adb shell am broadcast -a ru.pda.tvhome.action.VOICE_KEY_ON -e on true -n ru.pda.tvhome/ru.pda.tvhome.BootReceiver
     *  Turns the remote's microphone button into the voice search. Answering an unmapped key needs a filtering
     *  helper, which this firmware pays for by dropping the volume keys' auto-repeat; off gives the repeat back. */
    public static final String ACTION_VOICE_KEY = "ru.pda.tvhome.action.VOICE_KEY";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (ACTION_PICTURE_KEY_OFF.equals(action)) {
            pictureKeyOff(context);
            return;
        }
        if (ACTION_VOICE_KEY.equals(action)) {
            // adb's -e sends a string, so a plain getBooleanExtra would read false.
            String requested = intent.getStringExtra("on");
            voiceKey(context, requested != null ? !"false".equalsIgnoreCase(requested)
                    : intent.getBooleanExtra("on", true));
            return;
        }
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !"android.intent.action.QUICKBOOT_POWERON".equals(action)) return;
        OverlayService.start(context);
        // A reboot leaves the assistant choice and the accessibility helpers behind: the shell-side guard is not
        // running then, and the accessibility list is empty until something writes it. Home holds
        // WRITE_SECURE_SETTINGS from the installer, so both are put back here before Home is opened.
        AssistantChoice.restore(context);
        if (Config.load(context).restoreKeyServices) ServiceRepair.restore(context);
        try {
            context.startActivity(new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .setPackage(context.getPackageName())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException refused) {
            // Nothing to do: the accessibility redirect and the next HOME press still land here.
        }
    }

    /** Forgets the chosen picture key and drops the key-filtering helper from the enabled accessibility list. */
    private void pictureKeyOff(Context context) {
        context.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE)
                .edit().putString(Config.PICTURE_KEY_KEY, "off").apply();
        ServiceRepair.setKeyHelper(context, false);
        context.sendBroadcast(new Intent(Config.CLOCK_CHANGED_ACTION).setPackage(context.getPackageName()));
    }

    /** Records the microphone-button choice and puts the filtering helper on (or off) the accessibility list. */
    private void voiceKey(Context context, boolean on) {
        context.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(Config.VOICE_KEY_KEY, on).apply();
        // The helper is only worth its price (no volume auto-repeat) while it has a button to answer.
        ServiceRepair.setKeyHelper(context, on || !isPictureKeyOff(context));
        context.sendBroadcast(new Intent(Config.VOICE_KEY_CHANGED_ACTION).setPackage(context.getPackageName()));
    }

    private boolean isPictureKeyOff(Context context) {
        String spec = context.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE)
                .getString(Config.PICTURE_KEY_KEY, "");
        return spec == null || spec.isEmpty() || "off".equals(spec);
    }
}
