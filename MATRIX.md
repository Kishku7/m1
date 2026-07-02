# M1 cross-version target matrix -- Minecraft 1.20 - 26.3

ONE source builds every supportable MC 1.20.0-26.3 for all applicable loaders via shared folders + Cog + reflection facades.

**Scope:** EVERY supportable release 1.20.0-26.3, each boot-smoketested per claimed loader BEFORE Raider live testing. A compat claim REQUIRES a smoketest demonstrating the effort. (Kishku7 2026-06-28)

Target mod version on cutover: **0.5**. Loader rule: Fabric on every version; Forge for every MC 1.20.1-1.21.8 that has a supported Forge loader (FG6 ceiling = 1.21.8, no Forge for 1.20.0); NeoForge from 1.20.1+; 26.x is Fabric+NeoForge only. Never Architectury.

## Build cells (legacy-proven coords; `unified` build wiring added per cell in Stages 1-3)

| MC | Loader | JDK | Key coords | Range |
|---|---|---|---|---|
| 1.20 | fabric | 17 | loader 0.16.10, api 0.83.0+1.20 | >=1.20- <1.21 |
| 1.20.6 | fabric | 21 | loader 0.16.10, api 0.100.8+1.20.6 | >=1.20.5- <1.21 |
| 1.21 | fabric | 21 | loader 0.16.10, api 0.100.8+1.21 | >=1.21- <1.21.2 |
| 1.21.1 | fabric | 21 | loader 0.19.3, api 0.116.12+1.21.1 | >=1.21- <1.21.2 |
| 1.21.2 | fabric | 21 | loader 0.19.3, api 0.106.1+1.21.2 | >=1.21.2- <1.21.3 |
| 1.21.5 | fabric | 21 | loader 0.19.3, api 0.128.2+1.21.5 | >=1.21.5- <1.21.6 |
| 1.21.8 | fabric | 21 | loader 0.19.3, api 0.136.1+1.21.8 | >=1.21.8- <1.21.9 |
| 1.21.11 | fabric | 21 | loader 0.19.3, api 0.141.4+1.21.11 | >=1.21.11- <1.21.12 |
| 26.1.2 | fabric | 25 | loader 0.18.6, api 0.152.1+26.1.2 | >=26.1- <26.2 |
| 26.2 | fabric | 25 | loader 0.19.3, api 0.152.1+26.2 | >=26.2- <26.3 |
| 26.3-snapshot-1 | fabric | 25 | loader 0.19.3, api 0.153.1+26.3 | >=26.3- <26.4 |
| 1.20.1 | forge | 17 | forge 1.20.1-47.3.0 | (forge mcversion) |
| 1.20.6 | forge | 21 | forge 1.20.6-50.2.8 | (forge mcversion) |
| 1.21 | forge | 21 | forge 1.21-51.0.33 | (forge mcversion) |
| 1.21.1 | forge | 21 | forge 1.21.1-52.1.14 | (forge mcversion) |
| 1.21.5 | forge | 21 | forge 1.21.5-55.1.10 | (forge mcversion) |
| 1.21.8 | forge | 21 | forge 1.21.8-58.1.18 | (forge mcversion) |
| 1.20.6 | neoforge | 21 | neo 20.6.139 | [1.20.5,1.20.7) |
| 1.21 | neoforge | 21 | neo 21.0.167 | [1.21,1.21.2) |
| 1.21.1 | neoforge | 21 | neo 21.1.234 | [1.21,1.21.2) |
| 1.21.2 | neoforge | 21 | neo 21.2.1-beta | [1.21.2,1.21.3) |
| 1.21.5 | neoforge | 21 | neo 21.5.97 | [1.21.5,1.21.6) |
| 1.21.8 | neoforge | 21 | neo 21.8.53 | [1.21.8,1.21.9) |
| 1.21.11 | neoforge | 21 | neo 21.11.42 | [1.21.11,1.21.12) |
| 26.1.2 | neoforge | 25 | neo 26.1.2.30-beta | [26.1,26.2) |
| 26.2 | neoforge | 25 | neo 26.2.0.1-beta | [26.2,26.3) |

## Claimed versions -- EVERY one boot-smoketested before Raider live testing

Legend: `cell` = dedicated build cell exists; `build+st` = needs a covering build + smoketest; `n/a` = loader has no release for that MC.

| MC | Fabric | Forge | NeoForge | Smoketest |
|---|---|---|---|---|
| 1.20 | cell | build+st | n/a | pending |
| 1.20.1 | build+st | cell | build+st | pending |
| 1.20.2 | build+st | build+st | build+st | pending |
| 1.20.3 | build+st | build+st | build+st | pending |
| 1.20.4 | build+st | build+st | build+st | pending |
| 1.20.5 | build+st | build+st | build+st | pending |
| 1.20.6 | cell | cell | cell | pending |
| 1.21 | cell | cell | cell | pending |
| 1.21.1 | cell | cell | cell | pending |
| 1.21.2 | cell | build+st | cell | pending |
| 1.21.3 | build+st | build+st | build+st | pending |
| 1.21.4 | build+st | build+st | build+st | pending |
| 1.21.5 | cell | cell | cell | pending |
| 1.21.6 | build+st | build+st | build+st | pending |
| 1.21.7 | build+st | build+st | build+st | pending |
| 1.21.8 | cell | cell | cell | pending |
| 1.21.9 | build+st | build+st | build+st | pending |
| 1.21.10 | build+st | build+st | build+st | pending |
| 1.21.11 | cell | build+st | cell | pending |
| 26.1 | build+st | build+st | build+st | pending |
| 26.1.1 | build+st | build+st | build+st | pending |
| 26.1.2 | cell | build+st | cell | pending |
| 26.2 | cell | build+st | cell | pending |
| 26.3 | cell | build+st | build+st | pending |

**Versions with no dedicated cell on any loader yet:** 1.20.2, 1.20.3, 1.20.4, 1.20.5, 1.21.3, 1.21.4, 1.21.6, 1.21.7, 1.21.9, 1.21.10, 26.1, 26.1.1

## Open gaps / decisions

- 1.20.0 Forge: no cell yet (Fabric 1.20 already covers 1.20.0). Add a Forge floor cell only if Forge wanted at 1.20.0.
- 1.20.1 NeoForge: currently served by the Forge 1.20.1 jar (Forge-API compatible); decide whether to add a dedicated NeoForge 1.20.1 cell.
- 1.20.2-1.20.5: no cell between the 1.20 and 1.20.6 Fabric ranges -- confirm whether these need coverage.
- 26.3 NeoForge: NeoForge not released for 26.3 yet -- legitimate loader-floor gap (Fabric 26.3 only for now).
- 1.21 vs 1.21.1: Fabric+NeoForge ranges overlap ([1.21,1.21.2)) -- collapse to one cell each in the unified source.
