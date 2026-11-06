# TVHome

Лаунчер для Android TV (Chiq-based TV, Dexp, Tuvio). Личный форк [CleanYourTV](https://github.com/Gathin23/cleanyourtv), MIT.

## Установка и обновления — русский

Требуется Android TV / Google TV **Android 8.0 и новее (API 26)**. Идентификатор приложения: **`ru.pda.tvhome`**. Совместимость выбора Home и кнопок пульта зависит от прошивки. Штатный лаунчер должен оставаться включённым: это позволяет безопасно вернуться к нему.

### Скачать и установить APK

1. Откройте [GitHub Releases](https://github.com/demoonk/TVHome/releases/latest) и скачайте `TVHome-<версия>.apk` из **Assets**. Архив исходного кода и `update.json` для обычной установки не нужны.
2. Перенесите APK на телевизор, например на USB-накопителе, и откройте через файловый менеджер ТВ. Если Android запросит разрешение установки из этого источника, разрешите его выбранному файловому менеджеру. Подтвердите установку или обновление системным установщиком.
3. Запустите TVHome. Откройте системные **Настройки → Приложения → Приложения по умолчанию → Главный экран / Home** и выберите TVHome. На некоторых телевизорах выбор предлагается при нажатии Home. Названия пунктов зависят от прошивки. Если выбора нет, оставьте текущий Home и запускайте TVHome как приложение; не отключайте штатные пакеты ради замены главного экрана.
4. Проверьте Home, переходы стрелками, открытие приложений и системных настроек. Сохраните доступ к системным настройкам для возврата к штатному Home.

На телевизоре с **1.4.18 или ниже первый APK 1.4.19 нужно установить вручную поверх имеющейся версии**. Установка поверх приложения с тем же package и тем же ключом подписи сохраняет его данные и настройки. APK с другим ключом не является совместимым обновлением: при отказе установщика сохраните текст ошибки и используйте сборку с исходной подписью, не удаляя приложение и не очищая данные.

### Альтернатива: установка с компьютера через ADB

Скачайте официальный [Android SDK Platform-Tools](https://developer.android.com/tools/releases/platform-tools) для Windows, macOS или Linux и распакуйте весь архив. Android Studio и Java для установки готового APK не нужны. Включите на ТВ режим разработчика (обычно семь нажатий на номер сборки) и доступную в прошивке отладку ADB. Подключайте свой компьютер и ТВ в доверенной локальной сети; подтвердите запрос отладки на экране ТВ.

В примерах `TV_ADDRESS:PORT` замените адресом и портом, показанными телевизором, а имя APK — именем скачанного файла. При беспроводной отладке с кодом сначала выполните `adb pair TV_ADDRESS:PAIRING_PORT`, введите код ТВ, затем подключитесь к **порту подключения**, который может отличаться от порта сопряжения. Для обычного сетевого ADB используйте порт своей прошивки.

Windows, PowerShell из папки Platform-Tools:

```powershell
.\adb.exe connect TV_ADDRESS:PORT
.\adb.exe devices
.\adb.exe install -r "C:\TV\TVHome-1.4.19.apk"
```

macOS / Linux, терминал из папки Platform-Tools:

```sh
./adb connect TV_ADDRESS:PORT
./adb devices
./adb install -r "$HOME/Downloads/TVHome-1.4.19.apk"
```

Перед установкой в `devices` должно отображаться `device`, а не `unauthorized`. Если подключено несколько устройств, добавьте `-s TV_ADDRESS:PORT` перед `install`. Дождитесь `Success`, затем выберите Home через системные настройки, как описано выше. Ключ `-r` обновляет совместимое приложение с сохранением данных. После установки отключите ADB / беспроводную отладку в настройках ТВ и отзовите разрешения отладки.

### Обновления после 1.4.19

Версия 1.4.19 впервые добавляет проверку GitHub Releases: при старте процесса лаунчера и затем примерно раз в сутки при доступной сети. Точное время фоновой проверки определяет Android. При новой версии перед «Входы» появляется кнопка с иконкой обновления и уведомление **«Вышла новая версия …»**. Нажмите кнопку, разрешите установку из TVHome, если Android попросит, затем подтвердите обновление системным установщиком. Скачивание не означает автоматическую установку. При необходимости APK всегда можно установить вручную поверх совместимой версии.

В «Помощи» первой строкой указаны название приложения, установленная версия и ссылка на GitHub. Наличие GitHub Releases и доступ к сети необходимы для проверки обновлений.

### Совместимость поиска и провайдеров

Для базового главного экрана отдельный backend не требуется. Поиск использует поддерживаемые сайты и публичные провайдеры установленных медиаприложений. Результаты и открытие карточек зависят от версии приложения-провайдера, его доступных интерфейсов и поддержки ссылок, доступности сайта, сети и авторизации. Установка TVHome не устанавливает эти приложения и не предоставляет подписки. Aerial Views требует совместимого wallpaper-provider API; импорт скачанных файлов — отдельный локальный режим, а не автоматическая интеграция сайта.

### Безопасный откат: вернуть штатный Home

1. Откройте системные **Настройки → Приложения → Приложения по умолчанию → Главный экран / Home** и выберите штатный лаунчер ТВ. Если TVHome не открывается, используйте кнопку системных настроек на пульте.
2. Нажмите Home и убедитесь, что открылся штатный экран. TVHome можно оставить установленным: его настройки сохранятся. Для возврата в TVHome выберите его Home тем же способом.
3. Штатный лаунчер и системные пакеты оставляйте включёнными. Для такого отката не нужны удаление приложений, очистка данных, root, сброс ТВ или скрипты отключения пакетов.

Возврат к прежнему Home и установка старого APK — разные действия. **Android может запретить установку APK с меньшим `versionCode`**, даже при той же подписи (`INSTALL_FAILED_VERSION_DOWNGRADE`). Старый APK можно ставить поверх только если Android допускает такую установку и совпадают package и ключ подписи; совместимость данных с более старой версией также не гарантируется. `adb install -r -d` не гарантирует downgrade обычной release-сборки. При отказе не удаляйте TVHome и не очищайте данные: оставайтесь на штатном Home и дождитесь исправленного совместимого обновления.

## Installation, updates and rollback — English

TVHome requires **Android TV / Google TV running Android 8.0 or newer (API 26)**. Its application ID is **`ru.pda.tvhome`**. Home selection and remote-button support depend on the TV firmware. **Keep the stock launcher enabled** so you can switch back safely.

### Download and install

1. Open [GitHub Releases](https://github.com/demoonk/TVHome/releases/latest) and download `TVHome-<version>.apk` under **Assets**. You do not need the source archive or `update.json` to install the app.
2. Copy the APK to your TV, for example using a USB drive, and open it with a TV file manager. If Android requests permission to install from this source, grant it to that file manager. Confirm installation or update in the system installer.
3. Launch TVHome. In the TV's system **Settings → Apps → Default apps → Home app**, choose TVHome. Some TVs show the chooser when you press Home. Menu names vary by firmware. If Home selection is unavailable, keep the current default and launch TVHome as an app; do not disable stock packages to force a replacement.
4. Test Home, directional navigation, launching apps and access to system Settings. Keep system Settings accessible so you can restore the stock Home.

On TVs running **1.4.18 or earlier, install the first 1.4.19 APK manually over the existing app**. A compatible in-place update with the same application ID and signing key preserves app data and settings. An APK signed with a different key cannot update the installed app. If installation fails, retain the error and obtain a build signed with the original key; do not uninstall the app or clear its data.

### Optional installation using a computer and ADB

Download the official [Android SDK Platform-Tools](https://developer.android.com/tools/releases/platform-tools) for Windows, macOS or Linux and extract the complete archive. Java and Android Studio are not required for installing a prebuilt APK. Enable developer options on the TV (usually by selecting its build number seven times), then enable the ADB debugging option provided by its firmware. Use your trusted local network and approve the debugging prompt on the TV.

Replace `TV_ADDRESS:PORT` with the address and connection port shown by your TV and replace the APK path with your downloaded file. If wireless debugging requires pairing, first run `adb pair TV_ADDRESS:PAIRING_PORT` and enter the TV's pairing code, then connect to its **connection port**, which may differ from the pairing port. For conventional network ADB, use the port provided by your firmware.

Windows PowerShell, from the Platform-Tools directory:

```powershell
.\adb.exe connect TV_ADDRESS:PORT
.\adb.exe devices
.\adb.exe install -r "C:\TV\TVHome-1.4.19.apk"
```

macOS / Linux terminal, from the Platform-Tools directory:

```sh
./adb connect TV_ADDRESS:PORT
./adb devices
./adb install -r "$HOME/Downloads/TVHome-1.4.19.apk"
```

Before installing, `devices` must show `device`, not `unauthorized`. With multiple devices connected, add `-s TV_ADDRESS:PORT` before `install`. Wait for `Success`, then select TVHome in system Home settings. The `-r` option preserves data when updating a compatible app. Afterwards, turn off ADB / wireless debugging in the TV's settings and revoke debugging authorizations.

### Updates from 1.4.19 onward

Version 1.4.19 introduces GitHub release checks when the launcher process starts and approximately once daily when a network is available; Android controls the actual background timing. When an update is available, an update-icon button appears before **Inputs**, together with a **“Вышла новая версия …”** (“A new version is available …”) notification. Select it, allow installations from TVHome if prompted, and confirm the update in Android's installer. Downloading never silently installs an update. Manual in-place installation remains available for compatible APKs.

The first row of **Help** shows the app name, installed version and GitHub link. Update checks require accessible GitHub Releases and a network connection.

### Search and provider compatibility

The basic launcher requires no separate backend. Media search uses supported websites and public providers exposed by installed media apps. Results and opening cards depend on provider-app versions, exposed interfaces, supported links, website availability, network access and login state. TVHome does not install these apps or provide subscriptions. Aerial Views requires a compatible wallpaper-provider API. Importing downloaded media is a separate local feature, not automatic website integration.

### Safe rollback to the stock Home

1. Open system **Settings → Apps → Default apps → Home app** and select the TV's stock launcher. If TVHome cannot open, use the remote's system Settings button.
2. Press Home and verify that the stock screen opens. Leave TVHome installed to retain its settings; select it as Home again whenever needed.
3. Keep the stock launcher and system packages enabled. Switching Home requires no app removal, data clearing, root, factory reset or package-disabling scripts.

Switching Home and downgrading the APK are separate actions. **Android may reject an APK with a lower `versionCode`**, even when its signature matches (`INSTALL_FAILED_VERSION_DOWNGRADE`). Install an older APK over the current app only if Android permits it and the application ID and signing key match; compatibility of newer app data with older code is not guaranteed. `adb install -r -d` does not guarantee a downgrade of a normal release build. If Android refuses, do not uninstall TVHome or clear data: use the stock Home while waiting for a fixed compatible update.

## Сборка

Нужны JDK 17, Android SDK Platform 34 и Build-tools 34.0.0. В `launcher/local.properties` задайте `sdk.dir` своего Android SDK. Этот файл и ключи подписи в репозиторий не входят.

```powershell
cd launcher
./gradlew.bat testReleaseUnitTest lintRelease assembleRelease -PtvhomeKeystore=C:/private/TVHome.keystore
```

Для Linux/macOS используйте `./gradlew`. Package по умолчанию: `ru.pda.tvhome`, minSdk 26. Путь к ключу можно передать через `TVHOME_KEYSTORE` или `-PtvhomeKeystore=...`. Параметры подписи: `TVHOME_STORE_PASSWORD`, `TVHOME_KEY_ALIAS`, `TVHOME_KEY_PASSWORD`; значения по умолчанию соответствуют стандартному Android debug key. Для обновления уже установленного приложения используйте ровно тот ключ, которым подписана установленная версия. Сам ключ храните отдельно и не загружайте в GitHub.

## Публикация следующей версии

1. Увеличить `versionCode` и `versionName` в `launcher/app/build.gradle`.
2. Собрать подписанный APK, пройти тесты и lint.
3. Создать обычный публичный Release с тегом `v<versionName>`, приложить APK `TVHome-<versionName>.apk` и `update.json`.
4. В `update.json` записать реальный `versionCode`, `versionName`, точное имя `apkName` и SHA-256 APK. Черновики и prerelease не предлагаются телевизору.

Пример схемы (SHA-256 нужно заменить фактическим):

```json
{"versionCode":68,"versionName":"1.4.19","apkName":"TVHome-1.4.19.apk","sha256":"<64 hexadecimal characters>"}
```

Приложение читает Releases API без авторизации; токенов GitHub внутри APK нет. Проверяются SHA-256, packageName, повышение versionCode и совпадение сертификатов подписи перед передачей APK системному установщику.

## Возможности форка

Поиск медиа через сайты и публичные провайдеры установленных приложений; встроенные видеообои и пользовательские локальные коллекции; анимация статичных обоев; настройка пульта, плиток, часов и оформления. Для VK/Кинопоиска доступность карточек зависит от страницы сайта/авторизации и поддержки ссылок установленным приложением. Импорт скачанных файлов обозначен отдельно от автоматической интеграции источников.

## Проверка

Для 1.4.19 выполняются `testReleaseUnitTest`, `lintRelease`, `assembleRelease`. Код проверки обновлений покрыт JVM-тестами правил URL, имени APK и SHA-256. Проверка визуального интерфейса и установки обновления на реальном ТВ обязательна отдельно.
