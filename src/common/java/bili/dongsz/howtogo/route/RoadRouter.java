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
 * A* over the road graph. Cost is the metric the RoutePreference in force asks for: travel time, each segment costed as length / pace, or pure geometric length. Preference penalties are applied on top of the metric and the heuristic is derived from the same quantities, so it can never overestimate.
 * The search is always made for one TravelMode and one policy, so only roads the mode may use and the policy has not excluded are in the graph, and the ETA is costed exactly as the search costed it.
 * The graph is tiny by pathfinding standards -- a few thousand nodes -- so a binary-heap A* with a hash map for scores is plenty.
 */
public final class RoadRouter {

    /**
     * Number of nearest road nodes tried as start and goal. A single nearest node can be a short dead-end stub that reaches nothing, silently reporting "no route" on a fully connected network; every candidate is a source and a target of one search, so the cost is the shared frontier, not the product.
     */
    private static final int START_CANDIDATES = 12;
    private static final int GOAL_CANDIDATES = 12;

    /**
     * Off-road speed as a fraction of walking pace, for the reported ETA. Crossing terrain is slower than following a road, and the first and last hop is walked whatever the mode.
     */
    private static final double OFF_ROAD_SPEED = 0.7;

    /**
     * One directed piece of road: leaving fromNode, arriving at toNode. Only segments wired to real nodes take part, and an edge is a direction -- a two-way road is two edges sharing one segment. The id is handed out once per graph build.
     * The search's state is the edge, not the node, because a turn is priced by the angle between the piece arrived on and the piece left on, so two arrivals at one node are not interchangeable; see Arm.
     */
    private record Edge(int id, int fromNode, int toNode, RoadSegment segment) {
    }

    /**
     * One directed piece as the search sees it: the edge, and the headings at its two ends. Working the headings out once per arm keeps the turn cost from spending trigonometry on every edge examined.
     * NaN is a heading a piece cannot give -- a zero-length synthetic join has no direction -- and both callers read that as "not a turn".
     */
    private record Arm(Edge edge, double headingIn, double headingOut) {

        int id() {
            return edge.id();
        }

        int toNode() {
            return edge.toNode();
        }

        RoadSegment segment() {
            return edge.segment();
        }
    }

    /** The arm for an edge, worked out once per search and kept in arms. */
    private static Arm armOf(Map<Integer, Arm> arms, Edge edge) {
        return arms.computeIfAbsent(edge.id(), id -> new Arm(edge,
                travelBearing(edge.segment(), edge.toNode()),
                leaveBearing(edge.segment(), edge.fromNode())));
    }

    /**
     * Extra search cost on a chosen set of roads, which is how a second plan is made genuinely different. A multiplier rather than a ban: removing the first plan's roads would answer "no route" whenever they are the only way through. Whole roads rather than segments, keyed by the same grouping keys the route reports.
     */
    private record Tolls(RoadChains.Grouping grouping, Set<Integer> roads, double multiplier) {

        /** No toll at all: the ordinary plan. */
        static final Tolls NONE = new Tolls(null, Set.of(), 1.0);

        /** The multiplier for one road, one for every road not being discouraged. */
        double on(RoadSegment segment) {
            if (roads.isEmpty() || grouping == null) {
                return 1.0;
            }
            return roads.contains(grouping.keyOf(segment)) ? multiplier : 1.0;
        }
    }

    private RoadRouter() {
    }

    /**
     * A network the router may split, shared by a batch of queries. Anchoring breaks a segment, which cannot be done to the caller's network; the copy is made once, on the batch's first query, and every later split lands on it.
     * Splitting is additive, so no query can be invalidated by an earlier one. The copy is not repaired: a crossing and a bridge look the same on a map that cannot show height, so a road connects only where a node says it does.
     * One workspace belongs to one batch, and the network inside it must not be edited between queries.
     */
    public static final class Workspace {

        private final RoadNetwork source;
        /** The revision of source this workspace was made from; see WorkspaceCache. */
        private int sourceRevision;
        /**
         * The one-way state of the world when this workspace was made. Separate from the revision because a direction is not geometry: without it, marking a street one-way left the copy reading two-way.
         */
        private int sourceDirections;
        /**
         * The closure state of the world when this workspace was made, for the same reason: a closed
         * road is absent from the graph, so a copy made before a road was shut would still route down it.
         */
        private int sourceClosures;
        /**
         * The storeys of the world when this workspace was made, for the same reason: a storey decides which near-coincident nodes join, so a copy made before a road moved would serve stale joins.
         */
        private int sourceLayers;
        private RoadNetwork work;
        private RoadEditor editor;
        private RoadChains.Grouping grouping;
        private int groupingSegments = -1;
        /** The graph as of graphRevision and graphDirections. */
        private Map<Integer, List<Edge>> graph;
        private RoutePreferences graphPreferences;
        private TravelMode graphMode;
        private int graphRevision = Integer.MIN_VALUE;
        private int graphDirections = Integer.MIN_VALUE;
        /**
         * @see #sourceClosures -- the graph's version of the closures.
         */
        private int graphClosures = Integer.MIN_VALUE;
        /**
         * @see #sourceLayers -- the graph's version of the storeys.
         */
        private int graphLayers = Integer.MIN_VALUE;
        /**
         * The walker's gap reach the graph was built with. The coincident pass makes walking links up to walk_offroad_blocks long, and that setting can change without touching the network.
         */
        private double graphWalkGapReach = Double.NaN;

        public Workspace(RoadNetwork source) {
            this.source = source;
        }

        /** The network to read: a copy of the caller's, made on first use. */
        RoadNetwork routingNetwork() {
            if (work == null) {
                work = source.deepCopy();
                // Without undo: every split would otherwise copy the whole network again.
                editor = RoadEditor.withoutUndo(work);
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
         * The road grouping for the network as it stands, rebuilt only after a split changed it. A split removes one segment and adds two, so the segment count is its version.
         */
        RoadChains.Grouping grouping() {
            RoadNetwork network = routingNetwork();
            if (grouping == null || groupingSegments != network.segmentCount()) {
                grouping = RoadChains.group(network);
                groupingSegments = network.segmentCount();
            }
            return grouping;
        }

        /**
         * The graph for one mode and policy, built once per version of the network. A transit plan asks for the same graph dozens of times, and building it walks every segment plus a coincident pass over every node.
         * The version is the segment count (what a split changes) plus the direction-change count, because RoadNetwork.revision does not move for a one-way flag and a two-way graph would route the wrong way down the street.
         */
        Map<Integer, List<Edge>> graphFor(TravelMode mode, RoutePreferences preferences) {
            RoadNetwork network = routingNetwork();
            int version = network.segmentCount();
            int directions = RoadSegment.directionChanges();
            int closures = RoadSegment.closureChanges();
            int layers = RoadSegment.layerChanges();
            double walkGapReach = walkGapReach();
            if (graph == null || graphRevision != version || graphDirections != directions
                    || graphClosures != closures || graphLayers != layers
                    || graphWalkGapReach != walkGapReach
                    || graphMode != mode || !graphPreferences.equals(preferences)) {
                graph = buildGraph(network, mode, preferences);
                graphRevision = version;
                graphDirections = directions;
                graphClosures = closures;
                graphLayers = layers;
                graphWalkGapReach = walkGapReach;
                graphMode = mode;
                graphPreferences = preferences;
            }
            return graph;
        }
    }

    /**
     * Workspaces kept for the networks a plan is being made over, so a batch of queries shares each. A workspace must outlive one query: the convenience entry point makes one per call, and the transit planner used to copy the same line tracks thousands of times.
     * The keys are weak against the source networks, so a workspace lives exactly as long as its network. The revision and the one-way state are checked on the way out, because the editor mutates one network in place for a session.
     */
    private static final class WorkspaceCache {

        private final Map<RoadNetwork, Workspace> entries =
                java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

        Workspace get(RoadNetwork network) {
            int directions = RoadSegment.directionChanges();
            int closures = RoadSegment.closureChanges();
            int layers = RoadSegment.layerChanges();
            Workspace found = entries.get(network);
            if (found == null || found.sourceRevision != network.revision()
                    || found.sourceDirections != directions
                    || found.sourceClosures != closures
                    || found.sourceLayers != layers) {
                found = new Workspace(network);
                found.sourceRevision = network.revision();
                found.sourceDirections = directions;
                found.sourceClosures = closures;
                found.sourceLayers = layers;
                entries.put(network, found);
            }
            return found;
        }
    }

    private static final WorkspaceCache WORKSPACES = new WorkspaceCache();

    /**
     * The workspace a batch of queries should share, kept across batches: this is what makes a line's copy be paid once for the session rather than once per journey.
     */
    public static Workspace workspaceFor(RoadNetwork network) {
        return WORKSPACES.get(network);
    }

    public static Route findRoute(RoadNetwork network, double startX, double startZ,
                                  double goalX, double goalZ, String destinationName) {
        return findRoute(network, startX, startZ, goalX, goalZ, destinationName, TravelMode.WALK,
                RoutePreferences.DEFAULTS);
    }

    public static Route findRoute(RoadNetwork network, double startX, double startZ,
                                  double goalX, double goalZ, String destinationName,
                                  TravelMode mode) {
        return findRoute(network, startX, startZ, goalX, goalZ, destinationName, mode,
                RoutePreferences.DEFAULTS);
    }

    public static Route findRoute(RoadNetwork network, double startX, double startZ,
                                  double goalX, double goalZ, String destinationName,
                                  TravelMode mode, RoutePreferences preferences) {
        return findRoute(WORKSPACES.get(network), startX, startZ, goalX, goalZ, destinationName,
                mode, preferences);
    }

    /**
     * Plans on a workspace the caller owns, so a batch of queries pays for the anchoring splits once. Same answer as the convenience entry point, with the per-query state hoisted out.
     */
    public static Route findRoute(Workspace workspace, double startX, double startZ,
                                  double goalX, double goalZ, String destinationName,
                                  TravelMode mode, RoutePreferences preferences) {
        return plan(workspace, startX, startZ, goalX, goalZ, destinationName, mode, preferences,
                Tolls.NONE);
    }

    /**
     * Plans a route that keeps off the given roads where it can: the roads a finished Route reports through Route.roadKeys(), made expensive rather than forbidden (see Tolls). A trip whose only way is through them still gets a slower route.
     * @param discouragedRoads the roads to avoid, as grouping keys
     * @param multiplier how much more they cost, above one
     */
    public static Route findRouteAvoiding(Workspace workspace, double startX, double startZ,
                                          double goalX, double goalZ, String destinationName,
                                          TravelMode mode, RoutePreferences preferences,
                                          Set<Integer> discouragedRoads, double multiplier) {
        if (discouragedRoads == null || discouragedRoads.isEmpty() || multiplier <= 1.0) {
            return findRoute(workspace, startX, startZ, goalX, goalZ, destinationName, mode,
                    preferences);
        }
        return plan(workspace, startX, startZ, goalX, goalZ, destinationName, mode, preferences,
                new Tolls(workspace.grouping(), Set.copyOf(discouragedRoads), multiplier));
    }

    /**
     * How two places stand to each other on a network, for a caller that must say whether they are connected rather than whether a trip could be planned. A plan is refused for reasons that are not about connection -- a connector longer than the mode allows, a one-way facing the wrong way -- and reading "no plan" as "not connected" marks a healthy line red.
     */
    public enum Connection {

        /** A path from the first place to the second exists on roads this mode and policy allow. */
        CONNECTED,

        /**
         * Both places are tied to the network and no path runs from the first to the second, possibly because a one-way restriction faces the wrong way. This is the only answer that means "not connected".
         */
        SEPARATE,

        /**
         * The question has no answer from this network: no road the mode may use exists, or none comes within JUDGEMENT_REACH. A caller must treat this as "cannot tell" -- a large station whose point stands off its own track is exactly this.
         */
        UNJUDGED
    }

    /**
     * How far from a place a road of the right kind may be and still be the road it is judged against, in blocks. Not the connector cap: a station a hundred blocks off its track still names a station on that track, and its distance from the rail is a fact about the station. 256 is the merge distance such a station is assembled under; past it the answer is UNJUDGED.
     */
    private static final double JUDGEMENT_REACH = 256.0;

    /**
     * Whether the two places are connected, which is not the same question as whether a trip can be planned -- see Connection. Each place is joined the way the planner joins it (split at the perpendicular foot), then a search asks whether a path runs in that direction.
     * The search starts from the whole neighbourhood of each place, because a place can stand beside more than one piece of road. A place with no road of the wanted kind within JUDGEMENT_REACH is not judged at all.
     * A place whose nearest road is shut is not split: a shut road is not a road to anchor a line on, and the split would add a junction to the workspace for a piece the line cannot use. It is judged by the usable roads within its own reach instead, which is what catches a closure between two stops.
     * @return CONNECTED, SEPARATE or UNJUDGED
     */
    public static Connection connection(Workspace workspace, double startX, double startZ,
                                        double goalX, double goalZ, TravelMode mode,
                                        RoutePreferences preferences) {
        RoadNetwork network = workspace.routingNetwork();
        RoadEditor editor = workspace.editor();
        // Closed roads are counted when the place is read: a stop standing on a shut road has a road
        // there, and the pair must read as a break rather than as "no road here", which would unmark it.
        RoadPoint startRoad = nearestRoadPointWithin(network, startX, startZ, mode, preferences,
                JUDGEMENT_REACH, true);
        if (startRoad == null) {
            return Connection.UNJUDGED;
        }
        boolean startShut = onClosedRoad(network, startRoad);
        int startNode = startShut ? -1 : anchorNode(network, editor, startRoad);
        if (!startShut && startNode < 0) {
            return Connection.UNJUDGED;
        }
        // Read after the first split: splitting the start's segment very often removes the goal's too.
        RoadPoint goalRoad = nearestRoadPointWithin(network, goalX, goalZ, mode, preferences,
                JUDGEMENT_REACH, true);
        if (goalRoad == null) {
            return Connection.UNJUDGED;
        }
        boolean goalShut = onClosedRoad(network, goalRoad);
        int goalNode = goalShut ? -1 : anchorNode(network, editor, goalRoad);
        if (!goalShut && goalNode < 0) {
            return Connection.UNJUDGED;
        }
        if (startNode >= 0 && startNode == goalNode) {
            // One place on the network, which is what two stops drawn on the same spot are.
            return Connection.CONNECTED;
        }
        Map<Integer, List<Edge>> graph = workspace.graphFor(mode, preferences);
        if (graph.isEmpty()) {
            return Connection.UNJUDGED;
        }
        List<Integer> starts = neighbourhood(network, graph, startNode, startX, startZ, mode);
        List<Integer> goals = neighbourhood(network, graph, goalNode, goalX, goalZ, mode);
        return reachesAny(graph, starts, goals) ? Connection.CONNECTED : Connection.SEPARATE;
    }

    /**
     * Whether a road point lies on a shut segment, read on the point's own segment and before anything
     * splits it. See connection for what the answer is used for.
     */
    private static boolean onClosedRoad(RoadNetwork network, RoadPoint road) {
        RoadSegment segment = network.segment(road.segmentId());
        return segment != null && segment.closed();
    }

    /**
     * How far from a place the nearest node may be for it to be an endpoint of a fallback trip, in blocks. Not the walker's maxConnectorDistance, a practical infinity: this is the reach of a candidate endpoint, and a candidate across a gap is the beeline the off-road allowance exists to refuse.
     */
    private static double endpointReach(TravelMode mode) {
        return mode == TravelMode.WALK
                ? Math.max(COINCIDENT_DISTANCE, walkGapReach())
                : mode.maxConnectorDistance();
    }

    /**
     * The nodes one place could be travelling from or to: the node it was just anchored as, plus every routable node within the mode's connector reach -- not JUDGEMENT_REACH, since these are endpoints a ride would really use.
     */
    private static List<Integer> neighbourhood(RoadNetwork network, Map<Integer, List<Edge>> graph,
                                               int anchor, double x, double z, TravelMode mode) {
        List<Integer> nodes = new ArrayList<>();
        if (graph.containsKey(anchor)) {
            nodes.add(anchor);
        }
        nodes.addAll(nearestNodesWithin(network, graph, x, z, START_CANDIDATES,
                endpointReach(mode)));
        return nodes;
    }

    /** Whether a path runs from any of the sources to any of the targets, in that direction. */
    private static boolean reachesAny(Map<Integer, List<Edge>> graph, List<Integer> starts,
                                      List<Integer> goals) {
        Set<Integer> targets = new HashSet<>(goals);
        Set<Integer> seen = new HashSet<>(starts);
        java.util.ArrayDeque<Integer> queue = new java.util.ArrayDeque<>(starts);
        while (!queue.isEmpty()) {
            int current = queue.poll();
            if (targets.contains(current)) {
                return true;
            }
            for (Edge edge : graph.getOrDefault(current, List.of())) {
                if (seen.add(edge.toNode())) {
                    queue.add(edge.toNode());
                }
            }
        }
        return false;
    }

    /** One attempt: anchored if it can be, between the nearest nodes if it cannot. */
    private static Route plan(Workspace workspace, double startX, double startZ, double goalX,
                              double goalZ, String destinationName, TravelMode mode,
                              RoutePreferences preferences, Tolls tolls) {
        // Worked out once for both attempts, from the saved network rather than the workspace copy.
        Set<Integer> continuations =
                highwayContinuations(workspace.routingNetwork(), mode, preferences);
        Route anchored = findAnchoredRoute(workspace, startX, startZ, goalX, goalZ, destinationName,
                mode, preferences, continuations, tolls);
        if (anchored != null) {
            return anchored;
        }
        return findNodeRoute(workspace, startX, startZ, goalX, goalZ,
                destinationName, mode, preferences, continuations, tolls);
    }

    /**
     * Routes between the points on the road network closest to each end. Routing is node-to-node, so the road is split at the perpendicular foot and that new node becomes the endpoint: the connector is a short straight hop onto the road the player stands beside.
     * A connector longer than the mode allows is refused rather than drawn as a beeline. The split happens on the workspace's own copy.
     * @return null when there is nothing to anchor to, the anchors are too far, or they are not connected
     */
    private static Route findAnchoredRoute(Workspace workspace, double startX, double startZ,
                                           double goalX, double goalZ, String destinationName,
                                           TravelMode mode, RoutePreferences preferences,
                                           Set<Integer> continuations, Tolls tolls) {
        RoadNetwork network = workspace.mutable();
        RoadEditor editor = workspace.editor();
        RoadPoint startRoad = nearestRoadPoint(network, startX, startZ, mode, preferences);
        RoadPoint goalRoad = nearestRoadPoint(network, goalX, goalZ, mode, preferences);
        if (startRoad == null || goalRoad == null) {
            return null;
        }

        int startNode = anchorNode(network, editor, startRoad);
        // Splitting the start removes the segment it was on, and the goal is very often on that same segment; re-project the goal onto what is left rather than dropping into the slower fallback.
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
        // Measured on the nodes the connectors are drawn from, so anchor rounding cannot exceed the cap.
        double maxConnector = mode.maxConnectorDistance();
        if (anchorDistance(network, startNode, startX, startZ) > maxConnector
                || anchorDistance(network, goalNode, goalX, goalZ) > maxConnector) {
            return null;
        }
        if (startNode == goalNode) {
            return preferDirectWalk(
                    buildRoute(network, workspace.grouping(), startNode, goalNode, List.of(),
                            startX, startZ, goalX, goalZ, destinationName, mode, preferences),
                    startX, startZ, goalX, goalZ, destinationName, mode);
        }

        Map<Integer, List<Edge>> graph = workspace.graphFor(mode, preferences);
        List<RoadSegment> path = search(graph, network, startNode, goalNode, mode, preferences,
                continuations, tolls);
        if (path == null) {
            return null;
        }
        return preferDirectWalk(
                buildRoute(network, workspace.grouping(), startNode, goalNode, path,
                        startX, startZ, goalX, goalZ, destinationName, mode, preferences),
                startX, startZ, goalX, goalZ, destinationName, mode);
    }

    /**
     * The walker's own line, when the road the trip anchored to is not on the way. Two points whose nearest road point is the same node used to make a connector-only route that doubled back, hundreds of blocks longer than the straight walk. So when the route uses no road at all and the straight line is shorter, the straight line is the route.
     * Only walking: a drive or a ride is a line the vehicle follows, and a car beside a road is parked rather than about to cross the field.
     */
    private static Route preferDirectWalk(Route route, double startX, double startZ, double goalX,
                                          double goalZ, String destinationName, TravelMode mode) {
        if (mode != TravelMode.WALK || route == null || !route.isPresent()
                || route.roadLength() > 1.0E-9) {
            return route;
        }
        double direct = Math.hypot(goalX - startX, goalZ - startZ);
        if (direct <= 1.0E-6 || direct >= route.totalLength() - 1.0E-6) {
            return route;
        }
        Route.Builder builder = new Route.Builder();
        builder.setDestinationName(destinationName);
        builder.setTravelMode(TravelMode.WALK);
        builder.setOffRoadSpeedFactor(OFF_ROAD_SPEED);
        double tolerance = RoadConfig.onRoadTolerance(RoadClass.ROAD);
        builder.addPoint(startX, startZ, tolerance, 0, null, false);
        // The whole line is the walked hop, so it goes on the first connector.
        builder.setStartConnector(direct);
        builder.addPoint(goalX, goalZ, tolerance, 0, null, false);
        return builder.build();
    }

    /**
     * Whether the point is one of the two ends of the segment, which are its only nodes. A RoadPoint's t of zero is the earlier of two vertices and t of one the later; only vertex zero and the last vertex are nodes, so a point on a bend still needs a node of its own -- reading "t is one" as "the segment's end" made standing on a bend the worst place to be.
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
     * Turns a point on a road into a node, splitting the segment there unless it already lands on an endpoint. The editor is the workspace's; a point arriving without one is a refused anchor.
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
     * Closest point on the network this trip may actually use, closed roads excluded: a shut street is not a road the trip may anchor to, and anchoring to it would split it and hand the search two pieces of it. Only segments wired to nodes are considered, and the filters matter just as much: without them a driver would anchor to the footpath beside the road or to an avoided class.
     */
    private static RoadPoint nearestRoadPoint(RoadNetwork network, double x, double z,
                                              TravelMode mode, RoutePreferences preferences) {
        return nearestRoadPointWithin(network, x, z, mode, preferences, Double.MAX_VALUE, false);
    }

    /**
     * The same with the closed roads counted, for the one question that is about where roads are rather than where a trip may go: tell someone standing on a shut street that there is no road there and they will go looking for one that is right under them. See nearestRoadPoint.
     */
    private static RoadPoint nearestRoadPointIncludingClosed(RoadNetwork network, double x, double z,
                                                             TravelMode mode,
                                                             RoutePreferences preferences) {
        return nearestRoadPointWithin(network, x, z, mode, preferences, Double.MAX_VALUE, true);
    }

    /**
     * The same, considering only roads within radius blocks. Not an optimisation: a caller asking "is there a road nearer than this one" must not have the whole network read.
     * @param includeClosed whether a shut road counts as a road for this question; see nearestRoadPoint
     */
    private static RoadPoint nearestRoadPointWithin(RoadNetwork network, double x, double z,
                                                    TravelMode mode, RoutePreferences preferences,
                                                    double radius, boolean includeClosed) {
        RoadPoint best = null;
        double bestDistanceSq = radius * radius;
        for (RoadSegment segment : network.segmentsSnapshot()) {
            if (!includeClosed && segment.closed()) {
                // A closure is a routing absence, exactly as buildGraph treats it: anchoring here
                // would split the shut road and let the trip travel down it.
                continue;
            }
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
     * Fallback routing between the nearest nodes when anchoring is impossible or the anchors are not connected. The endpoints still have to be reachable: the first and last hop are drawn as straight lines whatever their length, so a node past the connector cap produces a beeline and can send a trip the wrong way up a one-way street. Refusing the fallback past the cap is what makes a one-way mean what it says.
     */
    private static Route findNodeRoute(Workspace workspace, double startX, double startZ,
                                       double goalX, double goalZ, String destinationName,
                                       TravelMode mode, RoutePreferences preferences,
                                       Set<Integer> continuations, Tolls tolls) {
        RoadNetwork network = workspace.routingNetwork();
        Map<Integer, List<Edge>> graph = workspace.graphFor(mode, preferences);
        if (graph.isEmpty()) {
            return Route.empty();
        }

        List<Integer> starts =
                nearestNodesWithin(network, graph, startX, startZ, START_CANDIDATES,
                        endpointReach(mode));
        List<Integer> goals =
                nearestNodesWithin(network, graph, goalX, goalZ, GOAL_CANDIDATES,
                        endpointReach(mode));
        if (starts.isEmpty() || goals.isEmpty()) {
            return Route.empty();
        }

        Best best = searchBetweenCandidates(graph, network, starts, goals, startX, startZ, goalX,
                goalZ, mode, preferences, continuations, tolls);
        if (best == null || beyondConnector(network, best.source(), startX, startZ, mode)
                || beyondConnector(network, best.goal(), goalX, goalZ, mode)) {
            return Route.empty();
        }
        // The workspace's grouping, not a fresh one: this is the fallback path a transit plan walks per ride.
        Route route = buildRoute(network, workspace.grouping(), best.source(), best.goal(),
                best.path(), startX, startZ, goalX, goalZ, destinationName, mode, preferences);
        if (overshotTheRoad(network, best, route)) {
            return Route.empty();
        }
        return preferDirectWalk(route, startX, startZ, goalX, goalZ, destinationName, mode);
    }

    /**
     * Whether the node is further from the point than this trip may reach off the road, measured on the node the connector is drawn to.
     */
    private static boolean beyondConnector(RoadNetwork network, int nodeId, double x, double z,
                                           TravelMode mode) {
        RoadNode node = network.node(nodeId);
        return node != null
                && Math.hypot(node.x() - x, node.z() - z) > mode.maxConnectorDistance();
    }

    /**
     * Whether the last hop is standing in for a road the trip declined to use: two roads drawn sixty blocks apart are two fragments, and the node fallback's freedom to pick its end node would close the gap with a beeline the map cannot tell from a junction.
     * The two numbers are how far past the destination's own nearest point on the arrival road the trip stopped, and how long the hop actually is. The hop replaces road travel only when it is the shorter of the two; when it is longer it is just the trip leaving the road to reach a destination beside it, and the mode's cap exists to allow that.
     * That tells a genuine gap apart from a stub beside the destination or a destination on a bridge above the road.
     */
    private static boolean overshotTheRoad(RoadNetwork network, Best best, Route route) {
        if (best.path().isEmpty() || !route.isPresent()) {
            // The whole trip is a hop between two nodes of one road.
            return false;
        }
        // The road the trip arrived on, and the node it arrived at.
        RoadSegment arrival = best.path().get(best.path().size() - 1);
        RoadNode at = network.node(best.goal());
        if (at == null) {
            return false;
        }
        // The route's last point is the destination itself, so this compares two positions on the arrival road.
        double[] destination = route.points().get(route.points().size() - 1);
        double alongNode = along(arrival, at.x(), at.z());
        double alongGoal = along(arrival, destination[0], destination[1]);
        if (Double.isNaN(alongNode) || Double.isNaN(alongGoal)) {
            return false;
        }
        return alongNode - alongGoal > route.goalConnector();
    }

    /**
     * How far a point lies along a segment's polyline, in blocks from its first vertex: the closest projection onto the spans, so zero is the from end and the total length the to end.
     */
    private static double along(RoadSegment segment, double x, double z) {
        double best = Double.NaN;
        double bestDistance = Double.MAX_VALUE;
        double travelled = 0;
        for (int i = 1; i < segment.vertexCount(); i++) {
            double ax = segment.x(i - 1);
            double az = segment.z(i - 1);
            double ex = segment.x(i) - ax;
            double ez = segment.z(i) - az;
            double length = Math.hypot(ex, ez);
            double t = length < 1.0E-9 ? 0
                    : Math.max(0, Math.min(1, ((x - ax) * ex + (z - az) * ez) / (length * length)));
            double distance = Math.hypot(ax + ex * t - x, az + ez * t - z);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = travelled + t * length;
            }
            travelled += length;
        }
        return best;
    }

    /** The whole fallback trip: which candidate it leaves from, which it arrives at, and the road. */
    private record Best(int source, int goal, List<RoadSegment> path) {
    }

    /**
     * Walks a predecessor chain of arms back to the piece the trip set off along; a source arm has no predecessor. Null for a chain that does not end.
     */
    private static List<RoadSegment> armChain(Map<Integer, Integer> cameFrom,
                                              Map<Integer, Arm> arrived, int from) {
        List<RoadSegment> reversed = new ArrayList<>();
        int current = from;
        // Bounded by the predecessor count: guards against a malformed chain looping forever.
        int guard = cameFrom.size() + 2;
        while (guard-- > 0) {
            Arm arm = arrived.get(current);
            if (arm == null) {
                return null;
            }
            reversed.add(arm.segment());
            Integer previous = cameFrom.get(current);
            if (previous == null) {
                List<RoadSegment> path = new ArrayList<>(reversed.size());
                for (int i = reversed.size() - 1; i >= 0; i--) {
                    path.add(reversed.get(i));
                }
                return path;
            }
            current = previous;
        }
        return null;
    }

    /**
     * One multi-source, multi-target A* over the candidate endpoints: every start is a seeded source, every goal a target, and the answer is the cheapest whole trip with both connectors. A loop of separate searches per pair could not compare whole trips and explored its whole component on every "no".
     * The heuristic is the distance to the nearest goal -- over every goal, since a heuristic that changes as the search proceeds is no heuristic -- and the search stops as soon as the cheapest arrival cannot be beaten.
     */
    private static Best searchBetweenCandidates(Map<Integer, List<Edge>> graph, RoadNetwork network,
                                                List<Integer> starts, List<Integer> goals,
                                                double startX, double startZ, double goalX,
                                                double goalZ, TravelMode mode,
                                                RoutePreferences preferences,
                                                Set<Integer> continuations, Tolls tolls) {
        Map<Integer, Double> gScore = new HashMap<>();
        Map<Integer, Integer> turns = new HashMap<>();
        Map<Integer, Integer> cameFrom = new HashMap<>();
        Map<Integer, Integer> sourceOf = new HashMap<>();
        Map<Integer, Arm> arrived = new HashMap<>();
        Map<Integer, Arm> arms = new HashMap<>();
        Set<Integer> closed = new HashSet<>();

        // Ordered by cost and then turns: on a rectilinear network the cost cannot tell a staircase from one turn.
        PriorityQueue<double[]> frontier = new PriorityQueue<>(
                Comparator.<double[]>comparingDouble(a -> a[0]).thenComparingDouble(a -> a[1]));
        for (int start : starts) {
            double connector = connectorCost(network, start, startX, startZ, mode, preferences);
            for (Edge edge : graph.getOrDefault(start, List.of())) {
                // The first piece has no arrival, so no turn is priced; the bend inside the piece is still refused.
                if (!highwayBendAllowed(null, start, edge.segment(), continuations)) {
                    continue;
                }
                int target = edge.id();
                double cost = connector + edgeCost(edge.segment(), mode, preferences)
                        * tolls.on(edge.segment());
                if (cost >= gScore.getOrDefault(target, Double.MAX_VALUE) - COST_EPSILON) {
                    continue;
                }
                gScore.put(target, cost);
                turns.put(target, 0);
                sourceOf.put(target, start);
                arrived.put(target, armOf(arms, edge));
                frontier.add(new double[]{
                        cost + nearestGoalEstimate(network, edge.toNode(), goals, mode, preferences),
                        0, target});
            }
        }

        // The state that reached the goal, not just the node: arrivals differ once the turn into them is priced.
        int bestState = -1;
        double bestTotal = Double.MAX_VALUE;
        while (!frontier.isEmpty() && frontier.peek()[0] < bestTotal) {
            int currentId = (int) frontier.poll()[2];
            if (!closed.add(currentId)) {
                continue;
            }
            Arm current = arrived.get(currentId);
            if (current == null) {
                continue;
            }
            int node = current.toNode();
            if (goals.contains(node)) {
                double total = gScore.getOrDefault(currentId, Double.MAX_VALUE)
                        + connectorCost(network, node, goalX, goalZ, mode, preferences);
                if (total < bestTotal) {
                    bestTotal = total;
                    bestState = currentId;
                }
            }
            double currentG = gScore.getOrDefault(currentId, Double.MAX_VALUE);
            if (currentG >= bestTotal) {
                continue;
            }
            int currentTurns = turns.getOrDefault(currentId, 0);
            for (Edge edge : graph.getOrDefault(node, List.of())) {
                Arm next = armOf(arms, edge);
                // Turning round and heading straight back to the node just left is a U-turn, not a road: it would let the search reset the angle it arrives on, so a dead end beside a junction becomes a way to reach it pointing the other way. Refused rather than priced.
                if (next.edge().toNode() == current.edge().fromNode()) {
                    continue;
                }
                // The same highway-bend rule the anchored search asks, asked here because this search builds a route too: without it a highway reversal refused by the anchored search was then planned by this fallback.
                if (!highwayBendAllowed(current.segment(), node, next.segment(), continuations)) {
                    continue;
                }
                int target = edge.id();
                double tentative = currentG + turnCost(current, next, mode, preferences)
                        + edgeCost(next.segment(), mode, preferences) * tolls.on(next.segment());
                int viaTurns = currentTurns + (turnsAt(current, next) ? 1 : 0);
                double known = gScore.getOrDefault(target, Double.MAX_VALUE);
                boolean better = tentative < known - COST_EPSILON;
                boolean tidier = !better && tentative <= known + COST_EPSILON
                        && viaTurns < turns.getOrDefault(target, Integer.MAX_VALUE);
                if (!better && !tidier) {
                    continue;
                }
                double settled = Math.min(tentative, known);
                gScore.put(target, settled);
                turns.put(target, viaTurns);
                sourceOf.put(target, sourceOf.getOrDefault(currentId, -1));
                arrived.put(target, next);
                cameFrom.put(target, currentId);
                frontier.add(new double[]{
                        settled + nearestGoalEstimate(network, next.toNode(), goals, mode,
                                preferences),
                        viaTurns, target});
            }
        }

        // A trip whose two ends are one node: nothing to drive, only the connectors. The arm-keyed search seeds only ways out, so the case is asked for here.
        int sharedNode = -1;
        for (int start : starts) {
            if (!goals.contains(start)) {
                continue;
            }
            double total = connectorCost(network, start, startX, startZ, mode, preferences)
                    + connectorCost(network, start, goalX, goalZ, mode, preferences);
            if (total < bestTotal) {
                bestTotal = total;
                sharedNode = start;
            }
        }
        if (sharedNode >= 0) {
            return new Best(sharedNode, sharedNode, List.of());
        }
        if (bestState < 0) {
            return null;
        }
        List<RoadSegment> path = armChain(cameFrom, arrived, bestState);
        if (path == null) {
            return null;
        }
        return new Best(sourceOf.getOrDefault(bestState, -1), arrived.get(bestState).toNode(), path);
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

    // ------------------------------------------------------------------ graph

    /**
     * The graph the given mode and policy may travel on, so a footpath is simply absent from a drive and an avoided class is absent from everything.
     */
    private static Map<Integer, List<Edge>> buildGraph(RoadNetwork network, TravelMode mode,
                                                       RoutePreferences preferences) {
        return buildGraph(network, mode, preferences, false);
    }

    /**
     * The same graph with the closed roads allowed back in. That variant exists for one reader:
     * the failure explainer, which answers "did a closure split the ends apart" by comparing this
     * graph's reach with the routing graph's.
     */
    private static Map<Integer, List<Edge>> buildGraph(RoadNetwork network, TravelMode mode,
                                                       RoutePreferences preferences,
                                                       boolean includeClosed) {
        Map<Integer, List<Edge>> graph = new HashMap<>();
        // One counter for the whole build, so every directed edge has its own id; joins are edges too.
        int[] nextEdgeId = {0};
        for (RoadSegment segment : network.segmentsSnapshot()) {
            if (!includeClosed && segment.closed()) {
                // A closure removes the road from the graph the way a missing road would be absent:
                // neither direction travels it, and nothing downstream needs to know the segment exists.
                continue;
            }
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
            // One edge per direction the road allows, so the graph and the map's arrows read the same rule.
            if (segment.allowsTravelFrom(from)) {
                graph.computeIfAbsent(from, k -> new ArrayList<>())
                        .add(new Edge(nextEdgeId[0]++, from, to, segment));
            }
            if (segment.allowsTravelFrom(to)) {
                graph.computeIfAbsent(to, k -> new ArrayList<>())
                        .add(new Edge(nextEdgeId[0]++, to, from, segment));
            }
        }
        addCoincidentNodeLinks(network, graph, mode, preferences, nextEdgeId);
        return graph;
    }

    /**
     * Distance within which two nodes count as being at the same place. A click a block or two off creates a second node, and the two roads then look joined while the graph sees fragments.
     */
    private static final double COINCIDENT_DISTANCE = 3.0;

    /**
     * Adds zero-ish cost links between nodes that sit essentially on top of each other. The links are real RoadSegment objects, never inserted into the network: they exist only so the polyline has geometry across the join, and they borrow a class the mode may use so a join cannot smuggle a footpath into a drive.
     * Closed roads take no part: a shut street is absent here as it is absent from the graph, so its nodes are not places a join may be made from, and its own two ends are not a gap to be hopped across.
     * The bucket a node is filed under and the bucket looked up must both be the cell, not the node's coordinates; they used to disagree, so the pass silently did nothing away from the origin. Nothing here may take a join away, and the storey rule is the one samePlaceVertically makes, used with the storeys.
     */
    private static void addCoincidentNodeLinks(RoadNetwork network, Map<Integer, List<Edge>> graph,
                                               TravelMode mode, RoutePreferences preferences,
                                               int[] nextEdgeId) {
        List<RoadNode> nodes = network.nodesSnapshot();
        if (nodes.size() < 2) {
            return;
        }
        RoadClass junctionClass = junctionClass(mode, preferences);
        if (junctionClass == null) {
            // Everything this mode could travel on is avoided, so there is no junction to build.
            return;
        }

        // What each node has drawn at it, worked out once: a join is between two pieces of road, so the question is about the segments meeting at the node.
        Map<Integer, Set<RoadClass>> atNode = new HashMap<>();
        // What storeys are drawn at each node: two nodes merely passing within three blocks are one place only when something at each is on the same storey, which stops a bridge reading as a crossroads.
        Map<Integer, Set<Integer>> layersAtNode = new HashMap<>();
        // How many usable pieces meet at each node; one is a road end, where a walker may step off.
        Map<Integer, Integer> usableDegree = new HashMap<>();
        for (RoadSegment segment : network.segmentsSnapshot()) {
            if (segment.closed()) {
                // A closure is an absence, as it is in buildGraph: a shut street must add no degree to
                // its ends, or a walker could step off an end that is not really there.
                continue;
            }
            if (!mode.allows(segment.roadClass()) || preferences.avoids(segment.roadClass())) {
                continue;
            }
            for (int nodeId : new int[]{segment.fromNode(), segment.toNode()}) {
                if (nodeId != RoadSegment.NO_NODE) {
                    atNode.computeIfAbsent(nodeId, k -> java.util.EnumSet.noneOf(RoadClass.class))
                            .add(segment.roadClass());
                    layersAtNode.computeIfAbsent(nodeId, k -> new java.util.HashSet<>())
                            .add(segment.layer());
                    usableDegree.merge(nodeId, 1, Integer::sum);
                }
            }
        }

        // The node pairs one closed street spans: the road itself is the way between its ends, and a
        // shut road's ends must not be joined over the three-block gap, which is the shortest hop of all.
        Set<Long> closedRoadEnds = new HashSet<>();
        for (RoadSegment segment : network.segmentsSnapshot()) {
            if (!segment.closed()) {
                continue;
            }
            int from = segment.fromNode();
            int to = segment.toNode();
            if (from != RoadSegment.NO_NODE && to != RoadSegment.NO_NODE && from != to) {
                closedRoadEnds.add(nodePairKey(from, to));
            }
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
                        if (!samePlaceVertically(a, b)) {
                            continue;
                        }
                        if (closedRoadEnds.contains(nodePairKey(a.id(), b.id()))) {
                            // One shut street's two ends: the join would be a way across its closure.
                            continue;
                        }
                        if (!shareARoad(atNode.get(a.id()), atNode.get(b.id()))) {
                            continue;
                        }
                        // A storey is a statement about a vehicle: a bridge is not the road under it, and a drive must not climb from one onto the other. For walking the join is only allowed on the surface -- a footpath over a bridge and the one under it stay two paths.
                        if (mode == TravelMode.WALK) {
                            if (!shareSurfaceStorey(layersAtNode.get(a.id()), layersAtNode.get(b.id()))) {
                                continue;
                            }
                        } else if (!shareALayer(layersAtNode.get(a.id()),
                                layersAtNode.get(b.id()))) {
                            continue;
                        }
                        RoadSegment link = syntheticLink(a, b, junctionClass);
                        graph.computeIfAbsent(a.id(), k -> new ArrayList<>())
                                .add(new Edge(nextEdgeId[0]++, a.id(), b.id(), link));
                        graph.computeIfAbsent(b.id(), k -> new ArrayList<>())
                                .add(new Edge(nextEdgeId[0]++, b.id(), a.id(), link));
                    }
                }
            }
        }
        // The one distance the storey does not decide: a walker may step off a road end and cross open ground, bounded by the walker's own off-road allowance.
        if (mode == TravelMode.WALK) {
            double walkReach = walkGapReach();
            if (walkReach > COINCIDENT_DISTANCE) {
                RoadClass gapClass = preferences.avoids(RoadClass.PATH)
                        || !mode.allows(RoadClass.PATH) ? junctionClass : RoadClass.PATH;
                addWalkGapLinks(network, graph, atNode, layersAtNode, usableDegree, walkReach,
                        gapClass, nextEdgeId);
            }
        }
    }

    /**
     * The furthest the walker's gap beeline is looked for, in blocks. Not a second setting but a cap on what one plan may do, and the setting is declared with this as its upper bound.
     */
    private static final double MAX_WALK_GAP_REACH = 256.0;

    /** The gap reach one plan actually applies, after the cap. */
    private static double walkGapReach() {
        return Math.min(RoadConfig.walkOffroadBlocks(), MAX_WALK_GAP_REACH);
    }

    /**
     * Joins a road end to a nearby road on the surface -- the field crossing a walker makes. The pass above stops at three blocks; walk_offroad_blocks was documented but never implemented, so two roads plainly nearly meeting read as no path.
     * Only a road end may be left (a node with a single usable piece): joining every nearby pair would turn a field beside a road into a network of shortcuts, and a walker wanting to cross from the middle is at a trip end anyway.
     * The link is a footpath (or the junction class when footpaths are avoided) so the estimate does not pretend the crossing is a made road; a road's own two ends are left to that road.
     */
    private static void addWalkGapLinks(RoadNetwork network, Map<Integer, List<Edge>> graph,
                                        Map<Integer, Set<RoadClass>> atNode,
                                        Map<Integer, Set<Integer>> layersAtNode,
                                        Map<Integer, Integer> usableDegree, double reach,
                                        RoadClass gapClass, int[] nextEdgeId) {
        List<RoadNode> nodes = network.nodesSnapshot();
        double cell = reach;
        Map<Long, List<RoadNode>> buckets = new HashMap<>();
        for (RoadNode node : nodes) {
            buckets.computeIfAbsent(bucketKey(cellOf(node.x(), cell), cellOf(node.z(), cell)),
                    k -> new ArrayList<>()).add(node);
        }
        double maxSq = reach * reach;
        double coincidentSq = COINCIDENT_DISTANCE * COINCIDENT_DISTANCE;
        // Two ends of one road are already joined by that road; a chord across its curve is shorter and would always win, drawing a walk straight over the inside of the bend.
        Set<Long> joinedByTheirRoad = new HashSet<>();
        for (RoadSegment segment : network.segmentsSnapshot()) {
            int from = segment.fromNode();
            int to = segment.toNode();
            if (from >= 0 && to >= 0 && from != to) {
                joinedByTheirRoad.add(nodePairKey(from, to));
            }
        }
        for (RoadNode a : nodes) {
            if (usableDegree.getOrDefault(a.id(), 0) != 1) {
                // Only an end of a road may be left; see the method comment.
                continue;
            }
            int cx = cellOf(a.x(), cell);
            int cz = cellOf(a.z(), cell);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    List<RoadNode> bucket = buckets.get(bucketKey(cx + dx, cz + dz));
                    if (bucket == null) {
                        continue;
                    }
                    for (RoadNode b : bucket) {
                        if (b.id() == a.id()) {
                            continue;
                        }
                        double distanceSq = a.distSq(b.x(), b.z());
                        // The three-block pass owns everything inside that distance, and each pair is asked once.
                        if (distanceSq <= coincidentSq || distanceSq > maxSq) {
                            continue;
                        }
                        if (usableDegree.getOrDefault(b.id(), 0) == 1 && a.id() > b.id()) {
                            continue;
                        }
                        if (joinedByTheirRoad.contains(nodePairKey(a.id(), b.id()))) {
                            // One road's two ends: the road is the way between them.
                            continue;
                        }
                        // A crossing is ground a person can walk, not a bridge: the height difference of a walkable crossing is JOIN_DISTANCE, not the slope allowance.
                        if (Math.abs(a.y() - b.y()) > JOIN_DISTANCE) {
                            continue;
                        }
                        if (!shareARoad(atNode.get(a.id()), atNode.get(b.id()))) {
                            continue;
                        }
                        if (!shareSurfaceStorey(layersAtNode.get(a.id()),
                                layersAtNode.get(b.id()))) {
                            continue;
                        }
                        RoadSegment link = syntheticLink(a, b, gapClass);
                        graph.computeIfAbsent(a.id(), k -> new ArrayList<>())
                                .add(new Edge(nextEdgeId[0]++, a.id(), b.id(), link));
                        graph.computeIfAbsent(b.id(), k -> new ArrayList<>())
                                .add(new Edge(nextEdgeId[0]++, b.id(), a.id(), link));
                    }
                }
            }
        }
    }

    /** Which bucket a coordinate falls in, which is the only thing a bucket key may be built from. */
    private static int cellOf(double coordinate, double cell) {
        return (int) Math.floor(coordinate / cell);
    }

    /**
     * Whether two nodes this close together are also at the same level, which is what makes them one place rather than one over the other. Without it a bridge ramp joined the bridge: two blocks apart horizontally and thirty vertically.
     * The allowance grows with the horizontal gap -- SLOPE_RISE_PER_BLOCK * gap + JOIN_DISTANCE -- which admits a road up a hill (a hand-drawn network's heights are whatever the ground was under each click) and refuses a bridge, and at a gap of zero admits only the join distance itself.
     */
    private static boolean samePlaceVertically(RoadNode a, RoadNode b) {
        double gap = Math.hypot(a.x() - b.x(), a.z() - b.z());
        double allowed = SLOPE_RISE_PER_BLOCK * gap + JOIN_DISTANCE;
        return Math.abs(a.y() - b.y()) <= allowed;
    }

    /**
     * How far a node may stand from another and still be the same place, in blocks. Also the floor of the height allowance: a spot is not flat.
     */
    private static final double JOIN_DISTANCE = 3.0;

    /**
     * How much a road may rise per block of ground, in blocks per block. The allowance grows with the horizontal gap: a flat tolerance refuses the hill and accepts the bridge.
     */
    private static final double SLOPE_RISE_PER_BLOCK = 2.0;

    /**
     * Whether two nodes stand on roads this trip could travel on from both of them, which makes the gap a junction rather than two things merely near each other. A railway crossing a road has a vertex of each inside the join distance, and joining them lets a ride leave the rails; a road and its footpath are two different vehicles.
     * The link's borrowed class says only what the mode may travel on, so the nodes join only when some usable class is present at both -- the same question the graph would ask of a real segment. No height check here; that belongs in samePlaceVertically.
     */
    private static boolean shareARoad(Set<RoadClass> fromA, Set<RoadClass> fromB) {
        if (fromA == null || fromB == null) {
            return false;
        }
        // Some one mode can travel on both: a highway ending beside a road is one place to a driver.
        for (RoadClass here : fromA) {
            for (RoadClass there : fromB) {
                if (TravelMode.shareAMode(here, there)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Whether the two nodes share a storey, which is what makes the gap crossable on the ground: a bridge and the road under it are not joined however close they are. Asked of vehicles, and of walking only through shareSurfaceStorey.
     */
    private static boolean shareALayer(Set<Integer> fromA, Set<Integer> fromB) {
        if (fromA == null || fromB == null) {
            return false;
        }
        for (Integer layer : fromA) {
            if (fromB.contains(layer)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether both nodes stand on the surface storey (0), the only storey a walker may cross a gap on: a footpath over a bridge and the one under it are not the same ground. Node-sharing still joins them.
     */
    private static boolean shareSurfaceStorey(Set<Integer> fromA, Set<Integer> fromB) {
        return fromA != null && fromB != null && fromA.contains(0) && fromB.contains(0);
    }

    private static long bucketKey(int cellX, int cellZ) {
        // Pack two signed cell coordinates into one key; 24 bits each is far beyond any real map.
        return ((long) (cellX & 0xFFFFFF) << 24) | (cellZ & 0xFFFFFF);
    }

    /** One key for two node ids, whichever order they are given in. */
    private static long nodePairKey(int first, int second) {
        int low = Math.min(first, second);
        int high = Math.max(first, second);
        return ((long) low << 32) | (high & 0xFFFFFFFFL);
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
     * A class a junction link may borrow: one the mode may use and the policy has not excluded. Null when the mode has none left, which is also when the trip is honestly unroutable.
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
     * The nearest nodes that appear in the graph and lie within the mode's connector cap. Nodes with no edges are skipped, and nothing within the cap means the trip is refused, with explainFailure giving how far the nearest road was.
     */
    private static List<Integer> nearestRoutableNodes(RoadNetwork network, Map<Integer, List<Edge>> graph,
                                                      double x, double z, int limit,
                                                      TravelMode mode) {
        return nearestNodesWithin(network, graph, x, z, limit, mode.maxConnectorDistance());
    }

    /**
     * The nearest nodes the graph contains, within radius blocks. The radius is the caller's: a plan uses the connector cap, connection uses JUDGEMENT_REACH.
     */
    private static List<Integer> nearestNodesWithin(RoadNetwork network, Map<Integer, List<Edge>> graph,
                                                    double x, double z, int limit, double radius) {
        List<RoadNode> candidates = new ArrayList<>();
        for (RoadNode node : network.nodes()) {
            if (graph.containsKey(node.id())) {
                candidates.add(node);
            }
        }
        candidates.sort(Comparator.comparingDouble(node -> node.distSq(x, z)));

        List<Integer> result = new ArrayList<>(limit);
        double maxSq = radius * radius;
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
     * Connector cost for planning, at the pace the first and last hop is really made and in the same units as edgeCost, so a shortcut is weighed against the road detour it replaces.
     */
    private static double connectorCost(RoadNetwork network, int nodeId, double x, double z,
                                        TravelMode mode, RoutePreferences preferences) {
        RoadNode node = network.node(nodeId);
        if (node == null) {
            return 0;
        }
        double distance = Math.hypot(node.x() - x, node.z() - z);
        if (preferences.metric() == RoutePreference.SHORTEST_DISTANCE) {
            // Distance is distance: a geometric metric must not carry the mode's time preference.
            return distance;
        }
        // Walking pace whatever the mode, or a long connector would look cheap and invite the beeline.
        return distance * mode.offRoadCostFactor() / Math.max(0.05, mode.connectorSpeed());
    }

    /**
     * Why a route could not be found, as a translation key and its arguments. The common cause is a network that is really several disconnected fragments, invisible on the map; reporting the component sizes turns "it just does not work" into something actionable. Also a network with no road of the wanted kind, or a policy that excluded the roads that would have joined the ends -- named explicitly so the player can see their own avoid list broke the journey.
     */
    public static RouteFailure explainFailure(RoadNetwork network, double startX, double startZ,
                                              double goalX, double goalZ) {
        return explainFailure(network, startX, startZ, goalX, goalZ, TravelMode.WALK,
                RoutePreferences.DEFAULTS);
    }

    public static RouteFailure explainFailure(RoadNetwork network, double startX, double startZ,
                                              double goalX, double goalZ, TravelMode mode) {
        return explainFailure(network, startX, startZ, goalX, goalZ, mode, RoutePreferences.DEFAULTS);
    }

    public static RouteFailure explainFailure(RoadNetwork network, double startX, double startZ,
                                              double goalX, double goalZ, TravelMode mode,
                                              RoutePreferences preferences) {
        Object avoided = avoidedNote(preferences);
        Map<Integer, List<Edge>> graph = buildGraph(network, mode, preferences);
        if (graph.isEmpty()) {
            if (avoidsAnything(avoided)) {
                return RouteFailure.of("screen.howtogo.failure.all_avoided", modeName(mode), avoided);
            }
            return RouteFailure.of("screen.howtogo.failure.nothing_to_route_on", modeName(mode));
        }
        // Asked with the closed roads counted: a place standing on a shut street still has a road under
        // it, and answering that there is none sends the player looking for a road that is right there.
        if (nearestRoadPointIncludingClosed(network, startX, startZ, mode, preferences) == null) {
            return RouteFailure.of("screen.howtogo.failure.no_road_at_start", modeName(mode),
                    avoided);
        }
        if (nearestRoadPointIncludingClosed(network, goalX, goalZ, mode, preferences) == null) {
            return RouteFailure.of("screen.howtogo.failure.no_road_at_destination", modeName(mode),
                    avoided);
        }
        List<Integer> starts = nearestRoutableNodes(network, graph, startX, startZ, 1, mode);
        List<Integer> goals = nearestRoutableNodes(network, graph, goalX, goalZ, 1, mode);
        if (starts.isEmpty() || goals.isEmpty()) {
            // Asked before the distance: when the road under an end is shut rather than absent, the
            // nearest open road can be hundreds of blocks off, and "too far" names the wrong problem.
            RouteFailure closed = closureBlocks(network, startX, startZ, goalX, goalZ, mode,
                    preferences, avoided);
            if (closed != null) {
                return closed;
            }
            return RouteFailure.of("screen.howtogo.failure.too_far_from_road", modeName(mode),
                    Math.round(mode.maxConnectorDistance()), avoided);
        }
        Set<Integer> fromStart = component(graph, starts.get(0));
        Set<Integer> fromGoal = component(graph, goals.get(0));
        if (fromStart.contains(goals.get(0))) {
            return RouteFailure.of("screen.howtogo.failure.same_component", fromStart.size());
        }
        RouteFailure closed = closureBlocks(network, startX, startZ, goalX, goalZ, mode,
                preferences, avoided);
        if (closed != null) {
            return closed;
        }
        // No fragment sizes: a pair of node counts only pushes the part that matters off a picker row.
        return RouteFailure.of("screen.howtogo.failure.split_fragments", modeName(mode), avoided);
    }

    /**
     * Whether closures are what stands between the two ends: the open graph has split them, but the same graph with the shut roads allowed back in joins them, so the one thing the player can do is reopen a street. A closure is a rule rather than a hole, so nothing on the map shows where the fragments came from -- this is the only answer that names the cause.
     * Null when no closure answers, and when there is no closure at all; see hasClosedSegments for the cheap gate in front of the extra graph. Callers keep same_component ahead of it: a journey the open graph already joins is not explained by a closure.
     */
    private static RouteFailure closureBlocks(RoadNetwork network, double startX, double startZ,
                                              double goalX, double goalZ, TravelMode mode,
                                              RoutePreferences preferences, Object avoided) {
        if (!hasClosedSegments(network)) {
            return null;
        }
        Map<Integer, List<Edge>> full = buildGraph(network, mode, preferences, true);
        if (full.isEmpty()) {
            return null;
        }
        List<Integer> allStarts = nearestRoutableNodes(network, full, startX, startZ, 1, mode);
        List<Integer> allGoals = nearestRoutableNodes(network, full, goalX, goalZ, 1, mode);
        if (allStarts.isEmpty() || allGoals.isEmpty()
                || !component(full, allStarts.get(0)).contains(allGoals.get(0))) {
            return null;
        }
        return RouteFailure.of("screen.howtogo.failure.closed_blocks", modeName(mode), avoided);
    }

    /**
     * Whether any segment of the network is closed, the cheap gate in front of the closures' second
     * graph: a network with no closure cannot have been split by one.
     */
    private static boolean hasClosedSegments(RoadNetwork network) {
        for (RoadSegment segment : network.segmentsSnapshot()) {
            if (segment.closed()) {
                return true;
            }
        }
        return false;
    }

    /**
     * A clause naming the avoided classes, or nothing when the policy avoids none. Its own argument so a translation decides where the clause goes.
     */
    private static Object avoidedNote(RoutePreferences preferences) {
        return preferences.avoidsAny()
                ? RouteFailure.of("screen.howtogo.failure.avoided_clause",
                        preferences.avoidedSummary())
                : "";
    }

    /** Whether an avoided clause says anything, which is what decides if the message names it. */
    private static boolean avoidsAnything(Object avoided) {
        return avoided instanceof RouteFailure clause ? clause.isPresent()
                : avoided instanceof String text && !text.isEmpty();
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

    /**
     * Single-source A* from one node to another. The frontier holds arms, not nodes, because the cost of arriving depends on where the traveller came from: a turn is priced by the angle between pieces, so a node reached from two streets is two states.
     * Entries are {fScore, turns, edgeId}; stale entries are skipped via the closed set, and turns are the tie-break because under "shortest" a turn costs nothing.
     */
    private static List<RoadSegment> search(Map<Integer, List<Edge>> graph, RoadNetwork network,
                                            int startNode, int goalNode, TravelMode mode,
                                            RoutePreferences preferences,
                                            Set<Integer> continuations, Tolls tolls) {
        RoadNode goal = network.node(goalNode);
        if (goal == null) {
            return null;
        }

        Map<Integer, Double> gScore = new HashMap<>();
        Map<Integer, Integer> turns = new HashMap<>();
        Map<Integer, Integer> cameFrom = new HashMap<>();
        Map<Integer, Arm> arrived = new HashMap<>();
        Map<Integer, Arm> arms = new HashMap<>();
        Set<Integer> closed = new HashSet<>();

        PriorityQueue<double[]> frontier = new PriorityQueue<>(
                Comparator.<double[]>comparingDouble(a -> a[0]).thenComparingDouble(a -> a[1]));

        // Ways out of the start node: leaving is not a turn, but a bend inside the first piece is refused.
        for (Edge edge : graph.getOrDefault(startNode, List.of())) {
            if (!highwayBendAllowed(null, startNode, edge.segment(), continuations)) {
                continue;
            }
            int target = edge.id();
            double cost = edgeCost(edge.segment(), mode, preferences) * tolls.on(edge.segment());
            if (cost >= gScore.getOrDefault(target, Double.MAX_VALUE) - COST_EPSILON) {
                continue;
            }
            gScore.put(target, cost);
            turns.put(target, 0);
            arrived.put(target, armOf(arms, edge));
            frontier.add(new double[]{
                    cost + heuristic(network, edge.toNode(), goal, mode, preferences), 0, target});
        }

        while (!frontier.isEmpty()) {
            int currentId = (int) frontier.poll()[2];
            if (!closed.add(currentId)) {
                continue;
            }
            Arm current = arrived.get(currentId);
            if (current == null) {
                continue;
            }
            if (current.toNode() == goalNode) {
                return armChain(cameFrom, arrived, currentId);
            }

            double currentG = gScore.getOrDefault(currentId, Double.MAX_VALUE);
            int currentTurns = turns.getOrDefault(currentId, 0);
            for (Edge edge : graph.getOrDefault(current.toNode(), List.of())) {
                // A highway bend no highway makes is not an edge: the rule spans the two pieces meeting at a node and the piece's own line, so it lives where both are in hand.
                Arm next = armOf(arms, edge);
                // No U-turn in the middle of a trip: see the same rule in searchBetweenCandidates.
                if (next.edge().toNode() == current.edge().fromNode()) {
                    continue;
                }
                if (!highwayBendAllowed(current.segment(), current.toNode(), next.segment(),
                        continuations)) {
                    continue;
                }
                int target = edge.id();
                double tentative = currentG + turnCost(current, next, mode, preferences)
                        + edgeCost(next.segment(), mode, preferences) * tolls.on(next.segment());
                int viaTurns = currentTurns + (turnsAt(current, next) ? 1 : 0);
                double known = gScore.getOrDefault(target, Double.MAX_VALUE);
                boolean better = tentative < known - COST_EPSILON;
                // Equal cost and fewer turns: replace, so the settled route is the tidiest of the equally good ones.
                boolean tidier = !better && tentative <= known + COST_EPSILON
                        && viaTurns < turns.getOrDefault(target, Integer.MAX_VALUE);
                if (!better && !tidier) {
                    continue;
                }
                double settled = Math.min(tentative, known);
                gScore.put(target, settled);
                turns.put(target, viaTurns);
                cameFrom.put(target, currentId);
                arrived.put(target, next);
                frontier.add(new double[]{
                        settled + heuristic(network, next.toNode(), goal, mode, preferences),
                        viaTurns, target});
            }
        }
        return null;
    }

    /**
     * Segment cost in the metric in force, at the pace the mode makes on this class. Only classes the mode allows and the policy leaves in play reach here.
     * The preference weight multiplies the cost and nothing else: folding it into the pace made the search minimise a number that was not the printed estimate, and under the shortest metric it was dropped altogether.
     */
    private static double edgeCost(RoadSegment segment, TravelMode mode,
                                   RoutePreferences preferences) {
        RoadClass roadClass = segment.roadClass();
        double weight = preferences.weight(roadClass);
        if (preferences.metric() == RoutePreference.SHORTEST_DISTANCE) {
            // Pure geometry, and nothing else: "prefer major roads" is a taste about pace, and length is not pace. A footpath penalty here would return a route that is not the shortest under a label promising exactly that.
            return segment.length() * weight;
        }
        return segment.length() * weight / Math.max(0.05, effectiveSpeed(mode, roadClass));
    }

    /**
     * Pace in blocks per second the mode actually makes on a class, deliberately not preference-adjusted: this is what the route's legs and the panel's estimate are made of, so it must be the speed the player would see.
     */
    private static double effectiveSpeed(TravelMode mode, RoadClass roadClass) {
        return mode.speedOn(roadClass);
    }

    /** Whether a class takes part in this trip at all. */
    private static boolean usable(TravelMode mode, RoutePreferences preferences,
                                  RoadClass roadClass) {
        return mode.allows(roadClass) && !preferences.avoids(roadClass);
    }

    /**
     * How much the route must bend at a node before the bend counts as a turn, in degrees. The same angle the turn prompts use: a bend nobody is told to make is not a turn to count.
     */
    private static final double TURN_BREAK_DEGREES = 25.0;

    /**
     * What a ninety-degree turn is worth to the search, in blocks of road the mode would rather travel instead: one number per mode.
     * Turns used to be only a tie-break, which is not enough on hand-drawn streets whose blocks are ten and eleven long: what comes out is a staircase, in the guidance as well as on the map. The price is stated in blocks because that is the detour the traveller would accept, then converted to the metric's units in turnCost.
     * A driver will go sixty blocks out of their way to avoid a right-angled junction; a walker twelve; a ride is on a track it does not steer, so a turn costs nothing.
     */
    private static final double WALK_TURN_BLOCKS = 12.0;
    private static final double DRIVE_TURN_BLOCKS = 60.0;
    private static final double TRANSIT_TURN_BLOCKS = 0.0;

    /** The turn weight for one mode, in blocks per ninety degrees. */
    private static double turnBlocks(TravelMode mode) {
        return switch (mode) {
            case WALK -> WALK_TURN_BLOCKS;
            case DRIVE -> DRIVE_TURN_BLOCKS;
            case TRANSIT -> TRANSIT_TURN_BLOCKS;
        };
    }

    /**
     * How much the search pays for a turn, in the metric's own units, scaled by the angle: a right angle costs what the mode's turn blocks say and a reversal twice that. Only bends at or past TURN_BREAK_DEGREES are priced, so a curve stored as many short pieces is not punished.
     * It is a routing preference, not a fact about the world, so it is not in the reported time, and the shortest metric is not priced at all -- it promises the shorter line.
     */
    private static double turnCost(Arm arrived, Arm next, TravelMode mode,
                                   RoutePreferences preferences) {
        if (arrived == null || next == null
                || preferences.metric() != RoutePreference.FASTEST_TIME) {
            return 0;
        }
        double degrees = turnDegrees(arrived, next);
        if (Double.isNaN(degrees) || Math.abs(degrees) < TURN_BREAK_DEGREES) {
            return 0;
        }
        double blocks = turnBlocks(mode) * Math.abs(degrees) / 90.0;
        double speed = Math.max(0.05, effectiveSpeed(mode, next.segment().roadClass()));
        return blocks / speed;
    }

    /**
     * How sharply a highway may bend before the bend is not one a highway makes, in degrees. A highway never offers a way to turn round; the guidance re-words a highway U-turn as carrying on, but the route could still plan a hairpin.
     * 100 degrees leaves every real highway bend -- a dual carriageway's turn at a median, a ramp, a corner -- and refuses the hairpin and the reversal. The limit is asked between two highway pieces and inside a highway's own polyline, not where a highway meets another class, which is a ramp. Refused in the graph rather than priced: a trip that must double back on a highway is one this mod does not answer.
     */
    private static final double HIGHWAY_BEND_LIMIT_DEGREES = 100.0;

    /**
     * How sharply a highway may turn at a junction, in degrees. A junction is a choice and may be sharper, but a reversal is still the U-turn a highway does not have.
     */
    private static final double HIGHWAY_JUNCTION_LIMIT_DEGREES = 150.0;

    /**
     * Whether a highway may bend this sharply, which decides if the edge exists at all. Any other class may bend freely; the guidance has something to say about each such turn.
     */
    private static boolean highwayMayBend(double degrees) {
        return degrees <= HIGHWAY_BEND_LIMIT_DEGREES;
    }

    /**
     * How close two costs have to be to count as the same. Routes over the same ground can differ in the last bits once lengths are hypotenuses of different legs, so this makes "the same cost" mean the same cost.
     */
    private static final double COST_EPSILON = 1.0E-9;

    /**
     * Whether a traveller who arrived on arrived may leave current along next. The graph stores an edge per road, not per pair, so what a highway does at a node is known only when the node is reached; storing every pair would be the quadratic blow-up buildGraph avoids.
     * Only when both pieces are highways: a road joining a highway, or a highway leaving onto a road, is an on-ramp or off-ramp whose shape belongs to the ramp.
     * The angle limit is asked only where the highway carries on (exactly two usable pieces): at a fork the sharp turn is the junction's own shape, held to the wider HIGHWAY_JUNCTION_LIMIT_DEGREES. Doubling back is refused everywhere on its own terms.
     */
    private static boolean highwayBendAllowed(RoadSegment arrived, int current, RoadSegment next,
                                              Set<Integer> continuations) {
        // A bend inside the piece being taken is asked first, before the "nothing arrived" case: a highway whose own polyline doubles back is travelled as one edge, so no junction is ever reached to refuse it.
        if (bendsPastTheLimitInside(next)) {
            return false;
        }
        // The rule is for a highway junction, not a highway meeting anything else: turning on or off a highway is an ordinary turn whose shape belongs to the ramp.
        if (arrived == null
                || arrived.roadClass() != RoadClass.HIGHWAY
                || next.roadClass() != RoadClass.HIGHWAY) {
            return true;
        }
        // Doubling back is refused on its own terms: going back along the piece just travelled, or onto a piece that leaves for the node just come from, is the U-turn a highway does not have.
        if (doublesBackOnItself(arrived, current, next)) {
            return false;
        }
        // A junction is a choice and may be at any angle up to a reversal; only where the highway carries on through its own two arms is the tighter limit used.
        double degrees = turnDegrees(arrived, current, next);
        if (Double.isNaN(degrees)) {
            return true;
        }
        double limit = continuations.contains(current)
                ? HIGHWAY_BEND_LIMIT_DEGREES
                : HIGHWAY_JUNCTION_LIMIT_DEGREES;
        return Math.abs(degrees) <= limit;
    }

    /**
     * The nodes a highway passes through rather than forks at: exactly two usable pieces meet there. "Usable" means a class this mode may travel and this policy has not excluded; synthetic coincident links are not counted.
     */
    private static Set<Integer> highwayContinuations(RoadNetwork network, TravelMode mode,
                                                     RoutePreferences preferences) {
        Map<Integer, Integer> degree = new HashMap<>();
        for (RoadSegment segment : network.segmentsSnapshot()) {
            if (!usable(mode, preferences, segment.roadClass())) {
                continue;
            }
            if (segment.fromNode() == RoadSegment.NO_NODE
                    || segment.toNode() == RoadSegment.NO_NODE) {
                continue;
            }
            degree.merge(segment.fromNode(), 1, Integer::sum);
            degree.merge(segment.toNode(), 1, Integer::sum);
        }
        Set<Integer> continuations = new HashSet<>();
        for (Map.Entry<Integer, Integer> entry : degree.entrySet()) {
            if (entry.getValue() == 2) {
                continuations.add(entry.getKey());
            }
        }
        return continuations;
    }

    /**
     * Whether a highway piece bends past the limit at one of its own vertices. A polyline changes heading between its ends, and a traveller going from one end to the other makes every such bend; checked only at junctions, a highway folded in two inside one piece passed both searches.
     * Asked of the piece being taken, once per piece travelled, because the first piece has nothing arrived from.
     */
    private static boolean bendsPastTheLimitInside(RoadSegment segment) {
        if (segment.roadClass() != RoadClass.HIGHWAY) {
            return false;
        }
        for (int i = 2; i < segment.vertexCount(); i++) {
            double degrees = bendDegrees(segment, i);
            if (!Double.isNaN(degrees) && !highwayMayBend(Math.abs(degrees))) {
                return true;
            }
        }
        return false;
    }

    /**
     * The angle a polyline changes heading by at one of its own vertices, in degrees: the same reading turnDegrees takes of two pieces, with the same sign. NaN when either span has no length.
     */
    private static double bendDegrees(RoadSegment segment, int vertex) {
        double inX = segment.x(vertex - 1) - segment.x(vertex - 2);
        double inZ = segment.z(vertex - 1) - segment.z(vertex - 2);
        double outX = segment.x(vertex) - segment.x(vertex - 1);
        double outZ = segment.z(vertex) - segment.z(vertex - 1);
        if (Math.hypot(inX, inZ) < 1.0E-6 || Math.hypot(outX, outZ) < 1.0E-6) {
            return Double.NaN;
        }
        double before = Math.atan2(inZ, inX);
        double after = Math.atan2(outZ, outX);
        return Math.toDegrees(Math.atan2(Math.sin(after - before), Math.cos(after - before)));
    }

    /**
     * Whether travelling on from arrived to next at current puts the traveller back on ground just covered. True for the incoming piece itself and for two pieces running node-to-node between the same pair.
     */
    private static boolean doublesBackOnItself(RoadSegment arrived, int current, RoadSegment next) {
        if (arrived.id() == next.id()) {
            return true;
        }
        int arrivedFar = arrived.fromNode() == current ? arrived.toNode() : arrived.fromNode();
        int nextFar = next.fromNode() == current ? next.toNode() : next.fromNode();
        return arrivedFar == nextFar && arrivedFar != RoadSegment.NO_NODE
                && arrivedFar != current;
    }

    /**
     * Whether going from one arm to the next is a turn rather than a continuation, measured on the pieces' own headings where they meet.
     */
    private static boolean turnsAt(Arm arrived, Arm next) {
        double degrees = turnDegrees(arrived, next);
        return !Double.isNaN(degrees) && Math.abs(degrees) >= TURN_BREAK_DEGREES;
    }

    /**
     * The angle a traveller changes heading by going from one arm to the next, in degrees; positive is towards +Z. NaN when either arm has no heading -- a zero-length join.
     */
    private static double turnDegrees(Arm arrived, Arm next) {
        if (arrived == null || next == null) {
            return Double.NaN;
        }
        if (Double.isNaN(arrived.headingIn()) || Double.isNaN(next.headingOut())) {
            return Double.NaN;
        }
        return Math.toDegrees(Math.atan2(Math.sin(next.headingOut() - arrived.headingIn()),
                Math.cos(next.headingOut() - arrived.headingIn())));
    }

    /**
     * The angle a traveller changes heading by going from incoming to outgoing at a node, in degrees; positive is towards +Z. Read on the pieces because the highway rule is asked before any arm exists.
     */
    private static double turnDegrees(RoadSegment incoming, int atNode, RoadSegment outgoing) {
        if (incoming == null || outgoing == null) {
            // No way in means this is the first piece of the trip, and leaving a node is not a turn.
            return Double.NaN;
        }
        double before = travelBearing(incoming, atNode);
        double after = leaveBearing(outgoing, atNode);
        if (Double.isNaN(before) || Double.isNaN(after)) {
            return Double.NaN;
        }
        return Math.toDegrees(Math.atan2(Math.sin(after - before), Math.cos(after - before)));
    }

    /**
     * Heading the traveller is moving in as they arrive at one of a segment's two nodes. Mirrored with leaveBearing so a straight continuation comes out as no bend.
     */
    private static double travelBearing(RoadSegment segment, int arrivedAt) {
        int last = segment.vertexCount() - 1;
        if (last < 1) {
            return Double.NaN;
        }
        if (segment.toNode() == arrivedAt) {
            return bearing(segment.x(last - 1), segment.z(last - 1), segment.x(last), segment.z(last));
        }
        return bearing(segment.x(1), segment.z(1), segment.x(0), segment.z(0));
    }

    /** Heading the traveller sets off in when leaving one of a segment's two nodes. */
    private static double leaveBearing(RoadSegment segment, int leftFrom) {
        int last = segment.vertexCount() - 1;
        if (last < 1) {
            return Double.NaN;
        }
        if (segment.fromNode() == leftFrom) {
            return bearing(segment.x(0), segment.z(0), segment.x(1), segment.z(1));
        }
        return bearing(segment.x(last), segment.z(last), segment.x(last - 1), segment.z(last - 1));
    }

    /**
     * The heading of one span of a piece, in radians, or NaN when the span has no length. atan2(0, 0) is zero, so a zero-length piece used to be given a heading of due east -- a direction invented from nothing, read as a 180-degree bend. Two nodes within COINCIDENT_DISTANCE are joined by a synthetic segment that is often exactly zero long, and one sits between a highway's westbound carriageway and the road carrying on west.
     * NaN is the honest answer, and both callers already treat it as "not a turn".
     */
    private static double bearing(int fromX, int fromZ, int toX, int toZ) {
        double dx = toX - fromX;
        double dz = toZ - fromZ;
        if (Math.hypot(dx, dz) < 1.0E-6) {
            return Double.NaN;
        }
        return Math.atan2(dz, dx);
    }

    /**
     * Remaining cost, optimistic: the bound must sit under what the search will really pay, or A* returns a route that is not the best one. For time that means the fastest pace in play; for distance the straight line, which no line of roads can be shorter than.
     */
    private static double heuristic(RoadNetwork network, int nodeId, RoadNode goal,
                                    TravelMode mode, RoutePreferences preferences) {
        RoadNode node = network.node(nodeId);
        if (node == null) {
            return 0;
        }
        double distance = Math.hypot(goal.x() - node.x(), goal.z() - node.z());
        if (preferences.metric() == RoutePreference.SHORTEST_DISTANCE) {
            return distance;
        }
        double fastest = fastestSpeed(mode, preferences);
        // Nothing in play means the graph is empty; the bound only has to stay finite so the frontier can drain.
        return fastest <= 0 ? 0 : distance / fastest;
    }

    /** Fastest pace available on any class this trip may use, in blocks per second. */
    private static double fastestSpeed(TravelMode mode, RoutePreferences preferences) {
        double fastest = 0;
        for (RoadClass roadClass : RoadClass.values()) {
            if (usable(mode, preferences, roadClass)) {
                // The roomiest pace in play: a weight only multiplies costs up, so an unweighted bound is admissible.
                fastest = Math.max(fastest, effectiveSpeed(mode, roadClass));
            }
        }
        return fastest;
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
        // Junctions come from the same grouping as the keys, so marking them costs nothing per segment.
        for (RoadSegment segment : path) {
            appendSegment(builder, grouping, segment, previousNode);
            // The mode's real pace on this class, so the route's legs time what the player actually travels.
            builder.addRoadLeg(segment.length(), effectiveSpeed(mode, segment.roadClass()),
                    segment.roadClass());
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

        // No trimming: the endpoints are the perpendicular feet onto the road, already the short straight hops they should be.
        return builder.build();
    }

    /** Appends a segment's polyline oriented away from fromNode. */
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
