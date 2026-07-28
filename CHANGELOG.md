# Changelog

All notable changes to M1 are documented here.
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.0.0/); this project uses
`<mod version>+<minecraft family>-<loader>` jar naming.

## [0.13.5] - 2026-07-28

### Changed
- **Fabric 26.3 cell moved to MC 26.3-snapshot-6** (from snapshot-5): fabric-api
  `0.155.3+26.3` -> `0.156.1+26.3`, pack_format `93` -> `94` (data 113, world_version 5005,
  snapshot protocol 328). The declared MC range is unchanged (`>=26.3- <26.4`), so this jar
  covers the whole 26.3 line including snapshot-6.

### Notes
- **No source change was required.** The snapshot-6 API deltas were checked against M1's cog
  sources: `InputWithModifiers` still declares exactly `input()` + `modifiers()` as its only
  abstract methods, so M1's anonymous implementation in `compat.press_button` is unaffected by
  the removal of `getDigit()`/`NOT_DIGIT`; M1 touches none of the other snapshot-6 breaks
  (worldgen noise overhaul, Entity invulnerability split, SharedSuggestionProvider filter
  parameter, options-screen reshuffle, terrain multidraw path).
- Built `-Xlint:all` with zero warnings.

## [0.13.4] - 2026-07-27

### Fixed
- **Forge 1.21.7 could not load M1 at all.** The `Forge/1.21.8` cell declared minecraft
  `[1.21.7,1.21.9)` while requiring forge `[58,)` -- but MC 1.21.7 runs Forge **57**, so the jar
  claimed a version it could never load on (`Missing language javafml version [58,) ... found 57.0.3`).
  That is a cell straddling a hard loader-major boundary. SPLIT per the standard: the 1.21.8 cell is
  narrowed to `[1.21.8,1.21.9)`, and a new **`Forge/1.21.7` cell** (forge `1.21.7-57.0.3`, ranges
  `[57,)` / `[1.21.7,1.21.8)`, pack_format 64) provides real 1.21.7 coverage. Both cells server-boot
  smoketested green with zero mod errors.

### Changed
- `scripts/cog-gen.ps1` M1Forge shape table now maps 1.21.7 to `forge_eventbus7` (both neighbours,
  1.21.6/forge-56 and 1.21.8/forge-58, already used that shape).
- **`.gitignore` no longer hides the whole `scripts/` directory.** M1 was the only mod doing this, so
  its build tooling -- `cog-gen.ps1`, every `build-all-*.ps1`, and the 26-cell loader pin map -- was
  absent from the repo and builds were not reproducible from a clean clone. Now only the
  credential-reading `_publish_*.py` / `_release_*.py` / `build-stage.ps1` / `_*.txt` are ignored,
  matching every other mod and `mod-rules.md`.

## [0.13.3] - 2026-07-27

### Changed
- NeoForge 26 cells rebuilt against the now-PUBLISHED NeoForge builds: 26.1 -> 26.1.2.87, 26.2 -> 26.2.0.35-beta (previously 26.1.2.78 / 26.2.0.8-beta). mavenLocal() removed from the NeoForge/26 cell.
- No source or behaviour change. Redeployed to every smoketest server and client cell per the latest-M1-everywhere rule.

## [0.13.2] - 2026-07-21

### Fixed
- **Minecraft 26.3-snapshot-5 support (Fabric).** 26.3-snapshot-5 removed `DataComponentPatch.entrySet()`;
  the item-component listing (`M1Compat.componentPatchList`) now uses the surviving `split()` /
  `SplitResult(added, removed)` API. The 26.3 Fabric build compiles and runs on 26.3-snapshot-5, verified
  in-world on the headless client harness (world load + drive + render). `split()` is present across the
  whole 1.20.5+ data-component era, so the change is behavior-preserving on every earlier version.

## [0.13.1] - 2026-07-18

### Fixed
- **Forge 1.21.4 and 1.21.10** now build and run correctly. Two internal version-boundary
  misclassifications (the ResourceLocation/Identifier + piercing-weapon API is 1.21.11, not 1.21.9;
  the recipe-ingredient stream API is 1.21.4, not 1.21.5) were masked on those two Forge-only
  versions and are corrected.
- **26.3 menu clicks work again.** The single-source refactor below dropped a 26.3-only fix: on
  26.3-snapshot-3+ the primary (left) click button is encoded 1, not 0, so a hardcoded 0 made every
  in-game menu click a silent no-op. `screen_click` now emits the correct button per version (1 for
  26.3+, 0 for 26.1/26.2 and 1.21.9+). Verified on the 26.3-snapshot-4 client cell.

### Security
- **Hardened the local control socket.** Inbound command lines are now length-capped, and the
  `screenshot` command rejects file names containing path separators or `..` (the frame can only be
  written under the screenshots folder). The socket remains bound to localhost only.

### Changed
- **Internal single-source build refactor (no gameplay change):** the mod is now generated from one
  code source of truth per file, with every build cell and loader entrypoint produced from it.
  Behavior is identical to 0.13.0 on every supported version.

## [0.13.0] - 2026-07-18

First public release.

### Added
- **Server-side `/m1srv` command tree.** M1 absorbed the previously separate M1-Server companion mod,
  so a single jar now carries both the client agent and the server authority. M1 requires neither
  side: in single-player the client drives the in-process integrated server; on a dedicated server
  `/m1srv` runs server-side and replies over system chat, which the client captures.

### Changed
- **Forge cells build on ForgeGradle 7** (Gradle 9.6, Minecraft Mavenizer) on 1.20.1, 1.20.2, 1.20.6,
  1.21, 1.21.4, 1.21.5, 1.21.6, 1.21.8, 1.21.10 and 1.21.11. On the SRG-runtime cells (1.20.1, 1.20.2)
  the `net.minecraftforge.renamer` plugin produces the shipped mojmap->SRG jar.
  Forge 1.20.4 remains on ForgeGradle 6 pending an upstream Mavenizer fix.

### Fixed
- **Resource pack metadata on Minecraft 1.21.10 and 1.21.11.** These versions fall in the pack-format
  "dead zone" where no single `pack.mcmeta` satisfies both the client and server codecs. M1 previously
  shipped a plain resource-major format there, which made the game log
  `Couldn't load mod:m1 pack metadata` and drop the mod's resource pack entirely (its language file
  and textures). Forge cells now declare the exact data-major range (88 on 1.21.10, 94 on 1.21.11) and
  Fabric/NeoForge cells ship no `pack.mcmeta` at all, letting the loader synthesise correct per-type
  metadata.

### Notes
- Versions before 0.13.0 were internal builds and were never publicly available.

[0.13.0]: https://github.com/Kishku7/m1
