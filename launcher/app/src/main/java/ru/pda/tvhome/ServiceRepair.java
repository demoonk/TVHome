package ru.pda.tvhome;

import android.Manifest;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.provider.Settings;

import java.util.ArrayList;
import java.util.List;

/**
 * Android takes an app's accessibility services off the enabled list when the app is force-stopped (Settings, a
 * cleaner app, or am force-stop), and the remote's Home, Input and app buttons then stop working until the helper
 * is turned on again over ADB. With restore_key_services on, a running helper records itself here and drops the
 * record when Android unbinds it (the user turned it off); a force stop kills the process before that, so the
 * record stays. Home starting again puts recorded helpers back on the list, when the installer granted
 * WRITE_SECURE_SETTINGS. With the flag off nothing is recorded and nothing is restored. A boot clears the whole
 * list, so the boot receiver calls this too, and the redirect watcher is added even without a record: it is the
 * service that answers the firmware's recovery screen without asking Android to filter keys.
 */
final class ServiceRepair {
    private static final String PREFS = "accessibility_helpers";

    private ServiceRepair() {
    }

    static void connected(Context context, Class<?> service) {
        prefs(context).edit().putBoolean(service.getName(), true).apply();
    }

    static void disconnected(Context context, Class<?> service) {
        prefs(context).edit().remove(service.getName()).apply();
    }

    static void restore(Context context) {
        if (context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                != PackageManager.PERMISSION_GRANTED) return;
        try {
            ContentResolver cr = context.getContentResolver();
            String list = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            List<ComponentName> enabled = new ArrayList<>();
            if (list != null) {
                for (String entry : list.split(":")) {
                    ComponentName name = ComponentName.unflattenFromString(entry);
                    if (name != null) enabled.add(name);
                }
            }
            // The key-filtering helper answers the picture key and, on this firmware, stops the volume keys from
            // repeating. It may only be on the list while a picture button is chosen; otherwise it is dropped.
            boolean pictureKeyOn = !isPictureKeyOff(context);
            List<ComponentName> wanted = new ArrayList<>();
            for (ComponentName name : enabled) {
                if (keeps(name.getClassName(), pictureKeyOn, true)) wanted.add(name);
            }
            // The redirect watcher records nothing itself because it asks for no keys, so nothing added it after
            // the boot cleared the list. Home is on the list only while a picture button is chosen.
            ComponentName redirect = new ComponentName(context.getPackageName(), REDIRECT_SERVICE);
            if (!wanted.contains(redirect)) wanted.add(redirect);
            for (String service : prefs(context).getAll().keySet()) {
                ComponentName name = new ComponentName(context.getPackageName(), service);
                if (wanted.contains(name)) continue;
                if (!keeps(service, pictureKeyOn, true)) {
                    prefs(context).edit().remove(service).apply();
                    continue;
                }
                wanted.add(name);
            }
            StringBuilder kept = new StringBuilder();
            for (ComponentName name : wanted) {
                if (kept.length() > 0) kept.append(':');
                kept.append(name.flattenToShortString());
            }
            String before = list == null || "null".equals(list) ? "" : list;
            if (kept.toString().equals(before)) return;
            boolean written = Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, kept.toString());
            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            android.util.Log.w("ServiceRepair", "list before='" + before + "' after='" + kept + "' written=" + written);
        } catch (RuntimeException ignored) {
            // The setting is the system's; a refusal leaves the helper off, as before.
            android.util.Log.w("ServiceRepair", "restore refused", ignored);
        }
    }

    /** The service that answers the recovery screen without asking Android to filter keys. */
    private static final String REDIRECT_SERVICE = "ru.pda.tvhome.HomeRedirectService";

    /**
     * Which services belong on the enabled list. A reboot leaves the list empty and nothing put it back, so the
     * Home button landed on the firmware's recovery stub instead of here. The redirect watcher asks for no keys
     * at all, so it is kept even when it was never recorded; the key-filtering helper only stays while a picture
     * button is chosen. A decision alone, so a plain JVM test can call it.
     */
    static boolean keeps(String className, boolean pictureKeyOn, boolean recorded) {
        if (className.endsWith("HomeRedirectService")) return true;
        if (isKeyFilterHelperName(className)) return pictureKeyOn;
        return recorded;
    }

    private static boolean isKeyFilterHelperName(String className) {
        return className.endsWith("HomeKeyService");
    }

    private static boolean isPictureKeyOff(Context context) {
        String spec;
        try {
            spec = context.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE)
                    .getString(Config.PICTURE_KEY_KEY, "");
        } catch (ClassCastException legacy) {
            return true;
        }
        return spec == null || spec.isEmpty() || "off".equals(spec);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
