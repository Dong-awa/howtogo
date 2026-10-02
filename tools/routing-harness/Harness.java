import bili.dongsz.howtogo.client.MtrClientData;
import bili.dongsz.howtogo.road.RoadClass;
import bili.dongsz.howtogo.road.RoadNetwork;
import bili.dongsz.howtogo.road.RoadNode;
import bili.dongsz.howtogo.road.RoadSegment;
import bili.dongsz.howtogo.route.RoadRouter;
import bili.dongsz.howtogo.route.Route;
import bili.dongsz.howtogo.route.RoutePreferences;
import bili.dongsz.howtogo.route.TransitPlanner;
import bili.dongsz.howtogo.route.TravelMode;
import bili.dongsz.howtogo.route.Trip;
import bili.dongsz.howtogo.transit.LineStop;
import bili.dongsz.howtogo.transit.TransitLine;

import java.util.ArrayList;
import java.util.List;

/**
 * Offline regression harness for the routing changes. Not part of the mod: it lives outside the
 * project and is compiled against build/classes plus the runtime classpath.
 */
public final class Harness {

    private static int failures;
    private static int checks;

    public static void main(String[] args) {
        scenarioCoincidentNodes();
        scenarioSameSegmentAnchors();
        scenarioBendVertexAnchor();
        scenarioDestinationIsTheStop();
        scenarioFallbackPicksItsOwnEndpoints();
        scenarioTJunctionOffByABlock();
        scenarioCrossingRoads();
        scenarioCrossingRailsCarryARide();
        scenarioBridgeIsNotAJunction();
        scenarioWalkReachesOffNetworkDestinations();
        scenarioJoinSurvivesAHeightDifference();
        scenarioTrackShapedNetworkIsQuick();
        scenarioLargeNetworkIsQuick();
        scenarioTransferBeatsDetour();
        scenarioConcatKeepsConnectors();
        scenarioMtrTypeMapping();
        int[] imported = bili.dongsz.howtogo.client.MtrImportCheck.run();
        checks += imported[0];
        failures += imported[1];
        System.out.println();
        if (failures > 0) {
            System.out.println("FAILED: " + failures + " of " + checks + " checks");
            System.exit(1);
        }
        System.out.println("ALL OK (" + checks + " checks)");
    }

    // ------------------------------------------------------------------ cases

    /** Two roads whose ends are one block apart, far from the origin: the join must exist. */
    private static void scenarioCoincidentNodes() {
        System.out.println("== coincident node join ==");
        RoadNetwork net = new RoadNetwork();
        addRoad(net, RoadClass.ROAD, 500, 500, 600, 500);
        addRoad(net, RoadClass.ROAD, 601, 500, 700, 500);

        Route route = RoadRouter.findRoute(net, 500, 500, 700, 500, "far", TravelMode.WALK,
                RoutePreferences.DEFAULTS);
        expect("a route is found across the one-block gap", route.isPresent());
        if (route.isPresent()) {
            expectNear("it is the two roads plus the join", route.totalLength(), 200, 10);
        }
    }

    /** Both ends on the middle of one long road: anchoring must split it, not fall back to nodes. */
    private static void scenarioSameSegmentAnchors() {
        System.out.println("== both ends on one segment ==");
        RoadNetwork net = new RoadNetwork();
        addRoad(net, RoadClass.ROAD, 0, 0, 1000, 0);

        Route route = RoadRouter.findRoute(net, 100, 0, 900, 0, "along", TravelMode.WALK,
                RoutePreferences.DEFAULTS);
        expect("a route is found", route.isPresent());
        if (route.isPresent()) {
            expectNear("it runs the 800 blocks between the two anchors", route.totalLength(), 800, 20);
            expectNear("and starts at the player, not at a road end", route.startConnector(), 0, 2);
        }
    }

    /** Standing exactly on a bend, which is a vertex but not a node: it needs one made for it. */
    private static void scenarioBendVertexAnchor() {
        System.out.println("== standing on a bend ==");
        RoadNetwork net = new RoadNetwork();
        addRoad(net, RoadClass.ROAD, 0, 0, 200, 0, 200, 200);

        Route route = RoadRouter.findRoute(net, 200, 0, 200, 50, "bend", TravelMode.WALK,
                RoutePreferences.DEFAULTS);
        expect("a route is found from the bend", route.isPresent());
        if (route.isPresent()) {
            expectNear("it is the 50 blocks up the second leg", route.totalLength(), 50, 10);
            expectNear("with no connector hop to the far end of the road", route.startConnector(), 0, 2);
        }
    }

    /**
     * A destination that is a stop on the line: the alighting walk is from the stop to itself.
     *
     * <p>A route is a polyline and needs two points, so a walk of zero length was not a route, the
     * stop could not be alighted at, and the journey was reported as impossible. Choosing a station as
     * the destination is the commonest public transport journey there is.
     */
    private static void scenarioDestinationIsTheStop() {
        System.out.println("== the destination is the stop ==");
        RoadNetwork net = new RoadNetwork();
        addRoad(net, RoadClass.ROAD, -10, 0, 10, 0);
        addRoad(net, RoadClass.ROAD, 390, 0, 410, 0);
        addRoad(net, RoadClass.RAIL, 0, 0, 400, 0);

        List<TransitLine> lines = new ArrayList<>();
        lines.add(line("line", RoadClass.RAIL, stop("West", 0, 0), stop("East", 400, 0)));

        // The goal is exactly the eastern stop, so the last leg has nothing to walk.
        Trip trip = TransitPlanner.plan(net, lines, 5, 0, 400, 0, "the station",
                RoutePreferences.DEFAULTS);
        expect("the journey is found", trip.isPresent());
        if (trip.isPresent()) {
            System.out.println("   ETA " + round(trip.estimatedSeconds()) + "s, "
                    + trip.legs().size() + " legs");
            expect("it walks in, rides, and walks nowhere (legs=" + trip.legs().size() + ")",
                    trip.legs().size() == 3);
            expectNear("and the walking to the stop is the only walking in it",
                    trip.estimatedSeconds(), 5 / 5.612 + 60 + 400 / 8.0, 2);
        }
    }

    /**
     * A goal whose nearest road is a fragment nothing routes to, while a node of the connected
     * fragment stands twenty-one blocks away.
     *
     * <p>Anchoring takes the nearest road, which is the fragment, so the anchored attempt fails and
     * the node fallback has to find the other end itself. It now does that in one multi-source search
     * rather than up to a hundred and forty-four separate ones, so this is here to keep it answering
     * the same thing: the trip leaves from the split node beside the player, runs the long road, and
     * takes the connector at the far end.
     *
     * <p>The stub stands well clear of the long road on purpose. Closer than the join distance it would
     * be repaired into a junction by the conflation pass, which is the right answer and not the one
     * being tested here.
     */
    private static void scenarioFallbackPicksItsOwnEndpoints() {
        System.out.println("== fallback picks its own endpoints ==");
        RoadNetwork net = new RoadNetwork();
        // The connected fragment: a road east then away north-east, with a node at its far end.
        addRoad(net, RoadClass.ROAD, 0, 0, 100, 0, 55, 215);
        // A one block stub beside the destination, belonging to nothing.
        addRoad(net, RoadClass.ROAD, 40, 200, 41, 200);

        Route route = RoadRouter.findRoute(net, 10, 0, 40, 200, "stub", TravelMode.WALK,
                RoutePreferences.DEFAULTS);
        expect("a route is found through the fallback", route.isPresent());
        if (route.isPresent()) {
            System.out.println("   " + round(route.totalLength()) + " blocks, connector in "
                    + round(route.startConnector()) + ", out " + round(route.goalConnector()));
            expectNear("the player's own node is the one it leaves from", route.startConnector(), 0, 2);
            expectNear("and the hop off the road at the far end is kept",
                    route.goalConnector(), 21.2, 3);
            expectNear("over the 90 + 219 block road", route.totalLength(), 330.9, 6);
        }
    }

    /**
     * A road drawn up to another and stopped a block short.
     *
     * <p>Nothing about clicking the map guarantees the two were drawn through the same node, and the
     * snap that would have is a few screen pixels -- a few blocks at map scale. Before, the router saw
     * two fragments and answered that the roads were not connected however plainly they met on screen.
     */
    private static void scenarioTJunctionOffByABlock() {
        System.out.println("== a T junction drawn a block short ==");
        RoadNetwork net = new RoadNetwork();
        addRoad(net, RoadClass.ROAD, 0, 0, 200, 0);
        addRoad(net, RoadClass.ROAD, 100, 1, 100, 200);

        Route route = RoadRouter.findRoute(net, 0, 0, 100, 200, "up the side road", TravelMode.WALK,
                RoutePreferences.DEFAULTS);
        expect("a route is found through the join", route.isPresent());
        if (route.isPresent()) {
            System.out.println("   " + round(route.totalLength()) + " blocks");
            expectNear("100 along the main road, a block across, 199 up the side road",
                    route.totalLength(), 300, 10);
        }
        Route drive = RoadRouter.findRoute(net, 0, 0, 100, 200, "up the side road", TravelMode.DRIVE,
                RoutePreferences.DEFAULTS);
        expect("and a drive finds it too", drive.isPresent());

        // The same, with the near miss repaired rather than merely linked: the side road's end is
        // within the join distance of the main road's interior, so the repair breaks the main road
        // there and the two become one junction.
        RoadNetwork offByOne = new RoadNetwork();
        addRoad(offByOne, RoadClass.ROAD, 0, 0, 200, 0);
        addRoad(offByOne, RoadClass.ROAD, 100, 2, 100, 200);
        Route repaired = RoadRouter.findRoute(offByOne, 0, 0, 100, 200, "up the side road",
                TravelMode.WALK, RoutePreferences.DEFAULTS);
        expect("a side road two blocks short still routes", repaired.isPresent());
    }

    /** Two roads drawn across each other, sharing no node: the crossing has to become one. */
    private static void scenarioCrossingRoads() {
        System.out.println("== two roads crossing ==");
        RoadNetwork net = new RoadNetwork();
        addRoad(net, RoadClass.ROAD, 0, 0, 200, 200);
        addRoad(net, RoadClass.ROAD, 200, 0, 0, 200);

        Route route = RoadRouter.findRoute(net, 0, 0, 200, 0, "the far corner", TravelMode.WALK,
                RoutePreferences.DEFAULTS);
        expect("a route is present across the crossing", route.isPresent());
        if (route.isPresent()) {
            System.out.println("   " + round(route.totalLength()) + " blocks");
            expectNear("141 blocks to the middle and 141 on", route.totalLength(), 283, 10);
        }
    }

    /** The same crossing, on rails, carrying a ride that could not be planned at all before. */
    private static void scenarioCrossingRailsCarryARide() {
        System.out.println("== two railways crossing ==");
        RoadNetwork net = new RoadNetwork();
        addRoad(net, RoadClass.ROAD, -10, 0, 10, 0);
        addRoad(net, RoadClass.ROAD, 190, 0, 210, 0);
        addRoad(net, RoadClass.RAIL, 0, 0, 200, 200);
        addRoad(net, RoadClass.RAIL, 200, 0, 0, 200);

        List<TransitLine> lines = new ArrayList<>();
        lines.add(line("cross", RoadClass.RAIL, stop("West", 0, 0), stop("East", 200, 0)));

        Trip trip = TransitPlanner.plan(net, lines, 5, 0, 205, 0, "the other side",
                RoutePreferences.DEFAULTS);
        expect("the ride is planned through the crossing", trip.isPresent());
        if (trip.isPresent()) {
            long rides = trip.legs().stream().filter(leg -> leg.mode() != TravelMode.WALK).count();
            expect("and it does ride (rides=" + rides + ")", rides >= 1);
            expectNear("5 blocks in, 283 of rail, 5 out", trip.totalLength(), 293, 6);
        }
    }

    /**
     * A road over another one, at two heights.
     *
     * <p>The network records the height each road was drawn at, and these two are a bridge and the road
     * under it rather than a junction. What must not happen is a route that turns from one onto the
     * other at the crossing -- a walker going over the bridge has not found a junction.
     *
     * <p>Walking there is a straight hop across the field, which is the honest answer now that walking
     * has no connector distance to speak of, so the assertion is on the shape rather than on whether a
     * route exists: through a junction the trip would be 100 blocks along one road and 50 along the
     * other, and as a hop it is the 112 blocks between the two points.
     */
    private static void scenarioBridgeIsNotAJunction() {
        System.out.println("== a bridge is not a junction ==");
        RoadNetwork net = new RoadNetwork();
        addRoadAt(net, RoadClass.ROAD, 64, 0, 0, 200, 0);
        addRoadAt(net, RoadClass.ROAD, 100, 100, -50, 100, 50);

        Route walk = RoadRouter.findRoute(net, 0, 0, 100, 50, "up on the bridge", TravelMode.WALK,
                RoutePreferences.DEFAULTS);
        expect("a walk is planned", walk.isPresent());
        if (walk.isPresent()) {
            System.out.println("   " + round(walk.totalLength()) + " blocks");
            expect("but it is the straight hop, not a way through the crossing",
                    walk.totalLength() < 130);
        }
        Route drive = RoadRouter.findRoute(net, 0, 0, 100, 50, "up on the bridge", TravelMode.DRIVE,
                RoutePreferences.DEFAULTS);
        expect("and a drive is refused: the two roads are at two heights", !drive.isPresent());
    }

    /**
     * A destination a long way from any road.
     *
     * <p>Walking has no connector distance to speak of, because a straight line across open country is
     * what a person does when there is no road -- the mode answering "no route" to a place plainly in
     * sight is the one answer that cannot be acted on. A drive is still refused, because there is no
     * road there to drive on.
     */
    private static void scenarioWalkReachesOffNetworkDestinations() {
        System.out.println("== a destination off the network ==");
        RoadNetwork net = new RoadNetwork();
        addRoad(net, RoadClass.ROAD, 0, 0, 200, 0);

        Route walk = RoadRouter.findRoute(net, 10, 0, 100, 300, "out in the field", TravelMode.WALK,
                RoutePreferences.DEFAULTS);
        expect("the walk is planned", walk.isPresent());
        if (walk.isPresent()) {
            System.out.println("   " + round(walk.totalLength()) + " blocks");
            expectNear("90 blocks of road and 300 across the field", walk.totalLength(), 390, 10);
        }
        Route drive = RoadRouter.findRoute(net, 10, 0, 100, 300, "out in the field",
                TravelMode.DRIVE, RoutePreferences.DEFAULTS);
        expect("and the drive is refused: there is no road out there", !drive.isPresent());
    }

    /**
     * Two roads whose ends nearly meet, but at different heights.
     *
     * <p>The heights in a hand-drawn network are whatever the ground was under each click, so two ends
     * a block or two apart across a slope are routinely several blocks apart vertically. Refusing to
     * join those disconnected networks that had been routing for as long as they existed, which is a
     * repair taking a route away -- the one thing it must never do.
     */
    private static void scenarioJoinSurvivesAHeightDifference() {
        System.out.println("== ends that nearly meet at different heights ==");
        RoadNetwork net = new RoadNetwork();
        addRoadAt(net, RoadClass.ROAD, 64, 0, 0, 200, 0);
        addRoadAt(net, RoadClass.ROAD, 72, 200, 3, 200, 200);

        Route route = RoadRouter.findRoute(net, 0, 0, 200, 200, "up the hill", TravelMode.WALK,
                RoutePreferences.DEFAULTS);
        expect("the two roads still join", route.isPresent());
        if (route.isPresent()) {
            System.out.println("   " + round(route.totalLength()) + " blocks");
            expectNear("200 along, 3 across, 197 up", route.totalLength(), 400, 10);
        }
    }

    /**
     * A railway read out of the world: long polylines with a vertex every block, several of them close
     * together.
     *
     * <p>This is the shape that made the crossing pass quadratic. Every edge of such a polyline shares
     * cells with thousands of its own neighbours, and each of those was a candidate pair to build,
     * hash and reject. A plan has to stay quick over it, because a plan is what a button press is.
     */
    private static void scenarioTrackShapedNetworkIsQuick() {
        System.out.println("== a track-shaped network ==");
        RoadNetwork net = new RoadNetwork();
        int lines = 8;
        int length = 600;
        int gap = 4;
        for (int line = 0; line < lines; line++) {
            int z = line * gap;
            int[] points = new int[(length + 1) * 2];
            for (int i = 0; i <= length; i++) {
                points[i * 2] = i;
                points[i * 2 + 1] = z;
            }
            addRoad(net, RoadClass.RAIL, points);
        }
        System.out.println("   " + net.nodeCount() + " nodes, " + net.segmentCount()
                + " segments, " + (lines * length) + " edges");

        long started = System.nanoTime();
        RoadRouter.findRoute(net, 5, 1, 595, 1, "along the rails", TravelMode.WALK,
                RoutePreferences.DEFAULTS);
        long millis = (System.nanoTime() - started) / 1_000_000;
        System.out.println("   a plan over it took " + millis + " ms");
        expect("a plan over a track-shaped network stays quick (under 1000 ms)", millis < 1000);
    }

    /**
     * A network of a few thousand segments, to see what repairing it and routing over it costs.
     *
     * <p>Not a benchmark but a guard: the repair is done once per plan, on the client thread behind a
     * button press, so anything quadratic in the network would be felt as a freeze. The bound is loose
     * on purpose -- what it is there to catch is a change of complexity, not a slow machine.
     */
    private static void scenarioLargeNetworkIsQuick() {
        System.out.println("== a large network ==");
        RoadNetwork net = new RoadNetwork();
        int side = 40;
        int spacing = 25;
        for (int i = 0; i < side; i++) {
            for (int j = 0; j < side; j++) {
                if (i + 1 < side) {
                    addRoad(net, RoadClass.ROAD, i * spacing, j * spacing,
                            (i + 1) * spacing, j * spacing);
                }
                if (j + 1 < side) {
                    addRoad(net, RoadClass.ROAD, i * spacing, j * spacing,
                            i * spacing, (j + 1) * spacing);
                }
            }
        }
        System.out.println("   " + net.nodeCount() + " nodes, " + net.segmentCount() + " segments");

        long started = System.nanoTime();
        Route route = RoadRouter.findRoute(net, 12, 12, 962, 962, "across", TravelMode.WALK,
                RoutePreferences.DEFAULTS);
        long firstMillis = (System.nanoTime() - started) / 1_000_000;

        // A second plan, so that what is being read is the repair rather than the class loading and
        // the first pass of the just-in-time compiler.
        long again = System.nanoTime();
        RoadRouter.findRoute(net, 12, 962, 962, 12, "back", TravelMode.WALK, RoutePreferences.DEFAULTS);
        long againMillis = (System.nanoTime() - again) / 1_000_000;

        expect("a route is found across it", route.isPresent());
        if (route.isPresent()) {
            System.out.println("   " + round(route.totalLength()) + " blocks; first plan "
                    + firstMillis + " ms, second " + againMillis + " ms");
            expectNear("down one side and along the other is 1900 blocks",
                    route.totalLength(), 1900, 60);
        }
        expect("a plan over it stays well under a fifth of a second (under 200 ms)",
                againMillis < 200);
        expect("and the first plan is not an order of magnitude worse", firstMillis < 2000);
    }

    /**
     * A single line that goes the long way round against two lines that change at a short walk.
     *
     * <p>Boarding and alighting are forced to one stop each by distance, so the only difference
     * between the two journeys is which line is taken: before, the single-line journey was returned
     * without a second search ever considering the change.
     */
    private static void scenarioTransferBeatsDetour() {
        System.out.println("== transfer against the long way round ==");
        RoadNetwork net = new RoadNetwork();

        // The walkable network: a patch at each end, and nothing in between, so the only way across
        // is by rail.
        addRoad(net, RoadClass.ROAD, -10, 0, 10, 0);
        addRoad(net, RoadClass.ROAD, 690, 0, 710, 0);

        // Rail: the straight line the two fast lines run on, and a long way round.
        addRoad(net, RoadClass.RAIL, 0, 0, 700, 0);
        addRoad(net, RoadClass.RAIL, 0, 0, 0, 900);
        addRoad(net, RoadClass.RAIL, 0, 900, 700, 900);
        addRoad(net, RoadClass.RAIL, 700, 900, 700, 0);

        List<TransitLine> lines = new ArrayList<>();
        // The long way round, as one line: 2500 blocks of rail.
        lines.add(line("detour", RoadClass.RAIL, stop("A", 0, 0), stop("C", 0, 900),
                stop("B", 700, 0)));
        // The two fast lines: 300 blocks, a twenty block change, 380 blocks.
        lines.add(line("fast1", RoadClass.RAIL, stop("A", 0, 0), stop("F1", 300, 0)));
        lines.add(line("fast2", RoadClass.RAIL, stop("F2", 320, 0), stop("B", 700, 0)));

        // Only A is within the walk radius of the player, and only B is within it of the goal, so
        // neither end can be helped by walking to a better stop or walking away from a worse one.
        Trip trip = TransitPlanner.plan(net, lines, 5, 0, 705, 0, "far end",
                RoutePreferences.DEFAULTS);
        expect("a journey is found", trip.isPresent());
        if (trip.isPresent()) {
            long rides = trip.legs().stream().filter(leg -> leg.mode() != TravelMode.WALK).count();
            expect("it is not the single-line long way round (rides=" + rides + ")", rides >= 2);
            System.out.println("   ETA " + round(trip.estimatedSeconds()) + "s, "
                    + round(trip.totalLength()) + " blocks, " + trip.legs().size() + " legs");
            expect("and it beats the 2500 block detour at 8 b/s",
                    trip.estimatedSeconds() < 2500 / 8.0);
        }
    }

    /** The flattened journey must time the same as its legs, connectors included. */
    private static void scenarioConcatKeepsConnectors() {
        System.out.println("== a journey's ETA counts its connectors ==");
        RoadNetwork net = new RoadNetwork();
        addRoad(net, RoadClass.ROAD, -10, 0, 10, 0);
        addRoad(net, RoadClass.ROAD, 390, 0, 410, 0);
        addRoad(net, RoadClass.RAIL, 0, 0, 400, 0);

        List<TransitLine> lines = new ArrayList<>();
        lines.add(line("line", RoadClass.RAIL, stop("West", 0, 0), stop("East", 400, 0)));

        // The destination is 200 blocks off the network, so the last leg is a long off-road hop that
        // used to vanish from the flattened route's time and length.
        Trip trip = TransitPlanner.plan(net, lines, 5, 0, 400, 200, "off grid",
                RoutePreferences.DEFAULTS);
        expect("a journey is found", trip.isPresent());
        if (trip.isPresent()) {
            Route flat = TransitPlanner.planRoute(net, lines, 5, 0, 400, 200, "off grid",
                    RoutePreferences.DEFAULTS);
            expect("the flattened route is present", flat.isPresent());
            System.out.println("   trip " + round(trip.estimatedSeconds()) + "s / "
                    + round(trip.totalLength()) + " blocks; route "
                    + round(flat.estimatedSeconds()) + "s / " + round(flat.totalLength()) + " blocks");
            expectNear("flattened time equals the legs' time",
                    flat.estimatedSeconds(), trip.estimatedSeconds(), 0.01);
            expectNear("flattened length equals the legs' length",
                    flat.totalLength(), trip.totalLength(), 0.01);
            expect("and the 200 block off-road hop is in it",
                    flat.estimatedSeconds() > 400 / 8.0 + 200 / 4.317 - 5);
            // 5 blocks of walk at 5.612, 400 of rail at 8, 200 off-road at the router's off-road pace
            // (walking pace times 0.7), and one wait at a boarding: the default sixty seconds, because
            // the harness runs with no config file. The off-road hop used to come from the walk
            // fallback at full walking pace instead of from the router at the off-road one, which is
            // the same 200 blocks estimated two different ways depending on which code path answered.
            expectNear("with one boarding's waiting on top of the travelling",
                    flat.estimatedSeconds(), 5 / 5.612 + 60 + 400 / 8.0 + 200 / (4.317 * 0.7), 2);
        }
    }

    // ----------------------------------------------------------------- helpers

    /**
     * The MTR integration, in a session with no MTR in it.
     *
     * <p>Reading another mod's internals reflectively is only defensible if the session without that
     * mod is untouched by it, so that is the first thing to check: nothing bound, nothing read, and no
     * exception on the way. The rest is the type mapping, which is a table and can be checked here
     * whatever is installed.
     */
    private static void scenarioMtrTypeMapping() {
        System.out.println("== MTR ==");
        expect("with no MTR installed it reports itself unavailable", !MtrClientData.available());
        expect("and a reading comes back empty rather than throwing", MtrClientData.read().isEmpty());

        expect("a train line becomes a rail line", MtrClientData.roadClassFor("TRAIN") == RoadClass.RAIL);
        expect("a cable car becomes a rail line",
                MtrClientData.roadClassFor("CABLE_CAR") == RoadClass.RAIL);
        expect("a boat becomes a water line", MtrClientData.roadClassFor("BOAT") == RoadClass.WATER);
        expect("an aeroplane becomes no line at all", MtrClientData.roadClassFor("AIRPLANE") == null);
        expect("a mode this mod has never heard of becomes no line either",
                MtrClientData.roadClassFor("SOMETHING_A_LATER_MTR_ADDS") == null);
        expect("and so does no mode at all", MtrClientData.roadClassFor(null) == null);
    }

    private static TransitLine line(String id, RoadClass kind, LineStop... stops) {
        TransitLine line = new TransitLine(id, id, kind);
        for (LineStop stop : stops) {
            line.addStop(stop);
        }
        return line;
    }

    private static LineStop stop(String name, int x, int z) {
        return LineStop.ofStation(name, x, z);
    }

    /**
     * Adds a polyline as one segment, sharing a node with whatever is already at either end.
     *
     * <p>This is what the editor does when a click lands on an existing node, and it is what makes
     * two of these meet in the graph rather than merely on the map.
     */
    private static void addRoad(RoadNetwork net, RoadClass roadClass, int... xz) {
        addRoadAt(net, roadClass, 64, xz);
    }

    /** The same, at a chosen height, for the cases where two roads are drawn over one another. */
    private static void addRoadAt(RoadNetwork net, RoadClass roadClass, int y, int... xz) {
        RoadSegment segment = net.newSegment(roadClass, y, xz.length / 2);
        for (int i = 0; i < xz.length; i += 2) {
            segment.addVertex(xz[i], xz[i + 1]);
        }
        segment.setFromNode(nodeAt(net, y, xz[0], xz[1]).id());
        segment.setToNode(nodeAt(net, y, xz[xz.length - 2], xz[xz.length - 1]).id());
        net.addSegment(segment);
    }

    private static RoadNode nodeAt(RoadNetwork net, int y, int x, int z) {
        RoadNode existing = net.nearestNode(x, z, 0.0);
        return existing != null ? existing : net.addNode(x, y, z, RoadNode.Type.JUNCTION, null);
    }

    private static void expect(String what, boolean condition) {
        checks++;
        if (!condition) {
            failures++;
        }
        System.out.println((condition ? "  ok   " : "  FAIL ") + what);
    }

    private static void expectNear(String what, double actual, double wanted, double tolerance) {
        boolean ok = Math.abs(actual - wanted) <= tolerance;
        checks++;
        if (!ok) {
            failures++;
        }
        System.out.println((ok ? "  ok   " : "  FAIL ") + what + " (" + round(actual) + " vs "
                + round(wanted) + " +-" + round(tolerance) + ")");
    }

    private static double round(double value) {
        return Math.round(value * 100) / 100.0;
    }
}
