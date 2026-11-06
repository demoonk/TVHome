package com.gathin.tvhome;

import android.content.Context;
import android.graphics.*;
import android.view.View;
import android.widget.FrameLayout;
import androidx.dynamicanimation.animation.FloatValueHolder;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

/** One moving contour for the entire top bar, matching the tile spring. */
final class HeaderFocusOverlay extends View {
    private final float[] bounds = new float[5];
    private final SpringAnimation[] springs = new SpringAnimation[5];
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean placed;
    HeaderFocusOverlay(Context context) {
        super(context);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setFocusable(false);
        for (int i = 0; i < springs.length; i++) {
            final int axis = i;
            // The corner radius settles much faster than the position, so the contour is already the
            // tile's shape when it lands on a tile; the position spring keeps its rubber-band overshoot.
            springs[i] = new SpringAnimation(new FloatValueHolder()).setSpring(
                    new SpringForce().setStiffness(axis == 4 ? 900f : 260f)
                            .setDampingRatio(axis == 4 ? 1f : .58f));
            springs[i].addUpdateListener((animation, value, velocity) -> { bounds[axis] = value; invalidate(); });
        }
    }
    void pointAt(View target, FrameLayout root, boolean animate) { pointAt(target, root, animate, false); }
    void pointAt(View target, FrameLayout root, boolean animate, boolean tileShape) {
        int[] origin = new int[2], at = new int[2];
        root.getLocationOnScreen(origin); target.getLocationOnScreen(at);
        float density = getResources().getDisplayMetrics().density;
        float pad = 3 * density;
        float radius = tileShape
                ? AppAdapter.cornerRadius(target.getWidth(), density) + pad
                : target.getHeight() / 2f + pad;
        float[] goal = {at[0]-origin[0]-pad, at[1]-origin[1]-pad,
                target.getWidth()+pad*2, target.getHeight()+pad*2, radius};
        for (int i=0; i<springs.length; i++) {
            if (!placed || !animate) { springs[i].cancel(); bounds[i]=goal[i]; springs[i].setStartValue(goal[i]); }
            else springs[i].animateToFinalPosition(goal[i]);
        }
        placed=true; setVisibility(VISIBLE); invalidate();
    }
    @Override protected void onDraw(Canvas canvas) {
        if (!placed) return;
        RectF r = new RectF(bounds[0], bounds[1], bounds[0]+Math.max(1,bounds[2]), bounds[1]+Math.max(1,bounds[3]));
        // Variant 2: one thin contour only — no plate, no glow, no filled circle.
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, getResources().getDisplayMetrics().density));
        paint.setColor(0x66FFFFFF);
        canvas.drawRoundRect(r,bounds[4],bounds[4],paint);
    }
    @Override protected void onDetachedFromWindow() { for (SpringAnimation spring:springs) spring.cancel(); super.onDetachedFromWindow(); }
}
