import bili.dongsz.howtogo.client.MtrClientData;
import bili.dongsz.howtogo.road.RoadClass;
import bili.dongsz.howtogo.road.RoadDirection;
import bili.dongsz.howtogo.road.RoadEditor;
import bili.dongsz.howtogo.road.RoadNetwork;
import bili.dongsz.howtogo.road.RoadNode;
import bili.dongsz.howtogo.road.RoadSegment;
import bili.dongsz.howtogo.route.RoadRouter;
import bili.dongsz.howtogo.route.Route;
import bili.dongsz.howtogo.route.RoutePreferences;
import bili.dongsz.howtogo.route.RideRoads;
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
        scenarioInterchangeIsOnePlace();
        scenarioWholeRailwayPlanIsQuick();
        scenarioOneWayIsRespected();
        scenarioOffRoadEndsStillRoute();
        scenarioBridgeEndsAreNotAJunction();
        scenarioHandDrawnGridStillRoutes();
        scenarioGridIsCrossedWithOneTurn();
        scenarioStoreysSeparateRoads();
        scenarioConcatKeepsConnectors();
        scenarioTurnIsCountedFromThePlayer();
        scenarioOneWayRoad();
        scenarioDeleteRemovesTheWholeRoad();
        scenarioDriveTimingWalksOnlyTheWalkedPart();
        scenarioMtrTypeMapping();
        int[] direction = RouteDirectionCheck.run();
        checks += direction[0];
        failures += direction[1];
        int[] bend = HighwayBendCheck.run();
        checks += bend[0];
        failures += bend[1];
        int[] preference = RoutePreferenceCheck.run();
        checks += preference[0];
        failures += preference[1];
        int[] imported = bili.dongsz.howtogo.client.MtrImportCheck.run();
        checks += imported[0];
        failures += imported[1];
        int[] rideRoads = bili.dongsz.howtogo.route.RideRoadsCheck.run();
        checks += rideRoads[0];
        failures += rideRoads[1];
        int[] connectivity = bili.dongsz.howtogo.route.LineConnectivityCheck.run();
        checks += connectivity[0];
        failures += connectivity[1];
        int[] guidance = bili.dongsz.howtogo.route.TransitGuidanceCheck.run();
        checks += guidance[0];
        failures += guidance[1];
        int[] oneWay = bili.dongsz.howtogo.road.OneWayCheck.run();
        checks += oneWay[0];
        failures += oneWay[1];
        int[] spatial = bili.dongsz.howtogo.road.SpatialIndexCheck.run();
        checks += spatial[0];
        failures += spatial[1];
        int[] snapping = bili.dongsz.howtogo.road.SnapCheck.run();
        checks += snapping[0];
        failures += snapping[1];
        int[] labels = bili.dongsz.howtogo.road.LabelCacheCheck.run();
        checks += labels[0];
        failures += labels[1];
        int[] trackRuns = bili.dongsz.howtogo.client.TrackRunsCheck.run();
        checks += trackRuns[0];
        failures += trackRuns[1];
        int[] thinning = bili.dongsz.howtogo.client.PathThinningCheck.run();
        checks += thinning[0];
        failures += thinning[1];
        int[] spoken = bili.dongsz.howtogo.client.NarrationCheck.run();
        checks += spoken[0];
        failures += spoken[1];
        int[] turns = bili.dongsz.howtogo.client.TurnCursorCheck.run();
        checks += turns[0];
        failures += turns[1];
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
     * The distance a turn is counted down from: a manoeuvre's distance is measured along the route from
     * its own start, and the countdown is that number less how far the player has come.
     *
     * <p>This is the arithmetic behind "in 200 metres" and "now", and it is the one place a mistake
     * shows as the wrong number rather than as a missing route -- a turn called "now" while the player is
     * still two hundred blocks short is this count coming out short. It is checked here because the two
     * halves of it live in different classes: the manoeuvre's distance is the route's, and the distance
     * already travelled is `total - remaining`, which the readout asks for separately.
     */
    /**
     * A fork with a real node at it, and the countdown read off the route at points along the way.
     *
     * <p>The fork is built as two roads sharing the node at (100, 0), which is what the editor makes
     * when a point is placed on a road. It used to be one road drawn straight through and a side road
     * ending on it, relying on the repair to cut the junction; nothing joins those now, so a fixture
     * for the countdown arithmetic has to be a joined network -- the arithmetic is what is under test
     * here, not the joining.
     */
    private static void scenarioTurnIsCountedFromThePlayer() {
        System.out.println("== the distance to a turn ==");
        RoadNetwork net = new RoadNetwork();
        // A road east, and a road north branching off it: the turn is a real fork, which is what makes it
        // a manoeuvre rather than a bend inside one road.
        addNamedRoad(net, "A", 0, 0, 100, 0);
        addNamedRoad(net, "A", 100, 0, 200, 0);
        addNamedRoad(net, "B", 100, 0, 100, 200);

        Route route = RoadRouter.findRoute(net, 10, 0, 100, 150, "north", TravelMode.DRIVE,
                RoutePreferences.DEFAULTS);
        expect("a route through the fork is planned", route.isPresent());
        if (!route.isPresent()) {
            return;
        }
        expect("and the turn onto the other road is a manoeuvre ("
                        + route.maneuvers().size() + ")", route.maneuvers().size() == 1);
        if (route.maneuvers().size() != 1) {
            return;
        }
        Route.Maneuver turn = route.maneuvers().get(0);
        expectNear("the turn stands where the two roads meet", turn.junctionX(), 100, 1);
        expectNear("and is the 90 blocks from where the player set off", turn.distanceFromStart(), 90, 3);

        // Standing at the start: nothing travelled, so the countdown is the whole 90 along the road and
        // the 150 up the other one, which is 240 blocks of route from end to end.
        expectNear("the route is the 240 blocks of road it uses",
                route.totalLength(), 240, 5);
        expectNear("with nothing travelled the turn is 240 blocks of route away",
                remainingFrom(route, 10, 0), 240, 5);
        expectNear("the distance already travelled is nothing", travelled(route, 10, 0), 0, 5);
        expectNear("and so the turn is called 90 blocks out",
                turn.distanceFromStart() - travelled(route, 10, 0), 90, 5);

        // Halfway along the first road: 40 travelled, 50 to go. This is the countdown a player reads.
        expectNear("halfway there, 40 blocks have been travelled", travelled(route, 50, 0), 40, 5);
        expectNear("and the turn is called 50 blocks out",
                turn.distanceFromStart() - travelled(route, 50, 0), 50, 5);

        // Standing at the fork itself: the countdown reaches zero and not before.
        expectNear("at the fork the turn is called now",
                turn.distanceFromStart() - travelled(route, 100, 0), 0, 3);

        // And a point on the road the player is on rather than off it: the projection is the nearest
        // point of the route, so a player a few blocks to the side counts from where they are beside it.
        expectNear("standing beside the road counts from the point beside it",
                travelled(route, 50, 4), 40, 6);
    }

    /** Blocks of a route already covered, as the readout computes it: total less what is left. */
    private static double travelled(Route route, double x, double z) {
        return Math.max(0, route.totalLength() - remainingFrom(route, x, z));
    }    /** Blocks left along a route from a position, as the readout computes it. */
    private static double remainingFrom(Route route, double x, double z) {
        return route.remainingLength(x, z);
    }

    /** A named road, so that a fork onto it counts as entering a different road. */
    private static void addNamedRoad(RoadNetwork net, String name, int... xz) {
        addRoadAt(net, RoadClass.ROAD, 64, xz);
        // The last segment added is this road's, and a fork is only worth announcing onto a road that has
        // a name of its own.
        RoadSegment last = null;
        for (RoadSegment segment : net.segments()) {
            last = segment;
        }
        if (last != null) {
            last.setName(name);
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

        // Drawn a block short is drawn not joined. There was a pass that read the near miss as a
        // junction and cut one into both roads; it was wrong about the drawing, because a near miss and
        // a deliberate gap look the same, and the answer to "did the player join these" is the node
        // table rather than a distance.
        Route drive = RoadRouter.findRoute(net, 0, 0, 100, 200, "up the side road", TravelMode.DRIVE,
                RoutePreferences.DEFAULTS);
        expect("a drive is refused: the two roads share no node", !drive.isPresent());

        // Walking is not refused, and that is the walking mode's own rule rather than a join: walking may
        // leave the road, so it crosses the field to the side road and walks up that. What it cannot do is
        // start on the main road and step across the block, because there is no junction there -- and the
        // drive above is where that shows.
        Route walk = RoadRouter.findRoute(net, 0, 0, 100, 200, "up the side road", TravelMode.WALK,
                RoutePreferences.DEFAULTS);
        expect("a walk is still planned, reaching the side road across the field", walk.isPresent());
        if (walk.isPresent()) {
            System.out.println("   walk " + round(walk.totalLength()) + " blocks, "
                    + round(walk.startConnector()) + " of it off the road");
            // It gets onto the side road by walking to it, not by turning off the main road a block
            // before the end of the road: the hop off the network is the whole ninety-nine blocks.
            expectNear("reaching the side road on foot, not turning onto it",
                    walk.startConnector(), 100, 3);
        }

        // The same shape with a node where the side road meets the main road, which is what the editor
        // makes when a point is placed on a road: one junction, and a drive through it.
        RoadNetwork joined = new RoadNetwork();
        addRoad(joined, RoadClass.ROAD, 0, 0, 100, 0);
        addRoad(joined, RoadClass.ROAD, 100, 0, 200, 0);
        addRoad(joined, RoadClass.ROAD, 100, 0, 100, 200);
        Route through = RoadRouter.findRoute(joined, 0, 0, 100, 200, "up the side road",
                TravelMode.DRIVE, RoutePreferences.DEFAULTS);
        expect("and with a node at the meeting point a drive goes through", through.isPresent());
        if (through.isPresent()) {
            expectNear("100 along the main road and 200 up the side road", through.totalLength(), 300, 5);
        }
    }

    /**
     * Two roads drawn across each other, sharing no node: two roads.
     *
     * <p>This used to be the case for a pass that cut a junction into the crossing, on the reading that
     * a player who draws two roads through each other means a crossroads. It does not: a crossing and a
     * bridge are the same drawing on a map that cannot show height, and the pass answered both. What
     * connects roads here is a node, so a crossing is two roads until somebody puts one there -- which
     * the layer feature now makes sayable, and which the editor does when a point is placed on a road.
     */
    private static void scenarioCrossingRoads() {
        System.out.println("== two roads crossing ==");
        RoadNetwork net = new RoadNetwork();
        addRoad(net, RoadClass.ROAD, 0, 0, 200, 200);
        addRoad(net, RoadClass.ROAD, 200, 0, 0, 200);

        Route drive = RoadRouter.findRoute(net, 0, 0, 200, 0, "the far corner", TravelMode.DRIVE,
                RoutePreferences.DEFAULTS);
        expect("a drive from one road to the other is refused", !drive.isPresent());

        // Walking is not refused, because walking may leave the road: what it gets is the straight line
        // across the field (200 blocks), not the 283-block route through the crossing that the pass used
        // to make available.
        Route walk = RoadRouter.findRoute(net, 0, 0, 200, 0, "the far corner", TravelMode.WALK,
                RoutePreferences.DEFAULTS);
        expect("a walk is planned as the straight line across the field", walk.isPresent());
        if (walk.isPresent()) {
            System.out.println("   " + round(walk.totalLength()) + " blocks, "
                    + round(walk.roadLength()) + " of them on a road");
            expectNear("straight across rather than through the crossing", walk.totalLength(), 200, 5);
            expectNear("and none of it on a road", walk.roadLength(), 0, 1);
        }

        // With a node at the crossing -- the two roads split there, sharing the node -- a car drives
        // through it, and that is the whole of what changed.
        RoadNetwork joined = new RoadNetwork();
        addRoad(joined, RoadClass.ROAD, 0, 0, 100, 100);
        addRoad(joined, RoadClass.ROAD, 100, 100, 200, 200);
        addRoad(joined, RoadClass.ROAD, 200, 0, 100, 100);
        addRoad(joined, RoadClass.ROAD, 100, 100, 0, 200);
        Route through = RoadRouter.findRoute(joined, 0, 0, 200, 0, "the far corner", TravelMode.DRIVE,
                RoutePreferences.DEFAULTS);
        expect("and with a node there a car turns at the crossing onto the other road",
                through.isPresent());
        if (through.isPresent()) {
            expectNear("141 blocks each way through the node", through.totalLength(), 283, 5);
        }
    }

    /**
     * Two railways drawn across each other, sharing no node: two railways, so no ride.
     *
     * <p>This used to carry a ride, because the crossing was cut into a junction. It is the same
     * drawing as a road crossing a rail at a level crossing, and the same drawing as one line bridging
     * another, and nothing but a node tells them apart -- so it is a journey that needs a node, and the
     * honest answer to this shape is that the lines cannot carry anyone.
     */
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
        expect("no journey: the line's two stops are on rails that do not meet", !trip.isPresent());

        // The same line over rails that do meet at a shared node, which is what carrying a ride needs.
        RoadNetwork joined = new RoadNetwork();
        addRoad(joined, RoadClass.ROAD, -10, 0, 10, 0);
        addRoad(joined, RoadClass.ROAD, 190, 0, 210, 0);
        addRoad(joined, RoadClass.RAIL, 0, 0, 100, 100);
        addRoad(joined, RoadClass.RAIL, 100, 100, 200, 200);
        addRoad(joined, RoadClass.RAIL, 200, 0, 100, 100);
        addRoad(joined, RoadClass.RAIL, 100, 100, 0, 200);
        Trip through = TransitPlanner.plan(joined, lines, 5, 0, 205, 0, "the other side",
                RoutePreferences.DEFAULTS);
        expect("and with a node at the crossing the ride is planned through it",
                through.isPresent());
        if (through.isPresent()) {
            long rides = through.legs().stream().filter(leg -> leg.mode() != TravelMode.WALK).count();
            expect("riding (rides=" + rides + ")", rides >= 1);
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
     * An interchange of three stops that is longer than the transfer radius end to end.
     *
     * <p>A platform, the stop beside it and the stop beside that are one place to walk through, which
     * is why the map draws one marker for them however far apart the two ends are. The planner used to
     * allow only the pairs directly within the radius, so a journey could change lines onto the middle
     * of such a place and then not onto the far end of it -- while the map went on drawing the whole
     * thing as a single place to change at.
     *
     * <p>Laid out so the far end can only be reached through the middle: A and C are 40 blocks apart
     * with the radius at 24, B sits between them, and each of the three is called at by a different
     * line. Nothing rides between them -- B's line has one stop and cannot carry anyone -- so the
     * journey exists only if the transfer is allowed from one end of the place to the other.
     */
    private static void scenarioInterchangeIsOnePlace() {
        System.out.println("== an interchange wider than the transfer radius ==");
        RoadNetwork net = new RoadNetwork();
        // The rails the two rideable lines run on, four hundred blocks and six hundred and sixty long,
        // so that walking the whole way is not the better answer and the journey really is a journey by
        // line. One down each side of the place the three stops make up.
        addRoad(net, RoadClass.RAIL, 0, 0, 0, -400);
        addRoad(net, RoadClass.RAIL, 40, 0, 700, 0);

        List<TransitLine> lines = new ArrayList<>();
        lines.add(line("one", RoadClass.RAIL, stop("A", 0, 0), stop("A2", 0, -400)));
        // One stop, so it can carry nobody: its whole part in this is to make the place between A and C
        // a place rather than two ends looking at each other from 40 blocks away.
        lines.add(line("two", RoadClass.RAIL, stop("B", 20, 0)));
        lines.add(line("three", RoadClass.RAIL, stop("C", 40, 0), stop("C2", 700, 0)));

        // Diagnostic: is the ride itself plannable? If not, the fixture is wrong and nothing here is
        // about interchanges at all.
        Route ride = RoadRouter.findRoute(net, 0, 0, 0, -400, "", TravelMode.TRANSIT,
                bili.dongsz.howtogo.route.LinePlanner.ridePreferences(RoadClass.RAIL,
                        RoutePreferences.DEFAULTS));
        expect("the ride from A to A2 is plannable", ride.isPresent());

        // In at A2, out at C2, and the only way between them is the change at the place A, B and C make
        // up. Both ends stand exactly on their stops, so no access or egress walk is involved and the
        // journey is about the change and nothing else.
        Trip trip = TransitPlanner.plan(net, lines, 0, -400, 700, 0, "far end",
                RoutePreferences.DEFAULTS);
        expect("the journey is found across an interchange whose two ends are 40 blocks apart",
                trip.isPresent());
        if (trip.isPresent()) {
            long rides = trip.legs().stream().filter(leg -> leg.mode() != TravelMode.WALK).count();
            // The change of lines in the middle, which is what "one place" means: a journey that walks
            // out of one stop of the place and into another has changed lines once, however many stops
            // of the place it walked between. The two ordinary transfers it takes to chain A to B to C
            // are still a journey -- the planner always allowed those -- but they are two changes, each
            // with its own wait for a service, which is not what one place to change at costs.
            long changes = 0;
            List<Trip.Leg> legs = trip.legs();
            for (int i = 1; i + 1 < legs.size(); i++) {
                if (legs.get(i).mode() == TravelMode.WALK) {
                    changes++;
                }
            }
            System.out.println("   " + legs.size() + " leg(s), " + rides + " ride(s), " + changes
                    + " change(s) of line");
            expect("and it rides both lines (rides=" + rides + ")", rides >= 2);
            expect("and changes lines once, at the place as one place (changes=" + changes + ")",
                    changes == 1);
        }
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

    /**
     * What a whole railway costs to plan over, which is what the planner is handed with a whole-network
     * reading installed.
     *
     * <p>Every other transit scenario here hands the planner a handful of lines, which is what MTR's
     * own client data can produce: the railway around the player. A reading fetched from the server is
     * the whole network -- hundreds of lines and thousands of stops -- and the planner's cost model was
     * written for the handful. This is that shape, and it is a guard rather than a demonstration: what
     * it catches is a step in the planning that is quadratic in the stops, which is invisible on five
     * lines and is most of a second on a real network.
     *
     * <p>The shape is a grid of stations with a railway along every row and every column of it, split
     * into overlapping lines so that they meet the way a real network's do: the journey has to change
     * lines to cross it, and the two families stop ten blocks apart where they meet, so a change is a
     * transfer the planner has to find rather than a free step at a shared stop.
     */
    private static void scenarioWholeRailwayPlanIsQuick() {
        System.out.println("== a whole railway's worth of lines ==");

        final int rows = 40;
        final int columns = 40;
        final int spacing = 200;
        final int lineLength = 12;

        RoadNetwork net = new RoadNetwork();
        List<TransitLine> lines = new ArrayList<>();
        // What each line runs along, which is what a whole-network reading can name and the mod keeps
        // per line. The planner is handed both, exactly as the client hands it both.
        java.util.Map<TransitLine, RoadNetwork> ownTracks = new java.util.IdentityHashMap<>();

        // A patch of walkable road at each end, so both ends of the journey can be reached on foot and
        // the plan is about the railway rather than about the walk. The same patches, and only those,
        // stand in for the world without the railway in it -- which is the network the walking legs are
        // planned on, and the one the client hands the planner for them.
        RoadNetwork roads = new RoadNetwork();
        addRoad(roads, RoadClass.ROAD, -20, 0, 20, 0);
        addRoad(roads, RoadClass.ROAD, (columns - 1) * spacing - 20, (rows - 1) * spacing,
                (columns - 1) * spacing + 20, (rows - 1) * spacing);
        addRoad(net, RoadClass.ROAD, -20, 0, 20, 0);
        addRoad(net, RoadClass.ROAD, (columns - 1) * spacing - 20, (rows - 1) * spacing,
                (columns - 1) * spacing + 20, (rows - 1) * spacing);

        for (int row = 0; row < rows; row++) {
            for (int first = 0; first + lineLength <= columns; first += 7) {
                lines.add(railway(net, ownTracks, "R" + row + "_" + first, first, row, 1, 0,
                        lineLength, spacing, 0, 0));
            }
        }
        for (int column = 0; column < columns; column++) {
            for (int first = 0; first + lineLength <= rows; first += 7) {
                lines.add(railway(net, ownTracks, "C" + column + "_" + first, column, first, 0, 1,
                        lineLength, spacing, 10, 10));
            }
        }

        RideRoads rideRoads = RideRoads.of(net, roads, ignored -> true, ownTracks::get);

        // A journey the other side of the whole network, and one a few stops away. The second is the one
        // that matters: a plan over a whole railway used to cost the same wherever the player was going,
        // because the graph was every ride of every line however short the journey -- which is what "it
        // is slow even though the distance is small" was.
        long startedAt = System.nanoTime();
        Trip trip = TransitPlanner.plan(rideRoads, lines, 0, 0,
                (columns - 1) * spacing, (rows - 1) * spacing, "far corner",
                RoutePreferences.DEFAULTS);
        long millis = Math.round((System.nanoTime() - startedAt) / 1_000_000.0);

        long shortStart = System.nanoTime();
        Trip shortHop = TransitPlanner.plan(rideRoads, lines, 0, 0, 2 * spacing, 0, "two stops on",
                RoutePreferences.DEFAULTS);
        long shortMillis = Math.round((System.nanoTime() - shortStart) / 1_000_000.0);

        System.out.println("   " + lines.size() + " lines, " + (rows * columns) + " stations: across it "
                + millis + " ms, two stops " + shortMillis + " ms");
        expect("a journey across a whole railway is found", trip.isPresent());
        expect("and planning over one stays quick (under 1000 ms)", millis < 1000);
        expect("a journey two stops long is found too", shortHop.isPresent());
        expect("and it does not cost what a journey across the network costs (under 250 ms)",
                shortMillis < 250);

        // And a second journey, which is what the player actually makes. One plan's worth of line
        // tracks used to be copied and join-repaired all over again on the next plan -- the workspaces
        // that held them belonged to the plan -- so a journey over a whole railway cost the same every
        // time it was asked for, and asking twice is what the destination picker does: the mode buttons
        // re-plan on every press. What holds this is that the copy is kept against the line's own track
        // rather than against the plan; see RoadRouter.WorkspaceCache.
        long repeatStart = System.nanoTime();
        Trip repeat = TransitPlanner.plan(rideRoads, lines, 0, 0, 2 * spacing, 0, "two stops on",
                RoutePreferences.DEFAULTS);
        long repeatMillis = Math.round((System.nanoTime() - repeatStart) / 1_000_000.0);
        System.out.println("   the same short journey planned again: " + repeatMillis + " ms");
        expect("planning the same journey again still finds it", repeat.isPresent());
        expect("and it does not re-copy and re-repair every line's track (under 250 ms)",
                repeatMillis < 250);
    }

    /**
     * A one-way street has to be a one-way street for the router, not merely on the map.
     *
     * <p>Driven rather than walked, because that is where a one-way street means something and because
     * walking's connector distance is deliberately so large as to be no cap at all: a walker may always
     * be sent straight across the field, so a one-way street cannot be said to have stopped them. The
     * same is true of a vehicle, and it is the point of {@link TravelMode#maxConnectorDistance()} -- a
     * car does not start its trip across a field, so the node fallback must not answer a one-way street
     * with a straight line past it.
     *
     * <p>The direction is a property of the segment rather than of the network's geometry, and
     * {@code RoadNetwork} says so: its revision is deliberately not bumped by a one-way flag, because
     * nothing has moved. Anything the router caches off that revision is therefore not a reading of the
     * directions unless it is told that a direction is one of the things that can change; see
     * {@code RoadRouter.WorkspaceCache}.
     */
    private static void scenarioOneWayIsRespected() {
        System.out.println("== one-way is respected ==");
        RoadNetwork net = new RoadNetwork();
        RoadSegment west = road(net, RoadClass.ROAD, 0, 0, 400, 0);
        road(net, RoadClass.ROAD, 400, 0, 600, 0);

        Route eastwards = RoadRouter.findRoute(net, 10, 0, 590, 0, "east", TravelMode.DRIVE,
                RoutePreferences.DEFAULTS);
        expect("a two-way street can be driven both ways", eastwards.isPresent());

        west.setDirection(RoadDirection.FORWARD);
        expect("the street is one-way now",
                west.allowsTravelFrom(west.fromNode()) && !west.allowsTravelFrom(west.toNode()));

        Route stillEast = RoadRouter.findRoute(net, 10, 0, 590, 0, "east", TravelMode.DRIVE,
                RoutePreferences.DEFAULTS);
        expect("and it still carries travel the way it points", stillEast.isPresent());

        Route against = RoadRouter.findRoute(net, 590, 0, 10, 0, "west", TravelMode.DRIVE,
                RoutePreferences.DEFAULTS);
        expect("but not against it, when the one-way is the only way through", !against.isPresent());
    }

    /**
     * The cap the fallback now respects must not become a refusal of ordinary off-road ends.
     *
     * <p>A trip does not begin or end on a road: both ends are a short walk, and a destination beside
     * the road with nothing drawn up to it is the commonest kind there is. This is the other side of the
     * rule above -- the fallback's endpoints have to be within the mode's connector distance, and a
     * destination that is within it still has to be reachable.
     */
    private static void scenarioOffRoadEndsStillRoute() {
        System.out.println("== a destination off the road ==");
        RoadNetwork net = new RoadNetwork();
        road(net, RoadClass.ROAD, 0, 0, 400, 0);

        // Thirty blocks off the middle of the road, which is well inside the sixty-four a drive allows
        // to reach a road and far outside the three a junction is joined at.
        Route close = RoadRouter.findRoute(net, 200, 30, 350, -40, "off road", TravelMode.DRIVE,
                RoutePreferences.DEFAULTS);
        expect("a destination a short hop off the road is still reachable", close.isPresent());
        if (close.isPresent()) {
            expectNear("and the trip ends where it was asked to",
                    Math.hypot(close.points().get(close.points().size() - 1)[0] - 350,
                            close.points().get(close.points().size() - 1)[1] + 40), 0, 0.5);
        }
    }

    /**
     * Deleting a road deletes the road, not one straight piece of it.
     *
     * <p>A road drawn with bends is stored as several segments meeting at pass-through nodes, and
     * selecting it selects the whole chain: {@code selectSegment} takes the chain, the map highlights
     * the chain, and naming, re-classing, the storey and the one-way marking all walk it. Deletion was
     * the one operation that acted on the single piece the cursor happened to be over, so pressing
     * delete left the rest of a road the player was still being shown as selected, and a street with
     * two bends had to be deleted a bend at a time.
     */
    private static void scenarioDeleteRemovesTheWholeRoad() {
        System.out.println("== deleting a road ==");
        RoadNetwork net = new RoadNetwork();
        // One street drawn a click at a time: three segments meeting at pass-through nodes with both
        // ends free, which is what makes the three of them one road.
        road(net, RoadClass.ROAD, 0, 0, 100, 0);
        RoadSegment middle = road(net, RoadClass.ROAD, 100, 0, 100, 100);
        road(net, RoadClass.ROAD, 100, 100, 200, 100);
        // And another road well away from it, which deleting the street must not touch.
        road(net, RoadClass.ROAD, 500, 500, 600, 500);
        expect("the fixture is a street of three pieces and a road of one", net.segmentCount() == 4);

        RoadEditor editor = new RoadEditor(net);
        editor.selectSegment(middle.id());
        expect("selecting a piece of a street selects the street ("
                + editor.selectedChain().size() + " pieces)", editor.selectedChain().size() == 3);

        expect("delete removes the whole street",
                editor.deleteSelection() && net.segmentCount() == 1);
        expect("leaving the road that was not selected", net.segmentCount() == 1
                && net.segmentsSnapshot().get(0).midpoint()[0] > 500);
        // The street's corners were its own: a node nothing is left attached to is not a place.
        expect("and the nodes the street was made of go with it", net.nodeCount() == 2);

        editor.undo();
        expect("undo puts the whole street back", net.segmentCount() == 4 && net.nodeCount() == 6);
    }

    /**
     * A drive to a destination the roads do not reach is timed as a drive plus the walk.
     *
     * <p>When no road a car may use comes within its connector distance of the destination, the only
     * plan there can be is the walker's -- the walker is the one who leaves the network -- and the
     * trip the player then makes along that line is the car's up to the last road and the walker's
     * from there. Timed at walking pace throughout it reported a destination with a short walk at the
     * end as a much longer trip than it is, with every block of road the car would have driven costed
     * as though it were walked.
     */
    private static void scenarioDriveTimingWalksOnlyTheWalkedPart() {
        System.out.println("== a drive that ends in a walk ==");
        RoadNetwork net = new RoadNetwork();
        // Two kilometres of road with the destination a hundred blocks off its far end: far past the
        // sixty-four a drive will walk to a road, and well inside the walker's.
        road(net, RoadClass.ROAD, 0, 0, 2000, 0);

        Route drive = RoadRouter.findRoute(net, 0, 0, 2000, 100, "off the road",
                TravelMode.DRIVE, RoutePreferences.DEFAULTS);
        expect("the drive is refused: no road the car may use reaches it", !drive.isPresent());

        Route walk = RoadRouter.findRoute(net, 0, 0, 2000, 100, "off the road",
                TravelMode.WALK, RoutePreferences.DEFAULTS);
        expect("the walk is planned", walk.isPresent());
        if (!walk.isPresent()) {
            return;
        }

        Route asDrive = walk.timedFor(TravelMode.DRIVE);
        // The walk's own time less its road is the hop off the road, which stays walked whatever the
        // mode: the check reads it off the planned route rather than restating the connector pace.
        double hopSeconds = walk.estimatedSeconds()
                - 2000.0 / TravelMode.WALK.speedOn(RoadClass.ROAD);
        expectNear("the drive's time is the road driven and the hop walked",
                asDrive.estimatedSeconds(),
                2000.0 / TravelMode.DRIVE.speedOn(RoadClass.ROAD) + hopSeconds, 0.5);
        expect("and the road is no longer costed as if it were walked",
                asDrive.estimatedSeconds() < walk.estimatedSeconds() - 60);
        expectNear("the line itself is unchanged",
                asDrive.totalLength(), walk.totalLength(), 0.01);
        // The countdown is the same arithmetic read from the other end, so the two have to agree.
        expectNear("the whole trip is ahead at the start",
                asDrive.remainingSeconds(0, 0), asDrive.estimatedSeconds(), 0.5);
        expectNear("and nothing is left at the destination",
                asDrive.remainingSeconds(2000, 100), 0, 0.5);

        // A drive that does reach the destination is that mode's own route already: re-timing it for
        // the drive must leave it exactly as it is rather than rebuild it.
        Route beside = RoadRouter.findRoute(net, 0, 0, 1000, 30, "beside the road",
                TravelMode.DRIVE, RoutePreferences.DEFAULTS);
        expect("a drive that does reach the destination is left as it is",
                beside.isPresent() && beside.timedFor(TravelMode.DRIVE) == beside);
    }

    /**
     * A road and a bridge over it, whose ends are two blocks apart and thirty-six apart vertically.
     *
     * <p>What must not happen is a route that turns from one onto the other: they share no node, and a
     * bridge is not a junction. The height is what says so, and the coincident-node pass that joins two
     * nodes within three blocks had no height check at all -- so a road ramping up to a bridge and the
     * bridge above it were one place, and a drive went up the ramp and over the bridge.
     *
     * <p>Walking cannot tell the difference, because a walker may cross the field and the hop is the
     * same length either way; driving is where a junction would show, so a drive is what is asserted.
     */
    private static void scenarioBridgeEndsAreNotAJunction() {
        System.out.println("== a road ramp and the bridge above it ==");
        RoadNetwork net = new RoadNetwork();
        // The road on the ground, and a bridge two blocks past its end and thirty-six above it.
        addRoadAt(net, RoadClass.ROAD, 64, 0, 0, 100, 0);
        addRoadAt(net, RoadClass.ROAD, 100, 100, 2, 100, 200);

        Route drive = RoadRouter.findRoute(net, 10, 0, 100, 200, "onto the bridge",
                TravelMode.DRIVE, RoutePreferences.DEFAULTS);
        expect("a drive is not sent from the road up onto the bridge", !drive.isPresent());

        // And the same shape as a hill, which is a road: the ramp rises eight blocks over three blocks
        // of ground between the two ends, and a hand-drawn network is full of those.
        RoadNetwork hill = new RoadNetwork();
        addRoadAt(hill, RoadClass.ROAD, 64, 0, 0, 200, 0);
        addRoadAt(hill, RoadClass.ROAD, 72, 200, 3, 200, 200);
        Route up = RoadRouter.findRoute(hill, 10, 0, 200, 200, "up the hill", TravelMode.DRIVE,
                RoutePreferences.DEFAULTS);
        expect("but a road up a hill still joins", up.isPresent());
    }

    /**
     * A hand-drawn grid, where every junction is a join the repair has to find.
     *
     * <p>The height of a join is the thing the coincident-node pass is now allowed to refuse, so this is
     * the guard on the other side of that rule: a rule that refused too much would show up here as a
     * network that cannot be crossed, and the suite's own slope case is too narrow a sample to notice
     * it. Each piece stops a block short of the junction it aims at and the drawn heights wander, so
     * every join is both a short horizontal gap and a small rise -- exactly what a player's map is made
     * of.
     */
    private static void scenarioHandDrawnGridStillRoutes() {
        System.out.println("== a hand-drawn grid's junctions ==");
        for (int wander = 0; wander <= 3; wander++) {
            RoadNetwork net = new RoadNetwork();
            for (int row = 0; row < 5; row++) {
                for (int col = 0; col < 5; col++) {
                    int x = col * 100;
                    int z = row * 100;
                    int y = 64 + ((row + col) % (wander + 1));
                    if (col < 4) {
                        addRoadAt(net, RoadClass.ROAD, y, x, z, x + 99, z);
                    }
                    if (row < 4) {
                        addRoadAt(net, RoadClass.ROAD, y, x, z, x, z + 99);
                    }
                }
            }
            int reached = 0;
            for (int x = 0; x < 5; x++) {
                for (int z = 0; z < 5; z++) {
                    if (x == 3 && z == 3) {
                        continue;
                    }
                    Route route = RoadRouter.findRoute(net, x * 100, z * 100, 300, 300, "the middle",
                            TravelMode.WALK, RoutePreferences.DEFAULTS);
                    if (route.isPresent()) {
                        reached++;
                    }
                }
            }
            expect("with the heights wandering by " + wander + ", all 24 corners reach the middle ("
                    + reached + ")", reached == 24);
        }
    }

    /**
     * A rectangle of streets crossed corner to corner: one turn, not a staircase.
     *
     * <p>Every monotone path across a grid of streets is the same length, so the cost cannot choose
     * between them, and the search used to return whichever the frontier happened to reach first --
     * a staircase, which the panel then reads out as turn left, turn right, turn left, turn right,
     * for the whole trip. Equal cost is settled by the fewest turns now, so the answer is the
     * two-street route a driver would take. The length is asserted with it, because tidiness must not
     * be bought with distance: a route that is genuinely longer is still not chosen.
     */
    private static void scenarioGridIsCrossedWithOneTurn() {
        System.out.println("== a grid crossed corner to corner ==");
        RoadNetwork net = new RoadNetwork();
        for (int row = 0; row <= 6; row++) {
            for (int col = 0; col <= 6; col++) {
                int x = col * 100;
                int z = row * 100;
                if (col < 6) {
                    addRoadAt(net, RoadClass.ROAD, 64, x, z, x + 100, z);
                }
                if (row < 6) {
                    addRoadAt(net, RoadClass.ROAD, 64, x, z, x, z + 100);
                }
            }
        }
        Route route = RoadRouter.findRoute(net, 0, 0, 600, 600, "the far corner", TravelMode.WALK,
                RoutePreferences.DEFAULTS);
        expect("the far corner of the grid is reached", route.isPresent());
        if (!route.isPresent()) {
            return;
        }
        expectNear("along the shortest line through it", route.totalLength(), 1200, 20);
        // Counted off the drawn line rather than off the turn prompts: a bend inside one road is not
        // announced but it is still a bend the player drives, and a staircase is nothing but bends.
        // The two-street route across a square grid has exactly one.
        int bends = bends(route);
        expect("with one bend rather than a staircase (" + bends + " bend(s))", bends <= 1);

        // The same crossing read as a drive, which is the mode a staircase would annoy most: the
        // tie-break is about shape, so it has to hold for every mode and not just for walking.
        Route driven = RoadRouter.findRoute(net, 0, 0, 600, 600, "the far corner", TravelMode.DRIVE,
                RoutePreferences.DEFAULTS);
        expect("and the same by car", driven.isPresent() && bends(driven) <= 1);
    }

    /** How many times the drawn line changes direction by a turn's worth, in degrees. */
    private static int bends(Route route) {
        List<double[]> points = route.points();
        int bends = 0;
        for (int i = 2; i < points.size(); i++) {
            double[] a = points.get(i - 2);
            double[] b = points.get(i - 1);
            double[] c = points.get(i);
            double inX = b[0] - a[0];
            double inZ = b[1] - a[1];
            double outX = c[0] - b[0];
            double outZ = c[1] - b[1];
            if (Math.hypot(inX, inZ) < 1.0E-6 || Math.hypot(outX, outZ) < 1.0E-6) {
                continue;
            }
            double delta = Math.toDegrees(Math.atan2(inX * outZ - inZ * outX, inX * outX + inZ * outZ));
            if (Math.abs(delta) >= 25.0) {
                bends++;
            }
        }
        return bends;
    }

    /**
     * A bridge over a road, said with storeys rather than with heights.
     *
     * <p>The reason storeys exist. Heights come from the ground under each click, so they cannot say
     * which of two roads is above the other, and two road ends a block apart at the same nominal height
     * were one place whether they were a join or a bridge. A storey is the player's own statement, so
     * the near-coincident pass can ask it: two ends on one storey are one junction, and the same two
     * ends with one of them on another storey are two roads -- which is what a bridge over a road is.
     *
     * <p>And what a storey cannot do is take a junction away: two roads sharing a node are joined
     * whatever storey either is on, because that is what building the junction means.
     */
    private static void scenarioStoreysSeparateRoads() {
        System.out.println("== a bridge over a road, by storey ==");

        // Two ends a block apart on one storey: one junction, and a drive through it.
        RoadNetwork level = new RoadNetwork();
        road(level, RoadClass.ROAD, 0, 0, 100, 0);
        road(level, RoadClass.ROAD, 101, 0, 200, 0);
        Route through = RoadRouter.findRoute(level, 0, 0, 200, 0, "along the road",
                TravelMode.DRIVE, RoutePreferences.DEFAULTS);
        expect("two road ends a block apart on one storey are one road a car drives along",
                through.isPresent());

        // The same two ends, one of them moved to the storey above: a bridge, not a junction.
        RoadNetwork stacked = new RoadNetwork();
        road(stacked, RoadClass.ROAD, 0, 0, 100, 0);
        RoadSegment over = road(stacked, RoadClass.ROAD, 101, 0, 200, 0);
        over.setLayer(1);
        Route up = RoadRouter.findRoute(stacked, 0, 0, 200, 0, "up on the bridge", TravelMode.DRIVE,
                RoutePreferences.DEFAULTS);
        expect("the same two ends with one of them a storey up are two roads", !up.isPresent());

        // Sharing a node: the storey cannot take a junction away.
        RoadNetwork jogged = new RoadNetwork();
        road(jogged, RoadClass.ROAD, 0, 0, 100, 0);
        RoadSegment ramp = road(jogged, RoadClass.ROAD, 100, 0, 200, 0);
        ramp.setLayer(2);
        Route overRamp = RoadRouter.findRoute(jogged, 0, 0, 200, 0, "up the ramp", TravelMode.DRIVE,
                RoutePreferences.DEFAULTS);
        expect("two roads sharing a node are joined whatever storey each is on",
                overRamp.isPresent());
    }

    /** One two-node road, sharing a node with whatever is already at either end. */
    private static RoadSegment road(RoadNetwork net, RoadClass roadClass, int x1, int z1, int x2,
                                    int z2) {        RoadSegment segment = net.newSegment(roadClass, 64, 2);
        segment.addVertex(x1, z1);
        segment.addVertex(x2, z2);
        segment.setFromNode(nodeAt(net, 64, x1, z1).id());
        segment.setToNode(nodeAt(net, 64, x2, z2).id());
        net.addSegment(segment);
        return segment;
    }

    /**
     * One line of the grid: its stops along a row or a column, its own track, and the same track merged
     * into the shared layer the world gives the planner.
     *
     * @param fromX     where the line starts, in grid cells
     * @param fromZ     the same, for the other axis
     * @param stepX     which way it runs, in grid cells per stop
     * @param stepZ     the same, for the other axis
     * @param offsetX   how far its stops and its track sit from the grid line, which is what makes the
     *                  two families that cross at a station two platforms rather than one node
     */
    private static TransitLine railway(RoadNetwork net, java.util.Map<TransitLine, RoadNetwork> ownTracks,
                                       String id, int fromX, int fromZ, int stepX, int stepZ,
                                       int length, int spacing, int offsetX, int offsetZ) {
        List<LineStop> stops = new ArrayList<>(length);
        for (int i = 0; i < length; i++) {
            stops.add(stop(id + "_" + i,
                    (fromX + i * stepX) * spacing + offsetX,
                    (fromZ + i * stepZ) * spacing + offsetZ));
        }
        TransitLine built = line(id, RoadClass.RAIL, stops.toArray(new LineStop[0]));

        int firstX = fromX * spacing + offsetX;
        int firstZ = fromZ * spacing + offsetZ;
        int lastX = (fromX + (length - 1) * stepX) * spacing + offsetX;
        int lastZ = (fromZ + (length - 1) * stepZ) * spacing + offsetZ;
        RoadNetwork track = new RoadNetwork();
        addRoad(track, RoadClass.RAIL, firstX, firstZ, lastX, lastZ);
        ownTracks.put(built, track);
        addRoad(net, RoadClass.RAIL, firstX, firstZ, lastX, lastZ);
        return built;
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
     * A one-way street with a bypass around it, so that "the router would not go that way" is a question
     * about direction rather than about whether the two ends are connected at all.
     *
     * <p>The bypass is what makes this check able to fail: without it, refusing the one-way direction
     * would leave no route and a router that ignored the direction entirely would look identical to one
     * that obeyed it, since both would answer with the same straight street.
     */
    private static void scenarioOneWayRoad() {
        System.out.println("== one-way street ==");

        RoadNetwork open = oneWayNetwork(RoadDirection.TWO_WAY);
        expectNear("two-way: the route east is the 400 block street",
                walkLength(open, 0, 0, 400, 0), 400, 15);
        expectNear("two-way: and so is the route west", walkLength(open, 400, 0, 0, 0), 400, 15);

        RoadNetwork forward = oneWayNetwork(RoadDirection.FORWARD);
        expectNear("one-way east: the route east still uses the street",
                walkLength(forward, 0, 0, 400, 0), 400, 15);
        expectNear("one-way east: the route west goes round the bypass instead",
                walkLength(forward, 400, 0, 0, 0), 520, 30);

        RoadNetwork backward = oneWayNetwork(RoadDirection.BACKWARD);
        expectNear("one-way west: now the route west uses the street",
                walkLength(backward, 400, 0, 0, 0), 400, 15);
        expectNear("one-way west: and the route east goes round",
                walkLength(backward, 0, 0, 400, 0), 520, 30);

        // A vehicle obeys it too. The restriction is a property of the road rather than of who is on it,
        // and every mode goes through the one question the graph asks the segment, so this is a check
        // that the drive branch did not grow a rule of its own.
        expectNear("a drive obeys the same restriction",
                driveLength(forward, 400, 0, 0, 0), 520, 30);
    }

    /** The street from west to east, a bypass beside it, and a connector at each end. */
    private static RoadNetwork oneWayNetwork(RoadDirection direction) {
        RoadNetwork net = new RoadNetwork();
        addRoad(net, RoadClass.ROAD, 0, 0, 400, 0);
        addRoad(net, RoadClass.ROAD, 0, 60, 400, 60);
        addRoad(net, RoadClass.ROAD, 0, 0, 0, 60);
        addRoad(net, RoadClass.ROAD, 400, 0, 400, 60);

        // The street is the one road with both ends on z = 0: the connectors each have an end there, and
        // the bypass has none.
        for (RoadSegment segment : net.segments()) {
            RoadNode from = net.node(segment.fromNode());
            RoadNode to = net.node(segment.toNode());
            if (from != null && to != null && from.z() == 0 && to.z() == 0) {
                segment.setDirection(direction);
            }
        }
        return net;
    }

    private static double walkLength(RoadNetwork net, int fromX, int fromZ, int toX, int toZ) {
        return length(net, fromX, fromZ, toX, toZ, TravelMode.WALK);
    }

    private static double driveLength(RoadNetwork net, int fromX, int fromZ, int toX, int toZ) {
        return length(net, fromX, fromZ, toX, toZ, TravelMode.DRIVE);
    }

    private static double length(RoadNetwork net, int fromX, int fromZ, int toX, int toZ,
                                 TravelMode mode) {
        Route route = RoadRouter.findRoute(net, fromX, fromZ, toX, toZ, "one-way", mode,
                RoutePreferences.DEFAULTS);
        // Not a route at all is reported as a length no assertion can match, so that "refused" fails a
        // check that expected a way round rather than looking like a perfect zero.
        return route.isPresent() ? route.totalLength() : Double.NaN;
    }

    /** The MTR integration, in a session with no MTR in it.
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

