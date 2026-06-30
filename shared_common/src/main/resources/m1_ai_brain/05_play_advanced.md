# 05 -- Play through M1: mid/late game (goal playbook)
<!-- Valid as of: M1 v0.5.0 | MC 1.20 - 26.3 | updated 2026-06-29 -->
**Covers:** base-building, gearing up, and end-game preparation through M1. Goal-oriented and
M1-specific; assumes you have early-game basics (`04_play_basics.md`) handled.

## Establish a base
- Pick a spot from `scan` (flat-ish, near water/trees; `BOUNDS UP` open = surface). Confirm with `where`.
- Build by `place`-ing blocks you hold; move in short legs and re-`scan` so you keep your bearings.
  Storage: place chests, open them (`slots` to read the container indexes), move items by slot.
- Keep a safe, enclosed perimeter -- check `BOUNDS` on all sides before calling a shelter "closed".

## Gear progression
- Mine toward iron -> diamond. Diamonds are deep; descend in safe steps, checking `BOUNDS DOWN` each
  time (never dig straight down over open air). Watch MOBS in `scan`.
- Craft up tools/weapons at a table (3x3 via `slots`). Let armor **auto-upgrade** handle what you
  collect -- diamond/netherite get equipped automatically as you find them.

## Prep for the End
- Gather the staples: ender pearls (from Endermen -- MOBS in `scan`; fight with `face`+`mine`), blaze
  rods (Nether), then blaze powder -> Eyes of Ender. Stock food, blocks, and backup gear.
- Approach the stronghold by throwing/reading Eyes of Ender direction (the user can guide this), then
  `goto` toward it in legs. Locate and fill the End portal frames.
- Going to the Nether/End is a big, semi-irreversible step -- confirm with the user first
  (`20_ask_the_user.md`), and stage supplies before you go.

## M1 limits that shape strategy
- No fly: no aerial scouting or fast vertical travel -- plan ground routes and staircases.
- Line-of-sight `scan` + async moves: explore in legs, re-scanning; do not assume what is past a wall.
- One client, one block/leg at a time: large builds are incremental -- pace them and verify as you go.
- When stuck, `40_recovery.md`; for anything touching shared worlds or destructive `cmd`, `50_safety_etiquette.md`.
