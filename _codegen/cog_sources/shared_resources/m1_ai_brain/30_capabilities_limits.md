# Capabilities & limits (what M1 can and cannot do)
<!-- Valid as of: M1 v0.16.0 | MC 1.20 - 26.3 | updated 2026-08-02 (sign WRITING; m1srv server query; bulk mining; unreachable give-up; enumerate-all scan; sign reading; open by coord; take output) -->
**Covers:** an honest boundary list so you do not attempt impossible things or loop on them.

## CAN
- Read any screen as text (`describe`) and drive menus (`click` / `type`) -- create worlds, join
  servers, navigate any GUI.
- Read the world without looking: `where`, `scan` (line-of-sight), `look`, `inv`, `slots`.
- **Navigate anywhere** with M1's own pathfinder (`moveto` / `goto`): it opens gates + doors itself,
  walks rails and stairs, and reaches any distance in 16-block segments (a target millions of blocks
  away is fine). `nav own|vanilla|status` picks the engine (own is default).
- `mine`, `place`, `hold`, `equip`, generic `craft` (RecipeManager-driven, vault-sourced).
- **Bulk excavation, one command:** `mine area <x1 y1 z1> <x2 y2 z2>` clears a whole box (top-down,
  air gaps free, self-repositioning, [agent] progress, then collects the drops), and
  `mine hold on` holds the mine button so walking forward tunnels. Per-block `mine` is now only
  for single, precise blocks -- do NOT loop it over a volume.
- **High-level inventory verbs:** `equip all`, `equip <item>` (auto-routed), `organize hotbar`,
  `takeall`, `stash junk`, `moveitem <item> to <human slot>` -- names + human slots, no index math.
- **Combat, universal:** `attack [nearest|<id>|crosshair] [crit|normal|ranged]` -- auto-equips the
  best weapon (and puts your previous hotbar slot back afterwards), walks in, times crits; bow
  ballistics; spear stab; per-enemy tactics (creeper hit-and-back, skeleton shield-advance, etc).
  `shield` / `agent shield` for blocking. An unreachable target is reported and abandoned, not
  chased forever.
- **Follow + auto-defend:** `follow <player> [dist]`; `defend` auto-engages an attacker of you or the
  master and resumes. Both honor the SAFE-MOB doctrine (never pre-empt neutral mobs).
- **`sleep`** -- find a bed (or deploy a Travelers-Backpack sleeping bag*), walk beside it, sleep.
- **`recover`** -- clear a death grave-site (sign + chest + armor stand) in one command.
- **Storage:** Bank Vault (`vault ...`, incl. enchanted-gear `id#hash` withdraw) and Travelers
  Backpack (`pack on|contents|put|take`, code-level wear + batch move).
- **Enumerate anything:** `scan <name>` lists EVERY match by volume (NOT line-of-sight) with coords,
  facing, distance and any sign text, paged 50 at a time. This is how you survey a storage room or a
  furnace bank -- never walk-and-rescan, it skips things silently (it missed 3 of 16 furnaces live).
- **Read signs** -- `read [x y z]`, or inline in `scan sign`. Labels on a storage wall are readable.
- **WRITE signs** (0.16.0) -- `sign <x y z> L1|L2|L3|L4`. You can LABEL a storage room, not just
read one. Place with sneak + `open`; see the sign recipe in `10_command_card.md` Notes. ALWAYS
`read` back to verify -- a rejected write still replies OK.
- **Ask the server** -- `m1srv <query>` returns server-authoritative facts your client cannot see,
wherever the server also runs M1.
- **Address containers by COORDINATE** -- `open <x y z>`, so container work no longer depends on aiming.
- **`take output [radius]`** -- empty every furnace in range, output slot only, in one command.
- Run slash-commands via `cmd <...>` when cheats/permission are present.
- `screenshot` a frame; armor auto-equips itself to the best you carry.
- Queue **autonomous actions** via the `agent` layer; they run in-world and report back async.
- **Auto-recover from two screens:** a right-clicked SIGN edit dialog is auto-dismissed (EXCEPT during
a deliberate `sign` write, which holds it open just long enough to submit the text); on death you
  auto-respawn (and the death spot is reported).

## CANNOT (current)
- **Fly.** No fly command; a Creative air vantage is not reachable through M1 -- stay grounded.
- **Run two clients.** Exactly one M1 socket (`127.0.0.1:26000`); one client at a time.
- **Log the user in / authenticate.** M1 runs INSIDE an already-authenticated client; it cannot
  supply Microsoft credentials itself. Note this limits M1, NOT necessarily you: an external launcher
  (one that drives the vendor launcher's own library, reusing the stored session) can start an
  authenticated client for you, and some environments provide exactly that. Check your environment
  notes before telling the user you cannot start the game.
- **Beat the 10 s cap on a single synchronous handler.** Long actions must be the async ones
  (`move`/`mine`/`craft`/agent), which return immediately and run in the background.
- **Reach a mob it cannot walk to** (in a sealed pit, across a chasm, in the air). It will try the
  bow, then report `attack: CANNOT REACH ...` and stop. Dig/build a route, or kill it at range.
- **Deploy a Travelers-Backpack sleeping bag as a standable bed** (*detection works; the bag item is
  consumed but leaves no persistent block in single-player -- place a real bed instead for now).
- **Backpack/vault storage over real multiplayer.** The storage ops use the single-player integrated
  server; on a true MP server they are not wired yet (single-player is the current target).

## QUIRKS to plan around
- **Aiming at close range is unreliable.** `face <x y z>` can be tens of degrees off within a block
  or two and will target the block NEXT to the one you meant. For containers use `open <x y z>`;
  for a direction use `face <dir>`, which is exact.
- **`moveto` will not make sub-block adjustments** -- its 1-block stop radius means a short move
  reports "arrived" without moving. Use `move <dir> <n>` for fine positioning.
- **A chest wall several deep is only reachable from its outer faces**; standing on top and looking
  down reaches the top layer.
- `scan` is **line-of-sight only** -- you cannot SEE what is behind a wall, so `scan` from new spots
  to discover targets. (Once you have a coordinate, the pathfinder WILL route to it around walls --
  the old "cannot path behind a wall" caveat no longer applies to reaching a known point.)
- Output can be **block-buffered** in some setups -- a status/where poll is your live signal.
- **Menus differ by MC version** (1.20 / 1.21 / 26): drive by label from the live `describe`, never by
  hardcoded ids.
- Movement is segment-by-segment; poll `where` and read the `[agent] nav:` lines rather than firing a
  second move at the same target.
- **Drops scatter when you mine at range.** `mine [x y z]` has no practical range limit (the
  crosshair ray tunnels through whatever is in between), but the items land where the block was and
  despawn if nobody walks over them. `mine area` collects for you; a hand-rolled ranged dig does not.
- **A `mine area` box is capped at 65536 cells.** Split a bigger excavation into slices and issue
  them one after another -- each is its own queued job.
- **SAFE-MOB doctrine:** neutral mobs (zombified piglins, endermen, wolves, bees, iron golems...) are
  never attacked pre-emptively -- self-defense only, and the bot will not defend the master against
  them. Do not try to hunt them for drops.

Keep this file in sync with the command set: when a capability is added, move it from CANNOT to CAN
here and update `10_command_card.md` in the same pass.
