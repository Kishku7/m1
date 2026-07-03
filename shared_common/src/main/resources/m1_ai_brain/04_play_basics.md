# 04 -- Play through M1: early game (goal playbook)
<!-- Valid as of: M1 v0.9.1 | MC 1.20 - 26.3 | updated 2026-07-02 -->
**Covers:** the first in-world goals, expressed in M1's command vocabulary and limits. You already
know how to play Minecraft -- this is the M1-specific HOW, not a tutorial on the game.

## Orient first
`where` + `scan`. Report surroundings in a line or two. Note from `BOUNDS UP` whether you are under
open sky, and from `VISIBLE` where the trees / water / animals are. Set one concrete first goal.

## The early loop (each step is OBSERVE -> ONE ACTION -> CHECK)
1. **Wood.** `scan logs` -> `goto` the coordinate it gives -> face the log (`face <x y z>`), `look` to
   confirm, `mine`. Mining is async -- poll `inv` until the logs arrive. Repeat for a few logs.
2. **Tools.** `openinv` -> `craft planks` -> `craft sticks` -> for a table `craft table`, then `place`
   it and interact to open the 3x3 (`slots` to see its grid) -> `craft axe`. Re-run `inv` / `slots`
   after each to confirm.
3. **Food.** From `scan`, animals show under MOBS. `attack <id> normal` -- it walks to the animal
   and kills it for you (no crit needed on passive mobs). Pick up the drops by walking over them, or
   `goto` them. Eat when hunger is low: `hold` the food slot, `place` (use) to eat. Do NOT attack
   neutral mobs (piglins, wolves) for food -- SAFE-MOB doctrine (`50_safety_etiquette.md`).
4. **Stone + first ore.** Find stone via `BOUNDS DOWN` / `VISIBLE`; `mine` down carefully (never
   straight down over a drop -- check `BOUNDS DOWN` first). Get cobblestone -> better tools. Iron shows
   in `VISIBLE` as `iron_ore`/`raw_iron`.
5. **Shelter before night.** Time matters: build or dig a safe spot, place a door/blocks. If mobs
   appear (MOBS in `scan`), retreat to an enclosed `BOUNDS` and wait or fight from cover.

## M1-specific gotchas (these shape every plan)
- `scan` is line-of-sight only -- you can only SEE what is not behind a wall, so re-scan from new
  spots to DISCOVER targets. Once you have a coordinate, `goto`/`moveto` WILL route to it around
  walls, through gates/doors, up stairs, at any distance (M1's own pather).
- Async actions: fire one `mine`/`moveto`/`attack`, then POLL (`inv`/`where`/`scan`) -- do not stack.
- No fly: everything is ground-level traversal (`30_capabilities_limits.md`).
- Armor auto-upgrades itself; `equip all` wears everything else in one command.
- On `blocked`/`timeout`/`boxed in`, re-scan and pick a new or closer target (`40_recovery.md`).
- `sleep` skips the night in one command (finds a bed or deploys a sleeping bag).

When you have stable food, stone/iron tools, and a safe base, move to `05_play_advanced.md`.
