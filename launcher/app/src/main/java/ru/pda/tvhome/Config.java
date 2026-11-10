package ru.pda.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;


import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Built-in launcher defaults and owner settings. No theme import or external overlay. */
final class Config {
    private static final String TAG = "TvHome.Config";
    static final String SETTINGS_NAME = "launcher_settings";
    /** Overlay clock: colour (ARGB), corner (0 TR, 1 TL, 2 BR, 3 BL) and text size in sp. */
    static final String CLOCK_COLOR_KEY = "clock_color";
    static final String CLOCK_CORNER_KEY = "clock_corner";
    static final String CLOCK_SIZE_KEY = "clock_size";
    static final String CLOCK_ON_KEY = "clock_on";
    static final String CLOCK_ALPHA_KEY = "clock_alpha";
    static final String CLOCK_X_KEY = "clock_x";
    static final String CLOCK_Y_KEY = "clock_y";
    /** Font family of the overlay clock, one of the families the launcher offers. */
    static final String CLOCK_FONT_KEY = "clock_font";
    /** OLED pixel shift: nudge the whole interface, overlay clock included, every five minutes. */
    static final String OLED_SHIFT_KEY = "oled_shift";
    /** Header plates (launcher settings): a dark backing under the clock row and under every header pill,
     *  so the header stays visible on white wallpapers. config.xml's header_plate is the fallback default;
     *  opacity is a percent and blur the soft edge's depth in dp. */
    static final String TILE_CROP_KEY = "tile_crop_percent";
    static final String HEADER_PLATE_KEY = "header_plate";
    static final String HEADER_PLATE_OPACITY_KEY = "header_plate_opacity";
    static final String HEADER_PLATE_BLUR_KEY = "header_plate_blur";
    /** Which button opens the TV's picture/sound menu: "" off, "key:<code>", or "scan:<code>". */
    static final String PICTURE_KEY_KEY = "picture_key";
    static final String CLOCK_CHANGED_ACTION = "ru.pda.tvhome.action.CLOCK_CHANGED";
    static final String ROWS_BELOW_DOCK_KEY = "rows_below_dock";
    static final String WALLPAPER_CHOICE_KEY = "wallpaper_choice";
    static final String DOCK_OPACITY_KEY = "dock_opacity";
    /** Minutes of idle Home before the screen dims (launcher settings); 0 is off. */
    static final String DIM_DELAY_KEY = "dim_delay";
    /** How dark the dimmer draws, percent 0..100 chosen on a slider (launcher settings). */
    static final String DIM_LEVEL_KEY = "dim_level";
    /** OLED dimmer: pure black over a paused wallpaper instead of a translucent scrim (launcher settings). */
    static final String DIM_OLED_KEY = "dim_oled";
    /** How long the dimmer takes to fade out, in seconds (launcher settings): 0 draws the scrim at once. */
    static final String DIM_RAMP_KEY = "dim_ramp";
    /** Small clock over every app, drawn by HomeKeyService (launcher settings; needs the overlay permission). */
    static final String CLOCK_ON_TOP_KEY = "clock_on_top";
    /**
     * Counts the changes made in Launcher settings. Home remembers the count it was built with and rebuilds on
     * resume when it differs, however the settings were left (see MainActivity#onResume).
     */
    static final String SETTINGS_REVISION_KEY = "settings_revision";

    /** True when Launcher settings changed something since Home was built at revision {@code seen}. */
    static boolean settingsChanged(long seen, long current) {
        return seen != current;
    }

    final List<String> dockApps;
    /** Grid apps fill the dock slots dock_apps leaves free (config.xml, fill_dock); on by default, false leaves the slots empty. */
    final boolean fillDock;
    final List<String> hiddenApps;
    /** Packages pre-seeded into the App library's "added" set (config.xml, library_apps); empty by default. */
    final List<String> libraryApps;
    final String liveTvPackage;
    final String liveTvAction;
    final String liveTvInputId;
    /** Package to the action its tile sends instead of opening the start screen (config.xml, launch_actions). */
    final Map<String, String> launchActions;
    /** Package to the name its tile shows instead of the app's label (config.xml, app_labels). */
    final Map<String, String> appLabels;
    /** Package to a bundled banner_* drawable its tile shows instead of the app's art (config.xml, app_banners). */
    final Map<String, String> appBanners;
    final String sourcePanelService;
    final String sourcePanelAction;
    /** One int extra for the source panel, "name=value" (config.xml source_panel_extra); empty for none. */
    final String sourcePanelExtra;
    /** List Coocaa OS sources from com.skyworth.tv in the Inputs menu (config.xml coocaa_sources). */
    final boolean coocaaSources;
    final String inputAction;
    /** Scheme an Inputs row's passthrough URI is sent with instead of content:// (config.xml input_uri_scheme). */
    final String inputUriScheme;
    final int columns;
    final boolean animations;
    final boolean advancedEffects;
    final int rowsBelowDock;
    final boolean shamsiDate;
    /** Name after the greeting ("Good morning, Mr. Trб»‹nh"); empty for the greeting alone. */
    final String greetingName;
    final boolean moduleCards;
    /** Calmer motion for weak GPUs (config.xml, reduced_motion): stiff spring, short fades, no replay. */
    final boolean reducedMotion;
    /** Open the Live TV app on the first Home after the TV starts (config.xml, boot_to_live_tv). */
    final boolean bootToLiveTv;
    /** The focused dock app's name in large type above the dock (config.xml, dock_title). */
    final boolean dockTitle;
    /** The dock as its own row: band, no names, parked low (config.xml dock_row). false: one plain grid. */
    final boolean dockRow;
    /** Inputs, Wi-Fi and Settings folded into one gear button with a dropdown, like Google TV (config.xml top_menu). */
    final boolean topMenu;
    /** Direct launcher-settings header action; off by default (launcher_settings_button). */
    final boolean launcherSettingsButton;
    /** Names under every tile, dimmed until focused (config.xml always_show_names); the dock band grows for them. */
    final boolean alwaysShowNames;
    /** Reorder button in the top bar (config.xml reorder_button); the Settings long-press menu offers it either way. */
    final boolean reorderButton;
    /** Show the Wi-Fi/Ethernet pill in the top bar (config.xml, show_network_pill). */
    final boolean showNetworkPill;
    /** Show the Inputs pill when the TV has inputs to list (config.xml, show_inputs_pill). */
    final boolean showInputsPill;
    /** Wi-Fi and Settings pills as round icon buttons, Inputs keeps its label (config.xml icon_only_buttons). */
    final boolean iconOnlyButtons;
    /** The Inputs pill as a round icon button too (config.xml icon_only_inputs). */
    final boolean iconOnlyInputs;
    /** App library as a round button in the top bar (config.xml library_button); needs app_library. */
    final boolean libraryButton;
    /** With icon_only_buttons, the Wi-Fi pill keeps the network name up to this width in dp (0: icon only). */
    final int wifiLabelWidth;
    /** Decode wallpapers in 32-bit colour instead of RGB_565 (config.xml wallpaper_true_color). */
    final boolean wallpaperTrueColor;
    /** The focused tile and the glass highlight swing past their target before settling (config.xml, focus_overshoot). */
    final boolean focusOvershoot;
    final String vpnApp;
    /** Packages of the round VPN buttons in the top bar, in button order (config.xml vpn_buttons). */
    final List<String> vpnButtons;
    /** Moon button after Settings: menu to dim the screen now or on a timer (config.xml sleep_timer_button). */
    final boolean sleepTimerButton;
    final boolean showHomeSettingsShortcut;
    /** One line under the clock: CPU, free memory, free storage. Off unless config or a theme turns it on. */
    final boolean showStats;
    /** App library pill after the last Home row (config.xml app_library). */
    final boolean appLibrary;
    /** OK directly toggles Home membership in the library (app_library_selection). */
    final boolean appLibrarySelection;
    /** Background colour behind everything, #RRGGBB; black unless a theme says otherwise. */
    final int backgroundColor;
    /** Time-of-day backdrop, 0 to 100 (config.xml, sky_intensity); 0 keeps the plain background. */
    final int skyIntensity;
    /** Remote keycodes that open Settings through HomeKeyService (config.xml, settings_key_keycodes). */
    final int[] settingsKeycodes;
    /** Extra keycodes that open the input switcher in InputKeyService (config.xml input_key_keycodes). */
    final int[] inputKeycodes;
    /** Remote buttons that open an app, "keycode:package" or "KEYCODE_NAME:package" (config.xml app_keys). */
    final List<String> appKeys;
    /** Whether the key services request key filtering (config.xml or theme key_filter); false keeps key
     *  auto-repeat working on firmware whose AccessibilityInputFilter drops repeats (TCL Android 12). */
    final boolean keyFilter;
    /** With keyFilter on, the key services stop filtering while a game controller is connected (gamepad_passthrough). */
    final boolean gamepadPassthrough;
    /** Stock launcher whose home screen is sent back here if the firmware revives it (config.xml stock_home_redirect). */
    final String stockHomeRedirect;
    /** The Settings button opens the quick settings panel instead of the TV's Settings (settings_key_panel). */
    final boolean settingsKeyPanel;
    /** Home puts the remote's accessibility helpers back after a force stop (restore_key_services; ServiceRepair). */
    final boolean restoreKeyServices;
    /** Whether themes may show a wallpaper (config.json, wallpaper). */
    final boolean wallpaperEnabled;
    /** Full-screen picture behind the launcher, or null for the plain colour. */
    final File wallpaper;
    /** Rotating theme images, or the single wallpaper when no collection is configured. */
    final List<File> wallpapers;
    final int wallpaperRotationMinutes;
    final int wallpaperDim;
    /** The dock band's opacity in percent (config.json dock_opacity, 30-100): below 100 the wallpaper shows through. */
    final int dockOpacity;
    /**
     * Playback order for a wallpaper collection: {@code sequential} or {@code shuffle}. Missing or
     * invalid values keep the historical shuffle so existing themes rotate exactly as before.
     */
    final String wallpaperOrder;
    /** Whether the overlay file was present and parsed. */
    final boolean overlayApplied;
    /** Festive skin from a theme (festival, festival_until, greeting, accent_color); {@link Festival#NONE} if none. */
    final Festival festival;
    /** F1 weekends from a theme's race_calendar (see RaceWeekend); empty when the theme has none. */
    final List<RaceWeekend.Race> raceCalendar;
    /** Cinematic top shelf (config.xml, cinematic_home): the focused dock app's artwork behind a larger shelf. */
    final boolean cinematicHome;
    /** The glow in the focused app's colour behind the dock (config.xml, ambient_glow); false keeps the background plain. */
    final boolean ambientGlow;
    /** The focus highlight in the focused app's colour on every tile (config.xml, app_glow), as cinematic_home does for the dock. */
    final boolean appGlow;
    /** The rows fade out under the clock instead of being cut off (config.xml, fade_rows); TopFade. */
    final boolean fadeRows;
    /** Minutes Home may sit untouched before the clock screensaver (config.xml, home_idle_clock_minutes); 0 is off. */
    final int homeIdleClockMinutes;
    /** The dock band as light frosted glass like the pills (config.xml, dock_glass); false keeps the solid band. */
    final boolean dockGlass;
    /** The dock band's padding equals the gap between its tiles (config.xml, dock_even_padding); false keeps the fixed padding. */
    final boolean dockEvenPadding;
    /** The dock position dots under the cinematic shelf's name (config.xml, cinematic_dots); false leaves them out. */
    final boolean cinematicDots;
    /** The cinematic shelf keeps the white focus highlight instead of the app's colour (config.xml, cinematic_white_focus). */
    final boolean cinematicWhiteFocus;
    /** The Wi-Fi pill's icon green while the TV is online (config.xml, wifi_status_color); false keeps it white. */
    final boolean wifiStatusColor;
    /** Opacity of the app's artwork behind the cinematic shelf, 0 to 100 (config.xml, cinematic_art); 50 as before. */
    final int cinematicArt;
    /** Strength of the cinematic shelf's dark scrim, 0 to 100 percent (config.xml, cinematic_scrim); 100 as before. */
    final int cinematicScrim;
    /** A rotating collection's groups by time of day, as file names (config.xml, wallpaper_moods); empty rotates through all of it. */
    final Map<String, Set<String>> wallpaperMoods;
    /** Shape of the glow behind the focused app (config.xml, glow_style): {@link #GLOW_RADIAL} or {@link #GLOW_HORIZON}. */
    final String glowStyle;

    static final String GLOW_RADIAL = "radial", GLOW_HORIZON = "horizon";
    /** Show each rotating wallpaper's name from wallpapers/captions.txt (config.xml / theme wallpaper_captions). */
    final boolean wallpaperCaptions;
    /** Answer VIEW_INPUTS with the Inputs menu (handle_view_inputs); see InputsRequestActivity. */
    final boolean handleViewInputs;
    /** Hold OK on a tile opens a menu (Move, Remove from Home, App info) instead of App info (config.xml / theme tile_menu). */
    final boolean tileMenu;

    private Config(List<String> dockApps, boolean fillDock, List<String> hiddenApps, List<String> libraryApps, String liveTvPackage,
                   String liveTvAction, String liveTvInputId, Map<String, String> launchActions, Map<String, String> appLabels,
                   Map<String, String> appBanners, String sourcePanelService, String sourcePanelAction,
                   String sourcePanelExtra, boolean coocaaSources, String inputAction,
                   String inputUriScheme, int columns, int rowsBelowDock, boolean animations, boolean advancedEffects,
                   boolean shamsiDate, String greetingName, boolean moduleCards, boolean reducedMotion, boolean bootToLiveTv, boolean dockTitle, boolean dockRow, boolean topMenu, boolean launcherSettingsButton, boolean reorderButton, boolean showNetworkPill, boolean showInputsPill, boolean iconOnlyButtons, boolean iconOnlyInputs, boolean libraryButton, int wifiLabelWidth, boolean alwaysShowNames, boolean wallpaperTrueColor, boolean focusOvershoot, String vpnApp,
                   boolean showHomeSettingsShortcut, boolean showStats, boolean appLibrary, boolean appLibrarySelection, int backgroundColor, int skyIntensity,
                   int[] settingsKeycodes, boolean settingsKeyPanel, boolean restoreKeyServices, boolean wallpaperEnabled, File wallpaper, List<File> wallpapers,
                   int wallpaperRotationMinutes, int wallpaperDim, int dockOpacity, String wallpaperOrder, boolean overlayApplied,
                   Festival festival, int[] inputKeycodes, List<String> appKeys, String stockHomeRedirect,
                   List<RaceWeekend.Race> raceCalendar, boolean cinematicHome, boolean ambientGlow, boolean appGlow, boolean fadeRows, int homeIdleClockMinutes,
                   boolean keyFilter, boolean dockGlass, String glowStyle, boolean wallpaperCaptions,
                   boolean handleViewInputs, boolean tileMenu, boolean dockEvenPadding, boolean cinematicDots, boolean cinematicWhiteFocus, boolean wifiStatusColor, int cinematicScrim, int cinematicArt,
                   Map<String, Set<String>> wallpaperMoods, boolean gamepadPassthrough,
                   List<String> vpnButtons, boolean sleepTimerButton) {
        this.handleViewInputs = handleViewInputs;
        this.tileMenu = tileMenu;
        this.inputKeycodes = inputKeycodes;
        this.appKeys = Collections.unmodifiableList(appKeys);
        this.keyFilter = keyFilter;
        this.stockHomeRedirect = stockHomeRedirect;
        this.dockApps = Collections.unmodifiableList(dockApps);
        this.fillDock = fillDock;
        this.hiddenApps = Collections.unmodifiableList(hiddenApps);
        this.libraryApps = Collections.unmodifiableList(libraryApps);
        this.liveTvPackage = liveTvPackage;
        this.liveTvAction = liveTvAction;
        this.liveTvInputId = liveTvInputId;
        this.launchActions = Collections.unmodifiableMap(launchActions);
        this.appLabels = Collections.unmodifiableMap(appLabels);
        this.appBanners = Collections.unmodifiableMap(appBanners);
        this.sourcePanelService = sourcePanelService;
        this.sourcePanelAction = sourcePanelAction;
        this.sourcePanelExtra = sourcePanelExtra;
        this.coocaaSources = coocaaSources;
        this.inputAction = inputAction;
        this.inputUriScheme = inputUriScheme;
        this.columns = columns;
        this.rowsBelowDock = Math.max(1, Math.min(3, rowsBelowDock));
        this.animations = animations;
        this.advancedEffects = advancedEffects;
        this.shamsiDate = shamsiDate;
        this.greetingName = greetingName;
        this.moduleCards = moduleCards;
        this.reducedMotion = reducedMotion;
        this.bootToLiveTv = bootToLiveTv;
        this.dockTitle = dockTitle;
        this.dockRow = false;
        this.topMenu = topMenu;
        this.launcherSettingsButton = launcherSettingsButton;
        this.reorderButton = reorderButton;
        this.showNetworkPill = showNetworkPill;
        this.showInputsPill = showInputsPill;
        this.iconOnlyButtons = iconOnlyButtons;
        this.iconOnlyInputs = iconOnlyInputs;
        this.libraryButton = libraryButton;
        this.wifiLabelWidth = wifiLabelWidth;
        this.alwaysShowNames = alwaysShowNames;
        this.wallpaperTrueColor = wallpaperTrueColor;
        this.focusOvershoot = focusOvershoot;
        this.vpnApp = vpnApp;
        this.showHomeSettingsShortcut = showHomeSettingsShortcut;
        this.showStats = showStats;
        this.appLibrary = appLibrary;
        this.appLibrarySelection = appLibrarySelection;
        this.backgroundColor = backgroundColor;
        this.skyIntensity = skyIntensity;
        this.settingsKeycodes = settingsKeycodes;
        this.settingsKeyPanel = settingsKeyPanel;
        this.restoreKeyServices = restoreKeyServices;
        this.wallpaperEnabled = wallpaperEnabled;
        this.wallpaper = wallpaper;
        this.wallpapers = Collections.unmodifiableList(wallpapers);
        this.wallpaperRotationMinutes = wallpaperRotationMinutes;
        this.wallpaperDim = wallpaperDim;
        this.dockOpacity = dockOpacity;
        this.wallpaperOrder = wallpaperOrder;
        this.overlayApplied = overlayApplied;
        this.festival = festival;
        this.raceCalendar = Collections.unmodifiableList(raceCalendar);
        this.cinematicHome = cinematicHome;
        this.ambientGlow = ambientGlow;
        this.appGlow = appGlow;
        this.fadeRows = fadeRows;
        this.homeIdleClockMinutes = homeIdleClockMinutes;
        this.dockGlass = dockGlass;
        this.dockEvenPadding = dockEvenPadding;
        this.cinematicDots = cinematicDots;
        this.cinematicWhiteFocus = cinematicWhiteFocus;
        this.wifiStatusColor = wifiStatusColor;
        this.cinematicArt = cinematicArt;
        this.cinematicScrim = cinematicScrim;
        this.wallpaperMoods = wallpaperMoods;
        this.gamepadPassthrough = gamepadPassthrough;
        this.vpnButtons = Collections.unmodifiableList(vpnButtons);
        this.sleepTimerButton = sleepTimerButton;
        this.glowStyle = glowStyle(glowStyle);
        this.wallpaperCaptions = wallpaperCaptions;
    }

    /** A known glow_style, or radial (the default) for anything else. */
    static String glowStyle(String value) {
        return value != null && GLOW_HORIZON.equalsIgnoreCase(value.trim()) ? GLOW_HORIZON : GLOW_RADIAL;
    }

    /** The folder the theme tools push into; null when external storage is unavailable. */
    static File dir(Context ctx) {
        return ctx.getExternalFilesDir(null);
    }

    static Config load(Context ctx) {
        Resources res = ctx.getResources();
        List<String> dock = new ArrayList<>(Arrays.asList(res.getStringArray(R.array.dock_apps)));
        boolean fillDock = res.getBoolean(R.bool.fill_dock);
        List<String> hidden = new ArrayList<>(Arrays.asList(res.getStringArray(R.array.hidden_apps)));
        List<String> libraryApps = new ArrayList<>(Arrays.asList(res.getStringArray(R.array.library_apps)));
        String liveTv = res.getString(R.string.live_tv_package).trim();
        String liveTvAction = res.getString(R.string.live_tv_action).trim();
        String liveTvInputId = res.getString(R.string.live_tv_input_id).trim();
        List<String> launchActions = new ArrayList<>(Arrays.asList(res.getStringArray(R.array.launch_actions)));
        List<String> appLabels = new ArrayList<>(Arrays.asList(res.getStringArray(R.array.app_labels)));
        List<String> appBanners = new ArrayList<>(Arrays.asList(res.getStringArray(R.array.app_banners)));
        String panelService = res.getString(R.string.source_panel_service).trim();
        String panelAction = res.getString(R.string.source_panel_action).trim();
        String panelExtra = res.getString(R.string.source_panel_extra).trim();
        boolean coocaaSources = res.getBoolean(R.bool.coocaa_sources);
        String inputAction = res.getString(R.string.input_action).trim();
        String inputUriScheme = res.getString(R.string.input_uri_scheme).trim();
        int columns = res.getInteger(R.integer.columns);
        int rowsBelow = res.getInteger(R.integer.rows_below_dock);
        boolean animations = res.getBoolean(R.bool.animations);
        boolean advancedEffects = res.getBoolean(R.bool.advanced_effects);
        boolean shamsi = res.getBoolean(R.bool.shamsi_date);
        String greetingName = res.getString(R.string.greeting_name).trim();
        boolean moduleCards = res.getBoolean(R.bool.module_cards);
        boolean reducedMotion = res.getBoolean(R.bool.reduced_motion);
        boolean bootToLiveTv = res.getBoolean(R.bool.boot_to_live_tv);
        boolean dockTitle = res.getBoolean(R.bool.dock_title);
        boolean dockRow = res.getBoolean(R.bool.dock_row);
        boolean topMenu = res.getBoolean(R.bool.top_menu);
        boolean launcherSettingsButton = res.getBoolean(R.bool.launcher_settings_button);
        boolean reorderButton = res.getBoolean(R.bool.reorder_button);
        boolean showNetworkPill = res.getBoolean(R.bool.show_network_pill);
        boolean showInputsPill = res.getBoolean(R.bool.show_inputs_pill);
        boolean iconOnlyButtons = res.getBoolean(R.bool.icon_only_buttons);
        boolean iconOnlyInputs = res.getBoolean(R.bool.icon_only_inputs);
        boolean libraryButton = res.getBoolean(R.bool.library_button);
        int wifiLabelWidth = res.getInteger(R.integer.wifi_label_width);
        boolean alwaysShowNames = res.getBoolean(R.bool.always_show_names);
        boolean wallpaperTrueColor = res.getBoolean(R.bool.wallpaper_true_color);
        boolean focusOvershoot = res.getBoolean(R.bool.focus_overshoot);
        boolean wallpaperCaptions = res.getBoolean(R.bool.wallpaper_captions);
        boolean tileMenu = res.getBoolean(R.bool.tile_menu);
        String vpn = res.getString(R.string.vpn_app).trim();
        List<String> vpnButtons = packages(res.getString(R.string.vpn_buttons));
        boolean sleepTimerButton = res.getBoolean(R.bool.sleep_timer_button);
        boolean homeSettings = res.getBoolean(R.bool.show_home_settings_shortcut);
        boolean showStats = res.getBoolean(R.bool.show_stats);
        boolean library = res.getBoolean(R.bool.app_library);
        boolean librarySelection = res.getBoolean(R.bool.app_library_selection);
        int background = 0xFF000000;
        int skyIntensity = res.getInteger(R.integer.sky_intensity);
        int[] settingsKeycodes = res.getIntArray(R.array.settings_key_keycodes);
        boolean settingsKeyPanel = res.getBoolean(R.bool.settings_key_panel);
        boolean restoreKeyServices = res.getBoolean(R.bool.restore_key_services);
        int[] inputKeycodes = res.getIntArray(R.array.input_key_keycodes);
        boolean handleViewInputs = res.getBoolean(R.bool.handle_view_inputs);
        List<String> appKeys = new ArrayList<>(Arrays.asList(res.getStringArray(R.array.app_keys)));
        String stockHomeRedirect = res.getString(R.string.stock_home_redirect).trim();
        boolean cinematic = res.getBoolean(R.bool.cinematic_home);
        boolean ambientGlow = res.getBoolean(R.bool.ambient_glow);
        boolean appGlow = res.getBoolean(R.bool.app_glow);
        boolean fadeRows = res.getBoolean(R.bool.fade_rows);
        int idleClockMinutes = res.getInteger(R.integer.home_idle_clock_minutes);
        boolean keyFilter = res.getBoolean(R.bool.key_filter);
        boolean gamepadPassthrough = res.getBoolean(R.bool.gamepad_passthrough);
        boolean dockGlass = res.getBoolean(R.bool.dock_glass);
        boolean dockEvenPadding = res.getBoolean(R.bool.dock_even_padding);
        boolean cinematicDots = res.getBoolean(R.bool.cinematic_dots);
        boolean cinematicWhiteFocus = res.getBoolean(R.bool.cinematic_white_focus);
        boolean wifiStatusColor = res.getBoolean(R.bool.wifi_status_color);
        int cinematicArt = res.getInteger(R.integer.cinematic_art);
        int cinematicScrim = res.getInteger(R.integer.cinematic_scrim);
        Map<String, Set<String>> wallpaperMoods = Collections.emptyMap();
        String glowStyle = res.getString(R.string.glow_style);
        boolean applied = false;

        // Appearance is built in. External theme files are no longer read or applied.
        SharedPreferences choices = ctx.getSharedPreferences(SETTINGS_NAME, Context.MODE_PRIVATE);
        // Unmounted storage looks like a theme reset; compare only once the file is readable.

        int chosenColumns = choices.getInt("columns", columns);
        if (chosenColumns >= 4 && chosenColumns <= 7) columns = chosenColumns;
        int chosenRows = choices.getInt("visible_rows", 2);
        if (chosenRows >= 0 && chosenRows <= 3) rowsBelow = chosenRows;
        animations = choices.getBoolean("animations", animations);
        advancedEffects = choices.getBoolean("advanced_effects", advancedEffects);
        File wallpaper = null;
        boolean wallpaperEnabled = false;
        int rotationMinutes = 0, wallpaperDim = 0, dockOpacity = 100;
        String wallpaperOrder = WallpaperRotation.ORDER_SEQUENTIAL;
        List<File> wallpapers = Collections.emptyList();
        Festival festival = Festival.NONE;
        return new Config(dock, fillDock, hidden, libraryApps, liveTv, liveTvAction, liveTvInputId, packageMap(launchActions), packageMap(appLabels),
                packageMap(appBanners), panelService,
                panelAction, panelExtra, coocaaSources, inputAction, inputUriScheme,
                Math.max(1, columns), Math.max(0, rowsBelow), animations, advancedEffects,
                shamsi, greetingName, moduleCards, reducedMotion, bootToLiveTv, dockTitle, dockRow, topMenu, launcherSettingsButton, reorderButton, showNetworkPill, showInputsPill, iconOnlyButtons, iconOnlyInputs, libraryButton, wifiLabelWidth, alwaysShowNames, wallpaperTrueColor, focusOvershoot, vpn, homeSettings, showStats, library, librarySelection, background,
                Math.max(0, Math.min(100, skyIntensity)), settingsKeycodes, settingsKeyPanel, restoreKeyServices, wallpaperEnabled, wallpaper,
                wallpapers, rotationMinutes, wallpaperDim, dockOpacity, wallpaperOrder, applied,
                festival, inputKeycodes, appKeys, stockHomeRedirect, Collections.emptyList(), cinematic, ambientGlow, appGlow, fadeRows,
                Math.max(0, idleClockMinutes), keyFilter, dockGlass, glowStyle, wallpaperCaptions, handleViewInputs, tileMenu, dockEvenPadding, cinematicDots, cinematicWhiteFocus, wifiStatusColor, cinematicScrim, cinematicArt, wallpaperMoods, gamepadPassthrough, vpnButtons, sleepTimerButton);
    }

    static int chosenDockOpacity(int chosen,int fallback){return chosen>=30&&chosen<=100?chosen:fallback;}

    /** Dark fill opacity, 0..100 percent; the UI displays its complement as transparency. */
    static int chosenPlateOpacity(int chosen,int fallback){return chosen>=0&&chosen<=100?chosen:fallback;}
    /** Wallpaper blur radius in dp, 0..10; zero preserves the sharp wallpaper. */
    static int chosenPlateBlur(int chosen,int fallback){return chosen>=0&&chosen<=10?chosen:fallback;}

    /** "package=value" entries; an entry without both halves is skipped, and a later entry for a package wins. */
    static Map<String, String> packageMap(List<String> entries) {
        Map<String, String> out = new HashMap<>();
        for (String entry : entries) {
            int eq = entry.indexOf('=');
            if (eq <= 0) continue;
            String pkg = entry.substring(0, eq).trim(), value = entry.substring(eq + 1).trim();
            if (!pkg.isEmpty() && !value.isEmpty()) out.put(pkg, value);
        }
        return out;
    }

    /** A comma-separated list of packages from config.xml; blank items are ignored. */
    private static List<String> packages(String csv) {
        List<String> out = new ArrayList<>();
        for (String p : csv.split(",")) {
            String t = p.trim();
            if (!t.isEmpty() && !out.contains(t)) out.add(t);
        }
        return out;
    }
}
