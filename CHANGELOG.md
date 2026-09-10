# Changelog

All notable changes to M1 are documented here.
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.0.0/); this project uses
`<mod version>+<minecraft family>-<loader>` jar naming.

## [0.17.3] - 2026-09-10

### Changed
- **The 26.3 target moves from `26.3-pre-2` to `26.3-rc-1`.** pre-3 is skipped: it shipped on
  09-09 and rc-1 landed the next morning, so re-pinning twice in two days would have bought
  nothing. Same reasoning as 0.17.1 and 0.17.2 -- the pin names one exact build, so it gets
  advanced deliberately rather than left to a range. `pack_format` stays at **97**; rc-1's own
  `version.json` still reads resource 97.1 / data 121.0, the first 26.3 build in a while that
  did not move it. fabric-loader moves 0.19.3 -> 0.19.5.
- **No code change was needed.** The pre-3 to rc-1 source diff is 14 text files with nothing
  added, removed, or binary-changed, and M1 names none of the six symbols it touches:
  `LivingEntity.blockUsingItem` and `blockedByItem` (both gained a `fullyBlocked` flag, and a
  fully blocked hit no longer knocks the attacker back), `BlockPredicate.willMatchBlockEntity`,
  `LevelExtractor.getViewBlockingState` (rewritten off `Level.findBlocksIn`), `ChunkMap`'s
  chunk-load-failure path, `BlockableEventLoop`'s task-exception rethrow, and
  `ClientCommonPacketListenerImpl.onPacketError`.

### Notes
- One rc-1 behaviour change is worth knowing when reading a test log rather than a jar:
  `SharedConstants.CRASH_EAGERLY` flips to `false` and `BlockableEventLoop` now rethrows only
  what `isNonRecoverable` admits, so an exception thrown from a task on the main loop LOGS under
  the FATAL marker and the game keeps running. A run that would have crashed on a pre-release
  comes back green on rc-1. Read the log, not the exit code.
- **fabric-api must be `0.160.3+26.3` or newer on rc-1, and this is a hard requirement, not a
  preference.** Earlier builds on the line install and then crash: every 26.3 fabric-api declares
  `depends.minecraft = "~26.3-"`, which cannot tell one prerelease from another, while
  `fabric-renderer-api-v1`'s `LevelExtractorMixin` wraps `lambda$getViewBlockingState$1` -- a lambda
  rc-1 deleted when it rewrote `getViewBlockingState`. Running `0.160.2+26.3` (built for pre-3) on
  rc-1 kills the client during `Minecraft.<init>` with `Critical injection failure ... could not find
  any targets`. M1 is not implicated: its own dependency is a floor (`fabric-api >=0.145.0`) and the
  jar loads fine. Worth knowing that the DEDICATED SERVER boots clean with the bad API, because the
  failing mixin is client-only.

### Verified
- Fresh `Fabric/26.3-rc-1` harness cells built on Raider (server + client), M1 the only mod besides
  fabric-api. **Server:** `BOOT PASS`, log reads `Starting minecraft server version 26.3 Release
  Candidate 1`, `m1 0.17.3` in the loaded mod list, `Done (0.202s)`, console probe answered, clean
  save and stop, no mod-attributable errors. **Client:** `CELL PASS` -- M1 opened its socket, drove
  into the shared seeded world, ran its gamerule/weather/tp sequence and captured a 787 KB
  1280x720 render, which was EYEBALLED (real terrain, hotbar, and M1's own commands echoed in chat).
- That run is also what confirms the `26.3-rc.1` dependency predicate: it was written from the
  `26.3-pre-2 -> 26.3-pre.2` precedent rather than measured, and fabric-loader accepted the jar on
  both sides, so the normalization holds.

## [0.17.2] - 2026-09-04

### Changed
- **The 26.3 target moves again, from `26.3-pre-1` to `26.3-pre-2`**, which shipped the same day
  0.17.1 was built. Same reasoning as 0.17.1: the pin names one exact build, so it has to be
  advanced deliberately, and a driver that cannot be installed on the current build is a client
  cell that cannot exist. `pack_format` is unchanged at 97 (read from 26.3-pre-2's own
  `version.json`); fabric-api moves to 0.159.4+26.3.
- **No code change was needed.** The 26.3-pre-1 to pre-2 source diff was checked against every
  surface M1 uses: `LivingEntity.drop` and `MultiPlayerGameMode.dropItem` are both unchanged, so
  the 0.17.1 drop path holds, and M1 touches none of `InputConstants.grabMouse` (which gained
  coordinates), the removed `KeyboardHandler` IME-candidates API, or
  `ClientLevel.addBreakingBlockEffect`.

## [0.17.1] - 2026-09-04

### Changed
- **The 26.3 target moves from `26.3-snapshot-7` to `26.3-pre-1`.** M1 pins its 26.3 jar to one
  exact build rather than a range, because `pack_format` has moved on nearly every 26.3 build
  (89, 90, 91, 92, 93, 94, 95, then 97 at pre-1) and a jar carries exactly one. That pin makes the
  target something that has to be advanced deliberately, and it had fallen a build behind: the
  driver could not be installed on 26.3-pre-1 at all, so no 26.3-pre-1 client cell could exist and
  nothing could be tested there. `pack_format` 97 was read out of the 26.3-pre-1 client jar's own
  `version.json` (`resource_major`), not extrapolated from the snapshot series.
  Note that 26.3-pre-2 has since shipped and this jar does not cover it either.

## [0.17.0] - 2026-08-25

### Fixed
- **Pressing Escape with M1 loaded no longer dismisses the pause menu.** `ScreenWatch`'s PAUSE
  reflex (added 0.15.x for the unattended aim/pick stall) killed EVERY `PauseScreen` that was not
  inside the deliberate `pause`-verb grace window -- including the one the operator opened by hand,
  one client tick later, sometimes before it drew a frame. The reflex now tells the two apart by
  WINDOW FOCUS, which is exact rather than heuristic: vanilla can only summon that screen two ways,
  and they sit on opposite sides of the focus flag. `GameRenderer.render` opens it only while
  `!minecraft.isWindowActive() && options.pauseOnLostFocus`, after 500ms unfocused (1.20.1
  `GameRenderer` L1059-1064); `KeyboardHandler` opens it on key 256, and a key press only reaches a
  FOCUSED window (L420-423). Both call `pauseGame(false)`, so the `PauseScreen(showsPauseMenu)` flag
  canNOT discriminate -- read out of the decompiled sources, not assumed, and re-checked present and
  unchanged in 1.20, 1.20.6, 1.21, 1.21.5, 1.21.9, 1.21.11, 26.1, 26.2 and 26.3-snapshot-7. A pause
  seen while the window is focused is latched to that screen INSTANCE, so alt-tabbing away from a
  menu the operator opened does not make it vanish behind him. The lost-focus reflex is unchanged.

- **The agent layer never ran on ANY Forge or NeoForge cell.** The loader glue's client-tick handler
  stopped after `PickupUpgrade.tick`, so `AgentRuntime.tick` -- and with it the agent loop
  (`ActionQueue`, every queued multi-tick action: attack/defend/follow/mine-area/recover/craft/sleep)
  and `ScreenWatch` (death auto-respawn, sign-dialog dismissal, the pause reflex) -- plus
  `DamageWatch.tick` and `HungerWatch.tick` were dead on all 24 non-Fabric cells. Only the two
  Fabric client entrypoints ever called them. The glue carried a comment claiming it was "kept
  identical to the Fabric glue"; it had not been since the agent layer landed. All nine loader
  entrypoint masters now run the same eight-call chain, in the same order, as the Fabric client.

- **`M1SrvNet.tick` was missing entirely from the `neoforge_early` shape** (NeoForge 1.20.2, 1.20.3,
  1.20.4), so the `/m1srv` server-query backchannel could never complete a round trip there.

- **`VaultNet.onSystemLine` was not fed on Forge or NeoForge** (except the `neoforge_26` shape):
  the system-chat handler forwarded only to `M1SrvNet`, so Bank Vault's `/bank api` replies -- the
  async `[bv]` report path behind every multiplayer `vault` verb -- never arrived. The handler now
  feeds both, matching the Fabric `ClientReceiveMessageEvents.GAME` handler.

### Changed
- `mod_version` stamped **0.17.0 in all 34 cells**. 0.16.1 changed shared code that every cell
  compiles but bumped only `Fabric/26`, leaving 33 cells stamped 0.16.0 over 0.16.1 sources -- the
  same "shipped code under an unchanged version name" trap the project hit once before. Minor
  rather than patch because the Forge/NeoForge jars gain the whole agent layer.

### Notes
- `pack_format` was audited across all 34 cells against `Memory/knowledge/pack-formats.md` and is
  fully compliant: plain int at N <= 64, no `pack.mcmeta` on the Fabric/NeoForge 1.21.9-1.21.11
  cells, the exact-single range on the DATA major for Forge 1.21.10 (88) and 1.21.11 (94), and the
  `${packFormat}` range template on both 26 cells. The older "Forge 1.21.10/1.21.11 stale
  pack_format" follow-up is closed.
- **Smoketested: 13/13 PASS, every PNG eyeballed** (Raider `Client_Tests`, 2026-08-25). Tier =
  CRITICAL (1.20.1, 1.21.1, 1.21.11, 26.1.2 on all applicable loaders -- the publish gate),
  EXTENDED with Forge/1.20.6 and NeoForge/1.20.2 so that all ELEVEN distinct entrypoint shapes
  are covered, since the entrypoints are what this release actually changed. Every frame is a
  real in-world render, and 12 of the 13 show a live block-outline at the crosshair -- direct
  evidence that `GameRenderer.pick()` is running and `hitResult` is not stale, which is the
  surface the ScreenWatch change touches. (The exception, NeoForge 1.20.2, spawned inside a
  leaf canopy, so there is no valid pick target; the frame is otherwise a clean in-world
  render.)
- **Behaviour evidence for the parity fix, observed on a real client:** Forge 1.21.1 emitted
  BOTH a DamageWatch `[alert] took 20.0 damage (health 20.0 -> 0.0) -- DEAD` report and a
  ScreenWatch death report, and NeoForge 26.1.2 emitted the DamageWatch one. Those strings can
  only be produced by `DamageWatch` / `ScreenWatch`, which are reached only through
  `AgentRuntime.tick` -- the call these entrypoints did not make before 0.17.0. All four probed
  cells also auto-respawned with nobody clicking Respawn.
- **What is NOT proven:** a repeatable two-directional behaviour gate. Three attempts each
  scored wrong in an instructive way -- see `minecraft/m1.md` -- and the one that ends green has
  never been shown to go red, so it is not evidence. The COMPLETE tier (every MC version inside
  every jar's claimed range) has also not been run; this is CRITICAL + shape completion.
## [0.16.1] - 2026-08-05

### Fixed
- **`LocalPlayer.drop(boolean)` returns `void` from 26.3-snapshot-7** -- a hard compile break for
  `DropAction`, which read the return value to decide whether anything was actually thrown. New
  Cog facade `DropCompat.drop(Minecraft, boolean)` (predicate `compat.drop_void`) restores the
  old contract by asking the SAME question vanilla asks, one instruction earlier: the pre-snap-7
  return was `!getInventory().removeFromSelected(all).isEmpty()`, so the facade inspects the
  main-hand stack BEFORE the call. Semantically identical, not an approximation. Through
  snapshot-6 the facade compiles down to the plain `return mc.player.drop(all);`. Cog rather than
  reflection because the pre-26 Fabric cells run an INTERMEDIARY runtime, where a mojmap-name
  lookup misses entirely.

### Changed
- **Fabric 26.3 cell moved to MC 26.3-snapshot-7** (from snapshot-6): fabric-api
  `0.156.1+26.3` -> `0.156.2+26.3`, resource `pack_format` `94` -> `95`.
- **The 26.3 jar's MC dependency is now SNAPSHOT-EXCLUSIVE**, `>=26.3-alpha.7 <26.3-alpha.8`
  (was the line-wide `>=26.3- <26.4`). Two independent reasons it can no longer claim the whole
  26.3 line: every 26.3 snapshot bumps `pack_format` by one, and the `drop()` signature above
  differs across snapshots within the line. This matches how every other 26.3 mod cell is pinned.
- `mod_version` 0.16.0 -> 0.16.1. Only the Fabric 26.3 cell was rebuilt.

### Notes
- The other three snapshot-7 breaking surfaces do not touch M1: the `Prediction` argument added to
  `LivingEntity.drop(ItemStack, boolean)` / `Inventory.placeItemBackInInventory` (M1 calls
  neither -- its drop path is the client-side `LocalPlayer.drop`), the
  `InteractionResult.SwingSource` `CLIENT`/`SERVER` -> `PREDICTED`/`SERVER_ONLY` rename,
  and the 32 new concrete slab/stair blocks plus the filled-map colour removals.

## [0.16.0] - 2026-08-01

### Added
- **`sign <x> <y> <z> <text>` -- M1 can now WRITE signs.** It could READ them since 0.15.0, but
  writing was impossible: `ScreenWatch` auto-dismisses the sign-edit dialog (the grave-site reflex),
  so a freshly placed sign was killed the tick its editor opened and stayed blank forever. Asked to
  label a 20-double storage room, the whole job was blocked on this.
  - Driving the GUI text boxes would be fragile, so the edit is sent as the PACKET it actually is.
    The sign is opened first, because that is what makes the server accept us as its designated
    editor (`playerWhoMayEdit`); without it the server silently drops the update.
  - `ScreenWatch.allowSign(ms)` is a time-boxed grace window -- same shape as the 0.14.3 pause fix --
    so a deliberate edit survives while an accidental right-click is still auto-cleared. Time-boxed
    means a failed write cannot leave the reflex disabled.
  - Lines split on `|`, each clamped to 15 chars so overlong text truncates instead of being
    silently rejected by the server.

### Notes
- The packet drifts at the SAME 26.3 boundary as the sign READER, so it reuses
  `compat.sign_text_slot`: through 26.2 it is
  `ServerboundSignUpdatePacket(BlockPos, boolean isFrontText, String x4)`; from 26.3 it is a record
  `(BlockPos pos, List<String> lines, SignTextSlot slot)`. Deobf-confirmed from MC-Java 1.20 /
  1.21.11 / 26.2 / 26.3-snapshot-6 before writing any code.

## [0.15.2] - 2026-08-01

### Fixed
- **`agent craft <item> <count>` ignored the count and could consume an entire material stock.**
  Found by doing a real job: "make about 32 signs". `CraftAction` staged the grid with
  `RecipeCompat.placeRecipe(..., true)` -- vanilla's `useMaxItems`, the recipe-book shift-click,
  which fills the grid with as MANY sets as the available ingredients allow. The harvest then
  crafted all of them in a single round, and `want` was only re-checked BETWEEN rounds, so it acted
  as a FLOOR ("stop once you have at least N") rather than a limit. Live result: `agent craft
  birch_sign 33` with 13 sticks staged produced **39 signs** and consumed every stick. Pointed at a
  well-stocked chest, a single `craft x1` would have eaten the whole stock -- a data-loss-shaped bug,
  not a cosmetic one.
  Each round now stages exactly ONE set (`placeRecipe(..., false)`), so `count` actually binds and
  overshoot is at most `recipe yield - 1`, which is inherent to the recipe (signs come out in 3s).
  Cost is N rounds instead of 1; a round is a few ticks, which is the right trade for not
  destroying someone's materials.

## [0.15.1] - 2026-08-01

### Fixed
- **`face <x y z>` aimed at a block's CORNER, not its centre.** A block spans `[x, x+1)`, so the
  raw integer is its minimum corner -- up to 0.87 blocks off in 3D. At 10+ blocks that is a
  rounding error and it went unnoticed for months; at 1-2 blocks, which is exactly where container
  work happens, it is TENS OF DEGREES. Live symptom (2026-08-01): aiming at a chest one block away
  produced pitch 48-60 and repeatedly opened the chest below or behind the intended one, which is a
  large part of why searching a 49-chest room cost ~150 commands. Integer args are now treated as a
  BLOCK reference and aim at the centre (that is what `scan` hands back); anything with a decimal
  point is still honoured as an exact point, and the reply says `[block centre]` when it snapped.
- **`attack` re-pathed every tick during a close approach.** A target 3-4 blocks away produces a
  path that completes in ONE tick, so the "no active move" test was true again immediately and the
  loop re-issued a path every tick -- 7-10 identical `nav: segment 1 / arrived (1t)` lines per
  engagement against zombies. It always resolved, but it is pure noise in the report stream and
  pure waste in the pather. Approach re-paths are now on an 8-tick cooldown.

## [0.15.0] - 2026-08-01

### Added
All three come straight out of two live chores that went badly -- emptying a 16-furnace bank and
searching a 49-chest storage room -- where roughly 150 socket commands produced an incomplete
furnace sweep and no found chest at all. In every case the container logic was fine; AIMING and
POSITIONING were the bottleneck.

- **`read [x y z]` -- sign reading, from the block entity rather than a ray.** A storage room's 12
  wall signs label every chest column and were completely unreadable: the server's `lookingat` ray
  uses a COLLIDER context and wall signs have no collision box, so it passes straight through them
  and reports the chest behind. The labels that would have answered "which column is copper" in one
  command were invisible. Reading the `SignBlockEntity` sidesteps rays entirely; both faces are
  returned when the back is written.
- **`scan <name>` now returns each match's sign text inline**, so `scan sign` is a one-command
  readout of an entire labelled wall -- usually faster than `read` per sign.
- **`open <x> <y> <z>` -- interact with a container BY COORDINATE, no crosshair.** A synthesized
  `BlockHitResult` removes aiming from container work: `scan` already hands back exact coordinates,
  so the caller can act on them directly. This was the fix for three separate failures seen live --
  `face <x y z>` throwing 50-60 degree pitch errors at close range and opening the neighbouring
  chest; `moveto` silently no-opping any move under its 1-block stop radius while still reporting
  "arrived"; and a chest block several deep only being reachable from certain sides. Server reach
  (~4.4 from the eye) is still real and is now reported honestly with the distance instead of
  failing silently.
- **`take output [radius]` -- bulk furnace collection** (`TakeOutputAction`, agent-queued). Walks
  every furnace / blast furnace / smoker in range, opens each BY COORDINATE, takes the OUTPUT slot
  only and never touches input or fuel. Finds them by volume, not line-of-sight. Reports per-furnace
  progress, skips anything it cannot open rather than wedging, and stops early with an explicit
  message if the inventory fills instead of silently dropping items. Replaces six commands per
  furnace; the bank that prompted it was sixteen.

### Notes
- Version drift was handled by NOT depending on the drifting symbols: `Direction.getNearest` has
  come and gone in double/float/Vec3 forms across the range, so the dominant-axis face is computed
  in-mod from the stable enum constants. Same reasoning as the build-height accessor in 0.14.4.
  Cog remains the fallback for drift that cannot be avoided this way.

## [0.14.4] - 2026-08-01

### Changed
- **Targeted `scan <name|id>` rewritten: every match, by volume, with facing, paged.** Found by
  doing a real chore -- "empty every furnace in the bank" -- which turned into a ~40-command crawl
  and STILL missed furnaces. Two separate faults, both of which only show up in play:
  - it deduped by block id and reported only the NEAREST of each kind plus a bare `+more`, which
    for a bank of 8 furnaces is unusable: the controller had to step a few blocks and re-scan,
    repeatedly, and silently skipped three furnaces (`-63`, `-60`, `-59`) because the hops jumped
    over them;
  - it RAYCAST, so it was line-of-sight only -- the back rows of a chest/furnace wall, or anything
    behind a block, did not exist as far as the caller could tell.
  It now walks the block VOLUME (no rays, no LOS), lists EVERY match with exact coords, reports each
  block's `facing` where it has one (so the caller knows which side to stand on instead of guessing
  and mis-aiming), and PAGES 50 at a time -- `scan furnace` then `scan furnace 2`, with a
  `showing 1-50, page 1 of 5` header and a `(next: ...)` hint. `rN` sets the radius (default 16,
  max 32). The untargeted `scan` / `scan <radius>` situational-awareness form is unchanged.
- `facing` is read GENERICALLY off the state's property set rather than by naming a block class, so
  it covers furnaces, chests, dispensers, stairs and beds on every supported version.

### Notes
- The volume loop deliberately does NOT clamp to build height: that accessor was renamed across the
  supported range (`getMinBuildHeight`/`getMaxBuildHeight` -> `getMinY`/`getMaxY`), and
  `Level.getBlockState` already returns air outside the limits, so the check would buy nothing and
  cost a Cog branch on every cell. (The first build failed on exactly that rename.)

## [0.14.3] - 2026-08-01

### Fixed
Both found by playing, not by reading -- a live pillager ambush on the Zion server.

- **`defend` never gave the tool back.** 0.14.0 taught `AttackAction` to restore the hotbar slot it
  auto-equipped away from, and that works for a typed `attack`. But `DefendAction` -- the REFLEX,
  i.e. the common case -- finishes on the inner action's behalf on three fast paths (threat died,
  leash break-off, regroup give-up) and returned DONE without ever stepping into it again, so the
  restore never ran. Observed live: pillagers attacked mid-excavation, the bot drew its netherite
  sword, killed them, reported "defend: threat cleared, resuming" -- and was still holding the sword
  with the pickaxe back in slot 2. Every exit path now runs the wrapped action's cleanup
  (`finishInner`). GENERAL RULE for this codebase: a wrapper that can complete on behalf of the
  action it wraps MUST run that action's cleanup itself.
- **The 0.13.8 pause reflex made the graceful-exit path undrivable.** 0.13.8 auto-dismisses any
  `PauseScreen`, because vanilla's "Pause on Lost Focus" opens one whenever an unattended client
  loses OS focus and that stalls `mc.hitResult`. But the reflex could not tell a focus-loss pause
  from one the controller opened ON PURPOSE, so it ate the menu that `pause` had just created, one
  tick later, every time -- and since an unattended session is unfocused BY DEFINITION, the
  documented quit sequence (`pause` -> click "Save and Quit to Title" / "Disconnect") could never
  be driven at all. Caught trying to log off Zion cleanly (Master, 2026-08-01). `pause` now calls
  `ScreenWatch.allowPause(60s)`, a time-boxed grace window, so a deliberate pause stands while a
  focus-loss pause is still killed; the box means a crashed or abandoned plan cannot leave the
  reflex disabled.

- **An order issued during `follow` never ran.** `follow` and `patrol` are standing plans that only
  end on `stop`, but EVERY `agent` subcommand went onto the backlog, which only advances when the
  active task completes. So an order given while following queued up behind an action that never
  finishes: during the same fight a queued `attack nearest` answered "queued attack" and then sat
  there, with `agent status` showing `backlog=1` while follow kept walking. Silent, and precisely
  when it mattered. The design already specified this -- m1-agent.md's priority model is
  `standing plan < reflex interrupt < AI override`, "inject = default" -- but the socket layer only
  ever called `append`. One-shot orders now `injectInterrupt`, preempting the standing plan, which
  resumes underneath them afterwards (the mechanism the defend reflex already used). Only `follow`
  and `patrol` still append.

## [0.14.2] - 2026-08-01

### Fixed
- **Selection-list ROWS were invisible to the controller -- the saved multiplayer server list could
  not be seen or used at all.** A list's rows are not widgets, they are `AbstractSelectionList`
  Entries, so `describe` (which enumerates widgets) never showed them, and `worlds` only understood
  `WorldSelectionList.WorldListEntry` and answered "(no worlds)" for anything else. Net effect,
  found live (Master, 2026-08-01): on the Play Multiplayer screen the controller could see the
  Join Server / Direct Connection / Add Server buttons but NOT the saved servers themselves, so
  joining a saved server was impossible -- the rows simply did not exist as far as it could tell.
  - `describe` now appends a `list rows:` section (`<n> <narration>`) for whatever selection list
    is on screen, clearly marked as not-widgets so `<n>` is never confused with a widget `[n]`.
  - `worlds` is now GENERIC and aliased as `servers` / `entries`: it enumerates ANY
    `ObjectSelectionList` via `Entry.getNarration()`, so saved worlds, saved servers, LAN games and
    any other list screen all work through one code path with no version-specific entry class named
    anywhere.
  - New `select <n>` verb selects a row; buttons that require a selection (Join Server, Play
    Selected World, Edit, Delete) then go ACTIVE and are clicked by their normal widget id. The
    reply re-describes the screen so the caller sees what became available.
  - Implementation note for future ports: `AbstractSelectionList.Entry` is PROTECTED and can never
    be named from mod code -- the cast must go through the public `ObjectSelectionList.Entry`,
    which is a subtype of the erased `setSelected` parameter. Naming the protected type compiles
    nowhere (caught on the 1.21.11 cell).

## [0.14.1] - 2026-08-01

### Fixed
Both found by actually driving `mine area` against the real stone wall at x=-140 (live client,
Legacy 1.21.11) rather than by reading the code -- the 0.14.0 build was green and still did the
wrong thing in the world.

- **Out-of-reach cells burned 20 s each instead of being skipped.** Server interaction reach is
  ~4.5 blocks from the eye, so a cell more than ~4 blocks above the player's feet can never be
  broken from ground level -- but the action still ran the full dig budget twice (2 x 200 ticks)
  before giving up on it. A tall wall is mostly such cells, so the job spent its entire 30-minute
  budget achieving almost nothing (observed on `(-140,74,21)` while standing at y=68). There is now
  a hard reach check AFTER the approach: past `REACH_HARD` (5.0 m from the eye) the cell is skipped
  immediately, counted separately as `out of reach` in the progress line, and explained ONCE with
  an actionable message ("reach tops out ~4 blocks above my feet: run the high band from higher
  ground, or clear the low band first and stand on what is left").
- **Cell ordering ping-ponged, so travel dominated the whole job.** Sorting each layer by distance
  from wherever the player happened to be at PLAN time makes the cursor alternate outward either
  side of that point: the live run went z=18, 24, 16, 25, 15, 26, 14, 27 ... walking further
  between every single block until it was covering ~18 blocks per cell and still accelerating.
  Ordering is a travel problem, not a sorting problem, so each layer is now swept
  **serpentine** -- x ascending, z alternating per column, and the direction flipped per layer so
  the end of one layer is adjacent to the start of the next.
- **Stand-off reduced 3.0 -> 1.6 m** (`APPROACH_STOP`). Every block of stand-off is a block of
  reach spent, and reach is what decides how high up a wall the bot can still work; standing
  adjacent buys roughly two more vertical levels per pass.

### Known limitation (not a bug -- physics)
`mine area` cannot clear a wall taller than ~4 blocks above the ground it is standing on. Clear the
low band first and re-issue for the band above once there is something to stand on, or run the job
from higher ground. A proper fix (pillar up / stand on the wall) is a future feature.

## [0.14.0] - 2026-08-01

### Added
- **Bulk mining -- `mine area <x1 y1 z1> <x2 y2 z2> [nocollect]`.** `mine [x y z]` was strictly one
  block per socket command, so clearing a ~55-column x ~12-level slice cost 600+ round-trips and was
  not a workflow an AI client could actually run (found live during an excavation task, 2026-08-01).
  The new `MineAreaAction` is a single queued agent job that owns the whole volume:
  - clears **top-down** (never mines out from under itself or a gravity block), nearest cell first
    inside each layer;
  - **skips air cells for free** -- the ridge that surfaced this was NOT solid, and mid-column air
    pockets were exactly what forced per-block probing before;
  - **repositions itself** (one pather approach per out-of-reach cell) and, if that fails, still
    attempts the dig from range, since the crosshair ray tunnels through what is in between;
  - **never stalls**: a cell it cannot break is retried once, then reported and skipped;
  - never attempts bedrock/barrier (`getDestroySpeed < 0`) or liquids, which would burn the dig
    budget for nothing;
  - reports `[agent]` progress every 25 blocks (`N broken, N air, N unbreakable/liquid, N skipped,
    N left`);
  - finishes by **walking the drops in** (at range they land far away and despawn uncollected --
    `nocollect` opts out);
  - capped at 65536 cells and a 30-minute tick budget; `stop` cancels, re-issuing the same box
    resumes what is left.
- **`mine hold [on|off]`** -- the primitive behind Master's own description ("hold down the mine
  button and move"). `MineControl` gained an untargeted HOLD mode that latches `keyAttack` without
  touching facing, so `face` / `moveto` steer and the bot tunnels continuously. 5-minute safety cap;
  `mine hold off` and `stop` both release it.
- Both forms are also reachable as `agent mine area ...` / `agent mine hold ...`.

### Fixed
- **`attack` re-pathed forever on a vertically unreachable target.** A creeper in a pit ~4 blocks
  below produced an endless re-path cycle (`nav: segment 1 -- 2 wp, 0.3 to go / arrived` repeating
  ~200x) with no failure and no report. `AttackAction` now tracks the closest it has ever been to
  the target: 5 s of closing no distance (or 12 re-paths, or a `startMove` that returns no path)
  falls back to the bow when one is usable and otherwise FAILS with an actionable line --
  `attack: CANNOT REACH <mob> id=N -- X.Xm away, dy=+/-Y.Y (<why>)`. The same guard is wired into
  the creeper-charge and bow-reposition paths, which could loop the same way.
- **`attack` clobbered the held item.** It auto-equips the best hotbar weapon and never put the
  previous slot back, so mining silently resumed with a netherite sword (Master caught it, not the
  mod). The pre-engagement hotbar slot is now recorded on the first auto-equip -- melee, creeper and
  bow paths alike -- and restored in `release()`, which runs on DONE, FAILED, timeout and interrupt.
- **`defend` thrashed against the leash.** When the master fought beyond the 16 m leash, defend
  engaged and immediately broke off, over and over, spamming `[agent]` lines and never defending.
  Two root causes, both fixed:
  - the leash was measured **me-to-guard** when the rule it implements is "do not chase a mob far
    from the person I protect" -- it is now measured **guard-to-target**;
  - being out of position now means **regroup, not quit**: `DefendAction` walks to the guard,
    keeps the threat targeted, and re-engages there, giving up only after 10 s of failing to close
    on him. Every advisory fires at most once per engagement, and `ThreatWatch` no longer
    re-announces an engagement on the same mob more than once per 5 s.

### Verified
- Fabric 26.1.2 (dev target, newest APIs) and Forge 1.20.1 (oldest cell, SRG runtime) both build
  green with `-Xlint:all`, zero warnings -- so `LiquidBlock`, `BlockState.getDestroySpeed`,
  `ItemEntity` and `AABB` all resolve across the full 1.20 - 26.3 range.
- AI_Brain `10_command_card.md` + `30_capabilities_limits.md` updated in the same pass (the card's
  own sync note), covering the two new verbs, the give-up report, the tool restore and the new
  leash semantics.

## [0.13.8] - 2026-08-01

### Fixed
- **Vanilla's "Pause on Lost Focus" silently broke every aim-then-interact action (`sleep`,
  `look`, `screen face`) on an unattended/automated M1 session.** M1 runs headless, driven by an
  AI over a text socket, so the client window constantly loses OS focus -- and vanilla responds
  by auto-opening the ESC pause menu (`PauseScreen`). While any screen is open, Minecraft's client
  tick loop skips `GameRenderer.pick()` entirely, so `mc.hitResult` (what the crosshair is
  pointing at) goes stale and never updates no matter how many times code sets the player's
  yaw/pitch -- so `SleepAction`'s FACE step (aim at the bed, then use it), `LookAction`, and
  `ScreenOps.face` would all silently stall until a human physically clicked back into the game
  window to close the pause screen and resume picking. Root-caused from a real session transcript
  (2026-08-01) where the bed-sleep macro would not land until manually unpaused.
  - `ScreenWatch` gained a third reflex: any `PauseScreen` that appears is now auto-dismissed
    every tick, same pattern as the existing sign-edit and death-screen reflexes.
  - Also disables the underlying `Options.pauseOnLostFocus` client option (and persists it via
    `Options.save()`) once per tick series, so the pause screen stops being summoned by focus loss
    in the first place; the auto-dismiss reflex remains as a backstop for any other path that can
    still open it (e.g. a manual Escape press).

### Verified
- Full 34-cell / 37-jar matrix rebuilt clean, `-Xlint:all` zero warnings.
- `Options.pauseOnLostFocus` / `Options.save()` confirmed present under those exact mojmap names
  across the whole supported range via a Forge/1.20.1 (SRG-runtime) + Fabric/26 (newest) sanity
  build before committing to the full rebuild.
## [0.13.7] - 2026-07-31

### Added
- **Player-chat relay (the "master" feature) now works on Forge and NeoForge, not just Fabric.**
  M1's `ChatWatch` relay (which the AI uses to recognize a handshake and call `master <name>`)
  was wired only into the Fabric client entrypoints. Wired the equivalent `ClientChatReceivedEvent.Player`
  listener into all 5 Forge shapes and all 4 NeoForge shapes (including the "early" NeoForge shape,
  which previously had no chat capture of any kind), resolving the sender's name via the client's
  PlayerInfo/GameProfile, matching Fabric's behavior.

### Fixed
- **`com.mojang.authlib.GameProfile.getName()` does not exist on MC 1.21.9+.** GameProfile became a
  Java record (`name()`/`id()`) in the same 1.21.9 re-intermediation wave as `mouse_event`/`press_input`.
  Added a cog-gated `M1Compat.profileName(GameProfile)` helper and routed every chat-name lookup
  (Fabric and the new Forge/NeoForge listeners) through it instead of calling `.getName()`/`.name()`
  directly -- this broke compilation on 6 of 8 pre-26 Fabric cells when the chat-listener feature was
  first added.
- **`mod_version` had drifted to 0.13.9 on the Fabric/1.21.11 cell** while all other 33 cells were on
  0.13.6 (single-source-of-truth violation). Normalized all 34 cells to 0.13.7.

### Verified
- Full 34-cell / 37-jar matrix rebuilt clean, `-Xlint:all` zero warnings, zero non-ASCII.
- CRITICAL-tier client boot (Raider): Fabric/Forge/NeoForge at 1.20.1-1.20.6, 1.21.11 (the exact
  boundary the GameProfile bug lives at), and 26.1.2 -- 8/8 PASS, world loaded, M1 driving normally.
## [0.13.6] - 2026-07-29

### Fixed
- **Four NeoForge cells whose claimed MC range crossed an API gate.** The /m1srv server
  command tree (0.13.0) reads server state through compat-gated helpers; the gates in
  compat.py were correct but the jar ranges were not, so three NeoForge jars advertised
  versions on the far side of a gate and threw NoSuchMethodError while building the command
  tree for a joining player. The Fabric line had already been split correctly; NeoForge had not
  (classic cross-loader range asymmetry, mod-audit-doctrine D3a).
  - NeoForge/1.21.5: was [1.21.5,1.21.8). Built at 1.21.5 it emits ServerPlayer.serverLevel(),
    but player_level() switches to level() at 1.21.6 -- crashed 1.21.6/1.21.7. Narrowed to
    [1.21.5,1.21.6).
  - NeoForge/1.21.8: widened to [1.21.6,1.21.9), loader floor [21.6,). Built at 1.21.8 it is
    on the same side of the 1.21.6 gate as 1.21.6/1.21.7 and no other gate falls in that window,
    so it legitimately serves all three (21.6/21.7 are prerelease-only, so this is the
    claim-via-adjacent-release rule, not a new cell).
  - NeoForge/1.21.11: was [1.21.9,1.21.12). Built at 1.21.11 it emits the new
    world.level.gamerules.GameRules API (gate at 1.21.11) -- crashed 1.21.10. Narrowed to
    [1.21.11,1.21.12), floor [21.11,).
  - NEW NeoForge/1.21.10 cell on neo 21.10.64 (a release; 21.9 is beta-only), claiming
    [1.21.9,1.21.11) with floor [21.9,) -- mirrors what Fabric/1.21.9 already does.
  - NeoForge/1.20.6: was [1.20.5,1.20.7) while requiring loader [20.6,) -- MC 1.20.5 runs
    NeoForge 20.5.x, so the 1.20.5 claim was unsatisfiable. Narrowed to [1.20.6,1.20.7).

### Changed
- mod_version normalized to 0.13.6 across all 34 cells (had drifted to a mix of values across
  cells that shipped at different times).
## [0.13.5] - 2026-07-28

### Changed
- **Fabric 26.3 cell moved to MC 26.3-snapshot-6** (from snapshot-5): fabric-api
  `0.155.3+26.3` -> `0.156.1+26.3`, pack_format `93` -> `94` (data 113, world_version 5005,
  snapshot protocol 328). The declared MC range is unchanged (`>=26.3- <26.4`), so this jar
  covers the whole 26.3 line including snapshot-6.

### Notes
- **No source change was required.** The snapshot-6 API deltas were checked against M1's cog
  sources: `InputWithModifiers` still declares exactly `input()` + `modifiers()` as its only
  abstract methods, so M1's anonymous implementation in `compat.press_button` is unaffected by
  the removal of `getDigit()`/`NOT_DIGIT`; M1 touches none of the other snapshot-6 breaks
  (worldgen noise overhaul, Entity invulnerability split, SharedSuggestionProvider filter
  parameter, options-screen reshuffle, terrain multidraw path).
- Built `-Xlint:all` with zero warnings.

## [0.13.4] - 2026-07-27

### Fixed
- **Forge 1.21.7 could not load M1 at all.** The `Forge/1.21.8` cell declared minecraft
  `[1.21.7,1.21.9)` while requiring forge `[58,)` -- but MC 1.21.7 runs Forge **57**, so the jar
  claimed a version it could never load on (`Missing language javafml version [58,) ... found 57.0.3`).
  That is a cell straddling a hard loader-major boundary. SPLIT per the standard: the 1.21.8 cell is
  narrowed to `[1.21.8,1.21.9)`, and a new **`Forge/1.21.7` cell** (forge `1.21.7-57.0.3`, ranges
  `[57,)` / `[1.21.7,1.21.8)`, pack_format 64) provides real 1.21.7 coverage. Both cells server-boot
  smoketested green with zero mod errors.

### Changed
- `scripts/cog-gen.ps1` M1Forge shape table now maps 1.21.7 to `forge_eventbus7` (both neighbours,
  1.21.6/forge-56 and 1.21.8/forge-58, already used that shape).
- **`.gitignore` no longer hides the whole `scripts/` directory.** M1 was the only mod doing this, so
  its build tooling -- `cog-gen.ps1`, every `build-all-*.ps1`, and the 26-cell loader pin map -- was
  absent from the repo and builds were not reproducible from a clean clone. Now only the
  credential-reading `_publish_*.py` / `_release_*.py` / `build-stage.ps1` / `_*.txt` are ignored,
  matching every other mod and `mod-rules.md`.

## [0.13.3] - 2026-07-27

### Changed
- NeoForge 26 cells rebuilt against the now-PUBLISHED NeoForge builds: 26.1 -> 26.1.2.87, 26.2 -> 26.2.0.35-beta (previously 26.1.2.78 / 26.2.0.8-beta). mavenLocal() removed from the NeoForge/26 cell.
- No source or behaviour change. Redeployed to every smoketest server and client cell per the latest-M1-everywhere rule.

## [0.13.2] - 2026-07-21

### Fixed
- **Minecraft 26.3-snapshot-5 support (Fabric).** 26.3-snapshot-5 removed `DataComponentPatch.entrySet()`;
  the item-component listing (`M1Compat.componentPatchList`) now uses the surviving `split()` /
  `SplitResult(added, removed)` API. The 26.3 Fabric build compiles and runs on 26.3-snapshot-5, verified
  in-world on the headless client harness (world load + drive + render). `split()` is present across the
  whole 1.20.5+ data-component era, so the change is behavior-preserving on every earlier version.

## [0.13.1] - 2026-07-18

### Fixed
- **Forge 1.21.4 and 1.21.10** now build and run correctly. Two internal version-boundary
  misclassifications (the ResourceLocation/Identifier + piercing-weapon API is 1.21.11, not 1.21.9;
  the recipe-ingredient stream API is 1.21.4, not 1.21.5) were masked on those two Forge-only
  versions and are corrected.
- **26.3 menu clicks work again.** The single-source refactor below dropped a 26.3-only fix: on
  26.3-snapshot-3+ the primary (left) click button is encoded 1, not 0, so a hardcoded 0 made every
  in-game menu click a silent no-op. `screen_click` now emits the correct button per version (1 for
  26.3+, 0 for 26.1/26.2 and 1.21.9+). Verified on the 26.3-snapshot-4 client cell.

### Security
- **Hardened the local control socket.** Inbound command lines are now length-capped, and the
  `screenshot` command rejects file names containing path separators or `..` (the frame can only be
  written under the screenshots folder). The socket remains bound to localhost only.

### Changed
- **Internal single-source build refactor (no gameplay change):** the mod is now generated from one
  code source of truth per file, with every build cell and loader entrypoint produced from it.
  Behavior is identical to 0.13.0 on every supported version.

## [0.13.0] - 2026-07-18

First public release.

### Added
- **Server-side `/m1srv` command tree.** M1 absorbed the previously separate M1-Server companion mod,
  so a single jar now carries both the client agent and the server authority. M1 requires neither
  side: in single-player the client drives the in-process integrated server; on a dedicated server
  `/m1srv` runs server-side and replies over system chat, which the client captures.

### Changed
- **Forge cells build on ForgeGradle 7** (Gradle 9.6, Minecraft Mavenizer) on 1.20.1, 1.20.2, 1.20.6,
  1.21, 1.21.4, 1.21.5, 1.21.6, 1.21.8, 1.21.10 and 1.21.11. On the SRG-runtime cells (1.20.1, 1.20.2)
  the `net.minecraftforge.renamer` plugin produces the shipped mojmap->SRG jar.
  Forge 1.20.4 remains on ForgeGradle 6 pending an upstream Mavenizer fix.

### Fixed
- **Resource pack metadata on Minecraft 1.21.10 and 1.21.11.** These versions fall in the pack-format
  "dead zone" where no single `pack.mcmeta` satisfies both the client and server codecs. M1 previously
  shipped a plain resource-major format there, which made the game log
  `Couldn't load mod:m1 pack metadata` and drop the mod's resource pack entirely (its language file
  and textures). Forge cells now declare the exact data-major range (88 on 1.21.10, 94 on 1.21.11) and
  Fabric/NeoForge cells ship no `pack.mcmeta` at all, letting the loader synthesise correct per-type
  metadata.

### Notes
- Versions before 0.13.0 were internal builds and were never publicly available.

[0.13.0]: https://github.com/Kishku7/m1
