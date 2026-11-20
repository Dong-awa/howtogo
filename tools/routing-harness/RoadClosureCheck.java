package bili.dongsz.howtogo.road;

import bili.dongsz.howtogo.route.LinePlanner;
import bili.dongsz.howtogo.route.RoadRouter;
import bili.dongsz.howtogo.route.Route;
import bili.dongsz.howtogo.route.RoutePreferences;
import bili.dongsz.howtogo.route.TravelMode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Checks the closure model: the flag itself, that one segment can be shut and one undo reopens it,
 * that a closure survives a save, and -- the part the model alone cannot show -- that the router never
 * plans through a shut segment.
 *
 * <p>In the {@code road} package next to {@code OneWayCheck} because it is about the road model. The
 * planning consequence is checked here as well, because for closures it is the feature: the reported
 * bug was a route planning straight down the road the player had just closed. Three shapes are covered:
 * a split of a shut road stays shut (the router anchors by splitting, which used to reopen it), a trip
 * whose only road is shut has no route at all, and a trip with an open road available takes the detour.
 * The failure explainer must also name the closure rather than "too far from a road", both when a
 * crossing is shut and when the player stands on the shut road itself. The line editor's verdict
 * across a closure is checked too: a shut piece between two stops is a red pair, judged from the road
 * under each stop without splitting a road the line cannot ride.
 */
public final class RoadClosureCheck {

    private static int checks;
    private static int failures;

    private RoadClosureCheck() {
    }

    /**
     * Runs the checks.
     *
     * @return the number of checks made and the number that failed, in that order
     */
    public static int[] run() {
        checks = 0;
        failures = 0;
        System.out.println("== road closures ==");

        model();
        togglingOneSegment();
        splitKeepsState();
        storageRoundTrip();
        routingBlocked();
        closedRoadNeverPlanned();
        detourAroundClosedRoad();
        closedEndsExplainTheClosure();
        lineVerdictAcrossAClosure();
        return new int[]{checks, failures};
    }

    /** The flag itself: off by default, orthogonal to the one-way direction, kept by a copy. */
    private static void model() {
        RoadNetwork net = new RoadNetwork();
        RoadSegment segment = road(net, 0, 0, 400, 0);

        expect("a new road is open", !segment.closed());
        int before = RoadSegment.closureChanges();
        segment.setClosed(true);
        expect("closing is seen", segment.closed());
        expect("and moves the closure counter", RoadSegment.closureChanges() == before + 1);
        expect("a closed road keeps its direction", segment.direction() == RoadDirection.TWO_WAY);
        segment.setDirection(RoadDirection.FORWARD);
        expect("a closure is orthogonal to the one-way state", segment.closed()
                && segment.direction() == RoadDirection.FORWARD);
        expect("a copy keeps the closure", segment.copy().closed());
        segment.setClosed(false);
        expect("reopening is seen", !segment.closed());
        expect("and moves the counter too", RoadSegment.closureChanges() == before + 2);
    }

    /**
     * A closure is per segment and one undo step: a construction site shuts one block, and the rest of
     * the street keeps routing. Toggling used to be imagined as a whole-street operation, which is the
     * API this check used to call and which never existed.
     */
    private static void togglingOneSegment() {
        RoadNetwork net = new RoadNetwork();
        RoadSegment first = road(net, 0, 0, 200, 0);
        RoadSegment second = road(net, 200, 0, 400, 0);
        RoadEditor editor = new RoadEditor(net);
        int before = RoadSegment.closureChanges();

        expect("the segment reads open before anything is said", !first.closed());
        expect("closing it is accepted", editor.toggleClosed(first.id()));
        expect("the segment is shut", first.closed());
        expect("and moves the closure counter", RoadSegment.closureChanges() == before + 1);
        expect("while the next piece of the same street stays open", !second.closed());

        editor.undo();
        expect("one undo reopens it", !net.segment(first.id()).closed());
        expect("and leaves the other segment as it was", !net.segment(second.id()).closed());
    }

    /**
     * A split is a topology edit and must not change what the road is. The router anchors a trip by
     * splitting the nearest segment, so a shut road that lost its flag there was handed back to the
     * search as two open halves -- that is the reported bug, reproduced as close to the cause as it
     * can be. The category and the number are checked with it: a split used to drop those too. The
     * split is also required to be quiet: a split hands a road's flags to its pieces, it does not
     * change a flag, so a plan that anchors on a one-way or shut road must not look like an edit.
     */
    private static void splitKeepsState() {
        RoadNetwork net = new RoadNetwork();
        RoadSegment original = road(net, 0, 0, 400, 0);
        original.setClosed(true);
        RoadCategory category = RoadCategory.of(RoadClass.ROAD, "c7");
        original.setCategory(category);
        original.setNumber("G318");
        RoadEditor editor = new RoadEditor(net);

        int closuresBefore = RoadSegment.closureChanges();
        int junction = editor.splitSegment(original.id(), 1, 200, 64, 0);
        expect("a shut road splits", junction >= 0);
        expect("the original segment id is gone", net.segment(original.id()) == null);
        expect("and the split is a topology edit, not a closure change",
                RoadSegment.closureChanges() == closuresBefore);

        List<RoadSegment> halves = segmentsAt(net, junction);
        expect("into two pieces", halves.size() == 2);
        boolean bothClosed = halves.size() == 2;
        boolean stateKept = halves.size() == 2;
        for (RoadSegment half : halves) {
            bothClosed = bothClosed && half.closed();
            stateKept = stateKept && category.equals(half.category())
                    && "G318".equals(half.number());
        }
        expect("both halves are still shut", bothClosed);
        expect("both keep the road's category and number", stateKept);
        expect("and both halves have new ids, not the original's", halves.size() == 2
                && halves.get(0).id() != original.id()
                && halves.get(1).id() != original.id());

        // An open road must stay open: the copy is of the whole state, not a bare "closed" bit.
        RoadNetwork openNet = new RoadNetwork();
        RoadSegment openRoad = road(openNet, 0, 0, 400, 0);
        RoadEditor openEditor = new RoadEditor(openNet);
        RoadEditor.Split openSplit = openEditor.splitSegmentInto(openRoad.id(), 1, 200, 64, 0);
        expect("an open road splits as well", openSplit != null);
        if (openSplit != null) {
            RoadSegment firstHalf = openNet.segment(openSplit.firstSegment());
            RoadSegment secondHalf = openNet.segment(openSplit.secondSegment());
            expect("and both halves are open, not shut by the split",
                    firstHalf != null && secondHalf != null
                            && !firstHalf.closed() && !secondHalf.closed());
        }
    }

    /** A closure survives a save, and a file that predates the field reads as open. */
    private static void storageRoundTrip() {
        RoadNetwork net = new RoadNetwork();
        RoadSegment segment = road(net, 0, 0, 400, 0);
        segment.setClosed(true);
        Path modern = temp("howtogo-closure.json");
        expect("the network is saved", RoadStorage.save(modern, net));
        RoadNetwork loaded = RoadStorage.load(modern);
        expect("and read back", loaded.segmentCount() == 1);
        expect("with the closure kept", loaded.segments().iterator().next().closed());

        // The tolerance test: the same file with the field gone is what an older build wrote, and a
        // road it describes is open rather than missing or broken. The comma goes with the field, or
        // the pretty-printed file would not parse at all and the load would start empty.
        String text;
        try {
            text = Files.readString(modern, StandardCharsets.UTF_8)
                    .replace("\"closed\": true,", "")
                    .replace("\"closed\":true,", "");
            Files.write(modern, text.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            expect("legacy rewrite failed: " + e, false);
            return;
        }
        expect("the rewritten file no longer mentions a closure", !text.contains("closed"));
        RoadNetwork legacy = RoadStorage.load(modern);
        expect("a file without the field still loads", legacy.segmentCount() == 1);
        expect("and its road reads open", !legacy.segments().iterator().next().closed());
    }

    /**
     * The planning consequence on a three-node line: the only crossing between the two ends is the
     * segment under test, so with it open the ends are connected -- and with it shut the explainer
     * names the closure rather than a vague split.
     *
     * <p>A car asks the question, not a walker: walking's connector reach is WALK_CONNECTOR_REACH
     * (4096 blocks), so its explainer judges the destination against any open node within that reach,
     * finds the destination's end in the same open component as the start and answers "connected". A
     * walk along the open road plus that hop can really be planned, so the answer is honest; it is a
     * car, whose reach is its own carriageway, that the shut crossing actually blocks.
     */
    private static void routingBlocked() {
        RoadNetwork net = new RoadNetwork();
        road(net, 0, 0, 200, 0);
        RoadSegment bridge = road(net, 200, 0, 400, 0);

        // Kept near the two end nodes, since the explainer reads nodes rather than splitting at the
        // perpendicular foot the way a plan does.
        var open = RoadRouter.explainFailure(net, 1, 0, 399, 0, TravelMode.DRIVE);
        expect("an open network reads as connected",
                "screen.howtogo.failure.same_component".equals(open.key()));

        bridge.setClosed(true);
        var blocked = RoadRouter.explainFailure(net, 1, 0, 399, 0, TravelMode.DRIVE);
        expect("a closure across the only crossing is named as the reason",
                "screen.howtogo.failure.closed_blocks".equals(blocked.key()));
    }

    /**
     * The reported bug, with nothing else on the map to distract from it: one straight road, shut, and
     * a trip from one end of it to the other. Both ends are on the road, so an anchor split at either
     * end used to hand the search two open pieces of the road the player had just closed. There is no
     * open road to fall back to, so the only honest answer is no route at all -- for a car as well as
     * a walker, since both would otherwise be sent straight through the closure.
     */
    private static void closedRoadNeverPlanned() {
        for (TravelMode mode : new TravelMode[]{TravelMode.WALK, TravelMode.DRIVE}) {
            RoadNetwork net = new RoadNetwork();
            road(net, 0, 0, 400, 0).setClosed(true);
            Route route = RoadRouter.findRoute(net, 5, 0, 395, 0, "past the closure", mode);
            expect("a shut road is not planned along, even with both ends on it (" + mode.id() + ")",
                    !route.isPresent());
        }
    }

    /**
     * The same trip with an open road two blocks beside the shut one: a route must exist and must stay
     * on the open road. The two ends are the player's own positions on the shut road, so a route that
     * keeps to the open one has to cross that gap twice and pays two real connectors. A route down the
     * shut road underfoot has two zero-length connectors and no such length -- which is the shape the
     * bug had, and is why the connectors are asserted rather than only the drawn line: two straight
     * roads leave no interior point to sample.
     *
     * <p>The two-block gap is deliberate: it is inside every mode's connector reach (a car's is half
     * its widest carriageway), so a failure here is the closure being travelled rather than the
     * connector being refused.
     */
    private static void detourAroundClosedRoad() {
        for (TravelMode mode : new TravelMode[]{TravelMode.WALK, TravelMode.DRIVE}) {
            RoadNetwork net = new RoadNetwork();
            road(net, 0, 0, 400, 0).setClosed(true);
            road(net, 0, 2, 400, 2);

            Route route = RoadRouter.findRoute(net, 5, 0, 395, 0, "beside the closure", mode);
            expect("a trip beside a shut road still has a route (" + mode.id() + ")", route.isPresent());
            expect("and it anchors on the open road, not the shut one underfoot (" + mode.id() + ")",
                    route.isPresent() && route.startConnector() > 1.0
                            && route.goalConnector() > 1.0);
            expect("and it is a real road route, paying twice for the hops (" + mode.id() + ")",
                    route.isPresent() && route.roadLength() > 300 && route.totalLength() > 392);
            expect("and no drawn point lies on the shut road's centreline (" + mode.id() + ")",
                    clearsCorridor(route, 1.0));
        }
    }

    /**
     * The other half of the failure diagnosis: both ends stand on the only shut road. The explainer's
     * "is there a road at this end at all" questions must count closed roads (there is one), and the
     * closure comparison must be asked before "too far from a road" -- the nearest open road is a
     * thousand blocks away, so the old order blamed the distance for a road that is right there.
     *
     * <p>A car is used because walking's connector reach is the same practical infinity -- see
     * WALK_CONNECTOR_REACH -- so a walker's explainer is willing to judge the ends against nodes a
     * thousand blocks off, and the two ends are not the ends it compares. A car's reach is its own
     * carriageway, which is exactly the case where the only road under the player is the one that is
     * shut.
     */
    private static void closedEndsExplainTheClosure() {
        RoadNetwork net = new RoadNetwork();
        road(net, 0, 0, 400, 0).setClosed(true);
        // Somewhere else on the map: the open graph has to be non-empty, or the answer would be
        // "nothing to route on" rather than "the road under you is shut".
        road(net, 0, 1000, 100, 1000);

        var blocked = RoadRouter.explainFailure(net, 1, 0, 399, 0, TravelMode.DRIVE);
        expect("both ends on the only road, shut, is explained as the closure",
                "screen.howtogo.failure.closed_blocks".equals(blocked.key()));
    }

    // ----------------------------------------------------------------- helpers

    /**
     * The line editor's verdict across a closure: two stops either side of a shut rail piece must read
     * SEPARATE, which is the red mark, because the line cannot ride over a road that is shut. The
     * judgement reads the shut road to answer that -- a stop standing on it is not "no road here" -- but
     * does not split it: a shut road is not an anchor, and a split would add a junction to the
     * workspace for a piece the ride cannot use.
     */
    private static void lineVerdictAcrossAClosure() {
        RoadNetwork net = new RoadNetwork();
        addRail(net, 0, 0, 200, 0);
        addRail(net, 200, 0, 400, 0).setClosed(true);
        RoutePreferences rail = LinePlanner.ridePreferences(RoadClass.RAIL,
                RoutePreferences.DEFAULTS);
        RoadRouter.Workspace workspace = new RoadRouter.Workspace(net);

        RoadRouter.Connection verdict = RoadRouter.connection(workspace, 100, 0, 300, 0,
                TravelMode.TRANSIT, rail);
        expect("two stops either side of a shut rail piece are not connected",
                verdict == RoadRouter.Connection.SEPARATE);
    }

    /** Whether every drawn point but the trip's two ends stands clear of the z = 0 road's centreline. */
    private static boolean clearsCorridor(Route route, double corridor) {
        if (!route.isPresent()) {
            return false;
        }
        List<double[]> points = route.points();
        for (int i = 1; i < points.size() - 1; i++) {
            if (Math.abs(points.get(i)[1]) < corridor) {
                return false;
            }
        }
        return true;
    }

    /** The segments that meet at a node, which after a split are the two halves of the split road. */
    private static List<RoadSegment> segmentsAt(RoadNetwork net, int nodeId) {
        List<RoadSegment> found = new ArrayList<>();
        for (RoadSegment segment : net.segments()) {
            if (segment.fromNode() == nodeId || segment.toNode() == nodeId) {
                found.add(segment);
            }
        }
        return found;
    }

    private static void expect(String what, boolean holds) {
        checks++;
        if (!holds) {
            failures++;
            System.out.println("  FAIL " + what);
        }
    }

    private static RoadSegment road(RoadNetwork net, int x1, int z1, int x2, int z2) {
        RoadSegment segment = net.newSegment(RoadClass.ROAD, 64, 2);
        segment.addVertex(x1, z1);
        segment.addVertex(x2, z2);
        segment.setFromNode(nodeAt(net, x1, z1).id());
        segment.setToNode(nodeAt(net, x2, z2).id());
        net.addSegment(segment);
        return segment;
    }

    private static RoadNode nodeAt(RoadNetwork net, int x, int z) {
        RoadNode existing = net.nearestNode(x, z, 0.0);
        return existing != null ? existing : net.addNode(x, 64, z, RoadNode.Type.JUNCTION, null);
    }

    /** One straight piece of rail, wired to a node at each end, as the line editor draws one. */
    private static RoadSegment addRail(RoadNetwork net, int x1, int z1, int x2, int z2) {
        RoadSegment segment = net.newSegment(RoadClass.RAIL, 64, 2);
        segment.addVertex(x1, z1);
        segment.addVertex(x2, z2);
        segment.setFromNode(nodeAt(net, x1, z1).id());
        segment.setToNode(nodeAt(net, x2, z2).id());
        net.addSegment(segment);
        return segment;
    }

    private static Path temp(String name) {
        return Path.of(System.getProperty("java.io.tmpdir"), name);
    }
}
