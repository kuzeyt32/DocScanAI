@echo off
setlocal enableextensions enabledelayedexpansion
rem ============================================================================
rem SUBTASK H39 - detector TRACE DUMP lane.
rem
rem Replays bundled stills through the REAL detector with TraceDump enabled and
rem pulls the AI evidence (corner-model input + 4 heat channels + montage, the
rem classical edge map and candidates, and the gate's INK map) into
rem tooling\out\h39\<Tag>\.
rem
rem Usage:
rem   tooling\h39_dump.cmd <Tag> [ImageStem1,ImageStem2,...]
rem   tooling\h39_dump.cmd <Tag> all
rem
rem   <Tag>          evidence folder name (default: trace)
rem   ImageStem...   owner110 asset stems, e.g. 20261025_130818
rem   all            trace the whole bundled owner110 set (116 photos)
rem
rem Env:
rem   DOCSCAN_MODELS_DIR  local model source dir (default <repo>\..\dsmodels,
rem                       then the H4 cache copies if present)
rem   DOCSCAN_SERIAL      set for a physical device / specific emulator
rem   DOCSCAN_TRACE_JSON_ONLY  set to 1 to skip the PNG maps (trace.json + heat peaks only);
rem                       used by the H40 whole-set analysis lane
rem ============================================================================

for %%I in ("%~dp0..") do set "REPO_ROOT=%%~fI"
set "OUT=%REPO_ROOT%\tooling\out\h39"
if not exist "%OUT%" mkdir "%OUT%"

set "ADB=C:\Users\Kuzey\AppData\Local\Android\Sdk\platform-tools\adb.exe"
set "APK=%REPO_ROOT%\app\build\outputs\apk\debug\app-debug.apk"
set "TEST_APK=%REPO_ROOT%\app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk"
set "PKG=com.docscanai"
set "INSTR=com.docscanai.test/androidx.test.runner.AndroidJUnitRunner"

if not defined DOCSCAN_MODELS_DIR (
    if exist "%REPO_ROOT%\..\dsmodels" (
        set "DOCSCAN_MODELS_DIR=%REPO_ROOT%\..\dsmodels"
    ) else if exist "E:\DocScanAI-cache\tooling-out\h4_models" (
        set "DOCSCAN_MODELS_DIR=E:\DocScanAI-cache\tooling-out\h4_models"
    )
)

set "TAG=%~1"
if "%TAG%"=="" set "TAG=trace"
set "IMAGES=%~2"
set "RUN=%OUT%\%TAG%"
if exist "%RUN%" rmdir /s /q "%RUN%"
mkdir "%RUN%"

set "ADB_DEV=%ADB%"
if defined DOCSCAN_SERIAL set "ADB_DEV=%ADB% -s %DOCSCAN_SERIAL%"

echo [H39] tag=%TAG% images=%IMAGES% out=%RUN%
call :hb "[%TAG%] lane start images=%IMAGES%"

"%ADB_DEV%" wait-for-device
set /a WB=0
:wb
set /a WB+=1
"%ADB_DEV%" shell getprop sys.boot_completed 2>nul | findstr "1" >nul && goto wb_ok
if %WB% GEQ 60 (
    call :hb "[%TAG%] BOOT TIMEOUT"
    echo BOOT TIMEOUT
    endlocal & exit /b 3
)
ping -n 4 127.0.0.1 >nul
goto wb

:wb_ok
call :hb "[%TAG%] boot_completed=1"

"%ADB_DEV%" install -r -t "%APK%" > "%RUN%\install_app.log" 2>&1
set "IAR=%ERRORLEVEL%"
if not "%IAR%"=="0" (
    call :hb "[%TAG%] app install rc=%IAR% -> uninstall + retry"
    "%ADB_DEV%" uninstall %PKG% >nul 2>&1
    "%ADB_DEV%" install -r -t "%APK%" > "%RUN%\install_app.log" 2>&1
)
"%ADB_DEV%" install -r -t "%TEST_APK%" > "%RUN%\install_test.log" 2>&1
if errorlevel 1 (
    call :hb "[%TAG%] TEST APK INSTALL FAILED"
    echo TEST APK INSTALL FAILED - run: gradlew.bat assembleDebug assembleDebugAndroidTest
    endlocal & exit /b 2
)

if exist "%DOCSCAN_MODELS_DIR%" (
    call "%REPO_ROOT%\tooling\_provision_models.cmd" "%ADB_DEV%" "%DOCSCAN_MODELS_DIR%" "" > "%RUN%\provision.log" 2>&1
    call :hb "[%TAG%] models provisioned from %DOCSCAN_MODELS_DIR%"
) else (
    call :hb "[%TAG%] models dir missing - classical path only (heat maps will be absent)"
)

"%ADB_DEV%" logcat -c >nul 2>&1

set "EXTRA="
if /i "%IMAGES%"=="all" (
    set "EXTRA=-e traceAll 1"
) else if not "%IMAGES%"=="" (
    set "EXTRA=-e traceImages %IMAGES%"
)
if defined DOCSCAN_TRACE_JSON_ONLY set "EXTRA=!EXTRA! -e traceJsonOnly 1"
if defined DOCSCAN_ASSET_DIR set "EXTRA=!EXTRA! -e assetDir %DOCSCAN_ASSET_DIR%"

"%ADB_DEV%" shell am instrument -w -r -e class com.docscanai.DetectorTraceTest -e traceTag %TAG% !EXTRA! %INSTR% > "%RUN%\DetectorTraceTest.txt" 2>&1
set "IRC=%ERRORLEVEL%"
"%ADB_DEV%" logcat -d -v brief -s DocScan > "%RUN%\logcat.txt" 2>&1

findstr /c:"FAILURES!!!" /c:"INSTRUMENTATION_FAILED" /c:"shortMsg=Process crashed" "%RUN%\DetectorTraceTest.txt" >nul 2>&1
if not errorlevel 1 (
    call :hb "[%TAG%] instrumentation FAILED (see DetectorTraceTest.txt)"
    echo LANE_FAILED tag=%TAG%
    endlocal & exit /b 1
)

rem Pull the trace folders + manifest from the app's external files dir.
set "DEV_DIR=/sdcard/Android/data/%PKG%/files/diag/%TAG%"
"%ADB_DEV%" pull "%DEV_DIR%" "%RUN%\raw" > "%RUN%\pull.log" 2>&1
if errorlevel 1 (
    call :hb "[%TAG%] pull failed; retrying via run-as tar"
    "%ADB_DEV%" shell "run-as %PKG% tar -cf - files/diag/%TAG% 2>/dev/null" > "%RUN%\diag.tar" 2>nul
    if exist "%RUN%\diag.tar" tar -xf "%RUN%\diag.tar" -C "%RUN%\raw" 2>nul
)

rem NOTE (H40): do NOT write a bare ")" inside an echo within an if(...) block - cmd counts it
rem as the block terminator and aborts the whole script with errorlevel 1 even when the branch
rem is skipped. That silently killed this lane after the pull, leaving raw/ behind.
if not exist "%RUN%\raw" goto no_trace

rem Flatten the pulled tree into the run folder.
if exist "%RUN%\raw\%TAG%" (
    robocopy "%RUN%\raw\%TAG%" "%RUN%" /E /MOVE /NFL /NDL /NJH /NJS >nul
    rmdir /s /q "%RUN%\raw" >nul 2>&1
) else (
    rem adb pull lands the scene folders straight inside raw\, so move each entry up one
    rem level and then delete the emptied raw folder. Keep these comments bracket-free:
    rem a closing bracket inside a rem line still closes the enclosing block.
    for /d %%D in ("%RUN%\raw\*") do move "%%~fD" "%RUN%" >nul
    if exist "%RUN%\raw\manifest.json" move /y "%RUN%\raw\manifest.json" "%RUN%" >nul
    rmdir /s /q "%RUN%\raw" >nul 2>&1
)

set /a SCENES=0
for /d %%D in ("%RUN%\*") do set /a SCENES+=1
call :hb "[%TAG%] scenes=%SCENES%"
if defined DOCSCAN_TRACE_JSON_ONLY (
    call :hb "[%TAG%] json-only run - skipping contact sheets"
) else (
    python "%REPO_ROOT%\tooling\h39_sheet.py" "%RUN%" >> "%RUN%\sheet.log" 2>&1
    call :hb "[%TAG%] sheets written (sheet.log)"
)

echo LANE_DONE tag=%TAG% scenes=%SCENES%
call :hb "[%TAG%] lane end"
endlocal & exit /b 0

:no_trace
call :hb "[%TAG%] NO TRACE PULLED"
echo LANE_FAILED tag=%TAG% - no trace pulled
endlocal & exit /b 1

:hb
rem SUBTASK H40: the message may contain a ">" (e.g. "rc=1 -> uninstall"), which batch
rem would parse as a redirect and thereby create a junk file at the repo root. Assign it to
rem a quoted variable and echo it via DELAYED expansion, so operators are never re-parsed.
set "HBMSG=%~1"
>>"%OUT%\HEARTBEAT.txt" echo [%DATE% %TIME%] !HBMSG!
echo [%DATE% %TIME%] !HBMSG!
exit /b 0
