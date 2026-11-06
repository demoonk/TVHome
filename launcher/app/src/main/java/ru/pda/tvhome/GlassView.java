package ru.pda.tvhome;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;

/**
 * A translucent glass slab: soft sheen fill, bright top specular, crisp rim and a faint outer glow.
 * One instance floats over the grid and is moved with spring animations between tiles.
 */
public class GlassView extends View {

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint specular = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float density;
    private float radius;
    /** Glow colour as 0xRRGGBB: white normally, a festival's accent (diya gold) while a festive skin is on. */
    private int glowRgb = 0xFFFFFF;
    /** Glow strength: the white glow stays faint, a coloured one needs more alpha to read as warm light. */
    private int glowAlpha = 0x16;
    /** The rim's base colour with its own alpha; onDraw fades it by the pulse while the frame blinks. */
    private int rimColor = 0x5CFFFFFF;
    /** Blink factor: 1 at rest, oscillating low while the selection frame pulses. */
    private float pulse = 1f;
    private ValueAnimator pulseAnim;

    public GlassView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        radius = 20f * density;
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(density);
        rim.setColor(0x5CFFFFFF);
        glow.setStyle(Paint.Style.STROKE);
        // Cache the slab as a texture. The spring only changes translation, so the GPU can
        // move it without replaying clipPath and the glow on every frame. That path is
        // software-rendered on the MT5895 and is what makes the highlight crawl.
        setLayerType(LAYER_TYPE_HARDWARE, null);
    }

    /** Tints the rim and the outer glow; 0 restores the plain white glass. */
    void setAccent(int argb) {
        if (argb == 0) {
            glowRgb = 0xFFFFFF;
            glowAlpha = 0x16;
            rimColor = 0x5CFFFFFF;
            setPulse(false);
        } else {
            glowRgb = argb & 0xFFFFFF;
            glowAlpha = 0x70;
            // The rim is the accent lifted towards white, so the edge still reads as glass, not paint.
            int r = (((glowRgb >> 16) & 0xFF) + 255 * 2) / 3, g = (((glowRgb >> 8) & 0xFF) + 255 * 2) / 3,
                    b = ((glowRgb & 0xFF) + 255 * 2) / 3;
            rimColor = 0xE6000000 | (r << 16) | (g << 8) | b;
        }
        invalidate();
    }

    /**
     * Blinks the rim and the outer glow, so the frame over the focused tile breathes while reorder mode
     * waits for a choice. The fade never takes the frame below 30% of its colour, and releasing the pulse
     * restores the steady glass.
     */
    void setPulse(boolean on) {
        if (on) {
            if (pulseAnim != null) return;
            pulseAnim = ValueAnimator.ofFloat(1f, 0.15f);
            pulseAnim.setDuration(600);
            pulseAnim.setRepeatMode(ValueAnimator.REVERSE);
            pulseAnim.setRepeatCount(ValueAnimator.INFINITE);
            pulseAnim.setInterpolator(new AccelerateDecelerateInterpolator());
            pulseAnim.addUpdateListener(a -> {
                pulse = (float) a.getAnimatedValue();
                invalidate();
            });
            pulseAnim.start();
        } else if (pulseAnim != null) {
            pulseAnim.cancel();
            pulseAnim = null;
            pulse = 1f;
            invalidate();
        }
    }

    void setRadius(float px) {
        radius = px;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        fill.setShader(new LinearGradient(0, 0, 0, h, new int[]{0x73FFFFFF, 0x23FFFFFF, 0x0FFFFFFF}, new float[]{0, .45f, 1}, Shader.TileMode.CLAMP));
        specular.setShader(new LinearGradient(0, 0, 0, h * 0.45f, 0x00FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float inset = 6f * density;   // room for the glow
        rect.set(inset, inset, getWidth() - inset, getHeight() - inset);

        // Outer glow: thin rings across the inset, each a little fainter, so it fades out smoothly. Three wide
        // strokes left visible bands once the glow took an app's colour at a stronger alpha. The hardware layer
        // caches the result, so the rings cost one draw, not one per frame of the spring.
        int rings = 12;
        float step = inset / rings;
        glow.setStrokeWidth(step + 0.5f);
        for (int i = 0; i < rings; i++) {
            float o = step * (i + 0.5f);
            float t = 1f - (float) i / rings;
            glow.setColor(Math.round(glowAlpha * t * (0.3f + 0.7f * pulse)) << 24 | glowRgb);
            canvas.drawRoundRect(rect.left - o, rect.top - o, rect.right + o, rect.bottom + o, radius + o, radius + o, glow);
        }

        canvas.drawRoundRect(rect, radius, radius, fill);

        // Sheen across the top 45%: clipRect stays on the GPU (clipPath did not on the MT5895) and
        // the round rect under it keeps the top corners rounded with a flat bottom edge.
        canvas.save();
        canvas.clipRect(rect.left, rect.top, rect.right, rect.top + rect.height() * 0.45f);
        canvas.drawRoundRect(rect, radius, radius, specular);
        canvas.restore();

        // The blink fades the rim with the glow; its own alpha is the base, the pulse only scales it down.
        int rimA = Math.round(((rimColor >>> 24) & 0xFF) * (0.3f + 0.7f * pulse));
        rim.setColor((rimA << 24) | (rimColor & 0xFFFFFF));
        canvas.drawRoundRect(rect, radius, radius, rim);
    }
}
