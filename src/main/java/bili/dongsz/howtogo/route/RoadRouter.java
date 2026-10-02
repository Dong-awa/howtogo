package bili.dongsz.howtogo.route;

import bili.dongsz.howtogo.HowToGo;
import bili.dongsz.howtogo.RoadConfig;
import bili.dongsz.howtogo.road.RoadChains;
import bili.dongsz.howtogo.road.RoadClass;
import bili.dongsz.howtogo.road.RoadEditor;
import bili.dongsz.howtogo.road.RoadNetwork;
import bili.dongsz.howtogo.road.RoadNode;
import bili.dongsz.howtogo.road.RoadSegment;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * A* over the road graph.
 *
 * <p>Cost is whatever the {@link RoutePreference} in force asks for: travel <em>time</em>, each
 * segment costed as {@code length / pace}, so the router prefers a longer highway over a short
 * footpath when that actually gets there sooner; or pure geometric length, when the player would
 * rather have the shorter line of roads whatever it costs in minutes. The penalties of
 * {@link RoutePreferences} are applied on top of the metric, and the heuristic is derived from the
 * same quantities, so it can never overestimate what the search still has to pay.
 *
 * <p>The search is always made for one {@link TravelMode} and one policy. Three things follow from
 * that: only roads the mode may use and the policy has not excluded take part in the graph, the
 * class paces the heuristic leans on are drawn from that same set, or the estimate could
 * over-promise on a road that is not there, and the ETA reported for the finished route is costed
 * exactly as the search costed it.
 *
 * <p>The graph is tiny by pathfinding standards -- a large road network is a few thousand nodes --
 * so a straightforward binary-heap A* with a hash map for the scores is more than fast enough and
 * avoids the indexing machinery a grid router would need.
 */
public final class RoadRouter {

    /**
     * Number of nearest road nodes tried as start and goal.
     *
     * <p>Picking only the single nearest node looks right and is wrong in a very common case: the
     * player standing next to a short dead-end stub gets routed from that stub, which cannot reach
     * anything, and the trip silently reports "no route" even though the roads are connected.
     * Trying a handful of candidates and keeping the cheapest whole trip fixes that, and as a bonus
     * prefers a slightly further but better-connected road.
     *
     * <p>The two lists are not a grid of pairs to be searched one by one: {@link
     * #searchBetweenCandidates} makes every start a source of one search and every goal a target of
     * it, so the cost of the candidates is the cost of the frontier they share rather than the
     * product of the two counts.
     */
    private static final int START_CANDIDATES = 12;
    private static final int GOAL_CANDIDATES = 12;

    /**
     * Off-road speed as a fraction of walking pace, used for the reported ETA.
     *
     * <p>Crossing terrain really is slower than following a road, and the estimate should say so.
     * The first and last hop of every trip is walked whatever the mode, so this scales the
     * connector pace rather than replacing it.
     */
    private static final double OFF_ROAD_SPEED = 0.7;

    /** Only segments wired to real nodes take part; free-floating ones have no topology to route on. */
    private record Edge(int toNode, RoadSegment segment) {
    }

    private RoadRouter() {
    }

    /**
     * A network the router may repair and split, shared by a batch of queries.
     *
     * <p>Anchoring turns the point on a road nearest the player into a node, which means breaking the
     * segment there, and that cannot be done to the caller's network. The copy that protects it used
     * to be made on every single query -- and a public transport plan is a batch of dozens of them,
     * so the same few thousand segments were being copied dozens of times to place a handful of
     * endpoints.
     *
     * <p>Here the copy is made once, on the batch's first query. Every split after that lands on the
     * same copy: splitting is additive, subdividing one segment and adding one node while leaving the
     * rest of the graph exactly as it was, so a batch sharing a workspace always reads a refinement of
     * the network it started from and no query can be invalidated by an earlier one.
     *
     * <p>The copy is also where {@link RoadConflation} does its work, and that has to happen before
     * the first query rather than lazily on the first split: a join the player's drawing left out is
     * missing from every route through it, whether or not that particular query needed to anchor
     * anything.
     *
     * <p>One workspace belongs to one batch, and the network inside it must not be edited between the
     * queries made through it.
     */
    public static final class Workspace {

        private final RoadNetwork source;
        private final boolean repair;
        private RoadNetwork work;
        private RoadEditor editor;
        private boolean repaired;
        private RoadChains.Grouping grouping;
        private int groupingSegments = -1;

        public Workspace(RoadNetwork source) {
            this(source, true);
        }

        private Workspace(RoadNetwork source, boolean repair) {
            this.source = source;
            this.repair = repair;
        }

        /** The network to read: a repaired copy of the caller's, made on first use. */
        RoadNetwork routingNetwork() {
            if (work == null) {
                work = source.deepCopy();
                // Without undo: every split would otherwise copy the whole network again, which is the
                // cost this class was rewritten to stop paying.
                editor = RoadEditor.withoutUndo(work);
                if (repair && RoadConfig.repairRoadJoins()) {
                    // A repair that throws must cost the player nothing but the repair. The road they
                    // drew is still a road, and the route over it is what they asked for; losing the
                    // exception entirely would hide a real bug, so it is reported and the un-repaired
                    // network is used.
                    try {
                        repaired = RoadConflation.conflate(work, editor) > 0;
                    } catch (RuntimeException failed) {
                        repaired = false;
                        HowToGo.LOGGER.warn("[HowToGo] could not repair the road network; routing on "
                                + "the roads as drawn", failed);
                    }
                }
            }
            return work;
        }

        /** The same network, for a caller that is about to split it. */
        RoadNetwork mutable() {
            return routingNetwork();
        }

        /** The editor for the working copy, which is made with it. */
        RoadEditor editor() {
            routingNetwork();
            return editor;
        }

        /**
         * A second workspace over the same network with the repair left out, or null when there is
         * nothing to leave out.
         *
         * <p>For the caller that found no route at all on the repaired network. The repair only ever
         * adds a node and a join, so a route that existed before it existed must still be there
         * afterwards -- and asking the roads as they were drawn is how that is guaranteed rather than
         * argued. Null when the repair changed nothing, in which case the answer would be identical.
         */
        Workspace asDrawn() {
            routingNetwork();
            return repaired ? new Workspace(source, false) : null;
        }

        /**
         * The road grouping for the network as it stands, rebuilt only after a split has changed it.
         *
         * <p>A split always removes one segment and adds two, so the segment count is a version
         * number here: it changes on exactly the operations that invalidate the grouping, and on
         * nothing else that matters to it.
         */
        RoadChains.Grouping grouping() {
            RoadNetwork network = routingNetwork();
            if (grouping == null || groupingSegments != network.segmentCount()) {
                grouping = RoadChains.group(network);
                groupingSegments = network.segmentCount();
            }
            return grouping;
        }
    }

    /**
     * @return the route, or {@link Route#empty()} when there is no road path to the destination
     */
    public static Route findRoute(RoadNetwork network, double startX, double startZ,
                                  double goalX, double goalZ, String destinationName) {
        return findRoute(network, startX, startZ, goalX, goalZ, destinationName, TravelMode.WALK,
                RoutePreferences.DEFAULTS);
    }

    /**
     * @return the route for the given mode, or {@link Route#empty()} when that mode has no usable
     *         road path to the destination
     */
    public static Route findRoute(RoadNetwork network, double startX, double startZ,
                                  double goalX, double goalZ, String destinationName,
                                  TravelMode mode) {
        return findRoute(network, startX, startZ, goalX, goalZ, destinationName, mode,
                RoutePreferences.DEFAULTS);
    }

    /**
     * @return the route for the given mode and routing policy, or {@link Route#empty()} when that
     *         mode has no usable road path to the destination
     */
    public static Route findRoute(RoadNetwork network, double startX, double startZ,
                                  double goalX, double goalZ, String destinationName,
                                  TravelMode mode, RoutePreferences preferences) {
        return findRoute(new Workspace(network), startX, startZ, goalX, goalZ, destinationName,
                mode, preferences);
    }

    /**
     * Plans on a workspace the caller owns, so a batch of queries pays for the anchoring splits once.
     *
     * <p>Same answer as {@link #findRoute(RoadNetwork, double, double, double, double, String,
     * TravelMode, RoutePreferences)}, with the per-query state hoisted out: a caller planning many
     * trips over one network should make one workspace and use it for all of them.
     *
     * @return the route for the given mode and routing policy, or {@link Route#empty()} when that
     *         mode has no usable road path to the destination
     */
    public static Route findRoute(Workspace workspace, double startX, double startZ,
                                  double goalX, double goalZ, String destinationName,
                                  TravelMode mode, RoutePreferences preferences) {
        Route found = plan(workspace, startX, startZ, goalX, goalZ, destinationName, mode,
                preferences);
        if (found.isPresent()) {
            return found;
        }
        // Nothing at all on the repaired network. The repair only ever adds a node and a join, so it
        // cannot have taken a route away -- but that is an argument, and this is the guarantee: the
        // roads as the player drew them are asked as well, and the best of the two answers is what
        // comes back. A repair that loses a route would be worse than no repair, so it is not allowed
        // to be able to.
        Workspace asDrawn = workspace.asDrawn();
        if (asDrawn == null) {
            return found;
        }
        Route unrepaired = plan(asDrawn, startX, startZ, goalX, goalZ, destinationName, mode,
                preferences);
        if (unrepaired.isPresent()) {
            HowToGo.LOGGER.info("[HowToGo] the repaired road network found no route from ({}, {}) to "
                            + "({}, {}); the roads as drawn do, so those are used",
                    Math.round(startX), Math.round(startZ), Math.round(goalX), Math.round(goalZ));
        }
        return unrepaired;
    }

    /** One attempt: anchored if it can be, between the nearest nodes if it cannot. */
    private static Route plan(Workspace workspace, double startX, double startZ, double goalX,
                              double goalZ, String destinationName, TravelMode mode,
                              RoutePreferences preferences) {
        Route anchored = findAnchoredRoute(workspace, startX, startZ, goalX, goalZ, destinationName,
                mode, preferences);
        if (anchored != null) {
            return anchored;
        }
        return findNodeRoute(workspace.routingNetwork(), startX, startZ, goalX, goalZ,
                destinationName, mode, preferences);
    }

    /**
     * Routes between the points on the road network closest to each end.
     *
     * <p>This is the primary strategy, and it exists because routing is inherently node-to-node:
     * a player standing beside the middle of a long road would otherwise get a connector running
     * to whichever node the search liked, crossing the road on the way. Here the road is split at
     * the perpendicular foot and that new node becomes the endpoint, so the connector is a short
     * straight hop onto the road the player is actually standing next to.
     *
     * <p>A connector longer than the mode allows is refused here rather than drawn. That is the
     * difference between a route and a beeline: past the cap the straight hop is no longer a hop
     * onto the network but a line across open country that no vehicle in this mod can travel.
     *
     * <p>The split happens on the workspace's own copy, so routing never mutates the saved network:
     * see {@link Workspace}, which makes that copy once for a whole batch of queries and repairs the
     * joins the drawing left out before the first of them.
     *
     * @return null when there is nothing to anchor to, the anchors are too far away, or they are
     *         not connected
     */
    private static Route findAnchoredRoute(Workspace workspace, double startX, double startZ,
                                           double goalX, double goalZ, String destinationName,
                                           TravelMode mode, RoutePreferences preferences) {
        RoadNetwork network = workspace.mutable();
        RoadEditor editor = workspace.editor();
        RoadPoint startRoad = nearestRoadPoint(network, startX, startZ, mode, preferences);
        RoadPoint goalRoad = nearestRoadPoint(network, goalX, goalZ, mode, preferences);
        if (startRoad == null || goalRoad == null) {
            return null;
        }

        int startNode = anchorNode(network, editor, startRoad);
        // Splitting the start removes the segment it was on, and the goal is very often on that very
        // segment -- the two ends of one road is the commonest journey there is. Failing here drops
        // the whole anchored attempt into the fallback search, which is slower and free to choose a
        // worse pair of endpoints, so the goal is re-projected onto what is left of the network
        // instead. It can only ever be reached when a split really happened, so the editor is there.
        RoadPoint goalOnWork = network.segment(goalRoad.segmentId()) == null
                ? nearestRoadPoint(network, goalX, goalZ, mode, preferences)
                : goalRoad;
        if (startNode < 0 || goalOnWork == null) {
            return null;
        }
        int goalNode = anchorNode(network, editor, goalOnWork);
        if (goalNode < 0) {
            return null;
        }
        // Measured on the nodes, which is exactly what the connectors will be drawn from, rather
        // than on the raw road points, so the cap cannot be exceeded by the rounding of the anchor.
        double maxConnector = mode.maxConnectorDistance();
        if (anchorDistance(network, startNode, startX, startZ) > maxConnector
                || anchorDistance(network, goalNode, goalX, goalZ) > maxConnector) {
            return null;
        }
        if (startNode == goalNode) {
            return buildRoute(network, workspace.grouping(), startNode, goalNode, List.of(),
                    startX, startZ, goalX, goalZ, destinationName, mode, preferences);
        }

        Map<Integer, List<Edge>> graph = buildGraph(network, mode, preferences);
        List<RoadSegment> path = search(graph, network, startNode, goalNode, mode, preferences);
        if (path == null) {
            return null;
        }
        return buildRoute(network, workspace.grouping(), startNode, goalNode, path,
                startX, startZ, goalX, goalZ, destinationName, mode, preferences);
    }

    /**
     * Whether the point is one of the two ends of the segment, which are its only nodes.
     *
     * <p>A {@link RoadPoint} is on the edge running from vertex {@code edgeIndex - 1} to vertex
     * {@code edgeIndex}, so a t of zero is the earlier of those vertices and a t of one the later.
     * Only vertex zero and the last vertex of the polyline are nodes; every vertex in between is a
     * bend inside one piece of road, and a point that lands exactly on a bend still needs a node of
     * its own.
     *
     * <p>Reading "t is one" as "the segment's end" is what this used to do, and it made standing on a
     * bend the worst place to be: the anchor came back as the far end of the road, the connector was
     * then however long the whole road was, that is past the mode's cap, and the anchored attempt was
     * thrown away in favour of the node fallback -- so a player standing on a corner with a road under
     * both feet could be told there was no road near them at all.
     */
    private static boolean atSegmentEnd(RoadSegment segment, RoadPoint road) {
        if (road.t() <= 1.0E-3) {
            return road.edgeIndex() == 1;
        }
        if (road.t() >= 1.0 - 1.0E-3) {
            return road.edgeIndex() == segment.vertexCount() - 1;
        }
        return false;
    }

    /** Distance from a position to the node a connector would run to, in blocks. */
    private static double anchorDistance(RoadNetwork network, int nodeId, double x, double z) {
        RoadNode node = network.node(nodeId);
        return node == null ? 0 : Math.hypot(node.x() - x, node.z() - z);
    }

    /**
     * Turns a point on a road into a node, splitting the segment there unless it already lands on
     * an endpoint.
     *
     * <p>The editor is the workspace's, which exists from the moment the workspace's copy does; the
     * guard is there so that a point arriving without one is a refused anchor rather than a null
     * dereference.
     */
    private static int anchorNode(RoadNetwork network, RoadEditor editor, RoadPoint road) {
        RoadSegment segment = network.segment(road.segmentId());
        if (segment == null) {
            return -1;
        }
        if (atSegmentEnd(segment, road)) {
            return road.t() <= 1.0E-3 ? segment.fromNode() : segment.toNode();
        }
        if (editor == null) {
            return -1;
        }
        return editor.splitSegment(road.segmentId(), road.edgeIndex(),
                (int) Math.round(road.x()), segment.y(), (int) Math.round(road.z()));
    }

    /** A point on a real road, found by perpendicular projection. */
    private record RoadPoint(int segmentId, int edgeIndex, double t, double x, double z) {
    }

    /**
     * Closest point on the road network this trip may actually use, to the given position.
     *
     * <p>Only segments wired to nodes are considered: a free-floating segment has no topology, so
     * anchoring to it would produce an endpoint nothing can route from. The filters matter just as
     * much -- without them a driver would anchor to the footpath beside the road and be sent along
     * it, and an avoided class would be anchored to and then found to be missing from the graph,
     * which is exactly the mistake both the mode and the avoid list exist to prevent.
     */
    private static RoadPoint nearestRoadPoint(RoadNetwork network, double x, double z,
                                              TravelMode mode, RoutePreferences preferences) {
        RoadPoint best = null;
        double bestDistanceSq = Double.MAX_VALUE;
        for (RoadSegment segment : network.segmentsSnapshot()) {
            if (!mode.allows(segment.roadClass()) || preferences.avoids(segment.roadClass())) {
                continue;
            }
            if (segment.fromNode() == RoadSegment.NO_NODE || segment.toNode() == RoadSegment.NO_NODE) {
                continue;
            }
            if (network.node(segment.fromNode()) == null || network.node(segment.toNode()) == null) {
                continue;
            }
            for (int i = 1; i < segment.vertexCount(); i++) {
                double ax = segment.x(i - 1);
                double az = segment.z(i - 1);
                double ex = segment.x(i) - ax;
                double ez = segment.z(i) - az;
                double lenSq = ex * ex + ez * ez;
                double t = lenSq < 1.0E-9 ? 0
                        : Math.max(0, Math.min(1, ((x - ax) * ex + (z - az) * ez) / lenSq));
                double px = ax + ex * t;
                double pz = az + ez * t;
                double d = (px - x) * (px - x) + (pz - z) * (pz - z);
                if (d < bestDistanceSq) {
                    bestDistanceSq = d;
                    best = new RoadPoint(segment.id(), i, t, px, pz);
                }
            }
        }
        return best;
    }

    /**
     * Fallback routing between the nearest nodes when anchoring is impossible or the anchors are
     * not connected to each other.
     */
    private static Route findNodeRoute(RoadNetwork network, double startX, double startZ,
                                       double goalX, double goalZ, String destinationName,
                                       TravelMode mode, RoutePreferences preferences) {
        Map<Integer, List<Edge>> graph = buildGraph(network, mode, preferences);
        if (graph.isEmpty()) {
            return Route.empty();
        }

        List<Integer> starts =
                nearestRoutableNodes(network, graph, startX, startZ, START_CANDIDATES, mode);
        List<Integer> goals =
                nearestRoutableNodes(network, graph, goalX, goalZ, GOAL_CANDIDATES, mode);
        if (starts.isEmpty() || goals.isEmpty()) {
            return Route.empty();
        }

        Best best = searchBetweenCandidates(graph, network, starts, goals, startX, startZ, goalX,
                goalZ, mode, preferences);
        if (best == null) {
            return Route.empty();
        }
        return buildRoute(network, RoadChains.group(network), best.source(), best.goal(), best.path(),
                startX, startZ, goalX, goalZ, destinationName, mode, preferences);
    }

    /** The whole fallback trip: which candidate it leaves from, which it arrives at, and the road. */
    private record Best(int source, int goal, List<RoadSegment> path) {
    }

    /** A predecessor chain walked back to the source it started from. */
    private record Chain(int source, List<RoadSegment> path) {
    }

    /**
     * One multi-source, multi-target A* over the candidate endpoints.
     *
     * <p>Every candidate start is a source, seeded with the connector it costs to walk to; every
     * candidate goal is a target; the answer is the cheapest whole trip, both connectors included.
     *
     * <p>This replaces a loop that ran a separate A* for each of the twelve by twelve pairs: up to a
     * hundred and forty-four full searches, every one of which explored its whole component when the
     * answer was "no", and none of which could see that a different pair of endpoints would have been
     * cheaper once the connectors at each end were counted -- it compared whole trips only after
     * picking the twelve pairs, so a candidate that lost on road distance alone was never given the
     * chance its short connector would have given it.
     *
     * <p>The heuristic is the distance to the nearest goal, which is an admissible estimate of
     * reaching one of them from wherever the search is. It is computed over every goal rather than
     * only the ones still outstanding, because a heuristic that changes as the search proceeds is no
     * longer a heuristic. The search stops as soon as the cheapest arrival already found cannot be
     * beaten: every frontier entry still to come is bounded below by its own estimate, and the
     * connectors only add to what is left, so an estimate that has reached the best total means the
     * rest of the frontier can be abandoned.
     */
    private static Best searchBetweenCandidates(Map<Integer, List<Edge>> graph, RoadNetwork network,
                                                List<Integer> starts, List<Integer> goals,
                                                double startX, double startZ, double goalX,
                                                double goalZ, TravelMode mode,
                                                RoutePreferences preferences) {
        Map<Integer, Double> gScore = new HashMap<>();
        Map<Integer, Integer> cameFromNode = new HashMap<>();
        Map<Integer, RoadSegment> cameFromSegment = new HashMap<>();
        Set<Integer> closed = new HashSet<>();
        Set<Integer> settledGoals = new HashSet<>();

        PriorityQueue<double[]> frontier = new PriorityQueue<>(Comparator.comparingDouble(a -> a[0]));
        for (int start : starts) {
            double connector = connectorCost(network, start, startX, startZ, mode, preferences);
            if (connector < gScore.getOrDefault(start, Double.MAX_VALUE)) {
                gScore.put(start, connector);
                frontier.add(new double[]{
                        connector + nearestGoalEstimate(network, start, goals, mode, preferences),
                        start});
            }
        }

        double bestTotal = Double.MAX_VALUE;
        while (!frontier.isEmpty() && frontier.peek()[0] < bestTotal) {
            int current = (int) frontier.poll()[1];
            if (!closed.add(current)) {
                continue;
            }
            double currentG = gScore.getOrDefault(current, Double.MAX_VALUE);
            if (currentG >= bestTotal) {
                continue;
            }
            if (goals.contains(current) && settledGoals.add(current)) {
                double total = currentG
                        + connectorCost(network, current, goalX, goalZ, mode, preferences);
                bestTotal = Math.min(bestTotal, total);
            }

            for (Edge edge : graph.getOrDefault(current, List.of())) {
                if (closed.contains(edge.toNode())) {
                    continue;
                }
                double tentative = currentG + edgeCost(edge.segment(), mode, preferences);
                if (tentative < gScore.getOrDefault(edge.toNode(), Double.MAX_VALUE)) {
                    gScore.put(edge.toNode(), tentative);
                    cameFromNode.put(edge.toNode(), current);
                    cameFromSegment.put(edge.toNode(), edge.segment());
                    frontier.add(new double[]{
                            tentative + nearestGoalEstimate(network, edge.toNode(), goals, mode,
                                    preferences),
                            edge.toNode()});
                }
            }
        }

        // Settled in non-decreasing order, so the cheapest goal is the first one the search reached
        // for which the connector still leaves it cheapest overall.
        int bestGoal = -1;
        double bestWithConnector = Double.MAX_VALUE;
        for (int goal : settledGoals) {
            double total = gScore.getOrDefault(goal, Double.MAX_VALUE)
                    + connectorCost(network, goal, goalX, goalZ, mode, preferences);
            if (total < bestWithConnector) {
                bestWithConnector = total;
                bestGoal = goal;
            }
        }
        if (bestGoal < 0) {
            return null;
        }
        Chain chain = chain(cameFromNode, cameFromSegment, bestGoal);
        return new Best(chain.source(), bestGoal, chain.path());
    }

    /** The lowest estimate of what is left to any of the goals, in the metric in force. */
    private static double nearestGoalEstimate(RoadNetwork network, int nodeId, List<Integer> goals,
                                              TravelMode mode, RoutePreferences preferences) {
        double best = Double.MAX_VALUE;
        for (int goalId : goals) {
            RoadNode goal = network.node(goalId);
            if (goal == null) {
                continue;
            }
            best = Math.min(best, heuristic(network, nodeId, goal, mode, preferences));
        }
        return best == Double.MAX_VALUE ? 0 : best;
    }

    /**
     * Walks a predecessor chain back to the node it started from.
     *
     * <p>A source has no predecessor, which is what ends the walk: with more than one source there is
     * no single node to stop at, as there was when every search began at one end.
     */
    private static Chain chain(Map<Integer, Integer> cameFromNode,
                               Map<Integer, RoadSegment> cameFromSegment, int from) {
        List<RoadSegment> reversed = new ArrayList<>();
        int current = from;
        // Bounded by the predecessor count: guards against a malformed chain looping forever.
        int guard = cameFromNode.size() + 2;
        while (guard-- > 0) {
            Integer previous = cameFromNode.get(current);
            RoadSegment segment = cameFromSegment.get(current);
            if (previous == null || segment == null) {
                break;
            }
            reversed.add(segment);
            current = previous;
        }
        List<RoadSegment> path = new ArrayList<>(reversed.size());
        for (int i = reversed.size() - 1; i >= 0; i--) {
            path.add(reversed.get(i));
        }
        return new Chain(current, path);
    }

    // ------------------------------------------------------------------ graph

    /**
     * The graph the given mode and policy may travel on, so a footpath is simply absent from a
     * drive and an avoided class is absent from everything.
     */
    private static Map<Integer, List<Edge>> buildGraph(RoadNetwork network, TravelMode mode,
                                                       RoutePreferences preferences) {
        Map<Integer, List<Edge>> graph = new HashMap<>();
        for (RoadSegment segment : network.segmentsSnapshot()) {
            if (!mode.allows(segment.roadClass()) || preferences.avoids(segment.roadClass())) {
                continue;
            }
            int from = segment.fromNode();
            int to = segment.toNode();
            if (from == RoadSegment.NO_NODE || to == RoadSegment.NO_NODE || from == to) {
                continue;
            }
            if (network.node(from) == null || network.node(to) == null) {
                continue;
            }
            graph.computeIfAbsent(from, k -> new ArrayList<>()).add(new Edge(to, segment));
            if (!segment.oneWay()) {
                graph.computeIfAbsent(to, k -> new ArrayList<>()).add(new Edge(from, segment));
            }
        }
        addCoincidentNodeLinks(network, graph, mode, preferences);
        return graph;
    }

    /**
     * Distance, in blocks, within which two nodes count as being at the same place.
     *
     * <p>Drawing two roads that meet at the same spot does not always reuse one node: a click a
     * block or two off creates a second node, and the two roads then look joined on the map while
     * the graph sees separate fragments. Rather than asking the player to repair that by hand, the
     * router treats coincident points as what they visually are -- one junction.
     */
    private static final double COINCIDENT_DISTANCE = 3.0;

    /**
     * Adds zero-ish cost links between nodes that sit essentially on top of each other.
     *
     * <p>The links are real {@link RoadSegment} objects, but they are never inserted into the
     * network: they exist only so the route polyline has geometry to draw across the join. They
     * borrow a class the mode may use and the policy has not excluded, since a link is a junction
     * rather than a road and must not be the loophole that smuggles a footpath into a drive -- or an
     * avoided class back into a trip that banned it.
     *
     * <p>The bucket a node is filed under and the bucket that is then looked up have to be the same
     * bucket, which means both have to be the <em>cell</em> the node falls in and not the node's own
     * coordinates. They were the node's coordinates on the way in and the cell on the way out, so the
     * two only ever agreed within three blocks of the origin and this whole pass silently did nothing
     * anywhere else on the map: roads that met on screen stayed two fragments to the router, which is
     * the failure the pass exists to prevent.
     *
     * <p>Nothing here is allowed to take a join away, only to add one: a road that routed before must
     * route after. An earlier version of this pass also required the two nodes to be at roughly the
     * same height, to keep a road from being joined to the one passing over it, and that was the wrong
     * trade. The heights in a hand-drawn network are whatever the ground was under each click, so two
     * nodes a block apart across a slope are routinely several blocks apart vertically, and refusing
     * those joins disconnected networks that had been routing for as long as they existed. Where a
     * height check does belong is in {@link RoadConflation}, which invents joins rather than keeping
     * them: there it can only decline to add one.
     */
    private static void addCoincidentNodeLinks(RoadNetwork network, Map<Integer, List<Edge>> graph,
                                               TravelMode mode, RoutePreferences preferences) {
        List<RoadNode> nodes = network.nodesSnapshot();
        if (nodes.size() < 2) {
            return;
        }
        RoadClass junctionClass = junctionClass(mode, preferences);
        if (junctionClass == null) {
            // Everything this mode could travel on is avoided, so there is no junction to build.
            return;
        }

        // Bucketed so this stays near-linear instead of comparing every pair of nodes.
        double cell = COINCIDENT_DISTANCE;
        Map<Long, List<RoadNode>> buckets = new HashMap<>();
        for (RoadNode node : nodes) {
            buckets.computeIfAbsent(bucketKey(cellOf(node.x(), cell), cellOf(node.z(), cell)),
                    k -> new ArrayList<>()).add(node);
        }

        double maxSq = COINCIDENT_DISTANCE * COINCIDENT_DISTANCE;
        for (RoadNode a : nodes) {
            int cx = cellOf(a.x(), cell);
            int cz = cellOf(a.z(), cell);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    List<RoadNode> bucket = buckets.get(bucketKey(cx + dx, cz + dz));
                    if (bucket == null) {
                        continue;
                    }
                    for (RoadNode b : bucket) {
                        if (b.id() <= a.id() || a.distSq(b.x(), b.z()) > maxSq) {
                            continue;
                        }
                        RoadSegment link = syntheticLink(a, b, junctionClass);
                        graph.computeIfAbsent(a.id(), k -> new ArrayList<>()).add(new Edge(b.id(), link));
                        graph.computeIfAbsent(b.id(), k -> new ArrayList<>()).add(new Edge(a.id(), link));
                    }
                }
            }
        }
    }

    /** Which bucket a coordinate falls in, which is the only thing a bucket key may be built from. */
    private static int cellOf(double coordinate, double cell) {
        return (int) Math.floor(coordinate / cell);
    }

    private static long bucketKey(int cellX, int cellZ) {
        // Pack two signed cell coordinates into one key; 24 bits each is far beyond any real map.
        return ((long) (cellX & 0xFFFFFF) << 24) | (cellZ & 0xFFFFFF);
    }

    private static RoadSegment syntheticLink(RoadNode a, RoadNode b, RoadClass junctionClass) {
        RoadSegment link = new RoadSegment(RoadSegment.NO_SEGMENT, junctionClass, a.y(), 2);
        link.addVertex(a.x(), a.z());
        link.addVertex(b.x(), b.z());
        link.setFromNode(a.id());
        link.setToNode(b.id());
        return link;
    }

    /**
     * A class a junction link may borrow: one the mode may use and the policy has not excluded.
     *
     * <p>Null when the mode has no class left at all, which is also when the graph has nothing in
     * it and the trip is honestly unroutable.
     */
    private static RoadClass junctionClass(TravelMode mode, RoutePreferences preferences) {
        for (RoadClass roadClass : RoadClass.values()) {
            if (mode.allows(roadClass) && !preferences.avoids(roadClass)) {
                return roadClass;
            }
        }
        return null;
    }

    /**
     * The {@code limit} nearest nodes that actually appear in the graph and lie within the mode's
     * {@link TravelMode#maxConnectorDistance()}.
     *
     * <p>Nodes with no edges are skipped: a standalone landmark has nothing to route through, so
     * treating it as an endpoint would strand the trip on the spot.
     *
     * <p>Nothing within the cap means nothing is returned, even when there are nodes further off.
     * An endpoint beyond the cap would put a straight line across open country at the end of the
     * route, which is precisely the thing the cap exists to forbid; the trip is refused instead,
     * with {@link #explainFailure} saying how far the nearest road actually was.
     */
    private static List<Integer> nearestRoutableNodes(RoadNetwork network, Map<Integer, List<Edge>> graph,
                                                      double x, double z, int limit,
                                                      TravelMode mode) {
        List<RoadNode> candidates = new ArrayList<>();
        for (RoadNode node : network.nodes()) {
            if (graph.containsKey(node.id())) {
                candidates.add(node);
            }
        }
        candidates.sort(Comparator.comparingDouble(node -> node.distSq(x, z)));

        List<Integer> result = new ArrayList<>(limit);
        double maxConnector = mode.maxConnectorDistance();
        double maxSq = maxConnector * maxConnector;
        for (RoadNode node : candidates) {
            if (node.distSq(x, z) > maxSq) {
                break;
            }
            result.add(node.id());
            if (result.size() >= limit) {
                break;
            }
        }
        return result;
    }

    /**
     * Connector cost for planning, at the pace the first and last hop is really made.
     *
     * <p>Expressed in the same units as {@link #edgeCost}, so a shortcut across a field is weighed
     * against the road detour it replaces rather than against a distance.
     */
    private static double connectorCost(RoadNetwork network, int nodeId, double x, double z,
                                        TravelMode mode, RoutePreferences preferences) {
        RoadNode node = network.node(nodeId);
        if (node == null) {
            return 0;
        }
        double distance = Math.hypot(node.x() - x, node.z() - z);
        if (preferences.metric() == RoutePreference.SHORTEST_DISTANCE) {
            // Distance is distance: scoring a hop across a field as several times its length would
            // smuggle the mode's time preference back into a geometric metric.
            return distance;
        }
        // Walking pace whatever the mode: the first and last hop is walked, so costing it at the
        // vehicle's pace would make a long connector look cheap and invite the beeline.
        return distance * mode.offRoadCostFactor() / Math.max(0.05, mode.connectorSpeed());
    }

    /**
     * Why a route could not be found, in one line.
     *
     * <p>The overwhelmingly common cause is a road network that is really several disconnected
     * fragments, which is invisible on the map -- the roads look joined when they merely overlap.
     * Reporting the component sizes turns "it just does not work" into something actionable.
     *
     * <p>The other causes, which only exist once modes and preferences do, are a network with no
     * road of the kind being asked for and a policy that has excluded the roads that would have
     * joined the two ends. The second is named explicitly: the player could see the water line on
     * the map and has no way of guessing that their own avoid list is what broke the journey.
     */
    public static String explainFailure(RoadNetwork network, double startX, double startZ,
                                        double goalX, double goalZ) {
        return explainFailure(network, startX, startZ, goalX, goalZ, TravelMode.WALK,
                RoutePreferences.DEFAULTS);
    }

    /** Why no route for this mode could be found, in one line. */
    public static String explainFailure(RoadNetwork network, double startX, double startZ,
                                        double goalX, double goalZ, TravelMode mode) {
        return explainFailure(network, startX, startZ, goalX, goalZ, mode, RoutePreferences.DEFAULTS);
    }

    /** Why no route for this mode and policy could be found, in one line. */
    public static String explainFailure(RoadNetwork network, double startX, double startZ,
                                        double goalX, double goalZ, TravelMode mode,
                                        RoutePreferences preferences) {
        String avoided = avoidedNote(preferences);
        Map<Integer, List<Edge>> graph = buildGraph(network, mode, preferences);
        if (graph.isEmpty()) {
            if (!avoided.isEmpty()) {
                return "every road usable by " + modeName(mode) + " is excluded" + avoided
                        + ", so there is nothing to route on";
            }
            return "no road usable by " + modeName(mode)
                    + " has both endpoints attached to nodes, so there is nothing to route on";
        }
        if (nearestRoadPoint(network, startX, startZ, mode, preferences) == null) {
            return "no road usable by " + modeName(mode) + " near the start" + avoided;
        }
        if (nearestRoadPoint(network, goalX, goalZ, mode, preferences) == null) {
            return "no road usable by " + modeName(mode) + " near the destination" + avoided;
        }
        List<Integer> starts = nearestRoutableNodes(network, graph, startX, startZ, 1, mode);
        List<Integer> goals = nearestRoutableNodes(network, graph, goalX, goalZ, 1, mode);
        if (starts.isEmpty() || goals.isEmpty()) {
            return "no road usable by " + modeName(mode) + " within "
                    + Math.round(mode.maxConnectorDistance())
                    + " blocks of the start or the destination" + avoided;
        }
        Set<Integer> fromStart = component(graph, starts.get(0));
        Set<Integer> fromGoal = component(graph, goals.get(0));
        if (fromStart.contains(goals.get(0))) {
            return "nodes are in the same component (" + fromStart.size()
                    + " nodes) but no path was found - please report this";
        }
        return modeName(mode) + " cannot get across" + avoided
                + ": roads are split into separate fragments: nearest road to you has "
                + fromStart.size() + " nodes, nearest road to the destination has "
                + fromGoal.size() + " nodes. "
                + (avoided.isEmpty()
                        ? "Connect them to route across."
                        : "Connect them, or stop avoiding those classes.");
    }

    /** A clause naming the avoided classes, or nothing when the policy avoids none. */
    private static String avoidedNote(RoutePreferences preferences) {
        if (!preferences.avoidsAny()) {
            return "";
        }
        return " while avoiding " + preferences.avoidedSummary();
    }

    /** The mode by id and localised name, so a log line says which mode could not route. */
    private static String modeName(TravelMode mode) {
        return mode.id() + " (" + mode.label() + ")";
    }

    /** Flood fill of one connected component, ignoring edge direction. */
    private static Set<Integer> component(Map<Integer, List<Edge>> graph, int seed) {
        Set<Integer> visited = new HashSet<>();
        java.util.ArrayDeque<Integer> queue = new java.util.ArrayDeque<>();
        visited.add(seed);
        queue.add(seed);
        while (!queue.isEmpty()) {
            int current = queue.poll();
            for (Edge edge : graph.getOrDefault(current, List.of())) {
                if (visited.add(edge.toNode())) {
                    queue.add(edge.toNode());
                }
            }
        }
        return visited;
    }

    // --------------------------------------------------------------------- A*

    private static List<RoadSegment> search(Map<Integer, List<Edge>> graph, RoadNetwork network,
                                            int startNode, int goalNode, TravelMode mode,
                                            RoutePreferences preferences) {
        RoadNode goal = network.node(goalNode);
        if (goal == null) {
            return null;
        }

        Map<Integer, Double> gScore = new HashMap<>();
        Map<Integer, Integer> cameFromNode = new HashMap<>();
        Map<Integer, RoadSegment> cameFromSegment = new HashMap<>();
        Set<Integer> closed = new HashSet<>();

        gScore.put(startNode, 0.0);
        // Entries are {fScore, nodeId}. Stale entries are tolerated and skipped via the closed set.
        PriorityQueue<double[]> frontier = new PriorityQueue<>(Comparator.comparingDouble(a -> a[0]));
        frontier.add(new double[]{heuristic(network, startNode, goal, mode, preferences), startNode});

        while (!frontier.isEmpty()) {
            int current = (int) frontier.poll()[1];
            if (!closed.add(current)) {
                continue;
            }
            if (current == goalNode) {
                return reconstruct(cameFromNode, cameFromSegment, startNode, goalNode);
            }

            double currentG = gScore.getOrDefault(current, Double.MAX_VALUE);
            for (Edge edge : graph.getOrDefault(current, List.of())) {
                if (closed.contains(edge.toNode())) {
                    continue;
                }
                double tentative = currentG + edgeCost(edge.segment(), mode, preferences);
                if (tentative < gScore.getOrDefault(edge.toNode(), Double.MAX_VALUE)) {
                    gScore.put(edge.toNode(), tentative);
                    cameFromNode.put(edge.toNode(), current);
                    cameFromSegment.put(edge.toNode(), edge.segment());
                    frontier.add(new double[]{
                            tentative + heuristic(network, edge.toNode(), goal, mode, preferences),
                            edge.toNode()});
                }
            }
        }
        return null;
    }

    private static List<RoadSegment> reconstruct(Map<Integer, Integer> cameFromNode,
                                                 Map<Integer, RoadSegment> cameFromSegment,
                                                 int startNode, int goalNode) {
        List<RoadSegment> reversed = new ArrayList<>();
        int current = goalNode;
        // Bounded by the node count: guards against a malformed predecessor chain looping forever.
        int guard = cameFromNode.size() + 1;
        while (current != startNode && guard-- > 0) {
            RoadSegment segment = cameFromSegment.get(current);
            Integer previous = cameFromNode.get(current);
            if (segment == null || previous == null) {
                return null;
            }
            reversed.add(segment);
            current = previous;
        }
        if (current != startNode) {
            return null;
        }
        List<RoadSegment> path = new ArrayList<>(reversed.size());
        for (int i = reversed.size() - 1; i >= 0; i--) {
            path.add(reversed.get(i));
        }
        return path;
    }

    /**
     * Segment cost in the metric in force, at the pace the mode makes on this class.
     *
     * <p>Only classes the mode allows and the policy leaves in play can reach this point, since
     * {@link #buildGraph} never puts anything else into the graph.
     */
    private static double edgeCost(RoadSegment segment, TravelMode mode,
                                   RoutePreferences preferences) {
        RoadClass roadClass = segment.roadClass();
        if (preferences.metric() == RoutePreference.SHORTEST_DISTANCE) {
            // Pure geometry, with the preference penalty still laid on top: it expresses the
            // player's taste, which is the same taste whichever metric they asked for.
            return segment.length() * preferences.penalty(roadClass);
        }
        return segment.length() / Math.max(0.05, effectiveSpeed(mode, preferences, roadClass));
    }

    /**
     * Pace in blocks per second a class actually offers, once the preference penalty is folded in.
     *
     * <p>Dividing the penalty out of the pace rather than only multiplying it into the cost is what
     * lets one number serve the search and the readout: the ETA of a route is then the very cost
     * the router minimised, so the player is never told a different story from the one the route
     * was chosen by.
     */
    private static double effectiveSpeed(TravelMode mode, RoutePreferences preferences,
                                         RoadClass roadClass) {
        return mode.speedOn(roadClass) / preferences.penalty(roadClass);
    }

    /** Whether a class takes part in this trip at all. */
    private static boolean usable(TravelMode mode, RoutePreferences preferences,
                                  RoadClass roadClass) {
        return mode.allows(roadClass) && !preferences.avoids(roadClass);
    }

    /**
     * Remaining cost, optimistic: the bound has to sit under what the search will really pay, or
     * A* stops being admissible and can return a route that is not the best one.
     *
     * <p>For time that means the fastest pace any class in play offers, straight there; for
     * distance it means the straight line, which no line of roads can be shorter than, times the
     * cheapest penalty still on offer, which no road in play can beat.
     */
    private static double heuristic(RoadNetwork network, int nodeId, RoadNode goal,
                                    TravelMode mode, RoutePreferences preferences) {
        RoadNode node = network.node(nodeId);
        if (node == null) {
            return 0;
        }
        double distance = Math.hypot(goal.x() - node.x(), goal.z() - node.z());
        if (preferences.metric() == RoutePreference.SHORTEST_DISTANCE) {
            return distance * cheapestPenalty(mode, preferences);
        }
        double fastest = fastestSpeed(mode, preferences);
        // Nothing in play means the graph is empty and the search is about to find nothing; the
        // bound only has to stay finite so the frontier can drain.
        return fastest <= 0 ? 0 : distance / fastest;
    }

    /** Fastest pace available on any class this trip may use, in blocks per second. */
    private static double fastestSpeed(TravelMode mode, RoutePreferences preferences) {
        double fastest = 0;
        for (RoadClass roadClass : RoadClass.values()) {
            if (usable(mode, preferences, roadClass)) {
                fastest = Math.max(fastest, effectiveSpeed(mode, preferences, roadClass));
            }
        }
        return fastest;
    }

    /** Cheapest penalty on any class this trip may use. */
    private static double cheapestPenalty(TravelMode mode, RoutePreferences preferences) {
        double cheapest = Double.MAX_VALUE;
        for (RoadClass roadClass : RoadClass.values()) {
            if (usable(mode, preferences, roadClass)) {
                cheapest = Math.min(cheapest, preferences.penalty(roadClass));
            }
        }
        return cheapest == Double.MAX_VALUE ? 1.0 : cheapest;
    }

    // ------------------------------------------------------------------ output

    private static Route buildRoute(RoadNetwork network, RoadChains.Grouping grouping, int startNode,
                                    int goalNode, List<RoadSegment> path, double startX, double startZ,
                                    double goalX, double goalZ, String destinationName,
                                    TravelMode mode, RoutePreferences preferences) {
        Route.Builder builder = new Route.Builder();
        builder.setDestinationName(destinationName);
        builder.setTravelMode(mode);
        builder.setOffRoadSpeedFactor(OFF_ROAD_SPEED);

        RoadNode start = network.node(startNode);
        RoadNode goal = network.node(goalNode);

        double exitTolerance = path.isEmpty()
                ? RoadConfig.onRoadTolerance(RoadClass.ROAD)
                : RoadConfig.onRoadTolerance(path.get(0).roadClass());
        int exitRoadKey = path.isEmpty() ? 0 : grouping.keyOf(path.get(0));
        String exitRoadName = path.isEmpty() ? null : grouping.nameOf(path.get(0));

        builder.addPoint(startX, startZ, exitTolerance, exitRoadKey, exitRoadName, false);
        double startConnector = start == null ? 0 : Math.hypot(start.x() - startX, start.z() - startZ);
        builder.setStartConnector(startConnector);

        int previousNode = startNode;
        // The junctions come from the same grouping the keys do, so marking them costs nothing per
        // segment: the route needs to know where the road really forks, and that is a property of the
        // whole network rather than of any one segment.
        for (RoadSegment segment : path) {
            appendSegment(builder, grouping, segment, previousNode);
            builder.addRoadLeg(segment.length(),
                    effectiveSpeed(mode, preferences, segment.roadClass()));
            previousNode = other(segment, previousNode);
        }

        RoadSegment lastSegment = path.isEmpty() ? null : path.get(path.size() - 1);
        double arrivalTolerance = lastSegment == null
                ? RoadConfig.onRoadTolerance(RoadClass.ROAD)
                : RoadConfig.onRoadTolerance(lastSegment.roadClass());
        int arrivalRoadKey = lastSegment == null ? 0 : grouping.keyOf(lastSegment);
        String arrivalRoadName = lastSegment == null ? null : grouping.nameOf(lastSegment);

        double goalConnector = goal == null ? 0 : Math.hypot(goalX - goal.x(), goalZ - goal.z());
        builder.setGoalConnector(goalConnector);
        builder.addPoint(goalX, goalZ, arrivalTolerance, arrivalRoadKey, arrivalRoadName, false);

        // No trimming needed here: the endpoints are the perpendicular feet onto the road (see
        // findAnchoredRoute), so the connectors are already the short straight hops they should be.
        return builder.build();
    }

    /** Appends a segment's polyline oriented away from {@code fromNode}. */
    private static void appendSegment(Route.Builder builder, RoadChains.Grouping grouping,
                                      RoadSegment segment, int fromNode) {
        double tolerance = RoadConfig.onRoadTolerance(segment.roadClass());
        int key = grouping.keyOf(segment);
        String name = grouping.nameOf(segment);
        boolean forward = segment.fromNode() == fromNode;
        if (forward) {
            for (int i = 0; i < segment.vertexCount(); i++) {
                builder.addPoint(segment.x(i), segment.z(i), tolerance, key, name,
                        grouping.isBranch(segment, i));
            }
        } else {
            for (int i = segment.vertexCount() - 1; i >= 0; i--) {
                builder.addPoint(segment.x(i), segment.z(i), tolerance, key, name,
                        grouping.isBranch(segment, i));
            }
        }
    }

    private static int other(RoadSegment segment, int nodeId) {
        return segment.fromNode() == nodeId ? segment.toNode() : segment.fromNode();
    }
}
