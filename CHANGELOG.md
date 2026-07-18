# Changelog

All notable changes to M1 are documented here.
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.0.0/); this project uses
`<mod version>+<minecraft family>-<loader>` jar naming.

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
