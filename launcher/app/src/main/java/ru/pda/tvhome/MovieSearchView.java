package ru.pda.tvhome;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** One independent, cancellable results row per app. No shared provider timeout. */
final class MovieSearchView extends LinearLayout {
    private final Activity activity;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService searches = Executors.newFixedThreadPool(3);
    private final ExecutorService images = Executors.newFixedThreadPool(2);
    private final List<CancellationSignal> signals = new ArrayList<>();
    private final android.util.LruCache<String, Bitmap> posters = new android.util.LruCache<String, Bitmap>(8 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getAllocationByteCount(); }
    };
    private volatile boolean closed;
    private android.widget.EditText queryInput;

    String queryText() { return queryInput.getText().toString(); }

    MovieSearchView(Activity activity, String query, List<MediaVoiceApps.App> apps,
                    Consumer<MediaVoiceApps.App> openSearch, Consumer<String> submitQuery) {
        super(activity);
        this.activity = activity;
        setOrientation(VERTICAL);
        setBackgroundColor(Color.rgb(12, 17, 27));
        setPadding(dp(36), dp(24), dp(28), dp(12));
        LinearLayout header = new LinearLayout(activity);
        header.setGravity(Gravity.CENTER_VERTICAL);
        View icon = new View(activity) {
            private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            @Override protected void onDraw(android.graphics.Canvas canvas) {
                paint.setColor(0xff8cc7ff); paint.setStyle(android.graphics.Paint.Style.STROKE);
                paint.setStrokeWidth(dp(3)); paint.setStrokeCap(android.graphics.Paint.Cap.ROUND);
                canvas.drawCircle(dp(19), getHeight() / 2f - dp(3), dp(10), paint);
                canvas.drawLine(dp(26), getHeight() / 2f + dp(4), dp(35), getHeight() / 2f + dp(13), paint);
            }
        };
        header.addView(icon, new LayoutParams(dp(50), dp(58)));
        android.widget.EditText recognized = new android.widget.EditText(activity);
        queryInput = recognized;
        recognized.setText(query);
        recognized.setTextColor(Color.WHITE);
        recognized.setTextSize(28);
        recognized.setSingleLine(true);
        recognized.setSelectAllOnFocus(true);
        recognized.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        recognized.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        recognized.setContentDescription("Поисковый запрос");
        Runnable submit = () -> {
            String edited = queryText().trim();
            if (edited.isEmpty()) { recognized.setError("Введите запрос"); return; }
            android.view.inputmethod.InputMethodManager keyboard = activity.getSystemService(android.view.inputmethod.InputMethodManager.class);
            if (keyboard != null) keyboard.hideSoftInputFromWindow(recognized.getWindowToken(), 0);
            submitQuery.accept(edited);
        };
        recognized.setOnEditorActionListener((view, action, event) -> {
            if (action == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
                    || event != null && event.getAction() == android.view.KeyEvent.ACTION_UP
                    && event.getKeyCode() == android.view.KeyEvent.KEYCODE_ENTER) { submit.run(); return true; }
            return false;
        });
        recognized.setTypeface(null, Typeface.BOLD);
        recognized.setMaxLines(2);
        header.addView(recognized, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
        header.addView(action("Найти", submit), new LayoutParams(dp(90), dp(44)));
        TextView back = action("Закрыть", activity::finish);
        header.addView(back, new LayoutParams(dp(100), dp(44)));
        addView(header);
        TextView hint = text("Фильмы и сериалы", 14, 0xff91a0b7);
        hint.setPadding(dp(50), 0, 0, dp(18));
        addView(hint);
        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        LinearLayout rows = new LinearLayout(activity);
        rows.setOrientation(VERTICAL);
        scroll.addView(rows);
        addView(scroll, new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1));
        back.requestFocus();
        final int[] firstResultRow = {Integer.MAX_VALUE};
        if (apps.isEmpty()) {
            rows.addView(text("Нет приложений с доступным внешним поиском", 20, 0xffb4c0d3));
        }
        for (int rowIndex = 0; rowIndex < apps.size(); rowIndex++) {
            MediaVoiceApps.App app = apps.get(rowIndex);
            final int rowOrder = rowIndex;
            LinearLayout section = new LinearLayout(activity);
            section.setOrientation(VERTICAL);
            section.setPadding(0, 0, 0, dp(22));
            TextView name = text(app.label, 19, Color.WHITE);
            name.setTypeface(null, Typeface.BOLD);
            section.addView(name);
            TextView status = text("Поиск…", 14, 0xff91a0b7);
            status.setPadding(0, dp(4), 0, dp(8));
            LinearLayout loadingRow = new LinearLayout(activity);
            loadingRow.setGravity(Gravity.CENTER_VERTICAL);
            android.widget.ProgressBar loading = spinner();
            loading.setContentDescription("Загрузка результатов " + app.label);
            loadingRow.addView(loading, new LayoutParams(dp(26), dp(26)));
            loadingRow.addView(status);
            section.addView(loadingRow);
            HorizontalScrollView horizontal = new HorizontalScrollView(activity);
            horizontal.setHorizontalScrollBarEnabled(false);
            horizontal.setClipToPadding(false);
            LinearLayout cards = new LinearLayout(activity);
            cards.setPadding(dp(4), dp(5), dp(4), dp(5));
            horizontal.addView(cards);
            section.addView(horizontal);
            rows.addView(section);
            if (!MediaSearch.canReadResults(activity, app)) {
                loading.setVisibility(View.GONE);
                status.setText("Результаты доступны внутри приложения");
                cards.addView(action("Искать в " + app.label, () -> openSearch.accept(app)),
                        new LayoutParams(dp(270), dp(64)));
                continue;
            }
            CancellationSignal signal = new CancellationSignal();
            signals.add(signal);
            Runnable expired = () -> {
                if (closed || signal.isCanceled()) return;
                signal.cancel();
                loading.setVisibility(View.GONE);
                status.setText("Поиск не ответил. Повторите запрос позже");
            };
            // Bound queued rows too: a provider that ignores cancellation cannot leave them loading forever.
            main.postDelayed(expired, 18000);
            searches.submit(() -> {
                if (closed || signal.isCanceled()) return;
                try {
                    List<MediaSearch.Hit> hits = MediaSearch.find(activity, app, query, signal);
                    main.post(() -> {
                        main.removeCallbacks(expired);
                        if (closed || signal.isCanceled()) return;
                        loading.setVisibility(View.GONE);
                        status.setText(hits.isEmpty() ? "Ничего не найдено" : "Найдено: " + hits.size());
                        for (MediaSearch.Hit hit : hits) {
                            View card = card(hit, app.label);
                            card.setId(View.generateViewId());
                            LayoutParams lp = new LayoutParams(dp(148), dp(254));
                            lp.setMargins(0, 0, dp(14), 0);
                            cards.addView(card, lp);
                            if (rowOrder < firstResultRow[0]) {
                                firstResultRow[0] = rowOrder;
                                back.setNextFocusDownId(card.getId());
                            }
                        }
                        // Earlier rows may complete later. Keep the user's selected card visible.
                        main.post(() -> {
                            View focused = findFocus();
                            if (focused != null && focused != back) focused.requestRectangleOnScreen(
                                    new android.graphics.Rect(0, 0, focused.getWidth(), focused.getHeight()), true);
                        });
                    });
                } catch (RuntimeException unavailable) {
                    main.post(() -> {
                        main.removeCallbacks(expired);
                        if (!closed && !signal.isCanceled()) { loading.setVisibility(View.GONE); status.setText("Поиск сейчас недоступен"); }
                    });
                }
            });
        }
    }

    private View card(MediaSearch.Hit hit, String appName) {
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(VERTICAL);
        card.setPadding(dp(5), dp(5), dp(5), dp(5));
        card.setFocusable(true);
        card.setClickable(true);
        card.setContentDescription(hit.label + ", " + appName);
        decorate(card);
        android.widget.FrameLayout cover = new android.widget.FrameLayout(activity);
        TextView placeholder = text(hit.title, 19, 0xff9cacc4);
        placeholder.setGravity(Gravity.CENTER);
        placeholder.setMaxLines(4);
        placeholder.setPadding(dp(8), dp(8), dp(8), dp(8));
        cover.addView(placeholder, new android.widget.FrameLayout.LayoutParams(-1, -1));
        ImageView image = new ImageView(activity);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        cover.addView(image, new android.widget.FrameLayout.LayoutParams(-1, -1));
        card.addView(cover, new LayoutParams(-1, dp(184)));
        TextView title = text(hit.title, 14, Color.WHITE);
        title.setPadding(dp(3), dp(6), dp(3), 0);
        title.setMaxLines(2);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        card.addView(title);
        if (hit.description != null) {
            TextView description = text(hit.description, 11, 0xffa4b0c2);
            description.setSingleLine();
            description.setEllipsize(android.text.TextUtils.TruncateAt.END);
            card.addView(description);
        }
        card.setOnKeyListener((v,key,event)->{
            if(key!=android.view.KeyEvent.KEYCODE_DPAD_CENTER&&key!=android.view.KeyEvent.KEYCODE_ENTER&&key!=android.view.KeyEvent.KEYCODE_NUMPAD_ENTER&&key!=android.view.KeyEvent.KEYCODE_BUTTON_A)return false;
            if(event.getAction()==android.view.KeyEvent.ACTION_UP&&!event.isCanceled())v.performClick();
            return true;
        });
        card.setOnClickListener(v -> {
            try {
                // Keep the launch in the actual user click; an animation callback is too late on some TV firmwares.
                SearchNavigation.open(activity, hit.intent);
                activity.overridePendingTransition(0,0);
            }
            catch (RuntimeException unavailable) { Toast.makeText(activity, "Не удалось открыть в " + appName, Toast.LENGTH_SHORT).show(); }
        });
        if (hit.poster != null && !hit.poster.isEmpty()) {
            android.widget.ProgressBar loading = spinner();
            loading.setContentDescription("Загрузка постера " + hit.title);
            cover.addView(loading, new android.widget.FrameLayout.LayoutParams(dp(36), dp(36), Gravity.CENTER));
            loadPoster(image, hit.poster, loading);
        }
        return card;
    }

    private void loadPoster(ImageView view, String address, View loading) {
        Bitmap cached = posters.get(address);
        if (cached != null) { view.setImageBitmap(cached); loading.setVisibility(View.GONE); return; }
        images.submit(() -> {
            if (closed) return;
            HttpURLConnection connection = null;
            try {
                Uri uri = Uri.parse(address);
                InputStream stream;
                if ("https".equals(uri.getScheme()) && uri.getUserInfo() == null) {
                    connection = (HttpURLConnection) new URL(address).openConnection();
                    connection.setConnectTimeout(4000);
                    connection.setReadTimeout(4000);
                    connection.setInstanceFollowRedirects(false);
                    int code = connection.getResponseCode();
                    if (code != 200) {
                        android.util.Log.d("MoviePosters", uri.getHost() + ": HTTP " + code);
                        return;
                    }
                    stream = connection.getInputStream();
                } else if ("content".equals(uri.getScheme()) || "android.resource".equals(uri.getScheme())) {
                    stream = activity.getContentResolver().openInputStream(uri);
                } else return;
                if (stream == null) return;
                byte[] bytes;
                try (InputStream input = stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                    byte[] chunk = new byte[8192];
                    int count;
                    while ((count = input.read(chunk)) != -1) {
                        if (closed || output.size() + count > 4 * 1024 * 1024) return;
                        output.write(chunk, 0, count);
                    }
                    bytes = output.toByteArray();
                }
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
                if (options.outWidth <= 0 || options.outHeight <= 0 ||
                        (long) options.outWidth * options.outHeight > 48000000) return;
                options.inSampleSize = 1;
                while (options.outWidth / options.inSampleSize > 400 || options.outHeight / options.inSampleSize > 600)
                    options.inSampleSize *= 2;
                options.inJustDecodeBounds = false;
                Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
                if (bitmap == null || closed) return;
                posters.put(address, bitmap);
                main.post(() -> { if (!closed) view.setImageBitmap(bitmap); });
            } catch (Exception unavailable) {
                android.util.Log.d("MoviePosters", Uri.parse(address).getHost() + ": " + unavailable.getClass().getSimpleName());
            }
            finally {
                if (connection != null) connection.disconnect();
                main.post(() -> { if (!closed) loading.setVisibility(View.GONE); });
            }
        });
    }

    private android.widget.ProgressBar spinner() {
        android.widget.ProgressBar view = new android.widget.ProgressBar(activity, null, android.R.attr.progressBarStyleSmall);
        view.setIndeterminate(true);
        view.setFocusable(false);
        return view;
    }

    private TextView text(String value, int size, int color) {
        TextView text = new TextView(activity);
        text.setText(value); text.setTextSize(size); text.setTextColor(color);
        return text;
    }
    private TextView action(String label, Runnable click) {
        TextView view = text(label, 16, Color.WHITE);
        view.setGravity(Gravity.CENTER);
        view.setFocusable(true); view.setClickable(true);
        decorate(view);
        view.setOnClickListener(v -> click.run());
        return view;
    }
    private void decorate(View view) {
        view.setBackground(background(false));
        view.setOnFocusChangeListener((v, focused) -> v.setBackground(background(focused)));
    }
    private GradientDrawable background(boolean focused) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(focused ? 0xff263d58 : 0xff192332);
        shape.setCornerRadius(dp(12));
        shape.setStroke(dp(focused ? 3 : 1), focused ? 0xff8cc7ff : 0xff29374b);
        return shape;
    }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    void close() {
        closed = true;
        for (CancellationSignal signal : signals) signal.cancel();
        main.removeCallbacksAndMessages(null);
        searches.shutdownNow(); images.shutdownNow(); posters.evictAll();
    }
}
