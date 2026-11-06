package ru.pda.tvhome;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextClock;

/**
 * The floating clock, the sleep timer and the OLED shift, without an accessibility service.
 *
 * This firmware stops repeating the remote's volume keys as soon as ANY accessibility service is enabled — key
 * filtering or not — so the launcher keeps none. The clock is an ordinary app overlay (SYSTEM_ALERT_WINDOW, granted
 * over adb), and "going to sleep" is a black focusable window instead of a power-off: the panel stops showing
 * anything, and the first key press closes it.
 */
public final class OverlayService extends Service {

    static final String ACTION_WAKE = "com.gathin.tvhome.action.OVERLAY_WAKE";
    private static OverlayService instance;

    static boolean wake() {
        if(instance==null||instance.sleepScrim==null)return false;
        instance.dismissSleep();return true;
    }

    static boolean sleepNow() {
        return instance != null && instance.showSleep();
    }

    private static final long TICK_MS = 1000L;
    private static final long SHIFT_MS = 5 * 60_000L;
    private static final long FOREGROUND_POLL_MS = 2000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private WindowManager windowManager;
    private TextClock clock;
    private View sleepScrim;
    private SleepWarningOverlay warning;
    private String foregroundClass = "";
    private SharedPreferences prefs;

    private long lastShiftAt;
    private long lastForegroundPoll;
    private long lastUsageQuery;
    private int shiftDx, shiftDy;
    private java.util.Random shiftRandom;
    private String foreground = "";
    private BroadcastReceiver clockChanged;
    private BroadcastReceiver screenChanged;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            try {
                long now = SystemClock.elapsedRealtime();
                if (now - lastForegroundPoll >= FOREGROUND_POLL_MS) {
                    lastForegroundPoll = now;
                    foreground = foregroundPackage();
                    updateClock();
                }
                if (now - lastShiftAt >= SHIFT_MS) {
                    lastShiftAt = now;
                    oledShift();
                }
                sleepTick();
            } finally {
                handler.postDelayed(this, TICK_MS);
            }
        }
    };

    @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag")
    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        android.app.NotificationManager notifications = getSystemService(android.app.NotificationManager.class);
        notifications.createNotificationChannel(new android.app.NotificationChannel("overlay",
                "Часы и таймер сна", android.app.NotificationManager.IMPORTANCE_LOW));
        startForeground(21, new android.app.Notification.Builder(this, "overlay")
                .setSmallIcon(R.drawable.ic_home_settings_pill).setContentTitle("Часы и таймер сна")
                .setOngoing(true).build());
        SleepTimer.reconcile(this);
        prefs = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        shiftRandom = new java.util.Random();
        lastShiftAt = SystemClock.elapsedRealtime();
        clockChanged = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                foreground = foregroundPackage();
                updateClock();
            }
        };
        try {
            registerReceiver(clockChanged, new IntentFilter(Config.CLOCK_CHANGED_ACTION), Context.RECEIVER_NOT_EXPORTED);
        } catch (IllegalArgumentException | SecurityException older) {
            registerReceiver(clockChanged, new IntentFilter(Config.CLOCK_CHANGED_ACTION));
        }
        screenChanged = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                boolean waking = Intent.ACTION_SCREEN_ON.equals(intent.getAction())||Intent.ACTION_USER_PRESENT.equals(intent.getAction());
                dismissSleep(false);
                if (warning != null) warning.hide();
                SleepTimer.touch(OverlayService.this);
                if (waking) notifyLauncherWake();
            }
        };
        IntentFilter screenFilter = new IntentFilter(Intent.ACTION_SCREEN_ON);
        screenFilter.addAction(Intent.ACTION_SCREEN_OFF);
        screenFilter.addAction(Intent.ACTION_USER_PRESENT);
        if (android.os.Build.VERSION.SDK_INT >= 33)
            registerReceiver(screenChanged, screenFilter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(screenChanged, screenFilter);
        handler.post(tick);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        instance = null;
        handler.removeCallbacksAndMessages(null);
        if (clockChanged != null) {
            try {
                unregisterReceiver(clockChanged);
            } catch (RuntimeException ignored) {
            }
        }
        if (screenChanged != null) {
            try { unregisterReceiver(screenChanged); } catch (RuntimeException ignored) {}
        }
        removeClock();
        dismissSleep(false);
        if (warning != null) warning.hide();
        super.onDestroy();
    }

    // ---- the floating clock ----------------------------------------------------------------------

    /** Shows the clock over other apps (and over this launcher's own settings), hides it on Home. */
    private void updateClock() {
        boolean onHome = getPackageName().equals(foreground)
                && !foregroundClass.endsWith("LauncherSettingsActivity");
        boolean enabled = prefs.getBoolean(Config.CLOCK_ON_KEY, false);
        if (onHome || !enabled) {
            removeClock();
            return;
        }
        int alpha = Math.max(10, Math.min(100, prefs.getInt(Config.CLOCK_ALPHA_KEY, 40))) * 255 / 100;
        int size = prefs.getInt(Config.CLOCK_SIZE_KEY, 10);
        int placeX = prefs.getInt(Config.CLOCK_X_KEY, Integer.MIN_VALUE);
        int placeY = prefs.getInt(Config.CLOCK_Y_KEY, Integer.MIN_VALUE);
        String family = prefs.getString(Config.CLOCK_FONT_KEY, "sans-serif");

        if (clock == null) {
            clock = new TextClock(this);
            clock.setFormat24Hour("HH:mm");
            clock.setFormat12Hour("h:mm");
            clock.setIncludeFontPadding(false);
            clock.setPadding(0, 0, 0, 0);
        }
        clock.setTextColor((alpha << 24) | 0x00FFFFFF);
        clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        clock.setTypeface(Typeface.create(family, Typeface.NORMAL));

        float density = getResources().getDisplayMetrics().density;
        int screenW = Math.round(getResources().getDisplayMetrics().widthPixels / density);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        if (placeX == Integer.MIN_VALUE || placeY == Integer.MIN_VALUE) {
            params.x = Math.round((screenW - 70) * density);
            params.y = Math.round(6 * density);
        } else {
            params.x = Math.round(placeX * density);
            params.y = Math.round(placeY * density);
        }
        params.x += shiftDx;
        params.y += shiftDy;
        try {
            if (clock.getParent() == null) windowManager.addView(clock, params);
            else windowManager.updateViewLayout(clock, params);

        } catch (RuntimeException refused) {
            android.util.Log.w("TvHomeClock", "overlay refused: " + refused, refused);
            clock = null;   // try again on the next poll rather than keep a broken view
        }
    }

    private void removeClock() {
        if (clock == null) return;
        try {
            if (clock.getParent() != null) windowManager.removeView(clock);
        } catch (RuntimeException ignored) {
            // Already gone.
        }
        clock = null;
    }

    /** The package in front, from the usage log (PACKAGE_USAGE_STATS is granted over adb). */
    private String foregroundPackage() {
        try {
            android.app.usage.UsageStatsManager usm =
                    (android.app.usage.UsageStatsManager) getSystemService(USAGE_STATS_SERVICE);
            if (usm == null) return "";
            long now = System.currentTimeMillis();
            android.app.usage.UsageEvents events = usm.queryEvents(lastUsageQuery == 0L ? now - 86_400_000L : lastUsageQuery - 1000L, now);
            lastUsageQuery = now;
            android.app.usage.UsageEvents.Event event = new android.app.usage.UsageEvents.Event();
            String last = "";
            while (events.hasNextEvent()) {
                events.getNextEvent(event);
                if (event.getEventType() == android.app.usage.UsageEvents.Event.MOVE_TO_FOREGROUND
                        || event.getEventType() == android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED) {
                    last = event.getPackageName();
                    foregroundClass = event.getClassName() == null ? "" : event.getClassName();
                }
            }
            return last == null || last.isEmpty() ? foreground : last;
        } catch (RuntimeException ignored) {
            return foreground;
        }
    }

    // ---- OLED shift ------------------------------------------------------------------------------

    private void oledShift() {
        boolean on = prefs.getBoolean(Config.OLED_SHIFT_KEY, false);
        shiftDx = on ? shiftRandom.nextInt(7) - 3 : 0;
        shiftDy = on ? shiftRandom.nextInt(7) - 3 : 0;
    }

    // ---- the sleep timer -------------------------------------------------------------------------

    private void sleepTick() {
        if (warning == null) warning = new SleepWarningOverlay(this, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
        if (sleepScrim != null || !SleepTimer.armed(this)) { warning.hide(); return; }
        android.os.PowerManager power = getSystemService(android.os.PowerManager.class);
        if (power != null && !power.isInteractive()) { warning.hide(); return; }
        android.media.AudioManager audio = getSystemService(android.media.AudioManager.class);
        if (audio != null && audio.isMusicActive()) {
            SleepTimer.hold(this);
            warning.hide();
            return;
        }
        long remaining = SleepTimer.remaining(this);
        if (remaining > 0L) {
            if (remaining <= SleepWarningOverlay.WARNING_MS) warning.show(remaining);
            else warning.hide();
            return;
        }
        warning.hide();
        if (showSleep()) SleepTimer.touch(this);
    }

    /**
     * A black focusable window: the screen shows nothing, and the first key press closes it. Power-off needs
     * either an accessibility service (which costs the volume keys their repeat) or a system permission the TV
     * does not give apps, so this is the quiet equivalent.
     */
    private boolean showSleep() {
        android.os.PowerManager power=getSystemService(android.os.PowerManager.class);
        if(power!=null&&!power.isInteractive())return false;
        if (sleepScrim != null) return true;
        sleepScrim = new View(this);
        sleepScrim.setBackgroundColor(0xFF000000);
        sleepScrim.setFocusableInTouchMode(true);
        sleepScrim.setOnTouchListener((v,event)->{dismissSleep();return true;});
        sleepScrim.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN || event.getAction() == KeyEvent.ACTION_UP) {
                dismissSleep();
                return true;
            }
            return true;
        });
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.OPAQUE);
        params.gravity = Gravity.TOP | Gravity.START;
        params.screenBrightness = 0f;
        try {
            windowManager.addView(sleepScrim, params);
            sleepScrim.requestFocus();
            return true;
        } catch (RuntimeException refused) {
            android.util.Log.w("TvHomeClock", "sleep scrim refused: " + refused, refused);
            sleepScrim = null;
            return false;
        }
    }

    private void notifyLauncherWake() {
        sendBroadcast(new Intent(ACTION_WAKE).setPackage(getPackageName()));
    }

    private void dismissSleep() { dismissSleep(true); }

    private void dismissSleep(boolean wakeLauncher) {
        if (sleepScrim == null) return;
        try {
            windowManager.removeViewImmediate(sleepScrim);
        } catch (RuntimeException ignored) {
            // Already gone.
        }
        sleepScrim = null;
        SleepTimer.touch(this);
        if (wakeLauncher) notifyLauncherWake();
    }

    /** Starts the service from Home or from boot; both callers are allowed to start a background service. */
    static void start(Context context) {
        try {
            SharedPreferences settings = context.getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
            if (!settings.getBoolean(Config.CLOCK_ON_KEY, false) && !SleepTimer.armed(context)) {
                if (instance != null) context.stopService(new Intent(context, OverlayService.class));
                return;
            }
            context.startForegroundService(new Intent(context, OverlayService.class));
        } catch (RuntimeException refused) {
            android.util.Log.w("TvHomeClock", "service start refused: " + refused, refused);
        }
    }

    /** True while this service is ticking: the accessibility helper steps aside, so no clock is drawn twice. */
    static boolean isAlive(Context context) {
        return instance != null;
    }
}
