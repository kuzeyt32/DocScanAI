@echo off
setlocal enableextensions enabledelayedexpansion
rem ============================================================================
rem SUBTASK H38 - ship lane (0.3.13 / versionCode 16).
rem
rem Contract:
rem   build (debug + signed release), aapt2 dump badging, apksigner verify
rem   --print-certs with CERT-CONTINUITY assertion (7a6977076f2c16e1c521b9301339ec9196aaac6f6a7641d7ec3fc965854253dc),
rem   byte size + SHA-256, install + launch smoke with ONE screenshot.
rem
rem Writes tooling\out\h38\SHIP.txt and exits. Usage: call tooling\h38_ship.cmd
rem ============================================================================

for %%I in ("%~dp0..") do set "REPO_ROOT=%%~fI"
set "OUT=%REPO_ROOT%\tooling\out\h38"
if not exist "%OUT%" mkdir "%OUT%"
set "SHIP=%OUT%\SHIP.txt"
set "HB=%OUT%\HEARTBEAT.txt"
set "ADB=C:\Users\Kuzey\AppData\Local\Android\Sdk\platform-tools\adb.exe"
for %%D in (36.0.0 35.0.0 34.0.0) do if exist "C:\Users\Kuzey\AppData\Local\Android\Sdk\build-tools\%%D" set "BUILD_TOOLS=C:\Users\Kuzey\AppData\Local\Android\Sdk\build-tools\%%D"
set "JAVA_HOME=C:\Program Files\Java\jdk-21"
set "PATH=%JAVA_HOME%\bin;%PATH%"
set "EXPECTED_CERT=7a6977076f2c16e1c521b9301339ec9196aaac6f6a7641d7ec3fc965854253dc"

> "%SHIP%" echo SUBTASK H38 - SHIP ARTIFACTS (0.3.13 / versionCode 16)
>> "%SHIP%" echo ============================================================================

cd /d "%REPO_ROOT%"
>> "%HB%" echo [%DATE% %TIME%] [H38SHIP] start

rem --- 1) signed release build + debug rebuild --------------------------------------------
>> "%SHIP%" echo [1/6] assembleRelease + assembleDebug ...
call gradlew.bat :app:assembleRelease :app:assembleDebug --console=plain > "%OUT%\build_release.log" 2>&1
>> "%SHIP%" echo       build rc=%ERRORLEVEL%  (see build_release.log)

set "REL=%REPO_ROOT%\app\build\outputs\apk\release\app-release.apk"
set "DBG=%REPO_ROOT%\app\build\outputs\apk\debug\app-debug.apk"
if not exist "%REL%" (
    >> "%SHIP%" echo       FATAL: %REL% missing
    echo DONE rc=1
    exit /b 1
)

rem --- 2) aapt2 dump badging --------------------------------------------------------------
>> "%SHIP%" echo [2/6] aapt2 dump badging ...
"%BUILD_TOOLS%\aapt2.exe" dump badging "%REL%" > "%OUT%\badging.txt" 2>&1
findstr /c:"package:" /c:"application-label:" /c:"sdkVersion:" /c:"targetSdkVersion:" /c:"native-code:" "%OUT%\badging.txt" >> "%SHIP%"
findstr /c:"versionCode='16'" /c:"versionName='0.3.13'" "%OUT%\badging.txt" >> "%SHIP%"

rem --- 3) apksigner verify --print-certs + CERT CONTINUITY --------------------------------
>> "%SHIP%" echo [3/6] apksigner verify --print-certs ...
rem NOTE: apksigner is a BATCH file - without `call` the outer script hands over control.
call "%BUILD_TOOLS%\apksigner.bat" verify --print-certs "%REL%" > "%OUT%\certs_release.txt" 2>&1
>> "%SHIP%" echo       apksigner rc=%ERRORLEVEL%
type "%OUT%\certs_release.txt" >> "%SHIP%"
rem Deterministic cert-continuity check (reuses tooling\h32_cert_check.py; no inline
rem `python -c` - batch %-expansion inside -c strings is unreliable here).
> "%OUT%\expected_cert.txt" echo %EXPECTED_CERT%
rem Branch IMMEDIATELY after python with no intervening command (ERRORLEVEL clobber).
python tooling\h32_cert_check.py "%OUT%\certs_release.txt" "%OUT%\expected_cert.txt" > "%OUT%\cert_check.txt" 2>&1
if errorlevel 1 goto cert_fail
type "%OUT%\cert_check.txt" >> "%SHIP%"
>> "%SHIP%" echo       CERT CONTINUITY OK  %EXPECTED_CERT%
goto cert_ok
:cert_fail
type "%OUT%\cert_check.txt" >> "%SHIP%"
>> "%SHIP%" echo       CERT MISMATCH - expected %EXPECTED_CERT% (owner pin at 0.3.9)
>> "%HB%" echo [%DATE% %TIME%] [H38SHIP] CERT MISMATCH
echo DONE rc=2 cert_mismatch
endlocal & exit /b 2
:cert_ok

rem --- 4) byte size + SHA-256 -------------------------------------------------------------
>> "%SHIP%" echo [4/6] size + SHA-256 ...
for %%F in ("%REL%") do set "RELSIZE=%%~zF"
for %%F in ("%DBG%") do set "DBGSIZE=%%~zF"
for /f "tokens=* delims=" %%H in ('certutil -hashfile "%REL%" SHA256 ^| findstr /r /c:"^[0-9a-fA-F][0-9a-fA-F][0-9a-fA-F][0-9a-fA-F]"') do set "RELSHA=%%H"
for /f "tokens=* delims=" %%H in ('certutil -hashfile "%DBG%" SHA256 ^| findstr /r /c:"^[0-9a-fA-F][0-9a-fA-F][0-9a-fA-F][0-9a-fA-F]"') do set "DBGSHA=%%H"
>> "%SHIP%" echo       release %RELSIZE% B  SHA-256 %RELSHA%
>> "%SHIP%" echo       debug   %DBGSIZE% B  SHA-256 %DBGSHA%

rem --- 5) install + launch + screencap smoke (bounded; skipped when no device) -------------
>> "%SHIP%" echo [5/6] install + launch smoke ...
"%ADB%" devices 2>nul | findstr /c:"device" | findstr /v /c:"devices" >nul
if errorlevel 1 (
    >> "%SHIP%" echo       no emulator attached - smoke SKIPPED
    goto done
)
rem The device may carry the DEBUG build (different signature); uninstall first.
"%ADB%" uninstall com.docscanai > "%OUT%\install_release.log" 2>&1
"%ADB%" install -r -t "%REL%" >> "%OUT%\install_release.log" 2>&1
set "IRC=%ERRORLEVEL%"
>> "%SHIP%" echo       install rc=%IRC%
if not "%IRC%"=="0" type "%OUT%\install_release.log" >> "%SHIP%"
rem Grant CAMERA so the launch lands on the camera screen (the screenshot target).
"%ADB%" shell pm grant com.docscanai android.permission.CAMERA >nul 2>&1
"%ADB%" shell am force-stop com.docscanai >nul 2>&1
"%ADB%" shell monkey -p com.docscanai -c android.intent.category.LAUNCHER 1 > "%OUT%\launch.log" 2>&1
ping -n 10 127.0.0.1 >nul
"%ADB%" shell screencap -p /sdcard/h38_smoke.png >nul 2>&1
"%ADB%" pull /sdcard/h38_smoke.png "%OUT%\h38_launch_camera_screen.png" >nul 2>&1
"%ADB%" shell rm /sdcard/h38_smoke.png >nul 2>&1
"%ADB%" logcat -d -v brief -s DocScan AndroidRuntime > "%OUT%\smoke_logcat.txt" 2>&1
findstr /c:"FATAL EXCEPTION" /c:"Fatal signal" "%OUT%\smoke_logcat.txt" >nul
if errorlevel 1 (
    >> "%SHIP%" echo       smoke OK - no crash markers, screenshot h38_launch_camera_screen.png
) else (
    >> "%SHIP%" echo       smoke CRASH MARKERS FOUND - see smoke_logcat.txt
)

:done
rem --- 6) finished ------------------------------------------------------------------------
>> "%SHIP%" echo [6/6] done
>> "%SHIP%" echo ============================================================================
>> "%HB%" echo [%DATE% %TIME%] [H38SHIP] done
echo DONE rc=0
endlocal & exit /b 0
