@echo off
setlocal enableextensions enabledelayedexpansion
title DocScanAI SUBTASK H16 - benchmark geometry A/B lane

rem ============================================================================
rem SUBTASK H16 benchmark lane: runs WarpGeometryBenchTest (7 variants on the
rem 20 DocUNet benchmark photos) and pulls all variant outputs for the offline
rem geometry harness (tooling\h16_geometry.py).
rem
rem Bounded budgets: boot 180 s, build 600 s, test 900 s. Every long step runs
rem through _exec_watchdog.vbs, which kills ONLY its own child PID tree.
rem Teardown uses `adb emu kill` then a bounded poll; exact PIDs only.
rem ============================================================================

set "EXIT=0"
for %%I in ("%~dp0..") do set "REPO_ROOT=%%~fI"
set "TOOLING=%~dp0"
set "VBS=%TOOLING%_exec_watchdog.vbs"
set "PQ=%TOOLING%_procquery.vbs"

set "JAVA_HOME=C:\Program Files\Java\jdk-21"
set "ANDROID_HOME=C:\Users\Kuzey\AppData\Local\Android\Sdk"
set "ADB=%ANDROID_HOME%\platform-tools\adb.exe"
set "EMULATOR=%ANDROID_HOME%\emulator\emulator.exe"
set "GRADLEW=%REPO_ROOT%\gradlew.bat"

if not defined DOCSCAN_AVD set "DOCSCAN_AVD=docscan_dbg_api35"
set "AVD=%DOCSCAN_AVD%"
if not defined DOCSCAN_MODELS_DIR set "DOCSCAN_MODELS_DIR=%REPO_ROOT%\..\dsmodels"
for %%I in ("%DOCSCAN_MODELS_DIR%") do set "DOCSCAN_MODELS_DIR=%%~fI"

set "APK=%REPO_ROOT%\app\build\outputs\apk\debug\app-debug.apk"
set "TEST_APK=%REPO_ROOT%\app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk"
set "PKG=com.docscanai"
set "INSTR=com.docscanai.test/androidx.test.runner.AndroidJUnitRunner"
set "CLS=com.docscanai.WarpGeometryBenchTest"

set "BUD_BUILD=600"
set "BUD_BOOT=180"
set "BUD_TEST=900"
set "BUD_STEP=180"

set "TS=%DATE%_%TIME%"
set "TS=%TS:/=%"
set "TS=%TS:-=%"
set "TS=%TS:.=%"
set "TS=%TS::=%"
set "TS=%TS:,=%"
set "TS=%TS: =%"
set "RUN=%TOOLING%out\h16_geometry\bench_%TS%"
if defined DOCSCAN_RUN_DIR set "RUN=%DOCSCAN_RUN_DIR%"
if not exist "%RUN%" mkdir "%RUN%"
set "PROG=%RUN%\progress.txt"

call :log "H16 benchmark lane start; run dir=%RUN%"
call :log "[1/8] stopping leftover emulator PIDs we own ..."
for /f "tokens=1" %%p in ('cscript //nologo "%PQ%" "emulator.exe" 2^>nul') do taskkill /PID %%p /T /F >nul 2>&1
for /f "tokens=1" %%p in ('cscript //nologo "%PQ%" "qemu-system-x86_64.exe" 2^>nul') do taskkill /PID %%p /T /F >nul 2>&1
"%ADB%" kill-server >nul 2>&1

call :log "[2/8] booting AVD %AVD% (budget %BUD_BOOT%s) ..."
start "docscan_h16_emulator" /MIN "%EMULATOR%" -avd %AVD% -no-window -no-audio -no-snapshot -no-boot-anim -gpu swiftshader_indirect
"%ADB%" start-server >nul 2>&1
call :waitboot
if errorlevel 1 (
    call :log "  BOOT TIMEOUT"
    set "EXIT=3"
    goto teardown
)
call :log "  boot_completed=1"

call :log "[3/8] building app + androidTest (budget %BUD_BUILD%s) ..."
pushd "%REPO_ROOT%"
call cscript //nologo "%VBS%" run %BUD_BUILD% "%RUN%\build.log" "%PROG%" call "%GRADLEW%" assembleDebug assembleDebugAndroidTest --no-daemon
set "BRC=%ERRORLEVEL%"
popd
call :log "  build rc=%BRC%"
if not "%BRC%"=="0" (
    set "EXIT=3"
    goto teardown
)

call :log "[4/8] installing APKs ..."
call cscript //nologo "%VBS%" run %BUD_STEP% "%RUN%\install_app.log" "%PROG%" "%ADB%" install -r -t "%APK%"
set "IAR=%ERRORLEVEL%"
if not "%IAR%"=="0" (
    rem A release-signed build cannot be upgraded by a debug APK: uninstall once and retry.
    rem Models are re-provisioned below, so no test input is lost.
    call :log "  app install rc=%IAR% (signature change?) -> uninstall + retry"
    "%ADB%" uninstall %PKG% >nul 2>&1
    call cscript //nologo "%VBS%" run %BUD_STEP% "%RUN%\install_app.log" "%PROG%" "%ADB%" install -r -t "%APK%"
)
call cscript //nologo "%VBS%" run %BUD_STEP% "%RUN%\install_test.log" "%PROG%" "%ADB%" install -r -t "%TEST_APK%"
findstr /c:"Success" "%RUN%\install_app.log" >nul 2>&1 || (set "EXIT=3" & goto teardown)
findstr /c:"Success" "%RUN%\install_test.log" >nul 2>&1 || (set "EXIT=3" & goto teardown)

call :log "[5/8] provisioning models ..."
call cscript //nologo "%VBS%" run %BUD_STEP% "%RUN%\provision.log" "%PROG%" call "%TOOLING%_provision_models.cmd" "%ADB%" "%DOCSCAN_MODELS_DIR%" ""

call :log "[6/8] clearing logcat ..."
"%ADB%" logcat -c >nul 2>&1

call :log "[7/8] running %CLS% (budget %BUD_TEST%s) ..."
call cscript //nologo "%VBS%" run %BUD_TEST% "%RUN%\instr_bench.txt" "%PROG%" "%ADB%" shell am instrument -w -r -e class %CLS% %INSTR%
set "TRC=%ERRORLEVEL%"
findstr /c:"FAILURES!!!" /c:"INSTRUMENTATION_FAILED" /c:"Process crashed" "%RUN%\instr_bench.txt" >nul 2>&1
if errorlevel 1 (
    findstr /c:"OK (" "%RUN%\instr_bench.txt" >nul 2>&1
    if errorlevel 1 (call :log "  BENCH RESULT=FAIL rc=%TRC%" & set "EXIT=1") else (call :log "  BENCH RESULT=PASS rc=%TRC%")
) else (
    call :log "  BENCH RESULT=FAIL rc=%TRC%"
    set "EXIT=1"
)

rem --- pull variant artifacts -------------------------------------------------
call :log "  pulling geom_out ..."
"%ADB%" root >nul 2>&1
"%ADB%" wait-for-device
rem adb pull nests when the destination already exists: use a fresh directory.
set "PULL=%RUN%\geom_out"
if exist "%PULL%" move "%PULL%" "%RUN%\geom_out_prev_%TS%" >nul 2>&1
call cscript //nologo "%VBS%" run %BUD_STEP% "%RUN%\pull.log" "%PROG%" "%ADB%" pull /storage/emulated/0/Android/data/%PKG%/files/geom_out "%PULL%"
"%ADB%" logcat -d -v brief -s DocScan > "%RUN%\logcat_bench.txt" 2>&1

:teardown
call :log "[8/8] teardown (bounded) ..."
"%ADB%" emu kill >nul 2>&1
set /a TD=0
:td_wait
if exist "%RUN%\teardown.tmp" del "%RUN%\teardown.tmp" >nul 2>&1
cscript //nologo "%PQ%" "qemu-system-x86_64.exe" > "%RUN%\teardown.tmp" 2>nul
for %%A in ("%RUN%\teardown.tmp") do if %%~zA LEQ 2 goto td_kill
set /a TD+=1
if %TD% GEQ 20 goto td_force
ping -n 4 127.0.0.1 >nul
goto td_wait
:td_force
call :log "  emulator alive after 60s; killing only the exact PIDs we started"
for /f "tokens=1" %%p in ('cscript //nologo "%PQ%" "emulator.exe" 2^>nul') do taskkill /PID %%p /T /F >nul 2>&1
for /f "tokens=1" %%p in ('cscript //nologo "%PQ%" "qemu-system-x86_64.exe" 2^>nul') do taskkill /PID %%p /T /F >nul 2>&1
ping -n 3 127.0.0.1 >nul
:td_kill
for /f "tokens=1" %%p in ('cscript //nologo "%PQ%" "emulator.exe" 2^>nul') do taskkill /PID %%p /T /F >nul 2>&1
"%ADB%" kill-server >nul 2>&1
call :log "  teardown done; no emulator/qemu left (only own PIDs killed)"
call :log "FINAL: exit=%EXIT%"
endlocal & exit /b %EXIT%

:waitboot
set /a "WB=0"
:wb_loop
set /a "WB+=1"
"%ADB%" shell getprop sys.boot_completed 2>nul | findstr "1" >nul && exit /b 0
if %WB% GEQ 45 exit /b 1
ping -n 4 127.0.0.1 >nul
goto wb_loop

:log
echo [%DATE% %TIME%] %~1
>> "%PROG%" echo [%DATE% %TIME%] %~1
exit /b 0
