@echo off
rem ============================================================================
rem DocScan AI e2e harness - background logcat capture helper (SUBTASK B / H1).
rem
rem Launched in the background by `e2e.cmd` (via `start`) so the live logcat is
rem captured while the instrumented tests run. It streams `adb logcat`, keeps only
rem the lines relevant to the AI-ON crash investigation, and writes them to the
rem output file passed as %1:
rem   * the stable `DocScan` diagnostic tag (all pipeline stages + EP selection);
rem   * the native-crash signatures (Fatal signal / SIGSEGV / SIGABRT / signal
rem     11 / signal 6) and the ORT/OpenCV/XNNPACK native library names;
rem   * `AndroidRuntime` (Java FATAL EXCEPTION headers).
rem
rem Usage: capture_logcat.cmd "<output file>" [adb-serial]
rem
rem NOTE: adb logcat can only filter by tag/priority, never by message text, so
rem the filtering is done here by piping through `findstr` (multiple /C: terms
rem are OR-matched). This is exactly why the harness uses a helper script.
rem ============================================================================
setlocal
set "OUT=%~1"
if "%~2"=="" (set "SER=") else (set "SER=-s %~2")
if "%ANDROID_HOME%"=="" set "ANDROID_HOME=C:\Users\Kuzey\AppData\Local\Android\Sdk"
set "ADB=%ANDROID_HOME%\platform-tools\adb.exe"

"%ADB%" %SER% logcat -v threadtime | findstr /C:"DocScan" /C:"Fatal signal" /C:"SIGSEGV" /C:"SIGABRT" /C:"signal 11" /C:"signal 6" /C:"libonnxruntime" /C:"libonnxruntime4j_jni" /C:"libopencv_java4" /C:"XNNPACK" /C:"AndroidRuntime" > "%OUT%"

endlocal
