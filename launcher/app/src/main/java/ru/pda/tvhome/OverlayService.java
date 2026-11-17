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
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

/**
 * The floating clock, the sleep timer and the OLED shift, without an accessibility service.
 *
 * This firmware stops repeating the remote's volume keys as soon as ANY accessibility service is enabled — key
 * filtering or not — so the launcher keeps none. The clock is an ordinary app overlay (SYSTEM_ALERT_WINDOW, granted
 * over adb). "Going to sleep" is a real display-sleep request through SleepTimer; the old black focusable window
 * only dimmed the panel and is intentionally gone.
 */
public final class OverlayService extends Service {

    static final String ACTION_WAKE = "com.gathin.tvhome.action.OVERLAY_WAKE";
    private static OverlayService instance;

    static boolean wake() {
        if(instance==null||instance.sleepScrim==null)return false;
        instance.dismissSleep();return true;
    }


    private static final long TICK_MS = 1000L;
    private static final long SHIFT_MS = 5 * 60_000L;
    private static final long FOREGROUND_POLL_MS = 2000L;
    private static final long BLINK_MS = 500L;
    private static final long STATS_MS = 5000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private WindowManager windowManager;
    private View clockOverlay;
    private TextClock clock;
    private TextView cpuStat, memoryStat;
    private final SystemStats serviceStats = new SystemStats();
    private boolean statsActive;
    private int statsOffsetDp = 0;
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

    private final Runnable blink = new Runnable() {
        @Override
        public void run() {
            try {
                if (clock != null && prefs.getBoolean(Config.CLOCK_PLACING_KEY, false)) {
                    int alpha = Math.max(10, Math.min(100, prefs.getInt(Config.CLOCK_ALPHA_KEY, 40))) * 255 / 100;
                    boolean visible = ((SystemClock.elapsedRealtime() / BLINK_MS) & 1L) == 0L;
                    int color = (visible ? alpha : 0) << 24 | 0x00FFFFFF;
                    clock.setTextColor(color);
                    if (cpuStat != null && cpuStat.getVisibility() == View.VISIBLE) cpuStat.setTextColor(color);
                    if (memoryStat != null && memoryStat.getVisibility() == View.VISIBLE) memoryStat.setTextColor(color);
                }
            } finally {
                handler.postDelayed(this, BLINK_MS);
            }
        }
    };

    private final Runnable statsTick = new Runnable() {
        @Override
        public void run() {
            if (!statsActive) return;
            try {
                refreshOverlayStats();
            } finally {
                handler.postDelayed(this, STATS_MS);
            }
        }
    };

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
        handler.post(blink);
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
        boolean enabled = prefs.getBoolean(Config.CLOCK_ON_KEY, true);
        if (onHome || !enabled) {
            removeClock();
            return;
        }

        boolean placing = prefs.getBoolean(Config.CLOCK_PLACING_KEY, false);
        boolean cpuOn = !placing && prefs.getBoolean(Config.STATS_CPU_KEY, false);
        boolean memoryOn = !placing && prefs.getBoolean(Config.STATS_MEMORY_KEY, false);
        int alpha = Math.max(10, Math.min(100, prefs.getInt(Config.CLOCK_ALPHA_KEY, 40))) * 255 / 100;
        int size = prefs.getInt(Config.CLOCK_SIZE_KEY, 10);
        int placeX = prefs.getInt(Config.CLOCK_X_KEY, Integer.MIN_VALUE);
        int placeY = prefs.getInt(Config.CLOCK_Y_KEY, Integer.MIN_VALUE);
        String family = prefs.getString(Config.CLOCK_FONT_KEY, "sans-serif");

        if (clockOverlay == null) {
            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setGravity(Gravity.CENTER_VERTICAL);
            line.setPadding(0, 0, 0, 0);

            cpuStat = new TextView(this);
            cpuStat.setIncludeFontPadding(false);
            cpuStat.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_cpu, 0, 0, 0);
            line.addView(cpuStat);

            memoryStat = new TextView(this);
            memoryStat.setIncludeFontPadding(false);
            memoryStat.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_memory, 0, 0, 0);
            line.addView(memoryStat);

            clock = new TextClock(this);
            clock.setFormat24Hour("HH:mm");
            clock.setFormat12Hour("h:mm");
            clock.setIncludeFontPadding(false);
            clock.setPadding(0, 0, 0, 0);
            line.addView(clock);

            clockOverlay = line;
        }

        float density = getResources().getDisplayMetrics().density;
        int textSizePx = Math.max(8, Math.round(size * density));
        int padding = Math.max(4, Math.round(4 * density));
        cpuStat.setCompoundDrawablePadding(padding);
        memoryStat.setCompoundDrawablePadding(padding);
        cpuStat.setVisibility(cpuOn ? View.VISIBLE : View.GONE);
        memoryStat.setVisibility(memoryOn ? View.VISIBLE : View.GONE);

        int blinkAlpha = alpha;
        if (prefs.getBoolean(Config.CLOCK_PLACING_KEY, false)) {
            blinkAlpha = ((SystemClock.elapsedRealtime() / BLINK_MS) & 1L) == 0L ? alpha : 0;
        }
        int textColor = (blinkAlpha << 24) | 0x00FFFFFF;
        clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        clock.setTypeface(Typeface.create(family, Typeface.NORMAL));
        clock.setTextColor(textColor);
        clock.setLineSpacing(0, 1.0f);

        if (cpuOn) {
            cpuStat.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
            cpuStat.setTypeface(Typeface.create(family, Typeface.NORMAL));
            cpuStat.setTextColor(textColor);
            cpuStat.setLineSpacing(0, 1.0f);
        }
        if (memoryOn) {
            memoryStat.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
            memoryStat.setTypeface(Typeface.create(family, Typeface.NORMAL));
            memoryStat.setTextColor(textColor);
            memoryStat.setLineSpacing(0, 1.0f);
        }

        if (cpuOn || memoryOn) {
            refreshOverlayStats();
            int lineHeight = Math.max(clock.getLineHeight(), Math.max(cpuStat.getLineHeight(), memoryStat.getLineHeight()));
            int iconHeight = Math.max(8, Math.round(lineHeight * 0.82f));
            int statsOffsetPx = 0;

            if (cpuOn) {
                android.graphics.drawable.Drawable cpuIcon = getDrawable(R.drawable.ic_cpu);
                if (cpuIcon != null) {
                    cpuIcon.setBounds(0, 0, iconHeight, iconHeight);
                    cpuStat.setCompoundDrawables(cpuIcon, null, null, null);
                }
                setRightMargin(cpuStat, padding);
                int cpuWidth = cpuStat.getMeasuredWidth();
                if (cpuWidth <= 0) {
                    cpuWidth = iconHeight + padding + approxTextWidth(cpuStat.getText().toString(), textSizePx);
                }
                statsOffsetPx += cpuWidth + padding;
            }

            if (memoryOn) {
                android.graphics.drawable.Drawable memoryIcon = getDrawable(R.drawable.ic_memory);
                if (memoryIcon != null) {
                    memoryIcon.setBounds(0, 0, iconHeight, iconHeight);
                    memoryStat.setCompoundDrawables(memoryIcon, null, null, null);
                }
                setRightMargin(memoryStat, padding);
                int memoryWidth = memoryStat.getMeasuredWidth();
                if (memoryWidth <= 0) {
                    memoryWidth = iconHeight + padding + approxTextWidth(memoryStat.getText().toString(), textSizePx);
                }
                statsOffsetPx += memoryWidth + padding;
            }

            statsOffsetDp = Math.round(statsOffsetPx / density);
            if (!statsActive) {
                statsActive = true;
                handler.post(statsTick);
            }
        } else {
            statsOffsetDp = 0;
            if (statsActive) {
                statsActive = false;
                handler.removeCallbacks(statsTick);
            }
        }

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
            params.x = Math.round((screenW - 70 - statsOffsetDp) * density);
            params.y = Math.round(6 * density);
        } else {
            params.x = Math.round((placeX - statsOffsetDp) * density);
            params.y = Math.round(placeY * density);
        }
        params.x += shiftDx;
        params.y += shiftDy;
        try {
            if (clockOverlay.getParent() == null) windowManager.addView(clockOverlay, params);
            else windowManager.updateViewLayout(clockOverlay, params);
        } catch (RuntimeException refused) {
            android.util.Log.w("TvHomeClock", "overlay refused: " + refused, refused);
            clockOverlay = null;
            clock = null;
            cpuStat = null;
            memoryStat = null;
        }
    }

    private void setRightMargin(View view, int margin) {
        LinearLayout.LayoutParams lp = view.getLayoutParams() instanceof LinearLayout.LayoutParams
                ? (LinearLayout.LayoutParams) view.getLayoutParams()
                : new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = margin;
        view.setLayoutParams(lp);
    }

    private int approxTextWidth(String text, int textSizePx) {
        if (text == null || text.isEmpty()) return 0;
        float sum = 0f;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == ' ') {
                sum += 0.30f;
            } else if (c >= '0' && c <= '9') {
                sum += 0.55f;
            } else if (c == '%') {
                sum += 0.58f;
            } else if (c == '—') {
                sum += 0.65f;
            } else {
                sum += 0.65f;
            }
        }
        return Math.max(1, Math.round(sum * textSizePx));
    }

    private void refreshOverlayStats() {
        if (cpuStat != null && cpuStat.getVisibility() == View.VISIBLE) {
            int cpu = serviceStats.cpuBusyPercent();
            if (cpu < 0) {
                try {
                    cpu = Settings.Secure.getInt(getContentResolver(), "ru_pda_tvhome_cpu", -1);
                } catch (RuntimeException ignored) {
                    cpu = -1;
                }
            }
            cpuStat.setText(cpu < 0 ? "—" : cpu + "%");
        }
        if (memoryStat != null && memoryStat.getVisibility() == View.VISIBLE) {
            long mb = SystemStats.freeMemoryMb(this);
            memoryStat.setText(mb < 0 ? "—" : mb + " МБ");
        }
    }

    private void removeClock() {
        if (clockOverlay == null && clock == null) return;
        if (statsActive) {
            statsActive = false;
            handler.removeCallbacks(statsTick);
        }
        try {
            if (clockOverlay != null && clockOverlay.getParent() != null) windowManager.removeView(clockOverlay);
        } catch (RuntimeException ignored) {
            // Already gone.
        }
        clockOverlay = null;
        clock = null;
        cpuStat = null;
        memoryStat = null;
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
        boolean on = prefs.getBoolean(Config.OLED_SHIFT_KEY, true);
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
        // The timer must actually sleep the display: the black window alone leaves the backlight on.
        if (SleepTimer.fireNow(this)) SleepTimer.touch(this);
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
            if (!settings.getBoolean(Config.CLOCK_ON_KEY, true) && !SleepTimer.armed(context)) {
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
