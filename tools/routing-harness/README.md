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
| A T junction drawn a block short | A route, off the road, because a near miss used to be cut into a junction. It is not one: a drive is refused, a walk crosses the field to the side road instead of turning onto it, and the same shape with a node at the meeting point drives through |
| Two roads drawn across each other | A route through the crossing, because a crossing used to be cut into a junction. It is not one either -- a crossing and a bridge are the same drawing on a map with no height -- so a drive is refused and the walk is the straight line across the field |
| Two railways crossing, with a line calling either side | A ride, for the same reason. The honest answer is no journey; the same rails with a node at the crossing do carry the ride |
| A road crossing another at a different height | Must stay two roads: a walk over the bridge is a straight hop across the field, not a turn at the crossing, and a drive is refused outright |
| Two road ends a block apart on one storey, and the same ends with one of them a storey up | The first is one road a car drives along and the second is two roads, which is what a storey is for -- and two roads sharing a node are joined whatever storey either is on |
| A destination far from any road | Walked to -- 90 blocks of road and then 300 across the field -- while a drive is refused, because there is no road out there. Walking used to be capped at 64 blocks from the road, so this answered "no route" to a place plainly in sight |
| Two road ends a block apart but eight blocks apart vertically | Must still join: a hand-drawn network's heights are whatever the ground was under each click, and refusing those joins disconnected networks that had been routing for as long as they existed. This is a repair taking a route away, which is the one thing it must never do |
| A railway read out of the world: eight parallel polylines, a vertex every block | Was quadratic -- every edge of such a polyline shares cells with thousands of its own neighbours and each was a candidate pair to build and reject. A plan over 4800 such edges is now well under a fifth of a second |
| A 3120 segment network | Guards the cost of all of the above: the network is copied and repaired once per plan, on the client thread, so a plan must stay well under a fifth of a second |
| One line that goes 2500 blocks around against two lines that change at a 20 block walk | The 2500 block single-line journey: transfers were searched in a second pass that only ran when the first found nothing |
| A journey whose last leg is a 200 block off-road hop | Flattened route time and length short by that hop, because only the first part's start connector and the last part's goal connector were carried into the joined route |
| A turn at a fork, and the countdown read off the route at three points along it | Guards the arithmetic behind "in 200 metres" and "now": a turn's distance is measured from the route's own start and the countdown is that less what has been travelled, so a mistake shows as the wrong number rather than as a missing route |
| A trip with three turns and a bend after the second, driven to the destination | The turn already made shown again as the next instruction, at zero distance, and the turn actually being approached skipped in the same step -- the junction was remembered in one slot the walk over the route overwrote, because that walk restarts at the route's first junction every tick. 3932 of the 6856 guided trips over a saved network did it |

`RouteDirectionCheck.java` covers the reading the wrong-way call is built on: that a planned route never
reads as running **backwards** where it is drawn forwards. `Route.bearingAt` answers with the nearest
segment of the drawn line, and a line that passes over the same ground twice -- a road that loops back,
a divided highway whose carriageways are a lane apart, a destination on the piece of road the trip set
off along -- makes that answer ambiguous. The scan takes the earlier pass, and the direction it reports
is the one the player has already travelled, exactly reversed; the guidance then calls a U-turn at
somebody driving correctly. On the saved `sp_TEST` network that was 389 sampled positions over 13672
planned trips. The cases here are the shapes that produce it, driven end to end, and they hold the
fix: the nearest direction stands unless it says the player is going backwards, and another pass of
the route that the player is plainly following stands it down.

`HighwayBendCheck.java` covers the rule that a highway is never bent past a hundred degrees. A
highway is a road built to be driven along and it has no way to turn round on it -- the mod's own
guidance says so, where a highway U-turn is re-worded as carrying on to the next junction -- but that
was wording on top of routes that had already been allowed to plan the hairpin, so a player could be
sent up a highway and told to double back on it. The rule is now refused in the search: doubling back
onto the piece just travelled, or onto its partner between the same two nodes, is refused outright,
and anything else is held to the hundred-degree limit. The checks pin a right-angled highway bend and
a divided highway round its turning loop as still plannable, and sweep every pair of endpoints on each
network, reading the bend back off the route's own drawn line rather than out of the search that made
it.

`TurnCursorCheck.java` covers the other half of the guidance: **which** junction the readout is on.
`TurnCursor` is that state machine on its own, with no Minecraft in it, so it can be driven here.
Being level with a junction is not the same as having taken it, and only the heading says which the
player did; so the verdict is made once and remembered. What is remembered has to be a frontier
along the route -- "every junction up to here has been taken" -- rather than one junction, because
the walk over the route begins again at its first junction on every tick and would otherwise
re-decide one that had already been answered. One junction was the bug: an early one given up on
wrote its distance over the verdict on a later one the player had genuinely turned at, and the
instruction went back to the junction behind them, at zero distance, skipping the one they were
approaching. The check drives a route with three turns and a bend after the second, and asserts that
the junction shown never moves backwards and is never jumped over, that a junction driven past
without turning stays the instruction until the give-up distance, and that a re-planned route asks
about its junctions afresh.

The last two also assert that `TransitPlanner.planRoute`'s flattened route agrees with the sum of the
trip's own legs, and that one boarding's waiting is in the estimate -- the invariant that keeps the
HUD's number the same one the search chose the journey by.

The harness runs with no config file, so the config-backed values it depends on are the declared
defaults: sixty seconds of waiting per boarding, and falling back to walking when the chosen mode is
slower.

There is no repair to switch off any more. The router used to route over a copy of the network whose
junctions had been invented where the drawing left two roads crossed or a block short, and this file
used to describe the `repair_road_joins` setting that turned it off. What connects two roads is a node:
the pass could not tell a crossing from a bridge, or a near miss from a deliberate gap, and answered
both by cutting a junction the player had not drawn. A network that routes one way and not another for
no visible reason is now a question about its nodes, and `NetworkInspector` prints them.

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
to the next station), including an id whose high bit is set -- which is half of MTR's, and which used to
be read as "not ours" and thrown away, so that a line's switch did nothing and its track was never
marked at all -- that one line's answer marks its track and leaves another line's alone, and that a mode
which cannot reach a mark's class is not offered it.

And it covers **what a mark is**, through `MtrLineTracks` directly, because that is the part no view can
show: that a line's track is worked out rather than MTR's rails as a whole (MTR's data does not say which
rails belong to which line, so the ride between each pair of neighbouring stops is planned and its path
is what is found), that it follows the rails rather than joining the two stops with a chord, that its ends
are the two stops and the hops from a stop onto the track are not part of it, that a line whose stops are
nowhere near its rails gets no track rather than one joined up across open country, and that two lines
over one stretch of rail get tracks of their own with ids that cannot collide.

That split -- the track is worked out for **every** line, and the switch decides which tracks become
**roads** -- is what the checks hold: a line's track is there whether or not its marks are switched on,
because the map draws the line along it either way, while the road layer is empty with every switch off
and holds exactly the switched-on lines' tracks with them on.

And it covers the **interchange rule** the map draws its orange markers from, through
`TransitInterchanges`: that two lines calling a few blocks apart are one interchange (the platform and
the stop beside it are one place to travel through), that it is drawn once at the middle of the stops
that make it up rather than on each of them, that three lines at one place are still one marker, that
the radius is the planner's own and inclusive at its edge, that one line's own stops standing close are
*not* an interchange, and that with one of the two lines gone the place stops being one. That last pair
is what the rule is for: a marker that stayed orange after a line was cancelled, because the marker had
never been about two lines.

`checkKnown` covers the **memory** a session keeps of what MTR has said, which matters because MTR sends
a client only what is near it: a reading near a line is remembered with its track; a reading from far
away, with no lines in it at all, takes none of that away; walking along the line brings its newest stops
and adds the new stretch of track to the old; walking back over the same track does not remember it
twice; a line's switch filters the remembered track rather than the reading, so switching it off and back
on needs no fresh data; and switching the whole integration off forgets the railway.

`checkMapFilter` goes with it: what the map draws and leaves out is one question (`MapFilter.shows`),
answered from the player's own switches and from the map's zoom, so the panel and the shedding of detail
cannot disagree. The checks pin the order things go in as the map is zoomed out -- paths, then roads,
then waterways, then railways, with the highways and ice roads never shed; shops, then stations, then
landmarks, with resource points last -- and that the transit lines are never shed at all, while a kind
switched off by hand stays off at every scale.

`RideRoadsCheck.java` is in the `route` package for the same reason, and checks the seam the switch
rests on: a line whose marks are off is handed a network that never had them, because MTR's marks are
one layer and "do not add them for this line" is not something the planner could act on. It also pins
that a walk is handed the pair *without* them: a walk cannot use a rail either way, and the pair with
them is a copy of the whole railway to repair before a single walking leg can be answered.

`TrackRunsCheck.java` covers how a line's track becomes the polylines the map draws, which is the one
place a network of pieces can still come out as a straight line: a track known in several places is
drawn as several stretches and never across the places that are missing, a piece stored the other way
round is written the way the track runs, an arm meeting the last one end to end is still one stretch,
and a node two arms both *leave* from is a corner and not a join -- read as a join, the stretch is left
standing at the far end of the first arm and the two are drawn across each other, which on a real
railway is a handful of scratches hundreds or thousands of blocks long with no marked rail under them.
That last one is the newest: it needs a corner in the marks and a walk that happens to list the two arms
in the other order, and neither is visible from the outside -- what a player sees is a straight line
where the mod's own rail layer says there is nothing.

`PathThinningCheck.java` covers what a drawn line is put through so that a whole railway's worth of
them can be drawn at all. Every line read out of another mod is sampled along its own curve, so what the
map is asked to stroke grows with the railway and not with the window: the answer is that each stretch
is thinned to the zoom, and the checks are the two claims that makes -- that the ends of a path survive
(a drawn line that stopped short of its own end would not reach the places the line is known to run to)
and that nothing the thinning dropped is further from the line drawn instead than the tolerance, which
is a fraction of a pixel. Both are invisible when they are wrong, which is why they are held. The
tolerance and the banding of zoom it is kept under are checked here too: worked out from the wrong end
of a band, one band of zoom would be drawn up to twice as far off its track as the one below it, and a
line that changes shape as the map is zoomed is a line whose position cannot be read.

`scenarioWholeRailwayPlanIsQuick` is what holds the planner to a whole railway. Every other transit
scenario hands it a handful of lines, which is what MTR's own client data produces; a reading fetched
from the server is hundreds of lines and thousands of stops, and the planner's cost model was written
for the handful. Three things were quadratic or unconditional in the size of the railway and none of
them showed on five lines: the transfer pass walked every pair of stops, a ride was planned over every
line's track at once, and the Dijkstra ran to exhaustion so a journey two stops long settled every
station the network could reach. The scenario pins all three -- a journey across the network is found
and is quick, and a journey two stops long is planned for a fraction of what the crossing costs, which
is the property that was missing rather than a number.

It cannot check whether MTR hands back the shapes the reader looks for -- unless an MTR jar is on the
classpath, which is what the handshake check is for: with `run/mods/MTR-*.jar` present, every class,
field and method the reader looks up is looked up for real, with no game running. That check is what
found that MTR 4.1 moved its own classes from `org.mtr.mod.*` to `org.mtr.*`, and the reader now tries
both spellings. Without a jar the check prints that it is skipping and nothing fails, so the harness
still runs on a machine that has never seen MTR.

The same is done for **MTR Map Overlay** (`run/mods/*mtrmap*.jar`), whose client cache is the third
reading: the whole railway, fetched from the server, for a player on somebody else's server where
there is no local simulation to read. Its reader is checked the same way and skipped the same way.

### The three readings, and the ids they meet on

`checkThreeWayMerge` covers what a session with the overlay actually has: MTR's window, the railway
this process is simulating, and the whole railway the overlay fetched. Which of the three wins is
decided per kind and is invisible from outside -- a station kept from the wrong reading looks exactly
like a station. So the checks pin it: the fullest reading's stations, platforms and longest line;
the window's rails wherever the window has them, because only the window's copy carries a real height
and a transport mode (the overlay's geometry is flattened into X and Z, so keeping its copy would put
a mark at sea level and draw a boat line's rail as a train's); the overlay's rails wherever the window
has none, which is how a line the player has never been near gets drawn along its track at all; and a
rail that arrived with no readable id kept rather than dropped, since it cannot be matched to anything.

It also pins that the two-reading form -- every session without the overlay -- is unchanged, that an
empty window takes nothing away from readings that are not empty, and that a line whose *rails* only
one reading knows keeps them even when a different reading wins on stops. That last one is the whole
of what a line is drawn along, and losing it draws the line as straight hops between its stations.

`checkStatedTracks` covers the path that replaced planning a ride per pair of neighbouring stops: the
rails a reading names for a line, turned into the line's track. Three things about it decide whether a
railway comes out or a zig-zag, and none of them is visible from outside a running game -- a rail's
geometry runs from its own start to its own end and the order a route runs along its rails has nothing
to do with which way round that is, so a rail stored backwards has to come out forwards; a rail the
reading names and does not carry has to leave a gap rather than a straight line across it; and a run of
rails has to come out as one piece rather than one per rail.

`checkStatedWholeNetworkCost` is the counterpart of `checkWholeMapCost` and the reason the fetched
snapshot's rails are taken whole rather than bounded to a box around the player. Bounding them was
what left most of the railway without geometry, so a line the player had not walked to was drawn as
straight hops between its stations. It was bounded because a line's track used to mean planning a ride
per pair of its stops over every rail in hand; a reading that names the rails makes that a lookup. The
check is a few hundred lines over a few thousand rails, and it asserts that *every* line gets a track
-- not merely that the conversion is quick, because a fast conversion that drops the far half of the
railway is exactly the bug.

`checkOverlayIds` covers the join all of that rests on: MTR's own id, read back out of the string the
overlay carries it in. A mis-parse would not look like a bug, it would look like a railway with every
station listed twice -- and half of MTR's ids have the top bit set, so a signed read is the obvious
way to get it wrong. `depot:` ids, which name no route, and a string that is not hex at all are both
covered, because both are real answers the overlay produces.

`checkSharedWorkspace` covers the one router workspace every line of a reading shares. A workspace
copies the rail layer and repairs its joins, so one per line is the whole layer copied and repaired
once per line -- which a whole railway's worth of lines over a fetched snapshot's thousands of rails
cannot afford. What is checked is that sharing changes nothing: the same marks as a line marked on its
own, and a second line through the same workspace marked the same way, so the splits made for one line
cannot spoil the next.

The mod itself is compiled **without** MTR or the overlay on its classpath, deliberately: both readers
are reflective, and compiling against a mod only some users have would be a dependency by another name.
Those jars are added only for the harness, after the mod has been compiled.

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
