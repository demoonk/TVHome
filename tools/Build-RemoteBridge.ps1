#requires -Version 5.1
# Reproducible DEX build for the gear bridge. Only the standard JDK and Android
# build-tools d8 are used; nothing is downloaded.
param(
    [string]$Jdk = 'D:\neurolib\tmp\fix\cytv\jdk-17.0.20.1+1',
    [string]$Sdk = 'D:\neurolib\tmp\fix\cytv\sdk',
    [string]$BuildTools = '34.0.0',
    [string]$OutDir
)
$ErrorActionPreference = 'Stop'
$sourceDir = $PSScriptRoot
if (-not $OutDir) { $OutDir = Join-Path $PSScriptRoot 'bridge-out' }
$classDir = Join-Path $OutDir 'classes'
$dexDir = Join-Path $OutDir 'dex'
$tempDir = Join-Path $OutDir 'java-tmp'
New-Item -ItemType Directory -Path $classDir,$dexDir,$tempDir -Force | Out-Null

$sources = @(Get-ChildItem -LiteralPath $sourceDir -Filter '*.java' -File | Select-Object -ExpandProperty FullName)
if ($sources.Count -lt 1) { throw 'No bridge sources found' }

& (Join-Path $Jdk 'bin\javac.exe') --release 8 -encoding UTF-8 -d $classDir $sources
if ($LASTEXITCODE -ne 0) { throw 'javac failed' }

$d8Jar = Join-Path $Sdk ("build-tools\$BuildTools\lib\d8.jar")
$androidJar = Join-Path $Sdk 'platforms\android-34\android.jar'
if (-not (Test-Path -LiteralPath $d8Jar -PathType Leaf)) {
    throw "d8 is not available: $d8Jar. Run this build on the machine that has the Android build-tools."
}
$classes = @(Get-ChildItem -LiteralPath $classDir -Filter '*.class' | Select-Object -ExpandProperty FullName)
& (Join-Path $Jdk 'bin\java.exe') "-Djava.io.tmpdir=$tempDir" -cp $d8Jar com.android.tools.r8.D8 --min-api 26 --lib $androidJar --output $dexDir $classes
if ($LASTEXITCODE -ne 0) { throw 'd8 failed' }

$dex = Join-Path $dexDir 'classes.dex'
if (-not (Test-Path -LiteralPath $dex -PathType Leaf)) { throw 'd8 produced no classes.dex' }
$hash = (Get-FileHash -LiteralPath $dex -Algorithm SHA256).Hash.ToLower()
Write-Host "Built $dex"
Write-Host "SHA256: $hash"
