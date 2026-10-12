# libs/

This directory is **empty in the repository on purpose**. These jars have to be supplied by you
before this project will compile:

```
libs/xaero-26.3-1.47.0.jar         # required: everything renders through it
libs/xaerolib-26.3-1.7.17.jar      # required to compile: Xaero's bundled rendering library (see below)
libs/mcphone-1.21.1-neoforge-<version>.jar  # optional: needed only to compile the mcphone integration
```

The build fails with an unresolved-reference error until they are present. That is expected, not a
broken checkout. `libs/*.jar` is in `.gitignore`, so none of them is ever committed.

Get them from their official CurseForge or Modrinth pages. This project develops against
Xaero's World Map 26.3-1.47.0 and MCphone 1.10.2.

## Why none are redistributed

These are other people's work:

- **Xaero's World Map** is closed-source commercial software.
- **MCphone** publishes its own source, but its jar is still its author's to distribute, and this
  repository is not a mirror of it.

All are `compileOnly`: this project is compiled *against* them and never bundles them, so the mod
this produces contains no code of theirs. The `libs/` directory exists so the compiler can see their
public APIs and nothing more.

## Why no reobfuscation

All jars use the official (Mojang) mappings, which is what a NeoForge development environment
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

- Xaero's World Map: this branch compiles against the MC 26.3 build, 1.47.0
  (`xaero-26.3-1.47.0.jar`, named `xaeroworldmap-neoforge-26.3-1.47.0.jar` on Modrinth), and the
  declared range in `neoforge.mods.toml` is `[1.47.0,)`. From the third-tier rendering build the
  element API (`xaero.lib.client.graphics.XaeroBufferProvider` and friends) no longer lives in the
  world map jar: it is in **XaeroLib**, which the world map bundles as a jar-in-jar. Extract it
  before compiling — `Expand-Archive` cannot read nested jars, so take
  `META-INF/jarjar/xaerolib-neoforge-26.3-1.7.17.jar` out of the world map jar (e.g. with
  `[System.IO.Compression.ZipFile]` in PowerShell) and drop it next to it as
  `libs/xaerolib-26.3-1.7.17.jar`. Both jars are `compileOnly`; at run time the world map provides
  XaeroLib itself.
- MCphone: the app API is versioned and MCphone logs `MCphoneApi.VERSION`. It also logs an
  `App 已登记: howtogo:navigator` line when the integration registers — if that line is missing while
  MCphone is installed, the service file or the app class is the place to look.
