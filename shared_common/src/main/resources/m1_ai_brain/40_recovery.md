# Recovery & troubleshooting (when something goes wrong)
<!-- Valid as of: M1 v0.5.0 | MC 1.20 - 26.3 | updated 2026-06-29 -->
**Covers:** detecting and recovering from failure. Read this the moment something is not working --
do not keep firing the command that failed.

## First move when anything is off: re-orient
Send `where` and `describe` and READ them before doing anything else. Learn the true current state
(in a world? which screen? moving? stuck?) -- most "stuck" situations are really "acting on a stale
assumption about where you are."

## Movement failures
- `where` shows `blocked` / `timeout` / `no path`, or your position is not changing: STOP. Do not
  re-fire the same move. Re-`scan`, pick a NEW or CLOSER target, and move in shorter legs if the route
  is complex. Never bump the same wall twice.
- A move that replies `no path to (x,z)` up front: the target is unreachable as seen -- it may be
  behind a wall (`scan` is line-of-sight). Move to an intermediate visible point and re-scan from there.

## Command errors
- `ERR exec: ... TimeoutException`: a synchronous handler overran the 10 s cap. Make sure your socket
  read timeout is ~15 s. For long work use the async commands (`move`/`mine`/`craft`), not a blocking call.
- `ERR ...` other: read the message; fix the input (wrong slot index, no such item, nothing to interact
  with) and retry once with the correction -- not the same call verbatim.

## Wrong / unexpected screen
`describe` to see where you actually are, then navigate back deliberately one click at a time, reading
the `--- now ---` block after each. Do not mash `click`. If a load-warning or error dialog is up, find
its "Continue" / "Back" / "Done" control by label and click that.

## Socket desync (replies look shifted or belong to a previous command)
You dropped bytes after `<<END`. Reconnect, read and discard the `M1 ready...` greeting, and resume.
Always read until `<<END` and keep leftover bytes for the next read.

## In-world but lost
`scan` outward (up to r=32), use `BOUNDS UP` to tell if you are under open sky or enclosed, pick a
known coordinate from a previous `scan`, and `goto` it. If truly boxed in, mine a clear direction
toward `BOUNDS ... open`.

## When to stop and ask the user
After two genuine, different attempts at the same goal have failed, stop and report what you tried and
what you saw -- do not grind. Connection failures, version mismatches, and anything destructive are
immediate stop-and-ask (see `20_ask_the_user.md`).
