package ru.pda.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;

/** Uses the installed speech UI; never installs a remote key filter. */
public final class VoiceLaunchActivity extends Activity {
    private final android.os.Handler timeout = new android.os.Handler(android.os.Looper.getMainLooper());
    private android.app.AlertDialog activeDialog;
    private int speechRequestCode = 6000;
    private boolean awaitingSpeech;
    private TvMicrophoneInput tvMicrophone;
    private String activeQuery;
    private String activeTarget;
    private MovieSearchView movieSearch;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) {
            speechRequestCode = state.getInt("speech_request", 6000);
            awaitingSpeech = state.getBoolean("awaiting_speech", false);
            String query = state.getString("search_query");
            if (query != null) { search(query, state.getString("search_target", "")); return; }
            if (awaitingSpeech) return;
        }
        acceptIntent(getIntent());
    }

    private void acceptIntent(Intent intent) {
        if (intent.getBooleanExtra(SearchReturn.EXTRA, false)) {
            android.content.SharedPreferences saved = getSharedPreferences("search_return", 0);
            String query = saved.getString("query", "");
            if (!query.isEmpty()) { search(query, saved.getString("target", "")); return; }
        }
        String command = intent.getStringExtra("command");
        if (command != null) {
            ArrayList<String> phrases = new ArrayList<>();
            phrases.add(command);
            route(phrases);
            return;
        }
        try {
            if (getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE).getInt("voice_microphone", 1) == 0
                    && AssistantChoice.isLauncher(this)) {
                android.widget.TextView listening = new android.widget.TextView(this);
                listening.setText("Слушаю микрофон ТВ…");listening.setTextSize(24);
                listening.setGravity(android.view.Gravity.CENTER);setContentView(listening);
                tvMicrophone = new TvMicrophoneInput();
                tvMicrophone.start(this, phrases -> { tvMicrophone=null; route(phrases); }, this::fail);
                return;
            }
            if (!intent.getBooleanExtra("remote_microphone", false) && AssistantChoice.isLauncher(this)) {
                new AlertDialog.Builder(this).setTitle("Микрофон пульта")
                        .setMessage("Нажмите кнопку микрофона на пульте и произнесите команду")
                        .setPositiveButton(android.R.string.ok, (d,w)->finish())
                        .setOnCancelListener(d->finish()).show();
                return;
            }
            awaitingSpeech = true;
            startActivityForResult(new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .setPackage("com.google.android.tts")
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
                    .putExtra(RecognizerIntent.EXTRA_PROMPT, "Скажите: открой Виму или найди фильм в Нум")
                    .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5), ++speechRequestCode);
        } catch (RuntimeException unavailable) { fail("Распознавание речи недоступно"); }
    }

    static String normalize(String text) {
        return text.toLowerCase(Locale.ROOT).replace('ё', 'е')
                .replaceFirst("^(открой|открыть|запусти|запустить)\\s+", "")
                .replaceFirst("^приложение\\s+", "").replaceAll("[^\\p{L}\\p{N}]", "");
    }

    static String alias(String name) {
        MediaVoiceApps.App app = MediaVoiceApps.byName(name);
        if (app != null) return app.pkg;
        return name.matches("торр?серв|torrserve") ? "ru.yourok.torrserve" : "";
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if(request==SiteSearch.REQUEST)return;
        // A previous speech window can report cancellation after another command replaced it.
        if (request != speechRequestCode || !awaitingSpeech) return;
        awaitingSpeech = false;
        if (result != RESULT_OK || data == null) { finish(); return; }
        ArrayList<String> words = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
        if (words == null || words.isEmpty()) { fail("Речь не распознана"); return; }
        route(words);
    }

    private void openAlice(String query){
        android.net.Uri address=android.net.Uri.parse(VoiceAliceCommand.url(query));
        for(String pkg:new String[]{"com.yandex.browser.tv","com.yandex.browser"}){
            Intent open=new Intent(Intent.ACTION_VIEW,address).addCategory(Intent.CATEGORY_BROWSABLE).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if(open.resolveActivity(getPackageManager())==null)continue;
            try{startActivity(open);finish();return;}
            catch(RuntimeException unavailable){android.util.Log.w("VoiceAlice","Yandex Browser launch failed",unavailable);}
        }
        fail("Яндекс Браузер не установлен или не поддерживает открытие Алисы");
    }

    private void route(ArrayList<String> words) {
        String aliceQuery=VoiceAliceCommand.parse(words.get(0));
        if(aliceQuery!=null){openAlice(aliceQuery);return;}
        switch (VoiceTvCommand.parse(words.get(0))) {
            case OFF:
                if (!SleepTimer.fireNow(this)) fail(getString(R.string.sleep_failed));
                else finish();
                return;
            case ON:
                if (android.os.Build.VERSION.SDK_INT >= 27) {
                    setShowWhenLocked(true);
                    setTurnScreenOn(true);
                } else {
                    getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                            | android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
                }
                // Let this window resume with turnScreenOn before handing focus back to Home.
                timeout.postDelayed(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    finish();
                }, 500);
                return;
            case SETTINGS:
                activeDialog = new AlertDialog.Builder(this).setTitle("Настройки")
                        .setItems(new String[]{"Настройки лаунчера", "Настройки системы"}, (dialog, which) -> {
                            try {
                                startActivity(which == 0 ? new Intent(this, LauncherSettingsActivity.class)
                                        : SystemSettings.intent(this, android.provider.Settings.ACTION_SETTINGS));
                                finish();
                            } catch (RuntimeException unavailable) { fail(getString(R.string.not_available)); }
                        }).setOnCancelListener(dialog -> finish()).show();
                return;
            case TIMER:
                int[] minutes = {0, 1, 15, 30, 45, 60, 90};
                activeDialog = new AlertDialog.Builder(this).setTitle("Таймер сна")
                        .setItems(new String[]{"Выключен", "1 минута", "15 минут", "30 минут", "45 минут", "60 минут", "90 минут"},
                                (dialog, which) -> {
                                    if (minutes[which] == 0) SleepTimer.cancel(this);
                                    else SleepTimer.arm(this, minutes[which]);
                                    Toast.makeText(this, minutes[which] == 0 ? "Таймер выключен"
                                            : "Таймер включён: " + minutes[which] + " мин.", Toast.LENGTH_LONG).show();
                                    finish();
                                }).setOnCancelListener(dialog -> finish()).show();
                return;
            default: break;
        }
        Integer timerMinutes = VoiceTimerCommand.minutes(words.get(0));
        if (timerMinutes != null) {
            if (timerMinutes == 0) SleepTimer.cancel(this);
            else SleepTimer.arm(this, timerMinutes);
            Toast.makeText(this, timerMinutes == 0 ? "Таймер выключен"
                    : "Таймер включён: " + timerMinutes + " мин.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        VoiceSearchCommand command = VoiceSearchCommand.parse(words.get(0));
        if (command != null) {
            // A lower-ranked transcript may spell the spoken app name correctly.
            for (String phrase : words) {
                VoiceSearchCommand alternative = VoiceSearchCommand.parse(phrase);
                if (alternative != null && !alternative.target.isEmpty()) {
                    command = alternative;
                    break;
                }
            }
            search(command.query, command.target);
            return;
        }
        LinkedHashMap<String, ResolveInfo> apps = new LinkedHashMap<>();
        for (String category : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            for (ResolveInfo app : getPackageManager().queryIntentActivities(
                    new Intent(Intent.ACTION_MAIN).addCategory(category), 0)) {
                apps.put(app.activityInfo.packageName, app);
            }
        }
        LinkedHashMap<String, ResolveInfo> matches = new LinkedHashMap<>();
        for (String phrase : words) {
            String name = normalize(phrase);
            for (ResolveInfo app : apps.values()) {
                String mappedPackage = alias(name);
                if (!mappedPackage.isEmpty() ? app.activityInfo.packageName.equals(mappedPackage)
                        : normalize(app.loadLabel(getPackageManager()).toString()).equals(name)) {
                    matches.put(app.activityInfo.packageName, app);
                }
            }
        }
        if (matches.isEmpty()) { fail("Приложение не найдено: " + words.get(0)); return; }
        ResolveInfo[] found = matches.values().toArray(new ResolveInfo[0]);
        if (found.length == 1) { launch(found[0]); return; }
        String[] labels = new String[found.length];
        for (int i = 0; i < found.length; i++) labels[i] = found[i].loadLabel(getPackageManager()).toString();
        activeDialog = new AlertDialog.Builder(this).setTitle("Скажите: открой Виму или найди фильм в Нум")
                .setItems(labels, (dialog, which) -> launch(found[which]))
                .setOnCancelListener(dialog -> finish()).show();
    }

    private void search(String query, String target) {
        SearchReturn.remember(this, query, target);
        if (movieSearch != null) movieSearch.close();
        activeQuery = query;
        activeTarget = target;
        java.util.List<MediaVoiceApps.App> apps = MediaSearch.availableApps(this);
        // Movie catalogues before general video sites; results remain grouped by app.
        apps.sort(java.util.Comparator.comparingInt(app ->
                "youtube".equals(app.mode) || "deeplink".equals(app.mode) ? 1 : 0));
        if (!target.isEmpty()) {
            java.util.List<MediaVoiceApps.App> filtered = new ArrayList<>();
            for (MediaVoiceApps.App app : apps) if (app.id.equals(target)) filtered.add(app);
            if (filtered.isEmpty()) {
                MediaVoiceApps.App requested = MediaVoiceApps.byId(target);
                if (requested != null) filtered.add(requested);
            }
            apps = filtered;
        }
        movieSearch = new MovieSearchView(this, query, apps, app -> searchInApp(query, app.id), edited -> search(edited, activeTarget));
        setContentView(movieSearch);
    }

    @Override protected void onPause(){super.onPause();}
    @Override protected void onResume(){super.onResume();SearchReturn.clear(this);}

    private void searchInApp(String query, String target) {
        MediaVoiceApps.App app = MediaVoiceApps.byId(target);
        if (app == null) return;
        if(SiteSearch.supports(app)){SiteSearch.open(this,app,query);return;}
        android.net.Uri uri;
        if ("deeplink".equals(app.mode)) {
            uri = android.net.Uri.parse("vk".equals(app.id)
                    ? "vkvideo://vk.com/search" : "rutube://rutube.ru/search")
                    .buildUpon().appendQueryParameter("query", query).build();
        } else if ("youtube".equals(app.mode)) {
            uri = android.net.Uri.parse("https://www.youtube.com/results")
                    .buildUpon().appendQueryParameter("search_query", query).build();
        } else {
            manualSearch(app, query);
            return;
        }
        try {
            SearchNavigation.open(this, new Intent(Intent.ACTION_VIEW, uri).setPackage(app.pkg));
        } catch (RuntimeException unavailable) {
            Toast.makeText(this, "Поиск " + app.label + " недоступен", Toast.LENGTH_LONG).show();
        }
    }

    private void manualSearch(MediaVoiceApps.App app, String query) {
        activeDialog = new AlertDialog.Builder(this).setTitle(app.label + ": " + query)
                .setMessage("Здесь запрос нужно ввести вручную. Открыть приложение?")
                .setPositiveButton("Открыть", (dialog, which) -> {
                    try {
                        Intent intent = "reshala".equals(app.mode)
                                ? new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("reshala://open")
                                        .buildUpon().appendQueryParameter("screen", "search").build()).setPackage(app.pkg)
                                : getPackageManager().getLeanbackLaunchIntentForPackage(app.pkg);
                        if (intent == null) intent = getPackageManager().getLaunchIntentForPackage(app.pkg);
                        if (intent == null) { fail("Приложение не установлено"); return; }
                        SearchNavigation.open(this, intent);
                    } catch (RuntimeException unavailable) { fail("Не удалось открыть " + app.label); }
                }).setNegativeButton("Назад", null).show();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent.getBooleanExtra(SearchReturn.EXTRA, false) && movieSearch != null) { SearchReturn.clear(this); return; }
        if (awaitingSpeech && intent.getStringExtra("command") == null) return;
        setIntent(intent);
        timeout.removeCallbacksAndMessages(null);
        if (movieSearch != null) { movieSearch.close(); movieSearch = null; }
        if(tvMicrophone!=null){tvMicrophone.close();tvMicrophone=null;}
        awaitingSpeech = false;
        activeQuery = activeTarget = null;
        if (activeDialog != null) { activeDialog.dismiss(); activeDialog = null; }
        acceptIntent(intent);
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putInt("speech_request", speechRequestCode);
        state.putBoolean("awaiting_speech", awaitingSpeech);
        if (activeQuery != null) {
            state.putString("search_query", movieSearch == null ? activeQuery : movieSearch.queryText());
            state.putString("search_target", activeTarget);
        }
    }

    @Override protected void onStop() {
        if(tvMicrophone!=null){tvMicrophone.close();tvMicrophone=null;if(!isChangingConfigurations())finish();}
        super.onStop();
    }
    @Override protected void onDestroy() {
        timeout.removeCallbacksAndMessages(null);
        if (movieSearch != null) { movieSearch.close(); movieSearch = null; }
        if (tvMicrophone != null) tvMicrophone.close();
        if (activeDialog != null) { activeDialog.dismiss(); activeDialog = null; }
        super.onDestroy();
    }

    private void launch(ResolveInfo app) {
        try { startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setClassName(app.activityInfo.packageName, app.activityInfo.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); finish(); }
        catch (RuntimeException unavailable) { fail("Не удалось открыть приложение"); }
    }

    private void fail(String message) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); finish(); }
}
