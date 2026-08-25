param([string[]]$Versions)
$ErrorActionPreference="Stop"
$repo=Split-Path $PSScriptRoot -Parent; $nf=Join-Path $repo "NeoForge\26"; $dist=Join-Path $repo "dist"
# Keep dist/ to the current version only (see dist-prune.ps1 -- prunes BY VERSION, so drivers
# in the same sweep do not delete each other's jars).
& "$PSScriptRoot\dist-prune.ps1"

New-Item -ItemType Directory -Force -Path $dist|Out-Null
$matrix=[ordered]@{
  "26.1"=@{mc="26.1.2"; neo="26.1.2.87"; mcRange="[26.1,26.2)"; neoRange="[26.1.0-alpha,)"; pf="84"}
  "26.2"=@{mc="26.2";   neo="26.2.0.35-beta";  mcRange="[26.2,26.3)"; neoRange="[26.2.0-alpha,)"; pf="88"}
}
if(-not $Versions -or $Versions.Count -eq 0){$Versions=@($matrix.Keys)}
$modver=(Select-String -Path (Join-Path $nf "gradle.properties") -Pattern '^mod_version=(.+)$').Matches[0].Groups[1].Value
foreach($v in $Versions){
  $m=$matrix[$v]; if(-not $m){throw "Unknown $v"}
  Write-Host "=== M1 NeoForge $v (neo=$($m.neo)) ==="
  Push-Location $nf
  & "$PSScriptRoot\cog-gen.ps1" -Cell "NeoForge/26" -McVer $m.mc -Loader neoforge | Out-Null
  $env:PACK_FORMAT=$m.pf
  & ".\gradlew.bat" clean build "-Pminecraft_version=$($m.mc)" "-Pneo_version=$($m.neo)" "-Pmc_range=$($m.mcRange)" "-Pneoforge_range=$($m.neoRange)" --no-daemon
  $rc=$LASTEXITCODE; Pop-Location
  if($rc -ne 0){throw "NeoForge FAILED $v"}
  $jar=Get-ChildItem (Join-Path $nf "build\libs") -Filter "m1-*.jar"|?{$_.Name -notmatch 'sources|slim'}|Sort-Object LastWriteTime|Select-Object -Last 1
  Copy-Item $jar.FullName (Join-Path $dist ("m1-{0}+{1}-neoforge.jar" -f $modver,$v)) -Force
  Write-Host "  -> $v done"
}
Write-Host "M1 NeoForge builds complete."

# Refresh the AI_Brain memory mirror (local tooling; scripts/ is gitignored, absent in public clones)
$syncScript=Join-Path $repo "scripts\sync-aibrain.ps1"
if(Test-Path $syncScript){ try{ & $syncScript }catch{ Write-Warning "sync-aibrain failed: $_" } }

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
  Write-Host $__msg
  if (-not $__ok) { Write-Warning "VERSION PARITY FAIL -- jars do not all advertise the same version" }
}
