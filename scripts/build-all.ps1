# build-all.ps1 -- matrix LISTER for M1 (Minecraft 1.20 - 26.3).
#
# This is NOT a build driver. The canonical per-loader drivers are:
#   scripts\build-all-fabric-cog.ps1     pre-26 Fabric cells      (-Only <cell>)
#   scripts\build-all-fabric.ps1         Fabric/26 multi-target   (-Versions 26.1,26.2,26.3)
#   scripts\build-all-forge-cells.ps1    Forge cells              (-Only <cell>)
#   scripts\build-all-neoforge-cells.ps1 pre-26 NeoForge cells    (-Only <cell>)
#   scripts\build-all-neoforge.ps1       NeoForge/26 multi-target (-Versions 26.1,26.2)
#
# It reads matrix.json (regenerated from disk 2026-08-25) and prints the target list, so the
# matrix can be inspected without opening the JSON. Filters: -Mc, -Loader.
param(
  [string[]]$Mc,            # filter by MC version(s), e.g. -Mc 26.2,1.21.5
  [string[]]$Loader         # filter by loader(s): fabric forge neoforge
)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$m = Get-Content (Join-Path $root 'matrix.json') -Raw | ConvertFrom-Json

# flatten cells + the 26 cells' extra build targets into one target list
$targets = foreach ($c in $m.cells) {
  [pscustomobject]@{
    Loader  = $c.loader
    Mc      = $c.mc
    Jdk     = $c.jdk
    CellDir = if ($c.cellDir) { $c.cellDir } else { "{0}/{1}" -f ((Get-Culture).TextInfo.ToTitleCase($c.loader) -replace 'Neoforge','NeoForge'), $c.mc }
    Shape   = $c.shape
    Range   = if ($c.mc_dep) { $c.mc_dep } else { $c.mc_range }
    Watch   = $c.watch
  }
}
$targets += foreach ($e in $m.extraTargets) {
  $owner = $m.cells | Where-Object { $_.cellDir -eq $e.cellDir } | Select-Object -First 1
  [pscustomobject]@{
    Loader  = $owner.loader
    Mc      = $e.mc
    Jdk     = $owner.jdk
    CellDir = $e.cellDir
    Shape   = $owner.shape
    Range   = if ($e.mc_dep) { $e.mc_dep } else { $e.mc_range }
    Watch   = $e.watch
  }
}

if ($Mc)     { $targets = $targets | Where-Object { $Mc -contains $_.Mc } }
if ($Loader) { $targets = $targets | Where-Object { $Loader -contains $_.Loader } }

Write-Host ("M1 {0}  v{1}  ({2} of {3} targets shown; {4} cell dirs)" -f `
  $m.displayName, $m.modVersion, @($targets).Count, $m.targetCount, $m.cellCount)
Write-Host ""
foreach ($t in ($targets | Sort-Object Loader, Mc)) {
  $flag = if ($t.Watch) { "  <- $($t.Watch)" } else { '' }
  Write-Host ("  {0,-9} {1,-16} jdk{2,-3} {3,-16} {4,-30}{5}" -f `
    $t.Loader, $t.Mc, $t.Jdk, $t.Shape, $t.Range, $flag)
}
Write-Host ""
if ($m.watch)      { Write-Host "WATCH (declared but unverified):"; foreach ($w in $m.watch) { Write-Host ("  {0}  {1}" -f $w.id, ($w.cells -join ', ')) } }
if ($m.deadCells)  { Write-Host ""; Write-Host "DEAD CELLS (no mod can fix):"; foreach ($d in $m.deadCells) { Write-Host "  $d" } }
Write-Host ""
Write-Host "Structural gaps are in matrix.json .structuralGaps; full detail in MATRIX.md."
