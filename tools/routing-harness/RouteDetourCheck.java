import bili.dongsz.howtogo.road.RoadClass;
import bili.dongsz.howtogo.road.RoadNetwork;
import bili.dongsz.howtogo.road.RoadNode;
import bili.dongsz.howtogo.road.RoadSegment;
import bili.dongsz.howtogo.route.RoadRouter;
import bili.dongsz.howtogo.route.Route;
import bili.dongsz.howtogo.route.RouteAlternatives;
import bili.dongsz.howtogo.route.RouteOption;
import bili.dongsz.howtogo.route.RoutePreference;
import bili.dongsz.howtogo.route.RoutePreferences;
import bili.dongsz.howtogo.route.RouteStrategy;
import bili.dongsz.howtogo.route.TravelMode;

import java.util.List;
import java.util.Set;

/**
 * A driving trip gets a way round on the plan list, and only a plausible one.
 *
 * <h2>The report</h2>
 * "驾车模式的方案也加一个跟走路一样的绕行" -- a driving trip offered the fastest and the shortest and
 * no third way, while a walking trip of the same shape offered one. Both are planned by the same
 * four questions, so the difference was in the numbers: the way round was admitted by the bound the
 * other metric and the other taste are held to (four fifths slower than the recommendation), and a
 * driver's other road usually trails the main one by more than a walker's other street does. It was
 * also only asked for while the list had a row free, and the fastest and the shortest can be the same
 * street with a different corner -- two rows spent on one way round.
 *
 * <h2>What is held here</h2>
 * <b>It has to appear.</b> The first fixture is that shape in the driving case: the recommendation
 * takes a highway into a long shared road, "shortest distance" takes the road across the same first
 * stretch, and there is a genuine way round the outside. The two first plans are the same way out of
 * town, twenty-five blocks apart at their widest; the way round is twice the recommendation's time --
 * past the bound the other strategies are held to, which is why the check requires it to be slower
 * than that bound rather than merely present. The near-duplicate must not take a row of its own, or
 * the way round would have nowhere to go.
 *
 * <p><b>It must not be absurd.</b> The second fixture is a road with one loop round it four times as
 * long as the road: no way round may be offered. The third is the sharpest case where the admission
 * rule itself, rather than the detour search's own arithmetic, is what refuses a plan: a
 * "shortest distance" trip whose shortest line is a highway and whose only other way is a road loop.
 * The search charges that trip in blocks, so a loop inside the toll's multiple is reachable while its
 * time -- the number the player reads -- is already past the way round's bound. The check requires the
 * loop to be exactly that, and to be left off the list.
 */
public final class RouteDetourCheck {

    private static int checks;
    private static int failures;

    private RouteDetourCheck() {
    }

    public static void main(String[] args) {
        int[] result = run();
        System.exit(result[1] == 0 ? 0 : 1);
    }

    public static int[] run() {
        System.out.println("== a driving trip's way round ==");
        nearIdenticalPlansStillGetAWayRound();
        aWayRoundFourTimesAsSlowIsNotOffered();
        theWayRoundBoundRefusesWhatTheTollWouldAllow();
        System.out.println(failures == 0
                ? "route detour ok (" + checks + " checks)"
                : "route detour FAILED: " + failures + " of " + checks + " checks");
        return new int[]{checks, failures};
    }

    /**
     * The reported shape: the fastest and the shortest are the same way out of town, and the way round
     * is the long way round the outside.
     *
     * <p>The network is a road from the start to a junction and on to the destination, a highway that
     * leaves the start a little wider and joins the same junction, and one long road from the start to
     * the destination round the outside. The highway is quicker than the road it shadows, so the
     * recommendation takes it; the road is a little shorter, so "shortest distance" takes that; the
     * two are the same way round the town, twenty-five blocks apart at their widest. The way round is
     * the outside road, twice the recommendation's time.
     *
     * <p>The destination is a junction, with a short stub beside it, on purpose: a node with two arms
     * of one class is a bend inside one road, not a place where two roads meet (see
     * {@code RoadChains}), so without the stub the outside road and the shared road would be one road
     * and the detour search would toll the way round along with the recommendation. The stub is what
     * makes them two roads, which is what a destination at the end of a street really is.
     */
    private static void nearIdenticalPlansStillGetAWayRound() {
        RoadNetwork net = new RoadNetwork();
        addRoad(net, RoadClass.ROAD, 0, 0, 400, 0);                       // the shortest's way out
        addRoad(net, RoadClass.HIGHWAY, 0, 0, 200, -25, 400, 0);          // the fastest's, a bow wide
        addRoad(net, RoadClass.ROAD, 400, 0, 1800, 0);                    // the shared road
        addRoad(net, RoadClass.ROAD, 0, 0, 0, -800, 1800, -800, 1800, 0); // the way round
        addRoad(net, RoadClass.ROAD, 1800, 0, 1800, 80);                  // so the goal is a junction

        List<RouteOption> plans = RouteAlternatives.roads(net, 0, 0, 1800, 0, "the far side",
                TravelMode.DRIVE, RoutePreferences.DEFAULTS);
        printPlans(plans);
        RouteOption first = plans.isEmpty() ? null : plans.get(0);
        RouteOption alternative = alternative(plans);

        record("a driving trip whose fastest and shortest are the same way out still offers a way round",
                alternative != null, plans.size() + " plan(s)");
        if (alternative == null || first == null) {
            return;
        }
        Route plain = RoadRouter.findRoute(net, 0, 0, 1800, 0, "the far side", TravelMode.DRIVE,
                RoutePreferences.DEFAULTS);
        record("the recommendation is still the first plan, unchanged",
                first.strategy() == RouteStrategy.FASTEST && plain.isPresent()
                        && Math.abs(first.seconds() - plain.estimatedSeconds()) < 1.0E-6,
                first.strategy().id() + ", " + round(first.seconds()) + " s");
        // The room rule's whole job: the near-duplicate is one plan seen twice, so it must not spend a
        // row of its own. Without it the list fills with three rows and the way round is the one
        // squeezed out.
        record("the near-duplicate shortest does not take a row of its own",
                plans.size() == 2, plans.size() + " plan(s)");
        // Past the bound the other strategies are held to: this is the bug, not a courtesy.
        record("the way round is slower than the four-fifths bound the other strategies are held to",
                alternative.seconds() > first.seconds() * 1.8,
                round(alternative.seconds()) + " s against " + round(first.seconds())
                        + " s, a ratio of " + round(alternative.seconds() / first.seconds()));
        record("and it is a different line rather than the same one relabelled",
                alternative.length() > first.length() * 1.5,
                round(alternative.length()) + " blocks against " + round(first.length()));
        record("the plans are still in time order",
                first.seconds() <= alternative.seconds() + 1.0E-6,
                round(first.seconds()) + " s, then " + round(alternative.seconds()) + " s");
    }

    /**
     * A road with one loop round it four times as long: no way round may be offered for it.
     *
     * <p>Four times is not a choice while the road itself is on the list, and the way round's own
     * bound says so. The check pins the refusal where a player sees it -- the list -- so a change to
     * the detour search that starts offering absurd lines fails here whichever of the two rules ends
     * up doing the refusing.
     */
    private static void aWayRoundFourTimesAsSlowIsNotOffered() {
        RoadNetwork net = new RoadNetwork();
        addRoad(net, RoadClass.ROAD, 0, 0, 600, 0);                      // the only sensible road
        addRoad(net, RoadClass.ROAD, 0, 0, 0, -900, 600, -900, 600, 0);  // four times the time

        List<RouteOption> plans = RouteAlternatives.roads(net, 0, 0, 600, 0, "the end of the road",
                TravelMode.DRIVE, RoutePreferences.DEFAULTS);
        printPlans(plans);
        record("a way round four times as slow is not offered",
                alternative(plans) == null && plans.size() == 1, plans.size() + " plan(s)");
        RoadNetwork loopOnly = new RoadNetwork();
        addRoad(loopOnly, RoadClass.ROAD, 0, 0, 0, -900, 600, -900, 600, 0);
        Route loop = RoadRouter.findRoute(loopOnly, 0, 0, 600, 0, "the loop", TravelMode.DRIVE,
                RoutePreferences.DEFAULTS);
        Route direct = plans.isEmpty() ? Route.empty() : plans.get(0).route();
        record("which is fair, since the only other way round takes four times as long",
                loop.isPresent() && direct.isPresent() && ratio(loop, direct) > 3.9,
                loop.isPresent() && direct.isPresent()
                        ? round(ratio(loop, direct)) + " times the trip" : "no route");
    }

    /**
     * The case the way round's own bound exists for: the detour search charges in the metric the
     * player chose, while the bound is in the seconds the player reads.
     *
     * <p>A "shortest distance" trip whose shortest line is a highway, and a road loop round it at 2.4
     * times the highway's length. In blocks the loop is inside the toll's 2.5, so the search finds it;
     * in time, at the road's slower pace, it is more than three times the highway. The bound refuses
     * it, which is the refusal this fixture is about -- the toll alone would have let it through.
     */
    private static void theWayRoundBoundRefusesWhatTheTollWouldAllow() {
        RoadNetwork net = new RoadNetwork();
        addRoad(net, RoadClass.HIGHWAY, 0, 0, 1000, 0);                     // shortest and quickest
        addRoad(net, RoadClass.ROAD, 0, 0, 0, -700, 1000, -700, 1000, 0);   // 2.4 times as far

        List<RouteOption> plans = RouteAlternatives.roads(net, 0, 0, 1000, 0, "the far side",
                TravelMode.DRIVE,
                new RoutePreferences(RoutePreference.SHORTEST_DISTANCE, Set.of(), false));
        printPlans(plans);
        RoadNetwork loopOnly = new RoadNetwork();
        addRoad(loopOnly, RoadClass.ROAD, 0, 0, 0, -700, 1000, -700, 1000, 0);
        Route loop = RoadRouter.findRoute(loopOnly, 0, 0, 1000, 0, "the loop", TravelMode.DRIVE,
                new RoutePreferences(RoutePreference.SHORTEST_DISTANCE, Set.of(), false));
        Route recommended = plans.isEmpty() ? Route.empty() : plans.get(0).route();
        record("a way round is not offered when its time is past the bound",
                alternative(plans) == null && plans.size() == 1, plans.size() + " plan(s)");
        record("because in the metric the search charges it is within the toll",
                loop.isPresent() && recommended.isPresent()
                        && loop.totalLength() < recommended.totalLength() * 2.5,
                loop.isPresent() && recommended.isPresent()
                        ? round(loop.totalLength()) + " blocks against "
                                + round(recommended.totalLength()) + " blocks" : "no route");
        record("while in the seconds the list shows it is well past the bound",
                loop.isPresent() && recommended.isPresent() && ratio(loop, recommended) > 2.5,
                loop.isPresent() && recommended.isPresent()
                        ? round(loop.estimatedSeconds()) + " s against "
                                + round(recommended.estimatedSeconds()) + " s, a ratio of "
                                + round(ratio(loop, recommended)) : "no route");
    }

    /** The way round among the plans, or null when the list has none. */
    private static RouteOption alternative(List<RouteOption> plans) {
        for (RouteOption plan : plans) {
            if (plan.strategy() == RouteStrategy.ALTERNATIVE) {
                return plan;
            }
        }
        return null;
    }

    /** How many times slower one route is than another, by the estimate the list shows. */
    private static double ratio(Route slower, Route faster) {
        double base = faster.estimatedSeconds();
        return base <= 0 ? 0 : slower.estimatedSeconds() / base;
    }

    private static void printPlans(List<RouteOption> plans) {
        for (RouteOption plan : plans) {
            System.out.println("   " + plan.label() + ": " + round(plan.length()) + " blocks, "
                    + round(plan.seconds()) + " s, " + plan.turns() + " turn(s)");
        }
    }

    private static String round(double value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value);
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
