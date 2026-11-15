# H9 disk hygiene: delete superseded run-artifact folders, with hard safety rails.
# Refuses: paths outside the tooling-out root, keep-listed names, and any folder that
# contains .py / .cmd / .java / .onnx / .onnx.part (protected per the task directive).
# usage: powershell -NoProfile -ExecutionPolicy Bypass -File tooling\h9_prune.ps1 -Targets a,b,c [-DryRun] [-LogFile <path>]
param(
    [Parameter(Mandatory = $true)][string[]]$Targets,
    [switch]$DryRun,
    [string]$LogFile = 'tooling\out\h9_hygiene\progress.txt'
)

$ErrorActionPreference = 'Stop'
$root = [System.IO.Path]::GetFullPath('E:\DocScanAI-cache\tooling-out').TrimEnd('\')
$keep = @('h4_final', 'h7_final', 'h7_tests_final', 'h3b_final', 'h3a_20261016_1714', 'h4_models', 'h4_scratch', 'h9_hygiene', 'h7_tests', 'h3b_lanes')
$protectedExt = @('.py', '.cmd', '.java', '.onnx', '.ini', '.md', '.kt', '.kts')

$logAbs = if ([System.IO.Path]::IsPathRooted($LogFile)) { [System.IO.Path]::GetFullPath($LogFile) } else { [System.IO.Path]::GetFullPath((Join-Path (Get-Location) $LogFile)) }
function Write-Log([string]$msg) {
    $line = "[{0}] {1}" -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $msg
    Add-Content -LiteralPath $logAbs -Value $line -Encoding UTF8
    Write-Host $line
}

$targets = @($Targets | ForEach-Object { $_ -split ',' } | Where-Object { $_ -ne '' })
Write-Log ("PRUNE-START dryrun={0} targets={1}" -f [bool]$DryRun, ($targets -join ' ; '))
$totalBytes = 0
$totalFiles = 0

foreach ($t in $targets) {
    $p = $t
    if (-not [System.IO.Path]::IsPathRooted($p)) { $p = Join-Path $root $p }
    $p = [System.IO.Path]::GetFullPath($p).TrimEnd('\')
    $leaf = Split-Path -Leaf $p

    if (-not ($p.StartsWith($root + '\', [StringComparison]::OrdinalIgnoreCase))) {
        Write-Log ("PRUNE-REFUSE {0} : outside tooling-out root" -f $p); continue
    }
    if ($keep -contains $leaf) {
        Write-Log ("PRUNE-REFUSE {0} : keep-listed" -f $p); continue
    }
    if (-not (Test-Path -LiteralPath $p)) {
        Write-Log ("PRUNE-SKIP {0} : not present" -f $p); continue
    }

    $files = @(Get-ChildItem -LiteralPath $p -Recurse -Force -File -ErrorAction SilentlyContinue)
    $bytes = ($files | Measure-Object -Property Length -Sum).Sum
    if ($null -eq $bytes) { $bytes = 0 }
    $bad = @($files | Where-Object { $protectedExt -contains $_.Extension.ToLower() })
    if ($bad.Count -gt 0) {
        Write-Log ("PRUNE-REFUSE {0} : contains {1} protected file(s), e.g. {2}" -f $p, $bad.Count, $bad[0].Name)
        continue
    }

    if ($DryRun) {
        Write-Log ("PRUNE-DRYRUN {0} : files={1} bytes={2}" -f $p, $files.Count, $bytes)
    } else {
        Remove-Item -LiteralPath $p -Recurse -Force -ErrorAction Stop
        Write-Log ("PRUNE-DELETED {0} : files={1} bytes={2}" -f $p, $files.Count, $bytes)
    }
    $totalBytes += $bytes
    $totalFiles += $files.Count
}

Write-Log ("PRUNE-TOTAL reclaimedBytes={0} removedFiles={1} dryrun={2}" -f $totalBytes, $totalFiles, [bool]$DryRun)
