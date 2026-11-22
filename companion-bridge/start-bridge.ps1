<#
.SYNOPSIS
    Starts the Hubuddy DeskHub bridge on Windows, using the DeskHub companion's Python environment.
.EXAMPLE
    .\start-bridge.ps1 -Companion C:\src\deskhub\companion            # USB mode (adb reverse)
    .\start-bridge.ps1 -Companion C:\src\deskhub\companion -Lan       # Wi-Fi mode
    .\start-bridge.ps1 -Companion C:\src\deskhub\companion -Pair      # print a new pairing code
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Companion,
    [int]$Port = 8765,
    [switch]$Lan,
    [switch]$Pair,
    [switch]$AlsoBoard,
    [switch]$NoAdb
)

$ErrorActionPreference = 'Stop'
$bridgeRoot = $PSScriptRoot
$Companion = (Resolve-Path -LiteralPath $Companion).Path
$config = Join-Path $Companion 'config.toml'

if (-not (Test-Path (Join-Path $Companion 'deskhub\__main__.py'))) {
    Write-Host "Not a DeskHub companion folder: $Companion" -ForegroundColor Red; exit 1
}
if (-not (Test-Path $config)) {
    Write-Host "config.toml not found in $Companion. Run DeskHub's setup first (uv run deskhub-setup)." -ForegroundColor Red; exit 1
}
if (-not (Get-Command uv -ErrorAction SilentlyContinue)) {
    Write-Host 'uv not found. Install it as described in the DeskHub README.' -ForegroundColor Red; exit 1
}

# USB mode: forward the phone's 127.0.0.1:$Port to this PC.
if (-not $Lan -and -not $NoAdb) {
    $adb = Get-Command adb -ErrorAction SilentlyContinue
    if ($adb) {
        & adb reverse "tcp:$Port" "tcp:$Port"
        if ($LASTEXITCODE -eq 0) { Write-Host "adb reverse tcp:$Port tcp:$Port  (phone -> PC) OK" }
        else { Write-Host 'adb reverse failed: is the phone connected with USB debugging allowed? Continuing.' -ForegroundColor Yellow }
    } else {
        Write-Host 'adb not on PATH; run  adb reverse tcp:8765 tcp:8765  yourself after connecting the phone.' -ForegroundColor Yellow
    }
}

$uvArgs = @('run', '--project', $Companion, 'python', '-m', 'hubuddy_bridge', '--config', $config, '--port', "$Port")
if ($Lan) { $uvArgs += '--lan' }
if ($Pair) { $uvArgs += '--pair' }
if ($AlsoBoard) { $uvArgs += '--also-board' }

# Make the bridge package importable next to DeskHub's own package.
$env:PYTHONPATH = if ($env:PYTHONPATH) { "$bridgeRoot;$env:PYTHONPATH" } else { $bridgeRoot }
Push-Location $Companion
try {
    & uv @uvArgs
    exit $LASTEXITCODE
} finally {
    Pop-Location
}
