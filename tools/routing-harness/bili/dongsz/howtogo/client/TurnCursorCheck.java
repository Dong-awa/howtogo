package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.road.RoadClass;
import bili.dongsz.howtogo.road.RoadNetwork;
import bili.dongsz.howtogo.road.RoadNode;
import bili.dongsz.howtogo.road.RoadSegment;
import bili.dongsz.howtogo.route.RoadRouter;
import bili.dongsz.howtogo.route.Route;
import bili.dongsz.howtogo.route.RoutePreferences;
import bili.dongsz.howtogo.route.TravelMode;

import java.util.List;

/**
 * The turn the guidance is on, walked the way a player drives it.
 *
 * <h2>The reading this is about</h2>
 * Being level with a junction is not the same as having taken it, and only the player's heading says
 * which they did. So the verdict is made once, remembered, and then answered from memory -- and the
 * remembering is what was wrong: one junction was stored, the walk over the route always begins at
 * its first junction, and an early junction that had been given up on wrote its own distance over
 * the latch of a later one the player had genuinely turned at.
 *
 * <p>What a player saw: at a junction they turned at -- or a little after it, once the new road
 * bends -- the instruction went back to the turn they had already made, at zero distance, so it was
 * shown and spoken as "now"; and because the walk restarts at the first junction of the route, the
 * junction they were actually approaching was skipped over in the same step. The turn that came back
 * is one onto the road behind them, and the one that was skipped is the next real decision, so this
 * is exactly the report of a turn in another direction, a U-turn, and being sent back down the road
 * just travelled.
 *
 * <h2>What is checked</h2>
 * The guidance is walked with a heading that follows the road being travelled, which is what a
 * driver's does, and two things must hold for the whole trip:
 *
 * <ul>
 *   <li>the junction the readout is on never moves backwards -- a junction the player has been
 *       carried past on the route does not come back;</li>
 *   <li>no junction is jumped over -- the junction shown moves to the next one and no further, so
 *       the instruction is always about the next decision.</li>
 * </ul>
 *
 * <p>And the other half of the same rule, which must not be lost with it: a junction the player
 * drives straight past without turning stays the current instruction until they are well past it, and
 * is given up on only at {@link TurnCursor#TURN_GIVE_UP_DISTANCE}. Both halves are here because a
 * frontier that simply consumed every junction the player reached would pass the first two and be
 * wrong about the third -- it would tell a driver who missed their turn to carry on as though the
 * turn had been taken.
 */
public final class TurnCursorCheck {

    private static int checks;
    private static int failures;

    private TurnCursorCheck() {
    }

    public static void main(String[] args) {
        int[] result = run();
        System.exit(result[1] == 0 ? 0 : 1);
    }

    public static int[] run() {
        System.out.println("== the turn the guidance is on ==");
        scenarioJunctionsBehindStayTaken();
        scenarioJunctionDrivenPastIsStillShown();
        scenarioReplanningForgetsTheVerdicts();

        System.out.println(failures == 0
                ? "turn cursor ok (" + checks + " checks)"
                : "turn cursor FAILED: " + failures + " of " + checks + " checks");
        return new int[]{checks, failures};
    }

    /**
     * The whole walk: three turns, driven properly, with the instruction following them in order.
     *
     * <p>The walk ends at the destination rather than the last junction, which is what the bug needs:
     * it fires while the player is driving on down the new road, a little past the junction they
     * turned at, when the third turn stops being ahead by distance and the junctions behind them are
     * asked about again.
     */
    private static void scenarioJunctionsBehindStayTaken() {
        Route route = zigZag();
        if (route == null) {
            return;
        }
        expect("the route turns three times (" + route.maneuvers().size() + " manoeuvres)",
                route.maneuvers().size() == 3);
        if (route.maneuvers().size() != 3) {
            return;
        }
        Walk walk = walk(route, new TurnCursor());
        expect("the instruction never goes back to a junction already taken (" + walk.detail() + ")",
                walk.backwards() == 0);
        expect("and no junction is jumped over (" + walk.detail() + ")", walk.jumps() == 0);
        expect("and all three turns were shown, in order (" + walk.detail() + ")",
                walk.shown() == 3);
    }

    /**
     * The player drives straight past the turn without taking it, which is what the verdict is for.
     *
     * <p>Neither of the two ways a junction is taken applies here: the heading is the road they are
     * staying on, and they are inside the give-up distance. So the turn has to remain the current
     * instruction, at zero, which is what the readout and the voice show as "now" -- and then be
     * given up on once they really are past it, so the instruction moves on rather than pointing back
     * down the road behind them forever.
     */
    private static void scenarioJunctionDrivenPastIsStillShown() {
        Route route = zigZag();
        if (route == null) {
            return;
        }
        Route.Maneuver first = route.maneuvers().get(0);
        TurnCursor cursor = new TurnCursor();
        // Twenty blocks past the junction, still travelling the road the route arrived on: the turn
        // is behind by distance, has not been taken, and is well inside the give-up distance.
        int index = cursor.nextIndex(route, first.distanceFromStart() + 20, 0, first.junctionX() + 20,
                first.junctionZ());
        expect("a turn the player drove straight past is still the instruction (" + index + ")",
                index == 0);
        // And travel on: seventy blocks past it, which is beyond the give-up distance, so the call is
        // dropped rather than pointing back down the road behind them for the rest of the trip.
        index = cursor.nextIndex(route, first.distanceFromStart(), 0, first.junctionX() + 70,
                first.junctionZ());
        expect("and it is given up on once the player is well past it (" + index + ")", index == 1);
    }

    /**
     * A new plan renumbers every junction, so a verdict about the old ones must not survive it.
     *
     * <p>Two readings a junction apart in heading: one along the road the turn enters, which is the
     * verdict being made, and one along the road it arrives on, which is the same junction judged
     * afresh. With the verdict remembered the second reading is answered from memory; once the route
     * has been re-planned there is nothing to answer from and the junction is its own question
     * again, which is what keeps a new plan from inheriting the old one's taken turns.
     */
    private static void scenarioReplanningForgetsTheVerdicts() {
        Route route = zigZag();
        if (route == null) {
            return;
        }
        Route.Maneuver first = route.maneuvers().get(0);
        TurnCursor cursor = new TurnCursor();
        int turned = cursor.nextIndex(route, first.distanceFromStart(), 90, first.junctionX(),
                first.junctionZ());
        expect("turning at a junction moves the instruction on (" + turned + ")", turned == 1);
        int remembered = cursor.nextIndex(route, first.distanceFromStart(), 0, first.junctionX() + 4,
                first.junctionZ());
        expect("and a wandering heading does not bring it back (" + remembered + ")", remembered == 1);
        cursor.reset();
        int again = cursor.nextIndex(route, first.distanceFromStart(), 0, first.junctionX() + 4,
                first.junctionZ());
        expect("while a re-planned route asks about it afresh rather than remembering a junction "
                + "that belongs to the old numbering (" + again + ")", again == 0);
    }

    // ------------------------------------------------------------------ fixture

    /**
     * A route with three turns: two of them a long way apart, the third close behind the second,
     * with a bend between the second and the third.
     *
     * <p>Every ingredient of the reported bug is in that shape, and all three turns are needed:
     *
     * <ul>
     *   <li>the third turn is what the guidance has moved on to, so there is a later junction for the
     *       instruction to fall back from -- with only two, the junction that comes back is the one
     *       already being shown, which is invisible;</li>
     *   <li>the first turn is more than {@link TurnCursor#TURN_GIVE_UP_DISTANCE} behind by the time
     *       the player is at the third, so it is given up on -- and with one remembered junction its
     *       distance was written over the second's verdict in that same tick;</li>
     *   <li>the second turn is inside that distance, so re-judging it says "not taken" -- unless the
     *       heading happens to point down the road it entered, which the bend is there to prevent:
     *       the player is travelling north by then, having left that junction heading west.</li>
     * </ul>
     *
     * <pre>
     *  (30,-20) stub                (10,40) stub
     *      |                            |
     *      +----+ (30,0)                |          &lt;- bend at (10,100) is inside one road
     *           |                       |
     *           | 100             (10,100)+
     *           |                       |
     *           |                       | 30
     *           |                       |
     *      (30,100)+------+ (50,100) stub     &lt;- second turn, and the road west out of it
     *                          (10,70)+----------------+ (-40,70) destination
     * </pre>
     */
    private static Route zigZag() {
        RoadNetwork net = new RoadNetwork();
        // The route itself. It is cut at each turn because a turn is only a manoeuvre at a junction
        // the road forks at, and at a bend inside one road it is not one -- which is exactly the
        // difference between the second turn and the bend after it.
        //
        // Laid out so that no leg crosses another: a route that passes over itself puts two opposite
        // readings of "where am I along it" at one point, which is a different question from this
        // one and would make the walk below ambiguous rather than wrong.
        addRoad(net, RoadClass.ROAD, "A", 0, 0, 30, 0);
        addRoad(net, RoadClass.ROAD, "B", 30, 0, 30, 100);
        addRoad(net, RoadClass.ROAD, "C", 30, 100, 10, 100, 10, 70);
        addRoad(net, RoadClass.ROAD, "D", 10, 70, -40, 70);
        // A side road at each turn, so every one of those nodes is a fork. Their own class and name
        // are what the route does not use; all they contribute is the degree that splits the chain.
        addRoad(net, RoadClass.PATH, "side", 30, 0, 30, -20);
        addRoad(net, RoadClass.PATH, "side", 30, 100, 50, 100);
        addRoad(net, RoadClass.PATH, "side", 10, 70, 10, 40);

        Route route = RoadRouter.findRoute(net, 0, 0, -40, 70, "west", TravelMode.DRIVE,
                RoutePreferences.DEFAULTS);
        expect("a route is planned through all three turns", route.isPresent());
        if (!route.isPresent()) {
            return null;
        }
        return route;
    }

    // ------------------------------------------------------------------ walking

    /** How a trip read: which junction was shown, and whether the order of them ever broke. */
    private record Walk(int backwards, int jumps, int shown, String detail) {
    }

    /**
     * Drives the route from end to end and reports how the instruction moved.
     *
     * <p>The heading is the direction of the piece of route being travelled, which is what a driver
     * looking where they are going has: it comes round to the new road through the corner rather than
     * turning on the spot, and it follows a bend as the route does. The position is on the line, so
     * the projection is the point of the route being passed and nothing else.
     */
    private static Walk walk(Route route, TurnCursor cursor) {
        List<double[]> points = route.points();
        int highest = -1;
        int backwards = 0;
        int jumps = 0;
        boolean[] displayed = new boolean[route.maneuvers().size()];
        String detail = "none";
        for (int i = 1; i < points.size(); i++) {
            double[] a = points.get(i - 1);
            double[] b = points.get(i);
            double length = Math.hypot(b[0] - a[0], b[1] - a[1]);
            if (length < 1.0E-6) {
                continue;
            }
            double heading = Math.toDegrees(Math.atan2(b[1] - a[1], b[0] - a[0]));
            int samples = Math.max(1, (int) Math.ceil(length / 0.5));
            for (int s = 0; s <= samples; s++) {
                double t = s / (double) samples;
                double x = a[0] + (b[0] - a[0]) * t;
                double z = a[1] + (b[1] - a[1]) * t;
                double travelled = Math.max(0, route.totalLength() - route.remainingLength(x, z));
                int index = cursor.nextIndex(route, travelled, heading, x, z);
                if (index < 0) {
                    continue;
                }
                displayed[index] = true;
                if (index < highest) {
                    if (backwards == 0) {
                        detail = "showed M" + index + " after M" + highest + " at ("
                                + Math.round(x) + "," + Math.round(z) + ")";
                    }
                    backwards++;
                } else if (highest >= 0 && index > highest + 1) {
                    if (jumps == 0) {
                        detail = "went from M" + highest + " to M" + index + " at ("
                                + Math.round(x) + "," + Math.round(z) + ")";
                    }
                    jumps++;
                }
                highest = Math.max(highest, index);
            }
        }
        int shown = 0;
        for (boolean seen : displayed) {
            if (seen) {
                shown++;
            }
        }
        return new Walk(backwards, jumps, shown, detail);
    }

    // ------------------------------------------------------------------ fixture building

    private static void addRoad(RoadNetwork net, RoadClass roadClass, String name, int... xz) {
        RoadSegment segment = net.newSegment(roadClass, 64, xz.length / 2);
        for (int i = 0; i < xz.length; i += 2) {
            segment.addVertex(xz[i], xz[i + 1]);
        }
        RoadNode from = nodeAt(net, xz[0], xz[1]);
        RoadNode to = nodeAt(net, xz[xz.length - 2], xz[xz.length - 1]);
        segment.setFromNode(from.id());
        segment.setToNode(to.id());
        segment.setName(name);
        net.addSegment(segment);
    }

    private static RoadNode nodeAt(RoadNetwork net, int x, int z) {
        RoadNode existing = net.nearestNode(x, z, 0.0);
        return existing != null ? existing : net.addNode(x, 64, z, RoadNode.Type.JUNCTION, null);
    }

    private static void expect(String what, boolean ok) {
        checks++;
        if (!ok) {
            failures++;
        }
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
    }
}
