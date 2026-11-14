package bili.dongsz.howtogo.route;

import bili.dongsz.howtogo.road.RoadClass;
import bili.dongsz.howtogo.road.RoadNetwork;
import bili.dongsz.howtogo.transit.TransitLine;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Plans one trip several ways so the player can compare before committing.
 * Same start, destination and mode; different strategy. The mode is chosen first and is the promise
 * about how the trip is made, so a list never mixes a drive with a walk.
 * Candidates are checked, not trusted: duplicates and plans too much slower are dropped, and a plan
 * is never invented to fill the third row. The way round has its own bound,
 * ALTERNATIVE_MAX_SLOWDOWN, and near-duplicates are not offered as separate choices.
 */
public final class RouteAlternatives {

    /** How many plans a trip is offered: three is what a map app shows, and what the screen can list. */
    public static final int MAX_PLANS = 3;

    /**
     * How much more the recommendation's own roads cost a plan that wants another way round.
     * High enough that a different line wins, low enough that the roads stay usable: a ban would
     * answer "no route" where that street is the only way through.
     */
    private static final double DETOUR_TOLL = 2.5;

    /**
     * The slowest a plan answering a slightly different question may be, as a ratio.
     * The bound for the second metric and the second taste; it deliberately does not apply to the
     * way round (see the class comment).
     */
    private static final double MAX_SLOWDOWN = 1.8;

    /**
     * The slowest a way round may be relative to the recommendation, as a ratio.
     * A way round is chosen for being different, and the different road is often the long way round the
     * edge of town. Two and a half is where the row stops being a choice; a multiple rather than
     * minutes because trips range from seconds to hours.
     */
    private static final double ALTERNATIVE_MAX_SLOWDOWN = 2.5;

    /** How many points two routes are compared at, and how close they must be to be the same line. */
    private static final int SAME_PATH_SAMPLES = 12;
    private static final double SAME_PATH_DISTANCE = 15.0;

    /**
     * How far, and for how many samples, a candidate may run from a kept plan and still be that
     * plan seen twice.
     * Wider than SAME_PATH_DISTANCE on purpose: two streets a lane apart are different drawn lines
     * but one way round the town for a picker. A candidate leaving the kept plan for about a quarter
     * of the trip still counts as a plan of its own.
     */
    private static final double ALONGSIDE_DISTANCE = 40.0;
    private static final int ALONGSIDE_SAMPLES = 9;

    private RouteAlternatives() {
    }

    /**
     * The plans for one trip by road, at most three, quickest first.
     * @return the plans, or an empty list when the mode cannot reach the destination at all
     */
    public static List<RouteOption> roads(RoadNetwork network, double startX, double startZ,
                                          double goalX, double goalZ, String destinationName,
                                          TravelMode mode, RoutePreferences preferences) {
        return roads(RoadRouter.workspaceFor(network), startX, startZ, goalX, goalZ, destinationName,
                mode, preferences);
    }

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

        // The other metric, the cheapest different question there is: one more search over the same graph.
        RoutePreference otherMetric = shortestFirst ? RoutePreference.FASTEST_TIME
                : RoutePreference.SHORTEST_DISTANCE;
        RoutePreferences byOtherMetric = new RoutePreferences(otherMetric,
                preferences.avoidedClasses(), preferences.preferMajorRoads());
        addIfNew(plans, find(workspace, startX, startZ, goalX, goalZ, destinationName, mode,
                        byOtherMetric), mode,
                shortestFirst ? RouteStrategy.FASTEST : RouteStrategy.SHORTEST);

        // A different taste -- the player's own switch is honoured: if they already prefer major roads, the alternative leaves the highway out.
        RoutePreferences taste = tasteAlternative(mode, preferences);
        if (taste != null) {
            addIfNew(plans, find(workspace, startX, startZ, goalX, goalZ, destinationName, mode, taste),
                    mode, preferences.preferMajorRoads() ? RouteStrategy.AVOID_HIGHWAY
                            : RouteStrategy.MAJOR_ROADS);
        }

        if (plans.size() < MAX_PLANS) {
            Set<Integer> used = middleRoads(recommended);
            if (!used.isEmpty()) {
                addAlternative(plans, RoadRouter.findRouteAvoiding(workspace, startX, startZ,
                        goalX, goalZ, destinationName, mode, preferences, used, DETOUR_TOLL), mode);
            }
        }
        return ranked(plans);
    }

    /**
     * The plans for one trip by public transport, at most three, quickest first.
     * Journeys come from LinePlanner#alternatives. When no line is worth riding, the walk itself is
     * offered, labelled as such but handed over under the transit mode: the player asked to go by line,
     * and the answer is that there is none.
     */
    public static List<RouteOption> transit(RideRoads roads, List<TransitLine> lines, double startX,
                                            double startZ, double goalX, double goalZ,
                                            String destinationName, RoutePreferences preferences) {
        List<Trip> journeys = LinePlanner.alternatives(roads, lines, startX, startZ, goalX, goalZ,
                destinationName, preferences);
        if (journeys.isEmpty()) {
            return walkInstead(roads, startX, startZ, goalX, goalZ, destinationName, preferences);
        }
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

    /**
     * The walk offered where no line is worth riding, as the one plan there is.
     * Planned over the walking side of the network, with no straight-hop fallback: a plan draws a
     * line the player is meant to follow.
     * @return one plan, or an empty list when the destination cannot be walked to either
     */
    private static List<RouteOption> walkInstead(RideRoads roads, double startX, double startZ,
                                                 double goalX, double goalZ, String destinationName,
                                                 RoutePreferences preferences) {
        Route walk = RoadRouter.findRoute(RoadRouter.workspaceFor(roads.forWalks()), startX, startZ,
                goalX, goalZ, destinationName, TravelMode.WALK, preferences);
        if (!walk.isPresent()) {
            return List.of();
        }
        return List.of(new RouteOption(walk, null, TravelMode.TRANSIT, RouteStrategy.WALK));
    }

    private static Route find(RoadRouter.Workspace workspace, double startX, double startZ,
                              double goalX, double goalZ, String destinationName, TravelMode mode,
                              RoutePreferences preferences) {
        return RoadRouter.findRoute(workspace, startX, startZ, goalX, goalZ, destinationName, mode,
                preferences);
    }

    /**
     * The policy of the third question: the other end of the player's own taste, or null when there
     * is no other end to try.
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
     * Keeps a candidate for the second metric or second taste, if it is a plan of its own.
     * Slower than the recommendation is fine -- that is what an alternative is -- but one
     * max-slowdown worse is not a choice. Duplicates and plans running alongside earn no row.
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
        if (!earnsARow(plans, candidate)) {
            return;
        }
        plans.add(new RouteOption(candidate, null, mode, strategy));
    }

    /**
     * Offers the way round if it earned a row: different roads, and no slower than a real choice.
     * Its own bound rather than MAX_SLOWDOWN, and the same "plan of its own" test as every other
     * candidate -- a search keeping off the recommendation's roads can still come back with one.
     */
    private static void addAlternative(List<RouteOption> plans, Route candidate, TravelMode mode) {
        if (!candidate.isPresent() || plans.size() >= MAX_PLANS) {
            return;
        }
        double best = plans.get(0).seconds();
        if (best > 0 && candidate.estimatedSeconds() > best * ALTERNATIVE_MAX_SLOWDOWN) {
            return;
        }
        if (!earnsARow(plans, candidate)) {
            return;
        }
        plans.add(new RouteOption(candidate, null, mode, RouteStrategy.ALTERNATIVE));
    }

    /**
     * Whether a candidate is a plan of its own rather than one already kept, seen again.
     * It must not draw a kept line (samePath) and not run along one (runsAlongside); the second is
     * the room rule and the only test that costs a sample walk, so it is asked last.
     */
    private static boolean earnsARow(List<RouteOption> plans, Route candidate) {
        for (RouteOption kept : plans) {
            if (samePath(kept.route(), candidate) || runsAlongside(kept.route(), candidate)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether a route stays on the shoulder of a kept plan for most of its length.
     * Sampled at fixed fractions of the candidate's own length against the kept polyline; the
     * tolerance is wider than samePath's because the question is "same way round", not "same line".
     * A candidate only has to leave the kept plan for about a quarter of the trip.
     */
    private static boolean runsAlongside(Route kept, Route candidate) {
        List<double[]> points = samples(candidate);
        int close = 0;
        for (double[] point : points) {
            if (distanceToRoute(kept, point[0], point[1]) <= ALONGSIDE_DISTANCE) {
                close++;
            }
        }
        return close >= ALONGSIDE_SAMPLES;
    }

    private static double distanceToRoute(Route route, double x, double z) {
        List<double[]> points = route.points();
        double nearest = Double.MAX_VALUE;
        for (int i = 1; i < points.size(); i++) {
            double[] a = points.get(i - 1);
            double[] b = points.get(i);
            double ex = b[0] - a[0];
            double ez = b[1] - a[1];
            double lengthSq = ex * ex + ez * ez;
            double t = lengthSq < 1.0E-9 ? 0
                    : Math.max(0, Math.min(1, ((x - a[0]) * ex + (z - a[1]) * ez) / lengthSq));
            nearest = Math.min(nearest, Math.hypot(a[0] + ex * t - x, a[1] + ez * t - z));
        }
        return nearest;
    }

    private static List<RouteOption> ranked(List<RouteOption> plans) {
        plans.sort(java.util.Comparator.comparingDouble(RouteOption::seconds));
        return plans;
    }

    /**
     * The roads the middle of a route travels, which a detour plan is asked to keep off.
     * The ends are left out because every route uses the roads nearest its ends; tolling them
     * would produce a detour at both ends of the same way, not a different way.
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
     * Sampled at fixed fractions so plans of slightly different lengths compare, with a tolerance
     * wide enough for anchoring splits (a road split at a different place is the same road).
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
