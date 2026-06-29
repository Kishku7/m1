# cog-gen.ps1 -- generate a cell's cog'd source tree for pre-26 Fabric (intermediary runtime).
#
# Reflection-by-mojmap *Compat facades work where the RUNTIME is mojmap (26+, NeoForge, Forge 1.20.1+),
# but NOT on pre-26 Fabric (intermediary). For those cells we fall through to Cog (direct compilation):
# this builds <Cell>/gen = shared_minecraft business logic + the cog-instrumented *Compat (from
# _codegen/cog_sources) generated for the cell's MC version. The cell's build.gradle srcDir's "gen".
#
#   ./cog-gen.ps1 -Cell Fabric-1.20.6 -McVer 1.20.6 -Loader fabric
param(
  [Parameter(Mandatory)][string]$Cell,
  [Parameter(Mandatory)][string]$McVer,
  [string]$Loader = 'fabric'
)
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$cgf  = ($root + '\_codegen') -replace '\\','/'      # forward slashes for cog -D (avoids the \u trap)
$gen  = Join-Path $root "$Cell\gen"

Remove-Item $gen -Recurse -Force -ErrorAction SilentlyContinue
robocopy "$root\shared_minecraft\src\main\java" $gen /E /NFL /NDL /NJH /NJS /NP | Out-Null

# overwrite the reflection *Compat with the cog-instrumented versions, then generate for this MC version
Get-ChildItem "$root\_codegen\cog_sources" -Filter *.java | ForEach-Object {
  $dst = Join-Path $gen "com\kishku7\m1\$($_.Name)"
  Copy-Item $_.FullName $dst -Force
  python -m cogapp -r -D mcver=$McVer -D loader=$Loader -D "codegen=$cgf" $dst | Out-Null
  if ($LASTEXITCODE -ne 0) { throw "cog failed on $($_.Name) for $McVer" }
}
Write-Output "cog-gen OK: $Cell (mcver=$McVer loader=$Loader) -> $gen"
