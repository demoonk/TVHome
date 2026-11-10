package ru.pda.tvhome;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Spoken commands the launcher can carry out itself. Plain JVM, so the rules stay testable. */
final class VoiceActionCommand {
    enum Action { NONE, VOLUME_UP, VOLUME_DOWN, VOLUME_SET, MUTE_ON, MUTE_OFF, PICTURE_SETTINGS, CLOCK, WEATHER, INPUTS }

    record Parsed(Action action, int value) {
        Parsed(Action action) { this(action, -1); }
    }

    private static final Pattern VOLUME_LEVEL = Pattern.compile("^(?:громкость|громкости)\\s+(?:на\\s+)?(\\d{1,3})$");
    private static final Pattern NUMBER = Pattern.compile("(\\d{1,3})");
    /** A phrase with the search verb belongs to the search parser, which runs before this one. */
    private static final Pattern SEARCH_VERB = Pattern.compile("^(найди|поищи|ищи|поиск)\\s");

    static Parsed parse(String phrase) {
        String text = normalize(phrase);
        if (text.isEmpty() || SEARCH_VERB.matcher(text).find()) return new Parsed(Action.NONE);

        if (text.equals("без звука") || text.equals("выключи звук") || text.equals("звук выключи")
                || text.equals("mute") || text.equals("беззвучно")) return new Parsed(Action.MUTE_ON);
        if (text.equals("включи звук") || text.equals("звук включи") || text.equals("верни звук"))
            return new Parsed(Action.MUTE_OFF);

        Matcher level = VOLUME_LEVEL.matcher(text);
        if (level.matches()) return new Parsed(Action.VOLUME_SET, clamp(Integer.parseInt(level.group(1))));
        if (text.equals("громкость максимум") || text.equals("громкость на максимум")) return new Parsed(Action.VOLUME_SET, 100);
        if (text.equals("громкость минимум") || text.equals("громкость на минимум")) return new Parsed(Action.VOLUME_SET, 0);
        if (text.contains("громче") || text.contains("побольше") || text.startsWith("усиль")
                || text.startsWith("усили") || text.startsWith("прибав")) return new Parsed(Action.VOLUME_UP);
        if (text.contains("тише") || text.contains("поменьше") || text.startsWith("убав")) return new Parsed(Action.VOLUME_DOWN);

        if (text.startsWith("яркость") || text.startsWith("подсветка")) {
            return new Parsed(Action.PICTURE_SETTINGS, number(text));
        }
        if (text.equals("который час") || text.equals("часы") || text.equals("покажи часы")
                || text.equals("время")) return new Parsed(Action.CLOCK);
        if (text.contains("погода") || text.contains("погоде") || text.contains("погоду"))
            return new Parsed(Action.WEATHER);
        if (text.startsWith("вход") || text.contains("hdmi") || text.equals("антенна"))
            return new Parsed(Action.INPUTS, number(text));

        return new Parsed(Action.NONE);
    }

    /** Lowercase, without punctuation, so the phrases read the same however the engine wrote them. */
    static String normalize(String phrase) {
        if (phrase == null) return "";
        return phrase.toLowerCase(Locale.ROOT).replace('ё', 'е')
                .replaceAll("[^\\p{L}\\p{N}]+", " ").trim().replaceAll(" +", " ");
    }

    private static int number(String text) {
        Matcher match = NUMBER.matcher(text);
        return match.find() ? Integer.parseInt(match.group(1)) : -1;
    }

    private static int clamp(int value) { return Math.max(0, Math.min(100, value)); }

    private VoiceActionCommand() {}
}
