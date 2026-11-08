/**
 * Pure gear-button helpers for the remote bridge: input_event parsing and the
 * short/long press decision. No Android API and no process execution here, so the
 * decision is checkable without a TV.
 */
public final class GearDecision {
    public static final int EV_KEY = 1;
    public static final int EV_MSC = 4;
    public static final int MSC_SCAN = 4;

    /** Physical gear key on the TVC0GWBT Consumer Control device. */
    public static final int GEAR_SCAN = 0x000c009f;
    public static final int KEY_UNKNOWN = 240;
    public static final int KEY_BACK = 158;

    /** Hold at least this long counts as a long press; shorter press opens picture settings. */
    public static final long DEFAULT_LONG_PRESS_MS = 700;
    /** Minimum gap between two launches. */
    public static final long DEBOUNCE_MS = 800;

    private GearDecision() { }

    public static int u16(byte[] data, int offset) {
        return (data[offset] & 255) | ((data[offset + 1] & 255) << 8);
    }

    public static int i32(byte[] data, int offset) {
        return u16(data, offset) | (u16(data, offset + 2) << 16);
    }

    /*
     * struct input_event: timeval time, u16 type, u16 code, u32 value.
     * timeval is 8 bytes on this 32-bit userspace (record size 16) and 16 bytes on 64-bit
     * kernels (record size 24), so the fields are read from the end of the record.
     */
    public static int type(byte[] record, int eventSize) {
        return u16(record, eventSize - 8);
    }

    public static int code(byte[] record, int eventSize) {
        return u16(record, eventSize - 6);
    }

    public static int value(byte[] record, int eventSize) {
        return i32(record, eventSize - 4);
    }

    /**
     * Long press opens sound settings; a single press opens picture settings.
     * When the firmware has no sound settings activity the long press honestly
     * falls back to picture settings instead of doing nothing.
     */
    public static String targetFor(long holdMs, long longPressMs, boolean soundAvailable,
                                   String pictureTarget, String soundTarget) {
        if (soundAvailable && holdMs >= longPressMs) {
            return soundTarget;
        }
        return pictureTarget;
    }
}
