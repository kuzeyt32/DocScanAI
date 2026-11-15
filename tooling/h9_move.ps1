# H9 disk hygiene: relocate one regenerable tree to E: and leave a junction behind.
# usage: powershell -NoProfile -ExecutionPolicy Bypass -File tooling\h9_move.ps1 -Src <path> -Dst <path> -Junction <path> -Label <name> [-LogFile <path>]
# Guarantees: junction is created ONLY when robocopy rc < 8 and the source is fully drained.
param(
    [Parameter(Mandatory = $true)][string]$Src,
    [Parameter(Mandatory = $true)][string]$Dst,
    [Parameter(Mandatory = $true)][string]$Junction,
    [Parameter(Mandatory = $true)][string]$Label,
    [string]$LogFile = 'tooling\out\h9_hygiene\progress.txt'
)

$ErrorActionPreference = 'Stop'
$rootEx = 'E:\DocScanAI-cache'
$hbDir = Join-Path $rootEx '_h9_hb'
if (-not (Test-Path -LiteralPath $hbDir)) { New-Item -ItemType Directory -Path $hbDir -Force | Out-Null }
$logAbs = if ([System.IO.Path]::IsPathRooted($LogFile)) { [System.IO.Path]::GetFullPath($LogFile) } else { [System.IO.Path]::GetFullPath((Join-Path (Get-Location) $LogFile)) }
$logDir = Split-Path -Parent $logAbs
if (-not (Test-Path -LiteralPath $logDir)) { New-Item -ItemType Directory -Path $logDir -Force | Out-Null }

function Write-Log([string]$msg) {
    $line = "[{0}] {1}" -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $msg
    Add-Content -LiteralPath $logAbs -Value $line -Encoding UTF8
    Write-Host $line
}

$hb = Start-Job -ScriptBlock {
    param($lbl, $lf)
    while ($true) {
        Start-Sleep -Seconds 10
        try { Add-Content -LiteralPath $lf -Value ("[{0}] heartbeat {1} alive" -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $lbl) -Encoding UTF8 } catch { }
    }
} -ArgumentList $Label, $logAbs

try {
    Write-Log ("MOVE-START {0} : {1} -> {2}" -f $Label, $Src, $Dst)
    $rcLog = Join-Path $hbDir ($Label + '_robocopy.log')
    & robocopy $Src $Dst /E /MOVE /R:1 /W:1 /NFL /NDL /NP /XJ *> $rcLog
    $rc = $LASTEXITCODE
    Write-Log ("MOVE-RC {0} = {1}" -f $Label, $rc)

    if ($rc -ge 8) {
        Write-Log ("MOVE-FAILED {0} rc={1} -- NO junction created" -f $Label, $rc)
        Get-Content -LiteralPath $rcLog -ErrorAction SilentlyContinue | Select-Object -Last 15 |
            ForEach-Object { Add-Content -LiteralPath $logAbs -Value ("  rc> " + $_) -Encoding UTF8 }
        exit $rc
    }

    if (Test-Path -LiteralPath $Src) {
        $leftover = @(Get-ChildItem -LiteralPath $Src -Recurse -Force -ErrorAction SilentlyContinue | Select-Object -First 1)
        if ($leftover.Count -gt 0) {
            Write-Log ("SRC-REMAINS {0} -- residue present, NO junction created" -f $Label)
            exit 20
        }
        Remove-Item -LiteralPath $Src -Force -ErrorAction Stop
        Write-Log ("SRC-EMPTY-REMOVED {0}" -f $Label)
    }

    if (-not (Test-Path -LiteralPath $Dst)) {
        Write-Log ("DST-MISSING {0} -- NO junction created" -f $Label)
        exit 21
    }

    New-Item -ItemType Junction -Path $Junction -Target $Dst -ErrorAction Stop | Out-Null
    $item = Get-Item -LiteralPath $Junction -Force
    Write-Log ("JUNCTION {0} : {1} -> {2} (linktype={3})" -f $Label, (Resolve-Path -LiteralPath $Junction).Path, $item.Target, $item.LinkType)
}
finally {
    Stop-Job -Job $hb -ErrorAction SilentlyContinue
    Remove-Job -Job $hb -Force -ErrorAction SilentlyContinue
}
