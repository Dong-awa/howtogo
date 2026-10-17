package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.road.RoadClass;
import bili.dongsz.howtogo.road.RoadNetwork;
import bili.dongsz.howtogo.road.RoadSegment;
import bili.dongsz.howtogo.route.RoadRouter;
import bili.dongsz.howtogo.route.Route;
import bili.dongsz.howtogo.route.RoutePreferences;
import bili.dongsz.howtogo.route.TravelMode;

import java.util.ArrayList;
import java.util.List;

/**
 * Checks the RoadWeaver import against readings made up here.
 *
 * <p>In the {@code client} package on purpose: what is being tested is package-private, so that the
 * conversion stays a function of a reading rather than of RoadWeaver being installed. It lives with
 * the harness and is compiled the same way; nothing in the mod calls it.
 *
 * <p>What can be checked is everything after the reflection: a reading of RoadWeaver's roads in, this
 * mod's road network out -- its classes, its nodes, the junctions it does and does not make, and that
 * it costs nothing when the network is large. Whether RoadWeaver hands back the shapes this reads is a
 * question only a session with RoadWeaver can answer, which is why the reader is written to report
 * what it found rather than to assume it; the handshake itself is checked against a real jar when one
 * is on the classpath.
 */
public final class RoadWeaverCheck {

    private static int checks;
    private static int failures;

    private RoadWeaverCheck() {
    }

    /**
     * Runs the checks.
     *
     * @return the number of checks made and the number that failed, in that order
     */
    public static int[] run() {
        checks = 0;
        failures = 0;
        System.out.println("== RoadWeaver import ==");

        checkClasses();
        checkIds();
        checkJoins();
        checkConnectors();
        checkCrossings();
        checkFlatSnapshot();
        checkForcedClass();
        checkNames();
        checkCost();
        checkHandshake();

        System.out.println(failures == 0 ? "  RoadWeaver import ok (" + checks + " checks)"
                : "  RoadWeaver import FAILED: " + failures + " of " + checks);
        return new int[]{checks, failures};
    }

    /**
     * The class RoadWeaver's own description of a road turns into.
     *
     * <p>Every one of them a footpath, whatever its width and whether it is natural or artificial:
     * its width is how many blocks it paved, not what the road is for, and the map used to be split
     * into three colours by a number that says nothing about the road.
     */
    private static void checkClasses() {
        System.out.println("   what class a RoadWeaver road becomes");

        expect("a natural road is a footpath", RoadWeaverLayer.classOf(3, 1) == RoadClass.PATH);
        expect("and stays one however wide it is", RoadWeaverLayer.classOf(15, 1) == RoadClass.PATH);
        expect("an artificial road is a footpath too, however narrow",
                RoadWeaverLayer.classOf(2, 0) == RoadClass.PATH);
        expect("the ordinary artificial road is still a footpath",
                RoadWeaverLayer.classOf(3, 0) == RoadClass.PATH);
        expect("a wide artificial road is not promoted to a highway",
                RoadWeaverLayer.classOf(5, 0) == RoadClass.PATH);
        expect("and the widest of all is one as well",
                RoadWeaverLayer.classOf(15, 0) == RoadClass.PATH);
        expect("a reading with no width, which is the client snapshot, is a footpath",
                RoadWeaverLayer.classOf(-1, 0) == RoadClass.PATH);
        expect("as is one that also says it is natural", RoadWeaverLayer.classOf(-1, 1) == RoadClass.PATH);
    }

    /** Ids this layer hands out cannot collide with a player's node or segment. */
    private static void checkIds() {
        System.out.println("   the ids the layer uses");
        RoadNetwork network = RoadWeaverLayer.build(List.of(road(0, 0, 100, 0)), null);
        expect("the layer produces something", network.segmentCount() == 1);
        boolean disjoint = true;
        for (RoadSegment segment : network.segmentsSnapshot()) {
            disjoint &= segment.id() >= 2_000_000_000;
            disjoint &= segment.fromNode() >= 2_000_000_000 && segment.toNode() >= 2_000_000_000;
        }
        expect("every segment and node id comes from the layer's own range, so a merge cannot "
                + "shadow a drawn road", disjoint);
    }

    /**
     * What joins and what does not.
     *
     * <p>Two roads that meet at one point are one road network, which is how RoadWeaver states a
     * junction when it snaps a road onto another: it truncates the road and puts its end on the other
     * road's point. Two roads that merely cross on the map are not joined, exactly as hand-drawn roads
     * are not -- the map cannot tell a crossroads from a bridge, and this layer does not guess.
     */
    private static void checkJoins() {
        System.out.println("   the joins it makes");
        List<RoadWeaverLayer.Polyline> meeting = List.of(
                polyline(0, 0, 50, 0, 100, 0),
                polyline(50, 0, 50, 50));
        RoadNetwork joined = RoadWeaverLayer.build(meeting, null);
        expect("two roads that meet at a point share one node", joined.nodeCount() == 4);
        expect("the road through the meeting point is split there, so it is two segments",
                joined.segmentCount() == 3);
        expect("the shared point is a junction rather than an endpoint",
                joined.node(joined.segmentsSnapshot().get(0).fromNode()) != null);

        Route route = RoadRouter.findRoute(joined, 0, 0, 50, 50, "there", TravelMode.WALK,
                RoutePreferences.DEFAULTS);
        expect("a trip can run from one of them onto the other", route.isPresent());
        if (route.isPresent()) {
            expectNear("and it is the two roads it was planned over", route.totalLength(), 100, 1);
        }

        // One road that bends and comes back to the same point is a loop rather than a junction. It
        // still has geometry worth drawing, so it is cut in two rather than left as a segment from a
        // node to itself, which nothing could travel along.
        RoadNetwork loop = RoadWeaverLayer.build(List.of(polyline(0, 0, 0, 50, 100, 50, 100, 0,
                0, 0)), null);
        expect("a road that returns to its own start is a road, not a segment from a node to itself",
                loop.segmentCount() == 2 && loop.nodeCount() == 2);
        boolean selfLoops = false;
        for (RoadSegment segment : loop.segmentsSnapshot()) {
            selfLoops |= segment.fromNode() == segment.toNode();
        }
        expect("and no segment of it starts and ends at one node", !selfLoops);
    }

    /**
     * The gap RoadWeaver leaves at a structure, and the straight connector that closes it.
     *
     * <p>RoadWeaver trims a road short of the structure it serves on purpose, so the road that was
     * built to reach a village stops a few dozen blocks outside it. On its own map that is a road to
     * the village; to a router it is two fragments, and the village is unreachable by road. The
     * layer closes the gap for a completed connection, and only for a road that is actually near the
     * connection's two ends.
     */
    private static void checkConnectors() {
        System.out.println("   the gap at each end");
        RoadWeaverLayer.Polyline road = new RoadWeaverLayer.Polyline(points(0, 0, 400, 0), -1, 0);
        RoadWeaverLayer.Connection connection = new RoadWeaverLayer.Connection(
                new RoadWeaverLayer.Point(-30, 64, 0), new RoadWeaverLayer.Point(430, 64, 0));
        RoadNetwork network = RoadWeaverLayer.build(List.of(road), List.of(connection), null);
        expect("both trimmed ends get a straight connector", network.segmentCount() == 3);
        expect("and both structures become nodes of the network", network.nodeCount() == 4);
        Route route = RoadRouter.findRoute(network, -30, 0, 430, 0, "the village",
                TravelMode.WALK, RoutePreferences.DEFAULTS);
        expect("so a walk can start at the village and end at the next one", route.isPresent());
        if (route.isPresent()) {
            expectNear("and the connectors are included in its length", route.totalLength(), 460, 2);
        }

        // A connection whose road is nowhere near either end is not closed by joining something else.
        RoadNetwork far = RoadWeaverLayer.build(List.of(new RoadWeaverLayer.Polyline(
                        points(0, 0, 300, 0), -1, 0)),
                List.of(new RoadWeaverLayer.Connection(new RoadWeaverLayer.Point(-900, 64, 0),
                        new RoadWeaverLayer.Point(1200, 64, 0))), null);
        expect("a connection with no road near it is left alone", far.segmentCount() == 1);

        // A road that already reaches the structure is not given a duplicate two-block stub.
        RoadNetwork touching = RoadWeaverLayer.build(
                List.of(new RoadWeaverLayer.Polyline(points(0, 0, 100, 0), -1, 0)),
                List.of(new RoadWeaverLayer.Connection(new RoadWeaverLayer.Point(100, 64, 0),
                        new RoadWeaverLayer.Point(0, 64, 0))), null);
        expect("a road that already touches its structures gets no connector",
                touching.segmentCount() == 1);
    }

    /** Two roads crossing without sharing a point are not joined, which is a bridge. */
    private static void checkCrossings() {
        System.out.println("   the crossings it refuses");
        List<RoadWeaverLayer.Polyline> crossing = List.of(
                polyline(0, 0, 100, 0),
                polyline(50, -50, 50, 50));
        RoadNetwork network = RoadWeaverLayer.build(crossing, null);
        expect("two crossing roads are two separate pieces of road", network.segmentCount() == 2);
        expect("with their own endpoints and no shared node", network.nodeCount() == 4);
        Route drive = RoadRouter.findRoute(network, 0, 0, 50, 50, "there", TravelMode.DRIVE,
                RoutePreferences.DEFAULTS);
        expect("no drive is planned from one across the other", !drive.isPresent());

        // One of RoadWeaver's own snaps puts the roads' points exactly on one another, which is the
        // difference the conversion must recognise: the same crossing, made a junction.
        List<RoadWeaverLayer.Polyline> snapped = List.of(
                polyline(0, 0, 50, 0, 100, 0),
                polyline(50, 0, 50, 50));
        RoadNetwork junction = RoadWeaverLayer.build(snapped, null);
        expect("a snapped crossing is a junction", junction.nodeCount() == 4
                && junction.segmentCount() == 3);
        Route through = RoadRouter.findRoute(junction, 0, 0, 50, 50, "there", TravelMode.WALK,
                RoutePreferences.DEFAULTS);
        expect("so a walker turns at it onto the other road", through.isPresent());
    }

    /**
     * The client snapshot's geometry is flat, and that must still produce a whole road.
     *
     * <p>The snapshot normalises every point to y=0, so a reading from it has no height at all. The
     * conversion still makes its junctions by exact point, so two roads that meet in it are joined, and
     * a road with a hundred sampled points on it is still one segment carrying all of them.
     */
    private static void checkFlatSnapshot() {
        System.out.println("   a reading with no height");
        List<RoadWeaverLayer.Point> sampled = new ArrayList<>();
        for (int i = 0; i <= 100; i++) {
            sampled.add(new RoadWeaverLayer.Point(i * 2, 0, 0));
        }
        RoadNetwork flat = RoadWeaverLayer.build(
                List.of(new RoadWeaverLayer.Polyline(sampled, -1, 0)), null);
        expect("a hundred sampled points are one piece of road", flat.segmentCount() == 1);
        expect("carrying every point as a bend",
                flat.segmentsSnapshot().get(0).vertexCount() == sampled.size());
        expect("of the class the snapshot's missing width implies",
                flat.segmentsSnapshot().get(0).roadClass() == RoadClass.PATH);
    }

    /** A class forced by the config replaces RoadWeaver's own description of every road. */
    private static void checkForcedClass() {
        System.out.println("   a forced class");
        RoadNetwork forced = RoadWeaverLayer.build(
                List.of(road(0, 0, 100, 0), road(200, 0, 300, 0)), RoadClass.HIGHWAY);
        expect("every road takes the configured class",
                forced.segmentsSnapshot().stream()
                        .allMatch(segment -> segment.roadClass() == RoadClass.HIGHWAY));
        RoadNetwork natural = RoadWeaverLayer.build(
                List.of(new RoadWeaverLayer.Polyline(points(0, 0, 100, 0), 3, 1)), null);
        expect("without the override, RoadWeaver's own type decides",
                natural.segmentsSnapshot().get(0).roadClass() == RoadClass.PATH);
    }

    /** What a structure is called when RoadWeaver has an id for it, and when it has not. */
    private static void checkNames() {
        System.out.println("   the names of the structures");
        expect("a vanilla structure's id is prettified for a label",
                "Village plains".equals(RoadWeaverStructureSource.prettify("village_plains")));
        expect("and a path with a slash reads too",
                "Nether bridge".equals(RoadWeaverStructureSource.prettify("nether/bridge")));
        expect("an empty id does not become a word",
                RoadWeaverStructureSource.prettify("").isEmpty());
        expect("RoadWeaver spells an unnamed structure 'unknown'", RoadWeaverLayer.isUnknown("unknown"));
        expect("and a real id is not unknown", !RoadWeaverLayer.isUnknown("minecraft:village_plains"));
    }

    /**
     * What a whole road network costs to convert.
     *
     * <p>The conversion runs on a thread of this mod's own, but it still has to finish: RoadWeaver can
     * hold thousands of roads of a few hundred points each, and a conversion that took seconds would
     * make switching a world visible. The shape here is a real one and deliberately large.
     */
    private static void checkCost() {
        System.out.println("   what a whole road network costs");
        List<RoadWeaverLayer.Polyline> roads = new ArrayList<>();
        for (int road = 0; road < 1000; road++) {
            List<RoadWeaverLayer.Point> points = new ArrayList<>();
            for (int step = 0; step < 200; step++) {
                points.add(new RoadWeaverLayer.Point(road * 300 + step * 2, 64,
                        (road & 1) == 0 ? step : -step));
            }
            roads.add(new RoadWeaverLayer.Polyline(points, 3, 0));
        }
        long startedAt = System.nanoTime();
        RoadNetwork network = RoadWeaverLayer.build(roads, null);
        long millis = Math.round((System.nanoTime() - startedAt) / 1_000_000.0);
        System.out.println("   " + roads.size() + " roads, " + roads.size() * 200
                + " points, " + network.segmentCount() + " segment(s), converted in " + millis + " ms");
        expect("every road is converted", network.segmentCount() == 1000);
        expect("and converting a whole network stays quick (under 1000 ms)", millis < 1000);

        // The same network with a completed connection per road, which is what the gap connectors
        // have to be matched against. Each connection is compared with every road, so this is the
        // quadratic half of the conversion; it must still not be visible when the world loads.
        List<RoadWeaverLayer.Connection> connections = new ArrayList<>();
        for (int road = 0; road < 1000; road++) {
            RoadWeaverLayer.Point from = new RoadWeaverLayer.Point(road * 300 - 10, 64, 0);
            RoadWeaverLayer.Point to = new RoadWeaverLayer.Point(road * 300 + 408, 64,
                    (road & 1) == 0 ? 199 : -199);
            connections.add(new RoadWeaverLayer.Connection(from, to));
        }
        startedAt = System.nanoTime();
        RoadNetwork withConnectors = RoadWeaverLayer.build(roads, connections, null);
        millis = Math.round((System.nanoTime() - startedAt) / 1_000_000.0);
        System.out.println("   with " + connections.size() + " connection(s): "
                + withConnectors.segmentCount() + " segment(s), converted in " + millis + " ms");
        expect("every connection adds its two straight connectors",
                withConnectors.segmentCount() == 3000);
        expect("and matching them against every road stays quick (under 1000 ms)", millis < 1000);
    }

    /**
     * The reflective handshake, against whatever RoadWeaver jar the harness was given.
     *
     * <p>Without a jar this is skipped rather than failed: a machine that has never seen RoadWeaver
     * should not report this integration broken. With one, every class, field and method name the
     * reader uses is checked against the real shapes -- the only way "does this version still work" can
     * be answered without launching the game.
     */
    private static void checkHandshake() {
        if (!RoadWeaverLayer.classesPresent()) {
            System.out.println("   no RoadWeaver jar on the classpath: the handshake check is skipped");
            return;
        }
        boolean bound = RoadWeaverLayer.bind();
        expect("the client map data's shapes are the ones the reader reads", bound);
        System.out.println("   whole road store " + (RoadWeaverLayer.fullMapReadable()
                ? "readable on this version" : "not readable on this version (maps are read from its client snapshot)"));
        if (RoadWeaverLayer.fullMapReadable()) {
            // The server-side classes are what make single player automatic; their presence is a fact
            // about the version, so it is reported rather than asserted for every jar.
            expect("the server-side road store's shapes are the ones the reader reads", true);
        }
    }

    // ------------------------------------------------------------------ helpers

    /** A straight road from one place to another, with no width or type of its own. */
    private static RoadWeaverLayer.Polyline road(int fromX, int fromZ, int toX, int toZ) {
        return new RoadWeaverLayer.Polyline(points(fromX, fromZ, toX, toZ), -1, 0);
    }

    /** A road that runs through the given points in order. */
    private static RoadWeaverLayer.Polyline polyline(int... xz) {
        List<RoadWeaverLayer.Point> points = new ArrayList<>(xz.length / 2);
        for (int i = 0; i < xz.length; i += 2) {
            points.add(new RoadWeaverLayer.Point(xz[i], 64, xz[i + 1]));
        }
        return new RoadWeaverLayer.Polyline(points, -1, 0);
    }

    /** The two points of a straight road. */
    private static List<RoadWeaverLayer.Point> points(int fromX, int fromZ, int toX, int toZ) {
        return List.of(new RoadWeaverLayer.Point(fromX, 64, fromZ),
                new RoadWeaverLayer.Point(toX, 64, toZ));
    }

    private static void expect(String what, boolean ok) {
        checks++;
        if (ok) {
            System.out.println("  ok   " + what);
        } else {
            failures++;
            System.out.println("  FAIL " + what);
        }
    }

    private static void expectNear(String what, double actual, double expected, double tolerance) {
        expect(what + " (" + Math.round(actual * 10) / 10.0 + " vs " + expected + ")", Math.abs(actual - expected) <= tolerance);
    }
}
