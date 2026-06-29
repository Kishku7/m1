# 02 -- Create a single-player world
<!-- Valid as of: M1 v0.5.0 | MC 1.20 - 26.3 | updated 2026-06-29 -->
**Covers:** driving the Create World menus from the title screen -- gathering the user's choices,
setting the options (incl. Allow Commands), and confirming creation.

**Status:** STUB.

**Planned sections:**
- Pre-flight: load `ask_the_user.md` first; collect name, seed, game mode, difficulty, cheats Y/N.
- Navigate title -> Singleplayer -> Create New World; drive by `describe` widget labels, not coords.
- Set options: Game tab (mode, Allow Commands toggle -- read the neighbouring label), World tab
  (seed, type), Difficulty. Confirm each toggle state BEFORE Create.
- Create and verify you land in a world (`where` shows pos).
- VERSION DIFFERENCES callout (1.20 vs 1.21 vs 26 menu layout) -- general flow + deltas, not forks.
