package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.HowToGo;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
//? if fabric {
/*import net.fabricmc.loader.api.FabricLoader;
*///?} else {
import net.neoforged.fml.ModList;
//?}

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Create's client-side track graph, read reflectively; no Create class appears in a signature.
 *
 * Reading Create's graph beats reading blocks: node positions are Create's own, a curve is one edge with a real Bezier that can be sampled rather than a staircase of blocks, and a station carries its name. Every handle is looked up by name once and guarded; with Create absent this reports unavailable and the caller falls back to its own block scan.
 * The chain mirrors Create's own map integration: CreateClient.RAILWAYS.trackNetworks (Map<UUID, TrackGraph>) -> getNodes() -> locateNode -> getConnectionsFrom -> getPoints(EdgePointType.STATION), plus TrackEdge.isTurn, getLength and getPosition. The manager's version counter is used to skip rebuilding a layer that cannot have changed.
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

    private static final int MAX_CURVE_VERTICES = 24;
    private static final double CURVE_VERTEX_SPACING = 2.0;
    private static final double ENDS_MATCH_BLOCKS = 4.0;

    private static volatile boolean resolved;
    private static volatile boolean available;
    private static volatile boolean warned;

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

    public record Station(int x, int y, int z, String name) {
    }

    public record Edge(int from, int to, double[] polyline) {
    }

    /**
     * One reading of the client's graph, for one dimension.
     *
     * version is the manager's change counter; curves counts edges that carried a Bezier; stations carry their names.
     */
    public record Snapshot(int version, int graphs, int curves, int oddEdges, double[] nodeX,
                           double[] nodeY, double[] nodeZ, List<Edge> edges, List<Station> stations) {

        public int nodeCount() {
            return nodeX.length;
        }
    }

    public static boolean available() {
        resolve();
        return available;
    }

    /**
     * The manager's change counter, or Integer.MIN_VALUE when it cannot be read.
     *
     * Read on its own without walking the graph, so a caller can skip a read with nothing new; only compared, never used in arithmetic.
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
     * Reads the client's graph for one dimension, or null when it cannot be read. Never throws: this runs on a client tick, so anything unexpected comes back as nothing to read.
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
            // Keyed by the location's own equality: two equal locations are the same point of track, and an
            // identity map that missed would silently drop every edge.
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
                // One index per graph, keyed by Create's own node id.
                //
                // Two graphs are two separate networks -- Create splits a network at a portal -- so their nodes must
                // never share an index: merging by position would join networks that do not touch and draw a chord
                // across the map. The id also settles which of two nodes at the same place is which.
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
                        // Without an id there is no way to tell this node from another at the same place, so it is left out
                        // rather than guessed at.
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
     * The polyline of one edge, running from from to to, with its ends on the nodes.
     *
     * A straight edge is the line between the two nodes. A curve is sampled along its own Bezier through Create's accessor then oriented, since samples run the edge's direction and a backwards curve would leave the junction the wrong way. The ends are written as the node positions so segments meet exactly by sharing a coordinate, and only when the samples already ended there -- a curve that truly starts elsewhere is reported in oddEdges rather than stretched to fit. Create's map plots rasterised pixels and needs no direction; a road here must be an ordered polyline for the router and the renderer.
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
            // The curve does not run between the nodes it is attached to. Its own shape is still the only line
            // we are entitled to draw, so it is left exactly as Create gave it.
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
     * Reverses a sampled polyline when it runs the other way, so geometry always goes from the walk's start node to the node it is going to.
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
     * Guarded because this runs on a client tick and a failure is permanent -- a missing class does not appear later. Every lookup is Create's public API. Double-checked locking with volatile avoids synchronized on every call after the first success.
     */
    private static void resolve() {
        // Fast path: resolved already, and no lock is taken at all -- the path every tick after the first
        // takes.
        if (resolved) {
            return;
        }
        // Slow path: the first call, or two threads arriving together. The flag is volatile, so the second
        // check inside the lock sees a binding another thread already completed.
        synchronized (CreateTrackGraph.class) {
            if (resolved) {
                return;
            }
            bind();
        }
    }

    /**
     * The lookups themselves, run once from inside resolve's lock.
     *
     * resolved is published last, in a finally: volatile means a thread that reads it as set sees every handle assigned above it, so setting it first would let another thread skip the lock and use uninitialised handles. Set on failure too, because a class not on the classpath will not appear later.
     */
    private static void bind() {
        try {
            //? if fabric {
        /*if (!FabricLoader.getInstance().isModLoaded(MOD_ID)) {
        *///?} else {
        if (!ModList.get().isLoaded(MOD_ID)) {
        //?}
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
            HowToGo.logInfo("[HowToGo] Create track graph bound reflectively ({})", CREATE_CLIENT);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            available = false;
            warnUnavailable(e);
        } finally {
            // Published last, so every handle above is visible to a thread that reads this as set; the order is
            // the correctness of the lock-free path.
            resolved = true;
        }
    }

    private static void warnUnavailable(Throwable cause) {
        if (warned) {
            return;
        }
        warned = true;
        HowToGo.LOGGER.warn("[HowToGo] Create's track graph is unavailable ({}); the layer will fall "
                + "back to reading track blocks out of the loaded chunks", cause.toString());
    }
}
