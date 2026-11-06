package com.gathin.tvhome;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class VoiceSearchCommand {
    private static final String KEYWORDS = "найди|найти|ищи|поиск|фильм|сериал|мультфильм|мульт";
    private static final Pattern COMMAND = Pattern.compile(
            "^(?:(?:" + KEYWORDS + ")\\s+)++(.+?)[.!?]*$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern KEYWORD_ONLY = Pattern.compile("^(?:" + KEYWORDS + ")$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    final String query;
    final String target;
    private VoiceSearchCommand(String query, String target) { this.query = query; this.target = target; }
    static VoiceSearchCommand parse(String phrase) {
        Matcher match = COMMAND.matcher(phrase.trim());
        if (!match.matches()) return null;
        String query = match.group(1).trim();
        if (query.isEmpty() || KEYWORD_ONLY.matcher(query).matches()) return null;
        Matcher boundary = Pattern.compile("\\s+в\\s*", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(query);
        while (boundary.find()) {
            MediaVoiceApps.App app = MediaVoiceApps.byName(query.substring(boundary.end()));
            if (app != null) {
                String title = query.substring(0, boundary.start()).trim();
                if (!title.isEmpty()) return new VoiceSearchCommand(title, app.id);
            }
        }
        return new VoiceSearchCommand(query, "");
    }
}
