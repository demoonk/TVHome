param(
    [string]$Device = '',
    [string]$Apk = '',
    [string]$Adb = 'adb'
)
$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($Device) -or [string]::IsNullOrWhiteSpace($Apk)) {
    throw 'Usage: ./install.ps1 -Device TV_ADDRESS:PORT -Apk ./TVHome-VERSION.apk [-Adb ./adb.exe]'
}
if (-not (Test-Path -LiteralPath $Apk -PathType Leaf) -or [IO.Path]::GetExtension($Apk) -ne '.apk') {
    throw 'APK file not found or extension is not .apk'
}
$apkPath = (Resolve-Path -LiteralPath $Apk).Path
$adbPath = (Get-Command $Adb -ErrorAction Stop).Source
function Invoke-Adb {
    param([string[]]$Arguments, [switch]$AllowFailure)
    $previousPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $result = (& $adbPath @Arguments 2>&1 | Out-String).Trim()
        $code = $LASTEXITCODE
    } finally { $ErrorActionPreference = $previousPreference }
    if ($code -ne 0 -and -not $AllowFailure) { throw "ADB failed ($code): $result" }
    return $result
}
if ($Device.Contains(':')) { Write-Output (Invoke-Adb -Arguments @('connect', $Device)) }
if ((Invoke-Adb -Arguments @('-s', $Device, 'get-state')) -ne 'device') { throw 'TV is not authorized or connected' }
$sdkText = Invoke-Adb -Arguments @('-s', $Device, 'shell', 'getprop', 'ro.build.version.sdk')
$sdk = 0
if (-not [int]::TryParse($sdkText, [ref]$sdk) -or $sdk -lt 26) { throw 'Android API 26 or newer is required' }
$package = 'ru.pda.tvhome'
$old = Invoke-Adb -Arguments @('-s', $Device, 'shell', 'pm', 'path', $package) -AllowFailure
$paths = @($old -split "`r?`n" | Where-Object { $_.StartsWith('package:') })
if ($paths.Count -gt 0) {
    $backup = Join-Path $PSScriptRoot ('backups/' + [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss') + '-' + [guid]::NewGuid().ToString('N').Substring(0,8))
    New-Item -ItemType Directory -Path $backup -Force | Out-Null
    for ($index = 0; $index -lt $paths.Count; $index++) {
        $remote = $paths[$index].Substring(8).Trim()
        Write-Output (Invoke-Adb -Arguments @('-s', $Device, 'pull', $remote, (Join-Path $backup "installed-$index.apk")))
    }
    Write-Output "Previous APK backup: $backup"
}
$installed = Invoke-Adb -Arguments @('-s', $Device, 'install', '-r', $apkPath)
Write-Output $installed
if ($installed -notmatch '(?m)^Success\s*$') { throw 'Installation did not report Success; launch aborted' }
# Reapply after every installation: Android may reset app-op permissions.
foreach ($operation in @('SYSTEM_ALERT_WINDOW', 'GET_USAGE_STATS')) {
    Write-Output (Invoke-Adb -Arguments @('-s', $Device, 'shell', 'appops', 'set', $package, $operation, 'allow'))
    $permission = Invoke-Adb -Arguments @('-s', $Device, 'shell', 'appops', 'get', $package, $operation)
    if ($permission -notmatch ($operation + ': allow(?:;|\s|$)')) {
        throw "APK installed, but $operation was not granted: $permission. Clock setup incomplete."
    }
}
$started = Invoke-Adb -Arguments @('-s', $Device, 'shell', 'am', 'start', '-W', '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.HOME', '-n', 'ru.pda.tvhome/ru.pda.tvhome.MainActivity')
Write-Output $started
if ($started -match '(?im)^Error:|Exception') { throw 'APK installed but TVHome could not start' }
Write-Output 'TVHome installed. Select the default Home in system Settings if needed. App data was preserved by install -r.'
