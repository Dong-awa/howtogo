package bili.dongsz.howtogo.webmap;

import bili.dongsz.howtogo.road.RoadClass;
import bili.dongsz.howtogo.road.RoadNetwork;
import bili.dongsz.howtogo.road.RoadNode;
import bili.dongsz.howtogo.road.RoadSegment;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/**
 * An immutable reading of one dimension's roads, in the shape the browser map draws.
 *
 * <h2>Why a copy rather than the network itself</h2>
 * The road network belongs to the game's client thread and the browser is served by another. Handing
 * the live {@link RoadNetwork} to an HTTP thread would mean iterating a {@code HashMap} while the
 * editor mutates it -- the exact failure the map's own {@link RoadNetwork#nodesSnapshot()} exists to
 * avoid -- so this class flattens what is needed into arrays that nobody can mutate afterwards, and
 * the JSON is built from the flattened form.
 *
 * <p>Everything the page needs and nothing it does not: no ids that mean nothing outside the mod, no
 * fields the map does not draw, and the class colours taken from {@link RoadClass} rather than
 * written down a second time in JavaScript, so a recoloured road class changes on both maps at once.
 *
 * <h2>The documented payload</h2>
 * The JSON this writes is the contract the front end is written against, and it is checked on both
 * sides: {@code tools/routing-harness} asserts the key set and the values of a fixture network, and
 * {@code tools/webmap-test} feeds a payload of the same shape through the page's own parser. Adding a
 * field is therefore a two-place change, which is the point.
 */
public final class RoadMapSnapshot {

    /** Bumped when a field's meaning changes; the page reads it to refuse a payload it cannot use. */
    public static final int FORMAT_VERSION = 1;

    /** One graph vertex, flattened. */
    public record Node(int id, int x, int y, int z, String type, String placeKind, String name) {
    }

    /**
     * One piece of road, flattened.
     *
     * <p>{@code points} is the segment's vertices interleaved as x, z, x, z -- the network's own
     * layout -- because the JSON writer turns it into the nested {@code [[x,z],...]} form the page
     * wants and nothing else ever reads it.
     */
    public record Segment(int id, String roadClass, int y, int layer, int from, int to,
                          String direction, String name, int[] points) {
    }

    /** The horizontal extent of everything that will be drawn. */
    public record Bounds(int minX, int minZ, int maxX, int maxZ) {

        public int width() {
            return maxX - minX;
        }

        public int height() {
            return maxZ - minZ;
        }
    }

    private final String world;
    private final String dimension;
    private final Bounds bounds;
    private final List<Node> nodes;
    private final List<Segment> segments;
    private final int[] layers;
    private final double lengthBlocks;
    private final boolean empty;

    private RoadMapSnapshot(String world, String dimension, Bounds bounds, List<Node> nodes,
                            List<Segment> segments, int[] layers, double lengthBlocks,
                            boolean empty) {
        this.world = world;
        this.dimension = dimension;
        this.bounds = bounds;
        this.nodes = List.copyOf(nodes);
        this.segments = List.copyOf(segments);
        this.layers = layers.clone();
        this.lengthBlocks = lengthBlocks;
        this.empty = empty;
    }

    /**
     * Flattens a network.
     *
     * <p>Must be called on the thread that owns the network: this walks it. The result carries no
     * reference to it.
     *
     * @param world     the world the data belongs to, for the page's own title; may be blank
     * @param dimension the dimension id, e.g. {@code minecraft:overworld}
     */
    public static RoadMapSnapshot of(String world, String dimension, RoadNetwork network) {
        Objects.requireNonNull(network, "network");

        List<Node> nodes = new ArrayList<>(network.nodeCount());
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;

        for (RoadNode node : network.nodes()) {
            nodes.add(new Node(node.id(), node.x(), node.y(), node.z(),
                    node.type().name(), node.placeKind().name(), node.name()));
            // Nodes count towards the extent even when no road joins them: a map of nothing but
            // places is still a map, and leaving them out would have the page open zoomed to a
            // zero-size box, which is the one view it cannot recover from.
            minX = Math.min(minX, node.x());
            maxX = Math.max(maxX, node.x());
            minZ = Math.min(minZ, node.z());
            maxZ = Math.max(maxZ, node.z());
        }

        List<Segment> segments = new ArrayList<>(network.segmentCount());
        TreeSet<Integer> layers = new TreeSet<>();
        double length = 0.0;
        for (RoadSegment segment : network.segments()) {
            int count = segment.vertexCount();
            int[] points = new int[count * 2];
            for (int i = 0; i < count; i++) {
                points[i * 2] = segment.x(i);
                points[i * 2 + 1] = segment.z(i);
                minX = Math.min(minX, segment.x(i));
                maxX = Math.max(maxX, segment.x(i));
                minZ = Math.min(minZ, segment.z(i));
                maxZ = Math.max(maxZ, segment.z(i));
            }
            segments.add(new Segment(segment.id(), segment.roadClass().name(), segment.y(),
                    segment.layer(), segment.fromNode(), segment.toNode(),
                    segment.direction().name(), segment.name(), points));
            layers.add(segment.layer());
            length += segment.length();
        }

        boolean nothing = nodes.isEmpty() && segments.isEmpty();
        Bounds bounds = nothing
                ? new Bounds(0, 0, 0, 0)
                : new Bounds(minX, minZ, maxX, maxZ);
        int[] layerArray = new int[layers.size()];
        int at = 0;
        for (int layer : layers) {
            layerArray[at++] = layer;
        }
        return new RoadMapSnapshot(world == null ? "" : world,
                dimension == null || dimension.isBlank() ? "unknown" : dimension,
                bounds, nodes, segments, layerArray, length, nothing);
    }

    /** An empty map for the given dimension: what a world with no roads drawn yet looks like. */
    public static RoadMapSnapshot none(String world, String dimension) {
        return new RoadMapSnapshot(world == null ? "" : world,
                dimension == null || dimension.isBlank() ? "unknown" : dimension,
                new Bounds(0, 0, 0, 0), List.of(), List.of(), new int[0], 0.0, true);
    }

    public String world() {
        return world;
    }

    public String dimension() {
        return dimension;
    }

    public Bounds bounds() {
        return bounds;
    }

    public List<Node> nodes() {
        return nodes;
    }

    public List<Segment> segments() {
        return segments;
    }

    public int nodeCount() {
        return nodes.size();
    }

    public int segmentCount() {
        return segments.size();
    }

    /** Every storey any road is on, ascending; the page uses it for the layer switches. */
    public int[] layers() {
        return layers.clone();
    }

    public double lengthBlocks() {
        return lengthBlocks;
    }

    /** Whether there is anything at all to draw. */
    public boolean empty() {
        return empty;
    }

    /**
     * The payload, as the documented contract.
     *
     * <p>Built as an ordered map rather than a set of DTO classes so the key order is the documented
     * one and stable in the output a reader compares against, and serialised by Gson so that a name a
     * player typed -- a quote, a backslash, a newline, Chinese -- cannot produce JSON that does not
     * parse. Handing strings straight into a hand-built string is where that goes wrong, and a road
     * network with one such name in it would otherwise take the whole page down.
     *
     * @param generatedAt epoch milliseconds the reading was taken, for the page's own timestamp
     */
    public String toJson(long generatedAt) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", FORMAT_VERSION);
        root.put("generatedAt", generatedAt);
        root.put("world", world);
        root.put("dimension", dimension);
        root.put("empty", empty);

        Map<String, Object> extent = new LinkedHashMap<>();
        extent.put("minX", bounds.minX());
        extent.put("minZ", bounds.minZ());
        extent.put("maxX", bounds.maxX());
        extent.put("maxZ", bounds.maxZ());
        root.put("bounds", extent);

        List<Map<String, Object>> classes = new ArrayList<>(RoadClass.values().length);
        for (RoadClass roadClass : RoadClass.values()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", roadClass.name());
            entry.put("color", hex(roadClass.color()));
            entry.put("width", roadClass.width());
            classes.add(entry);
        }
        root.put("classes", classes);

        List<Map<String, Object>> nodeList = new ArrayList<>(nodes.size());
        for (Node node : nodes) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", node.id());
            entry.put("x", node.x());
            entry.put("y", node.y());
            entry.put("z", node.z());
            entry.put("type", node.type());
            entry.put("placeKind", node.placeKind());
            // Left out entirely when unnamed: the page reads a missing name as "no label" and a null
            // one as a bug, and Gson would drop the null anyway.
            if (node.name() != null && !node.name().isBlank()) {
                entry.put("name", node.name());
            }
            nodeList.add(entry);
        }
        root.put("nodes", nodeList);

        List<Map<String, Object>> segmentList = new ArrayList<>(segments.size());
        for (Segment segment : segments) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", segment.id());
            entry.put("roadClass", segment.roadClass());
            entry.put("y", segment.y());
            entry.put("layer", segment.layer());
            entry.put("from", segment.from());
            entry.put("to", segment.to());
            entry.put("direction", segment.direction());
            if (segment.name() != null && !segment.name().isBlank()) {
                entry.put("name", segment.name());
            }
            int[] flat = segment.points();
            List<int[]> points = new ArrayList<>(flat.length / 2);
            for (int i = 0; i + 1 < flat.length; i += 2) {
                points.add(new int[]{flat[i], flat[i + 1]});
            }
            entry.put("points", points);
            segmentList.add(entry);
        }
        root.put("segments", segmentList);

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("nodes", nodes.size());
        stats.put("segments", segments.size());
        stats.put("lengthBlocks", lengthBlocks);
        List<Integer> layerList = new ArrayList<>(layers.length);
        for (int layer : layers) {
            layerList.add(layer);
        }
        stats.put("layers", layerList);
        root.put("stats", stats);

        return GSON.toJson(root);
    }

    /** A road class's colour as the page's {@code #RRGGBB}: the alpha in the ARGB int is dropped. */
    private static String hex(int argb) {
        return String.format("#%06X", argb & 0xFFFFFF);
    }

    /**
     * The serialiser for the payload.
     *
     * <p>{@code disableHtmlEscaping} keeps Chinese names readable in the JSON a player may well open
     * in a browser tab of its own -- the payload is fetched, never embedded in a page, so there is
     * nothing for HTML escaping to protect here.
     */
    private static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .create();
}
