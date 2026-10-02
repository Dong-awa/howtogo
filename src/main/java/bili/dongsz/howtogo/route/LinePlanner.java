package bili.dongsz.howtogo.route;

import bili.dongsz.howtogo.HowToGo;
import bili.dongsz.howtogo.road.RoadClass;
import bili.dongsz.howtogo.road.RoadNetwork;
import bili.dongsz.howtogo.transit.LineStop;
import bili.dongsz.howtogo.transit.TransitLine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Plans a journey over the player's lines: walk to a stop, ride, change lines where two lines meet,
 * ride again, walk to the destination.
 *
 * <h2>The graph</h2>
 * One node per stop position, and two kinds of edge between them:
 * <ul>
 *   <li><b>ride</b> -- between stops that are neighbours <em>on a line</em>. This is what makes the
 *       order in a line mean something: a ride follows the line, so the journey can only get off
 *       where that line actually calls. Both directions are allowed, because a line is a service
 *       rather than a one-way street -- a player travelling back along it is riding it, not breaking
 *       it;</li>
 *   <li><b>transfer</b> -- between stops of <em>different</em> lines that stand within
 *       {@link #TRANSFER_RADIUS} blocks of each other. This is the leg the player asked to see: a
 *       change of lines is a short walk between where one line puts you down and where the next one
 *       picks you up, and it is a leg of the journey like any other. Two lines calling at the same
 *       block are the same node, so that change costs nothing and is not a leg at all.</li>
 * </ul>
 *
 * <h2>Why the ends are the only walks that are searched</h2>
 * The first and last legs are walks between the player and a stop, and there are as many candidates
 * for those as there are stops. Only the {@link #WALK_CANDIDATES} nearest at each end are tried: a
 * stop that is not among the three nearest to either end cannot be where a sensible journey starts
 * or finishes, and trying every one of them would plan a walk route per stop on every press.
 *
 * <h2>Cost</h2>
 * Every ride and transfer edge is a real route planned by the road router, so the search is bounded
 * by {@link #MAX_ROUTE_PLANS} plans. A network large enough to hit that bound loses the least
 * promising lines rather than spending a minute planning; the bound is deliberately far above what a
 * hand-built network needs.
 */
public final class LinePlanner {

    /** How many stops at each end are considered as boarding and alighting points. */
    private static final int WALK_CANDIDATES = 3;

    /**
     * How far apart two stops of different lines may stand and still count as one interchange.
     *
     * <p>Not zero, because a station a player builds out of two lines is rarely one block: the rail
     * platform and the bus stop beside it are the same place to travel through and two places to the
     * data. Not large either, or a change of lines would quietly become a walk across town.
     */
    private static final double TRANSFER_RADIUS = 24.0;

    /** Ceiling on the number of routes planned in one search, so a huge network cannot hang. */
    private static final int MAX_ROUTE_PLANS = 48;

    /**
     * What a change of lines costs on top of walking between the two stops, in seconds.
     *
     * <p>A change is not only the walk: it is finding the platform and waiting for the next service.
     * Without it the search treats a change as free and will answer a two-change journey that saves
     * ten seconds, which is not what a person would do.
     */
    private static final double TRANSFER_PENALTY_SECONDS = 45.0;

    /**
     * How far the player may be asked to walk to reach a stop, in blocks.
     *
     * <p>The nearest stops are the candidates, but only within this. Without a limit the planner will
     * offer a four-hundred-block walk to a station and call the result public transport; past this the
     * honest answer is that no stop is near enough for the journey to be worth taking by line.
     */
    private static final double MAX_WALK_TO_STOP = 256.0;

    /**
     * How much longer than the straight line a walked connector may be before the road's answer is
     * refused, as a multiple.
     *
     * <p>Two, because a road that doubles the distance is no longer taking the walker anywhere useful:
     * the detour is then not a route to the station but a route the router preferred, and the straight
     * hop across the field is what a person does.
     */
    private static final double WALK_DETOUR_LIMIT = 2.0;

    private LinePlanner() {
    }

    /** One stop position and every place on a line that calls there. */
    private record Node(LineStop at, List<int[]> calls) {
    }

    public static Trip plan(RoadNetwork network, List<TransitLine> lines, double startX, double startZ,
                            double goalX, double goalZ, String destinationName,
                            RoutePreferences preferences) {
        // Direct first, then with changes. Two searches rather than one, because the two answers mean
        // different things: a single ride is what a player expects to see when their lines reach, and
        // only when none of them does is a change of lines a better answer than nothing. The second
        // search is the same graph with the interchange edges switched on, so a direct ride is never
        // passed over in favour of a longer journey that happens to change lines.
        Trip direct = search(network, lines, startX, startZ, goalX, goalZ, destinationName,
                preferences, false);
        if (direct.isPresent()) {
            HowToGo.LOGGER.info("[HowToGo] public transport: direct ride, {} leg(s)",
                    direct.legs().size());
            return direct;
        }
        Trip changed = search(network, lines, startX, startZ, goalX, goalZ, destinationName,
                preferences, true);
        if (changed.isPresent()) {
            HowToGo.LOGGER.info("[HowToGo] public transport: {} leg(s), changing lines",
                    changed.legs().size());
        } else {
            HowToGo.LOGGER.info("[HowToGo] public transport: no journey -- {} stop(s) in the network, "
                    + "origin ({}, {}), goal ({}, {})", buildNodes(lines).size(), Math.round(startX),
                    Math.round(startZ), Math.round(goalX), Math.round(goalZ));
        }
        return changed;
    }

    private static Trip search(RoadNetwork network, List<TransitLine> lines, double startX,
                               double startZ, double goalX, double goalZ, String destinationName,
                               RoutePreferences preferences, boolean allowTransfers) {
        List<Node> nodes = buildNodes(lines);
        if (nodes.isEmpty()) {
            return Trip.empty();
        }

        int count = nodes.size();
        double[] dist = new double[count];
        int[] fromNode = new int[count];
        Route[] fromRoute = new Route[count];
        TravelMode[] fromMode = new TravelMode[count];
        boolean[] settled = new boolean[count];
        java.util.Arrays.fill(dist, Double.MAX_VALUE);
        java.util.Arrays.fill(fromNode, -1);

        Map<String, Route> cache = new HashMap<>();
        int[] budget = {MAX_ROUTE_PLANS};

        // Boarding: a walk from the player to one of the nearest stops.
        for (int index : nearest(nodes, startX, startZ)) {
            Node node = nodes.get(index);
            Route walk = walk(network, startX, startZ, node.at().x(), node.at().z(), destinationName,
                    preferences, cache, budget);
            if (walk.isPresent()) {
                dist[index] = walk.estimatedSeconds();
                fromRoute[index] = walk;
                fromMode[index] = TravelMode.WALK;
            }
        }

        // Dijkstra over the stops. Small graph, so the simple scan is clearer than a priority queue
        // and costs nothing at this size.
        while (true) {
            int current = -1;
            double best = Double.MAX_VALUE;
            for (int i = 0; i < count; i++) {
                if (!settled[i] && dist[i] < best) {
                    best = dist[i];
                    current = i;
                }
            }
            if (current < 0) {
                break;
            }
            settled[current] = true;
            relaxRides(network, lines, nodes, current, dist, fromNode, fromRoute, fromMode,
                    destinationName, preferences, cache, budget);
            if (allowTransfers) {
                relaxTransfers(network, nodes, current, dist, fromNode, fromRoute, fromMode,
                        destinationName, preferences, cache, budget);
            }
        }

        // Alighting: a walk from one of the nearest stops to the destination.
        int bestEnd = -1;
        double bestTotal = Double.MAX_VALUE;
        Route bestFinish = null;
        for (int index : nearest(nodes, goalX, goalZ)) {
            if (dist[index] == Double.MAX_VALUE) {
                continue;
            }
            Node node = nodes.get(index);
            Route walk = walk(network, node.at().x(), node.at().z(), goalX, goalZ, destinationName,
                    preferences, cache, budget);
            if (!walk.isPresent()) {
                continue;
            }
            double total = dist[index] + walk.estimatedSeconds();
            if (total < bestTotal) {
                bestTotal = total;
                bestEnd = index;
                bestFinish = walk;
            }
        }
        if (bestEnd < 0) {
            return Trip.empty();
        }

        List<Trip.Leg> reversed = new ArrayList<>();
        boolean rode = false;
        for (int at = bestEnd; at >= 0; at = fromNode[at]) {
            reversed.add(new Trip.Leg(fromRoute[at], fromMode[at]));
            rode |= fromMode[at] != TravelMode.WALK;
        }
        if (!rode) {
            // Walking to a stop and walking away from it again is not a journey by public transport.
            // The search offers exactly that whenever a line's stop happens to sit between the two
            // ends, because the two walks are a valid path through the graph -- and it is the cheaper
            // one whenever the walk to the stop follows a road the direct walk does not. Refusing it
            // here answers "no journey over the lines", which is the truth, and leaves the station
            // pair search and then the plain route to have their say.
            return Trip.empty();
        }
        java.util.Collections.reverse(reversed);
        reversed.add(new Trip.Leg(bestFinish, TravelMode.WALK));

        // The first and last stops of the chain, which are where the player boards and gets off. The
        // legs before and after them are walks, so the chain's ends are exactly the stations.
        int first = bestEnd;
        while (fromNode[first] >= 0) {
            first = fromNode[first];
        }
        return Trip.of(reversed, nodes.get(first).at().label(), nodes.get(bestEnd).at().label());
    }

    // ------------------------------------------------------------------ edges

    private static void relaxRides(RoadNetwork network, List<TransitLine> lines, List<Node> nodes,
                                   int current, double[] dist, int[] fromNode, Route[] fromRoute,
                                   TravelMode[] fromMode, String destinationName,
                                   RoutePreferences preferences, Map<String, Route> cache,
                                   int[] budget) {
        for (int[] call : nodes.get(current).calls()) {
            TransitLine line = lines.get(call[0]);
            int index = call[1];
            TravelMode mode = rideMode(line.kind());
            // The line's own class and nothing else. A mode is a set of classes, so the mode alone
            // would let a water line's ride come back along a rail; the policy is what makes the type
            // a player chose for a line mean something.
            RoutePreferences ridePolicy = ridePreferences(line.kind(), preferences);
            for (int step = -1; step <= 1; step += 2) {
                int next = index + step;
                if (next < 0 || next >= line.stopCount()) {
                    continue;
                }
                LineStop to = line.stops().get(next);
                int target = indexOf(nodes, to);
                if (target < 0 || target == current) {
                    continue;
                }
                // Keyed by direction, and planned from the end being travelled from: a stretch ridden
                // the other way is not the same route reversed, because its turn-by-turn instructions
                // have to point the way the rider is actually going.
                String key = "R|" + line.id() + "|" + index + "|" + next;
                Route ride = cache.get(key);
                if (ride == null) {
                    if (budget[0] <= 0) {
                        continue;
                    }
                    budget[0]--;
                    LineStop from = line.stops().get(index);
                    ride = RoadRouter.findRoute(network, from.x(), from.z(), to.x(), to.z(),
                            destinationName, mode, ridePolicy);
                    cache.put(key, ride);
                }
                if (!ride.isPresent()) {
                    // Named, because "no journey over 1 line(s)" cannot say which stretch of which line
                    // is the one that could not be ridden, and that is the only question worth asking.
                    HowToGo.LOGGER.info("[HowToGo] line ride cannot be planned: '{}' -> '{}' ({})",
                            line.stops().get(index).label(), to.label(), line.kind().name());
                    continue;
                }
                offer(current, target, ride.estimatedSeconds(), ride, mode, dist, fromNode, fromRoute,
                        fromMode);
            }
        }
    }

    private static void relaxTransfers(RoadNetwork network, List<Node> nodes, int current,
                                       double[] dist, int[] fromNode, Route[] fromRoute,
                                       TravelMode[] fromMode, String destinationName,
                                       RoutePreferences preferences, Map<String, Route> cache,
                                       int[] budget) {
        Node node = nodes.get(current);
        for (int other = 0; other < nodes.size(); other++) {
            if (other == current || dist[current] == Double.MAX_VALUE) {
                continue;
            }
            Node target = nodes.get(other);
            if (!touchesOtherLine(node, target)) {
                continue;
            }
            double dx = target.at().x() - node.at().x();
            double dz = target.at().z() - node.at().z();
            if (dx * dx + dz * dz > TRANSFER_RADIUS * TRANSFER_RADIUS) {
                continue;
            }
            String key = "T|" + node.at().x() + "," + node.at().z() + "|" + target.at().x() + ","
                    + target.at().z();
            Route walk = cache.get(key);
            if (walk == null) {
                if (budget[0] <= 0) {
                    continue;
                }
                budget[0]--;
                walk = RoadRouter.findRoute(network, node.at().x(), node.at().z(), target.at().x(),
                        target.at().z(), destinationName, TravelMode.WALK, walkPreferences(preferences));
                cache.put(key, walk);
            }
            if (!walk.isPresent()) {
                continue;
            }
            // Search cost only: the penalty is what makes a change of lines cost more than the bare
            // walk between the two platforms, so the search prefers fewer changes, while the leg's own
            // seconds stay what they are and the ETA does not inflate by time nobody spends.
            offer(current, other, walk.estimatedSeconds() + TRANSFER_PENALTY_SECONDS, walk,
                    TravelMode.WALK, dist, fromNode, fromRoute, fromMode);
        }
    }

    /** Keeps the cheaper way of reaching a stop, which is the whole of the search. */
    private static void offer(int from, int to, double seconds, Route route, TravelMode mode,
                              double[] dist, int[] fromNode, Route[] fromRoute,
                              TravelMode[] fromMode) {
        double candidate = dist[from] + seconds;
        if (candidate < dist[to]) {
            dist[to] = candidate;
            fromNode[to] = from;
            fromRoute[to] = route;
            fromMode[to] = mode;
        }
    }

    private static Route walk(RoadNetwork network, double startX, double startZ, double goalX,
                              double goalZ, String destinationName, RoutePreferences preferences,
                              Map<String, Route> cache, int[] budget) {
        String key = "T|" + Math.round(startX) + "," + Math.round(startZ) + "|" + Math.round(goalX)
                + "," + Math.round(goalZ);
        Route cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        if (budget[0] <= 0) {
            return Route.empty();
        }
        budget[0]--;
        Route planned = RoadRouter.findRoute(network, startX, startZ, goalX, goalZ, destinationName,
                TravelMode.WALK, preferences);
        // A walk that wanders is not a walk to a station. The router follows the roads, and off-road
        // walking is costed several times worse than road walking, so a long way round on a road can
        // beat a short straight hop -- which is how a station beside the player ends up reached by
        // walking away from it first and coming back. Past this factor the road's answer is refused and
        // the walk becomes what it always was at the ends of a trip: a straight connector.
        double straight = Math.hypot(goalX - startX, goalZ - startZ);
        if (!planned.isPresent() || planned.totalLength() > straight * WALK_DETOUR_LIMIT) {
            Route hop = straightWalk(startX, startZ, goalX, goalZ, destinationName);
            // Taken only if it is a usable route. A refusal here must never turn a journey that could be
            // ridden into no journey at all, which is the one way this fallback could make things worse.
            if (hop.isPresent()) {
                planned = hop;
            }
        }
        cache.put(key, planned);
        return planned;
    }

    /**
     * The walk with nothing to follow: a straight hop at connector pace.
     *
     * <p>The same shape the first and last hop of every trip already has, built the same way the router
     * builds it, so that a refused road walk is still a route the map can draw and the estimate can time
     * rather than a gap.
     */
    private static Route straightWalk(double startX, double startZ, double goalX, double goalZ,
                                      String destinationName) {
        Route.Builder builder = new Route.Builder();
        builder.setDestinationName(destinationName);
        builder.setTravelMode(TravelMode.WALK);
        builder.setOffRoadSpeedFactor(1.0);
        double tolerance = bili.dongsz.howtogo.RoadConfig.onRoadTolerance(RoadClass.ROAD);
        builder.addPoint(startX, startZ, tolerance, 0, null, false);
        builder.setStartConnector(Math.hypot(goalX - startX, goalZ - startZ));
        builder.addPoint(goalX, goalZ, tolerance, 0, null, false);
        return builder.build();
    }

    // ------------------------------------------------------------------ shape

    /**
     * The mode a ride on this kind of line is planned with.
     *
     * <p>A road line is driven and the other three are ridden. Which classes are actually travelled
     * on is not this method's business: a mode is a set of classes, so the restriction to the line's
     * own class is made by {@link #ridePreferences}.
     */
    public static TravelMode rideMode(RoadClass kind) {
        return kind == RoadClass.ROAD ? TravelMode.DRIVE : TravelMode.TRANSIT;
    }

    /**
     * The routing policy for a ride on a line of this kind: everything except that kind is avoided.
     *
     * <p>This is what makes a line's declared type real. Avoiding a class keeps it out of the graph
     * exactly as if the mode had disallowed it, so a ride on a water line is planned over water and
     * cannot take a rail that happens to be shorter, and a ride on an ice line is planned over ice.
     * A road line allows the highway as well as the road, because those are the same vehicle on a
     * wider surface rather than two different services.
     *
     * <p>The player's own avoidances are deliberately not merged in. They were asked for by someone
     * who then built a line of that kind and chose to travel on it; letting a global "avoid rails"
     * cancel a rail ride would make the line silently unusable rather than routing it. The rest of
     * the policy -- the metric, and whether minor roads are discouraged -- is kept, because those say
     * how to choose between routes rather than which routes exist.
     */
    /**
     * The policy for the walking legs of a journey: the player's own, unchanged.
     *
     * <p>It used to drop the avoidances here, on the theory that a walk to a station is not the mode the
     * avoidance is about. That was wrong in the direction that matters: a player who forbids made roads
     * and is then routed along one to reach a station has been overruled, and being overruled is worse
     * than being told there is no route. The walking legs keep the policy, and what keeps a station
     * reachable when its roads are forbidden is {@link #straightWalk} -- a refusal to follow a road is
     * answered with a straight hop across the ground, which is not a road, rather than with a road.
     */
    private static RoutePreferences walkPreferences(RoutePreferences base) {
        return base;
    }

    public static RoutePreferences ridePreferences(RoadClass kind, RoutePreferences base) {
        Set<RoadClass> allowed = kind == RoadClass.ROAD
                ? Set.of(RoadClass.HIGHWAY, RoadClass.ROAD)
                : Set.of(kind);
        Set<RoadClass> avoided = EnumSet.noneOf(RoadClass.class);
        for (RoadClass roadClass : RoadClass.values()) {
            if (!allowed.contains(roadClass)) {
                avoided.add(roadClass);
            }
        }
        return new RoutePreferences(base.metric(), avoided, base.preferMajorRoads());
    }

    /** Whether the two stops are called at by different lines, which is what makes a change a change. */
    private static boolean touchesOtherLine(Node a, Node b) {
        for (int[] left : a.calls()) {
            for (int[] right : b.calls()) {
                if (left[0] != right[0]) {
                    return true;
                }
            }
        }
        return false;
    }

    /** One node per stop position, carrying every line that calls there. */
    private static List<Node> buildNodes(List<TransitLine> lines) {
        List<Node> nodes = new ArrayList<>();
        for (int l = 0; l < lines.size(); l++) {
            TransitLine line = lines.get(l);
            for (int s = 0; s < line.stopCount(); s++) {
                LineStop stop = line.stops().get(s);
                int existing = indexOf(nodes, stop);
                if (existing < 0) {
                    List<int[]> calls = new ArrayList<>();
                    calls.add(new int[]{l, s});
                    nodes.add(new Node(stop, calls));
                } else {
                    nodes.get(existing).calls().add(new int[]{l, s});
                }
            }
        }
        return nodes;
    }

    private static int indexOf(List<Node> nodes, LineStop stop) {
        for (int i = 0; i < nodes.size(); i++) {
            if (nodes.get(i).at().samePlace(stop)) {
                return i;
            }
        }
        return -1;
    }

    /** The indices of the {@link #WALK_CANDIDATES} nodes nearest to a point. */
    private static List<Integer> nearest(List<Node> nodes, double x, double z) {
        List<Integer> order = new ArrayList<>(nodes.size());
        for (int i = 0; i < nodes.size(); i++) {
            order.add(i);
        }
        order.removeIf(i -> {
            Node node = nodes.get(i);
            double dx = node.at().x() - x;
            double dz = node.at().z() - z;
            return dx * dx + dz * dz > MAX_WALK_TO_STOP * MAX_WALK_TO_STOP;
        });
        order.sort(Comparator.comparingDouble(i -> {
            Node node = nodes.get(i);
            double dx = node.at().x() - x;
            double dz = node.at().z() - z;
            return dx * dx + dz * dz;
        }));
        return order.subList(0, Math.min(WALK_CANDIDATES, order.size()));
    }
}
