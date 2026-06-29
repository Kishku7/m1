# M1 AI_Brain -- INDEX (read me first)

You are an AI driving Minecraft through the **M1** mod. Read THIS file first. Do NOT read the whole
folder -- load only the file(s) whose trigger matches what you are about to do. Each file states at
the top what it covers and the M1 version / MC range it is valid for.

## Always load first
- `01_drive_m1.md` -- the M1 text interface: connect, wire protocol, command card, observe/act
  discipline. This is LAW (exact). Everything else assumes you already know it.

## Load on demand (match the trigger)
| If you are about to...                                   | Load                     |
|----------------------------------------------------------|--------------------------|
| create / configure a new single-player world            | `02_create_world.md`     |
| join a multiplayer or LAN server                        | `03_join_multiplayer.md` |
| decide what to ASK the user vs decide yourself          | `ask_the_user.md`        |
| play early game -- survive, food + iron                 | `04_play_basics.md`      |
| play mid/late game -- base, gear, prep + reach the End  | `05_play_advanced.md`    |
| a command errored / you are stuck / lost / wrong screen | `recovery.md`            |
| check whether M1 can even DO a thing                    | `capabilities_limits.md` |
| look up exact command syntax fast                       | `command_card.md`        |
| touch the user's existing worlds, or a shared server    | `safety_etiquette.md`    |

## Rules of the brain
- Load the minimum. The router exists so you do not burn context.
- `01_drive_m1.md` + `command_card.md` are exact reference -- follow them literally.
- `02` / `03` and the play files are PLAYBOOKS -- adapt them to the live `describe` / `scan`.
- If a file's header version is older than the running M1, trust live `describe` over the doc and
  flag the drift to the user.
