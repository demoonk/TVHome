# TVHome

## Версия 2.0.3

Обновление устанавливается поверх TVHome 1.x и 2.x с сохранением данных и исходной подписи.

- Обновлены поиск и меню «Входы»/VPN: тонкие шрифты и рамки, пружинный селектор, белая галочка VPN.
- В поиске убрана голубая лупа; кнопки «Найти» и «Закрыть» заменены иконками поиска и выхода. Плитки и селектор не обрезаются справа.
- Исправлены прокрутка до последнего ряда и движение селектора обратно вверх, форма и восстановление фокуса кнопок шапки.
- Повторный Home на главном экране выбирает первую плитку и возвращает начало списка без перезапуска видеообоев. Исправлено исчезновение рамки после Home.
- После пробуждения сразу показывается сохранённый кадр обоев, пока готовится видео.
- Очищен список встроенных видеообоев: локальные видео, Aerial Community, NASA, Wikimedia Commons и папка с видео. Удалены лишние источники и анимация сдвига/наезда; плавная смена сохранена.

Совместимость миграции, необязательный мостик физической кнопки Back и откат описаны в [переходе на 2.0.0](docs/migration-2.0.0.md).


Лаунчер для Android TV (Chiq-based TV, Dexp, Tuvio). Личный форк [CleanYourTV](https://github.com/Gathin23/cleanyourtv), MIT.

![Главный экран TVHome / Main screen](docs/screenshots/00-home.png)

## Установка и настройка TVHome

**Первоначальную настройку Home и голосового поиска достаточно выполнить один раз.** Дальнейшие совместимые обновления лаунчера устанавливаются поверх существующей версии с сохранением данных и настроек: повторно назначать Home, выдавать разрешения и выбирать голосового помощника не потребуется. Для этого не удаляйте TVHome и не очищайте его данные перед обновлением.

### 1. Что понадобится

- Телевизор или приставка с Android 8.0 и новее.
- Компьютер с Windows и подключение к той же локальной сети.
- APK `TVHome-2.0.3.apk` из [последнего релиза](https://github.com/demoonk/TVHome/releases/latest).
- Android SDK Platform-Tools: https://developer.android.com/tools/releases/platform-tools

Распакуйте Platform-Tools. Положите APK в папку с `adb.exe`. Откройте эту папку в Проводнике, в адресной строке введите `powershell` и нажмите Enter. Все команды ниже выполняются в этом окне.

В примерах `IP:PORT` — адрес и порт подключения вашего телевизора. Заменяйте их во всех командах, например на `192.168.1.50:5555`. Порт 5555 используйте только если он соответствует настройкам вашего ТВ.

### 2. Подключение к телевизору

1. Включите режим разработчика на ТВ: обычно для этого нужно несколько раз нажать на пункт «Сборка» в сведениях об устройстве.
2. Включите доступную на вашей прошивке отладку по сети / ADB. Названия пунктов отличаются.
3. Узнайте IP-адрес и порт отладки телевизора.
4. На компьютере выполните:

```powershell
.\adb.exe connect IP:PORT
.\adb.exe devices
```

На телевизоре подтвердите разрешение отладки для своего компьютера. В списке устройств напротив адреса должно быть `device`.

Если телевизор использует беспроводную отладку с кодом сопряжения, сначала выполните:

```powershell
.\adb.exe pair IP:ПОРТ_СОПРЯЖЕНИЯ
```

Введите код с экрана ТВ, затем выполните `connect` с **портом подключения**. Порт сопряжения и порт подключения могут отличаться.

### 3. Установка или обновление

```powershell
.\adb.exe -s IP:PORT install -r .\TVHome-2.0.3.apk
```

Дождитесь `Success`. После успешной установки откройте TVHome:

```powershell
.\adb.exe -s IP:PORT shell am start -W -n ru.pda.tvhome/ru.pda.tvhome.MainActivity
```

Обновление ставится поверх совместимой версии с сохранением данных. При ошибке установки остановитесь и сохраните её текст. Для обновления не нужно удалять приложение или очищать его данные.

Если Home и голосовой поиск уже настроены, после успешного обновления дополнительные действия из разделов 4 и 5 не нужны.

### 4. Как назначить TVHome на кнопку Home

Если в меню телевизора нет выбора главного экрана, назначьте его с компьютера через ADB.

Сначала узнайте текущий главный экран и сохраните ответ — он понадобится для возврата:

```powershell
.\adb.exe -s IP:PORT shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME
```

Нужна строка компонента вида `пакет/класс`. Если вместо штатного лаунчера показано окно выбора Android или непонятный результат, сохраните весь вывод: готовой команды возврата пока нет.

Назначьте TVHome:

```powershell
.\adb.exe -s IP:PORT shell cmd package set-home-activity ru.pda.tvhome/ru.pda.tvhome.MainActivity
```

Нажмите Home на пульте, затем проверьте Home из другого приложения и после перезагрузки ТВ.

Если команда завершается ошибкой или Home всё равно открывает штатный экран, способ зависит от прошивки и обработки кнопок пульта. Для разбора нужны модель ТВ, версия Android и текст ответа команды. Отключать штатный лаунчер для выполнения этой инструкции не требуется.

Установщики `install.ps1` и `install.sh` из релиза 2.0.3 сами Home не назначают.

### 5. Как настроить голосовой поиск

#### Что означает «Недоступно на этом телевизоре»

При выбранном системном помощнике значок микрофона в верхней панели пытается открыть системную голосовую команду. Сообщение означает, что Android не нашёл доступный обработчик или запретил запуск. По одному этому сообщению нельзя сделать вывод о неисправности микрофона.

Установка APK сама по себе голосовой помощник не настраивает.

#### Подготовка помощника TVHome через ADB

Этот способ рассчитан на прошивки с компонентами Google. TVHome использует распознавание из пакета `com.google.android.tts`, а переключатель помощника рассчитан на системный пакет `com.google.android.katniss`. На прошивках без этих компонентов работа не гарантируется.

Проверьте наличие пакетов:

```powershell
.\adb.exe -s IP:PORT shell pm path com.google.android.tts
.\adb.exe -s IP:PORT shell pm path com.google.android.katniss
```

В ответе каждого вызова ожидается хотя бы одна строка `package:...`. Это подтверждает наличие пакета, но ещё не гарантирует поддержку распознавания. Если пакета нет, сохраните результат и сообщите модель ТВ — эти команды не устанавливают отсутствующие компоненты.

Для настройки нужен файл [assistant-package-guard.sh](tools/assistant-package-guard.sh) из папки `tools` исходников TVHome (скачайте исходный файл через Raw). Положите его рядом с `adb.exe`.

Помощник при выборе «Google от лаунчера» отключает пакет системного Google-помощника Katniss; при переключении обратно на «Системный» включает его. Это влияет и на штатный голосовой сценарий телевизора.

Выполните последовательно:

```powershell
.\adb.exe -s IP:PORT shell pm grant ru.pda.tvhome android.permission.WRITE_SECURE_SETTINGS
.\adb.exe -s IP:PORT shell pm grant ru.pda.tvhome android.permission.RECORD_AUDIO
.\adb.exe -s IP:PORT push .\assistant-package-guard.sh /data/local/tmp/assistant-package-guard.sh
.\adb.exe -s IP:PORT shell 'nohup sh /data/local/tmp/assistant-package-guard.sh >/data/local/tmp/assistant-package-guard.log 2>&1 </dev/null &'
```

Если любая команда сообщает об ошибке, сохраните её текст: переходить к выбору помощника пока рано.

На телевизоре:

1. Откройте TVHome и удерживайте OK на значке микрофона в верхней панели.
2. В настройках голосового помощника переключите «Системный» на **«Google от лаунчера»**. Дождитесь сообщения об успешном переключении.
3. Выберите источник: **«Микрофон: Пульт»** или **«Микрофон: ТВ»**.
4. Проверьте распознавание, например командой «Открой NUM», если NUM установлен.

Для микрофона пульта проверьте физическую кнопку микрофона. Если при нажатии значка на экране появляется подсказка нажать кнопку пульта, следуйте ей.

Режим «Микрофон: ТВ» требует **Android 13 и новее** и встроенного микрофона, доступного Android. В этом режиме USB-микрофон и микрофон Bluetooth-пульта не используются как замена встроенному.

ADB-помощник работает до перезагрузки телевизора. После перезагрузки для дальнейшего переключения помощников снова выполните последнюю команду с `nohup`. Это не означает, что уже выбранный голосовой помощник обязательно перестанет работать: отдельно проверьте его кнопкой пульта.

`Install-RemoteBridge.ps1` тоже запускает ADB-помощник, но сначала проверяет конкретный совместимый пульт TVC0GWBT. Для настройки только голоса выше приведён отдельный запуск без установки мостика кнопок.

### 6. Решение проблем

| Проблема | Что сделать |
| --- | --- |
| `adb` не найден | Откройте PowerShell в папке Platform-Tools и используйте `.\adb.exe`. |
| `unauthorized` | Подтвердите запрос отладки на экране ТВ, затем повторите `devices`. |
| `offline` или не удаётся подключиться | Проверьте общую сеть, IP, порт и включённую отладку. Для беспроводной отладки различайте порты сопряжения и подключения. |
| APK не устанавливается | Сохраните полный текст ошибки. Не удаляйте установленную версию ради повторной попытки: это может привести к потере данных. |
| Home открывает штатный экран | Повторно проверьте ответ `set-home-activity`. Если команда успешна, но кнопка ведёт иначе, сообщите модель ТВ и версию Android: прошивка может обрабатывать Home отдельно. |
| Значок микрофона пишет «Недоступно на этом телевизоре» | Проверьте выбранного помощника удержанием значка. Для режима TVHome выполните подготовку из раздела 5. |
| «Не удалось переключить помощника. Проверьте запуск ADB-помощника управления Katniss» | Проверьте выдачу `WRITE_SECURE_SETTINGS` и повторите запуск `assistant-package-guard.sh`. После перезагрузки его нужно запускать заново. |
| Переключатель всё равно не работает | Сохраните вывод `pm path` для обоих Google-пакетов и содержимое лога командой ниже. |
| Распознавание речи недоступно | Проверьте наличие и состояние Google-компонентов, подключение к интернету и работу штатного голосового ввода с пульта. Наличие одного APK TVHome недостаточно. |
| «Нужен Android 13 или новее» / «Микрофон ТВ недоступен» | Выбран режим встроенного микрофона. Проверьте версию Android и наличие микрофона; если есть голосовой пульт, выберите «Микрофон: Пульт». |
| Микрофон не слышит речь | Проверьте выбранный источник и разрешение на запись звука. Для пульта проверьте его сопряжение с ТВ и работу кнопки микрофона. |

Получить лог ADB-помощника:

```powershell
.\adb.exe -s IP:PORT shell cat /data/local/tmp/assistant-package-guard.log
```

Для обращения за помощью приложите модель ТВ/приставки, версию Android, версию TVHome, выбранный источник микрофона, точный текст ошибки и укажите, работает ли голос с физической кнопки пульта.

### 7. Как вернуть прежние настройки

Чтобы вернуть голосового помощника, пока ADB-помощник запущен, удерживайте значок микрофона и переключите «Google от лаунчера» на «Системный». Дождитесь успешного переключения и проверьте штатный голосовой поиск. Если переключение не прошло, сохраните ошибку — возврат нельзя считать завершённым.

Чтобы вернуть прежний главный экран, используйте сохранённый в разделе 4 компонент:

```powershell
.\adb.exe -s IP:PORT shell cmd package set-home-activity ПАКЕТ/КЛАСС_ПРЕЖНЕГО_ЛАУНЧЕРА
```

Замените последний аргумент целиком, затем проверьте Home. Сброс телевизора для этих действий не нужен.

После завершения настройки можно отключить отладку и отозвать разрешения отладки в настройках ТВ. Для повторного запуска ADB-помощника понадобится снова включить и разрешить подключение.

## Бонус: обои / Bonus wallpapers

[Скачать ZIP с 67 обоями и посмотреть превью каждой папки / Download 67 wallpapers and browse folder previews](docs/wallpapers/README.md). Обои с дизерингом: плавные градиенты без заметных ступенек бандинга на ТВ. / Dithered wallpapers for smoother TV gradients with less visible banding.

## Скриншоты / Screenshots

[Галерея: 9 снимков с настоящего ТВ и настройки кратко / Nine real TV screenshots and a brief settings guide](docs/screenshots/README.md)

| Поиск «Терминатор» / Search | Оформление / Appearance | Помощь / Help |
|---|---|---|
| ![Поиск Терминатор](docs/screenshots/09-terminator-search.png) | ![Оформление](docs/screenshots/02-appearance-settings.png) | ![Помощь](docs/screenshots/08-help-and-update-check.png) |

## Обновления и совместимость — русский

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

The APK contains everything needed for the basic launcher installation. External apps (Yandex Browser, NUM and other providers) must be installed separately. Floating clocks require permission to display over other apps; microphone input requires audio recording permission. The installers do not grant these permissions or configure the system voice assistant; the complete voice setup on a fresh TV is not automated.

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
.\install.ps1 -Device "TV_ADDRESS:PORT" -Apk ".\TVHome-2.0.3.apk" -Adb "C:\platform-tools\adb.exe"
```

macOS / Linux:

```sh
sh ./install.sh "TV_ADDRESS:PORT" "./TVHome-2.0.3.apk" "./platform-tools/adb"
```

If PowerShell blocks a downloaded script, review its contents and use a single invocation with `powershell -NoProfile -ExecutionPolicy Bypass -File .\install.ps1 -Device "TV_ADDRESS:PORT" -Apk ".\TVHome-2.0.3.apk" -Adb "C:\platform-tools\adb.exe"`. This does not change the persistent system execution policy.

The scripts require an explicit device, check ADB authorization and API 26+, back up any discoverable installed APK to `backups/`, run `install -r`, and launch `ru.pda.tvhome/ru.pda.tvhome.MainActivity` only after installation reports `Success`. Installation errors stop the script. They do not clear app data, disable the stock Home, change default apps or modify accessibility services. Choose the Home app in system Settings yourself. Use these standalone installers for this repository rather than the legacy upstream `build-and-install` script.

Manual alternative from the Platform-Tools directory:

```powershell
.\adb.exe connect TV_ADDRESS:PORT
.\adb.exe -s TV_ADDRESS:PORT install -r "C:\TV\TVHome-2.0.3.apk"
.\adb.exe -s TV_ADDRESS:PORT shell am start -W -n ru.pda.tvhome/ru.pda.tvhome.MainActivity
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
{"versionCode":86,"versionName":"2.0.3","apkName":"TVHome-2.0.3.apk","sha256":"<64 hexadecimal characters>"}
```

Приложение читает Releases API без авторизации; токенов GitHub внутри APK нет. Проверяются SHA-256, packageName, повышение versionCode и совпадение сертификатов подписи перед передачей APK системному установщику.

## Команда Алисы / Alice voice command

С версии 1.4.21: «Алиса, <сообщение>» открывает страницу Алисы в установленном Яндекс Браузере и автоматически отправляет текст. Просто «Алиса» открывает страницу без сообщения. Команда внутри сообщения не выполняется локально: «Алиса, выключи телевизор» отправит эти слова Алисе. Яндекс Браузер для ТВ (`com.yandex.browser.tv`) имеет приоритет; также поддерживается Android Яндекс Браузер (`com.yandex.browser`).

Since 1.4.21, say “Алиса, <message>” to open Alice in Yandex Browser and automatically submit the message. “Алиса” alone opens the page without sending a message. The message is not interpreted as a local TV command. Yandex Browser for TV is preferred; the Android Yandex Browser package is also supported.

В 2.0.0 отключена и анимация возврата приложения в плитку. Пружинная обводка навигации сохранена; при запуске выбирается первая плитка, при возвращении — запомненное приложение. In 2.0.0 the return-to-tile animation is removed too. Spring navigation remains; cold starts select the first tile and returning restores the remembered app.

## Возможности форка

Поиск медиа через сайты и публичные провайдеры установленных приложений; встроенные видеообои и пользовательские локальные коллекции; анимация статичных обоев; настройка пульта, плиток, часов и оформления. Для VK/Кинопоиска доступность карточек зависит от страницы сайта/авторизации и поддержки ссылок установленным приложением. Импорт скачанных файлов обозначен отдельно от автоматической интеграции источников.

## Проверка

Для 2.0.3 выполнены `testReleaseUnitTest`, `lintRelease`, `assembleRelease`. Код проверки обновлений покрыт JVM-тестами правил URL, имени APK и SHA-256. Проверка визуального интерфейса и установки обновления на реальном ТВ обязательна отдельно.

Проверка установщиков / Installer checks: `python -m unittest discover -s tests -v` (PowerShell и Bash запускаются с подставным ADB; телевизор не меняется / fake ADB, no TV changes).
