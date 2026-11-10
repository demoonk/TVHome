package ru.pda.tvhome;

import java.util.ArrayList;
import java.util.List;

/** Hint line for the listening screen. Every example has to be understood by the router, so the hints are checked, not invented. */
final class VoiceHints {
    static List<String> examples() {
        List<String> lines = new ArrayList<>();
        lines.add("найди фильм");
        lines.add("открой Виму");
        lines.add("алиса привет");
        lines.add("настройки");
        lines.add("поставь таймер 30 минут");
        lines.add("выключи ТВ");
        lines.add("включи ТВ");
        lines.add("громче");
        lines.add("который час");
        lines.add("какая погода");
        return lines;
    }

    /** A hint is honest only if the app really acts on that phrase. */
    static boolean understood(String phrase) {
        if (VoiceAliceCommand.parse(phrase) != null) return true;
        if (VoiceTvCommand.parse(phrase) != VoiceTvCommand.Action.NONE) return true;
        if (VoiceTimerCommand.minutes(phrase) != null) return true;
        if (VoiceActionCommand.parse(phrase).action() != VoiceActionCommand.Action.NONE) return true;
        String text = phrase.toLowerCase(java.util.Locale.ROOT);
        return text.startsWith("найди ") || text.startsWith("открой ");
    }

    static List<String> honestExamples() {
        List<String> kept = new ArrayList<>();
        for (String phrase : examples()) if (understood(phrase)) kept.add(phrase);
        return kept;
    }

    static String line() { return String.join(" · ", honestExamples()); }

    /** Three balanced lines: what is said to search, what the TV switches, and what the launcher does itself. */
    static List<List<String>> chips() {
        List<String> speech = new ArrayList<>();
        List<String> switches = new ArrayList<>();
        List<String> actions = new ArrayList<>();
        for (String phrase : honestExamples()) {
            if (VoiceTvCommand.parse(phrase) != VoiceTvCommand.Action.NONE) switches.add(phrase);
            else if (VoiceTimerCommand.minutes(phrase) != null) switches.add(phrase);
            else if (VoiceActionCommand.parse(phrase).action() != VoiceActionCommand.Action.NONE) actions.add(phrase);
            else speech.add(phrase);
        }
        List<List<String>> lines = new ArrayList<>();
        lines.add(speech);
        lines.add(switches);
        lines.add(actions);
        return lines;
    }

    /** The same groups as lines of text, for anything that prints them. */
    static List<String> twoLines() {
        List<String> lines = new ArrayList<>();
        for (List<String> chips : chips()) lines.add(String.join(" · ", chips));
        return lines;
    }

    private VoiceHints() {}
}

