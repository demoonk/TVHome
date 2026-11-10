package ru.pda.tvhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;

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

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (ACTION_PICTURE_KEY_OFF.equals(action)) {
            pictureKeyOff(context);
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
        try {
            String helper = "ru.pda.tvhome/ru.pda.tvhome.HomeKeyService";
            String list = Settings.Secure.getString(context.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (list == null) return;
            StringBuilder kept = new StringBuilder();
            for (String part : list.split(":")) {
                String name = part.trim();
                if (name.isEmpty() || name.equals(helper)) continue;
                if (kept.length() > 0) kept.append(':');
                kept.append(name);
            }
            Settings.Secure.putString(context.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, kept.toString());
        } catch (RuntimeException ignored) {
            // Without WRITE_SECURE_SETTINGS only the preference is reset; the next Home start drops the helper.
        }
        context.sendBroadcast(new Intent(Config.CLOCK_CHANGED_ACTION).setPackage(context.getPackageName()));
    }
}
