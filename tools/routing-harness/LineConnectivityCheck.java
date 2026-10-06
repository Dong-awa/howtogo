package bili.dongsz.howtogo.route;

import bili.dongsz.howtogo.road.RoadClass;
import bili.dongsz.howtogo.road.RoadDirection;
import bili.dongsz.howtogo.road.RoadNetwork;
import bili.dongsz.howtogo.road.RoadNode;
import bili.dongsz.howtogo.road.RoadSegment;

/**
 * Checks what the line editor means by "not connected".
 *
 * <p>The editor used to read "the router came back with no route" as "the two stops are not connected",
 * and the two are not the same claim. A route is refused for a connector longer than the mode allows,
 * for a one-way facing the way the journey has to go, and for endpoints the fallback declines to reach
 * -- none of which says anything about whether the line runs there. The cost was a red stop on a line
 * that works, which is the one thing a readout like that must never say; see
 * {@link RoadRouter#connection}.
 *
 * <p>What is pinned here is the difference itself, case by case: a stop two hundred blocks off its own
 * railway is connected even though no ride can be planned to it, a genuine gap between two rails is
 * not, a stop with no road of the line's kind near it is neither (and so is left unmarked), and a
 * one-way street is still caught in the direction it forbids.
 */
public final class LineConnectivityCheck {

    private static int checks;
    private static int failures;

    private LineConnectivityCheck() {
    }

    /**
     * Runs the checks.
     *
     * @return the number of checks made and the number that failed, in that order
     */
    public static int[] run() {
        checks = 0;
        failures = 0;
        System.out.println("== what a line editor calls not connected ==");

        RoutePreferences rail = LinePlanner.ridePreferences(RoadClass.RAIL, RoutePreferences.DEFAULTS);

        // Two stops on one unbroken piece of rail: connected, and a ride between them can be planned as
        // well, so nothing about the pair is remarkable.
        RoadNetwork one = rail(0, 0, 400, 0);
        expect("two stops on one unbroken rail are connected",
                RoadRouter.Connection.CONNECTED, judge(one, 100, 0, 300, 0, rail));

        // The false alarm itself: a stop two hundred blocks off its own railway -- a large station, or
        // several platforms merged into one place -- is beyond the mode's 64 block connector cap, so no
        // ride to it can be planned, while the railway between the two stops is one unbroken stretch.
        // "No route" is not "not connected", and this is the case that has to stay unmarked.
        RoadNetwork platform = rail(0, 0, 400, 0);
        expect("a stop past the connector cap but on the line's own railway is still connected",
                RoadRouter.Connection.CONNECTED, judge(platform, 200, 200, 300, 0, rail));
        expect("and it is exactly the pair the ride planner refuses",
                !RoadRouter.findRoute(new RoadRouter.Workspace(platform), 200, 200, 300, 0, "",
                        TravelMode.TRANSIT, rail).isPresent());

        // Two rails drawn sixty blocks short of each other: no ride across, and here that is the truth
        // about the line rather than a fact about connectors. This is the pair that must stay red.
        RoadNetwork split = new RoadNetwork();
        addRail(split, 0, 0, 100, 0);
        addRail(split, 160, 0, 300, 0);
        expect("two rails with a gap between them are not connected",
                RoadRouter.Connection.SEPARATE, judge(split, 50, 0, 200, 0, rail));

        // A stop with no road of the line's kind anywhere near it is not judged at all: the answer is
        // neither a connection nor an accusation, and the screen says nothing rather than going red.
        RoadNetwork far = rail(0, 0, 400, 0);
        expect("a stop with no rail within judging reach cannot be judged",
                RoadRouter.Connection.UNJUDGED, judge(far, 200, 500, 300, 0, rail));

        // The same for a line whose kind the world has none of: a water line over a railway is not a
        // broken water line, it is a question this network cannot answer.
        RoadNetwork waterless = rail(0, 0, 400, 0);
        expect("a line whose kind the world has no roads of cannot be judged",
                RoadRouter.Connection.UNJUDGED, judge(waterless, 100, 0, 300, 0,
                        LinePlanner.ridePreferences(RoadClass.WATER, RoutePreferences.DEFAULTS)));

        // A stop standing beside more than one piece of road: the dead stub it sits at is the nearest
        // thing to it, and the through line sixty blocks off is the one the journey uses. Judged on the
        // nearest piece alone the pair would read as a disconnection, which is why every piece within a
        // traveller's reach of the stop is offered to the search. Sixty blocks is inside the mode's own
        // connector cap, so this is a piece the ride really could start on.
        RoadNetwork stub = new RoadNetwork();
        addRail(stub, 0, 0, 20, 0);
        addRail(stub, 80, 0, 400, 0);
        expect("a stop beside a dead stub and a through line is judged by the through line",
                RoadRouter.Connection.CONNECTED, judge(stub, 20, 0, 300, 0, rail));

        // A one-way is still a disconnection when it faces the way the journey has to go -- the reading
        // the old route-based test got right, and the reason this is a directed question rather than a
        // connected-components one.
        RoadNetwork forward = oneWayRail(0, 0, 400, 0, RoadDirection.FORWARD);
        expect("a ride along a one-way the way it faces is connected",
                RoadRouter.Connection.CONNECTED, judge(forward, 100, 0, 300, 0, rail));
        RoadNetwork backward = oneWayRail(0, 0, 400, 0, RoadDirection.FORWARD);
        expect("and the same one-way ridden against it is not",
                RoadRouter.Connection.SEPARATE, judge(backward, 300, 0, 100, 0, rail));

        System.out.println(failures == 0 ? "  line connectivity ok (" + checks + " checks)"
                : "  line connectivity FAILED: " + failures + " of " + checks);
        return new int[]{checks, failures};
    }

    /** The verdict the line editor reads for one neighbouring pair of stops. */
    private static RoadRouter.Connection judge(RoadNetwork network, double fromX, double fromZ,
                                               double toX, double toZ, RoutePreferences policy) {
        return RoadRouter.connection(new RoadRouter.Workspace(network), fromX, fromZ, toX, toZ,
                TravelMode.TRANSIT, policy);
    }

    private static RoadNetwork rail(double x0, double z0, double x1, double z1) {
        RoadNetwork network = new RoadNetwork();
        addRail(network, x0, z0, x1, z1);
        return network;
    }

    private static RoadNetwork oneWayRail(double x0, double z0, double x1, double z1,
                                          RoadDirection direction) {
        RoadNetwork network = rail(x0, z0, x1, z1);
        for (RoadSegment segment : network.segmentsSnapshot()) {
            segment.setDirection(direction);
        }
        return network;
    }

    /** One straight piece of rail, wired to a node at each end, as the editor draws one. */
    private static void addRail(RoadNetwork network, double x0, double z0, double x1, double z1) {
        RoadNode from = network.addNode((int) x0, 64, (int) z0, RoadNode.Type.JUNCTION, null);
        RoadNode to = network.addNode((int) x1, 64, (int) z1, RoadNode.Type.JUNCTION, null);
        RoadSegment segment = network.newSegment(RoadClass.RAIL, 64, 2);
        segment.addVertex((int) x0, (int) z0);
        segment.addVertex((int) x1, (int) z1);
        segment.setFromNode(from.id());
        segment.setToNode(to.id());
        network.addSegment(segment);
    }

    private static void expect(String what, RoadRouter.Connection expected,
                               RoadRouter.Connection actual) {
        expect(what + (expected == actual ? "" : " (expected " + expected + ", got " + actual + ")"),
                expected == actual);
    }

    private static void expect(String what, boolean condition) {
        checks++;
        if (!condition) {
            failures++;
        }
        System.out.println((condition ? "  ok   " : "  FAIL ") + what);
    }
}
