package ru.pda.tvhome;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/**
 * The gear bridge cannot be started by the launcher: only the shell domain reads /dev/input and runs
 * app_process, so the launcher asks the on-device helper, which connects to adbd with the key the TV has
 * already recorded and runs the ensure script there.
 *
 * Loading is manual by default: the settings row in the «Система» section asks, and the helper answers that one
 * ask. Home asks on its own only when the row is set to «При старте Home», and then once per start, after the
 * first frame is drawn — at BOOT_COMPLETED the Bluetooth remote is often not enumerated yet, so a bridge started
 * then would start on nothing. Every extra ask is another connection and another chance of a key dialog on the TV,
 * so one ask at a time is the rule, and this class is the only place that decides it.
 */
final class BridgeTrigger {
    static final String HELPER_PACKAGE = "io.github.sumyabazar1.bridgehelper";
    static final String ACTION = "io.github.sumyabazar1.bridgehelper.action.ENSURE";

    /** The settings key for the loading mode; the default is manual, so nothing loads without a press. */
    static final String AUTO_KEY = "bridge_auto_load";
    static final String[] MODES = {"Вручную", "При старте Home"};

    /** Loading is manual until the row turns it on: nothing on the TV runs without a press. */
    static boolean autoDefault() {
        return false;
    }

    static boolean auto(SharedPreferences prefs) {
        return prefs.getBoolean(AUTO_KEY, autoDefault());
    }

    static int modeIndex(boolean auto) {
        return auto ? 1 : 0;
    }

    /**
     * Whether the row may ask: a broadcast to an app that is not installed goes nowhere, so the row stays closed
     * until the helper is on the TV.
     */
    static boolean canAsk(boolean helperInstalled) {
        return helperInstalled;
    }

    private boolean asked;

    /** True only for the first call, so Home asks the helper once per process start. */
    boolean shouldAsk() {
        if (asked) return false;
        asked = true;
        return true;
    }

    boolean asked() {
        return asked;
    }

    boolean helperInstalled(Context context) {
        try {
            context.getPackageManager().getPackageInfo(HELPER_PACKAGE, 0);
            return true;
        } catch (android.content.pm.PackageManager.NameNotFoundException missing) {
            return false;
        }
    }

    void ask(Context context) {
        context.sendBroadcast(new Intent(ACTION).setPackage(HELPER_PACKAGE));
    }
}
