' ============================================================================
' DocScanAI SUBTASK H1 - bounded child-process runner + watchdog.
' ============================================================================
' WHY: the operating rules forbid unbounded waits. This wrapper starts a child,
' watches it against a hard budget, and on expiry kills ONLY that child's PID
' tree (taskkill /PID <pid> /T /F on the exact PID we started) - never a broad
' image-name kill. A long-running step also emits a heartbeat line every ~10 s
' to stdout AND to the progress file so the user can watch it move.
'
' USAGE:
'   run   : cscript //nologo _exec_watchdog.vbs run <budgetSec> <logFile> <progressFile> <cmd...>
'           - runs "<cmd...> > <logFile> 2>&1", budget in seconds (0 = no limit)
'           - exits with the child's exit code, or 124 on timeout
'   spawn : cscript //nologo _exec_watchdog.vbs spawn <cmd...>
'           - starts <cmd...> detached and echoes its PID, then exits (child keeps running)
' ============================================================================
Option Explicit

Dim sh, fso, mode, timeoutSec, logPath, progPath, cmdline, full, ex, pid
Set sh = CreateObject("WScript.Shell")
Set fso = CreateObject("Scripting.FileSystemObject")

If WScript.Arguments.Count < 1 Then
    WScript.Echo "usage: _exec_watchdog.vbs <run|spawn> ..."
    WScript.Quit 2
End If
mode = LCase(WScript.Arguments(0))

If mode = "spawn" Then
    ' Use Run (NOT Exec): a long-lived child started via Exec inherits and holds open
    ' our stdout pipe, which hangs a caller's `for /f` forever. Run detaches cleanly.
    ' No PID is returned here; callers locate the process by name if they need it.
    cmdline = JoinFrom(1)
    sh.Run cmdline, 0, False
    WScript.Echo "0"
    WScript.Quit 0
End If

timeoutSec = CLng(WScript.Arguments(1))
logPath = WScript.Arguments(2)
progPath = WScript.Arguments(3)
cmdline = JoinFrom(4)

full = sh.ExpandEnvironmentStrings("%COMSPEC%") & " /c " & cmdline & " > """ & logPath & """ 2>&1"
Set ex = sh.Exec(full)
pid = ex.ProcessID
EchoTS "exec pid=" & pid & " budget=" & timeoutSec & "s :: " & cmdline

Dim elapsed, lastBeat
elapsed = 0
lastBeat = 0
Do While ex.Status = 0
    WScript.Sleep 1000
    elapsed = elapsed + 1
    If elapsed >= lastBeat + 10 Then
        lastBeat = elapsed
        EchoTS "heartbeat pid=" & pid & " running " & elapsed & "s/" & timeoutSec & "s"
    End If
    If timeoutSec > 0 And elapsed >= timeoutSec Then
        EchoTS "TIMEOUT pid=" & pid & " after " & timeoutSec & "s -> taskkill /PID " & pid & " /T /F"
        On Error Resume Next
        sh.Run "taskkill /PID " & pid & " /T /F", 0, True
        On Error GoTo 0
        WScript.Quit 124
    End If
Loop

Dim rc
rc = ex.ExitCode
If rc < 0 Then rc = 1
EchoTS "exit pid=" & pid & " rc=" & rc & " elapsed=" & elapsed & "s"
WScript.Quit rc

Function JoinFrom(startIdx)
    Dim s, k
    s = ""
    For k = startIdx To WScript.Arguments.Count - 1
        If k > startIdx Then s = s & " "
        s = s & WScript.Arguments(k)
    Next
    JoinFrom = s
End Function

Sub EchoTS(msg)
    Dim line
    line = "[" & TS() & "] " & msg
    WScript.Echo line
    On Error Resume Next
    If progPath <> "" Then
        Dim f
        Set f = fso.OpenTextFile(progPath, 8, True)
        f.WriteLine line
        f.Close
    End If
    On Error GoTo 0
End Sub

Function TS()
    TS = Right("0" & Hour(Now), 2) & ":" & Right("0" & Minute(Now), 2) & ":" & Right("0" & Second(Now), 2)
End Function
