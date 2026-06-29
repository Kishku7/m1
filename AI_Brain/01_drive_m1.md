# 01 -- Drive the M1 interface (LAW)
<!-- Valid as of: M1 v0.5.0 | MC 1.20 - 26.3 | updated 2026-06-29 -->
**Covers:** how to talk to M1 -- connect, wire protocol, command vocabulary, and the
observe -> decide -> one small action -> check discipline. Exact reference; follow literally.

**Status:** STUB. Source = refactor of `main/AI_INSTRUCTIONS.md` (scope rewritten: menu-driving and
world creation are now FIRST-CLASS, not "only if asked").

**Planned sections:**
- Connect: 127.0.0.1:26000 loopback; one client at a time; discard the greeting.
- Wire protocol: one command/line, read until `<<END`, keep leftover bytes, ~15s socket timeout,
  the `--- now ---` confirmation block, `[auto-upgrade]` unsolicited lines.
- Operating discipline: look before you move; pathfind, don't bump walls; don't repeat what failed.
- Reading `scan` (line-of-sight), `where` movement status, `inv` vs `slots` numbering, armor auto-upgrade.
- Camera / interacting / leaving a world cleanly.
- Pointer: for syntax see `command_card.md`; when stuck see `recovery.md`.
