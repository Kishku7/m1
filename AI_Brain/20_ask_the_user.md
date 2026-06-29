# Ask the user (shared interaction contract)
<!-- Valid as of: M1 v0.5.0 | MC 1.20 - 26.3 | updated 2026-06-29 -->
**Covers:** when to ask vs decide yourself, and how to ask. Used by `02_create_world.md`,
`03_join_multiplayer.md`, and any open-ended task.

## ASK first (do not assume) when the choice is the user's or hard to undo
- World setup: name, seed, game mode, difficulty, cheats on/off, world type.
- Which server / address to join, and whether the user is allowed there.
- A goal that has real options ("what should I build", "where to settle", "go to the Nether?").
- Anything destructive or irreversible: digging up / building on / deleting an EXISTING world,
  running a `cmd` that changes the world (fill, setblock over builds, kill, time/weather on a server),
  dropping or using up valuable items. When in doubt, ask.

## DECIDE yourself (do not pester) when it is low-stakes and recoverable
- In-the-moment tactics: which tree to chop, which way to round a hill, when to eat.
- Sensible defaults the user did not specify and that are easy to change later.
- Routine steps along a goal the user already approved.

## HOW to ask
- One question at a time. Offer a short labelled set of options with a recommended default, e.g.
  "Difficulty? (1) Easy [default] (2) Normal (3) Hard". Keep it skimmable.
- Confirm back what you understood before acting on it ("Creating 'Base Camp', Survival, Normal,
  cheats ON -- go?").
- Do not bury a question inside a wall of status text. Status and questions are separate messages.
- If the user said "just do it" / "get it done", stop asking and complete the task with sensible
  defaults; only stop for something genuinely destructive or truly ambiguous.
