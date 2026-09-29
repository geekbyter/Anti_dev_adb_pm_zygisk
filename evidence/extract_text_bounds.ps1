param([Parameter(Mandatory=$true)][string]$Path, [string]$Filter = "")
$x = Get-Content -Raw -LiteralPath $Path
$nodes = [regex]::Matches($x, '<node[^>]*>')
foreach ($n in $nodes) {
    $v = $n.Value
    $tm = [regex]::Match($v, 'text="([^"]*)"')
    $cm = [regex]::Match($v, 'content-desc="([^"]*)"')
    $bm = [regex]::Match($v, 'bounds="([^"]*)"')
    $t = if ($tm.Success) { $tm.Groups[1].Value } else { "" }
    $c = if ($cm.Success) { $cm.Groups[1].Value } else { "" }
    $b = if ($bm.Success) { $bm.Groups[1].Value } else { "" }
    $label = if ($t -ne "") { $t } elseif ($c -ne "") { $c } else { $null }
    if ($null -eq $label) { continue }
    if ($Filter -ne "" -and $label -notmatch $Filter) { continue }
    "{0}`t{1}" -f $b, $label
}
