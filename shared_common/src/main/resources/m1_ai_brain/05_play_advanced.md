# 05 -- Play through M1: mid/late game (goal playbook)
<!-- Valid as of: M1 v0.9.1 | MC 1.20 - 26.3 | updated 2026-07-02 -->
**Covers:** base-building, gearing up, and end-game preparation through M1. Goal-oriented and
M1-specific; assumes you have early-game basics (`04_play_basics.md`) handled.

## Establish a base
- Pick a spot from `scan` (flat-ish, near water/trees; `BOUNDS UP` open = surface). Confirm with `where`.
- Build by `place`-ing blocks you hold; `goto` freely between spots -- the pather handles distance,
  gates, doors, stairs. Storage: place a chest and `takeall`/`slot`, or use a **Bank Vault**
  (`vault ...`) and a **Travelers Backpack** (`pack put/take`) for bulk. `stash junk` clears clutter;
  `equip all` + `organize hotbar` keep your kit set.
- Keep a safe, enclosed perimeter -- check `BOUNDS` on all sides before calling a shelter "closed".

## Gear progression
- Mine toward iron -> diamond. Diamonds are deep; descend in safe steps, checking `BOUNDS DOWN` each
  time (never dig straight down over open air). Watch MOBS in `scan`.
- Craft up tools/weapons at a table (3x3 via `slots`, or generic `craft <item>`). Let armor
  **auto-upgrade** handle what you collect. For combat, `attack` auto-equips your best weapon and
  applies per-enemy tactics (bow for flyers, shield-advance vs skeletons, hit-and-back vs creepers).

## Prep for the End
- Gather the staples: ender pearls, blaze rods (Nether) -> blaze powder -> Eyes of Ender. Stock
  food, blocks, and backup gear. NOTE: Endermen are SAFE-MOB (neutral) -- do NOT hunt them; take
  pearls only from self-defense kills when one aggros, or trade. Same restraint for zombified
  piglins in the Nether (`50_safety_etiquette.md`).
- Approach the stronghold by throwing/reading Eyes of Ender direction (the user can guide this), then
  `goto` toward it in legs. Locate and fill the End portal frames.
- Going to the Nether/End is a big, semi-irreversible step -- confirm with the user first
  (`20_ask_the_user.md`), and stage supplies before you go.

## M1 limits that shape strategy
- No fly: no aerial scouting or fast vertical travel -- plan ground routes and staircases.
- Line-of-sight `scan`: you only SEE unobstructed surfaces, so re-scan to DISCOVER -- but the pather
  reaches any known coordinate around walls, so long-haul `goto` between known places is fine.
- One client; builds are still placed block-by-block -- pace them and verify as you go.
- SAFE-MOB doctrine governs all combat -- never pre-empt neutrals (`50_safety_etiquette.md`).
- When stuck, `40_recovery.md`; for shared worlds or destructive `cmd`, `50_safety_etiquette.md`.
