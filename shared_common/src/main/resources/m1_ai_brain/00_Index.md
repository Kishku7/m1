# M1 AI_Brain -- 00 Index (read me first)

You are an AI driving Minecraft through the **M1** mod. Read THIS file first. Do NOT read the whole
folder -- load only the file(s) whose trigger matches what you are about to do. Each file states at the
top what it covers and the M1 version / MC range it is valid for.

## Always load (every session)
- `01_drive_m1.md` -- the M1 text interface: connect, wire protocol, command card, observe/act
  discipline. This is LAW (exact). Everything else assumes you already know it.
- `100_User_Overrides.md` -- the user's own standing rules. **Its instructions OVERRIDE anything in
  files 00-99.** If it is empty, nothing changes -- but always read it before acting.

## Load on demand (match the trigger)
| If you are about to...                                   | Load                        |
|----------------------------------------------------------|-----------------------------|
| create / configure a new single-player world            | `02_create_world.md`        |
| join a multiplayer or LAN server                        | `03_join_multiplayer.md`    |
| play early game -- survive, food + iron                 | `04_play_basics.md`         |
| play mid/late game -- base, gear, prep + reach the End  | `05_play_advanced.md`       |
| look up exact command syntax fast                       | `10_command_card.md`        |
| decide what to ASK the user vs decide yourself          | `20_ask_the_user.md`        |
| check whether M1 can even DO a thing                    | `30_capabilities_limits.md` |
| a command errored / you are stuck / lost / wrong screen | `40_recovery.md`            |
| touch the user's existing worlds, or a shared server    | `50_safety_etiquette.md`    |

## Rules of the brain
- Load the minimum. The router exists so you do not burn context.
- `01_drive_m1.md` + `10_command_card.md` are exact reference -- follow them literally.
- The `02`-`05` files are PLAYBOOKS -- adapt them to the live `describe` / `scan`.
- `100_User_Overrides.md` wins over every other file here when they conflict.
- If a file's header version is older than the running M1, trust live `describe` over the doc and flag
  the drift to the user.

## File numbering
Default files are numbered `00`-`99` and are maintained by the mod (it may refresh them on a version
update). `100_User_Overrides.md` is yours -- the mod ships it blank and never overwrites it, and carries
your edits forward when you upgrade to a new version.
