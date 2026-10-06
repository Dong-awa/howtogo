import bili.dongsz.howtogo.road.RoadClass;
import bili.dongsz.howtogo.road.RoadNetwork;
import bili.dongsz.howtogo.road.RoadNode;
import bili.dongsz.howtogo.road.RoadSegment;
import bili.dongsz.howtogo.route.RoadRouter;
import bili.dongsz.howtogo.route.Route;
import bili.dongsz.howtogo.route.RoutePreference;
import bili.dongsz.howtogo.route.RoutePreferences;
import bili.dongsz.howtogo.route.TravelMode;

import java.util.List;
import java.util.Set;

/**
 * The routing preference changes which route is chosen, and nothing else.
 *
 * <h2>The two things this holds</h2>
 * <b>It has to do something.</b> "Prefer major roads" used to be a single penalty on footpaths, which
 * a car may not travel: driving's two classes were left at the same pace, so a highway and a road
 * cost exactly the same and the switch changed no route a driver could ever be offered. Worse, the
 * shortest-distance metric returned a bare length and dropped the penalty entirely, so the same
 * switch meant one thing under "fastest" and nothing at all under "shortest". Both are checked here,
 * on a network where a direct road and a slightly longer highway join the same two places: the switch
 * has to move the route, and it has to move it in either metric.
 *
 * <p><b>It must not do anything else.</b> A taste decides which of two routes wins; it must not change
 * what a route costs in the units the player reads. The preference used to be folded into the pace,
 * so the search minimised a weighted speed while the panel's estimate was built from the real one --
 * they agreed only because the weighted value happened never to reach the route's own legs. The check
 * works the estimate out again from the route's drawn geometry and the mode's real paces and holds the
 * two together, for a policy with the switch off and on and in both metrics, so a return of that
 * mistake is caught rather than believed.
 */
public final class RoutePreferenceCheck {

    private static int checks;
    private static int failures;

    private RoutePreferenceCheck() {
    }

    public static void main(String[] args) {
        int[] result = run();
        System.exit(result[1] == 0 ? 0 : 1);
    }

    public static int[] run() {
        System.out.println("== the routing preference steers the route and nothing else ==");

        // Two ways between the same two places: a road straight along z=0, and a highway bowing out
        // to z=30 and back. The highway is the longer of the two, so a router with no taste takes the
        // road -- which is what makes the switch's effect visible.
        RoadNetwork both = new RoadNetwork();
        addRoad(both, RoadClass.ROAD, 0, 0, 300, 0);
        addRoad(both, RoadClass.ROAD, 300, 0, 600, 0);
        addRoad(both, RoadClass.HIGHWAY, 0, 0, 300, 30);
        addRoad(both, RoadClass.HIGHWAY, 300, 30, 600, 0);

        // The same journey with no highway to prefer, so the switch has nothing to choose between and
        // must leave the route exactly where it was.
        RoadNetwork roadOnly = new RoadNetwork();
        addRoad(roadOnly, RoadClass.ROAD, 0, 0, 600, 0);

        for (RoutePreference metric : RoutePreference.values()) {
            String name = metric.id();
            Route plain = plan(both, metric, false);
            Route preferred = plan(both, metric, true);

            record(name + ": a route is planned either way",
                    plain.isPresent() && preferred.isPresent(),
                    "plain " + present(plain) + ", preferred " + present(preferred));

            record(name + ": preferring major roads moves the route",
                    plain.isPresent() && preferred.isPresent() && differs(plain, preferred),
                    plain.isPresent() && preferred.isPresent()
                            ? Math.round(plain.totalLength()) + " blocks against "
                                    + Math.round(preferred.totalLength())
                            : "one of them is missing");

            record(name + ": preferring major roads finds the highway",
                    preferred.isPresent() && highwayBlocks(both, preferred) > 100,
                    preferred.isPresent() ? Math.round(highwayBlocks(both, preferred))
                            + " blocks over a highway" : "no route");

            record(name + ": the direct road wins when nothing is preferred",
                    plain.isPresent() && highwayBlocks(both, plain) < 1,
                    plain.isPresent() ? Math.round(highwayBlocks(both, plain))
                            + " blocks over a highway" : "no route");

            // The estimate, worked out again from the route as drawn.
            record(name + ": the estimate is the route's own geometry at the mode's real pace (off)",
                    plain.isPresent() && sameEstimate(plain),
                    plain.isPresent() ? Math.round(plain.estimatedSeconds()) + " against "
                            + Math.round(geometrySeconds(plain)) : "no route");
            record(name + ": and the same with the preference on",
                    preferred.isPresent() && sameEstimate(preferred),
                    preferred.isPresent() ? Math.round(preferred.estimatedSeconds()) + " against "
                            + Math.round(geometrySeconds(preferred)) : "no route");
        }

        Route onlyPlain = plan(roadOnly, RoutePreference.FASTEST_TIME, false);
        Route onlyPreferred = plan(roadOnly, RoutePreference.FASTEST_TIME, true);
        record("with no highway to prefer the route does not move",
                onlyPlain.isPresent() && onlyPreferred.isPresent()
                        && !differs(onlyPlain, onlyPreferred),
                onlyPlain.isPresent() && onlyPreferred.isPresent()
                        ? Math.round(onlyPlain.totalLength()) + " against "
                                + Math.round(onlyPreferred.totalLength())
                        : "one of them is missing");

        // Walking, where the class this used to penalise is one the mode may actually use. A footpath
        // straight there against a longer road: the switch has to choose the road, and -- the point
        // of this half -- the estimate must still be the footpath's real walking pace, which is the
        // case the old fold-into-the-pace arithmetic got wrong, because the weighted pace was what
        // the estimate was built from.
        RoadNetwork walk = new RoadNetwork();
        addRoad(walk, RoadClass.PATH, 0, 0, 400, 0);
        addRoad(walk, RoadClass.ROAD, 0, 0, 200, 40);
        addRoad(walk, RoadClass.ROAD, 200, 40, 400, 0);
        Route walkPlain = RoadRouter.findRoute(walk, 0, 0, 400, 0, "probe", TravelMode.WALK,
                new RoutePreferences(RoutePreference.FASTEST_TIME, Set.of(), false));
        Route walkPreferred = RoadRouter.findRoute(walk, 0, 0, 400, 0, "probe", TravelMode.WALK,
                new RoutePreferences(RoutePreference.FASTEST_TIME, Set.of(), true));
        // A walker's own pace already makes the road the better route here, switch or no switch: the
        // footpath is slower per block and the road is not much longer. So this half is not about the
        // route moving -- it is about the estimate, which is where the old arithmetic went wrong even
        // once the route was right.
        record("walking: the switch leaves a footpath out of the route either way",
                walkPlain.isPresent() && walkPreferred.isPresent()
                        && pathBlocks(walkPlain) < 1 && pathBlocks(walkPreferred) < 1,
                "plain " + (walkPlain.isPresent() ? Math.round(pathBlocks(walkPlain)) : -1)
                        + " blocks on a footpath, preferred "
                        + (walkPreferred.isPresent() ? Math.round(pathBlocks(walkPreferred)) : -1));
        record("walking: the estimate is the route's own geometry at the walker's real paces",
                walkPlain.isPresent()
                        && Math.abs(walkPlain.estimatedSeconds() - walkGeometrySeconds(walkPlain))
                                < 1.0E-6,
                walkPlain.isPresent() ? Math.round(walkPlain.estimatedSeconds()) + " against "
                        + Math.round(walkGeometrySeconds(walkPlain)) : "no route");
        record("walking: and the same with the preference on",
                walkPreferred.isPresent()
                        && Math.abs(walkPreferred.estimatedSeconds()
                                - walkGeometrySeconds(walkPreferred)) < 1.0E-6,
                walkPreferred.isPresent() ? Math.round(walkPreferred.estimatedSeconds())
                        + " against " + Math.round(walkGeometrySeconds(walkPreferred))
                        : "no route");

        System.out.println(failures == 0
                ? "route preference ok (" + checks + " checks)"
                : "route preference FAILED: " + failures + " of " + checks + " checks");
        return new int[]{checks, failures};
    }

    /** Blocks of the walk drawn along the footpath, which is the straight line at z=0. */
    private static double pathBlocks(Route route) {
        List<double[]> points = route.points();
        double on = 0;
        for (int i = 1; i < points.size(); i++) {
            double[] a = points.get(i - 1);
            double[] b = points.get(i);
            if (Math.abs((a[1] + b[1]) / 2) < 1.0) {
                on += Math.hypot(b[0] - a[0], b[1] - a[1]);
            }
        }
        return on;
    }

    /** The walk's estimate worked out from its drawn geometry and the walker's real paces. */
    private static double walkGeometrySeconds(Route route) {
        List<double[]> points = route.points();
        double seconds = 0;
        for (int i = 1; i < points.size(); i++) {
            double[] a = points.get(i - 1);
            double[] b = points.get(i);
            double length = Math.hypot(b[0] - a[0], b[1] - a[1]);
            RoadClass kind = Math.abs((a[1] + b[1]) / 2) < 1.0 ? RoadClass.PATH : RoadClass.ROAD;
            seconds += length / Math.max(0.05, TravelMode.WALK.speedOn(kind));
        }
        return seconds;
    }

    private static Route plan(RoadNetwork net, RoutePreference metric, boolean preferMajor) {
        return RoadRouter.findRoute(net, 0, 0, 600, 0, "probe", TravelMode.DRIVE,
                new RoutePreferences(metric, Set.of(), preferMajor));
    }

    private static String present(Route route) {
        return route.isPresent() ? Math.round(route.totalLength()) + " blocks" : "none";
    }

    /** Whether two planned routes are drawn differently, which is what a preference changing means. */
    private static boolean differs(Route a, Route b) {
        List<double[]> first = a.points();
        List<double[]> second = b.points();
        if (first.size() != second.size()) {
            return true;
        }
        for (int i = 0; i < first.size(); i++) {
            if (Math.hypot(first.get(i)[0] - second.get(i)[0],
                    first.get(i)[1] - second.get(i)[1]) > 1.0E-6) {
                return true;
            }
        }
        return false;
    }

    /** Blocks of the route whose drawn midpoint lies on a highway segment. */
    private static double highwayBlocks(RoadNetwork net, Route route) {
        List<double[]> points = route.points();
        double on = 0;
        for (int i = 1; i < points.size(); i++) {
            double[] a = points.get(i - 1);
            double[] b = points.get(i);
            double mx = (a[0] + b[0]) / 2;
            double mz = (a[1] + b[1]) / 2;
            if (nearHighway(net, mx, mz)) {
                on += Math.hypot(b[0] - a[0], b[1] - a[1]);
            }
        }
        return on;
    }

    private static boolean nearHighway(RoadNetwork net, double x, double z) {
        for (RoadSegment segment : net.segments()) {
            if (segment.roadClass() != RoadClass.HIGHWAY) {
                continue;
            }
            for (int i = 1; i < segment.vertexCount(); i++) {
                if (distanceToEdge(x, z, segment.x(i - 1), segment.z(i - 1), segment.x(i),
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

    /**
     * Whether the route's estimate is its own geometry at the mode's real paces.
     *
     * <p>Every step is timed at the pace of the class under it, found by asking which of this
     * network's segments the step lies on -- never by asking the router what it thought the pace was,
     * which would only confirm the router's own arithmetic.
     */
    private static boolean sameEstimate(Route route) {
        return Math.abs(route.estimatedSeconds() - geometrySeconds(route)) < 1.0E-6;
    }

    private static double geometrySeconds(Route route) {
        List<double[]> points = route.points();
        double seconds = 0;
        for (int i = 1; i < points.size(); i++) {
            double[] a = points.get(i - 1);
            double[] b = points.get(i);
            double length = Math.hypot(b[0] - a[0], b[1] - a[1]);
            seconds += length / Math.max(0.05, TravelMode.DRIVE.speedOn(classUnder(a, b)));
        }
        return seconds;
    }

    /** The class under a step of this check's own two-road network. */
    private static RoadClass classUnder(double[] a, double[] b) {
        double mx = (a[0] + b[0]) / 2;
        double mz = (a[1] + b[1]) / 2;
        // The highway is the bow out to z=30; the road is the straight line at z=0.
        return Math.abs(mz) > 1.0 ? RoadClass.HIGHWAY : RoadClass.ROAD;
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
