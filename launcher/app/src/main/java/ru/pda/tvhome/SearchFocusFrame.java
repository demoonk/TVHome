package ru.pda.tvhome;

import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;
import java.util.HashMap;
import java.util.Map;

/** The home screen's thin spring contour, with tracked scroll coordinates. */
final class SearchFocusFrame extends FrameLayout {
    private final HeaderFocusOverlay contour;
    private final Map<View, Float> targets = new HashMap<>();

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

    void track(View target, float radiusDp) {
        targets.put(target, radiusDp);
        target.setOnFocusChangeListener((view, focused) -> {
            if (focused) contour.pointAtRounded(view, this, true, radiusDp);
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
        if (focused && targets.containsKey(target))
            contour.pointAtRounded(target, this, false, targets.get(target));
    }
}
