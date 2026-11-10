package ru.pda.tvhome;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** The listening screen: the plate with the microphone, the rings the level leaves behind, the phrase, and the commands. */
final class VoiceListeningView extends LinearLayout {
    private static final int ACCENT = 0xFF6EC1FF;
    private static final int PLATE = 0xFF11151B;      // the plate under the glyph
    private static final int TITLE = 0xFFE8EAED;
    private static final int MUTED = 0xFF9AA0A6;
    private static final int FONT = 18;
    private static final int PHRASE_FONT = 30;
    private static final int RINGS = 12;              // rings the level leaves behind the plate
    private static final int STAGE_DP = 160;          // the plate plus the rings around it
    private static final int BUTTON_DP = 48;

    private final VoiceLevelMeter meter = new VoiceLevelMeter(2048);
    private final TextView phrase;
    private final TextView status;
    private final ImageView microphone;
    private final Rings rings;
    private final int density;
    private final String limitText;
    private final Runnable close;

    VoiceListeningView(Context context, int seconds) { this(context, seconds, null); }

    VoiceListeningView(Context context, int seconds, Runnable close) {
        super(context);
        this.close = close;
        density = (int) context.getResources().getDisplayMetrics().density;
        limitText = seconds + " с";
        setOrientation(VERTICAL);
        setClipChildren(false);        // the rings are drawn beyond the plate
        setClipToPadding(false);
        setBackground(panel());
        setPadding(dp(34), dp(28), dp(34), dp(26));

        // The close button at the top right: what the reference shows and what Back already does.
        ImageView exit = new ImageView(context);
        exit.setImageResource(R.drawable.ic_hint_close);
        exit.setImageTintList(ColorStateList.valueOf(0xFFFFFFFF));
        exit.setPadding(dp(12), dp(12), dp(12), dp(12));
        exit.setBackground(circle(0x22FFFFFF, 0xFF11151B));
        exit.setContentDescription("Закрыть");
        exit.setFocusable(true);
        exit.setClickable(true);
        if (close != null) exit.setOnClickListener(v -> close.run());
        LinearLayout top = new LinearLayout(context);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(new View(context), new LinearLayout.LayoutParams(0, 1, 1f));
        top.addView(exit, new LinearLayout.LayoutParams(dp(BUTTON_DP), dp(BUTTON_DP)));
        addView(top, row(-1, -2));

        // The plate in the middle of the stage; the rings around it are the level as it was a moment ago.
        FrameLayout stage = new FrameLayout(context);
        stage.setClipChildren(false);
        rings = new Rings(context);
        stage.addView(rings, new FrameLayout.LayoutParams(-1, -1));
        microphone = new ImageView(context);
        microphone.setImageResource(R.drawable.ic_microphone);      // the same glyph as the home menu button
        microphone.setImageTintList(ColorStateList.valueOf(0xFFFFFFFF));
        microphone.setPadding(dp(12), dp(12), dp(12), dp(12));
        microphone.setBackground(circle(0x40FFFFFF, PLATE));
        stage.addView(microphone, new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER));
        addView(stage, row(-1, dp(STAGE_DP)));

        phrase = text("", PHRASE_FONT, TITLE);
        phrase.setGravity(Gravity.CENTER);
        phrase.setMaxLines(2);
        addView(phrase, row(-1, -2));

        status = text("Слушаю · " + limitText, 14, MUTED);
        status.setGravity(Gravity.CENTER);
        addView(status, row(-1, -2));

        TextView heading = text("попробуйте сказать", 13, MUTED);
        heading.setGravity(Gravity.CENTER);
        heading.setLetterSpacing(0.16f);
        LinearLayout.LayoutParams head = row(-1, -2);
        head.topMargin = dp(26);
        head.bottomMargin = dp(14);
        addView(heading, head);

        // Chips without icons, wrapped by the measured width so nothing is cut off.
        int gap = dp(10);
        int available = getResources().getDisplayMetrics().widthPixels - dp(68) - dp(48);
        for (java.util.List<String> group : VoiceHints.chips()) {
            java.util.List<TextView> views = new java.util.ArrayList<>();
            java.util.List<Integer> widths = new java.util.ArrayList<>();
            for (String chip : group) {
                TextView view = chip(chip);
                view.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED);
                views.add(view);
                widths.add(view.getMeasuredWidth());
            }
            java.util.List<java.util.List<TextView>> lines = new java.util.ArrayList<>();
            java.util.List<TextView> line = new java.util.ArrayList<>();
            int used = 0, index = 0;
            while (index < views.size()) {
                int width = widths.get(index);
                if (!line.isEmpty() && used + gap + width > available) { lines.add(line); line = new java.util.ArrayList<>(); used = 0; }
                if (!line.isEmpty()) used += gap;
                line.add(views.get(index));
                used += width;
                index++;
            }
            if (!line.isEmpty()) lines.add(line);
            for (java.util.List<TextView> chips : lines) {
                LinearLayout row2 = new LinearLayout(context);
                row2.setGravity(Gravity.CENTER);
                for (TextView view : chips) {
                    LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-2, -2);
                    size.rightMargin = gap;
                    row2.addView(view, size);
                }
                LinearLayout.LayoutParams place = row(-1, -2);
                place.bottomMargin = dp(9);
                addView(row2, place);
            }
        }
    }

    void setState(String value) { main.post(() -> status.setText(value + " · " + limitText)); }

    void setHeard(String heard) {
        String shown = heard == null ? "" : heard;
        main.post(() -> phrase.setText(shown.isEmpty() ? "" : "\u00AB" + shown + "\u00BB"));
    }

    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean repaintPending;

    void startPulse() {
        main.postDelayed(() -> {
            if (!isAttachedToWindow()) return;
            meter.push(meter.newest());   // one more ring is added at the plate, the old one moves out
            repaint();
            startPulse();
        }, 40);
    }

    void stopPulse() { main.removeCallbacksAndMessages(null); }

    void setLevel(float level) {
        meter.push(level);
        if (repaintPending) return;
        repaintPending = true;
        main.postDelayed(() -> { repaintPending = false; repaint(); }, 40);
    }

    private void repaint() {
        rings.invalidate();
        float scale = VoiceLevelMeter.iconScale(meter.newest());
        microphone.setScaleX(scale);
        microphone.setScaleY(scale);
    }

    private LinearLayout.LayoutParams row(int width, int height) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width, height);
        params.bottomMargin = dp(10);
        return params;
    }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(getContext());
        view.setText(value);
        view.setTextColor(color);
        view.setTextSize(size);
        return view;
    }

    private TextView chip(String value) {
        TextView view = text("\u00AB" + value + "\u00BB", FONT - 4, TITLE);
        view.setPadding(dp(14), dp(8), dp(14), dp(8));
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(0x16FFFFFF);
        pill.setCornerRadius(dp(16));
        pill.setStroke(Math.max(1, dp(1)), 0x2AFFFFFF);
        view.setBackground(pill);
        return view;
    }

    private GradientDrawable panel() {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(0xF20A0F16);
        shape.setCornerRadius(dp(24));
        shape.setStroke(Math.max(1, dp(1)), 0x1FFFFFFF);
        return shape;
    }

    private GradientDrawable circle(int border, int fill) {
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.OVAL);
        shape.setColor(fill);
        shape.setStroke(Math.max(1, dp(1)), border);
        return shape;
    }

    private int dp(float value) { return Math.round(value * density); }

    private float plateRadius() { return dp(24f); }

    /** Each ring is one reading: the newest next to the plate, the older ones further out and thinner. */
    private final class Rings extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        Rings(Context context) { super(context); }

        @Override protected void onDraw(Canvas canvas) {
            int width = getWidth(), height = getHeight();
            if (width == 0 || height == 0) return;
            int available = meter.drawnColumns(RINGS);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            float step = dp(4f);
            float middleX = width / 2f, middleY = height / 2f;
            for (int age = 0; age < available; age++) {
                float level = meter.valueAtAge(age);
                paint.setColor(ACCENT);
                // The further a ring is from the plate, the more transparent it is.
                paint.setAlpha(235 - (int) (190f * age / RINGS));
                paint.setStrokeWidth(Math.max(1f, level * dp(13f)));
                canvas.drawCircle(middleX, middleY, plateRadius() + dp(4) + age * step, paint);
            }
        }
    }
}
