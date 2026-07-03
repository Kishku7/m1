# M1 -- Machine One AI Interface

Client-side Minecraft mod that exposes the live game client over a localhost text socket, described
and driven entirely by text -- so a human at a terminal or an AI over the socket can operate
Minecraft by typing. The window exists only for observability. Internal tooling, all rights reserved.

This branch (`minecraft-1.20-26.3`) is the **unified source**: one tree builds every supportable MC
release from **1.20 through 26.x** for **Fabric** and **NeoForge** (plus Forge on 1.20.1, where
NeoForge has none). The full reference manual -- complete command set, `scan`/inventory semantics,
worked examples -- lives in the [`main` branch README](https://github.com/Kishku7/m1/blob/main/README.md).

## What M1 can do (v0.9.0)

Everything is text in / text out over the socket. Highlights of the current command surface:

* **Perception:** `describe` (any screen), `scan` (line-of-sight world), `where`, `look`, `inv`,
  `slots` (role-labelled).
* **Navigation -- M1's own pathfinder:** `moveto`/`goto` to any coordinate at any distance; it
  opens gates + doors itself, walks rails and stairs, and routes in 16-block segments. `nav
  own|vanilla` picks the engine.
* **Combat (universal):** `attack [nearest|<id>|crosshair] [crit|normal|ranged]` -- auto-equips the
  best weapon, times crits, bow ballistics, spear stab, per-enemy tactics; `shield`, `follow`,
  `defend`. A SAFE-MOB doctrine never pre-empts neutral mobs (piglins, endermen, ...).
* **Inventory verbs (names, not indices):** `equip all`, `equip <item>`, `organize hotbar`,
  `takeall`, `stash junk`, `moveitem <item> to <hb1-9|offhand|head|chest|legs|feet>`.
* **Composites:** `sleep` (bed or backpack sleeping bag), `recover` (clear a death grave-site),
  generic `craft <item>` (RecipeManager-driven, vault-sourced).
* **Storage:** Bank Vault (`vault ...`, enchanted-gear `id#hash` withdraw) and Travelers Backpack
  (`pack on|contents|put|take`, code-level wear + batch move).
* **Autonomy:** the `agent` layer queues actions that run in-world and report back async; chat
  master control (`Who is your daddy` -> take orders from that player).

The exhaustive syntax + semantics live in the AI_Brain (below) and the [`main` branch
README](https://github.com/Kishku7/m1/blob/main/README.md).

## Connecting

The modded client opens a TCP server on **`localhost:26000`**, bound on BOTH `127.0.0.1` and `::1`
(loopback only -- no external surface), so `telnet localhost 26000` connects either way. One client
at a time.

Greeting on connect: `Connected to Machine One (M1). Type START to begin, or HELP for commands.`

* `START` -- returns the AI_Brain index-file path (for an AI agent) plus a human HELP/RAW tip.
* `HELP` -- lists the commands.
* `RAW OFF` -- hides the `<<END` end-of-reply marker (friendlier for a human on a terminal).

Replies are newline-delimited and terminated by a `<<END` sentinel line (unless RAW is off). START is
optional discovery, not a gate -- a client that knows the protocol can issue commands immediately.

## AI_Brain -- the operating brief

The brief an AI reads before driving M1 ships **inside the jar** and is extracted to
`config/M1_AI_Brain/<mod major.minor>/` on first run (create-once; user edits are preserved, and a
`100_User_Overrides.md` layer overrides the numbered defaults). Source of truth:
[`shared_common/src/main/resources/m1_ai_brain/`](https://github.com/Kishku7/m1/tree/minecraft-1.20-26.3/shared_common/src/main/resources/m1_ai_brain)
-- start at `00_Index.md`, a router to the topic files (drive-M1, create-world, join-server, play,
command-card, recovery, safety, ...).

## Layout

* `shared_minecraft/` -- the MC-coupled observe+actuate engine plus the `*Compat` reflection facades.
  Single source of truth; `srcDir`'d into each mojmap-runtime cell.
* `shared_common/` -- MC-agnostic Java (the `AiBrain` extractor) + resources, including the AI_Brain docs.
* `Fabric*/`, `NeoForge*/`, `Forge-1.20.1/` -- per-version cells; each holds only the loader
  entrypoint (`M1Client` / `M1NeoForge` / `M1Forge`). Mojmap cells (26 Fabric, all NeoForge/Forge)
  `srcDir` the shared folders directly; pre-26 Fabric cells build from a generated `gen/` tree
  (`cog-gen.ps1` + Cog) because their runtime is intermediary, not mojmap.
* `_codegen/` -- Cog sources/data for the generated cells. `matrix.json` -- the cell/version matrix.
* Build: `build-all-fabric.ps1` / `build-all-fabric-cog.ps1` / `build-all-neoforge.ps1`; jars land in `dist/`.
