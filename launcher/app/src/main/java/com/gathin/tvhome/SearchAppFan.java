package com.gathin.tvhome;

import android.animation.Animator;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

/** Horizontal provider rows; simultaneous flight from the recognized query, 750 ms. */
final class SearchAppFan extends FrameLayout {
    interface Selection { void choose(MediaVoiceApps.App app); }
    private final TextView queryTitle;
    private final ArrayList<View> tiles = new ArrayList<>();
    private final float density;
    private AnimatorSet entrance;
    private boolean started;
    private float[][] points;
    private int tileWidth, tileHeight, iconSize, iconCentre;

    SearchAppFan(Context context, String query, List<MediaVoiceApps.App> apps, Selection selection) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        setBackgroundColor(0xFF111B29);
        setClipChildren(false);
        TextView caption = text("Где искать?", 16, 0xFFB5BFCE);
        LayoutParams captionSize = new LayoutParams(-1, dp(25), Gravity.TOP);
        captionSize.topMargin = dp(28);
        addView(caption, captionSize);
        queryTitle = text("«" + query + "»", 26, 0xFFFFFFFF);
        queryTitle.setMaxLines(2);
        queryTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LayoutParams titleSize = new LayoutParams(-1, -2, Gravity.TOP);
        titleSize.topMargin = dp(58);
        titleSize.leftMargin = titleSize.rightMargin = dp(32);
        addView(queryTitle, titleSize);
        int rows = (apps.size() + 6) / 7;
        float scale = Math.max(.65f, Math.min(1f, 1.5f / Math.max(1, rows)));
        tileWidth = dp(Math.round(96 * scale));
        tileHeight = dp(Math.round(112 * scale));
        iconSize = dp(Math.round(64 * scale));
        iconCentre = dp(8) + iconSize / 2;
        for (MediaVoiceApps.App app : apps) {
            LinearLayout tile = new LinearLayout(context);
            tile.setId(View.generateViewId());
            tile.setOrientation(LinearLayout.VERTICAL);
            tile.setGravity(Gravity.CENTER_HORIZONTAL);
            tile.setPadding(dp(4), dp(8), dp(4), 0);
            tile.setFocusable(true);
            tile.setClickable(true);
            tile.setContentDescription("Искать в " + app.label);
            tile.setBackground(surface(false));
            tile.setAlpha(0f);
            ImageView icon = new ImageView(context);
            icon.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            try { icon.setImageDrawable(context.getPackageManager().getApplicationIcon(app.pkg)); }
            catch (android.content.pm.PackageManager.NameNotFoundException removed) {
                icon.setImageDrawable(context.getPackageManager().getDefaultActivityIcon());
            }
            icon.setClipToOutline(true);
            icon.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override public void getOutline(View view, android.graphics.Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(16));
                }
            });
            tile.addView(icon, new LinearLayout.LayoutParams(iconSize, iconSize));
            String displayName = app.label;
            try {
                displayName = context.getPackageManager().getApplicationLabel(
                        context.getPackageManager().getApplicationInfo(app.pkg, 0)).toString();
            } catch (android.content.pm.PackageManager.NameNotFoundException removed) { /* Retain the label. */ }
            TextView label = text(displayName, Math.max(12, 14 * scale), 0xFFFFFFFF);
            label.setMaxLines(2);
            label.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams labelSize = new LinearLayout.LayoutParams(-1, -2);
            labelSize.topMargin = dp(6);
            tile.addView(label, labelSize);
            tile.setOnFocusChangeListener((view, focused) -> tile.setBackground(surface(focused)));
            tile.setOnClickListener(view -> selection.choose(app));
            addView(tile, new LayoutParams(tileWidth, tileHeight));
            tiles.add(tile);
        }
        for (int i = 0; i < tiles.size(); i++) {
            View tile = tiles.get(i);
            tile.setNextFocusLeftId(tiles.get(Math.max(0, i - 1)).getId());
            tile.setNextFocusRightId(tiles.get(Math.min(tiles.size() - 1, i + 1)).getId());
            tile.setNextFocusUpId(tiles.get(i >= 7 ? i - 7 : i).getId());
            tile.setNextFocusDownId(tiles.get(i + 7 < tiles.size() ? i + 7 : i).getId());
        }
        TextView hint = text("Стрелки — выбрать · OK — искать · Назад — отменить", 12, 0xFFB5BFCE);
        LayoutParams hintSize = new LayoutParams(-1, dp(30), Gravity.BOTTOM);
        hintSize.bottomMargin = dp(16);
        addView(hint, hintSize);
    }

    private int dp(int value) { return Math.round(value * density); }
    private TextView text(String value, float size, int color) {
        TextView view = new TextView(getContext());
        view.setText(value); view.setTextSize(size); view.setTextColor(color); view.setGravity(Gravity.CENTER);
        return view;
    }
    private GradientDrawable surface(boolean focus) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(focus ? 0x1AFFFFFF : 0x00000000);
        drawable.setCornerRadius(dp(16));
        drawable.setStroke(dp(2), focus ? 0xFFFFFFFF : 0x00000000);
        return drawable;
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        float minY = queryTitle.getBottom() + iconSize / 2f + dp(24);
        float maxY = Math.max(minY + dp(40), getHeight() - dp(58) - (tileHeight - iconCentre));
        points = SearchFanLayout.positions(tiles.size(), getWidth(), minY, maxY, density);
        for (int i = 0; i < tiles.size(); i++) {
            int x = Math.round(points[i][0] - tileWidth / 2f);
            int y = Math.round(points[i][1] - iconCentre);
            tiles.get(i).layout(x, y, x + tileWidth, y + tileHeight);
        }
        if (!started && !tiles.isEmpty()) { started = true; post(this::animateEntrance); }
    }

    private void animateEntrance() {
        if (!isAttachedToWindow()) return;
        Rect title = new Rect(0, 0, queryTitle.getWidth(), queryTitle.getHeight());
        offsetDescendantRectToMyCoords(queryTitle, title);
        ArrayList<Animator> animations = new ArrayList<>();
        for (int i = 0; i < tiles.size(); i++) {
            View tile = tiles.get(i);
            tile.setPivotX(tileWidth / 2f);
            tile.setPivotY(iconCentre);
            animations.add(ObjectAnimator.ofPropertyValuesHolder(tile,
                    PropertyValuesHolder.ofFloat(View.TRANSLATION_X, title.exactCenterX() - points[i][0], 0f),
                    PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, title.exactCenterY() - points[i][1], 0f),
                    PropertyValuesHolder.ofFloat(View.SCALE_X, .72f, 1f),
                    PropertyValuesHolder.ofFloat(View.SCALE_Y, .72f, 1f),
                    PropertyValuesHolder.ofFloat(View.ALPHA, 0f, 1f)));
        }
        entrance = new AnimatorSet();
        entrance.playTogether(animations);
        boolean enabled = ValueAnimator.areAnimatorsEnabled()
                && getContext().getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE).getBoolean("animations", true);
        entrance.setDuration(enabled ? SearchFanLayout.DURATION_MS : 0L);
        entrance.setInterpolator(new PathInterpolator(.16f, 1f, .3f, 1f));
        tiles.get(0).requestFocus();
        entrance.start();
    }

    @Override protected void onDetachedFromWindow() {
        if (entrance != null) entrance.cancel();
        super.onDetachedFromWindow();
    }
}
