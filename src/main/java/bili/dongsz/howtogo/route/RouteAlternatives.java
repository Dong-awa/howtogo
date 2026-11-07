package bili.dongsz.howtogo.route;

import bili.dongsz.howtogo.road.RoadClass;
import bili.dongsz.howtogo.road.RoadNetwork;
import bili.dongsz.howtogo.transit.TransitLine;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Plans one trip several ways, so the player can compare before committing to a line.
 *
 * <h2>What "several ways" means here</h2>
 * Same starting point, same destination, same mode of travel -- different <em>strategy</em>. That is
 * the shape every map app offers: a driving trip comes back as "fastest", "shortest", "avoid the
 * motorway" and "another way round", not as a walk, a drive and a train. The mode is chosen first and
 * is the promise about how the trip is made; the strategies are preferences within it, so a plan list
 * never mixes a route a car may take with one only a walker may.
 *
 * <p>For a public transport trip the strategies are the journey's own: the recommendation, one with
 * fewer changes and one with less walking -- see {@link LinePlanner#alternatives}. For a walk or a
 * drive they are the three questions the road router can be asked cheaply: the policy in force, the
 * other metric, and a different taste; then, if the list still has room, one genuinely different way
 * round made by putting a toll on the roads the recommendation uses.
 *
 * <h2>Why the plans are checked rather than trusted</h2>
 * Three strategies are three questions, not three guarantees: on a network with one road they all
 * find the same line, and a list of the same plan three times is worse than a list of one. Every
 * candidate is therefore compared with the plans already kept and dropped when it draws the same
 * line, and a candidate that is much slower than the recommendation is dropped too -- a "different
 * way round" that doubles the trip is not an alternative, it is a mistake. What is never done is
 * inventing a plan to fill the third row: fewer plans is an honest answer.
 */
public final class RouteAlternatives {

    /** How many plans a trip is offered. Three is what a map app shows, and what the screen can list. */
    public static final int MAX_PLANS = 3;

    /**
     * How much more the recommendation's own roads cost a plan that wants another way round.
     *
     * <p>High enough that a genuinely different line wins, low enough that the roads stay usable: a
     * ban would answer "no route" on a network whose only way through is that street, and an
     * alternative list that collapses to one entry there is worse than a slower way round it.
     */
    private static final double DETOUR_TOLL = 2.5;

    /** The slowest a plan may be relative to the recommendation and still be offered, as a ratio. */
    private static final double MAX_SLOWDOWN = 1.8;

    /** How many points two routes are compared at, and how close they must be to be the same line. */
    private static final int SAME_PATH_SAMPLES = 12;
    private static final double SAME_PATH_DISTANCE = 15.0;

    private RouteAlternatives() {
    }

    /**
     * The plans for one trip by road, at most three, quickest first.
     *
     * @return the plans, or an empty list when the mode cannot reach the destination at all
     */
    public static List<RouteOption> roads(RoadNetwork network, double startX, double startZ,
                                          double goalX, double goalZ, String destinationName,
                                          TravelMode mode, RoutePreferences preferences) {
        return roads(RoadRouter.workspaceFor(network), startX, startZ, goalX, goalZ, destinationName,
                mode, preferences);
    }

    /** The same, on a workspace the caller owns: see {@link RoadRouter.Workspace}. */
    public static List<RouteOption> roads(RoadRouter.Workspace workspace, double startX,
                                          double startZ, double goalX, double goalZ,
                                          String destinationName, TravelMode mode,
                                          RoutePreferences preferences) {
        List<RouteOption> plans = new ArrayList<>(MAX_PLANS);
        Route recommended = find(workspace, startX, startZ, goalX, goalZ, destinationName, mode,
                preferences);
        if (!recommended.isPresent()) {
            return plans;
        }
        boolean shortestFirst = preferences.metric() == RoutePreference.SHORTEST_DISTANCE;
        plans.add(new RouteOption(recommended, null, mode,
                shortestFirst ? RouteStrategy.SHORTEST : RouteStrategy.FASTEST));

        // The other metric, which is the cheapest different question there is: one more search over
        // the same graph and a route that is genuinely chosen differently when the two disagree.
        RoutePreference otherMetric = shortestFirst ? RoutePreference.FASTEST_TIME
                : RoutePreference.SHORTEST_DISTANCE;
        RoutePreferences byOtherMetric = new RoutePreferences(otherMetric,
                preferences.avoidedClasses(), preferences.preferMajorRoads());
        addIfNew(plans, find(workspace, startX, startZ, goalX, goalZ, destinationName, mode,
                        byOtherMetric), mode,
                shortestFirst ? RouteStrategy.FASTEST : RouteStrategy.SHORTEST);

        // A different taste. The player's own switch is honoured rather than overridden: if they have
        // already asked for the major roads, the alternative is the one that leaves the highway out.
        RoutePreferences taste = tasteAlternative(mode, preferences);
        if (taste != null) {
            addIfNew(plans, find(workspace, startX, startZ, goalX, goalZ, destinationName, mode, taste),
                    mode, preferences.preferMajorRoads() ? RouteStrategy.AVOID_HIGHWAY
                            : RouteStrategy.MAJOR_ROADS);
        }

        // And, while there is room, a way round the recommendation's own roads.
        if (plans.size() < MAX_PLANS) {
            Set<Integer> used = middleRoads(recommended);
            if (!used.isEmpty()) {
                addIfNew(plans, RoadRouter.findRouteAvoiding(workspace, startX, startZ, goalX, goalZ,
                                destinationName, mode, preferences, used, DETOUR_TOLL), mode,
                        RouteStrategy.ALTERNATIVE);
            }
        }
        return ranked(plans);
    }

    /**
     * The plans for one trip by public transport, at most three, quickest first.
     *
     * <p>The journeys come from {@link LinePlanner#alternatives}, which asks the same stop graph
     * several questions; flattening each one here rather than there keeps the route package's two
     * halves where they belong -- the planner knows journeys, this class knows what a plan is.
     */
    public static List<RouteOption> transit(RideRoads roads, List<TransitLine> lines, double startX,
                                            double startZ, double goalX, double goalZ,
                                            String destinationName, RoutePreferences preferences) {
        List<Trip> journeys = LinePlanner.alternatives(roads, lines, startX, startZ, goalX, goalZ,
                destinationName, preferences);
        List<RouteOption> plans = new ArrayList<>(journeys.size());
        RouteStrategy[] strategies = {RouteStrategy.FASTEST, RouteStrategy.FEWER_TRANSFERS,
                RouteStrategy.LESS_WALKING, RouteStrategy.OTHER_LINES};
        for (int i = 0; i < journeys.size() && i < MAX_PLANS; i++) {
            Trip journey = journeys.get(i);
            plans.add(new RouteOption(TransitPlanner.asRoute(journey, destinationName), journey,
                    TravelMode.TRANSIT, strategies[Math.min(i, strategies.length - 1)]));
        }
        return ranked(plans);
    }

    /** One search under one policy, so the strategies below are three lines of composition and no more. */
    private static Route find(RoadRouter.Workspace workspace, double startX, double startZ,
                              double goalX, double goalZ, String destinationName, TravelMode mode,
                              RoutePreferences preferences) {
        return RoadRouter.findRoute(workspace, startX, startZ, goalX, goalZ, destinationName, mode,
                preferences);
    }

    /**
     * The policy of the third question: the other end of the player's own taste, or null when there is
     * no other end to try.
     */
    private static RoutePreferences tasteAlternative(TravelMode mode, RoutePreferences preferences) {
        if (!preferences.preferMajorRoads()) {
            return new RoutePreferences(preferences.metric(), preferences.avoidedClasses(), true);
        }
        if (!mode.allows(RoadClass.HIGHWAY) || preferences.avoids(RoadClass.HIGHWAY)) {
            return null;
        }
        Set<RoadClass> avoided = new HashSet<>(preferences.avoidedClasses());
        avoided.add(RoadClass.HIGHWAY);
        return new RoutePreferences(preferences.metric(), avoided, true);
    }

    /**
     * Keeps a candidate only if it is a different line and not a wild detour.
     *
     * <p>The slowest plan is compared with the recommendation, which is the first one offered and the
     * one the policy in force says is best. A candidate that is genuinely slower is still offered --
     * that is what an alternative is -- but one eight tenths slower again is not a choice, it is a
     * line the strategy happened to find.
     */
    private static void addIfNew(List<RouteOption> plans, Route candidate, TravelMode mode,
                                 RouteStrategy strategy) {
        if (!candidate.isPresent() || plans.size() >= MAX_PLANS) {
            return;
        }
        double best = plans.get(0).seconds();
        if (best > 0 && candidate.estimatedSeconds() > best * MAX_SLOWDOWN) {
            return;
        }
        for (RouteOption known : plans) {
            if (samePath(known.route(), candidate)) {
                return;
            }
        }
        plans.add(new RouteOption(candidate, null, mode, strategy));
    }

    /** Quickest first: the order a results list is read in, whatever strategy produced each plan. */
    private static List<RouteOption> ranked(List<RouteOption> plans) {
        plans.sort(java.util.Comparator.comparingDouble(RouteOption::seconds));
        return plans;
    }

    /**
     * The roads the middle of a route travels, which is what a detour plan is asked to keep off.
     *
     * <p>The ends are left out on purpose: every route between two places uses the roads nearest its
     * own ends, so tolling them would make the alternative plan drive round the start and the
     * destination instead of round the middle, which is not a different way to the destination -- it
     * is a detour at both ends of the same way. The middle is what makes the two plans genuinely
     * different roads rather than different turnings.
     */
    private static Set<Integer> middleRoads(Route route) {
        int[] keys = route.roadKeys();
        Set<Integer> roads = new HashSet<>();
        if (keys.length < 5) {
            for (int key : keys) {
                if (key != 0) {
                    roads.add(key);
                }
            }
            return roads;
        }
        int skip = keys.length / 6;
        for (int i = skip; i < keys.length - skip; i++) {
            if (keys[i] != 0) {
                roads.add(keys[i]);
            }
        }
        return roads;
    }

    /**
     * Whether two routes draw the same line.
     *
     * <p>Sampled at fixed fractions of each route's length, so two plans of slightly different
     * lengths are still comparable, and compared point by point with a tolerance wide enough for the
     * anchoring splits (the same road split at a different place is the same road). Twelve samples
     * across a trip is enough to tell "the same line" from "a different way round": the two differ
     * for whole stretches, not by a rounding.
     */
    private static boolean samePath(Route a, Route b) {
        if (!a.isPresent() || !b.isPresent()) {
            return false;
        }
        double longer = Math.max(a.totalLength(), b.totalLength());
        if (Math.abs(a.totalLength() - b.totalLength()) > Math.max(4.0, longer * 0.05)) {
            return false;
        }
        List<double[]> left = samples(a);
        List<double[]> right = samples(b);
        for (int i = 0; i < left.size() && i < right.size(); i++) {
            if (Math.hypot(left.get(i)[0] - right.get(i)[0],
                    left.get(i)[1] - right.get(i)[1]) > SAME_PATH_DISTANCE) {
                return false;
            }
        }
        return true;
    }

    /** Points at fixed fractions along a route, including both ends. */
    private static List<double[]> samples(Route route) {
        List<double[]> points = route.points();
        List<double[]> samples = new ArrayList<>(SAME_PATH_SAMPLES);
        double total = route.totalLength();
        int segment = 1;
        double travelled = 0;
        for (int sample = 0; sample < SAME_PATH_SAMPLES; sample++) {
            double at = total * sample / (SAME_PATH_SAMPLES - 1);
            while (segment < points.size() - 1) {
                double[] from = points.get(segment - 1);
                double[] to = points.get(segment);
                double length = Math.hypot(to[0] - from[0], to[1] - from[1]);
                if (travelled + length >= at - 1.0E-9) {
                    break;
                }
                travelled += length;
                segment++;
            }
            double[] from = points.get(Math.max(0, segment - 1));
            double[] to = points.get(Math.min(points.size() - 1, segment));
            double length = Math.hypot(to[0] - from[0], to[1] - from[1]);
            double t = length < 1.0E-9 ? 0 : Math.max(0, Math.min(1, (at - travelled) / length));
            samples.add(new double[]{from[0] + (to[0] - from[0]) * t,
                    from[1] + (to[1] - from[1]) * t});
        }
        return samples;
    }
}
