@echo off
rem ============================================================================
rem DocScanAI - no-space launcher for the pinned JDK 21.
rem
rem `_exec_watchdog.vbs` joins its argv into a single command line and the
rem quoting around "C:\Program Files\Java\..." is not preserved, so calling the
rem JVM by its full path through the watchdog breaks at the space. This tiny
rem wrapper lives under tooling\ (no spaces in its path) and quotes JAVA_HOME
rem itself at run time, so the watchdog can invoke it safely.
rem
rem Usage:  call "%~dp0_java.cmd" <single-file source> [args...]
rem ============================================================================
setlocal enableextensions
if not defined JAVA_HOME set "JAVA_HOME=C:\Program Files\Java\jdk-21"
set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
if not exist "%JAVA_EXE%" (
    echo [_java] FATAL: java.exe not found at "%JAVA_EXE%" 1>&2
    endlocal & exit /b 3
)
"%JAVA_EXE%" %*
set "RC=%ERRORLEVEL%"
endlocal & exit /b %RC%
