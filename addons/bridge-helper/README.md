# Bridge helper

Виджет-помощник для TVHome: bridge клавиши-шестерёнки и ассистент-гард исчезают после перезагрузки или рестарта
фреймворка, потому что только shell-домен читает `/dev/input` и запускает `app_process`. Обычное приложение этого
не может — но приложение, которое подключается к `adbd` как ADB-клиент, получает выполнение команды как uid 2000
shell. Само оно ничего не запускает: оно отвечает на одно действие
(`io.github.sumyabazar1.bridgehelper.action.ENSURE`), которое присылает строка настроек лаунчера
«Система» → «Запросить загрузку сейчас», когда хозяин её нажимает, или когда приложение открывают. Подключается к
`adbd` на `127.0.0.1:5555`, авторизуется своим ключом и выполняет ровно одну команду:

```
sh /data/local/tmp/tvhome-bridge-ensure.sh
```

Скрипт уже лежит на ТВ (`tools/tvhome-bridge-ensure.sh`, идмпотентный, его устанавливает `Setup-TVHome.ps1`) —
это приложение ничего не pushит само. Выход команды читается ограниченное время, пишется в log, и приёмник
завершается.

## Ключ и одноразовая авторизация

Ключ RSA (2048 бит) генерируется один раз при первом запуске и хранится как PKCS#8 PEM в собственном каталоге
файлов приложения; на каждом boot используется тот же ключ. ТВ хранит отпечаток (fingerprint) тех байтов,
которые получает, поэтому приложение просто должно быть самосогласованным: струк pub-ключа отправляется
одинаково в каждом запуске (int32 количество слов little-endian, modulus как 8-байтовые little-endian слова,
весь modulus записан байт-наоборот, int32 экспонента 65537, base64 стандартным алфавитом без padding).

Подключение идёт в том же порядке, что у adb: приложение шлёт CNXN, adbd отвечает challenge'ем `AUTH` типа 1,
приложение подписывает 20-байтный токен и отправляет подпись кадром `AUTH` типа 2. Если ключ уже известен ТВ,
adbd сразу отвечает CNXN — без диалога. Если нет, adbd присылает новый challenge, и приложение отправляет саму
строку ключа кадром `AUTH` типа 3 — именно этот кадр ТВ запоминает и показывает в диалоге; ответ на диалог
приходит по этому же соединению.

Новый ключ автоматически не принимается: первое подключение висит, пока хозяин не нажмёт Allow
в диалоге авторизации на ТВ. На прошивке гостиной (проверено 2026-11-11) ключ не сохраняется между загрузками:
после `reboot` ТВ спрашивает один раз снова, уже после загрузки лаунчера. Одно подтверждение за загрузку —
достаточно, после него мост работает. Если adbd отказал ключу или не отвечает — приложение пишет это в log
ясно и завершается, не крутясь.

**Исправление 1.7 (2026-11-11).** В версиях до 1.7 кадры были перепутаны: ключ уходил типом 1 (adbd его
игнорирует), а 256-байтная подпись — типом 3 (adbd читал её как публичный ключ). В диалоге показывалась подпись,
framework писал `error doing base64 decoding`, после Allow в `/data/misc/adb/adb_keys` сохранялись бинарные байты,
а настоящий ключ не авторизовывался никогда. Поэтому мост не запускался, а диалог не помогал. В 1.7 порядок
кадров стандартный, и после одного Allow мост действительно поднимается; бинарные строки, оставшиеся в
`adb_keys`, adbd пропускает с `Invalid base64 key` (при желании — «Отозвать авторизации отладки» один раз).

Файл, который положил хозяин, используется только когда приложение может его прочитать. Файл, созданный adb
в папке приложения, принадлежит shell (uid 2000, режим 0640), и приложение его не читает: запуск падал с
`AccessDeniedException`, не доходя до собственного ключа. Теперь нечитаемый файл пропускается, в log пишется
почему, и берётся ключ приложения. Значит на чистой прошивке всё работает без файлов: приложение создаёт свой
ключ в собственном каталоге и просит Allow один раз. Если ключ всё же кладят через adb, файл нужно сделать
читаемым для приложения (`chmod 644`) и положить именно во внешнюю папку приложения, а не в папку с похожим
именем в корне общего хранилища.

## Адрес подключения

Приложение работает на самом ТВ, поэтому ему достаточно loopback: `127.0.0.1:5555`. Выбирать маршрут не нужно —
adbd обслуживает одно подключение за раз, и пока adb-клиент на компьютере подключён, это подключение не
дожидается до таймаута.

Вариант с определением адреса ТВ в LAN убран. Раньше казалось, что при одном и том же ключе подключение с
компьютера по LAN проходило, а приложению по loopback приходил challenge; причина была не в маршруте, а в
перепутанных типах AUTH-кадров (см. «Исправление 1.7»). Маршрут ничего не решает — ключ нужно утвердить один раз.

## Connection address

The app runs on the TV itself, so loopback is enough: `127.0.0.1:5555`. There is no route to choose — adbd serves
one client at a time, and while the adb client on the PC is connected this connect waits until the connect timeout.

The variant that detected the TV's address on the LAN was removed. It once looked as if, with the same key, a
connection from the PC over the LAN was accepted while the app's loopback connection was challenged; the cause was
not the route but the swapped AUTH frame types (see "Fix 1.7"). The address decides nothing by itself — the key
still has to be approved once.

## Как запускается

Приложение ничего не делает само по себе: `BOOT_COMPLETED` больше не слушается, разрешение
`RECEIVE_BOOT_COMPLETED` убрано. Job выполняется, когда строка настроек лаунчера «Система» → «Запросить загрузку
сейчас» присылает действие `ENSURE`, или когда приложение открывают. Одна строка решает, спрашивает ли Home сам:
«Загрузка моста» — «Вручную» (по умолчанию) или «При старте Home». Каждый запрос — одно подключение, то есть
одна возможность диалога на экране, поэтому приложение не соединяется само и не повторяет запрос.

## How it starts

The app does nothing on its own: `BOOT_COMPLETED` is no longer listened for and the `RECEIVE_BOOT_COMPLETED`
permission is gone. The job runs when Home's settings row «Система» → «Запросить загрузку сейчас» sends the
`ENSURE` action, or when the app is opened. One row decides whether Home asks on its own: «Загрузка моста» is
«Вручную» (manual, the default) or «При старте Home». Every ask is one connection, so it is one chance of a dialog
on the screen; the app does not connect by itself and does not repeat an ask.

## Права

| Permission | Why |
|---|---|
| `INTERNET` | Подключение к `adbd` на `127.0.0.1:5555` — больше никаких сетей. |

Ничего больше. Нет UI, телеметрии, сторонних библиотек и фонового сервиса; `allowBackup=false`. Receiver
слушает только действие `ENSURE`, которое присылает строка настроек лаунчера, работает через `goAsync()` с
ограниченным таймаутом и максимум тремя попытками.

## Отклонение от правила upstream

Upstream-TVHome rule: «close ADB afterwards». This add-on by design deviates from it: it works only while
ADB debugging stays enabled, and that was the owner's explicit choice — he turns it on and keeps it on. The key
is approved with Allow; on this firmware the TV asks once at every boot (it does not keep the key between boots),
and everything works after that one confirmation — but while debugging is off the app is useless, and that is
expected behavior, not a bug.

## Сборка и установка

JDK 17 и Android SDK platform 34 с build-tools 34.0.0, как для лаунчера:

```bash
cd addons/bridge-helper && ./gradlew testReleaseUnitTest lintRelease assembleRelease
```

Release собирается с debug-ключом, потому что устанавливается по ADB, как лаунчер:

```bash
./gradlew assembleRelease -PtvhomeKeystore=C:/private/TVHome.keystore
```

Готовый APK публикуется ассетом релиза как `bridge-helper-<версия>.apk`; установщик `Setup-TVHome.ps1`
скачивает его с проверкой SHA-256 и ставит сам, если ассет есть в выпуске.

Тесты — чистый JVM (JUnit 4, `src/test/java`): фрейминг сообщений, структура pub-ключа, base64 без padding
и строка команды. Никакого Robolectric: протокольная логика lives in plain classes, receiver их только вызывает.
