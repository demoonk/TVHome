package com.gathin.tvhome;

/** Icon-centre positions in horizontal rows; the query-title launch origin is unchanged. No Android dependency. */
final class SearchFanLayout {
    static final long DURATION_MS = 750L;
    static float[][] positions(int count, float width, float minY, float maxY, float density) {
        float[][] points = new float[Math.max(0, count)][2];
        if (count <= 0) return points;
        int rows = (count + 6) / 7;
        float band = Math.max(1f, maxY - minY) / rows;
        for (int row = 0, offset = 0; row < rows; row++) {
            int size = Math.min(7, count - offset);
            double scale = width / 736.0;
            double halfSpan = size == 1 ? 0 : Math.max(0, Math.min(
                    (size - 1) * 72 * scale, width / 2 - 60 * density));
            double middle = minY + row * band + band / 2;
            for (int i = 0; i < size; i++) {
                double x = size == 1 ? 0 : -halfSpan + 2 * halfSpan * i / (size - 1);
                points[offset + i][0] = (float) (width / 2 + x);
                points[offset + i][1] = (float) middle;
            }
            offset += size;
        }
        return points;
    }
    private SearchFanLayout() {}
}
