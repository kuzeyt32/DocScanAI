@echo off
setlocal enableextensions enabledelayedexpansion
rem ============================================================================
rem SUBTASK H41 - SHIP LANE (version-agnostic).
rem
rem Builds the debug + signed release APKs, proves what they are, and stages
rem them for a GitHub Release:
rem   aapt2 dump badging -> versionCode / versionName / label / native-code
rem   apksigner verify --print-certs -> CERT CONTINUITY against the pinned cert
rem   SHA-256 + byte size for every staged asset
rem   optional install + launch smoke (skipped when no device is attached)
rem
rem Usage:  tooling\h41_ship.cmd [Tag]
rem
rem Output: tooling\out\h41\<Tag>\  (SHIP.txt, badging.txt, certs_*.txt,
rem         SHA256.txt, INSTALL.txt, DocScanAI-<ver>.apk, DocScanAI-<ver>-debug.apk)
rem   and release\DocScanAI-<ver>.apk + release\DocScanAI-<ver>-debug.apk
rem ============================================================================

for %%I in ("%~dp0..") do set "REPO_ROOT=%%~fI"
set "TAG=%~1"
if "%TAG%"=="" set "TAG=ship"
set "OUT=%REPO_ROOT%\tooling\out\h41\%TAG%"
if not exist "%OUT%" mkdir "%OUT%"
set "SHIP=%OUT%\SHIP.txt"
set "ADB=C:\Users\Kuzey\AppData\Local\Android\Sdk\platform-tools\adb.exe"
for %%D in (36.0.0 35.0.0 34.0.0) do if exist "C:\Users\Kuzey\AppData\Local\Android\Sdk\build-tools\%%D" set "BUILD_TOOLS=C:\Users\Kuzey\AppData\Local\Android\Sdk\build-tools\%%D"
set "JAVA_HOME=C:\Program Files\Java\jdk-21"
set "PATH=%JAVA_HOME%\bin;%PATH%"
set "EXPECTED_CERT=7a6977076f2c16e1c521b9301339ec9196aaac6f6a7641d7ec3fc965854253dc"

cd /d "%REPO_ROOT%"
> "%SHIP%" echo SUBTASK H41 - SHIP (tag=%TAG%)
>> "%SHIP%" echo ============================================================================

rem --- 1) build both variants -------------------------------------------------------------
>> "%SHIP%" echo [1/6] assembleDebug + assembleRelease ...
call "%REPO_ROOT%\gradlew.bat" :app:assembleDebug :app:assembleRelease --console=plain > "%OUT%\build.log" 2>&1
set "BRC=%ERRORLEVEL%"
>> "%SHIP%" echo       build rc=%BRC%  (see build.log)
if not "%BRC%"=="0" (
    echo BUILD_FAILED rc=%BRC%
    endlocal & exit /b 1
)

set "REL=%REPO_ROOT%\app\build\outputs\apk\release\app-release.apk"
set "DBG=%REPO_ROOT%\app\build\outputs\apk\debug\app-debug.apk"
for %%F in ("%REL%") do if not exist "%%~fF" (
    >> "%SHIP%" echo       FATAL: release APK missing
    echo DONE rc=1
    endlocal & exit /b 1
)

rem --- 2) identity ------------------------------------------------------------------------
>> "%SHIP%" echo [2/6] aapt2 dump badging ...
"%BUILD_TOOLS%\aapt2.exe" dump badging "%REL%" > "%OUT%\badging.txt" 2>&1
rem The package line is:
rem   package: name='com.docscanai' versionCode='17' versionName='0.3.14' ...
rem so with delims=' the fields are 2=pkg 4=versionCode 6=versionName.
set "VCODE_N="
set "VNAME="
for /f "tokens=4 delims='" %%A in ('findstr /b /c:"package: name=" "%OUT%\badging.txt"') do set "VCODE_N=%%A"
for /f "tokens=6 delims='" %%A in ('findstr /b /c:"package: name=" "%OUT%\badging.txt"') do set "VNAME=%%A"
>> "%SHIP%" echo       versionCode=!VCODE_N! versionName=!VNAME!
findstr /c:"package: name=" /c:"application-label:" /c:"sdkVersion:" /c:"targetSdkVersion:" /c:"native-code:" "%OUT%\badging.txt" >> "%SHIP%"
if "!VNAME!"=="" (
    >> "%SHIP%" echo       FATAL: could not parse versionName
    echo PARSE_FAILED
    endlocal & exit /b 1
)
if "!VCODE_N!"=="" (
    >> "%SHIP%" echo       FATAL: could not parse versionCode
    echo PARSE_FAILED
    endlocal & exit /b 1
)

rem --- 3) certificate continuity ----------------------------------------------------------
>> "%SHIP%" echo [3/6] apksigner verify --print-certs ...
call "%BUILD_TOOLS%\apksigner.bat" verify --print-certs "%REL%" > "%OUT%\certs_release.txt" 2>&1
>> "%SHIP%" echo       apksigner rc=%ERRORLEVEL%
type "%OUT%\certs_release.txt" >> "%SHIP%"
> "%OUT%\expected_cert.txt" echo %EXPECTED_CERT%
python tooling\h32_cert_check.py "%OUT%\certs_release.txt" "%OUT%\expected_cert.txt" > "%OUT%\cert_check.txt" 2>&1
if errorlevel 1 goto cert_fail
type "%OUT%\cert_check.txt" >> "%SHIP%"
>> "%SHIP%" echo       CERT CONTINUITY OK
goto cert_ok
:cert_fail
type "%OUT%\cert_check.txt" >> "%SHIP%"
>> "%SHIP%" echo       CERT MISMATCH - expected %EXPECTED_CERT%
echo DONE rc=2 cert_mismatch
endlocal & exit /b 2
:cert_ok

rem --- 4) stage assets with the version in the filename -----------------------------------
>> "%SHIP%" echo [4/6] stage + hash ...
set "RELNAME=DocScanAI-!VNAME!.apk"
set "DBGNAME=DocScanAI-!VNAME!-debug.apk"
if not exist "%REPO_ROOT%\release" mkdir "%REPO_ROOT%\release"
copy /y "%REL%" "%OUT%\!RELNAME!" >nul
copy /y "%DBG%" "%OUT%\!DBGNAME!" >nul
copy /y "%REL%" "%REPO_ROOT%\release\!RELNAME!" >nul
copy /y "%DBG%" "%REPO_ROOT%\release\!DBGNAME!" >nul
for /f "tokens=* delims=" %%H in ('certutil -hashfile "%OUT%\!RELNAME!" SHA256 ^| findstr /r /c:"^[0-9a-fA-F][0-9a-fA-F][0-9a-fA-F][0-9a-fA-F]"') do set "RELSHA=%%H"
for /f "tokens=* delims=" %%H in ('certutil -hashfile "%OUT%\!DBGNAME!" SHA256 ^| findstr /r /c:"^[0-9a-fA-F][0-9a-fA-F][0-9a-fA-F][0-9a-fA-F]"') do set "DBGSHA=%%H"
for %%F in ("%OUT%\!RELNAME!") do set "RELSIZE=%%~zF"
for %%F in ("%OUT%\!DBGNAME!") do set "DBGSIZE=%%~zF"
>> "%SHIP%" echo       !RELNAME!  !RELSIZE! B  SHA-256 !RELSHA!
>> "%SHIP%" echo       !DBGNAME!  !DBGSIZE! B  SHA-256 !DBGSHA!

> "%OUT%\SHA256.txt" echo !RELNAME!  !RELSIZE! bytes
>> "%OUT%\SHA256.txt" echo SHA-256 !RELSHA!
>> "%OUT%\SHA256.txt" echo !DBGNAME!  !DBGSIZE! bytes
>> "%OUT%\SHA256.txt" echo SHA-256 !DBGSHA!
>> "%OUT%\SHA256.txt" echo versionCode=!VCODE_N! versionName=!VNAME!
>> "%OUT%\SHA256.txt" echo cert SHA-256 %EXPECTED_CERT%
type "%OUT%\SHA256.txt" >> "%SHIP%"

> "%OUT%\INSTALL.txt" echo DocScan AI !VNAME! - install
>> "%OUT%\INSTALL.txt" echo.
>> "%OUT%\INSTALL.txt" echo RELEASE APK  (signed, recommended)
>> "%OUT%\INSTALL.txt" echo   1. If a DEBUG build is already installed, remove it first:
>> "%OUT%\INSTALL.txt" echo        adb uninstall com.docscanai
>> "%OUT%\INSTALL.txt" echo   2. adb install -r !RELNAME!
>> "%OUT%\INSTALL.txt" echo   3. No PC? Copy !RELNAME! to the phone and open it from the file manager.
>> "%OUT%\INSTALL.txt" echo.
>> "%OUT%\INSTALL.txt" echo DEBUG APK  (for bug reports; different signing key)
>> "%OUT%\INSTALL.txt" echo   adb install -r !DBGNAME!
>> "%OUT%\INSTALL.txt" echo.
>> "%OUT%\INSTALL.txt" echo Both are arm64-v8a. Android 8.0+ (API 26). No Play services needed.
>> "%OUT%\INSTALL.txt" echo The AI models download in-app on first launch, or are pushed by tooling.

rem --- 5) optional smoke ------------------------------------------------------------------
>> "%SHIP%" echo [5/6] install + launch smoke ...
"%ADB%" devices 2>nul | findstr /c:"device" | findstr /v /c:"devices" >nul
if errorlevel 1 (
    >> "%SHIP%" echo       no device attached - smoke SKIPPED
    goto done
)
"%ADB%" uninstall com.docscanai > "%OUT%\install_release.log" 2>&1
"%ADB%" install -r -t "%OUT%\!RELNAME!" >> "%OUT%\install_release.log" 2>&1
>> "%SHIP%" echo       install rc=%ERRORLEVEL%
"%ADB%" shell pm grant com.docscanai android.permission.CAMERA >nul 2>&1
"%ADB%" shell am force-stop com.docscanai >nul 2>&1
"%ADB%" shell monkey -p com.docscanai -c android.intent.category.LAUNCHER 1 > "%OUT%\launch.log" 2>&1
ping -n 10 127.0.0.1 >nul
"%ADB%" shell screencap -p /sdcard/h41_smoke.png >nul 2>&1
"%ADB%" pull /sdcard/h41_smoke.png "%OUT%\launch_screen.png" >nul 2>&1
"%ADB%" shell rm /sdcard/h41_smoke.png >nul 2>&1
"%ADB%" logcat -d -v brief -s DocScan AndroidRuntime > "%OUT%\smoke_logcat.txt" 2>&1
findstr /c:"FATAL EXCEPTION" /c:"Fatal signal" "%OUT%\smoke_logcat.txt" >nul
if errorlevel 1 (
    >> "%SHIP%" echo       smoke OK - no crash markers (launch_screen.png)
) else (
    >> "%SHIP%" echo       smoke CRASH MARKERS FOUND - see smoke_logcat.txt
)

:done
>> "%SHIP%" echo [6/6] done - assets under %OUT%
>> "%SHIP%" echo ============================================================================
type "%SHIP%"
echo DONE rc=0 version=!VNAME!
endlocal & exit /b 0
