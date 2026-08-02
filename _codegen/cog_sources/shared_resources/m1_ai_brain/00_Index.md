# M1 AI_Brain -- 00 Index (read me first)
<!-- Valid as of: M1 v0.16.0 | MC 1.20 - 26.3 | updated 2026-08-02 -->

You are an AI driving Minecraft through the **M1** mod.

## LOAD THE ENTIRE BRAIN AT THE START OF EVERY SESSION

Read **every** `.md` file in this folder before you issue your first command. All of them. Do not
defer any file until you think you need it.

**Every file listed below sits in the SAME DIRECTORY AS THIS FILE.** Resolve them against the folder
you just read `00_Index.md` from -- do not guess a path, and do not go searching. If you loaded this
index from a mirror rather than from inside the mod, the other files are in that same mirror. A
session on 2026-08-02 read this index, guessed wrong for the remaining files, and had to fall back
to a filesystem search; that is what this paragraph exists to prevent. If a listed file is genuinely
missing, say so rather than silently continuing with a partial brain.

The whole brain is roughly **850 lines**. That is a rounding error against your context window, and
it is far cheaper than the alternative.

**Why this is the rule (changed 2026-08-02).** This index used to say "do NOT read the whole
folder -- load only the file(s) whose trigger matches." That was wrong, and it failed in a specific
way: on-demand loading only works if you already know a capability exists, because you cannot match
a trigger for a thing you have never heard of. So the verbs you never learned about are exactly the
ones you never load the file for -- you hand-drive a task M1 already has a single command for, or you
conclude M1 "cannot" do something it does. Recovery and safety files have the same problem: you need
them BEFORE the situation, not after you are already stuck or have already broken something.

Loading everything up front costs a few hundred lines once. Not loading it costs wrong work,
repeatedly, and you never find out.

## The files

| File | What it is |
|------|-----------|
| `01_drive_m1.md` | the M1 text interface: connect, wire protocol, observe/act discipline. **LAW -- follow exactly.** |
| `10_command_card.md` | every command with exact syntax. **LAW -- follow exactly.** |
| `02_create_world.md` | creating / configuring a single-player world |
| `03_join_multiplayer.md` | joining a multiplayer or LAN server |
| `04_play_basics.md` | early game -- survive, food, iron |
| `05_play_advanced.md` | mid/late game -- base, gear, the End |
| `20_ask_the_user.md` | what to ASK vs what to decide yourself |
| `30_capabilities_limits.md` | what M1 can and cannot do |
| `40_recovery.md` | a command errored / stuck / lost / wrong screen |
| `50_safety_etiquette.md` | touching the user's worlds or a shared server |
| `100_User_Overrides.md` | **the user's own standing rules. OVERRIDES every file here.** Read it last so it wins. |
| `README_GENERATED.md` | present only in a generated MIRROR (not shipped in the mod). Says where the mirror came from. Read it if present; it is not part of the instructions. |

## Rules of the brain
- `01_drive_m1.md` and `10_command_card.md` are exact reference -- follow them literally.
- The `02`-`05` files are PLAYBOOKS -- adapt them to the live `describe` / `scan`.
- `100_User_Overrides.md` wins over every other file when they conflict.
- If a file's header version is older than the running M1, trust live `describe` / `help` over the
  doc, and flag the drift to the user.
- **`help` is the ground truth.** If `help` lists a command this card does not, believe `help`.

## File numbering
Default files are numbered `00`-`99` and are maintained by the mod (it may refresh them on a version
update). `100_User_Overrides.md` is yours -- the mod ships it blank and never overwrites it, and carries
your edits forward when you upgrade to a new version.
