# build-all-forge-cells.ps1 -- build the Forge cells (FG6, mojmap facade, no cog) from the shared source.
#
# Forge is mojmap on 1.20.1+, so the reflection *Compat facades resolve at runtime -- no Cog step. Each
# Forge/<ver> cell is a thin FG6 project that srcDir's the shared source and carries a per-version
# M1Forge entrypoint (TickEvent through 1.21.5; EventBus-7 TickEvent.ClientTickEvent.Post.BUS at 1.21.8+).
# Forge/FG6 ceiling = 1.21.8. Walks Forge/<ver>, builds each, records PASS/FAIL + warning count.
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
    Push-Location $dir
    & "$dir\gradlew.bat" build --console=plain *> $blog
    $ok = ($LASTEXITCODE -eq 0)
    Pop-Location
    $jar = Get-ChildItem "$dir\build\libs\m1-*-forge.jar" -EA SilentlyContinue | Where-Object { $_.Name -notmatch 'sources' } | Select-Object -First 1
    $w   = (Get-Content $blog -EA SilentlyContinue | Select-String -Pattern '\.java:\d+:\s*warning:').Count
    $sw.Stop()
    if ($ok -and $jar) {
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
