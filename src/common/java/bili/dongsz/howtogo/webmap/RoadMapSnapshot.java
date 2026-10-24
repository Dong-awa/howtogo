package bili.dongsz.howtogo.webmap;

import bili.dongsz.howtogo.road.RoadClass;
import bili.dongsz.howtogo.road.RoadCurve;
import bili.dongsz.howtogo.road.RoadNetwork;
import bili.dongsz.howtogo.road.RoadNode;
import bili.dongsz.howtogo.road.RoadSegment;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.util.ArrayList;
import java.util.Collection;
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
 * fields the map does not draw, and the class colours taken from {@link RoadClass} -- or from the
 * configured colours handed in beside the network -- rather than written down a second time in
 * JavaScript, so a recoloured road class changes on both maps at once.
 *
 * <h2>The documented payload</h2>
 * The JSON this writes is the contract the front end is written against, and it is checked on both
 * sides: {@code tools/routing-harness} asserts the key set and the values of a fixture network, and
 * {@code tools/webmap-test} feeds a payload of the same shape through the page's own parser. Adding a
 * field is therefore a two-place change, which is the point.
 *
 * <h2>The one field that is not a fact about the roads</h2>
 * {@link #theme()} says which of the page's two palettes to draw the map in, because the browser map
 * is a second surface of this mod and has to look like the interfaces beside it. It is resolved by
 * the caller on the game's thread -- the config, the dimension and the sky are not questions an HTTP
 * thread may ask -- and handed in as a word, exactly as the class colours are handed in as numbers.
 */
public final class RoadMapSnapshot {

    /** Bumped when a field's meaning changes; the page reads it to refuse a payload it cannot use. */
    public static final int FORMAT_VERSION = 2;

    /**
     * The white style, as the page spells it in its {@code data-theme} attribute.
     *
     * <p>The words are the page's own -- "light" and "dark", the two its stylesheet and its
     * normaliser agree on -- rather than a boolean, because the field is a hint to the page and the
     * page's vocabulary is what decides what the two words mean.
     */
    public static final String LIGHT_THEME = "light";

    /**
     * The black style.
     *
     * <p>Also the answer for a caller that has no theme to give, which is the same answer the
     * in-game theme gives when no world is loaded: see {@link #of(String, String, RoadNetwork)}.
     */
    public static final String DARK_THEME = "dark";

    /** One graph vertex, flattened. */
    public record Node(int id, int x, int y, int z, String type, String placeKind, String name) {
    }

    /**
     * One piece of road, flattened.
     *
     * <p>{@code points} is the segment's vertices interleaved as x, z, x, z -- the network's own
     * layout -- because the JSON writer turns it into the nested {@code [[x,z],...]} form the page
     * wants and nothing else ever reads it.
     *
     * <p>{@code curve} is the smoothed display line in the same interleaved layout, or null when the
     * road has none. It is what the page draws; {@code points} stays what it hovers, labels and
     * measures, on the same argument as in the game: display geometry never decides anything.
     *
     * <p>{@code source} names the read-only layer a segment was read out of ({@code ROADWEAVER},
     * {@code CREATE}, {@code MTR}, {@code ADDON}), or null for a road the player drew. The browser
     * map is a view of the same map the game draws, and the game draws those layers too -- a
     * browser map that showed only the hand-drawn roads was missing the roads the world is actually
     * made of.
     */
    public record Segment(int id, String roadClass, int y, int layer, int from, int to,
                          String direction, String name, int[] points, double[] curve, String source) {
    }

    /**
     * One read-only layer to fold into the map: its source id, and the segments it holds.
     *
     * <p>Handed in by the caller for the same reason the colours are: the layers live in the client's
     * own stores, and this class is the portable half of the web map. The segments are only read, and
     * are copied into the snapshot, so the caller keeps ownership of the live network.
     */
    public record ReadOnlyLayer(String source, Collection<RoadSegment> segments) {

        public ReadOnlyLayer {
            Objects.requireNonNull(source, "source");
            segments = segments == null ? List.of() : segments;
        }
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
    /**
     * Which palette the page is to draw in, as the page spells it: {@link #LIGHT_THEME} or
     * {@link #DARK_THEME}.
     *
     * <p>Never null, and never anything else: the factories reduce whatever the caller says to one of
     * the two words, so the page has one branch and the payload has one possible shape.
     */
    private final String theme;
    private final Bounds bounds;
    private final List<Node> nodes;
    private final List<Segment> segments;
    private final int[] layers;
    private final double lengthBlocks;
    private final boolean empty;
    /**
     * The colour the page is to draw each class in, or nothing for the class's own default.
     *
     * <p>Handed in rather than read here: the colour a player chose lives in the client config, and
     * this class is the portable half of the web map -- it knows about roads and JSON and nothing
     * about Minecraft or the loader. The caller reads the config on the game thread and passes the
     * answer, so the flattening is still a pure function of what it is given and the HTTP thread
     * never asks a config question of its own.
     */
    private final Map<RoadClass, Integer> classColours;

    private RoadMapSnapshot(String world, String dimension, String theme, Bounds bounds,
                            List<Node> nodes, List<Segment> segments, int[] layers,
                            double lengthBlocks, boolean empty,
                            Map<RoadClass, Integer> classColours) {
        this.world = world;
        this.dimension = dimension;
        this.theme = normaliseTheme(theme);
        this.bounds = bounds;
        this.nodes = List.copyOf(nodes);
        this.segments = List.copyOf(segments);
        this.layers = layers.clone();
        this.lengthBlocks = lengthBlocks;
        this.empty = empty;
        this.classColours = Map.copyOf(classColours);
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
        return of(world, dimension, network, Map.of());
    }

    /**
     * Flattens a network, with the colours the page should draw each class in.
     *
     * <p>Must be called on the thread that owns the network: this walks it. The result carries no
     * reference to it.
     *
     * <p>The page is told the dark style. A caller that does not state a theme is one with no game
     * to ask -- the standalone demo server, or a self-test that has no world loaded -- and dark is
     * the same answer the in-game theme gives in that situation; see
     * {@link #of(String, String, RoadNetwork, Map, List, String)} for the form that carries a real
     * answer.
     *
     * @param world        the world the data belongs to, for the page's own title; may be blank
     * @param dimension    the dimension id, e.g. {@code minecraft:overworld}
     * @param classColours the ARGB colour to draw each class in; a class left out keeps its own
     *                     default, so an empty map is the ordinary reading
     */
    public static RoadMapSnapshot of(String world, String dimension, RoadNetwork network,
                                     Map<RoadClass, Integer> classColours) {
        return of(world, dimension, network, classColours, List.of(), DARK_THEME);
    }

    /**
     * Flattens a network together with the read-only layers the game's world map draws over it.
     *
     * <p>The auto-recognised roads are the map: a world whose only roads were made by another mod is
     * mostly those layers, and a browser map that read the player's own network alone showed an empty
     * map for it. They are folded into the same {@code segments} list because on the map they are the
     * same thing -- lines of a road class, drawn in that class's colour -- with a {@code source} field
     * to say where each came from. Nothing on the page can edit them, which is exactly their status in
     * the game as well.
     *
     * <p>Must be called on the thread that owns the networks: this walks them. The result carries no
     * reference to any of them.
     *
     * <p>The page is told the dark style, for the reason given on
     * {@link #of(String, String, RoadNetwork, Map)}.
     *
     * @param readOnlyLayers layers to fold in, in draw order (first under, last on top); may be empty
     */
    public static RoadMapSnapshot of(String world, String dimension, RoadNetwork network,
                                     Map<RoadClass, Integer> classColours,
                                     List<ReadOnlyLayer> readOnlyLayers) {
        return of(world, dimension, network, classColours, readOnlyLayers, DARK_THEME);
    }

    /**
     * Flattens a network, with the colours to draw each class in and the palette to draw the page in.
     *
     * <p>This is the form the running game uses: {@code WebMapService} reads the theme on the
     * client thread, beside the colours and for the same reason, and hands the answer in as one of
     * the two words above. Nothing is resolved here -- whether the sky is dark, whether the dimension
     * has a sky at all, and what the player chose in the config are all questions only the game's
     * thread may answer.
     *
     * <p>Must be called on the thread that owns the network: this walks it. The result carries no
     * reference to it.
     *
     * @param theme the palette the page is to draw in, {@link #LIGHT_THEME} or {@link #DARK_THEME};
     *              null or an unknown word is the dark style
     */
    public static RoadMapSnapshot of(String world, String dimension, RoadNetwork network,
                                     Map<RoadClass, Integer> classColours,
                                     List<ReadOnlyLayer> readOnlyLayers, String theme) {
        Objects.requireNonNull(network, "network");
        Map<RoadClass, Integer> colours = classColours == null ? Map.of() : classColours;
        List<ReadOnlyLayer> extraLayers = readOnlyLayers == null ? List.of() : readOnlyLayers;

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
            Segment flattened = flatten(segment, segment.id(), segment.fromNode(), segment.toNode(),
                    null);
            segments.add(flattened);
            int[] points = flattened.points();
            minX = Math.min(minX, extentMin(points, 0, minX));
            maxX = Math.max(maxX, extentMax(points, 0, maxX));
            minZ = Math.min(minZ, extentMin(points, 1, minZ));
            maxZ = Math.max(maxZ, extentMax(points, 1, maxZ));
            layers.add(segment.layer());
            length += segment.length();
        }

        // Read-only layers last, so the page's paint order matches the world map's: the player's own
        // roads under, the layers read from other mods over them. Their ids are made negative: they
        // are not the editor network's segments, and a browser tab must never mistake one for a road
        // it could ask about by the same number.
        int nextReadOnlyId = -1;
        for (ReadOnlyLayer layer : extraLayers) {
            if (layer == null || layer.segments() == null) {
                continue;
            }
            for (RoadSegment segment : layer.segments()) {
                if (segment == null || segment.vertexCount() < 2) {
                    continue;
                }
                Segment flattened = flatten(segment, nextReadOnlyId--, -1, -1, layer.source());
                segments.add(flattened);
                int[] points = flattened.points();
                minX = Math.min(minX, extentMin(points, 0, minX));
                maxX = Math.max(maxX, extentMax(points, 0, maxX));
                minZ = Math.min(minZ, extentMin(points, 1, minZ));
                maxZ = Math.max(maxZ, extentMax(points, 1, maxZ));
                double[] curve = flattened.curve();
                if (curve != null) {
                    for (int i = 0; i + 1 < curve.length; i += 2) {
                        minX = Math.min(minX, (int) Math.floor(curve[i]));
                        maxX = Math.max(maxX, (int) Math.ceil(curve[i]));
                        minZ = Math.min(minZ, (int) Math.floor(curve[i + 1]));
                        maxZ = Math.max(maxZ, (int) Math.ceil(curve[i + 1]));
                    }
                }
                layers.add(segment.layer());
                length += segment.length();
            }
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
                theme, bounds, nodes, segments, layerArray, length, nothing, colours);
    }

    /**
     * A theme name reduced to one of the two words the page knows.
     *
     * <p>Anything that is not "light" becomes the dark style: a caller with no answer, a null, a
     * word a newer version might have added. That is the same answer the in-game theme gives when no
     * world is loaded, and it is the page's own default, so a payload carrying it can never be the
     * reason a page comes up in a palette the player did not expect.
     */
    private static String normaliseTheme(String theme) {
        return theme != null && LIGHT_THEME.equalsIgnoreCase(theme.trim())
                ? LIGHT_THEME
                : DARK_THEME;
    }

    /** One segment as the page receives it, with its display curve when it has one. */
    private static Segment flatten(RoadSegment segment, int id, int from, int to, String source) {
        int count = segment.vertexCount();
        int[] points = new int[count * 2];
        for (int i = 0; i < count; i++) {
            points[i * 2] = segment.x(i);
            points[i * 2 + 1] = segment.z(i);
        }
        RoadCurve curve = segment.curve();
        double[] flattenedCurve = null;
        if (curve != null && curve.size() >= 2) {
            flattenedCurve = new double[curve.size() * 2];
            for (int i = 0; i < curve.size(); i++) {
                flattenedCurve[i * 2] = curve.x(i);
                flattenedCurve[i * 2 + 1] = curve.z(i);
            }
        }
        return new Segment(id, segment.roadClass().name(), segment.y(), segment.layer(),
                from, to, segment.direction().name(), segment.name(), points, flattenedCurve,
                source);
    }

    /** The smallest x (or z) in an interleaved point array, or the current bound when empty. */
    private static int extentMin(int[] points, int offset, int fallback) {
        int best = fallback;
        for (int i = offset; i < points.length; i += 2) {
            best = Math.min(best, points[i]);
        }
        return best;
    }

    /** The largest x (or z). See {@link #extentMin}. */
    private static int extentMax(int[] points, int offset, int fallback) {
        int best = fallback;
        for (int i = offset; i < points.length; i += 2) {
            best = Math.max(best, points[i]);
        }
        return best;
    }

    /** An empty map for the given dimension: what a world with no roads drawn yet looks like. */
    public static RoadMapSnapshot none(String world, String dimension) {
        return none(world, dimension, Map.of());
    }

    /** The same, with the colours the legend is to be drawn in. */
    public static RoadMapSnapshot none(String world, String dimension,
                                       Map<RoadClass, Integer> classColours) {
        return none(world, dimension, classColours, DARK_THEME);
    }

    /** The same, with the palette the page is to draw the empty map in; see {@link #of}. */
    public static RoadMapSnapshot none(String world, String dimension,
                                       Map<RoadClass, Integer> classColours, String theme) {
        return new RoadMapSnapshot(world == null ? "" : world,
                dimension == null || dimension.isBlank() ? "unknown" : dimension,
                theme, new Bounds(0, 0, 0, 0), List.of(), List.of(), new int[0], 0.0, true,
                classColours == null ? Map.of() : classColours);
    }

    public String world() {
        return world;
    }

    public String dimension() {
        return dimension;
    }

    /**
     * The palette the page is to draw in, as the page spells it: {@link #LIGHT_THEME} or
     * {@link #DARK_THEME}, never null.
     *
     * <p>Resolved from the config, the dimension and the sky before this snapshot existed; see
     * {@link #of(String, String, RoadNetwork, Map, List, String)} for why it is carried rather than
     * looked up by whoever is writing the JSON.
     */
    public String theme() {
        return theme;
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
        // Always present, even though the page tolerates its absence: this build always has an
        // answer, and a field that is sometimes omitted is a field every reader has to branch on.
        root.put("theme", theme);

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
            Integer configured = classColours.get(roadClass);
            entry.put("color", hex(configured == null ? roadClass.color() : configured));
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
            if (segment.source() != null) {
                entry.put("source", segment.source());
            }
            int[] flat = segment.points();
            List<int[]> points = new ArrayList<>(flat.length / 2);
            for (int i = 0; i + 1 < flat.length; i += 2) {
                points.add(new int[]{flat[i], flat[i + 1]});
            }
            entry.put("points", points);
            double[] curve = segment.curve();
            if (curve != null && curve.length >= 4) {
                List<double[]> display = new ArrayList<>(curve.length / 2);
                for (int i = 0; i + 1 < curve.length; i += 2) {
                    display.add(new double[]{curve[i], curve[i + 1]});
                }
                entry.put("curve", display);
            }
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
