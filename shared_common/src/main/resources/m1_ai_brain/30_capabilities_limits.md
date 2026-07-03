# Capabilities & limits (what M1 can and cannot do)
<!-- Valid as of: M1 v0.9.1 | MC 1.20 - 26.3 | updated 2026-07-02 -->
**Covers:** an honest boundary list so you do not attempt impossible things or loop on them.

## CAN
- Read any screen as text (`describe`) and drive menus (`click` / `type`) -- create worlds, join
  servers, navigate any GUI.
- Read the world without looking: `where`, `scan` (line-of-sight), `look`, `inv`, `slots`.
- **Navigate anywhere** with M1's own pathfinder (`moveto` / `goto`): it opens gates + doors itself,
  walks rails and stairs, and reaches any distance in 16-block segments (a target millions of blocks
  away is fine). `nav own|vanilla|status` picks the engine (own is default).
- `mine`, `place`, `hold`, `equip`, generic `craft` (RecipeManager-driven, vault-sourced).
- **High-level inventory verbs:** `equip all`, `equip <item>` (auto-routed), `organize hotbar`,
  `takeall`, `stash junk`, `moveitem <item> to <human slot>` -- names + human slots, no index math.
- **Combat, universal:** `attack [nearest|<id>|crosshair] [crit|normal|ranged]` -- auto-equips the
  best weapon, walks in, times crits; bow ballistics; spear stab; per-enemy tactics (creeper
  hit-and-back, skeleton shield-advance, etc). `shield` / `agent shield` for blocking.
- **Follow + auto-defend:** `follow <player> [dist]`; `defend` auto-engages an attacker of you or the
  master and resumes. Both honor the SAFE-MOB doctrine (never pre-empt neutral mobs).
- **`sleep`** -- find a bed (or deploy a Travelers-Backpack sleeping bag*), walk beside it, sleep.
- **`recover`** -- clear a death grave-site (sign + chest + armor stand) in one command.
- **Storage:** Bank Vault (`vault ...`, incl. enchanted-gear `id#hash` withdraw) and Travelers
  Backpack (`pack on|contents|put|take`, code-level wear + batch move).
- Run slash-commands via `cmd <...>` when cheats/permission are present.
- `screenshot` a frame; armor auto-equips itself to the best you carry.
- Queue **autonomous actions** via the `agent` layer; they run in-world and report back async.
- **Auto-recover from two screens:** a right-clicked SIGN edit dialog is auto-dismissed; on death you
  auto-respawn (and the death spot is reported).

## CANNOT (current)
- **Fly.** No fly command; a Creative air vantage is not reachable through M1 -- stay grounded.
- **Run two clients.** Exactly one M1 socket (`127.0.0.1:26000`); one client at a time.
- **Log the user in / authenticate.** You cannot supply Microsoft credentials; the operator handles
  launch and auth.
- **Beat the 10 s cap on a single synchronous handler.** Long actions must be the async ones
  (`move`/`mine`/`craft`/agent), which return immediately and run in the background.
- **Deploy a Travelers-Backpack sleeping bag as a standable bed** (*detection works; the bag item is
  consumed but leaves no persistent block in single-player -- place a real bed instead for now).
- **Backpack/vault storage over real multiplayer.** The storage ops use the single-player integrated
  server; on a true MP server they are not wired yet (single-player is the current target).

## QUIRKS to plan around
- `scan` is **line-of-sight only** -- you cannot SEE what is behind a wall, so `scan` from new spots
  to discover targets. (Once you have a coordinate, the pathfinder WILL route to it around walls --
  the old "cannot path behind a wall" caveat no longer applies to reaching a known point.)
- Output can be **block-buffered** in some setups -- a status/where poll is your live signal.
- **Menus differ by MC version** (1.20 / 1.21 / 26): drive by label from the live `describe`, never by
  hardcoded ids.
- Movement is segment-by-segment; poll `where` and read the `[agent] nav:` lines rather than firing a
  second move at the same target.
- **SAFE-MOB doctrine:** neutral mobs (zombified piglins, endermen, wolves, bees, iron golems...) are
  never attacked pre-emptively -- self-defense only, and the bot will not defend the master against
  them. Do not try to hunt them for drops.

Keep this file in sync with the command set: when a capability is added, move it from CANNOT to CAN
here and update `10_command_card.md` in the same pass.
