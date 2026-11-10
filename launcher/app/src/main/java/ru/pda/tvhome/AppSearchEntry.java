package ru.pda.tvhome;

import java.net.URLEncoder;

/**
 * In-app search entry points. URL rules were verified on the living-room TV (192.168.1.7) on
 * 2026-11-08 with `cmd package query-activities`: only hosts and schemes the installed app itself
 * declares are used. An app without a reachable entry can only be opened, so the button does not
 * promise a search it cannot deliver.
 */
final class AppSearchEntry {
    /** Verified entry base per app, or null when the app has no reachable search screen by URL. */
    static String base(String id) {
        return switch (id) {
            // VK Видео declares vk.com/video*, so its search page is reachable only through its own scheme.
            case "vk" -> "vkvideo://vk.com/search?query=";
            case "reshala" -> "reshala://search?q=";
            // SmartTube and TizenTube resolve the public YouTube results URL to their own search screen.
            case "smarttube", "tizentube" -> "https://www.youtube.com/results?search_query=";
            // The preinstalled YouTube TV app has no search provider and no exported ACTION_SEARCH handler.
            // Verified on 192.168.1.7 on 2026-11-10 with `cmd package query-activities`: the results URL
            // resolves to its own ShellActivity, so this is the only honest entry point it has.
            case "youtube" -> "https://www.youtube.com/results?search_query=";
            // Boosty resolves its own search page, so its button can promise a search.
            case "boosty" -> "https://vkplay.live/search?query=";
            default -> null;
        };
    }

    static String searchUrl(String id, String query) {
        String base = base(id);
        if (base == null) return null;
        try {
            // The charset-name overload is available on minSdk 26; the StandardCharsets one needs API 33.
            return base + URLEncoder.encode(query, "UTF-8").replace("+", "%20");
        } catch (java.io.UnsupportedEncodingException impossible) {
            return base + query;
        }
    }

    /**
     * Kion exposes a search activity, but on this TV it draws nothing: started with ACTION_SEARCH it stays
     * black even when the app is already open (checked on 192.168.1.7 on 2026-11-10, frames
     * kion-plain-search.png and kion-search-while-open.png). Its home screen does render, so Kion can only
     * be opened, and the button must not promise a search that shows an empty screen.
     */
    static boolean searchActivityIsUsable(String id) { return !"kion".equals(id); }

    /** hasSearchActivity is the app's own visible search activity, discovered at runtime by MediaSearch. */
    static String buttonLabel(MediaVoiceApps.App app, boolean hasSearchActivity) {
        return base(app.id) != null || (hasSearchActivity && searchActivityIsUsable(app.id))
                ? "Искать в " + app.label : "Открыть " + app.label;
    }

    private AppSearchEntry(){}
}
