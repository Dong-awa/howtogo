package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.HowToGo;
import bili.dongsz.howtogo.api.PointKind;
import bili.dongsz.howtogo.api.RoadDataSource;
import bili.dongsz.howtogo.api.RoadDataSources;
import bili.dongsz.howtogo.api.RoadDataWriter;
import bili.dongsz.howtogo.road.RoadClass;
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
 * The road layers addons have contributed, kept apart from the player's roads.
 *
 * <h2>Why a store of its own</h2>
 * An addon's points and lines are read out of a {@link RoadDataSource} and cannot be written back to
 * it, so they live in a network this class owns. That network is not {@link RoadStore}'s: it is never
 * loaded from or saved to the player's file, it never appears in the editor, and nothing the player
 * does can change it. A mod that could add to the player's roads could also silently move them, and a
 * layer an addon contributes is a statement about the world rather than a road the player may fix.
 *
 * <h2>When a layer is read</h2>
 * On the client tick, by comparing {@link RoadDataSource#version()} with the number read beside the
 * layer currently held. Equal means nothing happens: no copy, no allocation, no walk. Different means
 * the source is asked to state its layer again from scratch, for the dimension the player is in --
 * read per dimension rather than once, because coordinates mean nothing across dimensions and a
 * source is free to have nothing to say in most of them.
 *
 * <h2>Failure</h2>
 * A source that throws while stating its layer is reported and its previous layer is kept: one
 * addon's bad day does not empty the map. The report is per re-read rather than per tick, so a broken
 * source is not a log flood.
 *
 * <h2>Threads</h2>
 * {@link #tick()} and every reader are on the client thread -- the tick that reads the sources and the
 * render pass that draws them are the same thread, so the flat list handed to the map cannot be
 * rewritten underneath it. Registration, which is the only thing an addon does, is safe from any
 * thread; see {@link RoadDataSources}.
 */
public final class AddonRoadStore {

    /**
     * The layers held, by source id and then by dimension.
     *
     * <p>Concurrent because registration and its log lines can happen on any thread while this is
     * being read; the values are only ever replaced whole, never mutated, so a reader that saw a layer
     * sees a complete one.
     */
    private static final Map<String, Map<String, Layer>> LAYERS = new ConcurrentHashMap<>();

    /** The dimension the flat list below was built for, or null when no world is loaded. */
    private static String boundDimension;

    /** The flat list the map draws: every source's segments for the bound dimension, in order. */
    private static volatile List<RoadSegment> segments = List.of();

    /**
     * Every source's vertices for the bound dimension, in the same order as {@link #segments}.
     *
     * <p>Kept beside the segments rather than walked out of them because a point need not be on a
     * line: a landmark an addon states is a place with a name and a kind, and it has to be drawn --
     * marker and label -- whether or not anything is attached to it.
     */
    private static volatile List<RoadNode> nodes = List.of();

    /** Bumped whenever {@link #segments} is rebuilt, for anything caching a reading of it. */
    private static volatile int revision;

    private AddonRoadStore() {
    }

    /**
     * Re-reads any source whose version has moved, and rebinds when the world or dimension changes.
     *
     * <p>Called every client tick. The steady-state cost is one {@code version()} call per registered
     * source and one reference comparison -- there are no allocations and no copying on the path where
     * nothing changed, which is every tick of a normal session.
     */
    public static void tick() {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            if (boundDimension != null) {
                // Left the world: the layers describe a place that is no longer loaded, and holding
                // them would keep the coordinates of a world the player is not in.
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
     * The segments to draw, for the dimension the player is in. Empty when no world is loaded.
     *
     * <p>The list is this class's own and is replaced whole when a layer is re-read, so a caller may
     * hold on to it for the duration of one pass but must ask again next pass. The segments in it are
     * the layers' live geometry: a caller draws them and writes nothing to them.
     */
    public static List<RoadSegment> segments() {
        return segments;
    }

    /**
     * The points to draw, for the dimension the player is in. Empty when no world is loaded.
     *
     * <p>A point is drawn whether or not a line touches it: this is where a landmark an addon states
     * gets its marker and its name. Same ownership and same lifetime as {@link #segments()} -- the
     * list is replaced whole, the nodes in it are the layers' live geometry.
     */
    public static List<RoadNode> nodes() {
        return nodes;
    }

    /** Bumped whenever the drawn layer changes, for a caller caching something derived from it. */
    public static int revision() {
        return revision;
    }

    /** Asks for the next tick to read every source again, whatever their versions say. */
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

    /** Reads one source, or returns null when it failed and its previous layer should stand. */
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
     * Collects one source's stated layer into a network.
     *
     * <p>Ids are the source's own strings and are translated here into the network's integer ids. A
     * repeated point id, a repeated line id or a line naming a point that was never written is a
     * mistake in the source: it is thrown rather than absorbed, and the source's previous layer is
     * kept, because the alternative is a map that quietly shows something nobody stated.
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
            // From-point to to-point is the one-way direction, which is why the writer's parameters
            // are named for the two ends rather than for the direction: an addon states which way
            // traffic runs, and the geometry is the same either way.
            segment.setDirection(oneWay ? RoadDirection.FORWARD : RoadDirection.TWO_WAY);
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
