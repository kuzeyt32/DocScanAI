@echo off
rem ============================================================================================
rem SUBTASK H25 — measurement lane for the pencil/handwriting attempt-2 candidate.
rem
rem Runs the ONE candidate (HandwritingEnhancer.Variant.RIDGE) on the 12 real public-domain
rem pencil pages plus the normal corpus pages, using exactly the H23 masks and metrics, and pulls
rem the per-page JSON + before/after pairs into tooling\out\h25\device.
rem
rem Assumes the emulator is already booted and the APKs are built:
rem   gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest
rem This script never starts or kills the emulator; it only uses the attached device.
rem ============================================================================================
setlocal
set "ADB=C:\Users\Kuzey\AppData\Local\Android\Sdk\platform-tools\adb.exe"
set "ROOT=%~dp0"
set "RUN=%ROOT%out\h25"
set "PKG=com.docscanai"
set "CLS=com.docscanai.HandwritingEnhanceTest"
set "INSTR=com.docscanai.test/androidx.test.runner.AndroidJUnitRunner"

if not exist "%RUN%" mkdir "%RUN%"
rem Pull target is a fresh directory per run: `adb pull` nests when the destination exists, and this
rem lane never deletes anything (no rmdir/del of previous evidence).
set "STAMP=%DATE:~-4%%DATE:~3,2%%DATE:~0,2%_%TIME:~0,2%%TIME:~3,2%"
set "STAMP=%STAMP: =0%"
set "DEVDIR=%RUN%\device_%STAMP%"
echo [%DATE% %TIME%] lane start > "%RUN%\HEARTBEAT.txt"

echo [1/5] install debug + androidTest
"%ADB%" install -r -t "%ROOT%..\app\build\outputs\apk\debug\app-debug.apk" > "%RUN%\install_app.txt" 2>&1
"%ADB%" install -r -t "%ROOT%..\app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk" > "%RUN%\install_test.txt" 2>&1
echo [%DATE% %TIME%] installed >> "%RUN%\HEARTBEAT.txt"

echo [2/5] clear logcat and run %CLS%
"%ADB%" logcat -c
"%ADB%" shell am instrument -w -r -e class %CLS% %INSTR% > "%RUN%\instr_run.txt" 2>&1
echo [%DATE% %TIME%] instrument done >> "%RUN%\HEARTBEAT.txt"

echo [3/5] capture logcat
"%ADB%" logcat -d -v brief -s DocScan > "%RUN%\logcat_run.txt" 2>&1

echo [4/5] pull per-page results into %DEVDIR%
"%ADB%" pull /storage/emulated/0/Android/data/%PKG%/files/h25 "%DEVDIR%" > "%RUN%\pull.txt" 2>&1

echo [5/5] verdict lines
findstr /i "H25_ OK ( FAILURES" "%RUN%\instr_run.txt" "%RUN%\logcat_run.txt" > "%RUN%\verdict.txt" 2>&1
type "%RUN%\verdict.txt"
echo [%DATE% %TIME%] lane end >> "%RUN%\HEARTBEAT.txt"
endlocal
