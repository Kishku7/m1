# M1 -- Machine One AI Interface (MC 26.x)

Client-side control mod: exposes the live Minecraft client over a localhost text socket
(127.0.0.1:26000), described + driven by text commands (the m1-cmd harness). Internal tooling, ARR.

Layout (shared standard):
- shared_minecraft/ -- all MC-coupled core. Single source of truth, srcDir'd into each loader.
- Fabric/ -- Fabric entrypoint only (M1Client).
- NeoForge/ -- NeoForge entrypoint only (M1NeoForge).
