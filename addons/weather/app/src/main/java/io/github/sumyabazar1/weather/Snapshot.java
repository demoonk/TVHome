package io.github.sumyabazar1.weather;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * The few numbers the launcher's top bar shows, kept where the launcher can read them without this page on
 * screen: after every successful fetch the place's short name, the temperature, the humidity and the moment
 * go into the app's preferences, and {@link WeatherProvider} serves them. Only the numbers cross over; the
 * launcher has no network of its own and never fetches anything.
 */
final class Snapshot {
    /** The place preferences are here too: the short name of the chosen city, and its coordinates. */
    static final String PREFS = "place";
    static final String CITY = "snapshot_city";
    static final String TEMP = "snapshot_temp";
    static final String HUMIDITY = "snapshot_humidity";
    static final String AT = "snapshot_at";

    private Snapshot() { }

    /** Keeps what the top bar shows. Called from the UI thread with the report just drawn. */
    static void publish(Context context, String city, Forecast.Report report) {
        if (report == null) return;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(CITY, city == null ? "" : city)
                .putInt(TEMP, report.tempC)
                .putInt(HUMIDITY, report.humidity)
                .putLong(AT, System.currentTimeMillis())
                .apply();
    }

    /**
     * One fetch for the place already chosen, on the caller's background thread: the answer to the launcher's
     * refresh request. False when no place is set — the launcher can only ask, not make this app choose one.
     */
    static boolean refreshOnce(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String city = prefs.getString("name", "");
        if (city == null || city.isEmpty()) return false;
        // A reading younger than the retry interval is not worth another call, however often the launcher asks.
        long last = prefs.getLong(AT, 0L);
        if (last > 0L && System.currentTimeMillis() - last < Forecast.RETRY_MS) return true;
        double lat = Double.longBitsToDouble(prefs.getLong("lat", 0));
        double lon = Double.longBitsToDouble(prefs.getLong("lon", 0));
        try {
            publish(context, city, Forecast.fetch(lat, lon, Forecast.MIN_DAYS));
            return true;
        } catch (Exception offline) {
            return false;
        }
    }
}
