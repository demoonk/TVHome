package ru.pda.tvhome;

import android.graphics.Bitmap;

/** Quantizes an analytic glow so its smooth falloff does not band on a dark TV screen. */
final class GradientDither {
    private GradientDither() {}

    /** The tile spotlight: a soft white radial mask, tinted and moved onto the focused tile.
     *  A plain 8-bit rounding of the falloff shows concentric rings, so each pixel's alpha is
     *  spread over the two code values around it (the same stochastic rounding the old
     *  full-screen glow used); transparent areas stay clean. The side must be the size the mask
     *  is drawn at: dither noise is only noise at screen scale, and a smaller bitmap gets
     *  bilinearly smoothed by the upscale, which averages the noise away and brings the rings back. */
    static Bitmap spotlight(int n) {
        int[] px = new int[n * n];
        int state = 0x43595456;
        for (int y = 0; y < n; y++) for (int x = 0; x < n; x++) {
            float dx = (x - (n - 1) / 2f) / ((n - 1) / 2f), dy = (y - (n - 1) / 2f) / ((n - 1) / 2f);
            float d = (float) Math.sqrt(dx * dx + dy * dy);
            float a = Math.max(0f, 1f - d); a = a * a * (3f - 2f * a);
            state ^= state << 13;
            state ^= state >>> 17;
            state ^= state << 5;
            float threshold = (state >>> 8) * (1f / 16777216f);
            int quantized = a <= 0f ? 0 : Math.max(0, Math.min(255,
                    (int) Math.floor(a * 255f + 2f * threshold - 0.5f)));
            px[y * n + x] = (quantized << 24) | 0x00FFFFFF;
        }
        return Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888);
    }
}
