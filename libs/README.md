# libs/

This directory is **empty in the repository on purpose**. These jars have to be supplied by you
before this project will compile. Which files are needed, and what they must be called, depends on
the node: the file name is how each build script selects a jar, and one Minecraft generation's jar
is not compilable against another.

```
# NeoForge nodes -- the NeoForge build of the world map, one per Minecraft version:
libs/xaero-1.21.1-1.47.0.jar                     # required: everything renders through it
libs/xaero-26.3-1.47.1.jar
# Fabric nodes -- the FABRIC build of it; Xaero names these two ways:
libs/xaeroworldmap-fabric-1.21.1-1.47.0.jar
libs/XaerosWorldMap_1.39.10_Fabric_1.21.6.jar
# 26.x (both loaders) -- XaeroLib, extracted from the world map's nested jar (see below):
libs/xaerolib-neoforge-26.3-1.8.3.jar
libs/xaerolib-fabric-26.3-1.8.3.jar
# optional, NeoForge only -- needed only to compile the mcphone integration:
libs/mcphone-1.10.2-1.21.1-neoforge.jar
```

A node whose Xaero jar is missing fails with an unresolved-reference error. That is expected, not a
broken checkout: building one node needs only that node's jar, while `chiseledBuild` (all 21 nodes)
needs one for every node. The build scripts prefer the jar in `libs/` and fall back to the Modrinth
coordinate in the node's `gradle.properties`; the libs jar is the offline-capable path. The mcphone
jar is different: without it the integration is simply excluded from the build, and a jar is only
used by the node whose Minecraft version is in its file name (`mcphone-*-<minecraft version>-neoforge*.jar`),
see the note in `build.neoforge.gradle.kts`. `libs/*.jar` is in `.gitignore`, so none of them is
ever committed.

Get them from their official CurseForge or Modrinth pages. This project develops against Xaero's
World Map 1.47.x and MCphone 1.10.2.

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

## Extracting XaeroLib (26.x only)

From the third-tier rendering build the element API (`xaero.lib.client.graphics.XaeroBufferProvider`
and friends) no longer lives in the world map jar: it is in **XaeroLib**, which the world map
bundles as a nested jar. Extract it and give it the name that node's `deps.xaerolib` asks for
(`versions/<node>/gradle.properties`):

- NeoForge world map: `META-INF/jarjar/xaerolib-neoforge-<mc>-<ver>.jar`
- Fabric world map: `META-INF/jars/xaerolib-fabric-<mc>-<ver>.jar`

`Expand-Archive` cannot read nested jars; use `[System.IO.Compression.ZipFile]` in PowerShell. Both
the world map and XaeroLib are `compileOnly`; at run time the world map provides XaeroLib itself.

## Version notes

- Xaero's World Map: every node compiles against the build of the same Minecraft generation, and the
  declared range in `neoforge.mods.toml` is `[1.40.0,)` (`[1.1.0,)` for XaeroLib). Older
  generations (1.20.6, 1.21.6) only ever got the 1.39.x world map, which is what those nodes use —
  they declare `deps.xaero_range=[1.39.0,)` in their own `gradle.properties`, and their XaeroLib
  entry goes out as `optional` because the 1.39.x jars ship no XaeroLib at all. Requiring either at
  the newer values made those two nodes refuse to start on every install.
- MCphone: the app API is versioned and MCphone logs `MCphoneApi.VERSION`. It also logs an
  `App 已登记: howtogo:navigator` line when the integration registers — if that line is missing while
  MCphone is installed, the service file or the app class is the place to look.
