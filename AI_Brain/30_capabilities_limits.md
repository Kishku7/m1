# Capabilities & limits (what M1 can and cannot do)
<!-- Valid as of: M1 v0.5.0 | MC 1.20 - 26.3 | updated 2026-06-29 -->
**Covers:** an honest boundary list so you do not attempt impossible things or loop on them.

## CAN
- Read any screen as text (`describe`) and drive menus (`click` / `type`) -- create worlds, join
  servers, navigate any GUI.
- Read the world without looking: `where`, `scan` (line-of-sight), `look`, `inv`, `slots`.
- Move with pathfinding (`moveto` / `goto`), `mine`, `place`, `hold`, `equip`, basic `craft`.
- Run slash-commands via `cmd <...>` when cheats/permission are present.
- `screenshot` a frame; armor auto-equips itself to the best you carry.

## CANNOT (current)
- **Fly.** There is no fly command. In Creative a vantage from the air is not reachable through M1
  yet, so plans that need flight do not work -- stay grounded.
- **Run two clients.** Exactly one M1 socket (`127.0.0.1:26000`); one client at a time.
- **Log the user in / authenticate.** You cannot supply Microsoft credentials; the operator handles launch and auth.
- **Beat the 10 s cap on a single synchronous handler.** Long actions must be the async ones
  (`move`/`mine`/`craft`), which return immediately and run in the background.

## QUIRKS to plan around
- `scan` is **line-of-sight only** -- you cannot path to what you cannot see; re-scan from new spots.
- Output can be **block-buffered** in some setups -- a status/where poll is your live signal of progress.
- **Menus differ by MC version** (1.20 / 1.21 / 26): drive by label from the live `describe`, never by
  hardcoded ids.
- Movement is one step/leg at a time; do not fire a new move while one is running -- poll `where` first.

Keep this file in sync with the command set: when a capability is added (e.g. a future `fly on/off`),
move it from CANNOT to CAN here and update `10_command_card.md` in the same pass.
