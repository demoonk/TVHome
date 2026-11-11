param(
    [Parameter(Mandatory=$true)][string]$Device,
    [string]$Adb = 'adb'
)
$ErrorActionPreference = 'Stop'
$adbPath = (Get-Command $Adb -ErrorAction Stop).Source
function Invoke-Target([string]$Command) {
    $result = (& $adbPath -s $Device shell $Command 2>&1 | Out-String).Trim()
    if ($LASTEXITCODE -ne 0) { throw "ADB failed: $result" }
    return $result
}
if ((& $adbPath -s $Device get-state) -ne 'device') { throw 'Device must already be connected and authorized' }
function Ensure-File([string]$Local, [string]$Remote) {
    if (-not (Invoke-Target "test -f $Remote")) {
        if (-not (Test-Path -LiteralPath $Local -PathType Leaf)) { throw "Missing on the TV and locally: $Remote" }
        & $adbPath -s $Device push $Local $Remote | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "Upload failed: $Remote" }
        # Files are pushed with the CRLF endings of this host; the guard script runs under ash.
        Invoke-Target "tr -d '\r' < $Remote > $Remote.clean; mv $Remote.clean $Remote" | Out-Null
    }
}
Ensure-File (Join-Path $PSScriptRoot 'tvhome-bridge-ensure.sh') '/data/local/tmp/tvhome-bridge-ensure.sh'
Ensure-File (Join-Path $PSScriptRoot 'assistant-package-guard.sh') '/data/local/tmp/assistant-package-guard.sh'
Ensure-File (Join-Path $PSScriptRoot 'tvhome-remote-2.dex') '/data/local/tmp/tvhome-remote-2.dex'
$report = Invoke-Target 'sh /data/local/tmp/tvhome-bridge-ensure.sh'
Write-Output ($report | Out-String).Trim()
# The ensure script only starts what was missing, so readiness is read from the bridge's own log.
$ready = Invoke-Target 'tail -1 /data/local/tmp/tvhome-remote-2.log'
if ($ready -match 'PQ_REMOTE_READY') { Write-Output $ready } else { Write-Warning 'The bridge has not reported readiness yet' }
