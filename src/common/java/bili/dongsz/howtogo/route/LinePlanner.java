package bili.dongsz.howtogo.route;

import bili.dongsz.howtogo.HowToGo;
import bili.dongsz.howtogo.RoadConfig;
import bili.dongsz.howtogo.road.RoadClass;
import bili.dongsz.howtogo.road.RoadNetwork;
import bili.dongsz.howtogo.road.RoadSegment;
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
 * The graph has one node per stop position and two kinds of edge: ride between neighbouring stops of
 * one line, and transfer between stops of different lines standing within TRANSFER_RADIUS. A ride
 * follows the line's order; a one-way line gets only its own direction, since a journey the line
 * cannot carry is not a journey. Two lines calling at the same block are one node, so that change
 * costs nothing.
 * One search, not two: a transfer costs the walk between platforms plus TRANSFER_PENALTY_SECONDS, so
 * a direct ride is not vetoed, only priced -- the old veto made the best single-line journey the
 * answer however far around it went.
 * All links are planned before the search, so Dijkstra runs over a graph that does not move; the
 * budget can then only drop the tail of the ride list, once, and say so.
 * Only the WALK_CANDIDATES nearest stops at each end are tried; trying every stop would plan a walk
 * route per stop on every press.
 */
public final class LinePlanner {

    /** How many stops at each end are considered as boarding and alighting points. */
    private static final int WALK_CANDIDATES = 12;

    /**
     * How far apart two stops of different lines may stand and still count as one interchange. Not zero,
     * because a station built from two lines is rarely one block, and not large or a change of lines
     * becomes a walk across town.
     */
    private static final double TRANSFER_RADIUS = 24.0;

    /**
     * The same number, for the map, so it cannot mark a different set of interchange places than the
     * planner uses.
     */
    public static double transferRadius() {
        return TRANSFER_RADIUS;
    }

    /**
     * Whether a destination is near enough that walking is the whole answer, in blocks. A straight-line
     * question about how near the place is, not how the streets run; it says nothing about whether a
     * journey exists -- worthRiding owns that.
     */
    public static boolean closeEnoughToWalk(double startX, double startZ, double goalX,
                                            double goalZ) {
        return Math.hypot(goalX - startX, goalZ - startZ) <= CLOSE_ENOUGH_TO_WALK;
    }

    /**
     * The cell the transfer pass files a stop under: the radius itself, so nine cells around a stop are
     * exactly its neighbourhood.
     */
    private static final double TRANSFER_CELL = TRANSFER_RADIUS;

    /**
     * Ceiling on the number of ride legs planned in one search: a safety valve, applied while the graph
     * is built, so tripping it drops the lines at the end of the list rather than removing edges from a
     * running search, and it is always reported. It was 48, which a network of five lines already
     * exceeded; a whole railway read from a server is thousands of rides, and the per-ride cost (a
     * search over that line's own track) is what makes 20000 safe.
     */
    private static final int MAX_RIDE_PLANS = 20_000;

    /** How many journeys one plan offers: the recommendation and two alternatives at most. */
    private static final int MAX_PLANS = 3;

    /**
     * How far the player may be asked to walk to reach a stop, in blocks. Past this no stop is near
     * enough for the journey to be worth taking by line.
     */
    private static final double MAX_WALK_TO_STOP = 256.0;

    /**
     * How near a destination has to be for walking to be the whole answer, in blocks: the same distance
     * the planner would ask a player to walk to board a line. Answered by closeEnoughToWalk, never by the
     * search.
     */
    private static final double CLOSE_ENOUGH_TO_WALK = MAX_WALK_TO_STOP;

    /**
     * How close two positions have to be before the walk between them is no walk. A route needs two
     * points and the builder drops a duplicate, so a stop exactly where the player is -- or exactly on
     * the destination -- used to be skipped as unusable.
     */
    private static final double STATIONARY_DISTANCE = 1.0E-6;

    /**
     * How long a walk has to be before falling back to a straight hop is worth a log line: a few blocks
     * off a road is not news, a hundred is.
     */
    private static final double SHORT_HOP = 8.0;

    /**
     * Shortest piece of actual track a leg must ride to count as public transport, in blocks. A ride is
     * two hops plus the stretch between them; with no stretch it is a line between two platforms.
     */
    private static final double MIN_RIDE_TRACK = 1.0;

    /**
     * How much closer to the destination a ride has to carry the rider, in blocks, measured as net ground
     * won: straight-line distance from the first stop boarded at to the destination, less the same from
     * the last stop got off at. Riding out and back nets nothing however much track it covered. The
     * bound is the walk between two platforms of one interchange -- walking already asked of a rider for
     * nothing. Small on purpose: a ride clearing it is never second-guessed, and one below it is only
     * dropped if the plan is no quicker than the walk that replaces it.
     */
    private static final double MIN_RIDE_GAIN = 24.0;

    /**
     * Ceiling on the walking routes planned for interchanges in one plan: a dense set of platforms must
     * not make a short journey spend seconds on walks. Past it the straight hop is used.
     */
    private static final int MAX_TRANSFER_WALK_PLANS = 128;

    /**
     * How far apart two stops of different lines may stand and still be joined by a walk, when public
     * transport may mix walking and lines, in blocks. Past a few hundred blocks the walk is no longer a
     * connection between lines but a journey on foot with two rides attached. The walk is planned by the
     * walking router, not drawn straight, so it follows real roads and takes the time that walk takes.
     */
    private static final double MIXED_WALK_RADIUS = 256.0;

    /** The cell the mixed walking pass files a stop under: the reach itself. */
    private static final double MIXED_WALK_CELL = MIXED_WALK_RADIUS;

    /**
     * How many stops one stop offers a mixed walking link to: the nearest of other lines. A cap because
     * each link is a real walking route.
     */
    private static final int MIXED_WALK_CANDIDATES = 8;

    /**
     * How near a walkable road a stop must stand for a mixed middle leg to be looked for, in blocks. A
     * stop far from any road would make the leg a field crossing with a road at one end.
     */
    private static final double MIXED_WALK_STOP_REACH = 32.0;

    /**
     * Ceiling on the mixed walking routes planned in one plan, for the same reason as MAX_RIDE_PLANS.
     * Reaching it drops the tail of the links and reports the count once.
     */
    private static final int MAX_MIXED_WALK_PLANS = 256;

    private static volatile boolean mixedBudgetWarned;

    /** The length of the part of a ride that is actually on the line, connectors excluded. */
    private static double trackLength(Route ride) {
        return ride.totalLength() - ride.startConnector() - ride.goalConnector();
    }

    private LinePlanner() {
    }

    private record Node(LineStop at, List<int[]> calls) {
    }

    /**
     * One way out of a stop: where it leads, what the search pays, and the route there. The seconds are
     * the whole cost and the route carries the same cost, so the search minimises exactly the number the
     * readout shows.
     */
    private record Link(int target, double seconds, Route route, TravelMode mode, RideInfo info,
                        boolean transfer) {
    }

    /**
     * The line and the two stops one ride link runs between, carried because the search only sees costs.
     * The indices are into the line's own order, so the stops called at are read off the line rather
     * than guessed from geometry.
     */
    private record RideInfo(TransitLine line, int fromStop, int toStop) {
    }

    private record PendingLeg(Route route, TravelMode mode, RideInfo info) {
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
            HowToGo.diagnostic("[HowToGo] public transport: {} leg(s)", trip.legs().size());
        } else {
            HowToGo.diagnostic("[HowToGo] public transport: no journey -- {} stop(s) in the network, "
                    + "origin ({}, {}), goal ({}, {})", buildNodes(lines).size(), Math.round(startX),
                    Math.round(startZ), Math.round(goalX), Math.round(goalZ));
        }
        return trip;
    }

    /**
     * Everything one journey search needs, built once so the alternative searches can share it. The
     * expensive part is the rides, each a road route cached in Links, so a second search reuses every
     * ride the first planned; that is what makes several plans cost less than several plans' worth.
     */
    private record Context(List<Node> nodes, Map<Long, Integer> byPosition, Map<String, Route> cache,
                           double serviceWait, String destinationName, RoutePreferences preferences,
                           RoadRouter.Workspace workspace, Links links) {
    }

    private record Journey(Trip trip, Set<TransitLine> lines) {

        static final Journey EMPTY = new Journey(Trip.empty(), Set.of());

        boolean isPresent() {
            return trip.isPresent();
        }
    }

    /**
     * What each part of a journey costs one search, so several searches can ask different questions of
     * one graph. A weight multiplies the search's cost and nothing else: the journey still reports the
     * real time it takes.
     */
    private record Weights(double walking, double transfers, double lines,
                           Set<TransitLine> discouraged) {

        static final Weights NONE = new Weights(1.0, 1.0, 1.0, Set.of());

        static Weights fewerTransfers() {
            return new Weights(1.0, 3.0, 1.0, Set.of());
        }

        static Weights lessWalking() {
            return new Weights(3.0, 3.0, 1.0, Set.of());
        }

        static Weights otherLines(Set<TransitLine> lines) {
            return new Weights(1.0, 1.0, 4.0, Set.copyOf(lines));
        }

        double cost(Link link) {
            if (link.info() != null) {
                return discouraged.contains(link.info().line()) ? link.seconds() * lines
                        : link.seconds();
            }
            // A walk: an interchange, a mixed leg through the streets, or a hop with no road behind it. The two
            // weights are how "fewer changes" and "less walking" are told apart.
            return link.seconds() * (link.transfer() ? transfers : walking);
        }
    }

    /**
     * The graph and workspaces one plan's searches share, or null when there are no stops. The stop list
     * is the whole vertex set, and saying so once keeps every search below from having to.
     */
    private static Context prepare(RideRoads roads, List<TransitLine> lines, String destinationName,
                                   RoutePreferences preferences) {
        List<Node> nodes = buildNodes(lines);
        if (nodes.isEmpty()) {
            return null;
        }
        Map<Long, Integer> byPosition = positions(nodes);
        Map<String, Route> cache = new HashMap<>();
        double wait = RoadConfig.transitWaitSeconds();        // Read once, so one plan cannot mix links from one policy with links from another.
        boolean mixed = RoadConfig.transitMixed();
        RoadRouter.Workspace workspace = RoadRouter.workspaceFor(roads.forWalks());
        // What a mixed middle leg can stand on: the walking network filed in a grid, so a stop can be asked
        // cheaply whether a walk from it would touch the roads. Without that question a crowd of roadless
        // stops burns the whole mixed budget on plans that are thrown away.
        WalkRoads walkRoads = mixed ? new WalkRoads(roads.forWalks(), preferences) : null;

        // The graph is worked out a stop at a time as the search reaches it; building all of it first is what
        // a whole-network reading made unaffordable.
        Links links = new Links(roads, lines, nodes, byPosition, destinationName, preferences, wait,
                workspace, mixed, walkRoads);
        return new Context(nodes, byPosition, cache, wait, destinationName, preferences, workspace,
                links);
    }

    private static Trip search(RideRoads roads, List<TransitLine> lines, double startX,
                               double startZ, double goalX, double goalZ, String destinationName,
                               RoutePreferences preferences) {
        Context context = prepare(roads, lines, destinationName, preferences);
        if (context == null) {
            return Trip.empty();
        }
        Journey best = searchOnce(context, startX, startZ, goalX, goalZ, Weights.NONE);
        return worthRiding(context, best, startX, startZ, goalX, goalZ) ? best.trip()
                : Trip.empty();
    }

    /**
     * The journey planned under several strategies at once, best first, at most MAX_PLANS. Questions are
     * asked in the order a picker shows them: recommendation, fewer changes, less walking, other lines.
     * A strategy that finds the same journey again has found the same plan, so results are deduplicated
     * by what the journey actually does. A journey that walking would beat is not a plan (see
     * worthRiding), and every candidate goes through the same test.
     * @return the journeys, best first; empty when no line can carry the trip, or when the ride found is
     * not worth taking and the walk is the honest answer
     */
    public static List<Trip> alternatives(RideRoads roads, List<TransitLine> lines, double startX,
                                          double startZ, double goalX, double goalZ,
                                          String destinationName, RoutePreferences preferences) {
        Context context = prepare(roads, lines, destinationName, preferences);
        if (context == null) {
            return List.of();
        }
        List<Trip> journeys = new ArrayList<>(MAX_PLANS);
        Journey best = searchOnce(context, startX, startZ, goalX, goalZ, Weights.NONE);
        if (!worthRiding(context, best, startX, startZ, goalX, goalZ)) {
            return journeys;
        }
        journeys.add(best.trip());
        addIfNew(context, journeys, searchOnce(context, startX, startZ, goalX, goalZ,
                Weights.fewerTransfers()), startX, startZ, goalX, goalZ);
        if (journeys.size() < MAX_PLANS) {
            addIfNew(context, journeys, searchOnce(context, startX, startZ, goalX, goalZ,
                    Weights.lessWalking()), startX, startZ, goalX, goalZ);
        }
        if (journeys.size() < MAX_PLANS) {
            addIfNew(context, journeys, searchOnce(context, startX, startZ, goalX, goalZ,
                    Weights.otherLines(best.lines())), startX, startZ, goalX, goalZ);
        }
        return journeys;
    }

    private static void addIfNew(Context context, List<Trip> journeys, Journey candidate,
                                 double startX, double startZ, double goalX, double goalZ) {
        if (journeys.size() >= MAX_PLANS
                || !worthRiding(context, candidate, startX, startZ, goalX, goalZ)) {
            return;
        }
        String signature = signatureOf(candidate.trip());
        for (Trip known : journeys) {
            if (signatureOf(known).equals(signature)) {
                return;
            }
        }
        journeys.add(candidate.trip());
    }

    private static String signatureOf(Trip trip) {
        StringBuilder signature = new StringBuilder();
        for (Trip.Ride ride : trip.rides()) {
            signature.append('|').append(ride.line()).append(':').append(ride.boardedAt())
                    .append('>').append(ride.leftAt());
        }
        return signature.toString();
    }

    /**
     * Whether the journey the search found is worth taking rather than walking the whole way.
     * The search runs two states per stop -- before the first ride and after it -- and only the second
     * may finish, so a cheaper walk is not handed back as public transport. The price is that a
     * destination near the stop the player is beside answers with "ride out to the next station and
     * back", the cheapest journey that rides anything.
     * The ride is judged by the ground it wins, not the line it is on: a two-way line is a service, and
     * a ferry across a river legitimately rides between close stops. rideGain is that measure, and
     * MIN_RIDE_GAIN the amount below which the line has not moved the rider closer in any way that
     * matters.
     * A small gain is not refused on its own: the destination may be behind a wall the ride is the only
     * way through. The walk to the destination is planned -- a real walking route, not the straight hop
     * -- and the journey is refused only when that walk exists and is at least as quick. The walk is
     * planned only for the suspicious shape and through the plan's cache.
     */
    private static boolean worthRiding(Context context, Journey journey, double startX,
                                       double startZ, double goalX, double goalZ) {
        if (!journey.isPresent()) {
            return false;
        }
        Trip trip = journey.trip();
        double gain = rideGain(trip, goalX, goalZ);
        if (gain > MIN_RIDE_GAIN) {
            return true;
        }
        Route onFoot = pureWalk(context, startX, startZ, goalX, goalZ);
        if (!onFoot.isPresent()) {
            return true;
        }
        boolean worth = trip.estimatedSeconds() < onFoot.estimatedSeconds();
        if (!worth) {
            // Said when it happens, because "public transport reports no journey" otherwise says nothing about
            // whether the network or the refused plan is the reason.
            HowToGo.diagnostic("[HowToGo] public transport: the ride gains {} blocks towards the "
                            + "destination, the journey is {} s and walking is {} s -- the walk is "
                            + "the answer", Math.round(gain), Math.round(trip.estimatedSeconds()),
                    Math.round(onFoot.estimatedSeconds()));
        }
        return worth;
    }

    /**
     * How much closer to the destination the riding carried the rider, in blocks: from the first stop
     * boarded at to the last got off at, along the straight line to the destination. Positive infinity
     * for a journey that rides nothing, so the filter has no opinion on it.
     */
    private static double rideGain(Trip trip, double goalX, double goalZ) {
        List<Trip.Ride> rides = trip.rides();
        if (rides.isEmpty()) {
            return Double.POSITIVE_INFINITY;
        }
        Trip.Ride first = rides.get(0);
        Trip.Ride last = rides.get(rides.size() - 1);
        if (first.stops().isEmpty() || last.stops().isEmpty()) {
            return Double.POSITIVE_INFINITY;
        }
        Trip.RideStop board = first.stops().get(0);
        Trip.RideStop alight = last.stops().get(last.stops().size() - 1);
        return Math.hypot(board.x() - goalX, board.z() - goalZ)
                - Math.hypot(alight.x() - goalX, alight.z() - goalZ);
    }

    /**
     * The walk from the player to the destination, planned by the walking router and cached for the plan.
     * Deliberately not the straight-hop helper: the comparison decides whether the request is answered
     * with the walk instead, so it must be made against a walk that exists. Cached so several journeys
     * of one plan share one walking route.
     */
    private static Route pureWalk(Context context, double startX, double startZ, double goalX,
                                  double goalZ) {
        String key = "W|" + startX + "," + startZ + "|" + goalX + "," + goalZ;
        Route known = context.cache().get(key);
        if (known != null) {
            return known;
        }
        Route planned;
        if (Math.hypot(goalX - startX, goalZ - startZ) <= STATIONARY_DISTANCE) {
            planned = stationary(startX, startZ, context.destinationName());
        } else {
            planned = RoadRouter.findRoute(context.workspace(), startX, startZ, goalX, goalZ,
                    context.destinationName(), TravelMode.WALK, context.preferences());
            if (!planned.isPresent()) {
                planned = Route.empty();
            }
        }
        context.cache().put(key, planned);
        return planned;
    }

    /**
     * One Dijkstra over the stop graph, with the cost of each part set by weights. Split out so the graph
     * can be shared between the recommendation and the alternatives.
     */
    private static Journey searchOnce(Context context, double startX, double startZ, double goalX,
                                      double goalZ, Weights weights) {
        List<Node> nodes = context.nodes();
        Map<Long, Integer> byPosition = context.byPosition();
        Map<String, Route> cache = context.cache();
        double wait = context.serviceWait();
        String destinationName = context.destinationName();
        RoutePreferences preferences = context.preferences();
        Links links = context.links();
        RoadRouter.Workspace workspace = context.workspace();
        int count = nodes.size();

        // Two copies of the stop graph: reaching a stop before the first ride (state 0) and after any ride
        // (state 1). Only state 1 may finish, so a walk cheaper than any line cannot be the answer; without
        // the split the search found the cheapest path and refused it for having no ride.
        int states = count * 2;
        double[] dist = new double[states];
        int[] fromState = new int[states];
        Route[] fromRoute = new Route[states];
        TravelMode[] fromMode = new TravelMode[states];
        Link[] fromLink = new Link[states];
        boolean[] settled = new boolean[states];
        Arrays.fill(dist, Double.MAX_VALUE);
        Arrays.fill(fromState, -1);

        // Boarding: a walk from the player to one of the nearest stops plus the wait for the first service,
        // pushed on the same queue as the links so foot and ride compete on one footing. State before the
        // first ride.
        PriorityQueue<double[]> frontier =
                new PriorityQueue<>(Comparator.comparingDouble(entry -> entry[0]));
        for (int index : nearest(nodes, startX, startZ)) {
            Node node = nodes.get(index);
            Route walk = walk(workspace, startX, startZ, node.at().x(), node.at().z(),
                    destinationName, preferences, cache);
            if (!walk.isPresent()) {
                continue;
            }
            double seeded = walk.estimatedSeconds() * weights.walking() + wait;
            int state = stateOf(index, false);
            if (seeded < dist[state]) {
                dist[state] = seeded;
                fromRoute[state] = walk.plusFixedSeconds(wait);
                fromMode[state] = TravelMode.WALK;
                frontier.add(new double[]{dist[state], state});
            }
        }

        // The stops a journey may finish at: the destination is not a graph node, so the cost of getting off
        // must be added before there is anything for the search to stop at.
        List<Integer> alighting = nearest(nodes, goalX, goalZ);
        boolean[] mayAlight = new boolean[count];
        for (int index : alighting) {
            mayAlight[index] = true;
        }
        int bestEnd = -1;
        double bestTotal = Double.MAX_VALUE;
        Route bestFinish = null;

        // Dijkstra over the stops, ended as soon as nothing left could beat the best journey found.
        // Running the queue to exhaustion settles every reachable stop however short the journey is: on a
        // 400-line network a two-stop journey settled all 1600 stations. The queue is ordered by cost, so the
        // cheapest unsettled stop bounds every journey through it; once that bounds the best found, the rest
        // cannot improve.
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
            int stop = stopOf(current);
            boolean ridden = hasRidden(current);
            if (ridden && mayAlight[stop]) {
                // Getting off here: the walk from this stop to the destination, the same walk the alighting pass used
                // to make. Asked only after a ride, because a journey that rides nothing is not public transport.
                Node node = nodes.get(stop);
                Route walk = walk(workspace, node.at().x(), node.at().z(), goalX, goalZ,
                        destinationName, preferences, cache);
                if (walk.isPresent()
                        && dist[current] + walk.estimatedSeconds() * weights.walking() < bestTotal) {
                    bestTotal = dist[current] + walk.estimatedSeconds() * weights.walking();
                    bestEnd = current;
                    bestFinish = walk;
                }
            }
            for (Link link : links.outgoing(stop)) {
                int target = stateOf(link.target(), ridden || link.mode() != TravelMode.WALK);
                if (settled[target]) {
                    continue;
                }
                double candidate = dist[current] + weights.cost(link);
                if (candidate < dist[target]) {
                    dist[target] = candidate;
                    fromState[target] = current;
                    fromRoute[target] = link.route();
                    fromMode[target] = link.mode();
                    fromLink[target] = link;
                    frontier.add(new double[]{candidate, target});
                }
            }
        }

        links.reportUnplanned();
        if (bestEnd < 0) {
            return Journey.EMPTY;
        }

        List<PendingLeg> reversed = new ArrayList<>();
        int guard = states + 1;
        for (int at = bestEnd; at >= 0 && guard-- > 0; at = fromState[at]) {
            Link link = fromLink[at];
            reversed.add(new PendingLeg(fromRoute[at], fromMode[at],
                    link == null ? null : link.info()));
        }
        java.util.Collections.reverse(reversed);
        reversed.add(new PendingLeg(bestFinish, TravelMode.WALK, null));

        // The legs rebuilt in travelling order with what each rides, and each ride's stop distances counted as
        // the chain is walked -- only here is it known how much of the journey comes before a ride.
        // Ride edges run between neighbouring stops, so a rider staying on through three stations is three
        // legs and one ride; every leg of a run carries the same ride, and Trip.rides() reads the run as one.
        List<Double> offsets = new ArrayList<>(reversed.size());
        double travelled = 0;
        for (PendingLeg leg : reversed) {
            offsets.add(travelled);
            travelled += leg.route().totalLength();
        }
        List<Trip.Leg> legs = new ArrayList<>(reversed.size());
        int at = 0;
        while (at < reversed.size()) {
            if (reversed.get(at).info() == null) {
                legs.add(new Trip.Leg(reversed.get(at).route(), reversed.get(at).mode(), null));
                at++;
                continue;
            }
            int end = at;
            while (end + 1 < reversed.size() && continues(reversed.get(end), reversed.get(end + 1))) {
                end++;
            }
            Trip.Ride ride = rideOf(reversed, at, end, offsets);
            for (int i = at; i <= end; i++) {
                legs.add(new Trip.Leg(reversed.get(i).route(), reversed.get(i).mode(), ride));
            }
            at = end + 1;
        }

        // The first and last stops of the chain, where the player boards and gets off. The links are states,
        // so a stop is what its index names either way round.
        int first = bestEnd;
        while (fromState[first] >= 0) {
            first = fromState[first];
        }
        // The lines the journey actually rides, kept because the "other lines" alternative needs them and a
        // ride's label cannot be turned back into its line object.
        Set<TransitLine> ridden = new java.util.HashSet<>();
        for (PendingLeg leg : reversed) {
            if (leg.info() != null) {
                ridden.add(leg.info().line());
            }
        }
        return new Journey(Trip.of(legs, nodes.get(stopOf(first)).at().label(),
                nodes.get(stopOf(bestEnd)).at().label()), ridden);
    }

    private static int stateOf(int stop, boolean ridden) {
        return stop * 2 + (ridden ? 1 : 0);
    }

    private static int stopOf(int state) {
        return state / 2;
    }

    private static boolean hasRidden(int state) {
        return (state & 1) == 1;
    }

    /**
     * Whether the second leg continues the first: one line, one way along it, and the very next stop. A
     * change of line or direction breaks the run, because the rider has to be told which way to go.
     */
    private static boolean continues(PendingLeg here, PendingLeg next) {
        RideInfo before = here.info();
        RideInfo after = next.info();
        if (before == null || after == null || before.line() != after.line()) {
            return false;
        }
        if (before.toStop() != after.fromStop()) {
            return false;
        }
        return Integer.signum(before.toStop() - before.fromStop())
                == Integer.signum(after.toStop() - after.fromStop());
    }

    /**
     * The ride a run of hops is, or null for a run that goes nowhere. The stops of the run are its ends;
     * their distances are the legs' own offsets, so nothing can drift from the distance the navigation
     * measures. The direction is the line's own order, and the terminus named is the end being
     * approached.
     * @param legs    every leg of the journey, in travelling order
     * @param from    the first leg of the run
     * @param to      the last leg of the run
     * @param offsets where each leg begins along the whole journey
     */
    private static Trip.Ride rideOf(List<PendingLeg> legs, int from, int to, List<Double> offsets) {
        RideInfo first = legs.get(from).info();
        RideInfo last = legs.get(to).info();
        if (first == null || last == null) {
            return null;
        }
        TransitLine line = first.line();
        if (!line.stops().isEmpty() && (first.fromStop() < 0 || last.toStop() < 0
                || first.fromStop() >= line.stopCount() || last.toStop() >= line.stopCount())) {
            return null;
        }
        List<Trip.RideStop> stops = new ArrayList<>();
        for (int i = from; i <= to; i++) {
            RideInfo info = legs.get(i).info();
            LineStop boarded = line.stops().get(info.fromStop());
            stops.add(new Trip.RideStop(boarded.label(), boarded.x(), boarded.z(), offsets.get(i)));
            if (i == to) {
                LineStop left = line.stops().get(info.toStop());
                stops.add(new Trip.RideStop(left.label(), left.x(), left.z(),
                        offsets.get(i) + legs.get(i).route().totalLength()));
            }
        }
        int terminusIndex = last.toStop() >= first.fromStop() ? line.stopCount() - 1 : 0;
        return new Trip.Ride(line.label(), line.stops().get(terminusIndex).label(),
                line.stops().get(first.fromStop()).label(),
                line.stops().get(last.toStop()).label(), stops);
    }


    /**
     * Every way out of every stop, planned before the search runs. Rides first, then transfers: if the
     * budget bites it should bite a ride rather than leave the journey no way to change lines.
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
                // The line's own class and nothing else: a mode is a set of classes, so the mode alone would let a
                // water line's ride come back along a rail.
                RoutePreferences ridePolicy = ridePreferences(line.kind(), preferences);
                // The network the line asked for, which is how a line with its marks switched off is kept off them.
                RoadRouter.Workspace workspace = RoadRouter.workspaceFor(roads.forLine(line));
                // The same rule as Links.ridesFrom, in this eager form: a one-way line has no edge back.
                for (int step = -1; step <= 1; step += 2) {
                    if (step < 0 && line.oneWay()) {
                        continue;
                    }
                    int next = at + step;
                    if (next < 0 || next >= line.stopCount()) {
                        continue;
                    }
                    LineStop to = line.stops().get(next);
                    Integer target = byPosition.get(positionKey(to.x(), to.z()));
                    if (target == null || target == index) {
                        continue;
                    }
                    // Keyed by direction and planned from the end being travelled from: a stretch ridden the other way is
                    // not the same route reversed, because its instructions must point the way the rider is going.
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
                        if (ride.isPresent() && trackLength(ride) < MIN_RIDE_TRACK) {
                            // Nothing but the two hops onto and off the track: there is no riding in this leg, and calling it
                            // public transport would be a straight line between two platforms dressed up as a train.
                            ride = Route.empty();
                        }
                        cache.put(key, ride);
                        if (!ride.isPresent()) {
                            // Named, because "no journey over 1 line(s)" cannot say which stretch is the one that could not be
                            // ridden.
                            HowToGo.diagnostic(
                                    "[HowToGo] line ride cannot be planned: '{}' -> '{}' ({})",
                                    from.label(), to.label(), line.kind().name());
                        }
                    }
                    if (!ride.isPresent()) {
                        continue;
                    }
                    links.get(index).add(new Link(target, ride.estimatedSeconds(), ride, mode,
                            new RideInfo(line, at, next), false));
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
     * Building all of it up front was sound on a handful of lines, but the graph is every neighbouring
     * pair of stops of every line: a two-hundred-block trip across three hundred lines plans twelve
     * thousand rides, and the map froze. Dijkstra needs only the edges out of each stop when it settles
     * that stop, so a short journey plans only what it reaches. Nothing is capped per stop, so the graph
     * a running search sees never changes underneath it; the remaining budget is on the whole plan and
     * is reported.
     */
    /**
     * The walking network filed in a grid, to answer one cheap question: does a stop stand near a walkable
     * road? A mixed middle leg is a real walking route, so planning for two roadless stops is wasted, and
     * a dead-end line beside a real one is enough to burn the whole mixed budget. The grid makes it a cell
     * lookup, and only stops that pass are offered to the router.
     * Walkable classes only, and avoided classes do not count. An edge is filed in every cell its samples
     * land in, sampled along its length, and a query reads the nine cells around the stop.
     */
    private static final class WalkRoads {

        private static final double CELL = 64.0;
        private static final double SAMPLE = 16.0;

        private final Map<Long, List<double[]>> cells = new HashMap<>();

        WalkRoads(RoadNetwork network, RoutePreferences preferences) {
            for (RoadSegment segment : network.segmentsSnapshot()) {
                if (!TravelMode.WALK.allows(segment.roadClass())
                        || preferences.avoids(segment.roadClass())) {
                    continue;
                }
                for (int i = 1; i < segment.vertexCount(); i++) {
                    addEdge(segment.x(i - 1), segment.z(i - 1), segment.x(i), segment.z(i));
                }
            }
        }

        private void addEdge(double ax, double az, double bx, double bz) {
            double length = Math.hypot(bx - ax, bz - az);
            int steps = Math.max(1, (int) Math.ceil(length / SAMPLE));
            double[] edge = {ax, az, bx, bz};
            java.util.Set<Long> filed = new java.util.HashSet<>();
            for (int step = 0; step <= steps; step++) {
                double t = (double) step / steps;
                long key = cellKey((int) Math.floor((ax + (bx - ax) * t) / CELL),
                        (int) Math.floor((az + (bz - az) * t) / CELL));
                if (filed.add(key)) {
                    cells.computeIfAbsent(key, k -> new ArrayList<>()).add(edge);
                }
            }
        }

        boolean near(double x, double z) {
            int cx = (int) Math.floor(x / CELL);
            int cz = (int) Math.floor(z / CELL);
            double maxSq = MIXED_WALK_STOP_REACH * MIXED_WALK_STOP_REACH;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    List<double[]> bucket = cells.get(cellKey(cx + dx, cz + dz));
                    if (bucket == null) {
                        continue;
                    }
                    for (double[] edge : bucket) {
                        if (distanceSq(x, z, edge) <= maxSq) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }

        private static double distanceSq(double x, double z, double[] edge) {
            double ex = edge[2] - edge[0];
            double ez = edge[3] - edge[1];
            double lengthSq = ex * ex + ez * ez;
            double t = lengthSq < 1.0E-9 ? 0
                    : Math.max(0, Math.min(1,
                            ((x - edge[0]) * ex + (z - edge[1]) * ez) / lengthSq));
            double px = edge[0] + ex * t;
            double pz = edge[1] + ez * t;
            double dx = x - px;
            double dz = z - pz;
            return dx * dx + dz * dz;
        }
    }

    private static final class Links {

        private final RideRoads roads;
        private final List<TransitLine> lines;
        private final List<Node> nodes;
        private final Map<Long, Integer> byPosition;
        private final String destinationName;
        private final RoutePreferences preferences;
        private final double wait;
        private final RoadRouter.Workspace walkWorkspace;
        private final boolean mixed;
        private final WalkRoads walkRoads;
        private final byte[] stopNearRoad;

        private final Map<Long, List<Integer>> cells = new HashMap<>();
        private final Map<Long, List<Integer>> mixedCells = new HashMap<>();
        private final List<Set<Integer>> lineSets = new ArrayList<>();
        private final Map<String, Route> mixedWalks = new HashMap<>();
        private final Map<String, Route> transferWalks = new HashMap<>();
        private final Map<String, Route> rides = new HashMap<>();
        private final List<List<Link>> planned;
        private int budget = MAX_RIDE_PLANS;
        private int mixedBudget = MAX_MIXED_WALK_PLANS;
        private int transferBudget = MAX_TRANSFER_WALK_PLANS;
        private int unplanned;
        private int mixedUnplanned;
        private int transferUnplanned;

        Links(RideRoads roads, List<TransitLine> lines, List<Node> nodes,
              Map<Long, Integer> byPosition, String destinationName, RoutePreferences preferences,
              double wait, RoadRouter.Workspace walkWorkspace, boolean mixed, WalkRoads walkRoads) {
            this.roads = roads;
            this.lines = lines;
            this.nodes = nodes;
            this.byPosition = byPosition;
            this.destinationName = destinationName;
            this.preferences = preferences;
            this.wait = wait;
            this.walkWorkspace = walkWorkspace;
            this.mixed = mixed;
            this.walkRoads = walkRoads;
            this.stopNearRoad = new byte[nodes.size()];
            for (int index = 0; index < nodes.size(); index++) {
                Node node = nodes.get(index);
                cells.computeIfAbsent(cellOf(node), key -> new ArrayList<>()).add(index);
                if (mixed) {
                    mixedCells.computeIfAbsent(mixedCellOf(node), key -> new ArrayList<>()).add(index);
                }
                Set<Integer> atStop = new java.util.HashSet<>();
                for (int[] call : node.calls()) {
                    atStop.add(call[0]);
                }
                lineSets.add(atStop);
            }
            this.planned = new ArrayList<>(nodes.size());
            for (int index = 0; index < nodes.size(); index++) {
                planned.add(null);
            }
        }

        /**
         * Every way out of one stop: a ride to each neighbour on each line calling there, a walk to every other
         * stop of a different line within the interchange radius, and -- when mixing is allowed -- a walked leg
         * to the nearest stops of other lines. Worked out once per stop because the same list serves both
         * search states, so the lists are not pruned against one search's numbers.
         */
        List<Link> outgoing(int index) {
            List<Link> known = planned.get(index);
            if (known != null) {
                return known;
            }
            List<Link> links = new ArrayList<>();
            ridesFrom(index, links);
            transfersFrom(index, links);
            if (mixed) {
                mixedWalksFrom(index, links);
            }
            planned.set(index, links);
            return links;
        }

        private void ridesFrom(int index, List<Link> links) {
            for (int[] call : nodes.get(index).calls()) {
                TransitLine line = lines.get(call[0]);
                int at = call[1];
                TravelMode mode = rideMode(line.kind());
                // The line's own class and nothing else: a mode alone would let a water line's ride use a rail.
                RoutePreferences ridePolicy = ridePreferences(line.kind(), preferences);
                // The network the line asked for; this keeps a line with its marks switched off off them.
                RoadRouter.Workspace workspace = RoadRouter.workspaceFor(roads.forLine(line));
                // A two-way line is ridden either way round -- it is a service. A one-way line is ridden in its own
                // order and nowhere else, so the edge back is not in the graph at all: the rule is true of the
                // journey returned, not applied afterwards.
                for (int step = -1; step <= 1; step += 2) {
                    if (step < 0 && line.oneWay()) {
                        continue;
                    }
                    int next = at + step;
                    if (next < 0 || next >= line.stopCount()) {
                        continue;
                    }
                    LineStop to = line.stops().get(next);
                    Integer target = byPosition.get(positionKey(to.x(), to.z()));
                    if (target == null || target == index) {
                        continue;
                    }
                    // Keyed by direction and planned from the end travelled from, because the instructions have to point
                    // the way the rider is going.
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
                            // Named, because "no journey over 1 line(s)" cannot say which stretch failed.
                            HowToGo.diagnostic(
                                    "[HowToGo] line ride cannot be planned: '{}' -> '{}' ({})",
                                    from.label(), to.label(), line.kind().name());
                        }
                    }
                    if (!ride.isPresent()) {
                        continue;
                    }
                    links.add(new Link(target, ride.estimatedSeconds(), ride, mode,
                            new RideInfo(line, at, next), false));
                }
            }
        }

        /**
         * The transfers out of one stop: a walk to every other stop of the interchange it is in.
         * A transfer is a change at one interchange -- two stops within TRANSFER_RADIUS -- and it used to be a
         * straight hop on the argument that station floors have no roads. Two stops of different lines are
         * often two platforms either side of a street, so the walk is planned by the walking router, with the
         * hop as fallback where no road route exists or the budget is spent. Same-line stops keep the hop:
         * the line itself is the link. Near stops are found through a grid, because asking every pair was the
         * square of the network.
         */
        private void transfersFrom(int index, List<Link> links) {
            Node node = nodes.get(index);
            for (int other : interchangeGroup(index)) {
                if (other == index) {
                    continue;
                }
                Node target = nodes.get(other);
                Route hop = null;
                if (!sharesLine(index, other)) {
                    hop = interchangeWalk(node, target);
                    if (!hop.isPresent()) {
                        hop = null;
                    }
                }
                if (hop == null) {
                    hop = straightWalk(node.at().x(), node.at().z(), target.at().x(),
                            target.at().z(), destinationName);
                }
                if (!hop.isPresent()) {
                    continue;
                }
                links.add(new Link(other, hop.estimatedSeconds() + wait,
                        hop.plusFixedSeconds(wait), TravelMode.WALK, null, true));
            }
        }

        private boolean sharesLine(int a, int b) {
            return !java.util.Collections.disjoint(lineSets.get(a), lineSets.get(b));
        }

        /**
         * The walk between two platforms of different lines, planned by the walking router and cached by
         * ordered pair. A station with many lines would otherwise plan the same route again for every
         * platform; a pair with no route, or a spent budget, answers empty and the caller falls back to the
         * hop.
         */
        private Route interchangeWalk(Node from, Node to) {
            String key = "X|" + from.at().x() + "," + from.at().z() + "|"
                    + to.at().x() + "," + to.at().z();
            Route known = transferWalks.get(key);
            if (known != null) {
                return known;
            }
            if (transferBudget <= 0) {
                transferUnplanned++;
                return Route.empty();
            }
            transferBudget--;
            Route walk = RoadRouter.findRoute(walkWorkspace, from.at().x(), from.at().z(),
                    to.at().x(), to.at().z(), destinationName, TravelMode.WALK, preferences);
            if (!walk.isPresent()) {
                walk = Route.empty();
            }
            transferWalks.put(key, walk);
            return walk;
        }

        /**
         * The mixed mode's walking legs: a real walking route to the nearest stops of other lines.
         * Not a transfer: this is a leg through the streets, so it follows the roads a player on foot would
         * follow. Only stops whose lines differ are offered, and only the nearest few, since each link is a
         * real route.
         */
        private void mixedWalksFrom(int index, List<Link> links) {
            // A middle leg is a walk through the streets, so both ends must stand on the walking network; asking
            // that first stops roadless stops from burning the budget.
            if (!nearRoad(index)) {
                return;
            }
            List<Integer> nearby = mixedNeighboursOf(index);
            if (nearby.isEmpty()) {
                return;
            }
            List<Integer> interchange = interchangeGroup(index);
            Node node = nodes.get(index);
            for (int other : nearby) {
                if (!nearRoad(other)) {
                    continue;
                }
                if (interchange.contains(other)) {
                    continue;
                }
                Route walk = mixedWalk(node, nodes.get(other));
                if (!walk.isPresent()) {
                    continue;
                }
                links.add(new Link(other, walk.estimatedSeconds() + wait,
                        walk.plusFixedSeconds(wait), TravelMode.WALK, null, false));
            }
        }

        private boolean nearRoad(int index) {
            byte known = stopNearRoad[index];
            if (known != 0) {
                return known == 1;
            }
            Node node = nodes.get(index);
            boolean near = walkRoads != null
                    && walkRoads.near(node.at().x(), node.at().z());
            stopNearRoad[index] = (byte) (near ? 1 : 2);
            return near;
        }

        private List<Integer> mixedNeighboursOf(int index) {
            Node node = nodes.get(index);
            int cellX = (int) Math.floor(node.at().x() / MIXED_WALK_CELL);
            int cellZ = (int) Math.floor(node.at().z() / MIXED_WALK_CELL);
            List<Integer> found = new ArrayList<>();
            List<Double> distance = new ArrayList<>();
            double maxSq = MIXED_WALK_RADIUS * MIXED_WALK_RADIUS;
            double interchangeSq = TRANSFER_RADIUS * TRANSFER_RADIUS;
            Set<Integer> mine = lineSets.get(index);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    List<Integer> nearby = mixedCells.get(cellKey(cellX + dx, cellZ + dz));
                    if (nearby == null) {
                        continue;
                    }
                    for (int other : nearby) {
                        if (other == index) {
                            continue;
                        }
                        Node target = nodes.get(other);
                        double dSq = apart(node, target);
                        if (dSq > maxSq || dSq <= interchangeSq) {
                            continue;
                        }
                        if (lineSets.get(other).equals(mine)) {
                            continue;
                        }
                        if (!java.util.Collections.disjoint(mine, lineSets.get(other))) {
                            continue;
                        }
                        found.add(other);
                        distance.add(dSq);
                    }
                }
            }
            if (found.isEmpty()) {
                return List.of();
            }
            List<Integer> order = new ArrayList<>(found.size());
            for (int i = 0; i < found.size(); i++) {
                order.add(i);
            }
            order.sort(Comparator.comparingDouble(distance::get));
            int kept = Math.min(MIXED_WALK_CANDIDATES, order.size());
            List<Integer> nearest = new ArrayList<>(kept);
            for (int i = 0; i < kept; i++) {
                nearest.add(found.get(order.get(i)));
            }
            return nearest;
        }

        private double apart(Node a, Node b) {
            double dx = a.at().x() - b.at().x();
            double dz = a.at().z() - b.at().z();
            return dx * dx + dz * dz;
        }

        /**
         * One mixed walking leg, planned by the walking router and cached. A route that used no road at all is
         * dropped: that is the straight connector the walker's fallback produces, the direct connection this
         * mode does not make.
         */
        private Route mixedWalk(Node from, Node to) {
            String key = "M|" + from.at().x() + "," + from.at().z() + "|"
                    + to.at().x() + "," + to.at().z();
            Route known = mixedWalks.get(key);
            if (known != null) {
                return known;
            }
            if (mixedBudget <= 0) {
                mixedUnplanned++;
                return Route.empty();
            }
            mixedBudget--;
            Route walk = RoadRouter.findRoute(walkWorkspace, from.at().x(), from.at().z(),
                    to.at().x(), to.at().z(), destinationName, TravelMode.WALK, preferences);
            if (!walk.isPresent() || walk.roadLength() <= 1.0E-9) {
                walk = Route.empty();
            }
            mixedWalks.put(key, walk);
            return walk;
        }

        /**
         * Every stop of the interchange this one stands in, itself included.
         * An interchange is a place, not a pair: a platform, the stop beside it and the stop beside that are
         * one place even when the ends are more than the radius apart, which is why the map draws one marker
         * for them -- see TransitInterchanges, which grows its groups the same way. Started from a stop and
         * grown while it keeps finding stops near one already in, using the same walk the map does.
         */
        private List<Integer> interchangeGroup(int index) {
            Map<Integer, List<Integer>> groups = interchangeGroups();
            List<Integer> group = groups.get(index);
            return group == null ? List.of(index) : group;
        }

        /**
         * The stops of this network grouped into interchanges, worked out once per plan. Lazily, because a
         * plan that never asks for a transfer never needs it, and in one pass because doing it per stop would
         * make a hundred-stop hub cost a hundred walks.
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
                        // The distance first, because it is two subtractions and is what most pairs fail; the call lists are
                        // only worth reading once the stops are close enough to be one interchange.
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

        private Map<Integer, List<Integer>> interchangeGroups;

        void reportUnplanned() {
            if (unplanned > 0) {
                HowToGo.LOGGER.warn("[HowToGo] ride budget of {} plans exhausted: {} ride leg(s) were "
                                + "not planned, so the lines they belong to cannot be used at all. "
                                + "Connect the lines' stops to the network, or raise the budget in code.",
                        MAX_RIDE_PLANS, unplanned);
            }
            if (mixedUnplanned > 0 && !mixedBudgetWarned) {
                // Warned once rather than per plan: with a whole railway this can fire on every plan. It is a real
                // finding -- some lines could not be joined on foot -- so it is not hidden behind the debug switch.
                mixedBudgetWarned = true;
                HowToGo.LOGGER.warn("[HowToGo] mixed walking budget of {} plans exhausted: {} "
                                + "walking link(s) were not planned, so some lines could not be joined "
                                + "on foot. This is reported once; the journeys still planned are valid.",
                        MAX_MIXED_WALK_PLANS, mixedUnplanned);
            }
            if (transferUnplanned > 0) {
                // Diagnostic, not a warning: a transfer that falls back to a hop is still a platform walk, and only
                // loses the station's roads.
                HowToGo.diagnostic("[HowToGo] interchange walking budget of {} plans exhausted: {} "
                                + "platform walk(s) were drawn as straight hops",
                        MAX_TRANSFER_WALK_PLANS, transferUnplanned);
            }
        }
    }

    /**
     * The cell a stop is filed under for the transfer pass. The size is the radius, so the nine cells
     * around a stop reach every stop within it and no further.
     */
    private static long cellOf(Node node) {
        return cellKey((int) Math.floor(node.at().x() / TRANSFER_CELL),
                (int) Math.floor(node.at().z() / TRANSFER_CELL));
    }

    private static long mixedCellOf(Node node) {
        return cellKey((int) Math.floor(node.at().x() / MIXED_WALK_CELL),
                (int) Math.floor(node.at().z() / MIXED_WALK_CELL));
    }

    private static long cellKey(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    private static Route walk(RoadRouter.Workspace workspace, double startX, double startZ,
                              double goalX, double goalZ, String destinationName,
                              RoutePreferences preferences, Map<String, Route> cache) {
        // Exact rather than rounded: a rounded key would answer with a route planned to another half a block
        // away, and the trip's geometry is built from the route's points.
        String key = "T|" + startX + "," + startZ + "|" + goalX + "," + goalZ;
        Route cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        double straight = Math.hypot(goalX - startX, goalZ - startZ);
        if (straight <= STATIONARY_DISTANCE) {
            Route still = stationary(startX, startZ, destinationName);
            cache.put(key, still);
            return still;
        }
        Route planned = RoadRouter.findRoute(workspace, startX, startZ, goalX, goalZ, destinationName,
                TravelMode.WALK, preferences);
        // A planned walk is the walk, however far round the roads it goes: a leg drawn straight over the
        // fields is not what walking navigation would give. Only a walk the router cannot find becomes a
        // straight connector.
        if (!planned.isPresent()) {
            // The straight hop is walking off-road, and walking's first and last hop is always allowed. What
            // bounds it is how far a stop may be at all (MAX_WALK_TO_STOP, which the candidates already obey).
            if (straight > MAX_WALK_TO_STOP) {
                planned = Route.empty();
                cache.put(key, planned);
                return planned;
            }
            if (straight > SHORT_HOP) {
                // Said out loud, because this is the one place a broken road network admits itself: a public transport
                // journey with straight-line walking legs still draws and still gives a time, while the same network
                // in walking mode answers "no route".
                HowToGo.diagnostic("[HowToGo] no road route to walk from ({}, {}) to ({}, {}); the "
                                + "{} block walk is drawn as a straight hop",
                        Math.round(startX), Math.round(startZ), Math.round(goalX), Math.round(goalZ),
                        Math.round(straight));
            }
            Route hop = straightWalk(startX, startZ, goalX, goalZ, destinationName);
            if (hop.isPresent()) {
                planned = hop;
            }
        }
        cache.put(key, planned);
        return planned;
    }

    /**
     * The walk between a point and itself: nothing to walk, nothing to draw, no time. The second point is
     * a thousandth of a block away -- under every tolerance and over the builder's duplicate test -- so
     * the route is a dot rather than a gap.
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
     * The walk with nothing to follow: a straight hop at connector pace, built the way the router builds
     * it, so a refused road walk is still a route the map can draw and the estimate can time.
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


    /**
     * The mode a ride on this kind of line is planned with. A road line is driven and the other three are
     * ridden; the restriction to the line's own class is made by ridePreferences.
     */
    public static TravelMode rideMode(RoadClass kind) {
        return kind == RoadClass.ROAD ? TravelMode.DRIVE : TravelMode.TRANSIT;
    }

    /**
     * The routing policy for a ride on a line of this kind: everything except that kind is avoided. This
     * is what makes a line's declared type real -- a ride on a water line cannot take a rail that happens
     * to be shorter. A road line allows the highway as well as the road, since those are the same vehicle
     * on a wider surface.
     * The player's own avoidances are deliberately not merged in: someone who built a line of that kind
     * chose to travel on it, and a global "avoid rails" would make the line silently unusable. The metric
     * and minor-road policy are kept because they say how to choose between routes.
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

    private static Map<Long, Integer> positions(List<Node> nodes) {
        Map<Long, Integer> byPosition = new HashMap<>();
        for (int i = 0; i < nodes.size(); i++) {
            LineStop stop = nodes.get(i).at();
            byPosition.put(positionKey(stop.x(), stop.z()), i);
        }
        return byPosition;
    }

    private static long positionKey(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

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
