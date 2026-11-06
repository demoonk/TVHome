package ru.pda.tvhome;

import java.util.function.ToIntFunction;

/** Restore by app identity; a missing app or a cold start always selects the first tile. */
final class HomeFocus {
    static int position(String key, ToIntFunction<String> lookup) {
        return key == null ? 0 : Math.max(0, lookup.applyAsInt(key));
    }
    private HomeFocus() {}
}
