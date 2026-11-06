package com.gathin.tvhome;

import android.app.SearchManager;
import android.app.SearchableInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.CancellationSignal;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.PathPermission;

final class MediaSearch {
    static SearchableInfo searchable(Context context, String pkg) {
        SearchManager manager = context.getSystemService(SearchManager.class);
        for (ResolveInfo candidate : context.getPackageManager().queryIntentActivities(
                new Intent(Intent.ACTION_SEARCH).setPackage(pkg), 0)) {
            if (!candidate.activityInfo.exported) continue;
            SearchableInfo info = manager.getSearchableInfo(new ComponentName(pkg, candidate.activityInfo.name));
            if (info != null && info.getSuggestAuthority() != null
                    && pkg.equals(info.getSearchActivity().getPackageName())) return info;
        }
        return null;
    }

    private static boolean readable(Context context, String authority, String path) {
        ProviderInfo provider = context.getPackageManager().resolveContentProvider(authority, 0);
        if (provider == null || !provider.enabled || !provider.exported) return false;
        if (provider.readPermission != null && context.checkSelfPermission(provider.readPermission)
                != PackageManager.PERMISSION_GRANTED) return false;
        if (provider.pathPermissions != null) for (PathPermission permission : provider.pathPermissions) {
            if (permission.getReadPermission() != null
                    && (permission.match(path) || permission.match(path + "/dummy"))
                    && context.checkSelfPermission(permission.getReadPermission()) != PackageManager.PERMISSION_GRANTED) return false;
        }
        return true;
    }

    /** Discover without issuing network-backed provider queries or requesting privileged permissions. */
    static List<MediaVoiceApps.App> availableApps(Context context) {
        LinkedHashMap<String, MediaVoiceApps.App> found = new LinkedHashMap<>();
        LinkedHashMap<String, MediaVoiceApps.App> candidates = new LinkedHashMap<>();
        for (MediaVoiceApps.App app : MediaVoiceApps.ALL) candidates.put(app.pkg, app);
        for (ResolveInfo info : context.getPackageManager().queryIntentActivities(new Intent(Intent.ACTION_SEARCH), 0)) {
            String pkg = info.activityInfo.packageName;
            if (candidates.containsKey(pkg) || pkg.equals(context.getPackageName())) continue;
            android.content.pm.ApplicationInfo ai = info.activityInfo.applicationInfo;
            // Video-category additions can participate without another hardcoded dictionary entry.
            if (ai.category != android.content.pm.ApplicationInfo.CATEGORY_VIDEO) continue;
            candidates.put(pkg, new MediaVoiceApps.App("provider:" + pkg,
                    ai.loadLabel(context.getPackageManager()).toString(), pkg, "provider"));
        }
        for (MediaVoiceApps.App app : candidates.values()) {
            try {
                if (context.getPackageManager().getLeanbackLaunchIntentForPackage(app.pkg) == null
                        && context.getPackageManager().getLaunchIntentForPackage(app.pkg) == null) continue;
                if (SiteSearch.supports(app) || "deeplink".equals(app.mode)) { found.put(app.pkg, app); continue; }
                SearchableInfo info = searchable(context, app.pkg);
                if ("num".equals(app.id) && readable(context, "ru.yourok.num.atvsearch", "/search/search_suggest_query")) {
                    found.put(app.pkg, app);
                } else if (info != null) {
                    String path = (info.getSuggestPath() == null ? "" : "/" + info.getSuggestPath())
                            + "/" + SearchManager.SUGGEST_URI_PATH_QUERY;
                    // SmartTube's public search URL is usable even if its suggestions are protected.
                    if ("youtube".equals(app.mode) || readable(context, info.getSuggestAuthority(), path)) {
                        found.put(app.pkg, "youtube".equals(app.mode) ? app
                                : new MediaVoiceApps.App(app.id, app.label, app.pkg, "provider", app.aliases));
                    }
                }
            } catch (RuntimeException unavailable) { /* Package replaced or provider unavailable. */ }
        }
        return new ArrayList<>(found.values());
    }
    static final class Hit {
        final String label, title, description, poster;
        final Intent intent;
        Hit(String title, String description, String poster, Intent intent) {
            this.title = title; this.description = description; this.poster = poster;
            this.label = title + (description == null || description.isEmpty() ? "" : " — " + description);
            this.intent = intent;
        }
    }
    static boolean canReadResults(Context context, MediaVoiceApps.App app) {
        if (SiteSearch.supports(app)) return true;
        if ("deeplink".equals(app.mode) || "manual".equals(app.mode) || "reshala".equals(app.mode)) return false;
        if ("num".equals(app.id)) return readable(context, "ru.yourok.num.atvsearch", "/search/search_suggest_query");
        SearchableInfo info = searchable(context, app.pkg);
        if (info == null) return false;
        return readable(context, info.getSuggestAuthority(),
                (info.getSuggestPath() == null ? "" : "/" + info.getSuggestPath()) + "/search_suggest_query");
    }
    static List<Hit> find(Context context, MediaVoiceApps.App app, String query, CancellationSignal signal) {
        if (SiteSearch.supports(app)) return SiteSearch.find(context,app,query,signal);
        SearchableInfo searchable = null;
        if (!"num".equals(app.id)) {
            searchable = searchable(context, app.pkg);
            if (searchable == null) throw new IllegalStateException("Search unavailable");
        }
        Uri.Builder uri;
        String selection = null;
        String[] args = null;
        if (searchable == null) {
            uri = Uri.parse("content://ru.yourok.num.atvsearch/search/search_suggest_query").buildUpon().appendPath(query);
        } else {
            uri = new Uri.Builder().scheme("content").authority(searchable.getSuggestAuthority());
            if (searchable.getSuggestPath() != null) uri.appendEncodedPath(searchable.getSuggestPath());
            uri.appendPath(SearchManager.SUGGEST_URI_PATH_QUERY);
            selection = searchable.getSuggestSelection();
            if (selection != null) args = new String[]{query};
            else uri.appendPath(query);
        }
        List<Hit> hits = new ArrayList<>();
        try (Cursor cursor = context.getContentResolver().query(uri.appendQueryParameter("limit", "20").build(), null, selection, args, null, signal)) {
            if (cursor == null) return hits;
            while (cursor.moveToNext() && hits.size() < 20) {
                String title = column(cursor, SearchManager.SUGGEST_COLUMN_TEXT_1);
                if (title == null || title.isEmpty()) continue;
                String description = column(cursor, SearchManager.SUGGEST_COLUMN_TEXT_2);
                Intent intent;
                if (searchable == null) {
                    String id = column(cursor, SearchManager.SUGGEST_COLUMN_INTENT_DATA_ID);
                    String type = column(cursor, SearchManager.SUGGEST_COLUMN_INTENT_EXTRA_DATA);
                    if (id == null || !id.matches("[0-9]+") || !("movie".equals(type) || "tv".equals(type))) continue;
                    intent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.themoviedb.org/" + type + "/" + id)).setPackage(app.pkg);
                } else {
                    String action = column(cursor, SearchManager.SUGGEST_COLUMN_INTENT_ACTION);
                    if (action == null) action = searchable.getSuggestIntentAction();
                    if (action == null) action = Intent.ACTION_SEARCH;
                    String data = column(cursor, SearchManager.SUGGEST_COLUMN_INTENT_DATA);
                    if (data == null) data = searchable.getSuggestIntentData();
                    String id = column(cursor, SearchManager.SUGGEST_COLUMN_INTENT_DATA_ID);
                    if (data != null && id != null) data = data + "/" + Uri.encode(id);
                    intent = new Intent(action).setComponent(searchable.getSearchActivity())
                            .putExtra(SearchManager.USER_QUERY, query);
                    if (data != null) intent.setData(Uri.parse(data));
                    String extra = column(cursor, SearchManager.SUGGEST_COLUMN_INTENT_EXTRA_DATA);
                    if (extra != null) intent.putExtra(SearchManager.EXTRA_DATA_KEY, extra);
                    String resultQuery = column(cursor, SearchManager.SUGGEST_COLUMN_QUERY);
                    intent.putExtra(SearchManager.QUERY, resultQuery == null ? query : resultQuery);
                }
                String poster = column(cursor, "suggest_result_card_image");
                if (poster == null) poster = column(cursor, SearchManager.SUGGEST_COLUMN_ICON_1);
                String year = column(cursor, "suggest_production_year");
                if ((description == null || description.isEmpty()) && year != null) description = year;
                hits.add(new Hit(title, description, poster, intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
            }
        }
        return hits;
    }
    private static String column(Cursor cursor, String name) {
        int index = cursor.getColumnIndex(name);
        return index < 0 || cursor.isNull(index) ? null : cursor.getString(index);
    }
    private MediaSearch() {}
}
