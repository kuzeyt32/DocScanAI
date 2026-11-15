@echo off
setlocal enableextensions enabledelayedexpansion
rem ============================================================================
rem SUBTASK H36 - instrumented lane for the REAL in-app detector path on the
rem owner-110 photos (same contract as tooling\h35_lane.cmd: install, provision,
rem run, collect) but writes its evidence to tooling\out\h36\.
rem
rem Usage:  tooling\h36_lane.cmd <Tag> [TestClass ...]
rem   e.g.  tooling\h36_lane.cmd baseline Owner110RealPathTest
rem
rem Output: tooling\out\h36\<Tag>\<Class>.txt + pulled owner110_realpath.json +
rem         tooling\out\h36\HEARTBEAT.txt
rem ============================================================================

for %%I in ("%~dp0..") do set "REPO_ROOT=%%~fI"
set "OUT=%REPO_ROOT%\tooling\out\h36"
if not exist "%OUT%" mkdir "%OUT%"

set "ADB=C:\Users\Kuzey\AppData\Local\Android\Sdk\platform-tools\adb.exe"
set "APK=%REPO_ROOT%\app\build\outputs\apk\debug\app-debug.apk"
set "TEST_APK=%REPO_ROOT%\app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk"
set "PKG=com.docscanai"
set "INSTR=com.docscanai.test/androidx.test.runner.AndroidJUnitRunner"
if not defined DOCSCAN_MODELS_DIR set "DOCSCAN_MODELS_DIR=%REPO_ROOT%\..\dsmodels"

set "TAG=%~1"
set "RUN=%OUT%\%TAG%"
if not exist "%RUN%" mkdir "%RUN%"
set "HB=%OUT%\HEARTBEAT.txt"

call :hb "[%TAG%] lane start classes=%*"

"%ADB%" wait-for-device
set /a WB=0
:wb
set /a WB+=1
"%ADB%" shell getprop sys.boot_completed 2>nul | findstr "1" >nul && goto wb_ok
if %WB% GEQ 60 (
    call :hb "[%TAG%] BOOT TIMEOUT"
    echo BOOT TIMEOUT
    endlocal & exit /b 3
)
ping -n 4 127.0.0.1 >nul
goto wb
:wb_ok
call :hb "[%TAG%] boot_completed=1"

"%ADB%" install -r -t "%APK%" > "%RUN%\install_app.log" 2>&1
set "IAR=%ERRORLEVEL%"
if not "%IAR%"=="0" (
    call :hb "[%TAG%] app install rc=%IAR% -> uninstall + retry"
    "%ADB%" uninstall %PKG% >nul 2>&1
    "%ADB%" install -r -t "%APK%" > "%RUN%\install_app.log" 2>&1
)
"%ADB%" install -r -t "%TEST_APK%" > "%RUN%\install_test.txt" 2>&1
call :hb "[%TAG%] installed"

if exist "%DOCSCAN_MODELS_DIR%" (
    call "%REPO_ROOT%\tooling\_provision_models.cmd" "%ADB%" "%DOCSCAN_MODELS_DIR%" "" > "%RUN%\provision.log" 2>&1
    call :hb "[%TAG%] models provisioned"
) else (
    call :hb "[%TAG%] models dir missing (%DOCSCAN_MODELS_DIR%) - classical path only"
)

"%ADB%" logcat -c >nul 2>&1

set "FAILED=0"
set "FIRSTARG=1"
for %%C in (%*) do (
  if defined FIRSTARG (
    set "FIRSTARG="
  ) else (
    call :hb "[%TAG%] -> %%C"
    "%ADB%" shell am instrument -w -r -e class com.docscanai.%%C -e h35Tag %TAG% %INSTR% > "%RUN%\%%C.txt" 2>&1
    findstr /c:"FAILURES!!!" /c:"INSTRUMENTATION_FAILED" /c:"shortMsg=Process crashed" "%RUN%\%%C.txt" >nul 2>&1
    if errorlevel 1 (
        findstr /c:"OK (" "%RUN%\%%C.txt" >nul 2>&1
        if errorlevel 1 (
            call :hb "[%TAG%]    %%C RESULT=FAIL (no OK marker)"
            set "FAILED=1"
        ) else (
            call :hb "[%TAG%]    %%C RESULT=PASS"
        )
    ) else (
        call :hb "[%TAG%]    %%C RESULT=FAIL (failure marker)"
        set "FAILED=1"
    )
  )
)

"%ADB%" logcat -d -v brief -s DocScan > "%RUN%\logcat.txt" 2>&1

rem Pull the evidence JSON the test wrote under Android/data/com.docscanai/files/.
"%ADB%" shell "run-as %PKG% cat files/h35_owner110/owner110_realpath.json" > "%RUN%\owner110_realpath.json" 2>nul
for %%S in ("%RUN%\owner110_realpath.json") do call :hb "[%TAG%] evidence bytes=%%~zS"

call :hb "[%TAG%] lane end failed=%FAILED%"
echo LANE_DONE tag=%TAG% failed=%FAILED%
endlocal & exit /b %FAILED%

:hb
rem SUBTASK H40: the message may contain a ">" (e.g. "rc=1 -> uninstall"), which batch
rem would parse as a redirect and thereby create a junk file at the repo root. Assign it to
rem a quoted variable and echo it via DELAYED expansion, so operators are never re-parsed.
set "HBMSG=%~1"
>>"%HB%" echo [%DATE% %TIME%] !HBMSG!
echo [%DATE% %TIME%] !HBMSG!
exit /b 0
