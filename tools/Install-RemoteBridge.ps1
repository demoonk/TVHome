param(
    [Parameter(Mandatory=$true)][string]$Device,
    [Parameter(Mandatory=$true)][string]$Dex,
    [string]$Adb = 'adb'
)
$ErrorActionPreference = 'Stop'
$adbPath = (Get-Command $Adb -ErrorAction Stop).Source
if (-not (Test-Path -LiteralPath $Dex -PathType Leaf)) { throw 'Remote bridge DEX not found' }
function Invoke-Target([string]$Command) {
    $result = (& $adbPath -s $Device shell $Command 2>&1 | Out-String).Trim()
    if ($LASTEXITCODE -ne 0) { throw "ADB failed: $result" }
    return $result
}
if ((& $adbPath -s $Device get-state) -ne 'device') { throw 'Device must already be connected and authorized' }
$inventory = Invoke-Target 'getevent -lp'
$blocks = [regex]::Matches($inventory, '(?ms)add device \d+: (?<path>/dev/input/event\d+)\s*\n\s*name:\s*"(?<name>[^"]+)"(?<body>.*?)(?=add device |\z)')
$candidates = @($blocks | Where-Object {
    $_.Groups['name'].Value -eq 'TVC0GWBT Consumer Control' -and
    $_.Groups['body'].Value -match 'KEY_UNKNOWN' -and $_.Groups['body'].Value -match 'MSC_SCAN'
})
if ($candidates.Count -ne 1) { throw 'No unique compatible TVC0GWBT remote; no helper was started' }
$eventDevice = $candidates[0].Groups['path'].Value
$eventSize = if ((Invoke-Target 'getprop ro.product.cpu.abi') -match '64') { 24 } else { 16 }
$inputState = Invoke-Target 'dumpsys input'
if ($inputState -match 'InputFilterEnabled:\s*true') { throw 'An input filter is already active; check volume-key repeat first' }
Invoke-Target 'pm grant ru.pda.tvhome android.permission.WRITE_SECURE_SETTINGS' | Out-Null
& $adbPath -s $Device push $Dex /data/local/tmp/tvhome-remote-2.dex
if ($LASTEXITCODE -ne 0) { throw 'Bridge upload failed' }
# Stop only the named bridge, after checking the process identity.
$bridgePids = (& $adbPath -s $Device shell pidof pqtv-bridge 2>$null | Out-String).Trim()
foreach ($bridgePid in ($bridgePids -split '\s+')) {
    if ($bridgePid -notmatch '^\d+$') { continue }
    $commandLine = Invoke-Target "cat /proc/$bridgePid/cmdline"
    if ($commandLine -notmatch 'pqtv-bridge') { throw 'Bridge PID identity changed; stopped' }
    Invoke-Target "kill $bridgePid" | Out-Null
}
Invoke-Target "CLASSPATH=/data/local/tmp/tvhome-remote-2.dex nohup app_process /system/bin --nice-name=pqtv-bridge PqRemoteProof $eventDevice $eventSize 0 >/data/local/tmp/tvhome-remote-2.log 2>&1 </dev/null &" | Out-Null
$ready = $false
for ($attempt = 0; $attempt -lt 10; $attempt++) {
    Start-Sleep -Milliseconds 200
    if ((Invoke-Target 'cat /data/local/tmp/tvhome-remote-2.log') -match 'PQ_REMOTE_READY') { $ready = $true; break }
}
if (-not $ready) { throw 'Bridge did not report readiness' }
$guard = Join-Path $PSScriptRoot 'assistant-package-guard.sh'
if (Test-Path -LiteralPath $guard -PathType Leaf) {
    $guardPid = (& $adbPath -s $Device shell cat /data/local/tmp/assistant-package-guard.pid 2>$null | Out-String).Trim()
    if ($guardPid -match '^\d+$') {
        $guardCommand = (& $adbPath -s $Device shell cat "/proc/$guardPid/cmdline" 2>$null | Out-String)
        if ($guardCommand.Replace([char]0, [char]32) -match 'sh /data/local/tmp/assistant-package-guard.sh') { Invoke-Target "kill $guardPid" | Out-Null }
    }
    & $adbPath -s $Device push $guard /data/local/tmp/assistant-package-guard.sh
    if ($LASTEXITCODE -ne 0) { throw 'Assistant guard upload failed' }
    Invoke-Target 'nohup sh /data/local/tmp/assistant-package-guard.sh >/data/local/tmp/assistant-package-guard.log 2>&1 </dev/null &' | Out-Null
}
Write-Output 'Remote bridge started for this boot. Test the physical gear, Back from search, and held volume keys.'
