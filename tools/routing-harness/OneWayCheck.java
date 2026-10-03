package bili.dongsz.howtogo.road;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Checks the one-way model itself: the direction states, what a segment allows, what marking a whole
 * street does, and that all of it survives a save.
 *
 * <p>In the {@code road} package because it is about the road model rather than about planning: the
 * routing consequence of a one-way street is checked in {@code Harness}, where a network with a way
 * round it can be built. What is here is the part a planner cannot see -- that "one-way" is a direction
 * and not a flag, that marking a bent street orients its pieces consistently instead of writing one word
 * onto all of them, and that the file reads back as what was written.
 */
public final class OneWayCheck {

    private static int checks;
    private static int failures;

    private OneWayCheck() {
    }

    /**
     * Runs the checks.
     *
     * @return the number of checks made and the number that failed, in that order
     */
    public static int[] run() {
        checks = 0;
        failures = 0;
        System.out.println("== one-way model ==");

        states();
        segmentRules();
        markingAStreet();
        savingAndLoading();
        return new int[]{checks, failures};
    }

    /** The three states, and that the switch moves through them and comes back. */
    private static void states() {
        expect("two-way is not one-way", !RoadDirection.TWO_WAY.isOneWay());
        expect("forwards is one-way", RoadDirection.FORWARD.isOneWay());
        expect("backwards is one-way", RoadDirection.BACKWARD.isOneWay());
        expect("the switch turns two-way into forwards", RoadDirection.TWO_WAY.next() == RoadDirection.FORWARD);
        expect("forwards into backwards", RoadDirection.FORWARD.next() == RoadDirection.BACKWARD);
        expect("and backwards back to two-way", RoadDirection.BACKWARD.next() == RoadDirection.TWO_WAY);
        expect("reversing a two-way road leaves it two-way",
                RoadDirection.TWO_WAY.reversed() == RoadDirection.TWO_WAY);
        expect("reversing twice is where it started",
                RoadDirection.BACKWARD.reversed().reversed() == RoadDirection.BACKWARD);
        expect("an id round-trips", RoadDirection.byId("backward") == RoadDirection.BACKWARD);
        expect("and a word this version does not know reads as unrestricted travel, "
                        + "which is the one answer that cannot invent a direction",
                RoadDirection.byId("sideways") == RoadDirection.TWO_WAY);
        expect("as does nothing at all", RoadDirection.byId(null) == RoadDirection.TWO_WAY);
    }

    /** Which end of a segment each state lets a traveller set off from. */
    private static void segmentRules() {
        RoadNetwork net = new RoadNetwork();
        RoadSegment segment = road(net, 0, 0, 400, 0);
        int west = segment.fromNode();
        int east = segment.toNode();

        segment.setDirection(RoadDirection.TWO_WAY);
        expect("two-way: travel may start at either end",
                segment.allowsTravelFrom(west) && segment.allowsTravelFrom(east));

        segment.setDirection(RoadDirection.FORWARD);
        expect("forwards: travel may start at the from-node", segment.allowsTravelFrom(west));
        expect("and not at the to-node", !segment.allowsTravelFrom(east));
        expect("and the segment reports itself one-way", segment.oneWay());

        segment.setDirection(RoadDirection.BACKWARD);
        expect("backwards: travel may start at the to-node", segment.allowsTravelFrom(east));
        expect("and not at the from-node", !segment.allowsTravelFrom(west));

        segment.setDirection(RoadDirection.TWO_WAY);
        expect("and back to two-way it lets both ends in again",
                segment.allowsTravelFrom(west) && segment.allowsTravelFrom(east));
    }

    /**
     * Marking a bent street, whose two pieces were drawn from opposite ends.
     *
     * <p>The interesting part is that the two pieces are stored pointing different ways, which is what a
     * player drawing the second half from the far end produces. Marking the street one-way has to leave
     * the two pieces running the same way along it, and the check is the shape of that: walking the
     * street from one end, every piece lets travel in at the node the previous piece let it out at.
     */
    private static void markingAStreet() {
        RoadNetwork net = new RoadNetwork();
        // Drawn west to east, then east to west: one street, two pieces, opposite numbering.
        RoadSegment first = road(net, 0, 0, 200, 0);
        RoadSegment second = road(net, 200, 200, 200, 0);
        RoadEditor editor = new RoadEditor(net);

        expect("the two pieces are one street", RoadChains.chainContaining(net, first.id()).size() == 2);
        expect("and they are stored pointing opposite ways",
                first.fromNode() != second.fromNode() && first.fromNode() != second.toNode());
        expect("which reads as two-way before anything is said", 
                editor.chainDirection(first.id()) == RoadDirection.TWO_WAY);

        expect("marking the street one-way is accepted",
                editor.setChainDirection(first.id(), RoadDirection.FORWARD));
        expect("the street now reports one-way",
                editor.chainDirection(first.id()) == RoadDirection.FORWARD);
        expect("both pieces are one-way",
                first.oneWay() && second.oneWay());
        expect("and neither lets travel start at both of its ends",
                first.allowsTravelFrom(first.fromNode()) != first.allowsTravelFrom(first.toNode())
                        && second.allowsTravelFrom(second.fromNode())
                        != second.allowsTravelFrom(second.toNode()));

        // The street runs from the first piece's from-node, through the shared node, to the second
        // piece's far end. Each piece must let travel in at the node the walk arrives at.
        int node = first.fromNode();
        boolean walked = true;
        for (int id : RoadChains.chainContaining(net, first.id())) {
            RoadSegment piece = net.segment(id);
            walked = walked && piece.allowsTravelFrom(node);
            node = piece.fromNode() == node ? piece.toNode() : piece.fromNode();
        }
        expect("and the pieces agree about which way along the street is allowed", walked);
        expect("with the walk ending at the far end of the street",
                node == second.toNode() || node == second.fromNode());

        // Reversed: the same street, the other way, and the pieces are oriented the other way with it.
        editor.setChainDirection(first.id(), RoadDirection.BACKWARD);
        int start = first.fromNode();
        boolean reversedOk = true;
        for (int id : RoadChains.chainContaining(net, first.id())) {
            RoadSegment piece = net.segment(id);
            reversedOk = reversedOk && !piece.allowsTravelFrom(start);
            start = piece.fromNode() == start ? piece.toNode() : piece.fromNode();
        }
        expect("reversing the street refuses travel from the end it used to allow", reversedOk);

        editor.setChainDirection(first.id(), RoadDirection.TWO_WAY);
        expect("and two-way clears it on every piece",
                !first.oneWay() && !second.oneWay());
    }

    /** A file keeps what it was given, including the boolean written before directions existed. */
    private static void savingAndLoading() {
        Path modern = temp("howtogo-oneway.json");
        RoadNetwork net = new RoadNetwork();
        RoadSegment east = road(net, 0, 0, 400, 0);
        RoadSegment west = road(net, 0, 100, 400, 100);
        east.setDirection(RoadDirection.FORWARD);
        west.setDirection(RoadDirection.BACKWARD);

        expect("the network is saved", RoadStorage.save(modern, net));
        RoadNetwork loaded = RoadStorage.load(modern);
        expect("and read back with both roads on it", loaded.segmentCount() == 2);
        boolean forwardKept = false;
        boolean backwardKept = false;
        for (RoadSegment segment : loaded.segments()) {
            RoadNode from = loaded.node(segment.fromNode());
            if (from == null) {
                continue;
            }
            if (from.z() == 0) {
                forwardKept = segment.direction() == RoadDirection.FORWARD;
            } else {
                backwardKept = segment.direction() == RoadDirection.BACKWARD;
            }
        }
        expect("a forwards road comes back forwards", forwardKept);
        expect("and a backwards road comes back backwards, which the old boolean could not say",
                backwardKept);

        // A file from the build that wrote `oneWay` instead of `direction`.
        Path legacy = temp("howtogo-oneway-legacy.json");
        String json = "{\"version\":1,\"nodes\":["
                + "{\"id\":1,\"x\":0,\"y\":64,\"z\":0,\"type\":\"JUNCTION\"},"
                + "{\"id\":2,\"x\":400,\"y\":64,\"z\":0,\"type\":\"ENDPOINT\"}],\"segments\":["
                + "{\"id\":10,\"roadClass\":\"ROAD\",\"from\":1,\"to\":2,\"oneWay\":true,\"y\":64,"
                + "\"xs\":[0,400],\"zs\":[0,0]}]}";
        try {
            Files.write(legacy, json.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            expect("the legacy file could be written (" + e.getMessage() + ")", false);
            return;
        }
        RoadNetwork old = RoadStorage.load(legacy);
        RoadSegment only = old.segment(10);
        expect("a file written with the old boolean still loads", only != null);
        if (only != null) {
            expect("and its one-way road is the one direction that boolean could mean",
                    only.direction() == RoadDirection.FORWARD);
        }
    }

    // ----------------------------------------------------------------- helpers

    /** One road segment between two new nodes, which is what the editor writes for one click pair. */
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

    private static void expect(String what, boolean condition) {
        checks++;
        if (!condition) {
            failures++;
        }
        System.out.println((condition ? "  ok   " : "  FAIL ") + what);
    }
}
