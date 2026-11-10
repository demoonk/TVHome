package ru.pda.tvhome;

/** Amplitude history for the indicator: one stored sample is drawn as one pixel column over time. Plain JVM so the maths stays testable. */
final class VoiceLevelMeter {
    private final float[] history;
    private int head;
    private int count;

    VoiceLevelMeter(int samples) { history = new float[Math.max(1, samples)]; }

    /** The engine reports -2 when it has no reading and up to 10 at full scale. */
    static float normalize(float rms) {
        if (rms < 0) return 0f;
        return Math.max(0f, Math.min(1f, rms / 10f));
    }

    /** Level from captured 16-bit mono PCM, which the app can measure even when the engine sends nothing. */
    static float fromSamples(byte[] pcm, int length) {
        if (length < 2) return 0f;
        long sum = 0;
        int samples = length / 2;
        for (int i = 0; i + 1 < length; i += 2) {
            int value = (pcm[i + 1] << 8) | (pcm[i] & 0xff);
            sum += (long) value * value;
        }
        double rms = Math.sqrt((double) sum / samples);
        return (float) Math.min(1.0, rms / 6000.0);
    }

    /** How much the microphone circle grows with the newest reading. */
    static float iconScale(float level) {
        return 1f + Math.max(0f, Math.min(1f, level)) * 0.3f;
    }

    void push(float level) {
        history[head] = Math.max(0f, Math.min(1f, level));
        head = (head + 1) % history.length;
        if (count < history.length) count++;
    }

    /** Value at a given age: 0 is the newest sample, count - 1 the oldest drawn column. */
    float valueAtAge(int age) {
        if (age < 0 || age >= count) return 0f;
        return history[(head - 1 - age + history.length * 2) % history.length];
    }

    int drawnColumns(int width) { return Math.min(count, Math.max(1, width)); }

    float newest() { return valueAtAge(0); }

    int count() { return count; }

    boolean silent() {
        for (int i = 0; i < count; i++) if (history[i] > 0f) return false;
        return true;
    }
}
