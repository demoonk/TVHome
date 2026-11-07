#requires -Version 5.1
$ErrorActionPreference = 'Stop'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$work = Join-Path ([Environment]::GetFolderPath('LocalApplicationData')) ('TVHome/Installer/' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $work -Force | Out-Null
Write-Host 'Установка TVHome: ADB и приложение будут скачаны автоматически.'
Write-Host 'На ТВ: Настройки → Об устройстве → несколько раз нажмите «Сборка», чтобы включить режим разработчика.'
Write-Host 'В настройках разработчика включите отладку USB / ADB по сети. Названия зависят от прошивки.'
Write-Host 'Подключите ТВ и компьютер к одной сети. Узнайте IP ТВ и порт ADB в настройках сети / беспроводной отладки.'
Write-Host 'При подключении подтвердите на ТВ разрешение отладки для компьютера.'
Write-Host 'Установщик назначит TVHome главным экраном и выдаст права для голосового поиска и часов.'
function Read-Endpoint([string]$Prompt, [switch]$DefaultPort) {
    while ($true) {
        $value = (Read-Host $Prompt).Trim()
        if ($DefaultPort -and $value -match '^\d{1,3}(\.\d{1,3}){3}$') { $value += ':5555' }
        if ($value -match '^(\d{1,3}(?:\.\d{1,3}){3}):(\d{1,5})$') {
            $ipText = $Matches[1]; $port = [int]$Matches[2]
            if ((@($ipText.Split('.') | Where-Object { [int]$_ -gt 255 }).Count -eq 0) -and $port -ge 1 -and $port -le 65535) { return $value }
        }
        Write-Host 'Введите IPv4 и порт, например 192.168.1.50:5555.'
    }
}
$Device = Read-Endpoint 'IP ТВ и порт подключения (без порта — 5555, если он подходит вашему ТВ)' -DefaultPort
Write-Host 'Скачиваю официальный Android SDK Platform-Tools с сайта Google…'
$zip = Join-Path $work 'platform-tools.zip'
Invoke-WebRequest -UseBasicParsing -Uri 'https://dl.google.com/android/repository/platform-tools-latest-windows.zip' -OutFile $zip
Expand-Archive -LiteralPath $zip -DestinationPath $work
$adbPath = Join-Path $work 'platform-tools/adb.exe'
if (-not (Test-Path -LiteralPath $adbPath -PathType Leaf)) { throw 'В архиве Google не найден adb.exe' }
function Invoke-Adb {
    param([string[]]$Arguments, [switch]$AllowFailure)
    $savedPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $result = (& $adbPath @Arguments 2>&1 | Out-String).Trim()
        $code = $LASTEXITCODE
    } finally { $ErrorActionPreference = $savedPreference }
    if (($code -ne 0 -or $result -match '(?im)^Error:|Exception|SecurityException') -and -not $AllowFailure) { throw "ADB: $result" }
    return $result
}
$pair = Read-Host 'На ТВ есть беспроводная отладка с кодом сопряжения? (да / Enter — нет)'
if ($pair -match '^(да|д|yes|y)$') {
    $pairEndpoint = Read-Endpoint 'IP ТВ и порт СОПРЯЖЕНИЯ с экрана ТВ (не порт подключения)'
    & $adbPath pair $pairEndpoint
    if ($LASTEXITCODE -ne 0) { throw 'Не удалось выполнить сопряжение. Проверьте код и порт.' }
}
do {
    Write-Output (Invoke-Adb -Arguments @('connect', $Device) -AllowFailure)
    $state = Invoke-Adb -Arguments @('-s', $Device, 'get-state') -AllowFailure
    if ($state -eq 'device') { break }
    Write-Host 'Подтвердите отладку на ТВ. Проверьте адрес, порт и общую сеть.'
    $answer = Read-Host 'Enter — повторить; Q — выйти'
    if ($answer -eq 'q') { throw 'Подключение отменено' }
} while ($true)
$sdkText = Invoke-Adb -Arguments @('-s', $Device, 'shell', 'getprop', 'ro.build.version.sdk')
$sdk = 0
if (-not [int]::TryParse($sdkText, [ref]$sdk) -or $sdk -lt 26) { throw 'Нужен Android 8.0 или новее (API 26)' }
Write-Host 'Скачиваю последний выпуск TVHome с GitHub…'
$release = Invoke-RestMethod -Uri 'https://api.github.com/repos/demoonk/TVHome/releases/latest' -Headers @{ 'User-Agent' = 'TVHome-Installer' }
$metadataAsset = @($release.assets | Where-Object { $_.name -eq 'update.json' })
if ($metadataAsset.Count -ne 1) { throw 'В релизе не найден update.json' }
$metadataPath = Join-Path $work 'update.json'
Invoke-WebRequest -UseBasicParsing -Uri $metadataAsset[0].browser_download_url -OutFile $metadataPath
$metadata = Get-Content -LiteralPath $metadataPath -Raw | ConvertFrom-Json
if ($metadata.apkName -notmatch '^TVHome-[0-9][0-9A-Za-z.\-]*\.apk$' -or $metadata.sha256 -notmatch '^[a-fA-F0-9]{64}$') { throw 'Некорректные данные APK в update.json' }
$apkAsset = @($release.assets | Where-Object { $_.name -eq $metadata.apkName })
if ($apkAsset.Count -ne 1) { throw 'APK из update.json не найден в релизе' }
$apkPath = Join-Path $work $metadata.apkName
Invoke-WebRequest -UseBasicParsing -Uri $apkAsset[0].browser_download_url -OutFile $apkPath
if ((Get-FileHash -LiteralPath $apkPath -Algorithm SHA256).Hash -ne $metadata.sha256) { throw 'Контрольная сумма APK не совпала. Установка остановлена.' }
$package = 'ru.pda.tvhome'
$previous = [ordered]@{
    device = $Device
    home = Invoke-Adb -Arguments @('-s', $Device, 'shell', 'cmd', 'package', 'resolve-activity', '--brief', '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.HOME')
    assistant = Invoke-Adb -Arguments @('-s', $Device, 'shell', 'settings', 'get', 'secure', 'assistant')
    voiceInteractionService = Invoke-Adb -Arguments @('-s', $Device, 'shell', 'settings', 'get', 'secure', 'voice_interaction_service')
}
$previous | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $work 'previous-settings.json') -Encoding UTF8
Write-Host "Прежние настройки сохранены: $work\previous-settings.json"
$old = Invoke-Adb -Arguments @('-s', $Device, 'shell', 'pm', 'path', $package) -AllowFailure
$paths = @($old -split "`r?`n" | Where-Object { $_.StartsWith('package:') })
if ($paths.Count -gt 0) {
    $backup = Join-Path $work 'backups'
    New-Item -ItemType Directory -Path $backup -Force | Out-Null
    for ($index = 0; $index -lt $paths.Count; $index++) {
        Write-Output (Invoke-Adb -Arguments @('-s', $Device, 'pull', $paths[$index].Substring(8).Trim(), (Join-Path $backup "installed-$index.apk")))
    }
}
$installed = Invoke-Adb -Arguments @('-s', $Device, 'install', '-r', $apkPath)
Write-Output $installed
if ($installed -notmatch '(?m)^Success\s*$') { throw 'Установка не подтвердила Success; настройка остановлена' }
foreach ($operation in @('SYSTEM_ALERT_WINDOW', 'GET_USAGE_STATS')) {
    Write-Output (Invoke-Adb -Arguments @('-s', $Device, 'shell', 'appops', 'set', $package, $operation, 'allow'))
    $permission = Invoke-Adb -Arguments @('-s', $Device, 'shell', 'appops', 'get', $package, $operation)
    if ($permission -notmatch ($operation + ': allow(?:;|\s|$)')) { throw "Не подтверждено разрешение ${operation}: $permission" }
}
foreach ($permission in @('android.permission.WRITE_SECURE_SETTINGS', 'android.permission.RECORD_AUDIO')) {
    Write-Output (Invoke-Adb -Arguments @('-s', $Device, 'shell', 'pm', 'grant', $package, $permission))
    $check = Invoke-Adb -Arguments @('-s', $Device, 'shell', 'dumpsys', 'package', $package)
    if ($check -notmatch ([regex]::Escape($permission) + ': granted=true')) { throw "Не подтверждено разрешение ${permission}: $check" }
}
Write-Output (Invoke-Adb -Arguments @('-s', $Device, 'shell', 'cmd', 'package', 'set-home-activity', 'ru.pda.tvhome/ru.pda.tvhome.MainActivity'))
$resolved = Invoke-Adb -Arguments @('-s', $Device, 'shell', 'cmd', 'package', 'resolve-activity', '--brief', '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.HOME')
if ($resolved -notmatch 'ru\.pda\.tvhome/(ru\.pda\.tvhome\.)?MainActivity') { throw "Не удалось подтвердить Home TVHome: $resolved" }
$googleReady = $true
foreach ($googlePackage in @('com.google.android.tts', 'com.google.android.katniss')) {
    $found = Invoke-Adb -Arguments @('-s', $Device, 'shell', 'pm', 'path', $googlePackage) -AllowFailure
    if ($found -notmatch '(?m)^package:') {
        Write-Warning "Не найден $googlePackage. Голосовой режим Google от лаунчера может быть недоступен."
        $googleReady = $false
    }
}
if ($googleReady) {
    # Embedded helper from tools/assistant-package-guard.sh; no extra download is required.
    $guardBase64 = 'IyEvc3lzdGVtL2Jpbi9zaAojIFN0YXJ0ZWQgZXhwbGljaXRseSB2aWEgQURCOyBubyBuZXR3b3JrIGxpc3RlbmVyLCBhcmJpdHJhcnkgY29tbWFuZHMsIG9yIGtleSBmaWx0ZXJpbmcuCiMgUmVxdWVzdHMgYW5kIGFja25vd2xlZGdlbWVudHMgcmVxdWlyZSBXUklURV9TRUNVUkVfU0VUVElOR1MgKG5vdCBhbiBvcmRpbmFyeSBhcHAgcGVybWlzc2lvbikuCnBpZF9maWxlPS9kYXRhL2xvY2FsL3RtcC9hc3Npc3RhbnQtcGFja2FnZS1ndWFyZC5waWQKaWYgWyAtZiAiJHBpZF9maWxlIiBdOyB0aGVuCiAgICBwcmV2aW91c19waWQ9JChjYXQgIiRwaWRfZmlsZSIpCiAgICBjYXNlICIkcHJldmlvdXNfcGlkIiBpbgogICAgICAgICpbITAtOV0qfCcnKSA7OwogICAgICAgICopCiAgICAgICAgICAgIHByZXZpb3VzX2NvbW1hbmQ9JCh0ciAnXDAwMCcgJyAnIDwgIi9wcm9jLyRwcmV2aW91c19waWQvY21kbGluZSIgMj4vZGV2L251bGwpCiAgICAgICAgICAgIGNhc2UgIiRwcmV2aW91c19jb21tYW5kIiBpbgogICAgICAgICAgICAgICAgKiJzaCAvZGF0YS9sb2NhbC90bXAvYXNzaXN0YW50LXBhY2thZ2UtZ3VhcmQuc2giKikgZXhpdCAwIDs7CiAgICAgICAgICAgIGVzYWMKICAgICAgICAgICAgOzsKICAgIGVzYWMKZmkKZWNobyAkJCA+ICIkcGlkX2ZpbGUiCnRyYXAgJ3JtIC1mICIkcGlkX2ZpbGUiOyBleGl0JyBIVVAgSU5UIFRFUk0KcmVxdWVzdF9rZXk9cnVfcGRhX3R2aG9tZV9hc3Npc3RhbnRfcmVxdWVzdAphY2tfa2V5PXJ1X3BkYV90dmhvbWVfYXNzaXN0YW50X2FjawpwYWNrYWdlPWNvbS5nb29nbGUuYW5kcm9pZC5rYXRuaXNzCnNldHRpbmdzIGRlbGV0ZSBzZWN1cmUgIiRhY2tfa2V5IiA+L2Rldi9udWxsCmxhc3RfcmVxdWVzdD0Kd2hpbGUgcG0gcGF0aCBydS5wZGEudHZob21lID4vZGV2L251bGwgMj4mMTsgZG8KICAgIHJlcXVlc3Q9JChzZXR0aW5ncyBnZXQgc2VjdXJlICIkcmVxdWVzdF9rZXkiKQogICAgaWYgWyAiJHJlcXVlc3QiICE9ICIkbGFzdF9yZXF1ZXN0IiBdOyB0aGVuCiAgICAgICAgbW9kZT0ke3JlcXVlc3QlJToqfQogICAgICAgIGNhc2UgIiRtb2RlIiBpbgogICAgICAgICAgICBjaGVjaykgc2V0dGluZ3MgcHV0IHNlY3VyZSAiJGFja19rZXkiICIkcmVxdWVzdCIgOzsKICAgICAgICAgICAgbGF1bmNoZXIpCiAgICAgICAgICAgICAgICBzZWxlY3RlZD0kKHNldHRpbmdzIGdldCBzZWN1cmUgdm9pY2VfaW50ZXJhY3Rpb25fc2VydmljZSkKICAgICAgICAgICAgICAgIGlmIFsgIiRzZWxlY3RlZCIgPSAicnUucGRhLnR2aG9tZS9ydS5wZGEudHZob21lLkxhdW5jaGVyQXNzaXN0YW50U2VydmljZSIgXTsgdGhlbgogICAgICAgICAgICAgICAgICAgIGlmIHBtIGRpc2FibGUtdXNlciAtLXVzZXIgMCAiJHBhY2thZ2UiID4vZGV2L251bGwgMj4mMTsgdGhlbgogICAgICAgICAgICAgICAgICAgICAgICAjIFBhY2thZ2UgcmVtb3ZhbCBjYW4gY2xlYXIgdGhlIHNlbGVjdGVkIGFzc2lzdGFudCBldmVuIHdoZW4gYW5vdGhlciBvbmUgd2FzCiAgICAgICAgICAgICAgICAgICAgICAgICMgc2VsZWN0ZWQgZmlyc3QuIFJlYXNzZXJ0IG91ciBlbnRyeSBhZnRlciB0aGF0IHBhY2thZ2Ugb3BlcmF0aW9uIGNvbXBsZXRlcy4KICAgICAgICAgICAgICAgICAgICAgICAgc2V0dGluZ3MgcHV0IHNlY3VyZSBhc3Npc3RhbnQgcnUucGRhLnR2aG9tZS9ydS5wZGEudHZob21lLkxhdW5jaGVyQXNzaXN0YW50U2VydmljZQogICAgICAgICAgICAgICAgICAgICAgICBzZXR0aW5ncyBwdXQgc2VjdXJlIHZvaWNlX2ludGVyYWN0aW9uX3NlcnZpY2UgcnUucGRhLnR2aG9tZS9ydS5wZGEudHZob21lLkxhdW5jaGVyQXNzaXN0YW50U2VydmljZQogICAgICAgICAgICAgICAgICAgICAgICBzZXR0aW5ncyBwdXQgc2VjdXJlICIkYWNrX2tleSIgIiRyZXF1ZXN0IgogICAgICAgICAgICAgICAgICAgIGZpCiAgICAgICAgICAgICAgICBmaQogICAgICAgICAgICAgICAgOzsKICAgICAgICAgICAgc3lzdGVtKQogICAgICAgICAgICAgICAgaWYgcG0gZW5hYmxlIC0tdXNlciAwICIkcGFja2FnZSIgPi9kZXYvbnVsbCAyPiYxOyB0aGVuCiAgICAgICAgICAgICAgICAgICAgc2V0dGluZ3MgcHV0IHNlY3VyZSAiJGFja19rZXkiICIkcmVxdWVzdCIKICAgICAgICAgICAgICAgIGZpCiAgICAgICAgICAgICAgICA7OwogICAgICAgIGVzYWMKICAgICAgICBsYXN0X3JlcXVlc3Q9JHJlcXVlc3QKICAgIGZpCiAgICBzbGVlcCAxCmRvbmUK'
    $guardPath = Join-Path $work 'assistant-package-guard.sh'
    [IO.File]::WriteAllBytes($guardPath, [Convert]::FromBase64String($guardBase64))
    Write-Output (Invoke-Adb -Arguments @('-s', $Device, 'push', $guardPath, '/data/local/tmp/assistant-package-guard.sh'))
    Write-Output (Invoke-Adb -Arguments @('-s', $Device, 'shell', 'nohup sh /data/local/tmp/assistant-package-guard.sh >/data/local/tmp/assistant-package-guard.log 2>&1 </dev/null &'))
    $request = 'check:' + [guid]::NewGuid().ToString('N')
    Write-Output (Invoke-Adb -Arguments @('-s', $Device, 'shell', 'settings', 'put', 'secure', 'ru_pda_tvhome_assistant_request', $request))
    $ack = ''
    for ($attempt = 0; $attempt -lt 10; $attempt++) {
        Start-Sleep -Milliseconds 500
        $ack = Invoke-Adb -Arguments @('-s', $Device, 'shell', 'settings', 'get', 'secure', 'ru_pda_tvhome_assistant_ack')
        if ($ack -eq $request) { break }
    }
    if ($ack -ne $request) { throw 'ADB-помощник не подтвердил запуск. Проверьте /data/local/tmp/assistant-package-guard.log на ТВ.' }
}
$started = Invoke-Adb -Arguments @('-s', $Device, 'shell', 'am', 'start', '-W', '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.HOME', '-n', 'ru.pda.tvhome/ru.pda.tvhome.MainActivity')
Write-Output $started
Write-Host 'TVHome установлен и назначен главным экраном. Права для голоса и часов выданы и проверены.'
Write-Host 'Включите «Оверлейные часы» в настройках TVHome, если нужны часы поверх других приложений.'
if ($googleReady) {
    Write-Host 'Для голоса: удерживайте OK на значке микрофона → «Google от лаунчера» → выберите «Пульт» или «ТВ».'
    Write-Host 'Этот выбор может отключить системного Google-помощника Katniss. Возврат — выбор «Системный» в том же меню.'
    Write-Host 'Источник «ТВ» требует Android 13+ и встроенного микрофона. ADB-помощник работает до перезагрузки.'
    Write-Host 'Для повторного переключения помощника после перезагрузки можно снова запустить установщик.'
} else {
    Write-Warning 'Голосовой режим не настроен: отсутствуют компоненты Google. Home и разрешения настроены.'
}
Write-Host "Файлы, резервная копия APK и прежние настройки: $work"
Write-Host 'Проверьте Home и голос с пульта. После настройки отладку можно отключить.'
