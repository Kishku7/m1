# Command card (exact syntax cheat-sheet)
<!-- Valid as of: M1 v0.5.7 | MC 1.20 - 26.3 | updated 2026-07-02 -->
**Covers:** every M1 command with syntax + a one-line note, so you can reload just the syntax cheaply.
Concepts behind these live in `01_drive_m1.md`.

```
OBSERVE
  describe              list the current screen's widgets (id, type, message/label, pos, V/A/F flags)
  where                your position + movement status (pos / moving / arrived / blocked / no world)
  look                 what you are pointed at (block/entity + distance)
  scan [r|<name>]      line-of-sight scan; r<=32 (default 32). scan <name> = nearest match. scan 16 = radius 16
  inv                  stable player inventory (0-8 hotbar, 9-35 main, 36-40 armor/offhand)
  slots                slots of the OPEN menu (renumber per container -- always run live)

MENUS
  click <id>           click widget <id> from the latest describe; reply includes a `--- now ---` re-describe
  type <id> <text>     type into edit box <id> (e.g. world name, seed, server address)
  worlds               list saved single-player worlds (index + name)
  joinworld <idx>      load an EXISTING saved world by its `worlds` index

MOVE (async -- returns at once, poll `where`)
  moveto <x> <z>       pathfind to an x,z (routes around walls, steps up). Preferred mover
  goto <x> <y> <z>     pathfind to a full coordinate
  move <dir> <n>       move n blocks in a compass dir (north/south/east/west/ne/nw/se/sw)
  face <dir | x y z>   aim the camera (resting view = face 0 0; yaw 0 = south)
  stop                 cancel movement, mining, AND any queued agent plan (attack/follow)

ACT (async -- poll inv/look)
  mine [x y z]         mine the block you face, or the one at x y z
  place                place/use/interact the held item or the block you face
  hold <0-8>           select hotbar slot 0-8 (= in-game 1-9)
  equip <item>         equip a named item from inventory

COMBAT / FOLLOW (top-level; run on the agent engine, progress arrives as [agent] lines)
  attack [nearest|<id>|crosshair] [crit|normal]   engage a mob: WALKS to it then hits (auto-approach + timed crits). Default nearest, crit
  follow <player> [dist]   lock onto a player, keep within dist (default 3); the mod re-tracks them as they move -- issue ONCE, ends on stop
  defend [on|off|status|auto|<player>]   auto-defense reflex, ON by default: if the master (or the named
                       player) or I get attacked, the MOD immediately engages the attacker (no command
                       needed), leashed to 16m of the protectee, then RESUMES whatever it was doing
                       (e.g. follow). You will see [agent] lines: "hostile nearby", "X was hit by",
                       "defend: engaging/threat down/resuming". Do NOT queue your own attack while a
                       defend is running -- just watch the [agent] lines. 'defend off' disables.

CRAFT
  openinv              open your inventory (2x2 crafting)
  craft planks|sticks|table|axe   craft a known legacy recipe (prefer agent craft below)
  cancraft <item>      recipe-book feasibility: YES via <recipe>, or NO + per-ingredient
                       HAVE/NEED lines with "(in vault: id)" and "(craftable)" annotations
  close                close the open menu

STORAGE (Bank Vault -- 26.x, needs the bank-vault mod; vault = your player-bound bank)
  vault mark [x y z]   remember the vault block position (default: the block you face). Do this
                       once per session BEFORE closing -- the trinket guard needs it to verify
  vault status         is the vault screen open; kinds/items; trinket slot count; marked pos
  vault contents [f]   list contents (filter f) AND record them to per-world storage memory
  vault withdraw <n> <item>   withdraw n of item (BV /bank withdraw; arrives in inventory)
  vault deposit rows   deposit ALL main inventory rows -- the HOTBAR IS THE KEEP-LIST (kept)
  vault deposit all    deposit main rows AND hotbar
  vault deposit <item> deposit every player stack whose id contains <item>
  vault find <item>    search per-world storage memory (last-seen) for an item
  vault memory         storage-memory summary for this world
  NOTE open the vault by walking to it and `place` (use) with an EMPTY or non-placeable hand;
       it only opens as a COMPLETED 3x3 set. Close with `agent vault close` (guarded), not close

WORLD
  cmd <server command> run a slash-command (e.g. cmd time set day) -- needs cheats/permission
  say <text>           send an in-game CHAT message (use to acknowledge your master, e.g. say Ok)
  pause                open the pause menu (then click "Save and Quit to Title" to leave)

GEAR
  openpack             open a worn Travelers Backpack
  upgrades             re-show pending armor auto-upgrade notes
  autoupgrade on|off   toggle the armor auto-upgrade loop

UTIL
  screenshot [name]    capture a frame (saved on the client machine)
  help                 M1's own command help
  quit                 disconnect this M1 session

AGENT (autonomous action layer -- queue an action; it runs in-world and reports back async)
  agent status                        engine status: tick, idle, backlog, interrupt, lastSeq, budgetMs
  agent ping                          end-to-end probe -> a "pong" report
  agent goto <x> <y> <z>              queue a pathfind to a full coordinate
  agent moveto <x> <z>                queue a pathfind to x,z (Y follows terrain)
  agent patrol <x1> <z1> [x2 z2 ...]  queue a multi-leg patrol
  agent look <x y z> | <yaw [pitch]>  queue aim at a point, or at yaw[/pitch]
  agent mine <x> <y> <z>              queue breaking the block at x,y,z
  agent hold <0-8>                    queue selecting a hotbar slot
  agent equip <item>                  queue moving a named item to hand (needs an open container)
  agent use | agent place             queue use/place on the crosshair block
  agent attack [nearest|<id>|crosshair] [crit|normal]   queue an attack: auto-approaches then hits (default nearest, crit)
  agent follow <player> [dist]        lock onto a player and follow until stop (default dist 3)
  agent shield [ticks]                queue raising the shield (default 40t = 2s; alias: agent block)
  agent drop [all]                    queue dropping 1 (or the whole stack) of the HELD item on the ground
  agent jump                          queue a one-shot jump
  agent sneak [on|off]                sneak state toggle (agent stop releases it)
  agent sprint [on|off]               sprint state toggle (pathing may override while moving)
  agent slot <id> [btn] [mode]        queue a raw click on the OPEN menu slot (pickup|quick|swap)
  agent openinv | agent close         queue opening the 2x2 inventory / closing the open screen
  agent vault <sub>                   queue any vault subcommand (see STORAGE)
  agent vault close                   guarded vault close: closes, REOPENS the marked vault, verifies
                                      worn trinkets vs the session snapshot, re-equips any the
                                      Trinkets eject bug knocked into your inventory, closes again
  agent craft <item> [count]          GENERIC recipe-book craft: needs the 2x2 or a crafting table
                                      OPEN; picks a satisfiable recipe, auto-fills the grid, collects.
                                      AUTO-SOURCES missing ingredients from the vault (storage memory
                                      -> /bank withdraw) from anywhere. Shift-harvest may overshoot
                                      count. NOT the vault grid; furnace/smithing/stonecutter not yet
  agent stop                          clear the queued plan and stop movement + mining (and release
                                      sneak/sprint toggles)
```

Notes:
- **Async commands** (`move*`/`goto`/`mine`/`craft`) return immediately -- poll `where` / `inv` /
  `look` to track them. They do NOT hit the 10 s execution cap.
- `slot <id> ...` and `slots` use the OPEN MENU index; `hold` / `equip` use the stable `inv` index.
  Do not mix them (see `01_drive_m1.md` section 7).
- `worlds`/`joinworld` load an EXISTING world. CREATING a new world is menu-driving with
  `describe`+`click`+`type` (see `02_create_world.md`).
- This card tracks the code. If `help` shows a command not listed here, trust `help` and flag the drift.
- **`agent ...`** is the queued autonomous layer: each subcommand returns at once with `queued ...`, runs on later in-world ticks, and its result arrives as a drained `[agent] <seq> <CLASS>: <text>` line on a subsequent reply. Poll `agent status`. Only runs while in a world.
- **Unsolicited event lines are PUSHED to you** even with no command pending -- the server flushes them within ~200 ms, so a bare `listen` (read with no command sent) receives them. Kinds: `[alert] took X damage (health A -> B); nearest hostile ...` (you are being hit), `[agent] ...` (agent progress), `[auto-upgrade] ...` (armor). During combat/follow, `listen` between actions to catch them; check `where` (health) before declaring a threat handled.
