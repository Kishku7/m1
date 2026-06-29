# Command card (exact syntax cheat-sheet)
<!-- Valid as of: M1 v0.5.0 | MC 1.20 - 26.3 | updated 2026-06-29 -->
**Covers:** every M1 command with syntax + a one-line note, so you can reload just the syntax cheaply.
Concepts behind these live in `01_drive_m1.md`.

```
OBSERVE
  describe              list the current screen's widgets (id, type, message/label, pos, V/A/F flags)
  where                your position + movement status (pos / moving / arrived / blocked / no world)
  look                 what you are pointed at (block/entity + distance)
  scan [r|<name>]      line-of-sight scan; r<=32 (default 32). scan <name> = nearest match. scan 16 = radius 16
  inv                  stable player inventory (0-8 hotbar, 9-35 main, 36-40 armor/offhand)
  slots                slots of the OPEN menu (renumber per container -- always run live)

MENUS
  click <id>           click widget <id> from the latest describe; reply includes a `--- now ---` re-describe
  type <id> <text>     type into edit box <id> (e.g. world name, seed, server address)
  worlds               list saved single-player worlds (index + name)
  joinworld <idx>      load an EXISTING saved world by its `worlds` index

MOVE (async -- returns at once, poll `where`)
  moveto <x> <z>       pathfind to an x,z (routes around walls, steps up). Preferred mover
  goto <x> <y> <z>     pathfind to a full coordinate
  move <dir> <n>       move n blocks in a compass dir (north/south/east/west/ne/nw/se/sw)
  face <dir | x y z>   aim the camera (resting view = face 0 0; yaw 0 = south)
  stop                 cancel the current move

ACT (async -- poll inv/look)
  mine [x y z]         mine the block you face, or the one at x y z
  place                place/use/interact the held item or the block you face
  hold <0-8>           select hotbar slot 0-8 (= in-game 1-9)
  equip <item>         equip a named item from inventory

CRAFT
  openinv              open your inventory (2x2 crafting)
  craft planks|sticks|table|axe   craft a known recipe
  close                close the open menu

WORLD
  cmd <server command> run a slash-command (e.g. cmd time set day) -- needs cheats/permission
  pause                open the pause menu (then click "Save and Quit to Title" to leave)

GEAR
  openpack             open a worn Travelers Backpack
  upgrades             re-show pending armor auto-upgrade notes
  autoupgrade on|off   toggle the armor auto-upgrade loop

UTIL
  screenshot [name]    capture a frame (saved on the client machine)
  help                 M1's own command help
  quit                 disconnect this M1 session
```

Notes:
- **Async commands** (`move*`/`goto`/`mine`/`craft`) return immediately -- poll `where` / `inv` /
  `look` to track them. They do NOT hit the 10 s execution cap.
- `slot <id> ...` and `slots` use the OPEN MENU index; `hold` / `equip` use the stable `inv` index.
  Do not mix them (see `01_drive_m1.md` section 7).
- `worlds`/`joinworld` load an EXISTING world. CREATING a new world is menu-driving with
  `describe`+`click`+`type` (see `02_create_world.md`).
- This card tracks the code. If `help` shows a command not listed here, trust `help` and flag the drift.
