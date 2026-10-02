# libs/

This directory is **empty in the repository on purpose**. Two jars have to be supplied by you
before this project will compile:

```
libs/xaeroworldmap-neoforge-1.21.1-<version>.jar     # required: everything renders through it
libs/mcphone-1.21.1-neoforge-<version>.jar           # required to compile: optional integration
```

The build fails with an unresolved-reference error until both are present. That is expected, not a
broken checkout. `libs/*.jar` is in `.gitignore`, so neither jar is ever committed.

Get them from their official CurseForge or Modrinth pages. This project develops against
Xaero's World Map 1.40.16 and MCphone 1.10.2.

## Why neither is redistributed

Both are other people's work:

- **Xaero's World Map** is closed-source commercial software.
- **MCphone** publishes its own source, but its jar is still its author's to distribute, and this
  repository is not a mirror of it.

Both are `compileOnly`: this project is compiled *against* them and never bundles them, so the mod
this produces contains no code of theirs. The `libs/` directory exists so the compiler can see their
public APIs and nothing more.

## Why no reobfuscation

Both jars use the official (Mojang) mappings, which is what a NeoForge development environment
already uses, so no remapping step is needed.

## How the two integrations differ

They are not the same kind of dependency, and the difference matters when reading the code:

- **Xaero is required at build time and at run time.** Every road and route is drawn through its map
  layer, and `neoforge.mods.toml` declares it as a hard dependency.
- **MCphone is optional at run time and only present for compilation.** Nothing in this mod refers to
  it except one class under `bili.dongsz.howtogo.compat.mcphone`, which MCphone discovers through
  `META-INF/services`. With MCphone absent that class is never loaded and the mod behaves exactly as
  it did before. See that package's javadoc for the invariant that keeps this true.

## Version notes

- Xaero's World Map: the declared range in `neoforge.mods.toml` is `[1.40.0,)`. If you substitute a
  much newer jar than 1.40.16, check the log for the layer registration line before assuming a
  rendering problem is a bug in this mod.
- MCphone: the app API is versioned and MCphone logs `MCphoneApi.VERSION`. It also logs an
  `App 已登记: howtogo:navigator` line when the integration registers — if that line is missing while
  MCphone is installed, the service file or the app class is the place to look.
