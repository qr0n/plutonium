@echo off
REM Double-clickable wrapper around launch-windows.ps1.
REM Passes through any args, e.g.:  launch-windows.bat -Compile
setlocal
set "HERE=%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%HERE%launch-windows.ps1" %*
REM Keep the window open on failure so the error can be read (also in jars\logs\console.log).
if errorlevel 1 (
    echo.
    echo Plutonium exited with an error. Send jars\logs\console.log to the developer.
    pause
)
endlocal
