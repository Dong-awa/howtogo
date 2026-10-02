package bili.dongsz.howtogo.route;

import bili.dongsz.howtogo.road.RoadClass;
import bili.dongsz.howtogo.road.RoadNetwork;
import bili.dongsz.howtogo.road.RoadNode;
import bili.dongsz.howtogo.road.RoadSegment;
import bili.dongsz.howtogo.transit.TransitLine;

/**
 * Checks which network a plan runs a line on.
 *
 * <p>In the {@code route} package because {@link RideRoads#forLine} is package-private: which roads a
 * line rides is a decision the planner makes, so the seam is internal to the package and cannot be
 * reached from outside it. It lives with the harness and is compiled the same way; nothing in the mod
 * calls it.
 *
 * <p>The property being pinned down is the one that makes a per-line switch real rather than decorative:
 * a line whose marks are off is handed a network that never had them, not the marked network with a
 * filter in front of it. The difference matters because MTR's marks are one shared layer -- every line
 * rides the same rails -- so "do not add the marks for this line" is not something the planner can act
 * on.
 */
public final class RideRoadsCheck {

    private static int checks;
    private static int failures;

    private RideRoadsCheck() {
    }

    /**
     * Runs the checks.
     *
     * @return the number of checks made and the number that failed, in that order
     */
    public static int[] run() {
        checks = 0;
        failures = 0;
        System.out.println("== which roads a line rides ==");

        RoadNetwork marked = twoRoads();
        RoadNetwork plain = twoRoads();
        TransitLine withMarks = new TransitLine("mtr:1", "Rail", RoadClass.RAIL);
        TransitLine withoutMarks = new TransitLine("mtr:2", "Boat", RoadClass.WATER);

        RideRoads roads = RideRoads.of(marked, plain, line -> line == withMarks);
        expect("a line that wants the marks is planned on the marked roads",
                roads.forLine(withMarks) == marked);
        expect("and one that does not is planned on the roads without them",
                roads.forLine(withoutMarks) == plain);
        expect("with nothing to say about a null line, which is the plain answer",
                roads.forLine(null) == plain);

        // The walking legs of a journey are the same either way -- a walk cannot take a rail or a
        // waterway, so the marks are filtered out by the mode before they are looked at -- and taking
        // the marked pair keeps the walking side of a journey out of a decision about riding.
        expect("and a walk is offered the marked roads, which it cannot travel on anyway",
                roads.forWalks() == marked);

        // One network for every line when no line wants the difference: the second copy of the world is
        // only paid for when it is actually asked for.
        RideRoads oneNetwork = RideRoads.of(marked);
        expect("with no line wanting the difference there is only one network",
                oneNetwork.forLine(withMarks) == marked && oneNetwork.forLine(withoutMarks) == marked);

        // A predicate is asked per line and per plan, so it has to be consulted once per call rather
        // than remembered: the player can flip a switch between two plans.
        boolean[] answer = {true};
        RideRoads flipping = RideRoads.of(marked, plain, line -> answer[0]);
        expect("the first plan rides the marks", flipping.forLine(withMarks) == marked);
        answer[0] = false;
        expect("and after the switch is turned off, the next one does not",
                flipping.forLine(withMarks) == plain);

        System.out.println(failures == 0 ? "  ride roads ok (" + checks + " checks)"
                : "  ride roads FAILED: " + failures + " of " + checks);
        return new int[]{checks, failures};
    }

    /** Two networks that are different objects and otherwise alike, so identity is the whole test. */
    private static RoadNetwork twoRoads() {
        RoadNetwork network = new RoadNetwork();
        RoadNode from = network.addNode(0, 64, 0, RoadNode.Type.JUNCTION, null);
        RoadNode to = network.addNode(100, 64, 0, RoadNode.Type.JUNCTION, null);
        RoadSegment segment = network.newSegment(RoadClass.RAIL, 64, 2);
        segment.addVertex(0, 0);
        segment.addVertex(100, 0);
        segment.setFromNode(from.id());
        segment.setToNode(to.id());
        network.addSegment(segment);
        return network;
    }

    private static void expect(String what, boolean condition) {
        checks++;
        if (!condition) {
            failures++;
        }
        System.out.println((condition ? "  ok   " : "  FAIL ") + what);
    }
}
