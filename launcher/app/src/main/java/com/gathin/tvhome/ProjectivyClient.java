package com.gathin.tvhome;

import android.content.*;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import tv.projectivy.plugin.wallpaperprovider.api.EventTimeElapsed;
import tv.projectivy.plugin.wallpaperprovider.api.IWallpaperProviderService;
import tv.projectivy.plugin.wallpaperprovider.api.Wallpaper;

/**
 * Client for Projectivy wallpaper-provider plugins (Aerial Views offers its wallpapers over this
 * protocol). The service is found through its plugin action plus the API version metadata, then
 * bound briefly to ask for its wallpapers — bind, one call, unbind, so no connection is kept and
 * nothing is polled. Called only from the wallpaper worker thread; every plugin failure (no
 * service, a refused binding, a dead provider, a timeout) just yields an empty list, because a
 * third-party plugin must never take Home down.
 */
final class ProjectivyClient {
    static final String TAG = "ProjectivyClient";
    static final String ACTION = "tv.projectivy.plugin.WALLPAPER_PROVIDER";
    // The plugin template declares its metadata under plain names (apiVersion, uuid, name),
    // and that is what Aerial Views ships: check "apiVersion", with a default so a missing key
    // or a wrong-typed value just fails the match instead of throwing.
    private static final String META_VERSION = "apiVersion";
    private static final long BIND_TIMEOUT_MS = 5000;

    /** The plugin service speaking API version 1, or null when no app on the TV offers it. */
    static ComponentName discover(Context c) {
        try {
            List<ResolveInfo> services = c.getPackageManager().queryIntentServices(new Intent(ACTION), PackageManager.GET_META_DATA);
            if (services == null) return null;
            for (ResolveInfo info : services) {
                if (info.serviceInfo == null) continue;
                Bundle meta = info.serviceInfo.metaData;
                if (meta != null && meta.getInt(META_VERSION) == 1)
                    return new ComponentName(info.serviceInfo.packageName, info.serviceInfo.name);
            }
        } catch (RuntimeException ignored) {}
        return null;
    }

    /**
     * The provider's VIDEO wallpapers as player items: the provider refreshes them itself, so this
     * is asked again on every Home start. Empty, and logged, on any failure.
     */
    static List<WallpaperSource.Item> videos(Context c) {
        List<WallpaperSource.Item> items = new ArrayList<>();
        ComponentName service = discover(c);
        if (service == null) { Log.i(TAG, "no wallpaper provider plugin found"); return items; }
        Log.i(TAG, "provider service " + service.flattenToShortString());
        try {
            final IBinder[] binder = new IBinder[1];
            CountDownLatch connected = new CountDownLatch(1);
            ServiceConnection connection = new ServiceConnection() {
                @Override public void onServiceConnected(ComponentName name, IBinder service) { binder[0] = service; connected.countDown(); }
                @Override public void onServiceDisconnected(ComponentName name) { connected.countDown(); }
            };
            if (!c.bindService(new Intent(ACTION).setComponent(service), connection, Context.BIND_AUTO_CREATE)) {
                Log.w(TAG, "bindService refused by " + service.getPackageName()); return items;
            }
            try {
                if (!connected.await(BIND_TIMEOUT_MS, TimeUnit.MILLISECONDS) || binder[0] == null) {
                    Log.w(TAG, "binding to " + service.getPackageName() + " timed out"); return items;
                }
                List<Wallpaper> wallpapers = null;
                try { wallpapers = IWallpaperProviderService.Stub.asInterface(binder[0]).getWallpapers(new EventTimeElapsed()); }
                catch (Exception failure) { Log.w(TAG, "getWallpapers failed: " + failure); return items; }
                int total = wallpapers == null ? 0 : wallpapers.size();
                for (Wallpaper w : wallpapers == null ? Collections.<Wallpaper>emptyList() : wallpapers) {
                    if (w == null || w.uri == null || w.type != Wallpaper.TYPE_VIDEO) continue;
                    items.add(new WallpaperSource.Item(Uri.parse(w.uri), w.title == null ? "" : w.title, "", w.displayMode, true));
                }
                Log.i(TAG, "provider " + service.getPackageName() + ": " + total + " wallpapers, " + items.size()
                        + " video, first uri scheme " + (items.isEmpty() ? "-" : items.get(0).uri.getScheme()));
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            finally { try { c.unbindService(connection); } catch (RuntimeException ignored) {} }
        } catch (Exception refused) { Log.w(TAG, "binding failed: " + refused); }
        return items;
    }
}
