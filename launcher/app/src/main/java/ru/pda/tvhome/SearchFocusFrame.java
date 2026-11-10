package ru.pda.tvhome;

import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;
import java.util.HashMap;
import java.util.Map;

/** The home screen's thin spring contour, with tracked scroll coordinates. */
final class SearchFocusFrame extends FrameLayout {
    private final HeaderFocusOverlay contour;
    private final Map<View, float[]> targets = new HashMap<>();

    SearchFocusFrame(Context context, View content) {
        super(context);
        setClipChildren(false);
        setClipToPadding(false);
        addView(content, new LayoutParams(-1, -2));
        contour = new HeaderFocusOverlay(context);
        contour.setElevation(content.getElevation() + getResources().getDisplayMetrics().density);
        contour.setVisibility(INVISIBLE);
        addView(contour, new LayoutParams(-1, -1));
    }

    void track(View target, float radiusDp) { track(target, radiusDp, 3f); }

    /** Radius and outset in dp. The header buttons get a tighter contour than the cards. */
    void track(View target, float radiusDp, float padDp) {
        targets.put(target, new float[] {radiusDp, padDp});
        target.setOnFocusChangeListener((view, focused) -> {
            if (focused) contour.pointAtRounded(view, this, true, radiusDp, padDp);
            else post(() -> { if (!targets.containsKey(findFocus())) contour.clear(); });
        });
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        // In a wrap-content popup FrameLayout can measure its sole MATCH_PARENT child at zero height.
        // The contour must cover the final content bounds without contributing to popup measurement.
        contour.layout(0, 0, getWidth(), getHeight());
    }

    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        View target = findFocus();
        if (focused && targets.containsKey(target)) {
            float[] values = targets.get(target);
            contour.pointAtRounded(target, this, false, values[0], values[1]);
        }
    }
}
