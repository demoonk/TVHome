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
 * WRITE_SECURE_SETTINGS. With the flag off nothing is recorded and nothing is restored.
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
            StringBuilder kept = new StringBuilder();
            for (ComponentName name : enabled) {
                if (isKeyFilterHelper(name) && !pictureKeyOn) continue;
                if (kept.length() > 0) kept.append(':');
                kept.append(name.flattenToShortString());
            }
            boolean changed = kept.length() != (list == null || list.equals("null") ? 0 : list.length());
            for (String service : prefs(context).getAll().keySet()) {
                ComponentName name = new ComponentName(context.getPackageName(), service);
                if (enabled.contains(name)) continue;
                if (isKeyFilterHelper(name) && !pictureKeyOn) {
                    prefs(context).edit().remove(service).apply();
                    continue;
                }
                if (kept.length() > 0) kept.append(':');
                kept.append(name.flattenToShortString());
                changed = true;
            }
            if (!changed) return;
            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, kept.toString());
            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1);
        } catch (RuntimeException ignored) {
            // The setting is the system's; a refusal leaves the helper off, as before.
        }
    }

    /** The picture-key helper, the only service here that asks Android to filter keys. */
    private static boolean isKeyFilterHelper(ComponentName name) {
        return name.getClassName() != null && name.getClassName().endsWith("HomeKeyService");
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
