# M1 -- Build & Setup Guide (`minecraft-1.20-26.3` branch)

This branch is the unified source tree for **M1 -- Machine One AI Interface**: one shared codebase
that builds every shipped jar -- Fabric, NeoForge, and Forge -- across Minecraft 1.20 - 26.x.

- **What M1 is + the full command reference:** [landing page (`main`)](https://github.com/Kishku7/m1/tree/main)
- **Download (players):** https://modrinth.com/mod/m1-machine-one-ai-interface
- **Report issues / support:** https://github.com/Kishku7/mod_support/issues

---

## Setup -- install M1 and drive it from Claude Desktop

Three pieces: **(1)** the M1 mod in your game, **(2)** the **MCP-Minecraft** bridge (this repo, under
[`mcp-minecraft/`](https://github.com/Kishku7/m1/tree/minecraft-1.20-26.3/mcp-minecraft)), **(3)** an
MCP client (e.g. Claude Desktop) pointed at the bridge.

**1. Install the mod.** Get the jar for your loader + MC version (a release, or build it -- below) and
drop it in your instance's `mods/` folder with the matching loader (Fabric Loader + Fabric API, or
NeoForge, or Forge). Launch and load a world. M1 is **client-side only** -- no server mod needed. On
load it opens its socket on `127.0.0.1:26000` and, first run, extracts the AI_Brain docs.

**2. Run the MCP-Minecraft bridge.**

```bash
git clone https://github.com/Kishku7/m1.git
cd m1/mcp-minecraft
npm install && npm run build
```

Configure it for M1 (copy `.env.example` to `.env`):

```
TARGET_PORT=26000
REPLY_SENTINEL=<<END
CONNECT_INIT=RAW ON
DISCARD_CONNECT_BANNER=true
```

Start it with `npm start`. It auto-connects when the game socket opens and reconnects if the game
restarts, so start order does not matter. It prints its endpoint (default `http://localhost:26001/mcp`)
and a `/health` URL. Full options + running as a service: [`mcp-minecraft/README.md`](https://github.com/Kishku7/m1/tree/minecraft-1.20-26.3/mcp-minecraft).

**3. Add it to Claude Desktop.** Edit `claude_desktop_config.json` (Settings -> Developer -> Edit
Config). The bridge serves Streamable HTTP, so bridge it with `mcp-remote`:

```json
{
  "mcpServers": {
    "mcp-minecraft": {
      "command": "npx",
      "args": ["-y", "mcp-remote", "http://localhost:26001/mcp"]
    }
  }
}
```

Restart Claude Desktop. You get three tools -- `send_command`, `listen` (streams `[chat]`/`[alert]`/
`[agent]` events), and `connection_status`. Ask Claude to send `where`; if you are in a world it
reports your position, and you are driving Minecraft from chat. (The full command grammar + wire
protocol are on the [landing page](https://github.com/Kishku7/m1/tree/main).)

> Bridge on a different machine than Claude Desktop? Set `BIND_HOST=0.0.0.0` and use that machine's
> LAN address in the URL. There is **no authentication** -- trusted network only.

---

## What you need installed (to build)

- **JDKs (Eclipse Adoptium):** JDK **17** for the Forge 1.20.1 cell + all pre-1.20.6 cells; JDK **21**
  for MC 1.20.6 - 1.21.11 (all loaders); JDK **25** for the 26.x line.
- **Python 3** + **Cog** (`pip install cogapp`) -- the codegen for pre-26 Fabric and the SRG-runtime
  Forge / early-NeoForge cells.
- **Gradle 8.13** is pinned in the NeoGradle cells' wrappers (NeoGradle 7.x dies on Gradle 9.x).
- **PowerShell 7** for the `scripts/` build helpers.

---

## Repo layout

- `shared_minecraft/` -- the MC-coupled observe+actuate engine (single source of truth), `srcDir`'d
  into the per-loader `Fabric/<ver>`, `NeoForge/<ver>`, and `Forge/<ver>` cells.
- `shared_common/` -- MC-agnostic code + resources, including the **AI_Brain** operating brief that
  ships in every jar and extracts to `config/M1_AI_Brain/<MAJOR.MINOR>/` on first run.
- `_codegen/` -- the Cog drift brain (`compat.py`) + cog sources.
- `<Loader>/<mc-version>/` -- thin per-version build cells. `scripts/` -- build helpers (gitignored).
  `dist/` -- built jars (gitignored).

---

## Version coverage & gaps (v0.12.0)

One jar per supported (loader, MC-line). Coverage is honestly bounded by what each loader shipped:

| Loader   | Covered MC versions |
|----------|---------------------|
| Fabric   | 1.20 - 26.3 (every line) |
| NeoForge | 1.20.1 - 1.20.4, 1.20.6 - 26.2 |
| Forge    | 1.20.1 - 1.20.4, 1.20.6, 1.21 - 1.21.1, 1.21.3 - 1.21.11 (FG6) |

The only real gaps are structural loader absences:

* **Forge 1.20.5 and 1.21.2** -- Forge never shipped a build (1.20.4 -> 1.20.6, 1.21.1 -> 1.21.3). Permanently skipped.
* **Forge 26.x** -- FG6 cannot build the unobfuscated 26.x line (26.x is Fabric + NeoForge only).
* **NeoForge 1.20.5** -- NeoForge 20.5 is beta-only and does not resolve; skipped.
* **NeoForge 26.3** -- NeoForge has not released a build for MC 26.3 (Fabric 26.3 only for now).
* **Forge 1.20.0** -- M1 fails to load on forge 46; junked (trivial -- Fabric 1.20 covers 1.20.0).

The 1.21 and 1.21.1 lines are served by a single 1.21 cell per loader (shared API window).

---

## How the code generation works

M1 keeps ONE business source (`shared_minecraft` + `shared_common`) and bridges per-version/loader API
drift two ways:

- **Reflection facades (`*Compat`)** where the runtime is **mojmap** -- MC 26+ (all loaders), and
  Forge/NeoForge from ~1.21 on. A mojmap-name lookup resolves directly, so the shared source compiles
  and runs unchanged.
- **Cog (`cogapp`) direct-access `gen/` trees** where the runtime is **NOT mojmap** -- pre-26 Fabric
  (intermediary) and the early Forge / NeoForge 1.20.1 - 1.20.4 line (SRG). There a mojmap reflection
  string MISSES at runtime, so `cog-gen.ps1` generates a direct-compiled `gen/` tree that the loader
  remaps at load. Any cell whose `build.gradle` srcDir's `gen` is cog-generated before building.

Toolchain per loader (the non-obvious part):

- **Fabric:** loom. Pre-26 cells are cog-gen'd (intermediary runtime); 26.x builds direct (mojmap).
- **NeoForge:** **ModDevGradle** for neo >= 20.6; **NeoGradle 7.0.192** + legacy `META-INF/mods.toml`
  + cog `gen/` for the SRG early line neo 20.2 - 20.4 (MDG publishes no moddev-bundle below neo 20.4);
  **ForgeGradle 6** against the `net.neoforged:forge` artifact for NeoForge 1.20.1 (a Forge 1.20.1 fork).
- **Forge:** ForgeGradle 6 (FG6). The 1.20.1 cell (SRG) is cog-gen'd; mojmap cells build direct.

**Never Architectury.** Full per-version boundary + loader-floor tables live in the maintainer's
`minecraft/version-gates.md`.

---

## Building

Each per-version cell is a standalone Gradle build:

```
cd <Loader>/<mc-version> && ./gradlew build      # SRG cells: cog-gen first
```

The per-loader `scripts/build-all-*-cells.ps1` walk every cell, cog-gen where needed, build, and
record PASS/FAIL; jars land in `dist/`.

**Manifest metadata is single-source.** Shared manifest fields (the issue-tracker URL) come from
`scripts/_metadata.py` -- one canonical constant. `python scripts/_metadata.py stamp` writes it into
every manifest; `check` fails if any drift. Run `check` before every build/publish.

---

Internal tooling. All rights reserved (ARR).
