package ru.pda.tvhome;

/**
 * Search endpoints and card targets verified on the living-room TV (192.168.1.7) on 2026-11-09 with
 * `content query` and `cmd package query-activities`. A card can only be opened through a target the
 * installed app itself declares, so a row whose data is only an identifier needs an app-specific rule.
 * Rows with no verified target fall back to the app's own exported search activity.
 */
final class SearchCardRules {
    /** Provider that answers for this app, or null when the app exposes no reachable search provider. */
    static String authority(String id) {
        return switch (id) {
            case "num" -> "ru.yourok.num.atvsearch";
            // Кинопоиск and VK expose providers, but they reject the standard suggest path, so they have no rule here.
            case "reshala" -> "reshala.tv.app.poisk";
            default -> null;
        };
    }

    /** Path the provider actually answers on. Reshala answers without the /search prefix and returns nothing with it. */
    static String path(String id) {
        return switch (id) {
            case "num" -> "/search/search_suggest_query";
            case "reshala" -> "/search_suggest_query";
            default -> null;
        };
    }

    /**
     * Launch target for one provider row, or null when this app has no verified target for that row.
     * A full URI in the row is the app's own deeplink and is used unchanged.
     */
    static String cardUrl(String id, String data, String dataId, String extra) {
        if (data != null && data.contains("://")) return data;
        if (dataId == null) return null;
        switch (id) {
            case "num":
                // NUM rows carry a TMDB id plus its type, and the app declares themoviedb.org app links.
                if (dataId.matches("[0-9]+") && ("movie".equals(extra) || "tv".equals(extra)))
                    return "https://www.themoviedb.org/" + extra + "/" + dataId;
                return null;
            case "smarttube":
                // SmartTube rows have no intent data at all, only the YouTube video id.
                if (dataId.matches("[A-Za-z0-9_-]{11}")) return "https://www.youtube.com/watch?v=" + dataId;
                return null;
            case "kinopoisk":
                if (dataId.matches("[0-9]+")) return "https://hd.kinopoisk.ru/film/" + dataId + "/";
                return null;
            case "vk":
                if (dataId.matches("[0-9]+_[0-9]+")) return "https://vk.com/video-" + dataId;
                return null;
            default:
                return null;
        }
    }

    private SearchCardRules() {}
}
