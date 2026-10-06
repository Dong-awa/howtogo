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
     * A network the router may split, shared by a batch of queries.
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
     * <p>What the copy is not is repaired. There used to be a pass here that cut junctions where two
     * roads crossed, or where one road's node landed on another, on the theory that a player who drew
     * two roads through each other meant a crossroads. It was wrong about the drawing: a crossing and
     * a bridge look the same on a map that cannot show height, a landing and a near-miss look the same
     * too, and the pass answered both by inventing a junction the player had not drawn. A road connects
     * where a node says it connects, and nowhere else; the player who wants a crossing to be a
     * crossroads puts a node on it, which the editor does when a point is placed on a road.
     *
     * <p>One workspace belongs to one batch, and the network inside it must not be edited between the
     * queries made through it.
     */
    public static final class Workspace {

        private final RoadNetwork source;
        /** The revision of {@link #source} this workspace was made from; see {@link WorkspaceCache}. */
        private int sourceRevision;
        /**
         * The one-way state of the world when this workspace was made; see {@link WorkspaceCache}.
         *
         * <p>Separate from the revision because a direction is not geometry and the network does not
         * count it: without this, marking a street one-way left the copy inside this workspace reading
         * two-way, and every route after it was planned the wrong way down the street.
         */
        private int sourceDirections;
        /**
         * The storeys of the world when this workspace was made, for the same reason.
         *
         * <p>A storey is not geometry either, and it decides which near-coincident nodes the graph
         * connects as one place, so a copy made before a road was moved to another storey would serve
         * the joins of the world as it used to be.
         */
        private int sourceLayers;
        private RoadNetwork work;
        private RoadEditor editor;
        private RoadChains.Grouping grouping;
        private int groupingSegments = -1;
        /** The graph as of {@link #graphRevision} and {@link #graphDirections}. */
        private Map<Integer, List<Edge>> graph;
        private RoutePreferences graphPreferences;
        private TravelMode graphMode;
        private int graphRevision = Integer.MIN_VALUE;
        private int graphDirections = Integer.MIN_VALUE;
        /** @see #sourceLayers -- the graph's version of the storeys, for the same reason. */
        private int graphLayers = Integer.MIN_VALUE;

        public Workspace(RoadNetwork source) {
            this.source = source;
        }

        /** The network to read: a copy of the caller's, made on first use. */
        RoadNetwork routingNetwork() {
            if (work == null) {
                work = source.deepCopy();
                // Without undo: every split would otherwise copy the whole network again, which is the
                // cost this class was rewritten to stop paying.
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

        /**
         * The graph for one mode and policy, built once per version of the network.
         *
         * <p>A public transport plan asks the same network for the same graph dozens of times: every
         * ride of a line is a query, and every query that anchors an endpoint asks for the graph again.
         * Building it is a walk of every segment plus a coincident-node pass over every node, so on a
         * whole railway it was paid dozens of times a plan for one unchanging answer.
         *
         * <h2>What the version is made of</h2>
         * A graph is a reading of two things, and it is only reusable while both are unchanged. The
         * first is the geometry, and the segment count is what stands for it here: it is exactly what a
         * split changes, and a split is the one way this class itself moves the network -- subdividing
         * one segment into two and adding a node, so a graph built before it would be missing the half
         * the anchor was made on.
         *
         * <p>The second is which way each segment may be travelled, and {@link RoadNetwork#revision}
         * does <em>not</em> move for that: a one-way flag is not geometry, and the network says so
         * outright. Keying on the segment count alone therefore served a two-way graph after a street
         * was marked one-way, and the player was routed the wrong way down it -- with the map drawing an
         * arrow against them. {@link RoadSegment#directionChanges()} is the count that does move, and
         * both are part of the version because both change what the graph contains: one changes which
         * edges exist, the other which of the two directions each edge has.
         */
        Map<Integer, List<Edge>> graphFor(TravelMode mode, RoutePreferences preferences) {
            RoadNetwork network = routingNetwork();
            int version = network.segmentCount();
            int directions = RoadSegment.directionChanges();
            int layers = RoadSegment.layerChanges();
            if (graph == null || graphRevision != version || graphDirections != directions
                    || graphLayers != layers
                    || graphMode != mode || !graphPreferences.equals(preferences)) {
                graph = buildGraph(network, mode, preferences);
                graphRevision = version;
                graphDirections = directions;
                graphLayers = layers;
                graphMode = mode;
                graphPreferences = preferences;
            }
            return graph;
        }
    }

    /**
     * Workspaces kept for the networks a plan is being made over, so a batch of queries shares each.
     *
     * <h2>Why a workspace has to outlive one query</h2>
     * A {@link Workspace} exists to make one network's copy be paid once for a batch of queries. Only a
     * caller that holds one gets that: the convenience entry point
     * {@link #findRoute(RoadNetwork, double, double, double, double, String, TravelMode,
     * RoutePreferences)} makes a workspace per call, and so did the public transport planner -- one per
     * ride, and a ride is one pair of neighbouring stops, so a journey over a whole railway asked for
     * the same few hundred line tracks thousands of times. Measured on a network of four hundred lines,
     * one plan copied four hundred line tracks in full, and then did it all again on the next plan,
     * because the map that held them belonged to the plan and was thrown away with it.
     *
     * <h2>Why the key is weak, and the revision is checked</h2>
     * The workspaces are held against the source networks rather than against the calls, so the same
     * track is copied once for the session. A plain map of those would be a leak the size
     * of every network ever planned over, so the keys are weak: the workspace lives exactly as long as
     * the network it was made from, and a merged network rebuilt for one plan takes its copy with it
     * when the plan lets go.
     *
     * <p>The revision is stored with the workspace and checked on the way out, for the reason
     * {@code RoadSnapper} gives for its own cache: the editor mutates one network in place for a whole
     * session, so a workspace keyed on the reference alone would keep serving a copy of roads that have
     * since moved. Here it is a field of the workspace rather than part of a map key, because the key
     * has to be the network itself for the weakness to mean anything -- and an edited network replaces
     * its own entry, which is right: the old copy is stale and has nothing left to answer.
     *
     * <p>The one-way state is checked beside it, because the network's revision deliberately does not
     * count it -- a direction is not geometry -- and the copy inside a workspace is exactly what a
     * direction change invalidates. Marking a street one-way used to leave the copy reading two-way, so
     * every route planned after it went the wrong way down the street while the map drew the arrow
     * against the traveller. See {@link RoadSegment#directionChanges()}.
     */
    private static final class WorkspaceCache {

        private final Map<RoadNetwork, Workspace> entries =
                java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

        Workspace get(RoadNetwork network) {
            int directions = RoadSegment.directionChanges();
            int layers = RoadSegment.layerChanges();
            Workspace found = entries.get(network);
            if (found == null || found.sourceRevision != network.revision()
                    || found.sourceDirections != directions
                    || found.sourceLayers != layers) {
                found = new Workspace(network);
                found.sourceRevision = network.revision();
                found.sourceDirections = directions;
                found.sourceLayers = layers;
                entries.put(network, found);
            }
            return found;
        }
    }

    private static final WorkspaceCache WORKSPACES = new WorkspaceCache();

    /**
     * The workspace a batch of queries should share, kept across batches.
     *
     * <p>For a caller that plans many journeys over the same networks -- a public transport planner
     * asks for one workspace per ride, over a line's own track, and the same tracks are asked about
     * again on the next plan. Handing the class's own kept workspace over is what makes a line's copy
     * be paid once for the session rather than once per journey; see
     * {@link WorkspaceCache}.
     */
    public static Workspace workspaceFor(RoadNetwork network) {
        return WORKSPACES.get(network);
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
        return findRoute(WORKSPACES.get(network), startX, startZ, goalX, goalZ, destinationName,
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
        return plan(workspace, startX, startZ, goalX, goalZ, destinationName, mode, preferences);
    }

    /**
     * How two places stand to each other on a network, for a caller that has to say whether they are
     * connected rather than whether a trip between them could be planned.
     *
     * <p>The two are not the same question, and the difference is the whole reason this exists. A plan
     * is refused for reasons that are not about connection at all: a connector longer than the mode
     * allows, a one-way street facing the wrong way, a destination beside the road whose last hop the
     * fallback's own arithmetic declines. A line editor that reads "no plan" as "not connected" then
     * marks a stop red on a line that runs perfectly well, which is the one readout on that screen that
     * must never accuse a healthy line.
     */
    public enum Connection {

        /** A path from the first place to the second exists on roads this mode and policy allow. */
        CONNECTED,

        /**
         * Both places are tied to the network and no path runs from the first to the second.
         *
         * <p>This is the only answer that means "not connected": the two ends are on separate pieces of
         * the roads being asked about, or a one-way restriction between them faces the way the journey
         * has to go.
         */
        SEPARATE,

        /**
         * The question has no answer from this network: no road the mode may use and the policy allows
         * exists at all, or none of them comes within {@link #JUDGEMENT_REACH} of one of the two places.
         *
         * <p>A caller must treat this as "cannot tell" rather than "broken". A station whose
         * representative point stands well off its own track -- a large station, several platforms
         * merged into one place -- is exactly this, and reporting it as a disconnection would be an
         * accusation the network does not support.
         */
        UNJUDGED
    }

    /**
     * How far from a place a road of the right kind may be and still be the road it is judged against,
     * in blocks.
     *
     * <h2>Why this is not the connector cap</h2>
     * {@link TravelMode#maxConnectorDistance()} says how far a traveller will walk to reach the
     * network, and a trip whose connector is longer than that is refused. That is the right number for
     * planning a journey and the wrong one for asking whether two places are connected: it made a
     * station a hundred blocks off its own railway read as "not connected to the previous stop" when
     * the railway between the two is one unbroken stretch. The distance of a station from the track is
     * a fact about the station, not about the line.
     *
     * <p>What this number has to cover is how far a stop's own position can stand from the track it
     * serves. A station is entered in the world as the middle of its platforms, and a station whose
     * platforms have been drawn as several areas is kept as one place at the middle of all of them --
     * so the point the line calls at can be well over a hundred blocks from any single piece of rail
     * and still name a station on that rail. Two hundred and fifty-six blocks is the merge distance
     * such a station is assembled under, and past it this class stops claiming to know: the answer is
     * {@link Connection#UNJUDGED}, not a red mark.
     */
    private static final double JUDGEMENT_REACH = 256.0;

    /**
     * Whether the two places are connected on this network, which is not the same question as whether a
     * trip between them can be planned -- see {@link Connection}.
     *
     * <h2>How the answer is reached</h2>
     * Each place is joined to the network the way the planner joins it, by splitting the road it stands
     * beside at the perpendicular foot, so a stop in the middle of a long straight rail is a node on
     * that rail rather than a point two hundred blocks from the nearest end of it. Both joinings are
     * made before the graph is read, because a split changes it.
     *
     * <p>Then a search -- not a plan: nothing is costed, no connector is timed and no destination is
     * built -- asks whether a path runs from the first node to the second, in that direction, so a
     * one-way facing the way the journey has to go is {@link Connection#SEPARATE}. The search starts
     * from the whole neighbourhood of the first place as well as from its own node, because a place can
     * stand beside more than one piece of road -- a stub end five blocks away and the through line
     * sixty -- and only the further one need be part of the journey; the same reason
     * {@link #findNodeRoute} tries more than one endpoint.
     *
     * <p>A place with no road of the wanted kind within {@link #JUDGEMENT_REACH} is not judged at all,
     * which is the one answer that leaves a caller free to say nothing rather than accuse a line.
     *
     * @return {@link Connection#CONNECTED}, {@link Connection#SEPARATE} or {@link Connection#UNJUDGED}
     */
    public static Connection connection(Workspace workspace, double startX, double startZ,
                                        double goalX, double goalZ, TravelMode mode,
                                        RoutePreferences preferences) {
        RoadNetwork network = workspace.routingNetwork();
        RoadEditor editor = workspace.editor();
        RoadPoint startRoad = nearestRoadPointWithin(network, startX, startZ, mode, preferences,
                JUDGEMENT_REACH);
        if (startRoad == null) {
            return Connection.UNJUDGED;
        }
        int startNode = anchorNode(network, editor, startRoad);
        if (startNode < 0) {
            return Connection.UNJUDGED;
        }
        // Read after the first split rather than before it: splitting the segment one end stands on
        // removes that segment, and the other end is very often on it. This is the same care
        // findAnchoredRoute takes for the same reason.
        RoadPoint goalRoad = nearestRoadPointWithin(network, goalX, goalZ, mode, preferences,
                JUDGEMENT_REACH);
        if (goalRoad == null) {
            return Connection.UNJUDGED;
        }
        int goalNode = anchorNode(network, editor, goalRoad);
        if (goalNode < 0) {
            return Connection.UNJUDGED;
        }
        if (startNode == goalNode) {
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
     * The nodes one place could be travelling from or to: the node it was just anchored as, and every
     * routable node within the mode's own connector reach of it.
     *
     * <p>The reach is the connector cap rather than {@link #JUDGEMENT_REACH}, because these are the
     * endpoints a ride would really use: a second piece of road further away than a traveller will walk
     * to it is not an endpoint of this ride, and offering every node within the much longer judging
     * reach would let two stops on either side of a genuine gap answer connected through a node both
     * of them merely stand near.
     */
    private static List<Integer> neighbourhood(RoadNetwork network, Map<Integer, List<Edge>> graph,
                                               int anchor, double x, double z, TravelMode mode) {
        List<Integer> nodes = new ArrayList<>();
        if (graph.containsKey(anchor)) {
            nodes.add(anchor);
        }
        nodes.addAll(nearestNodesWithin(network, graph, x, z, START_CANDIDATES,
                mode.maxConnectorDistance()));
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
                              RoutePreferences preferences) {
        Route anchored = findAnchoredRoute(workspace, startX, startZ, goalX, goalZ, destinationName,
                mode, preferences);
        if (anchored != null) {
            return anchored;
        }
        return findNodeRoute(workspace, startX, startZ, goalX, goalZ,
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
     * see {@link Workspace}, which makes that copy once for a whole batch of queries.
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

        Map<Integer, List<Edge>> graph = workspace.graphFor(mode, preferences);
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
        return nearestRoadPointWithin(network, x, z, mode, preferences, Double.MAX_VALUE);
    }

    /**
     * The same, considering only roads within {@code radius} blocks.
     *
     * <p>The bound is not an optimisation: a caller that is asking "is there a road nearer than this
     * one" must not be answered by a road so far away that the answer cannot matter, and reading the
     * whole network to find it would make that question cost as much as the routing it is checking.
     */
    private static RoadPoint nearestRoadPointWithin(RoadNetwork network, double x, double z,
                                                    TravelMode mode, RoutePreferences preferences,
                                                    double radius) {
        RoadPoint best = null;
        double bestDistanceSq = radius * radius;
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
     *
     * <h2>The endpoints still have to be reachable</h2>
     * The nodes here are the nearest ones that the graph contains, not the ones the trip would use, and
     * the first and last hop are drawn as straight lines whatever their length. A node further from the
     * end than the mode's connector allows therefore produces a route with a beeline at one end of it --
     * which is exactly the thing {@link TravelMode#maxConnectorDistance()} exists to forbid, and which
     * the anchored attempt refuses.
     *
     * <p>That was not only a long walk. A one-way street is invisible to this search in the sense that
     * matters: nothing can be driven the wrong way down it, so the anchored attempt returns nothing,
     * the fallback is asked instead, and it answers with the nearest node on the far side of the street
     * joined by a straight line. The player asked for a route and got one that goes the wrong way up a
     * one-way street without ever claiming to -- so the street is not one-way at all, it is merely
     * inconvenient. Refusing the fallback when its own endpoints are past the cap is what makes the
     * one-way street mean what it says, and it is the same rule the anchored path already follows.
     */
    private static Route findNodeRoute(Workspace workspace, double startX, double startZ,
                                       double goalX, double goalZ, String destinationName,
                                       TravelMode mode, RoutePreferences preferences) {
        RoadNetwork network = workspace.routingNetwork();
        Map<Integer, List<Edge>> graph = workspace.graphFor(mode, preferences);
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
        if (best == null || beyondConnector(network, best.source(), startX, startZ, mode)
                || beyondConnector(network, best.goal(), goalX, goalZ, mode)) {
            return Route.empty();
        }
        // The workspace's own grouping, not a fresh one: this is the fallback path, which a public
        // transport plan walks once per ride, and building the grouping is a walk of every segment.
        // The anchored path beside this one has always used the cached one.
        Route route = buildRoute(network, workspace.grouping(), best.source(), best.goal(),
                best.path(), startX, startZ, goalX, goalZ, destinationName, mode, preferences);
        return overshotTheRoad(network, best, route) ? Route.empty() : route;
    }

    /**
     * Whether the node is further from the point than this trip may reach off the road.
     *
     * <p>Measured on the node rather than on the road point, because the node is what the connector is
     * actually drawn to -- the same care the anchored attempt takes, and for the same reason: a cap that
     * a rounding of the anchor could exceed is not a cap.
     */
    private static boolean beyondConnector(RoadNetwork network, int nodeId, double x, double z,
                                           TravelMode mode) {
        RoadNode node = network.node(nodeId);
        return node != null
                && Math.hypot(node.x() - x, node.z() - z) > mode.maxConnectorDistance();
    }

    /**
     * Whether the last hop is standing in for a road the trip declined to use.
     *
     * <h2>The route this refuses</h2>
     * Two roads drawn sixty blocks short of each other are two fragments, and nothing can travel from
     * one to the other -- that is what "not connected" means, and it is what the player has to be told
     * rather than have papered over. The node fallback is free to choose <em>which</em> node the trip
     * ends at, though, and that freedom is what closes the gap: it ends the road at a node on the far
     * side, and the last hop -- drawn as a straight line whatever its length -- is spent coming back
     * across the gap. Measured on two collinear roads with a sixty block gap, the walk came out as one
     * straight line from the end of the first road to a point past the start of the second, 250 blocks
     * of connector at the end of it. On the map that is indistinguishable from a junction, so
     * the player is told the roads meet when they do not.
     *
     * <h2>The two numbers</h2>
     * The trip arrived on a road at some point along it, and the connector leaves from there to the
     * destination. Two things about that road can be compared:
     *
     * <ul>
     *   <li>how far <em>past</em> the destination's own nearest point on that road the trip stopped --
     *       the distance it would have to come back, which is what makes the hop a doubling back at
     *       all; and</li>
     *   <li>how long the hop actually is.</li>
     * </ul>
     *
     * <p>The hop replaces road travel only when it is the shorter of the two: coming back further than
     * the hop is long means the road was left for the hop rather than walked, and that is the shortcut.
     * When the hop is the longer of the two it is simply the trip leaving the road to reach a
     * destination beside it, however far past the nearest point it stopped -- a destination with
     * nothing drawn up to it is reached from whichever node the road happens to end at, and that hop is
     * the one every route has and the mode's cap exists to allow.
     *
     * <p>That is what tells the gap apart from a stub beside the destination that nothing routes to, and
     * from a destination on a bridge whose foot is below it: in both of those the hop is longer than the
     * road it would have saved, so both keep the hop they always had. Both are harness cases, and both
     * failed against the first two rules tried here -- one that refused any hop past a nearer road, and
     * one that refused any doubling back at all.
     */
    private static boolean overshotTheRoad(RoadNetwork network, Best best, Route route) {
        if (best.path().isEmpty() || !route.isPresent()) {
            // The whole trip is a hop between two nodes of one road, so there is no road ahead of the
            // arrival to have been overshot.
            return false;
        }
        // The road the trip arrived on, and the node it arrived at.
        RoadSegment arrival = best.path().get(best.path().size() - 1);
        RoadNode at = network.node(best.goal());
        if (at == null) {
            return false;
        }
        // The route's last point is the destination itself -- buildRoute ends there whatever the hop --
        // so this is a comparison of two positions on the arrival road, in the same units.
        double[] destination = route.points().get(route.points().size() - 1);
        double alongNode = along(arrival, at.x(), at.z());
        double alongGoal = along(arrival, destination[0], destination[1]);
        if (Double.isNaN(alongNode) || Double.isNaN(alongGoal)) {
            return false;
        }
        return alongNode - alongGoal > route.goalConnector();
    }

    /**
     * How far a point lies along a segment's polyline, in blocks from its first vertex.
     *
     * <p>The point is projected onto each vertex span and the closest projection wins, which is the
     * distance to the polyline itself rather than to its vertices. Zero is the segment's {@code from}
     * end and the total length is its {@code to} end, so two points on one road are comparable.
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
        Map<Integer, Integer> turns = new HashMap<>();
        Map<Integer, RoadSegment> arrivedBy = new HashMap<>();
        Map<Integer, Integer> cameFromNode = new HashMap<>();
        Map<Integer, RoadSegment> cameFromSegment = new HashMap<>();
        Set<Integer> closed = new HashSet<>();
        Set<Integer> settledGoals = new HashSet<>();

        // Ordered by cost and then by turns, for the reason given on the single-source search: on a
        // rectilinear network the cost cannot tell a staircase from a route with one turn in it.
        PriorityQueue<double[]> frontier = new PriorityQueue<>(
                Comparator.<double[]>comparingDouble(a -> a[0]).thenComparingDouble(a -> a[1]));
        for (int start : starts) {
            double connector = connectorCost(network, start, startX, startZ, mode, preferences);
            if (connector < gScore.getOrDefault(start, Double.MAX_VALUE)) {
                gScore.put(start, connector);
                turns.put(start, 0);
                frontier.add(new double[]{
                        connector + nearestGoalEstimate(network, start, goals, mode, preferences),
                        0, start});
            }
        }

        double bestTotal = Double.MAX_VALUE;
        while (!frontier.isEmpty() && frontier.peek()[0] < bestTotal) {
            int current = (int) frontier.poll()[2];
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

            int currentTurns = turns.getOrDefault(current, 0);
            RoadSegment arrived = arrivedBy.get(current);
            for (Edge edge : graph.getOrDefault(current, List.of())) {
                if (closed.contains(edge.toNode())) {
                    continue;
                }
                double tentative = currentG + edgeCost(edge.segment(), mode, preferences);
                int viaTurns = currentTurns
                        + (turnsAt(arrived, current, edge.segment()) ? 1 : 0);
                double known = gScore.getOrDefault(edge.toNode(), Double.MAX_VALUE);
                boolean better = tentative < known - COST_EPSILON;
                boolean tidier = !better && tentative <= known + COST_EPSILON
                        && viaTurns < turns.getOrDefault(edge.toNode(), Integer.MAX_VALUE);
                if (!better && !tidier) {
                    continue;
                }
                // The same rule the anchored search asks, and the whole reason it is asked in the
                // search rather than priced into the cost: this search builds a route too. It was the
                // one place that never asked, so a trip whose only road path doubles back on a
                // highway was refused by the anchored search and then planned by this fallback --
                // the restriction held for the route that could go round and not for the one that
                // had to go back. See highwayBendAllowed.
                if (!highwayBendAllowed(arrived, current, edge.segment())) {
                    continue;
                }
                double settled = Math.min(tentative, known);
                gScore.put(edge.toNode(), settled);
                turns.put(edge.toNode(), viaTurns);
                arrivedBy.put(edge.toNode(), edge.segment());
                cameFromNode.put(edge.toNode(), current);
                cameFromSegment.put(edge.toNode(), edge.segment());
                frontier.add(new double[]{
                        settled + nearestGoalEstimate(network, edge.toNode(), goals, mode,
                                preferences),
                        viaTurns, edge.toNode()});
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
            // One edge per direction the road actually allows: a one-way street contributes exactly one,
            // and asking the segment rather than testing the flag here keeps the graph and the arrows the
            // map draws reading the same rule -- see RoadSegment.allowsTravelFrom.
            if (segment.allowsTravelFrom(from)) {
                graph.computeIfAbsent(from, k -> new ArrayList<>()).add(new Edge(to, segment));
            }
            if (segment.allowsTravelFrom(to)) {
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
     * those joins disconnected networks that had been routing for as long as they existed. The allowance
     * that admits the slope and refuses the bridge is the one {@link #samePlaceVertically} makes, and
     * this pass uses it with the storeys beside it.
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

        // What each node has drawn at it, worked out once: a join is between two pieces of road, so the
        // question "could this trip travel from here" is about the segments meeting at the node rather
        // than about the node's position. Built here because the pairs below are looked at in buckets and
        // asking the network per pair would walk every segment per pair.
        Map<Integer, Set<RoadClass>> atNode = new HashMap<>();
        // What storeys are drawn at each node, worked out in the same pass and for the same reason: a
        // join is only a join between roads on one storey. Roads that share a *node* are connected
        // whatever storey either is on -- that is what the node is -- but two nodes merely passing
        // within three blocks are one place only when something at each of them is on the same storey,
        // which is what stops a bridge being read as a crossroads with the road under it.
        Map<Integer, Set<Integer>> layersAtNode = new HashMap<>();
        for (RoadSegment segment : network.segmentsSnapshot()) {
            if (!mode.allows(segment.roadClass()) || preferences.avoids(segment.roadClass())) {
                continue;
            }
            for (int nodeId : new int[]{segment.fromNode(), segment.toNode()}) {
                if (nodeId != RoadSegment.NO_NODE) {
                    atNode.computeIfAbsent(nodeId, k -> java.util.EnumSet.noneOf(RoadClass.class))
                            .add(segment.roadClass());
                    layersAtNode.computeIfAbsent(nodeId, k -> new java.util.HashSet<>())
                            .add(segment.layer());
                }
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
                        if (!shareARoad(atNode.get(a.id()), atNode.get(b.id()))
                                || !shareALayer(layersAtNode.get(a.id()), layersAtNode.get(b.id()))) {
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

    /**
     * Whether two nodes this close together are also at the same level, which is what makes them one
     * place rather than one over the other.
     *
     * <h2>Why a height check belongs here after all</h2>
     * This pass joins two nodes within {@link #COINCIDENT_DISTANCE} of each other whatever their
     * heights, and on a road network that is measured in horizontal blocks that is the same as saying a
     * bridge is a junction: a road ramping up to a bridge has a node at the foot of the ramp and the
     * bridge has a node above it, two blocks apart and thirty apart vertically, and the pass made them
     * one place. Measured on exactly that shape, a drive was sent up the ramp and onto the bridge and
     * over it -- a turn from one road onto another that share no node, which is the one thing this pass
     * is not allowed to invent.
     *
     * <p>An earlier version of this pass had a height check and it was removed, correctly: a fixed
     * tolerance refused two ends across a slope, and a hand-drawn network's heights are whatever the
     * ground was under each click, so roads that had routed for as long as they existed stopped routing.
     * The harness still holds that case -- two ends three blocks apart and eight apart vertically, which
     * must join.
     *
     * <h2>What the tolerance is, and why it is not a constant</h2>
     * A place on the ground is not flat, so how much a road may rise between two points two blocks apart
     * is a question about the ground and not a number to pick. What separates the two cases is the
     * <em>slope</em>: eight blocks of rise over three blocks of ground is a road going up a hill, and
     * thirty-six over two is not a road at all. So the allowance grows with the horizontal gap --
     * {@code SLOPE_RISE_PER_BLOCK * gap + JOIN_DISTANCE} -- which admits the slope (nine allowed against
     * eight), refuses the bridge (seven against thirty-six), and at a gap of zero admits only the join
     * distance itself, which is two nodes the player put in the same spot.
     */
    private static boolean samePlaceVertically(RoadNode a, RoadNode b) {
        double gap = Math.hypot(a.x() - b.x(), a.z() - b.z());
        double allowed = SLOPE_RISE_PER_BLOCK * gap + JOIN_DISTANCE;
        return Math.abs(a.y() - b.y()) <= allowed;
    }

    /**
     * How far a node may stand from where another one is and still be the same place, in blocks.
     *
     * <p>The distance this pass calls coincident, and the floor of the height allowance below: two
     * nodes at the same spot may be that far apart vertically and still be one place, because a spot is
     * not flat.
     */
    private static final double JOIN_DISTANCE = 3.0;

    /**
     * How much a road may rise per block of ground and still be the same road, in blocks per block.
     *
     * <p>Grows the height allowance with the horizontal gap, which is what tells a road going up a hill
     * from a bridge over one -- see {@link #samePlaceVertically}. A flat tolerance is the wrong answer
     * in both directions: it refuses the hill and accepts the bridge.
     */
    private static final double SLOPE_RISE_PER_BLOCK = 2.0;

    /**
     * Whether two nodes stand on roads this trip could travel on from both of them, which is what makes
     * the gap between them a junction rather than two things that happen to be near each other.
     *
     * <h2>Why being near is not enough</h2>
     * Two roads drawn a block apart with no shared node are one road as far as the player is concerned,
     * and joining them is the whole purpose of the pass. But a node is a point on the map rather than a
     * statement about what is there, and two of them within three blocks is not evidence that anything
     * can pass between them:
     *
     * <ul>
     *   <li>a railway crossing a road has a vertex of each inside the join distance at the level
     *       crossing, and joining them lets a ride leave the rails and continue down the street;</li>
     *   <li>a road and a footpath beside it are two things a different vehicle travels on, and joining
     *       them lets a drive appear to use a surface it never touched.</li>
     * </ul>
     *
     * <p>The class the link borrows cannot answer this on its own: it is one class for the whole pass and
     * says only what the mode may travel on. What matters is what is actually drawn at each end, so the
     * two nodes are joined only when some class the trip can use is present at both of them -- which is
     * exactly the question the graph would ask of a real segment between them.
     *
     * <p>What this deliberately does not add is a height check. An earlier version of this pass had one,
     * and it was the wrong trade: the heights in a hand-drawn network are whatever the ground was under
     * each click, so two ends across a slope are routinely several blocks apart vertically, and refusing
     * those joins disconnected networks that had been routing for as long as they existed. The height
     * check belongs in {@link #samePlaceVertically}, which is where the question of whether two nodes are
     * one place is actually asked.
     */
    private static boolean shareARoad(Set<RoadClass> fromA, Set<RoadClass> fromB) {
        if (fromA == null || fromB == null) {
            return false;
        }
        // "Some one mode can travel on both": a highway ending beside a road is one place to a driver,
        // and this used to demand an identical class, so a driver's road and the highway it was drawn
        // up against were two fragments however plainly they met. See TravelMode.shareAMode.
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
     * Whether the two nodes have a storey in common, which is what makes the gap between them
     * something that can be crossed on the ground.
     *
     * <p>The whole of the storey rule where a join is invented: this pass exists to join roads that
     * were drawn a block apart, and a road on a bridge and the road under it are not that however
     * close together they are on a map that cannot show height. Roads that share a <em>node</em> are
     * untouched by this: they are already joined, and a storey cannot take a junction away.
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
        return nearestNodesWithin(network, graph, x, z, limit, mode.maxConnectorDistance());
    }

    /**
     * The nearest nodes the graph contains, within {@code radius} blocks.
     *
     * <p>The radius is the caller's, because the two questions this answers bound it differently: a
     * plan may only reach as far as the mode's connector cap, while {@link #connection} asks whether
     * the place is tied to this network at all and takes {@link #JUDGEMENT_REACH} for it.
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
     * Why a route could not be found, as a translation key and its arguments.
     *
     * <p>The overwhelmingly common cause is a road network that is really several disconnected
     * fragments, which is invisible on the map -- the roads look joined when they merely overlap.
     * Reporting the component sizes turns "it just does not work" into something actionable.
     *
     * <p>The other causes, which only exist once modes and preferences do, are a network with no
     * road of the kind being asked for and a policy that has excluded the roads that would have
     * joined the two ends. The second is named explicitly: the player could see the water line on
     * the map and has no way of guessing that their own avoid list is what broke the journey.
     *
     * <p>Answered as {@link RouteFailure} rather than as a sentence because the picker shows this line
     * to the player and everything else it shows is translated; see that record for why the router
     * cannot translate it itself.
     */
    public static RouteFailure explainFailure(RoadNetwork network, double startX, double startZ,
                                              double goalX, double goalZ) {
        return explainFailure(network, startX, startZ, goalX, goalZ, TravelMode.WALK,
                RoutePreferences.DEFAULTS);
    }

    /** Why no route for this mode could be found. */
    public static RouteFailure explainFailure(RoadNetwork network, double startX, double startZ,
                                              double goalX, double goalZ, TravelMode mode) {
        return explainFailure(network, startX, startZ, goalX, goalZ, mode, RoutePreferences.DEFAULTS);
    }

    /** Why no route for this mode and policy could be found. */
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
        if (nearestRoadPoint(network, startX, startZ, mode, preferences) == null) {
            return RouteFailure.of("screen.howtogo.failure.no_road_at_start", modeName(mode),
                    avoided);
        }
        if (nearestRoadPoint(network, goalX, goalZ, mode, preferences) == null) {
            return RouteFailure.of("screen.howtogo.failure.no_road_at_destination", modeName(mode),
                    avoided);
        }
        List<Integer> starts = nearestRoutableNodes(network, graph, startX, startZ, 1, mode);
        List<Integer> goals = nearestRoutableNodes(network, graph, goalX, goalZ, 1, mode);
        if (starts.isEmpty() || goals.isEmpty()) {
            return RouteFailure.of("screen.howtogo.failure.too_far_from_road", modeName(mode),
                    Math.round(mode.maxConnectorDistance()), avoided);
        }
        Set<Integer> fromStart = component(graph, starts.get(0));
        Set<Integer> fromGoal = component(graph, goals.get(0));
        if (fromStart.contains(goals.get(0))) {
            return RouteFailure.of("screen.howtogo.failure.same_component", fromStart.size());
        }
        // No fragment sizes in the reason. They were there, and no player could act on them: what the
        // line is for is saying that the two ends are on roads that do not meet, and a pair of node
        // counts beside it only pushed the part that matters off the end of a picker row.
        return RouteFailure.of("screen.howtogo.failure.split_fragments", modeName(mode), avoided);
    }

    /**
     * A clause naming the avoided classes, or nothing when the policy avoids none.
     *
     * <p>Passed to the message as its own argument rather than built into it, so a translation decides
     * where the clause goes and may leave it out of a sentence that reads better without it.
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

    private static List<RoadSegment> search(Map<Integer, List<Edge>> graph, RoadNetwork network,
                                            int startNode, int goalNode, TravelMode mode,
                                            RoutePreferences preferences) {
        RoadNode goal = network.node(goalNode);
        if (goal == null) {
            return null;
        }

        Map<Integer, Double> gScore = new HashMap<>();
        Map<Integer, Integer> turns = new HashMap<>();
        Map<Integer, RoadSegment> arrivedBy = new HashMap<>();
        Map<Integer, Integer> cameFromNode = new HashMap<>();
        Map<Integer, RoadSegment> cameFromSegment = new HashMap<>();
        Set<Integer> closed = new HashSet<>();

        gScore.put(startNode, 0.0);
        turns.put(startNode, 0);
        // Entries are {fScore, turns, nodeId}. Stale entries are tolerated and skipped via the closed
        // set.
        //
        // The middle number is the tie-break, and it is the whole answer to a rectilinear network.
        // Every staircase across a grid of streets is the same length as the two-turn route that goes
        // straight and turns once, so the cost cannot choose between them and the search used to
        // return whichever the heap happened to reach first -- which the panel then read out as turn
        // left, turn right, turn left, turn right, all the way to the destination. Ordering equal
        // costs by the fewest turns is what a driver does and what a maps app shows. Nothing is added
        // to the cost: a route that is genuinely longer is still not chosen, so "shortest distance"
        // remains the shortest and "fastest time" the fastest.
        PriorityQueue<double[]> frontier = new PriorityQueue<>(
                Comparator.<double[]>comparingDouble(a -> a[0]).thenComparingDouble(a -> a[1]));
        frontier.add(new double[]{heuristic(network, startNode, goal, mode, preferences), 0, startNode});

        while (!frontier.isEmpty()) {
            int current = (int) frontier.poll()[2];
            if (!closed.add(current)) {
                continue;
            }
            if (current == goalNode) {
                return reconstruct(cameFromNode, cameFromSegment, startNode, goalNode);
            }

            double currentG = gScore.getOrDefault(current, Double.MAX_VALUE);
            int currentTurns = turns.getOrDefault(current, 0);
            RoadSegment arrived = arrivedBy.get(current);
            for (Edge edge : graph.getOrDefault(current, List.of())) {
                if (closed.contains(edge.toNode())) {
                    continue;
                }
                double tentative = currentG + edgeCost(edge.segment(), mode, preferences);
                int viaTurns = currentTurns
                        + (turnsAt(arrived, current, edge.segment()) ? 1 : 0);
                double known = gScore.getOrDefault(edge.toNode(), Double.MAX_VALUE);
                boolean better = tentative < known - COST_EPSILON;
                // Equal cost and fewer turns: replace, so the settled route is the tidiest of the
                // equally good ones rather than the first one found.
                boolean tidier = !better && tentative <= known + COST_EPSILON
                        && viaTurns < turns.getOrDefault(edge.toNode(), Integer.MAX_VALUE);
                if (!better && !tidier) {
                    continue;
                }
                // A highway is a road with no way to turn round on it, so a bend no highway makes is
                // not an edge: see highwayBendAllowed. Neither half of the rule can be read off one
                // piece alone -- what happens at this node is a question about the two pieces meeting
                // there, and what happens along a piece is about that piece's own line -- which is
                // why the rule lives at the one place both are in hand.
                if (!highwayBendAllowed(arrived, current, edge.segment())) {
                    continue;
                }
                double settled = Math.min(tentative, known);
                gScore.put(edge.toNode(), settled);
                turns.put(edge.toNode(), viaTurns);
                arrivedBy.put(edge.toNode(), edge.segment());
                cameFromNode.put(edge.toNode(), current);
                cameFromSegment.put(edge.toNode(), edge.segment());
                frontier.add(new double[]{
                        settled + heuristic(network, edge.toNode(), goal, mode, preferences),
                        viaTurns, edge.toNode()});
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
     *
     * <p>The preference weight is applied here, to the cost and to nothing else. It used to be folded
     * into the pace instead, which quietly made the search minimise a number that was not the estimate
     * the panel prints -- and, under the shortest-distance metric, was dropped altogether, so the same
     * switch meant one thing in one metric and nothing in the other. A weight multiplies whichever
     * metric is in force, so the preference now steers both, and no reported figure is touched.
     */
    private static double edgeCost(RoadSegment segment, TravelMode mode,
                                   RoutePreferences preferences) {
        RoadClass roadClass = segment.roadClass();
        double weight = preferences.weight(roadClass);
        if (preferences.metric() == RoutePreference.SHORTEST_DISTANCE) {
            // Pure geometry, and nothing else. "Prefer major roads" is a taste about *pace* -- a
            // footpath is slow, so a longer road is worth the detour -- and length is not pace, so it
            // has nothing to say in this metric. It used to multiply the length by the footpath's 1.6
            // here, which made a hundred blocks of footpath lose to a hundred and fifty of road: an
            // answer that is not the shortest route, under a label that promises exactly that.
            return segment.length() * weight;
        }
        return segment.length() * weight / Math.max(0.05, effectiveSpeed(mode, roadClass));
    }

    /**
     * Pace in blocks per second the mode actually makes on a class.
     *
     * <p>The real pace, and deliberately not a preference-adjusted one. It is what the route's legs
     * are written with and therefore what the panel's estimate is made of, so it has to be the speed
     * the player would see, not the speed the search was steered by -- see
     * {@link RoutePreferences#weight}, which is where the steering lives now. A value that depended on
     * the routing policy would make the same journey report a different time depending on a switch
     * that does not move the player any faster.
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
     * How much the route has to bend at a node before the bend counts as a turn, in degrees.
     *
     * <p>The same angle the turn prompts use, because the two are answers to the same question: a
     * bend nobody is told to make is not a turn to count either.
     */
    private static final double TURN_BREAK_DEGREES = 25.0;

    /**
     * How sharply a highway may bend before the bend is not one a highway makes, in degrees.
     *
     * <h2>What this is for</h2>
     * A highway is a road built to be driven along, and the one thing it never offers is a way to
     * turn round: the mod's own guidance says so out loud -- see {@code Navigation.uturnSentence},
     * where a highway U-turn is re-worded as carrying on to the next junction. That was wording only,
     * and it could say whatever it liked because the route had already been allowed to plan a
     * hairpin: a player could be sent onto a highway and told to double back on it, which no highway
     * has ever permitted and which no driver could carry out.
     *
     * <p>A hundred degrees leaves a highway every bend it really has -- a dual carriageway's turn at
     * the end of a median, a ramp curving away, a road that turns a corner -- and refuses the ones it
     * does not: the hairpin, the doubling back, and the reversal. A right-angled junction is ninety
     * and stays.
     *
     * <p>Refused in the <em>graph</em> rather than priced in the cost, because this is a rule about
     * what a highway is and not a preference about which way is nicer: a trip that can only be made
     * by doubling back on a highway is one this mod does not have an answer for, and the failure it
     * reports is the honest one. A penalty would leave that route in the answer for a player who
     * avoided everything else, which is the opposite of what was asked for.
     */
    private static final double HIGHWAY_BEND_LIMIT_DEGREES = 100.0;

    /**
     * Whether a highway may bend this sharply, which is what decides if the edge exists at all.
     *
     * <p>On any other class it may: a driver turning off a road into a side street, a walker cutting
     * back along a path and a boat turning a corner are all ordinary, and the guidance has something
     * to say about each of them.
     */
    private static boolean highwayMayBend(double degrees) {
        return degrees <= HIGHWAY_BEND_LIMIT_DEGREES;
    }

    /**
     * How close two costs have to be to count as the same cost.
     *
     * <p>Costs here are sums of lengths over paces, so two routes over the same ground at the same
     * pace come out equal exactly -- but not necessarily bit for bit once the lengths are hypotenuses
     * of different legs. The tolerance is what makes "the same cost" mean the same cost rather than
     * the same double.
     */
    private static final double COST_EPSILON = 1.0E-9;

    /**
     * Whether a traveller who arrived on {@code arrived} may leave {@code current} along
     * {@code next}.
     *
     * <h2>Why this is asked during the search as well as on the road</h2>
     * The graph stores an edge per road, not per pair of roads: what a highway does at this node is
     * known once the node is reached, and it is then a question about two pieces at once. Putting it
     * on the edge would mean storing every pair of roads meeting at every node, which is the
     * quadratic blow-up {@link #buildGraph} is careful not to be. So the rule lives here, where both
     * pieces are in hand, and every search that builds a route asks it.
     *
     * <p>{@code arrived} is null for the first piece out of the source, which is not a turn: leaving
     * the node a trip starts at is what a trip is. That is the one place a route may set off along a
     * highway without having come down one.
     */
    private static boolean highwayBendAllowed(RoadSegment arrived, int current, RoadSegment next) {
        // A bend inside the piece being taken is asked first, and before the "nothing arrived" case
        // below, because the first piece out of the source arrives from nowhere: a highway whose own
        // polyline doubles back is travelled as one edge, so no junction is ever reached to refuse it
        // at, and the driver would be asked to make the bend all the same. See
        // bendsPastTheLimitInside.
        if (bendsPastTheLimitInside(next)) {
            return false;
        }
        // A highway is only restricted where a highway is involved: a driver who has come down a road
        // and turns onto a highway has made an ordinary turn, and a walker on a highway's verge is
        // not driving one.
        if (arrived == null
                || (arrived.roadClass() != RoadClass.HIGHWAY
                        && next.roadClass() != RoadClass.HIGHWAY)) {
            return true;
        }
        // Doubling back on a highway is refused on its own terms rather than through an angle: going
        // back along the piece just travelled, or onto a piece that leaves this node for the one the
        // traveller came from, is the U-turn a highway does not have -- whatever the arithmetic of
        // the bend says. Anything that is not that is then held to the hundred-degree limit, which is
        // what keeps the hairpin out: a highway cannot curve round and rejoin itself either.
        if (doublesBackOnItself(arrived, current, next)) {
            return false;
        }
        double degrees = turnDegrees(arrived, current, next);
        return Double.isNaN(degrees) || highwayMayBend(Math.abs(degrees));
    }

    /**
     * Whether a highway piece bends past the limit at one of its own vertices.
     *
     * <h2>Why the rule cannot be about junctions alone</h2>
     * A road is a polyline, and the places it changes heading are not all junctions: a piece stored
     * with vertices in its middle bends between its own ends, and a traveller going from one end of
     * it to the other is asked to make every one of those bends. Read only at junctions, a highway
     * folded in two inside one piece -- the same hairpin as two pieces meeting at a node, with no
     * node to meet at -- passed both searches, and the map drew the driver a hundred-and-seventy-six
     * degree reversal with nothing between it and the rule. The bend belongs to the piece, so the
     * piece is where it is asked.
     *
     * <p>Asked of the piece <em>being taken</em> and asked once for each piece travelled: the first
     * piece of a trip is taken from its source with nothing arrived from, so a check that only ever
     * looked at the piece arrived on would never look at that one.
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
     * The angle a polyline changes heading by at one of its own vertices, in degrees.
     *
     * <p>Exactly the reading {@link #turnDegrees} takes of two pieces at a node, taken here of two
     * spans of one piece, and with the same sign convention. NaN when either span has no length,
     * because there is no heading there to change.
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
     * Whether travelling on from {@code arrived} to {@code next} at {@code current} puts the
     * traveller back on the ground they have just come over.
     *
     * <p>True when the outgoing piece is the incoming piece itself, and true when the two run
     * node-to-node between the same pair of nodes -- a road drawn as two pieces, one each way, which
     * is one road to everybody but the graph.
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
     * Whether going from {@code incoming} to {@code outgoing} at a node is a turn rather than a
     * continuation.
     *
     * <p>Measured on the two pieces' own directions where they meet, not on the nodes: a road that
     * curves is still one way to travel, and the question here is only whether the traveller has to
     * change heading.
     */
    private static boolean turnsAt(RoadSegment incoming, int atNode, RoadSegment outgoing) {
        double degrees = turnDegrees(incoming, atNode, outgoing);
        return !Double.isNaN(degrees) && Math.abs(degrees) >= TURN_BREAK_DEGREES;
    }

    /**
     * The angle a traveller changes heading by, going from {@code incoming} to {@code outgoing} at a
     * node, in degrees; positive is towards +Z, which is {@link #turnsAt}'s convention.
     *
     * <p>NaN when either piece has no direction to give -- nothing arrives, or nothing leaves -- which
     * is the same answer {@link #turnsAt} treats as "not a turn".
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
     * Heading the traveller is moving in as they arrive at one of a segment's two nodes.
     *
     * <p>Read together with {@link #leaveBearing}, which answers for the piece being left: the angle
     * between the two is the bend at the node, and the two are written as mirror images of each other
     * so that a straight continuation comes out as no bend at all.
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
     * The heading of one span of a piece, in radians, or NaN when the span has no length.
     *
     * <h2>Why a span with no length has no heading</h2>
     * {@code atan2(0, 0)} is zero, so a piece whose two relevant vertices are the same point used to
     * be given a heading of due east -- a direction invented out of nothing, and read against the
     * real heading of the piece on the other side of the node as a bend of a hundred and eighty
     * degrees. The case is not hypothetical: two nodes within {@link #COINCIDENT_DISTANCE} of each
     * other are joined by a synthetic {@link RoadSegment} that is often exactly zero long, and one
     * of those links sits between the westbound carriageway of a highway and the road that carries
     * on west from the same spot. Driving straight on was refused there as a U-turn, and a trip that
     * had to pass the junction was sent the long way round.
     *
     * <p>NaN is the honest answer -- nothing arrived, or nothing leaves -- and both callers already
     * treat it as "not a turn": see {@link #turnDegrees}, which returns NaN when either side has no
     * heading to give.
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
     * Remaining cost, optimistic: the bound has to sit under what the search will really pay, or
     * A* stops being admissible and can return a route that is not the best one.
     *
     * <p>For time that means the fastest pace any class in play offers, straight there; for distance
     * it means the straight line itself, which no line of roads can be shorter than and which is
     * exactly what the search pays per block -- the metre no longer carries a preference penalty, so
     * the bound must not carry one either.
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
        // Nothing in play means the graph is empty and the search is about to find nothing; the
        // bound only has to stay finite so the frontier can drain.
        return fastest <= 0 ? 0 : distance / fastest;
    }

    /** Fastest pace available on any class this trip may use, in blocks per second. */
    private static double fastestSpeed(TravelMode mode, RoutePreferences preferences) {
        double fastest = 0;
        for (RoadClass roadClass : RoadClass.values()) {
            if (usable(mode, preferences, roadClass)) {
                // The roomiest pace in play, whatever the trip is being steered towards. A weight
                // only ever multiplies a cost up -- see RoutePreferences.weight -- so a bound built
                // from unweighted paces stays under every real cost and the search stays admissible.
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
        // The junctions come from the same grouping the keys do, so marking them costs nothing per
        // segment: the route needs to know where the road really forks, and that is a property of the
        // whole network rather than of any one segment.
        for (RoadSegment segment : path) {
            appendSegment(builder, grouping, segment, previousNode);
            // The mode's real pace on this class, so the route's own legs time it at the speed the
            // player actually travels: the preference steers the search and never the estimate.
            builder.addRoadLeg(segment.length(), effectiveSpeed(mode, segment.roadClass()));
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
