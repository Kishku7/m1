# M1 cross-version target matrix -- Minecraft 1.20 - 26.3

Single-source goal: one branch builds every cell below from shared folders + Cog + facades.
Status: coords are legacy-proven (built+smoketested in the old per-version projects); `unified` build wiring
is added per cell in Stages 1-3. Target mod version on cutover: **0.5**.

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
| 1.20.6 | neoforge | 21 | neo 20.6.139, mdg 2.0.141 | [1.20.5,1.20.7) |
| 1.21 | neoforge | 21 | neo 21.0.167, mdg 2.0.141 | [1.21,1.21.2) |
| 1.21.1 | neoforge | 21 | neo 21.1.234, mdg 2.0.141 | [1.21,1.21.2) |
| 1.21.2 | neoforge | 21 | neo 21.2.1-beta, mdg 2.0.141 | [1.21.2,1.21.3) |
| 1.21.5 | neoforge | 21 | neo 21.5.97, mdg 2.0.141 | [1.21.5,1.21.6) |
| 1.21.8 | neoforge | 21 | neo 21.8.53, mdg 2.0.141 | [1.21.8,1.21.9) |
| 1.21.11 | neoforge | 21 | neo 21.11.42, mdg 2.0.141 | [1.21.11,1.21.12) |
| 26.1.2 | neoforge | 25 | neo 26.1.2.30-beta, mdg 2.0.141 | [26.1,26.2) |
| 26.2 | neoforge | 25 | neo 26.2.0.1-beta, mdg 2.0.141 | [26.2,26.3) |

## Open gaps / decisions

- 1.20.0 Forge: no cell yet (Fabric 1.20 already covers 1.20.0). Add a Forge floor cell only if Forge wanted at 1.20.0.
- 1.20.1 NeoForge: currently served by the Forge 1.20.1 jar (Forge-API compatible); decide whether to add a dedicated NeoForge 1.20.1 cell.
- 1.20.2-1.20.5: no cell between the 1.20 and 1.20.6 Fabric ranges -- confirm whether these need coverage.
- 26.3 NeoForge: NeoForge not released for 26.3 yet -- legitimate loader-floor gap (Fabric 26.3 only for now).
- 1.21 vs 1.21.1: Fabric+NeoForge ranges overlap ([1.21,1.21.2)) -- collapse to one cell each in the unified source.
