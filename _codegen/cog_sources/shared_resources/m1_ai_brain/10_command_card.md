# Command card (exact syntax cheat-sheet)
<!-- Valid as of: M1 v0.16.0 | MC 1.20 - 26.3 | updated 2026-08-02 (sign WRITING; m1srv server query; bulk mining: mine hold + mine area; attack give-up + tool restore; defend regroup; scan lists EVERY match with facing + paging; sign reading; open by coordinate; take output) -->
**Covers:** every M1 command with syntax + a one-line note, so you can reload just the syntax cheaply.
Concepts behind these live in `01_drive_m1.md`.

```
OBSERVE
  describe              list the current screen's widgets (id, type, message/label, pos, V/A/F flags)
  where                your position + movement status (pos / moving / arrived / blocked / no world)
  look                 what you are pointed at (block/entity + distance)
  scan [r]             line-of-sight situational scan; r<=32 (default 32). 'scan 16' = radius 16
  scan <name|id> [rN] [page]
                       FIND EVERY MATCH. Walks the block VOLUME, so it is NOT line-of-sight -- it
                       sees the back rows of a wall, which the old raycast scan could not. Returns
                       exact coords + facing + distance for each, plus any SIGN TEXT inline, 50 per
                       page ('scan furnace' then 'scan furnace 2'). rN sets radius (default 16).
                       USE THIS to enumerate a bank of chests/furnaces. Do NOT walk-and-rescan --
                       it silently skips things (missed 3 of 16 furnaces live, 2026-08-01).
  read [x y z]         read a SIGN, by coord or crosshair. Reads the block entity, so it works on
                       wall signs (a collider ray goes straight through them, which is why sign
                       labels used to be unreadable). 'scan sign' returns every sign's text at once.
  sign <x> <y> <z> <text>
                       WRITE a placed sign. Split lines with '|' -- 4 lines max, ~15 chars each
                       (longer is truncated). e.g. 'sign -80 69 23 Metals + Gems|ingots blocks'.
                       ALWAYS 'read <x y z>' afterwards to verify: a rejected write still replies OK.
                       To PLACE one first, hold a sign and see the sign recipe in the Notes below.
  m1srv <query>        ask the SERVER side (M1-Server, if the server runs it) instead of your client.
                       Server-authoritative answers your client cannot see. Only works where the
                       server has M1 installed; harmless to try -- it just reports unavailable.
  open <x> <y> <z>     open/use a container BY COORDINATE -- no aiming, no crosshair. Feed it the
                       coords 'scan' gave you, then 'slots'. Server reach (~4.4 from the eye) still
                       applies; too far and the reply tells you the distance -- walk, then re-issue.
                       PREFER THIS over face+place: close-range aiming is unreliable and will
                       happily open the chest NEXT to the one you meant.
  take output [radius] BULK: empty EVERY furnace/blast furnace/smoker in range. Walks them all,
                       takes the OUTPUT slot only, never touches input or fuel. One command instead
                       of six per furnace. [agent] progress; stops early if your inventory fills.
  inv                  stable player inventory. Slots are labeled in BOTH spaces: [hb6 (idx 5)]
                       for hotbar (hbN = as spoken, idx = raw 0-8), [inv 9..35] main,
                       feet/legs/chest/head (36-39), offhand (40)
  examine [slot|<item>]   FULL readout of one item: exact id, custom name, enchantments (+levels),
                       durability, advanced tooltip, non-default components, and its vault key
                       form (plain id vs id#hash special). Slot: hand|hb1-9|inv N|head|chest|
                       legs|feet|offhand, or a name/id to search all 41 slots.
                       "Does my sword have mending?" -> examine sword
  slots                slots of the OPEN menu, each labeled with its ROLE:
                       CRAFT-RESULT/CRAFT-GRID(no storage!)/armor:head|chest|legs|feet/
                       hotbar-N/main/offhand/container. NEVER park items in CRAFT slots

MENUS
  click <id>           click widget <id> from the latest describe; reply includes a `--- now ---` re-describe
  type <id> <text>     type into edit box <id> (e.g. world name, seed, server address)
  worlds               list saved single-player worlds (index + name)
  joinworld <idx>      load an EXISTING saved world by its `worlds` index

MOVE (async -- returns at once, poll `where`)
  moveto <x> <z>       pathfind to an x,z (routes around walls, steps up). Preferred mover
  goto <x> <y> <z>     pathfind to a full coordinate
  move <dir> <n>       move n blocks in a compass dir (north/south/east/west/ne/nw/se/sw)
  face <dir|up|down|x y z>  aim the camera (resting view = face 0 0; yaw 0 = south)
  sleep                ONE-SHOT composite: find a usable bed nearby, walk BESIDE it, sleep in it.
                       Fails with a reason (daytime / none found / unreachable / no effect)
  nav [own|vanilla|status]  pathfinder engine toggle. OWN is default: opens gates/doors itself,
                       walks rails and stairs, 16-block segmented paths to ANY distance
  stop                 cancel movement, mining, AND any queued agent plan (attack/follow)

ACT (async -- poll inv/look)
  mine [x y z]         mine ONE block: the one you face, or the one at x y z
  mine hold [on|off]   BULK: hold mouse-1 down continuously -- you break whatever the crosshair is
                       on. Steer with face / moveto / agent moveto and you TUNNEL as you walk.
                       Auto-releases after 5 min; 'mine hold off' or 'stop' releases it now.
                       Use this when you are cutting a corridor or a face and do not care about
                       exact block coordinates.
  mine area <x1 y1 z1> <x2 y2 z2> [nocollect]
                       BULK: clear an entire BOX as one queued job -- ONE command instead of one
                       per block. Clears top-down (never digs out from under itself), nearest
                       first inside each layer, SKIPS AIR CELLS FOR FREE (a hollow/ragged volume
                       costs nothing extra), walks itself closer when a cell is out of reach,
                       and never stalls on a cell it cannot break -- it reports and moves on.
                       Bedrock/barrier and liquids are never attempted. Progress arrives as
                       [agent] lines ("N broken, N air, N skipped, N left"). When it finishes it
                       WALKS THE DROPS IN (they land out of reach and despawn otherwise) unless
                       you pass 'nocollect'. Cap 65536 cells -- split bigger volumes.
                       'stop' cancels; re-issuing the same box resumes what is left.
  place | use | interact   place/use/interact the held item or the block you face.
                       A "Pass" result = NOTHING HAPPENED (wrong block/out of reach) -- re-aim
  hold <0-8>           select hotbar slot 0-8 (= in-game 1-9)
  equip all            ONE command: wear best armor + shield to off-hand + best weapon to hand
  equip <item>         by NAME or id, auto-routed: armor -> its slot, shield -> off-hand, else hand
  organize hotbar      standard layout: hb1 sword, hb2 pick, hb3 axe, hb4 shovel, hb5 hoe, hb9 food
  takeall              empty the OPEN container (chest/vault) into your inventory in one shot
  stash junk           move junk (rotten flesh/bones/spider eyes) off the hotbar
  moveitem <item> to <hb1..hb9|offhand|head|chest|legs|feet>   named move using HUMAN slot words
  drop [slot|<item>] [n|all]   THROW item(s) on the ground (the Q key). Default: 1 from hand.
                       Any source slot: hand|hb1-9|inv N|head|chest|legs|feet|offhand, or an item
                       name -- non-held sources are brought to hand automatically. n = that many
                       single throws; all = the whole stack. Alias: throw
  pack on              WEAR a Travelers Backpack from your inventory (code-level equip; no GUI)
  pack contents [f]    list the worn backpack contents (filter f)
  pack put <item|all|junk>   stash matching inventory items INTO the backpack (batch, quick)
  pack take <item> [n]       pull items OUT of the backpack into your inventory (batch)
  recover              GRAVE-SITE recovery composite: break the name sign, empty the chest, break
                       the armor stand, collect drops, then equip all + organize hotbar. Run it
                       when standing AT a death grave (chest + armor-stand + sign). One command
  (see the equip/organize/takeall/stash/moveitem/recover verbs under ACT above)

COMBAT / FOLLOW (top-level; run on the agent engine, progress arrives as [agent] lines)
  attack [nearest|<id>|crosshair] [crit|normal|ranged]   engage a mob. AUTO-EQUIPS the best hotbar
                       weapon and RESTORES the slot you were holding when the fight ends (your
                       pickaxe comes back -- it will not silently leave you mining with a sword).
                       Walks to it, timed crits.
                       GIVES UP HONESTLY: a target it cannot reach (in a pit, across a gap,
                       flying) falls back to the bow if you have one, else reports
                       "attack: CANNOT REACH <mob> ... dy=<+/-N>" and FAILS instead of re-pathing
                       forever. If you see that, dig/build to the mob or use a ranged weapon. Per-enemy policies built in: creeper =
                       hit-and-back (never lingers in blast range); skeleton/pillager = shield-advance
                       (off-hand shield up while closing); blaze/ghast = prefers the bow. RANGED mode
                       (or a far/unreachable target + a bow and arrows) = full-draw ballistic bow fire
                       with line-of-sight repositioning. A held SPEAR stabs at 2-4.5 blocks and swaps
                       to a close weapon if the target hugs you. Default nearest, crit
  follow <player> [dist]   lock onto a player, keep within dist (default 3); the mod re-tracks them as they move -- issue ONCE, ends on stop
  defend [on|off|status|auto|<player>]   auto-defense reflex, ON by default: if the master (or the named
                       player) or I get attacked, the MOD immediately engages the attacker (no command
                       needed), then RESUMES whatever it was doing
                       (e.g. follow). LEASH: it will not chase a mob more than 16m away from the
                       person it guards. If the GUARD himself is more than 16m away, it walks to
                       him and re-engages there (it does not abandon him, and it says so once).
                       You will see [agent] lines: "hostile nearby", "X was hit by",
                       "defend: engaging/threat down/resuming". Do NOT queue your own attack while a
                       defend is running -- just watch the [agent] lines. 'defend off' disables.

CRAFT
  openinv              open your inventory (2x2 crafting)
  craft planks|sticks|table|axe   craft a known legacy recipe (prefer agent craft below)
  cancraft <item>      recipe-book feasibility: YES via <recipe>, or NO + per-ingredient
                       HAVE/NEED lines with "(in vault: id)" and "(craftable)" annotations
  close                close the open menu

STORAGE (Bank Vault -- vault = your player-bound bank; needs the bank-vault mod.
         BV 1.4.0+ api verbs return ONE machine-readable line: "BV|op|OK|..." or
         "BV|op|ERR|reason"; keys are plain ids or "id#hash" specials. NO screen needed.
         SINGLEPLAYER: the BV| line comes back immediately as the reply. MULTIPLAYER: the
         verb returns "OK sent ..." at once and the BV| line is PUSHED shortly after as a
         "[bv] BV|..." report line (read it from the same reply, the next command's reply,
         or a bare listen). No [bv] line = the server's Bank Vault predates 1.4.0.
         ERR lines SELF-EXPLAIN: every "BV|op|ERR|reason" arrives with " -- <plain-language
         explanation + fix>" appended, e.g. deposit-only-member tells you to ask the owner
         for a promotion. Read the explanation BEFORE retrying -- do not burn turns probing)
  vault snapshot       bank totals: BV|snapshot|OK|total=..|unique=..|cap=..|upgrades=..|members=..
  vault list [page]    full bank listing, 50 keys/page: BV|list|OK|page=1/N|key=count;...
  vault count <key>    exact count of a key; a plain id also lists its id#hash variants
  vault find <item>    LIVE bank search (BV|find|OK|key=n;...); on old BV falls back to the
                       per-world storage memory (last-seen)
  vault withdraw <item> [n]   withdraw item (either arg order; plain id or id#hash key).
                       BV 1.4.0+: parsed result (BV|withdraw|OK|key|taken=n; AMBIG lists the
                       variant keys to pick from). Old BV: fires /bank withdraw, watch chat.
                       Items land in your inventory either way, components INTACT
  vault deposit hand [n]      deposit the held stack via the api (n omitted = whole stack)
  vault deposit <id> [n]      deposit by plain item id (api; n omitted/0 = every matching stack).
                       With the vault screen OPEN this quick-moves matching stacks instead (GUI)
  vault deposit rows   GUI (screen open): deposit ALL main rows -- the HOTBAR IS THE KEEP-LIST
  vault deposit all    GUI (screen open): deposit main rows AND hotbar
  vault mark [x y z]   remember the vault block position (default: the block you face). Do this
                       once per session BEFORE closing -- the trinket guard needs it to verify
  vault status         is the vault screen open; kinds/items; trinket slots; marked pos; api mode
  vault contents [f]   (screen open) list synced contents AND record to per-world storage memory
  vault memory         storage-memory summary for this world
  NOTE the api verbs (snapshot/list/count/find/withdraw/deposit-by-id/hand) work WITHOUT opening
       the vault screen. The GUI still opens only as a COMPLETED 3x3 set; close it with
       `agent vault close` (guarded), not close

WORLD
  cmd <server command> run a slash-command (e.g. cmd time set day) -- needs cheats/permission
  say <text>           send an in-game CHAT message (use to acknowledge your master, e.g. say Ok)
  master                query who is currently master (or "none set")
  master <name>         YOU decide someone should be master (after recognizing a handshake in
                         relayed chat) -- set it; narrows chat relay to that player only
  master clear          release the current master; chat relay reopens to every player
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
  agent mine area <x1 y1 z1> <x2 y2 z2> [nocollect]   queue a whole-box excavation (see mine area)
  agent mine hold [on|off]            hold/release the mine button (see mine hold)
  agent hold <0-8>                    queue selecting a hotbar slot
  agent equip <item>                  queue moving a named item to hand (needs an open container)
  agent use | agent place             queue use/place on the crosshair block
  agent attack [nearest|<id>|crosshair] [crit|normal|ranged]   queue an attack (see COMBAT above)
  agent sleep                         queue the sleep-in-nearby-bed composite (see MOVE above)
  agent follow <player> [dist]        lock onto a player and follow until stop (default dist 3)
  agent shield [ticks]                queue raising the shield (default 40t = 2s; alias: agent block)
  agent drop [slot|<item>] [n|all]    queue a drop/throw -- same syntax as top-level drop above
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
- **INVENTORY: prefer the high-level verbs over raw `slot`/`click`.** `equip all`, `equip <name>`,
  `organize hotbar`, `takeall`, `stash junk`, `moveitem <name> to <human slot>` all take NAMES and
  HUMAN slot words (hb1..hb9 as spoken, offhand, head/chest/legs/feet) -- you never compute menu
  indices. They auto-open the player inventory if a container is up. Raw `slot`/`slots` stay for
  surgery but you rarely need them now.
- **A grave/death chest** (chest + armor stand + name sign)? Just `recover`. Do NOT hand-drive it.
- **PLACING + WRITING A SIGN (the recipe that actually works, M1 0.16.0+).** Hold a sign, then:
  `agent sneak on` -> `open <chest x y z>` -> `agent sneak off` -> `sign <sign x y z> L1|L2` ->
  `read <sign x y z>` to VERIFY. Three traps, each paid for once:
    * WITHOUT sneak, right-clicking a chest just OPENS it instead of placing the sign.
    * ONE placement per command round-trip -- two `open` calls back to back hit vanilla's use
      cooldown and the second is silently ignored.
    * Do NOT stand where the sign goes: your HEAD occupies (x, y+1, z) and the placement fails.
  Sign-edit dialogs otherwise auto-close, so right-clicking a sign never traps you; mine/attack a
  sign to remove it.
- **On death you auto-respawn** and the death spot is reported (a `DIED at x,y,z` line).
- `where` now announces `screen OPEN: ...` when a menu is up (movement holds until you `close`).
- **Async commands** (`move*`/`goto`/`mine`/`craft`) return immediately -- poll `where` / `inv` /
  `look` to track them. They do NOT hit the 10 s execution cap.
- `slot <id> ...` and `slots` use the OPEN MENU index; `hold` / `equip` use the stable `inv` index.
  Do not mix them (see `01_drive_m1.md` section 7).
- `worlds`/`joinworld` load an EXISTING world. CREATING a new world is menu-driving with
  `describe`+`click`+`type` (see `02_create_world.md`).
- This card tracks the code. If `help` shows a command not listed here, trust `help` and flag the drift.
- **`agent ...`** is the queued autonomous layer: each subcommand returns at once with `queued ...`, runs on later in-world ticks, and its result arrives as a drained `[agent] <seq> <CLASS>: <text>` line on a subsequent reply. Poll `agent status`. Only runs while in a world.
- **Unsolicited event lines are PUSHED to you** even with no command pending -- the server flushes them within ~200 ms, so a bare `listen` (read with no command sent) receives them. Kinds: `[alert] took X damage (health A -> B); nearest hostile ...` (you are being hit), `[agent] ...` (agent progress), `[auto-upgrade] ...` (armor). During combat/follow, `listen` between actions to catch them; check `where` (health) before declaring a threat handled.
