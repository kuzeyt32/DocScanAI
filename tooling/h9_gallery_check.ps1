# H9 disk hygiene: prove gallery/index.html still resolves every relative src/href after
# the gallery directory became a junction. Read-only.
# usage: powershell -NoProfile -ExecutionPolicy Bypass -File tooling/h9_gallery_check.ps1 [-Dir gallery]
param(
    [string]$Dir = 'gallery'
)

$ErrorActionPreference = 'Stop'
$index = Join-Path $Dir 'index.html'
if (-not (Test-Path -LiteralPath $index)) { "INDEX_MISSING|$index"; exit 1 }

$html = Get-Content -LiteralPath $index -Raw
$refs = [regex]::Matches($html, '(?:src|href)="([^"]+)"')
$total = 0
$missing = 0
$seen = @{}
foreach ($r in $refs) {
    $p = $r.Groups[1].Value
    if ($p -match '^(https?:|#|mailto:|data:)') { continue }
    $p = ($p -split '\?')[0]
    if ($seen.ContainsKey($p)) { continue }
    $seen[$p] = $true
    $total++
    $full = Join-Path $Dir $p
    if (-not (Test-Path -LiteralPath $full)) {
        $missing++
        if ($missing -le 10) { "MISSING|$p" }
    }
}
"INDEX_BYTES|$((Get-Item -LiteralPath $index).Length)"
"REFS_UNIQUE|$total"
"REFS_MISSING|$missing"
if ($missing -eq 0) { "GALLERY_REFS_OK" } else { "GALLERY_REFS_BROKEN" }
