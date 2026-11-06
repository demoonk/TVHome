package com.gathin.tvhome;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class VoiceTimerCommand {
    private static final Pattern SET = Pattern.compile(
            "^(?:(?:поставь|установи|включи)\\s+)?таймер\\s+(?:на\\s+)?(\\d{1,4}|одну|один|пятнадцать|тридцать|сорок пять|шестьдесят|девяносто)\\s+минут(?:а|ы|у)?[.!?]?$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    /** Null means another command; 0 cancels, positive values arm the idle timer. */
    static Integer minutes(String phrase) {
        String text = phrase.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        text = text.replaceAll("\\bсон\\b", "таймер");
        if (text.matches("(?:выключи|отключи|отмени) таймер[.!?]?|таймер (?:выключить|отключить)[.!?]?")) return 0;
        if (text.matches("(?:(?:поставь|установи|включи) )?таймер (?:на )?минут[ау][.!?]?")) return 1;
        Matcher match = SET.matcher(text);
        if (!match.matches()) return null;
        String amount = match.group(1);
        int value;
        switch (amount) {
            case "одну": case "один": value = 1; break;
            case "пятнадцать": value = 15; break;
            case "тридцать": value = 30; break;
            case "сорок пять": value = 45; break;
            case "шестьдесят": value = 60; break;
            case "девяносто": value = 90; break;
            default: value = Integer.parseInt(amount);
        }
        return value >= 1 && value <= 1440 ? value : null;
    }
}
