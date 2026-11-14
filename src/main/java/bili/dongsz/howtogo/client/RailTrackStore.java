package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.HowToGo;
import bili.dongsz.howtogo.RoadConfig;
import bili.dongsz.howtogo.road.RoadClass;
import bili.dongsz.howtogo.road.RoadCurve;
import bili.dongsz.howtogo.road.RoadNetwork;
import bili.dongsz.howtogo.road.RoadNode;
import bili.dongsz.howtogo.road.RoadSegment;
import bili.dongsz.howtogo.route.RoutePreferences;
import bili.dongsz.howtogo.route.TravelMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Create's train tracks, read out of loaded chunks as a read-only rail layer.
 *
 * A track block is recognised by registry name (create:track by default), never by a Create class,
 * so no Create is loaded and a rename cannot break it. Its own axes decide the joins: a block
 * joins along each axis end and the neighbour must reach back. An unknown shape reaches
 * everywhere, so a new shape cannot make a line vanish.
 *
 * Create's graph is preferred; a block scan is the fallback. Track between junctions becomes one
 * polyline per run, as a plain RoadNetwork of RoadClass#RAIL.
 *
 * Never saved: forRouting returns a copy with it folded in. One batch a second on the client tick.
 */
public final class RailTrackStore {

    /**
     * Node and segment ids for this layer start here.
     * Made disjoint from the saved network's ids so a wrong-network lookup returns nothing, not a road.
     */
    private static final int ID_BASE = 1_000_000_000;

    private static final int INTERVAL_TICKS = 20;

    private static final int RESCAN_TICKS = 200;

    private static final int ID_REFRESH_BATCHES = 5;

    /**
     * Batches between unconditional re-reads of Create's graph: a safety net for a change its
     * own counter did not move.
     */
    private static final int GRAPH_RECHECK_BATCHES = 5;

    /** The blockstate property a track block's shape lives in, as Create names it. */
    private static final String SHAPE_PROPERTY = "shape";

    /** Index of the shape used when a block has no shape property, or one this table does not know. */
    private static final int UNKNOWN_SHAPE = 0;

    /** Ceiling on remembered track blocks; reaching it stops the scan and says so once. */
    private static final int MAX_TRACK_BLOCKS = 100_000;

    /** Every shape this layer knows, by index, with UNKNOWN_SHAPE first; filled by define(). */
    private static final List<Shape> SHAPE_TABLE = new ArrayList<>();
    /** Shape name as a blockstate serialises it, to its index in SHAPE_TABLE. */
    private static final Map<String, Integer> SHAPE_IDS = new HashMap<>();
    /** Shape index per block state, looked up once per state rather than once per block. */
    private static final Map<BlockState, Integer> STATE_SHAPES = new IdentityHashMap<>();

    static {
        // Reaching every direction, for a shape whose name is not in the table: clumsy beats a vanishing line.
        SHAPE_TABLE.add(kingMoves());
        define("none");
        define("zo", 0, 0, 1);
        define("xo", 1, 0, 0);
        define("pd", 1, 0, 1);
        define("nd", -1, 0, 1);
        define("an", 0, 1, -1);
        define("as", 0, 1, 1);
        define("ae", 1, 1, 0);
        define("aw", -1, 1, 0);
        define("tn", 0, 0, -1);
        define("ts", 0, 0, 1);
        define("te", 1, 0, 0);
        define("tw", -1, 0, 0);
        define("cr_o", 0, 0, 1, 1, 0, 0);
        define("cr_d", 1, 0, 1, -1, 0, 1);
        define("cr_pdx", 1, 0, 0, 1, 0, 1);
        define("cr_pdz", 0, 0, 1, 1, 0, 1);
        define("cr_ndx", 1, 0, 0, -1, 0, 1);
        define("cr_ndz", 0, 0, 1, -1, 0, 1);
    }

    /** Track blocks found so far, by chunk. A chunk that held track and no longer does is absent. */
    private static final Map<Long, ChunkTracks> chunkTracks = new HashMap<>();
    private static final Map<Long, long[]> chunkStations = new HashMap<>();
    /** When each chunk was last read, so a reading can be trusted for a while. */
    private static final Map<Long, Long> scannedAt = new HashMap<>();

    /** Chunks of the pass over the map currently in progress, nearest ring first. */
    private static final Deque<Long> sweep = new ArrayDeque<>();
    /** Chunks read out of turn, which ignore the freshness gate. */
    private static final Deque<Long> urgent = new ArrayDeque<>();

    /** The coarse layer: one polyline per run of track between two junctions. */
    private static RoadNetwork coarse = new RoadNetwork();

    /**
     * Which source built the layer currently held.
     * Exactly one is ever the layer; Create's graph is preferred, the block scan used when it gave nothing.
     */
    private static Source source = Source.NONE;

    /** Stations from Create's graph, with their names; empty while the block scan is the source. */
    private static List<Station> layerStations = List.of();

    /** Whether Create's graph has been read at all, and the manager's counter when it last was. */
    private static boolean graphRead;
    private static int graphVersion;
    private static boolean reportedGraphBuild;

    private static Set<ResourceLocation> trackBlocks = Set.of();
    private static Set<ResourceLocation> stationBlocks = Set.of();
    private static List<? extends String> configuredTrackIds = List.of();
    private static List<? extends String> configuredStationIds = List.of();

    /** The level the layer was read from; a dimension change throws all of it away. */
    private static Object boundLevel;

    private static long ticks;
    /** Passes of one second each since the layer started; the id re-read and the report count these. */
    private static long passes;
    private static boolean coarseDirty;
    private static int storedTrackBlocks;
    /** Edges the last rebuild joined track blocks with, for the diagnostic only. */
    private static int lastEdgeCount;
    private static boolean capReported;
    private static boolean layerReported;
    private static boolean warnedBadId;

    // Rail layer diagnostic: counters that tell apart scan-found-nothing, empty-layer and never-drawn.
    // Written only when the player asked for diagnostics (RoadConfig#debugLog); cheap when off.

    /** Chunks read since the layer started, and how many of them held anything. */
    private static long chunksRead;
    private static long chunksWithContent;
    /**
     * What the map side has done with the layer, counted since it started.
     * mapHandedOver / mapPasses == segments is the invariant: otherwise the drawn layer is not the reported one.
     */
    private static long mapPasses;
    private static long mapHandedOver;
    private static long mapStroked;
    private static final int[] offersByLocation = new int[8];
    private static final int[] lastOffersByLocation = new int[8];
    private static long lastMapPasses;
    private static long lastMapHandedOver;
    private static long lastMapStroked;
    /** What the last reading of Create's graph produced; zeroed when it was not the source. */
    private static int graphCount;
    private static int graphNodes;
    private static int graphEdges;
    private static int graphCurves;
    /** Curves whose sampled ends did not sit at the nodes they were asked for; zero is healthy. */
    private static int graphOddEdges;
    /** Segment count at the last endpoint dump, so the dump is written when the layer changes only. */
    private static int dumpedSegments = -1;
    /** The station list at the last station dump; empty means nothing has been dumped yet. */
    private static String dumpedStations = "";

    /** Rail diagnostic: the provider began enumerating the layer for one location. */
    public static void noteMapPass(int location) {
        if (!RoadConfig.debugLog()) {
            return;
        }
        mapPasses++;
        if (location >= 0 && location < offersByLocation.length) {
            offersByLocation[location]++;
        }
    }

    /** Rail diagnostic: one rail element was offered to the map's pipeline. */
    public static void noteElementOffered() {
        if (!RoadConfig.debugLog()) {
            return;
        }
        mapHandedOver++;
    }

    /** Rail diagnostic: one of this layer's segments was actually stroked on the map. */
    public static void noteElementStroked() {
        if (!RoadConfig.debugLog()) {
            return;
        }
        mapStroked++;
    }

    /** Whether a segment came from this layer rather than the editor, by id range. */
    public static boolean isOurs(RoadSegment segment) {
        return segment != null && segment.id() >= ID_BASE;
    }

    /**
     * The first few segments' ends and vertex counts, written when the layer's size changes -- to
     * match a line on the map against the numbers and to spot invented geometry.
     */
    private static void reportSegments() {
        if (!RoadConfig.debugLog()) {
            return;
        }
        StringBuilder line = new StringBuilder();
        int shown = 0;
        for (RoadSegment segment : coarse.segmentsSnapshot()) {
            if (shown++ == 3) {
                break;
            }
            RoadNode from = coarse.node(segment.fromNode());
            RoadNode to = coarse.node(segment.toNode());
            line.append(" | #").append(segment.id()).append(' ');
            line.append(from == null ? "?" : from.x() + "," + from.z()).append(" -> ");
            line.append(to == null ? "?" : to.x() + "," + to.z());
            line.append(" v=").append(segment.vertexCount());
            line.append(" ends ").append(segment.x(0)).append(',').append(segment.z(0)).append('/')
                    .append(segment.x(segment.vertexCount() - 1)).append(',')
                    .append(segment.z(segment.vertexCount() - 1));
        }
        HowToGo.diagnostic("[HowToGo] rail seg | source={} segments={}{}", source,
                coarse.segmentCount(), line);
    }

    /**
     * The one line a second, tag [HowToGo] rail: tells apart scan-empty, layer-empty and
     * never-drawn failures, names the source, and reports last-second deltas.
     */
    private static void report() {
        if (!RoadConfig.debugLog()) {
            return;
        }
        long passes = mapPasses - lastMapPasses;
        long handedOver = mapHandedOver - lastMapHandedOver;
        long stroked = mapStroked - lastMapStroked;
        lastMapPasses = mapPasses;
        lastMapHandedOver = mapHandedOver;
        lastMapStroked = mapStroked;
        StringBuilder locations = new StringBuilder();
        for (int i = 0; i < offersByLocation.length; i++) {
            int delta = offersByLocation[i] - lastOffersByLocation[i];
            lastOffersByLocation[i] = offersByLocation[i];
            if (delta > 0) {
                locations.append(locations.length() == 0 ? "" : ",").append(i).append(':').append(delta);
            }
        }
        HowToGo.diagnostic("[HowToGo] rail | enabled={} tracks={} stations={} | source={} graphs={} "
                        + "nodes={} edges={} curves={} odd={} | radius={} perSecond={} | queued={} read={} "
                        + "withContent={} | remembered tracks={} stations={} | layer edges={} segments={} "
                        + "stations={} | map perSecond passes={} offersByLocation=[{}] offered={} "
                        + "stroked={} totals {}/{}/{}",
                RoadConfig.createTrainTracks(), configuredTrackIds, configuredStationIds,
                source, graphCount, graphNodes, graphEdges, graphCurves, graphOddEdges,
                RoadConfig.createTrackScanRadius(), RoadConfig.createTrackChunksPerSecond(),
                sweep.size() + urgent.size(), chunksRead, chunksWithContent,
                storedTrackBlocks, stationBlockCount(), lastEdgeCount, coarse.segmentCount(),
                stationCount(), passes, locations, handedOver, stroked, mapPasses, mapHandedOver,
                mapStroked);
    }

    /** A train station, as a place to navigate to; the name is Create's when the graph supplied it. */
    public record Station(int x, int y, int z, String name) {
    }

    /** The track blocks of one chunk, and the shape of each, in the same order. */
    private record ChunkTracks(long[] positions, byte[] shapes) {
    }

    /** Where a track block's own shape says it reaches, as packed one-block offsets. */
    private record Shape(byte[] offsets, boolean known) {

        boolean reaches(byte offset) {
            for (byte candidate : offsets) {
                if (candidate == offset) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * The graph the compression walks, from either source. A flat edge list (edges pair as e and
     * e ^ 1); geometry is per directed edge, already oriented from its from end.
     */
    private record Graph(int[] head, int[] other, int[] next, int[] degree, int edges,
                         double[] nodeX, double[] nodeY, double[] nodeZ, double[] vertexX,
                         double[] vertexZ, int[] vertexStart, int[] vertexCount) {

        int unusedEdgeAt(int node, boolean[] used) {
            for (int edge = head[node]; edge >= 0; edge = next[edge]) {
                if (!used[edge]) {
                    return edge;
                }
            }
            return -1;
        }
    }

    /** Which source built the layer on the last build, for the diagnostic. */
    private enum Source {
        NONE,
        CREATE_GRAPH,
        BLOCK_SCAN
    }

    private RailTrackStore() {
    }


    /**
     * Called every client tick: nineteen ticks count, the twentieth does one second's work. A period,
     * not a has-it-been-long-enough test -- an earlier version's subtraction overflowed on "never".
     */
    public static void tick() {
        ticks++;
        if (ticks % INTERVAL_TICKS != 0) {
            return;
        }
        passes++;
        if (passes % ID_REFRESH_BATCHES == 0) {
            refreshBlockIds();
        }

        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft == null ? null : minecraft.level;
        if (level == null) {
            // Left the world; nothing here survives it, since the blocks are only what is loaded.
            if (boundLevel != null) {
                clear();
            }
            return;
        }
        if (level != boundLevel) {
            clear();
            boundLevel = level;
            // The ids are read the moment there is something to read them for, so the first pass is not
            // delayed by the periodic re-read.
            refreshBlockIds();
        }
        if (!RoadConfig.createTrainTracks()) {
            if (!chunkTracks.isEmpty() || !chunkStations.isEmpty()) {
                clear();
            }
            report();
            return;
        }
        if (trackBlocks.isEmpty() && stationBlocks.isEmpty()) {
            // Nothing is configured to look for, so there is nothing to read.
            report();
            return;
        }

        LocalPlayer player = minecraft.player;
        if (player == null) {
            report();
            return;
        }
        int chunkX = player.getBlockX() >> 4;
        int chunkZ = player.getBlockZ() >> 4;

        // Create's own graph first: while it answers, the block scan is not run at all.
        if (CreateTrackGraph.available()) {
            int version = CreateTrackGraph.version();
            boolean due = !graphRead || version != graphVersion
                    || passes % GRAPH_RECHECK_BATCHES == 0;
            if (due) {
                CreateTrackGraph.Snapshot snapshot = CreateTrackGraph.read(level.dimension());
                if (snapshot != null) {
                    graphRead = true;
                    graphVersion = version;
                    graphCount = snapshot.graphs();
                    graphNodes = snapshot.nodeCount();
                    graphEdges = snapshot.edges().size();
                    graphCurves = snapshot.curves();
                    graphOddEdges = snapshot.oddEdges();
                    if (!snapshot.edges().isEmpty()) {
                        layerStations = stationsOf(snapshot);
                        forgetScan();
                        buildFromCreateGraph(snapshot);
                        report();
                        return;
                    }
                    // It answered with an empty network: the previous build is dropped rather than left on the map;
                    // the scan still gets its turn.
                    if (source == Source.CREATE_GRAPH) {
                        adopt(new RoadNetwork());
                        layerStations = List.of();
                        source = Source.NONE;
                    }
                }
            } else if (source == Source.CREATE_GRAPH) {
                // Read a moment ago, unchanged, and already the layer: nothing to do this second.
                report();
                return;
            }
        }

        // Nothing from the graph: read track blocks instead, a batch a second.
        if (source != Source.BLOCK_SCAN) {
            graphCount = 0;
            graphNodes = 0;
            graphEdges = 0;
            graphCurves = 0;
            graphOddEdges = 0;
            layerStations = List.of();
        }

        // The chunk underfoot is read every pass, so track laid under the player shows within the second.
        urgent.addLast(chunkKey(chunkX, chunkZ));
        if (sweep.isEmpty()) {
            refillSweep(chunkX, chunkZ);
        }

        int budget = RoadConfig.createTrackChunksPerSecond();
        for (int i = 0; i < budget; i++) {
            Long key = urgent.pollFirst();
            if (key != null) {
                scan(key, level, chunkX, chunkZ, true);
                continue;
            }
            key = sweep.pollFirst();
            if (key == null) {
                break;
            }
            scan(key, level, chunkX, chunkZ, false);
        }

        // The rebuild happens inside the second's own pass, so it cannot be skipped by arithmetic.
        if (coarseDirty) {
            buildFromBlockScan();
        }
        report();
    }

    /** Create's stations as this class's own, keeping the names. */
    private static List<Station> stationsOf(CreateTrackGraph.Snapshot snapshot) {
        List<Station> stations = new ArrayList<>(snapshot.stations().size());
        for (CreateTrackGraph.Station station : snapshot.stations()) {
            stations.add(new Station(station.x(), station.y(), station.z(), station.name()));
        }
        return stations;
    }

    /**
     * Bumped whenever the layer is replaced. Each rebuild renumbers its segments, so callers caching
     * derived values need a version of the layer itself.
     */
    private static int layerStamp;

    /** A version of the layer, so a reader can tell one build from the next. */
    public static int layerVersion() {
        return layerStamp;
    }

    /** Whether the layer has anything in it and is switched on. */
    public static boolean active() {
        return RoadConfig.createTrainTracks() && coarse.segmentCount() > 0;
    }

    /**
     * The layer's segments, for drawing: a live view nothing outside may add to, so a track is drawn
     * from the geometry the router routes along.
     */
    public static Collection<RoadSegment> segments() {
        return coarse.segments();
    }

    /**
     * The layer as a network, for click hit-testing and for walking a chain of rail segments.
     * Read-only: the editor's network is the saved one, so a rail can be named but never redrawn.
     */
    public static RoadNetwork network() {
        return coarse;
    }

    /**
     * Makes a freshly built network the layer, stamping stored names onto it first.
     * Every assignment goes through here so a build cannot silently lose the player's names.
     */
    private static void adopt(RoadNetwork built) {
        coarse = built;
        layerStamp++;
        RailNameStore.apply(coarse);
    }

    /** Every station the layer offers; names come from Create's graph, the scan has none. */
    public static List<Station> stations() {
        if (!RoadConfig.createTrainTracks()) {
            return List.of();
        }
        if (source == Source.CREATE_GRAPH) {
            return layerStations;
        }
        if (stationBlocks.isEmpty()) {
            return List.of();
        }
        List<Station> result = new ArrayList<>();
        for (long[] blocks : chunkStations.values()) {
            for (long pos : blocks) {
                result.add(new Station(BlockPos.getX(pos), BlockPos.getY(pos), BlockPos.getZ(pos), null));
            }
        }
        return result;
    }

    /**
     * The network to plan on: the saved roads plus every machine-read layer the trip could use.
     * A mode that cannot travel on a layer gets RoadStore#get() itself, so its routes cannot change;
     * avoidances are not asked here. The merge is a copy -- the router mutates while anchoring -- and
     * layers are never saved with the player's roads.
     * Three layers are merged: Create rails, MTR rails, RoadWeaver roads.
     */
    public static RoadNetwork forRouting(TravelMode mode, RoutePreferences preferences) {
        return forRouting(mode, preferences, true);
    }

    /**
     * The same, with MTR's route marks left out on request.
     * A line with them switched off needs a network that never had them (see RideRoads).
     * @param withMtrMarks whether MTR's rails are merged in
     */
    public static RoadNetwork forRouting(TravelMode mode, RoutePreferences preferences,
                                         boolean withMtrMarks) {
        RoadNetwork handDrawn = RoadStore.get();
        // What a mode may travel on; avoidances are deliberately not consulted, or a line that declared
        // itself a railway would lose its rails.
        boolean movesOnRails = mode != null && mode.allows(RoadClass.RAIL);
        RoadNetwork mtr = withMtrMarks && movesOnMtrMarks(mode) ? MtrTransit.railLayer()
                : new RoadNetwork();
        // RoadWeaver's roads when the config lets a trip use them; null rather than empty so the common
        // case allocates nothing.
        RoadNetwork roadWeaver = RoadWeaverLayer.routingNetwork(mode);
        if ((!active() || !movesOnRails) && mtr.segmentCount() == 0
                && (roadWeaver == null || roadWeaver.segmentCount() == 0)) {
            return handDrawn;
        }
        RoadNetwork merged = handDrawn.deepCopy();
        if (active() && movesOnRails) {
            for (RoadNode node : coarse.nodesSnapshot()) {
                merged.putNode(node.copy());
            }
            for (RoadSegment segment : coarse.segmentsSnapshot()) {
                merged.putSegment(segment.copy());
            }
        }
        // MTR's marks are gated inside the layer, so there is nothing to check here.
        for (RoadNode node : mtr.nodesSnapshot()) {
            merged.putNode(node.copy());
        }
        for (RoadSegment segment : mtr.segmentsSnapshot()) {
            merged.putSegment(segment.copy());
        }
        if (roadWeaver != null) {
            for (RoadNode node : roadWeaver.nodesSnapshot()) {
                merged.putNode(node.copy());
            }
            for (RoadSegment segment : roadWeaver.segmentsSnapshot()) {
                merged.putSegment(segment.copy());
            }
        }
        return merged;
    }

    /**
     * Whether a mode can travel on MTR's marks: both classes (rail and water), or a boat line would
     * be planned as if its switch were off.
     */
    static boolean movesOnMtrMarks(TravelMode mode) {
        return mode != null && (mode.allows(RoadClass.RAIL) || mode.allows(RoadClass.WATER));
    }


    /**
     * Re-reads the configured block ids, parsed once: parsed because the test runs inside the block
     * loop. The lists are compared first, so an unchanged config costs nothing.
     */
    private static void refreshBlockIds() {
        List<? extends String> trackIds = RoadConfig.createTrackBlockIds();
        List<? extends String> stationIds = RoadConfig.createStationBlockIds();
        if (trackIds.equals(configuredTrackIds) && stationIds.equals(configuredStationIds)) {
            return;
        }
        configuredTrackIds = trackIds;
        configuredStationIds = stationIds;
        trackBlocks = parseIds(trackIds);
        stationBlocks = parseIds(stationIds);
        HowToGo.LOGGER.info("[HowToGo] Create track layer: reading {} track id(s) and {} station id(s)",
                trackBlocks.size(), stationBlocks.size());
    }

    private static Set<ResourceLocation> parseIds(List<? extends String> ids) {
        Set<ResourceLocation> parsed = new HashSet<>();
        for (String id : ids) {
            ResourceLocation location = id == null ? null : ResourceLocation.tryParse(id.trim());
            if (location == null) {
                if (!warnedBadId) {
                    warnedBadId = true;
                    HowToGo.LOGGER.warn("[HowToGo] ignoring block id \"{}\" in the track layer config; "
                            + "expected namespace:path", id);
                }
                continue;
            }
            parsed.add(location);
        }
        return parsed;
    }


    /** Throws the layer away, which is what every level change and switching the feature off does. */
    private static void clear() {
        forgetScan();
        adopt(new RoadNetwork());
        source = Source.NONE;
        layerStations = List.of();
        graphRead = false;
        graphVersion = 0;
        layerReported = false;
        reportedGraphBuild = false;
        dumpedSegments = -1;
        dumpedStations = "";
    }

    /**
     * Drops what the block scan remembered: when Create's graph becomes the source, and on every
     * level change.
     */
    private static void forgetScan() {
        chunkTracks.clear();
        chunkStations.clear();
        scannedAt.clear();
        sweep.clear();
        urgent.clear();
        coarseDirty = false;
        storedTrackBlocks = 0;
        lastEdgeCount = 0;
        capReported = false;
    }

    /**
     * Queues every chunk within the configured radius in rings outward from the player, so what the
     * player is looking at is read first.
     */
    private static void refillSweep(int centerChunkX, int centerChunkZ) {
        int radiusChunks = Math.max(1, (RoadConfig.createTrackScanRadius() + 15) / 16);
        for (int ring = 0; ring <= radiusChunks; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) {
                        continue;
                    }
                    sweep.addLast(chunkKey(centerChunkX + dx, centerChunkZ + dz));
                }
            }
        }
    }

    /**
     * Reads one chunk and remembers what was in it.
     * @param forced read regardless of freshness, for the player's own chunk
     */
    private static void scan(long key, ClientLevel level, int centerChunkX, int centerChunkZ,
                             boolean forced) {
        int chunkX = (int) (key >> 32);
        int chunkZ = (int) key;

        if (!forced) {
            int radiusChunks = Math.max(1, (RoadConfig.createTrackScanRadius() + 15) / 16);
            if (Math.max(Math.abs(chunkX - centerChunkX), Math.abs(chunkZ - centerChunkZ)) > radiusChunks) {
                // Queued before the player walked away; the ring order means this is rare.
                return;
            }
            Long last = scannedAt.get(key);
            if (last != null && ticks - last < RESCAN_TICKS) {
                return;
            }
        }

        // The client's own chunk cache never blocks: an unloaded chunk comes back empty, and reading it
        // as empty is right.
        LevelChunk chunk = level.getChunk(chunkX, chunkZ);
        List<Long> tracks = new ArrayList<>();
        List<Byte> shapes = new ArrayList<>();
        List<Long> stations = new ArrayList<>();
        if (chunk != null) {
            collect(chunk, tracks, shapes, stations);
        }
        scannedAt.put(key, ticks);
        // The two counters, updated here so nothing is measured outside the pass; per chunk, so left
        // ungated.
        chunksRead++;
        if (!tracks.isEmpty() || !stations.isEmpty()) {
            chunksWithContent++;
        }
        storeTracks(key, tracks, shapes);
        storeStations(key, stations);
    }

    /**
     * Walks the sections that could hold something, keeping track and station blocks.
     * The palette test comes first: a handful of comparisons instead of 4096 block reads.
     */
    private static void collect(LevelChunk chunk, List<Long> tracks, List<Byte> shapes,
                                List<Long> stations) {
        LevelChunkSection[] sections = chunk.getSections();
        // 1.21.4 renamed getMinBuildHeight/getMaxBuildHeight to getMinY/getMaxY; the source on disk
        // carries the 1.21.1 spelling.
        //? if >=1.21.4 {
        /*int minY = chunk.getMinY();
        *///?} else {
        int minY = chunk.getMinBuildHeight();
        //?}
        for (int index = 0; index < sections.length; index++) {
            LevelChunkSection section = sections[index];
            if (section == null || section.hasOnlyAir()) {
                continue;
            }
            boolean maybeTrack = !trackBlocks.isEmpty() && section.maybeHas(RailTrackStore::isTrack);
            boolean maybeStation = !stationBlocks.isEmpty() && section.maybeHas(RailTrackStore::isStation);
            if (!maybeTrack && !maybeStation) {
                continue;
            }
            int baseY = minY + (index << 4);
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        BlockState state = section.getBlockState(x, y, z);
                        if (state.isAir()) {
                            continue;
                        }
                        if (maybeTrack && isTrack(state)) {
                            tracks.add(BlockPos.asLong(chunk.getPos().getMinBlockX() + x, baseY + y,
                                    chunk.getPos().getMinBlockZ() + z));
                            shapes.add((byte) shapeId(state));
                        } else if (maybeStation && isStation(state)) {
                            stations.add(BlockPos.asLong(chunk.getPos().getMinBlockX() + x, baseY + y,
                                    chunk.getPos().getMinBlockZ() + z));
                        }
                    }
                }
            }
        }
    }

    /**
     * Replaces what is remembered for a chunk, flagging the coarse layer only if it changed -- a
     * different answer would rebuild it every pass.
     */
    private static void storeTracks(long key, List<Long> found, List<Byte> shapeIds) {
        long[] fresh = new long[found.size()];
        byte[] freshShapes = new byte[found.size()];
        for (int i = 0; i < fresh.length; i++) {
            fresh[i] = found.get(i);
            freshShapes[i] = shapeIds.get(i);
        }
        ChunkTracks previous = chunkTracks.get(key);
        if (previous != null && Arrays.equals(previous.positions(), fresh)
                && Arrays.equals(previous.shapes(), freshShapes)) {
            return;
        }
        int had = previous == null ? 0 : previous.positions().length;
        if (storedTrackBlocks + fresh.length - had > MAX_TRACK_BLOCKS) {
            if (!capReported) {
                capReported = true;
                HowToGo.LOGGER.warn("[HowToGo] Create track layer is holding {} track blocks, its limit; "
                        + "it will not read any more of them until the player moves away", storedTrackBlocks);
            }
            return;
        }
        storedTrackBlocks += fresh.length - had;
        if (fresh.length == 0) {
            chunkTracks.remove(key);
        } else {
            chunkTracks.put(key, new ChunkTracks(fresh, freshShapes));
        }
        coarseDirty = true;
    }

    private static void storeStations(long key, List<Long> found) {
        long[] fresh = new long[found.size()];
        for (int i = 0; i < fresh.length; i++) {
            fresh[i] = found.get(i);
        }
        if (Arrays.equals(chunkStations.get(key), fresh)) {
            return;
        }
        if (fresh.length == 0) {
            chunkStations.remove(key);
        } else {
            chunkStations.put(key, fresh);
        }
    }

    private static boolean isTrack(BlockState state) {
        ResourceLocation key = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return key != null && trackBlocks.contains(key);
    }

    private static boolean isStation(BlockState state) {
        ResourceLocation key = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return key != null && stationBlocks.contains(key);
    }

    private static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }


    /** The shape of a block state as an index into SHAPE_TABLE, cached per state. */
    private static int shapeId(BlockState state) {
        Integer cached = STATE_SHAPES.get(state);
        if (cached != null) {
            return cached;
        }
        String name = shapeName(state);
        Integer known = name == null ? null : SHAPE_IDS.get(name);
        int resolved = known == null ? UNKNOWN_SHAPE : known;
        STATE_SHAPES.put(state, resolved);
        return resolved;
    }

    /**
     * The shape property's serialised value, or null. Read through StringRepresentable, so no
     * Create class is needed.
     */
    private static String shapeName(BlockState state) {
        for (Property<?> property : state.getProperties()) {
            if (SHAPE_PROPERTY.equals(property.getName())) {
                return serialised(state, property);
            }
        }
        return null;
    }

    private static <T extends Comparable<T>> String serialised(BlockState state, Property<T> property) {
        T value = state.getValue(property);
        return value instanceof StringRepresentable named
                ? named.getSerializedName() : String.valueOf(value);
    }

    /**
     * Registers one shape: its name, then each axis as three numbers.
     * Both signs of every axis become offsets, because an axis is a line through the block.
     */
    private static void define(String name, int... axes) {
        Set<Byte> offsets = new LinkedHashSet<>();
        for (int i = 0; i + 2 < axes.length; i += 3) {
            int dx = axes[i];
            int dy = axes[i + 1];
            int dz = axes[i + 2];
            offsets.add((byte) pack(dx, dy, dz));
            offsets.add((byte) pack(-dx, -dy, -dz));
        }
        byte[] packed = new byte[offsets.size()];
        int at = 0;
        for (byte offset : offsets) {
            packed[at++] = offset;
        }
        SHAPE_IDS.put(name, SHAPE_TABLE.size());
        SHAPE_TABLE.add(new Shape(packed, true));
    }

    private static Shape kingMoves() {
        byte[] offsets = new byte[24];
        int at = 0;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx != 0 || dz != 0) {
                        offsets[at++] = (byte) pack(dx, dy, dz);
                    }
                }
            }
        }
        return new Shape(offsets, false);
    }

    /** One horizontal offset in a byte: two bits per axis, each biased by one. */
    private static int pack(int dx, int dy, int dz) {
        return ((dx + 1) << 4) | ((dy + 1) << 2) | (dz + 1);
    }

    private static int offsetX(int packed) {
        return ((packed >> 4) & 3) - 1;
    }

    private static int offsetY(int packed) {
        return ((packed >> 2) & 3) - 1;
    }

    private static int offsetZ(int packed) {
        return (packed & 3) - 1;
    }

    private static int negate(int packed) {
        return pack(-offsetX(packed), -offsetY(packed), -offsetZ(packed));
    }


    /**
     * Builds the layer from Create's own graph, one segment per Create edge.
     * Nothing is joined: Create draws each edge on its own, so no assumption is made that one edge's
     * geometry starts where the previous ended -- every phantom-geometry bug came from stitching.
     * Segments still meet because they share node coordinates. RoadChains still gathers a run of
     * edges for naming, since a node between two edges has degree two.
     */
    private static void buildFromCreateGraph(CreateTrackGraph.Snapshot snapshot) {
        List<CreateTrackGraph.Edge> edges = snapshot.edges();
        double[] nodeX = snapshot.nodeX();
        double[] nodeY = snapshot.nodeY();
        double[] nodeZ = snapshot.nodeZ();
        int[] degree = new int[nodeX.length];
        for (CreateTrackGraph.Edge edge : edges) {
            degree[edge.from()]++;
            degree[edge.to()]++;
        }

        Build build = new Build();
        for (CreateTrackGraph.Edge edge : edges) {
            double[] polyline = edge.polyline();
            int vertices = polyline.length / 2;
            if (vertices < 2) {
                continue;
            }
            int fromId = nodeId(build, nodeX, nodeY, nodeZ, degree, edge.from());
            // A loop can end where it started, in which case one node is both ends.
            int toId = edge.from() == edge.to()
                    ? fromId : nodeId(build, nodeX, nodeY, nodeZ, degree, edge.to());

            RoadSegment segment = new RoadSegment(build.nextId++, RoadClass.RAIL,
                    (int) Math.round(nodeY[edge.from()]), vertices);
            List<double[]> display = new ArrayList<>(vertices);
            for (int v = 0; v < vertices; v++) {
                segment.addVertex(round(polyline[v * 2]), round(polyline[v * 2 + 1]));
                display.add(new double[]{polyline[v * 2], polyline[v * 2 + 1]});
            }
            segment.setFromNode(fromId);
            segment.setToNode(toId);
            // Create's own samples, not block-rounded vertices: rounding put a half-block wobble on curves;
            // the ends are already the two node positions.
            segment.setCurve(RoadCurve.precise(display));
            build.network.putSegment(segment);
        }

        adopt(build.network);
        lastEdgeCount = edges.size();
        source = Source.CREATE_GRAPH;
        layerReported = true;
        reportSegmentsIfChanged();
        if (!reportedGraphBuild) {
            reportedGraphBuild = true;
            HowToGo.diagnostic("[HowToGo] Create track layer from Create's graph: {} nodes, {} edges "
                            + "({} curved), {} segments, {} stations",
                    nodeX.length, edges.size(), snapshot.curves(), coarse.segmentCount(),
                    snapshot.stations().size());
        }
    }

    /**
     * Builds the layer from the block scan: blocks joined along their axes, runs compressed.
     * Used when Create's graph is missing; only one source is ever the layer.
     */
    private static void buildFromBlockScan() {
        coarseDirty = false;

        int count = 0;
        for (ChunkTracks tracks : chunkTracks.values()) {
            count += tracks.positions().length;
        }
        storedTrackBlocks = count;
        if (count < 2) {
            // A lone track block is a point, and a point is not a line to travel along or draw.
            adopt(new RoadNetwork());
            source = Source.NONE;
            return;
        }

        long[] positions = new long[count];
        byte[] shapes = new byte[count];
        int at = 0;
        for (ChunkTracks tracks : chunkTracks.values()) {
            System.arraycopy(tracks.positions(), 0, positions, at, tracks.positions().length);
            System.arraycopy(tracks.shapes(), 0, shapes, at, tracks.shapes().length);
            at += tracks.positions().length;
        }

        Map<Long, Integer> index = new HashMap<>(count * 2);
        for (int i = 0; i < count; i++) {
            index.put(positions[i], i);
        }
        Graph graph = buildBlockGraph(positions, shapes, index);
        adopt(compress(graph));
        lastEdgeCount = graph.edges() / 2;
        source = Source.BLOCK_SCAN;
        if (!layerReported && coarse.segmentCount() > 0) {
            layerReported = true;
            HowToGo.diagnostic("[HowToGo] Create track layer from the block scan: {} track blocks in "
                            + "{} segments, {} stations", count, coarse.segmentCount(), stationBlockCount());
        }
        reportSegmentsIfChanged();
    }

    /** Writes the segment dump again only when the layer's size changed. */
    private static void reportSegmentsIfChanged() {
        if (coarse.segmentCount() != dumpedSegments) {
            dumpedSegments = coarse.segmentCount();
            reportSegments();
        }
        reportStationsIfChanged();
    }

    /**
     * Builds the layer from Create's own graph, one segment per Create edge.
     * Nothing is joined: Create draws each edge on its own, so no edge's geometry is assumed to start
     * where the previous ended -- every phantom-geometry bug came from stitching. Segments meet because
     * they share node coordinates; RoadChains still gathers a run of edges for naming.
     */
    private static void reportStationsIfChanged() {
        if (!RoadConfig.debugLog()) {
            // Out of the whole diagnostic, not only the writing: building a line per station just to compare
            // it is not worth it if nobody reads it.
            return;
        }
        StringBuilder line = new StringBuilder();
        for (Station station : stations()) {
            line.append(" | @").append(station.x()).append(',').append(station.y()).append(',')
                    .append(station.z()).append(" name='").append(station.name()).append('\'');
        }
        String signature = line.toString();
        if (signature.equals(dumpedStations)) {
            return;
        }
        dumpedStations = signature;
        HowToGo.diagnostic("[HowToGo] rail station | source={} count={}{}", source, stationCount(), line);
    }

    /**
     * Joins every track block to the blocks at the ends of its own axes.
     * A join needs both blocks to reach along the shared axis, so parallel tracks stay two. Edges are
     * stored both ways, so an unknown shape still joins through its neighbours.
     */
    private static Graph buildBlockGraph(long[] positions, byte[] shapeIds, Map<Long, Integer> index) {
        List<int[]> directed = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (int i = 0; i < positions.length; i++) {
            Shape shape = SHAPE_TABLE.get(shapeIds[i]);
            long position = positions[i];
            for (byte offset : shape.offsets()) {
                int j = lookup(index, position, offset);
                if (j < 0 || j == i) {
                    continue;
                }
                Shape other = SHAPE_TABLE.get(shapeIds[j]);
                if (shape.known() && other.known() && !other.reaches((byte) negate(offset))) {
                    continue;
                }
                long key = edgeKey(i, j);
                if (!seen.add(key)) {
                    continue;
                }
                directed.add(new int[]{i, j});
                directed.add(new int[]{j, i});
            }
        }

        int edges = directed.size();
        double[] nodeX = new double[positions.length];
        double[] nodeZ = new double[positions.length];
        double[] nodeY = new double[positions.length];
        for (int i = 0; i < positions.length; i++) {
            nodeX[i] = BlockPos.getX(positions[i]);
            nodeY[i] = BlockPos.getY(positions[i]);
            nodeZ[i] = BlockPos.getZ(positions[i]);
        }
        int[] head = new int[positions.length];
        Arrays.fill(head, -1);
        int[] next = new int[edges];
        int[] other = new int[edges];
        int[] degree = new int[positions.length];
        int[] vertexStart = new int[edges];
        int[] vertexCount = new int[edges];
        // One block long, so every edge is its own two node positions -- the graph shape the compression
        // walks, without geometry.
        double[] vertexX = new double[edges * 2];
        double[] vertexZ = new double[edges * 2];
        for (int edge = 0; edge < edges; edge++) {
            int[] pair = directed.get(edge);
            connect(head, next, other, degree, edge, pair[0], pair[1]);
            vertexStart[edge] = edge * 2;
            vertexCount[edge] = 2;
            vertexX[edge * 2] = nodeX[pair[0]];
            vertexZ[edge * 2] = nodeZ[pair[0]];
            vertexX[edge * 2 + 1] = nodeX[pair[1]];
            vertexZ[edge * 2 + 1] = nodeZ[pair[1]];
        }
        return new Graph(head, other, next, degree, edges, nodeX, nodeZ, nodeY, vertexX, vertexZ,
                vertexStart, vertexCount);
    }

    private static void connect(int[] head, int[] next, int[] other, int[] degree, int edge,
                                int from, int to) {
        other[edge] = to;
        next[edge] = head[from];
        head[from] = edge;
        degree[from]++;
    }

    private static int lookup(Map<Long, Integer> index, long position, byte offset) {
        Integer found = index.get(BlockPos.asLong(
                BlockPos.getX(position) + offsetX(offset),
                BlockPos.getY(position) + offsetY(offset),
                BlockPos.getZ(position) + offsetZ(offset)));
        return found == null ? -1 : found;
    }

    /** One key per undirected pair, so a join claimed from both sides is still one edge. */
    private static long edgeKey(int a, int b) {
        int low = Math.min(a, b);
        int high = Math.max(a, b);
        return ((long) low << 32) | high;
    }

    /**
     * Compresses a graph into one polyline per run between junctions.
     * Not RoadChains: it scans every segment per node, which is quadratic over tens of thousands of
     * edges and runs on a client tick. One pass over an adjacency table gives a plain RoadNetwork, so
     * everything downstream handles it like a hand-drawn rail. Ends are walked first, so runs are whole.
     */
    private static RoadNetwork compress(Graph graph) {
        int count = graph.nodeX().length;
        Build build = new Build();
        int[] edgePath = new int[graph.edges() / 2 + 1];
        boolean[] used = new boolean[graph.edges()];
        for (int start = 0; start < count; start++) {
            if (graph.degree()[start] == 2) {
                continue;
            }
            for (int edge = graph.head()[start]; edge >= 0; edge = graph.next()[edge]) {
                if (used[edge]) {
                    continue;
                }
                emit(build, graph, start, edgePath, walk(start, edge, graph, used, edgePath));
            }
        }
        // Whatever is left is a closed loop, walked from wherever the reading met it.
        for (int start = 0; start < count; start++) {
            for (int edge = graph.head()[start]; edge >= 0; edge = graph.next()[edge]) {
                if (used[edge]) {
                    continue;
                }
                emit(build, graph, start, edgePath, walk(start, edge, graph, used, edgePath));
            }
        }
        return build.network;
    }

    /**
     * Follows the track from one node along one edge until the run ends, filling edgePath.
     * The end is a node that is not a pass-through, or the start node (a loop).
     */
    private static int walk(int start, int firstEdge, Graph graph, boolean[] used, int[] edgePath) {
        int length = 0;
        int edge = firstEdge;
        while (edge >= 0 && length < edgePath.length) {
            used[edge] = true;
            // Edges are appended in pairs, so the edge back is its neighbour in the array; marking it stops
            // the walk turning round.
            used[edge ^ 1] = true;
            edgePath[length++] = edge;
            int current = graph.other()[edge];
            if (current == start) {
                // A loop with nothing to break it: the last vertex is the first one.
                break;
            }
            if (graph.degree()[current] != 2) {
                break;
            }
            edge = graph.unusedEdgeAt(current, used);
        }
        return length;
    }

    /**
     * One polyline per run, between the nodes at its ends. Each edge contributes its geometry less
     * its first vertex; coordinates are rounded to whole blocks, invisible at map scale.
     */
    private static void emit(Build build, Graph graph, int startNode, int[] edgePath, int length) {
        if (length == 0) {
            return;
        }
        int lastNode = graph.other()[edgePath[length - 1]];
        int vertices = 1;
        for (int i = 0; i < length; i++) {
            vertices += Math.max(0, graph.vertexCount()[edgePath[i]] - 1);
        }

        int fromId = nodeId(build, graph, startNode);
        // A closed loop ends where it started, so one node is both ends.
        int toId = lastNode == startNode ? fromId : nodeId(build, graph, lastNode);

        RoadSegment segment = new RoadSegment(build.nextId++, RoadClass.RAIL,
                (int) Math.round(graph.nodeY()[startNode]), vertices);
        segment.addVertex(round(graph.nodeX()[startNode]), round(graph.nodeZ()[startNode]));
        List<double[]> display = new ArrayList<>(vertices);
        display.add(new double[]{graph.nodeX()[startNode], graph.nodeZ()[startNode]});
        for (int i = 0; i < length; i++) {
            int edge = edgePath[i];
            int first = graph.vertexStart()[edge];
            int count = graph.vertexCount()[edge];
            for (int v = 1; v < count; v++) {
                segment.addVertex(round(graph.vertexX()[first + v]), round(graph.vertexZ()[first + v]));
                display.add(new double[]{graph.vertexX()[first + v], graph.vertexZ()[first + v]});
            }
        }
        segment.setFromNode(fromId);
        segment.setToNode(toId);
        // Create's own sampling, for the same reason as the graph source: the scan exists for when the
        // graph cannot be read, not to lose precision.
        segment.setCurve(RoadCurve.precise(display));
        build.network.putSegment(segment);
    }

    private static int round(double value) {
        return (int) Math.round(value);
    }

    /** The node for a point, created the first time that point is an end of a run. */
    private static int nodeId(Build build, Graph graph, int nodeIndex) {
        return nodeId(build, graph.nodeX(), graph.nodeY(), graph.nodeZ(), graph.degree(), nodeIndex);
    }

    /** The node for one point of a source's own node list, created the first time it is needed. */
    private static int nodeId(Build build, double[] nodeX, double[] nodeY, double[] nodeZ, int[] degree,
                              int nodeIndex) {
        Integer existing = build.nodeIds.get(nodeIndex);
        if (existing != null) {
            return existing;
        }
        int id = build.nextId++;
        RoadNode.Type type = degree[nodeIndex] >= 3 ? RoadNode.Type.JUNCTION : RoadNode.Type.ENDPOINT;
        build.network.putNode(new RoadNode(id, round(nodeX[nodeIndex]),
                (int) Math.round(nodeY[nodeIndex]), round(nodeZ[nodeIndex]), type, null));
        build.nodeIds.put(nodeIndex, id);
        return id;
    }

    private static int stationBlockCount() {
        int count = 0;
        for (long[] blocks : chunkStations.values()) {
            count += blocks.length;
        }
        return count;
    }

    /**
     * How many stations the layer offers; counted rather than listed because it is asked every
     * second.
     */
    private static int stationCount() {
        return source == Source.CREATE_GRAPH ? layerStations.size() : stationBlockCount();
    }

    /** Scratch state for one rebuild, so the walk helpers can hand back what they made. */
    private static final class Build {
        private final RoadNetwork network = new RoadNetwork();
        private final Map<Integer, Integer> nodeIds = new HashMap<>();
        private int nextId = ID_BASE;
    }
}
