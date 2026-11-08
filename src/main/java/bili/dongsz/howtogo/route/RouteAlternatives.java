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
 *
 * <h2>The way round is judged by its own rule</h2>
 * The fourth plan -- the road router asked to keep off the recommendation's own middle roads -- is the
 * one plan that is asked for <em>because</em> it is different, and it used to be judged by the bound
 * the first three are held to: no more than four fifths slower than the recommendation. That bound is
 * right for a second metric or a second taste, which are meant to be nearly as good as the
 * recommendation; it is wrong for a way round, which a driver takes for being another road, and which
 * on a hand-drawn network is often the long way round the edge of the town. Walking never showed the
 * fault because a walker's alternatives are cheap street variants inside the old bound; a driving
 * one is not, so a driving trip came back as the fastest and the shortest and nothing else. The way
 * round therefore has its own bound, {@link #ALTERNATIVE_MAX_SLOWDOWN}: a line up to two and a half
 * times the recommendation is still a line a player might choose, and past it the list says fewer
 * plans rather than offer one nobody would take.
 *
 * <h2>Where the third row comes from</h2>
 * The strategies before the way round are asked in the order a player would rank them, and a
 * candidate that runs within forty blocks of a plan already kept for most of its length is that plan
 * seen twice -- two streets a lane apart are one way round the town -- so it does not earn a row. That
 * is what leaves one for the way round when the fastest and the shortest are the same street with a
 * different corner: the near-duplicate is not offered as a separate choice, and the third row goes to
 * a line that actually leaves the others. A genuinely different candidate is never displaced, because
 * the way round is only asked for while a row is left for it, and a row full of genuinely different
 * plans leaves none. Picking the three most different candidates instead was rejected: "different"
 * would have to be a score this class invented, and a score can always be argued with -- it could also
 * let a way round push out a shortest way that was genuinely another route. Priority order plus a
 * rule against near-duplicates says the same thing with no number to defend.
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

    /**
     * The slowest a plan answering a slightly different question may be, as a ratio.
     *
     * <p>This is the bound for the second metric and the second taste. Both are the same trip asked a
     * little differently -- "the shorter line", "the bigger road" -- so a plan far behind the
     * recommendation is not a choice the player would make, it is a question that found the wrong
     * road. It deliberately does not apply to the way round; see the class comment.
     */
    private static final double MAX_SLOWDOWN = 1.8;

    /**
     * The slowest a way round may be relative to the recommendation and still be offered, as a ratio.
     *
     * <h2>Why its own number, and not {@link #MAX_SLOWDOWN}</h2>
     * A way round is the one plan on the list chosen for being different rather than for being nearly
     * as good, and in a hand-drawn network the different road is often the long way round the edge of
     * the town: a driver's alternatives are exactly the plans the four-fifths bound used to throw
     * away, while a walker's cheaper variants stayed inside it. Judging both by that bound is what
     * made "another way round" a plan only walking ever offered.
     *
     * <h2>Why two and a half</h2>
     * Two and a half is where the row stops being a choice: the recommendation is on the list beside
     * it, and a trip that takes two and a half times as long is a different journey rather than
     * another way round the same one. It is a multiple of the trip rather than an absolute number of
     * minutes because hand-drawn trips run from a few seconds to many minutes, and ten extra minutes
     * is enormous for a walk across a village and almost nothing for a drive across a continent.
     *
     * <p>It is also the same multiple {@link #DETOUR_TOLL} makes the recommendation's roads cost, but
     * the two are not the same number in disguise: the search charges in whichever metric is in force
     * while this bound is in the seconds the player reads, so a trip planned under "shortest distance"
     * can come back a third again slower than the toll alone would suggest. That is why the way round
     * needs an admission rule of its own instead of inheriting the search's arithmetic, and why the
     * bound is checked against the estimate rather than against what the search paid.
     */
    private static final double ALTERNATIVE_MAX_SLOWDOWN = 2.5;

    /** How many points two routes are compared at, and how close they must be to be the same line. */
    private static final int SAME_PATH_SAMPLES = 12;
    private static final double SAME_PATH_DISTANCE = 15.0;

    /**
     * How far a candidate may run from a plan already kept and still be that plan seen twice, and for
     * how many of {@link #SAME_PATH_SAMPLES} samples.
     *
     * <p>Wider and stricter than {@link #SAME_PATH_DISTANCE} on purpose: two plans a lane apart are
     * two different drawn lines, so the dedupe above cannot see them, but they are one way round the
     * town for the purpose of a picker. Nine of twelve samples within forty blocks is the rule that
     * keeps such a near-duplicate from taking a row, while a candidate that leaves the kept plan for
     * about a quarter of the trip still counts as a plan of its own: a different bridge or a
     * different cut-through is a different way even when most of the trip is shared.
     */
    private static final double ALONGSIDE_DISTANCE = 40.0;
    private static final int ALONGSIDE_SAMPLES = 9;

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

        // And, while there is a row left, a way round the recommendation's own roads.
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
     *
     * <p>The journeys come from {@link LinePlanner#alternatives}, which asks the same stop graph
     * several questions; flattening each one here rather than there keeps the route package's two
     * halves where they belong -- the planner knows journeys, this class knows what a plan is.
     *
     * <p>When the planner answers that no line is worth riding -- no line can carry the trip, or the
     * only journey the lines could offer is one the walk would beat, see {@link
     * LinePlanner#alternatives} -- the list would otherwise be empty, and an empty list reads as the
     * mode failing rather than as the walk being the answer. So one plan is offered instead: the walk
     * itself, labelled as such. It is not a public transport journey (its trip is null, it rides
     * nothing) and the mode it is handed to the picker under stays public transport on purpose: the
     * player asked to go by line, and the answer is that there is no line to take rather than that
     * they chose to walk. The navigation keeps that mode and arrives at the same walk through its own
     * fallback, so the row and the trip it starts are one plan.
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
     *
     * <p>Planned over the walking side of the network -- the same one every walking leg of a journey
     * is planned on -- and with no straight-hop fallback of its own: a plan draws a line the player
     * is meant to follow, so a walk that only exists as a line over the fields is not one.
     *
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
     * Keeps a candidate for the second metric or the second taste, if it is a plan of its own.
     *
     * <p>The slowest plan is compared with the recommendation, which is the first one offered and the
     * one the policy in force says is best. A candidate that is genuinely slower is still offered --
     * that is what an alternative is -- but one eight tenths slower again is not a choice, it is a
     * line the strategy happened to find. A candidate that draws a line already kept, or that runs
     * along one, earns no row: see {@link #earnsARow}.
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
     * Offers the way round, if it earned a row: different roads, and no slower than a real choice.
     *
     * <p>Its own bound rather than {@link #MAX_SLOWDOWN} -- see {@link #ALTERNATIVE_MAX_SLOWDOWN} and
     * the class comment -- and the same test for being a plan of its own as every other candidate: a
     * search asked to keep off the recommendation's roads can come back with a line that was already
     * on the list, or one that runs along the recommendation, and neither is a way round.
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
     * Whether a candidate is a plan of its own rather than a plan already kept, seen again.
     *
     * <p>Two tests, and the second is the room rule: it must not draw a line already kept
     * ({@link #samePath}), and it must not run along one ({@link #runsAlongside}). The second is what
     * keeps a near-duplicate from taking the row a genuinely different way round could use, and it is
     * the only test that costs a sample walk, so it is asked last.
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
     * Whether a route stays on the shoulder of an already-kept plan for most of its length.
     *
     * <p>Sampled at fixed fractions of the candidate's own length and measured against the kept
     * route's whole polyline, so a candidate a little shorter or longer over the same streets still
     * counts. The tolerance is wider than {@link #samePath}'s because the question is not "is this
     * the same line" but "is this the same way round": two streets a lane apart are the same way, and
     * listing both spends a row on a choice the map cannot show. A candidate only has to leave the
     * kept plan for about a quarter of the trip to be a way round of its own.
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

    /** Distance from a point to the nearest point of a route's drawn line, in blocks. */
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
