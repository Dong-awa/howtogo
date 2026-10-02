# Routing regression harness

Runs the routing code outside the game, on a handful of networks built to reproduce the defects it
was written after. Nothing here is part of the mod: `tools/` is outside the source set, so the jar is
unaffected and nothing in `src/` depends on it.

## Running it

```powershell
.\tools\routing-harness\run.ps1
```

If the machine's execution policy refuses to run scripts, which is the default on Windows:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\routing-harness\run.ps1
```

The script type-checks the mod's sources with `javac`, compiles `Harness.java` against them into
`tools/routing-harness/build/`, runs it, and exits with the harness's own status: non-zero when any
check fails.

It compiles the mod itself rather than calling Gradle's `compileJava`, because that task depends on
the minecraft artifacts task, which cannot rewrite its jars while the game is running from them --
and having the harness work while the game is open is the point of it. The classpath still comes from
Gradle, through a temporary init script, so `build.gradle` is untouched.

## What it covers

| Scenario | What it would have done before |
|---|---|
| Two roads whose ends are one block apart, far from the origin | No route at all: the pass that joins coincident nodes filed them under their world coordinates on the way in and looked them up by grid cell on the way out, so it only ever matched within three blocks of the origin |
| Both ends standing in the middle of one long road | Correct answer only by way of the 12-by-12 node fallback, because splitting the start removed the segment the goal was measured on |
| Standing exactly on a bend | No route: the anchor was read as the far end of the road, the connector was past the mode's distance cap, and the whole anchored attempt was discarded |
| A destination that is itself a stop on the line | No journey: the walk from the stop to the destination is zero blocks long, a route needs two points to be a route, so the stop could not be alighted at and the search found nothing |
| A goal whose nearest road is a fragment nothing routes to | Nothing, unless the node fallback happened to pick the same endpoints -- it now does so in one multi-source search instead of up to 144 separate ones |
| A T junction drawn a block short of the road it meets | No route, however plainly the two meet on screen: nothing but a shared node joins two roads |
| Two roads drawn across each other | No route across the crossing, for the same reason |
| Two railways crossing, with a line calling either side | No ride at all, so no journey: the ride between the two stops could not be planned |
| A road crossing another at a different height | Should stay two roads, and does: the repair declines to invent a junction at a bridge |
| Two road ends a block apart but eight blocks apart vertically | Must still join: a hand-drawn network's heights are whatever the ground was under each click, and refusing those joins disconnected networks that had been routing for as long as they existed. This is a repair taking a route away, which is the one thing it must never do |
| A railway read out of the world: eight parallel polylines, a vertex every block | Was quadratic -- every edge of such a polyline shares cells with thousands of its own neighbours and each was a candidate pair to build and reject. A plan over 4800 such edges is now well under a fifth of a second |
| A 3120 segment network | Guards the cost of all of the above: the network is copied and repaired once per plan, on the client thread, so a plan must stay well under a fifth of a second |
| One line that goes 2500 blocks around against two lines that change at a 20 block walk | The 2500 block single-line journey: transfers were searched in a second pass that only ran when the first found nothing |
| A journey whose last leg is a 200 block off-road hop | Flattened route time and length short by that hop, because only the first part's start connector and the last part's goal connector were carried into the joined route |

The last two also assert that `TransitPlanner.planRoute`'s flattened route agrees with the sum of the
trip's own legs, and that one boarding's waiting is in the estimate -- the invariant that keeps the
HUD's number the same one the search chose the journey by.

The harness runs with no config file, so the config-backed values it depends on are the declared
defaults: sixty seconds of waiting per boarding, falling back to walking when the chosen mode is
slower, and the road-join repair on.

The repair is also switchable in game, as `repair_road_joins` in `config/howtogo-client.toml`. It is
the newest and most invasive part of the router -- it rewrites the network it routes on, though never
the network the player saved -- so having a way to turn it off without a rebuild is worth its line in
the config. A route that appears with it off and not with it on is a bug in the repair.

## Adding a case

`Harness.java` is plain Java with no test framework. Add a `scenarioX()` method, call it from
`main`, and build the network with `addRoad(net, RoadClass.ROAD, x0, z0, x1, z1, ...)` -- one polyline
per segment, sharing a node with anything already at either end, which is what the editor does when a
click lands on a node. Lines are `line(id, kind, stop(name, x, z), ...)`.
