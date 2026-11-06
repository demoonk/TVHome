package ru.pda.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.ref.WeakReference;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Bounded private previews and expensive artwork are prepared off the UI thread. */
final class HomeArtwork {
    private static final ExecutorService WORK = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile Bitmap wallpaper;
    private static final java.util.concurrent.atomic.AtomicBoolean saving = new java.util.concurrent.atomic.AtomicBoolean();
    private static WeakReference<Bitmap> glow = new WeakReference<>(null);
    static void spotlight(int size, Consumer<Bitmap> ready) {
        Bitmap cached = glow.get();
        if (cached != null && !cached.isRecycled() && cached.getWidth() == size) { ready.accept(cached); return; }
        WORK.execute(() -> {
            try {
                Bitmap bitmap = glow.get();
                if (bitmap == null || bitmap.isRecycled() || bitmap.getWidth() != size) {
                    bitmap = GradientDither.spotlight(size); glow = new WeakReference<>(bitmap);
                }
                final Bitmap result = bitmap; MAIN.post(() -> ready.accept(result));
            } catch (OutOfMemoryError unavailable) { android.util.Log.w("HomeArtwork", "Spotlight memory unavailable"); }
        });
    }
    static Bitmap previewCopy(Bitmap original) {
        float scale = Math.min(1f, Math.min(1280f / original.getWidth(), 720f / original.getHeight()));
        Bitmap small = Bitmap.createScaledBitmap(original, Math.max(1, Math.round(original.getWidth() * scale)),
                Math.max(1, Math.round(original.getHeight() * scale)), true);
        return small == original ? original.copy(Bitmap.Config.ARGB_8888, false) : small;
    }
    static void remember(Context context, Bitmap preview) {
        if (preview == null) return;
        wallpaper = preview;
        save(context.getCacheDir());
    }
    private static void save(File directory) {
        if (!saving.compareAndSet(false, true)) return;
        WORK.execute(() -> {
            Bitmap written = null;
            try {
                while (wallpaper != null && written != wallpaper) {
                    written = wallpaper;
                    File temporary = new File(directory, "wallpaper-preview.tmp");
                    try {
                        try (FileOutputStream output = new FileOutputStream(temporary)) {
                            if (!written.compress(Bitmap.CompressFormat.PNG, 100, output)) break;
                        }
                        if (!temporary.renameTo(new File(directory, "wallpaper-preview.png"))) temporary.delete();
                    } catch (Exception unavailable) { android.util.Log.d("HomeArtwork", "Preview cache unavailable"); break; }
                }
            } finally {
                saving.set(false);
                if (wallpaper != null && wallpaper != written) save(directory);
            }
        });
    }
    static void loadPreview(Context context, Consumer<Bitmap> ready) {
        Bitmap cached = wallpaper;
        if (cached != null && !cached.isRecycled()) { ready.accept(cached); return; }
        File file = new File(context.getCacheDir(), "wallpaper-preview.png");
        WORK.execute(() -> {
            try {
                Bitmap bitmap = BitmapFactory.decodeFile(file.getPath());
                if (bitmap != null) { wallpaper = bitmap; MAIN.post(() -> ready.accept(bitmap)); }
            } catch (OutOfMemoryError unavailable) { android.util.Log.w("HomeArtwork", "Preview memory unavailable"); }
        });
    }
    static void trim() { wallpaper = null; glow.clear(); }
    private HomeArtwork() {}
}
