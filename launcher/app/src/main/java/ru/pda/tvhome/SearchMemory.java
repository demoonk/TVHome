package ru.pda.tvhome;

import java.util.ArrayList;
import java.util.List;

/** Stored results, one line per card, so the results screen can be rebuilt without asking the providers again. Plain JVM. */
final class SearchMemory {
    record Card(String app, String title, String description, String poster,
                String action, String component, String data) {}

    static String encode(List<Card> cards) {
        StringBuilder text = new StringBuilder();
        for (Card card : cards) {
            if (text.length() > 0) text.append('\n');
            text.append(escape(card.app)).append('\t').append(escape(card.title)).append('\t')
                .append(escape(card.description)).append('\t').append(escape(card.poster)).append('\t')
                .append(escape(card.action)).append('\t').append(escape(card.component)).append('\t')
                .append(escape(card.data));
        }
        return text.toString();
    }

    static List<Card> decode(String stored) {
        List<Card> cards = new ArrayList<>();
        if (stored == null || stored.isEmpty()) return cards;
        for (String line : stored.split("\n")) {
            String[] parts = line.split("\t", -1);
            if (parts.length < 7) continue;
            for (int i = 0; i < parts.length; i++) parts[i] = unescape(parts[i]);
            cards.add(new Card(parts[0], parts[1], parts[2], parts[3], parts[4], parts[5], parts[6]));
        }
        return cards;
    }

    /** Cards of one app, keeping the stored order. */
    static List<Card> of(List<Card> all, String app) {
        List<Card> picked = new ArrayList<>();
        for (Card card : all) if (card.app().equals(app)) picked.add(card);
        return picked;
    }

    /** Apps in the order they were stored, without repeats. */
    static List<String> apps(List<Card> all) {
        List<String> order = new ArrayList<>();
        for (Card card : all) if (!order.contains(card.app())) order.add(card.app());
        return order;
    }

    private static String escape(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n");
    }

    private static String unescape(String value) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != '\\' || i + 1 >= value.length()) { text.append(c); continue; }
            char next = value.charAt(++i);
            if (next == 't') text.append('\t');
            else if (next == 'n') text.append('\n');
            else text.append(next);
        }
        return text.toString();
    }

    private SearchMemory() {}
}
