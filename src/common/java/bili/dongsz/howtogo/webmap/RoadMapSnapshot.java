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
 * A copy, not the network: the network belongs to the client thread and the browser is served by another, so handing the live graph over would iterate a HashMap while the editor mutates it. Everything is flattened into arrays nobody can mutate, and the JSON is built from those.
 * It carries what the page needs and nothing else: class colours come from RoadClass or from the configured colours handed in, never written down again in JavaScript.
 * The JSON is a contract checked on both sides (tools/routing-harness asserts the key set of a fixture, tools/webmap-test feeds the same shape through the page's parser), so adding a field is a two-place change.
 * Places travel beside the roads because a place has an owner, tags and a description the network knows nothing about, and a waypoint may be nowhere near a road; the array is built on the game's thread from every destination source.
 * theme() is the one field that is not a fact about the roads: the page's palette, resolved by the caller on the game's thread and handed in as a word, like the colours.
 */
public final class RoadMapSnapshot {

    /**
     * Bumped when a field's meaning changes; the page reads it to refuse a payload it cannot use. Version 3 added places (tags, favourite flag, one-line description) and removed nothing.
     */
    public static final int FORMAT_VERSION = 3;

    /**
     * The white style, as the page spells it in its data-theme attribute. The words are the page's own, not a boolean, because the field is a hint and the page's vocabulary decides what they mean.
     */
    public static final String LIGHT_THEME = "light";

    /**
     * The black style. Also the answer for a caller with no theme to give, as the in-game theme gives when no world is loaded.
     */
    public static final String DARK_THEME = "dark";

    /** One graph vertex, flattened. */
    public record Node(int id, int x, int y, int z, String type, String placeKind, String name) {
    }

    /**
     * One piece of road, flattened. points is the vertices interleaved as x, z, x, z -- the network's own layout; the writer turns it into [[x,z],...] and nothing else reads it.
     * curve is the smoothed display line in the same layout, or null when the road has none: the page draws it, while points stays what is hovered, labelled and measured -- display geometry never decides anything.
     * source names the read-only layer the segment came from (ROADWEAVER, CREATE, MTR, ADDON), or null for a road the player drew.
     */
    public record Segment(int id, String roadClass, int y, int layer, int from, int to,
                          String direction, String name, int[] points, double[] curve, String source) {
    }

    /**
     * One place, flattened for the page -- not a road, and not a graph vertex either.
     * id is the source-stable id (PlaceKeys.stableId) the /api/place endpoint is asked about; source is the id of the DestinationSource, so the page can badge it; kind is a PlaceKind name the marker's colour comes from.
     * description is the cut payload form -- a couple of hundred characters with line breaks kept -- because the detail card fetches the rest on demand; descriptionTruncated decides whether it offers the full-text button.
     * The constructor fills in a missing name, kind or description rather than failing: one addon returning a null must not stop the page drawing the map.
     */
    public record Place(String id, String name, int x, int y, int z, String kind, String source,
                        List<String> tags, boolean favorite, String description,
                        boolean descriptionTruncated) {

        public Place {
            id = id == null ? "" : id;
            name = name == null ? "" : name;
            kind = kind == null || kind.isBlank() ? "PLACE" : kind;
            source = source == null ? "" : source;
            // Null tags are dropped rather than thrown on: one bad element should cost its chip, not the whole map.
            tags = tags == null ? List.of() : tags.stream().filter(Objects::nonNull).toList();
            description = description == null ? "" : description;
        }
    }

    /**
     * One read-only layer to fold into the map: its source id and its segments. Handed in by the caller like the colours, since the layers live in the client's own stores; the segments are copied, so the caller keeps ownership of the live network.
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
     * Which palette the page is to draw in: LIGHT_THEME or DARK_THEME, never anything else -- the factories reduce whatever the caller says to one of the two words.
     */
    private final String theme;
    private final Bounds bounds;
    private final List<Node> nodes;
    private final List<Segment> segments;
    /**
     * Places to mark, flattened from the destination sources on the game's thread. Never null; empty means the page falls back to marking the POI nodes it was sent.
     */
    private final List<Place> places;
    private final int[] layers;
    private final double lengthBlocks;
    private final boolean empty;
    /**
     * The colour to draw each class in, or nothing for the class's own default. Handed in because the colour lives in the client config and this class knows roads and JSON, not Minecraft; the caller reads the config on the game thread.
     */
    private final Map<RoadClass, Integer> classColours;

    private RoadMapSnapshot(String world, String dimension, String theme, Bounds bounds,
                            List<Node> nodes, List<Segment> segments, List<Place> places, int[] layers,
                            double lengthBlocks, boolean empty,
                            Map<RoadClass, Integer> classColours) {
        this.world = world;
        this.dimension = dimension;
        this.theme = normaliseTheme(theme);
        this.bounds = bounds;
        this.nodes = List.copyOf(nodes);
        this.segments = List.copyOf(segments);
        this.places = List.copyOf(places);
        this.layers = layers.clone();
        this.lengthBlocks = lengthBlocks;
        this.empty = empty;
        this.classColours = Map.copyOf(classColours);
    }

    /**
     * Flattens a network. Must be called on the thread that owns the network, as this walks it.
     * @param dimension the dimension id, e.g. minecraft:overworld
     */
    public static RoadMapSnapshot of(String world, String dimension, RoadNetwork network) {
        return of(world, dimension, network, Map.of());
    }

    /**
     * Flattens a network, with the colours the page should draw each class in. Must be called on the thread that owns the network.
     * The page is told the dark style: a caller that states no theme is one with no game to ask (a demo server, a self-test with no world), and dark is what the in-game theme gives then.
     * @param classColours the ARGB colour per class; a class left out keeps its own default
     */
    public static RoadMapSnapshot of(String world, String dimension, RoadNetwork network,
                                     Map<RoadClass, Integer> classColours) {
        return of(world, dimension, network, classColours, List.of(), DARK_THEME);
    }

    /**
     * Flattens a network together with the read-only layers the game's world map draws over it; a world whose only roads came from another mod would otherwise show an empty browser map. Layers are folded into the same segments list, with source saying where each came from, and nothing on the page can edit them -- their status in the game too.
     * Must be called on the thread that owns the networks. The page is told the dark style.
     * @param readOnlyLayers layers to fold in, in draw order (first under, last on top)
     */
    public static RoadMapSnapshot of(String world, String dimension, RoadNetwork network,
                                     Map<RoadClass, Integer> classColours,
                                     List<ReadOnlyLayer> readOnlyLayers) {
        return of(world, dimension, network, classColours, readOnlyLayers, DARK_THEME);
    }

    /**
     * Flattens a network, with the class colours and the palette to draw the page in. This is the form the running game uses: WebMapService reads the theme on the client thread, beside the colours, because the sky, the dimension and the config are questions only that thread may answer.
     * Must be called on the thread that owns the network.
     * @param theme LIGHT_THEME or DARK_THEME; null or an unknown word is the dark style
     */
    public static RoadMapSnapshot of(String world, String dimension, RoadNetwork network,
                                     Map<RoadClass, Integer> classColours,
                                     List<ReadOnlyLayer> readOnlyLayers, String theme) {
        return of(world, dimension, network, classColours, readOnlyLayers, theme, List.of());
    }

    /**
     * Flattens a network together with the places the browser map is to mark. The list is built on the game's thread from Destinations and PlaceDetailStore, the same sources the picker lists, so the two surfaces cannot disagree.
     * Places count towards the bounds even when no road reaches them: the page fits its view to the box it is handed, so a marker outside it has to be hunted for. A world with places but no roads is therefore not empty.
     * Must be called on the thread that owns the network.
     * @param places the places to mark, each already flattened; may be null or empty
     */
    public static RoadMapSnapshot of(String world, String dimension, RoadNetwork network,
                                     Map<RoadClass, Integer> classColours,
                                     List<ReadOnlyLayer> readOnlyLayers, String theme,
                                     List<Place> places) {
        Objects.requireNonNull(network, "network");
        List<Place> placeList = places == null
                ? List.of()
                : places.stream().filter(Objects::nonNull).toList();
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
            // Nodes count towards the extent even when no road joins them: a map of nothing but places is still a map, and leaving them out would open the page zoomed to a zero-size box.
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

        // Read-only layers last, so the page's paint order matches the world map's: the player's own roads under, layers read from other mods over them. Their ids are negative so a browser tab can never mistake one for an editor-network road it could ask about.
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

        // Places last, and into the extent as well: a waypoint from another mod may be nowhere near a road, and the page fits its view to the box it is handed.
        for (Place place : placeList) {
            minX = Math.min(minX, place.x());
            maxX = Math.max(maxX, place.x());
            minZ = Math.min(minZ, place.z());
            maxZ = Math.max(maxZ, place.z());
        }

        boolean nothing = nodes.isEmpty() && segments.isEmpty() && placeList.isEmpty();
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
                theme, bounds, nodes, segments, placeList, layerArray, length, nothing, colours);
    }

    /**
     * A theme name reduced to one of the two words the page knows. Anything that is not light becomes dark -- no answer, a null, a word a newer version added -- which is the in-game answer with no world loaded and the page's own default.
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

    /** The largest x (or z). See extentMin. */
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

    /** The same, with the palette the page is to draw the empty map in; see of. */
    public static RoadMapSnapshot none(String world, String dimension,
                                       Map<RoadClass, Integer> classColours, String theme) {
        return new RoadMapSnapshot(world == null ? "" : world,
                dimension == null || dimension.isBlank() ? "unknown" : dimension,
                theme, new Bounds(0, 0, 0, 0), List.of(), List.of(), List.of(), new int[0], 0.0, true,
                classColours == null ? Map.of() : classColours);
    }

    public String world() {
        return world;
    }

    public String dimension() {
        return dimension;
    }

    /**
     * The palette the page is to draw in: LIGHT_THEME or DARK_THEME, never null. Resolved before this snapshot existed -- see of -- so the JSON writer never has to ask.
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

    /**
     * The places the page is to mark, each with tags, favourite flag and one-line description. Empty for a reading with no destination sources, which is what the page's POI fallback exists for.
     */
    public List<Place> places() {
        return places;
    }

    public int nodeCount() {
        return nodes.size();
    }

    public int segmentCount() {
        return segments.size();
    }

    public int placeCount() {
        return places.size();
    }

    /** Every storey any road is on, ascending, for the page's layer switches. */
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
     * The payload, as the documented contract. Built as an ordered map rather than DTO classes so the key order is the documented, stable one, and serialised by Gson so a name with a quote, a backslash or a newline cannot produce unparseable JSON.
     * @param generatedAt epoch milliseconds the reading was taken
     */
    public String toJson(long generatedAt) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", FORMAT_VERSION);
        root.put("generatedAt", generatedAt);
        root.put("world", world);
        root.put("dimension", dimension);
        root.put("empty", empty);
        // Always present, even though the page tolerates its absence: a field that is sometimes omitted is one every reader has to branch on.
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
            // Left out entirely when unnamed: the page reads a missing name as no label and a null one as a bug.
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

        // Always present, like every other list here: the page reads an absent list as a payload from before places existed and falls back to POI nodes, while an empty one means this server has no places. Different answers, so the field is never omitted.
        List<Map<String, Object>> placeList = new ArrayList<>(places.size());
        for (Place place : places) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", place.id());
            entry.put("name", place.name());
            entry.put("x", place.x());
            entry.put("y", place.y());
            entry.put("z", place.z());
            entry.put("kind", place.kind());
            entry.put("source", place.source());
            entry.put("tags", place.tags());
            entry.put("favorite", place.favorite());
            entry.put("description", place.description());
            entry.put("descriptionTruncated", place.descriptionTruncated());
            placeList.add(entry);
        }
        root.put("places", placeList);

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

    /** A road class's colour as #RRGGBB; the alpha in the ARGB int is dropped. */
    private static String hex(int argb) {
        return String.format("#%06X", argb & 0xFFFFFF);
    }

    /**
     * The serialiser for the payload. disableHtmlEscaping keeps names readable in JSON a player may open in a tab; the payload is fetched, never embedded in a page, so there is nothing for HTML escaping to protect.
     */
    private static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .create();
}
