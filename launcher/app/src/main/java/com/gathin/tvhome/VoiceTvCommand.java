package com.gathin.tvhome;

import java.util.Locale;

final class VoiceTvCommand {
    enum Action { NONE, ON, OFF, TIMER, SETTINGS }
    static Action parse(String phrase) {
        String text = phrase.toLowerCase(Locale.ROOT).trim().replaceAll("\\s+", " ")
                .replaceFirst("[.!?]+$", "");
        if (text.matches("(?:включи|включить)(?: (?:тв|телевизор))?")) return Action.ON;
        if (text.matches("(?:выключи|выключить)(?: (?:тв|телевизор))?")) return Action.OFF;
        if (text.matches("(?:открой |открыть )?настройки")) return Action.SETTINGS;
        if (text.matches("(?:открой |открыть )?(?:таймер|сон)")) return Action.TIMER;
        return Action.NONE;
    }
}
