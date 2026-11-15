@echo off
setlocal enableextensions enabledelayedexpansion
title DocScanAI SUBTASK F check (viewer UI + detector latch)
rem ============================================================================
rem DocScan AI - focused SUBTASK F lane.
rem
rem Runs, on one headless emulator boot:
rem   1. com.docscanai.PageViewerUiTest
rem        - renders the full-screen viewer, drives zoom/pan/double-tap + pager
rem        - exercises the raw/processed toggle and the missing/corrupt-file path
rem        - writes PNG screenshots which are pulled into tooling\out\
rem   2. com.docscanai.DetectorLatchRuntimeTest
rem        - AI OFF capture (expect ZERO ONNX sessions), runtime AI-ON flip with NO
rem          restart, AI ON capture (expect the DexiNed boundary session created)
rem        - verifies, by ordering the LATCHTEST phase markers vs the 'session EP='
rem          logcat lines, that no session appears before the FLIP and >=1 after it
rem
rem Exit codes: 0=PASS  1=FAIL  2=INVALID  3=INFRA
rem Models are NOT bundled -> provisioned from %DOCSCAN_MODELS_DIR% (default ..\dsmodels);
rem if absent the latch run is reported INVALID (never a false pass).
rem ============================================================================

set "EXIT_PASS=0"
set "EXIT_FAIL=1"
set "EXIT_INVALID=2"
set "EXIT_INFRA=3"

for %%I in ("%~dp0..") do set "REPO_ROOT=%%~fI"
set "TOOLING=%~dp0"
set "OUT=%TOOLING%out"
if not exist "%OUT%" mkdir "%OUT%"

rem --- pinned environment (inline; ANDROID_HOME is NOT global on this machine) -
set "JAVA_HOME=C:\Program Files\Java\jdk-21"
set "ANDROID_HOME=C:\Users\Kuzey\AppData\Local\Android\Sdk"
set "ADB=%ANDROID_HOME%\platform-tools\adb.exe"
set "EMULATOR=%ANDROID_HOME%\emulator\emulator.exe"

if not defined DOCSCAN_AVD set "DOCSCAN_AVD=docscan_dbg_api35"
set "AVD=%DOCSCAN_AVD%"
if not defined DOCSCAN_EMULATOR_TIMEOUT set "DOCSCAN_EMULATOR_TIMEOUT=300"
set "BOOT_TIMEOUT=%DOCSCAN_EMULATOR_TIMEOUT%"
if not defined DOCSCAN_MODELS_DIR set "DOCSCAN_MODELS_DIR=%REPO_ROOT%\..\dsmodels"
for %%I in ("%DOCSCAN_MODELS_DIR%") do set "DOCSCAN_MODELS_DIR=%%~fI"

set "M1_BYTES=47235563"
set "M2_BYTES=31684150"

set "APK=%REPO_ROOT%\app\build\outputs\apk\debug\app-debug.apk"
set "TEST_APK=%REPO_ROOT%\app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk"
set "PKG=com.docscanai"
set "VIEWER_CLASS=com.docscanai.PageViewerUiTest"
set "LATCH_CLASS=com.docscanai.DetectorLatchRuntimeTest"
set "INSTR=com.docscanai.test/androidx.test.runner.AndroidJUnitRunner"

set "TS=%DATE%_%TIME%"
set "TS=%TS:/=%"
set "TS=%TS:-=%"
set "TS=%TS:.=%"
set "TS=%TS::=%"
set "TS=%TS:,=%"
set "TS=%TS: =%"
set "VIEWER_OUT=%OUT%\subtaskf_viewer_instr_%TS%.txt"
set "VIEWER_LOG=%OUT%\subtaskf_viewer_logcat_%TS%.txt"
set "LATCH_OUT=%OUT%\subtaskf_latch_instr_%TS%.txt"
set "LATCH_LOG=%OUT%\subtaskf_latch_logcat_%TS%.txt"

set "EMU_STARTED=0"
set "VIEWER_RESULT=NOTRUN"
set "LATCH_RESULT=NOTRUN"
set "SESSIONS_BEFORE=0"
set "SESSIONS_AFTER=0"
set "NATIVE_SIGNAL=0"
set "RESULT=FAIL"
set "EXIT=%EXIT_FAIL%"

echo ============================================================================
echo DocScan AI - SUBTASK F check (viewer UI + detector latch)
echo   avd    : %AVD%
echo   models : %DOCSCAN_MODELS_DIR%
echo   viewer : %VIEWER_CLASS%
echo   latch  : %LATCH_CLASS%
echo ============================================================================

echo [1/7] cleaning stray emulator/qemu + stale adb state ...
taskkill /IM emulator.exe /F >nul 2>&1
taskkill /IM qemu-system-x86_64.exe /F >nul 2>&1
"%ADB%" kill-server >nul 2>&1
call :sleep 3

echo [2/7] booting AVD %AVD% headless ...
start "docscan_emulator" /MIN "%EMULATOR%" -avd %AVD% -no-window -no-audio -no-snapshot -no-boot-anim -gpu swiftshader_indirect
set "EMU_STARTED=1"
set /a WAITED=0
:boot_poll
"%ADB%" shell getprop sys.boot_completed 2>nul | findstr /r "^1" >nul
if not errorlevel 1 goto boot_ok
call :sleep 3
set /a WAITED+=3
if !WAITED! GEQ %BOOT_TIMEOUT% goto boot_timeout
goto boot_poll

:boot_timeout
echo   [FAIL] emulator boot timeout (%BOOT_TIMEOUT%s)
set "RESULT=INFRA"
set "EXIT=%EXIT_INFRA%"
goto teardown

:boot_ok
echo   boot_completed=1 after ~!WAITED!s
call :wait_ready
if errorlevel 1 goto pm_timeout

echo [3/7] installing app + androidTest APKs ...
"%ADB%" install -r -t "%APK%" > "%OUT%\subtaskf_install_app_%TS%.txt" 2>&1
"%ADB%" install -r -t "%TEST_APK%" > "%OUT%\subtaskf_install_test_%TS%.txt" 2>&1
findstr /c:"Success" "%OUT%\subtaskf_install_app_%TS%.txt" >nul 2>&1 || goto install_fail
findstr /c:"Success" "%OUT%\subtaskf_install_test_%TS%.txt" >nul 2>&1 || goto install_fail

echo [4/7] provisioning ONNX models (needed by the latch behavioural test) ...
set "MODELS_OK=0"
if not exist "%DOCSCAN_MODELS_DIR%\m1_boundary.onnx" goto models_skip
if not exist "%DOCSCAN_MODELS_DIR%\m2_dewarp.onnx" goto models_skip
"%ADB%" shell run-as %PKG% mkdir -p files/models/m1_boundary >nul 2>&1
"%ADB%" shell run-as %PKG% mkdir -p files/models/m2_dewarp >nul 2>&1
"%ADB%" push "%DOCSCAN_MODELS_DIR%\m1_boundary.onnx" /data/local/tmp/docscan_m1_boundary.onnx >nul 2>&1
"%ADB%" push "%DOCSCAN_MODELS_DIR%\m2_dewarp.onnx" /data/local/tmp/docscan_m2_dewarp.onnx >nul 2>&1
"%ADB%" shell "run-as %PKG% sh -c 'cat > files/models/m1_boundary/model.onnx' < /data/local/tmp/docscan_m1_boundary.onnx"
"%ADB%" shell "run-as %PKG% sh -c 'cat > files/models/m2_dewarp/model.onnx' < /data/local/tmp/docscan_m2_dewarp.onnx"
"%ADB%" shell rm -f /data/local/tmp/docscan_m1_boundary.onnx /data/local/tmp/docscan_m2_dewarp.onnx >nul 2>&1
"%ADB%" shell run-as %PKG% ls -l files/models/m1_boundary/model.onnx > "%OUT%\subtaskf_modelcheck_m1_%TS%.txt" 2>&1
"%ADB%" shell run-as %PKG% ls -l files/models/m2_dewarp/model.onnx > "%OUT%\subtaskf_modelcheck_m2_%TS%.txt" 2>&1
findstr /c:"%M1_BYTES%" "%OUT%\subtaskf_modelcheck_m1_%TS%.txt" >nul 2>&1 || goto models_skip
findstr /c:"%M2_BYTES%" "%OUT%\subtaskf_modelcheck_m2_%TS%.txt" >nul 2>&1 || goto models_skip
set "MODELS_OK=1"
echo   models present with exact size (m1=%M1_BYTES% m2=%M2_BYTES%)
goto viewer_run

:models_skip
echo   [WARN] ONNX models NOT available on device -> latch run will be reported INVALID

rem ============================================================================
rem 5) Viewer UI tests (+ screenshot pull)
rem ============================================================================
:viewer_run
echo [5/7] running PageViewerUiTest (viewer render/gestures/multi-page/raw/corrupt) ...
"%ADB%" logcat -c >nul 2>&1
"%ADB%" shell am force-stop %PKG% >nul 2>&1
"%ADB%" shell am instrument -w -r -e class %VIEWER_CLASS% %INSTR% > "%VIEWER_OUT%" 2>&1
"%ADB%" logcat -d -v threadtime 2>nul | findstr /C:"DocScan" /C:"Fatal signal" /C:"SIGSEGV" /C:"SIGABRT" /C:"signal 11" /C:"signal 6" /C:"AndroidRuntime" > "%VIEWER_LOG%" 2>&1
call :classify "%VIEWER_OUT%" VIEWER_RESULT
echo   viewer result: %VIEWER_RESULT%
call :pull_screenshots "%TS%"

rem ============================================================================
rem 6) Detector latch behavioural test (+ EP ordering check)
rem ============================================================================
:latch_run
echo [6/7] running DetectorLatchRuntimeTest (AI OFF -> runtime flip -> AI ON, no restart) ...
"%ADB%" logcat -c >nul 2>&1
"%ADB%" shell am force-stop %PKG% >nul 2>&1
"%ADB%" shell am instrument -w -r -e class %LATCH_CLASS% %INSTR% > "%LATCH_OUT%" 2>&1
"%ADB%" logcat -d -v threadtime 2>nul | findstr /C:"DocScan" /C:"Fatal signal" /C:"SIGSEGV" /C:"SIGABRT" /C:"signal 11" /C:"signal 6" /C:"AndroidRuntime" > "%LATCH_LOG%" 2>&1
call :classify "%LATCH_OUT%" LATCH_RESULT
echo   latch result: %LATCH_RESULT%

rem Order the 'session EP=' lines relative to the FLIP marker.
set "FLIPLINE="
for /f "tokens=1 delims=:" %%a in ('findstr /n /c:"LATCHTEST phase=FLIP" "%LATCH_LOG%" 2^>nul') do set "FLIPLINE=%%a"
if not defined FLIPLINE set "FLIPLINE=0"
for /f "tokens=1 delims=:" %%a in ('findstr /n /c:"session EP=" "%LATCH_LOG%" 2^>nul') do (
  if %%a GTR !FLIPLINE! (set /a SESSIONS_AFTER+=1) else (set /a SESSIONS_BEFORE+=1)
)
echo   onnx sessions before FLIP=%SESSIONS_BEFORE%  after FLIP=%SESSIONS_AFTER%

rem ============================================================================
rem 7) classify + report
rem ============================================================================
:classify_result
set "RESULT=FAIL"
set "REASON=-"
if "%NATIVE_SIGNAL%"=="1" (
  set "RESULT=FAIL"
  set "REASON=native signal in the app process"
  goto report
)
if "%VIEWER_RESULT%"=="FAIL" (
  set "RESULT=FAIL"
  set "REASON=viewer UI test failed"
  goto report
)
if "%LATCH_RESULT%"=="INVALID" (
  set "RESULT=INVALID"
  set "REASON=latch run did not exercise the neural path (models absent)"
  goto report
)
if "%LATCH_RESULT%"=="FAIL" (
  set "RESULT=FAIL"
  set "REASON=detector latch behavioural test failed"
  goto report
)
if not "%SESSIONS_BEFORE%"=="0" (
  set "RESULT=FAIL"
  set "REASON=%SESSIONS_BEFORE% ONNX session(s) created while AI was OFF"
  goto report
)
if %SESSIONS_AFTER% LSS 1 (
  set "RESULT=FAIL"
  set "REASON=no ONNX session created after the runtime AI-ON flip"
  goto report
)
set "RESULT=PASS"
set "REASON=viewer UI ok; boundary session created after runtime AI flip (no restart); sessionsBeforeFlip=0 sessionsAfterFlip=%SESSIONS_AFTER%"

:report
set "RESULT_CODE=%EXIT_FAIL%"
if "%RESULT%"=="PASS" set "RESULT_CODE=%EXIT_PASS%"
if "%RESULT%"=="INVALID" set "RESULT_CODE=%EXIT_INVALID%"
echo ============================================================================
echo SUBTASK F - TEST REPORT
echo   RESULT       : %RESULT%  (%REASON%)
echo   viewer UI    : %VIEWER_RESULT%      latch: %LATCH_RESULT%
echo   EP sessions  : beforeFlip=%SESSIONS_BEFORE% afterFlip=%SESSIONS_AFTER%   nativeSignal=%NATIVE_SIGNAL%
echo   --- viewer UI test lines ---
findstr /c:"OK (" /c:"FAILURES!!!" /c:"Tests run" "%VIEWER_OUT%" 2>nul
echo   --- viewer UI evidence (DocScan) ---
findstr /c:"VIEWER_UI" /c:"VIEWER_SHOT" /c:"Viewer.decode" "%VIEWER_LOG%" 2>nul
echo   --- latch test lines ---
findstr /c:"OK (" /c:"FAILURES!!!" /c:"Tests run" "%LATCH_OUT%" 2>nul
echo   --- latch markers + EP lines (DocScan) ---
findstr /c:"LATCHTEST" /c:"session EP=" /c:"boundary model resolved" "%LATCH_LOG%" 2>nul
echo   --- app native-crash / FATAL lines ---
findstr /c:">>> com.docscanai <<<" /c:"FATAL UNCAUGHT" "%VIEWER_LOG%" "%LATCH_LOG%" 2>nul
echo   artifacts under: %OUT%
echo ============================================================================
endlocal & exit /b %EXIT%

rem ############################################################################
rem subroutines
rem ############################################################################

:pull_screenshots
rem %1 = timestamp tag; pulls viewer PNGs from the app's external files dir.
set "PT=%~1"
for %%N in (viewer_session.png viewer_thumbnail.png viewer_fullscreen.png viewer_zoomed.png viewer_page2.png viewer_error.png) do (
  if exist "%OUT%\%%N" del "%OUT%\%%N" >nul 2>&1
  "%ADB%" pull /sdcard/Android/data/com.docscanai/files/%%N "%OUT%\%%N" >nul 2>&1
  if exist "%OUT%\%%N" (echo   pulled screenshot %%N) else (echo   [warn] screenshot %%N not pulled)
)
exit /b

:classify
rem %1 file, %2 varname -> PASS/FAIL/INVALID
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
findstr /c:"LATCHTEST_FAIL" "!CF!" >nul 2>&1 && set "HAS_FAIL=1"
set "CR=FAIL"
if "!HAS_INVALID!"=="1" goto classify_invalid
if "!HAS_CRASH!"=="1" set "NATIVE_SIGNAL=1"
if "!HAS_CRASH!"=="1" goto classify_done
if "!HAS_FAIL!"=="1" goto classify_done
if "!HAS_OK!"=="1" set "CR=PASS"
goto classify_done
:classify_invalid
set "CR=INVALID"
:classify_done
set "%CVN%=!CR!"
exit /b

:sleep
set "SL=%~1"
if "%SL%"=="" set "SL=1"
set /a SLN=SL+1
ping -n %SLN% 127.0.0.1 >nul 2>&1
exit /b

:wait_ready
set /a PW=0
:wait_ready_loop
"%ADB%" shell pm path android >nul 2>&1
if errorlevel 1 goto wait_ready_next
"%ADB%" shell am get-current-user >nul 2>&1
if not errorlevel 1 exit /b 0
:wait_ready_next
call :sleep 3
set /a PW+=3
if !PW! GEQ 180 exit /b 1
goto wait_ready_loop

:pm_timeout
echo   [FAIL] package/activity manager never became ready
set "RESULT=INFRA"
set "EXIT=%EXIT_INFRA%"
goto teardown

:install_fail
echo   [FAIL] APK install failed
set "RESULT=INFRA"
set "EXIT=%EXIT_INFRA%"
goto teardown

:teardown
echo [7/7] teardown ...
taskkill /IM findstr.exe /F >nul 2>&1
if "%EMU_STARTED%"=="1" "%ADB%" emu kill >nul 2>&1
set /a TW=0
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
"%ADB%" kill-server >nul 2>&1
echo   remaining emulator/qemu:
tasklist /fi "imagename eq emulator.exe" /fo table 2>nul | findstr /i "emulator.exe"
tasklist /fi "imagename eq qemu-system-x86_64.exe" /fo table 2>nul | findstr /i "qemu"
echo   (no rows above == process gone)
endlocal & exit /b %EXIT%
