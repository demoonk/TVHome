package ru.pda.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.ContentValues;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationManager;
import android.net.Uri;
import android.text.InputType;
import android.widget.EditText;
import android.content.SharedPreferences;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.view.KeyEvent;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Locale;

/** Remote-friendly settings owned by the launcher; system settings stay one row away. */
public final class LauncherSettingsActivity extends Activity {
    @Override public void onBackPressed() { if(!SettingsColumns.back(this))super.onBackPressed(); }
    static final String EXTRA_REORDER = "com.gathin.tvhome.extra.REORDER_FROM_SETTINGS";
    private SharedPreferences choices;
    private WallpaperSettings backgroundSettings;
    void pickBackgroundFile(){backgroundSettings.file();}
    void pickBackgroundFolder(){backgroundSettings.folder();}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(backgroundSettings!=null)backgroundSettings.result(request,result,data);}
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){super.onRequestPermissionsResult(request,permissions,results);if(request==WallpaperSettings.MEDIA_PERMISSION){boolean granted=results.length>0;for(int result:results)granted&=result==android.content.pm.PackageManager.PERMISSION_GRANTED;if(granted)backgroundSettings.mediaReady();else Toast.makeText(this,"Для выбора папки нужен доступ к изображениям и видео",Toast.LENGTH_LONG).show();}}

    private boolean changingAssistant;
    /** The system tells apps when Home was pressed; this screen steps aside instead of holding the TV. */
    private final BroadcastReceiver homeKey = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!Intent.ACTION_CLOSE_SYSTEM_DIALOGS.equals(intent.getAction())) return;
            String reason = intent.getStringExtra("reason");
            if ("homekey".equals(reason) || "recentapps".equals(reason)) goHome();
        }
    };
    private final TextView[] columnChoices = new TextView[4];
    private final TextView[] rowChoices = new TextView[3];
    private static final int[] DOCK_OPACITIES = {100, 70, 50, 30};
    private TextView wallpaperChoice;
    private TextView animationsChoice;
    private int columns;
    private int rowsBelowDock;
    private int dockOpacity;
    private boolean animations;
    private boolean advancedEffects;
    /** Minutes of idle Home before the dimmer starts (dim_delay); 0 is off. */
    private static final int[] DIM_DELAYS = {0, 1, 3, 5, 10, 15};
    private int dimDelayMin;
    /** How dark the dimmer goes, in percent (dim_level); the fade itself is fixed at three seconds. */
    private static final int[] DIM_LEVELS = {30, 50, 80, 90};
    private int dimLevelPct;
    private TextView dimDelayChoice, dimRampChoice, weatherCityChoice;
    /** The clock over other apps: five presets, four corners, five sizes. */
    private static final int[] CLOCK_SIZES = {8, 10, 12, 14, 16};
    private boolean clockOn;
    private int clockAlpha, clockSize;
    private TextView clockColorChoice, clockCornerChoice, clockSizeChoice, clockPositionChoice;
    /** Font families the TV resolves: the Android generics plus the display faces it ships. */
    private static final String[] CLOCK_FONTS = {"sans-serif", "sans-serif-light", "sans-serif-thin",
            "sans-serif-condensed", "serif", "monospace", "cursive", "casual"};
    private static final String[] CLOCK_FONT_NAMES = {"Sans", "Sans Light", "Sans Thin", "Condensed",
            "Serif", "Mono", "Cursive", "Casual"};
    private int clockFont;
    private TextView clockFontChoice, oledShiftChoice, headerPlateChoice, plateOpacityChoice, plateBlurChoice;
    private boolean oledShift;
    /** Header plates: the backing under the clock row and under every pill, for bright wallpapers. */
    private boolean headerPlate;
    private int plateOpacity, plateBlur;
    private TextView tileCropChoice;
    private int tileCropPercent;
    /** The buttons offered for the picture menu, in cycle order; -1 is "off". */
    /** The button choices for the picture menu: "" is off, "key:" a keycode, "scan:" a raw scancode. */
    private static final String[] PICTURE_KEYS = {"off", "key:288", "key:165", "key:278", "scan:375",
            "key:83", "key:172", "scan:159"};
    private int pictureKeyIndex;
    /** Ink box of the clock at each size and font, measured once: the placement limits come from it. */
    private final Map<String, android.graphics.Rect> clockInk = new HashMap<>();
    /** The preview shown while the clock is being placed, and the offsets being chosen. */
    private boolean placingClock;
    private int placingX, placingY;

    @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag")
    @Override
    protected void onResume() {
        super.onResume();
        refreshAssistantChoice();
        IntentFilter homeFilter = new IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
        if (Build.VERSION.SDK_INT >= 33) {
            // Android 13+ wants the exposure flag on a runtime receiver, even for a system broadcast.
            registerReceiver(homeKey, homeFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(homeKey, homeFilter);
        }
    }

    @Override
    protected void onPause() {
        try {
            unregisterReceiver(homeKey);
        } catch (IllegalArgumentException notRegistered) {
            // Not registered (paused twice): nothing to undo.
        }
        super.onPause();
    }

    /** Home closed this screen: back to the launcher itself, not to whatever sat behind it. */
    private void goHome() {
        startActivity(new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
        finish();
    }
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_launcher_settings);
        choices = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
        backgroundSettings=new WallpaperSettings(this);
        Config config = Config.load(this);
        View reorderChoice = findViewById(R.id.reorderChoice);
        reorderChoice.setVisibility(config.launcherSettingsButton ? View.VISIBLE : View.GONE);
        reorderChoice.setOnClickListener(v -> {
            setResult(RESULT_OK, new Intent().putExtra(EXTRA_REORDER, true));
            finish();
        });
        columns = config.columns;
        rowsBelowDock = config.rowsBelowDock;
        dockOpacity = config.dockOpacity;
        animations = config.animations;
        advancedEffects = config.animations && config.advancedEffects;

        int[] ids = {R.id.columns4, R.id.columns5, R.id.columns6, R.id.columns7};
        for (int i = 0; i < ids.length; i++) {
            final int value = i + 4;
            columnChoices[i] = findViewById(ids[i]);
            columnChoices[i].setOnClickListener(v -> {
                if (columns == value) return;
                columns = value;
                choices.edit().putInt("columns", value).apply();
                changed();
                refresh();
            });
        }
        int[] rowIds = {R.id.rows1, R.id.rows2, R.id.rows3};
        for (int i = 0; i < rowIds.length; i++) {
            final int value = i + 1;
            rowChoices[i] = findViewById(rowIds[i]);
            rowChoices[i].setOnClickListener(v -> {
                if (rowsBelowDock == value) return;
                rowsBelowDock = value;
                choices.edit().putInt("visible_rows", value).apply();
                changed();
                refresh();
            });
        }
        // Keep the numeric choices increasing in the Hebrew reading direction. Reorder the
        // actual views so D-pad focus follows their positions without changing the saved values.
        if (getResources().getBoolean(R.bool.rtl_app_grid)) {
            reverseRow(columnChoices);
            reverseRow(rowChoices);

        }
        wallpaperChoice = findViewById(R.id.wallpaperChoice);
        wallpaperChoice.setOnClickListener(v -> showWallpaperPicker());
        tileCropPercent = TileCrop.chosen(choices.getInt(Config.TILE_CROP_KEY, 0));
        tileCropChoice = findViewById(R.id.tileCropChoice);
        tileCropChoice.setOnClickListener(v -> chooseSlider("Обрезка изображений плиток", 5,
                tileCropPercent, value -> {
                    tileCropPercent = value;
                    choices.edit().putInt(Config.TILE_CROP_KEY, value).apply();
                    changed();
                    refresh();
                }, ""));
        animationsChoice = findViewById(R.id.animationsChoice);
        animationsChoice.setOnClickListener(v -> {
            animations = !animations;
            if (!animations) advancedEffects = false;
            choices.edit().putBoolean("animations", animations)
                    .putBoolean("advanced_effects", advancedEffects).apply();
            changed();
            refresh();
        });
        // The dimmer: when it starts and how long it takes. Any key on Home wakes it and restarts both.
        dimDelayMin = choices.getInt(Config.DIM_DELAY_KEY, 0);
        dimLevelPct = choices.getInt(Config.DIM_LEVEL_KEY, 50);
        dimDelayChoice = findViewById(R.id.dimDelayChoice);
        dimDelayChoice.setOnClickListener(v -> chooseValues("Затемнение через", DIM_DELAYS,
                dimDelayMin, value -> { dimDelayMin = value;
                    choices.edit().putInt(Config.DIM_DELAY_KEY, value).apply(); changed(); refresh(); }, " мин."));
        dimRampChoice = findViewById(R.id.dimRampChoice);
        dimRampChoice.setOnClickListener(v -> chooseValues("Сила затемнения", DIM_LEVELS,
                dimLevelPct, value -> { dimLevelPct = value;
                    choices.edit().putInt(Config.DIM_LEVEL_KEY, value).apply(); changed(); refresh(); }, "%"));
        // The clock drawn over other apps: on/off, opacity, size and a D-pad placement.
        clockOn = choices.getBoolean(Config.CLOCK_ON_KEY, false);
        clockAlpha = choices.getInt(Config.CLOCK_ALPHA_KEY, 40);
        clockSize = choices.getInt(Config.CLOCK_SIZE_KEY, 10);
        clockFont = Math.max(0, indexOf(CLOCK_FONTS, choices.getString(Config.CLOCK_FONT_KEY, CLOCK_FONTS[0])));
        clockColorChoice = findViewById(R.id.clockColorChoice);
        clockColorChoice.setOnClickListener(v -> {
            clockOn = !clockOn;
            choices.edit().putBoolean(Config.CLOCK_ON_KEY, clockOn).apply();
            notifyClockChanged();
            refresh();
        });
        clockCornerChoice = findViewById(R.id.clockCornerChoice);
        clockCornerChoice.setOnClickListener(v -> chooseValues("Непрозрачность часов",
                new int[]{10,20,30,40,50,60,70,80,90,100}, clockAlpha, value -> {
                    clockAlpha = value; choices.edit().putInt(Config.CLOCK_ALPHA_KEY, value).apply();
                    notifyClockChanged(); refresh(); }, "%"));
        clockSizeChoice = findViewById(R.id.clockSizeChoice);
        clockSizeChoice.setOnClickListener(v -> chooseValues("Размер часов", CLOCK_SIZES, clockSize, value -> {
            clockSize = value; choices.edit().putInt(Config.CLOCK_SIZE_KEY, value).apply();
            notifyClockChanged(); refresh(); }, " sp"));
        clockFontChoice = findViewById(R.id.clockFontChoice);
        clockFontChoice.setOnClickListener(v -> chooseList("Шрифт часов", CLOCK_FONT_NAMES, clockFont, which -> {
            clockFont = which; choices.edit().putString(Config.CLOCK_FONT_KEY, CLOCK_FONTS[which]).apply();
            clockInk.clear(); notifyClockChanged(); refresh(); }));
        oledShift = choices.getBoolean(Config.OLED_SHIFT_KEY, false);
        String pictureKey = choices.getString(Config.PICTURE_KEY_KEY, "off");
        for (int i = 0; i < PICTURE_KEYS.length; i++) if (PICTURE_KEYS[i].equals(pictureKey)) pictureKeyIndex = i;
        oledShiftChoice = findViewById(R.id.oledShiftChoice);
        oledShiftChoice.setOnClickListener(v -> {
            oledShift = !oledShift;
            choices.edit().putBoolean(Config.OLED_SHIFT_KEY, oledShift).apply();
            notifyClockChanged();
            refresh();
        });
        // Header plates: on/off and the two values the plate is drawn with. They take effect the way every
        // other choice does: changed() bumps the revision and the home screen rebuilds on returning to it.
        headerPlate = choices.getBoolean(Config.HEADER_PLATE_KEY, getResources().getBoolean(R.bool.header_plate));
        plateOpacity = Config.chosenPlateOpacity(choices.getInt(Config.HEADER_PLATE_OPACITY_KEY,
                getResources().getInteger(R.integer.header_plate_opacity)), 7);
        plateBlur = Config.chosenPlateBlur(choices.getInt(Config.HEADER_PLATE_BLUR_KEY,
                getResources().getInteger(R.integer.header_plate_blur)), 5);
        headerPlateChoice = findViewById(R.id.headerPlateChoice);
        headerPlateChoice.setOnClickListener(v -> {
            headerPlate = !headerPlate;
            choices.edit().putBoolean(Config.HEADER_PLATE_KEY, headerPlate).apply();
            changed();
            refresh();
        });
        plateOpacityChoice = findViewById(R.id.plateOpacityChoice);
        plateOpacityChoice.setOnClickListener(v -> chooseSlider("Прозрачность плашек",
                100, 100 - plateOpacity, value -> {
                    plateOpacity = 100 - value;
                    choices.edit().putInt(Config.HEADER_PLATE_OPACITY_KEY, plateOpacity).apply();
                    changed();
                    refresh();
                }, "%"));
        plateBlurChoice = findViewById(R.id.plateBlurChoice);
        plateBlurChoice.setOnClickListener(v -> chooseSlider("Размытие плашек",
                10, plateBlur, value -> {
                    plateBlur = value;
                    choices.edit().putInt(Config.HEADER_PLATE_BLUR_KEY, value).apply();
                    changed();
                    refresh();
                }, ""));
        clockPositionChoice = findViewById(R.id.clockPositionChoice);
        clockPositionChoice.setOnClickListener(v -> startPlacingClock());        // The weather line's place: typed with the TV keyboard, or taken from the location services.
        // Either way it is the Weather card that fetches; the launcher only hands the place over.
        weatherCityChoice = findViewById(R.id.weatherCityChoice);
        weatherCityChoice.setOnClickListener(v -> askWeatherCity());
        findViewById(R.id.weatherGpsChoice).setOnClickListener(v -> weatherFromGps());

        findViewById(R.id.tvSettingsChoice).setOnClickListener(v ->
                open(SystemSettings.intent(this, Settings.ACTION_SETTINGS)));
        findViewById(R.id.assistantChoice).setOnClickListener(v -> switchAssistant());
        findViewById(R.id.microphoneChoice).setOnClickListener(v -> {
            if (!AssistantChoice.isLauncher(this)) return;
            int mode=choices.getInt("voice_microphone",1);
            choices.edit().putInt("voice_microphone",1-mode).apply();refreshAssistantChoice();
        });
        ((TextView)findViewById(R.id.sleepSettingsChoice)).setText("Время до сна: "+(SleepTimer.selectedMinutes(this)==0?"Не засыпать":SleepTimer.selectedMinutes(this)+" мин."));
        findViewById(R.id.sleepSettingsChoice).setOnClickListener(v -> {
            int[] times={0,1,15,30,45,60,90};
            chooseValues("Таймер сна",times,SleepTimer.selectedMinutes(this),minutes->{
                if(minutes==0)SleepTimer.cancel(this);else SleepTimer.arm(this,minutes);
                Toast.makeText(this,minutes==0?"Таймер выключен":"Таймер включён: "+minutes+" мин.",Toast.LENGTH_LONG).show();
                ((TextView)findViewById(R.id.sleepSettingsChoice)).setText("Время до сна: "+(minutes==0?"Не засыпать":minutes+" мин."));
            }," мин.");
        });
        refreshAssistantChoice();
        refresh();
        if (config.launcherSettingsButton) reorderChoice.requestFocus();
        else columnChoices[Math.max(0, Math.min(columns - 4, 3))].requestFocus();
        SettingsColumns.install(this);
    }

    private void switchAssistant() {
        if (changingAssistant) return;
        boolean launcher = !AssistantChoice.isLauncher(this);
        changingAssistant = true;
        // Keep the focused row available while the async service switch is in progress.
        // changingAssistant already prevents duplicate requests.
        new Thread(() -> {
            boolean success = AssistantChoice.select(getApplicationContext(), launcher);
            runOnUiThread(() -> {
                changingAssistant = false;
                if (isFinishing() || isDestroyed()) return;
                findViewById(R.id.assistantChoice).setEnabled(true);
                Toast.makeText(this, success ? R.string.assistant_switched
                        : R.string.assistant_guard_required, Toast.LENGTH_LONG).show();
                refreshAssistantChoice();
            });
        }, "assistant-choice").start();
    }

    private void chooseList(String title, String[] labels, int current, java.util.function.IntConsumer selected) {
        if (SettingsColumns.showChoices(this,title,labels,current,selected)) return;
        new AlertDialog.Builder(this).setTitle(title).setSingleChoiceItems(labels, current, (dialog, which) -> {
            dialog.dismiss(); selected.accept(which);
        }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private void chooseSlider(String title, int maximum, int current,
            java.util.function.IntConsumer selected, String unit) {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(24 * getResources().getDisplayMetrics().density);
        content.setPadding(padding, padding, padding, padding);
        TextView label = new TextView(this);
        label.setText(current + unit);
        content.addView(label);
        android.widget.SeekBar slider = new android.widget.SeekBar(this);
        slider.setFocusableInTouchMode(true);
        slider.setMax(maximum);
        slider.setProgress(current);
        slider.setKeyProgressIncrement(1);
        content.addView(slider);
        slider.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(android.widget.SeekBar bar, int value, boolean user) {
                label.setText(value + unit);
                if (user) selected.accept(value);
            }
            public void onStartTrackingTouch(android.widget.SeekBar bar) {}
            public void onStopTrackingTouch(android.widget.SeekBar bar) {}
        });
        android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(this).setTitle(title).setView(content)
                .setPositiveButton(android.R.string.ok, null).create();
        dialog.setOnShowListener(shown -> slider.requestFocus());
        dialog.show();
    }

    private void chooseValues(String title, int[] values, int current, java.util.function.IntConsumer selected, String unit) {
        String[] labels = new String[values.length];
        for (int i = 0; i < values.length; i++) labels[i] = values[i] == 0 ? "Выключено" : values[i] + unit;
        chooseList(title, labels, indexOf(values, current), index -> selected.accept(values[index]));
    }

    private void refreshAssistantChoice() {
        TextView mic=findViewById(R.id.microphoneChoice);
        if(mic!=null){boolean enabled=AssistantChoice.isLauncher(this);mic.setEnabled(enabled);
            mic.setText("Микрофон: "+(enabled?(getSharedPreferences(Config.SETTINGS_NAME,MODE_PRIVATE).getInt("voice_microphone",1)==0?"ТВ":"Пульт"):"Доступно с помощником от лаунчера"));}
        TextView row = findViewById(R.id.assistantChoice);
        if (row != null) row.setText(getString(R.string.voice_assistant) + ": "
                + getString(AssistantChoice.isLauncher(this) ? R.string.assistant_launcher : R.string.assistant_system));
        SettingsColumns.refresh(this);
    }

    private void reverseRow(TextView[] views) {
        LinearLayout row = (LinearLayout) views[0].getParent();
        row.removeAllViews();
        for (int i = views.length - 1; i >= 0; i--) row.addView(views[i]);
    }

    private void showWallpaperPicker(){pickBackgroundFile();}

    /**
     * A choice was saved. Home rebuilds when it next resumes because the change count moved, whether these
     * settings are left with Back or with the remote's Home button (which cancels the activity result).
     */
    private void changed() {
        OverlayService.start(this);
        setResult(RESULT_OK);
        choices.edit().putLong(Config.SETTINGS_REVISION_KEY,
                choices.getLong(Config.SETTINGS_REVISION_KEY, 0) + 1).apply();
    }

    private void refresh() {
        boolean clocks=clockOn;
        for(int id:new int[]{R.id.clockCornerChoice,R.id.clockSizeChoice,R.id.clockFontChoice,R.id.clockPositionChoice}){
            View option=findViewById(id);if(option!=null)option.setVisibility(clocks?View.VISIBLE:View.GONE);
        }
        for (int i = 0; i < columnChoices.length; i++) {
            columnChoices[i].setSelected(columns == i + 4);
        }
        for (int i = 0; i < rowChoices.length; i++) {
            rowChoices[i].setSelected(rowsBelowDock == i + 1);
        }
        tileCropChoice.setText("Обрезка изображений плиток: " + tileCropPercent);
        animationsChoice.setText(getString(R.string.animations_state,
                getString(animations ? R.string.on : R.string.off)));
        wallpaperChoice.setText("Обои: "+WallpaperSource.MODES[WallpaperSource.modeIndex(this)]);
        dimDelayChoice.setText(getString(R.string.dim_delay_state, dimDelayMin == 0
                ? getString(R.string.dim_off) : getString(R.string.dim_minutes, dimDelayMin)));
        dimRampChoice.setText(getString(R.string.dim_level_state, dimLevelPct));
        clockColorChoice.setText(getString(R.string.clock_on_state, getString(clockOn ? R.string.clock_on_yes : R.string.clock_on_no)));
        clockCornerChoice.setText(getString(R.string.clock_alpha_state, clockAlpha));
        clockSizeChoice.setText(getString(R.string.clock_size_state, clockSize));
        clockFontChoice.setText(getString(R.string.clock_font_state, CLOCK_FONT_NAMES[clockFont]));
        oledShiftChoice.setText(getString(R.string.oled_shift, getString(oledShift ? R.string.clock_on_yes : R.string.clock_on_no)));
        headerPlateChoice.setText("Плашки шапки: " + getString(headerPlate ? R.string.clock_on_yes : R.string.clock_on_no));
        plateOpacityChoice.setText("Прозрачность плашек: " + (100 - plateOpacity) + "%");
        plateBlurChoice.setText("Размытие плашек: " + plateBlur);
        clockPositionChoice.setText(R.string.clock_position);
        String city = choices.getString("weather_city", null);
        weatherCityChoice.setText(getString(R.string.weather_city_state,
                city == null || city.isEmpty() ? getString(R.string.weather_city_none) : city));
    }

    /** The next value in a fixed list, wrapping: one row taps through the choices in order. */
    private static int indexOf(String[] values, String value) {
        for (int i = 0; i < values.length; i++) if (values[i].equals(value)) return i;
        return 0;
    }

    private static int indexOf(int[] values, int value) {
        for (int i = 0; i < values.length; i++) if (values[i] == value) return i;
        return 0;
    }

    private static int next(int[] values, int current) {
        for (int i = 0; i < values.length; i++) {
            if (values[i] == current) return values[(i + 1) % values.length];
        }
        return values[0];
    }

    /** Type a city; the launcher looks its coordinates up itself, so the card needs no search of its own. */
    /**
     * Places the overlay clock with the remote: a live preview moves with the arrows, and OK, Back or Home
     * fixes the offset and stores it. Home is not delivered to the app, so onPause stores it as well.
     */
    /**
     * Places the overlay clock with the remote. There is no preview of our own any more: the service draws the
     * real clock, and every arrow press moves it at once, so what is being positioned is exactly what will stay.
     */
    private void startPlacingClock() {
        if (placingClock) return;
        placingClock = true;
        float density = getResources().getDisplayMetrics().density;
        int screenW = Math.round(getResources().getDisplayMetrics().widthPixels);
        int screenH = Math.round(getResources().getDisplayMetrics().heightPixels);
        // Integer.MIN_VALUE means "never placed": any real coordinate, negative included, is a position.
        placingX = choices.getInt(Config.CLOCK_X_KEY, Integer.MIN_VALUE);
        placingY = choices.getInt(Config.CLOCK_Y_KEY, Integer.MIN_VALUE);
        if (placingX == Integer.MIN_VALUE || placingY == Integer.MIN_VALUE) {
            placingX = clockMaxX();
            placingY = 6;
        }
        clockPositionChoice.setText(R.string.clock_position_active);
    }

    /** While placing, every arrow belongs to the clock: the activity sees them before any focus does. */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (!placingClock) return super.dispatchKeyEvent(event);
        if (event.getAction() == KeyEvent.ACTION_UP) return true;
        if (event.getAction() != KeyEvent.ACTION_DOWN) return true;
        int code = event.getKeyCode();
        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_HOME) {
            stopPlacingClock();
            return true;
        }
        float density = getResources().getDisplayMetrics().density;
        int screenW = Math.round(getResources().getDisplayMetrics().widthPixels / density);
        int screenH = Math.round(getResources().getDisplayMetrics().heightPixels / density);
        int step = 1;
        if (event.getRepeatCount() > 0) {
            // Progressive: doubles every 6 repeats, capped at 32dp.
            int doublings = event.getRepeatCount() / 6;
            step = Math.min(32, 1 << Math.min(doublings, 5));
        }
        if (code == KeyEvent.KEYCODE_DPAD_LEFT) placingX -= step;
        else if (code == KeyEvent.KEYCODE_DPAD_RIGHT) placingX += step;
        else if (code == KeyEvent.KEYCODE_DPAD_UP) placingY -= step;
        else if (code == KeyEvent.KEYCODE_DPAD_DOWN) placingY += step;
        else return super.dispatchKeyEvent(event);
        // The far bounds follow the font: the right/bottom edge of the digits lands exactly on the screen edge.
        // A little slack past the near edges lets them sit flush there too.
        // Bounds from the measured ink box: every edge of the digits can land exactly on the screen edge.
        placingX = Math.max(clockMinX(), Math.min(clockMaxX(), placingX));
        placingY = Math.max(clockMinY(), Math.min(clockMaxY(), placingY));
        choices.edit().putInt(Config.CLOCK_X_KEY, placingX).putInt(Config.CLOCK_Y_KEY, placingY).apply();
        notifyClockChanged();
        return true;
    }
    /** Stores the chosen spot and turns the placement off. */
    private void stopPlacingClock() {
        if (!placingClock) return;
        placingClock = false;
        choices.edit().putInt(Config.CLOCK_X_KEY, placingX).putInt(Config.CLOCK_Y_KEY, placingY).apply();
        notifyClockChanged();
        clockFontChoice.setText(getString(R.string.clock_font_state, CLOCK_FONT_NAMES[clockFont]));
        oledShiftChoice.setText(getString(R.string.oled_shift, getString(oledShift ? R.string.clock_on_yes : R.string.clock_on_no)));
        clockPositionChoice.setText(R.string.clock_position);
    }
    /**
     * Ink box of the clock digits at the current size and font, measured once and cached. measureText would
     * give the advance width; the ink box is what actually has to touch the screen edge. Two samples cover
     * the extremes: zeros are the widest digits, ones the narrowest.
     */
    private android.graphics.Rect clockInkBox() {
        String key = clockSize + "|" + clockFont;
        android.graphics.Rect cached = clockInk.get(key);
        if (cached != null) return cached;
        android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        paint.setTextSize(clockSize * getResources().getDisplayMetrics().scaledDensity);
        paint.setTypeface(android.graphics.Typeface.create(CLOCK_FONTS[clockFont], android.graphics.Typeface.NORMAL));
        int width = 0, height = 0, left = 0, top = 0;
        for (String sample : new String[] {"00:00", "88:88"}) {
            android.graphics.Rect box = new android.graphics.Rect();
            paint.getTextBounds(sample, 0, sample.length(), box);
            width = Math.max(width, box.width());
            height = Math.max(height, box.height());
            left = Math.min(left, box.left);
            top = Math.min(top, box.top);
        }
        android.graphics.Rect ink = new android.graphics.Rect(left, top, left + width, top + height);
        clockInk.put(key, ink);
        return ink;
    }

    private int clockMinX() {
        return Math.round(-clockInkBox().left / getResources().getDisplayMetrics().density);
    }

    private int clockMaxX() {
        float density = getResources().getDisplayMetrics().density;
        int screenW = Math.round(getResources().getDisplayMetrics().widthPixels / density);
        android.graphics.Rect ink = clockInkBox();
        return Math.round(screenW - ink.width() / density - ink.left / density);
    }

    /**
     * Where the ink's top edge sits inside the overlay view, in dp. The view draws the text with its baseline
     * at -ascent from the top, so the ink starts at -ascent + ink.top, not at ink.top. Without this the clock
     * stopped roughly one font height short of the top edge.
     */
    private float clockInkTopInViewDp() {
        float density = getResources().getDisplayMetrics().density;
        android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        paint.setTextSize(clockSize * getResources().getDisplayMetrics().scaledDensity);
        paint.setTypeface(android.graphics.Typeface.create(CLOCK_FONTS[clockFont], android.graphics.Typeface.NORMAL));
        android.graphics.Paint.FontMetrics metrics = paint.getFontMetrics();
        float baseline = -metrics.ascent;                       // px from the view's top to the baseline
        return (baseline + clockInkBox().top) / density;
    }

    private int clockMinY() {
        return Math.round(-clockInkTopInViewDp());
    }

    private int clockMaxY() {
        float density = getResources().getDisplayMetrics().density;
        int screenH = Math.round(getResources().getDisplayMetrics().heightPixels / density);
        android.graphics.Rect ink = clockInkBox();
        return Math.round(screenH - ink.height() / density - clockInkTopInViewDp());
    }
    /**
     * Adds or removes the key-filtering helper in the enabled accessibility list. Rewriting the list is the one
     * way to be sure the helper really filters or really does not: the filter flag can survive a rebind from the
     * service's metadata, which is what kept the volume keys single-stepping with no button chosen.
     */


    private void notifyClockChanged() {
        OverlayService.start(this);
        sendBroadcast(new Intent(Config.CLOCK_CHANGED_ACTION).setPackage(getPackageName()));
    }

    private void askWeatherCity() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint(R.string.weather_city_hint);
        input.setText(choices.getString("weather_city", ""));
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        new AlertDialog.Builder(this)
                .setTitle(R.string.weather_city_title)
                .setView(input)
                .setPositiveButton(android.R.string.ok,
                        (dialog, which) -> lookUpCity(input.getText().toString().trim()))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
        input.requestFocus();
    }

    private void lookUpCity(final String typed) {
        if (typed.isEmpty()) return;
        Toast.makeText(this, R.string.weather_searching, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String name = null;
            double lat = 0, lon = 0;
            try {
                List<Address> found = new Geocoder(this, Locale.getDefault()).getFromLocationName(typed, 1);
                if (found != null && !found.isEmpty()) {
                    Address a = found.get(0);
                    lat = a.getLatitude();
                    lon = a.getLongitude();
                    name = a.getLocality() != null ? a.getLocality() : typed;
                }
            } catch (Exception offline) {
                // IOException from the geocoder service, or no service at all: the row says so below.
            }
            final String city = name;
            final double latitude = lat, longitude = lon;
            runOnUiThread(() -> {
                if (city == null) {
                    Toast.makeText(this, R.string.weather_not_found, Toast.LENGTH_LONG).show();
                    return;
                }
                setWeatherPlace(city, latitude, longitude);
            });
        }, "weather-city").start();
    }

    /** The TV's own idea of where it is: the last fix any provider has, then a name for it. */
    private void weatherFromGps() {
        LocationManager locations = (LocationManager) getSystemService(LOCATION_SERVICE);
        Location best = null;
        if (locations != null) {
            for (String provider : locations.getProviders(true)) {
                try {
                    Location fix = locations.getLastKnownLocation(provider);
                    if (fix != null && (best == null || fix.getTime() > best.getTime())) best = fix;
                } catch (SecurityException | IllegalArgumentException unreadable) {
                    // A provider we may not read, or one the TV does not have: try the next one.
                }
            }
        }
        if (best == null) {
            Toast.makeText(this, R.string.weather_gps_failed, Toast.LENGTH_LONG).show();
            return;
        }
        final double lat = best.getLatitude(), lon = best.getLongitude();
        Toast.makeText(this, R.string.weather_searching, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String name = null;
            try {
                List<Address> found = new Geocoder(this, Locale.getDefault()).getFromLocation(lat, lon, 1);
                if (found != null && !found.isEmpty()) {
                    Address a = found.get(0);
                    name = a.getLocality() != null ? a.getLocality() : a.getSubAdminArea();
                }
            } catch (Exception offline) {
                // No geocoder: the coordinates themselves name the place then.
            }
            final String city = name == null || name.isEmpty()
                    ? String.format(Locale.US, "%.2f, %.2f", lat, lon) : name;
            runOnUiThread(() -> setWeatherPlace(city, lat, lon));
        }, "weather-gps").start();
    }

    /** Hands the place to the Weather card, which fetches for it; the card's snapshot feeds the header line. */
    private void setWeatherPlace(String name, double lat, double lon) {
        ContentValues values = new ContentValues();
        values.put("name", name);
        values.put("lat", lat);
        values.put("lon", lon);
        try {
            getContentResolver().update(Uri.parse("content://io.github.sumyabazar1.weather.snapshot/place"),
                    values, null, null);
            choices.edit().putString("weather_city", name).apply();
            sendBroadcast(new Intent("io.github.sumyabazar1.weather.action.REFRESH")
                    .setPackage("io.github.sumyabazar1.weather"));
        } catch (Exception missing) {
            Toast.makeText(this, R.string.weather_card_missing, Toast.LENGTH_LONG).show();
            return;
        }
        changed();
        refresh();
    }

    private void open(Intent intent) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_LONG).show();
        }
    }
}
