' DocScanAI SUBTASK H1 - non-destructive process query helper.
' wmic is removed on this Windows 11 build; this prints "<pid> <name>" for every
' process whose NAME contains the (lowercased) filter, so the agent can identify
' and kill ONLY its own leftovers by exact PID.
' Usage: cscript //nologo tooling\_procquery.vbs [namefilter]
Option Explicit
Dim wmi, procs, p, needle
needle = ""
If WScript.Arguments.Count > 0 Then needle = LCase(WScript.Arguments(0))
Set wmi = GetObject("winmgmts:\\.\root\cimv2")
Set procs = wmi.ExecQuery("SELECT ProcessId,Name FROM Win32_Process")
For Each p In procs
    If needle = "" Then
        WScript.Echo p.ProcessId & " " & p.Name
    ElseIf InStr(LCase(p.Name), needle) > 0 Then
        WScript.Echo p.ProcessId & " " & p.Name
    End If
Next
