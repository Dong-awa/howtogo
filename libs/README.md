# libs/

Put **your own copy** of the Xaero's World Map jar here before building:

```
libs/xaeroworldmap-neoforge-1.21.1-<version>.jar
```

## Why it is not in the repository

Xaero's World Map is **closed-source commercial software**, so its jar is not redistributed here.
It is declared as a `compileOnly` dependency purely so this project can compile against its public
extension API; the jar never ends up inside the built mod, and nothing from it is copied into this
source tree.

Get it from the official CurseForge or Modrinth page. The build fails with an unresolved-reference
error until the jar is present — that is expected, not a broken checkout.

## Why no reobfuscation

Xaero's jars use the official (Mojang) mappings, which is what a NeoForge development environment
already uses, so no remapping step is needed.

## Version note

The declared dependency range in `neoforge.mods.toml` is `[1.40.0,)`. If you substitute a much newer
jar than the one this was developed against (1.40.16), check the log for the layer registration line
before assuming a rendering problem is a bug in this mod.
