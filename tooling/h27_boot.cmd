@echo off
setlocal enableextensions enabledelayedexpansion
rem ============================================================================
rem SUBTASK H27 - boot the AVD only (bounded). Pair with h27_stop.cmd.
rem Never kills anything by image name; teardown is `adb emu kill` + exact PIDs.
rem ============================================================================
set "ADB=C:\Users\Kuzey\AppData\Local\Android\Sdk\platform-tools\adb.exe"
set "EMULATOR=C:\Users\Kuzey\AppData\Local\Android\Sdk\emulator\emulator.exe"
set "AVD=docscan_dbg_api35"
set "OUT=%~dp0out\h27"
if not exist "%OUT%" mkdir "%OUT%"

"%ADB%" start-server >nul 2>&1
"%ADB%" devices | findstr "emulator-" >nul && (
    echo [%DATE% %TIME%] emulator already running >> "%OUT%\HEARTBEAT.txt"
    echo ALREADY_RUNNING
    endlocal & exit /b 0
)
echo [%DATE% %TIME%] booting %AVD% >> "%OUT%\HEARTBEAT.txt"
start "docscan_h27_emulator" /MIN "%EMULATOR%" -avd %AVD% -no-window -no-audio -no-snapshot -no-boot-anim -gpu swiftshader_indirect
set /a N=0
:wait
set /a N+=1
"%ADB%" shell getprop sys.boot_completed 2>nul | findstr "1" >nul && goto ready
if %N% GEQ 120 (
    echo [%DATE% %TIME%] BOOT TIMEOUT >> "%OUT%\HEARTBEAT.txt"
    echo BOOT_TIMEOUT
    endlocal & exit /b 3
)
ping -n 3 127.0.0.1 >nul
goto wait
:ready
echo [%DATE% %TIME%] boot_completed=1 after %N% polls >> "%OUT%\HEARTBEAT.txt"
echo BOOTED
endlocal & exit /b 0
