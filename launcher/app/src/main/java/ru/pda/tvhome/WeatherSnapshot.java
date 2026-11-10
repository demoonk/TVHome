package ru.pda.tvhome;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;

/** One reading of the Weather card's snapshot, shared by Home and by the voice command. */
final class WeatherSnapshot {
    static final String PACKAGE = "io.github.sumyabazar1.weather";
    private static final String AUTHORITY = PACKAGE + ".snapshot";

    record Row(String city, int tempC, int humidity, long at) {}

    static Row read(Context context) {
        try (Cursor cursor = context.getContentResolver().query(Uri.parse("content://" + AUTHORITY),
                new String[] {"city", "temp_c", "humidity", "at"}, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst()) return null;
            String city = cursor.getString(0);
            if (city == null || city.isEmpty()) return null;
            return new Row(city, cursor.getInt(1), cursor.getInt(2), cursor.getLong(3));
        } catch (Exception noCard) {
            return null;
        }
    }

    /** The sentence the weather command says out loud. */
    static String speech(Row row) {
        return row.city() + ", " + row.tempC() + " градуса, влажность " + row.humidity() + " процентов";
    }

    private WeatherSnapshot() {}
}
