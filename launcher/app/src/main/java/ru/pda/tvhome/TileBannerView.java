package ru.pda.tvhome;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.widget.ImageView;

/** Applies crop in the image matrix, independently of the existing focus animation. */
public final class TileBannerView extends ImageView {
    private final Matrix cropMatrix = new Matrix();
    private int cropPercent;

    public TileBannerView(Context context, AttributeSet attributes) {
        super(context, attributes);
        setScaleType(ScaleType.MATRIX);
    }

    void setCropPercent(int value) {
        cropPercent = TileCrop.chosen(value);
        updateCrop();
    }

    @Override public void setImageDrawable(Drawable image) {
        super.setImageDrawable(image);
        updateCrop();
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        updateCrop();
    }

    private void updateCrop() {
        Drawable image = getDrawable();
        if (cropMatrix == null || image == null) return;
        int w = getWidth() - getPaddingLeft() - getPaddingRight();
        int h = getHeight() - getPaddingTop() - getPaddingBottom();
        int iw = image.getIntrinsicWidth(), ih = image.getIntrinsicHeight();
        if (w <= 0 || h <= 0 || iw <= 0 || ih <= 0) return;
        float scale = TileCrop.scale(w, h, iw, ih, cropPercent);
        cropMatrix.setScale(scale, scale);
        cropMatrix.postTranslate((w - iw * scale) / 2f, (h - ih * scale) / 2f);
        setImageMatrix(cropMatrix);
    }
}
