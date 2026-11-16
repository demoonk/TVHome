# Publish the current TVHome release without making a new one.
#
# The TV reads releases/latest, so a fix goes into the SAME release: the tag stays,
# the assets are replaced with --clobber, and update.json + SHA256SUMS.txt are rewritten.
# Only the versionCode has to grow for the launcher to offer the new APK.
#
#   pwsh tools/publish-release.ps1 -Version 2.1.12 -VersionCode 156
#   pwsh tools/publish-release.ps1 -Version 2.1.12 -VersionCode 156 -DryRun
#
# -DryRun prints what would be uploaded and checks the manifest, without touching GitHub.

param(
    [string] $Version = '2.1.12',
    [int] $VersionCode = 156,
    [string] $Repository = 'demoonk/TVHome',
    [switch] $SkipBuild,
    [switch] $DryRun
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$releaseFolder = Join-Path $root "build-output\release-$Version"
$tag = "v$Version"

New-Item -ItemType Directory -Force -Path $releaseFolder | Out-Null

if (-not $SkipBuild) {
    Write-Host 'Building the launcher...'
    Push-Location (Join-Path $root 'launcher')
    try {
        $env:ANDROID_HOME = 'C:\Tools\tvhome-toolchain\sdk'
        .\gradlew.bat --no-daemon testReleaseUnitTest lintRelease assembleRelease --offline
    } finally {
        Pop-Location
    }
    Write-Output (Copy-Item -Force (Join-Path $root 'launcher\app\build\outputs\apk\release\app-release.apk') (Join-Path $releaseFolder "TVHome-$Version.apk"))
}

Write-Output (python (Join-Path $root 'tools\refresh-installer-blobs.py'))
foreach ($source in @('tools\assistant-package-guard.sh', 'tools\tvhome-bridge-ensure.sh', 'install.ps1', 'install.sh', 'Setup-TVHome.ps1')) {
    Write-Output (Copy-Item -Force (Join-Path $root $source) $releaseFolder)
}

Write-Output (python (Join-Path $root 'tools\make-release-manifest.py') $Version $VersionCode)

$manifest = Get-Content -LiteralPath (Join-Path $releaseFolder 'update.json') -Raw | ConvertFrom-Json
foreach ($component in $manifest.components) {
    $asset = Join-Path $releaseFolder $component.name
    if (-not (Test-Path -LiteralPath $asset)) { throw "Component $($component.name) is not in $releaseFolder" }
    $actual = (Get-FileHash -LiteralPath $asset -Algorithm SHA256).Hash.ToLower()
    if ($actual -ne $component.sha256) { throw "$($component.name): update.json says $($component.sha256), the file is $actual" }
}
$apkHash = (Get-FileHash -LiteralPath (Join-Path $releaseFolder $manifest.apkName) -Algorithm SHA256).Hash.ToLower()
if ($apkHash -ne $manifest.sha256) { throw "$($manifest.apkName): update.json checksum does not match the APK" }
Write-Host "Manifest is consistent: $tag, versionCode $($manifest.versionCode), apk $apkHash"

if ($DryRun) {
    Write-Host 'Dry run: nothing uploaded.'
    return
}

$assets = Get-ChildItem -LiteralPath $releaseFolder -File | ForEach-Object { $_.FullName }
if (gh release view $tag --json name 2>$null) {
    Write-Output (gh release upload $tag --clobber @assets)
} else {
    $notes = Join-Path $root "docs\releases\$Version.md"
    Write-Output (gh release create $tag --title "TVHome $Version" --notes-file $notes @assets)
}
Write-Host (gh release view $tag --json assets | python -c "import json,sys;[print(a['name'],a['size']) for a in json.load(sys.stdin)['assets']]")
