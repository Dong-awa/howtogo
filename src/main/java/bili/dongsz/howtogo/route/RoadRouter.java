package bili.dongsz.howtogo.route;

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
     * Trying a handful of candidates and keeping the cheapest route fixes that, and as a bonus
     * prefers a slightly further but better-connected road.
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
        Route anchored = findAnchoredRoute(network, startX, startZ, goalX, goalZ, destinationName,
                mode, preferences);
        if (anchored != null) {
            return anchored;
        }
        return findNodeRoute(network, startX, startZ, goalX, goalZ, destinationName, mode,
                preferences);
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
     * <p>The split happens on a throwaway copy, so routing never mutates the saved network.
     *
     * @return null when there is nothing to anchor to, the anchors are too far away, or they are
     *         not connected
     */
    private static Route findAnchoredRoute(RoadNetwork network, double startX, double startZ,
                                           double goalX, double goalZ, String destinationName,
                                           TravelMode mode, RoutePreferences preferences) {
        RoadPoint startRoad = nearestRoadPoint(network, startX, startZ, mode, preferences);
        RoadPoint goalRoad = nearestRoadPoint(network, goalX, goalZ, mode, preferences);
        if (startRoad == null || goalRoad == null) {
            return null;
        }

        RoadNetwork work = network.deepCopy();
        RoadEditor editor = new RoadEditor(work);

        int startNode = anchorNode(work, editor, startRoad);
        int goalNode = anchorNode(work, editor, goalRoad);
        if (startNode < 0 || goalNode < 0) {
            return null;
        }
        // Measured on the nodes, which is exactly what the connectors will be drawn from, rather
        // than on the raw road points, so the cap cannot be exceeded by the rounding of the anchor.
        double maxConnector = mode.maxConnectorDistance();
        if (anchorDistance(work, startNode, startX, startZ) > maxConnector
                || anchorDistance(work, goalNode, goalX, goalZ) > maxConnector) {
            return null;
        }
        if (startNode == goalNode) {
            return buildRoute(work, startNode, goalNode, List.of(),
                    startX, startZ, goalX, goalZ, destinationName, mode, preferences);
        }

        Map<Integer, List<Edge>> graph = buildGraph(work, mode, preferences);
        List<RoadSegment> path = search(graph, work, startNode, goalNode, mode, preferences);
        if (path == null) {
            return null;
        }
        return buildRoute(work, startNode, goalNode, path,
                startX, startZ, goalX, goalZ, destinationName, mode, preferences);
    }

    /** Distance from a position to the node a connector would run to, in blocks. */
    private static double anchorDistance(RoadNetwork network, int nodeId, double x, double z) {
        RoadNode node = network.node(nodeId);
        return node == null ? 0 : Math.hypot(node.x() - x, node.z() - z);
    }

    /**
     * Turns a point on a road into a node, splitting the segment there unless it already lands on
     * an endpoint.
     */
    private static int anchorNode(RoadNetwork network, RoadEditor editor, RoadPoint road) {
        RoadSegment segment = network.segment(road.segmentId());
        if (segment == null) {
            return -1;
        }
        if (road.t() <= 1.0E-3) {
            return segment.fromNode();
        }
        if (road.t() >= 1.0 - 1.0E-3) {
            return segment.toNode();
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

        Route best = null;
        double bestCost = Double.MAX_VALUE;

        for (int start : starts) {
            for (int goal : goals) {
                List<RoadSegment> path = start == goal
                        ? List.of()
                        : search(graph, network, start, goal, mode, preferences);
                if (path == null) {
                    continue;
                }
                // Compare on the metric in force, connectors included, so the choice is "best whole
                // trip" rather than "best road path with whatever connectors come with it".
                double cost = connectorCost(network, start, startX, startZ, mode, preferences)
                        + connectorCost(network, goal, goalX, goalZ, mode, preferences);
                for (RoadSegment segment : path) {
                    cost += edgeCost(segment, mode, preferences);
                }
                if (cost < bestCost) {
                    bestCost = cost;
                    best = buildRoute(network, start, goal, path,
                            startX, startZ, goalX, goalZ, destinationName, mode, preferences);
                }
            }
        }
        return best != null ? best : Route.empty();
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
            buckets.computeIfAbsent(bucketKey(node.x(), node.z(), cell), k -> new ArrayList<>()).add(node);
        }

        double maxSq = COINCIDENT_DISTANCE * COINCIDENT_DISTANCE;
        for (RoadNode a : nodes) {
            int cx = (int) Math.floor(a.x() / cell);
            int cz = (int) Math.floor(a.z() / cell);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    List<RoadNode> bucket = buckets.get(bucketKey(cx + dx, cz + dz, cell));
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

    private static long bucketKey(int cellX, int cellZ, double cell) {
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

    private static Route buildRoute(RoadNetwork network, int startNode, int goalNode,
                                    List<RoadSegment> path, double startX, double startZ,
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
        int exitRoadKey = path.isEmpty() ? 0 : roadKey(network, path.get(0));
        String exitRoadName = path.isEmpty() ? null : roadName(network, path.get(0));

        builder.addPoint(startX, startZ, exitTolerance, exitRoadKey, exitRoadName, false);
        double startConnector = start == null ? 0 : Math.hypot(start.x() - startX, start.z() - startZ);
        builder.setStartConnector(startConnector);

        int previousNode = startNode;
        // One pass over the network for the node degrees, so marking the junctions costs nothing per
        // segment: the route needs to know where the road really forks, and that is a property of the
        // whole network rather than of any one segment.
        Map<Integer, Integer> degrees = RoadChains.degrees(network);
        for (RoadSegment segment : path) {
            appendSegment(builder, network, segment, previousNode, degrees);
            builder.addRoadLeg(segment.length(),
                    effectiveSpeed(mode, preferences, segment.roadClass()));
            previousNode = other(segment, previousNode);
        }

        RoadSegment lastSegment = path.isEmpty() ? null : path.get(path.size() - 1);
        double arrivalTolerance = lastSegment == null
                ? RoadConfig.onRoadTolerance(RoadClass.ROAD)
                : RoadConfig.onRoadTolerance(lastSegment.roadClass());
        int arrivalRoadKey = lastSegment == null ? 0 : roadKey(network, lastSegment);
        String arrivalRoadName = lastSegment == null ? null : roadName(network, lastSegment);

        double goalConnector = goal == null ? 0 : Math.hypot(goalX - goal.x(), goalZ - goal.z());
        builder.setGoalConnector(goalConnector);
        builder.addPoint(goalX, goalZ, arrivalTolerance, arrivalRoadKey, arrivalRoadName, false);

        // No trimming needed here: the endpoints are the perpendicular feet onto the road (see
        // findAnchoredRoute), so the connectors are already the short straight hops they should be.
        return builder.build();
    }

    /**
     * Identifies which road a segment belongs to.
     *
     * <p>The first segment of the chain, which is the same value for every segment of that road
     * because the chain walk always runs end to end. Manoeuvres are announced where this changes.
     */
    private static int roadKey(RoadNetwork network, RoadSegment segment) {
        List<Integer> chain = RoadChains.chainContaining(network, segment.id());
        return chain.isEmpty() ? segment.id() : chain.get(0);
    }

    /**
     * Name of the road a segment belongs to, or null when it has none.
     *
     * <p>Naming applies to a whole road, so every segment of the chain carries the same name and
     * the first one found is the answer.
     */
    private static String roadName(RoadNetwork network, RoadSegment segment) {
        for (int id : RoadChains.chainContaining(network, segment.id())) {
            RoadSegment part = network.segment(id);
            if (part != null && part.name() != null) {
                return part.name();
            }
        }
        return null;
    }

    /** Appends a segment's polyline oriented away from {@code fromNode}. */
    private static void appendSegment(Route.Builder builder, RoadNetwork network,
                                      RoadSegment segment, int fromNode,
                                      Map<Integer, Integer> degrees) {
        double tolerance = RoadConfig.onRoadTolerance(segment.roadClass());
        int key = roadKey(network, segment);
        String name = roadName(network, segment);
        boolean forward = segment.fromNode() == fromNode;
        if (forward) {
            for (int i = 0; i < segment.vertexCount(); i++) {
                builder.addPoint(segment.x(i), segment.z(i), tolerance, key, name,
                        isBranch(segment, i, degrees));
            }
        } else {
            for (int i = segment.vertexCount() - 1; i >= 0; i--) {
                builder.addPoint(segment.x(i), segment.z(i), tolerance, key, name,
                        isBranch(segment, i, degrees));
            }
        }
    }

    /**
     * Whether the vertex at this index is a junction the road forks at.
     *
     * <p>Only the two ends of a segment are nodes at all -- everything between is the polyline of one
     * piece of road -- and a node counts as a junction only when three or more segment ends meet
     * there. Two is a road carrying on, and one is a road stopping.
     */
    private static boolean isBranch(RoadSegment segment, int vertexIndex,
                                    Map<Integer, Integer> degrees) {
        int nodeId;
        if (vertexIndex == 0) {
            nodeId = segment.fromNode();
        } else if (vertexIndex == segment.vertexCount() - 1) {
            nodeId = segment.toNode();
        } else {
            return false;
        }
        return nodeId != RoadSegment.NO_NODE && degrees.getOrDefault(nodeId, 0) >= 3;
    }

    private static int other(RoadSegment segment, int nodeId) {
        return segment.fromNode() == nodeId ? segment.toNode() : segment.fromNode();
    }
}
