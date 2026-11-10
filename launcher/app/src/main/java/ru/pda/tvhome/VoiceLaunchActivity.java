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
    private int speechRetries;
    private int captureRetries;
    private int listenSeconds = 10;
    private boolean pipeAttempted;
    private VoiceListeningView listening;
    private boolean ownSession; private int ownSessionMode = 1;
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
            if (!query.isEmpty()) { showStored(query); return; }
        }
        String command = intent.getStringExtra("command");
        if (command != null) {
            ArrayList<String> phrases = new ArrayList<>();
            phrases.add(command);
            route(phrases);
            return;
        }
        try {
            android.content.SharedPreferences settings = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
            int mode = settings.getInt("voice_microphone", 1);
            int seconds = TvMicrophoneInput.captureSeconds(settings.getInt("voice_timeout", 10));
            if (AssistantChoice.isLauncher(this)) {
                android.util.Log.w("tvvoice","path own session mode "+mode+" limit "+seconds+"s");
                showListening(mode, seconds);
                return;
            }
            android.util.Log.w("tvvoice","path engine window");
            listen();
        } catch (RuntimeException 
unavailable) { fail("Распознавание речи недоступно"); }
    }

    private void startOwnSession(int mode, int viewSeconds, VoiceListeningView view) {
        ownSession = true; ownSessionMode = mode;
        tvMicrophone = new TvMicrophoneInput();
        tvMicrophone.onPartial(view::setHeard);
        tvMicrophone.onLevel(value -> view.setLevel((float) value));
        tvMicrophone.onState(view::setState);
        // The pipe is only worth trying for a chosen remote mic; otherwise the session takes the engine's
        // own microphone and the listening time is ours, not the engine's short silence window.
        if (TvMicrophoneInput.pipeIsWorthTrying(mode)) tvMicrophone.start(this, this::phrases, this::captureError, viewSeconds, mode);
        else tvMicrophone.startPlain(this, this::phrases, this::captureError, viewSeconds);
    }

    private void listen() {
        awaitingSpeech = true;
        try {
            startActivityForResult(new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .setPackage("com.google.android.tts")
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
                    .putExtra(RecognizerIntent.EXTRA_PROMPT, "Скажите: открой Виму или найди фильм в Нум")
                    // The engine stops after a short silence and reports nothing at all. These give the spoken
                    // phrase time; the engine may still ignore them, so the timing is checked on the device.
                    .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 3000)
                    .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 4000)
                    .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 4000)
                    .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5), ++speechRequestCode);
        } catch (RuntimeException unavailable) { fail("Распознавание речи недоступно"); }
    }

    /** Phrases decide: the speech window can report cancellation and still carry the transcript. */
    static boolean acceptsSpokenResult(java.util.List<String> words) { return words != null && !words.isEmpty(); }

    /** Once our own session is the chosen path, the Google window must not appear after our input. */
    static boolean engineWindowAllowed(boolean ownSession) { return !ownSession; }

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
        ArrayList<String> words = data == null ? null : data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
        android.util.Log.w("tvvoice","window result "+result+" phrases "+(words==null?0:words.size())+" retries "+speechRetries);
        // Phrases decide: the window can report cancellation and still carry the transcript.
        if (!acceptsSpokenResult(words)) {
            Toast.makeText(this, "Речь не распознана. Повторите запрос", Toast.LENGTH_LONG).show();
            // Окно остаётся: движок уже живой, повтор не платит за холодный старт. Две попытки, не цикл.
            if (speechRetries < 2) {
                speechRetries++;
                // The first attempt pays for the cold engine, so the second waits until it is live.
                if (speechRetries == 1) listen();
                else timeout.postDelayed(() -> { if (!isFinishing() && !isDestroyed()) listen(); }, 1500);
            } else finish();
            return;
        }
        speechRetries = 0;
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
        VoiceActionCommand.Parsed action = VoiceActionCommand.parse(words.get(0));
        if (action.action() != VoiceActionCommand.Action.NONE) { act(action); return; }
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
        if (matches.isEmpty()) {
            // A spoken title is usually not an app name. Without a match it is a search query, not a failure.
            search(words.get(0), "");
            return;
        }
        ResolveInfo[] found = matches.values().toArray(new ResolveInfo[0]);
        if (found.length == 1) { launch(found[0]); return; }
        String[] labels = new String[found.length];
        for (int i = 0; i < found.length; i++) labels[i] = found[i].loadLabel(getPackageManager()).toString();
        activeDialog = new AlertDialog.Builder(this).setTitle("Скажите: открой Виму или найди фильм в Нум")
                .setItems(labels, (dialog, which) -> launch(found[which]))
                .setOnCancelListener(dialog -> finish()).show();
    }

    private void search(String query, String target) {
        android.util.Log.w("tvsearch","query "+query+" target "+(target.isEmpty()?"all":target));
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
        movieSearch = new MovieSearchView(this, query, apps, app -> searchInApp(query, app.id),
                edited -> search(edited, activeTarget), java.util.List.of(), this::resumeListening);
        setContentView(movieSearch);
    }

    /** Back to listening from the results screen: the microphone in the header asks for a new phrase. */
    private void resumeListening() {
        android.util.Log.w("tvvoice", "header microphone: back to listening");
        if (!AssistantChoice.isLauncher(this)) { listen(); return; }
        android.content.SharedPreferences settings = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
        int mode = settings.getInt("voice_microphone", 1);
        showListening(mode, TvMicrophoneInput.captureSeconds(settings.getInt("voice_timeout", 10)));
    }

    /** Our own listening screen, created once and reused when the header microphone asks for a new phrase. */
    private void showListening(int mode, int seconds) {
        listenSeconds = seconds;
        pipeAttempted = TvMicrophoneInput.pipeIsWorthTrying(mode);
        if (listening == null) listening = new VoiceListeningView(this, seconds, this::finish);
        setContentView(listening);
        if (tvMicrophone != null) tvMicrophone.close();
        listening.stopPulse();
        listening.startPulse();
        startOwnSession(mode, seconds, listening);
    }

    /** The results screen rebuilt from the stored cards, so nothing is asked from the providers again. */
    private void showStored(String query) {
        java.util.List<SearchMemory.Card> cards = SearchReturn.cards(this);
        java.util.List<MediaVoiceApps.App> apps = new ArrayList<>();
        for (String id : SearchMemory.apps(cards)) {
            MediaVoiceApps.App app = MediaVoiceApps.byId(id);
            if (app != null) apps.add(app);
        }
        if (apps.isEmpty()) { search(query, ""); return; }
        android.util.Log.w("tvsearch", "results restored from memory: " + cards.size());
        activeQuery = query;
        activeTarget = getSharedPreferences("search_return", 0).getString("target", "");
        movieSearch = new MovieSearchView(this, query, apps, app -> searchInApp(query, app.id),
                edited -> search(edited, activeTarget), cards, this::resumeListening);
        setContentView(movieSearch);
    }

    /** What the launcher can do by itself: volume, mute, the picture menu, clock, weather, the input panel. */
    private void act(VoiceActionCommand.Parsed action) {
        android.util.Log.w("tvvoice", "action " + action.action() + " " + action.value());
        switch (action.action()) {
            case VOLUME_UP: case VOLUME_DOWN: case VOLUME_SET: case MUTE_ON: case MUTE_OFF:
                volume(action); return;
            case PICTURE_SETTINGS:
                Toast.makeText(this, "Яркость картинки — в меню изображения", Toast.LENGTH_SHORT).show();
                try {
                    startActivity(SystemSettings.intent(this, android.provider.Settings.ACTION_DISPLAY_SETTINGS));
                    finish();
                } catch (RuntimeException unavailable) { fail(getString(R.string.not_available)); }
                return;
            case CLOCK:
                try {
                    startActivity(new Intent(this, ClockActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    finish();
                } catch (RuntimeException unavailable) { fail(getString(R.string.not_available)); }
                return;
            case WEATHER:
                WeatherSnapshot.Row row = WeatherSnapshot.read(this);
                if (row == null) { fail("Погода: карточка ничего не прислала"); return; }
                speak(WeatherSnapshot.speech(row)); return;
            case INPUTS:
                try {
                    startActivity(new Intent("com.android.tv.action.VIEW_INPUTS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    finish();
                } catch (RuntimeException unavailable) { fail(getString(R.string.not_available)); }
                return;
            default: return;
        }
    }

    private void volume(VoiceActionCommand.Parsed action) {
        android.media.AudioManager audio = getSystemService(android.media.AudioManager.class);
        if (audio == null) { fail(getString(R.string.not_available)); return; }
        int stream = android.media.AudioManager.STREAM_MUSIC;
        if (action.action() == VoiceActionCommand.Action.VOLUME_SET) {
            int index = Math.round(action.value() / 100f * audio.getStreamMaxVolume(stream));
            audio.setStreamVolume(stream, index, android.media.AudioManager.FLAG_SHOW_UI);
        } else {
            int adjust = action.action() == VoiceActionCommand.Action.VOLUME_UP
                    ? android.media.AudioManager.ADJUST_RAISE
                    : action.action() == VoiceActionCommand.Action.VOLUME_DOWN
                    ? android.media.AudioManager.ADJUST_LOWER
                    : action.action() == VoiceActionCommand.Action.MUTE_ON
                    ? android.media.AudioManager.ADJUST_MUTE : android.media.AudioManager.ADJUST_UNMUTE;
            audio.adjustStreamVolume(stream, adjust, android.media.AudioManager.FLAG_SHOW_UI);
        }
        finish();
    }

    private android.speech.tts.TextToSpeech tts;

    /** The weather line is spoken; where the TV has no voice for it, the same line shows as a message. */
    private void speak(String text) {
        if (tts == null) {
            tts = new android.speech.tts.TextToSpeech(this, status -> {
                if (status != android.speech.tts.TextToSpeech.SUCCESS) { Toast.makeText(this, text, Toast.LENGTH_LONG).show(); finish(); return; }
                int language = tts.setLanguage(new Locale("ru"));
                if (language == android.speech.tts.TextToSpeech.LANG_MISSING_DATA
                        || language == android.speech.tts.TextToSpeech.LANG_NOT_SUPPORTED) {
                    Toast.makeText(this, text, Toast.LENGTH_LONG).show(); finish(); return;
                }
                tts.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null);
                timeout.postDelayed(this::finish, 5000);
            });
            return;
        }
        tts.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null);
        timeout.postDelayed(this::finish, 5000);
    }

    @Override protected void onPause(){super.onPause();}
    @Override protected void onResume(){super.onResume();SearchReturn.clear(this);}

    private void searchInApp(String query, String target) {
        MediaVoiceApps.App app = MediaVoiceApps.byId(target);
        if (app == null) return;
        String entry = AppSearchEntry.searchUrl(app.id, query);
        if (entry != null) {
            try {
                SearchNavigation.open(this, new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(entry)).setPackage(app.pkg));
                return;
            } catch (RuntimeException unavailable) { /* fall through to the site search page */ }
        }
        if (SiteSearch.supports(app)) { SiteSearch.open(this, app, query); return; }
        String activity = MediaSearch.searchActivity(this, app.pkg);
        if (activity == null || !AppSearchEntry.searchActivityIsUsable(app.id)) { manualSearch(app, query); return; }
        try {
            SearchNavigation.open(this, new Intent(Intent.ACTION_SEARCH)
                    .setClassName(app.pkg, activity)
                    .putExtra(android.app.SearchManager.QUERY, query));
        } catch (RuntimeException unavailable) { manualSearch(app, query); }
    }

    private void manualSearch(MediaVoiceApps.App app, String query) {
        activeDialog = new AlertDialog.Builder(this).setTitle(app.label + ": " + query)
                .setMessage("Здесь запрос нужно ввести вручную. Открыть приложение?")
                .setPositiveButton("Открыть", (dialog, which) -> {
                    try {
                        Intent intent = getPackageManager().getLeanbackLaunchIntentForPackage(app.pkg);
                        if (intent == null) intent = getPackageManager().getLaunchIntentForPackage(app.pkg);
                        if (intent == null) { fail("Приложение не установлено"); return; }
                        SearchNavigation.open(this, intent);
                    } catch (RuntimeException unavailable) { fail("Не удалось открыть " + app.label); }
                }).setNegativeButton("Назад", null).show();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent.getBooleanExtra(SearchReturn.EXTRA, false) && movieSearch != null) { SearchReturn.clear(this); return; }
        // Повторное обращение во время прослушивания — новая попытка, а не игнорируемый дубль.
        boolean retry = awaitingSpeech;
        setIntent(intent);
        speechRetries = 0;
        timeout.removeCallbacksAndMessages(null);
        if (movieSearch != null) { movieSearch.close(); movieSearch = null; }
        if(tvMicrophone!=null){tvMicrophone.close();tvMicrophone=null;}
        awaitingSpeech = false;
        activeQuery = activeTarget = null;
        if (activeDialog != null) { activeDialog.dismiss(); activeDialog = null; }
        if (retry && intent.getStringExtra("command") == null) { listen(); return; }
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
        if (listening != null) listening.stopPulse();
        if (activeDialog != null) { activeDialog.dismiss(); activeDialog = null; }
        if (tts != null) { tts.shutdown(); tts = null; }
        super.onDestroy();
    }

    private void launch(ResolveInfo app) {
        try { startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setClassName(app.activityInfo.packageName, app.activityInfo.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); finish(); }
        catch (RuntimeException unavailable) { fail("Не удалось открыть приложение"); }
    }

    /** The engine hands the words over only at the end of the session, so they are shown before the results. */
    private void phrases(ArrayList<String> words) {
        tvMicrophone = null;
        if (listening == null) { route(words); return; }
        listening.setHeard(String.join(" ", words));
        timeout.postDelayed(() -> { if (!isFinishing() && !isDestroyed()) route(words); }, 800);
    }

    /** The engine can refuse the pipe source (ERROR_CLIENT on this firmware): plain session, then the window. */
    private void captureError(String message) {
        android.util.Log.w("tvvoice","capture error: "+message+" retries "+captureRetries);
        if (!engineWindowAllowed(ownSession)) {
            // The chosen path is our own session, so the Google window must not appear after our input:
            // one more attempt with the same session, then the message.
            if (captureRetries == 0) {
                captureRetries = 1;
                android.util.Log.w("tvvoice","retry own session");
                if (listening != null) listening.setState("Слушаю снова");
                startOwnSession(ownSessionMode, listenSeconds, listening);
            } else fail(message);
            return;
        }
        if (captureRetries == 0 && pipeAttempted) {
            captureRetries = 1;
            if (tvMicrophone == null) tvMicrophone = new TvMicrophoneInput();
            android.util.Log.w("tvvoice","fallback to plain session (engine mic)");
            tvMicrophone.startPlain(this, this::phrases, this::captureError, listenSeconds);
            return;
        }
        if (captureRetries < 2) { captureRetries = 2; android.util.Log.w("tvvoice","fallback to engine window"); listen(); return; }
        fail(message);
    }

    private void fail(String message) { android.util.Log.w("tvvoice","fail: "+message); Toast.makeText(this, message, Toast.LENGTH_LONG).show(); finish(); }
}
