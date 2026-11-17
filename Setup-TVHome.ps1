#requires -Version 5.1
param(
    [switch]$Offline,
    [int]$MaxDownloadAttempts = 5,
    [switch]$NoWeather,
    # Add-on APKs (weather widget, bridge helper) are replaced only after the old package is removed
    # when Android rejects the new signature. Off by default: the already installed add-on keeps working,
    # and removing the helper resets its bridge key.
    [switch]$ReinstallAddOns,
    # Замокательный список (see launcher FreezableApps.java): applied over ADB, because the
    # launcher itself cannot disable another package without privileged rights.
    [string[]]$Freeze = @('com.google.android.tungsten.setupwraith', 'com.mediatek.wwtv.setupwizard', 'com.google.android.katniss')
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
Write-Host 'Если в выпуске есть APK помощника моста (bridge-helper), установщик ставит его: он нужен для автозапуска моста шестерёнки после перезагрузки.'
Write-Host 'Виджет погоды и помощник моста не останавливают установку: отказ Android из-за другой подписи пакета — предупреждение. Ключ -ReinstallAddOns удаляет старый пакет и ставит APK из выпуска (у помощника это сбрасывает ключ моста).'
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
$BridgeHelperPackage = 'io.github.sumyabazar1.bridgehelper'
# Packages the installer must never disable: Home, settings, the tuner and the inputs, the keyboard,
# WebView, the Google services the launcher depends on, and the weather add-on itself. The complete
# list is launcher/app/src/main/java/ru/pda/tvhome/FreezableApps.java; this is the part the installer
# enforces before applying the freeze list.
# com.google.android.tungsten.setupwraith is deliberately not here: while that recovery stub is enabled the
# system resolves HOME to it, so the Home key never reaches TVHome from any app. It stays enabled until the
# initial setup of the firmware has finished and is then frozen for user 0; Restore-Google.ps1 puts it back.
$ProtectedPackages = @('ru.pda.tvhome', 'com.android.tv.settings',
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
    # The saved sums file is verified with the digest GitHub reports for the asset: a release can be
    # repaired after publication, and an old cached copy must not keep the installer on stale checksums.
    $sumDigest = Get-AssetChecksum 'SHA256SUMS.txt'
    if (-not (Get-CachedDownload -Uri (Get-AssetUrl 'SHA256SUMS.txt') -Path $sumPath -Sha256 $sumDigest)) { return '' }
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

# An add-on checksum can come from three sources: the SHA256SUMS.txt of the current release, the digest
# GitHub reports for the asset, and — when the current release does not list the file — the saved sums of
# an earlier release, which still verifies the exact bytes of the file that is already in the cache.
function Get-AddOnChecksum {
    param([string]$Name)
    $checksum = Get-ReleaseChecksum $Name
    if ($checksum) { return $checksum }
    $checksum = Get-AssetChecksum $Name
    if ($checksum) { return $checksum }
    foreach ($sumFile in @(Get-ChildItem -LiteralPath $cache -Filter 'SHA256SUMS*' -File -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending)) {
        $found = [regex]::Match((Get-Content -LiteralPath $sumFile.FullName -Raw), '(?im)^([a-f0-9]{64})\s+\*?' + [regex]::Escape($Name) + '\s*$')
        if ($found.Success) {
            Write-Host "Контрольная сумма $Name взята из сохранённого файла выпуска, в котором этот файл публиковался: $($sumFile.FullName)"
            return $found.Groups[1].Value
        }
    }
    return ''
}

# An APK rebuilt with a different key cannot update an installed package: Android answers
# INSTALL_FAILED_UPDATE_INCOMPATIBLE. That is not a reason to stop the whole setup. The installed add-on
# keeps working; only -ReinstallAddOns removes it first, which for the bridge helper also resets its key.
function Install-AddOnApk {
    param([string]$Package, [string]$Path, [string]$Label)
    $result = Invoke-Adb -Arguments @('-s', $Device, 'install', '-r', $Path) -AllowFailure
    if ($result -match '(?m)^Success\s*$') { return $true }
    if ($result -match 'INSTALL_FAILED_UPDATE_INCOMPATIBLE') {
        $state = Invoke-Adb -Arguments @('-s', $Device, 'shell', 'dumpsys', 'package', $Package) -AllowFailure
        $version = [regex]::Match($state, 'versionCode=(\d+)').Groups[1].Value
        Write-Warning "$Label не обновлён: установленный $Package (versionCode $version) подписан другим ключом, чем APK из выпуска. Android разрешает замену только после удаления пакета."
        if (-not $ReinstallAddOns) {
            Write-Warning "$Label остался в прежней версии. Чтобы поставить APK из выпуска: adb -s $Device shell pm uninstall $Package, затем повторный запуск установщика с ключом -ReinstallAddOns. Остальные настройки уже применены."
            return $false
        }
        Write-Host (Invoke-Adb -Arguments @('-s', $Device, 'shell', 'pm', 'uninstall', $Package) -AllowFailure)
        $retry = Invoke-Adb -Arguments @('-s', $Device, 'install', $Path) -AllowFailure
        if ($retry -match '(?m)^Success\s*$') { return $true }
        Write-Warning "$Label не установлен после удаления старого пакета: $retry"
        return $false
    }
    Write-Warning "$Label не установлен: $result"
    return $false
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
    $checksum = Get-AddOnChecksum $assetName
    if (-not $checksum) {
        Write-Warning "Нет контрольной суммы для $assetName. Файл без проверки не устанавливается, остальные настройки сохранены."
        return $false
    }
    $widgetPath = Join-Path $cache $assetName
    try {
        $verified = Get-CachedDownload -Uri (Get-AssetUrl $assetName) -Path $widgetPath -Sha256 $checksum
    } catch {
        Write-Warning "Виджет погоды не прошёл проверку контрольной суммы: $($_.Message). Остальные настройки сохранены."
        return $false
    }
    if (-not $verified) {
        Write-Warning "Виджет погоды недоступен: GitHub не ответил, а файл в кэше отсутствует или не проходит контрольную сумму: $widgetPath. Остальные настройки сохранены."
        return $false
    }
    if (-not (Install-AddOnApk $WeatherPackage $widgetPath 'Виджет погоды')) { return $false }
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
# The bridge helper APK asset is named bridge-helper-<version>.apk; without it the launcher cannot
# start the gear bridge after a reboot, so the step warns but leaves everything else configured.
function Install-BridgeHelper {
    $assetName = ''
    if ($release) {
        $assets = @($release.assets | Where-Object { $_.name -match '^bridge-helper-[0-9][0-9A-Za-z.\-]*\.apk$' })
        if ($assets.Count -eq 1) { $assetName = [string]$assets[0].name }
    }
    if (-not $assetName) {
        foreach ($sumFile in @(Get-ChildItem -LiteralPath $cache -Filter 'SHA256SUMS*' -File -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending)) {
            $found = [regex]::Match((Get-Content -LiteralPath $sumFile.FullName -Raw), '(?im)^([a-f0-9]{64})\s+\*?(bridge-helper-[0-9][0-9A-Za-z.\-]*\.apk)\s*$')
            if ($found.Success) { $assetName = $found.Groups[2].Value; break }
        }
    }
    if (-not $assetName) {
        Write-Warning 'В выпуске нет APK помощника моста (bridge-helper). Автозапуск моста после перезагрузки не настроен; до перезагрузки мост работает.'
        return $false
    }
    $checksum = Get-AddOnChecksum $assetName
    if (-not $checksum) {
        Write-Warning "Нет контрольной суммы для $assetName. Файл без проверки не устанавливается."
        return $false
    }
    $helperPath = Join-Path $cache $assetName
    try {
        $verified = Get-CachedDownload -Uri (Get-AssetUrl $assetName) -Path $helperPath -Sha256 $checksum
    } catch {
        Write-Warning "Помощник моста не прошёл проверку контрольной суммы: $($_.Message)."
        return $false
    }
    if (-not $verified) {
        Write-Warning "Помощник моста недоступен: GitHub не ответил, а файл в кэше отсутствует или не проходит контрольную сумму: $helperPath."
        return $false
    }
    if (-not (Install-AddOnApk $BridgeHelperPackage $helperPath 'Помощник моста')) {
        Write-Warning 'До перезагрузки мост работает, автозапуск после перезагрузки — нет.'
        return $false
    }
    $helperState = Invoke-Adb -Arguments @('-s', $Device, 'shell', 'dumpsys', 'package', $BridgeHelperPackage) -AllowFailure
    if ($helperState -notmatch 'versionName') {
        Write-Warning 'Помощник моста установлен, но пакет не отвечает. Проверьте его вручную.'
        return $false
    }
    Write-Host "Помощник моста установлен: $assetName. После перезагрузки подтвердите разрешение ADB один раз — мост поднимется сам."
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
try { $weatherInstalled = Install-WeatherWidget } catch { Write-Warning "Виджет погоды не установлен: $($_.Message). Лаунчер остаётся настроенным."; $weatherInstalled = $false }
try { $bridgeHelperInstalled = Install-BridgeHelper } catch { Write-Warning "Помощник моста не установлен: $($_.Message). До перезагрузки мост работает, автозапуск — нет."; $bridgeHelperInstalled = $false }
$homeManual = $false
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
        Write-Warning "Не удалось подтвердить назначение Home автоматически. Настройка голоса и моста продолжится; TVHome можно назначить вручную. Ответ Android: $resolved"
    }
}
# Launch the replacement explicitly before disabling Google Home. "Warning: Activity not started" means the
# activity is already in front (a second run), so it counts as a successful launch.
$probe = Invoke-Adb -Arguments @('-s', $Device, 'shell', 'am', 'start', '-W', '-n', 'ru.pda.tvhome/ru.pda.tvhome.MainActivity')
$launchConfirmed = $probe -match '(?m)^Status: ok\s*$' -or $probe -match 'Warning: Activity not started'
if (-not $launchConfirmed) { throw "Не подтверждён запуск TVHome. Google Home остаётся включённым: $probe" }
$googleHome = 'com.google.android.apps.tv.launcherx'
$googleHomePath = Invoke-Adb -Arguments @('-s', $Device, 'shell', 'pm', 'path', $googleHome) -AllowFailure
$googleHomeDisabled = $false
try {
    # Google Home is taken off only when TVHome owns the HOME role: on firmware that never grants the role
    # (DEXP Q series, 2026-11-11) disabling the stock launcher would leave the TV with no working Home, so it
    # stays enabled and the owner assigns TVHome manually.
    if ($roleAssigned -and $googleHomePath -match '(?m)^package:') {
        Write-Host 'Отключаю Google Home для текущего пользователя. Службы доступности не изменяются.'
        $googleHomeDisabled = $true
        Write-Output (Invoke-Adb -Arguments @('-s', $Device, 'shell', 'pm', 'disable-user', '--user', '0', $googleHome))
    } elseif (-not $roleAssigned) {
        Write-Warning 'Google Home не отключён: TVHome не получил роль главного экрана. Назначьте его вручную; голос и мост настраиваются дальше.'
    }
    Write-Output (Invoke-Adb -Arguments @('-s', $Device, 'shell', 'cmd', 'package', 'set-home-activity', '--user', '0', $package))
    # On this firmware the recovery stub wins HOME resolution while it is enabled: a third-party app cannot get
    # an intent-filter priority above 0, so the Home key goes to the stub, which relaunches itself in a burst
    # and shows nothing. The freeze therefore happens before Home is checked, and only after the initial setup
    # of the firmware has finished, so the setup wizard can still run on a fresh TV.
    Freeze-App 'com.google.android.tungsten.setupwraith' -RequireProvisioned | Out-Null
    $homeVerified = $false
    # First-time Google RecoveryActivity may briefly take over HOME. Do not
    # keep restarting it every 500 ms: let its transition finish first.
    $homeLaunch = Invoke-Adb -Arguments @('-s', $Device, 'shell', 'am', 'start', '-W', '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.HOME')
    # Without the HOME role the firmware will not hand HOME over at all, so a few checks are enough before the
    # manual instruction; with the role assigned the recovery-stub transition deserves the full minute.
    $homeAttempts = if ($roleAssigned) { 60 } else { 12 }
    for ($attempt = 0; $attempt -lt $homeAttempts; $attempt++) {
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
            Write-Warning "Роль главного экрана назначена TVHome, но телевизор показал не TVHome. Проверьте кнопку Home после завершения первоначальной настройки прошивки. Диагностика: $work\home-activity.txt"
        } else {
            $homeManual = $true
            Write-Warning "TVHome не назначен главным экраном автоматически: прошивка не дала роль HOME. Голос и остальные настройки применены; назначьте TVHome вручную (системные Настройки → Приложения → Приложения по умолчанию → Главный экран, либо Button Mapper). Диагностика: $work\home-activity.txt"
        }
    } elseif (-not $roleAssigned) {
        $homeManual = $true
        Write-Warning 'TVHome открылся, но роль главного экрана за ним не закреплена. Проверьте кнопку Home и при необходимости назначьте TVHome вручную.'
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
    $ensureBase64 = 'IyEvc3lzdGVtL2Jpbi9zaAojIEVuc3VyZXMgdGhlIGd1YXJkIGlzIHJ1bm5pbmcsIGFuZCBzdGFydHMgbm90aGluZyB0aGF0IGFscmVhZHkgcnVucy4gVGhlIGd1YXJkIGlzIHRoZSBwcm9jZXNzIHRoYXQga2VlcHMgdGhlCiMgZ2VhciBicmlkZ2UgYWxpdmUsIHNvIHRoaXMgc2NyaXB0IG9ubHkgYnJpbmdzIHRoZSBndWFyZCB1cDogYSBicmlkZ2Ugc3RhcnRlZCBmcm9tIHRoZSBzZXNzaW9uIHRoYXQgY2FsbHMgdGhpcwojIHNjcmlwdCBkaWVzIHdoZW4gdGhhdCBzZXNzaW9uIGNsb3Nlcywgd2hpbGUgdGhlIGd1YXJkIHN1cnZpdmVzIGl0LgojIFJ1bnMgYXMgc2hlbGwgKGFkYiksIG5ldmVyIGZyb20gdGhlIGxhdW5jaGVyOiBvbmx5IHRoZSBzaGVsbCBkb21haW4gcmVhZHMgL2Rldi9pbnB1dCBhbmQgcnVucyBhcHBfcHJvY2Vzcy4KIyBTYWZlIHRvIGNhbGwgYWZ0ZXIgZXZlcnkgcmVzdGFydCBvciByZWJvb3Q7IHRoZSBmaWxlcyBhcmUgbm90IHJlLXB1c2hlZCBoZXJlLiBVc2FnZToKIyAgIGFkYiAtcyA8dHYgaXA+OjU1NTUgc2hlbGwgc2ggL2RhdGEvbG9jYWwvdG1wL3R2aG9tZS1icmlkZ2UtZW5zdXJlLnNoCiMgVGhlIGxhdW5jaGVyJ3Mgb24tZGV2aWNlIGhlbHBlciBjYWxscyB0aGlzIHNhbWUgc2NyaXB0IG9uY2UsIGFmdGVyIEhvbWUgaGFzIGRyYXduIGl0cyBmaXJzdCBmcmFtZS4KIyBUVkhPTUVfVE1QIGFuZCBUVkhPTUVfUFJPQyBsZXQgdGVzdHMgcnVuIHRoaXMgc2FtZSBsb2dpYyBhZ2FpbnN0IGZha2UgZGV2aWNlczsgVFZIT01FX0ZPUkNFX0dVQVJEPTEKIyByZXN0YXJ0cyBhIGd1YXJkIHRoYXQgaXMgYWxpdmUgYnV0IGRvZXMgbm90IGFuc3dlciAodXNlZCBieSB0aGUgaW5zdGFsbGVyIGJlZm9yZSBpdHMgcmV0cnkpLgpUTVA9JHtUVkhPTUVfVE1QOi0vZGF0YS9sb2NhbC90bXB9ClBST0M9JHtUVkhPTUVfUFJPQzotL3Byb2N9CkdVQVJEPSRUTVAvYXNzaXN0YW50LXBhY2thZ2UtZ3VhcmQuc2gKUElERklMRT0kVE1QL2Fzc2lzdGFudC1wYWNrYWdlLWd1YXJkLnBpZApIQVNIRklMRT0kVE1QL2Fzc2lzdGFudC1wYWNrYWdlLWd1YXJkLmhhc2gKRU5TVVJFX0xPRz0kVE1QL3R2aG9tZS1icmlkZ2UtZW5zdXJlLmxvZwoKIyBUaGUgbGF1bmNoZXIgZG93bmxvYWRzIHRoZSBndWFyZCBmcm9tIHRoZSByZWxlYXNlIGludG8gaXRzIG93biBleHRlcm5hbCBmb2xkZXI7IG9ubHkgdGhlIHNoZWxsIGRvbWFpbiBjYW4KIyBydW4gaXQsIHNvIHRoZSBjb3B5IGlzIG1hZGUgaGVyZSwgYmVmb3JlIHRoZSBydW5uaW5nIGd1YXJkIGlzIGNvbXBhcmVkIHdpdGggdGhlIGRlcGxveWVkIHNjcmlwdC4gQSBmaWxlIGlzCiMgYWNjZXB0ZWQgb25seSB3aGVuIGl0cyBTSEEtMjU2IG1hdGNoZXMgdGhlIGNoZWNrc3VtIHRoZSBsYXVuY2hlciB3cm90ZSBiZXNpZGUgaXQuCnN0YWdlPS9zZGNhcmQvQW5kcm9pZC9kYXRhL3J1LnBkYS50dmhvbWUvZmlsZXMvYnJpZGdlCmlmIFsgLWYgIiRzdGFnZS9hc3Npc3RhbnQtcGFja2FnZS1ndWFyZC5zaCIgXSAmJiBbIC1mICIkc3RhZ2UvYXNzaXN0YW50LXBhY2thZ2UtZ3VhcmQuc2guc2hhMjU2IiBdOyB0aGVuCiAgd2FudD0kKGNhdCAiJHN0YWdlL2Fzc2lzdGFudC1wYWNrYWdlLWd1YXJkLnNoLnNoYTI1NiIgMj4vZGV2L251bGwpCiAgZ290PSQoc2hhMjU2c3VtICIkc3RhZ2UvYXNzaXN0YW50LXBhY2thZ2UtZ3VhcmQuc2giIDI+L2Rldi9udWxsIHwgYXdrICd7cHJpbnQgJDF9JykKICBpZiBbIC1uICIkd2FudCIgXSAmJiBbICIkd2FudCIgPSAiJGdvdCIgXSAmJgogICAgIHsgWyAhIC1mICIkR1VBUkQiIF0gfHwgWyAiJChzaGEyNTZzdW0gIiRHVUFSRCIgMj4vZGV2L251bGwgfCBhd2sgJ3twcmludCAkMX0nKSIgIT0gIiRnb3QiIF07IH07IHRoZW4KICAgIGlmIGNwICIkc3RhZ2UvYXNzaXN0YW50LXBhY2thZ2UtZ3VhcmQuc2giICIkR1VBUkQiIDI+L2Rldi9udWxsOyB0aGVuCiAgICAgIGVjaG8gImd1YXJkIGNvcGllZCBmcm9tIHRoZSByZWxlYXNlIiA+PiAiJEVOU1VSRV9MT0ciCiAgICBlbHNlCiAgICAgIGVjaG8gImd1YXJkIGNvcHkgZmFpbGVkIiA+PiAiJEVOU1VSRV9MT0ciCiAgICBmaQogIGZpCmZpCgojIEEgcmVjb3JkZWQgcGlkIGNhbiBiZSByZXVzZWQgYnkgYW5vdGhlciBwcm9jZXNzLCBzbyB0aGUgY29tbWFuZCBsaW5lIGlzIGNoZWNrZWQgYmVmb3JlIHRydXN0aW5nIGl0LgpndWFyZF9hbGl2ZT0wCnBpZD0KaWYgWyAtZiAiJFBJREZJTEUiIF07IHRoZW4KICBwaWQ9JChjYXQgIiRQSURGSUxFIiAyPi9kZXYvbnVsbCkKICBpZiBbIC1uICIkcGlkIiBdICYmIGNhdCAiJFBST0MvJHBpZC9jbWRsaW5lIiAyPi9kZXYvbnVsbCB8IHRyICdcMCcgJyAnIHwgZ3JlcCAtcSAic2ggJEdVQVJEIjsgdGhlbgogICAgZ3VhcmRfYWxpdmU9MQogIGZpCmZpCgojIFRoZSBndWFyZCBydW5zIHRoZSBzY3JpcHQgaXQgd2FzIHN0YXJ0ZWQgZnJvbSwgc28gYSBkZXBsb3llZCBzY3JpcHQgdXBkYXRlIGRvZXMgbm90IHJlYWNoIGFuIGluc3RhbmNlIHRoYXQKIyBoYXMgYmVlbiBydW5uaW5nIHNpbmNlIGJlZm9yZSB0aGUgdXBkYXRlOiBvbiB0aGUgYmVkcm9vbSBUViB0aGUgZ3VhcmQgc3RhcnRlZCBvbiAyMDI2LTExLTA1IGtlcHQgYW5zd2VyaW5nCiMgImNoZWNrIiBidXQgbm90IHRoZSBuZXdlciAibGF1bmNoZXIiIHJlcXVlc3QsIGFuZCB0aGUgZ3VhcmQncyBwaWQgYW5kIGNtZGxpbmUgbG9va2VkIGhlYWx0aHkuIENvbXBhcmUgdGhlCiMgZGVwbG95ZWQgc2NyaXB0J3MgaGFzaCB3aXRoIHRoZSByZWNvcmRlZCBvbmUgYW5kIHJlcGxhY2UgdGhlIG9sZCBpbnN0YW5jZS4gQSBtaXNzaW5nIGhhc2ggZmlsZSBtZWFucyB0aGUKIyBndWFyZCB3YXMgc3RhcnRlZCBiZWZvcmUgdGhpcyBjaGVjayBleGlzdGVkLCBzbyBpdCBpcyByZXBsYWNlZCB0b28uCiMgVGhlIGd1YXJkIGNhbm5vdCByZXBsYWNlIHRoZSBzY3JpcHQgaXQgaXMgcnVubmluZyBmcm9tLCBzbyB0aGUgcmVsZWFzZSBidWlsZCB3YWl0cyBuZXh0IHRvIGl0IHVuZGVyIGEKIyB0ZW1wb3JhcnkgbmFtZSBhbmQgaXMgbW92ZWQgaW50byBwbGFjZSBoZXJlOyB0aGUgaGFzaCBjb21wYXJpc29uIGJlbG93IHRoZW4gcmVzdGFydHMgdGhlIGd1YXJkLgppZiBbIC1mICIkVE1QL2Fzc2lzdGFudC1wYWNrYWdlLWd1YXJkLm5ldy5zaCIgXTsgdGhlbgogIGlmIG12IC1mICIkVE1QL2Fzc2lzdGFudC1wYWNrYWdlLWd1YXJkLm5ldy5zaCIgIiRHVUFSRCIgMj4vZGV2L251bGw7IHRoZW4KICAgIGVjaG8gImd1YXJkIG1vdmVkIGZyb20gdGhlIHJlbGVhc2UiID4+ICIkRU5TVVJFX0xPRyIKICBmaQpmaQoKY3VycmVudF9oYXNoPSQobWQ1c3VtICIkR1VBUkQiIDI+L2Rldi9udWxsIHwgYXdrICd7cHJpbnQgJDF9JykKcnVubmluZ19oYXNoPSQoY2F0ICIkSEFTSEZJTEUiIDI+L2Rldi9udWxsKQpmb3JjZT0ke1RWSE9NRV9GT1JDRV9HVUFSRDotMH0KaWYgWyAiJGd1YXJkX2FsaXZlIiA9IDEgXTsgdGhlbgogIGlmIFsgIiRmb3JjZSIgPSAxIF0gfHwgeyBbIC1uICIkY3VycmVudF9oYXNoIiBdICYmIFsgIiRydW5uaW5nX2hhc2giICE9ICIkY3VycmVudF9oYXNoIiBdOyB9OyB0aGVuCiAgICBpZiBbICIkZm9yY2UiID0gMSBdOyB0aGVuIGVjaG8gImd1YXJkIHJlc3RhcnRpbmc6IGZvcmNlZCIgPj4iJEVOU1VSRV9MT0ciCiAgICBlbHNlIGVjaG8gImd1YXJkIHJlc3RhcnRpbmc6IHNjcmlwdCBjaGFuZ2VkIiA+PiIkRU5TVVJFX0xPRyI7IGZpCiAgICBraWxsIC05ICIkcGlkIiAyPi9kZXYvbnVsbAogICAgc2xlZXAgMQogICAgZ3VhcmRfYWxpdmU9MAogIGZpCmZpCgppZiBbICIkZ3VhcmRfYWxpdmUiID0gMSBdOyB0aGVuCiAgZWNobyAiZ3VhcmQgYWxyZWFkeSBydW5uaW5nIiA+PiIkRU5TVVJFX0xPRyIKZWxzZQogIHNldHNpZCBzaCAiJEdVQVJEIiA+PiRUTVAvYXNzaXN0YW50LXBhY2thZ2UtZ3VhcmQubG9nIDI+JjEgPC9kZXYvbnVsbCAmCiAgIyBhZGJkIHRlYXJzIHRoaXMgc2hlbGwgZG93biwgYW5kIGl0cyBwcm9jZXNzIGdyb3VwIHdpdGggaXQsIGFzIHNvb24gYXMgdGhlIGNvbW1hbmQgcmV0dXJucy4gc2V0c2lkCiAgIyBuZWVkcyB0aGlzIHNoZWxsIHRvIHN0YXkgYWxpdmUgZm9yIGEgbW9tZW50IHRvIGxlYXZlIHRoYXQgZ3JvdXA6IHdoZW4gdGhlIGhlbHBlciBjbG9zZXMgdGhlCiAgIyBjb25uZWN0aW9uIHJpZ2h0IGFmdGVyIENMU0UgKHRoZSAxLjcgY2xpZW50IHJlYWRzIGl0OyB0aGUgZWFybGllciBidWlsZCB3YWl0ZWQgZm9yIGEgcmVhZCB0aW1lb3V0KQogICMgdGhlIGd1YXJkIHdhcyBraWxsZWQgYmVmb3JlIGl0IHdyb3RlIGV2ZW4gaXRzIHBpZCAoc2VlbiBvbiAyMDI2LTExLTExKS4gT25lIHNlY29uZCBpcyBlbm91Z2guCiAgc2xlZXAgMQogIGlmIFsgLW4gIiRjdXJyZW50X2hhc2giIF07IHRoZW4gZWNobyAiJGN1cnJlbnRfaGFzaCIgPiAiJEhBU0hGSUxFIjsgZmkKICBlY2hvICJndWFyZCBzdGFydGVkIiA+PiIkRU5TVVJFX0xPRyIKZmkKZXhpdCAwCg=='
    $guardBase64 = 'IyEvc3lzdGVtL2Jpbi9zaAojIFN0YXJ0ZWQgZXhwbGljaXRseSB2aWEgQURCOyBubyBuZXR3b3JrIGxpc3RlbmVyLCBhcmJpdHJhcnkgY29tbWFuZHMsIG9yIGtleSBmaWx0ZXJpbmcuCiMgUmVxdWVzdHMgYW5kIGFja25vd2xlZGdlbWVudHMgcmVxdWlyZSBXUklURV9TRUNVUkVfU0VUVElOR1MgKG5vdCBhbiBvcmRpbmFyeSBhcHAgcGVybWlzc2lvbikuCiMgTW9kZXM6IGNoZWNrLCBsYXVuY2hlciwgc3lzdGVtLCBncmFudCwgZnJlZXplOjxwa2c+Ojx1dWlkPiwgdW5mcmVlemU6PHBrZz46PHV1aWQ+LgojIEEgcGFja2FnZSBvdXRzaWRlIHRoaXMgcHJvdGVjdGVkIG1pcnJvciBvZiB0aGUgbGF1bmNoZXIncyBvd24gc2FmZSBsaXN0IGlzIG5ldmVyIHRvdWNoZWQsCiMgYW5kIGEgcmVmdXNlZCByZXF1ZXN0IGFuc3dlcnMgbm90aGluZywgc28gdGhlIGxhdW5jaGVyIHNldHRpbmdzIHJvdyByZXBvcnRzIG5vIGFuc3dlci4KdG1wX2Rpcj0ke1RWSE9NRV9UTVA6LS9kYXRhL2xvY2FsL3RtcH0KcGlkX2ZpbGU9JHRtcF9kaXIvYXNzaXN0YW50LXBhY2thZ2UtZ3VhcmQucGlkCiMgTm8gc2luZ2xlLWluc3RhbmNlIGV4aXQgb24gdGhlIHJlY29yZGVkIHBpZDogdGhlIGZpbGUgc3Vydml2ZXMgYSByZWJvb3QsIGl0cyBwaWQgZ2V0cyByZXVzZWQgYnkKIyBhbm90aGVyIHByb2Nlc3MsIGFuZCBvbiAyMDI2LTExLTEwIGV2ZXJ5IGJvb3QgaW5zdGFuY2UgZXhpdGVkIHJlYWRpbmcgaXRzIG93biBzdGFsZSByZWNvcmQuCiMgVGhlIGVuc3VyZSBzY3JpcHQgaXMgdGhlIHNpbmdsZS1pbnN0YW5jZSBwb2ludCwgYW5kIGEgZHVwbGljYXRlZCBpbnN0YW5jZSBhbnN3ZXJzIHRoZSBzYW1lIHdheSwKIyBzbyB0aGUgZ3VhcmQgb25seSByZWNvcmRzIGl0cyBwaWQgaGVyZSBhbmQga2VlcHMgcnVubmluZy4KIyBObyBleGl0IHRyYXAgb24gc2lnbmFsczogdGhlIGJvb3QgaW5zdGFuY2UgZGllZCBmcm9tIGEgc2lnbmFsIHJpZ2h0IGFmdGVyIHRoZSBoZWxwZXIgc2Vzc2lvbgojIGNsb3NlZCBvbiAyMDI2LTExLTEwLCBzbyB0aGUgcm93cyB3ZW50IHNpbGVudCB1bnRpbCB0aGUgbmV4dCBlbnN1cmUgY2FsbC4gVGhlIHNpZ25hbHMgYXJlIGlnbm9yZWQKIyBpbnN0ZWFkOyB0aGUgZW5zdXJlIHNjcmlwdCBzdGFydHMgdGhlIGd1YXJkIGZyb20gc2V0dGluZ3MsIHNvIHN1cnZpdmluZyB0aGVtIGlzIHdoYXQgbWF0dGVycy4KZWNobyAkJCA+ICIkcGlkX2ZpbGUiCnRyYXAgOiBIVVAgSU5UIFRFUk0KdHJhY2VfZmlsZT0kdG1wX2Rpci9hc3Npc3RhbnQtcGFja2FnZS1ndWFyZC50cmFjZQplY2hvICJwaWQgJCQgc3RhcnRlZCIgPj4gIiR0cmFjZV9maWxlIgpkZXhfZmlsZT0kdG1wX2Rpci90dmhvbWUtcmVtb3RlLTIuZGV4CmJyaWRnZV9sb2c9JHRtcF9kaXIvdHZob21lLXJlbW90ZS0yLmxvZwpicmlkZ2VfbmFtZT1wcXR2LWJyaWRnZQojIEhvdyBtYW55IHBhc3NlcyB0byBza2lwIGJlZm9yZSBsb29raW5nIGZvciB0aGUgcmVtb3RlIGFnYWluOyBzZWUgdGhlIGJyaWRnZSBibG9jayBiZWxvdy4Kc2VhcmNoX3BhdXNlPTAKWyAtZiAiJGRleF9maWxlIiBdIHx8IGVjaG8gIm5vIGRleCIgPj4gIiR0cmFjZV9maWxlIgpyZXF1ZXN0X2tleT1ydV9wZGFfdHZob21lX2Fzc2lzdGFudF9yZXF1ZXN0CmFja19rZXk9cnVfcGRhX3R2aG9tZV9hc3Npc3RhbnRfYWNrCnBhY2thZ2U9Y29tLmdvb2dsZS5hbmRyb2lkLmthdG5pc3MKIyBUaGUgY29tcG9uZW50IHRoZSByZW1vdGUgbWljcm9waG9uZSBidXR0b24gcmVhY2hlcyB3aGVuIHRoZSBsYXVuY2hlciBpcyB0aGUgY2hvc2VuIGFzc2lzdGFudC4KbGF1bmNoZXJfYXNzaXN0YW50PXJ1LnBkYS50dmhvbWUvLlZvaWNlTGF1bmNoQWN0aXZpdHkKcHJvdGVjdGVkPSIgcnUucGRhLnR2aG9tZSBhbmRyb2lkIGNvbS5hbmRyb2lkLnR2LnNldHRpbmdzIGNvbS5tZWRpYXRlay50dmlucHV0IGNvbS5nb29nbGUuYW5kcm9pZC50di5pbnB1dHBsYXllciBjb20ubWVkaWF0ZWsudHYuaW5wdXRwbGF5ZXJvdmVybGF5IGNvbS5tZWRpYXRlay50di5zZXJ2aWNlIGNvbS5tZWRpYXRlay5hbmRyb2lkLmxlYW5iYWNrbGF1bmNoZXIucGFydG5lcmN1c3RvbWl6ZXIgY29tLm1lZGlhdGVrLmFuZHJvaWRib3ggY29tLm1lZGlhdGVrLmZhY3RvcnlrZXkgY29tLm1lZGlhdGVrLnd3dHYud2VidmlldyBjb20uZ29vZ2xlLmFuZHJvaWQud2VidmlldyBjb20uZ29vZ2xlLmFuZHJvaWQuaW5wdXRtZXRob2QubGF0aW4gY29tLmdvb2dsZS5hbmRyb2lkLmdtcyBjb20uZ29vZ2xlLmFuZHJvaWQuZ3NmIGNvbS5nb29nbGUuYW5kcm9pZC5wbGF5IGNvbS5hbmRyb2lkLnZlbmRpbmcgY29tLmFuZHJvaWQuc3lzdGVtdWkgY29tLmFuZHJvaWQucHJvdmlkZXJzLnNldHRpbmdzIGNvbS5hbmRyb2lkLnByb3ZpZGVycy50diBjb20uYW5kcm9pZC5wcm92aWRlcnMuZG93bmxvYWRzIGNvbS5nb29nbGUuYW5kcm9pZC50di5hc3Npc3RhbnQgY29tLmdvb2dsZS5hbmRyb2lkLnR2LnJlbW90ZS5zZXJ2aWNlIGNvbS5nb29nbGUuYW5kcm9pZC5hcHBzLm1lZGlhc2hlbGwgY29tLmdvb2dsZS5hbmRyb2lkLmFwcHMudHYuZHJlYW14IGNvbS5nb29nbGUuYW5kcm9pZC50dHMgY29tLmRvbGJ5LmFuZHJvaWQuYXVkaW8uc2VydmljZSBjb20ubWVkaWF0ZWsuc3BlYWtlcnNlcnZpY2UgY29tLmhvbXdlZS5keW5hbWljYmFja2xpZ2h0IGNvbS5ob213ZWUubGlnaHRzZW5zb3JzZXJ2aWNlIGNvbS5ob213ZWUuc3luY2tleSBjb20uaG9td2VlLnVwZ3JhZGUgY29tLmhvbXdlZS5kbXQudXBncmFkZSBjb20uaG9td2VlLm1tcC5zY3JlZW5zaGFyZSBjb20uaG9td2VlLm11bHRpc2NyZWVuc2hhcmUgY29tLm1lZGlhdGVrLmF1dG9wYWlyIGNvbS5hbmRyb2lkLnN0YXRlbWVudHNlcnZpY2UgY29tLmFuZHJvaWQucmtwZGFwcCBjb20uYW5kcm9pZC5kZXZpY2Vsb2NrY29udHJvbGxlciBjb20uYW5kcm9pZC5tYW5hZ2VkcHJvdmlzaW9uaW5nIGNvbS5hbmRyb2lkLnBhY2thZ2VpbnN0YWxsZXIgY29tLmdvb2dsZS5hbmRyb2lkLnBhY2thZ2VpbnN0YWxsZXIgY29tLmFuZHJvaWQua2V5Y2hhaW4gY29tLmFuZHJvaWQuYmFja3VwY29uZmlybSBjb20uYW5kcm9pZC5jZXJ0aW5zdGFsbGVyIGNvbS5hbmRyb2lkLnR2LmZyYW1ld29ya3BhY2thZ2VzdHVicyBjb20uYW5kcm9pZC52cG5kaWFsb2dzIGNvbS5hbmRyb2lkLnNlIGNvbS5hbmRyb2lkLmJsdWV0b290aCBjb20uYW5kcm9pZC53aWZpLnJlc291cmNlcyBjb20uYW5kcm9pZC5uZXR3b3Jrc3RhY2sgY29tLmFuZHJvaWQuaW5wdXRkZXZpY2VzIGNvbS5nb29nbGUuYW5kcm9pZC5leHQuc2VydmljZXMgY29tLmdvb2dsZS5hbmRyb2lkLmV4dC5zaGFyZWQgY29tLmdvb2dsZS5hbmRyb2lkLm1vZHVsZW1ldGFkYXRhIGNvbS5nb29nbGUuYW5kcm9pZC5wZXJtaXNzaW9uY29udHJvbGxlciAiCmNtZCBzZXR0aW5ncyBkZWxldGUgc2VjdXJlICIkYWNrX2tleSIgPi9kZXYvbnVsbCAyPiYxCiMgVG90YWwgQ1BVIHVzYWdlOiB0aGUgc2hlbGwgZ3VhcmQgY2FuIHJlYWQgL3Byb2Mvc3RhdCwgd2hpbGUgdGhlIGxhdW5jaGVyIGFwcCBjYW5ub3QuIEtlZXAgdGhlIG1vc3QgcmVjZW50CiMgYnVzeSBwZXJjZW50IGluIGEgc2VjdXJlIHNldHRpbmcgc28gdGhlIENQVSByb3cgY2FuIHNob3cgYSBudW1iZXIgZXZlbiB3aGVuIHByb2MgZmlsZXMgYXJlIGRlbmllZCBieSBTRUxpbnV4LgpzdGF0c19rZXk9cnVfcGRhX3R2aG9tZV9jcHUKKAogICAgbGFzdF90b3RhbD0tMQogICAgbGFzdF9pZGxlPTAKICAgIHdoaWxlIDo7IGRvCiAgICAgICAgbGluZT0kKGhlYWQgLTEgL3Byb2Mvc3RhdCAyPi9kZXYvbnVsbCkKICAgICAgICBpZiBbIC1uICIkbGluZSIgXTsgdGhlbgogICAgICAgICAgICBzZXQgLS0gJGxpbmUKICAgICAgICAgICAgdXNlcj0kezI6LTB9CiAgICAgICAgICAgIG5pY2U9JHszOi0wfQogICAgICAgICAgICBzeXN0ZW09JHs0Oi0wfQogICAgICAgICAgICBpZGxlPSR7NTotMH0KICAgICAgICAgICAgaW9fd2FpdD0kezY6LTB9CiAgICAgICAgICAgIGlycT0kezc6LTB9CiAgICAgICAgICAgIHNvZnRfaXJxPSR7ODotMH0KICAgICAgICAgICAgdG90YWw9JCgodXNlciArIG5pY2UgKyBzeXN0ZW0gKyBpZGxlICsgaW9fd2FpdCArIGlycSArIHNvZnRfaXJxKSkKICAgICAgICAgICAgaWYgWyAiJHRvdGFsIiAtZ3QgMCBdOyB0aGVuCiAgICAgICAgICAgICAgICBpZiBbICIkbGFzdF90b3RhbCIgLWd0IDAgXTsgdGhlbgogICAgICAgICAgICAgICAgICAgIGR0PSQoKHRvdGFsIC0gbGFzdF90b3RhbCkpCiAgICAgICAgICAgICAgICAgICAgZGk9JCgoaWRsZSAtIGxhc3RfaWRsZSkpCiAgICAgICAgICAgICAgICAgICAgaWYgWyAiJGR0IiAtZ3QgMCBdOyB0aGVuCiAgICAgICAgICAgICAgICAgICAgICAgIGJ1c3k9JCgoKGR0IC0gZGkpICogMTAwIC8gZHQpKQogICAgICAgICAgICAgICAgICAgIGVsc2UKICAgICAgICAgICAgICAgICAgICAgICAgYnVzeT0wCiAgICAgICAgICAgICAgICAgICAgZmkKICAgICAgICAgICAgICAgIGVsc2UKICAgICAgICAgICAgICAgICAgICBidXN5PSQoKCh0b3RhbCAtIGlkbGUpICogMTAwIC8gdG90YWwpKQogICAgICAgICAgICAgICAgZmkKICAgICAgICAgICAgICAgIGlmIFsgIiRidXN5IiAtbHQgMCBdOyB0aGVuIGJ1c3k9MDsgZmkKICAgICAgICAgICAgICAgIGlmIFsgIiRidXN5IiAtZ3QgMTAwIF07IHRoZW4gYnVzeT0xMDA7IGZpCiAgICAgICAgICAgICAgICBjbWQgc2V0dGluZ3MgcHV0IHNlY3VyZSAiJHN0YXRzX2tleSIgIiRidXN5IiA+L2Rldi9udWxsIDI+JjEKICAgICAgICAgICAgICAgIGxhc3RfdG90YWw9JHRvdGFsCiAgICAgICAgICAgICAgICBsYXN0X2lkbGU9JGlkbGUKICAgICAgICAgICAgZmkKICAgICAgICBmaQogICAgICAgIHNsZWVwIDUKICAgIGRvbmUKKSAmCiMgUmVtb3RlIGdlYXI6IHRoZSBzdG9jayBzaGVsbCByZWFjdHMgb24gaXRzIG93biBhbmQgc2hvd3MgdGhlIG9sZCB0dXJua2V5IG1lbnUgbmV4dCB0byB0aGUgbGF1bmNoZXIKIyBzZXR0aW5ncy4gVGhlIHBoeXNpY2FsIGdlYXIgYWxzbyBnb2VzIHRvIHRoZSBmYWN0b3J5IHJlY2VpdmVyLCB3aGljaCBvcGVucyBzeXN0ZW0gc2V0dGluZ3MgbmV4dCB0bwojIHRoZSBwaWN0dXJlIHNldHRpbmdzIHdpbmRvdzsgdGhlIG93bmVyIGRlY2lkZWQgb24gMjAyNi0xMS0xMCB0aGF0IHRoZSByZWNlaXZlciBwYWNrYWdlIGlzIGZyb3plbgojIGluc3RlYWQgb2YgcHJvdGVjdGVkLiBCb3RoIGVudHJpZXMgYXJlIGZyb3plbiBhdCBldmVyeSBzdGFydDsgYWxyZWFkeSBvZmYgY2hhbmdlcyBub3RoaW5nLgojIEF0IGJvb3QgcG0gYW5zd2VycyBub3RoaW5nIHVudGlsIHRoZSB1c2VyIGlzIHVubG9ja2VkLCBzbyB0aGUgZ3VhcmQgbmV2ZXIgZXhpdHMgb24gYSBmYWlsZWQgcG0KIyBwYXNzOiBpdCB3YWl0cywgZnJlZXplcyBvbiB0aGUgZmlyc3Qgd29ya2luZyBwYXNzLCBhbmQgYW5zd2VycyByZXF1ZXN0cyBhcyBzb29uIGFzIHNldHRpbmdzIHdvcmsuCiMgT24gMjAyNi0xMS0xMCBhIHRocmVzaG9sZCBleGl0IGtpbGxlZCB0aGUgYm9vdCBpbnN0YW5jZSBiZWZvcmUgdW5sb2NrIGFuZCB0aGUgc2V0dGluZ3Mgcm93cyB3ZW50CiMgc2lsZW50IG9uIHRoZSBmcmVzaGx5IHN0YXJ0ZWQgVFYuIFRoZSB0cmFjZSBmaWxlIHJlY29yZHMgd2hlcmUgYSBib290IGluc3RhbmNlIHN0b3BzLCBzbyB0aGUgZGVhdGgKIyBjYW4gYmUgcmVhZCBpbnN0ZWFkIG9mIGd1ZXNzZWQ6IHN0YXJ0LCB3YWl0aW5nIGZvciBwbSwgZnJlZXplIGRvbmUuCmJvb3RfZnJlZXplPTAKcG1fd2FpdF9sb2dnZWQ9MApsYXN0X3JlcXVlc3Q9CndoaWxlIDo7IGRvCiAgICBpZiBwbSBwYXRoIHJ1LnBkYS50dmhvbWUgPi9kZXYvbnVsbCAyPiYxOyB0aGVuCiAgICAgICAgaWYgWyAiJGJvb3RfZnJlZXplIiA9IDAgXTsgdGhlbgogICAgICAgICAgICBwbSBkaXNhYmxlLXVzZXIgLS11c2VyIDAgY29tLm1lZGlhdGVrLnd3dHYudHZjZW50ZXIgPi9kZXYvbnVsbCAyPiYxCiAgICAgICAgICAgIHBtIGRpc2FibGUtdXNlciAtLXVzZXIgMCBjb20uaG9td2VlLmZhY3RvcnkgPi9kZXYvbnVsbCAyPiYxCiAgICAgICAgICAgIHBtIGRpc2FibGUtdXNlciAtLXVzZXIgMCBjb20uZ29vZ2xlLmFuZHJvaWQuYXBwcy50di5sYXVuY2hlcnggPi9kZXYvbnVsbCAyPiYxCiAgICAgICAgICAgICMgSG9tZSByZXNvbHV0aW9uOiB3aGlsZSB0aGUgcmVjb3Zlcnkgc3R1YiBpcyBlbmFibGVkIHRoZSBzeXN0ZW0gcmVzb2x2ZXMgSE9NRSB0byBpdHMKICAgICAgICAgICAgIyBSZWNvdmVyeUFjdGl2aXR5LCB3aGljaCByZWxhdW5jaGVzIGl0c2VsZiBpbiBhIGJ1cnN0IGFuZCBzaG93cyBub3RoaW5nLCBzbyB0aGUgSG9tZSBrZXkgbmV2ZXIKICAgICAgICAgICAgIyByZWFjaGVzIFRWSG9tZSBmcm9tIGFueSBhcHAsIHRoZSBzZXR0aW5ncyBtZW51IGluY2x1ZGVkIC0gc2VlbiBvbiB0aGUgbGl2aW5nLXJvb20gVFYgb24KICAgICAgICAgICAgIyAyMDI2LTExLTExLiBJdCBpcyBmcm96ZW4gb25seSB3aGVuIHRoZSBpbml0aWFsIHNldHVwIGlzIGZpbmlzaGVkLCBzbyB0aGUgc2V0dXAgd2l6YXJkIGNhbiBzdGlsbAogICAgICAgICAgICAjIHJ1bjsgdGhlIGluc3RhbGxlciBhcHBsaWVzIHRoZSBzYW1lIHJ1bGUsIGFuZCB0aGUgdHJhY2UgcmVjb3JkcyB3aGljaCB3YXkgdGhpcyBwYXNzIHdlbnQuCiAgICAgICAgICAgIGlmIFsgIiQoY21kIHNldHRpbmdzIGdldCBnbG9iYWwgZGV2aWNlX3Byb3Zpc2lvbmVkKSIgPSAxIF07IHRoZW4KICAgICAgICAgICAgICAgIHBtIGRpc2FibGUtdXNlciAtLXVzZXIgMCBjb20uZ29vZ2xlLmFuZHJvaWQudHVuZ3N0ZW4uc2V0dXB3cmFpdGggPi9kZXYvbnVsbCAyPiYxCiAgICAgICAgICAgICAgICBlY2hvICJob21lIHN0dWIgZnJvemVuIiA+PiAiJHRyYWNlX2ZpbGUiCiAgICAgICAgICAgIGVsc2UKICAgICAgICAgICAgICAgIGVjaG8gImhvbWUgc3R1YiBsZWZ0IG9uOiBpbml0aWFsIHNldHVwIG5vdCBmaW5pc2hlZCIgPj4gIiR0cmFjZV9maWxlIgogICAgICAgICAgICBmaQogICAgICAgICAgICAjIFRoZSBvd25lcidzIHJ1bGUgb2YgMjAyNi0xMS0xNDogdGhlIHJlbW90ZSBtaWNyb3Bob25lIGJ1dHRvbiBiZWxvbmdzIHRvIHRoZSBsYXVuY2hlcidzIG93biB2b2ljZQogICAgICAgICAgICAjIHNjcmVlbiB3aGlsZSB0aGUgYXNzaXN0YW50IHNldHRpbmcgbmFtZXMgaXQsIHNvIHRoZSBHb29nbGUgYXNzaXN0YW50IHN0YXlzIGZyb3plbiBhZnRlciBhIHJlYm9vdAogICAgICAgICAgICAjIG9yIGEgZmlybXdhcmUgdXBkYXRlIHRoYXQgcHV0cyB0aGUgcGFja2FnZSBiYWNrLiBTd2l0Y2hpbmcgdG8gdGhlIHN5c3RlbSBhc3Npc3RhbnQgdW5mcmVlemVzIGl0LgogICAgICAgICAgICBpZiBbICIkKGNtZCBzZXR0aW5ncyBnZXQgc2VjdXJlIGFzc2lzdGFudCkiID0gIiRsYXVuY2hlcl9hc3Npc3RhbnQiIF07IHRoZW4KICAgICAgICAgICAgICAgIHBtIGRpc2FibGUtdXNlciAtLXVzZXIgMCAiJHBhY2thZ2UiID4vZGV2L251bGwgMj4mMQogICAgICAgICAgICAgICAgZWNobyAia2F0bmlzcyBmcm96ZW4gZm9yIHRoZSBsYXVuY2hlciBhc3Npc3RhbnQiID4+ICIkdHJhY2VfZmlsZSIKICAgICAgICAgICAgZmkKCiAgICAgICAgICAgICMgSERNSSBjYW5ub3QgYmUgb3BlbmVkIG9uIHRoZSBNZWRpYVRlay9DSGlRIHNldHMgd2hpbGUgdGhlIHN0b2NrIFRWLXNldHRpbmdzL2lucHV0CiAgICAgICAgICAgICMgc2VydmljZXMgYXJlIGZyb3plbiwgZXZlbiB3aGVuIFRWSG9tZSBpcyB0aGUgSG9tZS4gRmlybXdhcmUsIGEgcHJldmlvdXMgY2xlYW51cCBvciBhCiAgICAgICAgICAgICMgcmVib290IGNhbiBsZWF2ZSB0aGVtIGRpc2FibGVkIGZvciB1c2VyIDAsIHNvIGV2ZXJ5IHN0YXJ0IHdha2VzIHRoZSBzZXJ2aWNlcyBiYWNrLgogICAgICAgICAgICBpZiBwbSBwYXRoIGNvbS5hbmRyb2lkLnR2LnNldHRpbmdzID4vZGV2L251bGwgMj4mMSB8fAogICAgICAgICAgICAgICBwbSBwYXRoIGNvbS5tZWRpYXRlay50dmlucHV0ID4vZGV2L251bGwgMj4mMSB8fAogICAgICAgICAgICAgICBwbSBwYXRoIGNvbS5tZWRpYXRlay50di5zZXJ2aWNlID4vZGV2L251bGwgMj4mMTsgdGhlbgogICAgICAgICAgICAgICAgZm9yIHNlcnZpY2UgaW4gY29tLmFuZHJvaWQudHYuc2V0dGluZ3MgY29tLm1lZGlhdGVrLnR2aW5wdXQgY29tLm1lZGlhdGVrLnR2LnNlcnZpY2U7IGRvCiAgICAgICAgICAgICAgICAgICAgaWYgcG0gcGF0aCAiJHNlcnZpY2UiID4vZGV2L251bGwgMj4mMSAmJgogICAgICAgICAgICAgICAgICAgICAgIGNtZCBkdW1wc3lzIHBhY2thZ2UgIiRzZXJ2aWNlIiAyPi9kZXYvbnVsbCB8IGdyZXAgLXEgJ2VuYWJsZWQ9MCcgJiYKICAgICAgICAgICAgICAgICAgICAgICBwbSBlbmFibGUgLS11c2VyIDAgIiRzZXJ2aWNlIiA+L2Rldi9udWxsIDI+JjE7IHRoZW4KICAgICAgICAgICAgICAgICAgICAgICAgZWNobyAiSERNSSBzZXJ2aWNlcyB1bmZyb3plbjogJHNlcnZpY2UiID4+ICIkdHJhY2VfZmlsZSIKICAgICAgICAgICAgICAgICAgICBmaQogICAgICAgICAgICAgICAgZG9uZQogICAgICAgICAgICBmaQogICAgICAgICAgICBib290X2ZyZWV6ZT0xCiAgICAgICAgICAgIGVjaG8gImZyZWV6ZSBkb25lIiA+PiAiJHRyYWNlX2ZpbGUiCiAgICAgICAgZmkKICAgIGVsaWYgWyAiJHBtX3dhaXRfbG9nZ2VkIiA9IDAgXTsgdGhlbgogICAgICAgIHBtX3dhaXRfbG9nZ2VkPTEKICAgICAgICBlY2hvICJ3YWl0aW5nOiBwbSBub3QgYW5zd2VyaW5nIiA+PiAiJHRyYWNlX2ZpbGUiCiAgICBmaQogICAgcmVxdWVzdD0kKGNtZCBzZXR0aW5ncyBnZXQgc2VjdXJlICIkcmVxdWVzdF9rZXkiKQogICAgaWYgWyAiJHJlcXVlc3QiICE9ICIkbGFzdF9yZXF1ZXN0IiBdOyB0aGVuCiAgICAgICAgbW9kZT0ke3JlcXVlc3QlJToqfQogICAgICAgIGFyZz0ke3JlcXVlc3QjIiRtb2RlIjp9CiAgICAgICAgY2FzZSAiJG1vZGUiIGluCiAgICAgICAgICAgIGNoZWNrKSBjbWQgc2V0dGluZ3MgcHV0IHNlY3VyZSAiJGFja19rZXkiICIkcmVxdWVzdCIgOzsKICAgICAgICAgICAgZ3JhbnQpCiAgICAgICAgICAgICAgICAjIFRoZSBwZXJtaXNzaW9ucyB0aGUgbGF1bmNoZXIgZGVjbGFyZWQgYnV0IGFuIGFwcCBjYW5ub3QgZ3JhbnQgdG8gaXRzZWxmOyB0aGUKICAgICAgICAgICAgICAgICMgc2V0dGluZ3MgbWVudSBoYXMgbm8gb3RoZXIgd2F5IHRvIGdldCB0aGVtIGFmdGVyIGEgcmVpbnN0YWxsIHJlc2V0cyB0aGUgc3RhdGUuCiAgICAgICAgICAgICAgICBpZiBwbSBncmFudCBydS5wZGEudHZob21lIGFuZHJvaWQucGVybWlzc2lvbi5SRUNPUkRfQVVESU8gPi9kZXYvbnVsbCAyPiYxICYmCiAgICAgICAgICAgICAgICAgICAgICAgIGFwcG9wcyBzZXQgcnUucGRhLnR2aG9tZSBSRUNPUkRfQVVESU8gYWxsb3cgPi9kZXYvbnVsbCAyPiYxICYmCiAgICAgICAgICAgICAgICAgICAgICAgIGFwcG9wcyBzZXQgcnUucGRhLnR2aG9tZSBTWVNURU1fQUxFUlRfV0lORE9XIGFsbG93ID4vZGV2L251bGwgMj4mMSAmJgogICAgICAgICAgICAgICAgICAgICAgICBhcHBvcHMgc2V0IHJ1LnBkYS50dmhvbWUgR0VUX1VTQUdFX1NUQVRTIGFsbG93ID4vZGV2L251bGwgMj4mMTsgdGhlbgogICAgICAgICAgICAgICAgICAgIGNtZCBzZXR0aW5ncyBwdXQgc2VjdXJlICIkYWNrX2tleSIgIiRyZXF1ZXN0IgogICAgICAgICAgICAgICAgZmkKICAgICAgICAgICAgICAgIDs7CiAgICAgICAgICAgIGZyZWV6ZSkKICAgICAgICAgICAgICAgIHBrZz0ke2FyZyUlOip9CiAgICAgICAgICAgICAgICBjYXNlICIkcHJvdGVjdGVkIiBpbiAqIiAkcGtnICIqKSA7OwogICAgICAgICAgICAgICAgICAgICopCiAgICAgICAgICAgICAgICAgICAgICAgICMgQSBwYWNrYWdlIGFscmVhZHkgb2ZmIGFuc3dlcnMgbm90aGluZyBmcm9tIGRpc2FibGUtdXNlciwgc28gYWxyZWFkeS1vZmYgaXMKICAgICAgICAgICAgICAgICAgICAgICAgIyBjb3VudGVkIGFzIHRoZSBzYW1lIHN0YXRlIHRocm91Z2ggdGhlIGRpc2FibGVkIGxpc3QuCiAgICAgICAgICAgICAgICAgICAgICAgIGlmIHBtIGRpc2FibGUtdXNlciAtLXVzZXIgMCAiJHBrZyIgPi9kZXYvbnVsbCAyPiYxIHx8CiAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgcG0gbGlzdCBwYWNrYWdlcyAtZCB8IGdyZXAgLXF4ICIkcGtnIjsgdGhlbgogICAgICAgICAgICAgICAgICAgICAgICAgICAgY21kIHNldHRpbmdzIHB1dCBzZWN1cmUgIiRhY2tfa2V5IiAiJHJlcXVlc3QiCiAgICAgICAgICAgICAgICAgICAgICAgIGZpCiAgICAgICAgICAgICAgICAgICAgOzsKICAgICAgICAgICAgICAgIGVzYWMKICAgICAgICAgICAgICAgIDs7CiAgICAgICAgICAgIHVuZnJlZXplKQogICAgICAgICAgICAgICAgcGtnPSR7YXJnJSU6Kn0KICAgICAgICAgICAgICAgIGNhc2UgIiRwcm90ZWN0ZWQiIGluICoiICRwa2cgIiopIDs7CiAgICAgICAgICAgICAgICAgICAgKikKICAgICAgICAgICAgICAgICAgICAgICAgaWYgcG0gZW5hYmxlIC0tdXNlciAwICIkcGtnIiA+L2Rldi9udWxsIDI+JjE7IHRoZW4KICAgICAgICAgICAgICAgICAgICAgICAgICAgIGNtZCBzZXR0aW5ncyBwdXQgc2VjdXJlICIkYWNrX2tleSIgIiRyZXF1ZXN0IgogICAgICAgICAgICAgICAgICAgICAgICBmaQogICAgICAgICAgICAgICAgICAgIDs7CiAgICAgICAgICAgICAgICBlc2FjCiAgICAgICAgICAgICAgICA7OwogICAgICAgICAgICBsYXVuY2hlcikKICAgICAgICAgICAgICAgICMgVGhlIHBoeXNpY2FsIHJlbW90ZSdzIG1pYyBidXR0b24gaXMgc2VydmljZWQgYnkgQXR2UmVtb3RlLCB3aGljaCBmb2xsb3dzIHRoZSAiYXNzaXN0YW50IgogICAgICAgICAgICAgICAgIyBzZXR0aW5nLiBDaGVja2VkIG9uIHRoZSBsaXZpbmctcm9vbSBUViBvbiAyMDI2LTExLTE0OiBuYW1pbmcgVFZIb21lJ3Mgdm9pY2UgYWN0aXZpdHkgbWFrZXMgdGhlCiAgICAgICAgICAgICAgICAjIHJlbW90ZSBidXR0b24gb3BlbiB0aGUgbGF1bmNoZXIncyBvd24gc2VhcmNoLCBzbyB0aGUgbGF1bmNoZXIgY2hvaWNlIHBvaW50cyB0aGUgYXNzaXN0YW50IGF0CiAgICAgICAgICAgICAgICAjIFRWSG9tZSBhbmQgZnJlZXplcyBLYXRuaXNzLiBUaGUgc3lzdGVtIGNob2ljZSwgdGhlICJzeXN0ZW0iIG1vZGUgYmVsb3csIHVuZnJlZXplcyBpdCBhZ2Fpbi4KICAgICAgICAgICAgICAgIGlmIGNtZCBzZXR0aW5ncyBwdXQgc2VjdXJlIGFzc2lzdGFudCAiJGxhdW5jaGVyX2Fzc2lzdGFudCIgPi9kZXYvbnVsbCAyPiYxICYmCiAgICAgICAgICAgICAgICAgICAgICAgIGNtZCBzZXR0aW5ncyBwdXQgc2VjdXJlIHZvaWNlX2ludGVyYWN0aW9uX3NlcnZpY2UgcnUucGRhLnR2aG9tZS9ydS5wZGEudHZob21lLkxhdW5jaGVyQXNzaXN0YW50U2VydmljZSA+L2Rldi9udWxsIDI+JjEgJiYKICAgICAgICAgICAgICAgICAgICAgICAgY21kIHNldHRpbmdzIHB1dCBzZWN1cmUgdm9pY2VfcmVjb2duaXRpb25fc2VydmljZSBjb20uZ29vZ2xlLmFuZHJvaWQudHRzL2NvbS5nb29nbGUuYW5kcm9pZC5hcHBzLnNwZWVjaC50dHMuZ29vZ2xldHRzLnNlcnZpY2UuR29vZ2xlVFRTUmVjb2duaXRpb25TZXJ2aWNlID4vZGV2L251bGwgMj4mMSAmJgogICAgICAgICAgICAgICAgICAgICAgICAoIHBtIGRpc2FibGUtdXNlciAtLXVzZXIgMCAiJHBhY2thZ2UiID4vZGV2L251bGwgMj4mMSB8fAogICAgICAgICAgICAgICAgICAgICAgICAgIHBtIGxpc3QgcGFja2FnZXMgLWQgfCBncmVwIC1xeCAiJHBhY2thZ2UiICk7IHRoZW4KICAgICAgICAgICAgICAgICAgICBjbWQgc2V0dGluZ3MgcHV0IHNlY3VyZSAiJGFja19rZXkiICIkcmVxdWVzdCIKICAgICAgICAgICAgICAgIGZpCiAgICAgICAgICAgICAgICA7OwogICAgICAgICAgICBzeXN0ZW0pCiAgICAgICAgICAgICAgICAjIFRoZSBvd25lciBjaG9zZSB0aGUgc3lzdGVtIGFzc2lzdGFudDogS2F0bmlzcyBoYXMgdG8gYmUgYWJsZSB0byBhbnN3ZXIgdGhlIHJlbW90ZSBidXR0b24sCiAgICAgICAgICAgICAgICAjIGFuZCB0aGUgYXNzaXN0YW50IHNldHRpbmcgaGFzIHRvIG5hbWUgaXQgZm9yIHRoZSByZW1vdGUgbWljcm9waG9uZSB0byBiZSByb3V0ZWQgdG8gaXQuCiAgICAgICAgICAgICAgICBpZiBwbSBlbmFibGUgLS11c2VyIDAgIiRwYWNrYWdlIiA+L2Rldi9udWxsIDI+JjEgJiYKICAgICAgICAgICAgICAgICAgICAgICAgY21kIHNldHRpbmdzIHB1dCBzZWN1cmUgYXNzaXN0YW50ICIkcGFja2FnZS8uc2VhcmNoLnNlcnZpY2VhcGkuS2F0bmlzc1ZvaWNlSW50ZXJhY3Rpb25TZXJ2aWNlIiA+L2Rldi9udWxsIDI+JjE7IHRoZW4KICAgICAgICAgICAgICAgICAgICBjbWQgc2V0dGluZ3MgcHV0IHNlY3VyZSAiJGFja19rZXkiICIkcmVxdWVzdCIKICAgICAgICAgICAgICAgIGZpCiAgICAgICAgICAgICAgICA7OwogICAgICAgIGVzYWMKICAgICAgICBsYXN0X3JlcXVlc3Q9JHJlcXVlc3QKICAgIGZpCiAgICAjIFRoZSBnZWFyIGJyaWRnZSBjYW4gb25seSBiZSBzdGFydGVkIGJ5IHRoZSBzaGVsbCBkb21haW4sIHNvIHRoaXMgbG9vcCBpcyB3aGF0IGtlZXBzIGl0IHJ1bm5pbmcuIEEgYnJpZGdlCiAgICAjIHN0YXJ0ZWQgc3RyYWlnaHQgZnJvbSB0aGUgaGVscGVyJ3MgYWRiZCBzZXNzaW9uIGRpZXMgd2l0aCB0aGF0IHNlc3Npb24sIHdoaWxlIHRoZSBndWFyZCBzdXJ2aXZlcyBpdCwgc28gdGhlCiAgICAjIGJyaWRnZSBpcyB0aGUgZ3VhcmQncyBvd24gY2hpbGQgaGVyZSBhbmQgaXMgc3RhcnRlZCBhZ2FpbiB3aGVuZXZlciBpdCBpcyBnb25lLiBUaGUgcmVtb3RlIGlzIGVudW1lcmF0ZWQKICAgICMgbGF0ZSwgc28gYSBtaXNzaW5nIG9uZSBpcyBzZWFyY2hlZCBhZ2FpbiBpbnN0ZWFkIG9mIGdpdmVuIHVwIG9uOyB0aGUgc2VhcmNoIG9ubHkgcnVucyB3aGlsZSB0aGUgYnJpZGdlIGlzCiAgICAjIG5vdCBydW5uaW5nLCBhbmQgaXMgdGhlbiB0aHJvdHRsZWQgc28gYSBxdWlldCBndWFyZCBzdGF5cyBxdWlldC4KICAgICMgVGhlIGxhdW5jaGVyIGRvd25sb2FkcyB0aGUgcmVsZWFzZSdzIG93biBwYXJ0cyBpbnRvIGl0cyBleHRlcm5hbCBmb2xkZXIuIE9ubHkgdGhlIHNoZWxsIGRvbWFpbiBjYW4gcHV0CiAgICAjIGZpbGVzIGludG8gL2RhdGEvbG9jYWwvdG1wIGFuZCBydW4gYXBwX3Byb2Nlc3MsIHNvIHRoZSBjb3BpZXMgYXJlIG1hZGUgaGVyZSwgZWFjaCBvbmUgY2hlY2tlZCBhZ2FpbnN0IHRoZQogICAgIyBjaGVja3N1bSB0aGUgbGF1bmNoZXIgd3JvdGUgYmVzaWRlIGl0LiBUaGUgZ3VhcmQgbmV2ZXIgb3ZlcndyaXRlcyB0aGUgc2NyaXB0IGl0IGlzIHJ1bm5pbmcgZnJvbTogdGhlIG5ldwogICAgIyBndWFyZCBpcyBwYXJrZWQgbmV4dCB0byBpdCwgYW5kIHRoZSBlbnN1cmUgc2NyaXB0IG1vdmVzIGl0IGludG8gcGxhY2UgYW5kIHJlc3RhcnRzIHRoZSBndWFyZCBieSB0aGUKICAgICMgcmVjb3JkZWQgaGFzaC4gQSBtaXNtYXRjaCBpcyByZXBvcnRlZCBvbmNlIHBlciBwYWlyIG9mIGNoZWNrc3Vtcywgbm90IG9uIGV2ZXJ5IHBhc3Mgb2YgdGhlIGxvb3AuCiAgICBzdGFnZV9kaXI9JHtUVkhPTUVfU1RBR0U6LS9zZGNhcmQvQW5kcm9pZC9kYXRhL3J1LnBkYS50dmhvbWUvZmlsZXMvYnJpZGdlfQogICAgbWlzbWF0Y2hfc3RhdGU9JHRtcF9kaXIvc3RhZ2UtbWlzbWF0Y2gKICAgIGZvciBzdGFnZWQgaW4gdHZob21lLXJlbW90ZS0yLmRleCB0dmhvbWUtYnJpZGdlLWVuc3VyZS5zaCBhc3Npc3RhbnQtcGFja2FnZS1ndWFyZC5zaDsgZG8KICAgICAgICBzb3VyY2U9JHN0YWdlX2Rpci8kc3RhZ2VkCiAgICAgICAgWyAtZiAiJHNvdXJjZSIgXSAmJiBbIC1mICIkc291cmNlLnNoYTI1NiIgXSB8fCBjb250aW51ZQogICAgICAgIHdhbnQ9JChjYXQgIiRzb3VyY2Uuc2hhMjU2IiAyPi9kZXYvbnVsbCkKICAgICAgICBnb3Q9JChzaGEyNTZzdW0gIiRzb3VyY2UiIDI+L2Rldi9udWxsIHwgYXdrICd7cHJpbnQgJDF9JykKICAgICAgICBpZiBbIC16ICIkd2FudCIgXSB8fCBbICIkd2FudCIgIT0gIiRnb3QiIF07IHRoZW4KICAgICAgICAgICAgaWYgWyAiJChjYXQgIiRtaXNtYXRjaF9zdGF0ZSIgMj4vZGV2L251bGwpIiAhPSAiJHN0YWdlZDokd2FudDokZ290IiBdOyB0aGVuCiAgICAgICAgICAgICAgICBlY2hvICJzdGFnZWQgJHN0YWdlZCBjaGVja3N1bSBtaXNtYXRjaCIgPj4gIiR0cmFjZV9maWxlIgogICAgICAgICAgICAgICAgZWNobyAiJHN0YWdlZDokd2FudDokZ290IiA+ICIkbWlzbWF0Y2hfc3RhdGUiCiAgICAgICAgICAgIGZpCiAgICAgICAgICAgIGNvbnRpbnVlCiAgICAgICAgZmkKICAgICAgICBpZiBbICIkc3RhZ2VkIiA9ICJ0dmhvbWUtcmVtb3RlLTIuZGV4IiBdOyB0aGVuCiAgICAgICAgICAgIGlmIFsgISAtZiAiJGRleF9maWxlIiBdIHx8IFsgIiQoc2hhMjU2c3VtICIkZGV4X2ZpbGUiIDI+L2Rldi9udWxsIHwgYXdrICd7cHJpbnQgJDF9JykiICE9ICIkZ290IiBdOyB0aGVuCiAgICAgICAgICAgICAgICBpZiBjcCAiJHNvdXJjZSIgIiRkZXhfZmlsZSIgMj4vZGV2L251bGw7IHRoZW4KICAgICAgICAgICAgICAgICAgICBlY2hvICJicmlkZ2UgZGV4IHJlcGxhY2VkIGZyb20gdGhlIHJlbGVhc2UiID4+ICIkdHJhY2VfZmlsZSIKICAgICAgICAgICAgICAgICAgICBmb3IgcGlkIGluICQocGlkb2YgIiRicmlkZ2VfbmFtZSIgMj4vZGV2L251bGwpOyBkbyBraWxsICIkcGlkIiAyPi9kZXYvbnVsbDsgZG9uZQogICAgICAgICAgICAgICAgZWxzZQogICAgICAgICAgICAgICAgICAgIGVjaG8gImJyaWRnZSBkZXggY29weSBmYWlsZWQiID4+ICIkdHJhY2VfZmlsZSIKICAgICAgICAgICAgICAgIGZpCiAgICAgICAgICAgIGZpCiAgICAgICAgZWxpZiBbICIkc3RhZ2VkIiA9ICJ0dmhvbWUtYnJpZGdlLWVuc3VyZS5zaCIgXTsgdGhlbgogICAgICAgICAgICB0YXJnZXQ9JHRtcF9kaXIvdHZob21lLWJyaWRnZS1lbnN1cmUuc2gKICAgICAgICAgICAgaWYgWyAhIC1mICIkdGFyZ2V0IiBdIHx8IFsgIiQoc2hhMjU2c3VtICIkdGFyZ2V0IiAyPi9kZXYvbnVsbCB8IGF3ayAne3ByaW50ICQxfScpIiAhPSAiJGdvdCIgXTsgdGhlbgogICAgICAgICAgICAgICAgaWYgY3AgIiRzb3VyY2UiICIkdGFyZ2V0IiAyPi9kZXYvbnVsbDsgdGhlbgogICAgICAgICAgICAgICAgICAgIGVjaG8gImVuc3VyZSBzY3JpcHQgcmVwbGFjZWQgZnJvbSB0aGUgcmVsZWFzZSIgPj4gIiR0cmFjZV9maWxlIgogICAgICAgICAgICAgICAgZmkKICAgICAgICAgICAgZmkKICAgICAgICBlbHNlCiAgICAgICAgICAgIHRhcmdldD0kdG1wX2Rpci9hc3Npc3RhbnQtcGFja2FnZS1ndWFyZC5uZXcuc2gKICAgICAgICAgICAgaWYgWyAhIC1mICIkdGFyZ2V0IiBdIHx8IFsgIiQoc2hhMjU2c3VtICIkdGFyZ2V0IiAyPi9kZXYvbnVsbCB8IGF3ayAne3ByaW50ICQxfScpIiAhPSAiJGdvdCIgXTsgdGhlbgogICAgICAgICAgICAgICAgaWYgY3AgIiRzb3VyY2UiICIkdGFyZ2V0IiAyPi9kZXYvbnVsbDsgdGhlbgogICAgICAgICAgICAgICAgICAgIGVjaG8gImd1YXJkIHN0YWdlZCBmb3IgdGhlIG5leHQgZW5zdXJlIHJ1biIgPj4gIiR0cmFjZV9maWxlIgogICAgICAgICAgICAgICAgZmkKICAgICAgICAgICAgZmkKICAgICAgICBmaQogICAgZG9uZQogICAgaWYgWyAtZiAiJGRleF9maWxlIiBdICYmICEgcGlkb2YgIiRicmlkZ2VfbmFtZSIgPi9kZXYvbnVsbCAyPiYxOyB0aGVuCiAgICAgICAgaWYgWyAiJHNlYXJjaF9wYXVzZSIgLWxlIDAgXTsgdGhlbgogICAgICAgICAgICBkZXZpY2U9JChnZXRldmVudCAtbHAgMj4vZGV2L251bGwgfCBhd2sgJwogICAgICAgICAgICAgIC9eYWRkIGRldmljZS8geyBwYXRoID0gJE5GIH0KICAgICAgICAgICAgICAvbmFtZTouKlRWQzBHV0JUIChDb25zdW1lciBDb250cm9sfEtleXBhZHxLZXlib2FyZCkvIHsKICAgICAgICAgICAgICAgIGlmIChzZWVuW3BhdGhdKysgPT0gMCkgZm91bmQgPSAoZm91bmQgPT0gIiIgPyBwYXRoIDogZm91bmQgIiwiIHBhdGgpCiAgICAgICAgICAgICAgfQogICAgICAgICAgICAgIEVORCB7IHByaW50IGZvdW5kIH0nKQogICAgICAgICAgICBzZWFyY2hfcGF1c2U9MTUKICAgICAgICAgICAgaWYgWyAtbiAiJGRldmljZSIgXTsgdGhlbgogICAgICAgICAgICAgICAgc2l6ZT0xNgogICAgICAgICAgICAgICAgY2FzZSAiJChnZXRwcm9wIHJvLnByb2R1Y3QuY3B1LmFiaSkiIGluCiAgICAgICAgICAgICAgICAgICAgKjY0Kikgc2l6ZT0yNCA7OwogICAgICAgICAgICAgICAgZXNhYwogICAgICAgICAgICAgICAgQ0xBU1NQQVRIPSIkZGV4X2ZpbGUiIHNldHNpZCBhcHBfcHJvY2VzcyAvc3lzdGVtL2JpbiAtLW5pY2UtbmFtZT0iJGJyaWRnZV9uYW1lIiBQcVJlbW90ZVByb29mICIkZGV2aWNlIiAiJHNpemUiIDAgPj4iJGJyaWRnZV9sb2ciIDI+JjEgPC9kZXYvbnVsbCAmCiAgICAgICAgICAgICAgICBlY2hvICJicmlkZ2Ugc3RhcnRlZCBvbiAkZGV2aWNlIHNpemUgJHNpemUiID4+ICIkdHJhY2VfZmlsZSIKICAgICAgICAgICAgZWxzZQogICAgICAgICAgICAgICAgZWNobyAibm8gcmVtb3RlIiA+PiAiJHRyYWNlX2ZpbGUiCiAgICAgICAgICAgIGZpCiAgICAgICAgZWxzZQogICAgICAgICAgICBzZWFyY2hfcGF1c2U9JCgoc2VhcmNoX3BhdXNlIC0gMSkpCiAgICAgICAgZmkKICAgIGZpCiAgICBzbGVlcCAxCmRvbmUK'
    $guardPath = Join-Path $work 'assistant-package-guard.sh'
    [IO.File]::WriteAllBytes($guardPath, [Convert]::FromBase64String($guardBase64))
    Write-Output (Invoke-Adb -Arguments @('-s', $Device, 'push', $guardPath, '/data/local/tmp/assistant-package-guard.sh'))
    # The ensure script is left on the TV as well: it is the single-instance point, and the manual command in
    # the README works after a reboot only when this file is on the device.
    $ensurePath = Join-Path $work 'tvhome-bridge-ensure.sh'
    [IO.File]::WriteAllBytes($ensurePath, [Convert]::FromBase64String($ensureBase64))
    Write-Output (Invoke-Adb -Arguments @('-s', $Device, 'push', $ensurePath, '/data/local/tmp/tvhome-bridge-ensure.sh'))
    # setsid, not nohup: on this firmware nohup is only a shell alias, and the alias is not expanded in the
    # non-interactive shell ADB runs, so the guard's pid file (its single-instance check) was never written
    # and the helper could not be traced. setsid is a real binary and detaches the helper from the ADB session.
    # The ensure script uses it for the guard, so the installer calls the ensure script instead of starting
    # the guard itself: the same path as the on-device helper and as the manual command in the README.
    Write-Output (Invoke-Adb -Arguments @('-s', $Device, 'shell', 'sh /data/local/tmp/tvhome-bridge-ensure.sh'))
    # setsid forks when it is already a group leader, so the pid in the file can belong to
    # the forked child and can lag one moment behind the backgrounded command. Verify the
    # cmdline of the pid in the file, retrying briefly; do not rely on ls exit code here.
    $guardPid = ''
    for ($attempt = 0; $attempt -lt 8; $attempt++) {
        $guardPid = ((Invoke-Adb -Arguments @('-s', $Device, 'shell', 'cat /data/local/tmp/assistant-package-guard.pid')) -join '')
        if ($guardPid -match '^[1-9][0-9]*$' -and (Invoke-Adb -Arguments @('-s', $Device, 'shell', "cat /proc/$guardPid/cmdline 2>/dev/null") -AllowFailure) -match 'assistant-package-guard\.sh') { break }
        Start-Sleep -Milliseconds 500
    }
    if ($guardPid -notmatch '^[1-9][0-9]*$') { throw 'Помощник не записал pid-файл: его нельзя проверить как единственный запущенный. Запустите guard вручную.' }
    if ((Invoke-Adb -Arguments @('-s', $Device, 'shell', "cat /proc/$guardPid/cmdline 2>/dev/null") -AllowFailure) -notmatch 'assistant-package-guard\.sh') { throw "Помощник не запущен (pid $guardPid)." }
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
        # A guard that has been running since before the deployed script was replaced keeps the old code in
        # memory: on the bedroom TV (2026-11-11) it answered "check" but ignored the newer "launcher" request.
        # The ensure script replaces a guard whose script changed; force that once and repeat the same handshake
        # before giving up.
        Write-Warning 'Помощник не ответил на запрос launcher. Перезапускаю помощника с текущим скриптом и повторяю проверку.'
        Write-Output (Invoke-Adb -Arguments @('-s', $Device, 'shell', 'env', 'TVHOME_FORCE_GUARD=1', 'sh', '/data/local/tmp/tvhome-bridge-ensure.sh'))
        $guardPid = ''
        for ($attempt = 0; $attempt -lt 12; $attempt++) {
            Start-Sleep -Milliseconds 500
            $guardPid = ((Invoke-Adb -Arguments @('-s', $Device, 'shell', 'cat /data/local/tmp/assistant-package-guard.pid')) -join '')
            if ($guardPid -match '^[1-9][0-9]*$' -and (Invoke-Adb -Arguments @('-s', $Device, 'shell', "cat /proc/$guardPid/cmdline 2>/dev/null") -AllowFailure) -match 'assistant-package-guard\.sh') { break }
        }
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
            throw 'Помощник TVHome не ответил на запрос launcher после перезапуска. Проверьте /data/local/tmp/assistant-package-guard.log на ТВ.'
        }
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
        $_.Groups['name'].Value -match '^TVC0GWBT (Consumer Control|Keypad|Keyboard)$' -and
        $_.Groups['body'].Value -match 'KEY_UNKNOWN' -and $_.Groups['body'].Value -match 'MSC_SCAN'
    })
    if ($candidates.Count -eq 0) {
        Write-Warning 'Совместимый пульт TVC0GWBT не найден. Мост шестерёнки не запущен; остальные настройки сохранены.'
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
    if (-not $dexHash) { $dexHash = Get-AddOnChecksum $dexName }
    if (-not $dexHash) {
        Write-Warning "Контрольная сумма $dexName недоступна: ни в выпуске, ни в сохранённых файлах её строки нет. Мост не запускается: файл без проверки контрольной суммы не устанавливается."
        return
    }
    $dexPath = Join-Path $cache $dexName
    try {
        $verified = Get-CachedDownload -Uri (Get-AssetUrl $dexName) -Path $dexPath -Sha256 $dexHash
    } catch {
        Write-Warning "DEX моста не прошёл контрольную сумму: $($_.Message). Мост не запускается, остальные настройки сохранены."
        return
    }
    if (-not $verified) {
        Write-Warning "DEX моста недоступен: GitHub не ответил, а файл в кэше отсутствует или не проходит контрольную сумму: $dexPath. Остальные настройки сохранены."
        return
    }
    # Every device of the remote is watched by one bridge process: the gear key arrives on whichever of its two
    # HID devices the firmware enumerated first, and that order changes after a reboot.
    $eventDevice = ($candidates | ForEach-Object { $_.Groups['path'].Value }) -join ','
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
    Write-Host 'Проверьте одно и долгое нажатие шестерёнки, Back из поиска и удержание громкости.'
Write-Host 'После перезагрузки ТВ один раз подтвердите разрешение ADB (диалог появляется через 30–40 секунд после Home) — мост запустится сам.'
}
try { Start-RemoteBridge } catch { Write-Warning "Мост шестерёнки не запущен: $($_.Message). Остальные настройки применены; мост можно запустить вручную по README." }
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
if ($homeManual) {
    Write-Warning 'Главный экран: TVHome не назначен автоматически (прошивка не отдаёт роль HOME). Назначьте его вручную: системные Настройки → Приложения → Приложения по умолчанию → Главный экран, либо кнопкой Home через Button Mapper. Остальные настройки уже применены.'
} elseif ($homeVerified) { Write-Host 'Назначение TVHome главным экраном подтверждено Android. Проверьте кнопку Home на пульте.' }
else { Write-Warning 'Назначение главного экрана требует проверки в системных настройках ТВ.' }
Write-Host 'Включите «Оверлейные часы» в настройках TVHome, если нужны часы поверх других приложений.'
Write-Host 'Перехват шестерёнки: настройки TVHome → «Система» → «Загрузка моста» → «При старте Home». После каждой перезагрузки подтвердите ADB один раз.'
if ($googleReady) {
    Write-Host 'Голосовой помощник TVHome выбран автоматически. При необходимости выберите источник «Пульт» или «ТВ» удержанием значка микрофона.'
    Write-Host 'Этот выбор может отключить системного Google-помощника Katniss. Возврат — выбор «Системный» в том же меню.'
    Write-Host 'Источник «ТВ» требует Android 13+ и встроенного микрофона. ADB-помощник работает до перезагрузки.'
    Write-Host 'Для повторного переключения помощника после перезагрузки можно снова запустить установщик.'
} else {
    Write-Warning 'Голосовой режим не настроен: отсутствуют компоненты Google. Home и разрешения настроены.'
}
Write-Host "Файлы, резервная копия APK и прежние настройки: $work"
Write-Host 'Проверьте Home и голос с пульта. Отладку USB/ADB оставьте включённой: она нужна помощнику для перехвата шестерёнки после перезагрузки.'
