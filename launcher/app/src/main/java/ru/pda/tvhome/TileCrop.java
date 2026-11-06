package ru.pda.tvhome;

/** Extra center crop removes this percentage from each edge of a fitted banner. */
final class TileCrop {
    private TileCrop() {}
    static int chosen(int value) { return value >= 0 && value <= 5 ? value : 0; }
    static float scale(int width, int height, int imageWidth, int imageHeight, int percent) {
        if (width <= 0 || height <= 0 || imageWidth <= 0 || imageHeight <= 0) return 1f;
        return Math.max((float) width / imageWidth, (float) height / imageHeight)
                / (1f - chosen(percent) / 50f);
    }
}
