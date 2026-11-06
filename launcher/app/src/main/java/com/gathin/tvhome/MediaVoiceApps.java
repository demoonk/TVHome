package com.gathin.tvhome;

/** Only user-facing media apps; no system/factory search handlers are queried. */
final class MediaVoiceApps {
    static final class App {
        final String id, label, pkg, mode;
        final String[] aliases;
        App(String id, String label, String pkg, String mode, String... aliases) {
            this.id = id; this.label = label; this.pkg = pkg; this.mode = mode; this.aliases = aliases;
        }
    }
    static final App[] ALL = {
        new App("smarttube", "SmartTube", "org.smarttube.stable", "youtube",
                "смартуб", "смартубе", "смартубом", "смарттуб", "смарттубе", "смарттубом", "смарттюб", "смарттюбе", "смартюб", "смартюбе", "smarttube", "ютуб", "ютубе", "ютюб", "ютюбе", "youtube"),
        new App("num", "NUM", "ru.yourok.num", "num", "нум", "нуме", "num"),
        new App("kinopoisk", "Кинопоиск", "ru.kinopoisk.tv", "provider", "кинопоиск", "кинопоиске", "kinopoisk"),
        new App("kion", "КИОН / МТС ТВ", "ru.mts.mtstv", "provider", "кион", "кионе", "kion", "мтс", "мтств", "mtstv"),
        new App("vk", "VK Видео", "com.vk.tv", "deeplink", "вк", "вквидео", "вконтакте", "vk", "vkvideo"),
        new App("rutube", "Rutube", "ru.rutube.app", "deeplink", "рутуб", "рутубе", "рутюб", "рутюбе", "rutube"),
        new App("reshala", "Решала", "reshala.tv.app", "reshala", "решала", "решале", "решалу", "reshala", "резка", "резке", "hdrezka"),
        new App("lift", "Lift", "io.lift.app", "manual", "лифт", "лифте", "lift"),
        new App("vimu", "Vimu", "net.gtvbox.videoplayer", "provider", "виму", "vimu", "плеер"),
        new App("mpv", "mpv", "is.xyz.mpv", "manual", "мпв", "эмпиви", "mpv"),
        new App("tizentube", "TizenTube", "io.gh.reisxd.tizentube.cobalt", "youtube", "тайзентуб", "тайзентубе", "тизентуб", "тизентубе", "tizentube"),
        new App("boosty", "VK Видео Live / Boosty", "live.vkplay.app", "manual", "бусти", "boosty", "вкплей", "вклайв", "vkplay", "vkplaylive")
    };
    static App byId(String id) {
        for (App app : ALL) if (app.id.equals(id)) return app;
        return null;
    }
    static App byName(String spoken) {
        String name = VoiceLaunchActivity.normalize(spoken);
        for (App app : ALL) for (String alias : app.aliases) if (alias.equals(name)) return app;
        return null;
    }
    private MediaVoiceApps() {}
}
