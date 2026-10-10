# libs/

This directory is **empty in the repository on purpose**. One jar has to be supplied by you before
this project will compile:

```
libs/xaeroworldmap-fabric-1.20.1-<version>.jar     # required: everything renders through it
```

The build fails with an unresolved-reference error until it is present. That is expected, not a
broken checkout. `libs/*.jar` is in `.gitignore`, so the jar is never committed.

Get it from its official Modrinth or CurseForge page, or from the author's own developer Maven
(`https://chocolateminecraft.com/maven`, artifact `xaero.map:xaeroworldmap-fabric-1.20.1`). This
branch develops against Xaero's World Map 1.47.0.

## Why it is not redistributed

Xaero's World Map is closed-source commercial software: it may not be redistributed, bundled, or
jar-in-jarred, so this repository is not a mirror of it. It is a compile-time dependency only --
this project is compiled *against* it and never packages it, so the mod this produces contains no
code of its own. The `libs/` directory exists so the compiler can see its public API and nothing
more.

## Why the jar is remapped here and was not on NeoForge

The NeoForge branch needed no remapping step: NeoForge runs on the official (Mojang) mappings, which
is what that jar is published with, and `src/common` and `src/main` are both written in those names.

Fabric is different: its mods are published against **intermediary** names, so the shipped jar
cannot be compiled against directly. Loom remaps it into the development environment's mappings,
which is why the dependency is declared `modCompileOnly` and not `compileOnly`. This branch still
uses `loom.officialMojangMappings()` for the same reason the other branch uses official mappings --
so that the source keeps its `net.minecraft.*` names and `src/common` can be shared between branches
verbatim.

## What is not needed here

Only Xaero's World Map is a compile-time dependency. MTR (`org.mtr.*`), Create
(`com.simibubi.create.*`), Xaero's Minimap (`xaero.common.*`) and MTR Map Overlay
(`com.lx862.mtrmap.*`) are reached purely by reflection -- `Class.forName` plus `getMethod` behind a
`FabricLoader.isModLoaded` guard -- so their jars are runtime mods rather than build inputs.

MCphone is **not** part of this branch. Its 1.20.1 build is Forge-only and no Fabric build for
1.20.1 exists, so `bili.dongsz.howtogo.compat.mcphone` and its `META-INF/services` entry are absent
here; the NeoForge branch keeps that integration. Nothing else refers to that package, so no other
class changed as a result.

## Version notes

- Xaero's World Map: 1.47.0 is what this branch was built and tested against. `fabric.mod.json`
  declares it under `suggests` rather than as a hard dependency, and every use is guarded by
  `FabricLoader.isModLoaded("xaeroworldmap")`; with the mod absent this one loads and logs that the
  road layer is disabled.
- If you substitute a much newer jar, check the log for the registration line
  (`[HowToGo] road layer registered with Xaero's World Map`) before assuming a rendering problem is
  a bug in this mod.
