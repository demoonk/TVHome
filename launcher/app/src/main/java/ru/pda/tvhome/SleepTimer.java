package ru.pda.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.PowerManager;
import android.os.SystemClock;

import java.lang.reflect.Method;

/**
 * The sleep timer behind the moon button. It is not an alarm any more: the accessibility service ticks it,
 * so the countdown can follow what the user is doing.
 *
 * Rules:
 *  - picking a duration turns it on and remembers it; picking "не засыпать" turns it off but keeps nothing running;
 *  - any user action (focus or content change seen by the service) restarts the countdown from the chosen duration;
 *  - while audio is playing the countdown holds and only starts again when the sound stops.
 *
 * The timer puts the panel to sleep: the display goes off, and any key brings it back.
 */
public final class SleepTimer {

    private static final String PREFS = "sleep_timer";
    private static final String MINUTES = "minutes";
    private static final String DEADLINE = "deadline";
    /** Wall-clock milliseconds of the sleep moment; 0 while nothing runs. */
    static final long NONE = 0L;
    /** Kept for SleepReceiver's old alarm intents; the timer itself no longer schedules any. */
    static final String EXTRA_WARN = "warn";

    private SleepTimer() {
    }

    /** Turns the timer on with this duration, and starts the countdown now. */
    public static void arm(Context context, int minutes) {
        long at = System.currentTimeMillis() + minutes * 60_000L;
        prefs(context).edit().putInt(MINUTES, minutes).putLong(DEADLINE, at).apply();
        OverlayService.start(context);
    }

    /** Turns it off; the remembered duration goes with it, as "не засыпать" is a choice too. */
    public static void cancel(Context context) {
        prefs(context).edit().putInt(MINUTES, 0).putLong(DEADLINE, NONE).apply();
        OverlayService.start(context);
    }

    /** Any user action: start the chosen duration over. */
    public static void touch(Context context) {
        int minutes = selectedMinutes(context);
        if (minutes <= 0) return;
        prefs(context).edit().putLong(DEADLINE, System.currentTimeMillis() + minutes * 60_000L).apply();
    }

    /** Audio is playing: hold the countdown where it is. */
    public static void hold(Context context) {
        int minutes = selectedMinutes(context);
        if (minutes <= 0) return;
        prefs(context).edit().putLong(DEADLINE, System.currentTimeMillis() + minutes * 60_000L).apply();
    }

    /**
     * After a reboot or a long standby the stored deadline can already be in the past. The chosen duration
     * must survive that: start the countdown over instead of switching the screen off the moment the TV comes up.
     */
    public static void reconcile(Context context) {
        int minutes = selectedMinutes(context);
        if (minutes <= 0) return;
        long deadline = deadline(context);
        if (deadline <= NONE || deadline + 60_000L < System.currentTimeMillis()) touch(context);
    }

    public static boolean armed(Context context) {
        return selectedMinutes(context) > 0 && deadline(context) > NONE;
    }

    /** The chosen duration in minutes, 0 while the timer is off. Default 15 — the value carried across the 2026-11-10 releases. */
    public static int selectedMinutes(Context context) {
        return prefs(context).getInt(MINUTES, 15);
    }

    /** Wall-clock deadline, NONE while nothing runs. */
    public static long deadline(Context context) {
        return prefs(context).getLong(DEADLINE, NONE);
    }

    /** Milliseconds left; never negative. */
    public static long remaining(Context context) {
        return Math.max(0L, deadline(context) - System.currentTimeMillis());
    }

    /** Switches the display off now. True when the system accepted the sleep request. */
    public static boolean fireNow(Context context) {
        // Sleep is actual display sleep, not a black overlay: the scrim only dimmed the panel and left its
        // backlight lit (bedroom TV, 2026-11-11). The admin's lockNow, the firmware's goToSleep and the
        // accessibility lock action all ask the system to put the display to sleep.
        if (lockNow(context)) return true;
        if (goToSleep(context)) return true;
        if (HomeRedirectService.sleepNow()) return true;
        if (HomeKeyService.sleepNow()) return true;
        return false;
    }

    /** DevicePolicyManager.lockNow() with the admin the installer activates; false when it is not active. */
    static boolean lockNow(Context context) {
        try {
            android.app.admin.DevicePolicyManager dpm = (android.app.admin.DevicePolicyManager)
                    context.getSystemService(Context.DEVICE_POLICY_SERVICE);
            android.content.ComponentName admin = new android.content.ComponentName(context, TvHomeAdmin.class);
            if (dpm == null || !dpm.isAdminActive(admin)) return false;
            dpm.lockNow();
            return true;
        } catch (RuntimeException | LinkageError unavailable) {
            return false;
        }
    }

    /** The timer fired: the countdown starts over from the chosen duration. */
    public static void clearFired(Context context) {
        touch(context);
    }

    /** PowerManager.goToSleep(long) is @hide, so it can only be reached by reflection. */
    private static boolean goToSleep(Context context) {
        try {
            PowerManager power = context.getSystemService(PowerManager.class);
            Method method = power.getClass().getMethod("goToSleep", long.class);
            method.invoke(power, SystemClock.uptimeMillis());
            return true;
        } catch (Exception unavailable) {
            return false;
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}