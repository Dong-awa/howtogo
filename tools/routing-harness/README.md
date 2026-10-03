# Routing regression harness

Runs the routing code outside the game, on a handful of networks built to reproduce the defects it
was written after. Nothing here is part of the mod: `tools/` is outside the source set, so the jar is
unaffected and nothing in `src/` depends on it.

## Running it

```powershell
.\tools\routing-harness\run.ps1
```

While working on the code, `run-fast.ps1` runs the same two steps with the classpath kept from the last
full run, which skips Gradle's configuration phase:

```powershell
.\tools\routing-harness\run-fast.ps1            # seconds
.\tools\routing-harness\run-fast.ps1 -Refresh   # re-read the classpath, after a build.gradle change
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
| A road crossing another at a different height | Must stay two roads: a walk over the bridge is a straight hop across the field, not a turn at the crossing, and a drive is refused outright |
| A destination far from any road | Walked to -- 90 blocks of road and then 300 across the field -- while a drive is refused, because there is no road out there. Walking used to be capped at 64 blocks from the road, so this answered "no route" to a place plainly in sight |
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

## The MTR checks

`MtrImportCheck.java` runs as part of the harness and checks the MTR integration after the reflection:
a reading of MTR's own shapes in, this mod's stops, lines and read-only rail layer out. It is in the
`client` package because what it tests is package-private, so that the conversion stays a function of
a reading rather than of MTR being installed -- which is the only way it can be checked at all on a
machine with no MTR.

What it covers: one stop per station, placed at the middle of the station's platforms rather than the
middle of its area; a line whose type this mod has no kind for (an aeroplane) counted and not imported;
a stop whose station the client has not been sent counted and not placed; a boat line becoming a water
line; every marked segment identifiable as read rather than drawn by its id alone; and the
`mtr_auto_route_marks` switch, which must change what is marked and nothing else.

It also covers the **per-line marks switch** the line editor draws beside each imported line: that a
line nobody has answered for takes the configured default, that an answer is kept by MTR's own line id
(an imported line is rebuilt from every reading, so a field on it would not survive the player walking
to the next station), that one line's answer marks its track and leaves another line's alone, and that a
mode which cannot reach a mark's class is not offered it -- the rule that had to ask about water as well
as rail, or a boat line's switch would have done nothing.

And it covers **what a mark is**, through `MtrLineTracks` directly, because that is the part no view can
show: that a line's track is marked rather than MTR's rails as a whole (MTR's data does not say which
rails belong to which line, so the ride between each pair of neighbouring stops is planned and its path
is what is marked), that the mark follows the rails rather than joining the two stops with a chord, that
its ends are the two stops and the hops from a stop onto the track are not marked as track, that a line
whose stops are nowhere near its rails is marked nowhere rather than joined up across open country, and
that two lines over one stretch of rail get marks of their own with ids that cannot collide.

And it covers the **interchange rule** the map draws its orange markers from, through
`TransitInterchanges`: that two lines calling a few blocks apart are an interchange (the platform and
the stop beside it are one place to travel through), that the radius is the planner's own and inclusive
at its edge, that one line's own stops standing close are *not* an interchange, and that with one of the
two lines gone the place stops being one. That last pair is what the rule is for: a marker that stayed
orange after a line was cancelled, because the marker had never been about two lines.

`RideRoadsCheck.java` is in the `route` package for the same reason, and checks the seam the switch
rests on: a line whose marks are off is handed a network that never had them, because MTR's marks are
one layer and "do not add them for this line" is not something the planner could act on.

It cannot check whether MTR hands back the shapes the reader looks for -- unless an MTR jar is on the
classpath, which is what the handshake check is for: with `run/mods/MTR-*.jar` present, every class,
field and method the reader looks up is looked up for real, with no game running. That check is what
found that MTR 4.1 moved its own classes from `org.mtr.mod.*` to `org.mtr.*`, and the reader now tries
both spellings. Without a jar the check prints that it is skipping and nothing fails, so the harness
still runs on a machine that has never seen MTR.

The mod itself is compiled **without** MTR on its classpath, deliberately: the reader is reflective,
and compiling against a mod only some users have would be a dependency by another name. The MTR jar is
added only for the harness, after the mod has been compiled.

## Inspecting a real network

`NetworkInspector` runs the router over a network saved by the game, and is how the harness's
scenarios were checked against the network they were written for:

```powershell
# what the network is, and whether the router can cross it
java -cp "<classes>" bili.dongsz.howtogo.route.NetworkInspector run\config\howtogo\<world>\minecraft_overworld.json

# and then particular trips, as origin and goal coordinates
java -cp "<classes>" bili.dongsz.howtogo.route.NetworkInspector <same json> 50 -115 41 -88 50 -115 10 62
```

It reports the network's classes, its connected components before and after the repair, how many
probe pairs route, and for the trips it is given it says for each mode whether a route came back and
how far the start and the goal are from a usable road -- which is what a "no route" is usually about.
It is in the `route` package on purpose, so that it can reach the package-private repair and
workspace.

## Adding a case

`Harness.java` is plain Java with no test framework. Add a `scenarioX()` method, call it from
`main`, and build the network with `addRoad(net, RoadClass.ROAD, x0, z0, x1, z1, ...)` -- one polyline
per segment, sharing a node with anything already at either end, which is what the editor does when a
click lands on a node. Lines are `line(id, kind, stop(name, x, z), ...)`.
