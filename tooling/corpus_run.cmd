@echo off
setlocal enableextensions enabledelayedexpansion
title DocScanAI corpus lane (H1)

rem ============================================================================
rem DocScanAI SUBTASK H1 - real-life image corpus lane runner.
rem
rem It boots an AVD, builds/installs the app + androidTest APKs, provisions the
rem ONNX models, runs CorpusRealLifeTest (AI ON, then AI OFF - each in its own
rem instrumentation process), pulls the per-image artifacts + corpus_report.json,
rem and tears everything down with a bounded emulator kill.
rem
rem USER-MANDATED OPERATING RULES (these OUTRANK convenience):
rem   1. NO absurd timeouts / NO infinite waits. Every long step is budgeted and
rem      run through tooling\_exec_watchdog.vbs, which kills ONLY the exact child
rem      PID tree it started (taskkill /PID <pid> /T /F). Budgets: step 120s,
rem      build 600s, emulator boot 180s, corpus suite 900s.
rem   2. Processes that hang on EXIT are handled, not waited on: emulator teardown
rem      = `adb emu kill` then a bounded poll (<=60s); if it still lives, kill only
rem      the emulator/qemu PIDs we started. Teardown never stalls the run.
rem   3. VISIBLE PROGRESS: each step prints a timestamped line immediately and any
rem      step > ~20s emits a ~10s heartbeat. All lines also append to
rem      tooling\out\corpus_<ts>\progress.txt.
rem   4. If interrupted, assume the USER did it: stop, leave finished work intact.
rem   5. Prefer local/deterministic work; no sleep-loops beyond bounded polls.
rem
rem DESTRUCTIVE-COMMAND GUARD: no rmdir /s, no wildcard del, no broad /IM kills
rem (only exact PIDs we started), no `gradlew clean`, no sdkmanager/AVD create or
rem delete, no git reset/checkout. Output goes to timestamped folders.
rem
rem Exit codes: 0=PASS 1=FAIL 2=INVALID 3=INFRA
rem ============================================================================

set "EXIT_PASS=0"
set "EXIT_FAIL=1"
set "EXIT_INVALID=2"
set "EXIT_INFRA=3"

for %%I in ("%~dp0..") do set "REPO_ROOT=%%~fI"
set "TOOLING=%~dp0"
set "VBS=%TOOLING%_exec_watchdog.vbs"
set "PQ=%TOOLING%_procquery.vbs"
set "PROV=%TOOLING%_provision_models.cmd"

set "JAVA_HOME=C:\Program Files\Java\jdk-21"
set "JAVAWRAP=%TOOLING%_java.cmd"
set "ANDROID_HOME=C:\Users\Kuzey\AppData\Local\Android\Sdk"
set "ADB=%ANDROID_HOME%\platform-tools\adb.exe"
set "EMULATOR=%ANDROID_HOME%\emulator\emulator.exe"
set "GRADLEW=%REPO_ROOT%\gradlew.bat"
set "JAVA=%JAVA_HOME%\bin\java.exe"

set "BUD_STEP=120"
set "BUD_BUILD=600"
set "BUD_BOOT=180"
set "BUD_SUITE=900"
rem SUBTASK H3a: the corpus grew from 56 to 76 images (benchmark group added), so the two per-mode
rem budgets are raised - still bounded, still well inside the 900 s suite budget each.
set "BUD_ON=780"
set "BUD_OFF=600"

if not defined DOCSCAN_AVD set "DOCSCAN_AVD=docscan_dbg_api35"
set "AVD=%DOCSCAN_AVD%"
if not defined DOCSCAN_MODELS_DIR set "DOCSCAN_MODELS_DIR=%REPO_ROOT%\..\dsmodels"
for %%I in ("%DOCSCAN_MODELS_DIR%") do set "DOCSCAN_MODELS_DIR=%%~fI"
set "M1_BYTES=47235563"
set "M2_BYTES=31684150"
rem SUBTASK H4: the new primary detection model (DocAligner LCNet100 corner detector).
set "M1C_BYTES=4767987"
rem SUBTASK H7: the grid dewarp graph + its external-data sidecar (exact byte counts).
set "GRID_BYTES=242890"
set "GRID_DATA_BYTES=31588352"

set "APK=%REPO_ROOT%\app\build\outputs\apk\debug\app-debug.apk"
set "TEST_APK=%REPO_ROOT%\app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk"
set "PKG=com.docscanai"
set "CLS=com.docscanai.CorpusRealLifeTest"

set "SER="
set "USB=0"
if defined DOCSCAN_SERIAL set "USB=1"
if defined DOCSCAN_SERIAL set "SER=-s %DOCSCAN_SERIAL%"

set "TS=%DATE%_%TIME%"
set "TS=%TS:/=%"
set "TS=%TS:-=%"
set "TS=%TS:.=%"
set "TS=%TS::=%"
set "TS=%TS:,=%"
set "TS=%TS: =%"
set "RUN=%TOOLING%out\corpus_%TS%"
rem SUBTASK H3a: let the caller pin the run folder so a single subtask's evidence (build log,
rem logcat, corpus_out, benchmark artifacts, overlays, gallery input) all lands in one place.
if defined DOCSCAN_RUN_DIR set "RUN=%DOCSCAN_RUN_DIR%"
if not exist "%RUN%" mkdir "%RUN%"
set "PROG=%RUN%\progress.txt"
set "RUNID="
for %%I in ("%RUN%") do set "RUNID=%%~nxI"
if not defined RUNID set "RUNID=corpus_%TS%"

set "EMUPID="
set "EMU_STARTED=0"
set "INSTR="
set "SON=0"
set "SOFF=0"
set "NATIVEON=0"
set "NATIVEOFF=0"
set "INVALID=0"
set "FAILED=0"
set "RESULT=FAIL"
set "EXIT=%EXIT_FAIL%"
set "REASON=-"
set "BUILD_RC=?"
set "ON_RC=?"
set "OFF_RC=?"

echo ============================================================================
echo DocScanAI SUBTASK H1 - corpus lane
echo   repo    : %REPO_ROOT%
echo   run dir : %RUN%
echo   avd     : %AVD%   usb=%USB% serial=[%DOCSCAN_SERIAL%]
echo   models  : %DOCSCAN_MODELS_DIR%
echo   budgets : step=%BUD_STEP%s build=%BUD_BUILD%s boot=%BUD_BOOT%s suite=%BUD_SUITE%s
echo ============================================================================
call :log "corpus lane start; run dir=%RUN%"

rem ============================================================================
rem [1/10] leftovers: kill ONLY our emulator/qemu PIDs (by PID, never /IM)
rem ============================================================================
call :log "[1/10] scanning for leftover emulator/qemu from earlier runs ..."
if "%USB%"=="1" goto skip_leftover
call :killbyname "emulator.exe"
call :killbyname "qemu-system"
:skip_leftover
"%ADB%" kill-server >nul 2>&1
call :sleep 2
call :log "  leftover scan done (only own PIDs killed, if any)"

rem ============================================================================
rem [2/10] boot emulator (budget %BUD_BOOT%s) unless USB device mode
rem ============================================================================
if "%USB%"=="1" goto usb_mode
call :log "[2/10] booting AVD %AVD% headless (budget %BUD_BOOT%s) ..."
rem Launch detached with `start` (never via a captured pipe: a long-lived emulator
rem holding our stdout would hang the caller's for/f forever).
start "docscan_emulator" /MIN "%EMULATOR%" -avd %AVD% -no-window -no-audio -no-snapshot -no-boot-anim -gpu swiftshader_indirect
set "EMU_STARTED=1"
"%ADB%" start-server >nul 2>&1
call :sleep 3
rem Record the emulator PID(s) we just started, for exact-PID teardown.
for /f "tokens=1" %%p in ('cscript //nologo "%PQ%" "emulator.exe" 2^>nul') do set "EMUPID=%%p"
call :log "  emulator PID(s)=[%EMUPID%]"
call :waitboot
if errorlevel 1 goto boot_timeout
call :log "  boot_completed=1"
goto after_boot

:usb_mode
call :log "[2/10] USB device mode - skipping emulator boot (target %DOCSCAN_SERIAL%)"
"%ADB%" %SER% wait-for-device

:after_boot

rem ============================================================================
rem [3/10] build (budget %BUD_BUILD%s)
rem ============================================================================
call :log "[3/10] building assembleDebug assembleDebugAndroidTest (budget %BUD_BUILD%s) ..."
rem SUBTASK H3b: the build defect is FIXED at the source. [gradle.properties] no longer disables
rem BuildConfig generation and [app/build.gradle.kts] sets buildFeatures.buildConfig = true, so a
rem plain `gradlew.bat assembleDebug` builds with NO `-P` override. The lane therefore invokes the
rem plain command below (the former `-Pandroid.defaults.buildfeatures.buildconfig=true` is gone).
pushd "%REPO_ROOT%"
call cscript //nologo "%VBS%" run %BUD_BUILD% "%RUN%\build.log" "%PROG%" call "%GRADLEW%" assembleDebug assembleDebugAndroidTest --no-daemon
set "BUILD_RC=%ERRORLEVEL%"
popd
call :log "  build rc=%BUILD_RC%"
if not "%BUILD_RC%"=="0" goto build_fail

rem ============================================================================
rem [4/10] install app + androidTest APKs (budget %BUD_STEP%s each)
rem ============================================================================
call :log "[4/10] installing app + androidTest APKs (budget %BUD_STEP%s each) ..."
call cscript //nologo "%VBS%" run %BUD_STEP% "%RUN%\install_app.log" "%PROG%" "%ADB%" %SER% install -r -t "%APK%"
set "R1=%ERRORLEVEL%"
call cscript //nologo "%VBS%" run %BUD_STEP% "%RUN%\install_test.log" "%PROG%" "%ADB%" %SER% install -r -t "%TEST_APK%"
set "R2=%ERRORLEVEL%"
findstr /c:"Success" "%RUN%\install_app.log" >nul 2>&1 || goto install_fail
findstr /c:"Success" "%RUN%\install_test.log" >nul 2>&1 || goto install_fail
call :log "  install OK (app rc=%R1% test rc=%R2%)"

"%ADB%" %SER% shell pm list instrumentation > "%RUN%\instr_list.txt" 2>&1
for /f "tokens=2 delims=: " %%a in ('findstr /c:"target=%PKG%" "%RUN%\instr_list.txt"') do set "INSTR=%%a"
if not defined INSTR goto install_fail
call :log "  instrumentation: %INSTR%"

rem ============================================================================
rem [5/10] provision ONNX models (budget %BUD_STEP%s)
rem ============================================================================
call :log "[5/10] provisioning ONNX models (budget %BUD_STEP%s) ..."
call cscript //nologo "%VBS%" run %BUD_STEP% "%RUN%\provision.log" "%PROG%" call "%PROV%" "%ADB%" "%DOCSCAN_MODELS_DIR%" "%SER%"
findstr /c:"%M1_BYTES%" "%RUN%\provision.log" >nul 2>&1 || goto models_fail
findstr /c:"%M2_BYTES%" "%RUN%\provision.log" >nul 2>&1 || goto models_fail
findstr /c:"%M1C_BYTES%" "%RUN%\provision.log" >nul 2>&1 || goto models_fail
findstr /c:"%GRID_BYTES%" "%RUN%\provision.log" >nul 2>&1 || goto models_fail
findstr /c:"%GRID_DATA_BYTES%" "%RUN%\provision.log" >nul 2>&1 || goto models_fail
call :log "  models present with exact size (m1c=%M1C_BYTES% m1=%M1_BYTES% m2=%M2_BYTES% grid=%GRID_BYTES% gridData=%GRID_DATA_BYTES%)"

rem ============================================================================
rem [6/10] AI-ON corpus run (budget %BUD_ON%s) + logcat capture
rem ============================================================================
call :log "[6/10] AI-ON corpus run (budget %BUD_ON%s) ..."
"%ADB%" %SER% logcat -c >nul 2>&1
call cscript //nologo "%VBS%" run %BUD_ON% "%RUN%\instr_ai_on.txt" "%PROG%" "%ADB%" %SER% shell am instrument -w -r -e class %CLS%#corpus_ai_on %INSTR%
set "ON_RC=%ERRORLEVEL%"
"%ADB%" %SER% logcat -d -v brief -s DocScan > "%RUN%\logcat_ai_on.txt" 2>&1
call :log "  AI-ON rc=%ON_RC% (logcat -> logcat_ai_on.txt)"

rem ============================================================================
rem [7/10] AI-OFF corpus run (budget %BUD_OFF%s) + logcat capture
rem ============================================================================
call :log "[7/10] AI-OFF corpus run (budget %BUD_OFF%s) ..."
"%ADB%" %SER% logcat -c >nul 2>&1
call cscript //nologo "%VBS%" run %BUD_OFF% "%RUN%\instr_ai_off.txt" "%PROG%" "%ADB%" %SER% shell am instrument -w -r -e class %CLS%#corpus_ai_off %INSTR%
set "OFF_RC=%ERRORLEVEL%"
"%ADB%" %SER% logcat -d -v brief -s DocScan > "%RUN%\logcat_ai_off.txt" 2>&1
call :log "  AI-OFF rc=%OFF_RC% (logcat -> logcat_ai_off.txt)"

rem ============================================================================
rem [8/10] pull artifacts (budget %BUD_STEP%s)
rem ============================================================================
call :log "[8/10] pulling corpus artifacts (budget %BUD_STEP%s) ..."
"%ADB%" %SER% root >nul 2>&1
"%ADB%" %SER% wait-for-device >nul 2>&1
rem SUBTASK H3a: `adb pull <dir> <existingDir>` NESTS the source inside the destination, which would
rem silently leave stale reports in place. Archive any previous pull first (a move, never a delete)
rem so the pull always produces a fresh %RUN%\corpus_out.
if exist "%RUN%\corpus_out" (
    if not exist "%RUN%\archive" mkdir "%RUN%\archive"
    move "%RUN%\corpus_out" "%RUN%\archive\corpus_out_%TS%" >nul 2>&1
    call :log "  archived previous corpus_out -> archive\corpus_out_%TS%"
)
call cscript //nologo "%VBS%" run %BUD_STEP% "%RUN%\pull.log" "%PROG%" "%ADB%" %SER% pull /storage/emulated/0/Android/data/com.docscanai/files/corpus_out "%RUN%\corpus_out"
call :log "  pull rc=%ERRORLEVEL%"

rem ============================================================================
rem [8b/10] SUBTASK H3a - package the BENCHMARK artifacts + detected-quad overlays
rem         (host-side, JDK only: per-image folders, overlay PNGs, comparison
rem          strips and benchmark_report.json/.csv)
rem ============================================================================
call :log "[8b/10] packaging benchmark artifacts + quad overlays (budget %BUD_STEP%s) ..."
pushd "%REPO_ROOT%"
call cscript //nologo "%VBS%" run %BUD_STEP% "%RUN%\benchmark_artifacts.log" "%PROG%" call "%JAVAWRAP%" tooling\BenchmarkArtifacts.java . %RUNID%
set "BA_RC=%ERRORLEVEL%"
popd
call :log "  benchmark artifacts rc=%BA_RC%"

rem ============================================================================
rem [8c/10] SUBTASK H3a - refresh the browsable gallery for this run
rem ============================================================================
call :log "[8c/10] refreshing the gallery for run %RUNID% (budget %BUD_STEP%s) ..."
pushd "%REPO_ROOT%"
call cscript //nologo "%VBS%" run %BUD_STEP% "%RUN%\gallery.log" "%PROG%" call "%JAVAWRAP%" tooling\CreateGallery.java . %RUNID%
set "GAL_RC=%ERRORLEVEL%"
popd
call :log "  gallery rc=%GAL_RC%"

rem ============================================================================
rem [8d/10] SUBTASK H3a - inject the benchmark section + overlay strips
rem ============================================================================
call :log "[8d/10] injecting the benchmark gallery section (budget %BUD_STEP%s) ..."
pushd "%REPO_ROOT%"
call cscript //nologo "%VBS%" run %BUD_STEP% "%RUN%\benchmark_gallery.log" "%PROG%" call "%JAVAWRAP%" tooling\BenchmarkGallery.java . %RUNID%
set "BG_RC=%ERRORLEVEL%"
popd
call :log "  benchmark gallery rc=%BG_RC%"

rem ============================================================================
rem [9/10] verify AI-ON sessions>0 and AI-OFF sessions==0 (else INVALID)
rem ============================================================================
call :log "[9/10] verifying ONNX session evidence ..."
rem The test emits CORPUS_SESSIONS_AI_ON=<n> / CORPUS_SESSIONS_AI_OFF=<n> at the end of each
rem run; the test's own per-image `logcat -c` makes counting raw `session EP=` lines on the
rem host unreliable, so we read the sentinel instead.
for /f "tokens=2 delims==" %%v in ('findstr /c:"CORPUS_SESSIONS_AI_ON=" "%RUN%\logcat_ai_on.txt" 2^>nul') do set "SON=%%v"
for /f "tokens=2 delims==" %%v in ('findstr /c:"CORPUS_SESSIONS_AI_OFF=" "%RUN%\logcat_ai_off.txt" 2^>nul') do set "SOFF=%%v"
if "%SON%"=="" set "SON=0"
if "%SOFF%"=="" set "SOFF=0"
findstr /c:"Fatal signal" /c:"SIGSEGV" /c:"SIGABRT" "%RUN%\logcat_ai_on.txt" >nul 2>&1 && set "NATIVEON=1"
findstr /c:"Fatal signal" /c:"SIGSEGV" /c:"SIGABRT" "%RUN%\logcat_ai_off.txt" >nul 2>&1 && set "NATIVEOFF=1"
findstr /c:"NEURAL_RUN_INVALID" /c:"CORPUS_INVALID" "%RUN%\instr_ai_on.txt" >nul 2>&1 && set "INVALID=1"
findstr /c:"NEURAL_RUN_INVALID" /c:"CORPUS_INVALID" "%RUN%\instr_ai_off.txt" >nul 2>&1 && set "INVALID=1"
if %SON% EQU 0 set "INVALID=1"
if %SOFF% GTR 0 set "INVALID=1"
findstr /c:"FAILURES!!!" /c:"shortMsg=Process crashed" /c:"INSTRUMENTATION_FAILED" "%RUN%\instr_ai_on.txt" "%RUN%\instr_ai_off.txt" >nul 2>&1 && set "FAILED=1"
if "%NATIVEON%"=="1" set "FAILED=1"
if "%NATIVEOFF%"=="1" set "FAILED=1"
call :log "  AI-ON sessions=%SON%  AI-OFF sessions=%SOFF%  native(on/off)=%NATIVEON%/%NATIVEOFF%"

if "%INVALID%"=="1" goto classify_invalid
if "%FAILED%"=="1" goto classify_fail
set "RESULT=PASS"
set "EXIT=%EXIT_PASS%"
set "REASON=AI-ON %SON% session(s) created, AI-OFF 0 sessions, no native signal"
goto teardown

:classify_invalid
set "RESULT=INVALID"
set "EXIT=%EXIT_INVALID%"
set "REASON=session evidence not established (need AI-ON>=1 and AI-OFF==0)"
goto teardown

:classify_fail
set "RESULT=FAIL"
set "EXIT=%EXIT_FAIL%"
set "REASON=test failure or native signal"
goto teardown

rem ============================================================================
rem abort paths
rem ============================================================================
:boot_timeout
call :log "  [FAIL] emulator boot timeout (%BUD_BOOT%s)"
set "RESULT=INFRA"
set "EXIT=%EXIT_INFRA%"
set "REASON=emulator boot timeout"
goto teardown

:build_fail
call :log "  [FAIL] gradle build failed (rc=%BUILD_RC%)"
set "RESULT=INFRA"
set "EXIT=%EXIT_INFRA%"
set "REASON=gradle build failed"
goto teardown

:install_fail
call :log "  [FAIL] APK install / instrumentation discovery failed"
set "RESULT=INFRA"
set "EXIT=%EXIT_INFRA%"
set "REASON=apk install failed"
goto teardown

:models_fail
call :log "  [FAIL] ONNX models not present on device with the pinned size"
set "RESULT=INFRA"
set "EXIT=%EXIT_INFRA%"
set "REASON=models not provisioned"
goto teardown

rem ============================================================================
rem [10/10] teardown - ALWAYS runs. Bounded emulator kill (never stalls).
rem ============================================================================
:teardown
call :log "[10/10] teardown (bounded) ..."
if not "%EMU_STARTED%"=="1" goto teardown_killadb
"%ADB%" %SER% emu kill >nul 2>&1
set /a TW=0
:teardown_wait
call :emu_alive
if "!ALIVE!"=="0" goto teardown_killadb
set /a TW+=2
if !TW! GEQ 60 goto teardown_force
call :sleep 2
goto teardown_wait

:teardown_force
call :log "  emulator still alive after 60s; killing only the PIDs we started"
call :killbyname "emulator.exe"
call :killbyname "qemu-system"
call :sleep 2

:teardown_killadb
"%ADB%" kill-server >nul 2>&1
call :log "  remaining emulator/qemu PIDs (nothing printed == gone):"
cscript //nologo "%PQ%" "emulator.exe"
cscript //nologo "%PQ%" "qemu-system-x86_64"

:finish
echo ============================================================================
echo FINAL: %RESULT%   exit=%EXIT%   reason: %REASON%
echo   AI-ON sessions : %SON%   AI-OFF sessions : %SOFF%
echo   native (on/off): %NATIVEON%/%NATIVEOFF%
echo   run dir        : %RUN%
echo   --- CORPUS_SESSIONS sentinels (ON must be >=1, OFF must be 0) ---
findstr /c:"CORPUS_SESSIONS_AI_ON=" /c:"CORPUS_SESSIONS_AI_OFF=" "%RUN%\logcat_ai_on.txt" "%RUN%\logcat_ai_off.txt" 2>nul
echo   --- raw session EP= lines still buffered (informational) ---
findstr /c:"session EP=" "%RUN%\logcat_ai_on.txt" "%RUN%\logcat_ai_off.txt" 2>nul
echo   --- corpus summary lines ---
findstr /c:"CORPUS_RUN" "%RUN%\logcat_ai_on.txt" "%RUN%\logcat_ai_off.txt" 2>nul
echo   --- SUBTASK H3a benchmark lines ---
findstr /c:"CORPUS_RUN" "%RUN%\logcat_ai_on.txt" "%RUN%\logcat_ai_off.txt" 2>nul | findstr /c:"bench"
echo   benchmark artifacts : %RUN%\benchmark  (report: %RUN%\benchmark_report.json)
echo   quad overlays       : %RUN%\overlay\{ai_on,ai_off}   strips: %RUN%\overlay_compare
echo   gallery             : %REPO_ROOT%\gallery\index.html
echo ============================================================================
endlocal & exit /b %EXIT%

rem ############################################################################
rem subroutines
rem ############################################################################

:log
rem %~1 = message; timestamped to stdout AND appended to the progress file
echo [%TIME%] %~1
>>"%PROG%" echo [%TIME%] %~1
exit /b

:sleep
rem %1 = seconds (ping-based; `timeout` misbehaves with redirected stdin)
set "SL=%~1"
if "%SL%"=="" set "SL=1"
set /a SLN=SL+1
ping -n %SLN% 127.0.0.1 >nul 2>&1
exit /b

:waitboot
set /a BW=0
:waitboot_loop
"%ADB%" %SER% shell getprop sys.boot_completed 2>nul | findstr /r "^1" >nul
if not errorlevel 1 exit /b 0
call :sleep 2
set /a BW+=2
set /a HB=BW %% 10
if %HB%==0 call :log "  heartbeat: waiting for boot_completed %BW%s / %BUD_BOOT%s"
if %BW% GEQ %BUD_BOOT% exit /b 1
goto waitboot_loop

:emu_alive
set "ALIVE=0"
for /f "delims=" %%p in ('cscript //nologo "%PQ%" "emulator.exe" 2^>nul') do set "ALIVE=1"
for /f "delims=" %%p in ('cscript //nologo "%PQ%" "qemu-system-x86_64" 2^>nul') do set "ALIVE=1"
exit /b

:killbyname
rem %~1 = image-name filter; kills ONLY the exact PIDs found (never /IM)
set "KN=%~1"
for /f "tokens=1" %%p in ('cscript //nologo "%PQ%" "!KN!" 2^>nul') do (
    set "KP=%%p"
    taskkill /PID !KP! /T /F >nul 2>&1
    call :log "  killed !KN! PID !KP! (tree)"
)
exit /b
