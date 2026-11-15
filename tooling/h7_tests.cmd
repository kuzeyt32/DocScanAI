@echo off
setlocal enableextensions enabledelayedexpansion
rem ============================================================================
rem DocScanAI SUBTASK H7 - run the standard instrumented lanes (one process each)
rem
rem Classes: DetectionRegressionTest, DewarpFidelityTest, NeuralPathReproTest,
rem          NeuralPathXnnpackTest, PageViewerUiTest, DetectorLatchRuntimeTest,
rem          AutoCaptureGatingTest, LiveCapturePathTest,
rem          LiveRoomNegativeTest, AutoCaptureDuplicateTest,
rem          RefineSeedGuardTest (SUBTASK H26 — the seeded refine() guard),
rem          BookSpreadDetectionTest (SUBTASK H29 — open-book / facing-page sliver:
rem          detection rate, corner error vs exact GT, and whether the quad absorbs
rem          the neighbouring page).
rem
rem LiveRoomNegativeTest (SUBTASK H21) is the ACCEPTANCE GATE for the live-room false
rem positive: the 16 real room frames harvested from the H20 camera run
rem (app/src/androidTest/assets/live_room/) are replayed through the real detector and
rem PagePlausibility. Every one must FAIL the gate, and a 3-frame hold of each frame must
rem produce ZERO auto-captures. It was recorded RED on 0.3.4 (room_00 passed, 1 capture).
rem
rem AutoCaptureDuplicateTest (SUBTASK H21) proves the capture-safety rules: 30 identical
rem stable frames yield exactly 1 capture, a moving scene never captures, the minimum hold
rem time is enforced on the frame clock, the post-capture debounce blocks an immediate
rem second page and then releases, and the manual shutter is never suppressed.
rem
rem AutoCaptureGatingTest (SUBTASK H13) is the capture-gating proof: 5 negatives must
rem produce 0 gate passes / 0 auto-captures, >=18/20 benchmark positives must pass, and a
rem 30-frame clutter sequence must never fire.
rem
rem LiveCapturePathTest (SUBTASK H20) is the LIVE-CAPTURE chain proof: synthesised camera
rem frames (a real page photo perspective-warped onto a desk) are driven through the real
rem detector -> PagePlausibility gate -> AutoCaptureStability -> CaptureStateMachine ->
rem captureRequests -> ScanPipeline -> saved files -> SessionStore -> Review -> Viewer.
rem It asserts auto-capture fires exactly once per hold (never on room/clutter), that the
rem page->room transition does not re-fire, that jitter blocks the fire until the hold is
rem stable, and that the manual shutter bypasses stability and still saves.
rem
rem Every long step is budgeted and run through _exec_watchdog.vbs, which kills
rem ONLY the exact child PID tree it started. No wildcard deletes, no broad kills.
rem Exit codes: 0=PASS 1=FAIL 3=INFRA
rem ============================================================================

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
set "BUD_BUILD=600"
set "BUD_STEP=180"
set "BUD_TEST=900"

set "RUN=%TOOLING%out\h7_tests"
if defined DOCSCAN_RUN_DIR set "RUN=%DOCSCAN_RUN_DIR%"
if not exist "%RUN%" mkdir "%RUN%"
set "PROG=%RUN%\progress.txt"
set "RESULT=PASS"
set "EXIT=0"

call :log "H7 test lane start; run dir=%RUN%"
call :log "[1/8] stopping leftover emulator PIDs we own ..."
for /f "tokens=1" %%p in ('cscript //nologo "%PQ%" "emulator.exe" 2^>nul') do taskkill /PID %%p /T /F >nul 2>&1
for /f "tokens=1" %%p in ('cscript //nologo "%PQ%" "qemu-system-x86_64.exe" 2^>nul') do taskkill /PID %%p /T /F >nul 2>&1
"%ADB%" kill-server >nul 2>&1

call :log "[2/8] booting AVD %AVD% (budget %BUD_STEP%s) ..."
start "docscan_h7_emulator" /MIN "%EMULATOR%" -avd %AVD% -no-window -no-audio -no-snapshot -no-boot-anim -gpu swiftshader_indirect
"%ADB%" start-server >nul 2>&1
call :waitboot
if errorlevel 1 (
    call :log "  BOOT TIMEOUT"
    set "RESULT=INFRA"
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
    set "RESULT=INFRA"
    set "EXIT=3"
    goto teardown
)

call :log "[4/8] installing APKs ..."
call cscript //nologo "%VBS%" run %BUD_STEP% "%RUN%\install_app.log" "%PROG%" "%ADB%" install -r -t "%APK%"
set "IAR=!ERRORLEVEL!"
if not "!IAR!"=="0" (
    rem A release-signed build cannot be upgraded in place by a debug APK. Uninstall
    rem once and retry; models are re-provisioned two steps below, so no test input
    rem is lost. This only triggers on a real signature change.
    call :log "  app install rc=!IAR! (signature change?) -> uninstall + retry"
    "%ADB%" uninstall %PKG% >nul 2>&1
    call cscript //nologo "%VBS%" run %BUD_STEP% "%RUN%\install_app.log" "%PROG%" "%ADB%" install -r -t "%APK%"
)
call cscript //nologo "%VBS%" run %BUD_STEP% "%RUN%\install_test.log" "%PROG%" "%ADB%" install -r -t "%TEST_APK%"

call :log "[5/8] provisioning models (grid graph + sidecar included) ..."
call cscript //nologo "%VBS%" run %BUD_STEP% "%RUN%\provision.log" "%PROG%" call "%TOOLING%_provision_models.cmd" "%ADB%" "%DOCSCAN_MODELS_DIR%" ""

call :log "[6/8] clearing logcat ..."
"%ADB%" logcat -c >nul 2>&1

call :log "[7/8] running instrumented classes (each in its own process, budget %BUD_TEST%s) ..."
for %%C in (DetectionRegressionTest DewarpFidelityTest NeuralPathReproTest NeuralPathXnnpackTest PageViewerUiTest DetectorLatchRuntimeTest AutoCaptureGatingTest LiveCapturePathTest LiveRoomNegativeTest AutoCaptureDuplicateTest RefineSeedGuardTest BookSpreadDetectionTest RealBookSpreadDetectionTest) do (
    call :log "  -> %%C"
    call cscript //nologo "%VBS%" run %BUD_TEST% "%RUN%\%%C.txt" "%PROG%" "%ADB%" shell am instrument -w -r -e class com.docscanai.%%C %INSTR%
    set "TRC=!ERRORLEVEL!"
    findstr /c:"FAILURES!!!" /c:"INSTRUMENTATION_FAILED" /c:"shortMsg=Process crashed" /c:"Process crashed" "%RUN%\%%C.txt" >nul 2>&1
    if errorlevel 1 (
        findstr /c:"OK (" "%RUN%\%%C.txt" >nul 2>&1
        if errorlevel 1 (
            call :log "     %%C RESULT=FAIL rc=!TRC! (no OK marker)"
            set "RESULT=FAIL"
            set "EXIT=1"
        ) else (
            call :log "     %%C RESULT=PASS rc=!TRC!"
        )
    ) else (
        call :log "     %%C RESULT=FAIL rc=!TRC! (failure marker)"
        set "RESULT=FAIL"
        set "EXIT=1"
    )
)

rem --- crash artifacts (best effort) -----------------------------------------
"%ADB%" pull /data/data/%PKG%/files/crash/last_crash.txt "%RUN%\last_crash.txt" >nul 2>&1
"%ADB%" logcat -d -v brief -s DocScan > "%RUN%\logcat_all.txt" 2>&1

:teardown
call :log "[8/8] teardown (bounded) ..."
"%ADB%" emu kill >nul 2>&1
set /a TD=0
:td_wait
if exist "%RUN%\teardown.tmp" del "%RUN%\teardown.tmp" >nul 2>&1
cscript //nologo "%PQ%" "qemu-system-x86_64.exe" > "%RUN%\teardown.tmp" 2>nul
for %%A in ("%RUN%\teardown.tmp") do if %%~zA LEQ 2 goto td_killadb
set /a TD+=1
if %TD% GEQ 20 goto td_force
ping -n 4 127.0.0.1 >nul
goto td_wait

rem Bounded poll expired: the emulator ignored `adb emu kill`. Kill ONLY the exact
rem emulator/qemu PIDs we found (never a broad /IM kill), then continue.
:td_force
call :log "  emulator alive after 60s; killing only the exact PIDs we started"
for /f "tokens=1" %%p in ('cscript //nologo "%PQ%" "emulator.exe" 2^>nul') do taskkill /PID %%p /T /F >nul 2>&1
for /f "tokens=1" %%p in ('cscript //nologo "%PQ%" "qemu-system-x86_64.exe" 2^>nul') do taskkill /PID %%p /T /F >nul 2>&1
ping -n 3 127.0.0.1 >nul

rem The emulator launcher (emulator.exe) can outlive its qemu child even after
rem `adb emu kill`. Reap only the exact launcher PIDs we started, then stop our
rem adb host server so nothing from this lane keeps running.
:td_killadb
for /f "tokens=1" %%p in ('cscript //nologo "%PQ%" "emulator.exe" 2^>nul') do taskkill /PID %%p /T /F >nul 2>&1
"%ADB%" kill-server >nul 2>&1
set /a AK=0
:td_adb_wait
if exist "%RUN%\teardown_adb.tmp" del "%RUN%\teardown_adb.tmp" >nul 2>&1
cscript //nologo "%PQ%" "adb.exe" > "%RUN%\teardown_adb.tmp" 2>nul
for %%A in ("%RUN%\teardown_adb.tmp") do if %%~zA LEQ 2 goto teardown_done
set /a AK+=1
if %AK% GEQ 10 goto td_adb_force
ping -n 2 127.0.0.1 >nul
goto td_adb_wait

:td_adb_force
rem Retry with the exact PIDs only if `adb kill-server` did not take effect.
for /f "tokens=1" %%p in ('cscript //nologo "%PQ%" "adb.exe" 2^>nul') do taskkill /PID %%p /T /F >nul 2>&1

:teardown_done
call :log "  adb host stopped; no emulator/qemu left (only own PIDs killed)"
call :log "FINAL: %RESULT% exit=%EXIT%"
endlocal & exit /b %EXIT%

:waitboot
set /a "WB=0"
:wb_loop
set /a "WB+=1"
"%ADB%" shell getprop sys.boot_completed 2>nul | findstr "1" >nul && exit /b 0
if %WB% GEQ 60 exit /b 1
ping -n 4 127.0.0.1 >nul
goto wb_loop

:log
rem SUBTASK H40: see h38_lane.cmd :hb — a ">" inside the message must not become a redirect.
set "LOGMSG=%~1"
echo [%DATE% %TIME%] !LOGMSG!
>> "%PROG%" echo [%DATE% %TIME%] !LOGMSG!
exit /b 0
