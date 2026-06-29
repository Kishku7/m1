# Capabilities & limits (what M1 can and cannot do)
<!-- Valid as of: M1 v0.5.0 | MC 1.20 - 26.3 | updated 2026-06-29 -->
**Covers:** an honest boundary list so you don't attempt impossible things or loop on them.

**Status:** STUB.

**Planned sections:**
- CAN: read any screen as text, drive menus, move/mine/craft via async commands, run `cmd <server command>`
  when cheats are on, screenshot, auto-equip armor.
- CANNOT (current): fly (no fly command yet); run two clients (one socket :26000); authenticate /
  log the user in; beat the 10s main-thread cap on a single synchronous handler.
- QUIRKS: scan is line-of-sight only; output can be block-buffered; some menus differ by MC version.
- Keep this in sync with the command set -- update when a capability is added (e.g. fly on/off).
