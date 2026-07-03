# 03 -- Join a multiplayer / LAN world
<!-- Valid as of: M1 v0.9.1 | MC 1.20 - 26.3 | updated 2026-07-02 -->
**Covers:** picking and joining a server (saved entry, direct connect, or an open LAN game), and what
to confirm with the user first.

Menu-driving basics are in `01_drive_m1.md` section 5.

## Before you connect
Load `20_ask_the_user.md` and confirm:
- WHICH server: a saved entry in the list, a direct address (`host` or `host:port`), or a detected LAN
  game. If the user gives an address, read it back before connecting.
- That the user actually wants to join THIS server and is allowed to be there. If it is someone else's
  server, also read `50_safety_etiquette.md` before doing anything in-world.

## Flow (drive by label -- read the live screen)
1. From the title: `describe` -> click **Multiplayer**. (If a "may be unsafe" notice appears, that is
   the standard third-party-server warning -- proceed only with the user's go-ahead.)
2. On the server list `describe` shows: saved servers, plus **Add Server**, **Direct Connection**, and
   **Join Server** / **Refresh**. LAN games on the network appear in this same list automatically.
   - **Direct connect:** click **Direct Connection** -> `type <id>` the address into the box ->
     click **Join Server**.
   - **Saved server:** click the entry, then **Join Server**. (Or **Add Server** -> `type` a name +
     address -> **Done**, then join it.)
   - **LAN:** click the detected LAN entry -> **Join Server**.
3. Expect a "Connecting..." screen. Then verify: `where`. `pos=(...)` = you are in. A failure screen
   ("Can't connect", timeout, version mismatch) means STOP and report it -- see `40_recovery.md`; do not
   spam retry.

## First moments on a shared server
- `where` + `scan`, give a short situation report, and ASK the user what they want before acting.
- Follow the server's rules and `50_safety_etiquette.md`: no griefing, stealing, or PvP unless the user
  confirms it is allowed. Do not run `cmd` there unless you have permission.

## Version differences
Button text and the multiplayer screen layout vary across 1.20 / 1.21 / 26. Do not hardcode it --
`describe`, find by label, act, confirm with the `--- now ---` block and `where`.
