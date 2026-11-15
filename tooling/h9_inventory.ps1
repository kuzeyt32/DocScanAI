# H9 disk hygiene: per-child size inventory for one directory (read-only).
# usage: powershell -NoProfile -ExecutionPolicy Bypass -File tooling\h9_inventory.ps1 -Dir <path>
param(
    [Parameter(Mandatory = $true)][string]$Dir
)

$ErrorActionPreference = 'Stop'
if (-not (Test-Path -LiteralPath $Dir)) { "MISSING|$Dir"; exit 0 }
$kids = Get-ChildItem -LiteralPath $Dir -Force | Sort-Object Name
$total = 0
foreach ($k in $kids) {
    if ($k.PSIsContainer) {
        $f = @(Get-ChildItem -LiteralPath $k.FullName -Recurse -Force -File -ErrorAction SilentlyContinue)
        $b = ($f | Measure-Object -Property Length -Sum).Sum
        if ($null -eq $b) { $b = 0 }
        $total += $b
        "{0}|dir|{1}|{2}" -f $k.Name, $f.Count, $b
    } else {
        $total += $k.Length
        "{0}|file|1|{1}" -f $k.Name, $k.Length
    }
}
"TOTAL|$Dir|$total"
