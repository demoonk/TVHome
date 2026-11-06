package ru.pda.tvhome;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.provider.Settings;

final class PackageUpgrade {
    static String componentNames(String value) {
        return value == null ? null : value.replace("ru.pda.tvhome/com.gathin.tvhome.", "ru.pda.tvhome/ru.pda.tvhome.");
    }
    static void migrate(Context context) {
        SharedPreferences helpers = context.getSharedPreferences("accessibility_helpers", Context.MODE_PRIVATE);
        SharedPreferences.Editor edit = helpers.edit();
        for (String key : helpers.getAll().keySet()) {
            if (key.startsWith("com.gathin.tvhome.")) {
                edit.putBoolean(key.replace("com.gathin.tvhome.", "ru.pda.tvhome."), helpers.getBoolean(key, false)).remove(key);
            }
        }
        edit.apply();
        if (context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) return;
        for (String key : new String[]{"assistant", "voice_interaction_service", "enabled_accessibility_services", "screensaver_components"}) {
            try {
                String before = Settings.Secure.getString(context.getContentResolver(), key);
                String after = componentNames(before);
                if (before != null && !before.equals(after)) Settings.Secure.putString(context.getContentResolver(), key, after);
            } catch (RuntimeException unavailable) {
                android.util.Log.w("PackageUpgrade", "Component migration unavailable: " + key, unavailable);
            }
        }
    }
    private PackageUpgrade() {}
}
