param([string[]]$Versions)
$ErrorActionPreference="Stop"
$repo=Split-Path $PSScriptRoot -Parent; $fabric=Join-Path $repo "Fabric\26"; $dist=Join-Path $repo "dist"
New-Item -ItemType Directory -Force -Path $dist|Out-Null
$matrix=[ordered]@{
  "26.1"=@{mc="26.1.2";          api="0.152.1+26.1.2"; loader="0.18.6"; dep=">=26.1- <26.2"; pf="84"}
  "26.2"=@{mc="26.2";            api="0.152.1+26.2";   loader="0.19.3"; dep=">=26.2- <26.3"; pf="88"}
  "26.3"=@{mc="26.3-snapshot-5"; api="0.155.3+26.3";   loader="0.19.3"; dep=">=26.3- <26.4"; pf="93"}
}
if(-not $Versions -or $Versions.Count -eq 0){$Versions=@($matrix.Keys)}
$modver=(Select-String -Path (Join-Path $fabric "gradle.properties") -Pattern '^mod_version=(.+)$').Matches[0].Groups[1].Value
foreach($v in $Versions){
  $m=$matrix[$v]; if(-not $m){throw "Unknown $v"}
  Write-Host "=== M1 Fabric $v (mc=$($m.mc)) ==="
  Push-Location $fabric
  & "$PSScriptRoot\cog-gen.ps1" -Cell "Fabric/26" -McVer $m.mc -Loader fabric | Out-Null
  $env:PACK_FORMAT=$m.pf
  & ".\gradlew.bat" clean build "-Pminecraft_version=$($m.mc)" "-Pfabric_api_version=$($m.api)" "-Ploader_version=$($m.loader)" "-Pmc_dep=$($m.dep)" --no-daemon
  $rc=$LASTEXITCODE; Pop-Location
  if($rc -ne 0){throw "Fabric FAILED $v"}
  $jar=Get-ChildItem (Join-Path $fabric "build\libs") -Filter "m1-*.jar"|?{$_.Name -notmatch 'sources'}|Sort-Object LastWriteTime|Select-Object -Last 1
  Copy-Item $jar.FullName (Join-Path $dist ("m1-{0}+{1}-fabric.jar" -f $modver,$v)) -Force
  Write-Host "  -> $v done"
}
Write-Host "M1 Fabric builds complete."

# Refresh the AI_Brain memory mirror (local tooling; scripts/ is gitignored, absent in public clones)
$syncScript=Join-Path $repo "scripts\sync-aibrain.ps1"
if(Test-Path $syncScript){ try{ & $syncScript }catch{ Write-Warning "sync-aibrain failed: $_" } }


