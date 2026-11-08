#requires -Version 5.1
param(
    [switch]$Offline,
    [int]$MaxDownloadAttempts = 5,
    [switch]$NoWeather,
    # Замокательный список (see launcher FreezableApps.java): applied over ADB, because the
    # launcher itself cannot disable another package without privileged rights.
    [string[]]$Freeze = @('com.mediatek.wwtv.setupwizard', 'com.google.android.katniss')
)
$ErrorActionPreference = 'Stop'
try {
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12, [Net.SecurityProtocolType]::Tls13
} catch {
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
}
$work = Join-Path ([Environment]::GetFolderPath('LocalApplicationData')) ('TVHome/Installer/' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $work -Force | Out-Null
$cache = Join-Path ([Environment]::GetFolderPath('LocalApplicationData')) 'TVHome/Installer/cache'
New-Item -ItemType Directory -Path $cache -Force | Out-Null
function Invoke-NetworkRequest {
    param([scriptblock]$Request, [switch]$AllowFailure)
    for ($attempt = 1; $attempt -le $MaxDownloadAttempts; $attempt++) {
        try { return (& $Request) }
        catch {
            $status = 0
            $response = $null
            if ($_.Exception.Response) {
                $response = $_.Exception.Response
                $status = [int]$response.StatusCode
            }
            $temporary = $status -eq 0 -or $status -in @(408, 429, 500, 502, 503, 504)
            # GitHub announces temporary overload and anonymous API limits with
            # Retry-After. Waiting the announced pause works better than blind retries.
            $delay = 0
            if ($response) {
                try {
                    $retryAfter = $response.Headers['Retry-After']
                    if ($retryAfter -match '^\d+$') { $delay = [int]$retryAfter }
                    elseif ($retryAfter) {
                        $when = [DateTime]::Parse($retryAfter, [Globalization.DateTimeFormatInfo]::InvariantInfo, [Globalization.DateTimeStyles]::AdjustToUniversal)
                        $delay = [Math]::Max(1, [int](($when - [DateTime]::UtcNow).TotalSeconds))
                    }
                } catch { $delay = 0 }
            }
            if ($delay -le 0) { $delay = [Math]::Min(10, 2 * $attempt) + (Get-Random -Maximum 2) }
            $delay = [Math]::Min(60, $delay)
            if (-not $temporary -or $attempt -eq $MaxDownloadAttempts) {
                if ($AllowFailure) { return $null }
                throw "Не удалось скачать данные после попытки ${attempt}: $($_.Exception.Message). Уже скачанные файлы сохранены. Запустите установщик повторно позже."
            }
            Write-Host "Временная ошибка загрузки ($status). Повтор $($attempt + 1)/$MaxDownloadAttempts через $delay сек…"
            Start-Sleep -Seconds $delay
        }
    }
    return $null
}
function Get-CachedDownload {
    param([string]$Uri, [string]$Path, [string]$Sha256 = '')
    if (Test-Path -LiteralPath $Path -PathType Leaf) {
        $valid = (Get-Item -LiteralPath $Path).Length -gt 0
        if ($valid -and $Sha256) { $valid = (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash -eq $Sha256 }
        if ($valid) { Write-Host "Использую скачанный файл: $Path"; return $true }
        Write-Host "Файл неполный или повреждён, скачиваю заново: $Path"
    }
    if ($Offline) { return $false }
    $partial = $Path + '.part'
    # Invoke-WebRequest writes the file and returns no object when -OutFile is used, so the
    # result of a download is judged by the written file, not by the return value of the call.
    Invoke-NetworkRequest -AllowFailure { Invoke-WebRequest -UseBasicParsing -Uri $Uri -OutFile $partial -TimeoutSec 180 } | Out-Null
    if (-not (Test-Path -LiteralPath $partial -PathType Leaf)) { return $false }
    if ((Get-Item -LiteralPath $partial).Length -eq 0) { throw "Скачан пустой файл: $Uri" }
    if ($Sha256 -and (Get-FileHash -LiteralPath $partial -Algorithm SHA256).Hash -ne $Sha256) { throw 'Контрольная сумма APK не совпала. Установка остановлена.' }
    Move-Item -LiteralPath $partial -Destination $Path -Force
    return $true
}
# Release asset URL without the API: the "latest/download" alias works when
# api.github.com is unavailable, so cached data can still be refreshed or used.
function Get-AssetUrl {
    param([string]$Name)
    if ($repoAssets.ContainsKey($Name)) { return $repoAssets[$Name] }
    return ('https://github.com/demoonk/TVHome/releases/latest/download/' + [Uri]::EscapeDataString($Name))
}
function Test-ReleaseMetadata {
    param([string]$Path)
    if (-not $Path -or -not (Test-Path -LiteralPath $Path -PathType Leaf)) { return $null }
    try { $json = Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json } catch { return $null }
    if ($json.apkName -notmatch '^TVHome-[0-9][0-9A-Za-z.\-]*\.apk$' -or $json.sha256 -notmatch '^[a-fA-F0-9]{64}$') { return $null }
    return $json
}
function Get-CachedMetadataPath {
    param([string]$Filter = 'update*.json')
    $files = @(Get-ChildItem -LiteralPath $cache -Filter $Filter -File -ErrorAction SilentlyContinue |
        Where-Object { $_.Length -gt 0 } | Sort-Object LastWriteTime -Descending)
    if ($files.Count -eq 0) { return '' }
    return $files[0].FullName
}

Write-Host 'Установка TVHome: ADB и приложение будут скачаны автоматически.'
Write-Host 'На ТВ: Настройки → Об устройстве → несколько раз нажмите «Сборка», чтобы включить режим разработчика.'
Write-Host 'В настройках разработчика включите отладку USB / ADB по сети. Названия зависят от прошивки.'
Write-Host 'Подключите ТВ и компьютер к одной сети. Узнайте IP ТВ в настройках сети. Используется ADB по сети без кода сопряжения.'
Write-Host 'Отладка только с кодом сопряжения этим установщиком не поддерживается. Код запрашиваться не будет.'
Write-Host 'При подключении подтвердите на ТВ разрешение отладки для компьютера.'
Write-Host 'Установщик назначит TVHome главным экраном и выдаст права для голосового поиска, часов и установки из неизвестных источников.'
Write-Host 'Если виджет погоды есть в выпуске, он устанавливается с проверкой контрольной суммы; -NoWeather пропускает его.'
Write-Host 'Список заморозки применяется как pm disable-user --user 0: -Freeze меняет его, откат — Restore-Google.ps1 или pm enable --user 0.'
Write-Host 'Если GitHub не отвечает, уже скачанные файлы используются из кэша. Ключ -Offline работает только с кэшем; -MaxAttempts меняет число повторов.'
function Read-EditableAddress {
    Write-Host 'IP телевизора (можно изменить весь адрес; без порта используется 5555):'
    if ([Console]::IsInputRedirected) {
        return Read-Host 'IP ТВ (например 192.168.1.50)'
    }
    $text = '192.168.1.'
    $position = $text.Length
    $left = [Console]::CursorLeft
    $top = [Console]::CursorTop
    [Console]::Write($text)
    while ($true) {
        $key = [Console]::ReadKey($true)
        $previousLength = $text.Length
        switch ($key.Key) {
            'Enter' { [Console]::WriteLine(); return $text }
            'LeftArrow' { if ($position -gt 0) { $position-- } }
            'RightArrow' { if ($position -lt $text.Length) { $position++ } }
            'Home' { $position = 0 }
            'End' { $position = $text.Length }
            'Backspace' {
                if ($position -gt 0) { $text = $text.Remove($position - 1, 1); $position-- }
            }
            'Delete' { if ($position -lt $text.Length) { $text = $text.Remove($position, 1) } }
            'Escape' { $text = ''; $position = 0 }
            default {
                if ('0123456789.:'.Contains([string]$key.KeyChar) -and $key.KeyChar -ne [char]0 -and $text.Length -lt 21) {
                    $text = $text.Insert($position, [string]$key.KeyChar); $position++
                }
            }
        }
        [Console]::SetCursorPosition($left, $top)
        [Console]::Write($text.PadRight($previousLength + 1))
        [Console]::SetCursorPosition($left + $position, $top)
    }
}
function Read-YesNo([string]$Prompt) {
    while ($true) {
        $answer = (Read-Host "$Prompt (1 — да, 0 — нет; Enter — 1)").Trim()
        if ($answer -eq '' -or $answer -eq '1') { return $true }
        if ($answer -eq '0') { return $false }
        Write-Host 'Введите 1 или 0. Enter означает 1.'
    }
}
$value = (Read-EditableAddress).Trim()
if ($value -match '^\d{1,3}(\.\d{1,3}){3}$') { $value += ':5555' }
if ($value -notmatch '^(\d{1,3}(?:\.\d{1,3}){3}):(\d{1,5})$') { throw 'Некорректный IP ТВ. Запустите установщик снова и укажите адрес, например 192.168.1.50.' }
$ipText = $Matches[1]; $port = [int]$Matches[2]
if (@($ipText.Split('.') | Where-Object { [int]$_ -gt 255 }).Count -gt 0 -or $port -lt 1 -or $port -gt 65535) { throw 'Некорректный IP или порт ТВ.' }
$Device = $value
$zip = Join-Path $cache 'platform-tools.zip'
$adbPath = Join-Path $cache 'platform-tools/adb.exe'
$adbFiles = @($adbPath, (Join-Path $cache 'platform-tools/AdbWinApi.dll'), (Join-Path $cache 'platform-tools/AdbWinUsbApi.dll'))
$adbReady = @($adbFiles | Where-Object { -not (Test-Path -LiteralPath $_ -PathType Leaf) -or (Get-Item -LiteralPath $_).Length -eq 0 }).Count -eq 0
if ($adbReady) {
    Write-Host 'Использую уже скачанный ADB.'
} else {
    if ($Offline) { throw "В режиме -Offline ADB отсутствует в кэше: $cache\platform-tools. Скачайте Platform-Tools заранее при наличии интернета." }
    Write-Host 'Подготавливаю официальный Android SDK Platform-Tools…'
    $zipUrl = 'https://dl.google.com/android/repository/platform-tools-latest-windows.zip'
    if (-not (Get-CachedDownload -Uri $zipUrl -Path $zip)) {
        throw "Не удалось скачать Platform-Tools: сервер Google не ответил. Уже скачанные файлы сохранены; повторите позже."
    }
    try { Expand-Archive -LiteralPath $zip -DestinationPath $cache -Force }
    catch {
        Write-Host 'Архив ADB повреждён. Скачиваю заново…'
        if (-not (Invoke-NetworkRequest -AllowFailure { Invoke-WebRequest -UseBasicParsing -Uri $zipUrl -OutFile ($zip + '.part') -TimeoutSec 180 })) {
            throw 'Не удалось повторно скачать архив ADB. Уже скачанные файлы сохранены; повторите позже.'
        }
        Move-Item -LiteralPath ($zip + '.part') -Destination $zip -Force
        Expand-Archive -LiteralPath $zip -DestinationPath $cache -Force
    }
    foreach ($file in $adbFiles) {
        if (-not (Test-Path -LiteralPath $file -PathType Leaf) -or (Get-Item -LiteralPath $file).Length -eq 0) { throw "В архиве Google отсутствует файл ADB: $file" }
    }
}
function Invoke-Adb {
    param([string[]]$Arguments, [switch]$AllowFailure)
    $savedPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        # Windows PowerShell wraps native stderr in ErrorRecord. Convert each item
        # to plain text before formatting, so successful ADB progress is not
        # expanded into a NativeCommandError stack trace containing 'Exception'.
        $lines = @(& $adbPath @Arguments 2>&1 | ForEach-Object { $_.ToString() })
        $code = $LASTEXITCODE
        $result = ($lines -join "`n").Trim()
    } finally { $ErrorActionPreference = $savedPreference }
    if (($code -ne 0 -or $result -match '(?im)^Error:|Exception|SecurityException') -and -not $AllowFailure) { throw "ADB: $result" }
    return $result
}

# The weather add-on is a module card: the top bar reads its snapshot provider, so it is never frozen.
$WeatherPackage = 'io.github.sumyabazar1.weather'
# Packages the installer must never disable: Home, settings, the tuner and the inputs, the keyboard,
# WebView, the Google services the launcher depends on, and the weather add-on itself. The complete
# list is launcher/app/src/main/java/ru/pda/tvhome/FreezableApps.java; this is the part the installer
# enforces before applying the freeze list.
$ProtectedPackages = @('ru.pda.tvhome', 'com.google.android.tungsten.setupwraith', 'com.android.tv.settings',
    'com.mediatek.wwtv.tvcenter', 'com.mediatek.tvinput', 'com.google.android.tv.inputplayer',
    'com.mediatek.tv.inputplayeroverlay', 'com.mediatek.tv.service', 'com.mediatek.wwtv.webview',
    'com.google.android.webview', 'com.google.android.inputmethod.latin', 'com.google.android.gms',
    'com.google.android.gsf', 'com.google.android.play', 'com.android.vending', 'com.android.systemui',
    'com.android.providers.settings', 'com.android.providers.tv', 'com.android.providers.downloads',
    'com.google.android.tts', 'com.google.android.tv.assistant', 'com.google.android.tv.remote.service',
    'com.google.android.apps.mediashell', 'com.google.android.apps.tv.dreamx', $WeatherPackage)

function Get-ReleaseChecksum {
    param([string]$Name)
    $sumPath = Join-Path $cache ("SHA256SUMS-$metadataKey.txt")
    if (-not (Get-CachedDownload -Uri (Get-AssetUrl 'SHA256SUMS.txt') -Path $sumPath)) { return '' }
    $found = [regex]::Match((Get-Content -LiteralPath $sumPath -Raw), '(?im)^([a-f0-9]{64})\s+\*?' + [regex]::Escape($Name) + '\s*$')
    if ($found.Success) { return $found.Groups[1].Value }
    return ''
}

function Get-AssetChecksum {
    param([string]$Name)
    if (-not $release) { return '' }
    $asset = @($release.assets | Where-Object { $_.name -eq $Name })[0]
    if (-not $asset) { return '' }
    # GitHub reports the published digest; it is a second source when SHA256SUMS.txt has no line.
    if ($asset.digest -match '^sha256:([a-f0-9]{64})$') { return $Matches[1] }
    return ''
}

function Freeze-App {
    param([string]$Pkg, [switch]$RequireProvisioned)
    if ($Pkg -eq $package -or $ProtectedPackages -contains $Pkg) {
        Write-Warning "Заморозка $Pkg запрещена: пакет нужен телевизору или лаунчеру."
        return $false
    }
    $found = Invoke-Adb -Arguments @('-s', $Device, 'shell', 'pm', 'path', $Pkg) -AllowFailure
    if ($found -notmatch '(?m)^package:') {
        Write-Host "$Pkg на ТВ не установлен — заморозка пропущена."
        return $false
    }
    $already = Invoke-Adb -Arguments @('-s', $Device, 'shell', 'pm', 'list', 'packages', '-d', $Pkg) -AllowFailure
    if ($already -match ('(?m)^package:' + [regex]::Escape($Pkg) + '\s*$')) {
        Write-Host "$Pkg уже заморожен (отключён для пользователя 0)."
        return $true
    }
    if ($RequireProvisioned) {
        $provisioned = Invoke-Adb -Arguments @('-s', $Device, 'shell', 'settings', 'get', 'global', 'device_provisioned') -AllowFailure
        if ($provisioned -ne '1') {
            Write-Warning "Первоначальная настройка прошивки не завершена: $Pkg не замораживаю. Запустите установщик после завершения настройки."
            return $false
        }
    }
    Write-Host (Invoke-Adb -Arguments @('-s', $Device, 'shell', 'pm', 'disable-user', '--user', '0', $Pkg) -AllowFailure)
    $after = Invoke-Adb -Arguments @('-s', $Device, 'shell', 'pm', 'list', 'packages', '-d', $Pkg) -AllowFailure
    if ($after -notmatch ('(?m)^package:' + [regex]::Escape($Pkg) + '\s*$')) {
        Write-Warning "Заморозка $Pkg не подтверждена Android: $after"
        return $false
    }
    Write-Host "Заморожено: $Pkg. Откат: adb -s $Device shell pm enable --user 0 $Pkg"
    return $true
}

# Weather APK asset in the release is named TVHome-weather.apk; a versioned name TVHome-weather-1.0.apk
# also matches. Without the asset in the release the step warns and continues without the weather line.
function Install-WeatherWidget {
    if ($NoWeather) {
        Write-Host 'Виджет погоды пропускается: ключ -NoWeather.'
        return $false
    }
    $assetName = ''
    if ($release) {
        $assets = @($release.assets | Where-Object { $_.name -match '^TVHome-weather[A-Za-z0-9.\-]*\.apk$' })
        if ($assets.Count -eq 1) { $assetName = [string]$assets[0].name }
    }
    if (-not $assetName) {
        # Without the API the file name can only come from the saved checksums.
        foreach ($sumFile in @(Get-ChildItem -LiteralPath $cache -Filter 'SHA256SUMS*' -File -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending)) {
            $found = [regex]::Match((Get-Content -LiteralPath $sumFile.FullName -Raw), '(?im)^([a-f0-9]{64})\s+\*?(TVHome-weather[A-Za-z0-9.\-]*\.apk)\s*$')
            if ($found.Success) { $assetName = $found.Groups[2].Value; break }
        }
    }
    if (-not $assetName) {
        Write-Warning 'В выпуске нет APK виджета погоды. Строка погоды в шапке не появится, лаунчер работает без него.'
        return $false
    }
    $checksum = Get-ReleaseChecksum $assetName
    if (-not $checksum) { $checksum = Get-AssetChecksum $assetName }
    if (-not $checksum) {
        Write-Warning "Нет контрольной суммы для $assetName. Файл без проверки не устанавливается, остальные настройки сохранены."
        return $false
    }
    $widgetPath = Join-Path $cache $assetName
    if (-not (Get-CachedDownload -Uri (Get-AssetUrl $assetName) -Path $widgetPath -Sha256 $checksum)) {
        Write-Warning "Виджет погоды недоступен: GitHub не ответил, а файл в кэше отсутствует или не проходит контрольную сумму: $widgetPath. Остальные настройки сохранены."
        return $false
    }
    $installed = Invoke-Adb -Arguments @('-s', $Device, 'shell', 'install', '-r', $widgetPath)
    if ($installed -notmatch '(?m)^Success\s*$') {
        Write-Warning "Виджет погоды не установлен: $installed. Лаунчер остаётся настроенным; повторите позже."
        return $false
    }
    Write-Host "Виджет погоды установлен: $assetName"
    # The add-on declares only INTERNET, a normal permission granted at install. It is checked,
    # not requested: nothing else is needed for the snapshot provider the top bar reads.
    $widgetState = Invoke-Adb -Arguments @('-s', $Device, 'shell', 'dumpsys', 'package', $WeatherPackage) -AllowFailure
    if ($widgetState -notmatch 'android\.permission\.INTERNET: granted=true') {
        Write-Warning "Виджету не выдано INTERNET: $widgetState. Прогноз не загрузится, пока разрешение не выдано."
    }
    # One fetch for the top bar; the city is chosen in TVHome settings (Погода) or on the widget page.
    # "am broadcast --package" is not accepted by this shell, so the receiver is named directly.
    Write-Host (Invoke-Adb -Arguments @('-s', $Device, 'shell', 'am', 'broadcast', '-a', 'io.github.sumyabazar1.weather.action.REFRESH', '-n', ($WeatherPackage + '/.RefreshReceiver')) -AllowFailure)
    Write-Host 'Выберите город в настройках TVHome (Погода) — виджет сам его не определяет.'
    return $true
}
do {
    Write-Output (Invoke-Adb -Arguments @('connect', $Device) -AllowFailure)
    $state = Invoke-Adb -Arguments @('-s', $Device, 'get-state') -AllowFailure
    if ($state -eq 'device') { break }
    Write-Host 'Подтвердите отладку на ТВ. Проверьте адрес, порт и общую сеть.'
    if (-not (Read-YesNo 'Повторить подключение?')) { throw 'Подключение отменено' }
} while ($true)
$sdkText = Invoke-Adb -Arguments @('-s', $Device, 'shell', 'getprop', 'ro.build.version.sdk')
$sdk = 0
if (-not [int]::TryParse($sdkText, [ref]$sdk) -or $sdk -lt 26) { throw 'Нужен Android 8.0 или новее (API 26)' }
Write-Host 'Скачиваю последний выпуск TVHome с GitHub…'
$repoAssets = @{}
$release = $null
if ($Offline) {
    Write-Host 'Режим -Offline: GitHub не запрашивается, работаю только с кэшем.'
} else {
    $release = Invoke-NetworkRequest -AllowFailure { Invoke-RestMethod -Uri 'https://api.github.com/repos/demoonk/TVHome/releases/latest' -Headers @{ 'User-Agent' = 'TVHome-Installer' } -TimeoutSec 60 }
    foreach ($asset in @($release.assets)) {
        if ($asset.name -and $asset.browser_download_url) { $repoAssets[[string]$asset.name] = [string]$asset.browser_download_url }
    }
    if (-not $repoAssets.Count) { Write-Warning 'Список файлов releases недоступен, использую постоянный путь последнего выпуска и кэш.' }
}
# Asset ID changes when release metadata is replaced; unchanged metadata is reused.
$metadataKey = 'latest'
if ($release -and $release.tag_name) { $metadataKey = [string]$release.tag_name }
$metadataKey = ($metadataKey -replace '[^A-Za-z0-9._-]', '_')
if (-not $metadataKey) { $metadataKey = 'latest' }
$metadataPath = Join-Path $cache ("update-$metadataKey.json")
$metadata = $null
$metadataSource = 'GitHub'
if (-not $Offline) {
    if (Get-CachedDownload -Uri (Get-AssetUrl 'update.json') -Path $metadataPath) {
        $metadata = Test-ReleaseMetadata $metadataPath
        if ($metadata) { Copy-Item -LiteralPath $metadataPath -Destination (Join-Path $cache 'update-last.json') -Force }
    }
}
if (-not $metadata) {
    $cachedMetadataPath = Get-CachedMetadataPath
    $metadata = Test-ReleaseMetadata $cachedMetadataPath
    if ($metadata) {
        $metadataSource = 'кэш'
        Write-Warning 'Данные выпуска взяты из сохранённого кэша. Версия может быть не самой новой.'
    }
}
if (-not $metadata) {
    throw 'Не удалось получить update.json: GitHub недоступен и в кэше нет сохранённых данных выпуска. Повторите позже либо заранее скачайте кэш при наличии интернета.'
}
$apkPath = Join-Path $cache $metadata.apkName
if (-not (Get-CachedDownload -Uri (Get-AssetUrl $metadata.apkName) -Path $apkPath -Sha256 $metadata.sha256)) {
    throw "APK недоступен: GitHub не ответил, а файл в кэше отсутствует или не проходит контрольную сумму: $apkPath. Уже скачанные файлы сохранены; повторите позже."
}
if ((Get-FileHash -LiteralPath $apkPath -Algorithm SHA256).Hash -ne $metadata.sha256) { throw 'Контрольная сумма APK не совпала. Установка остановлена.' }
Write-Host "Данные выпуска: $metadataSource, версия $($metadata.versionName), код $($metadata.versionCode)."
$package = 'ru.pda.tvhome'
$previous = [ordered]@{
    device = $Device
    home = Invoke-Adb -Arguments @('-s', $Device, 'shell', 'cmd', 'package', 'resolve-activity', '--brief', '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.HOME')
    assistant = Invoke-Adb -Arguments @('-s', $Device, 'shell', 'settings', 'get', 'secure', 'assistant')
    voiceInteractionService = Invoke-Adb -Arguments @('-s', $Device, 'shell', 'settings', 'get', 'secure', 'voice_interaction_service')
}
$previous | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $work 'previous-settings.json') -Encoding UTF8
Write-Host "Прежние настройки сохранены: $work\previous-settings.json"
# Keep recovery next to the original settings, before disabling any package.
$restoreLines = @(
    '$ErrorActionPreference = "Stop"',
    ("& '{0}' -s '{1}' shell pm enable --user 0 com.google.android.apps.tv.launcherx" -f $adbPath.Replace("'", "''"), $Device),
    ("& '{0}' -s '{1}' shell settings put secure ru_pda_tvhome_assistant_request system:restore" -f $adbPath.Replace("'", "''"), $Device),
    ("& '{0}' -s '{1}' shell settings put secure assistant com.google.android.katniss/.search.serviceapi.KatnissVoiceInteractionService" -f $adbPath.Replace("'", "''"), $Device),
    ("& '{0}' -s '{1}' shell settings put secure voice_interaction_service com.google.android.katniss/.search.serviceapi.KatnissVoiceInteractionService" -f $adbPath.Replace("'", "''"), $Device),
    ("& '{0}' -s '{1}' shell cmd package set-home-activity --user 0 com.google.android.apps.tv.launcherx" -f $adbPath.Replace("'", "''"), $Device),
    ("& '{0}' -s '{1}' shell am start -a android.intent.action.MAIN -c android.intent.category.HOME" -f $adbPath.Replace("'", "''"), $Device)
)
# Undo of the freeze list, in its own section of the same script.
$restoreLines += '', '# Возврат замороженных пакетов'
foreach ($pkg in $Freeze) {
    $restoreLines += ("& '{0}' -s '{1}' shell pm enable --user 0 {2}" -f $adbPath.Replace("'", "''"), $Device, $pkg)
}
$restoreLines | Set-Content -LiteralPath (Join-Path $work 'Restore-Google.ps1') -Encoding UTF8
Write-Host "Возврат Google Home и помощника: $work\Restore-Google.ps1"

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
# App-ops the launcher needs and cannot grant to itself: the overlay clock (SYSTEM_ALERT_WINDOW),
# the active-app check that hides the clock on Home (GET_USAGE_STATS), and installing APKs from
# unknown sources (REQUEST_INSTALL_PACKAGES, the "Install unknown apps" special access).
foreach ($operation in @('SYSTEM_ALERT_WINDOW', 'GET_USAGE_STATS', 'REQUEST_INSTALL_PACKAGES')) {
    Write-Output (Invoke-Adb -Arguments @('-s', $Device, 'shell', 'appops', 'set', $package, $operation, 'allow'))
    $permission = Invoke-Adb -Arguments @('-s', $Device, 'shell', 'appops', 'get', $package, $operation)
    if ($permission -notmatch ($operation + ': allow(?:;|\s|$)')) { throw "Не подтверждено разрешение ${operation}: $permission" }
}
foreach ($permission in @('android.permission.WRITE_SECURE_SETTINGS', 'android.permission.RECORD_AUDIO')) {
    Write-Output (Invoke-Adb -Arguments @('-s', $Device, 'shell', 'pm', 'grant', $package, $permission))
    $check = Invoke-Adb -Arguments @('-s', $Device, 'shell', 'dumpsys', 'package', $package)
    if ($check -notmatch ([regex]::Escape($permission) + ': granted=true')) { throw "Не подтверждено разрешение ${permission}: $check" }
}
# The network pill reads the Wi-Fi name, which needs location on Android 10+. A firmware that
# refuses it only loses the network name, so this is a warning, not a stop.
Write-Output (Invoke-Adb -Arguments @('-s', $Device, 'shell', 'pm', 'grant', $package, 'android.permission.ACCESS_FINE_LOCATION') -AllowFailure)
$locationState = Invoke-Adb -Arguments @('-s', $Device, 'shell', 'dumpsys', 'package', $package) -AllowFailure
if ($locationState -notmatch 'android\.permission\.ACCESS_FINE_LOCATION: granted=true') {
    Write-Warning 'Разрешение на геолокацию не выдано: имя сети в плашке не будет видно. Остальные настройки сохранены.'
}
$weatherInstalled = Install-WeatherWidget
Write-Output (Invoke-Adb -Arguments @('-s', $Device, 'shell', 'cmd', 'package', 'set-home-activity', 'ru.pda.tvhome/ru.pda.tvhome.MainActivity'))
$resolved = Invoke-Adb -Arguments @('-s', $Device, 'shell', 'cmd', 'package', 'resolve-activity', '--brief', '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.HOME')
$homeVerified = $resolved -match 'ru\.pda\.tvhome/(ru\.pda\.tvhome\.)?MainActivity'
$roleAssigned = $homeVerified
if (-not $homeVerified) {
    # Some TV firmware resolves HOME to its stock activity even after assigning
    # the HOME role successfully. Check the actual role before reporting failure.
    $holders = Invoke-Adb -Arguments @('-s', $Device, 'shell', 'cmd', 'role', 'get-role-holders', '--user', '0', 'android.app.role.HOME') -AllowFailure
    $homeVerified = @($holders -split "`r?`n" | Where-Object { $_.Trim() -eq $package }).Count -gt 0
    $roleAssigned = $homeVerified
    if ($homeVerified) {
        Write-Warning 'Роль главного экрана назначена TVHome, но прошивка направляет HOME в штатный лаунчер. После установки проверьте кнопку Home на пульте.'
    } else {
        Write-Warning "Не удалось подтвердить назначение Home. Настройка голоса и часов продолжится. Выберите TVHome в системных настройках главного экрана. Ответ Android: $resolved"
    }
}
# Launch the replacement explicitly before disabling Google Home.
$probe = Invoke-Adb -Arguments @('-s', $Device, 'shell', 'am', 'start', '-W', '-n', 'ru.pda.tvhome/ru.pda.tvhome.MainActivity')
if ($probe -notmatch '(?m)^Status: ok\s*$') { throw "Не подтверждён запуск TVHome. Google Home остаётся включённым: $probe" }
$googleHome = 'com.google.android.apps.tv.launcherx'
$googleHomePath = Invoke-Adb -Arguments @('-s', $Device, 'shell', 'pm', 'path', $googleHome) -AllowFailure
$googleHomeDisabled = $false
try {
    if ($googleHomePath -match '(?m)^package:') {
        Write-Host 'Отключаю Google Home для текущего пользователя. Службы доступности не изменяются.'
        $googleHomeDisabled = $true
        Write-Output (Invoke-Adb -Arguments @('-s', $Device, 'shell', 'pm', 'disable-user', '--user', '0', $googleHome))
    }
    Write-Output (Invoke-Adb -Arguments @('-s', $Device, 'shell', 'cmd', 'package', 'set-home-activity', '--user', '0', $package))
    $homeVerified = $false
    # First-time Google RecoveryActivity may briefly take over HOME. Do not
    # keep restarting it every 500 ms: let its transition finish first.
    $homeLaunch = Invoke-Adb -Arguments @('-s', $Device, 'shell', 'am', 'start', '-W', '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.HOME')
    for ($attempt = 0; $attempt -lt 60; $attempt++) {
        if ($attempt -eq 20 -or $attempt -eq 40) {
            Write-Host 'Ожидаю завершения первоначальной настройки Home…'
            Write-Output (Invoke-Adb -Arguments @('-s', $Device, 'shell', 'cmd', 'package', 'set-home-activity', '--user', '0', $package))
            $homeLaunch = Invoke-Adb -Arguments @('-s', $Device, 'shell', 'am', 'start', '-W', '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.HOME')
        }
        # am start may omit Activity when reusing an existing task. Verify the
        # foreground activity itself rather than relying on its summary text.
        $foregroundState = Invoke-Adb -Arguments @('-s', $Device, 'shell', 'dumpsys', 'activity', 'activities')
        $homeVerified = $foregroundState -match '(?:topResumedActivity|mResumedActivity)[^\r\n]*\bru\.pda\.tvhome/(?:ru\.pda\.tvhome)?\.MainActivity\b'
        if ($homeVerified) { break }
        Start-Sleep -Milliseconds 500
    }
    if (-not $homeVerified) {
        $homeLaunch | Set-Content -LiteralPath (Join-Path $work 'home-launch.txt') -Encoding UTF8
        $foregroundState | Set-Content -LiteralPath (Join-Path $work 'home-activity.txt') -Encoding UTF8
        if ($roleAssigned) {
            # Роль уже назначена TVHome, но прошивка во время первоначальной настройки держит
            # HOME за своей установочной активностью. Откатывать Google Home нельзя: это
            # отменило бы выполненное назначение. Home проверяется после настройки прошивки.
            Write-Warning 'Роль главного экрана назначена TVHome, но телевизор показал не TVHome. Проверьте кнопку Home после завершения первоначальной настройки прошивки. Диагностика: $work\home-activity.txt'
        } else {
            throw "После назначения Home телевизор не открыл TVHome. Диагностика: $work\home-activity.txt"
        }
    }
} catch {
    if ($googleHomeDisabled) {
        Write-Output (Invoke-Adb -Arguments @('-s', $Device, 'shell', 'pm', 'enable', '--user', '0', $googleHome) -AllowFailure)
    }
    throw
}
$googleReady = $true
$frozen = @()
foreach ($googlePackage in @('com.google.android.tts', 'com.google.android.katniss')) {
    $found = Invoke-Adb -Arguments @('-s', $Device, 'shell', 'pm', 'path', $googlePackage) -AllowFailure
    if ($found -notmatch '(?m)^package:') {
        Write-Warning "Не найден $googlePackage. Голосовой режим Google от лаунчера может быть недоступен."
        $googleReady = $false
    }
}
if ($googleReady) {
    # Embedded helper from tools/assistant-package-guard.sh; no extra download is required.
    $guardBase64 = 'IyEvc3lzdGVtL2Jpbi9zaAojIFN0YXJ0ZWQgZXhwbGljaXRseSB2aWEgQURCOyBubyBuZXR3b3JrIGxpc3RlbmVyLCBhcmJpdHJhcnkgY29tbWFuZHMsIG9yIGtleSBmaWx0ZXJpbmcuCiMgUmVxdWVzdHMgYW5kIGFja25vd2xlZGdlbWVudHMgcmVxdWlyZSBXUklURV9TRUNVUkVfU0VUVElOR1MgKG5vdCBhbiBvcmRpbmFyeSBhcHAgcGVybWlzc2lvbikuCnBpZF9maWxlPS9kYXRhL2xvY2FsL3RtcC9hc3Npc3RhbnQtcGFja2FnZS1ndWFyZC5waWQKaWYgWyAtZiAiJHBpZF9maWxlIiBdOyB0aGVuCiAgICBwcmV2aW91c19waWQ9JChjYXQgIiRwaWRfZmlsZSIpCiAgICBjYXNlICIkcHJldmlvdXNfcGlkIiBpbgogICAgICAgICpbITAtOV0qfCcnKSA7OwogICAgICAgICopCiAgICAgICAgICAgICMgY2F0LCBub3QgYSByZWRpcmVjdGlvbjogYSByZWRpcmVjdGlvbiBlcnJvciBpcyBwcmludGVkIGJlZm9yZSAyPi9kZXYvbnVsbCBhcHBsaWVzLAogICAgICAgICAgICAjIGFuZCBhIGRlYWQgcGlkIHB1dCB0aGF0IGVycm9yIGludG8gdGhlIGd1YXJkJ3MgbG9nLgogICAgICAgICAgICBwcmV2aW91c19jb21tYW5kPSQoY2F0ICIvcHJvYy8kcHJldmlvdXNfcGlkL2NtZGxpbmUiIDI+L2Rldi9udWxsIHwgdHIgJ1wwMDAnICcgJykKICAgICAgICAgICAgY2FzZSAiJHByZXZpb3VzX2NvbW1hbmQiIGluCiAgICAgICAgICAgICAgICAqInNoIC9kYXRhL2xvY2FsL3RtcC9hc3Npc3RhbnQtcGFja2FnZS1ndWFyZC5zaCIqKSBleGl0IDAgOzsKICAgICAgICAgICAgZXNhYwogICAgICAgICAgICA7OwogICAgZXNhYwpmaQplY2hvICQkID4gIiRwaWRfZmlsZSIKdHJhcCAncm0gLWYgIiRwaWRfZmlsZSI7IGV4aXQnIEhVUCBJTlQgVEVSTQpyZXF1ZXN0X2tleT1ydV9wZGFfdHZob21lX2Fzc2lzdGFudF9yZXF1ZXN0CmFja19rZXk9cnVfcGRhX3R2aG9tZV9hc3Npc3RhbnRfYWNrCnBhY2thZ2U9Y29tLmdvb2dsZS5hbmRyb2lkLmthdG5pc3MKc2V0dGluZ3MgZGVsZXRlIHNlY3VyZSAiJGFja19rZXkiID4vZGV2L251bGwKbGFzdF9yZXF1ZXN0PQp3aGlsZSBwbSBwYXRoIHJ1LnBkYS50dmhvbWUgPi9kZXYvbnVsbCAyPiYxOyBkbwogICAgcmVxdWVzdD0kKHNldHRpbmdzIGdldCBzZWN1cmUgIiRyZXF1ZXN0X2tleSIpCiAgICBpZiBbICIkcmVxdWVzdCIgIT0gIiRsYXN0X3JlcXVlc3QiIF07IHRoZW4KICAgICAgICBtb2RlPSR7cmVxdWVzdCUlOip9CiAgICAgICAgY2FzZSAiJG1vZGUiIGluCiAgICAgICAgICAgIGNoZWNrKSBzZXR0aW5ncyBwdXQgc2VjdXJlICIkYWNrX2tleSIgIiRyZXF1ZXN0IiA7OwogICAgICAgICAgICBsYXVuY2hlcikKICAgICAgICAgICAgICAgIHNlbGVjdGVkPSQoc2V0dGluZ3MgZ2V0IHNlY3VyZSB2b2ljZV9pbnRlcmFjdGlvbl9zZXJ2aWNlKQogICAgICAgICAgICAgICAgaWYgWyAiJHNlbGVjdGVkIiA9ICJydS5wZGEudHZob21lL3J1LnBkYS50dmhvbWUuTGF1bmNoZXJBc3Npc3RhbnRTZXJ2aWNlIiBdOyB0aGVuCiAgICAgICAgICAgICAgICAgICAgIyBBIHBhY2thZ2UgYWxyZWFkeSBmcm96ZW4gYW5zd2VycyBub3RoaW5nLCBzbyB0aGUgbGF1bmNoZXIgd291bGQgd2FpdCBmaXZlIHNlY29uZHMKICAgICAgICAgICAgICAgICAgICAjIGFuZCBnaXZlIHVwOiBhbHJlYWR5IG9mZiBjb3VudHMgYXMgdGhlIHNhbWUgc3RhdGUuCiAgICAgICAgICAgICAgICAgICAgaWYgcG0gZGlzYWJsZS11c2VyIC0tdXNlciAwICIkcGFja2FnZSIgPi9kZXYvbnVsbCAyPiYxIHx8CiAgICAgICAgICAgICAgICAgICAgICAgICAgICBwbSBsaXN0IHBhY2thZ2VzIC1kIHwgZ3JlcCAtcXggIiRwYWNrYWdlIjsgdGhlbgogICAgICAgICAgICAgICAgICAgICAgICAjIFBhY2thZ2UgcmVtb3ZhbCBjYW4gY2xlYXIgdGhlIHNlbGVjdGVkIGFzc2lzdGFudCBldmVuIHdoZW4gYW5vdGhlciBvbmUgd2FzCiAgICAgICAgICAgICAgICAgICAgICAgICMgc2VsZWN0ZWQgZmlyc3QuIFJlYXNzZXJ0IG91ciBlbnRyeSBhZnRlciB0aGF0IHBhY2thZ2Ugb3BlcmF0aW9uIGNvbXBsZXRlcy4KICAgICAgICAgICAgICAgICAgICAgICAgc2V0dGluZ3MgcHV0IHNlY3VyZSBhc3Npc3RhbnQgcnUucGRhLnR2aG9tZS9ydS5wZGEudHZob21lLkxhdW5jaGVyQXNzaXN0YW50U2VydmljZQogICAgICAgICAgICAgICAgICAgICAgICBzZXR0aW5ncyBwdXQgc2VjdXJlIHZvaWNlX2ludGVyYWN0aW9uX3NlcnZpY2UgcnUucGRhLnR2aG9tZS9ydS5wZGEudHZob21lLkxhdW5jaGVyQXNzaXN0YW50U2VydmljZQogICAgICAgICAgICAgICAgICAgICAgICBzZXR0aW5ncyBwdXQgc2VjdXJlICIkYWNrX2tleSIgIiRyZXF1ZXN0IgogICAgICAgICAgICAgICAgICAgIGZpCiAgICAgICAgICAgICAgICBmaQogICAgICAgICAgICAgICAgOzsKICAgICAgICAgICAgc3lzdGVtKQogICAgICAgICAgICAgICAgaWYgcG0gZW5hYmxlIC0tdXNlciAwICIkcGFja2FnZSIgPi9kZXYvbnVsbCAyPiYxOyB0aGVuCiAgICAgICAgICAgICAgICAgICAgc2V0dGluZ3MgcHV0IHNlY3VyZSAiJGFja19rZXkiICIkcmVxdWVzdCIKICAgICAgICAgICAgICAgIGZpCiAgICAgICAgICAgICAgICA7OwogICAgICAgIGVzYWMKICAgICAgICBsYXN0X3JlcXVlc3Q9JHJlcXVlc3QKICAgIGZpCiAgICBzbGVlcCAxCmRvbmUK'
    $guardPath = Join-Path $work 'assistant-package-guard.sh'
    [IO.File]::WriteAllBytes($guardPath, [Convert]::FromBase64String($guardBase64))
    Write-Output (Invoke-Adb -Arguments @('-s', $Device, 'push', $guardPath, '/data/local/tmp/assistant-package-guard.sh'))
    # setsid, not nohup: on this firmware nohup is only a shell alias, and the alias is not expanded in the
    # non-interactive shell ADB runs, so the guard's pid file (its single-instance check) was never written
    # and the helper could not be traced. setsid is a real binary and detaches the helper from the ADB session.
    Write-Output (Invoke-Adb -Arguments @('-s', $Device, 'shell', 'setsid sh /data/local/tmp/assistant-package-guard.sh >/data/local/tmp/assistant-package-guard.log 2>&1 < /dev/null &'))
    $guardPid = ((Invoke-Adb -Arguments @('-s', $Device, 'shell', 'cat /data/local/tmp/assistant-package-guard.pid')) -join '')
    if ($guardPid -notmatch '^[1-9][0-9]*$') { throw 'Помощник не записал pid-файл: его нельзя проверить как единственный запущенный. Запустите guard вручную.' }
    if ((Invoke-Adb -Arguments @('-s', $Device, 'shell', "ls -d /proc/$guardPid")) -match 'No such file') { throw "Помощник не запущен (pid $guardPid)." }
    $request = 'check:' + [guid]::NewGuid().ToString('N')
    Write-Output (Invoke-Adb -Arguments @('-s', $Device, 'shell', 'settings', 'put', 'secure', 'ru_pda_tvhome_assistant_request', $request))
    $ack = ''
    for ($attempt = 0; $attempt -lt 10; $attempt++) {
        Start-Sleep -Milliseconds 500
        $ack = Invoke-Adb -Arguments @('-s', $Device, 'shell', 'settings', 'get', 'secure', 'ru_pda_tvhome_assistant_ack')
        if ($ack -eq $request) { break }
    }
    if ($ack -ne $request) { throw 'ADB-помощник не подтвердил запуск. Проверьте /data/local/tmp/assistant-package-guard.log на ТВ.' }
    $assistantComponent = 'ru.pda.tvhome/ru.pda.tvhome.LauncherAssistantService'
    # Katniss is frozen as part of the assistant switch: TVHome owns the voice interaction service,
    # so its package must be off before Android reselects the service. Disable first: Android may
    # clear/reselect its voice service asynchronously after package state changes. An
    # acknowledgement alone cannot verify this.
    if (-not (Freeze-App 'com.google.android.katniss')) { throw 'Заморозка Katniss не подтверждена; помощник TVHome не мог заработать.' }
    $frozen += 'com.google.android.katniss'
    Write-Output (Invoke-Adb -Arguments @('-s', $Device, 'shell', 'settings', 'put', 'secure', 'voice_recognition_service', 'com.google.android.tts/com.google.android.apps.speech.tts.googletts.service.GoogleTTSRecognitionService'))
    $stable = 0
    for ($attempt = 0; $attempt -lt 30; $attempt++) {
        $selected = Invoke-Adb -Arguments @('-s', $Device, 'shell', 'settings', 'get', 'secure', 'voice_interaction_service')
        if ($selected -ne $assistantComponent) {
            Write-Output (Invoke-Adb -Arguments @('-s', $Device, 'shell', 'settings', 'put', 'secure', 'assistant', $assistantComponent))
            Write-Output (Invoke-Adb -Arguments @('-s', $Device, 'shell', 'settings', 'put', 'secure', 'voice_interaction_service', $assistantComponent))
            $stable = 0
        } else {
            $voiceState = Invoke-Adb -Arguments @('-s', $Device, 'shell', 'dumpsys', 'voiceinteraction')
            if ($voiceState -match 'mComponent=ru\.pda\.tvhome/(?:ru\.pda\.tvhome)?\.LauncherAssistantService' -and $voiceState -match 'mBound=true') { $stable++ }
            else { $stable = 0 }
            if ($stable -ge 3) { break }
        }
        Start-Sleep -Milliseconds 500
    }
    if ($stable -lt 3) {
        $selected | Set-Content -LiteralPath (Join-Path $work 'voice-selected.txt') -Encoding UTF8
        $voiceState | Set-Content -LiteralPath (Join-Path $work 'voice-service.txt') -Encoding UTF8
        Write-Output (Invoke-Adb -Arguments @('-s', $Device, 'shell', 'pm', 'enable', '--user', '0', 'com.google.android.katniss') -AllowFailure)
        throw "Переключение на голосового помощника TVHome не подтверждено. Katniss включён обратно. Диагностика: $work\voice-service.txt"
    }
    # The launcher itself asks the guard over the same handshake when its voice source is chosen: one settings
    # call, then five seconds for the acknowledgement. A guard that died after the install leaves that request
    # unacknowledged, and the launcher rolls the choice back to an empty value. Prove this path, not only the
    # initial check.
    $launcherRequest = 'launcher:' + [guid]::NewGuid().ToString('N')
    Invoke-Adb -Arguments @('-s', $Device, 'shell', 'settings', 'put', 'secure', 'ru_pda_tvhome_assistant_request', $launcherRequest) | Out-Null
    $launcherAck = ''
    for ($attempt = 0; $attempt -lt 12; $attempt++) {
        Start-Sleep -Milliseconds 500
        $launcherAck = Invoke-Adb -Arguments @('-s', $Device, 'shell', 'settings', 'get', 'secure', 'ru_pda_tvhome_assistant_ack')
        if ($launcherAck -eq $launcherRequest) { break }
    }
    if ($launcherAck -ne $launcherRequest) {
        $guardPid = (Invoke-Adb -Arguments @('-s', $Device, 'shell', 'cat', '/data/local/tmp/assistant-package-guard.pid')) -join ''
        Write-Output (Invoke-Adb -Arguments @('-s', $Device, 'shell', "ls -d /proc/$guardPid") -AllowFailure)
        throw 'Помощник TVHome не ответил на запрос launcher. Помощник выбран, но голосовой источник нельзя будет сменить из настроек TVHome, пока guard не отвечает. Перезапустите guard и повторите проверку.'
    }
    Write-Host 'Katniss заморожен для пользователя 0, выбран голосовой помощник TVHome. Распознавание Google TTS включено. Guard отвечает на запрос лаунчера.'

} else {
    Write-Warning 'Голосовой режим не настроен: отсутствуют компоненты Google. Home и разрешения настроены.'
    Write-Warning 'Katniss не замораживается: без выбора помощника TVHome его отключение лишило бы голосовой поиск.'
}
# The rest of the freeze list. It runs after the assistant switch and after Home is confirmed, so
# nothing the launcher depends on is taken off the enabled list.
foreach ($pkg in $Freeze) {
    if ($pkg -eq 'com.google.android.katniss') { continue }
    if (Freeze-App $pkg -RequireProvisioned) { $frozen += $pkg }
}
function Start-RemoteBridge {
    $inventory = Invoke-Adb -Arguments @('-s', $Device, 'shell', 'getevent -lp') -AllowFailure
    $blocks = [regex]::Matches($inventory, '(?ms)add device \d+: (?<path>/dev/input/event\d+)\s*\n\s*name:\s*"(?<name>[^"]+)"(?<body>.*?)(?=add device |\z)')
    $candidates = @($blocks | Where-Object {
        $_.Groups['name'].Value -eq 'TVC0GWBT Consumer Control' -and
        $_.Groups['body'].Value -match 'KEY_UNKNOWN' -and $_.Groups['body'].Value -match 'MSC_SCAN'
    })
    if ($candidates.Count -ne 1) {
        Write-Warning 'Совместимый пульт TVC0GWBT не найден однозначно. Мост шестерёнки не запущен; остальные настройки сохранены.'
        return
    }
    $inputState = Invoke-Adb -Arguments @('-s', $Device, 'shell', 'dumpsys input')
    if ($inputState -match 'InputFilterEnabled:\s*true') {
        Write-Warning 'На ТВ уже включён фильтр ввода. Мост не запускается: сначала проверьте обработку громкости.'
        return
    }
    # The bridge DEX stays optional: without GitHub the bridge is skipped, never
    # installed from an unverified file.
    $dexName = ''
    if ($release) {
        $dexAssets = @($release.assets | Where-Object { $_.name -match '^TVHome-remote-[0-9][0-9A-Za-z.\-]*\.dex$' })
        if ($dexAssets.Count -eq 1) { $dexName = [string]$dexAssets[0].name }
    }
    $sumPath = Join-Path $cache ("SHA256SUMS-$metadataKey.txt")
    $dexHash = ''
    if (-not $dexName) {
        # Without the API the DEX name can only come from saved checksums.
        foreach ($sumFile in @(Get-ChildItem -LiteralPath $cache -Filter 'SHA256SUMS*' -File -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending)) {
            $found = [regex]::Match((Get-Content -LiteralPath $sumFile.FullName -Raw), '(?im)^([a-f0-9]{64})\s+\*?(TVHome-remote-[0-9][0-9A-Za-z.\-]*\.dex)\s*$')
            if ($found.Success) { $dexName = $found.Groups[2].Value; $dexHash = $found.Groups[1].Value; $sumPath = $sumFile.FullName; break }
        }
    }
    if (-not $dexName) {
        Write-Warning 'DEX моста не найден в релизе и в кэше. Мост шестерёнки не запускается; остальные настройки сохранены.'
        return
    }
    if (-not $dexHash) {
        if (-not (Get-CachedDownload -Uri (Get-AssetUrl 'SHA256SUMS.txt') -Path $sumPath)) {
            Write-Warning 'Контрольные суммы DEX недоступны. Мост не запускается: файл без проверки контрольной суммы не устанавливается.'
            return
        }
        $pattern = '(?im)^([a-f0-9]{64})\s+\*?' + [regex]::Escape($dexName) + '\s*$'
        $checksums = [regex]::Matches((Get-Content -LiteralPath $sumPath -Raw), $pattern)
        if ($checksums.Count -lt 1) { throw 'Не найдена однозначная контрольная сумма DEX моста' }
        $dexHash = $checksums[0].Groups[1].Value
    }
    $dexPath = Join-Path $cache $dexName
    if (-not (Get-CachedDownload -Uri (Get-AssetUrl $dexName) -Path $dexPath -Sha256 $dexHash)) {
        Write-Warning "DEX моста недоступен: GitHub не ответил, а файл в кэше отсутствует или не проходит контрольную сумму: $dexPath. Остальные настройки сохранены."
        return
    }
    $eventDevice = $candidates[0].Groups['path'].Value
    $abi = Invoke-Adb -Arguments @('-s', $Device, 'shell', 'getprop ro.product.cpu.abi')
    $eventSize = if ($abi -match '64') { 24 } else { 16 }
    Write-Output (Invoke-Adb -Arguments @('-s', $Device, 'push', $dexPath, '/data/local/tmp/tvhome-remote-2.dex'))
    $bridgePids = Invoke-Adb -Arguments @('-s', $Device, 'shell', 'pidof pqtv-bridge') -AllowFailure
    foreach ($bridgePid in ($bridgePids -split '\s+')) {
        if ($bridgePid -notmatch '^\d+$') { continue }
        $commandLine = Invoke-Adb -Arguments @('-s', $Device, 'shell', "cat /proc/$bridgePid/cmdline")
        if ($commandLine -notmatch 'pqtv-bridge') { throw 'Процесс моста изменился; остановка отменена' }
        Write-Output (Invoke-Adb -Arguments @('-s', $Device, 'shell', "kill $bridgePid"))
    }
    # The threshold is a default inside the bridge. It is not passed as an argument:
    # the published DEX asset accepts only three, so an extra one would break the bridge.
    $command = "CLASSPATH=/data/local/tmp/tvhome-remote-2.dex nohup app_process /system/bin --nice-name=pqtv-bridge PqRemoteProof $eventDevice $eventSize 0 >/data/local/tmp/tvhome-remote-2.log 2>&1 </dev/null &"
    Write-Output (Invoke-Adb -Arguments @('-s', $Device, 'shell', $command))
    $readyLine = ''
    for ($attempt = 0; $attempt -lt 20; $attempt++) {
        Start-Sleep -Milliseconds 250
        $bridgeLog = Invoke-Adb -Arguments @('-s', $Device, 'shell', 'cat /data/local/tmp/tvhome-remote-2.log') -AllowFailure
        $found = @($bridgeLog -split "`r?`n" | Where-Object { $_ -match 'PQ_REMOTE_READY' })
        if ($found.Count -gt 0) { $readyLine = $found[0]; break }
    }
    if (-not $readyLine) { throw "Мост шестерёнки не подтвердил запуск: $bridgeLog" }
    Write-Host 'Мост шестерёнки запущен до перезагрузки ТВ. Он наблюдает события пульта без захвата ввода и фильтра клавиш.'
    Write-Host 'Одно нажатие шестерёнки — настройки экрана. Удержание (по умолчанию 700 мс) — настройки звука, открывается сразу при достижении порога.'
    if ($readyLine -match 'sound=unavailable') {
        Write-Warning 'Штатные настройки звука на этом ТВ не найдены: долгое нажатие откроет настройки экрана.'
    }
    Write-Host 'Проверьте одно и долгое нажатие шестерёнки, Back из поиска и удержание громкости. После перезагрузки запустите установщик снова.'
}
Start-RemoteBridge
$started = Invoke-Adb -Arguments @('-s', $Device, 'shell', 'am', 'start', '-W', '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.HOME', '-n', 'ru.pda.tvhome/ru.pda.tvhome.MainActivity')
Write-Output $started
Write-Host 'TVHome установлен. Права для голоса, часов и установки из неизвестных источников выданы и проверены.'
if ($weatherInstalled) {
    Write-Host 'Виджет погоды установлен. Выберите город в настройках TVHome (Погода), строка в шапке обновится после загрузки.'
}
if ($frozen.Count -gt 0) {
    Write-Host ('Заморожено для пользователя 0: ' + ($frozen -join ', ') + '. Откат: Restore-Google.ps1 или pm enable --user 0 <пакет>.')
}
if ($metadataSource -eq 'кэш') { Write-Host 'Установлена версия из сохранённого кэша. Когда GitHub заработает, запустите установщик снова для проверки обновлений.' }
if ($homeVerified) { Write-Host 'Назначение TVHome главным экраном подтверждено Android. Проверьте кнопку Home на пульте.' }
else { Write-Warning 'Назначение главного экрана требует проверки в системных настройках ТВ.' }
Write-Host 'Включите «Оверлейные часы» в настройках TVHome, если нужны часы поверх других приложений.'
if ($googleReady) {
    Write-Host 'Голосовой помощник TVHome выбран автоматически. При необходимости выберите источник «Пульт» или «ТВ» удержанием значка микрофона.'
    Write-Host 'Этот выбор может отключить системного Google-помощника Katniss. Возврат — выбор «Системный» в том же меню.'
    Write-Host 'Источник «ТВ» требует Android 13+ и встроенного микрофона. ADB-помощник работает до перезагрузки.'
    Write-Host 'Для повторного переключения помощника после перезагрузки можно снова запустить установщик.'
} else {
    Write-Warning 'Голосовой режим не настроен: отсутствуют компоненты Google. Home и разрешения настроены.'
}
Write-Host "Файлы, резервная копия APK и прежние настройки: $work"
Write-Host 'Проверьте Home и голос с пульта. После настройки отладку можно отключить.'
