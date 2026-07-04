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
$root = $PSScriptRoot
$status = Join-Path $env:TEMP 'm1_forge_cells_status.txt'
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
      & "$root\cog-gen.ps1" -Cell "Forge/$name" -McVer $mv -Loader forge *> (Join-Path $env:TEMP "m1_coggen_forge_$name.log")
      if ($LASTEXITCODE -ne 0) { "FAIL  $name (mc=$mv)  cog-gen failed (see m1_coggen_forge_$name.log)" | Add-Content $status; continue }
    }
    Push-Location $dir
    & "$dir\gradlew.bat" build --console=plain *> $blog
    $ok = ($LASTEXITCODE -eq 0)
    Pop-Location
    $jar = Get-ChildItem "$dir\build\libs\m1-*-forge.jar" -EA SilentlyContinue | Where-Object { $_.Name -notmatch 'sources' } | Select-Object -First 1
    $w   = (Get-Content $blog -EA SilentlyContinue | Select-String -Pattern '\.java:\d+:\s*warning:').Count
    $sw.Stop()
    if ($ok -and $jar) {
      $null = New-Item -ItemType Directory -Force -Path (Join-Path $root 'dist')
      Copy-Item $jar.FullName (Join-Path $root 'dist' $jar.Name) -Force -EA SilentlyContinue
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
