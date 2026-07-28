# build-all.ps1 -- cross-version matrix driver for M1 (Minecraft 1.20 - 26.3)
# Stage 0 scaffold: reads matrix.json and lists targets. Per-cell build invocation is
# wired in Stages 1-3 as each cell starts compiling from the single source.
param(
  [string[]]$Mc,            # filter by MC version(s), e.g. -Mc 26.2,1.21.5
  [string[]]$Loader,        # filter by loader(s): fabric forge neoforge
  [switch]$DryRun           # (default behaviour for now) list only
)
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$m=Get-Content (Join-Path $root 'matrix.json') -Raw | ConvertFrom-Json
$cells=$m.cells
if($Mc){     $cells=$cells | Where-Object { $Mc -contains $_.mc } }
if($Loader){ $cells=$cells | Where-Object { $Loader -contains $_.loader } }
Write-Host ("M1 {0}  target v{1}  ({2} cells)" -f $m.displayName,$m.modVersionTarget,$cells.Count)
foreach($c in $cells){
  $tag = if($c.unified){'UNIFIED'}else{'legacy-coords'}
  Write-Host ("  [{0,-13}] {1,-8} jdk{2}  {3}" -f $tag,$c.loader,$c.jdk,$c.mc)
}
if(-not $DryRun){ Write-Host "`n(Per-cell build wiring lands in Stages 1-3; this is the Stage 0 scaffold.)" }
