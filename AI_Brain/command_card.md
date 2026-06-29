# Command card (exact syntax cheat-sheet)
<!-- Valid as of: M1 v0.5.0 | MC 1.20 - 26.3 | updated 2026-06-29 -->
**Covers:** every M1 command with syntax + a one-line note, so you can reload just the syntax cheaply.

**Status:** STUB. Source = section 5 of `main/AI_INSTRUCTIONS.md`, kept current with the code.

**Planned groups (from the live command set):**
- Observe: `describe` | `where` | `look` | `scan [r|<name>]` | `inv` | `slots`
- Menus:   `click <id>` | `type <id> <text>` | `worlds` | `joinworld <idx>`
- Move:    `moveto <x> <z>` | `goto <x y z>` | `move <dir> <n>` | `face <dir|x y z>` | `stop`
- Act:     `mine [x y z]` | `place` | `hold <0-8>` | `equip <item>`
- Craft:   `openinv` | `craft planks|sticks|table|axe` | `close`
- World:   `cmd <server command>` | `pause`
- Gear:    `openpack` | `upgrades` | `autoupgrade on|off`
- Util:    `screenshot [name]` | `help` | `quit`
Note async commands (move/mine/craft) return immediately -- poll to track. Keep in sync with code.
