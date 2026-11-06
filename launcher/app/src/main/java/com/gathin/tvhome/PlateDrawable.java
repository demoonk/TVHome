package com.gathin.tvhome;

import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import java.util.ArrayList;

/** Blur each wallpaper layer once; composite cached samples with the wallpaper's live fade alpha. */
final class PlateDrawable extends Drawable {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF box = new RectF();
    private final float radius, density;
    private final int opacity, blur;
    private View owner;
    private final Path clip = new Path();
    private final ArrayList<Sample> samples = new ArrayList<>(2);
    private ViewGroup source;
    private final android.view.ViewTreeObserver.OnPreDrawListener trackWallpaper = () -> {
        if (owner != null && source != null && changed()) invalidateSelf();
        return true;
    };

    private static final class Sample {
        final View layer;
        final Bitmap bitmap;
        final int x, y, width, height;
        float alpha = -1f;
        Sample(View layer, Bitmap bitmap, int x, int y, int width, int height) {
            this.layer = layer; this.bitmap = bitmap;
            this.x = x; this.y = y; this.width = width; this.height = height;
        }
    }
    private final int[] location = new int[2], sourceLocation = new int[2];

    PlateDrawable(float density, float radius, int opacity, int blur) {
        this.density = density;
        this.radius = radius;
        this.opacity = Math.max(0, Math.min(100, opacity));
        this.blur = Math.max(0, Math.min(10, blur));
    }

    void bind(View owner) {
        this.owner = owner;
        owner.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            public void onViewAttachedToWindow(View view) { observe(); }
            public void onViewDetachedFromWindow(View view) {
                if (source != null && source.getViewTreeObserver().isAlive())
                    source.getViewTreeObserver().removeOnPreDrawListener(trackWallpaper);
                source = null;
                samples.clear();
            }
        });
        if (owner.isAttachedToWindow()) observe();
    }

    private void observe() {
        if (blur == 0 || owner == null) return;
        View view = owner.getRootView().findViewWithTag("header_wallpaper_source");
        if (!(view instanceof ViewGroup) || source == view) return;
        source = (ViewGroup) view;
        source.getViewTreeObserver().addOnPreDrawListener(trackWallpaper);
    }

    private boolean changed() {
        if (samples.size() != source.getChildCount()) return true;
        owner.getLocationInWindow(location);
        for (int i = 0; i < samples.size(); i++) {
            Sample sample = samples.get(i);
            View layer = source.getChildAt(i);
            layer.getLocationInWindow(sourceLocation);
            if (sample.layer != layer || sample.alpha != layer.getAlpha()
                    || sample.x != location[0] - sourceLocation[0]
                    || sample.y != location[1] - sourceLocation[1]
                    || sample.width != getBounds().width() || sample.height != getBounds().height()) return true;
        }
        return false;
    }

    private void drawWallpaper(Canvas canvas) {
        observe();
        if (source == null || source.getWidth() == 0) return;
        owner.getLocationInWindow(location);
        for (int i = samples.size() - 1; i >= 0; i--)
            if (samples.get(i).layer.getParent() != source) samples.remove(i);
        for (int i = 0; i < source.getChildCount(); i++) {
            View layer = source.getChildAt(i);
            layer.getLocationInWindow(sourceLocation);
            int x = location[0] - sourceLocation[0], y = location[1] - sourceLocation[1];
            Sample cached = i < samples.size() ? samples.get(i) : null;
            if (cached == null || cached.layer != layer || cached.x != x || cached.y != y
                    || cached.width != getBounds().width() || cached.height != getBounds().height()) {
                int w = Math.max(1, getBounds().width() / 4);
                int h = Math.max(1, getBounds().height() / 4);
                Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                Canvas capture = new Canvas(bitmap);
                capture.scale(w / box.width(), h / box.height());
                capture.translate(-x - box.left, -y - box.top);
                // Drawing this layer directly omits its crossfade alpha. Apply that only when compositing.
                layer.draw(capture);
                soften(bitmap, Math.max(1, Math.round(blur * density / 4)));
                cached = new Sample(layer, bitmap, x, y, getBounds().width(), getBounds().height());
                if (i < samples.size()) samples.set(i, cached); else samples.add(cached);
            }
            cached.alpha = layer.getAlpha();
            paint.setColor(Color.WHITE);
            paint.setAlpha(Math.round(255f * cached.alpha));
            canvas.drawBitmap(cached.bitmap, null, box, paint);
        }
    }

    @Override public void draw(Canvas canvas) {
        if (getBounds().isEmpty()) return;
        box.set(getBounds());
        int save = canvas.save();
        clip.rewind();
        clip.addRoundRect(box, radius, radius, Path.Direction.CW);
        canvas.clipPath(clip);
        if (blur > 0 && owner != null) drawWallpaper(canvas);
        paint.setColor(Color.BLACK);
        paint.setAlpha(Math.round(255f * opacity / 100));
        canvas.drawRoundRect(box, radius, radius, paint);
        canvas.restoreToCount(save);
    }

    /** Three separable box passes approximate a Gaussian without blurring text or icons. */
    private static void soften(Bitmap bitmap, int radius) {
        int w = bitmap.getWidth(), h = bitmap.getHeight();
        int[] pixels = new int[w * h], output = new int[w * h];
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h);
        for (int pass = 0; pass < 3; pass++) {
            for (int axis = 0; axis < 2; axis++) {
                int lines = axis == 0 ? h : w, length = axis == 0 ? w : h;
                for (int line = 0; line < lines; line++) {
                    int a=0, r=0, g=0, b=0, count=radius*2+1;
                    for (int k=-radius;k<=radius;k++) {
                        int pos=Math.max(0,Math.min(length-1,k));
                        int c=pixels[axis==0?line*w+pos:pos*w+line];
                        a+=c>>>24;r+=(c>>>16)&255;g+=(c>>>8)&255;b+=c&255;
                    }
                    for (int pos=0;pos<length;pos++) {
                        output[axis==0?line*w+pos:pos*w+line]=(a/count<<24)|(r/count<<16)|(g/count<<8)|b/count;
                        int left=Math.max(0,pos-radius), right=Math.min(length-1,pos+radius+1);
                        int c=pixels[axis==0?line*w+left:left*w+line];
                        int d=pixels[axis==0?line*w+right:right*w+line];
                        a+=(d>>>24)-(c>>>24);r+=((d>>>16)&255)-((c>>>16)&255);
                        g+=((d>>>8)&255)-((c>>>8)&255);b+=(d&255)-(c&255);
                    }
                }
                int[] swap=pixels;pixels=output;output=swap;
            }
        }
        bitmap.setPixels(pixels,0,w,0,0,w,h);
    }

    @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
    @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
