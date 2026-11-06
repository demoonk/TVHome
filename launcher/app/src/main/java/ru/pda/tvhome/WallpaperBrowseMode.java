package ru.pda.tvhome;

/** State shared by explicit wallpaper mode and the channel-key shortcut. */
final class WallpaperBrowseMode {
    static final float TILE_ALPHA = .05f;
    static final long ENTER_MS = 1000L;
    private static volatile boolean arrowsReserved;
    static boolean arrowsReserved() { return arrowsReserved; }
    private boolean active, explicit;
    boolean enter(boolean fromSettings) {
        boolean newlyActive = !active;
        active = true;
        arrowsReserved = true;
        explicit |= fromSettings;
        return newlyActive;
    }
    boolean active() { return active; }
    boolean explicit() { return explicit; }
    boolean acceptsStep(boolean navigationKey, boolean programKey) {
        return active && (explicit ? navigationKey : programKey);
    }
    static void reserveArrowRelease() { arrowsReserved = true; }
    static void releaseArrowRelease() { arrowsReserved = false; }
    long exitDuration(boolean leavingMode) { return leavingMode && explicit ? 1000L : 500L; }
    void reset() { active = explicit = false; arrowsReserved = false; }
}
