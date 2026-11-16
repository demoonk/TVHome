package ru.pda.tvhome;

import android.app.admin.DeviceAdminReceiver;

/**
 * An inactive admin whose only policy is force-lock, activated by the installer with
 * {@code dpm set-active-admin}. The sleep timer then calls {@link android.app.admin.DevicePolicyManager#lockNow()}:
 * unlike a black overlay window, that asks the system to put the display to sleep, so the TV's backlight goes off
 * (bedroom TV, 2026-11-11: the black overlay stayed lit). Where the admin was never activated, SleepTimer falls
 * back to the shell/accessibility paths and finally to the overlay.
 */
public final class TvHomeAdmin extends DeviceAdminReceiver {
}
