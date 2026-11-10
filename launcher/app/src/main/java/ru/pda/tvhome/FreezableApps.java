package ru.pda.tvhome;

import java.util.*;

/**
 * Packages that can be frozen on this firmware. Nothing here is Home, settings, the tuner, the keyboard,
 * WebView, an audio/display service or a Google service the launcher depends on. Verified on the living-room
 * TV (2026-11-08): every package below is installed there.
 * The launcher cannot freeze them by itself: enabling or disabling another package needs privileged rights
 * (CHANGE_COMPONENT_ENABLED_STATE) or root, so the settings rows ask the adb helper over the assistant
 * handshake, and the helper applies each package against its own protected mirror of this list. The
 * installer applies the same list directly over adb.
 * The stock shell com.mediatek.wwtv.tvcenter is on the list, not on the protected side: on the living-room TV
 * (2026-11-10) the remote gear opens its TurnkeyUiMainActivity next to the launcher settings, so freezing it
 * leaves one menu instead of two; the system settings, tuner and audio services stay enabled. The helper
 * freezes it at every start, and a package already off changes nothing.
 * com.homwee.factory left the protected side by the owner's decision on 2026-11-10: the physical gear reaches
 * the firmware handler, and FactoryKeyReciver there opens system settings next to the picture settings window
 * of the bridge. Checked with a physical press on the living-room TV. Freezing the package stops that window;
 * the settings app itself stays enabled, only the factory key receiver stops answering.
 * The setupwraith entry is the recovery stub, not a launcher: while it is enabled the system resolves HOME to
 * its RecoveryActivity, which relaunches itself in a burst and shows nothing, so the Home key never reaches
 * TVHome from any app. Verified on the living-room TV (2026-11-09): with it disabled for user 0 HOME resolves
 * to ru.pda.tvhome/.MainActivity and the Home key opens the launcher from the settings screen. It is frozen
 * only after the initial setup of the firmware has finished, so the setup wizard itself can still run.
 * The same happened again on 2026-11-11 after a reinstall put the package back, so the adb helper now re-applies
 * this freeze at every start, only when the initial setup is finished, and records frozen or skipped in its trace.
 */
final class FreezableApps {
    record Item(String pkg,String what){}

    static final List<Item> ITEMS=List.of(
        new Item("com.google.android.tungsten.setupwraith","Заглушка первоначальной настройки; пока она включена, клавиша Home уходит в неё, а не в лаунчер"),
        new Item("com.mediatek.wwtv.setupwizard","Первоначальная настройка"),
        new Item("com.mediatek.wwtv.tvcenter","Оболочка прошивки и меню шестерёнки (TurnkeyUiMainActivity); настройки, тюнер и звуковые сервисы остаются включены"),
        new Item("com.homwee.factory","Приёмник физической шестерёнки (FactoryKeyReciver), который показывает системные настройки рядом с меню изображения; по решению владельца (2026-11-10) frozen helper его замораживает при каждом запуске, фабричное меню по клавише больше не открывается"),
        new Item("com.mstar.android.tv.disclaimercustomization","Экран дисклеймера при первом включении"),
        new Item("com.google.android.apps.tv.launcherx","Google TV Home, пока Home выбран TVHome"),
        new Item("com.google.android.katniss","Голосовой помощник Google; помощник должен быть выбран TVHome"),
        new Item("com.homwee.help","Экран помощи производителя"),
        new Item("com.homwee.store","Магазин производителя"),
        new Item("com.homwee.mmp.fileexplorer","Проводник производителя"),
        new Item("com.homwee.mmp.imageplayer","Просмотрщик фото производителя"),
        new Item("com.gdch.gameoptimiser","Оптимизатор игр производителя"),
        new Item("tv.anoki.acr.anokiacroptin","Распознавание контента (ACR)"),
        new Item("com.google.android.partnersetup","Телеметрия партнёра Google"),
        new Item("com.google.android.play.games","Сервис Play Games"),
        new Item("com.google.android.feedback","Сбор отзывов"),
        new Item("com.google.android.marvin.talkback","TalkBack; сервис доступности не используется"),
        new Item("com.android.htmlviewer","Просмотр HTML"),
        new Item("com.android.companiondevicemanager","Менеджер компаньон-устройств"),
        new Item("com.android.healthconnect.controller","Health Connect"),
        new Item("com.android.health.connect.backuprestore","Резервирование Health Connect"),
        new Item("com.android.cameraextensions","Расширения камеры; камеры на ТВ нет"),
        new Item("com.android.nearby.halfsheet","Half-sheet Nearby"),
        new Item("com.android.captiveportallogin","Страница входа captive portal"),
        new Item("com.android.proxyhandler","Обработчик прокси"),
        new Item("com.android.sharedstoragebackup","Резервное копирование"),
        new Item("com.android.providers.calendar","Пустой провайдер календаря"),
        new Item("com.android.providers.contacts","Пустой провайдер контактов"),
        new Item("com.android.providers.userdictionary","Словарь пользователя"),
        new Item("com.google.android.syncadapters.calendar","Синхронизация календаря"),
        new Item("com.google.android.youtube.tvmusic","Музыкальный сервис")
    );

    /** Never frozen: the launcher, settings, tuner and inputs, keyboard, WebView, Google services and the audio/display services. */
    static final Set<String> FORBIDDEN=Set.of(
        "ru.pda.tvhome","com.android.tv.settings",
        "com.mediatek.tvinput","com.google.android.tv.inputplayer","com.mediatek.tv.inputplayeroverlay",
        "com.mediatek.tv.service","com.mediatek.android.leanbacklauncher.partnercustomizer","com.mediatek.androidbox","com.mediatek.factorykey",
        "com.mediatek.wwtv.webview","com.google.android.webview","com.google.android.inputmethod.latin",
        "com.google.android.gms","com.google.android.gsf","com.google.android.play","com.android.vending",
        "com.android.systemui","com.android.providers.settings","com.android.providers.tv","com.android.providers.downloads",
        "com.google.android.tv.assistant","com.google.android.tv.remote.service","com.google.android.apps.mediashell",
        "com.google.android.apps.tv.dreamx","com.google.android.tts",
        "com.dolby.android.audio.service","com.mediatek.speakerservice","com.homwee.dynamicbacklight","com.homwee.lightsensorservice",
        "com.homwee.synckey","com.homwee.upgrade","com.homwee.dmt.upgrade","com.homwee.mmp.screenshare",
        "com.homwee.multiscreenshare","com.mediatek.autopair",
        "com.android.statementservice","com.android.rkpdapp","com.android.devicelockcontroller","com.android.managedprovisioning",
        "com.android.packageinstaller","com.google.android.packageinstaller","com.android.keychain","com.android.backupconfirm",
        "com.android.certinstaller","com.android.tv.frameworkpackagestubs","com.android.vpndialogs","com.android.se",
        "com.android.bluetooth","com.android.wifi.resources","com.android.networkstack","com.android.inputdevices");

    static List<Item> list(){return ITEMS;}

    /** A resource overlay cannot be frozen without breaking the package it overlays. */
    static boolean safe(String pkg,String appPackage){
        return !pkg.equals(appPackage)&&!FORBIDDEN.contains(pkg)&&!pkg.contains("auto_generated_rro")
            &&!pkg.startsWith("com.android.tv.overlay")&&!pkg.startsWith("com.google.android.overlay")
            &&!pkg.startsWith("com.mediatek.overlay")&&!pkg.startsWith("com.android.tv.settings");
    }

    private FreezableApps(){}
}
