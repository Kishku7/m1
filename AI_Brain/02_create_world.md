# 02 -- Create a single-player world
<!-- Valid as of: M1 v0.5.0 | MC 1.20 - 26.3 | updated 2026-06-29 -->
**Covers:** driving the Create World menus from the title screen -- gathering the user's choices,
setting the options (including Allow Commands), and confirming you land in the world.

You already know how to drive a menu (`01_drive_m1.md` section 5): read `describe`, find a control by
its message/label, `click`/`type`, then read the `--- now ---` block. This file is the recipe.

## Before you touch a menu
Load `ask_the_user.md` and collect the choices you cannot safely assume:
- world name, seed (blank = random), game mode (Survival/Creative/Hardcore), difficulty,
  cheats/Allow Commands on or off.
Confirm them back in one line, then proceed.

## Flow (drive by label -- layouts shift across versions, so READ, do not assume)
1. From the title screen: `describe` -> click **Singleplayer**.
2. On the world-select screen: click **Create New World** (M1's `worlds` lists existing saves; use
   this menu only when making a NEW one).
3. The create screen has tabbed sections (named differently across versions -- commonly a Game tab and
   a World / More-Options tab). `describe` after each step and work from what you actually see:
   - **Name:** find the name edit box -> `type <id> <name>`.
   - **Game mode:** the mode control is a cycle button -- `click` it until its message/label reads the
     mode you want (Survival / Creative / Hardcore). Re-`describe` to confirm the value each click.
   - **Allow Commands (cheats):** find the toggle. Its meaning may live in a neighbouring label
     ("Allow Commands"); M1 attaches that label to the ON/OFF control. **Click only if needed**, then
     **re-`describe` and confirm it actually reads ON (or OFF)** before continuing. Do not assume a
     click flipped it the way you wanted.
   - **Difficulty:** cycle button -> click to the chosen value; confirm.
   - **Seed / World type:** on the World/More-Options tab -> `type` the seed into its box; cycle the
     world type if asked. Blank seed = random.
4. Click **Create New World**. Then verify: send `where`. A `pos=(...)` means you are in. If still on a
   menu, `describe` and see `recovery.md`.

## Notes
- cheats != game mode. Allow Commands lets you run `cmd <...>`; it is independent of Survival/Creative.
- If the user wants you to fly for a vantage, that needs Creative AND fly is engaged in-world; M1 has
  no fly command yet (see `capabilities_limits.md`).
- Anything destructive or about the user's EXISTING saves: see `safety_etiquette.md`. Prefer a fresh
  world for experiments; never overwrite or delete an existing save without explicit go-ahead.

## Version differences
The create-world screen's tab names and control layout differ across 1.20 / 1.21 / 26. Do not hardcode
ids or tab names. The robust method is identical on every version: `describe`, locate by message/label,
act, re-`describe` to confirm. If a label you expect is missing, read the live screen and adapt.
