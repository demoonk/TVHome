package io.github.sumyabazar1.weather;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

/**
 * Read-only view of {@link Snapshot} for the launcher's top bar: one row with the city, the temperature, the
 * humidity and the moment it was read, or no row at all before the first fetch. Nothing here touches the
 * network; the numbers came off the page's own refresh.
 */
public final class WeatherProvider extends ContentProvider {
    static final String AUTHORITY = "io.github.sumyabazar1.weather.snapshot";
    static final String[] COLUMNS = {"city", "temp_c", "humidity", "at"};

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        SharedPreferences prefs = getContext().getSharedPreferences(Snapshot.PREFS, Context.MODE_PRIVATE);
        MatrixCursor cursor = new MatrixCursor(COLUMNS);
        long at = prefs.getLong(Snapshot.AT, 0L);
        String city = prefs.getString(Snapshot.CITY, "");
        if (at > 0L && city != null && !city.isEmpty()) {
            cursor.addRow(new Object[]{city, prefs.getInt(Snapshot.TEMP, 0),
                    prefs.getInt(Snapshot.HUMIDITY, 0), at});
        }
        return cursor;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    // Read-only except for the place: the launcher's settings write the city there (its own keyboard and
    // its location services), and the page then fetches for that place like it does for its own picker.

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        if (!uri.getPathSegments().contains("place")) throw new UnsupportedOperationException("read-only");
        String name = values.getAsString("name");
        Double lat = values.getAsDouble("lat");
        Double lon = values.getAsDouble("lon");
        if (name == null || name.isEmpty() || lat == null || lon == null) return 0;
        SharedPreferences prefs = getContext().getSharedPreferences(Snapshot.PREFS, Context.MODE_PRIVATE);
        prefs.edit()
                .putString("name", name)
                .putLong("lat", Double.doubleToRawLongBits(lat))
                .putLong("lon", Double.doubleToRawLongBits(lon))
                .putLong(Snapshot.AT, 0L)   // a new place makes the old reading stale at once
                .apply();
        final Context context = getContext();
        new Thread(() -> Snapshot.refreshOnce(context), "weather-place").start();
        return 1;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("read-only");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("read-only");
    }
}
