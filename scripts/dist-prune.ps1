# dist-prune.ps1 -- drop superseded jars from dist/ so it only ever holds the CURRENT mod version.
#
# WHY: every full matrix run drops 37 jars into dist/ and nothing ever removed the previous set.
# Six runs had stacked up to 238 jars / 70 MB before this existed (cleaned 2026-08-01).
#
# DESIGN -- prune by VERSION, never "empty the folder":
# A full sweep is several drivers run back to back (fabric-cog, forge-cells, neoforge-cells, then
# the 26 cells). If each driver blanked dist/ on entry, the second driver would delete the first
# driver's jars from the SAME run. So this removes only artifacts whose version differs from the
# current one. That makes it idempotent and order-independent: every driver can call it on entry,
# in any order, and a completed sweep leaves exactly one version behind.
#
# VERSION SOURCE -- gradle.properties, NOT matrix.json.
# matrix.json has a modVersionTarget field but it is gitignored and unmaintained; on 2026-08-01 it
# still read 0.12 while the cells were at 0.16.0. Trusting it would have pruned the CURRENT jars and
# kept the stale ones. The per-cell gradle.properties files are the real source and are bumped
# together, so cell 1 is read and the rest are checked for agreement.
#
#   pwsh scripts\dist-prune.ps1              # prune to the current version
#   pwsh scripts\dist-prune.ps1 -WhatIfOnly  # report what WOULD go, delete nothing
param(
  [switch]$WhatIfOnly
)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$dist = Join-Path $root 'dist'
if (-not (Test-Path $dist)) { Write-Host "dist-prune: no dist/ yet, nothing to do."; return }

# --- resolve the current mod version from the cells -------------------------
$props = Get-ChildItem (Join-Path $root 'Fabric'),(Join-Path $root 'Forge'),(Join-Path $root 'NeoForge') `
           -Filter 'gradle.properties' -Recurse -ErrorAction SilentlyContinue
# @(...) IS LOAD-BEARING. Sort-Object -Unique returns a SCALAR STRING when every cell agrees
# (the normal case). Indexing a string yields a CHARACTER, so $vers[0] became '0' instead of
# '0.16.0' -- every jar then mismatched "0" and the whole of dist/ was deleted. Cost 37 jars on
# 2026-08-01. .Count is 1 on a bare string too, so the disagreement guard below did not catch it.
$vers = @($props | ForEach-Object {
  $m = Select-String -Path $_.FullName -Pattern '^mod_version\s*=\s*(.+)$'
  if ($m) { $m.Matches.Groups[1].Value.Trim() }
} | Sort-Object -Unique)

if (-not $vers)        { throw "dist-prune: no mod_version found in any cell gradle.properties -- refusing to delete." }
if ($vers.Count -gt 1) {
  # Cells disagree: a bump is half-applied. Pruning now could delete jars that are still wanted.
  Write-Host ("dist-prune: SKIPPED -- cells disagree on mod_version ({0}). Finish the bump, then rerun." -f ($vers -join ', '))
  return
}
$cur = $vers[0]

# Shape gate: whatever we resolved must LOOK like a version before it may authorise deletions.
# A malformed value matches nothing in dist/ and would therefore condemn everything, so refuse
# loudly instead. This is the backstop for the scalar/array trap above.
if ($cur -notmatch '^\d+\.\d+(\.\d+)?') {
  throw "dist-prune: resolved mod_version '$cur' is not a version -- refusing to delete anything."
}

# Emptying dist/ completely is EXPECTED at the start of a sweep: the version has just been bumped,
# so dist/ still holds only the previous release and none of the new one exists yet. So this is a
# NOTICE, not a veto -- vetoing here would defeat the whole point of pruning before a run.
# What makes that safe is that $cur is corroborated twice over: every cell gradle.properties agrees
# on it (checked above), and it passed the shape gate. The scalar/array defect that once emptied
# dist/ produced $cur='0', which the shape gate now rejects outright before reaching this point.
$present = @(Get-ChildItem $dist -File | Where-Object { $_.Name -match '^m1-.+?\+' })
$keepers = @($present | Where-Object { $_.Name -match ('^m1-' + [regex]::Escape($cur) + '\+') })
if ($present.Count -gt 0 -and $keepers.Count -eq 0) {
  Write-Host ("dist-prune: dist/ holds {0} artifact(s), none at {1} -- clearing for a fresh {1} run." -f $present.Count,$cur)
}

# --- prune anything that is not the current version -------------------------
# Artifact names are m1-<modver>+<family>-<loader>.jar, so the version is the text between
# the leading "m1-" and the "+". Anything unparseable is LEFT ALONE rather than guessed at.
$doomed = Get-ChildItem $dist -File | Where-Object {
  $_.Name -match '^m1-(.+?)\+' -and $Matches[1] -ne $cur
}

if (-not $doomed) { Write-Host ("dist-prune: dist/ already clean at {0}." -f $cur); return }

$mb = [math]::Round((($doomed | Measure-Object Length -Sum).Sum / 1mb), 1)
if ($WhatIfOnly) {
  Write-Host ("dist-prune: WOULD remove {0} file(s) ({1} MB), keeping {2}:" -f $doomed.Count,$mb,$cur)
  $doomed | ForEach-Object { Write-Host ("  " + $_.Name) }
} else {
  $doomed | Remove-Item -Force
  Write-Host ("dist-prune: removed {0} superseded file(s) ({1} MB); dist/ now holds {2} only." -f $doomed.Count,$mb,$cur)
}
