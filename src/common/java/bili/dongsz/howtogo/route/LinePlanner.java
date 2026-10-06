package bili.dongsz.howtogo.route;

import bili.dongsz.howtogo.HowToGo;
import bili.dongsz.howtogo.RoadConfig;
import bili.dongsz.howtogo.road.RoadClass;
import bili.dongsz.howtogo.road.RoadNetwork;
import bili.dongsz.howtogo.transit.LineStop;
import bili.dongsz.howtogo.transit.TransitLine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
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
 * <h2>One search, and why it is one</h2>
 * This used to run twice: first with transfers forbidden, and if that found anything at all it was
 * returned without the second search ever running. The idea was that a direct ride should not be
 * passed over in favour of a longer journey that happens to change lines -- but the answer to that is
 * a price on changing lines, not a veto, and the veto did the opposite of what it intended: it made
 * the best <em>single-line</em> journey the answer whenever one existed, however far around it went,
 * even with a two-ride journey standing next to it that arrived in a third of the time. That is what
 * "the route goes the long way round" was. A transfer now costs the walk between the two platforms
 * plus {@link #TRANSFER_PENALTY_SECONDS}, both searches are one, and the search simply takes the
 * cheapest whole journey.
 *
 * <h2>Why the graph is built before the search</h2>
 * A link's cost is a real route planned by the road router, so it is the expensive part of planning.
 * Those plans used to be made lazily, from inside the search, under a budget -- and when the budget
 * ran out the neighbouring links were silently skipped, which changes the graph underneath a running
 * shortest-path search and leaves it answering a question about a network that never existed. The
 * links are now all planned first, so the search is a plain Dijkstra over a graph that does not move,
 * and the budget can only ever drop the tail of the ride list, once, out loud.
 *
 * <h2>Why the ends are the only walks that are searched</h2>
 * The first and last legs are walks between the player and a stop, and there are as many candidates
 * for those as there are stops. The {@link #WALK_CANDIDATES} nearest at each end are tried: a stop
 * that is not among them cannot be where a sensible journey starts or finishes, and trying every one
 * of them would plan a walk route per stop on every press. The number is well above the three it once
 * was, because three stops sorted by <em>straight-line</em> distance is not three good ways to start a
 * journey: the fourth nearest is often the only one on a line that goes anywhere.
 */
public final class LinePlanner {

    /** How many stops at each end are considered as boarding and alighting points. */
    private static final int WALK_CANDIDATES = 12;

    /**
     * How far apart two stops of different lines may stand and still count as one interchange.
     *
     * <p>Not zero, because a station a player builds out of two lines is rarely one block: the rail
     * platform and the bus stop beside it are the same place to travel through and two places to the
     * data. Not large either, or a change of lines would quietly become a walk across town.
     */
    private static final double TRANSFER_RADIUS = 24.0;

    /**
     * The same number, for the map.
     *
     * <p>Exposed so that the map cannot mark a different set of places from the ones a journey may
     * change lines at: a stop the planner will transfer at and the map calls two separate stations is
     * the map arguing with the route, and the marker that outlives the line it belonged to is what that
     * looks like from the player's side.
     */
    public static double transferRadius() {
        return TRANSFER_RADIUS;
    }

    /**
     * The cell the transfer pass files a stop under, which is the radius itself.
     *
     * <p>Not a constant of its own: nine cells of this size around a stop is exactly the neighbourhood a
     * transfer may be found in, and a size that did not match the radius would either miss stops that
     * are within it or search cells that reach further than it does.
     */
    private static final double TRANSFER_CELL = TRANSFER_RADIUS;

    /**
     * Ceiling on the number of ride legs planned in one search.
     *
     * <p>A safety valve, not a design: each leg is a route planned over some network, so a pathological
     * one should not hang the client. It is applied while the graph is built, before the search, so
     * tripping it drops the lines at the end of the list rather than removing edges from a search that
     * is already running, and it is always reported in the log. It was 48, which a network of five lines
     * and ten stops each already exceeds -- so on any real network the tail of the graph was being cut
     * away mid-search and the journey that came out was the best of what happened to have been planned,
     * not the best there was.
     *
     * <p><b>Why it is no longer 512.</b> A ride used to be an A* over every rail in the world at once,
     * which is what the number was sized for: a handful of lines around the player, each ride searching
     * a layer that served every line of its kind. A reading that names each line's rails changes what a
     * ride costs -- it is a search over <em>that line's own track</em> and nothing else, see
     * {@link RideRoads} -- and a whole railway read from a server is hundreds of lines and thousands of
     * stops, which is three and a half thousand rides rather than the hundreds a window holds. At 512
     * the graph was cut off after the first few lines and every journey across the network answered "no
     * journey", which is the failure this number was meant to be a guard against rather than the cause
     * of. The per-ride cost is what makes raising it safe, and the check in the regression harness is
     * what keeps that true: it plans over a hundred and sixty lines and asserts both that a journey
     * comes out and that the whole plan is quick.
     */
    private static final int MAX_RIDE_PLANS = 20_000;

    /**
     * How far the player may be asked to walk to reach a stop, in blocks.
     *
     * <p>The nearest stops are the candidates, but only within this. Without a limit the planner will
     * offer a four-hundred-block walk to a station and call the result public transport; past this the
     * honest answer is that no stop is near enough for the journey to be worth taking by line.
     */
    private static final double MAX_WALK_TO_STOP = 256.0;

    /**
     * How close two positions have to be before the walk between them is no walk at all.
     *
     * <p>A route is a polyline and needs two points, and the builder drops a second point that lands
     * on the first -- so "how do I get from here to here" had no answer, and a stop standing exactly
     * where the player is, or exactly on the destination they picked, was skipped as unusable. Picking
     * a station as the destination is the commonest public transport journey there is.
     */
    private static final double STATIONARY_DISTANCE = 1.0E-6;

    /**
     * How long a walk has to be before its falling back to a straight hop is worth a log line.
     *
     * <p>Stepping a few blocks off a road is not news. A walk of a hundred blocks that no road could
     * carry is, and it is the only visible symptom of a road network the router cannot use.
     */
    private static final double SHORT_HOP = 8.0;

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

    /**
     * One way out of a stop: where it leads, what the search pays to take it, and the route that gets
     * there.
     *
     * <p>The seconds are the whole cost of taking it, and the route carries the same cost: a change of
     * lines costs the walk between the platforms plus the wait for the next service, and both are in
     * the route's own estimate. The search therefore minimises exactly the number the readout shows,
     * which is the only way the two can be trusted to agree.
     */
    private record Link(int target, double seconds, Route route, TravelMode mode) {
    }

    public static Trip plan(RoadNetwork network, List<TransitLine> lines, double startX, double startZ,
                            double goalX, double goalZ, String destinationName,
                            RoutePreferences preferences) {
        return plan(RideRoads.of(network), lines, startX, startZ, goalX, goalZ, destinationName,
                preferences);
    }

    /** Plans over roads that depend on the line, which is how a line's own marks are switched off. */
    public static Trip plan(RideRoads roads, List<TransitLine> lines, double startX, double startZ,
                            double goalX, double goalZ, String destinationName,
                            RoutePreferences preferences) {
        Trip trip = search(roads, lines, startX, startZ, goalX, goalZ, destinationName, preferences);
        if (trip.isPresent()) {
            HowToGo.LOGGER.info("[HowToGo] public transport: {} leg(s)", trip.legs().size());
        } else {
            HowToGo.LOGGER.info("[HowToGo] public transport: no journey -- {} stop(s) in the network, "
                    + "origin ({}, {}), goal ({}, {})", buildNodes(lines).size(), Math.round(startX),
                    Math.round(startZ), Math.round(goalX), Math.round(goalZ));
        }
        return trip;
    }

    private static Trip search(RideRoads roads, List<TransitLine> lines, double startX,
                               double startZ, double goalX, double goalZ, String destinationName,
                               RoutePreferences preferences) {
        List<Node> nodes = buildNodes(lines);
        if (nodes.isEmpty()) {
            return Trip.empty();
        }

        int count = nodes.size();
        Map<Long, Integer> byPosition = positions(nodes);
        Map<String, Route> cache = new HashMap<>();
        double wait = RoadConfig.transitWaitSeconds();

        // The graph is worked out a stop at a time as the search reaches it. See Links: it is the same
        // graph, and building all of it first is what a whole-network reading made unaffordable, because
        // it is every ride of every line however short the journey asked for is.
        Links links = new Links(roads, lines, nodes, byPosition, destinationName, preferences, wait);
        RoadRouter.Workspace workspace = RoadRouter.workspaceFor(roads.forWalks());

        double[] dist = new double[count];
        int[] fromNode = new int[count];
        Route[] fromRoute = new Route[count];
        TravelMode[] fromMode = new TravelMode[count];
        boolean[] settled = new boolean[count];
        Arrays.fill(dist, Double.MAX_VALUE);
        Arrays.fill(fromNode, -1);

        // Boarding: a walk from the player to one of the nearest stops, plus the wait for the first
        // service. Pushed on the same queue the links are, so a stop reached on foot and a stop
        // reached by riding compete on one footing.
        PriorityQueue<double[]> frontier =
                new PriorityQueue<>(Comparator.comparingDouble(entry -> entry[0]));
        for (int index : nearest(nodes, startX, startZ)) {
            Node node = nodes.get(index);
            Route walk = walk(workspace, startX, startZ, node.at().x(), node.at().z(),
                    destinationName, preferences, cache);
            if (!walk.isPresent()) {
                continue;
            }
            double seeded = walk.estimatedSeconds() + wait;
            if (seeded < dist[index]) {
                dist[index] = seeded;
                fromRoute[index] = walk.plusFixedSeconds(wait);
                fromMode[index] = TravelMode.WALK;
                frontier.add(new double[]{dist[index], index});
            }
        }

        // The stops a journey may finish at, which is where the search learns how well it is doing: the
        // destination is not one of the graph's stops, so the cost of getting off has to be added to a
        // stop's own cost to make a whole journey, and until that has been done there is nothing for the
        // search to stop at.
        List<Integer> alighting = nearest(nodes, goalX, goalZ);
        boolean[] mayAlight = new boolean[count];
        for (int index : alighting) {
            mayAlight[index] = true;
        }
        int bestEnd = -1;
        double bestTotal = Double.MAX_VALUE;
        Route bestFinish = null;

        // Dijkstra over the stops, ended as soon as nothing left could beat the best journey already
        // found.
        //
        // <h2>Why the early end is not an optimisation</h2>
        // This used to run the queue to exhaustion and work the alighting out afterwards, which settles
        // every stop the network can reach however short the journey is. That is invisible on a handful
        // of lines and it is the whole of the cost on a whole railway: measured against a network of
        // four hundred lines, a journey two stops long settled all sixteen hundred stations and planned
        // three thousand two hundred rides, exactly as many as a journey clean across it. "It is slow
        // even though the distance is small" is this loop, and building the graph a stop at a time
        // underneath it cannot help while the search visits every stop anyway.
        //
        // The queue is ordered by cost, so the cheapest unsettled stop is a lower bound on the cost of
        // every journey through any of them -- including the walk off at the far end, which only ever
        // adds. Once that bound reaches the best journey already found, nothing further can improve it,
        // and the rest of the queue is stops the answer does not go through.
        while (!frontier.isEmpty()) {
            double[] next = frontier.poll();
            int current = (int) next[1];
            if (settled[current]) {
                continue;
            }
            if (next[0] >= bestTotal) {
                break;
            }
            settled[current] = true;
            if (mayAlight[current]) {
                // Getting off here: the walk from this stop to the destination, which is the same walk
                // the alighting pass used to make from every candidate at the end.
                Node node = nodes.get(current);
                Route walk = walk(workspace, node.at().x(), node.at().z(), goalX, goalZ,
                        destinationName, preferences, cache);
                if (walk.isPresent() && dist[current] + walk.estimatedSeconds() < bestTotal) {
                    bestTotal = dist[current] + walk.estimatedSeconds();
                    bestEnd = current;
                    bestFinish = walk;
                }
            }
            for (Link link : links.outgoing(current)) {
                if (settled[link.target()]) {
                    continue;
                }
                double candidate = dist[current] + link.seconds();
                if (candidate < dist[link.target()]) {
                    dist[link.target()] = candidate;
                    fromNode[link.target()] = current;
                    fromRoute[link.target()] = link.route();
                    fromMode[link.target()] = link.mode();
                    frontier.add(new double[]{candidate, link.target()});
                }
            }
        }

        // The valve is about the graph the search was over, and every answer below -- including "no
        // journey" -- was reached on that graph.
        links.reportUnplanned();
        if (bestEnd < 0) {
            return Trip.empty();
        }

        List<Trip.Leg> reversed = new ArrayList<>();
        boolean rode = false;
        // Bounded by the node count: a Dijkstra chain cannot loop, and this is here so that a
        // malformed one is a refused journey rather than a hang.
        int guard = count + 1;
        for (int at = bestEnd; at >= 0 && guard-- > 0; at = fromNode[at]) {
            reversed.add(new Trip.Leg(fromRoute[at], fromMode[at]));
            rode |= fromMode[at] != TravelMode.WALK;
        }
        if (!rode) {
            // Walking to a stop and walking away from it again is not a journey by public transport.
            // The search offers exactly that whenever a line's stop happens to sit between the two
            // ends, because the two walks are a valid path through the graph -- and it is the cheaper
            // one whenever the walk to the stop follows a road the direct walk does not. Refusing it
            // here answers "no journey over the lines", which is the truth, and leaves the plain route
            // to have its say.
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

    // ------------------------------------------------------------------- graph

    /**
     * Every way out of every stop, planned before the search runs.
     *
     * <p>Rides first, then transfers: the rides are the expensive plans, and if the ride budget is
     * going to bite it should bite a ride rather than leave the journey with no way to change lines at
     * all -- a network whose rides have all been planned but whose transfers have not answers every
     * journey with a single-line detour, which is the failure this class was rewritten to remove.
     */
    private static List<List<Link>> buildLinks(RideRoads roads,
                                               List<TransitLine> lines, List<Node> nodes,
                                               Map<Long, Integer> byPosition,
                                               String destinationName, RoutePreferences preferences,
                                               Map<String, Route> cache, int[] rideBudget,
                                               double wait) {
        int count = nodes.size();
        List<List<Link>> links = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            links.add(new ArrayList<>());
        }

        int unplanned = 0;
        for (int index = 0; index < count; index++) {
            for (int[] call : nodes.get(index).calls()) {
                TransitLine line = lines.get(call[0]);
                int at = call[1];
                TravelMode mode = rideMode(line.kind());
                // The line's own class and nothing else. A mode is a set of classes, so the mode alone
                // would let a water line's ride come back along a rail; the policy is what makes the
                // type a player chose for a line mean something.
                RoutePreferences ridePolicy = ridePreferences(line.kind(), preferences);
                // And the network the line asked for: this is where a line with its marks switched off
                // is kept off them, rather than merely not adding a layer another line has added.
                RoadRouter.Workspace workspace = RoadRouter.workspaceFor(roads.forLine(line));
                for (int step = -1; step <= 1; step += 2) {
                    int next = at + step;
                    if (next < 0 || next >= line.stopCount()) {
                        continue;
                    }
                    LineStop to = line.stops().get(next);
                    Integer target = byPosition.get(positionKey(to.x(), to.z()));
                    if (target == null || target == index) {
                        continue;
                    }
                    // Keyed by direction, and planned from the end being travelled from: a stretch
                    // ridden the other way is not the same route reversed, because its turn-by-turn
                    // instructions have to point the way the rider is actually going.
                    String key = "R|" + line.id() + "|" + at + "|" + next;
                    Route ride = cache.get(key);
                    if (ride == null) {
                        if (rideBudget[0] <= 0) {
                            unplanned++;
                            continue;
                        }
                        rideBudget[0]--;
                        LineStop from = line.stops().get(at);
                        ride = RoadRouter.findRoute(workspace, from.x(), from.z(), to.x(), to.z(),
                                destinationName, mode, ridePolicy);
                        cache.put(key, ride);
                        if (!ride.isPresent()) {
                            // Named, because "no journey over 1 line(s)" cannot say which stretch of
                            // which line is the one that could not be ridden, and that is the only
                            // question worth asking.
                            HowToGo.LOGGER.info(
                                    "[HowToGo] line ride cannot be planned: '{}' -> '{}' ({})",
                                    from.label(), to.label(), line.kind().name());
                        }
                    }
                    if (!ride.isPresent()) {
                        continue;
                    }
                    links.get(index).add(new Link(target, ride.estimatedSeconds(), ride, mode));
                }
            }
        }
        if (unplanned > 0) {
            HowToGo.LOGGER.warn("[HowToGo] ride budget of {} plans exhausted: {} ride leg(s) were "
                            + "not planned, so the lines they belong to cannot be used at all. "
                            + "Connect the lines' stops to the network, or raise the budget in code.",
                    MAX_RIDE_PLANS, unplanned);
        }
        return links;
    }

    /**
     * Every way out of a stop, worked out when the search reaches it rather than before it starts.
     *
     * <h2>Why this is not done up front</h2>
     * It was, and the reason was sound while a plan ran over a handful of lines: the links are the
     * expensive part, planning them all first makes the search a plain Dijkstra over a graph that does
     * not move, and the budget that used to guard the planning could then only ever drop the tail of
     * the list rather than remove edges from a search already running.
     *
     * <p>What a reading of a whole railway changed is the arithmetic. The graph is every neighbouring
     * pair of stops of every line, in both directions, and it has nothing to do with the journey asked
     * for: a two-hundred-block trip across a network of three hundred lines plans twelve thousand rides,
     * every one of them a route the road router has to work out, and the player sees the map freeze
     * while it happens. Journey length not mattering was the symptom, and this is the cause.
     *
     * <p>Dijkstra does not need the whole graph. It needs the edges out of each stop at the moment it
     * settles that stop, because an edge is only ever relaxed from a settled node. So a stop's links are
     * planned when it is settled and kept for the one time they are asked for, and a short journey plans
     * the stops it actually reaches -- which is bounded by the cost of the answer rather than by the
     * size of the railway.
     *
     * <p>This is sound in a way the old lazy attempt was not: nothing here is capped per stop, so a
     * stop's links are always all of them, and the graph a running search sees never changes underneath
     * it. What is left of the budget is a budget on the whole plan, reported out loud if it is ever
     * reached, and reaching it now means a search that genuinely explored the network rather than one
     * that was asked a short question about it.
     */
    private static final class Links {

        private final RideRoads roads;
        private final List<TransitLine> lines;
        private final List<Node> nodes;
        private final Map<Long, Integer> byPosition;
        private final String destinationName;
        private final RoutePreferences preferences;
        private final double wait;

        /** The stops of the network filed by cell, for the transfer neighbourhood query. */
        private final Map<Long, List<Integer>> cells = new HashMap<>();
        /** The rides already planned, by line and direction, so a pair is planned once. */
        private final Map<String, Route> rides = new HashMap<>();
        /** One entry per stop: its links once worked out, or null while they have not been. */
        private final List<List<Link>> planned;
        private int budget = MAX_RIDE_PLANS;
        private int unplanned;

        Links(RideRoads roads, List<TransitLine> lines, List<Node> nodes,
              Map<Long, Integer> byPosition, String destinationName, RoutePreferences preferences,
              double wait) {
            this.roads = roads;
            this.lines = lines;
            this.nodes = nodes;
            this.byPosition = byPosition;
            this.destinationName = destinationName;
            this.preferences = preferences;
            this.wait = wait;
            for (int index = 0; index < nodes.size(); index++) {
                cells.computeIfAbsent(cellOf(nodes.get(index)), key -> new ArrayList<>()).add(index);
            }
            this.planned = new ArrayList<>(nodes.size());
            for (int index = 0; index < nodes.size(); index++) {
                planned.add(null);
            }
        }

        /**
         * Every way out of one stop: a ride to each neighbour on each line calling there, and a walk to
         * every other stop of a different line that stands near enough to be the same interchange.
         */
        List<Link> outgoing(int index) {
            List<Link> known = planned.get(index);
            if (known != null) {
                return known;
            }
            List<Link> links = new ArrayList<>();
            ridesFrom(index, links);
            transfersFrom(index, links);
            planned.set(index, links);
            return links;
        }

        /** The rides: one per direction, per line calling at this stop. */
        private void ridesFrom(int index, List<Link> links) {
            for (int[] call : nodes.get(index).calls()) {
                TransitLine line = lines.get(call[0]);
                int at = call[1];
                TravelMode mode = rideMode(line.kind());
                // The line's own class and nothing else. A mode is a set of classes, so the mode alone
                // would let a water line's ride come back along a rail; the policy is what makes the
                // type a player chose for a line mean something.
                RoutePreferences ridePolicy = ridePreferences(line.kind(), preferences);
                // And the network the line asked for: this is where a line with its marks switched off
                // is kept off them, rather than merely not adding a layer another line has added.
                RoadRouter.Workspace workspace = RoadRouter.workspaceFor(roads.forLine(line));
                for (int step = -1; step <= 1; step += 2) {
                    int next = at + step;
                    if (next < 0 || next >= line.stopCount()) {
                        continue;
                    }
                    LineStop to = line.stops().get(next);
                    Integer target = byPosition.get(positionKey(to.x(), to.z()));
                    if (target == null || target == index) {
                        continue;
                    }
                    // Keyed by direction, and planned from the end being travelled from: a stretch
                    // ridden the other way is not the same route reversed, because its turn-by-turn
                    // instructions have to point the way the rider is actually going.
                    String key = "R|" + line.id() + "|" + at + "|" + next;
                    Route ride = rides.get(key);
                    if (ride == null) {
                        if (budget <= 0) {
                            unplanned++;
                            continue;
                        }
                        budget--;
                        LineStop from = line.stops().get(at);
                        ride = RoadRouter.findRoute(workspace, from.x(), from.z(), to.x(), to.z(),
                                destinationName, mode, ridePolicy);
                        rides.put(key, ride);
                        if (!ride.isPresent()) {
                            // Named, because "no journey over 1 line(s)" cannot say which stretch of
                            // which line is the one that could not be ridden, and that is the only
                            // question worth asking.
                            HowToGo.LOGGER.info(
                                    "[HowToGo] line ride cannot be planned: '{}' -> '{}' ({})",
                                    from.label(), to.label(), line.kind().name());
                        }
                    }
                    if (!ride.isPresent()) {
                        continue;
                    }
                    links.add(new Link(target, ride.estimatedSeconds(), ride, mode));
                }
            }
        }

        /**
         * The transfers out of one stop: a measured hop to every other stop of the interchange it is in.
         *
         * <h2>Why a transfer is measured rather than planned</h2>
         * A transfer is a change of lines at one interchange, and an interchange is by definition two
         * stops within {@link #TRANSFER_RADIUS} of each other -- a platform and the stop beside it, not a
         * walk across town. Planning one is therefore asking the road router for a route it almost never
         * has an answer to: there are no roads inside a station, so the answer is absent or a detour, and
         * it is thrown away for the straight hop this builds anyway. What it cost to ask is the whole of
         * the world scanned, because the router finds its nearest road by walking every segment of the
         * network -- once for the start, once for the goal, and again on its fallback search. The legs
         * where following the roads does matter -- the walk from the player to the first stop and from the
         * last stop to the destination -- are still planned; see {@link #walk}.
         *
         * <h2>And why the near stops are found through a grid</h2>
         * A transfer is two stops within the radius, so the pairs that could be one are a neighbourhood
         * question, and asking it of every pair was the square of the network: invisible on the handful of
         * lines MTR's own client data can produce, and most of a second on the thousands a whole-network
         * reading brings. The grid is the same trick the road network's own spatial index uses, for the
         * same reason -- the pairs worth measuring are the pairs that are near each other, and finding
         * them should not cost the size of the world.
         */
        private void transfersFrom(int index, List<Link> links) {
            Node node = nodes.get(index);
            for (int other : interchangeGroup(index)) {
                if (other == index) {
                    continue;
                }
                Node target = nodes.get(other);
                // The hop the router would have fallen back to, built directly: the same shape,
                // the same pace, and no search of the world to arrive at it.
                Route hop = straightWalk(node.at().x(), node.at().z(), target.at().x(),
                        target.at().z(), destinationName);
                if (!hop.isPresent()) {
                    continue;
                }
                links.add(new Link(other, hop.estimatedSeconds() + wait,
                        hop.plusFixedSeconds(wait), TravelMode.WALK));
            }
        }

        /**
         * Every stop of the interchange this one stands in, itself included.
         *
         * <h2>Why a group rather than the stops within the radius</h2>
         * "Within the radius" is not a statement about a place, it is a statement about a pair, and a
         * place is what an interchange is. A platform, the stop beside it and the stop beside that are
         * one place to walk through even when the two ends of it are more than the radius apart, which is
         * why the map draws one orange marker for them rather than two -- see
         * {@code TransitInterchanges}, which grows its groups the same way. The planner used to allow
         * only the pairs that were directly within the radius, so a journey could change lines onto the
         * middle stop of such a place and then not onto the far one, while the map went on drawing the
         * whole of it as a single place to change at. The two now answer the same question the same way.
         *
         * <p>Started from a stop and grown while it keeps finding stops near one already in: the same
         * walk the map does, and only over the stops the budget below cares about -- the grid keeps it to
         * the neighbourhood rather than to every pair of stops on the railway.
         */
        private List<Integer> interchangeGroup(int index) {
            Map<Integer, List<Integer>> groups = interchangeGroups();
            List<Integer> group = groups.get(index);
            return group == null ? List.of(index) : group;
        }

        /**
         * The stops of this network grouped into interchanges, worked out once per plan.
         *
         * <p>Lazily, because a plan that never asks for a transfer out of a stop never needs it, and
         * built in one pass rather than per stop: the growth is a walk over the whole group, so doing it
         * per stop would make a hub of a hundred stops cost a hundred walks of a hundred.
         */
        private Map<Integer, List<Integer>> interchangeGroups() {
            if (interchangeGroups != null) {
                return interchangeGroups;
            }
            Map<Integer, List<Integer>> built = new HashMap<>();
            boolean[] grouped = new boolean[nodes.size()];
            for (int seed = 0; seed < nodes.size(); seed++) {
                if (grouped[seed]) {
                    continue;
                }
                List<Integer> group = new ArrayList<>();
                group.add(seed);
                grouped[seed] = true;
                for (int at = 0; at < group.size(); at++) {
                    for (int other : neighboursOf(group.get(at))) {
                        if (!grouped[other]) {
                            grouped[other] = true;
                            group.add(other);
                        }
                    }
                }
                if (group.size() > 1) {
                    for (int member : group) {
                        built.put(member, group);
                    }
                }
            }
            interchangeGroups = built;
            return built;
        }

        /** The stops one stop could change lines at: near enough, and call at another line. */
        private List<Integer> neighboursOf(int index) {
            Node node = nodes.get(index);
            int cellX = (int) Math.floor(node.at().x() / TRANSFER_CELL);
            int cellZ = (int) Math.floor(node.at().z() / TRANSFER_CELL);
            List<Integer> found = new ArrayList<>();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    List<Integer> nearby = cells.get(cellKey(cellX + dx, cellZ + dz));
                    if (nearby == null) {
                        continue;
                    }
                    for (int other : nearby) {
                        if (other == index) {
                            continue;
                        }
                        Node target = nodes.get(other);
                        // The distance first, because it is two subtractions and it is what most pairs
                        // fail: the call lists are only worth reading once the two stops are close
                        // enough to be one interchange at all.
                        double dx2 = target.at().x() - node.at().x();
                        double dz2 = target.at().z() - node.at().z();
                        if (dx2 * dx2 + dz2 * dz2 > TRANSFER_RADIUS * TRANSFER_RADIUS
                                || !touchesOtherLine(node, target)) {
                            continue;
                        }
                        found.add(other);
                    }
                }
            }
            return found;
        }

        /** The interchanges, worked out the first time a transfer is asked for. */
        private Map<Integer, List<Integer>> interchangeGroups;

        /** Says so, once, if the plan ever asked for more rides than the valve allows. */
        void reportUnplanned() {
            if (unplanned > 0) {
                HowToGo.LOGGER.warn("[HowToGo] ride budget of {} plans exhausted: {} ride leg(s) were "
                                + "not planned, so the lines they belong to cannot be used at all. "
                                + "Connect the lines' stops to the network, or raise the budget in code.",
                        MAX_RIDE_PLANS, unplanned);
            }
        }
    }

    /**
     * The cell a stop is filed under for the transfer pass.
     *
     * <p>The size is the transfer radius, so looking at the nine cells around a stop reaches every stop
     * within it and no further: one cell size smaller would miss the corners of the neighbourhood, and
     * one larger would put stops in the same cell that are further apart than any transfer.
     */
    private static long cellOf(Node node) {
        return cellKey((int) Math.floor(node.at().x() / TRANSFER_CELL),
                (int) Math.floor(node.at().z() / TRANSFER_CELL));
    }

    /** A grid cell as one key. */
    private static long cellKey(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    private static Route walk(RoadRouter.Workspace workspace, double startX, double startZ,
                              double goalX, double goalZ, String destinationName,
                              RoutePreferences preferences, Map<String, Route> cache) {
        // Exact rather than rounded: a rounded key would answer a query about one position with a
        // route planned to another half a block away, and the trip's own geometry is built from the
        // route's points.
        String key = "T|" + startX + "," + startZ + "|" + goalX + "," + goalZ;
        Route cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        double straight = Math.hypot(goalX - startX, goalZ - startZ);
        if (straight <= STATIONARY_DISTANCE) {
            // Standing on the stop, or bound for the stop one is standing on. There is nothing to
            // walk, and the route that says so still needs two points to be a route.
            Route still = stationary(startX, startZ, destinationName);
            cache.put(key, still);
            return still;
        }
        Route planned = RoadRouter.findRoute(workspace, startX, startZ, goalX, goalZ, destinationName,
                TravelMode.WALK, preferences);
        // A walk that wanders is not a walk to a station. The router follows the roads, and off-road
        // walking is costed several times worse than road walking, so a long way round on a road can
        // beat a short straight hop -- which is how a station beside the player ends up reached by
        // walking away from it first and coming back. Past this factor the road's answer is refused and
        // the walk becomes what it always was at the ends of a trip: a straight connector.
        if (!planned.isPresent() || planned.totalLength() > straight * WALK_DETOUR_LIMIT) {
            if (!planned.isPresent() && straight > SHORT_HOP) {
                // Said out loud, because this is the one place a broken road network admits itself.
                // A public transport journey whose walking legs are straight lines still draws and
                // still gives a time, so a network the router cannot walk at all looks like a working
                // journey here -- while the same network in walking mode answers "no route". If the
                // log is full of these, the roads are what is wrong, not the lines.
                HowToGo.LOGGER.info("[HowToGo] no road route to walk from ({}, {}) to ({}, {}); the "
                                + "{} block walk is drawn as a straight hop",
                        Math.round(startX), Math.round(startZ), Math.round(goalX), Math.round(goalZ),
                        Math.round(straight));
            }
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
     * The walk between a point and itself: nothing to walk, nothing to draw, no time.
     *
     * <p>The second point is a thousandth of a block from the first, which is under every tolerance in
     * the mod and over the builder's duplicate test -- so the route is a dot rather than a gap, and the
     * journey through the stop it was planned to is a journey at all.
     */
    private static Route stationary(double x, double z, String destinationName) {
        Route.Builder builder = new Route.Builder();
        builder.setDestinationName(destinationName);
        builder.setTravelMode(TravelMode.WALK);
        builder.setOffRoadSpeedFactor(1.0);
        double tolerance = RoadConfig.onRoadTolerance(RoadClass.ROAD);
        builder.addPoint(x, z, tolerance, 0, null, false);
        builder.addPoint(x + 1.0E-3, z, tolerance, 0, null, false);
        return builder.build();
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
        Map<Long, Integer> byPosition = new HashMap<>();
        for (int l = 0; l < lines.size(); l++) {
            TransitLine line = lines.get(l);
            for (int s = 0; s < line.stopCount(); s++) {
                LineStop stop = line.stops().get(s);
                Integer existing = byPosition.get(positionKey(stop.x(), stop.z()));
                if (existing == null) {
                    List<int[]> calls = new ArrayList<>();
                    calls.add(new int[]{l, s});
                    byPosition.put(positionKey(stop.x(), stop.z()), nodes.size());
                    nodes.add(new Node(stop, calls));
                } else {
                    nodes.get(existing).calls().add(new int[]{l, s});
                }
            }
        }
        return nodes;
    }

    /** Where each stop position ended up, so finding the node for a stop is a lookup. */
    private static Map<Long, Integer> positions(List<Node> nodes) {
        Map<Long, Integer> byPosition = new HashMap<>();
        for (int i = 0; i < nodes.size(); i++) {
            LineStop stop = nodes.get(i).at();
            byPosition.put(positionKey(stop.x(), stop.z()), i);
        }
        return byPosition;
    }

    /** A block position as one key. Two stops are the same stop when they share both coordinates. */
    private static long positionKey(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
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
