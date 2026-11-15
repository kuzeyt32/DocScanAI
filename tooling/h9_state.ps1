# H9 disk hygiene: state probe. Prints size/file-count for a path and whether it is a junction.
param(
    [string[]]$Paths = @(),
    [switch]$Free
)

# cmd.exe hands "-Paths a,b" over as one literal token, so split it back into a list.
$Paths = @($Paths | ForEach-Object { $_ -split ',' } | Where-Object { $_ -ne '' })

function Get-TreeStats([string]$p) {
    $abs = $null
    try { $abs = (Resolve-Path -LiteralPath $p -ErrorAction Stop).Path } catch { return $null }
    $item = Get-Item -LiteralPath $abs -Force
    $isLink = ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0
    if (-not $item.PSIsContainer) {
        return [pscustomobject]@{ Path = $p; Kind = 'file'; Files = 1; Bytes = $item.Length; Link = "$isLink" }
    }
    if ($isLink) {
        return [pscustomobject]@{ Path = $p; Kind = 'junction'; Files = '-'; Bytes = '-'; Link = "True -> $($item.Target)" }
    }
    $m = Get-ChildItem -LiteralPath $abs -Recurse -Force -File -ErrorAction SilentlyContinue |
         Measure-Object -Property Length -Sum
    return [pscustomobject]@{ Path = $p; Kind = 'dir'; Files = $m.Count; Bytes = $m.Sum; Link = 'False' }
}

$rows = @()
foreach ($p in $Paths) {
    $s = Get-TreeStats $p
    if ($null -eq $s) {
        $rows += [pscustomobject]@{ Path = $p; Kind = 'MISSING'; Files = '-'; Bytes = '-'; Link = '-' }
    } else {
        $rows += $s
    }
}
if ($rows.Count -gt 0) {
    $rows | ForEach-Object {
        "{0}|{1}|{2}|{3}|{4}" -f $_.Path, $_.Kind, $_.Files, $_.Bytes, $_.Link
    }
}
if ($Free) {
    Get-PSDrive C, E | ForEach-Object {
        "DRIVE|{0}|FreeGB={1}|UsedGB={2}" -f $_.Name, [math]::Round($_.Free / 1GB, 2), [math]::Round($_.Used / 1GB, 2)
    }
}
