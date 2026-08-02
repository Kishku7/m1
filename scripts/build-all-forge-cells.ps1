# build-all-forge-cells.ps1 -- build the Forge cells (FG6) from the shared source.
#
# Most Forge cells (1.21.1+) run a MOJMAP runtime, so the reflection *Compat facades resolve at runtime --
# a straight gradle build from shared_minecraft. EXCEPTION (2026-07-04): Forge 47.x / MC 1.20.1 runs SRG
# (f_xxxxx_/m_xxxxx_) names at RUNTIME, so reflection-by-mojmap MISSES (M1Compat.screen() returned null).
# That cell falls through to Cog/direct and its build.gradle srcDir's "gen". So: for ANY cell whose
# build.gradle srcDir's "gen", regenerate its cog tree first (cog-gen.ps1 -Loader forge) before building.
# Each Forge/<ver> cell is a thin FG6 project + per-version M1Forge entrypoint (TickEvent through 1.21.5;
# EventBus-7 ClientTickEvent.Post.BUS at 1.21.8+). Forge/FG6 ceiling = 1.21.11 (forge 61.x). Walks
# Forge/<ver>, builds each, records PASS/FAIL + warning count.
param([string[]]$Only)
$ErrorActionPreference = 'Continue'
$root = Split-Path $PSScriptRoot -Parent
$status = Join-Path $env:TEMP 'm1_forge_cells_status.txt'
# Keep dist/ to the current version only (see dist-prune.ps1 -- prunes BY VERSION, so drivers
# in the same sweep do not delete each other's jars).
& "$PSScriptRoot\dist-prune.ps1"

"=== m1 Forge cell build  $(Get-Date -Format s) ===" | Set-Content $status

$cells = Get-ChildItem (Join-Path $root 'Forge') -Directory | Sort-Object Name
if ($Only) { $cells = $cells | Where-Object { $Only -contains $_.Name } }

foreach ($cell in $cells) {
  $name = $cell.Name
  $dir  = $cell.FullName
  $mv   = (Select-String -Path "$dir\gradle.properties" -Pattern '^minecraft_version\s*=\s*(.+)$').Matches.Groups[1].Value.Trim()
  $sw   = [Diagnostics.Stopwatch]::StartNew()
  try {
    $blog = Join-Path $env:TEMP "m1_fgbuild_$name.log"
    # SRG-runtime cells (e.g. 1.20.1) build from a cog-generated DIRECT-access gen/ tree; regenerate it
    # from _codegen so a clean checkout (gen/ is gitignored) is reproducible.
    if ((Get-Content "$dir\build.gradle" -Raw) -match 'srcDir\s+"gen"') {
      & "$PSScriptRoot\cog-gen.ps1" -Cell "Forge/$name" -McVer $mv -Loader forge *> (Join-Path $env:TEMP "m1_coggen_forge_$name.log")
      if ($LASTEXITCODE -ne 0) { "FAIL  $name (mc=$mv)  cog-gen failed (see m1_coggen_forge_$name.log)" | Add-Content $status; continue }
    }
    Push-Location $dir
    & "$dir\gradlew.bat" build --console=plain *> $blog
    $ok = ($LASTEXITCODE -eq 0)
    Pop-Location
    # PRODUCTION ARTIFACT (FG7, 2026-07-18): on <=1.20.4 SRG cells the net.minecraftforge.renamer plugin
    # emits m1-*-forge-srg.jar and THAT is what ships -- the plain m1-*-forge.jar is the pre-reobf mojmap
    # jar and will NOT load at runtime. Prefer -srg whenever the cell applies the renamer; mojmap cells
    # (1.20.6+) have no -srg jar and ship the plain one.
    $usesRenamer = (Get-Content "$dir\build.gradle" -Raw) -match "id\s+'net\.minecraftforge\.renamer'"
    $jars = Get-ChildItem "$dir\build\libs\m1-*-forge*.jar" -EA SilentlyContinue | Where-Object { $_.Name -notmatch 'sources' }
    if ($usesRenamer) { $jars = $jars | Where-Object { $_.Name -match '-forge-srg\.jar$' } }
    else              { $jars = $jars | Where-Object { $_.Name -match '-forge\.jar$' } }
    $jar = $jars | Sort-Object LastWriteTime | Select-Object -Last 1
    $w   = (Get-Content $blog -EA SilentlyContinue | Select-String -Pattern '\.java:\d+:\s*warning:').Count
    $sw.Stop()
    if ($ok -and $jar) {
      $null = New-Item -ItemType Directory -Force -Path (Join-Path $root 'dist')
      # dist carries the CANONICAL publish name `m1-<ver>+<fam>-forge.jar`. On renamer cells the built
      # artifact is `...-forge-srg.jar`; the `-srg` classifier is a BUILD-INTERNAL detail and must NOT
      # reach dist -- tools\mod-publish\publish.py parses `<prefix>-<modver>+<fam>[-<loader>].jar` and a
      # trailing `-srg` makes it read the family as "1.20.1-forge-srg" with NO loader (2026-07-18).
      $distName = $jar.Name -replace '-forge-srg\.jar$', '-forge.jar'
      Copy-Item $jar.FullName (Join-Path (Join-Path $root 'dist') $distName) -Force -EA SilentlyContinue
      "PASS  $name (mc=$mv)  $($sw.Elapsed.ToString('mm\:ss'))  warn=$w  $($jar.Name)" | Add-Content $status
    } else {
      $err = (Get-Content $blog -EA SilentlyContinue | Where-Object { $_ -match 'error:|\.java:\d+:' } | Select-Object -First 3) -join ' || '
      "FAIL  $name (mc=$mv)  exit=$LASTEXITCODE  $err" | Add-Content $status
    }
  } catch {
    $sw.Stop()
    "ERROR $name (mc=$mv)  $($_.Exception.Message)" | Add-Content $status
  }
}
"=== DONE  $(Get-Date -Format s) ===" | Add-Content $status
