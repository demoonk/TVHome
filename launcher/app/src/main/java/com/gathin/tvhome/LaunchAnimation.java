package com.gathin.tvhome;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.Toast;
import java.util.WeakHashMap;

/** Launch immediately; keep only the existing return-to-tile transition. */
final class LaunchAnimation {
    private static final WeakHashMap<Activity, State> states = new WeakHashMap<>();
    private static final class State {
        Bitmap bitmap;
        View tile;
        float alpha;
        boolean away;
        ImageView image;
    }

    static void open(Activity activity, View tile, Intent intent) {
        open(activity,tile,intent,null);
    }

    static void open(Activity activity, View tile, Intent intent, Runnable opened) {
        State previous=states.get(activity);
        if(previous!=null)clear(activity,previous);
        if (tile == null || !tile.isAttachedToWindow() || tile.getWidth() < 1
                || tile.getHeight() < 1 || !enabled(activity)) {
            launch(activity, intent, opened);
            return;
        }
        State state = new State();
        state.tile = tile;
        state.alpha = tile.getAlpha();
        try {
            state.bitmap = Bitmap.createBitmap(tile.getWidth(), tile.getHeight(), Bitmap.Config.ARGB_8888);
            tile.draw(new Canvas(state.bitmap));
        } catch (OutOfMemoryError failure) {
            launch(activity, intent, opened);
            return;
        }
        states.put(activity, state);
        state.away=true;
        if(!launch(activity,intent,opened))clear(activity,state);
    }

    private static boolean launch(Activity activity, Intent intent, Runnable opened) {
        try {
            activity.startActivity(intent);
            activity.overridePendingTransition(0, 0);
        } catch (RuntimeException unavailable) {
            android.util.Log.w("LaunchAnimation","Could not open result in "+intent.getPackage(),unavailable);
            Toast.makeText(activity, unavailable instanceof android.content.ActivityNotFoundException?"Приложение не поддерживает открытие этой карточки":"Не удалось открыть приложение", Toast.LENGTH_LONG).show();
            return false;
        }
        if(opened!=null)opened.run();
        return true;
    }

    private static boolean enabled(Activity activity) {
        return activity.getSharedPreferences(Config.SETTINGS_NAME, 0).getBoolean("animations", true)
                && android.animation.ValueAnimator.areAnimatorsEnabled();
    }

    private static ImageView attach(Activity activity, State state) {
        ViewGroup root = (ViewGroup) activity.getWindow().getDecorView();
        ImageView image = new ImageView(activity);
        image.setImageBitmap(state.bitmap);
        image.setScaleType(ImageView.ScaleType.FIT_XY);
        root.addView(image, new ViewGroup.LayoutParams(state.tile.getWidth(), state.tile.getHeight()));
        int[] tile = new int[2], origin = new int[2];
        state.tile.getLocationOnScreen(tile);
        root.getLocationOnScreen(origin);
        image.setX(tile[0] - origin[0]);
        image.setY(tile[1] - origin[1]);
        image.setPivotX(0);
        image.setPivotY(0);
        image.setScaleX(state.tile.getScaleX());
        image.setScaleY(state.tile.getScaleY());
        image.setAlpha(state.alpha);
        image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return image;
    }

    static void resume(Activity activity) {
        final State state = states.get(activity);
        if (state == null || !state.away) return;
        state.away = false;
        activity.getWindow().getDecorView().post(() -> {
            if (states.get(activity) != state) return;
            if (activity.isDestroyed() || activity.isFinishing()
                    || !state.tile.isAttachedToWindow() || !enabled(activity)) {
                clear(activity, state);
                return;
            }
            state.tile.setAlpha(0f);
            ImageView image = attach(activity, state);
            state.image = image;
            float x = image.getX(), y = image.getY();
            View root = (View) image.getParent();
            image.setX(0);
            image.setY(0);
            image.setAlpha(0);
            image.setScaleX((float) root.getWidth() / state.tile.getWidth());
            image.setScaleY((float) root.getHeight() / state.tile.getHeight());
            image.animate().x(x).y(y).scaleX(state.tile.getScaleX()).scaleY(state.tile.getScaleY())
                    .alpha(state.alpha).setDuration(500)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator())
                    .withEndAction(() -> clear(activity, state)).start();
        });
    }

    /** Interrupt an in-window animation when another UI takes over. */
    static void pause(Activity activity) {
        State state = states.get(activity);
        if (state != null && !state.away) clear(activity, state);
    }

    static void destroy(Activity activity) {
        State state = states.get(activity);
        if (state != null) clear(activity, state);
    }

    private static void detach(State state) {
        if (state.image == null) return;
        state.image.animate().withEndAction(null).cancel();
        if (state.image.getParent() instanceof ViewGroup)
            ((ViewGroup) state.image.getParent()).removeView(state.image);
        state.image.setImageDrawable(null);
        state.image = null;
    }

    private static void clear(Activity activity, State state) {
        if (states.get(activity) != state) return;
        states.remove(activity);
        state.tile.setAlpha(state.alpha);
        detach(state);
        if (state.bitmap != null && !state.bitmap.isRecycled()) state.bitmap.recycle();
    }

    private LaunchAnimation() {}
}
