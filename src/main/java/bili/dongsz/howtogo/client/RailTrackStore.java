package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.HowToGo;
import bili.dongsz.howtogo.RoadConfig;
import bili.dongsz.howtogo.road.RoadClass;
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
 * Create's train tracks, read out of the loaded chunks as a read-only rail layer.
 *
 * <h2>Blocks, not classes</h2>
 * A track is recognised by the block in the world -- by its registry name, {@code create:track} by
 * default -- and by nothing else. No Create class is ever loaded, so a player without Create simply
 * has an empty layer, a Create release that renames a class cannot break anything, and there is no
 * dependency in {@code build.gradle} and none in the mod metadata. The names are configuration
 * rather than constants, so a renamed or add-on block can be followed without a new build.
 *
 * <h2>The shape of the layer, and why a track's own shape decides the joins</h2>
 * A track block is not a crossroads of four directions: it carries tracks along one or two
 * <em>axes</em>, and an axis can run diagonally. A straight piece is {@code zo} or {@code xo}; a
 * 45-degree piece is {@code pd} or {@code nd}, whose axis is a corner-to-corner diagonal; a slope is
 * {@code an}, {@code as}, {@code ae} or {@code aw}, whose axis carries its own one-block rise; a
 * curve or a crossing is one of the {@code cr_} shapes, which carry two axes and therefore connect
 * in four places.
 *
 * <p>So a block is joined to the block at each end of each of its own axes, and the other block has
 * to reach back along the same axis for the join to exist. Joining every side neighbour instead
 * would be wrong twice over: a diagonal piece of track -- which is how Create builds every bend --
 * would be a row of isolated blocks with no line at all, and two tracks laid touching side by side
 * would be welded into one. The axes are read from the block state's own {@code shape} property, by
 * name, so this is still nothing but data: the property is a plain enum implementing Minecraft's
 * {@link StringRepresentable}, and the table below is Create's own {@code TrackShape} axes. A shape
 * this table does not know is treated as reaching in every direction around it rather than as
 * reaching nowhere, so a shape added by a later release or by an add-on can make the layer clumsier
 * but cannot make a line disappear.
 *
 * <p>Blocks are then compressed: track blocks are the graph, and a run of track between two
 * junctions becomes one polyline. A thousand-block line is a handful of segments, and a bend inside
 * one of those runs is kept as a vertex of it. The result is a plain {@link RoadNetwork} of
 * {@link RoadClass#RAIL} segments, so the router, the map and the class colours all treat it exactly
 * as they treat a hand-drawn rail: there is no second kind of road anywhere downstream.
 *
 * <h2>Two sources, one layer</h2>
 * The layer is built from <b>Create's own client-side track graph</b> when it can be read, and from a
 * scan of track blocks when it cannot. The graph is preferred because it is the data Create itself
 * draws on the map: its node positions are Create's, a curve arrives as a real Bezier rather than as
 * a staircase of blocks, and a station carries the name the player gave it. The block scan stays as
 * the fallback for a client without Create, a Create that renamed something, or a graph that has not
 * been sent -- and it is the reason this feature works at all if the reflection ever breaks.
 *
 * <p>Only one of the two is ever the layer, so no track can be counted twice: while the graph answers,
 * the scan is not run and whatever it had remembered is dropped. Both sources produce the same thing
 * -- a graph of nodes and edges, compressed into one polyline per run of track between two junctions
 * -- so everything downstream cannot tell which one built it.
 *
 * <h2>Read-only, and never saved</h2>
 * The layer lives here and in no other place. It is never handed to {@link RoadStore}, so the editor
 * -- which is built over that network -- cannot see it, and it is never passed to storage, so
 * nothing about it reaches {@code config/howtogo/...}. Routing does not merge into the saved network
 * either: {@link #forRouting} returns a copy with the layer folded in, and returns the saved network
 * itself when the layer cannot take part at all, which is what makes "walking and driving routes are
 * untouched" true by construction rather than by inspection. Create's graph is only ever read.
 *
 * <h2>Cost</h2>
 * Reading the world is new ground for this mod, so the budget is explicit. The layer works in
 * <b>one batch a second</b>, on the client tick and never on the render thread: between batches
 * nothing runs but a counter, and when the counter comes round the layer does one second's work and
 * stops. From Create's graph that work is a walk of the graph itself -- no chunk is read at all, and
 * the graph is only re-read when Create's own change counter moves or every fifth second as a safety
 * net. From the block scan it is {@code create_track_chunks_per_second} chunks read in one go, taken
 * nearest-first from a queue filled as rings around the player out to
 * {@code create_track_scan_radius}; a chunk's sections are tested through their palettes and only a
 * section whose palette holds a track is walked block by block, so a chunk with no track costs a few
 * dozen palette tests. A scanned chunk is remembered once read and not read again for a whole sweep,
 * and the chunk the player stands in is read every second regardless, so track laid underfoot shows
 * up at once rather than at the next pass over the map.
 */
public final class RailTrackStore {

    /**
     * Node and segment ids for this layer start here.
     *
     * <p>Ids are only ever resolved against the network they came from, so the two id spaces do not
     * have to be disjoint -- but they are made so anyway. A reader that holds a segment id and the
     * wrong network then gets nothing rather than somebody else's road, which is the difference
     * between a missing line and a highlighted road that was never selected.
     */
    private static final int ID_BASE = 1_000_000_000;

    /** One second: every piece of the layer's work happens on a tick that is a multiple of this. */
    private static final int INTERVAL_TICKS = 20;

    /** How long a chunk's reading is trusted before that chunk is read again. */
    private static final int RESCAN_TICKS = 200;

    /** Batches between re-reads of the configured block id lists. */
    private static final int ID_REFRESH_BATCHES = 5;

    /**
     * Batches between unconditional re-reads of Create's graph.
     *
     * <p>The manager's counter says when the network changed, so this is only a safety net for a
     * change that counter did not move for: five seconds, at which point the graph is read again and
     * rebuilt if it differs.
     */
    private static final int GRAPH_RECHECK_BATCHES = 5;

    /** The blockstate property a track block's shape lives in, as Create names it. */
    private static final String SHAPE_PROPERTY = "shape";

    /** Index of the shape used when a block has no shape property, or one this table does not know. */
    private static final int UNKNOWN_SHAPE = 0;

    /**
     * Ceiling on remembered track blocks.
     *
     * <p>The scan radius bounds this anyway; the cap is what makes the bound a promise rather than an
     * expectation on a server with a rail network larger than any the radius was chosen for. Reaching
     * it stops the scan rather than growing without limit, and says so once.
     */
    private static final int MAX_TRACK_BLOCKS = 100_000;

    /**
     * Every shape this layer knows, by index, with {@link #UNKNOWN_SHAPE} first.
     *
     * <p>Filled from {@link #define} in declaration order; the table is data, not code, which is why
     * the axes are written out here the way Create declares them.
     */
    private static final List<Shape> SHAPE_TABLE = new ArrayList<>();
    /** Shape name as a blockstate serialises it, to its index in {@link #SHAPE_TABLE}. */
    private static final Map<String, Integer> SHAPE_IDS = new HashMap<>();
    /** Shape index per block state, looked up once per state rather than once per block. */
    private static final Map<BlockState, Integer> STATE_SHAPES = new IdentityHashMap<>();

    static {
        // Reaching in every direction around it, for a shape whose name is not in the table below:
        // a track that cannot be placed correctly is better than a line that vanishes mid-way.
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
    /** Station block positions found so far, by chunk. */
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
     *
     * <p>Exactly one of the two is ever the layer, so no track can be counted twice: Create's graph
     * is preferred because it is Create's own data, and the block scan is used only when the graph
     * gave nothing to build from.
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

    // ------------------------------------------------- TEMPORARY rail diagnostic
    // Everything from here to the matching banner is scaffolding for one round of in-game
    // diagnosis: the one line a second, and the two counters the drawing side feeds. Delete this
    // block, the report() call in tick(), and the two call sites marked "TEMPORARY rail
    // diagnostic" once the layer is seen working. Nothing else reads any of it.

    /** Chunks read since the layer started, and how many of them held anything. */
    private static long chunksRead;
    private static long chunksWithContent;
    /**
     * What the map side has done with the layer, counted since the layer started.
     *
     * <p>{@code mapPasses} is one per enumeration of the elements, and Xaero asks for them once per
     * {@code ElementRenderLocation} per frame, so an ordinary frame is more than one pass over the same
     * layer -- which is why {@code mapHandedOver} is a multiple of {@code mapStroked} rather than equal
     * to it. {@code mapHandedOver} is one per rail element offered in a pass, {@code mapStroked} one
     * per rail element actually drawn. Offers are attributed to the location that made them, because
     * {@code begin} is told the location; strokes are not attributed, because {@code renderElement} is
     * not, and a guess at which pass a stroke belonged to would be exactly the kind of invented number
     * this diagnostic exists to avoid. The invariant to read is
     * {@code mapHandedOver / mapPasses == segments in the layer}: if that ever fails, the layer being
     * drawn is not the layer being reported.
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

    /** TEMPORARY rail diagnostic: the provider began enumerating the layer for one location. */
    public static void noteMapPass(int location) {
        mapPasses++;
        if (location >= 0 && location < offersByLocation.length) {
            offersByLocation[location]++;
        }
    }

    /** TEMPORARY rail diagnostic: one rail element was offered to the map's pipeline. */
    public static void noteElementOffered() {
        mapHandedOver++;
    }

    /** TEMPORARY rail diagnostic: one of this layer's segments was actually stroked on the map. */
    public static void noteElementStroked() {
        mapStroked++;
    }

    /**
     * TEMPORARY rail diagnostic: whether a segment came from this layer rather than from the editor.
     *
     * <p>By id range, which is also what keeps the two apart everywhere else.
     */
    public static boolean isOurs(RoadSegment segment) {
        return segment != null && segment.id() >= ID_BASE;
    }

    /**
     * TEMPORARY rail diagnostic: the first few segments' two ends and vertex count, written whenever
     * the layer's size changes.
     *
     * <p>Not per second: it is there so that a line seen on the map which no edge connects can be
     * matched against the numbers. Each entry says which segment it is, where its nodes are, how many
     * vertices it has, and where its polyline actually starts and ends -- a polyline end that does not
     * match its node is the signature of invented geometry, and it would be visible here rather than
     * argued about.
     */
    private static void reportSegments() {
        if (!HowToGo.LOGGER.isInfoEnabled()) {
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
        HowToGo.LOGGER.info("[HowToGo] rail seg | source={} segments={}{}", source,
                coarse.segmentCount(), line);
    }

    /**
     * TEMPORARY rail diagnostic: the single line a second, tag {@code [HowToGo] rail}.
     *
     * <p>Written to tell apart the failures that look identical from outside: the scan finding
     * nothing, the layer being empty after a scan that found blocks, and the layer existing but
     * never reaching the map. It also names the source, because "Create's graph gave us the tracks"
     * and "we read them out of the blocks ourselves" are different answers with different next steps.
     * The map figures are the last second's deltas followed by the session totals, because a total
     * on its own cannot say whether anything is being drawn *now*.
     */
    private static void report() {
        if (!HowToGo.LOGGER.isInfoEnabled()) {
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
        HowToGo.LOGGER.info("[HowToGo] rail | enabled={} tracks={} stations={} | source={} graphs={} "
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
    // --------------------------------------------- end TEMPORARY rail diagnostic

    /** A train station, as a place to navigate to; the name is Create's when the graph supplied it. */
    public record Station(int x, int y, int z, String name) {
    }

    /** The track blocks of one chunk, and the shape of each, in the same order. */
    private record ChunkTracks(long[] positions, byte[] shapes) {
    }

    /** Where a track block's own shape says it reaches, as packed one-block offsets. */
    private record Shape(byte[] offsets, boolean known) {

        /** Whether this shape reaches along the given offset. */
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
     * The graph the compression walks, whichever source built it.
     *
     * <p>Adjacency is a flat edge list: {@code head[node]} is the first edge at that node and edges
     * pair as {@code e} and {@code e ^ 1}, so the reverse of an edge is its neighbour in the arrays.
     * Geometry is per <em>directed</em> edge, already oriented from its {@code from} end, because the
     * two sources differ: the block scan's edges are one block long, and Create's edges are its own
     * pieces of track, a curve among them carrying its sampled Bezier.
     */
    private record Graph(int[] head, int[] other, int[] next, int[] degree, int edges,
                         double[] nodeX, double[] nodeY, double[] nodeZ, double[] vertexX,
                         double[] vertexZ, int[] vertexStart, int[] vertexCount) {

        /** The first edge at a node that the walk has not used, or -1. */
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

    // ------------------------------------------------------------------ public

    /**
     * Called every client tick. Nineteen ticks out of twenty it does nothing but count; the
     * twentieth is one second's worth of work.
     *
     * <p>The cadence is a period rather than a "has it been long enough" test on purpose. An earlier
     * version asked whether {@code ticks - lastRebuild >= interval} and seeded {@code lastRebuild}
     * with {@link Long#MIN_VALUE} as "never"; the subtraction overflowed to a large negative number
     * on every tick, so the test never came true and the coarse layer was never built at all -- a
     * layer that scanned correctly and then drew nothing. A period cannot say "never" wrongly.
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
            // Left the world. Nothing here survives it: the blocks are only ever what is loaded.
            if (boundLevel != null) {
                clear();
            }
            return;
        }
        if (level != boundLevel) {
            clear();
            boundLevel = level;
            // The ids are read at the moment there is something to read them for, rather than
            // waiting for the periodic re-read, so the first pass of a world is not delayed by it.
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
            // Nothing is configured to look for, so there is no reason to read a chunk at all.
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

        // Create's own graph first. It is the data Create itself draws on the map, so while it answers
        // there is nothing for the block scan to do and it is not run at all.
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
                    // It answered, and the answer is an empty network: whatever it built before is no
                    // longer there, so it is dropped rather than left on the map. The scan below gets
                    // its turn either way.
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

        // The chunk underfoot is read every pass whether or not it is still fresh, so a track laid
        // where the player is standing shows up within the second rather than at the next sweep.
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

        // Once a second, inside the second's own pass: no "am I due" arithmetic, and so no way for
        // the rebuild to be skipped by accident, which is exactly how the first version failed.
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

    /** Whether the layer has anything in it and is switched on. */
    public static boolean active() {
        return RoadConfig.createTrainTracks() && coarse.segmentCount() > 0;
    }

    /**
     * The layer's segments, for drawing.
     *
     * <p>A live view of a network only this class builds, handed out to read: nothing outside may
     * add to it. Drawing reads it directly so a track is drawn from the same geometry the router
     * routes along, rather than from a second copy that could disagree.
     */
    public static Collection<RoadSegment> segments() {
        return coarse.segments();
    }

    /**
     * The layer as a network, for the two things that need topology rather than geometry: hit-testing
     * a click against a rail, and walking a chain of rail segments to name a whole line.
     *
     * <p>Handed out to read. Nothing outside may add to it, and nothing does: the editor's own
     * network is the saved one, so a rail can be named but never redrawn, moved or deleted. That is
     * the point of the layer -- it is a reading of what is in the world, and an edit to it would be
     * overwritten by the next reading anyway.
     */
    public static RoadNetwork network() {
        return coarse;
    }

    /**
     * Makes a freshly built network the layer, stamping the stored names onto it first.
     *
     * <p>Every assignment to the layer goes through here, and that is deliberate: a rebuild numbers
     * its segments from scratch, so a build that skipped the naming step would draw a named railway
     * with no name and silently lose the player's work until the next rebuild happened to name it
     * again. With one way in, that cannot be forgotten at a new call site.
     */
    private static void adopt(RoadNetwork built) {
        coarse = built;
        RailNameStore.apply(coarse);
    }

    /**
     * Every station the layer is offering, in no particular order.
     *
     * <p>From Create's graph these carry the name the player gave the station; from the block scan
     * they carry none, because a station's name is not in the world's blocks, only in Create's
     * railway data.
     */
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
     * The network to plan on: the saved roads, with this layer merged in when the trip can use rail.
     *
     * <h2>Why the unchanged network is returned as-is</h2>
     * A mode that cannot travel on rail -- walking and driving -- and a policy that avoids it both
     * get {@link RoadStore#get()} itself, so there is no rail node and no rail segment anywhere in
     * the graph they are planned on. Nothing about their routes can change, and that is a property of
     * this method rather than a claim about the router's filters, which is the version of the
     * argument that survives the next change to those filters.
     *
     * <h2>Why the merge is a copy</h2>
     * The saved network is not touched, and neither is this layer: the router splits segments while
     * anchoring, so it needs something it may mutate, and it gets copies of both. The layer is
     * therefore read-only in the strong sense -- nothing downstream holds a reference it could write
     * through.
     */
    public static RoadNetwork forRouting(TravelMode mode, RoutePreferences preferences) {
        RoadNetwork handDrawn = RoadStore.get();
        if (!active() || mode == null || !mode.allows(RoadClass.RAIL)
                || (preferences != null && preferences.avoids(RoadClass.RAIL))) {
            return handDrawn;
        }
        RoadNetwork merged = handDrawn.deepCopy();
        for (RoadNode node : coarse.nodesSnapshot()) {
            merged.putNode(node.copy());
        }
        for (RoadSegment segment : coarse.segmentsSnapshot()) {
            merged.putSegment(segment.copy());
        }
        return merged;
    }

    // ------------------------------------------------------------------ config

    /**
     * Re-reads the configured block ids, parsing them once.
     *
     * <p>Parsed rather than compared as text because the test runs inside the block loop of a
     * section scan, where a {@code toString} per block would be the expensive part of the feature.
     * The lists are compared first, so a config that has not changed costs nothing.
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

    // ------------------------------------------------------------------- scan

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
     * Drops what the block scan remembered.
     *
     * <p>Done when Create's graph becomes the source, so the scan's memory is not held for a layer
     * that is not being drawn from it, and again on every level change.
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
     * Queues every chunk within the configured radius, in rings outward from the player.
     *
     * <p>Rings rather than rows so the chunks nearest the player are read first: a pass over the map
     * is allowed to take a while, and what the player is looking at should not be the last thing in
     * it. The order is square (Chebyshev) distance, because chunks are square.
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
     *
     * @param forced read regardless of how recently it was read, used for the player's own chunk
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

        // The client's own chunk cache, which never blocks and never asks the server for anything: a
        // chunk that is not loaded comes back as an empty placeholder, and reading one as empty is
        // right -- a track nobody has loaded is a track nobody can see.
        LevelChunk chunk = level.getChunk(chunkX, chunkZ);
        List<Long> tracks = new ArrayList<>();
        List<Byte> shapes = new ArrayList<>();
        List<Long> stations = new ArrayList<>();
        if (chunk != null) {
            collect(chunk, tracks, shapes, stations);
        }
        scannedAt.put(key, ticks);
        // TEMPORARY rail diagnostic: the two counters, updated here so nothing is measured outside
        // the pass. Cheap increments, no allocation.
        chunksRead++;
        if (!tracks.isEmpty() || !stations.isEmpty()) {
            chunksWithContent++;
        }
        storeTracks(key, tracks, shapes);
        storeStations(key, stations);
    }

    /**
     * Walks the sections of a chunk that could hold anything, and keeps the track and station blocks.
     *
     * <p>The palette test comes first for every section: it answers "could this section hold one of
     * these blocks at all" from the section's state palette, which is a handful of comparisons,
     * instead of from 4096 block reads. Only a section that passes is walked, which is what keeps a
     * chunk with no rail in it nearly free.
     */
    private static void collect(LevelChunk chunk, List<Long> tracks, List<Byte> shapes,
                                List<Long> stations) {
        LevelChunkSection[] sections = chunk.getSections();
        int minY = chunk.getMinBuildHeight();
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
     * Replaces what is remembered for a chunk, and flags the coarse layer if it actually changed.
     *
     * <p>Compared rather than assumed: a pass over the map re-reads every chunk in it, and without
     * this every pass would rebuild the whole coarse layer to arrive at the same answer.
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

    // ------------------------------------------------------------------ shape

    /**
     * The shape of a block state, as an index into {@link #SHAPE_TABLE}.
     *
     * <p>Cached per state: the lookup is a search through the state's properties, and a chunk full of
     * track is a chunk full of the same handful of states.
     */
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
     * The shape property's serialised value, or null when the state has no property by that name.
     *
     * <p>Read through Minecraft's own {@link StringRepresentable} rather than Create's enum, which is
     * what keeps this free of any Create class: the value is asked for its name and nothing else.
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
     *
     * <p>Both signs of every axis become offsets, because an axis is a line through the block and its
     * two ends are both connections. This is Create's {@code TrackShape} table -- the axis of a track
     * block is the direction its rails run in, and for a curve or a crossing there are two of them.
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

    /** The fallback shape: every straight and diagonal neighbour, one block up and one block down. */
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

    // ---------------------------------------------------------------- topology

    /**
     * Builds the layer from Create's own graph, <b>one segment per Create edge</b>.
     *
     * <h2>Why nothing is joined here</h2>
     * Create's own map draws each edge on its own: a straight edge as a line between its two nodes, a
     * curve from the points of {@code BezierConnection.rasterise()}. Nothing in it decides that two
     * edges form one road, and nothing in it can therefore invent a line between two edges that do not
     * meet. An earlier version here walked runs of edges through pass-through nodes and stitched them
     * into one polyline, and every bug that produced phantom geometry came from that stitching: each
     * edge's geometry had to be assumed to start where the previous one ended. Taking the edges one at
     * a time removes the assumption instead of guarding it -- there is no order beyond an edge's own
     * two ends, and no vertex can come from any edge but its own.
     *
     * <p>Segments still meet, because the edges that meet at a node are given that node's coordinates
     * (the ends of each polyline are the node positions): meeting is a consequence of shared
     * coordinates, not of joining.
     *
     * <p>The cost is one segment per edge instead of one per run -- twelve instead of four in the case
     * that prompted this -- which is nothing for a graph of this size, and a segment per edge is
     * exactly what a hand-drawn road already is. The router, the map and the class colours see the
     * same kind of network either way, and {@code RoadChains} still gathers a run of edges into one
     * road for naming and for the manoeuvre rule, because a node between two edges has degree two.
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
            for (int v = 0; v < vertices; v++) {
                segment.addVertex(round(polyline[v * 2]), round(polyline[v * 2 + 1]));
            }
            segment.setFromNode(fromId);
            segment.setToNode(toId);
            build.network.putSegment(segment);
        }

        adopt(build.network);
        lastEdgeCount = edges.size();
        source = Source.CREATE_GRAPH;
        layerReported = true;
        reportSegmentsIfChanged();
        if (!reportedGraphBuild) {
            reportedGraphBuild = true;
            HowToGo.LOGGER.info("[HowToGo] Create track layer from Create's graph: {} nodes, {} edges "
                            + "({} curved), {} segments, {} stations",
                    nodeX.length, edges.size(), snapshot.curves(), coarse.segmentCount(),
                    snapshot.stations().size());
        }
    }

    /**
     * Builds the layer from the block scan: track blocks joined along their own axes, then runs of
     * track between two junctions compressed into one polyline each.
     *
     * <p>Used when Create's graph is not there to read -- no Create, a renamed class, or a graph the
     * client has not been sent. Only one of the two sources is ever the layer, so a track can never
     * be counted twice.
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
            HowToGo.LOGGER.info("[HowToGo] Create track layer from the block scan: {} track blocks in "
                            + "{} segments, {} stations", count, coarse.segmentCount(), stationBlockCount());
        }
        reportSegmentsIfChanged();
    }

    /** Writes the segment dump again only when the layer's size changed, so it cannot become noise. */
    private static void reportSegmentsIfChanged() {
        if (coarse.segmentCount() != dumpedSegments) {
            dumpedSegments = coarse.segmentCount();
            reportSegments();
        }
        reportStationsIfChanged();
    }

    /**
     * TEMPORARY rail diagnostic: every station's position and name, written when that set changes.
     *
     * <p>Wanted because a station's name was reported as landing slightly beside the station rather
     * than on it. This says which point the name is being drawn from, so the offset can be compared
     * with the station block that is actually in the world instead of being guessed at -- and it
     * pairs with the block scan, which finds stations by their blocks and can be asked for the same
     * thing from the other source.
     */
    private static void reportStationsIfChanged() {
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
        HowToGo.LOGGER.info("[HowToGo] rail station | source={} count={}{}", source, stationCount(), line);
    }

    /**
     * Joins every track block to the blocks at the ends of its own axes.
     *
     * <p>A join needs both blocks to reach along the shared axis: a track's ends are at particular
     * points of its block -- a corner for a diagonal, an edge for a straight -- so two tracks in
     * touching blocks only meet where each of them actually runs to that point. Two parallel tracks
     * laid against each other stay two tracks, and a diagonal piece beside a straight one does not
     * merge into it.
     *
     * <p>Edges are stored both ways round, so a block whose shape this table does not know still ends
     * up in the graph through its neighbours' claims and does not break the line it sits in.
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
        // One block long, so every edge is its own two node positions: the same graph shape the
        // compression walks for Create's graph, only without the geometry in between.
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
     * Compresses a graph into one polyline per run of track between two junctions.
     *
     * <h2>Why the walk here is not {@link bili.dongsz.howtogo.road.RoadChains}</h2>
     * {@code RoadChains} is the right tool for the saved network, which is a few hundred segments
     * and is walked a handful of times. It cannot be the tool for this step: it finds the segment
     * continuing through a node by scanning every segment of the network, which over a graph of tens
     * of thousands of edges is quadratic, and this runs on a client tick. So the compression is done
     * here, in one pass over an adjacency table built once, and the result is a plain
     * {@link RoadNetwork}: from that point on -- the router's degree counts, its graph, the map --
     * everything downstream is the same code that already handles a hand-drawn rail, whichever source
     * the geometry came from.
     *
     * <p>Nodes are only created at junctions and ends, and shared between every run that meets at
     * them, so a fork is one node rather than three stacked on one point. Ends and junctions are
     * walked first, so every run comes out whole; walking from the middle of one would split it in two.
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
        // Whatever is left is track with no end in it: a closed loop. It is walked from wherever the
        // reading happened to meet it and closes back on its first node.
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
     * Follows the track from one node along one edge until the run ends, filling {@code edgePath}.
     *
     * <p>The end is a node that is not a pass-through -- fewer than two connections or more than two
     * of them -- or the node the walk started from, which is how a loop closes.
     */
    private static int walk(int start, int firstEdge, Graph graph, boolean[] used, int[] edgePath) {
        int length = 0;
        int edge = firstEdge;
        while (edge >= 0 && length < edgePath.length) {
            used[edge] = true;
            // Edges are appended in pairs, so the edge back along this one is its neighbour in the
            // array. Marking it too is what stops the walk from turning round and coming back.
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
     * One polyline per run of track, between the nodes at its two ends.
     *
     * <p>Each edge of the run contributes its geometry less its first vertex, which is the point the
     * previous edge already laid down. Coordinates are rounded to whole blocks on the way in: the
     * layer is a plain {@link RoadNetwork} whose vertices are block coordinates, so a curve sampled
     * from Create keeps its shape but not its half-block precision. That is invisible at map scale
     * and well inside the six blocks of tolerance a rail is judged "on route" by, and it is what
     * keeps the editor, the storage format and the renderer untouched.
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
        // A closed loop ends where it started, so its single node is both ends of the segment.
        int toId = lastNode == startNode ? fromId : nodeId(build, graph, lastNode);

        RoadSegment segment = new RoadSegment(build.nextId++, RoadClass.RAIL,
                (int) Math.round(graph.nodeY()[startNode]), vertices);
        segment.addVertex(round(graph.nodeX()[startNode]), round(graph.nodeZ()[startNode]));
        for (int i = 0; i < length; i++) {
            int edge = edgePath[i];
            int first = graph.vertexStart()[edge];
            int count = graph.vertexCount()[edge];
            for (int v = 1; v < count; v++) {
                segment.addVertex(round(graph.vertexX()[first + v]), round(graph.vertexZ()[first + v]));
            }
        }
        segment.setFromNode(fromId);
        segment.setToNode(toId);
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
     * How many stations the layer is offering, whichever source built it.
     *
     * <p>Counted rather than listed: the diagnostic asks for this every second, and the picker is the
     * only thing that needs the list.
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
