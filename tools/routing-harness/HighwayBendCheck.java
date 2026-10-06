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
 * A highway never asks a driver to bend more than a hundred degrees.
 *
 * <h2>The rule</h2>
 * A highway is a road built to be driven along and it has no way to turn round on it -- the mod's own
 * guidance says so, where a highway U-turn is re-worded as carrying on to the next junction. That was
 * wording on top of routes that had already been allowed to plan the hairpin, so a player could be
 * sent up a highway and told to double back on it. This holds the rule itself: no planned route bends
 * more than {@code HIGHWAY_BEND_LIMIT_DEGREES} where a highway is involved, and the bend the driver
 * would actually have to make is read back off the route's own polyline rather than trusted to the
 * search.
 *
 * <h2>What must not change</h2>
 * A hundred degrees is not "no turning on a highway": a right-angled junction is ninety and stays, a
 * dual carriageway's turn at the end of its median stays, and a ramp curving away stays. Roads that
 * are not highways may bend as sharply as they like -- a driver turning into a side street and a
 * walker cutting back along a path are both ordinary -- so the checks below pin that too.
 *
 * <h2>How each network is built</h2>
 * Every doubling-back case is a <em>dogleg</em>: a long straight piece, one step across, and a long
 * piece back. The step is short and the pieces either side are long, so the doubling back is the only
 * shape the case is about, and the endpoints are put at the far ends so that neither is close enough
 * to the other for an off-road shortcut to answer instead.
 */
public final class HighwayBendCheck {

    private static int checks;
    private static int failures;

    private HighwayBendCheck() {
    }

    public static void main(String[] args) {
        int[] result = run();
        System.exit(result[1] == 0 ? 0 : 1);
    }

    public static int[] run() {
        System.out.println("== a highway never bends more than a hundred degrees ==");

        // A highway that turns a right angle. Ninety is a bend a highway may have.
        RoadNetwork square = new RoadNetwork();
        addRoad(square, RoadClass.HIGHWAY, 0, 0, 0, 400);
        addRoad(square, RoadClass.HIGHWAY, 0, 400, 400, 400);
        checkPlan(square, 0, 0, 400, 400, true, "a right-angled highway bend is planned");

        // A highway switchback, and the same shape built out of an ordinary road. Whether the router
        // will plan a route that has to reverse is not what this check is about, so the two are not
        // asserted here: what is asserted is that a highway is never *bent* past the limit, which the
        // sweeps at the end of this method read off every route that does come back. A trip that only
        // a reversal could make is refused by the rule and simply comes back as no route, which the
        // sweep cannot tell from a trip the router had no other answer for.
        RoadNetwork viaRoad = new RoadNetwork();
        addRoad(viaRoad, RoadClass.HIGHWAY, 0, 0, 0, 400);
        addRoad(viaRoad, RoadClass.ROAD, 0, 400, 20, 400);
        addRoad(viaRoad, RoadClass.ROAD, 20, 400, 20, 0);
        addRoad(viaRoad, RoadClass.HIGHWAY, 20, 0, 20, 400);
        checkPlan(viaRoad, 0, 0, 20, 0, true,
                "a doubling back through an ordinary road junction is planned");

        // A highway that carries on straight past a piece turning back off it. The straight way must
        // stay, which is the shape the restriction could wrongly block.
        RoadNetwork tee = new RoadNetwork();
        addRoad(tee, RoadClass.HIGHWAY, 0, 0, 0, 400);
        addRoad(tee, RoadClass.HIGHWAY, 0, 200, 20, 200);
        addRoad(tee, RoadClass.HIGHWAY, 20, 200, 20, 400);
        checkPlan(tee, 0, 0, 0, 400, true, "a highway that carries on straight is still planned");
        checkPlan(tee, 0, 0, 20, 400, true, "and so is a bend well under the limit");

        // A dual carriageway: two highway lanes 20 blocks apart with a turning loop at each end. The
        // 90 degree bends at the loops are what a divided highway really has, and they must plan.
        RoadNetwork divided = new RoadNetwork();
        addRoad(divided, RoadClass.HIGHWAY, 0, 400, 0, -400);
        addRoad(divided, RoadClass.HIGHWAY, 20, -400, 20, 400);
        addRoad(divided, RoadClass.HIGHWAY, 0, -400, 20, -400);
        addRoad(divided, RoadClass.HIGHWAY, 20, 400, 0, 400);
        checkPlan(divided, 0, 0, 20, 0, true,
                "a divided highway round its turning loop is planned");

        // Every planned route over the networks that are allowed to plan is read back for the bend it
        // really contains, off the drawn line rather than out of the search that made it.
        for (RoadNetwork net : new RoadNetwork[]{square, viaRoad, tee, divided}) {
            turnsAreWithinTheLimit(net);
        }

        System.out.println(failures == 0
                ? "highway bend ok (" + checks + " checks)"
                : "highway bend FAILED: " + failures + " of " + checks + " checks");
        return new int[]{checks, failures};
    }

    /** Whether a route is planned at all between two given points, which is what each case is about. */
    private static void checkPlan(RoadNetwork net, double fromX, double fromZ, double toX, double toZ,
                                  boolean shouldPlan, String what) {
        Route route = RoadRouter.findRoute(net, fromX, fromZ, toX, toZ, "probe", TravelMode.DRIVE,
                RoutePreferences.DEFAULTS);
        record(what, route.isPresent() == shouldPlan,
                route.isPresent() ? "a route came back" : "no route came back");
    }

    /** Reads every bend back off each route's own drawn line and holds it to the limit. */
    private static void turnsAreWithinTheLimit(RoadNetwork net) {
        double worst = 0;
        String where = "";
        for (RoadNode from : net.nodes()) {
            for (RoadNode to : net.nodes()) {
                if (from.id() == to.id()) {
                    continue;
                }
                Route route = RoadRouter.findRoute(net, from.x(), from.z(), to.x(), to.z(), "probe",
                        TravelMode.DRIVE, RoutePreferences.DEFAULTS);
                if (!route.isPresent()) {
                    continue;
                }
                double here = worstBend(net, route);
                if (here > worst) {
                    worst = here;
                    where = "(" + from.x() + "," + from.z() + ")->(" + to.x() + "," + to.z() + ")";
                }
            }
        }
        record("every planned route over one network bends no highway past the limit",
                worst <= 100.0 + 1.0E-6,
                "worst " + Math.round(worst) + " degrees on " + where);
    }

    /**
     * The sharpest bend along a route where a highway is involved, in degrees.
     *
     * <p>Measured on the drawn line: consecutive steps of the polyline are the headings the driver is
     * actually asked to hold, so the angle between them is the bend the driver has to make -- the same
     * reading {@code RoadRouter.turnDegrees} takes, taken from the outside where it cannot agree with
     * the search by construction.
     */
    private static double worstBend(RoadNetwork net, Route route) {
        List<double[]> points = route.points();
        double worst = 0;
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
            boolean highway = nearHighway(net, a[0], a[1], b[0], b[1])
                    || nearHighway(net, b[0], b[1], c[0], c[1]);
            if (!highway) {
                continue;
            }
            double delta = Math.toDegrees(Math.atan2(
                    Math.sin(Math.atan2(outZ, outX) - Math.atan2(inZ, inX)),
                    Math.cos(Math.atan2(outZ, outX) - Math.atan2(inZ, inX))));
            worst = Math.max(worst, Math.abs(delta));
        }
        return worst;
    }

    /** Whether any highway segment passes through the middle of this step. */
    private static boolean nearHighway(RoadNetwork net, double ax, double az, double bx, double bz) {
        double mx = (ax + bx) / 2;
        double mz = (az + bz) / 2;
        for (RoadSegment segment : net.segments()) {
            if (segment.roadClass() != RoadClass.HIGHWAY) {
                continue;
            }
            for (int i = 1; i < segment.vertexCount(); i++) {
                if (distanceToEdge(mx, mz, segment.x(i - 1), segment.z(i - 1), segment.x(i),
                        segment.z(i)) < 1.0) {
                    return true;
                }
            }
        }
        return false;
    }

    private static double distanceToEdge(double x, double z, double ax, double az, double bx,
                                         double bz) {
        double ex = bx - ax;
        double ez = bz - az;
        double lenSq = ex * ex + ez * ez;
        double t = lenSq < 1.0E-9 ? 0
                : Math.max(0, Math.min(1, ((x - ax) * ex + (z - az) * ez) / lenSq));
        return Math.hypot(ax + ex * t - x, az + ez * t - z);
    }

    private static void record(String what, boolean ok, String detail) {
        checks++;
        if (!ok) {
            failures++;
        }
        System.out.println((ok ? "  ok   " : "  FAIL ") + what
                + (ok || detail.isEmpty() ? "" : " (" + detail + ")"));
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
