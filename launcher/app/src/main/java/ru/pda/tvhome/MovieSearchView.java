package ru.pda.tvhome;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
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
    static final float HEADER_PAD_DP = 2f;   // the rubber contour around a header button
    static final float CARD_PAD_DP = 3f;     // the rubber contour around a card
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
    private final Consumer<MediaVoiceApps.App> openSearch;
    private final Runnable voiceSearch;
    private final SearchFocusFrame focusFrame;
    /** Cards of the previous search: Back from an app shows the same results screen again. */
    private final List<SearchMemory.Card> stored;
    private final List<SearchMemory.Card> saved = new ArrayList<>();

    String queryText() { return queryInput.getText().toString(); }

    MovieSearchView(Activity activity, String query, List<MediaVoiceApps.App> apps,
                    Consumer<MediaVoiceApps.App> openSearch, Consumer<String> submitQuery) {
        this(activity, query, apps, openSearch, submitQuery, List.of(), () -> {});
    }

    MovieSearchView(Activity activity, String query, List<MediaVoiceApps.App> apps,
                    Consumer<MediaVoiceApps.App> openSearch, Consumer<String> submitQuery,
                    List<SearchMemory.Card> stored, Runnable voiceSearch) {
        super(activity);
        this.activity = activity;
        this.openSearch = openSearch;
        this.voiceSearch = voiceSearch;
        this.stored = stored;
        saved.addAll(stored);
        setOrientation(VERTICAL);
        setBackgroundColor(Color.rgb(12, 17, 27));
        setPadding(dp(36), dp(24), dp(28), dp(12));
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(VERTICAL);
        // Reserve the contour's 3dp outset, including at the rightmost header action.
        content.setPadding(dp(4), dp(4), dp(4), dp(4));
        focusFrame = new SearchFocusFrame(activity, content);
        focusFrame.getChildAt(0).setLayoutParams(new android.widget.FrameLayout.LayoutParams(-1, -1));
        addView(focusFrame, new LayoutParams(-1, 0, 1));
        LinearLayout header = new LinearLayout(activity);
        header.setGravity(Gravity.CENTER_VERTICAL);
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
        // The default field background is a light plate: white text on it is unreadable.
        recognized.setBackground(null);
        Runnable submit = () -> {
            String edited = queryText().trim();
            if (edited.isEmpty()) { recognized.setError("Введите запрос"); return; }
            android.view.inputmethod.InputMethodManager keyboard = activity.getSystemService(android.view.inputmethod.InputMethodManager.class);
            if (keyboard != null) keyboard.hideSoftInputFromWindow(recognized.getWindowToken(), 0);
            android.util.Log.w("tvsearch","submit "+edited);
            submitQuery.accept(edited);
        };
        recognized.setOnEditorActionListener((view, action, event) -> {
            if (action == 9 /* EditorInfo.IME_ACTION_SEARCH */
                    || event != null && event.getAction() == android.view.KeyEvent.ACTION_UP
                    && event.getKeyCode() == android.view.KeyEvent.KEYCODE_ENTER) { submit.run(); return true; }
            return false;
        });
        // The remote's OK is delivered to the focused view. A text field only submits through the IME action,
        // so OK on the field itself did nothing; the key is handled here as well.
        recognized.setOnKeyListener((view, keyCode, event) -> {
            if (isOkKey(keyCode) && event.getAction() == android.view.KeyEvent.ACTION_UP) { submit.run(); return true; }
            return false;
        });
        // Focus on the field opens the keyboard: on this TV nothing can be typed without it.
        recognized.setOnFocusChangeListener((view, focused) -> {
            if (!focused) return;
            android.view.inputmethod.InputMethodManager keyboard =
                    activity.getSystemService(android.view.inputmethod.InputMethodManager.class);
            if (keyboard == null) { android.util.Log.w("tvsearch", "no keyboard service on this device"); return; }
            boolean shown = keyboard.showSoftInput(view, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
            if (!shown) shown = keyboard.showSoftInput(view, android.view.inputmethod.InputMethodManager.SHOW_FORCED);
            android.util.Log.w("tvsearch", "keyboard on field focus: " + shown);
        });
        recognized.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        recognized.setMaxLines(2);
        header.addView(recognized, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
        // The header action is the microphone: OK on it goes back to listening instead of submitting text.
        // The name stays `find` because the focus chain from the header to the first card uses it.
        View find = iconAction("Голосовой поиск", R.drawable.ic_microphone, voiceSearch);
        header.addView(find, new LayoutParams(dp(48), dp(48)));
        View back = iconAction("Закрыть поиск", R.drawable.ic_search_exit, activity::finish);
        LayoutParams backParams = new LayoutParams(dp(48), dp(48));
        backParams.leftMargin = dp(12);
        header.addView(back, backParams);
        content.addView(header);
        // A thin accent rule between the query and the results, so the header reads as its own block.
        View rule = new View(activity);
        GradientDrawable ruleShape = new GradientDrawable();
        ruleShape.setColor(0x406EC1FF);
        ruleShape.setCornerRadius(dp(1));
        rule.setBackground(ruleShape);
        content.addView(rule, new LayoutParams(LayoutParams.MATCH_PARENT, dp(2)));
        TextView hint = text("Фильмы и сериалы", 14, 0xff91a0b7);
        hint.setPadding(0, dp(12), 0, dp(18));
        content.addView(hint);
        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        LinearLayout rows = new LinearLayout(activity);
        rows.setOrientation(VERTICAL);
        scroll.addView(rows);
        content.addView(scroll, new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1));
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
            TextView name = text(app.label, 19, 0xFF8FCEFF);
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
            horizontal.addOnLayoutChangeListener((v, l, t, r, b, oldL, oldT, oldR, oldB) -> {
                if (r - l != oldR - oldL) fitCards(horizontal, cards);
            });
            section.addView(horizontal);
            rows.addView(section);
            List<SearchMemory.Card> remembered = SearchMemory.of(stored, app.id);
            if (!remembered.isEmpty()) {
                // The cards are already known: show them, without asking the provider again.
                loading.setVisibility(View.GONE);
                status.setText("Восстановлено из памяти · " + remembered.size());
                for (SearchMemory.Card item : remembered) {
                    View card = card(hitOf(item), app.label);
                    card.setId(View.generateViewId());
                    LayoutParams lp = new LayoutParams(dp(148), dp(254));
                    lp.setMargins(0, 0, dp(14), 0);
                    cards.addView(card, lp);
                    if (rowOrder < firstResultRow[0]) {
                        firstResultRow[0] = rowOrder;
                        back.setNextFocusDownId(card.getId());
                        find.setNextFocusDownId(card.getId());
                        recognized.setNextFocusDownId(card.getId());
                    }
                }
                addEntry(app, cards);
                fitCards(horizontal, cards);
                continue;
            }
            if (!MediaSearch.canReadResults(activity, app)) {
                loading.setVisibility(View.GONE);
                status.setText("Результаты доступны внутри приложения");
                addEntry(app, cards);
                continue;
            }
            CancellationSignal signal = new CancellationSignal();
            signals.add(signal);
            Runnable expired = () -> {
                if (closed || signal.isCanceled()) return;
                signal.cancel();
                loading.setVisibility(View.GONE);
                status.setText("Поиск не ответил. Повторите запрос позже");
                addEntry(app, cards);
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
                        if (hits.isEmpty()) addEntry(app, cards);
                        for (MediaSearch.Hit hit : hits) {
                            saved.add(new SearchMemory.Card(app.id, hit.title, hit.description, hit.poster,
                                    hit.intent.getAction(),
                                    hit.intent.getComponent() == null ? "" : hit.intent.getComponent().flattenToString(),
                                    hit.intent.getDataString() == null ? "" : hit.intent.getDataString()));
                            View card = card(hit, app.label);
                            card.setId(View.generateViewId());
                            LayoutParams lp = new LayoutParams(dp(148), dp(254));
                            lp.setMargins(0, 0, dp(14), 0);
                            cards.addView(card, lp);
                            if (rowOrder < firstResultRow[0]) {
                                firstResultRow[0] = rowOrder;
                                back.setNextFocusDownId(card.getId());
                                find.setNextFocusDownId(card.getId());
                                recognized.setNextFocusDownId(card.getId());
                            }
                        }
                        SearchReturn.remember(activity, query, saved);
                        fitCards(horizontal, cards);
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
                        if (!closed && !signal.isCanceled()) {
                            loading.setVisibility(View.GONE);
                            status.setText("Поиск сейчас недоступен");
                            addEntry(app, cards);
                        }
                    });
                }
            });
        }
    }

    /** Whole slots at the viewport edge; the next poster starts outside it, not halfway through a tile. */
    private void fitCards(HorizontalScrollView horizontal, LinearLayout cards) {
        int width = horizontal.getWidth();
        if (width <= 0) return;
        int columns = Math.max(1, width / dp(162));
        for (int i = 0; i < cards.getChildCount(); i++) {
            View card = cards.getChildAt(i);
            LayoutParams lp = (LayoutParams) card.getLayoutParams();
            int slotStart = Math.round((i % columns) * width / (float) columns);
            int slotEnd = Math.round((i % columns + 1) * width / (float) columns);
            int cardWidth = Math.max(1, slotEnd - slotStart - dp(14));
            if (lp.width != cardWidth) {
                lp.width = cardWidth;
                card.setLayoutParams(lp);
            }
        }
    }

    /** Keys that mean OK on this remote: DPAD_CENTER(23), ENTER(66), NUMPAD_ENTER(90), BUTTON_A(98). The numbers are the framework constants, so the rule stays testable on plain JVM. */
    static boolean isOkKey(int key) { return key == 23 || key == 66 || key == 90 || key == 98; }

    private View card(MediaSearch.Hit hit, String appName) {
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(VERTICAL);
        card.setPadding(dp(5), dp(5), dp(5), dp(5));
        card.setFocusable(true);
        card.setClickable(true);
        card.setContentDescription(hit.label + ", " + appName);
        decorate(card);
        android.widget.FrameLayout cover = new android.widget.FrameLayout(activity);
        // The cover is clipped to its own rounded outline, so the poster reads as part of the plate,
        // not as a square picture glued onto a rounded card.
        cover.setClipToOutline(true);
        cover.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override public void getOutline(View view, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(8));
            }
        });
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
            if(!isOkKey(key)){
                // Arrows navigate; anything else that arrives on a card is logged, because what the remote
                // really sends for OK can only be decided on the device.
                if(key!=android.view.KeyEvent.KEYCODE_DPAD_LEFT&&key!=android.view.KeyEvent.KEYCODE_DPAD_RIGHT
                        &&key!=android.view.KeyEvent.KEYCODE_DPAD_UP&&key!=android.view.KeyEvent.KEYCODE_DPAD_DOWN)
                    android.util.Log.w("tvkeys","card key "+key+" scan "+event.getScanCode());
                return false;
            }
            if(event.getAction()==android.view.KeyEvent.ACTION_UP&&!event.isCanceled())v.performClick();
            return true;
        });
        card.setOnClickListener(v -> {
            try {
                android.util.Log.w("tvsearch", "card click " + appName + " " + hit.intent);
                // Keep the launch in the actual user click; an animation callback is too late on some TV firmwares.
                SearchNavigation.open(activity, hit.intent);
                activity.overridePendingTransition(0,0);
            }
            catch (RuntimeException unavailable) {
                android.util.Log.w("tvsearch", "card open failed: " + unavailable);
                Toast.makeText(activity, "Не удалось открыть в " + appName, Toast.LENGTH_SHORT).show();
            }
        });
        if (hit.poster != null && !hit.poster.isEmpty()) {
            android.widget.ProgressBar loading = spinner();
            loading.setContentDescription("Загрузка постера " + hit.title);
            cover.addView(loading, new android.widget.FrameLayout.LayoutParams(dp(36), dp(36), Gravity.CENTER));
            loadPoster(image, hit.poster, loading);
        }
        return card;
    }

    /** A stored card, rebuilt as a hit so the same card view and the same click are used. */
    private MediaSearch.Hit hitOf(SearchMemory.Card item) {
        Intent intent = new Intent(item.action().isEmpty() ? Intent.ACTION_VIEW : item.action());
        if (!item.data().isEmpty()) intent.setData(Uri.parse(item.data()));
        if (!item.component().isEmpty()) {
            ComponentName name = ComponentName.unflattenFromString(item.component());
            if (name != null) intent.setComponent(name);
        }
        return new MediaSearch.Hit(item.title(), item.description(), item.poster(),
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
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
        text.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        return text;
    }
    private View iconAction(String label, int drawable, Runnable click) {
        ImageView view = new ImageView(activity);
        view.setImageResource(drawable);
        view.setPadding(dp(12), dp(12), dp(12), dp(12));
        android.content.res.ColorStateList tint = new android.content.res.ColorStateList(
                new int[][] {new int[] {android.R.attr.state_focused}, new int[0]},
                new int[] {Color.WHITE, 0xffb1b3b8});
        view.setImageTintList(tint);
        view.setContentDescription(label);
        view.setFocusable(true); view.setClickable(true);
        // At density 2.0 this is 4 px tighter than the contour around a card.
        focusFrame.track(view, 24, HEADER_PAD_DP);
        view.setOnClickListener(v -> click.run());
        return view;
    }
    /** Entry point for a row that cannot show cards: search inside the app, or open it if it has no search. */
    private void addEntry(MediaVoiceApps.App app, LinearLayout cards) {
        if (closed || cards.getChildCount() > 0) return;
        boolean inAppSearch = AppSearchEntry.base(app.id) != null || SiteSearch.supports(app)
                || (MediaSearch.searchActivity(activity, app.pkg) != null
                    && AppSearchEntry.searchActivityIsUsable(app.id));
        cards.addView(action(AppSearchEntry.buttonLabel(app, inAppSearch), () -> openSearch.accept(app)),
                new LayoutParams(dp(270), dp(64)));
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
        view.setBackground(background());
        focusFrame.track(view, 12, CARD_PAD_DP);
    }
    private GradientDrawable background() {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(0xff161c27);
        shape.setCornerRadius(dp(12));
        shape.setStroke(Math.max(1, dp(.5f)), 0x24ffffff);
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
