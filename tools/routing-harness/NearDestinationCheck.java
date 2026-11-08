import bili.dongsz.howtogo.road.RoadClass;
import bili.dongsz.howtogo.road.RoadNetwork;
import bili.dongsz.howtogo.road.RoadNode;
import bili.dongsz.howtogo.road.RoadSegment;
import bili.dongsz.howtogo.route.LinePlanner;
import bili.dongsz.howtogo.route.RideRoads;
import bili.dongsz.howtogo.route.RoadRouter;
import bili.dongsz.howtogo.route.Route;
import bili.dongsz.howtogo.route.RouteAlternatives;
import bili.dongsz.howtogo.route.RouteOption;
import bili.dongsz.howtogo.route.RoutePreferences;
import bili.dongsz.howtogo.route.RouteStrategy;
import bili.dongsz.howtogo.route.TransitPlanner;
import bili.dongsz.howtogo.route.TravelMode;
import bili.dongsz.howtogo.route.Trip;
import bili.dongsz.howtogo.transit.LineStop;
import bili.dongsz.howtogo.transit.TransitLine;

import java.util.ArrayList;
import java.util.List;

/**
 * Public transport must not answer a destination that is just down the road with a ride out and back.
 *
 * <h2>The shape that was reported</h2>
 * A player chose a place near where they were standing, and the public transport mode drew a journey
 * that rode a line away from it and rode the same line straight back -- "as if I had not ridden at
 * all". The search is built to refuse a plan that rides nothing, so that a walk cheaper than any line
 * is not handed back as the answer to a line request; when the destination stands by the stop the
 * player is already beside, the cheapest plan that satisfies "must ride something" is exactly that
 * circle, and it was reported as the best journey the network knew.
 *
 * <h2>What is held here</h2>
 * The planner is asked for a destination a few blocks from the player, off a line whose two stops
 * stand close together, and must answer that there is no ride -- an empty journey, not a circle. Then
 * the two ways of being wrongly cautious about that are closed:
 *
 * <ul>
 *   <li>a destination far down the line must still be a ride, because the ride genuinely carries the
 *       player to it; and</li>
 *   <li>a destination near the player that only the line can reach -- a long way round on foot -- must
 *       still be a ride, because the walk is not an answer there. That is what keeps the check from
 *       pinning "near places are walked" instead of "rides that gain nothing are refused".</li>
 * </ul>
 *
 * <p>Nothing here knows about the line a ride is on, and that is deliberate: the fix judges the ride by
 * whether it carried the rider towards the destination, not by whether it was the same line in the
 * opposite direction. The fixture for the reported shape therefore works through line identity not at
 * all -- a one-line network where the two stops are neighbours is enough.
 */
public final class NearDestinationCheck {

    private static int checks;
    private static int failures;

    private NearDestinationCheck() {
    }

    /**
     * Runs the checks.
     *
     * @return the number of checks made and the number that failed, in that order
     */
    public static int[] run() {
        checks = 0;
        failures = 0;
        System.out.println("== a destination near the player is walked, not ridden out and back ==");

        // The reported shape: one line, two stops twenty blocks either side of the player, a road
        // under both so walking the few blocks is a walk the navigation can plan, and the destination
        // ten blocks away. Riding a line here can only mean leaving the neighbourhood and coming back
        // to it: the two stops are the only stops, and either direction away from the destination is
        // as bad as the other.
        RoadNetwork near = new RoadNetwork();
        addRoad(near, RoadClass.ROAD, -60, 0, 60, 0);
        addRoad(near, RoadClass.RAIL, -20, 0, 20, 0);
        List<TransitLine> nearLines = new ArrayList<>();
        nearLines.add(line("round the corner", RoadClass.RAIL, stop("Left", -20, 0),
                stop("Right", 20, 0)));

        Trip circle = TransitPlanner.plan(near, nearLines, 0, 0, 10, 0, "just over there",
                RoutePreferences.DEFAULTS);
        System.out.println("   near destination: journey "
                + (circle.isPresent() ? circle.legs().size() + " legs" : "none"));
        expect("a destination ten blocks away is not answered with a journey", !circle.isPresent());
        expect("so nothing is ridden out and back", !circle.ridesAnything());

        // The upper layer answers that refusal with the walk, so the walk has to be a plan: a fixture
        // whose walk was impossible would leave the player with no answer at all, which is a different
        // bug wearing the same shape.
        Route walk = RoadRouter.findRoute(near, 0, 0, 10, 0, "just over there", TravelMode.WALK,
                RoutePreferences.DEFAULTS);
        expect("and the walk to it is a route the upper layer can answer with", walk.isPresent());
        if (walk.isPresent()) {
            expectNear("which is the ten blocks it is", walk.totalLength(), 10, 1);
        }

        // The client's sentence for that answer is chosen by the planner's own notion of "near": the
        // distance it would ask a player to walk to reach a stop. Both sides of the line are pinned, so
        // that a later change to the number moves the sentence with the plan rather than leaving the
        // two disagreeing.
        expect("a destination this close counts as close enough to walk",
                LinePlanner.closeEnoughToWalk(0, 0, 10, 0));
        expect("a destination past that many blocks does not",
                !LinePlanner.closeEnoughToWalk(0, 0, 2000, 0));

        // The picker's answer to that refusal: one row, labelled as the walk, carrying the walk's own
        // line so the map can draw it and the footer can start it. The mode it is handed over under
        // stays public transport on purpose -- the player asked to go by line and the answer is that
        // there is no line worth taking, not that they chose to walk -- and the row rides nothing.
        List<RouteOption> plans = RouteAlternatives.transit(RideRoads.of(near), nearLines, 0, 0, 10, 0,
                "just over there", RoutePreferences.DEFAULTS);
        expect("the picker is offered a walking row rather than an empty list (rows=" + plans.size()
                + ")", plans.size() == 1);
        if (plans.size() == 1) {
            RouteOption walkRow = plans.get(0);
            expect("and the row is labelled as the walk", walkRow.strategy() == RouteStrategy.WALK);
            expect("its label key is the walk's own", "walk".equals(RouteStrategy.WALK.id()));
            expect("it carries no journey, because it rides nothing", walkRow.trip() == null);
            expect("while the mode the player chose is kept", walkRow.mode() == TravelMode.TRANSIT);
            expect("and it is the ten-block walk itself", Math.abs(walkRow.length() - 10) < 1);
        }

        // The line really carrying the player: two stops at either end of a destination two kilometres
        // down the line. Riding it saves half an hour, and the riding crosses the whole distance.
        RoadNetwork far = new RoadNetwork();
        addRoad(far, RoadClass.ROAD, 0, 0, 2000, 0);
        addRoad(far, RoadClass.RAIL, 0, 0, 2000, 0);
        List<TransitLine> farLines = new ArrayList<>();
        farLines.add(line("the long line", RoadClass.RAIL, stop("Start", 0, 0),
                stop("End", 2000, 0)));
        Trip ride = TransitPlanner.plan(far, farLines, 0, 0, 2000, 0, "the far end",
                RoutePreferences.DEFAULTS);
        expect("a destination two kilometres down the line is still a ride", ride.isPresent());
        if (ride.isPresent()) {
            long rides = ride.legs().stream().filter(leg -> leg.mode() != TravelMode.WALK).count();
            Route onFoot = RoadRouter.findRoute(far, 0, 0, 2000, 0, "the far end", TravelMode.WALK,
                    RoutePreferences.DEFAULTS);
            System.out.println("   far destination: " + Math.round(ride.estimatedSeconds())
                    + " s by line, " + (onFoot.isPresent()
                    ? Math.round(onFoot.estimatedSeconds()) + " s on foot" : "no walk"));
            expect("and it rides the line (rides=" + rides + ")", rides >= 1);
            expect("and walking the whole way is a route too", onFoot.isPresent());
            if (onFoot.isPresent()) {
                expect("which the ride is clearly quicker than",
                        ride.estimatedSeconds() < onFoot.estimatedSeconds());
            }
        }

        // And a journey worth riding is offered as itself: the walk row is the answer to "no line
        // worth taking", not a general fallback that replaces a real one.
        List<RouteOption> farPlans = RouteAlternatives.transit(RideRoads.of(far), farLines, 0, 0,
                2000, 0, "the far end", RoutePreferences.DEFAULTS);
        expect("a journey worth riding is offered as a journey, not as a walk",
                !farPlans.isEmpty()
                        && farPlans.stream().noneMatch(plan -> plan.strategy() == RouteStrategy.WALK));

        // Near, but the line is the only way there: the destination is sixty blocks away and the roads
        // make the walk eight hundred and sixty blocks around. The ride gains the whole distance, so it
        // is kept -- the fix refuses rides that gain nothing, not places that are near.
        RoadNetwork across = new RoadNetwork();
        addRoad(across, RoadClass.ROAD, 0, 0, 0, 400, 60, 400, 60, 0);
        addRoad(across, RoadClass.RAIL, 0, 0, 60, 0);
        List<TransitLine> acrossLines = new ArrayList<>();
        acrossLines.add(line("across the gap", RoadClass.RAIL, stop("This side", 0, 0),
                stop("That side", 60, 0)));
        Trip shortcut = TransitPlanner.plan(across, acrossLines, 0, 0, 60, 0, "across the gap",
                RoutePreferences.DEFAULTS);
        expect("a near destination the line genuinely carries you to is still a ride",
                shortcut.isPresent() && shortcut.ridesAnything());
        if (shortcut.isPresent() && shortcut.ridesAnything()) {
            Trip.Ride rideTaken = shortcut.rides().get(0);
            System.out.println("   near but barred on foot: " + rideTaken.line() + " from "
                    + rideTaken.boardedAt() + " to " + rideTaken.leftAt());
        }

        return report();
    }

    private static int[] report() {
        System.out.println(failures == 0 ? "  near destination ok (" + checks + " checks)"
                : "  near destination FAILED: " + failures + " of " + checks);
        return new int[]{checks, failures};
    }

    /** A rail line calling at the given stops. */
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

    /** One polyline as one segment, sharing a node with whatever is already at either end. */
    private static void addRoad(RoadNetwork network, RoadClass roadClass, int... xz) {
        RoadSegment segment = network.newSegment(roadClass, 64, xz.length / 2);
        for (int i = 0; i < xz.length; i += 2) {
            segment.addVertex(xz[i], xz[i + 1]);
        }
        segment.setFromNode(nodeAt(network, xz[0], xz[1]).id());
        segment.setToNode(nodeAt(network, xz[xz.length - 2], xz[xz.length - 1]).id());
        network.addSegment(segment);
    }

    private static RoadNode nodeAt(RoadNetwork network, int x, int z) {
        RoadNode existing = network.nearestNode(x, z, 0.0);
        return existing != null ? existing : network.addNode(x, 64, z, RoadNode.Type.JUNCTION, null);
    }

    private static void expectNear(String what, double actual, double wanted, double tolerance) {
        boolean ok = Math.abs(actual - wanted) <= tolerance;
        checks++;
        if (!ok) {
            failures++;
        }
        System.out.println((ok ? "  ok   " : "  FAIL ") + what + " (" + Math.round(actual)
                + " vs " + Math.round(wanted) + " +-" + Math.round(tolerance) + ")");
    }

    private static void expect(String what, boolean condition) {
        checks++;
        if (!condition) {
            failures++;
        }
        System.out.println((condition ? "  ok   " : "  FAIL ") + what);
    }
}
