param([Parameter(Mandatory=$true)][string]$Path)
$x = Get-Content -Raw -LiteralPath $Path
[regex]::Matches($x, '(?:text|content-desc)="([^"]+)"') |
    ForEach-Object { $_.Groups[1].Value } |
    Sort-Object -Unique
