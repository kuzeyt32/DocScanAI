@echo off
setlocal enableextensions enabledelayedexpansion
rem ============================================================================
rem SUBTASK H31 - final pin verification. Self-terminating: bounded polls only,
rem no unbounded waits, ends with `exit /b`. Boots the AVD, runs the quality +
rem cost lane against the TIGHTENED pins, then stops the emulator and exits.
rem Usage:  call tooling\h31_verify.cmd
rem ============================================================================
for %%I in ("%~dp0..") do set "REPO_ROOT=%%~fI"
set "OUT=%REPO_ROOT%\tooling\out\h31"
set "ADB=C:\Users\Kuzey\AppData\Local\Android\Sdk\platform-tools\adb.exe"
set "EMULATOR=C:\Users\Kuzey\AppData\Local\Android\Sdk\emulator\emulator.exe"
set "VB=%OUT%\HEARTBEAT.txt"

>> "%VB%" echo [%DATE% %TIME%] h31_verify: booting
start "docscan_h31_verify" /MIN "%EMULATOR%" -avd docscan_dbg_api35 -no-window -no-audio -no-snapshot -no-boot-anim -gpu swiftshader_indirect

set /a N=0
:wait
set /a N+=1
"%ADB%" shell getprop sys.boot_completed 2>nul | findstr "1" >nul && goto ready
if %N% GEQ 120 (
    >> "%VB%" echo [%DATE% %TIME%] h31_verify: BOOT TIMEOUT
    echo VERIFY_BOOT_TIMEOUT
    exit /b 3
)
ping -n 3 127.0.0.1 >nul
goto wait
:ready
>> "%VB%" echo [%DATE% %TIME%] h31_verify: boot_completed after %N% polls

call "%REPO_ROOT%\tooling\h31_lane.cmd" H31V BookSpreadDetectionTest MlsdCostBudgetTest
set "LRC=%ERRORLEVEL%"
>> "%VB%" echo [%DATE% %TIME%] h31_verify: lane rc=%LRC%

rem stop the AVD cleanly, then reap only the exact PIDs we started
"%ADB%" emu kill >nul 2>&1
ping -n 4 127.0.0.1 >nul
for /f "tokens=1" %%p in ('tasklist /fi "imagename eq emulator.exe" /nh ^| findstr /r "[0-9]"') do taskkill /PID %%p /T /F >nul 2>&1
for /f "tokens=1" %%p in ('tasklist /fi "imagename eq qemu-system-x86_64.exe" /nh ^| findstr /r "[0-9]"') do taskkill /PID %%p /T /F >nul 2>&1

echo VERIFY_DONE rc=%LRC%
endlocal & exit /b %LRC%
