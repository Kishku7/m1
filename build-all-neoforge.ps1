param([string[]]$Versions)
$ErrorActionPreference="Stop"
$repo=Split-Path -Parent $MyInvocation.MyCommand.Path; $nf=Join-Path $repo "NeoForge"; $dist=Join-Path $repo "dist"
New-Item -ItemType Directory -Force -Path $dist|Out-Null
$matrix=[ordered]@{
  "26.1"=@{mc="26.1.2"; neo="26.1.2.30-beta"; mcRange="[26.1,26.2)"; neoRange="[26.1.0-alpha,)"}
  "26.2"=@{mc="26.2";   neo="26.2.0.1-beta";  mcRange="[26.2,26.3)"; neoRange="[26.2.0-alpha,)"}
}
if(-not $Versions -or $Versions.Count -eq 0){$Versions=@($matrix.Keys)}
$modver=(Select-String -Path (Join-Path $nf "gradle.properties") -Pattern '^mod_version=(.+)$').Matches[0].Groups[1].Value
foreach($v in $Versions){
  $m=$matrix[$v]; if(-not $m){throw "Unknown $v"}
  Write-Host "=== M1 NeoForge $v (neo=$($m.neo)) ==="
  Push-Location $nf
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

