# Changelog

All notable changes to M1 are documented here.
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.0.0/); this project uses
`<mod version>+<minecraft family>-<loader>` jar naming.

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
