package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.HowToGo;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.fabricmc.loader.api.FabricLoader;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Create's client-side track graph, read reflectively.
 *
 * <h2>Why this and not the blocks</h2>
 * Create puts the whole rail network on the client: {@code GlobalRailwayManager.trackNetworks} holds
 * the {@code TrackGraph}s, and Create's own map integration draws them. Reading that is better than
 * reading blocks for three reasons: the node positions are Create's own, so a track is where Create
 * says it is rather than at a block centre; a curve is one edge with a real Bezier on it, which can
 * be sampled, rather than a staircase of diagonal blocks; and a station carries its name, which the
 * blocks never do. Nothing here needs a dependency: no Create class appears in a signature, every
 * handle is looked up by name once and guarded, and with Create absent this reports itself
 * unavailable and the caller falls back to its own block scan.
 *
 * <h2>The chain, taken from Create's own integration</h2>
 * Create's {@code XaeroFullscreenMapMixin} hands off to {@code XaeroTrainMap.onRender}, which goes
 * through {@code TrainMapManager}. Its bytecode reads exactly this, and so does this class:
 * <pre>
 * CreateClient.RAILWAYS                     // static, the client's own manager
 *   .trackNetworks                          // Map&lt;UUID, TrackGraph&gt; -- one per connected network
 *     .getNodes()                           // Set&lt;TrackNodeLocation&gt;
 *     .locateNode(TrackNodeLocation)        // TrackNode
 *     .getConnectionsFrom(TrackNode)        // Map&lt;TrackNode, TrackEdge&gt;
 *     .getPoints(EdgePointType.STATION)     // Collection&lt;GlobalStation&gt;
 * TrackNode.getLocation().getLocation()     // Vec3, the node's world position
 * TrackEdge.isTurn() / getLength() / getPosition(TrackGraph, double)
 * GlobalStation.blockEntityPos / name
 * </pre>
 * The manager also reads {@code version}, a counter it bumps when the network changes, which is used
 * here to avoid rebuilding a layer that cannot have changed.
 */
public final class CreateTrackGraph {

    private static final String MOD_ID = "create";

    private static final String CREATE_CLIENT = "com.simibubi.create.CreateClient";
    private static final String RAILWAY_MANAGER = "com.simibubi.create.content.trains.GlobalRailwayManager";
    private static final String TRACK_GRAPH = "com.simibubi.create.content.trains.graph.TrackGraph";
    private static final String TRACK_NODE = "com.simibubi.create.content.trains.graph.TrackNode";
    private static final String NODE_LOCATION = "com.simibubi.create.content.trains.graph.TrackNodeLocation";
    private static final String TRACK_EDGE = "com.simibubi.create.content.trains.graph.TrackEdge";
    private static final String EDGE_POINT_TYPE = "com.simibubi.create.content.trains.graph.EdgePointType";
    private static final String EDGE_POINT = "com.simibubi.create.content.trains.signal.SingleBlockEntityEdgePoint";
    private static final String GLOBAL_STATION = "com.simibubi.create.content.trains.station.GlobalStation";

    /** Longest a curve is sampled at, in vertices: enough for a map line, bounded for a long one. */
    private static final int MAX_CURVE_VERTICES = 24;
    /** One vertex per this many blocks of a curve, so a short one still shows its shape. */
    private static final double CURVE_VERTEX_SPACING = 2.0;
    /** How far a sampled end may sit from the node it should be, before the edge is counted as odd. */
    private static final double ENDS_MATCH_BLOCKS = 4.0;

    private static boolean resolved;
    private static boolean available;
    private static boolean warned;

    private static Field railwaysField;
    private static Field trackNetworksField;
    private static Field versionField;
    private static Method getNodesMethod;
    private static Method locateNodeMethod;
    private static Method getConnectionsFromMethod;
    private static Method getPointsMethod;
    private static Object stationType;
    private static Class<?> nodeLocationClass;
    private static Class<?> trackGraphClass;
    private static Method nodeGetNetIdMethod;
    private static Method locationGetLocationMethod;
    private static Method locationGetDimensionMethod;
    private static Method edgeIsTurnMethod;
    private static Method edgeGetLengthMethod;
    private static Method edgeGetPositionMethod;
    private static Class<?> stationClass;
    private static Field stationNameField;
    private static Method stationBlockPosMethod;
    private static Method stationDimensionMethod;

    private CreateTrackGraph() {
    }

    /** A station, with the name Create gave it, or null when it came from the block scan. */
    public record Station(int x, int y, int z, String name) {
    }

    /** One undirected graph edge and its geometry, as interleaved x, z running from {@code from}. */
    public record Edge(int from, int to, double[] polyline) {
    }

    /**
     * One reading of the client's graph, for one dimension.
     *
     * @param version  the manager's change counter when this was read
     * @param curves   how many of the edges carried a Bezier rather than a straight line
     * @param stations stations found, with their names
     */
    public record Snapshot(int version, int graphs, int curves, int oddEdges, double[] nodeX,
                           double[] nodeY, double[] nodeZ, List<Edge> edges, List<Station> stations) {

        public int nodeCount() {
            return nodeX.length;
        }
    }

    /** Whether Create's graph can be read at all in this session. */
    public static boolean available() {
        resolve();
        return available;
    }

    /**
     * The manager's change counter, or {@link Integer#MIN_VALUE} when it cannot be read.
     *
     * <p>Read on its own, without walking the graph, so a caller can skip a read that cannot have
     * anything new in it. Never used in arithmetic, only compared.
     */
    public static int version() {
        if (!available()) {
            return Integer.MIN_VALUE;
        }
        try {
            Object manager = railwaysField.get(null);
            return manager == null ? Integer.MIN_VALUE : versionField.getInt(manager);
        } catch (ReflectiveOperationException | RuntimeException e) {
            warnUnavailable(e);
            return Integer.MIN_VALUE;
        }
    }

    /**
     * Reads the client's graph for one dimension, or null when it cannot be read.
     *
     * <p>Never throws: this runs on a client tick, so anything unexpected has to come back as
     * "nothing to read" rather than as a crash.
     */
    public static Snapshot read(ResourceKey<Level> dimension) {
        if (!available()) {
            return null;
        }
        try {
            Object manager = railwaysField.get(null);
            if (manager == null) {
                return null;
            }
            Object networks = trackNetworksField.get(manager);
            if (!(networks instanceof Map<?, ?> graphs) || graphs.isEmpty()) {
                return new Snapshot(versionField.getInt(manager), 0, 0, 0, new double[0], new double[0],
                        new double[0], List.of(), List.of());
            }

            List<Integer> from = new ArrayList<>();
            List<Integer> to = new ArrayList<>();
            List<double[]> polylines = new ArrayList<>();
            List<Double> xs = new ArrayList<>();
            List<Double> ys = new ArrayList<>();
            List<Double> zs = new ArrayList<>();
            List<Station> stations = new ArrayList<>();
            // Keyed by the location's own equality rather than by identity: the graph hands the same
            // object back in practice, but two equal locations are the same point of track either way,
            // and an identity map that missed would silently drop every edge.
            int curves = 0;
            int oddEdges = 0;
            int graphCount = 0;

            for (Object graph : graphs.values()) {
                if (graph == null || !trackGraphClass.isInstance(graph)) {
                    continue;
                }
                graphCount++;
                Object nodes = getNodesMethod.invoke(graph);
                if (!(nodes instanceof Set<?> locations)) {
                    continue;
                }
                // One index per graph, keyed by Create's own node id. Two graphs are two separate
                // networks -- Create splits a network at a portal, and unrelated lines are separate
                // graphs -- so their nodes must never share an index: merging them by position would
                // join networks that do not touch, which is drawn as a chord across the map. The id
                // also settles which of two nodes at the same place is which, which a position cannot.
                Map<Integer, Integer> byId = new HashMap<>();
                List<Object> graphNodes = new ArrayList<>();
                List<Integer> graphIndices = new ArrayList<>();
                for (Object location : locations) {
                    if (!nodeLocationClass.isInstance(location) || !inDimension(location, dimension)) {
                        continue;
                    }
                    Object node = locateNodeMethod.invoke(graph, location);
                    if (node == null) {
                        continue;
                    }
                    Object netId = nodeGetNetIdMethod.invoke(node);
                    if (!(netId instanceof Integer id)) {
                        // Without an id there is no way to tell this node from another at the same
                        // place, so it is left out rather than guessed at.
                        continue;
                    }
                    if (byId.containsKey(id)) {
                        continue;
                    }
                    Vec3 position = nodePosition(location);
                    xs.add(position.x);
                    ys.add(position.y);
                    zs.add(position.z);
                    int index = xs.size() - 1;
                    byId.put(id, index);
                    graphNodes.add(node);
                    graphIndices.add(index);
                }

                for (int n = 0; n < graphNodes.size(); n++) {
                    Object node = graphNodes.get(n);
                    int index = graphIndices.get(n);
                    Object connections = getConnectionsFromMethod.invoke(graph, node);
                    if (!(connections instanceof Map<?, ?> neighbours)) {
                        continue;
                    }
                    for (Map.Entry<?, ?> entry : neighbours.entrySet()) {
                        Object other = entry.getKey();
                        Object edge = entry.getValue();
                        if (other == null || edge == null) {
                            continue;
                        }
                        Object otherNetId = nodeGetNetIdMethod.invoke(other);
                        if (!(otherNetId instanceof Integer otherId)) {
                            continue;
                        }
                        Integer otherIndex = byId.get(otherId);
                        if (otherIndex == null || otherIndex == index) {
                            continue;
                        }
                        // One entry per undirected edge: the other end adds the same pair back.
                        if (otherIndex < index) {
                            continue;
                        }
                        double[] polyline = geometry(graph, edge,
                                new double[]{xs.get(index), zs.get(index)},
                                new double[]{xs.get(otherIndex), zs.get(otherIndex)});
                        if (polyline.length < 4) {
                            continue;
                        }
                        boolean curved = Boolean.TRUE.equals(edgeIsTurnMethod.invoke(edge));
                        if (curved) {
                            curves++;
                            if (!endsMatch(polyline, xs.get(index), zs.get(index),
                                    xs.get(otherIndex), zs.get(otherIndex))) {
                                oddEdges++;
                            }
                        }
                        from.add(index);
                        to.add(otherIndex);
                        polylines.add(polyline);
                    }
                }

                Object points = getPointsMethod.invoke(graph, stationType);
                if (points instanceof Collection<?> edgePoints) {
                    for (Object point : edgePoints) {
                        if (!stationClass.isInstance(point)) {
                            continue;
                        }
                        Station station = station(point, dimension);
                        if (station != null) {
                            stations.add(station);
                        }
                    }
                }
            }

            double[] nodeX = new double[xs.size()];
            double[] nodeY = new double[xs.size()];
            double[] nodeZ = new double[zs.size()];
            for (int i = 0; i < nodeX.length; i++) {
                nodeX[i] = xs.get(i);
                nodeY[i] = ys.get(i);
                nodeZ[i] = zs.get(i);
            }
            List<Edge> edges = new ArrayList<>(from.size());
            for (int i = 0; i < from.size(); i++) {
                edges.add(new Edge(from.get(i), to.get(i), polylines.get(i)));
            }
            return new Snapshot(versionField.getInt(manager), graphCount, curves, oddEdges, nodeX, nodeY,
                    nodeZ, edges, stations);
        } catch (ReflectiveOperationException | RuntimeException e) {
            warnUnavailable(e);
            return null;
        }
    }

    /**
     * The polyline of one edge, running from {@code from} to {@code to}, with its ends on the nodes.
     *
     * <p>A straight edge <em>is</em> the line between the two nodes, so it is two points and nothing
     * else. A curve is sampled along its own Bezier through Create's own accessor, then turned the
     * right way round: the samples run in the <em>edge's</em> direction, which is not necessarily the
     * direction the caller walked in, and taking a curve's path backwards draws a line that leaves the
     * junction the wrong way and comes back.
     *
     * <p>The ends are then written as the two node positions themselves. That is what makes segments
     * that meet at a node meet exactly -- by sharing a coordinate, not by anything being joined -- and
     * it is only done when the samples already ended at those nodes, so a curve that genuinely starts
     * somewhere else is reported in {@code oddEdges} rather than being stretched to fit.
     *
     * <p>Create's own map never has the direction problem because it does not keep a direction at all:
     * it plots the points of {@code BezierConnection.rasterise()} as pixels. A road here has to be an
     * ordered polyline for the router and the renderer, so the direction has to be settled, and the one
     * fact that settles it is that an edge's two ends <em>are</em> its two nodes.
     */
    private static double[] geometry(Object graph, Object edge, double[] from, double[] to)
            throws ReflectiveOperationException {
        if (!Boolean.TRUE.equals(edgeIsTurnMethod.invoke(edge))) {
            return new double[]{from[0], from[1], to[0], to[1]};
        }
        double length = (Double) edgeGetLengthMethod.invoke(edge);
        int steps = (int) Math.ceil(length / CURVE_VERTEX_SPACING);
        steps = Math.max(1, Math.min(MAX_CURVE_VERTICES, steps));
        double[] samples = new double[(steps + 1) * 2];
        for (int i = 0; i <= steps; i++) {
            // t is a fraction of the edge in both of Create's branches, straight and curved.
            Vec3 point = (Vec3) edgeGetPositionMethod.invoke(edge, graph, i / (double) steps);
            samples[i * 2] = point.x;
            samples[i * 2 + 1] = point.z;
        }
        double[] oriented = orient(samples, from[0], from[1], to[0], to[1]);
        if (!endsMatch(oriented, from[0], from[1], to[0], to[1])) {
            // The curve does not run between the nodes it is attached to. Its own shape is still the
            // only line we are entitled to draw, so it is left exactly as Create gave it.
            return oriented;
        }
        int points = oriented.length / 2;
        if (points <= 2) {
            return new double[]{from[0], from[1], to[0], to[1]};
        }
        double[] pinned = new double[oriented.length];
        pinned[0] = from[0];
        pinned[1] = from[1];
        for (int i = 1; i < points - 1; i++) {
            pinned[i * 2] = oriented[i * 2];
            pinned[i * 2 + 1] = oriented[i * 2 + 1];
        }
        pinned[(points - 1) * 2] = to[0];
        pinned[(points - 1) * 2 + 1] = to[1];
        return pinned;
    }

    /**
     * Reverses a sampled polyline when it runs the other way, so that geometry always goes from the
     * node the walk started at to the node it is going to.
     *
     * <p>Decided by which end of the sample sits at the start node, which needs nothing from Create
     * but the two positions the caller already has.
     */
    private static double[] orient(double[] polyline, double fromX, double fromZ, double toX, double toZ) {
        int points = polyline.length / 2;
        if (points < 2) {
            return polyline;
        }
        double headToFrom = distanceSq(polyline[0], polyline[1], fromX, fromZ);
        double headToTo = distanceSq(polyline[0], polyline[1], toX, toZ);
        if (headToTo >= headToFrom) {
            return polyline;
        }
        double[] flipped = new double[polyline.length];
        for (int i = 0; i < points; i++) {
            int source = (points - 1 - i) * 2;
            flipped[i * 2] = polyline[source];
            flipped[i * 2 + 1] = polyline[source + 1];
        }
        return flipped;
    }

    /** Whether a sampled curve really does start and end at the two nodes it was asked for. */
    private static boolean endsMatch(double[] polyline, double fromX, double fromZ, double toX, double toZ) {
        int last = polyline.length - 2;
        return distanceSq(polyline[0], polyline[1], fromX, fromZ) <= ENDS_MATCH_BLOCKS * ENDS_MATCH_BLOCKS
                && distanceSq(polyline[last], polyline[last + 1], toX, toZ)
                        <= ENDS_MATCH_BLOCKS * ENDS_MATCH_BLOCKS;
    }

    private static double distanceSq(double ax, double az, double bx, double bz) {
        double dx = ax - bx;
        double dz = az - bz;
        return dx * dx + dz * dz;
    }

    private static Vec3 nodePosition(Object location) {
        try {
            return (Vec3) locationGetLocationMethod.invoke(location);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return Vec3.ZERO;
        }
    }

    private static boolean inDimension(Object location, ResourceKey<Level> dimension)
            throws ReflectiveOperationException {
        Object nodeDimension = locationGetDimensionMethod.invoke(location);
        return nodeDimension == null || nodeDimension.equals(dimension);
    }

    private static Station station(Object point, ResourceKey<Level> dimension)
            throws ReflectiveOperationException {
        Object pointDimension = stationDimensionMethod.invoke(point);
        if (pointDimension != null && !pointDimension.equals(dimension)) {
            return null;
        }
        Object pos = stationBlockPosMethod.invoke(point);
        if (!(pos instanceof BlockPos blockPos)) {
            return null;
        }
        Object name = stationNameField.get(point);
        return new Station(blockPos.getX(), blockPos.getY(), blockPos.getZ(),
                name instanceof String text ? text : null);
    }

    /**
     * Looks every class, field and method up once and keeps the result.
     *
     * <p>Guarded because this is read on a client tick, and a failure is permanent: a missing class
     * does not appear later. Every lookup is public API of Create, so no privileged access is asked
     * for -- which also means this cannot break a Create that keeps its API but changes its innards.
     */
    private static synchronized void resolve() {
        if (resolved) {
            return;
        }
        resolved = true;
        try {
            if (!FabricLoader.getInstance().isModLoaded(MOD_ID)) {
                return;
            }
            Class<?> client = Class.forName(CREATE_CLIENT);
            Class<?> manager = Class.forName(RAILWAY_MANAGER);
            trackGraphClass = Class.forName(TRACK_GRAPH);
            Class<?> node = Class.forName(TRACK_NODE);
            nodeLocationClass = Class.forName(NODE_LOCATION);
            Class<?> edge = Class.forName(TRACK_EDGE);
            Class<?> pointType = Class.forName(EDGE_POINT_TYPE);
            Class<?> edgePoint = Class.forName(EDGE_POINT);
            stationClass = Class.forName(GLOBAL_STATION);

            railwaysField = client.getField("RAILWAYS");
            trackNetworksField = manager.getField("trackNetworks");
            versionField = manager.getField("version");
            getNodesMethod = trackGraphClass.getMethod("getNodes");
            locateNodeMethod = trackGraphClass.getMethod("locateNode", nodeLocationClass);
            getConnectionsFromMethod = trackGraphClass.getMethod("getConnectionsFrom", node);
            getPointsMethod = trackGraphClass.getMethod("getPoints", pointType);
            stationType = pointType.getField("STATION").get(null);
            nodeGetNetIdMethod = node.getMethod("getNetId");
            locationGetLocationMethod = nodeLocationClass.getMethod("getLocation");
            locationGetDimensionMethod = nodeLocationClass.getMethod("getDimension");
            edgeIsTurnMethod = edge.getMethod("isTurn");
            edgeGetLengthMethod = edge.getMethod("getLength");
            edgeGetPositionMethod = edge.getMethod("getPosition", trackGraphClass, double.class);
            stationNameField = stationClass.getField("name");
            stationBlockPosMethod = edgePoint.getMethod("getBlockEntityPos");
            stationDimensionMethod = edgePoint.getMethod("getBlockEntityDimension");

            available = true;
            HowToGo.LOGGER.info("[HowToGo] Create track graph bound reflectively ({})", CREATE_CLIENT);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            available = false;
            warnUnavailable(e);
        }
    }

    /** Reports a failure once, then stays quiet. */
    private static void warnUnavailable(Throwable cause) {
        if (warned) {
            return;
        }
        warned = true;
        HowToGo.LOGGER.warn("[HowToGo] Create's track graph is unavailable ({}); the layer will fall "
                + "back to reading track blocks out of the loaded chunks", cause.toString());
    }
}
