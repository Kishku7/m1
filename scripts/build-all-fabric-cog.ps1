# build-all-fabric-cog.ps1 -- build every pre-26 Fabric cell from the cog-generated source.
#
# Pre-26 Fabric runs on the INTERMEDIARY runtime, where reflection-by-mojmap-name misses. Those cells
# fall through to Cog (direct compilation): cog-gen.ps1 builds <Cell>/gen from shared_minecraft + the
# cog-instrumented *Compat generated for the cell's MC version, and the cell's build.gradle srcDir's gen.
# This script wires each cell (srcDir -> gen, gitignore gen/), cog-gens, builds, and records PASS/FAIL.
#
# Status is written incrementally to %TEMP%\m1_fabric_cog_status.txt so a watcher can poll it.
param([string[]]$Only)   # optional: limit to specific cell names
$ErrorActionPreference = 'Continue'
$root = Split-Path $PSScriptRoot -Parent
$status = Join-Path $env:TEMP 'm1_fabric_cog_status.txt'
# Keep dist/ to the current version only (see dist-prune.ps1 -- prunes BY VERSION, so drivers
# in the same sweep do not delete each other's jars).
& "$PSScriptRoot\dist-prune.ps1"

"=== m1 pre-26 Fabric cog build  $(Get-Date -Format s) ===" | Set-Content $status

$cells = Get-ChildItem (Join-Path $root 'Fabric') -Directory | Where-Object { $_.Name -ne '26' } | Sort-Object Name
if ($Only) { $cells = $cells | Where-Object { $Only -contains $_.Name } }

foreach ($cell in $cells) {
  $name = $cell.Name
  $dir  = $cell.FullName
  $mv   = (Select-String -Path "$dir\gradle.properties" -Pattern '^minecraft_version\s*=\s*(.+)$').Matches.Groups[1].Value.Trim()
  $sw   = [Diagnostics.Stopwatch]::StartNew()
  try {
    # 1) wire srcDir -> gen
    $bg = Get-Content "$dir\build.gradle"
    if ($bg -match 'srcDir "\.\./shared_minecraft/src/main/java"') {
      ($bg -replace [regex]::Escape('sourceSets.main.java.srcDir "../shared_minecraft/src/main/java"'),'sourceSets.main.java.srcDir "gen"') | Set-Content "$dir\build.gradle"
    }
    # 2) gitignore gen/
    if (-not (Test-Path "$dir\.gitignore") -or -not (Select-String -Path "$dir\.gitignore" -Pattern '^gen/$' -Quiet)) {
      Add-Content "$dir\.gitignore" 'gen/'
    }
    # 3) cog-gen
    & "$PSScriptRoot\cog-gen.ps1" -Cell "Fabric\$name" -McVer $mv -Loader 'fabric' | Out-Null
    # 4) build
    $blog = Join-Path $env:TEMP "m1_build_$name.log"
    Push-Location $dir
    & "$dir\gradlew.bat" build --console=plain *> $blog
    $ok = ($LASTEXITCODE -eq 0)
    Pop-Location
    $jar = Get-ChildItem "$dir\build\libs\m1-*-fabric.jar" -EA SilentlyContinue | Where-Object { $_.Name -notmatch 'sources' } | Sort-Object LastWriteTime | Select-Object -Last 1
    $sw.Stop()
    if ($ok -and $jar) {
      $null = New-Item -ItemType Directory -Force -Path (Join-Path $root 'dist')
      Copy-Item $jar.FullName (Join-Path (Join-Path $root 'dist') $jar.Name) -Force -EA SilentlyContinue
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

# ---- VERSION PARITY GATE (2026-08-25) ------------------------------------------------------
# A check that is never RUN is not a check. The 0.17.0 drift shipped while every one of the 34
# gradle.properties agreed on 0.17.0 -- the JARS were what disagreed, so check-versions.ps1 reads
# the jars. Wired into every build driver here so it cannot be "available but never invoked",
# which is the same failure mode one layer up. Non-fatal: a single-loader build is a legitimate
# mid-campaign state, so this RECORDS and WARNS rather than failing the build.
$__vc = Join-Path $PSScriptRoot 'check-versions.ps1'
if (Test-Path $__vc) {
  $__out = & $__vc -Quiet 2>&1
  $__ok  = ($LASTEXITCODE -eq 0)
  $__msg = if ($__ok) { "VERSION CHECK: PASS" } else { "VERSION CHECK: FAIL`n$($__out -join "`n")" }
  if (Get-Variable -Name status -Scope Script -ErrorAction SilentlyContinue) { $__msg | Add-Content $status }
  elseif ($status) { $__msg | Add-Content $status }
  Write-Host $__msg
  if (-not $__ok) { Write-Warning "VERSION PARITY FAIL -- jars do not all advertise the same version" }
}
