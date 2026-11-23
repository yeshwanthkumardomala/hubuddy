<#
.SYNOPSIS
    Starts the Hubuddy agent on Windows. The first run creates a private Python environment (.venv) and
    installs the requirements.
.EXAMPLE
    .\start-agent.ps1            # USB mode: runs `adb reverse` for you, listens on 127.0.0.1 only
    .\start-agent.ps1 -Lan       # Wi-Fi mode: reachable from your local network (private addresses only)
    .\start-agent.ps1 -Pair      # print a new pairing code
#>
[CmdletBinding()]
param(
    [int]$Port = 8765,
    [switch]$Lan,
    [switch]$Pair,
    [switch]$NoAdb
)

$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot

# Python 3.11 or newer
$hasPy = [bool](Get-Command py -ErrorAction SilentlyContinue)
$hasPython = [bool](Get-Command python -ErrorAction SilentlyContinue)
if (-not ($hasPy -or $hasPython)) { Write-Host 'Python 3.11+ not found. Install it from python.org and tick "Add to PATH".' -ForegroundColor Red; exit 1 }

$venvPython = Join-Path $PSScriptRoot '.venv\Scripts\python.exe'
if (-not (Test-Path $venvPython)) {
    Write-Host 'Creating the private Python environment (first run only)...'
    if ($hasPy) { & py -3 -m venv .venv } else { & python -m venv .venv }
    if ($LASTEXITCODE -ne 0) { Write-Host 'Could not create .venv' -ForegroundColor Red; exit 1 }
    & $venvPython -m pip install --quiet -r requirements.txt
    if ($LASTEXITCODE -ne 0) { Write-Host 'Installing requirements failed (check your internet connection).' -ForegroundColor Red; exit 1 }
}
$ver = & $venvPython -c "import sys; print('%d.%d' % sys.version_info[:2])"
if ([version]$ver -lt [version]'3.11') { Write-Host "Python 3.11+ is required (found $ver). Delete .venv and install a newer Python." -ForegroundColor Red; exit 1 }

# USB mode: forward the phone's 127.0.0.1:$Port to this PC.
if (-not $Lan -and -not $NoAdb) {
    if (Get-Command adb -ErrorAction SilentlyContinue) {
        & adb reverse "tcp:$Port" "tcp:$Port"
        if ($LASTEXITCODE -eq 0) { Write-Host "adb reverse tcp:$Port tcp:$Port  (phone -> PC) OK" }
        else { Write-Host 'adb reverse failed: is the phone connected with USB debugging allowed? Continuing.' -ForegroundColor Yellow }
    } else {
        Write-Host "adb not on PATH; run  adb reverse tcp:$Port tcp:$Port  yourself after connecting the phone." -ForegroundColor Yellow
    }
}

$agentArgs = @('-m', 'hubuddy_agent', '--port', "$Port")
if ($Lan) { $agentArgs += '--lan' }
if ($Pair) { $agentArgs += '--pair' }
& $venvPython @agentArgs
exit $LASTEXITCODE
