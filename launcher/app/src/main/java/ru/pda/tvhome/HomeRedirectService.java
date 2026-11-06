package ru.pda.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.PixelFormat;
import android.media.AudioManager;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.TextClock;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.UserManager;
import android.provider.Settings;
import android.view.accessibility.AccessibilityEvent;

import java.util.HashSet;
import java.util.Set;

/**
 * stock_home_redirect without key filtering. Some firmware stops auto-repeating the remote's keys (the volume
 * keys above all) as soon as an enabled accessibility service asks for key filtering, and long-pressing volume
 * then steps once — see RemoteKeys.applyKeyFilter. This service asks for no keys at all: it only watches the
 * stock launcher's home screen come up and opens this launcher instead, which is everything a set that needs no
 * remapped remote buttons needs. Enable it instead of HomeKeyService:
 *   settings put secure enabled_accessibility_services ru.pda.tvhome/.HomeRedirectService
 *   settings put secure accessibility_enabled 1
 * With the stock home already on screen a short Home press cannot be seen here (that path needs the key
 * filter), so the redirect works on window changes and at boot (probeAtBoot).
 */
public class HomeRedirectService extends AccessibilityService {
    private SleepWarningOverlay sleepWarning;
    private static HomeRedirectService sleepService;

    static boolean sleepNow() {
        HomeRedirectService service = sleepService;
        if (service == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false;
        try { return service.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN); }
        catch (RuntimeException unavailable) { return false; }
    }


    /** The stock launcher's HOME activities (stock_home_redirect), disabled ones included. */
    private final Set<String> stockHome = new HashSet<>();
    /**
     * Android TV's own recovery screen. When Home is briefly ownerless — the launcher is being reinstalled, or
     * something stopped it — the system brings this stub up and it re-launches itself every few milliseconds,
     * forever, pegging the CPU (load average of 70 and a frozen grid). Seeing it means: open Home at once.
     */
    private static final String RECOVERY_HOME = "com.google.android.tungsten.setupwraith.RecoveryActivity";
    /** The last time this service opened Home, to keep the recovery screen from making it loop as well. */
    private long lastOpen;
    /** The sleep timer's once-a-second tick, and the last moment user activity restarted it. */
    private final Handler sleepHandler = new Handler(Looper.getMainLooper());
    /** The clock floating over other apps: our own overlay window, none while Home is on screen. */
    private WindowManager windowManager;
    private TextClock clockOverlay;
    /** OLED pixel shift: the same ±3px nudge the launcher gives its own views, applied to the floating clock. */
    private final java.util.Random shiftRandom = new java.util.Random();
    private long lastShiftAt;
    private int shiftDx, shiftDy;
    /** The window in front the last time it changed, so a shift can redraw without a new event. */
    private CharSequence lastForeground;
    private boolean lastInOwnSettings;
    /** Redraws the clock as soon as its settings change while an app is on screen. */
    private BroadcastReceiver clockChangedReceiver;
    private BroadcastReceiver screenReceiver;
    private long lastActivity;
    private final Runnable sleepTick = new Runnable() {
        @Override
        public void run() {
            if (OverlayService.isAlive(HomeRedirectService.this)) {
                sleepHandler.postDelayed(this, 1000L);
                return;
            }
            SleepTimer.reconcile(HomeRedirectService.this);
            tickSleep();
            tickOledShift();
            sleepHandler.postDelayed(this, 1000L);
        }
    };
    /** Waits for the user to unlock when Android binds this service at boot; null once the config is read. */
    private BroadcastReceiver unlockReceiver;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        // The service is directBootAware: Android 11 skips a service that is not while the user is still locked
        // at boot and writes accessibility_enabled=0, and Config.load reads credential-protected storage. Wait
        // for the unlock before reading it, as HomeKeyService does.
        UserManager users = getSystemService(UserManager.class);
        if (users != null && users.isUserUnlocked()) {
            connect();
            return;
        }
        unlockReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                stopWaitingForUnlock();
                connect();
            }
        };
        registerReceiver(unlockReceiver, new IntentFilter(Intent.ACTION_USER_UNLOCKED));
        // The unlock may have happened between the check and the registration.
        if (users != null && users.isUserUnlocked()) {
            stopWaitingForUnlock();
            connect();
        }
    }

    private void stopWaitingForUnlock() {
        if (unlockReceiver == null) return;
        unregisterReceiver(unlockReceiver);
        unlockReceiver = null;
    }

    @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void connect() {
        sleepService = this;
        Config config = Config.load(this);
        stockHome.clear();
        // A comma-separated list: one firmware ships launcherx, another the plain Google TV launcher, and
        // some have both. Any of them showing up means the remote's Home went to the stock screen.
        for (String pkg : config.stockHomeRedirect.split(",")) {
            String name = pkg.trim();
            if (name.isEmpty()) continue;
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(name);
            for (ResolveInfo ri : getPackageManager().queryIntentActivities(home,
                    PackageManager.MATCH_DISABLED_COMPONENTS)) {
                stockHome.add(ri.activityInfo.name);
            }
        }
        AccessibilityServiceInfo info = getServiceInfo();
        // Window changes are watched even with no stock home configured: the recovery stub is always worth seeing.
        // Focus and content changes come along for the sleep timer: they are how "any user action" is seen
        // without asking for key filtering, which breaks the remote's auto-repeat.
        if (info != null) {
            info.eventTypes |= AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                    | AccessibilityEvent.TYPE_VIEW_FOCUSED
                    | AccessibilityEvent.TYPE_VIEW_CLICKED
                    | AccessibilityEvent.TYPE_VIEW_SCROLLED;
            setServiceInfo(info);
        }
        sleepHandler.removeCallbacks(sleepTick);
        sleepHandler.post(sleepTick);
        // Settings changed: redraw the floating clock at once instead of waiting for the next app switch.
        if (clockChangedReceiver == null) {
            clockChangedReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    CharSequence fg = foregroundPackage();
                    updateClockOverlay(fg, true);   // a settings change always happens from our settings screen
                }
            };
            IntentFilter filter = new IntentFilter(Config.CLOCK_CHANGED_ACTION);
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(clockChangedReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(clockChangedReceiver, filter);
            }
        }
        if (screenReceiver == null) {
            screenReceiver = new BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent intent) {
                    if (sleepWarning != null) sleepWarning.hide();
                    if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())
                            || Intent.ACTION_USER_PRESENT.equals(intent.getAction())) {
                        SleepTimer.touch(HomeRedirectService.this);
                    }
                }
            };
            IntentFilter screen = new IntentFilter(Intent.ACTION_SCREEN_ON);
            screen.addAction(Intent.ACTION_SCREEN_OFF);
            screen.addAction(Intent.ACTION_USER_PRESENT);
            if (Build.VERSION.SDK_INT >= 33)
                registerReceiver(screenReceiver, screen, Context.RECEIVER_NOT_EXPORTED);
            else registerReceiver(screenReceiver, screen);
        }
        // Whatever is on screen right now gets the clock without waiting for the next window change.
        updateClockOverlay(foregroundPackage());
        if (!stockHome.isEmpty()) probeAtBoot();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        int eventType = event.getEventType();
        boolean interaction = eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED
                || eventType == AccessibilityEvent.TYPE_VIEW_CLICKED
                || eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED;
        if (interaction && (!SleepWarningOverlay.isVisible() || !getPackageName().contentEquals(
                event.getPackageName() == null ? "" : event.getPackageName()))) noteUserActivity();
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence cls = event.getClassName();
        if (cls == null) return;
        String name = cls.toString();
        // The recovery stub above wins over the stock-home rule: it never settles on its own.
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            CharSequence foreground = event.getPackageName();
            if (foreground == null) foreground = foregroundPackage();
            // Our own overlay raises window events too. Treating those as "the launcher is on screen" is what
            // made the clock blink once and disappear; only our real activities take it away.
            boolean ourPackage = foreground != null && getPackageName().contentEquals(foreground);
            boolean ourActivity = name.endsWith("MainActivity") || name.endsWith("LauncherSettingsActivity");
            // In the launcher's own settings the clock stays on screen: that is where it is set up.
            boolean ourSettings = name.endsWith("LauncherSettingsActivity");
            lastForeground = foreground;
            lastInOwnSettings = ourSettings;
            if (!ourPackage || ourActivity) updateClockOverlay(foreground, ourSettings);
        }
        if (RECOVERY_HOME.equals(name)) {
            openHomeThrottled();
            return;
        }
        // stock_home_redirect: the firmware turned the stock launcher back on and its home screen came up.
        if (!stockHome.isEmpty() && stockHome.contains(name)) openHomeThrottled();
    }

    /**
     * Draws the clock over whichever app is on screen, and takes it away on Home. Colour, corner and size come
     * from the launcher's settings; the window is an accessibility overlay, so no other permission is needed.
     */
    private void updateClockOverlay(CharSequence foreground) {
        updateClockOverlay(foreground, false);
    }

    private void updateClockOverlay(CharSequence foreground, boolean inOwnSettings) {

        if (OverlayService.isAlive(this)) {
            if (clockOverlay != null && clockOverlay.getParent() != null) windowManager().removeView(clockOverlay);
            return;
        }
        boolean otherApp = inOwnSettings || (foreground != null && !getPackageName().contentEquals(foreground));
        if (!otherApp) {
            if (clockOverlay != null && clockOverlay.getParent() != null) {
                windowManager().removeView(clockOverlay);
            }
            return;
        }
        SharedPreferences prefs = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
        if (!prefs.getBoolean(Config.CLOCK_ON_KEY, false)) {
            if (clockOverlay != null && clockOverlay.getParent() != null) windowManager().removeView(clockOverlay);
            return;
        }
        int alpha = Math.max(10, Math.min(100, prefs.getInt(Config.CLOCK_ALPHA_KEY, 40))) * 255 / 100;
        int color = (alpha << 24) | 0x00FFFFFF;
        int size = prefs.getInt(Config.CLOCK_SIZE_KEY, 10);
        int placeX = prefs.getInt(Config.CLOCK_X_KEY, Integer.MIN_VALUE);
        int placeY = prefs.getInt(Config.CLOCK_Y_KEY, Integer.MIN_VALUE);
        if (clockOverlay == null) {
            clockOverlay = new TextClock(this);
            clockOverlay.setFormat24Hour("HH:mm");
            clockOverlay.setFormat12Hour("h:mm");
            // No font padding: at y=0 the digits must touch the top edge, not float a few pixels below.
            clockOverlay.setIncludeFontPadding(false);
            clockOverlay.setPadding(0, 0, 0, 0);
        }
        clockOverlay.setTextColor(color);
        clockOverlay.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        String family = prefs.getString(Config.CLOCK_FONT_KEY, "sans-serif");
        clockOverlay.setTypeface(android.graphics.Typeface.create(family, android.graphics.Typeface.NORMAL));
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        // Absolute, in dp from the top-left corner: what the D-pad placement screen stores.
        float density = getResources().getDisplayMetrics().density;
        int screenW = Math.round(getResources().getDisplayMetrics().widthPixels / density);
        params.gravity = Gravity.TOP | Gravity.START;
        if (placeX == Integer.MIN_VALUE || placeY == Integer.MIN_VALUE) {
            params.x = Math.round((screenW - 70) * density);   // the default spot: top right, 6dp in
            params.y = Math.round(6 * density);
        } else {
            params.x = Math.round(placeX * density);
            params.y = Math.round(placeY * density);
        }
        params.x += shiftDx;   // the OLED shift rides on top of the chosen spot
        params.y += shiftDy;
        try {
            if (clockOverlay.getParent() == null) windowManager().addView(clockOverlay, params);
            else windowManager().updateViewLayout(clockOverlay, params);
            android.util.Log.i("TvHomeClock", "overlay shown for " + foreground + " at " + params.x + "," + params.y);
        } catch (RuntimeException refused) {
            android.util.Log.w("TvHomeClock", "accessibility overlay refused: " + refused, refused);
            // Fall back to the ordinary app-overlay window, which needs SYSTEM_ALERT_WINDOW (granted over adb).
            try {
                params.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
                windowManager().addView(clockOverlay, params);
                android.util.Log.i("TvHomeClock", "application overlay shown instead");
            } catch (RuntimeException alsoRefused) {
                android.util.Log.w("TvHomeClock", "application overlay refused too", alsoRefused);
            }
        }
    }

    /** The package of the window in front, or null when nothing readable is focused. */
    private CharSequence foregroundPackage() {
        try {
            android.view.accessibility.AccessibilityNodeInfo root = getRootInActiveWindow();
            return root == null ? null : root.getPackageName();
        } catch (Exception unreadable) {
            return null;
        }
    }

    private WindowManager windowManager() {
        if (windowManager == null) windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        return windowManager;
    }

    /** Any focus or content change counts as the user doing something: the sleep countdown starts over. */
    private void noteUserActivity() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastActivity < 700L) return;
        lastActivity = now;
        SleepTimer.touch(this);
    }

    /** Every five minutes, nudge the floating clock by up to ±3px when the OLED shift is on. */
    private void tickOledShift() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastShiftAt < 5 * 60_000L) return;
        lastShiftAt = now;
        boolean on = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE)
                .getBoolean(Config.OLED_SHIFT_KEY, false);
        int dx = on ? shiftRandom.nextInt(7) - 3 : 0;
        int dy = on ? shiftRandom.nextInt(7) - 3 : 0;
        if (dx == shiftDx && dy == shiftDy) return;
        shiftDx = dx;
        shiftDy = dy;
        if (lastForeground != null || lastInOwnSettings) updateClockOverlay(lastForeground, lastInOwnSettings);
    }

    /** Once a second: hold the countdown while audio plays, dim the screen when it runs out. */
    private void tickSleep() {
        if (sleepWarning == null) sleepWarning = new SleepWarningOverlay(this);
        android.os.PowerManager power = getSystemService(android.os.PowerManager.class);
        if (power != null && !power.isInteractive()) { sleepWarning.hide(); return; }
        if (!SleepTimer.armed(this)) { sleepWarning.hide(); return; }
        AudioManager audio = getSystemService(AudioManager.class);
        if (audio != null && audio.isMusicActive()) {
            sleepWarning.hide();
            SleepTimer.hold(this);
            return;
        }
        long remaining = SleepTimer.remaining(this);
        if (remaining <= 0L) {
            sleepWarning.hide();
            SleepTimer.fireNow(this);
            SleepTimer.touch(this);
        } else if (remaining <= SleepWarningOverlay.WARNING_MS) {
            sleepWarning.show(remaining);
        } else {
            sleepWarning.hide();
        }
    }

    /** Opens Home at most once every three seconds: the recovery stub fires far faster than that. */
    private void openHomeThrottled() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastOpen < 3000L) return;
        lastOpen = now;
        openHome();
    }

    /** Once per boot, open ProbeActivity so whatever is in front sends a window event when it closes. */
    private void probeAtBoot() {
        int boot = Settings.Global.getInt(getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        SharedPreferences prefs = getSharedPreferences("boot", MODE_PRIVATE);
        if (!HomeKeyService.firstBindAfterBoot(boot, prefs.getInt("probe_boot", -1),
                SystemClock.elapsedRealtime())) return;
        prefs.edit().putInt("probe_boot", boot).apply();
        try {
            startActivity(new Intent(this, ProbeActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
        } catch (android.content.ActivityNotFoundException | SecurityException ignored) {
        }
    }

    private void openHome() {
        startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(getPackageName())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
    }

    @Override
    public void onDestroy() {
        if (sleepService == this) sleepService = null;
        if (sleepWarning != null) sleepWarning.hide();
        stopWaitingForUnlock();
        if (clockChangedReceiver != null) {
            try { unregisterReceiver(clockChangedReceiver); } catch (IllegalArgumentException ignored) {}
            clockChangedReceiver = null;
        }
        if (screenReceiver != null) {
            try { unregisterReceiver(screenReceiver); } catch (IllegalArgumentException ignored) {}
            screenReceiver = null;
        }
        sleepHandler.removeCallbacks(sleepTick);
        if (clockOverlay != null && clockOverlay.getParent() != null) windowManager().removeView(clockOverlay);
        super.onDestroy();
    }

    @Override
    public void onInterrupt() {
    }
}
