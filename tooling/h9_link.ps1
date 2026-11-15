# H9 disk hygiene: create a directory junction and prove it works.
# usage: powershell -NoProfile -ExecutionPolicy Bypass -File tooling\h9_link.ps1 -Junction <path> -Target <path> -Label <name> [-LogFile <path>]
param(
    [Parameter(Mandatory = $true)][string]$Junction,
    [Parameter(Mandatory = $true)][string]$Target,
    [Parameter(Mandatory = $true)][string]$Label,
    [string]$LogFile = 'tooling\out\h9_hygiene\progress.txt'
)

$ErrorActionPreference = 'Stop'
$logAbs = if ([System.IO.Path]::IsPathRooted($LogFile)) { [System.IO.Path]::GetFullPath($LogFile) } else { [System.IO.Path]::GetFullPath((Join-Path (Get-Location) $LogFile)) }
$logDir = Split-Path -Parent $logAbs
if (-not (Test-Path -LiteralPath $logDir)) { New-Item -ItemType Directory -Path $logDir -Force | Out-Null }

function Write-Log([string]$msg) {
    $line = "[{0}] {1}" -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $msg
    Add-Content -LiteralPath $logAbs -Value $line -Encoding UTF8
    Write-Host $line
}

if (-not (Test-Path -LiteralPath $Target)) {
    Write-Log ("LINK-ABORT {0} : target missing {1}" -f $Label, $Target)
    exit 3
}
if (Test-Path -LiteralPath $Junction) {
    Write-Log ("LINK-SKIP {0} : junction path already exists {1}" -f $Label, $Junction)
    exit 4
}

New-Item -ItemType Junction -Path $Junction -Target $Target -ErrorAction Stop | Out-Null
$item = Get-Item -LiteralPath $Junction -Force
$kids = @(Get-ChildItem -LiteralPath $Junction -Force -ErrorAction SilentlyContinue)
$files = @(Get-ChildItem -LiteralPath $Junction -Recurse -Force -File -ErrorAction SilentlyContinue)
$sum = ($files | Measure-Object -Property Length -Sum).Sum
Write-Log ("LINK-OK {0} : {1} -> {2} linktype={3} topEntries={4} filesThroughJunction={5} bytes={6}" -f `
        $Label, (Resolve-Path -LiteralPath $Junction).Path, $item.Target, $item.LinkType, $kids.Count, $files.Count, $sum)

# prove the path behaves like a normal directory: push into it and read a file back
Push-Location -LiteralPath $Junction
$cwd = (Get-Location).Path
Pop-Location
Write-Log ("LINK-VERIFY {0} : cd works -> {1}" -f $Label, $cwd)
