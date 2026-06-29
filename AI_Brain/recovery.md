# Recovery & troubleshooting (when something goes wrong)
<!-- Valid as of: M1 v0.5.0 | MC 1.20 - 26.3 | updated 2026-06-29 -->
**Covers:** how to detect and recover from failure -- the highest-value file when an agent is stuck.

**Status:** STUB.

**Planned sections:**
- Re-orient first: `where` + `describe` to learn the true current state before doing anything.
- Errors: `ERR exec ... Timeout` (handler overran) vs `blocked` / `no path` / `timeout` on a move ->
  STOP, re-`scan`, pick a NEW/closer target; never re-fire the same failed command.
- Wrong / unexpected screen: `describe`, navigate back deliberately; don't mash clicks.
- Socket desync (replies look shifted): you dropped bytes after `<<END` -- reconnect and re-read greeting.
- Stuck/empty screen read: confirm a screen is actually up; if in-world but lost, `scan` outward.
