# M1-Legacy

M1's agentic-player front door for **legacy Minecraft** (pre-Flattening, on Legacy Fabric). Sibling
to the modern M1 project; shares M1's socket protocol and Cog build discipline but owns its own
pre-Flattening subsystem code.

Scope today: the **front door** -- a loopback socket core plus a generic GUI engine that enumerates
any screen's buttons/text-fields and drives click / type / key. First target: **MC 1.8.9**.

## Layout (mirrors modern M1's Cog topology)

```
_codegen/
  compat.py                       drift brain -- version-specific Platform bodies
  cog_sources/
    Platform.java                 the ONE MC-facing facade (cog-direct per version)
    shared/com/kishku7/m1/        MC-agnostic: M1Server, ScreenOps, WidgetInfo, TfInfo
    entrypoints/
      M1Client.legacyfabric.java  single-source loader entrypoint (plain copy)
matrix.json                       the version cells
scripts/cog-gen.ps1               generate a cell's gen/ tree
LegacyFabric/<ver>/               per-version cell: build.gradle, gradle.properties, resources, gen/
```

## Build a cell

```
# 1) generate the cog'd source tree
pwsh scripts/cog-gen.ps1 -Cell "LegacyFabric/1.8.9" -McVer 1.8.9
# 2) build (loom needs a JDK21 build JVM; output is Java-8 bytecode)
../modern-resources/gradlew.bat -p LegacyFabric/1.8.9 build
```

The jar lands in `LegacyFabric/1.8.9/build/libs/`. Run it on a **real 1.8.9 client** (Legacy
Fabric loader + Legacy Fabric API + a Java 8 runtime). It is a client-only mod (skipped on dedicated
servers).

## Add a legacy version

1. Add a cell to `matrix.json` and create `LegacyFabric/<ver>/` (build.gradle, gradle.properties,
   src/main/resources/{fabric.mod.json, m1.accesswidener}).
2. Where the new version's GUI shape differs from 1.8.9, branch the body functions in
   `_codegen/compat.py` on `V(mcver)`. Nothing in `shared/` changes.

## Protocol (loopback 127.0.0.1:26000, newline text, `<<END` sentinel)

`describe` | `click <id>` | `type <fieldId> <text>` | `key <name>` | `ping` | `help` | `raw on|off`

## Notes / known limits

- **Screen names** come back as the intermediary runtime class name on Legacy Fabric (button labels
  are real). A known-screen name map can be added later if needed.
- **Immediate-mode hand-drawn UIs** (no widget object) are not enumerable -- same ceiling as modern M1.
- **Client render / screenshot on LWJGL2** is an open question (shared with Modern Resources); it
  gates any visual smoketest, not the code.
