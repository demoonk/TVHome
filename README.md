# TVHome

Лаунчер для Android TV (Chiq-based TV, Dexp, Tuvio). Личный форк [CleanYourTV](https://github.com/Gathin23/cleanyourtv), MIT.

## Установка и обновления — русский

Требуется Android TV / Google TV **Android 8.0 и новее (API 26)**. Идентификатор приложения: **`ru.pda.tvhome`**. Совместимость выбора Home и кнопок пульта зависит от прошивки. Штатный лаунчер должен оставаться включённым: это позволяет безопасно вернуться к нему.

### Скачать и установить APK

1. Откройте [GitHub Releases](https://github.com/demoonk/TVHome/releases/latest) и скачайте `TVHome-<версия>.apk` из **Assets**. Архив исходного кода и `update.json` для обычной установки не нужны.
2. Перенесите APK на телевизор, например на USB-накопителе, и откройте через файловый менеджер ТВ. Если Android запросит разрешение установки из этого источника, разрешите его выбранному файловому менеджеру. Подтвердите установку или обновление системным установщиком.
3. Запустите TVHome. Откройте системные **Настройки → Приложения → Приложения по умолчанию → Главный экран / Home** и выберите TVHome. На некоторых телевизорах выбор предлагается при нажатии Home. Названия пунктов зависят от прошивки. Если выбора нет, оставьте текущий Home и запускайте TVHome как приложение; не отключайте штатные пакеты ради замены главного экрана.
4. Проверьте Home, переходы стрелками, открытие приложений и системных настроек. Сохраните доступ к системным настройкам для возврата к штатному Home.

На телевизоре с **1.4.18 или ниже установите последнюю версию (1.4.19 или новее) вручную поверх имеющейся**; промежуточные версии устанавливать не нужно. Установка поверх приложения с тем же package и тем же ключом подписи сохраняет его данные и настройки. APK с другим ключом не является совместимым обновлением: при отказе установщика сохраните текст ошибки и используйте сборку с исходной подписью, не удаляя приложение и не очищая данные.

### Установка с компьютера: проверенные скрипты и ADB

Скачайте официальный [Android SDK Platform-Tools](https://developer.android.com/tools/releases/platform-tools) и распакуйте весь архив. Android Studio и Java для установки APK не нужны. Включите на ТВ отладку ADB и подтвердите подключение вашего компьютера. При беспроводной отладке сначала выполните `adb pair TV_ADDRESS:PAIRING_PORT`, затем используйте отдельный **порт подключения**. При USB используйте серийный номер из `adb devices`.

Скачайте из [последнего релиза](https://github.com/demoonk/TVHome/releases/latest) APK и подходящий скрипт `install.ps1` или `install.sh` (они также лежат в корне репозитория). Замените адрес, порт и пути своими.

Windows, PowerShell:

```powershell
.\install.ps1 -Device "TV_ADDRESS:PORT" -Apk ".\TVHome-1.4.23.apk" -Adb "C:\platform-tools\adb.exe"
```

macOS / Linux:

```sh
sh ./install.sh "TV_ADDRESS:PORT" "./TVHome-1.4.23.apk" "./platform-tools/adb"
```

Если PowerShell блокирует запуск скачанного скрипта, после проверки его содержимого можно выполнить один запуск через `powershell -NoProfile -ExecutionPolicy Bypass -File .\install.ps1 -Device "TV_ADDRESS:PORT" -Apk ".\TVHome-1.4.23.apk" -Adb "C:\platform-tools\adb.exe"`. Этот параметр не меняет постоянную политику выполнения системы.

Скрипты требуют явного выбора устройства, проверяют доступ к ADB и API 26+, сохраняют доступный установленный APK в `backups/`, выполняют `install -r` и открывают `ru.pda.tvhome/com.gathin.tvhome.MainActivity` только после `Success`. Ошибка установки останавливает скрипт. Данные не очищаются; штатный Home не отключается, настройки по умолчанию и службы доступности не меняются. Выберите Home через системные настройки самостоятельно. Не используйте старый upstream-скрипт `build-and-install`: для отдельного репозитория TVHome предназначены эти самостоятельные установщики.

Ручная альтернатива из папки Platform-Tools:

```powershell
.\adb.exe connect TV_ADDRESS:PORT
.\adb.exe -s TV_ADDRESS:PORT install -r "C:\TV\TVHome-1.4.23.apk"
.\adb.exe -s TV_ADDRESS:PORT shell am start -W -n ru.pda.tvhome/com.gathin.tvhome.MainActivity
```

Для macOS/Linux замените `.\adb.exe` на `./adb`. При ошибке `install` не продолжайте запуск. После установки отключите отладку в настройках ТВ и отзовите разрешения отладки. `backups/` содержит APK, а не резервную копию настроек; возврат старой версии по-прежнему зависит от ограничений Android, описанных ниже.

### Обновления после 1.4.19

Версия 1.4.19 впервые добавляет проверку GitHub Releases: при старте процесса лаунчера и затем примерно раз в сутки при доступной сети. Точное время фоновой проверки определяет Android. При новой версии перед «Входы» появляется кнопка с иконкой обновления и уведомление **«Вышла новая версия …»**. Нажмите кнопку, разрешите установку из TVHome, если Android попросит, затем подтвердите обновление системным установщиком. Скачивание не означает автоматическую установку. При необходимости APK всегда можно установить вручную поверх совместимой версии.

В «Помощи» первой строкой указаны название приложения, установленная версия и ссылка на GitHub. Вторая кнопка «Проверить обновления» выполняет свежую проверку сразу, без суточного ожидания, и сообщает результат. Наличие GitHub Releases и доступ к сети необходимы для проверки обновлений.

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

On TVs running **1.4.18 or earlier, install the latest release (1.4.19 or newer) manually over the existing app**; intermediate versions are not required. A compatible in-place update with the same application ID and signing key preserves app data and settings. An APK signed with a different key cannot update the installed app. If installation fails, retain the error and obtain a build signed with the original key; do not uninstall the app or clear its data.

### Computer installation: verified scripts and ADB

Download the official [Android SDK Platform-Tools](https://developer.android.com/tools/releases/platform-tools) and extract the complete archive. Java and Android Studio are not required to install an APK. Enable ADB debugging on the TV and approve your computer. For wireless debugging, run `adb pair TV_ADDRESS:PAIRING_PORT` first, then use the separate **connection port**. For USB, use the serial reported by `adb devices`.

Download the APK and `install.ps1` or `install.sh` from the [latest release](https://github.com/demoonk/TVHome/releases/latest); the scripts are also in the repository root. Replace the address, port and paths with your own.

Windows PowerShell:

```powershell
.\install.ps1 -Device "TV_ADDRESS:PORT" -Apk ".\TVHome-1.4.23.apk" -Adb "C:\platform-tools\adb.exe"
```

macOS / Linux:

```sh
sh ./install.sh "TV_ADDRESS:PORT" "./TVHome-1.4.23.apk" "./platform-tools/adb"
```

If PowerShell blocks a downloaded script, review its contents and use a single invocation with `powershell -NoProfile -ExecutionPolicy Bypass -File .\install.ps1 -Device "TV_ADDRESS:PORT" -Apk ".\TVHome-1.4.23.apk" -Adb "C:\platform-tools\adb.exe"`. This does not change the persistent system execution policy.

The scripts require an explicit device, check ADB authorization and API 26+, back up any discoverable installed APK to `backups/`, run `install -r`, and launch `ru.pda.tvhome/com.gathin.tvhome.MainActivity` only after installation reports `Success`. Installation errors stop the script. They do not clear app data, disable the stock Home, change default apps or modify accessibility services. Choose the Home app in system Settings yourself. Use these standalone installers for this repository rather than the legacy upstream `build-and-install` script.

Manual alternative from the Platform-Tools directory:

```powershell
.\adb.exe connect TV_ADDRESS:PORT
.\adb.exe -s TV_ADDRESS:PORT install -r "C:\TV\TVHome-1.4.23.apk"
.\adb.exe -s TV_ADDRESS:PORT shell am start -W -n ru.pda.tvhome/com.gathin.tvhome.MainActivity
```

On macOS/Linux use `./adb` instead of `.\adb.exe`. Do not continue to launch after a failed installation. Afterwards, turn off debugging and revoke debugging authorizations in the TV's Settings. `backups/` contains APKs, not app-setting backups; downgrades remain subject to the Android restrictions below.

### Updates from 1.4.19 onward

Version 1.4.19 introduces GitHub release checks when the launcher process starts and approximately once daily when a network is available; Android controls the actual background timing. When an update is available, an update-icon button appears before **Inputs**, together with a **“Вышла новая версия …”** (“A new version is available …”) notification. Select it, allow installations from TVHome if prompted, and confirm the update in Android's installer. Downloading never silently installs an update. Manual in-place installation remains available for compatible APKs.

The first row of **Help** shows the app name, installed version and GitHub link. Update checks require accessible GitHub Releases and a network connection.

The second Help button, “Проверить обновления” (Check for updates), performs a fresh check immediately without waiting for the daily schedule.

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
{"versionCode":72,"versionName":"1.4.23","apkName":"TVHome-1.4.23.apk","sha256":"<64 hexadecimal characters>"}
```

Приложение читает Releases API без авторизации; токенов GitHub внутри APK нет. Проверяются SHA-256, packageName, повышение versionCode и совпадение сертификатов подписи перед передачей APK системному установщику.

## Команда Алисы / Alice voice command

С версии 1.4.21: «Алиса, <сообщение>» открывает страницу Алисы в установленном Яндекс Браузере и автоматически отправляет текст. Просто «Алиса» открывает страницу без сообщения. Команда внутри сообщения не выполняется локально: «Алиса, выключи телевизор» отправит эти слова Алисе. Яндекс Браузер для ТВ (`com.yandex.browser.tv`) имеет приоритет; также поддерживается Android Яндекс Браузер (`com.yandex.browser`).

Since 1.4.21, say “Алиса, <message>” to open Alice in Yandex Browser and automatically submit the message. “Алиса” alone opens the page without sending a message. The message is not interpreted as a local TV command. Yandex Browser for TV is preferred; the Android Yandex Browser package is also supported.

С версии 1.4.23 анимация при нажатии плитки отключена: приложение открывается сразу. Обратный переход при возврате сохранён. Пружинная обводка навигации сохранена и учитывает масштаб активных кнопок. Since 1.4.23, apps open immediately without a tile launch animation. The return transition is preserved. The spring navigation outline remains and follows the actual scaled button bounds.

## Возможности форка

Поиск медиа через сайты и публичные провайдеры установленных приложений; встроенные видеообои и пользовательские локальные коллекции; анимация статичных обоев; настройка пульта, плиток, часов и оформления. Для VK/Кинопоиска доступность карточек зависит от страницы сайта/авторизации и поддержки ссылок установленным приложением. Импорт скачанных файлов обозначен отдельно от автоматической интеграции источников.

## Проверка

Для 1.4.23 выполняются `testReleaseUnitTest`, `lintRelease`, `assembleRelease`. Код проверки обновлений покрыт JVM-тестами правил URL, имени APK и SHA-256. Проверка визуального интерфейса и установки обновления на реальном ТВ обязательна отдельно.

Проверка установщиков / Installer checks: `python -m unittest discover -s tests -v` (PowerShell и Bash запускаются с подставным ADB; телевизор не меняется / fake ADB, no TV changes).
