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
 * A route that runs the way the player has to travel, asked of the router directly.
 *
 * <h2>The reading this is about</h2>
 * The guidance calls a wrong-way reading by comparing the direction the player is travelling with the
 * direction the route runs where they are -- {@code Route.bearingAt}. Everything past 135 degrees,
 * held for five ticks, is announced as a U-turn; on a highway that is the "continue to the next
 * junction" line. So a route whose own direction contradicts its own drawn direction is a U-turn
 * call, out loud, to a player driving correctly.
 *
 * <h2>What the drawn direction is</h2>
 * The route is a polyline from the start to the destination, so the way it is travelled is the way it
 * is drawn: from each point to the next. This check walks a planned route the way the player will,
 * sampling every step, and asserts that {@code bearingAt} agrees with the step it is being taken on
 * wherever the route says the player is on it.
 *
 * <p>It is not a check that the line is straight. A route that leaves the player at a right angle to
 * the road and turns onto it is drawn that way and is read that way, which is the honest answer for
 * the first few blocks of any trip. What it refuses is a route that reads as running <em>backwards</em>
 * where it is drawn forwards, because that is the shape the wrong-way call cannot tell from a player
 * who has turned round.
 */
public final class RouteDirectionCheck {

    private static int checks;
    private static int failures;

    private RouteDirectionCheck() {
    }

    public static void main(String[] args) {
        int[] result = run();
        System.exit(result[1] == 0 ? 0 : 1);
    }

    public static int[] run() {
        System.out.println("== a route runs the way it is travelled ==");

        // The plainest highway there is, long enough that a connector across part of it is a real
        // part of the trip rather than a rounding.
        RoadNetwork straight = new RoadNetwork();
        addRoad(straight, RoadClass.HIGHWAY, -500, 0, 500, 0);

        // Both ends in the middle of that one segment.
        check(straight, -200, 0, 200, 0, "two points on one highway");
        check(straight, 200, 0, -200, 0, "the same the other way round");
        // Where the endpoints land on the segment's own nodes, which is what "next to the end of the
        // road" means: the anchor is at a vertex it shares with whatever the road joins.
        check(straight, -500, 0, 200, 0, "from the western end");
        check(straight, 200, 0, -500, 0, "to the western end");
        check(straight, -200, 0, 500, 0, "to the eastern end");
        check(straight, 500, 0, -200, 0, "from the eastern end");
        check(straight, -500, 0, 500, 0, "end to end");
        check(straight, 500, 0, -500, 0, "end to end, back");

        // A highway joined to a road at each end, so the ends are real junctions and not merely the
        // ends of one drawn piece.
        RoadNetwork joined = new RoadNetwork();
        addRoad(joined, RoadClass.HIGHWAY, -500, 0, 500, 0);
        addRoad(joined, RoadClass.ROAD, -500, 0, -500, -200);
        addRoad(joined, RoadClass.ROAD, 500, 0, 500, 200);
        check(joined, -200, 0, 200, 0, "along the highway between two roads");
        check(joined, -500, 0, 200, 0, "from the junction at the western end");
        check(joined, 200, 0, -500, 0, "to the junction at the western end");
        check(joined, 200, 0, 500, 0, "to the junction at the eastern end");

        // A highway split by nodes along it, which is what a drawn highway looks like after the
        // editor has cut it at every side road. The endpoints then land on nodes of their own.
        RoadNetwork cut = new RoadNetwork();
        for (int x = -500; x < 500; x += 100) {
            addRoad(cut, RoadClass.HIGHWAY, x, 0, x + 100, 0);
        }
        addRoad(cut, RoadClass.ROAD, 0, 0, 0, -200);
        check(cut, -150, 0, 150, 0, "across the node in the middle");
        check(cut, -100, 0, 100, 0, "from one node to the next");
        check(cut, 100, 0, -100, 0, "from one node to the next, back");

        // A highway that comes back on itself: two carriageways eight blocks apart with a loop at
        // each end, which is what a divided highway is. The destination behind the player is reached
        // by carrying on round the loop and back down the other carriageway, so the trip doubles back
        // -- and the two carriageways are within each other's on-road tolerance.
        RoadNetwork divided = new RoadNetwork();
        addRoad(divided, RoadClass.HIGHWAY, 0, 800, 0, -800);
        addRoad(divided, RoadClass.HIGHWAY, 20, -800, 20, 800);
        addRoad(divided, RoadClass.HIGHWAY, 0, -800, 20, -800);
        addRoad(divided, RoadClass.HIGHWAY, 20, 800, 0, 800);
        System.out.println("   divided: " + divided.nodes().size() + " nodes, "
                + divided.segments().size() + " segments");
        check(divided, 0, -400, 0, -790, "a destination just behind, on a divided highway");
        check(divided, 0, -790, 0, -400, "and the other way round");
        check(divided, 0, 400, 0, -100, "a long way past, on a divided highway");
        check(divided, 20, -400, 20, -790, "a destination just behind, on the west carriageway");

        System.out.println(failures == 0
                ? "route direction ok (" + checks + " checks)"
                : "route direction FAILED: " + failures + " of " + checks + " checks");
        return new int[]{checks, failures};
    }

    /**
     * Plans the trip and walks it, reporting where the reading contradicts the way it is drawn.
     */
    private static void check(RoadNetwork net, double fromX, double fromZ, double toX, double toZ,
                              String what) {
        Route route = RoadRouter.findRoute(net, fromX, fromZ, toX, toZ, "probe",
                TravelMode.DRIVE, RoutePreferences.DEFAULTS);
        if (!route.isPresent()) {
            record(what + ": a route is planned", false,
                    "no route at all");
            return;
        }
        List<double[]> points = route.points();
        // The step the player takes leaving the start, and the one that ends at the destination: the
        // two ends of the trip are where a connector that runs the wrong way shows up.
        StringBuilder complaint = new StringBuilder();
        double worst = 0;
        for (int i = 1; i < points.size(); i++) {
            double[] a = points.get(i - 1);
            double[] b = points.get(i);
            double step = Math.hypot(b[0] - a[0], b[1] - a[1]);
            if (step < 1.0E-6) {
                continue;
            }
            double travel = Math.toDegrees(Math.atan2(b[1] - a[1], b[0] - a[0]));
            for (double t = 0.0; t <= 1.0; t += 0.1) {
                double x = a[0] + (b[0] - a[0]) * t;
                double z = a[1] + (b[1] - a[1]) * t;
                if (!route.isOnRoute(x, z)) {
                    continue;
                }
                double along = route.bearingAt(x, z);
                if (Double.isNaN(along)) {
                    continue;
                }
                double gap = bearingGap(travel, along);
                if (gap > worst) {
                    worst = gap;
                    complaint.setLength(0);
                    complaint.append("step ").append(i).append(" of ").append(points.size() - 1)
                            .append(" drawn (").append(Math.round(a[0])).append(",")
                            .append(Math.round(a[1])).append(")->(")
                            .append(Math.round(b[0])).append(",").append(Math.round(b[1]))
                            .append(") is travelled at ").append(Math.round(travel))
                            .append(" degrees but read as ").append(Math.round(along));
                }
            }
        }
        record(what + ": the route never reads as running backwards", worst < 135.0,
                complaint.toString());
    }

    private static void record(String what, boolean ok, String detail) {
        checks++;
        if (!ok) {
            failures++;
        }
        System.out.println((ok ? "  ok   " : "  FAIL ") + what
                + (ok || detail.isEmpty() ? "" : " (" + detail + ")"));
    }

    private static double bearingGap(double from, double to) {
        double difference = Math.abs(from - to) % 360.0;
        return difference > 180.0 ? 360.0 - difference : difference;
    }

    private static void addRoad(RoadNetwork net, RoadClass roadClass, int... xz) {
        RoadSegment segment = net.newSegment(roadClass, 64, xz.length / 2);
        for (int i = 0; i < xz.length; i += 2) {
            segment.addVertex(xz[i], xz[i + 1]);
        }
        RoadNode from = nodeAt(net, xz[0], xz[1]);
        RoadNode to = nodeAt(net, xz[xz.length - 2], xz[xz.length - 1]);
        segment.setFromNode(from.id());
        segment.setToNode(to.id());
        net.addSegment(segment);
    }

    private static RoadNode nodeAt(RoadNetwork net, int x, int z) {
        RoadNode existing = net.nearestNode(x, z, 0.0);
        return existing != null ? existing : net.addNode(x, 64, z, RoadNode.Type.JUNCTION, null);
    }
}
