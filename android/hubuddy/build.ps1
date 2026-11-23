<#
.SYNOPSIS
    Builds Hubuddy (SIDYA Edition): runs unit tests and produces the debug APK.
.DESCRIPTION
    Usage (from this folder):   .\build.ps1
    Options:  -SkipTests   build without running unit tests
              -Release     also build the small, R8-optimised app-release.apk
              -Lint        also run Android Lint
    Exit code is non-zero when any step fails.
#>
[CmdletBinding()]
param(
    [switch]$SkipTests,
    [switch]$Release,
    [switch]$Lint
)

$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot

function Fail([string]$msg) {
    Write-Host "BUILD FAILED: $msg" -ForegroundColor Red
    exit 1
}

Write-Host '== Hubuddy build ==' -ForegroundColor Cyan

# 1. Java (Android Gradle Plugin 8.7 needs JDK 17 or newer)
$java = $null
if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
    $java = Join-Path $env:JAVA_HOME 'bin\java.exe'
} else {
    $cmd = Get-Command java -ErrorAction SilentlyContinue
    if ($cmd) { $java = $cmd.Source }
}
if (-not $java) {
    # Android Studio ships a JDK; use it when nothing else is configured.
    foreach ($jbr in @("$env:ProgramFiles\Android\Android Studio\jbr", "$env:LOCALAPPDATA\Programs\Android Studio\jbr")) {
        if (Test-Path (Join-Path $jbr 'bin\java.exe')) { $env:JAVA_HOME = $jbr; $java = Join-Path $jbr 'bin\java.exe'; break }
    }
}
if (-not $java) { Fail 'Java not found. Install JDK 17+ or Android Studio, or set JAVA_HOME.' }
# java -version writes to stderr; with 'Stop' Windows PowerShell 5.1 would treat that as an error.
$ErrorActionPreference = 'Continue'
$verLine = (& $java -version 2>&1 | Select-Object -First 1 | ForEach-Object { "$_" })
$ErrorActionPreference = 'Stop'
Write-Host "Java: $verLine"
if ($verLine -match 'version "(\d+)') {
    if ([int]$Matches[1] -lt 17) { Fail "JDK 17 or newer is required (found $($Matches[1]))." }
}

# 2. Android SDK
if (-not (Test-Path 'local.properties')) {
    $sdk = $env:ANDROID_HOME
    if (-not $sdk) { $sdk = $env:ANDROID_SDK_ROOT }
    if (-not $sdk) { $sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
    if (-not (Test-Path $sdk)) { Fail 'Android SDK not found. Install Android Studio (SDK Platform 34) or set ANDROID_HOME.' }
    # Gradle expects forward slashes or escaped backslashes in local.properties.
    "sdk.dir=$($sdk -replace '\\', '/')" | Set-Content -Encoding ASCII 'local.properties'
    Write-Host "Android SDK: $sdk (written to local.properties)"
}

# 3. Gradle wrapper
foreach ($f in @('gradlew.bat', 'gradle\wrapper\gradle-wrapper.jar', 'gradle\wrapper\gradle-wrapper.properties')) {
    if (-not (Test-Path $f)) { Fail "Gradle wrapper file missing: $f" }
}
Write-Host 'Gradle wrapper: OK'

# 4. Build
$tasks = @('assembleDebug')
if (-not $SkipTests) { $tasks = @('testDebugUnitTest') + $tasks }
if ($Lint) { $tasks += 'lintDebug' }
if ($Release) { $tasks += 'assembleRelease' }
Write-Host "Running: gradlew $($tasks -join ' ')"
& .\gradlew.bat @tasks --console=plain
if ($LASTEXITCODE -ne 0) {
    if (-not $SkipTests) { Write-Host 'Test report: app\build\reports\tests\testDebugUnitTest\index.html' }
    Fail "Gradle exited with code $LASTEXITCODE"
}

# 5. Verify output
$apk = Join-Path $PSScriptRoot 'app\build\outputs\apk\debug\app-debug.apk'
if (-not (Test-Path $apk)) { Fail "APK not found at $apk" }
$item = Get-Item $apk
Write-Host ''
Write-Host 'BUILD SUCCESSFUL' -ForegroundColor Green
if (-not $SkipTests) { Write-Host 'Unit tests: passed' }
Write-Host ("Debug APK : {0}" -f $item.FullName)
Write-Host ("Size      : {0:N0} bytes ({1:N2} MB)" -f $item.Length, ($item.Length / 1MB))
if ($Release) {
    $rel = Join-Path $PSScriptRoot 'app\build\outputs\apk\release\app-release.apk'
    if (Test-Path $rel) { Write-Host ("Release APK: {0} ({1:N0} bytes)" -f (Get-Item $rel).FullName, (Get-Item $rel).Length) }
}
Write-Host ''
Write-Host 'Install on the phone: copy app\build\outputs\apk\debug\app-debug.apk to it and open it (allow "install unknown apps")'
exit 0
