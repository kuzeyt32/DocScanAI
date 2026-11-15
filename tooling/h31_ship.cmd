@echo off
setlocal enableextensions enabledelayedexpansion
rem ============================================================================
rem SUBTASK H31 - one self-terminating ship lane.
rem
rem Does every remaining STEP 4 artifact in ONE process and EXITS, so the
rem terminal can never be left holding a running command:
rem   1. signed release build
rem   2. aapt2 dump badging
rem   3. apksigner verify --print-certs
rem   4. byte size + SHA-256 (release and debug)
rem   5. check_16k on the release APK
rem   6. install + launch + screencap smoke on the emulator (if any is attached)
rem   7. writes tooling\out\h31\SHIP.txt and echoes DONE, then exit /b
rem
rem No `start`, no `wait-for-device`, no interactive waits. Every wait is a
rem bounded poll. Usage:  call tooling\h31_ship.cmd
rem ============================================================================

for %%I in ("%~dp0..") do set "REPO_ROOT=%%~fI"
set "OUT=%REPO_ROOT%\tooling\out\h31"
if not exist "%OUT%" mkdir "%OUT%"
set "SHIP=%OUT%\SHIP.txt"
set "ADB=C:\Users\Kuzey\AppData\Local\Android\Sdk\platform-tools\adb.exe"
for %%D in (36.0.0 35.0.0 34.0.0) do if exist "C:\Users\Kuzey\AppData\Local\Android\Sdk\build-tools\%%D" set "BUILD_TOOLS=C:\Users\Kuzey\AppData\Local\Android\Sdk\build-tools\%%D"
set "JAVA_HOME=C:\Program Files\Java\jdk-21"
set "PATH=%JAVA_HOME%\bin;%PATH%"

> "%SHIP%" echo SUBTASK H31 - SHIP ARTIFACTS
>> "%SHIP%" echo ============================================================================

cd /d "%REPO_ROOT%"

rem --- 1) signed release build ------------------------------------------------------------
>> "%SHIP%" echo [1/7] assembleRelease ...
call gradlew.bat :app:assembleRelease --console=plain > "%OUT%\build_release.log" 2>&1
>> "%SHIP%" echo       build rc=%ERRORLEVEL%

set "REL=%REPO_ROOT%\app\build\outputs\apk\release\app-release.apk"
set "DBG=%REPO_ROOT%\app\build\outputs\apk\debug\app-debug.apk"
if not exist "%REL%" (
    >> "%SHIP%" echo       FATAL: %REL% missing
    echo DONE rc=1
    exit /b 1
)

rem --- 2) aapt2 dump badging -------------------------------------------------------------
>> "%SHIP%" echo [2/7] aapt2 dump badging ...
"%BUILD_TOOLS%\aapt2.exe" dump badging "%REL%" > "%OUT%\badging.txt" 2>&1
findstr /c:"package:" /c:"application-label:" /c:"sdkVersion:" /c:"targetSdkVersion:" /c:"native-code:" "%OUT%\badging.txt" >> "%SHIP%"

rem --- 3) apksigner verify --print-certs -------------------------------------------------
>> "%SHIP%" echo [3/7] apksigner verify --print-certs ...
rem NOTE: apksigner is a BATCH file. Without `call` the outer script would hand over
rem control to it and never resume (this is what killed the run at step 3).
call "%BUILD_TOOLS%\apksigner.bat" verify --print-certs "%REL%" > "%OUT%\certs_release.txt" 2>&1
>> "%SHIP%" echo       apksigner rc=%ERRORLEVEL%
type "%OUT%\certs_release.txt" >> "%SHIP%"

rem --- 4) byte size + SHA-256 -----------------------------------------------------------
>> "%SHIP%" echo [4/7] size + SHA-256 ...
for %%F in ("%REL%") do set "RELSIZE=%%~zF"
for %%F in ("%DBG%") do set "DBGSIZE=%%~zF"
for /f "tokens=* delims=" %%H in ('certutil -hashfile "%REL%" SHA256 ^| findstr /r /c:"^[0-9a-fA-F][0-9a-fA-F][0-9a-fA-F][0-9a-fA-F]"') do set "RELSHA=%%H"
for /f "tokens=* delims=" %%H in ('certutil -hashfile "%DBG%" SHA256 ^| findstr /r /c:"^[0-9a-fA-F][0-9a-fA-F][0-9a-fA-F][0-9a-fA-F]"') do set "DBGSHA=%%H"
>> "%SHIP%" echo       release %RELSIZE% B  SHA-256 %RELSHA%
>> "%SHIP%" echo       debug   %DBGSIZE% B  SHA-256 %DBGSHA%

rem --- 5) 16 KB page-size check on the RELEASE apk --------------------------------------
>> "%SHIP%" echo [5/7] check_16k release ...
python tooling\check_16k.py "%REL%" > "%OUT%\check16k_release.txt" 2>&1
python tooling\check_16k.py "%DBG%" > "%OUT%\check16k_debug.txt" 2>&1
for /f %%C in ('find /c "16KB-OK" ^< "%OUT%\check16k_release.txt"') do set "R16=%%C"
for /f %%C in ('find /c "16KB-OK" ^< "%OUT%\check16k_debug.txt"') do set "D16=%%C"
>> "%SHIP%" echo       release 16KB-OK=%R16%   debug 16KB-OK=%D16%

rem --- 6) install + launch + screencap smoke (bounded; skipped when no device) -----------
>> "%SHIP%" echo [6/7] install + launch smoke ...
"%ADB%" devices 2>nul | findstr /c:"device" | findstr /v /c:"devices" >nul
if errorlevel 1 (
    >> "%SHIP%" echo       no emulator attached - smoke SKIPPED
    goto done
)
rem The device has the DEBUG build (different signature); a plain `install -r` would
rem fail with INSTALL_FAILED_UPDATE_INCOMPATIBLE. Uninstall first, then install.
"%ADB%" uninstall com.docscanai > "%OUT%\install_release.log" 2>&1
"%ADB%" install -r -t "%REL%" >> "%OUT%\install_release.log" 2>&1
set "IRC=%ERRORLEVEL%"
>> "%SHIP%" echo       install rc=%IRC%
if not "%IRC%"=="0" type "%OUT%\install_release.log" >> "%SHIP%"
"%ADB%" shell am force-stop com.docscanai >nul 2>&1
"%ADB%" shell monkey -p com.docscanai -c android.intent.category.LAUNCHER 1 > "%OUT%\launch.log" 2>&1
ping -n 8 127.0.0.1 >nul
"%ADB%" shell screencap -p /sdcard/h31_smoke.png >nul 2>&1
"%ADB%" pull /sdcard/h31_smoke.png "%OUT%\h31_smoke.png" >nul 2>&1
"%ADB%" shell rm /sdcard/h31_smoke.png >nul 2>&1
"%ADB%" logcat -d -v brief -s DocScan AndroidRuntime > "%OUT%\smoke_logcat.txt" 2>&1
findstr /c:"FATAL EXCEPTION" /c:"Fatal signal" "%OUT%\smoke_logcat.txt" >nul
if errorlevel 1 (
    >> "%SHIP%" echo       smoke OK - no crash markers, screenshot h31_smoke.png
) else (
    >> "%SHIP%" echo       smoke CRASH MARKERS FOUND - see smoke_logcat.txt
)

:done
rem --- 7) finished ------------------------------------------------------------------------
>> "%SHIP%" echo [7/7] done
>> "%SHIP%" echo ============================================================================
echo DONE rc=0
endlocal & exit /b 0
