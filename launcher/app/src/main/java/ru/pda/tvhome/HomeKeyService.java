package ru.pda.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.UserManager;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Toast;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Sends the remote's Home button to this launcher on remotes that emit KEYCODE_ALL_APPS for Home
 * (Xiaomi sticks and boxes). Android TV 9 routes that key only to the system-image launcher, so once
 * the stock launcher is disabled the button does nothing. Every other key passes through untouched.
 *
 * With stock_home_redirect set, it also sends the stock launcher's home screen back here, for firmware that turns
 * that launcher on again after it was disabled (XGIMI's xrmservice does, on its own remote keys), or that cannot be
 * disabled at all, and a short press of Home opens this launcher even when the stock home is already on screen.
 *
 * Enable over adb:
 *   settings put secure enabled_accessibility_services ru.pda.tvhome/.HomeKeyService
 *   settings put secure accessibility_enabled 1
 */
public class HomeKeyService extends AccessibilityService {

    /** restore_key_services was on when the config was read: this helper is noted for ServiceRepair. */
    private boolean repairRecord;
    /** Remote buttons that should open Settings (config.xml, settings_key_scancodes and settings_key_keycodes). */
    private int[] settingsScancodes = new int[0];
    private int[] settingsKeycodes = new int[0];
    private boolean settingsPanel;
    /** app_keys, the same maps InputKeyService reads, for setups that run only this service. */
    private Map<Integer, String> appKeys = Collections.emptyMap();
    private Map<Integer, String> appScancodes = Collections.emptyMap();
    private Map<Integer, String> holdAppKeys = Collections.emptyMap();
    private Map<Integer, String> holdAppScancodes = Collections.emptyMap();

    /** Waits for the user to unlock when Android binds this service at boot; null once the config is read. */
    private BroadcastReceiver unlockReceiver;
    /** The stock launcher's HOME activities (stock_home_redirect), disabled ones included. */
    private final Set<String> stockHome = new HashSet<>();
    /** Stops key filtering while a game controller is connected; null unless gamepad_passthrough and key_filter, or before the unlock. */
    private GamepadPassthrough gamepads;
    /** The running helper; the sleep timer asks it to dim the screen (sleepNow). Null while unbound. */
    private static HomeKeyService instance;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        settingsScancodes = getResources().getIntArray(R.array.settings_key_scancodes);
        // The service is directBootAware: Android 11 skips a service that is not while the user is still
        // locked at boot, writes accessibility_enabled=0 and, on TCL G03 firmware, never binds it after the
        // unlock. Config.load reads credential-protected storage, so wait for the unlock before reading it.
        UserManager users = getSystemService(UserManager.class);
        if (users.isUserUnlocked()) {
            loadConfig();
            return;
        }
        unlockReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                stopWaitingForUnlock();
                loadConfig();
            }
        };
        registerReceiver(unlockReceiver, new IntentFilter(Intent.ACTION_USER_UNLOCKED));
        // The unlock may have happened between the check and the registration.
        if (users.isUserUnlocked()) {
            stopWaitingForUnlock();
            loadConfig();
        }
    }

    private void stopWaitingForUnlock() {
        if (unlockReceiver == null) return;
        unregisterReceiver(unlockReceiver);
        unlockReceiver = null;
    }

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        stopWaitingForUnlock();
        if (gamepads != null) gamepads.stop();
        super.onDestroy();
    }

    private void loadConfig() {
        // Keycodes and the panel choice can also come from a theme; theme.sh restarts this service.
        Config config = Config.load(this);
        // restore_key_services: note that this helper runs, so Home can put it back after a force stop. Here, not
        // in onServiceConnected: the note is in credential-protected storage too, readable only after the unlock.
        repairRecord = config.restoreKeyServices;
        if (repairRecord) ServiceRepair.connected(this, getClass());
        settingsKeycodes = config.settingsKeycodes;
        settingsPanel = config.settingsKeyPanel;
        appKeys = RemoteKeys.parseAppKeys(config.appKeys);
        appScancodes = RemoteKeys.parseAppScancodes(config.appKeys);
        RemoteKeys.applyKeyFilter(this, config.keyFilter);
        loadPictureKey();
        stockHome.clear();
        if (!config.stockHomeRedirect.isEmpty()) {
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(config.stockHomeRedirect);
            for (ResolveInfo ri : getPackageManager().queryIntentActivities(home, PackageManager.MATCH_DISABLED_COMPONENTS)) {
                stockHome.add(ri.activityInfo.name);
            }
        }
        // home_key_service.xml subscribes to no events, so the redirect asks for window changes only when it is on.
        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null && !stockHome.isEmpty()) {
            info.eventTypes |= AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;
            setServiceInfo(info);
        }
        if (!stockHome.isEmpty()) probeAtBoot();
        holdAppKeys = RemoteKeys.parseHoldAppKeys(config.appKeys);
        holdAppScancodes = RemoteKeys.parseHoldAppScancodes(config.appKeys);
        // gamepad_passthrough, off by default. Only with key_filter on: off, the service never filters keys.
        if (config.gamepadPassthrough && config.keyFilter && gamepads == null) {
            gamepads = new GamepadPassthrough(this);
            gamepads.start();
        }
    }

    /**
     * The button the owner picked for the TV's picture/sound menu. Empty means off; "key:<code>" is a keycode,
     * "scan:<code>" a raw scancode, for the buttons Android maps to nothing (this remote's gear is one).
     */
    private String pictureKeySpec = "";
    /** The TV's own picture menu; RemoteKeys understands "package/.Activity" and falls back quietly. */
    private static final String PICTURE_TARGET =
            "com.android.tv.settings/.partnercustomizer.picture.PictureActivity";

    private BroadcastReceiver pictureKeyReceiver;

    @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void loadPictureKey() {
        SharedPreferences prefs = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
        try {
            // Off by default: with a button chosen the service filters keys, and this firmware then stops
            // repeating the volume keys. The choice is in the launcher settings, one press away.
            pictureKeySpec = prefs.getString(Config.PICTURE_KEY_KEY, "");
        } catch (ClassCastException legacy) {
            // An older build stored a keycode here; drop it rather than crash on the type.
            prefs.edit().remove(Config.PICTURE_KEY_KEY).apply();
            pictureKeySpec = "";
        }
        if (pictureKeySpec == null || "off".equals(pictureKeySpec)) pictureKeySpec = "";
        applyKeyFilter(getServiceInfo());
        if (pictureKeyReceiver != null) return;
        pictureKeyReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                loadPictureKey();
            }
        };
        IntentFilter filter = new IntentFilter(Config.CLOCK_CHANGED_ACTION);
        try {
            registerReceiver(pictureKeyReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } catch (IllegalArgumentException | SecurityException older) {
            registerReceiver(pictureKeyReceiver, filter);
        }
    }

    private boolean isPictureKey(KeyEvent event) {
        if (pictureKeySpec == null || pictureKeySpec.isEmpty()) return false;
        try {
            if (pictureKeySpec.startsWith("scan:")) {
                return event.getScanCode() == Integer.parseInt(pictureKeySpec.substring(5).trim());
            }
            if (pictureKeySpec.startsWith("key:")) {
                return event.getKeyCode() == Integer.parseInt(pictureKeySpec.substring(4).trim());
            }
        } catch (NumberFormatException malformed) {
            return false;
        }
        return false;
    }

    /**
     * Key filtering is what lets the chosen button work while a film is playing, and it is what stops this
     * firmware from repeating the volume keys. So it is requested only while a button is chosen: off means the
     * service stays bound but filters nothing, and the remote's auto-repeat keeps working.
     */
    private void applyKeyFilter(AccessibilityServiceInfo info) {
        if (info == null) return;
        boolean wanted = pictureKeySpec != null && !pictureKeySpec.isEmpty();
        if (wanted) info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
        else info.flags &= ~AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
        setServiceInfo(info);
    }

    /** The TV's picture/sound menu over whatever is playing, the way the stock launcher opened it. */
    private void openPictureSettings() {
        android.content.ComponentName target =
                android.content.ComponentName.unflattenFromString(PICTURE_TARGET);
        Intent picture = target == null ? null : new Intent().setComponent(target);
        if (picture == null || getPackageManager().resolveActivity(picture, 0) == null) {
            picture = SystemSettings.intent(this, Settings.ACTION_DISPLAY_SETTINGS);
        }
        try {
            startActivity(picture.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException unavailable) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }
    private boolean isSettingsKey(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_SETTINGS) return true;
        for (int code : settingsScancodes) if (event.getScanCode() == code) return true;
        for (int code : settingsKeycodes) if (event.getKeyCode() == code) return true;
        return false;
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (RemoteKeys.wallpaperArrow(event)) return false;
        // A scancode entry is more specific than any keycode, so it goes first.
        if (RemoteKeys.handle(this, event, appScancodes.get(event.getScanCode()),
                holdAppScancodes.get(event.getScanCode()))) return true;
        // The stock launcher handled the remote's Settings button; with it disabled the button does nothing.
        if (isPictureKey(event)) {
            // The picture/sound key: the stock launcher used to open the TV's PQ menu, and with it gone the
            // button did nothing anywhere, watched films included. Open the same menu ourselves.
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) openPictureSettings();
            return true;
        }
        if (isSettingsKey(event)) {
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                Intent open = settingsPanel ? new Intent(this, QuickSettingsActivity.class)
                        : SystemSettings.intent(this, Settings.ACTION_SETTINGS);
                try {
                    startActivity(open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (ActivityNotFoundException | SecurityException unavailable) {
                    Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
                }
            }
            return true;
        }
        if (RemoteKeys.handle(this, event, appKeys.get(event.getKeyCode()),
                holdAppKeys.get(event.getKeyCode()))) return true;
        if (event.getKeyCode() == KeyEvent.KEYCODE_HOME && !stockHome.isEmpty()) {
            redirectHomeKey(event);
            return false;
        }
        if (event.getKeyCode() != KeyEvent.KEYCODE_ALL_APPS) return false;
        if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
            Intent home = new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .setPackage(getPackageName())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivity(home);
        }
        return true;
    }

    /** Unbound by Android: the user turned the helper off, so Home doesn't put it back (ServiceRepair). */
    @Override
    public boolean onUnbind(Intent intent) {
        if (repairRecord) ServiceRepair.disconnected(this, getClass());
        return super.onUnbind(intent);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // stock_home_redirect: the firmware turned the stock launcher back on and its home screen came up.
        if (stockHome.isEmpty() || event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence cls = event.getClassName();
        if (cls != null && stockHome.contains(cls.toString())) openHome();
    }

    /** How long after boot the first bind still counts as "at boot" for probeAtBoot. */
    static final long BOOT_WINDOW_MS = 3 * 60 * 1000L;

    /** stock_home_redirect at boot: the stock home is often on screen before Android binds this service, so no
     *  window event for it arrives (#1116). Once per boot, open ProbeActivity: when it closes, whatever was in
     *  front comes back and sends a window event, and onAccessibilityEvent covers it only if it is the stock home.
     *  An HDMI input or an app that the TV resumed at boot is left alone. */
    private void probeAtBoot() {
        int boot = Settings.Global.getInt(getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        SharedPreferences prefs = getSharedPreferences("boot", MODE_PRIVATE);
        if (!firstBindAfterBoot(boot, prefs.getInt("probe_boot", -1), SystemClock.elapsedRealtime())) return;
        prefs.edit().putInt("probe_boot", boot).apply();
        try {
            startActivity(new Intent(this, ProbeActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
        } catch (ActivityNotFoundException | SecurityException ignored) {
        }
    }

    static boolean firstBindAfterBoot(int boot, int lastBoot, long uptimeMs) {
        return boot >= 0 && boot != lastBoot && uptimeMs < BOOT_WINDOW_MS;
    }

    /** Lets the system see the key, so a long press still does what the firmware gives it. */
    private static final long HOME_KEY_DELAY_MS = 250;
    private final Handler handler = new Handler(Looper.getMainLooper());

    /** stock_home_redirect on the Home key: when the stock home is already on screen (it came up at boot, before
     *  Android bound this service), Home brings it up again with no window change, so the redirect above never
     *  fires and the TV stays on the stock home until another app opens (#1116). A short press opens this
     *  launcher shortly after the system has handled the key; a long press (Google TV's quick settings) is left
     *  alone. Held time, not repeats: key repeats are made after accessibility filtering, so none arrive here. */
    private void redirectHomeKey(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()
                && isShortPress(event.getDownTime(), event.getEventTime(), ViewConfiguration.getLongPressTimeout())) {
            handler.postDelayed(this::openHome, HOME_KEY_DELAY_MS);
        }
    }

    static boolean isShortPress(long downTime, long upTime, long longPressTimeout) {
        return upTime - downTime < longPressTimeout;
    }

    private void openHome() {
        startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(getPackageName())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
    }

    /**
     * Dims the screen: what the sleep timer does when its time comes (SleepTimer). The firmware's own
     * hidden goToSleep puts the display to sleep straight away where it is allowed; where it is blocked,
     * locking the screen does the same on Android TV without powering the TV itself off, and any remote
     * key wakes it. False when the helper is not bound or Android is too old for the action (API 28).
     */
    public static boolean sleepNow() {
        HomeKeyService service = instance;
        if (service == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false;
        try {
            android.os.PowerManager pm = (android.os.PowerManager) service.getSystemService(Context.POWER_SERVICE);
            pm.getClass().getMethod("goToSleep", long.class).invoke(pm, SystemClock.elapsedRealtime());
            return true;
        } catch (Exception hiddenBlocked) {
            // Firmware with the hidden call sealed: fall through to the lock action.
        }
        return service.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN);
    }

    @Override
    public void onInterrupt() {
    }
}
