package ru.pda.tvhome;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.View;

/**
 * The top bar's pill, drawn by hand: the mark sits on the plain wallpaper, or on its plate while the
 * launcher settings keep header_plate on — a plate at zero opacity draws nothing. No halo and no circle
 * is painted. The drawable stays as the button's background so updateSleepButton's instanceof check still
 * means "this launcher owns the background", and the focus/activated plumbing stays for tintHeader.
 */
final class GlowPillDrawable extends Drawable implements Drawable.Callback {

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private final PlateDrawable plate;
    private final float radius;
    private final float density;
    private boolean focused;
    private boolean activated;

    GlowPillDrawable(float density, int plateOpacity, int plateBlur) {
        this.density = density;
        this.radius = 21 * density;
        this.plate = new PlateDrawable(density, radius, plateOpacity, plateBlur);
        this.plate.setCallback(this);
        line.setStyle(Paint.Style.STROKE);
        halo.setStyle(Paint.Style.STROKE);
    }

    @Override public void invalidateDrawable(Drawable drawable) { invalidateSelf(); }
    @Override public void scheduleDrawable(Drawable drawable, Runnable task, long when) {
        scheduleSelf(task, when);
    }
    @Override public void unscheduleDrawable(Drawable drawable, Runnable task) {
        unscheduleSelf(task);
    }

    @Override
    public boolean isStateful() {
        return true;
    }

    @Override
    protected boolean onStateChange(int[] state) {
        boolean nowFocused = false, nowActivated = false;
        for (int s : state) {
            if (s == android.R.attr.state_focused) nowFocused = true;
            if (s == android.R.attr.state_activated) nowActivated = true;
        }
        if (nowFocused == focused && nowActivated == activated) return false;
        focused = nowFocused;
        activated = nowActivated;
        invalidateSelf();
        return true;
    }

    @Override
    public void draw(Canvas canvas) {
        // Only the plate, and only while header_plate is on: the mark itself is the button's text and icon.
        plate.setBounds(getBounds());
        plate.draw(canvas);
    }

    @Override
    public void setAlpha(int alpha) {
        fill.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter filter) {
        fill.setColorFilter(filter);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    /** Puts this pill behind a top-bar button. The plate numbers are the launcher settings' header values. */
    static void apply(View button, float density, int plateOpacity, int plateBlur) {
        GlowPillDrawable background = new GlowPillDrawable(density, plateOpacity, plateBlur);
        background.plate.bind(button);
        button.setBackground(background);
        button.setClipToOutline(false);
        if (android.os.Build.VERSION.SDK_INT >= 21) {
            button.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override
                public void getOutline(View view, android.graphics.Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), view.getHeight() / 2f);
                }
            });
        }
    }
}
