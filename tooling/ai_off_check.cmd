@echo off
setlocal enableextensions enabledelayedexpansion
title DocScanAI AI-OFF gating check
rem ============================================================================
rem DocScan AI - focused AI-OFF regression lane (SUBTASK E, 16 KB page size).
rem
rem Purpose: prove that with the ONNX models PRESENT on the device but
rem ServiceLocator.neuralProcessingEnabled = false, the pipeline takes the
rem classical OpenCV path and creates ZERO ONNX sessions - i.e. the neural-path
rem gating is intact after the ONNX Runtime / OpenCV version bumps.
rem
rem It runs exactly one instrumented test:
rem   com.docscanai.PipelineInstrumentedTest#pipeline_neuralDisabled_usesClassicalPath
rem and then counts "session EP=" lines in the captured logcat (must be 0).
rem
rem Exit codes: 0=PASS  1=FAIL  3=INFRA
rem ============================================================================

set "EXIT_PASS=0"
set "EXIT_FAIL=1"
set "EXIT_INFRA=3"

for %%I in ("%~dp0..") do set "REPO_ROOT=%%~fI"
set "TOOLING=%~dp0"
set "OUT=%TOOLING%out"
if not exist "%OUT%" mkdir "%OUT%"

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
set "OFF_CLASS=com.docscanai.PipelineInstrumentedTest#pipeline_neuralDisabled_usesClassicalPath"

set "TS=%DATE%_%TIME%"
set "TS=%TS:/=%"
set "TS=%TS:-=%"
set "TS=%TS:.=%"
set "TS=%TS::=%"
set "TS=%TS:,=%"
set "TS=%TS: =%"
set "LOG=%OUT%\ai_off_logcat_%TS%.txt"
set "INSTR_OUT=%OUT%\ai_off_instr_%TS%.txt"

set "EMU_STARTED=0"
set "SESSIONS=0"
set "CLASSICAL=0"
set "TEST_RESULT=NOTRUN"
set "NATIVE_SIGNAL=0"
set "RESULT=FAIL"
set "EXIT=%EXIT_FAIL%"

echo ============================================================================
echo DocScan AI - AI-OFF gating check (models PRESENT, neuralProcessingEnabled=false)
echo   avd    : %AVD%
echo   models : %DOCSCAN_MODELS_DIR%
echo   test   : %OFF_CLASS%
echo ============================================================================

echo [1/6] cleaning stray emulator/qemu + stale adb state ...
taskkill /IM emulator.exe /F >nul 2>&1
taskkill /IM qemu-system-x86_64.exe /F >nul 2>&1
"%ADB%" kill-server >nul 2>&1
call :sleep 3

echo [2/6] booting AVD %AVD% headless ...
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

echo [3/6] installing app + androidTest APKs ...
"%ADB%" install -r -t "%APK%" > "%OUT%\ai_off_install_app_%TS%.txt" 2>&1
"%ADB%" install -r -t "%TEST_APK%" > "%OUT%\ai_off_install_test_%TS%.txt" 2>&1
findstr /c:"Success" "%OUT%\ai_off_install_app_%TS%.txt" >nul 2>&1 || goto install_fail
findstr /c:"Success" "%OUT%\ai_off_install_test_%TS%.txt" >nul 2>&1 || goto install_fail

echo [4/6] provisioning ONNX models (so a disabled gate is the ONLY reason for zero sessions) ...
if not exist "%DOCSCAN_MODELS_DIR%\m1_boundary.onnx" goto models_missing
if not exist "%DOCSCAN_MODELS_DIR%\m2_dewarp.onnx" goto models_missing
"%ADB%" shell run-as %PKG% mkdir -p files/models/m1_boundary >nul 2>&1
"%ADB%" shell run-as %PKG% mkdir -p files/models/m2_dewarp >nul 2>&1
"%ADB%" push "%DOCSCAN_MODELS_DIR%\m1_boundary.onnx" /data/local/tmp/docscan_m1_boundary.onnx >nul 2>&1
"%ADB%" push "%DOCSCAN_MODELS_DIR%\m2_dewarp.onnx" /data/local/tmp/docscan_m2_dewarp.onnx >nul 2>&1
"%ADB%" shell "run-as %PKG% sh -c 'cat > files/models/m1_boundary/model.onnx' < /data/local/tmp/docscan_m1_boundary.onnx"
"%ADB%" shell "run-as %PKG% sh -c 'cat > files/models/m2_dewarp/model.onnx' < /data/local/tmp/docscan_m2_dewarp.onnx"
"%ADB%" shell rm -f /data/local/tmp/docscan_m1_boundary.onnx /data/local/tmp/docscan_m2_dewarp.onnx >nul 2>&1
"%ADB%" shell run-as %PKG% ls -l files/models/m1_boundary/model.onnx > "%OUT%\ai_off_modelcheck_m1_%TS%.txt" 2>&1
"%ADB%" shell run-as %PKG% ls -l files/models/m2_dewarp/model.onnx > "%OUT%\ai_off_modelcheck_m2_%TS%.txt" 2>&1
findstr /c:"%M1_BYTES%" "%OUT%\ai_off_modelcheck_m1_%TS%.txt" >nul 2>&1 || goto models_missing
findstr /c:"%M2_BYTES%" "%OUT%\ai_off_modelcheck_m2_%TS%.txt" >nul 2>&1 || goto models_missing
echo   models present with exact size (m1=%M1_BYTES% m2=%M2_BYTES%)

echo [5/6] clearing logcat + running AI-OFF test ...
"%ADB%" logcat -c >nul 2>&1
"%ADB%" shell am instrument -w -r -e class "%OFF_CLASS%" com.docscanai.test/androidx.test.runner.AndroidJUnitRunner > "%INSTR_OUT%" 2>&1
"%ADB%" logcat -d -v threadtime 2>nul | findstr /C:"DocScan" /C:"Fatal signal" /C:"SIGSEGV" /C:"SIGABRT" /C:"signal 11" /C:"signal 6" /C:"libonnxruntime" /C:"libopencv_java4" /C:"AndroidRuntime" > "%LOG%" 2>&1

for /f "delims=" %%l in ('findstr /c:"session EP=" "%LOG%" 2^>nul') do set /a SESSIONS+=1
for /f "delims=" %%l in ('findstr /c:"classical-only page=" "%LOG%" 2^>nul') do set "CLASSICAL=1"
findstr /c:"OK (" "%INSTR_OUT%" >nul 2>&1 && set "TEST_RESULT=PASS"
findstr /c:"FAILURES!!!" "%INSTR_OUT%" >nul 2>&1 && set "TEST_RESULT=FAIL"
findstr /c:"com.docscanai" "%LOG%" 2>nul | findstr /c:"Fatal signal" /c:"signal 11" /c:"signal 6" /c:"SIGSEGV" /c:"SIGABRT" >nul 2>&1 && set "NATIVE_SIGNAL=1"

echo [6/6] classifying ...
if "!TEST_RESULT!"=="NOTRUN" goto classify_fail
if "!TEST_RESULT!"=="FAIL" goto classify_fail
if "!NATIVE_SIGNAL!"=="1" goto classify_fail
if "!CLASSICAL!"=="0" goto classify_fail
if !SESSIONS! GTR 0 goto classify_fail
set "RESULT=PASS"
set "EXIT=%EXIT_PASS%"
goto teardown

:classify_fail
set "RESULT=FAIL"
set "EXIT=%EXIT_FAIL%"
goto teardown

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

:models_missing
echo   [FAIL] ONNX models not available on device - zero sessions would be meaningless
set "RESULT=INFRA"
set "EXIT=%EXIT_INFRA%"
goto teardown

:teardown
echo --- AI-OFF evidence ---
echo   testResult=%TEST_RESULT%  classicalPathRan=%CLASSICAL%  onnxSessions=%SESSIONS%  nativeSignal=%NATIVE_SIGNAL%
findstr /c:"session EP=" "%LOG%" 2>nul
findstr /c:"classical-only page=" "%LOG%" 2>nul
echo   logcat: %LOG%
echo   instr : %INSTR_OUT%
echo [teardown] stopping emulator ...
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

echo ============================================================================
echo AI-OFF RESULT: %RESULT%   exit=%EXIT%   (onnxSessions=%SESSIONS%, classicalPathRan=%CLASSICAL%, nativeSignal=%NATIVE_SIGNAL%)
echo ============================================================================
endlocal & exit /b %EXIT%

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
