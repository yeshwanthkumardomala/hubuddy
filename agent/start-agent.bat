@echo off
cd /d "%~dp0"
if "%~1"=="" (
  powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0start-agent.ps1" -Window
) else (
  powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0start-agent.ps1" %*
)
echo.
pause
