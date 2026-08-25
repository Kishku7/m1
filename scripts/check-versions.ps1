# check-versions.ps1 -- ONE version number across the whole M1 line, proven from the BINARY.
#
# WHY THIS EXISTS, AND WHY IT READS JARS AND NOT gradle.properties (2026-08-25)
#
# Two separate drifts have now happened, and the second is the reason this script inspects jars:
#
#  1. 0.16.1: shared code changed but only Fabric/26/gradle.properties was bumped, leaving 33 cells
#     stamped 0.16.0 over 0.16.1 sources. Caught by hand.
#  2. 0.17.0: ALL 34 gradle.properties agreed on 0.17.0 -- and the SHIPPED JARS STILL DISAGREED.
#     Each Fabric cell's build.gradle set `version = "${mod_version}+${minecraft_version}-fabric"`
#     and expanded THAT into fabric.mod.json, so 12 Fabric jars advertised `0.17.0+1.21-fabric`,
#     `0.17.0+26.1.2-fabric`, ... while all 25 Forge/NeoForge jars advertised a plain `0.17.0`.
#     A checker that read gradle.properties would have reported CLEAN through the whole thing.
#
# That is the standing rule in force: an automated check only proves what it ACTUALLY INSPECTS.
# The thing that must agree is what the LOADER READS, so this reads what the loader reads:
#   Fabric   -> fabric.mod.json               "version"
#   NeoForge -> META-INF/neoforge.mods.toml   version = "..."
#   Forge    -> META-INF/mods.toml            version = "..."   (often the FML placeholder
#               ${file.jarVersion}, which FML resolves from the jar manifest's
#               Implementation-Version -- so resolve it the same way rather than calling it a
#               mismatch, and FAIL only if nothing resolves it)
#
# The FILENAME is deliberately NOT checked for the family suffix: dist carries the canonical
# publish name `m1-<ver>+<fam>-<loader>.jar` and tools\mod-publish\publish.py parses the family
# out of it. Family belongs in the filename; it must NOT be inside the version the loader reads.
#
# Exit 0 = every cell and every jar agree. Exit 1 = drift. ASCII only.
param(
  [switch]$Quiet
)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
Add-Type -AssemblyName System.IO.Compression.FileSystem

$fail = 0
function note($m){ if (-not $Quiet) { Write-Host $m } }

# ---- 1. SOURCE: every cell's declared mod_version ------------------------------------------
$cells = Get-ChildItem $root -Recurse -Filter 'gradle.properties' -File |
         Where-Object { $_.FullName -notmatch '\\build\\' }
$srcVers = @{}
foreach ($c in $cells) {
  $m = Select-String -Path $c.FullName -Pattern '^mod_version\s*=\s*(.+)$'
  if (-not $m) { continue }
  $v = $m.Matches.Groups[1].Value.Trim()
  $cell = $c.Directory.FullName.Replace("$root\", '')
  if (-not $srcVers.ContainsKey($v)) { $srcVers[$v] = @() }
  $srcVers[$v] += $cell
}
note "SOURCE: $($cells.Count) cells"
foreach ($k in $srcVers.Keys) { note "  $k -> $($srcVers[$k].Count) cells" }
if ($srcVers.Keys.Count -ne 1) {
  Write-Host "FAIL: cells disagree on mod_version"
  foreach ($k in $srcVers.Keys) { Write-Host "  $k : $($srcVers[$k] -join ', ')" }
  $fail = 1
}
# @(...) FIRST: $srcVers.Keys with one key is a bare string, and indexing a bare string with
# [0] yields its first CHARACTER ("0"), not the version. That made every jar "disagree" with "0".
$expected = @($srcVers.Keys | Sort-Object)[0]

# ---- 2. BINARY: what each shipped jar actually advertises -----------------------------------
$dist = Join-Path $root 'dist'
$jars = @(Get-ChildItem $dist -Filter '*.jar' -File -EA SilentlyContinue)
if ($jars.Count -eq 0) { note "no jars in dist/ -- source check only"; exit $fail }

$bad = @()
foreach ($j in $jars) {
  $z = [System.IO.Compression.ZipFile]::OpenRead($j.FullName)
  try {
    $ver = $null
    foreach ($n in @('fabric.mod.json','META-INF/neoforge.mods.toml','META-INF/mods.toml')) {
      $e = $z.Entries | Where-Object { $_.FullName -eq $n } | Select-Object -First 1
      if (-not $e) { continue }
      $sr = New-Object System.IO.StreamReader($e.Open()); $txt = $sr.ReadToEnd(); $sr.Close()
      if ($n -eq 'fabric.mod.json') { if ($txt -match '"version"\s*:\s*"([^"]+)"') { $ver = $Matches[1] } }
      else { if ($txt -match '(?m)^\s*version\s*=\s*"([^"]+)"') { $ver = $Matches[1] } }
      break
    }
    # FML placeholder: resolve it the way the loader does, from the manifest.
    if ($ver -eq '${file.jarVersion}') {
      $e = $z.Entries | Where-Object { $_.FullName -eq 'META-INF/MANIFEST.MF' } | Select-Object -First 1
      $ver = $null
      if ($e) { $sr = New-Object System.IO.StreamReader($e.Open()); $txt = $sr.ReadToEnd(); $sr.Close()
                if ($txt -match '(?m)^Implementation-Version:\s*(.+?)\s*$') { $ver = $Matches[1] } }
      if (-not $ver) { $bad += "$($j.Name): UNRESOLVED ${file.jarVersion} (no Implementation-Version in the manifest -- this jar would tell the loader the literal placeholder)"; continue }
    }
    if (-not $ver) { $bad += "$($j.Name): no loader metadata found"; continue }
    if ($ver -ne $expected) { $bad += "$($j.Name): advertises '$ver', expected '$expected'" }
  } finally { $z.Dispose() }
}

note "BINARY: $($jars.Count) jars"
if ($bad.Count) {
  Write-Host "FAIL: shipped jars do not all advertise '$expected'"
  $bad | ForEach-Object { Write-Host "  $_" }
  $fail = 1
} else {
  note "  all $($jars.Count) jars advertise $expected"
}

if ($fail) { Write-Host "VERSION CHECK: FAIL"; exit 1 }
Write-Host "VERSION CHECK: PASS -- $($cells.Count) cells and $($jars.Count) jars all on $expected"
exit 0
