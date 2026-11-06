package ru.pda.tvhome;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.Locale;

/** Modal accessibility window; no accessibility key filtering is requested. */
final class SleepWarningOverlay {
    static final long WARNING_MS = 30_000L;
    private final Context context;
    private final int windowType;
    private final WindowManager manager;
    private FrameLayout root;
    private TextView countdown;
    private int lastSeconds = -1;
    private android.animation.AnimatorSet intro;
    private static volatile boolean visible;

    SleepWarningOverlay(Context context) {
        this(context, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY);
    }

    SleepWarningOverlay(Context context, int windowType) {
        this.windowType = windowType;
        this.context = new ContextThemeWrapper(context, R.style.Theme_TvHome);
        manager = context.getSystemService(WindowManager.class);
    }

    static boolean isVisible() { return visible; }
    private int dp(int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
    private GradientDrawable background(int color, boolean focus) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(16));
        shape.setStroke(dp(1), focus ? 0x66FFFFFF : 0x20FFFFFF);
        return shape;
    }

    void show(long remaining) {
        if (root == null) {
            root = new FrameLayout(context);
            View dim = new View(context);
            dim.setBackgroundColor(0x40000000);
            dim.setAlpha(0f);
            root.addView(dim, new FrameLayout.LayoutParams(-1, -1));
            LinearLayout panel = new LinearLayout(context);
            panel.setOrientation(LinearLayout.VERTICAL);
            panel.setGravity(Gravity.CENTER_HORIZONTAL);
            panel.setPadding(dp(28), dp(24), dp(28), dp(26));
            panel.setBackground(background(0x36202936, false));
            panel.setAlpha(0f);
            FrameLayout.LayoutParams panelSize = new FrameLayout.LayoutParams(dp(600), -2, Gravity.CENTER);
            root.addView(panel, panelSize);
            TextView title = new TextView(context);
            title.setText("ТВ будет выключен через:");
            title.setTextColor(0xFFE4E9EF);
            title.setTextSize(24);
            title.setGravity(Gravity.CENTER);
            panel.addView(title, new LinearLayout.LayoutParams(-1, -2));
            countdown = new TextView(context);
            countdown.setTextColor(Color.WHITE);
            countdown.setTextSize(64);
            countdown.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            countdown.setFontFeatureSettings("tnum");
            countdown.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams clockSize = new LinearLayout.LayoutParams(-1, -2);
            clockSize.setMargins(0, dp(10), 0, dp(22));
            panel.addView(countdown, clockSize);
            LinearLayout buttons = new LinearLayout(context);
            buttons.setOrientation(LinearLayout.HORIZONTAL);
            TextView keepWatching = button("Продолжить просмотр", () -> { SleepTimer.touch(context); hide(); });
            TextView sleepNow = button("Выключить сейчас", () -> {
                hide();
                if (!SleepTimer.fireNow(context)) android.widget.Toast.makeText(context,
                        R.string.sleep_failed, android.widget.Toast.LENGTH_LONG).show();
                SleepTimer.clearFired(context);
            });
            LinearLayout.LayoutParams first = new LinearLayout.LayoutParams(0, dp(54), 1f);
            first.setMarginEnd(dp(12));
            buttons.addView(keepWatching, first);
            buttons.addView(sleepNow, new LinearLayout.LayoutParams(0, dp(54), 1f));
            panel.addView(buttons, new LinearLayout.LayoutParams(-1, -2));
            WindowManager.LayoutParams window = new WindowManager.LayoutParams(-1, -1,
                    windowType,
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    android.graphics.PixelFormat.TRANSLUCENT);
            window.setTitle("CleanYourTV Sleep Timer");
            try {
                // Set before addView: the window's own accessibility events must not reset the timer.
                visible = true;
                manager.addView(root, window);
                keepWatching.requestFocus();
                intro = new android.animation.AnimatorSet();
                intro.playTogether(android.animation.ObjectAnimator.ofFloat(dim, View.ALPHA, 0f, 1f),
                        android.animation.ObjectAnimator.ofFloat(panel, View.ALPHA, 0f, 1f));
                intro.setDuration(android.animation.ValueAnimator.areAnimatorsEnabled() ? 500L : 0L);
                intro.setInterpolator(new android.view.animation.PathInterpolator(0f, 0f, .2f, 1f));
                intro.start();
            } catch (RuntimeException unavailable) { root = null; countdown = null; visible = false; return; }
        }
        int seconds = (int) Math.max(0, (remaining + 999L) / 1000L);
        if (seconds != lastSeconds && countdown != null) {
            lastSeconds = seconds;
            countdown.setText(String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60));
        }
    }

    private TextView button(String label, Runnable action) {
        TextView button = new TextView(context);
        button.setText(label);
        button.setTextSize(18);
        button.setGravity(Gravity.CENTER);
        button.setFocusable(true);
        button.setClickable(true);
        button.setTextColor(Color.WHITE);
        button.setBackground(background(0xFF303C4D, false));
        button.setOnClickListener(view -> action.run());
        button.setOnFocusChangeListener((view, focused) -> {
            button.setTextColor(focused ? 0xFF15202E : Color.WHITE);
            button.setBackground(background(focused ? Color.WHITE : 0xFF303C4D, focused));
        });
        button.setOnKeyListener((view, key, event) -> {
            if (key != KeyEvent.KEYCODE_BACK) return false;
            if (event.getAction() == KeyEvent.ACTION_UP) { SleepTimer.touch(context); hide(); }
            return true;
        });
        return button;
    }

    void hide() {
        if (intro != null) { intro.cancel(); intro = null; }
        FrameLayout old = root;
        root = null;
        countdown = null;
        lastSeconds = -1;
        visible = false;
        if (old != null && old.getParent() != null) manager.removeView(old);
    }
}
