# M1 -- Machine One AI Interface

**M1 is a client-side Minecraft mod that exposes the real, running game client over a plain
localhost text socket.** It describes whatever screen or world is in front of the client as text,
and accepts text commands to drive it -- so a human at a terminal, or an AI over a socket, can
operate Minecraft entirely by typing. No GUI mouse/keyboard is required and nothing needs to be
"looked at" on screen; the window is there purely for observability.

Loaders / versions: Fabric + NeoForge, MC 26.x (this `main` branch is the entry point; the unified
source tree (MC 1.20 - 26.x, Fabric + NeoForge) lives on branch `minecraft-1.20-26.3`). Internal
tooling, all rights reserved.

> **New here? Read [the AI_Brain (`00_Index.md`)](https://github.com/Kishku7/m1/tree/minecraft-1.20-26.3/shared_common/src/main/resources/m1_ai_brain) first.** That is the file an AI agent
> is expected to load before a play session. This README is the reference manual behind it.

---

## 1. Popular Uses for M1

Most "AI plays Minecraft" projects never actually run Minecraft. They are protocol bots that speak
the server's network protocol from the outside (Mineflayer, and LLM agents built on it like
Voyager), or research stacks pinned to ancient versions (Malmo, MineRL). Because they never load
the real client, **they are blind to anything the client draws -- mod GUIs simply do not exist to
them**, and a version or mod mismatch breaks them.

M1 *is* the real client. It loads as an ordinary Fabric/NeoForge mod, reads every real screen, and
drives the game the way you would. That unlocks what the protocol bots cannot touch:

1. **Mod development & real-client testing.** Test your mod the way a player actually experiences
   it -- open its screens, click its buttons, read its inventories, drive its menus, grab
   screenshots -- all by text. M1 has served as the live test harness across **20+ release builds
   spanning many Minecraft versions and both loaders**, so you validate a mod across the whole
   range, not one pinned version.
2. **AI agents that genuinely play.** Point any LLM at a text socket and let it go -- proven
   end-to-end: spawn, find a tree, mine, craft, and build a wooden axe, entirely over the wire --
   and because it is the real client, your agent can operate *mod* GUIs too, not just vanilla
   movement.
3. **Hands-free / accessible play.** Run the whole game -- menus, worlds, inventory, crafting -- by
   typing. Nothing on screen needs to be looked at.
4. **Automation & scripting.** Repeatable command sequences for the tedious stuff (gather, craft,
   navigate, set up a world), from any language, in plain text -- with auto armor-upgrade built in.

No protocol reverse-engineering, no pixel pipeline, no RL training, no lock-in to one AI model or
one Minecraft version -- just plain text into the real game.

---

## 2. Connecting

When the modded client starts, M1 opens a TCP server on **`127.0.0.1:26000`** on the machine
running the client. It binds loopback only -- there is **no new inbound network surface**; only
processes already on that machine can reach it. Run one modded client at a time (every build binds
the same port).

The channel is the same whether a person types it or an AI sends it -- one grammar, one code path.
Anything an AI does is reproducible by hand and vice versa.

### Wire protocol

* **Newline-delimited.** Send one command per line (`<command>\n`). The reply is one or more lines
  of text, terminated by a single sentinel line: **`<<END`**.
* **A greeting is sent on connect** -- `Connected to Machine One (M1). Type START to begin, or HELP for commands.` followed by a `<<END`. Read and
  discard it before sending your first command.
* **After an action command (`click` / `type`)** the reply also contains a `--- now ---` marker
  followed by a fresh `describe` of the resulting screen (the mod waits ~150 ms for any
  transition/fade to settle, then auto-describes). So every action tells you the new situation.
* **Auto-upgrade notices** (see the armor section) arrive **unsolicited**, prefixed
  `[auto-upgrade]`, prepended to the next reply you receive.
* **Each command has a 10-second execution cap.** This is how long M1 waits for a command's
  handler to finish **on the game's main thread** and hand back a reply. If a handler overruns,
  M1 returns `ERR exec: ... TimeoutException` rather than leaving your connection hanging. **The
  cap is on the handler returning a reply -- it is NOT a limit on how long an in-world action
  takes.** The asynchronous commands (`move` / `moveto` / `goto` / `mine` / `craft`) return almost
  instantly with `OK ... poll 'where'` and then run in the background, so a two-minute walk or a
  long mine never hits the cap -- only a genuinely stalled or hitched client would. Practical
  consequence: set your socket read timeout **above** 10 s (e.g. 15 s) so your reader outlasts the
  server's own cap and actually receives that `ERR` line instead of tripping its own timeout first.
* Each command runs on the client's main thread, so the game stays consistent.
* `quit` / `exit` closes your connection (it does **not** stop the client).

### Interfacing tips

**For a quick manual poke** (a human at a shell on the client machine): any line-oriented TCP tool
works -- `telnet 127.0.0.1 26000`, `nc 127.0.0.1 26000`, or PowerShell's `TcpClient`. Type `help`,
then `describe`. This is great for exploring, but raw `telnet` does not understand the `<<END`
sentinel -- you just eyeball where each reply ends.

**For programmatic / AI use**, use a **persistent, buffered socket reader**. The two mistakes that
break clients:

1. Reading a fixed number of bytes instead of reading **until `<<END`**. Replies vary in length.
2. Throwing away bytes that arrive **after** the sentinel. TCP does not respect message
   boundaries; a single `recv` can contain the tail of one reply and the head of the next. Keep a
   buffer, slice off everything up to the first `<<END`, and **retain the remainder** for the next
   read -- otherwise you desync and every later reply is misaligned.

A minimal, correct Python reader:

```python
import socket

s = socket.create_connection(("127.0.0.1", 26000), 6)
s.settimeout(15)            # > the server's 10s command cap
buf = b""

def read_reply():
    global buf
    while b"<<END" not in buf:
        chunk = s.recv(4096)
        if not chunk:
            break
        buf += chunk
    i = buf.find(b"<<END")
    out, buf = (buf[:i], buf[i + 5:].lstrip(b"\r\n")) if i >= 0 else (buf, b"")
    return out.decode("utf-8", "replace").rstrip()

def cmd(line):
    s.sendall((line + "\n").encode())
    return read_reply()

read_reply()                # consume the greeting first
print(cmd("describe"))
```

If you are driving from another machine, do not expose the port -- get a shell on the client
machine (SSH, a relay agent, etc.) and connect to `127.0.0.1:26000` from there. The mod stays
loopback-only by design.

---

## 3. The AI_Brain

[the AI_Brain (`00_Index.md`)](https://github.com/Kishku7/m1/tree/minecraft-1.20-26.3/shared_common/src/main/resources/m1_ai_brain) (in this branch) is the **standing brief an AI reads in
before a session.** It is deliberately on `main` so it travels with every branch and version, and
so any operator can read it. It is portable -- it contains the transferable rules, not any one
machine's launch/control specifics (those stay in internal infra docs).

It covers:

* **Role and scope** -- the agent's only job is to play through M1; it ignores unrelated tooling.
* **Connecting** -- the socket, the protocol, and the read-until-`<<END` discipline above.
* **Operating discipline** -- the habits that make autonomous play work, and that the command
  outputs are designed to support:
  * *Look before you move* -- run `scan` + `where` and actually read them before acting.
  * *Move with pathfinding* -- give a destination (`moveto` / `goto` / `move`); the mod routes
    around walls and steps/jumps up for you. Never enable the game's Auto-Jump, never try to jump
    manually.
  * *Do not repeat what failed* -- if a move comes back `blocked` / `timeout` / `no path`, stop,
    re-`scan`, and pick a new or closer target. Never bump the same wall twice.
  * *Verify before you act* -- confirm a target actually meets the goal first (e.g. `BOUNDS UP` is
    air/grass before treating a spot as "outside").
  * *Use what you know* -- apply given context (e.g. "trees are to the southeast") to choose a
    direction.
* **The command quick-reference**, plus **how to read a `scan`** and **how to read the two slot
  numbering systems** -- the parts an agent gets wrong without guidance (detailed in section 4).
* **Recording** -- how/when to append to a session log (on request only).

The interpretive glue matters as much as the raw commands: section 3 marks, for the non-obvious
outputs, **how the instruction file tells an agent to read them.**

---

## 4. Command reference

Send any verb on its own line. `help` prints the live list. Aliases are shown in parentheses.
Bracketed `[...]` args are optional. Coordinates are absolute world coordinates.

### Screen / menu navigation

| Command | What it does |
|---|---|
| `describe` (`screen`) | Enumerate the current screen and **every** widget, recursively (tab bars, nested config panels). |
| `click <id>` | Click the widget with that id (then auto-describes the result). |
| `type <id> <text>` | Set the text of an `EditBox` widget. |
| `slots` | List the slots of the currently open container. |
| `worlds` | List saved worlds (on the world-select screen). |
| `joinworld <idx>` (`join`) | Enter/re-enter saved world number `idx`. |
| `pause` | Open the pause menu (then `describe` + `click` "Save and Quit to Title" to save+exit). |

**`describe` example** -- each widget is `[id] Type "label" pos=(x,y) size=WxH FLAGS`, where the
three flags are **V**isible / **A**ctive / **F**ocused (a `-` means off). An `EditBox` also shows
`value="..."`.

```
screen: TitleScreen  title: "Minecraft"
widgets: 5
  [0] Button "Singleplayer" pos=(286,98) size=200x20 VA-
  [1] Button "Multiplayer"  pos=(286,122) size=200x20 VA-
  [2] Button "Options..."   pos=(286,146) size=98x20 VA-
  [3] ImageButton "Language" pos=(265,146) size=20x20 VA-
  [4] Button "Quit Game"    pos=(389,146) size=98x20 VA-
```

**`click` example** -- `handled=true` means the screen consumed the click (a real interaction);
`false` usually means you clicked a decorative/inactive widget. Note the auto-describe:

```
OK click 0 "Singleplayer" handled=true
--- now ---
screen: SelectWorldScreen  title: "Select World"
widgets: 6
  [0] EditBox "search" pos=(...) size=... VA- value=""
  [1] Button "Play Selected World" pos=(...) ...
  ...
```

**`type` example** -- targets the box by id; fires the field's responder (e.g. the world-seed
handler):

```
OK type 0 -> "myseed"
--- now ---
...
```

> `type` only works on an `EditBox`. Aiming it at another widget returns
> `ERR widget 3 is Button, not an EditBox`.

**`worlds` / `joinworld`** -- world rows live in a selection list (not the widget list), so they
have their own commands:

```
world entries:
  [0] M1Garden, Survival Mode, version 26.1.2
  [1] New World, Creative Mode, version 26.2
```
```
joinworld 0   ->   OK joining world #0
```

### World state & perception

| Command | What it does |
|---|---|
| `where` (`state`) | Player position, facing, health, food, dimension -- and live movement status while moving. |
| `look` | What the crosshair is pointed at right now. |
| `scan [r\|<name>]` | Line-of-sight perception (default and max radius 32). See "Reading a scan". |
| `inv` (`inventory`) | Held item + everything you carry, by stable inventory index. |
| `cmd <command>` | Run a server command (no leading slash). Needs cheats in the world. |

**`where` example:**

```
pos=(120.50, 71.00, -8.30) facing=south yaw=2.0 pitch=10.0 health=20.0 food=20 dim=minecraft:overworld
```
While a move is running it appends a live progress tail; once finished it shows the outcome:

```
pos=(124.10, 71.00, -4.90) facing=southeast yaw=-44.0 pitch=0.0 health=20.0 food=20 dim=minecraft:overworld  | moving -> (130.0,-2.0) wp 7/19 dist 8.6
...
pos=(130.00, 71.00, -2.00) ...  | last move: arrived
```

> **How the instruction file reads this:** `moving -> (x,z) wp i/N dist D` means waypoint `i` of
> `N`, `D` blocks from the goal -- poll it to watch progress. `arrived` = done. `blocked` /
> `timeout` = stuck: **stop, re-scan, pick a new target** -- do not re-fire the same move.

**`look` example** -- reports the block (or entity) under the crosshair, the face hit, and the
distance; `look: nothing in reach` if the crosshair is on nothing:

```
look: block minecraft:oak_log at (15,71,-8) face=north dist=2.4
```

**`scan` example** -- four labelled sections, **line-of-sight only** (it never sees through walls;
you cannot path to what you cannot see):

```
SCAN pos=(120,71,-8) facing=SOUTH r=32 (line-of-sight)
BOUNDS  N:stone 1  S:open >32  E:oak_log 3  W:stone 1  UP:open >32  DOWN:stone 1
VISIBLE oak_log (15,71,-8) E 3 +more | oak_leaves (14,73,-9) E 4 | water (122,69,3) S 11
MOBS   cow x2 (124,71,-4) SE 5 | zombie (139,72,2) SE 19
ITEMS  oak_log (15,71,-8) E 3
```

Reading the non-obvious bits (this is exactly what the instruction file teaches an agent):

* **`BOUNDS`** is the nearest surface straight along each of the 6 axes (N S E W UP DOWN), with
  distance -- your "am I boxed in / what's the floor and ceiling" read. **`open >32`** means
  nothing was hit within range that way. `BOUNDS UP: open >32` is the standard "I'm outside / under
  open sky" check.
* **`VISIBLE`** lists notable blocks in sight, grouped and sorted nearest-first, each as
  `name (x,y,z) BEARING dist`. Common filler (stone, dirt, grass, gravel, sand, leaves-grass,
  etc.) is **hidden here on purpose** -- use `BOUNDS` for those. **`+more`** means you also saw
  other blocks of that same type (only the nearest is listed). Coordinates are absolute -- feed
  them straight into `goto`.
* **`MOBS` / `ITEMS`** are living entities and dropped items in sight. **`xN`** is the count of
  that kind (e.g. `cow x2`).
* **Bearings** are absolute compass: `N S E W NE NW SE SW`, or `UP` / `DOWN` when something is
  essentially straight above/below.

**Targeted scan** -- `scan <name>` returns the nearest visible block or entity whose id contains
`<name>`; `scan <r>` overrides the radius:

```
scan logs   ->   oak_log (15,71,-8) E 3 +more
scan diamond ->  scan diamond: none visible within 32
scan 16     ->   (a full SCAN, but only out to 16 blocks)
```

**`inv` example** -- `held: hotbar[N]` is your selected hotbar slot; items are listed by a
**stable** inventory index (see slot numbering below):

```
held: hotbar[0] = 1x Wooden Axe
items:
  [0] 1x Wooden Axe
  [9] 12x Oak Log
  [10] 6x Oak Planks
```

**`cmd` example** -- sends a server command as if you typed `/...` (cheats required):

```
cmd time set 6000   ->   OK sent: /time set 6000
```

### Movement

| Command | What it does |
|---|---|
| `moveto <x> <z>` | Pathfind + walk to x,z (Y follows terrain). |
| `goto <x> <y> <z>` | Pathfind + walk to full coordinates. |
| `move <dir> <n>` | Pathfind + walk `n` blocks in a compass direction. |
| `face <dir\|yaw [pitch]\|x y z>` | Set facing: a compass word, a raw yaw (+optional pitch), or look-at a coordinate. |
| `stop` | Stop moving (and stop mining). |

All three move verbs compute a **real vanilla A\* path** (the same navigation villagers use, via an
un-ticked proxy mob) and follow it waypoint by waypoint -- routing **around** walls and
**stepping/jumping up** automatically. You do not time key-holds and you do not jump yourself.

```
move se 8     ->   OK move se -> (126.3,-2.3) via 9 waypoints. poll 'where'.
moveto 130 -2 ->   OK moveto -> (130.0,-2.0) via 19 waypoints. poll 'where'.
goto 15 71 -8 ->   OK goto -> (15.0,-8.0) via 12 waypoints (partial, will re-route). poll 'where'.
```

> Obscure outputs: **`via N waypoints`** = path length found. **`(partial, will re-route)`** means
> only part of the route was reachable up front; the mod will recompute as it goes. If the target
> is unreachable you get **`no path to (x,z) -- re-scan and pick a closer/clearer point`** straight
> away -- the instruction file treats that as a hard "pick a different target" signal.

`face` examples:

```
face south        ->   OK facing south (yaw 0.0)
face 90           ->   OK yaw=90.0
face 15 71 -8     ->   OK looking at (15.0,71.0,-8.0) yaw=-12.3 pitch=8.7
```
> Compass mapping in this engine: **yaw 0 = south**, and dirs are
> `north/south/east/west/ne/nw/se/sw`. Resting view is `face 0 0` (level, facing south).

### Mining

| Command | What it does |
|---|---|
| `mine [x y z]` | Mine the crosshair block, or the block at `x y z` (faces it for you first). |

```
mine 15 71 -8   ->   OK mining minecraft:oak_log at (15,71,-8). poll 'inv'/'look'.
mine            ->   mine: not looking at a block (face it first, or 'mine x y z')
```
> Mining holds the *attack* key function, which only breaks blocks when the game window has the
> mouse grabbed (true on a focused in-game window) and no menu is open.

### Inventory & crafting

| Command | What it does |
|---|---|
| `openinv` | Open the inventory screen (the 2x2 crafting grid). |
| `close` | Close the open screen. |
| `hold <0-8>` | Select a hotbar slot (0-based; `hold 0` = hotbar slot 1). |
| `equip <item>` | Move an item (id substring) into your hand. Requires an open container. |
| `place` | Use/place the held item on the block you are looking at (places a block, opens a table, presses a button, uses a bed, etc.). |
| `craft planks\|sticks\|table\|axe` | Craft an item (asynchronous; poll `inv`). |
| `slot <id> [btn] [pickup\|quick\|swap]` | Low-level raw slot click by the **open menu's** index. |

```
openinv            ->   OK inventory open (2x2 grid, containerId=2)
hold 0             ->   OK hold hotbar 0 = 1x Wooden Axe
equip crafting_table -> OK equipped crafting_table to hotbar 0
place              ->   OK place/use -> SUCCESS
craft planks       ->   OK crafting planks (poll 'inv')
slot 1 0 quick     ->   OK slot 1 btn 0 QUICK_MOVE
```

> **Crafting is grid-aware and tick-driven.** `planks` and `table` work in the 2x2 inventory grid;
> `sticks` and `axe` need a placed crafting table open (3x3). A result appears about one tick after
> ingredients are placed and only moves out with an empty hand -- the `craft` command handles that
> timing for you, so just poll `inv`.

### Agent layer, combat, storage & generic crafting (0.5.x)

The `agent` verb queues **autonomous, tick-stepped actions** on M1's agent engine: each subcommand
returns `queued ...` immediately, runs over later in-world ticks, and reports back asynchronously as
`[agent] <seq> <CLASS>: <text>` lines on subsequent replies. Poll `agent status`.

| Command | What it does |
|---|---|
| `agent status` / `agent ping` | Engine status / end-to-end probe. |
| `agent goto <x y z>` / `agent moveto <x z>` / `agent patrol <x z ...>` | Queued pathfinding movement. |
| `agent look <x y z\|yaw [pitch]>` / `agent mine <x y z>` / `agent use` / `agent place` | Aim, break, use/place. |
| `agent hold <0-8>` / `agent equip <item>` / `agent slot <id> [btn] [mode]` | Inventory verbs as plan steps. |
| `agent drop [all]` | Drop 1 (or the stack) of the held item on the ground. |
| `agent jump` / `agent sneak [on\|off]` / `agent sprint [on\|off]` | Movement-key leaves (KeyMapping-driven). |
| `agent openinv` / `agent close` | Open the 2x2 inventory / close the screen, as plan steps. |
| `agent attack [nearest\|<id>\|crosshair] [crit\|normal]` | Engage a mob: auto-approach + timed jump-crits. |
| `agent follow <player> [dist]` | Lock onto a player and keep pace (sprint catch-up, portals) until `stop`. |
| `defend [on\|off\|status\|auto\|<player>]` | Auto-defense reflex: if you or the protectee is hit, the mod engages the attacker, then resumes the previous plan. |
| `agent shield [ticks]` | Raise/hold a shield. |
| `agent stop` / `stop` | Clear the plan, stop moving/mining, release sneak/sprint. |

**Bank Vault storage** (requires the [Bank Vault](https://modrinth.com/mod/bank-vault) mod, 26.x):
`vault mark [x y z]` (remember the vault block), `vault status`, `vault contents [filter]` (reads the
open vault and records a **per-world storage memory**), `vault withdraw <n> <item>`,
`vault deposit rows|all|<item>` (`rows` never touches the hotbar -- the hotbar is the keep-list),
`vault find <item>` / `vault memory` (query what was last seen where), and **`agent vault close`** --
a guarded close that reopens the marked vault, verifies worn trinkets against the session snapshot,
and re-equips anything the known Trinkets eject-on-close bug knocked into the inventory.

**Generic crafting** (26.x): `cancraft <item>` answers "can I make this right now?" from the live
recipe book -- YES with the recipe, or NO with per-ingredient HAVE/NEED lines annotated
`(in vault: <id>)` and `(craftable)`. `agent craft <item> [count]` crafts ANY recipe-book recipe:
open the 2x2 or a crafting table, and it picks a satisfiable recipe, auto-fills the grid, collects
the result, and **auto-withdraws missing ingredients from your Bank Vault** when storage memory
knows they are there. (Furnace/smithing/stonecutter and auto-crafting of intermediates are planned.)

The AI_Brain command card (`m1_ai_brain/10_command_card.md`, shipped in the jar) is the always-current
syntax reference for everything above.

### Gear / auto armor-upgrade

| Command | What it does |
|---|---|
| `openpack` | Open your worn Travelers Backpack (fires its keybind in isolation). |
| `upgrades` | Re-show any pending auto-armor-upgrade messages. |
| `autoupgrade on\|off` | Toggle automatic armor upgrading (default on). |

**Auto-upgrade runs by itself** every ~5 s with no GUI open: it equips the best armor you are
carrying per slot, by the tree
`nothing < gold(bare-slot-only) < leather < copper < chainmail < iron < diamond < netherite`
(ties break on more remaining durability; gold only fills an empty slot and is replaced as soon as
a real tier is found). You do **not** manage armor. When it swaps something it tells you on your
**next** reply:

```
[auto-upgrade] CHEST: leather_chestplate -> iron_chestplate
[auto-upgrade] FEET: nothing -> golden_boots
```
```
upgrades        ->   no upgrades pending
autoupgrade off ->   OK auto-upgrade OFF
```

> `openpack` exists because a worn backpack's screen is opened by a keybind, not a widget. M1 finds
> the backpack key mapping and triggers it **in isolation** (temporarily rebinding it to a scratch
> key) so a shared physical key -- e.g. a minimap and the backpack both bound to `B` -- does not
> co-fire. Reply: `OK triggered key.travelersbackpack.inventory in isolation -- poll 'describe'`.

### Utility

| Command | What it does |
|---|---|
| `screenshot [name]` (`shot`) | Save a PNG of the current frame via the game's own writer. |
| `help` | Print the command list. |
| `quit` / `exit` | Close this connection (does not stop the client). |

```
screenshot scene1 -> OK screenshot scene1.png (148213 bytes) path=<gameDir>/screenshots/scene1.png
```

---

## 5. Slot numbering

There are **two** numbering schemes and mixing them up is the most common slot bug.

**`inv` indices are STABLE** -- they never change, no matter what screen is open. Use them for
`hold` / `equip` and for knowing what you carry:

```
0-8    hotbar, left to right   (hotbar slot N in-game = inv index N-1)
9-35   main storage (9-17 top row, 18-26 middle, 27-35 bottom)
36 boots | 37 leggings | 38 chestplate | 39 helmet | 40 off-hand
```

**`slots` indices RENUMBER for every open container** -- always run `slots` to read them live,
never assume. Your own storage is re-indexed and appended **after** the container's own slots:

```
Inventory open (openinv, 2x2):  0 result | 1-4 the 2x2 grid | 5 helmet 6 chest 7 legs 8 boots
                                | 9-35 main | 36-44 hotbar | 45 off-hand
Crafting table open (3x3):      0 result | 1-9 the 3x3 grid | 10-36 main | 37-45 hotbar
Any other container:            container slots first (0..N-1), then main (27), then hotbar (9)
```

`slot <id> ...` clicks by the **open menu's** index (from `slots`), not the `inv` index. The
craft grids exist only while a screen is open, so they appear in `slots`, never in `inv`.

---

## 6. Operating notes & safety

* **Client-only.** M1 never runs server-side logic. `cmd` is just sending a normal client-to-server
  command, exactly like a player typing `/time`.
* **Never cold-kill a loaded world.** To leave a world, `pause` then `click` "Save and Quit to
  Title" -- that saves first.
* **Asynchronous commands** (`move*`, `goto`, `mine`, `craft`) return immediately; poll
  `where` / `inv` / `look`. `stop` aborts movement and mining.
* **Coverage ceiling, honestly:** M1 enumerates well-structured, widget/container-based GUIs.
  Mods that hand-draw their UI in immediate mode (paint a texture, hit-test a hardcoded rectangle)
  have no widget object to list -- the visible window is the instrument for spotting those gaps.

---

## 7. Repo layout

`main` is the entry point (this README). The unified buildable source -- one tree spanning MC
1.20 - 26.x for Fabric + NeoForge -- lives on branch
[`minecraft-1.20-26.3`](https://github.com/Kishku7/m1/tree/minecraft-1.20-26.3):

* `shared_minecraft/` -- the MC-coupled observe+actuate engine (single source of truth), `srcDir`'d
  into the per-loader `Fabric*/` and `NeoForge*/` cells.
* `shared_common/` -- MC-agnostic code + resources, including the **AI_Brain** operating brief that
  ships inside every jar and extracts to `config/M1_AI_Brain/<version>/` at runtime:
  https://github.com/Kishku7/m1/tree/minecraft-1.20-26.3/shared_common/src/main/resources/m1_ai_brain
* Build with `build-all-fabric.ps1` / `build-all-neoforge.ps1`; jars land in `dist/`.