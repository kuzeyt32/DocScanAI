@echo off
setlocal enableextensions enabledelayedexpansion
rem ============================================================================
rem SUBTASK H27 - release APK launch smoke (boot AVD, install RELEASE, launch,
rem screenshot, graceful teardown). Bounded waits only; kills nothing by name.
rem Same procedure as tooling\h26_smoke.cmd, evidence written to out\h27.
rem Usage: tooling\h27_smoke.cmd [apk]
rem ============================================================================
for %%I in ("%~dp0..") do set "REPO_ROOT=%%~fI"
set "OUT=%REPO_ROOT%\tooling\out\h27"
if not exist "%OUT%" mkdir "%OUT%"
set "ADB=C:\Users\Kuzey\AppData\Local\Android\Sdk\platform-tools\adb.exe"
set "EMULATOR=C:\Users\Kuzey\AppData\Local\Android\Sdk\emulator\emulator.exe"
set "APK=%~1"
if not defined APK set "APK=%REPO_ROOT%\app\build\outputs\apk\release\app-release.apk"
set "PKG=com.docscanai"
set "AVD=docscan_dbg_api35"

call :log "smoke start apk=%APK%"
if not exist "%APK%" (
    call :log "APK MISSING"
    endlocal & exit /b 3
)

"%ADB%" start-server >nul 2>&1
"%ADB%" devices | findstr "emulator-" >nul && (
    call :log "emulator already running"
) || (
    call :log "booting %AVD%"
    start "docscan_h27_smoke_emu" /MIN "%EMULATOR%" -avd %AVD% -no-window -no-audio -no-snapshot -no-boot-anim -gpu swiftshader_indirect
    "%ADB%" wait-for-device
)
set /a N=0
:boot
set /a N+=1
"%ADB%" shell getprop sys.boot_completed 2>nul | findstr "1" >nul && goto booted
if %N% GEQ 60 (
    call :log "BOOT TIMEOUT"
    endlocal & exit /b 3
)
ping -n 4 127.0.0.1 >nul
goto boot
:booted
call :log "boot_completed=1 after %N% polls"

rem The debug APK cannot be upgraded in place by the release APK (different
rem signing key), so remove it once. This is the app under test on this AVD,
rem not user data on a real device.
"%ADB%" uninstall %PKG% > "%OUT%\smoke_uninstall.txt" 2>&1
"%ADB%" install -r -t "%APK%" > "%OUT%\smoke_install.txt" 2>&1
set "IRC=%ERRORLEVEL%"
call :log "install rc=%IRC%"
if not "%IRC%"=="0" (
    call :log "INSTALL FAILED"
    "%ADB%" emu kill >nul 2>&1
    endlocal & exit /b 1
)

"%ADB%" shell monkey -p %PKG% -c android.intent.category.LAUNCHER 1 > "%OUT%\smoke_launch.txt" 2>&1
ping -n 9 127.0.0.1 >nul
"%ADB%" shell dumpsys activity activities > "%OUT%\smoke_activities.txt" 2>&1
findstr /i "mResumedActivity topResumedActivity" "%OUT%\smoke_activities.txt" > "%OUT%\smoke_resumed.txt"
"%ADB%" shell screencap -p /sdcard/h27_launch.png
"%ADB%" pull /sdcard/h27_launch.png "%OUT%\launch_smoke.png" >> "%OUT%\smoke_pull.txt" 2>&1
"%ADB%" shell rm /sdcard/h27_launch.png
"%ADB%" shell dumpsys package %PKG% | findstr /i "versionCode versionName" > "%OUT%\smoke_package.txt" 2>&1
"%ADB%" logcat -d -s AndroidRuntime:E > "%OUT%\smoke_crash.txt" 2>&1

"%ADB%" emu kill >nul 2>&1
set /a N=0
:waitgone
set /a N+=1
if %N% GEQ 30 goto done
"%ADB%" devices | findstr "emulator-" >nul || goto done
ping -n 3 127.0.0.1 >nul
goto waitgone
:done
"%ADB%" kill-server >nul 2>&1
call :log "smoke end"
type "%OUT%\smoke_resumed.txt"
type "%OUT%\smoke_package.txt"
endlocal & exit /b 0

:log
echo [%DATE% %TIME%] %~1
echo [%DATE% %TIME%] %~1 >> "%OUT%\HEARTBEAT.txt"
exit /b 0
