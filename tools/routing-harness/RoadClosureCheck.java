package bili.dongsz.howtogo.road;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Checks the closure model: the flag itself, that marking a whole street closes it in one undo, that
 * a closure survives a save and that a file from before closures existed reads as open.
 *
 * <p>In the {@code road} package next to {@code OneWayCheck} because it is about the road model; the
 * planning consequence -- a closed road is absent from the graph and a journey blocked by one says so
 * -- is checked here too, on a three-node network whose only crossing is the segment under test: the
 * failure explainer must answer "reachable" while it is open and "blocked by a closed road" once it
 * is shut.
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
        chainToggling();
        storageRoundTrip();
        routingBlocked();
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
     * Marking a street closed closes every piece of it in one undo, and the chain reads closed only
     * while all of its pieces are.
     */
    private static void chainToggling() {
        RoadNetwork net = new RoadNetwork();
        // Drawn west to east, then east to west: one street, two pieces, opposite numbering.
        RoadSegment first = road(net, 0, 0, 200, 0);
        RoadSegment second = road(net, 200, 200, 200, 0);
        RoadEditor editor = new RoadEditor(net);

        expect("the chain reads open before anything is said", !editor.chainClosed(first.id()));
        expect("closing the street is accepted",
                editor.setChainClosed(first.id(), true));
        expect("both pieces read closed", first.closed() && second.closed());
        expect("and so does the chain", editor.chainClosed(first.id())
                && editor.chainClosed(second.id()));

        editor.undo();
        expect("one undo reopens the street", !first.closed() && !second.closed());

        expect("closing just one piece is still a chain edit",
                editor.setChainClosed(second.id(), true));
        expect("which the chain reports as closed -- every piece of it is",
                editor.chainClosed(first.id()));
        expect("even though the first piece itself is open", !first.closed());
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
        // road it describes is open rather than missing or broken.
        try {
            String text = Files.readString(modern, StandardCharsets.UTF_8)
                    .replace("\"closed\": true", "")
                    .replace("\"closed\":true", "");
            Files.write(modern, text.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            expect("legacy rewrite failed: " + e, false);
            return;
        }
        RoadNetwork legacy = RoadStorage.load(modern);
        expect("a file without the field still loads", legacy.segmentCount() == 1);
        expect("and its road reads open", !legacy.segments().iterator().next().closed());
    }

    /**
     * The planning consequence on a three-node line: the only crossing between the two ends is the
     * segment under test, so with it open the ends are connected -- and with it shut the explainer
     * names the closure rather than a vague split.
     */
    private static void routingBlocked() {
        RoadNetwork net = new RoadNetwork();
        road(net, 0, 0, 200, 0);
        RoadSegment bridge = road(net, 200, 0, 400, 0);

        var open = bili.dongsz.howtogo.route.RoadRouter.explainFailure(net, 5, 0, 395, 0);
        expect("an open network reads as connected",
                "screen.howtogo.failure.same_component".equals(open.key()));

        bridge.setClosed(true);
        var blocked = bili.dongsz.howtogo.route.RoadRouter.explainFailure(net, 5, 0, 395, 0);
        expect("a closure across the only crossing is named as the reason",
                "screen.howtogo.failure.closed_blocks".equals(blocked.key()));
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

    private static Path temp(String name) {
        return Path.of(System.getProperty("java.io.tmpdir"), name);
    }
}
