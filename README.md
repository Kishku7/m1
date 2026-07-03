# M1 -- Machine One AI Interface

Client-side Minecraft mod that exposes the live game client over a localhost text socket, described
and driven entirely by text -- so a human at a terminal, **or an AI (e.g. Claude) over an MCP
connection**, can operate Minecraft by typing. The game window exists only so you can watch.

This branch (`minecraft-1.20-26.3`) is the **unified source**: one tree builds every supportable MC
release from **1.20 through 26.x** for **Fabric** and **NeoForge** (plus Forge on 1.20.1, where
NeoForge has none). The full reference manual -- complete command set, `scan`/inventory semantics,
worked examples -- lives in the [`main` branch README](https://github.com/Kishku7/m1/blob/main/README.md).

## What M1 can do (v0.9.x)

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

The exhaustive syntax + semantics live in the AI_Brain (see below) and the [`main` branch
README](https://github.com/Kishku7/m1/blob/main/README.md).

---

## Quick start -- drive Minecraft from Claude Desktop

You need three pieces: **(1)** the M1 mod in your game, **(2)** the **MCP-Minecraft** bridge from
this repo, and **(3)** Claude Desktop (or any MCP client) pointed at the bridge.

### 1. Install the mod

1. Grab the jar for your loader + MC version from a release, or build it (see
   [Building](#building) below). Fabric and NeoForge are both supported on 26.x.
2. Drop the jar in your instance's `mods/` folder alongside the matching loader (Fabric Loader +
   Fabric API, or NeoForge). Launch the game and load into a world.
3. On load, M1 opens its control socket on **`localhost:26000`** (loopback only) and, on first run,
   **extracts the AI_Brain operating docs to disk** (see [AI_Brain](#ai_brain----the-operating-brief)).

That's the whole game side -- M1 is client-side only, no server mod required.

### 2. Run the MCP-Minecraft bridge

The bridge is a small Node MCP server that lives in this repo under
[`mcp-minecraft/`](mcp-minecraft/). Clone the repo and build it:

```bash
git clone https://github.com/Kishku7/m1.git
cd m1/mcp-minecraft
npm install
npm run build
```

Configure it for M1 (M1 frames replies with a `<<END` sentinel, greets on connect, and wants
`RAW ON`). Copy `.env.example` to `.env` and set:

```
TARGET_PORT=26000
REPLY_SENTINEL=<<END
CONNECT_INIT=RAW ON
DISCARD_CONNECT_BANNER=true
```

Then start it (it auto-connects the moment the game's socket opens, and reconnects if the game
restarts, so the order you start things in does not matter):

```bash
npm start
```

It prints the MCP endpoint (default `http://localhost:26001/mcp`) and a `/health` URL. Full option
list + how to run it as a service (systemd / launchd / Windows NSSM) is in
[`mcp-minecraft/README.md`](mcp-minecraft/README.md).

### 3. Add it to Claude Desktop

Claude Desktop reads a JSON config (`claude_desktop_config.json` -- Settings -> Developer -> Edit
Config). MCP-Minecraft serves **Streamable HTTP**, so bridge it with `mcp-remote`:

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

Restart Claude Desktop. You'll get three tools: **`send_command`** (send one M1 command, get its
reply), **`listen`** (stream async `[chat]`/`[alert]`/`[agent]` events + keepalive), and
**`connection_status`**. Ask Claude to send `where` -- if you're in a world it should report your
position and surroundings, and you're driving Minecraft from chat.

> Running the bridge on a different machine than Claude Desktop? Set `BIND_HOST=0.0.0.0` on the
> bridge and use that machine's LAN address in the URL. There is **no authentication** -- keep it on
> a trusted network only (see the bridge README's Security note).

---

## AI_Brain -- the operating brief

The brief an AI reads before driving M1 ships **inside the jar** and is extracted **the first time
the game runs** to, relative to your game/instance directory:

```
config/M1_AI_Brain/<MAJOR.MINOR>/          e.g.  config/M1_AI_Brain/0.9/
```

Create-once per minor version -- your edits are preserved across restarts, and a
`100_User_Overrides.md` layer (shipped blank, never overwritten) overrides the numbered defaults.
An AI agent gets this path from the `START` command on connect; start reading at `00_Index.md`, a
router to the topic files (drive-M1, create-world, join-server, play, command-card, capabilities,
recovery, safety). Source of truth for the docs:
[`shared_common/src/main/resources/m1_ai_brain/`](shared_common/src/main/resources/m1_ai_brain).

## The wire protocol (for writing your own client)

If you talk to `localhost:26000` directly instead of through the bridge:

* One command per line (`<command>\n`). Replies are newline-delimited, terminated by a `<<END`
  sentinel line. Read until `<<END`, and keep any bytes after it for the next reply.
* On connect M1 sends a greeting (`Connected to Machine One (M1)...` then `<<END`) -- read and
  discard it. Send `RAW ON` (keeps the `<<END` marker, which a machine client wants); `RAW OFF`
  hides it for a human on a terminal.
* `START` returns the AI_Brain index path; `HELP` lists commands. Neither is a gate -- a client that
  knows the protocol can issue commands immediately.
* Unsolicited event lines are pushed even with no command pending (`[chat]`, `[alert]`, `[agent]`,
  `[auto-upgrade]`) -- a bare read/`listen` receives them.
* Each command has a ~10 s execution cap on its synchronous handler; the long actions
  (`move`/`moveto`/`goto`/`mine`/`craft`/`agent`) return immediately and run in the background, so
  set your socket read timeout above 10 s.

The bridge in [`mcp-minecraft/`](mcp-minecraft/) handles all of this for you (`REPLY_SENTINEL`,
`CONNECT_INIT`, banner discard) -- writing a raw client is only needed if you are not using MCP.

## Building

Bulk build scripts (PowerShell) build every cell and drop jars in `dist/`:

```
build-all-fabric.ps1        # all Fabric cells (1.20 -> 26.x)
build-all-neoforge.ps1      # all NeoForge cells
```

Requires a JDK (21 for the modern cells) and network access for the loader toolchains on first run.

## Layout

* `shared_minecraft/` -- the MC-coupled observe+actuate engine plus the `*Compat` reflection facades.
  Single source of truth; `srcDir`'d into each mojmap-runtime cell.
* `shared_common/` -- MC-agnostic Java (the `AiBrain` extractor) + resources, including the AI_Brain docs.
* `Fabric*/`, `NeoForge*/`, `Forge-1.20.1/` -- per-version cells; each holds only the loader
  entrypoint (`M1Client` / `M1NeoForge` / `M1Forge`). Mojmap cells (26 Fabric, all NeoForge/Forge)
  `srcDir` the shared folders directly; pre-26 Fabric cells build from a generated `gen/` tree
  (`cog-gen.ps1` + Cog) because their runtime is intermediary, not mojmap.
* `mcp-minecraft/` -- the generic MCP bridge (Node/TypeScript) that connects an MCP client to M1's
  text socket. MIT-licensed and Minecraft-agnostic; see its own README.
* `_codegen/` -- Cog sources/data for the generated cells. `matrix.json` -- the cell/version matrix.
