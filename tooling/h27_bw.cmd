@echo off
rem ===========================================================================================
rem  SUBTASK H27 - ITEM B lane: B&W sharpness measurement on real pages.
rem
rem  Assumes the AVD is already running (tooling\h27_boot.cmd) and the APKs are built:
rem      gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest
rem
rem  Usage:  call tooling\h27_bw.cmd BEFORE    (baseline, writes filesDir/h27_bw/before/)
rem          call tooling\h27_bw.cmd AFTER     (after the ClassicalEnhancer change; builds the
rem                                             before/after contact sheet in the same run)
rem
rem  Output: tooling\out\h27\BW_<TAG>\instrument.txt, logcat.txt, metrics.txt, contact_sheet.png
rem  Never kills the emulator (see tooling\h27_lane.cmd for the teardown form).
rem ===========================================================================================
setlocal EnableDelayedExpansion
set "HERE=%~dp0"
set "PROJ=%HERE%.."
set "SDK=C:\Users\Kuzey\AppData\Local\Android\Sdk"
set "ADB=%SDK%\platform-tools\adb.exe"
set "TAG=%~1"
if "%TAG%"=="" set "TAG=BEFORE"
rem The test writes to files/h27_bw/<tag lowercased>/.
set "LTAG=%TAG%"
if /i "%TAG%"=="BEFORE" set "LTAG=before"
if /i "%TAG%"=="AFTER" set "LTAG=after"
set "OUT=%PROJ%\tooling\out\h27\BW_%TAG%"
if not exist "%OUT%" mkdir "%OUT%"
set "HB=%PROJ%\tooling\out\h27\HEARTBEAT.txt"

call :stamp "[BW_%TAG%] lane start"
"%ADB%" get-state >nul 2>&1
if errorlevel 1 (
  echo [BW_%TAG%] no device - run tooling\h27_boot.cmd first
  exit /b 2
)

"%ADB%" install -r -t "%PROJ%\app\build\outputs\apk\debug\app-debug.apk" > "%OUT%\install_app.log" 2>&1
if errorlevel 1 (
  "%ADB%" uninstall com.docscanai >nul 2>&1
  "%ADB%" install -r -t "%PROJ%\app\build\outputs\apk\debug\app-debug.apk" >> "%OUT%\install_app.log" 2>&1
)
"%ADB%" install -r -t "%PROJ%\app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk" > "%OUT%\install_test.log" 2>&1

if exist "%PROJ%\..\dsmodels" (
  call "%PROJ%\tooling\_provision_models.cmd" > "%OUT%\provision.log" 2>&1
)
call :stamp "[BW_%TAG%] installed + provisioned"

rem Keep the BEFORE PNGs where the AFTER run can find them: app-private storage survives a
rem same-signature reinstall, so nothing has to be copied between runs.
"%ADB%" logcat -c
call :stamp "[BW_%TAG%] instrument start"
"%ADB%" shell am instrument -w -r -e tag %TAG% -e class com.docscanai.BwSharpnessTest com.docscanai.test/androidx.test.runner.AndroidJUnitRunner > "%OUT%\instrument.txt" 2>&1
set "RC=%ERRORLEVEL%"
"%ADB%" logcat -d -v brief -s DocScan > "%OUT%\logcat.txt" 2>&1

rem exec-out (not shell) so the binary PNG and the text file are not mangled in transport.
"%ADB%" exec-out run-as com.docscanai cat files/h27_bw/%LTAG%/metrics.txt > "%OUT%\metrics.txt" 2>nul
"%ADB%" exec-out run-as com.docscanai cat files/h27_bw/contact_sheet.png > "%OUT%\contact_sheet.png" 2>nul
"%ADB%" shell run-as com.docscanai ls -l files/h27_bw > "%OUT%\files.txt" 2>&1

set "VERDICT=FAIL"
findstr /c:"FAILURES!!!" "%OUT%\instrument.txt" >nul
if errorlevel 1 (
  findstr /c:"OK (" "%OUT%\instrument.txt" >nul
  if not errorlevel 1 set "VERDICT=PASS"
)
echo [BW_%TAG%]   BwSharpnessTest verdict=!VERDICT! exit=%RC%
call :stamp "[BW_%TAG%] lane end verdict=!VERDICT! rc=%RC%"
echo BW_DONE tag=%TAG% verdict=!VERDICT! rc=%RC%
endlocal & exit /b %RC%

:stamp
echo [%DATE% %TIME%] %~1 >> "%HB%"
echo [%DATE% %TIME%] %~1
exit /b 0
