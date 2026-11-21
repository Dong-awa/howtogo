package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.HowToGo;
import bili.dongsz.howtogo.api.PointKind;
import bili.dongsz.howtogo.api.RoadDataSource;
import bili.dongsz.howtogo.api.RoadDataSources;
import bili.dongsz.howtogo.api.RoadDataWriter;
import bili.dongsz.howtogo.road.RoadClass;
import bili.dongsz.howtogo.road.RoadCurve;
import bili.dongsz.howtogo.road.RoadDirection;
import bili.dongsz.howtogo.road.RoadNetwork;
import bili.dongsz.howtogo.road.RoadNode;
import bili.dongsz.howtogo.road.RoadSegment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The road layers addons contribute, kept apart from the player's roads: read out of a
 * RoadDataSource, never saved, invisible to the editor.
 * Re-read on the client tick when the source's version() differs from the held layer's; the steady
 * state is one call per source per tick. A source that throws is reported once per re-read and
 * keeps its previous layer.
 * tick() and every reader are on the client thread; registration is safe from any thread.
 */
public final class AddonRoadStore {

    /**
     * Layers by source id, then by dimension; values are replaced whole, so a reader sees a complete
     * layer.
     */
    private static final Map<String, Map<String, Layer>> LAYERS = new ConcurrentHashMap<>();

    /** The dimension the flat lists below were built for, or null when no world is loaded. */
    private static String boundDimension;

    /** The flat list the map draws: every source's segments for the bound dimension, in order. */
    private static volatile List<RoadSegment> segments = List.of();

    /** Every source's vertices for the bound dimension; a landmark needs a marker even with no line. */
    private static volatile List<RoadNode> nodes = List.of();

    /** Bumped whenever segments is rebuilt, for anything caching a reading of it. */
    private static volatile int revision;

    private AddonRoadStore() {
    }

    /**
     * Re-reads any source whose version moved, and rebinds when the world or dimension changes. Called
     * every client tick; the unchanged path allocates and copies nothing.
     */
    public static void tick() {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            if (boundDimension != null) {
                // Leaving the world: the layers describe a place that is no longer loaded.
                boundDimension = null;
                LAYERS.clear();
                segments = List.of();
                nodes = List.of();
                revision++;
            }
            return;
        }

        String dimension = level.dimension().location().toString();
        boolean rebind = !dimension.equals(boundDimension);
        if (rebind) {
            boundDimension = dimension;
        }

        List<RoadDataSources.Entry> entries = RoadDataSources.all();
        if (entries.isEmpty() && !rebind) {
            return;
        }

        boolean changed = rebind;
        for (RoadDataSources.Entry entry : entries) {
            Map<String, Layer> byDimension =
                    LAYERS.computeIfAbsent(entry.id(), ignored -> new ConcurrentHashMap<>());
            Layer held = byDimension.get(dimension);

            int version;
            try {
                version = entry.source().version();
            } catch (Throwable broken) {
                HowToGo.LOGGER.warn("[HowToGo] api | road data source '{}' failed to report a version; "
                        + "its layer is left as it was: {}", entry.id(), broken.toString());
                continue;
            }
            if (held != null && held.version() == version) {
                continue;
            }

            RoadNetwork built = read(entry, dimension);
            if (built == null) {
                continue;
            }
            byDimension.put(dimension, new Layer(version, built));
            changed = true;
        }

        if (changed) {
            flatten(entries, dimension);
        }
    }

    /**
     * Segments to draw for the player's dimension; empty when no world is loaded. Replaced whole on
     * re-read, so ask again each pass; the geometry is live and read-only.
     */
    public static List<RoadSegment> segments() {
        return segments;
    }

    /** Points to draw for the player's dimension, landmark included; same ownership as segments(). */
    public static List<RoadNode> nodes() {
        return nodes;
    }

    /** Bumped whenever the drawn layer changes, for callers caching something derived from it. */
    public static int revision() {
        return revision;
    }

    /** Asks the next tick to read every source again, whatever its version says. */
    public static void invalidate() {
        LAYERS.clear();
        segments = List.of();
        nodes = List.of();
        revision++;
        boundDimension = null;
    }

    /** One source's layer for one dimension, with the version it was read at. */
    private record Layer(int version, RoadNetwork network) {
    }

    /** Reads one source, or null when it failed and its previous layer should stand. */
    private static RoadNetwork read(RoadDataSources.Entry entry, String dimension) {
        Builder builder = new Builder();
        try {
            entry.source().write(builder, dimension);
        } catch (Throwable failed) {
            HowToGo.LOGGER.warn("[HowToGo] api | road data source '{}' failed to state its layer in {}; "
                    + "its previous layer is kept: {}", entry.id(), dimension, failed.toString());
            return null;
        }
        return builder.network();
    }

    /** Rebuilds the flat lists for a dimension from the layers held. */
    private static void flatten(List<RoadDataSources.Entry> entries, String dimension) {
        List<RoadSegment> rebuiltSegments = new ArrayList<>();
        List<RoadNode> rebuiltNodes = new ArrayList<>();
        for (RoadDataSources.Entry entry : entries) {
            Map<String, Layer> byDimension = LAYERS.get(entry.id());
            if (byDimension == null) {
                continue;
            }
            Layer layer = byDimension.get(dimension);
            if (layer == null) {
                continue;
            }
            rebuiltSegments.addAll(layer.network().segments());
            rebuiltNodes.addAll(layer.network().nodes());
        }
        segments = Collections.unmodifiableList(rebuiltSegments);
        nodes = Collections.unmodifiableList(rebuiltNodes);
        revision++;
    }

    /**
     * Collects one source's stated layer into a network; source string ids become integer ids. A
     * repeated id or a line naming an unwritten point is a source bug and is thrown.
     */
    private static final class Builder implements RoadDataWriter {

        private final RoadNetwork network = new RoadNetwork();
        private final Map<String, Integer> points = new HashMap<>();
        private final Set<String> lines = new HashSet<>();

        @Override
        public void point(String id, int x, int y, int z, String name, PointKind kind) {
            String key = requireId(id, "point");
            if (points.containsKey(key)) {
                throw new IllegalArgumentException("point id '" + key + "' was written twice");
            }
            if (kind == null) {
                throw new IllegalArgumentException("point '" + key + "' has no kind");
            }
            RoadNode node = network.addNode(x, y, z, kind.nodeType(), name);
            if (kind.placeKind() != null) {
                node.setPlaceKind(kind.placeKind());
            }
            points.put(key, node.id());
        }

        @Override
        public void line(String id, String fromPoint, String toPoint, RoadClass roadClass, int layer,
                         boolean oneWay) {
            String key = requireId(id, "line");
            if (!lines.add(key)) {
                throw new IllegalArgumentException("line id '" + key + "' was written twice");
            }
            RoadNode from = endpoint(fromPoint, key, "from");
            RoadNode to = endpoint(toPoint, key, "to");
            if (from.id() == to.id()) {
                throw new IllegalArgumentException("line '" + key + "' joins point '" + fromPoint
                        + "' to itself");
            }
            if (roadClass == null) {
                throw new IllegalArgumentException("line '" + key + "' has no road class");
            }
            RoadSegment segment = network.newSegment(roadClass, from.y(), 2);
            segment.addVertex(from.x(), from.z());
            segment.addVertex(to.x(), to.z());
            segment.setFromNode(from.id());
            segment.setToNode(to.id());
            segment.setLayer(layer);
            // From-point to to-point is the forward direction; parameters are named for the ends so the
            // geometry reads independently of traffic direction.
            segment.setDirection(oneWay ? RoadDirection.FORWARD : RoadDirection.TWO_WAY);
            // Every read-only layer attaches its own display line, so the maps never have to guess: a line
            // the API states as two points is its own curve, and a later writer that states more keeps the
            // same treatment without any drawing code changing. See RoadCurve.
            segment.setCurve(RoadCurve.of(segment));
            network.addSegment(segment);
        }

        RoadNetwork network() {
            return network;
        }

        private RoadNode endpoint(String pointId, String lineId, String end) {
            String key = pointId == null ? null : pointId.trim();
            Integer id = key == null ? null : points.get(key);
            if (id == null) {
                throw new IllegalArgumentException("line '" + lineId + "' names " + end + "-point '"
                        + pointId + "' that was never written");
            }
            return network.node(id);
        }

        private static String requireId(String id, String what) {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("a " + what + " was written with no id");
            }
            return id.trim();
        }
    }
}
