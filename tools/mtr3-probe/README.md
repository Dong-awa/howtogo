# mtr3-probe — verifies the MTR 3.x (mtr.*) reflection handshake against the real 1.20.1-Fabric classpath

Answers one question, with no game running: **do the classes, fields and methods a reflective reader
for MTR 3.x plans to touch actually exist, under the names a running 1.20.1 Fabric game gives them?**

Two tools live here and they answer two different halves:

- `run.ps1` — the **names**. Every class/field/method the reader plans to look up, resolved against a
  real 1.20.1-Fabric runtime classpath.
- `bind.ps1` — the **reader itself**. Compiles the real `Mtr3Data` (from `src/`) together with a stub
  `HowToGo`, binds it against the jar, builds a two-station/one-line/one-rail railway out of MTR's own
  classes (`Station`, `Platform`, `Route`, `Rail`, all real objects) and runs the full conversion,
  reading the result back. 31 checks. This is the one that catches a reader that binds but converts
  wrongly.

The jar under test (YMTR `MTR-fabric-1.20.1-3.6.4.2.jar`) is a **production** Fabric build: it names
Minecraft with intermediary names (`net.minecraft.class_2338`). A Loom node's runtime classpath is
remapped to the **official** names (`net.minecraft.core.BlockPos`) instead, so the probe runs twice:

| pass | classpath | answers |
| --- | --- | --- |
| `dev` | `:1.20.1-fabric` runtimeClasspath + MTR jar | every `mtr.*` name; `class_*` rows are expected to miss (namespace fact) |
| `production` | the same + Loom's cached intermediary Minecraft jar | the names a running Fabric game actually has, and that MTR's own field types resolve |

## Usage

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File "H:\MCMOD\HowToGo\tools\mtr3-probe\run.ps1"
```

Options: `-Refresh` (re-read the classpath from Gradle), `-MtrJar <path>`, `-Node <stonecutter node>`,
`-GradleUserHome <path>`, `-IntermediaryJar <path>`, `-DevOnly`.

What it produces:

- `build/classpath-1.20.1-fabric.txt` — the node runtime classpath, cached (re-read with `-Refresh`).
- `build/probe-dev.log`, `build/probe-production.log` — the two tables, also printed.
- `build/probe-classes/` — `Probe.class`, compiled against the JDK only (every lookup is reflective).

## Verified (production pass, 2026-11-07, JDK 21.0.11, YMTR 3.6.4.2)

All MTR classes load with `Class.forName(name, false, loader)`; all planned members resolve:

| item | verified shape |
| --- | --- |
| `mtr.client.ClientData` | `STATIONS Set<Station>`, `PLATFORMS Set<Platform>`, `ROUTES Set<Route>`, all `public static final` |
| `ClientData.RAILS` | `Map<class_2338, Map<class_2338, mtr.data.Rail>>` (two levels) |
| `ClientData.DATA_CACHE` | `mtr.client.ClientCache` (extends `mtr.data.DataCache`) |
| `NameColorDataBase` | `public final long id`, `public String name`, `public int color`, `public final TransportMode transportMode`; inherited and found by `getField` on Station/Platform/Route |
| `TransportMode` constants | `TRAIN, BOAT, CABLE_CAR, AIRPLANE` |
| `AreaBase.corner1/corner2` | `public net.minecraft.class_3545<Integer, Integer>` (`= net.minecraft.util.Tuple`) |
| corner accessors | `method_15442()` = A/first, `method_15441()` = B/second (values proven on a constructed tuple). **`getLeft`/`getRight` do not exist**; the official spelling is `getA`/`getB` |
| `AreaBase.getCenter()` | `()Lnet/minecraft/class_2338;` |
| `SavedRailBase.getMidPos()` | `()Lnet/minecraft/class_2338;` (also `(Z)` overload) |
| `DataCache.platformIdToStation` | `public final Map<Long, mtr.data.Station>`; found through `ClientCache` too |
| `Rail.transportMode` | `public final TransportMode` |
| `Rail.getLength()` | `()D` |
| `Rail.getPosition(double)` | `(D)Lnet/minecraft/class_243;` |
| `Route.platformIds` | `public final List<Route$RoutePlatform>` |
| `Route.isHidden` | `public boolean` |
| `Route.getDestination(int)` | `(I)Ljava/lang/String;` |
| `Route$RoutePlatform.platformId` | `public final long` |
| `Route$RoutePlatform.customDestination` | `public String` |
| `RailwayData.getInstance` | `public static (Lnet/minecraft/class_1937;)Lmtr/data/RailwayData;` |
| `RailwayData` fields | `public final Set<Station> stations`, `Set<Platform> platforms`, `Set<Route> routes`, `mtr.data.DataCache dataCache` |
| `RailwayData.rails` | `private final Map<class_2338, Map<class_2338, Rail>>`; `setAccessible(true)` allowed. A **public `getRailsMap()`** exists and asks for no privileged access |
| `class_2338` (BlockPos) | `method_10263`/`method_10264`/`method_10260` = x/y/z (11/22/33 on a constructed pos), declared on `class_2382`; official spelling `getX/getY/getZ` |
| `class_243` (Vec3d) | `field_1352` = x, `field_1351` = y, `field_1350` = z (`public final double`), proven by constructing `(1.5, 2.5, 3.5)` |

Namespace map (from the node's layered Tiny mappings, confirmed at runtime):
`class_2338`↔`net.minecraft.core.BlockPos`, `class_2382`↔`net.minecraft.core.Vec3i`,
`class_243`↔`net.minecraft.world.phys.Vec3`, `class_3545`↔`net.minecraft.util.Tuple`,
`class_1937`↔`net.minecraft.world.level.Level`.

## Caveats the reader/harness has to live with

1. **A dev (named) classpath cannot bind MTR 3.x at all.** MTR's data classes name MC types in their
   members, and the JVM resolves those when the class is *verified*, so even `getField("id")` on
   `Station` and `getField("STATIONS")` on `ClientData` throw `NoClassDefFoundError` on the dev pass
   (`class_1657`, `class_3545`, `class_2540`, `class_2338`, …). `mtr.data.RailwayData` cannot even be
   *loaded* there: `mtr.mappings.PersistentStateMapper extends net.minecraft.class_18`.
2. **With the intermediary Minecraft jar appended, everything binds and loads with no game** — that is
   exactly the production pass. A dev-time harness handshake therefore needs that jar on the
   classpath (this tool already knows how to find it), or the MTR jar remapped to named by Loom.
3. The reader must catch `LinkageError` as well as `ReflectiveOperationException` around `bind()`:
   on a classpath without MC, the failure is a `NoClassDefFoundError`, not a `NoSuchFieldException`.
4. The MTR jar bundles `org.msgpack` (112 classes); no extra dependency is needed for these lookups.

## Files

- `Probe.java` — the probe. One table per section, `ok`/`MISS` per planned name, real descriptors,
  values for accessors that can be invoked. Exit code 0; the counts at the end are the verdict.
- `run.ps1` — obtains the node classpath via Gradle (`--offline`, init script, `--configure-on-demand`),
  compiles, runs both passes, writes the logs.
- `BindCheck.java` — the reader-under-test check: same package as `Mtr3Data`, so it can drive the
  package-private `build(...)` conversion directly.
- `bind.ps1` — compiles `Mtr3Data.java` + `MtrSnapshotData.java` + a stub `HowToGo` + `BindCheck.java`
  against the JDK only, then runs it on the production classpath. Reuses `run.ps1`'s cached classpath;
  run that once first (or pass `-Refresh`).

## Running the reader check

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File "H:\MCMOD\HowToGo\tools\mtr3-probe\bind.ps1"
```

Exit code is the check's own, so a reader that stops converting fails the script. It is a *reflective*
build: nothing of MTR or Minecraft is compiled against, only run against, so it can be pointed at
another 3.x jar with `-MtrJar <path>`.
