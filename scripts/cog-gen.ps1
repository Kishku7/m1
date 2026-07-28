# cog-gen.ps1 -- generate a cell's cog'd source tree for M1.
#
# D16 full-cog topology: every cell is identical shape. There is ONE source of truth --
# _codegen/cog_sources. gen/ is the ONLY java srcDir a cell build.gradle references.
#
#   gen/ = _codegen/cog_sources/shared (the invariant java: shared_minecraft's 53 non-Compat
#          classes + shared_common's AiBrain + agent/*) copied verbatim, PLUS the 8 cog-direct
#          *Compat facades and Queries.java (from _codegen/cog_sources), each cog-instrumented
#          DIRECT for this cell's MC version. No reflection *Compat remain; every cell is full-cog.
#
#   D15 entrypoints: the per-cell loader entrypoints (Fabric M1Main + client/M1Client,
#          Forge/NeoForge M1Forge/M1NeoForge) are NO LONGER hand-copied under each cell's
#          src/main/java. They are single-sourced as byte-identical masters in
#          _codegen/cog_sources/entrypoints and materialized into gen/ here, selected by
#          loader + MC version. Masters carry no [[[cog]]] directives, so the copy is
#          byte-for-byte identical to the historical per-cell file (no cogapp pass needed).
#
#   shared_minecraft + shared_common no longer exist. AI_Brain resources live at
#   _codegen/cog_sources/shared_resources (wired via build.gradle resources.srcDir).
#
#   ./cog-gen.ps1 -Cell Fabric/1.20.6 -McVer 1.20.6 -Loader fabric
param(
  [Parameter(Mandatory)][string]$Cell,
  [Parameter(Mandatory)][string]$McVer,
  [string]$Loader = 'fabric'
)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$cgf  = ($root + '\_codegen') -replace '\\','/'      # forward slashes for cog -D (avoids the \u trap)
$gen  = Join-Path $root "$Cell\gen"

Remove-Item $gen -Recurse -Force -ErrorAction SilentlyContinue

# invariant java (MC-version-agnostic): copy verbatim from the single cog_sources/shared tree
robocopy "$root\_codegen\cog_sources\shared" $gen /E /NFL /NDL /NJH /NJS /NP | Out-Null
# add the cog-direct *Compat facades + Queries, generate DIRECT for this MC version
Get-ChildItem "$root\_codegen\cog_sources" -Filter *.java | ForEach-Object {
  $dst = Join-Path $gen "com\kishku7\m1\$($_.Name)"
  Copy-Item $_.FullName $dst -Force
  python -m cogapp -r -D mcver=$McVer -D loader=$Loader -D "codegen=$cgf" $dst | Out-Null
  if ($LASTEXITCODE -ne 0) { throw "cog failed on $($_.Name) for $McVer" }
}

# --- D15: single-source loader entrypoints ----------------------------------
# Materialize the correct entrypoint master(s) into gen/ based on $Loader + $McVer.
# Plain copies (masters have no cog directives) => byte-identical to the old per-cell files.
# The McVer->shape boundaries below are the EXACT groupings the historical files hashed into;
# any McVer that matches no shape is a hard error (fail loud rather than emit a wrong entrypoint).
$ep = Join-Path $root '_codegen\cog_sources\entrypoints'
function Copy-Entrypoint([string]$Master, [string]$RelDst) {
  $srcM = Join-Path $ep $Master
  if (-not (Test-Path $srcM)) { throw "entrypoint master missing: $Master" }
  $dstF = Join-Path $gen $RelDst
  New-Item -ItemType Directory -Force -Path (Split-Path $dstF -Parent) | Out-Null
  Copy-Item $srcM $dstF -Force
}
$is26 = $McVer -like '26*'    # the 26 family reports McVer like 26.1.2 / 26.3-snapshot-4
switch ($Loader) {
  'fabric' {
    # M1Main: ONE shape across all Fabric cells.
    Copy-Entrypoint 'M1Main.fabric.java' 'com\kishku7\m1\M1Main.java'
    # M1Client: TWO shapes -- pre-26 vs 26.
    $cm = if ($is26) { 'M1Client.fabric_26.java' } else { 'M1Client.fabric_pre26.java' }
    Copy-Entrypoint $cm 'com\kishku7\m1\client\M1Client.java'
  }
  'forge' {
    # M1Forge: FOUR shapes across the Forge cells.
    $fm = switch ($McVer) {
      { $_ -in '1.20.1','1.20.2','1.20.4' }            { 'M1Forge.forge_srg.java';       break }  # SRG-runtime
      '1.20.6'                                          { 'M1Forge.forge_1206.java';       break }
      { $_ -in '1.21','1.21.4','1.21.5' }              { 'M1Forge.forge_eventbus.java';   break }  # eventbus
      { $_ -in '1.21.6','1.21.7','1.21.8','1.21.10','1.21.11' } { 'M1Forge.forge_eventbus7.java';  break }  # eventbus (1.21.6+); 1.21.7 added 2026-07-27 -- both neighbours (1.21.6 forge 56, 1.21.8 forge 58) use this shape
      default { throw "no Forge M1Forge shape for McVer=$McVer" }
    }
    Copy-Entrypoint $fm 'com\kishku7\m1\forge\M1Forge.java'
  }
  'neoforge' {
    if ($McVer -eq '1.20.1') {
      # NeoForge/1.20.1 is the forge-fork cell: it ships M1Forge (its own unique shape), not M1NeoForge.
      Copy-Entrypoint 'M1Forge.neoforge_fork.java' 'com\kishku7\m1\forge\M1Forge.java'
    } else {
      # M1NeoForge: FOUR shapes across the remaining NeoForge cells.
      $nm = switch ($McVer) {
        { $_ -in '1.20.2','1.20.3','1.20.4' }                  { 'M1NeoForge.neoforge_early.java';  break }
        { $_ -in '1.20.6','1.21','1.21.2','1.21.5','1.21.8' } { 'M1NeoForge.neoforge_modern.java'; break }
        '1.21.11'                                              { 'M1NeoForge.neoforge_1211.java';   break }
        { $_ -like '26*' }                                     { 'M1NeoForge.neoforge_26.java';     break }
        default { throw "no NeoForge M1NeoForge shape for McVer=$McVer" }
      }
      Copy-Entrypoint $nm 'com\kishku7\m1\neoforge\M1NeoForge.java'
    }
  }
  default { throw "unknown loader: $Loader" }
}

Write-Output "cog-gen OK (full-cog): $Cell (mcver=$McVer loader=$Loader) -> $gen"
