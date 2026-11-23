<#
.SYNOPSIS
    Starts Hubuddy on Windows from source (most people should use Hubuddy.exe instead).
    The first run creates a private Python environment (.venv) and installs the requirements.
.EXAMPLE
    .\start-agent.ps1                 # starts the PC side; open http://127.0.0.1:8766/ on this PC to pair a phone
    .\start-agent.ps1 -Window         # the same, in the Hubuddy window (needs Python with Tk, included by default)
    .\start-agent.ps1 -Invite         # also print a pairing QR code in this window
    .\start-agent.ps1 -Pin            # also print a PIN for pairing without a camera
#>
[CmdletBinding()]
param([switch]$Window, [switch]$Invite, [switch]$Pin)

$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot

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

if ($Window) { & $venvPython -m hubuddy_agent.launcher; exit $LASTEXITCODE }
$agentArgs = @('-m', 'hubuddy_agent')
if ($Invite) { $agentArgs += '--show-invite' }
if ($Pin) { $agentArgs += '--pin' }
& $venvPython @agentArgs
exit $LASTEXITCODE
