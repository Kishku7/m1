# build-all-neoforge-cells.ps1 -- build every pre-26 NeoForge cell from the shared source.
#
# NeoForge is mojmap-native on every version, so the reflection *Compat facades resolve at runtime --
# no Cog/gen step (unlike pre-26 Fabric). Each cell srcDir's the shared source directly and builds via
# ModDevGradle. This walks the nested NeoForge/<ver> cells (excluding the 26 cell, which build-all-
# neoforge.ps1 owns), builds each, and records PASS/FAIL incrementally to a status file.
param([string[]]$Only)
$ErrorActionPreference = 'Continue'
$root = $PSScriptRoot
$status = Join-Path $env:TEMP 'm1_neoforge_cells_status.txt'
"=== m1 pre-26 NeoForge cell build  $(Get-Date -Format s) ===" | Set-Content $status

$cells = Get-ChildItem (Join-Path $root 'NeoForge') -Directory | Where-Object { $_.Name -ne '26' } | Sort-Object Name
if ($Only) { $cells = $cells | Where-Object { $Only -contains $_.Name } }

foreach ($cell in $cells) {
  $name = $cell.Name
  $dir  = $cell.FullName
  $mv   = (Select-String -Path "$dir\gradle.properties" -Pattern '^minecraft_version\s*=\s*(.+)$').Matches.Groups[1].Value.Trim()
  $sw   = [Diagnostics.Stopwatch]::StartNew()
  try {
    $blog = Join-Path $env:TEMP "m1_nfbuild_$name.log"
    Push-Location $dir
    & "$dir\gradlew.bat" build --console=plain *> $blog
    $ok = ($LASTEXITCODE -eq 0)
    Pop-Location
    $jar = Get-ChildItem "$dir\build\libs\m1-*-neoforge.jar" -EA SilentlyContinue | Where-Object { $_.Name -notmatch 'sources|slim' } | Select-Object -First 1
    $sw.Stop()
    if ($ok -and $jar) {
      $null = New-Item -ItemType Directory -Force -Path (Join-Path $root 'dist')
      Copy-Item $jar.FullName (Join-Path $root 'dist' $jar.Name) -Force -EA SilentlyContinue
      "PASS  $name (mc=$mv)  $($sw.Elapsed.ToString('mm\:ss'))  $($jar.Name)" | Add-Content $status
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
