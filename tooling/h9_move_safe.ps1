# H9 disk hygiene: SAFE relocation for trees that may have live handles (e.g. the Android SDK).
# Order: copy -> verify identical -> rename source -> junction -> verify -> delete the old duplicate.
# The source is never deleted before the junction is proven to work.
# usage: powershell -NoProfile -ExecutionPolicy Bypass -File tooling\h9_move_safe.ps1 -Src <path> -Dst <path> -Label <name> [-LogFile <path>]
param(
    [Parameter(Mandatory = $true)][string]$Src,
    [Parameter(Mandatory = $true)][string]$Dst,
    [Parameter(Mandatory = $true)][string]$Label,
    [string]$LogFile = 'tooling\out\h9_hygiene\progress.txt'
)

$ErrorActionPreference = 'Stop'
$hbDir = 'E:\DocScanAI-cache\_h9_hb'
if (-not (Test-Path -LiteralPath $hbDir)) { New-Item -ItemType Directory -Path $hbDir -Force | Out-Null }
$logAbs = if ([System.IO.Path]::IsPathRooted($LogFile)) { [System.IO.Path]::GetFullPath($LogFile) } else { [System.IO.Path]::GetFullPath((Join-Path (Get-Location) $LogFile)) }
$logDir = Split-Path -Parent $logAbs
if (-not (Test-Path -LiteralPath $logDir)) { New-Item -ItemType Directory -Path $logDir -Force | Out-Null }

function Write-Log([string]$msg) {
    $line = "[{0}] {1}" -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $msg
    Add-Content -LiteralPath $logAbs -Value $line -Encoding UTF8
    Write-Host $line
}

function Get-Stats([string]$p) {
    $f = @(Get-ChildItem -LiteralPath $p -Recurse -Force -File -ErrorAction SilentlyContinue)
    $b = ($f | Measure-Object -Property Length -Sum).Sum
    if ($null -eq $b) { $b = 0 }
    [pscustomobject]@{ Files = $f.Count; Bytes = $b }
}

$Src = (Resolve-Path -LiteralPath $Src -ErrorAction Stop).Path
$Src = $Src.TrimEnd('\')
$old = $Src + '.old_h9'
$hb = Start-Job -ScriptBlock {
    param($lbl, $lf)
    while ($true) {
        Start-Sleep -Seconds 10
        try { Add-Content -LiteralPath $lf -Value ("[{0}] heartbeat {1} alive" -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $lbl) -Encoding UTF8 } catch { }
    }
} -ArgumentList $Label, $logAbs

try {
    $before = Get-Stats $Src
    Write-Log ("SAFE-START {0} : {1} -> {2} (files={3} bytes={4})" -f $Label, $Src, $Dst, $before.Files, $before.Bytes)

    & robocopy $Src $Dst /E /R:1 /W:1 /NFL /NDL /NP /XJ *> (Join-Path $hbDir ($Label + '_copy.log'))
    $rc = $LASTEXITCODE
    Write-Log ("SAFE-COPY-RC {0} = {1}" -f $Label, $rc)
    if ($rc -ge 8) { Write-Log ("SAFE-ABORT {0} : copy failed, source untouched" -f $Label); exit $rc }

    $after = Get-Stats $Dst
    Write-Log ("SAFE-COPY-VERIFY {0} : files {1}->{2} bytes {3}->{4}" -f $Label, $before.Files, $after.Files, $before.Bytes, $after.Bytes)
    if ($before.Files -ne $after.Files -or $before.Bytes -ne $after.Bytes) {
        Write-Log ("SAFE-ABORT {0} : copy does not match source, source untouched" -f $Label)
        exit 22
    }

    Rename-Item -LiteralPath $Src -NewName (Split-Path -Leaf $old) -ErrorAction Stop
    Write-Log ("SAFE-RENAMED {0} : {1} -> {2}" -f $Label, $Src, $old)

    try {
        New-Item -ItemType Junction -Path $Src -Target $Dst -ErrorAction Stop | Out-Null
    } catch {
        Rename-Item -LiteralPath $old -NewName (Split-Path -Leaf $Src) -ErrorAction Stop
        Write-Log ("SAFE-ABORT {0} : junction failed, source restored" -f $Label)
        exit 23
    }

    $j = Get-Item -LiteralPath $Src -Force
    $through = Get-Stats $Src
    Write-Log ("SAFE-JUNCTION {0} : {1} -> {2} linktype={3} filesThrough={4} bytesThrough={5}" -f `
            $Label, $Src, ($j.Target -join ','), $j.LinkType, $through.Files, $through.Bytes)

    if ($through.Files -ne $before.Files -or $through.Bytes -ne $before.Bytes) {
        Write-Log ("SAFE-WARN {0} : counts through junction differ from original - keeping old copy at {1}" -f $Label, $old)
        exit 24
    }

    try {
        Remove-Item -LiteralPath $old -Recurse -Force -ErrorAction Stop
        Write-Log ("SAFE-OLD-REMOVED {0} : deleted verified duplicate {1} ({2} bytes)" -f $Label, $old, $before.Bytes)
    } catch {
        Write-Log ("SAFE-OLD-KEPT {0} : could not delete {1} - {2}" -f $Label, $old, $_.Exception.Message)
    }
}
finally {
    Stop-Job -Job $hb -ErrorAction SilentlyContinue
    Remove-Job -Job $hb -Force -ErrorAction SilentlyContinue
}
