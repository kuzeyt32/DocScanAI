@echo off
setlocal enableextensions enabledelayedexpansion
title DocScanAI e2e harness

rem ============================================================================
rem DocScan AI - one-command build/install/test harness (SUBTASK B / H1)
rem ============================================================================
rem Turns "the agent can build, launch and test the app itself" into a single
rem re-runnable command. It:
rem   1. kills stray emulator.exe / qemu-system-x86_64.exe + stale adb state it
rem      started (never touches PrismLauncher / VS Code JDT);
rem   2. boots AVD `docscan_test` headless (wipe-data => deterministic) and waits
rem      (<=300 s) for boot_completed + a responsive package manager + am;
rem   3. builds with the pinned JDK-21 env: gradlew.bat assembleDebug
rem      assembleDebugAndroidTest --no-daemon;
rem   4. installs app-debug.apk + app-debug-androidTest.apk;
rem   5. provisions the ONNX models (they are NOT in assets/) by streaming them
rem      straight into the app's private storage, then clears logcat and starts a
rem      filtered background capture;
rem   6. runs the deterministic AI-ON regression tests scoped to just the neural
rem      classes (CPU-EP first, then XNNPACK - each in its own process);
rem   7. on failure / native signal pulls files/crash/last_crash.txt,
rem      crash_history.txt and dumpsys meminfo into tooling/out/;
rem   8. ALWAYS tears down (stop logcat, adb emu kill, wait, verify with tasklist);
rem   9. prints a compact PASS/FAIL/INVALID report.
rem
rem Native crashes are only attributed to the app when the log references
rem com.docscanai (system processes such as surfaceflinger can abort on their own).
rem
rem Exit codes: 0=PASS  1=FAIL  2=INVALID  3=INFRA (boot/build/install aborted)
rem
rem Usage:
rem   tooling\e2e.cmd                                   (emulator lane; default)
rem   set DOCSCAN_SERIAL=R5CX... && tooling\e2e.cmd     (real USB device lane)
rem Env overrides:
rem   DOCSCAN_AVD              (default docscan_test)
rem   DOCSCAN_EMULATOR_TIMEOUT (default 300 seconds)
rem   DOCSCAN_MODELS_DIR       (default <repo>\..\dsmodels)
rem   DOCSCAN_SERIAL           (set => USB/device mode: skip emulator boot)
rem   DOCSCAN_WIPE_DATA        (default 0; set 1 to boot with -wipe-data - slower)
rem   DOCSCAN_KEEP_ADB_SERVER  (set => do not `adb kill-server` at teardown)
rem ============================================================================

set "EXIT_PASS=0"
set "EXIT_FAIL=1"
set "EXIT_INVALID=2"
set "EXIT_INFRA=3"

rem --- resolve dirs (script may be launched from anywhere) --------------------
for %%I in ("%~dp0..") do set "REPO_ROOT=%%~fI"
set "TOOLING=%~dp0"
set "OUT=%TOOLING%out"
if not exist "%OUT%" mkdir "%OUT%"

rem --- pinned environment (inline; ANDROID_HOME is NOT global on this machine) -
set "JAVA_HOME=C:\Program Files\Java\jdk-21"
set "ANDROID_HOME=C:\Users\Kuzey\AppData\Local\Android\Sdk"
set "ADB=%ANDROID_HOME%\platform-tools\adb.exe"
set "EMULATOR=%ANDROID_HOME%\emulator\emulator.exe"
set "GRADLEW=%REPO_ROOT%\gradlew.bat"

if not defined DOCSCAN_AVD set "DOCSCAN_AVD=docscan_test"
set "AVD=%DOCSCAN_AVD%"
if not defined DOCSCAN_EMULATOR_TIMEOUT set "DOCSCAN_EMULATOR_TIMEOUT=300"
set "BOOT_TIMEOUT=%DOCSCAN_EMULATOR_TIMEOUT%"
if not defined DOCSCAN_MODELS_DIR set "DOCSCAN_MODELS_DIR=%REPO_ROOT%\..\dsmodels"
for %%I in ("%DOCSCAN_MODELS_DIR%") do set "DOCSCAN_MODELS_DIR=%%~fI"
if not defined DOCSCAN_WIPE_DATA set "DOCSCAN_WIPE_DATA=0"
set "WIPE_ARG="
if "%DOCSCAN_WIPE_DATA%"=="1" set "WIPE_ARG=-wipe-data"

rem expected pinned model sizes (bytes) - used to verify the provisioning
set "M1_BYTES=47235563"
set "M2_BYTES=31684150"

set "APK=%REPO_ROOT%\app\build\outputs\apk\debug\app-debug.apk"
set "TEST_APK=%REPO_ROOT%\app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk"

set "PKG=com.docscanai"
set "CPU_CLASS=com.docscanai.NeuralPathReproTest"
set "XNN_CLASS=com.docscanai.NeuralPathXnnpackTest"

rem --- lanes ------------------------------------------------------------------
set "USB_MODE=0"
set "SER="
if defined DOCSCAN_SERIAL set "USB_MODE=1"
if defined DOCSCAN_SERIAL set "SER=-s %DOCSCAN_SERIAL%"

rem --- run state ---------------------------------------------------------------
set "EMU_STARTED=0"
set "INSTR="
set "MODELS_ABSENT=0"
set "MODELS_PROVISIONED=0"
set "M1_ON=0"
set "M2_ON=0"
set "CPU_RESULT=NOTRUN"
set "XNN_RESULT=NOTRUN"
set "SESSIONS=0"
set "NATIVE_SIGNAL=0"
set "FATAL_UNCAUGHT=0"
set "CPU_EP_USED=none"
set "XNN_EP_USED=none"
set "XNN_FALLBACK=0"
set "BUILD_SEC=?"
set "INSTALL_SEC=?"
set "CPU_TEST_SEC=?"
set "XNN_TEST_SEC=?"
set "CRASH_BEFORE=no"
set "CRASH_AFTER=no"
set "RESULT=FAIL"
set "REASON=-"
set "EXIT=%EXIT_FAIL%"

rem --- timestamp (locale-tolerant) ---------------------------------------------
set "TS=%DATE%_%TIME%"
set "TS=%TS:/=%"
set "TS=%TS:-=%"
set "TS=%TS:.=%"
set "TS=%TS::=%"
set "TS=%TS:,=%"
set "TS=%TS: =%"
set "LOG_LIVE=%OUT%\logcat_%TS%.txt"
set "DUMP=%OUT%\logcat_dump_%TS%.txt"

echo ============================================================================
echo DocScan AI e2e harness
echo   repo    : %REPO_ROOT%
echo   out     : %OUT%
echo   avd     : %AVD%   emulator timeout %BOOT_TIMEOUT%s   wipeData=%DOCSCAN_WIPE_DATA%
echo   models  : %DOCSCAN_MODELS_DIR%
echo   usb mode: %USB_MODE%   serial=[%DOCSCAN_SERIAL%]
echo ============================================================================

rem ============================================================================
rem 1) Kill stray emulator/qemu + stale adb state
rem ============================================================================
echo [1/9] cleaning stray emulator/qemu + stale adb state ...
taskkill /FI "WINDOWTITLE eq docscan_logcat" /T /F >nul 2>&1
taskkill /IM emulator.exe /F >nul 2>&1
taskkill /IM qemu-system-x86_64.exe /F >nul 2>&1
"%ADB%" kill-server >nul 2>&1
call :sleep 3

rem ============================================================================
rem 2) Boot the emulator headless (unless USB/device mode)
rem ============================================================================
if "%USB_MODE%"=="1" goto usb_boot
echo [2/9] booting AVD %AVD% headless %WIPE_ARG% ...
start "docscan_emulator" /MIN "%EMULATOR%" -avd %AVD% -no-window -no-audio -no-snapshot -no-boot-anim -gpu swiftshader_indirect %WIPE_ARG%
set "EMU_STARTED=1"
set /a WAITED=0
:boot_poll
"%ADB%" %SER% shell getprop sys.boot_completed 2>nul | findstr /r "^1" >nul
if not errorlevel 1 goto boot_ok
call :sleep 3
set /a WAITED+=3
if !WAITED! GEQ %BOOT_TIMEOUT% goto boot_timeout
goto boot_poll

:boot_timeout
echo   [FAIL] emulator did not report sys.boot_completed=1 within %BOOT_TIMEOUT%s
set "REASON=emulator boot timeout (%BOOT_TIMEOUT%s)"
set "RESULT=INFRA"
set "EXIT=%EXIT_INFRA%"
goto teardown

:boot_ok
echo   boot_completed=1 after ~!WAITED!s
goto after_boot

:usb_boot
echo [2/9] USB device mode - skipping emulator boot (targeting %DOCSCAN_SERIAL%)
"%ADB%" %SER% wait-for-device
"%ADB%" %SER% shell getprop sys.boot_completed 2>nul | findstr /r "^1" >nul
if errorlevel 1 echo   [WARN] device %DOCSCAN_SERIAL% not reporting boot_completed=1 yet; continuing

:after_boot

rem ============================================================================
rem 3) Build (pinned env; no daemon)
rem ============================================================================
echo [3/9] building assembleDebug assembleDebugAndroidTest ...
call :nowsec
set "BT0=%NOWSEC%"
pushd "%REPO_ROOT%"
call "%GRADLEW%" assembleDebug assembleDebugAndroidTest --no-daemon > "%OUT%\build_%TS%.log" 2>&1
set "BUILD_RC=%ERRORLEVEL%"
popd
call :nowsec
set /a BUILD_SEC=%NOWSEC%-%BT0%
if %BUILD_SEC% lss 0 set /a BUILD_SEC+=86400
if not "%BUILD_RC%"=="0" goto build_fail
echo   build OK in %BUILD_SEC%s -> %OUT%\build_%TS%.log
goto install

:build_fail
echo   [FAIL] gradle build failed (rc=%BUILD_RC%). Relevant lines from %OUT%\build_%TS%.log :
findstr /n /c:"error:" /c:"FAILED" /c:"FAILURE" /c:"What went wrong" /c:"Could not" "%OUT%\build_%TS%.log"
set "REASON=gradle build failed rc=%BUILD_RC%"
set "RESULT=INFRA"
set "EXIT=%EXIT_INFRA%"
goto teardown

rem ============================================================================
rem 4) Install the app + androidTest APKs
rem ============================================================================
:install
echo [4/9] waiting for the package manager / activity manager, then installing ...
call :wait_ready
if errorlevel 1 goto pm_timeout
call :nowsec
set "IT0=%NOWSEC%"
set "IRETRY=0"
:install_retry
set /a IRETRY+=1
"%ADB%" %SER% install -r -t "%APK%" > "%OUT%\install_app_%TS%.txt" 2>&1
set "APP_RC=%ERRORLEVEL%"
"%ADB%" %SER% install -r -t "%TEST_APK%" > "%OUT%\install_test_%TS%.txt" 2>&1
set "TEST_RC=%ERRORLEVEL%"

set "APP_OK="
set "TEST_OK="
findstr /c:"Success" "%OUT%\install_app_%TS%.txt" >nul 2>&1 && set "APP_OK=1"
findstr /c:"Success" "%OUT%\install_test_%TS%.txt" >nul 2>&1 && set "TEST_OK=1"
if "%APP_RC%"=="0" if defined APP_OK if "%TEST_RC%"=="0" if defined TEST_OK goto install_ok
if !IRETRY! GEQ 3 goto install_fail
echo   [WARN] install attempt !IRETRY! failed; re-waiting and retrying ...
call :wait_ready
call :sleep 3
goto install_retry

:install_ok
call :nowsec
set /a INSTALL_SEC=%NOWSEC%-%IT0%
if %INSTALL_SEC% lss 0 set /a INSTALL_SEC+=86400
echo   install OK in %INSTALL_SEC%s

rem discover the instrumentation component (the test package is auto-generated)
"%ADB%" %SER% shell pm list instrumentation > "%OUT%\instr_list_%TS%.txt" 2>&1
set "INSTR="
for /f "tokens=2 delims=: " %%a in ('findstr /c:"target=%PKG%" "%OUT%\instr_list_%TS%.txt"') do set "INSTR=%%a"
if not defined INSTR goto install_fail
echo   instrumentation: %INSTR%
goto models

:install_fail
echo   [FAIL] APK install failed (app rc=%APP_RC%, test rc=%TEST_RC%)
echo   ---- install_app ---- & type "%OUT%\install_app_%TS%.txt"
echo   ---- install_test ---- & type "%OUT%\install_test_%TS%.txt"
set "REASON=apk install failed"
set "RESULT=INFRA"
set "EXIT=%EXIT_INFRA%"
goto teardown

:pm_timeout
echo   [FAIL] the package/activity manager never became ready
set "REASON=package/activity manager not ready"
set "RESULT=INFRA"
set "EXIT=%EXIT_INFRA%"
goto teardown

rem ============================================================================
rem 5) Provision ONNX models (NOT in assets/ -> stream into app private storage)
rem ============================================================================
:models
echo [5/9] provisioning ONNX models from %DOCSCAN_MODELS_DIR% ...
if not exist "%DOCSCAN_MODELS_DIR%\m1_boundary.onnx" goto models_src_missing
if not exist "%DOCSCAN_MODELS_DIR%\m2_dewarp.onnx" goto models_src_missing
"%ADB%" %SER% shell run-as %PKG% mkdir -p files/models/m1_boundary >nul 2>&1
"%ADB%" %SER% shell run-as %PKG% mkdir -p files/models/m2_dewarp >nul 2>&1
rem SUBTASK D fix: host-side stdin redirection (`adb shell ... < localfile`) truncated the
rem binaries on Windows (11 B / 4 B observed on device -> ORT "ModelProto does not have a
rem graph" and an INVALID run). Push first (binary-safe), then redirect the DEVICE shell's
rem stdin into run-as - no bytes ever cross Windows stdin.
"%ADB%" %SER% push "%DOCSCAN_MODELS_DIR%\m1_boundary.onnx" /data/local/tmp/docscan_m1_boundary.onnx >nul 2>&1
"%ADB%" %SER% push "%DOCSCAN_MODELS_DIR%\m2_dewarp.onnx" /data/local/tmp/docscan_m2_dewarp.onnx >nul 2>&1
"%ADB%" %SER% shell "run-as %PKG% sh -c 'cat > files/models/m1_boundary/model.onnx' < /data/local/tmp/docscan_m1_boundary.onnx"
"%ADB%" %SER% shell "run-as %PKG% sh -c 'cat > files/models/m2_dewarp/model.onnx' < /data/local/tmp/docscan_m2_dewarp.onnx"
"%ADB%" %SER% shell rm -f /data/local/tmp/docscan_m1_boundary.onnx /data/local/tmp/docscan_m2_dewarp.onnx >nul 2>&1
rem verify presence AND exact size on device
"%ADB%" %SER% shell run-as %PKG% ls -l files/models/m1_boundary/model.onnx > "%OUT%\modelcheck_m1_%TS%.txt" 2>&1
"%ADB%" %SER% shell run-as %PKG% ls -l files/models/m2_dewarp/model.onnx > "%OUT%\modelcheck_m2_%TS%.txt" 2>&1
findstr /c:"%M1_BYTES%" "%OUT%\modelcheck_m1_%TS%.txt" >nul 2>&1 && set "M1_ON=1"
findstr /c:"%M2_BYTES%" "%OUT%\modelcheck_m2_%TS%.txt" >nul 2>&1 && set "M2_ON=1"
if "!M1_ON!"=="1" if "!M2_ON!"=="1" goto models_ok
goto models_missing

:models_src_missing
echo   [WARN] local model files not found under %DOCSCAN_MODELS_DIR%
set "M1_ON=0"
set "M2_ON=0"
goto models_missing

:models_ok
set "MODELS_PROVISIONED=1"
echo   models present on device with exact size (m1_boundary=%M1_BYTES% m2_dewarp=%M2_BYTES%)
goto logcat

:models_missing
echo   [WARN] ONNX models NOT available on device (m1_ok=!M1_ON! m2_ok=!M2_ON!)
echo   [WARN] -> the AI-ON run will be reported INVALID, not as a PASS.
set "MODELS_ABSENT=1"
goto logcat

rem ============================================================================
rem 6) Clear logcat + start the filtered background capture
rem ============================================================================
:logcat
echo [6/9] clearing logcat and starting background capture ...
"%ADB%" %SER% logcat -c >nul 2>&1
rem snapshot the crash state BEFORE the run
"%ADB%" %SER% shell run-as %PKG% cat files/crash/last_crash.txt > "%OUT%\last_crash_before_%TS%.txt" 2>nul
for %%A in ("%OUT%\last_crash_before_%TS%.txt") do if %%~zA GTR 0 set "CRASH_BEFORE=yes"
rem start a clean slate so a crash from THIS run is unambiguous
"%ADB%" %SER% shell run-as %PKG% rm -rf files/crash >nul 2>&1
start "docscan_logcat" /MIN "%COMSPEC%" /c call "%TOOLING%capture_logcat.cmd" "%LOG_LIVE%" "%DOCSCAN_SERIAL%"
call :sleep 3

rem ============================================================================
rem 7) Deterministic AI-ON regression tests (CPU-EP first, then XNNPACK)
rem ============================================================================
echo [7/9] running AI-ON regression: CPU-EP class first ...
call :run_test "%CPU_CLASS%" "%OUT%\instr_cpu_%TS%.txt" "CPU_RESULT"
set "CPU_TEST_SEC=%LAST_TEST_SEC%"
echo   CPU-EP result: %CPU_RESULT%  (%CPU_TEST_SEC%s)
call :collect_crash "%TS%"
call :analyse_log "%LOG_LIVE%"

echo [7/9] running AI-ON regression: XNNPACK opt-in class ...
call :run_test "%XNN_CLASS%" "%OUT%\instr_xnn_%TS%.txt" "XNN_RESULT"
set "XNN_TEST_SEC=%LAST_TEST_SEC%"
echo   XNNPACK result: %XNN_RESULT%  (%XNN_TEST_SEC%s)
call :collect_crash "%TS%"
call :analyse_log "%LOG_LIVE%"

rem final authoritative logcat dump (captures the tail the live pipe may buffer)
"%ADB%" %SER% logcat -d -v threadtime 2>nul | findstr /C:"DocScan" /C:"Fatal signal" /C:"SIGSEGV" /C:"SIGABRT" /C:"signal 11" /C:"signal 6" /C:"libonnxruntime" /C:"libonnxruntime4j_jni" /C:"libopencv_java4" /C:"XNNPACK" /C:"AndroidRuntime" > "%DUMP%"
call :analyse_log "%DUMP%"

rem crash state AFTER the run
"%ADB%" %SER% shell run-as %PKG% cat files/crash/last_crash.txt > "%OUT%\last_crash_after_%TS%.txt" 2>nul
for %%A in ("%OUT%\last_crash_after_%TS%.txt") do if %%~zA GTR 0 set "CRASH_AFTER=yes"

call :report
set "EXIT=%RESULT_CODE%"
goto teardown

rem ============================================================================
rem 8) Teardown - ALWAYS runs
rem ============================================================================
:teardown
echo [8/9] teardown ...
taskkill /FI "WINDOWTITLE eq docscan_logcat" /T /F >nul 2>&1
taskkill /IM findstr.exe /F >nul 2>&1
if "%EMU_STARTED%"=="1" goto teardown_kill_emu
goto teardown_wait

:teardown_kill_emu
set "EMU_SER="
for /f "usebackq tokens=*" %%s in (`"%ADB%" get-serialno 2^>nul`) do set "EMU_SER=%%s"
if defined EMU_SER "%ADB%" -s !EMU_SER! emu kill >nul 2>&1

:teardown_wait
set "EMU_ALIVE="
set "QEMU_ALIVE="
tasklist /fi "imagename eq emulator.exe" 2>nul | findstr /i "emulator.exe" >nul && set "EMU_ALIVE=1"
tasklist /fi "imagename eq qemu-system-x86_64.exe" 2>nul | findstr /i "qemu-system-x86_64.exe" >nul && set "QEMU_ALIVE=1"
if not defined EMU_ALIVE if not defined QEMU_ALIVE goto teardown_down
set /a TW+=2
if !TW! GEQ 60 goto teardown_force
call :sleep 2
goto teardown_wait

:teardown_force
echo   forcing emulator/qemu termination ...
taskkill /IM emulator.exe /F >nul 2>&1
taskkill /IM qemu-system-x86_64.exe /F >nul 2>&1
call :sleep 3

:teardown_down
if not defined DOCSCAN_KEEP_ADB_SERVER "%ADB%" kill-server >nul 2>&1
echo   remaining emulator/qemu/adb processes:
tasklist /fi "imagename eq emulator.exe" /fo table 2>nul | findstr /i "emulator.exe"
tasklist /fi "imagename eq qemu-system-x86_64.exe" /fo table 2>nul | findstr /i "qemu"
tasklist /fi "imagename eq adb.exe" /fo table 2>nul | findstr /i "adb.exe"
echo   (no rows above == process gone)
goto finish

rem ============================================================================
rem 9) Final summary + exit
rem ============================================================================
:finish
if not defined RESULT set "RESULT=FAIL"
if not defined EXIT set "EXIT=%EXIT_FAIL%"
if not defined REASON set "REASON=-"
echo ============================================================================
echo FINAL: %RESULT%   exit=%EXIT%   reason: %REASON%
echo   build=%BUILD_SEC%s  install=%INSTALL_SEC%s  cpuTest=%CPU_TEST_SEC%s  xnnTest=%XNN_TEST_SEC%s
echo   cpu-EP=%CPU_RESULT%  xnnpack=%XNN_RESULT%  onnxSessions=%SESSIONS%
echo   nativeSignal=%NATIVE_SIGNAL%  fatalUncaught=%FATAL_UNCAUGHT%
echo   last_crash.txt before=%CRASH_BEFORE% after=%CRASH_AFTER%
echo   artifacts: %OUT%
echo ============================================================================
endlocal & exit /b %EXIT%

rem ############################################################################
rem subroutines
rem ############################################################################

:sleep
rem %1 = seconds (ping-based; `timeout` misbehaves under redirected stdin)
set "SL=%~1"
if "%SL%"=="" set "SL=1"
set /a SLN=SL+1
ping -n %SLN% 127.0.0.1 >nul 2>&1
exit /b

:wait_ready
rem Blocks until the device's package manager AND activity manager answer.
rem Returns 0 when ready, 1 on timeout. Caller must handle timeout in MAIN flow.
set /a PW=0
:wait_ready_loop
"%ADB%" %SER% shell pm path android >nul 2>&1
if errorlevel 1 goto wait_ready_next
"%ADB%" %SER% shell am get-current-user >nul 2>&1
if not errorlevel 1 exit /b 0
:wait_ready_next
call :sleep 3
set /a PW+=3
if !PW! GEQ 180 exit /b 1
goto wait_ready_loop

:nowsec
rem sets NOWSEC = seconds-of-day from %TIME% (locale tolerant: . or , fraction)
set "T=%TIME: =0%"
for /f "tokens=1,2,3 delims=:.," %%a in ("%T%") do set /a NOWSEC=(1%%a-100)*3600+(1%%b-100)*60+(1%%c-100)
exit /b

:run_test
rem %1 class, %2 output file, %3 result var name
set "TCLS=%~1"
set "TOF=%~2"
set "TVN=%~3"
set "ATTEMPT=0"
:run_test_loop
set /a ATTEMPT+=1
"%ADB%" %SER% shell am force-stop %PKG% >nul 2>&1
call :nowsec
set "TT0=%NOWSEC%"
"%ADB%" %SER% shell am instrument -w -r -e class %TCLS% %INSTR% > "%TOF%" 2>&1
set "IRC=%ERRORLEVEL%"
call :nowsec
set /a TT=%NOWSEC%-%TT0%
if %TT% lss 0 set /a TT+=86400
set "LAST_TEST_SEC=%TT%"
call :classify "%TOF%" %TVN%
if not "!%TVN%!"=="FAIL" goto run_test_done
rem transient framework/infra failure? retry once
findstr /c:"IActivityManager" /c:"Error type 2" /c:"device offline" /c:"Can't find service" /c:"INSTRUMENTATION_FAILED" "%TOF%" >nul 2>&1
if errorlevel 1 goto run_test_done
if !ATTEMPT! GEQ 2 goto run_test_done
echo   [WARN] transient instrumentation failure (attempt !ATTEMPT!); retrying ...
call :wait_ready
call :sleep 3
goto run_test_loop
:run_test_done
echo   [instr rc=%IRC% attempts=!ATTEMPT!] %TCLS%
exit /b

:classify
rem %1 file, %2 varname -> sets %2 to PASS/FAIL/INVALID
set "CF=%~1"
set "CVN=%~2"
set "HAS_OK=0"
set "HAS_FAIL=0"
set "HAS_CRASH=0"
set "HAS_INVALID=0"
findstr /c:"OK (" "!CF!" >nul 2>&1 && set "HAS_OK=1"
findstr /c:"FAILURES!!!" "!CF!" >nul 2>&1 && set "HAS_FAIL=1"
findstr /c:"shortMsg=Process crashed" "!CF!" >nul 2>&1 && set "HAS_CRASH=1"
findstr /c:"INSTRUMENTATION_FAILED" "!CF!" >nul 2>&1 && set "HAS_CRASH=1"
findstr /c:"NEURAL_RUN_INVALID" "!CF!" >nul 2>&1 && set "HAS_INVALID=1"
set "CR=FAIL"
if "!HAS_INVALID!"=="1" goto classify_invalid
if "!HAS_CRASH!"=="1" goto classify_done
if "!HAS_FAIL!"=="1" goto classify_done
if "!HAS_OK!"=="1" set "CR=PASS"
goto classify_done
:classify_invalid
set "CR=INVALID"
:classify_done
set "%CVN%=!CR!"
exit /b

:analyse_log
rem %1 = logcat file; updates SESSIONS (max) + app-scoped native/EP flags
set "LF=%~1"
if not exist "%LF%" exit /b
set "LCNT=0"
for /f "delims=" %%l in ('findstr /c:"session EP=" "%LF%"') do set /a LCNT+=1
if %LCNT% GTR %SESSIONS% set "SESSIONS=%LCNT%"
rem native crash ATTRIBUTED TO THE APP only (system procs e.g. surfaceflinger can abort too)
findstr /c:"com.docscanai" "%LF%" 2>nul | findstr /c:"Fatal signal" /c:"signal 11" /c:"signal 6" /c:"SIGSEGV" /c:"SIGABRT" >nul 2>&1 && set "NATIVE_SIGNAL=1"
findstr /c:">>> com.docscanai <<<" "%LF%" >nul 2>&1 && set "NATIVE_SIGNAL=1"
findstr /c:"FATAL UNCAUGHT" "%LF%" >nul 2>&1 && set "FATAL_UNCAUGHT=1"
findstr /c:"session EP=XNNPACK" "%LF%" >nul 2>&1 && set "XNN_EP_USED=XNNPACK"
findstr /c:"session EP=CPU" "%LF%" >nul 2>&1 && set "CPU_EP_USED=CPU"
findstr /c:"recreating with CPU EP only" "%LF%" >nul 2>&1 && set "XNN_FALLBACK=1"
exit /b

:collect_crash
rem %1 = timestamp tag; pulls crash reports + meminfo into tooling/out/
set "CT=%~1"
"%ADB%" %SER% shell run-as %PKG% cat files/crash/last_crash.txt > "%OUT%\last_crash_%CT%.txt" 2>nul
"%ADB%" %SER% shell run-as %PKG% cat files/crash/crash_history.txt > "%OUT%\crash_history_%CT%.txt" 2>nul
"%ADB%" %SER% shell dumpsys meminfo %PKG% > "%OUT%\meminfo_%CT%.txt" 2>nul
exit /b

:report
rem decides PASS / FAIL / INVALID and prints the compact report
set "RESULT=FAIL"
set "REASON=-"
if "%MODELS_ABSENT%"=="1" (
  set "RESULT=INVALID"
  set "REASON=ONNX models absent - neural path not exercised"
  goto report_print
)
if "%CPU_RESULT%"=="INVALID" (
  set "RESULT=INVALID"
  set "REASON=CPU-EP run did not exercise the neural path"
  goto report_print
)
if "%XNN_RESULT%"=="INVALID" (
  set "RESULT=INVALID"
  set "REASON=XNNPACK run did not exercise the neural path"
  goto report_print
)
if "%NATIVE_SIGNAL%"=="1" (
  set "RESULT=FAIL"
  set "REASON=native signal in the app process"
  goto report_print
)
if "%FATAL_UNCAUGHT%"=="1" (
  set "RESULT=FAIL"
  set "REASON=FATAL UNCAUGHT in logcat"
  goto report_print
)
if "%CPU_RESULT%"=="FAIL" (
  set "RESULT=FAIL"
  set "REASON=CPU-EP instrumentation failed"
  goto report_print
)
if "%XNN_RESULT%"=="FAIL" (
  set "RESULT=FAIL"
  set "REASON=XNNPACK instrumentation failed"
  goto report_print
)
if "%CPU_RESULT%"=="PASS" if "%XNN_RESULT%"=="PASS" if %SESSIONS% GEQ 1 (
  set "RESULT=PASS"
  set "REASON=AI-ON survived CPU-EP + XNNPACK across all modes; %SESSIONS% ONNX session(s) created"
  goto report_print
)
set "RESULT=INVALID"
set "REASON=no ONNX sessions observed - run not meaningful"

:report_print
set "RESULT_CODE=%EXIT_FAIL%"
if "%RESULT%"=="PASS" set "RESULT_CODE=%EXIT_PASS%"
if "%RESULT%"=="INVALID" set "RESULT_CODE=%EXIT_INVALID%"
echo ============================================================================
echo DocScan AI e2e - TEST REPORT
echo   RESULT        : %RESULT%  (%REASON%)
echo   cpu-EP class  : %CPU_RESULT%      xnnpack class: %XNN_RESULT%
echo   ONNX sessions : %SESSIONS%   EP seen: cpu=%CPU_EP_USED% xnnpack=%XNN_EP_USED% xnnFallback=%XNN_FALLBACK%
echo   native signal : %NATIVE_SIGNAL%   FATAL UNCAUGHT: %FATAL_UNCAUGHT%
echo   last_crash.txt: before=%CRASH_BEFORE% after=%CRASH_AFTER%
echo   timings       : build=%BUILD_SEC%s install=%INSTALL_SEC%s cpuTest=%CPU_TEST_SEC%s xnnTest=%XNN_TEST_SEC%s
echo   APK           : %APK%
for %%A in ("%APK%") do echo   APK size      : %%~zA bytes
echo   --- EP log lines ---
findstr /c:"session EP=" "%LOG_LIVE%" 2>nul
echo   --- per-stage timings (NEURAL_RUN_* lines) ---
findstr /c:"NEURAL_RUN_" "%LOG_LIVE%" 2>nul
echo   --- app native-crash / FATAL lines ---
findstr /c:">>> com.docscanai <<<" "%DUMP%" 2>nul
findstr /c:"FATAL UNCAUGHT" "%LOG_LIVE%" 2>nul
echo   artifacts under: %OUT%
echo ============================================================================
exit /b
