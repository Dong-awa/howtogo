package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.HowToGo;
import bili.dongsz.howtogo.RoadConfig;
import bili.dongsz.howtogo.road.RoadClass;
import bili.dongsz.howtogo.road.RoadCurve;
import bili.dongsz.howtogo.road.RoadDirection;
import bili.dongsz.howtogo.road.RoadNetwork;
import bili.dongsz.howtogo.road.RoadNode;
import bili.dongsz.howtogo.road.RoadSegment;
import bili.dongsz.howtogo.route.TravelMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * The roads RoadWeaver generated, read out and offered as a read-only layer of this mod's roads.
 * RoadWeaver generates roads between structures as polylines; this converts them into RoadNetwork segments so they can be drawn, planned over and their structures offered as destinations. Nothing is written to the player's network and nothing is editable -- the next reading replaces this layer whole.
 * Two sources: RoadWeaver's client map snapshot (MapSnapshotCache), filled only once the player opens its map; and, while hosting, its road store via RoadShardStorage loadAll, preferred because it is the whole network and needs no map. Only one is current at a time, so no road is counted twice.
 * Graph: a node at every road endpoint and wherever two roads share a point exactly; a crossing without a shared point is not a junction, and no topology is invented.
 * RoadWeaver trims roads short of the structures they serve, so for each completed connection this layer adds a straight two-point connector of the same class to the structure, bounded by JOIN_REACH. Connectors are routing links only: network() has them, drawnNetwork() does not.
 * The class is always PATH (roadweaver_road_class can force another). Structure aliases are read reflectively and re-read once a second. No RoadWeaver type appears in a signature; an unavailable version reports itself and the mod behaves as before.
 */
public final class RoadWeaverLayer {

    /** RoadWeaver's mod id. */
    static final String MOD_ID = "roadweaver";

    /**
     * Node and segment ids of this layer start here. Ids are made disjoint from every other network's (Create 1_000_000_000, MTR 1_500_000_000), so a reading held by mistake against the wrong network gives nothing rather than somebody else's road.
     */
    private static final int ID_BASE = 2_000_000_000;

    /**
     * How often the whole road store is re-read on the integrated server, in client ticks -- 200 ticks, ten seconds. The read is cheap but not free, and a newly generated road does not need to be seen within the second; the first read of a session is not delayed.
     */
    private static final int SERVER_POLL_TICKS = 200;

    /** The height a structure marker is given when RoadWeaver itself stores no height for it. */
    private static final int FALLBACK_STRUCTURE_Y = 64;

    /** Width used for a reading that does not carry one, which is the client snapshot. */
    private static final int UNKNOWN_WIDTH = -1;

    /**
     * How far from a structure a road end may stand and still be read as the road it was built for, in blocks. That is RoadWeaver's maximum trim (sixty for a village, fifteen otherwise by default), so a gap it created on purpose is always closable, and a connection whose road is absent is not joined to a nearby road by guess.
     */
    private static final int JOIN_REACH = 256;

    /**
     * How many ticks between re-readings of the structure aliases: one second. They are the player's own notes in RoadWeaver's map, not part of the road data, so nothing else notices one being edited; the read is a lookup per structure, cheap enough for a second and far too frequent for a frame.
     */
    private static final int ALIAS_POLL_TICKS = 20;

    /**
     * Closed form of one connection RoadWeaver completed: the two structures it joined. Only COMPLETED connections are read, because only those have a road behind them.
     */
    record Connection(Point from, Point to) {
    }


    /**
     * The conversion runs off both the client and the server thread. A whole road network is a lot of geometry to copy: the client thread would hitch a frame, the server thread a tick. Both readings are immutable snapshots when handed over, so a thread of this mod's own is safe.
     */
    private static final ExecutorService CONVERTER = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "howtogo-roadweaver-import");
        thread.setDaemon(true);
        return thread;
    });

    /** The reading in force. Replaced whole; never mutated. */
    private static volatile Reading reading = Reading.EMPTY;

    /** Bumped whenever reading is replaced, for anything caching a reading of it. */
    private static volatile int revision;

    /** Bumped when the world, the dimension or the source changes, so stale conversions are dropped. */
    private static volatile int epoch;

    /** The client level the current bind belongs to. */
    private static Object boundLevel;

    /** Whether the last tick read the server-side store rather than the client cache. */
    private static volatile boolean directSource;

    /** Whether a server-side reading has arrived for this world. */
    private static volatile boolean serverReadingKnown;

    /** The signature of the road store the last server-side reading was converted from. */
    private static volatile String serverReadingSignature;

    /** Ticks until the next server-side read is allowed, and whether one is already queued. */
    private static int serverPollCooldown;
    private static boolean serverPollPending;

    /** Serial numbers so a conversion that finishes late cannot overwrite a newer one. */
    private static int serverRequestSeq;
    private static volatile int cacheRequestSeq;

    /** The client snapshot last converted, by identity: RoadWeaver replaces it whole when it changes. */
    private static Object lastCacheSnapshot;

    /**
     * Whether a client-snapshot conversion is already running. RoadWeaver's map can replace its snapshot far more often than one conversion takes, and submitting a conversion per snapshot would queue a backlog that is stale the moment it starts; a new snapshot waits, and the following tick converts the newest.
     */
    private static volatile boolean cacheConversionBusy;


    private static boolean resolved;
    private static boolean available;
    private static boolean fullMapAvailable;
    private static boolean warned;
    private static boolean serverReported;
    private static boolean badClassReported;
    private static boolean readFailureReported;

    private static Method peekMethod;
    private static Method roadPolylinesMethod;
    private static Method structuresMethod;
    private static Method structureNameMethod;

    private static Method loadAllMethod;
    private static Method roadWidthMethod;
    private static Method roadTypeMethod;
    private static Method roadSegmentsMethod;
    private static Method placementMiddleMethod;
    private static Method ownerAMethod;
    private static Method ownerBMethod;

    private static Method structureSnapshotMethod;
    private static Method structureLocationsOfSnapshotMethod;
    private static Method structureInfosMethod;
    private static Method structurePosMethod;
    private static Method structureIdMethod;

    /** The client snapshot's connections, and the shape of one connection either source hands back. */
    private static Method snapshotConnectionsMethod;
    private static Method structureConnectionsMethod;
    private static Method connectionFromMethod;
    private static Method connectionToMethod;
    private static Method connectionStatusMethod;

    /** ClientMapNotes.getAlias(BlockPos), or null on a version that has no notes. */
    private static Method aliasMethod;


    /**
     * The aliases in force, keyed by structure position with the height flattened away -- RoadWeaver's map normalises structures to y=0, so the stored height would never match. Read on the client thread and replaced whole.
     */
    private static volatile Map<Long, String> aliases = Map.of();

    /** Bumped whenever aliases is replaced, so a list cached against it can be rebuilt. */
    private static volatile int aliasRevision;

    /** The layer revision the aliases were last read for, and the ticks until the next read. */
    private static volatile int aliasSourceRevision = -1;
    private static int aliasPollCooldown;

    private RoadWeaverLayer() {
    }


    /** The segments to draw, for the dimension the player is in. Empty when there is no reading. */
    public static List<RoadSegment> segments() {
        return reading.segments();
    }

    /**
     * The roads the maps draw: every segment except the synthetic connectors. The connectors are straight stubs up to JOIN_REACH blocks long and are not roads, so drawing them produced the long straight lines across the map. Every map draws this list; routing, snapping and the "which road am I on" reads keep segments().
     */
    public static List<RoadSegment> drawnSegments() {
        return reading.drawnSegments();
    }

    /** The structures the roads connect, as destinations. Empty when there is no reading. */
    public static List<Structure> structures() {
        return reading.structures();
    }

    /**
     * The alias RoadWeaver's own map has for a structure, or null when it has none. Looked up by position with the height flattened, the key RoadWeaver stores it under. Client thread only, which is where every caller is.
     */
    public static String aliasOf(Structure structure) {
        if (structure == null) {
            return null;
        }
        String alias = aliases.get(flattenedKey(structure.x(), structure.z()));
        return alias == null || alias.isBlank() ? null : alias;
    }

    /** Bumped when the aliases change, for a caller caching names built from them. */
    public static int aliasRevision() {
        return aliasRevision;
    }

    /**
     * The reading as this mod's own network, for a caller that wants to snap to it rather than plan over it. Never written to; the layer replaces it whole. This is the routing network, connectors included -- see drawnNetwork().
     */
    public static RoadNetwork network() {
        return reading.network();
    }

    /**
     * The same reading with the synthetic connectors left out, for the maps. See drawnSegments(): the connectors are routing links, and a map that drew them showed straight lines across the landscape that no road was under.
     */
    public static RoadNetwork drawnNetwork() {
        return reading.drawnNetwork();
    }

    /**
     * Offers every segment whose bounding box overlaps the rectangle. For the views that draw a small window of the map, walking a whole network's vertices per frame is the slow part; bounds are worked out once when a reading is published, so the reject is four integer comparisons per segment.
     */
    public static void forEachSegmentInBox(double minX, double minZ, double maxX, double maxZ,
                                           Consumer<RoadSegment> sink) {
        Reading current = reading;
        List<RoadSegment> segments = current.segments();
        int[] bounds = current.bounds();
        for (int i = 0; i < segments.size(); i++) {
            int o = i * 4;
            if (bounds[o] > maxX || bounds[o + 2] < minX
                    || bounds[o + 1] > maxZ || bounds[o + 3] < minZ) {
                continue;
            }
            sink.accept(segments.get(i));
        }
    }

    /**
     * The network a trip may be planned over, or null when this layer cannot take part. Read-only in the strong sense: the caller folds a copy into the network it plans on (see RailTrackStore forRouting), which keeps the player's roads untouched. A mode that cannot travel on these classes is given nothing.
     */
    public static RoadNetwork routingNetwork(TravelMode mode) {
        if (mode == null || !RoadConfig.roadWeaverRoads() || !RoadConfig.roadWeaverRouting()) {
            return null;
        }
        // The class the reading was built as, not a fixed one: under "auto" these are footpaths, and
        // a player who forced a drivable class must be given the layer to a car as well as to boots.
        RoadClass forced = forcedClass();
        boolean usable = forced != null
                ? mode.allows(forced)
                : mode.allows(RoadClass.PATH) || mode.allows(RoadClass.ROAD);
        if (!usable) {
            return null;
        }
        Reading current = reading;
        return current.network().segmentCount() == 0 ? null : current.network();
    }

    /** Bumped whenever the layer is replaced, for a caller caching a reading of it. */
    public static int revision() {
        return revision;
    }

    /**
     * Forgets the reading, which is what switching the integration off does. Also called when the class choice is edited, because the class is baked into the network this layer holds and the config cannot take effect until the conversion runs again.
     */
    public static void invalidate() {
        epoch++;
        lastCacheSnapshot = null;
        serverReadingSignature = null;
        serverReadingKnown = false;
        clearReading();
        forgetAliases();
    }

    /**
     * One client tick: follows the world, and asks whichever reader is in force for its layer. The steady-state cost is one mod-presence check, one MapSnapshotCache peek and -- while hosting -- one counter; nothing is copied unless a reading changed.
     */
    public static void tick() {
        if (!RoadConfig.roadWeaverRoads()) {
            // Off means forgotten rather than merely not read again: a whole road network left lying
            // about would go on being drawn and planned over after the player asked for it not to,
            // and the keys below would make switching it back on reuse a stale snapshot.
            clearBinding();
            return;
        }
        if (!resolve()) {
            clearBinding();
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null || minecraft.player == null) {
            clearBinding();
            return;
        }
        if (!Level.OVERWORLD.equals(level.dimension())) {
            // RoadWeaver's roads are overworld-only: its store refuses every other dimension, and its
            // map's own snapshot for one is empty. Nothing to read, and no reason to keep the last
            // dimension's coordinates.
            clearBinding();
            return;
        }

        ResourceLocation dimension = level.dimension().location();
        if (level != boundLevel) {
            // A new world (or a new session on one): everything held describes a place that is no
            // longer loaded, and a server reading queued for the old one must not land on this one.
            epoch++;
            clearReading();
            boundLevel = level;
            lastCacheSnapshot = null;
            serverReadingKnown = false;
            serverReadingSignature = null;
            directSource = false;
        }

        MinecraftServer server = minecraft.getSingleplayerServer();
        // Before either reader runs: the aliases belong to RoadWeaver's own map notes, not to
        // whatever the roads are read from, so they are refreshed independently of a rebuild.
        pollAliases();
        boolean direct = RoadConfig.roadWeaverFullMap()
                && server != null && server.isRunning() && fullMapAvailable;
        if (direct != directSource) {
            // Switching readers: the layer each one produces is a different set of roads, so nothing
            // of the old one is kept rather than shown beside the new one.
            epoch++;
            directSource = direct;
            serverReadingKnown = false;
            serverReadingSignature = null;
            lastCacheSnapshot = null;
            clearReading();
        }

        if (direct) {
            pollServer(server, epoch);
            if (!serverReadingKnown) {
                // Before the first server reading arrives, and if the store cannot be read at all, the
                // cache is the only data there is; it is also exactly what a LAN guest would get.
                readCache(dimension, epoch);
            }
            return;
        }
        serverReadingKnown = false;
        readCache(dimension, epoch);
    }


    /**
     * Reads RoadWeaver's client-side map snapshot, if it has one for this dimension. The snapshot is replaced whole whenever RoadWeaver's map fetches more or receives a patch, so identity says whether anything changed; the conversion itself is handed to the converter thread.
     */
    private static void readCache(ResourceLocation dimension, int expectedEpoch) {
        Object snapshot;
        try {
            snapshot = peekMethod.invoke(null, dimension);
        } catch (ReflectiveOperationException | RuntimeException failed) {
            reportFailure("client map snapshot", failed);
            return;
        }
        if (snapshot == null) {
            if (lastCacheSnapshot != null) {
                lastCacheSnapshot = null;
                clearReading();
            }
            return;
        }
        if (snapshot == lastCacheSnapshot || cacheConversionBusy) {
            // Nothing new, or the conversion of a previous snapshot is still running: the next tick
            // after it finishes sees this one is the latest and converts it then. See the comment on
            // cacheConversionBusy.
            return;
        }
        lastCacheSnapshot = snapshot;
        cacheConversionBusy = true;
        int request = ++cacheRequestSeq;
        // Read on the client thread: a config value is not something to ask from the converter thread,
        // and the answer cannot change between here and the conversion anyway.
        RoadClass forced = forcedClass();
        CONVERTER.execute(() -> {
            try {
                List<Polyline> roads = cachePolylines(snapshot);
                List<Connection> connections = cacheConnections(snapshot);
                List<Structure> structures = cacheStructures(snapshot);
                Converted converted = convert(roads, connections, forced);
                publish(new Reading(converted.network(), converted.drawn(), converted.segments(),
                        converted.drawnSegments(), boundsOf(converted.segments()), structures,
                        "client map snapshot"), expectedEpoch, request, false);
            } catch (ReflectiveOperationException | RuntimeException failed) {
                // Forget the failed snapshot so the next tick retries it instead of treating it as
                // already converted. A transient reflection failure must not hide the layer forever.
                lastCacheSnapshot = null;
                reportFailure("client map snapshot", failed);
            } finally {
                cacheConversionBusy = false;
            }
        });
    }

    /** The polylines of one client snapshot, as this class's own geometry. */
    private static List<Polyline> cachePolylines(Object snapshot) throws ReflectiveOperationException {
        List<Polyline> roads = new ArrayList<>();
        for (Object raw : elements(roadPolylinesMethod.invoke(snapshot))) {
            List<Point> points = new ArrayList<>();
            for (Object point : elements(raw)) {
                if (point instanceof BlockPos pos) {
                    // The snapshot is flat by design: RoadWeaver's map works in X and Z and its
                    // polylines carry no height. See the class comment on where that costs a junction.
                    points.add(new Point(pos.getX(), 0, pos.getZ()));
                }
            }
            if (points.size() >= 2) {
                roads.add(new Polyline(points, UNKNOWN_WIDTH, 0));
            }
        }
        return roads;
    }

    /** The structure points of one client snapshot, as this class's own places. */
    private static List<Structure> cacheStructures(Object snapshot) throws ReflectiveOperationException {
        List<Structure> structures = new ArrayList<>();
        for (Object raw : elements(structuresMethod.invoke(snapshot))) {
            if (!(raw instanceof BlockPos pos)) {
                continue;
            }
            Object name = structureNameMethod.invoke(snapshot, pos);
            // The height is kept as the snapshot states it -- flat, zero -- rather than replaced
            // here: it is what an alias was keyed under. The drawing height is chosen by
            // Structure.markerY, which is the only reader that needs a real one.
            structures.add(new Structure(pos.getX(), pos.getY(), pos.getZ(),
                    name instanceof String text ? text : null));
        }
        return sortedStructures(structures);
    }

    /** The completed connections of one client snapshot, as this class's own pairs. */
    private static List<Connection> cacheConnections(Object snapshot) throws ReflectiveOperationException {
        if (snapshotConnectionsMethod == null) {
            return List.of();
        }
        return readConnections(snapshotConnectionsMethod.invoke(snapshot));
    }

    /**
     * The completed connections of a reading, whatever shape the source hands them back in. Only COMPLETED ones have a road behind them; a version whose connection shape this does not know reports itself unreadable once and contributes no connectors, leaving the roads as they were.
     */
    private static List<Connection> readConnections(Object connections) throws ReflectiveOperationException {
        List<Connection> out = new ArrayList<>();
        if (connections == null || connectionFromMethod == null || connectionToMethod == null) {
            return out;
        }
        for (Object connection : elements(connections)) {
            if (connection == null) {
                continue;
            }
            if (connectionStatusMethod != null) {
                Object status = connectionStatusMethod.invoke(connection);
                if (status == null || !"COMPLETED".equals(String.valueOf(status))) {
                    continue;
                }
            }
            Object from = connectionFromMethod.invoke(connection);
            Object to = connectionToMethod.invoke(connection);
            if (from instanceof BlockPos a && to instanceof BlockPos b) {
                out.add(new Connection(new Point(a.getX(), a.getY(), a.getZ()),
                        new Point(b.getX(), b.getY(), b.getZ())));
            }
        }
        return out;
    }


    /**
     * Queues one read of RoadWeaver's whole road store, on the server thread. Only the copy of the road list is taken there -- roads are frozen when stored, so the conversion can walk them on the converter thread, and the signature that decides whether anything changed is cheap enough to compute where the store is.
     * One read is allowed in flight at a time: the server thread does not tick while paused, so asking every second with the pause menu open would queue a task per second and run them all on the way back into the world.
     */
    private static void pollServer(MinecraftServer server, int expectedEpoch) {
        if (serverPollPending) {
            return;
        }
        if (serverPollCooldown > 0) {
            serverPollCooldown--;
            return;
        }
        serverPollCooldown = SERVER_POLL_TICKS;
        serverPollPending = true;
        int request = ++serverRequestSeq;
        // Read on the client thread for the same reason the cache conversion reads it there: a config
        // value belongs to the thread that owns the config, not to the server's or the converter's.
        RoadClass forced = forcedClass();
        try {
            server.execute(() -> {
                try {
                    ServerLevel level = server.getLevel(Level.OVERWORLD);
                    if (level == null) {
                        return;
                    }
                    Object roads = loadAllMethod.invoke(null, level);
                    List<Structure> structures = serverStructures(level);
                    List<Connection> connections = serverConnections(level);
                    String signature = signatureOf(roads, structures, connections);
                    if (signature.equals(serverReadingSignature) && serverReadingKnown) {
                        return;
                    }
                    CONVERTER.execute(() -> {
                        try {
                            List<Polyline> polylines = serverPolylines(roads);
                            Converted converted = convert(polylines, connections, forced);
                            Reading next = new Reading(converted.network(), converted.drawn(),
                                    converted.segments(), converted.drawnSegments(),
                                    boundsOf(converted.segments()), structures,
                                    "RoadWeaver's road store");
                            if (publish(next, expectedEpoch, request, true)) {
                                serverReadingSignature = signature;
                            }
                        } catch (ReflectiveOperationException | RuntimeException failed) {
                            reportFailure("road store", failed);
                        }
                    });
                } catch (ReflectiveOperationException | RuntimeException failed) {
                    reportServerFailure(failed);
                } finally {
                    serverPollPending = false;
                }
            });
        } catch (RuntimeException failed) {
            // The server is shutting down and refused the task.
            serverPollPending = false;
            reportServerFailure(failed);
        }
    }

    /** One server-side reading's polylines, as this class's own geometry. */
    private static List<Polyline> serverPolylines(Object roads) throws ReflectiveOperationException {
        List<Polyline> polylines = new ArrayList<>();
        for (Object road : elements(roads)) {
            if (road == null) {
                continue;
            }
            int width = number(roadWidthMethod.invoke(road));
            int type = number(roadTypeMethod.invoke(road));
            List<Point> points = new ArrayList<>();
            for (Object placement : elements(roadSegmentsMethod.invoke(road))) {
                if (placement == null) {
                    continue;
                }
                Object middle = placementMiddleMethod.invoke(placement);
                if (middle instanceof BlockPos pos) {
                    points.add(new Point(pos.getX(), pos.getY(), pos.getZ()));
                }
            }
            if (points.size() >= 2) {
                polylines.add(new Polyline(points, width, type));
            }
        }
        return polylines;
    }

    /** The structures one server-side reading offers. Called on the server thread. */
    private static List<Structure> serverStructures(ServerLevel level) throws ReflectiveOperationException {
        Object snapshot = structureSnapshotMethod.invoke(null, level);
        if (snapshot == null) {
            return List.of();
        }
        Object locations = structureLocationsOfSnapshotMethod == null
                ? snapshot : structureLocationsOfSnapshotMethod.invoke(snapshot);
        if (locations == null) {
            return List.of();
        }
        List<Structure> structures = new ArrayList<>();
        for (Object info : elements(structureInfosMethod.invoke(locations))) {
            if (info == null) {
                continue;
            }
            Object pos = structurePosMethod.invoke(info);
            if (!(pos instanceof BlockPos blockPos)) {
                continue;
            }
            Object id = structureIdMethod.invoke(info);
            // Kept as stored, flat or not: the marker picks its own drawing height, and an alias is
            // keyed by the flattened position either way. See Structure.markerY and pollAliases.
            structures.add(new Structure(blockPos.getX(), blockPos.getY(), blockPos.getZ(),
                    id instanceof String text ? text : null));
        }
        return sortedStructures(structures);
    }

    /** The completed connections of the store. Called on the server thread. */
    private static List<Connection> serverConnections(ServerLevel level) throws ReflectiveOperationException {
        if (structureConnectionsMethod == null) {
            return List.of();
        }
        return readConnections(structureConnectionsMethod.invoke(null, level));
    }

    /**
     * A cheap fingerprint of one server-side reading, for telling whether a conversion is needed. Road, segment and structure counts move when the layer needs rebuilding; owner keys catch a road replaced by a corrected one, and completed connections catch a join finishing without any road's geometry changing.
     */
    private static String signatureOf(Object roads, List<Structure> structures,
                                      List<Connection> connections) {
        int roadCount = 0;
        long segmentCount = 0;
        long owners = 0;
        try {
            for (Object road : elements(roads)) {
                roadCount++;
                if (road == null) {
                    continue;
                }
                Object segments = roadSegmentsMethod.invoke(road);
                if (segments instanceof List<?> list) {
                    segmentCount += list.size();
                }
                Object a = ownerAMethod == null ? null : ownerAMethod.invoke(road);
                Object b = ownerBMethod == null ? null : ownerBMethod.invoke(road);
                owners += (a == null ? 0 : a.hashCode()) * 31L + (b == null ? 0 : b.hashCode());
            }
        } catch (ReflectiveOperationException | RuntimeException failed) {
            return "unreadable";
        }
        return roadCount + ":" + segmentCount + ":" + structures.size() + ":" + owners
                + ":" + connections.size();
    }


    /**
     * One reading as this mod's road network.
     * Not a copy of RoadWeaver's graph but a statement of what it says: a node at every endpoint, a node wherever two roads share a point exactly, and one segment per run of road between those nodes -- so a road with a hundred bends is still one piece of road on the map and one edge to the router.
     * A shared point is the definition of a junction because it is the only join RoadWeaver states: it snaps a road by truncating it and putting its end exactly on the other road's point. Two roads that merely cross share no point and are not joined -- whether a crossing is a crossroads or a bridge is not something either mod's data says.
     */
    static RoadNetwork build(List<Polyline> polylines, RoadClass forced) {
        return build(polylines, List.of(), forced);
    }

    /**
     * The same, with the completed connections whose trimmed ends are then joined to their structures. RoadWeaver deliberately stops a road short of the structure it serves, so the last stretch is put back here as ordinary polylines converted as ordinary roads, planned over like everything else. They are a special case only for drawing; see Converted.
     */
    static RoadNetwork build(List<Polyline> roads, List<Connection> connections, RoadClass forced) {
        return convert(roads, connections, forced).network();
    }

    /**
     * What one conversion produced: the network routing uses, and the network the maps draw.
     * A connector is this mod's own geometry, not a road: RoadWeaver ended its road short of the village it serves, and the straight stub is what lets a trip reach the village at all. The router needs it and the maps do not -- drawn as a road it is a straight line up to 256 blocks, which reads as a road that does not exist. So the reading carries both, sharing the real segments and nodes, computed once per reading rather than filtered per frame.
     */
    record Converted(RoadNetwork network, RoadNetwork drawn, List<RoadSegment> segments,
                     List<RoadSegment> drawnSegments, List<Integer> connectors) {
    }

    static Converted convert(List<Polyline> roads, List<Connection> connections, RoadClass forced) {
        List<Polyline> polylines = roads;
        int roadPolylines = roads.size();
        if (connections != null && !connections.isEmpty()) {
            List<Polyline> connectors = connectorPolylines(roads, connections);
            if (!connectors.isEmpty()) {
                polylines = new ArrayList<>(roads.size() + connectors.size());
                polylines.addAll(roads);
                polylines.addAll(connectors);
            }
        }
        RoadNetwork network = new RoadNetwork();
        Map<Point, Integer> occurrences = new HashMap<>();
        for (Polyline polyline : polylines) {
            Set<Point> distinct = new HashSet<>();
            for (Point point : polyline.points()) {
                if (distinct.add(point)) {
                    occurrences.merge(point, 1, Integer::sum);
                }
            }
        }

        Map<Point, Integer> nodeByPoint = new HashMap<>();
        int[] nodeIds = {ID_BASE};
        int[] segmentIds = {ID_BASE};
        // The connector polylines are appended after the roads, and segment ids are handed out in that
        // order, so the first connector's id is the line between a road and a stub without having to
        // match geometry afterwards. -1 means this reading has no connectors at all.
        int firstConnectorId = -1;
        for (int index = 0; index < polylines.size(); index++) {
            Polyline polyline = polylines.get(index);
            if (index == roadPolylines && polylines.size() > roadPolylines) {
                firstConnectorId = segmentIds[0];
            }
            List<Point> points = polyline.points();
            if (points.size() < 2) {
                continue;
            }
            RoadClass roadClass = forced != null
                    ? forced : classOf(polyline.width(), polyline.roadType());
            int runStart = 0;
            for (int i = 1; i < points.size(); i++) {
                boolean last = i == points.size() - 1;
                if (!last && occurrences.getOrDefault(points.get(i), 0) <= 1) {
                    continue;
                }
                // A run that comes back to the node it set off from is a loop no router can travel
                // round; leaving it out is better than an edge a trip would spend a moment on and
                // gain nothing from.
                appendRun(network, nodeByPoint, nodeIds, segmentIds, points, runStart, i,
                        occurrences, roadClass);
                runStart = i;
            }
        }

        List<RoadSegment> segments = List.copyOf(network.segmentsSnapshot());
        List<Integer> connectorIds = new ArrayList<>();
        List<RoadSegment> drawnSegments;
        RoadNetwork drawn;
        if (firstConnectorId < 0 || firstConnectorId >= segmentIds[0]) {
            drawn = network;
            drawnSegments = segments;
        } else {
            drawn = new RoadNetwork();
            for (RoadNode node : network.nodesSnapshot()) {
                drawn.putNode(node);
            }
            List<RoadSegment> real = new ArrayList<>(segments.size());
            for (RoadSegment segment : segments) {
                if (segment.id() >= firstConnectorId) {
                    connectorIds.add(segment.id());
                    continue;
                }
                real.add(segment);
                drawn.putSegment(segment);
            }
            drawnSegments = List.copyOf(real);
        }
        return new Converted(network, drawn, segments, drawnSegments, List.copyOf(connectorIds));
    }

    /**
     * One straight connector per structure at the end of a completed connection, when the road that connection was built for ends within JOIN_REACH of it. The connection states which two structures one road joins, so the search is for the road whose two ends are near the two ends in either order; a pair with no road near it produces nothing.
     */
    private static List<Polyline> connectorPolylines(List<Polyline> roads,
                                                     List<Connection> connections) {
        List<Polyline> connectors = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Connection connection : connections) {
            if (connection == null || connection.from() == null || connection.to() == null) {
                continue;
            }
            Polyline best = null;
            Point bestFrom = null;
            Point bestTo = null;
            double bestCost = Double.MAX_VALUE;
            for (Polyline road : roads) {
                List<Point> points = road.points();
                if (points.size() < 2) {
                    continue;
                }
                Point start = points.get(0);
                Point end = points.get(points.size() - 1);
                double forward = horizontalDistance(start, connection.from())
                        + horizontalDistance(end, connection.to());
                if (horizontalDistance(start, connection.from()) <= JOIN_REACH
                        && horizontalDistance(end, connection.to()) <= JOIN_REACH
                        && forward < bestCost) {
                    best = road;
                    bestFrom = start;
                    bestTo = end;
                    bestCost = forward;
                }
                double backward = horizontalDistance(start, connection.to())
                        + horizontalDistance(end, connection.from());
                if (horizontalDistance(start, connection.to()) <= JOIN_REACH
                        && horizontalDistance(end, connection.from()) <= JOIN_REACH
                        && backward < bestCost) {
                    best = road;
                    bestFrom = end;
                    bestTo = start;
                    bestCost = backward;
                }
            }
            if (best == null) {
                continue;
            }
            addConnector(connectors, seen, connection.from(), bestFrom, best);
            addConnector(connectors, seen, connection.to(), bestTo, best);
        }
        return connectors;
    }

    /** Adds one straight connector, unless that exact pair of points already has one. */
    private static void addConnector(List<Polyline> connectors, Set<String> seen, Point structure,
                                     Point roadEnd, Polyline road) {
        if (structure == null || roadEnd == null) {
            return;
        }
        if (horizontalDistance(structure, roadEnd) <= 2.0) {
            // The road already reaches it: a two-block connector would only put a new node on top of
            // the structure's own point.
            return;
        }
        String key = flattenedKey(structure.x(), structure.z()) + ">"
                + flattenedKey(roadEnd.x(), roadEnd.z());
        if (!seen.add(key)) {
            return;
        }
        connectors.add(new Polyline(List.of(structure, roadEnd), road.width(), road.roadType()));
    }

    /** Horizontal distance between two points, in blocks: the map is flat and so is this question. */
    private static double horizontalDistance(Point a, Point b) {
        return Math.hypot(a.x() - b.x(), a.z() - b.z());
    }

    /** One reading's segments as bounding boxes, four ints each: minX, minZ, maxX, maxZ. */
    private static int[] boundsOf(List<RoadSegment> segments) {
        int[] bounds = new int[segments.size() * 4];
        for (int i = 0; i < segments.size(); i++) {
            RoadSegment segment = segments.get(i);
            int minX = Integer.MAX_VALUE;
            int minZ = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE;
            int maxZ = Integer.MIN_VALUE;
            for (int v = 0; v < segment.vertexCount(); v++) {
                int x = segment.x(v);
                int z = segment.z(v);
                minX = Math.min(minX, x);
                minZ = Math.min(minZ, z);
                maxX = Math.max(maxX, x);
                maxZ = Math.max(maxZ, z);
            }
            int offset = i * 4;
            bounds[offset] = minX;
            bounds[offset + 1] = minZ;
            bounds[offset + 2] = maxX;
            bounds[offset + 3] = maxZ;
        }
        return bounds;
    }

    /**
     * The class one of RoadWeaver's roads is drawn and planned as: always a footpath. Its roads are the paths it lays through the world, and the width is how many blocks it paved, not a statement about vehicles; the client snapshot does not even carry the width. roadweaver_road_class forces another class for a player who wants them drivable.
     */
    static RoadClass classOf(int width, int roadType) {
        return RoadClass.PATH;
    }

    /** Appends one run of a polyline between two nodes, unless it is a loop back to one node. */
    private static void appendRun(RoadNetwork network, Map<Point, Integer> nodeByPoint,
                                  int[] nodeIds, int[] segmentIds, List<Point> points,
                                  int fromIndex, int toIndex, Map<Point, Integer> occurrences,
                                  RoadClass roadClass) {
        Point fromPoint = points.get(fromIndex);
        Point toPoint = points.get(toIndex);
        int fromNode = node(network, nodeByPoint, nodeIds, fromPoint, occurrences);
        int toNode = node(network, nodeByPoint, nodeIds, toPoint, occurrences);
        if (fromNode == toNode) {
            // The run comes back to the node it set off from. A road that is a whole loop at once --
            // RoadWeaver does not make one, but a road snapped onto itself could -- still has geometry
            // worth drawing, so it is cut at its middle into two runs that each join two nodes. A run
            // of two points that collapse onto one spot is not a road at all and is left out.
            int span = toIndex - fromIndex;
            if (span < 2) {
                return;
            }
            int middle = fromIndex + span / 2;
            appendRun(network, nodeByPoint, nodeIds, segmentIds, points, fromIndex, middle,
                    occurrences, roadClass);
            appendRun(network, nodeByPoint, nodeIds, segmentIds, points, middle, toIndex,
                    occurrences, roadClass);
            return;
        }
        RoadSegment segment = new RoadSegment(segmentIds[0]++, roadClass, fromPoint.y(),
                toIndex - fromIndex + 1);
        for (int i = fromIndex; i <= toIndex; i++) {
            segment.addVertex(points.get(i).x(), points.get(i).z());
        }
        segment.setFromNode(fromNode);
        segment.setToNode(toNode);
        segment.setLayer(0);
        segment.setDirection(RoadDirection.TWO_WAY);
        segment.setCurve(RoadCurve.of(segment));
        network.putSegment(segment);
    }

    /** The node for a point, created once and reused by every road that passes it. */
    private static int node(RoadNetwork network, Map<Point, Integer> nodeByPoint, int[] nodeIds,
                            Point point, Map<Point, Integer> occurrences) {
        Integer existing = nodeByPoint.get(point);
        if (existing != null) {
            return existing;
        }
        int id = nodeIds[0]++;
        RoadNode.Type type = occurrences.getOrDefault(point, 0) > 1
                ? RoadNode.Type.JUNCTION : RoadNode.Type.ENDPOINT;
        RoadNode node = new RoadNode(id, point.x(), point.y(), point.z(), type, null);
        network.putNode(node);
        nodeByPoint.put(point, id);
        return id;
    }

    /** The class configured for this layer, or null when the config says to choose per road. */
    private static RoadClass forcedClass() {
        String configured = RoadConfig.roadWeaverRoadClass();
        if (configured == null || configured.isBlank() || "auto".equalsIgnoreCase(configured.trim())) {
            return null;
        }
        RoadClass roadClass = RoadClass.byId(configured);
        if (roadClass == null && !badClassReported) {
            badClassReported = true;
            HowToGo.LOGGER.warn("[HowToGo] roadweaver_road_class is not a road class: '{}'; the class "
                    + "each road was built as is used instead", configured);
        }
        return roadClass;
    }


    /**
     * One immutable reading: the roads, the places and which reader produced them. The bounds are the same segments' bounding boxes, worked out once so a view can reject geometry with four comparisons per segment. network and segments are the routing view; drawnNetwork and drawnSegments are the maps' view. See Converted.
     */
    record Reading(RoadNetwork network, RoadNetwork drawnNetwork, List<RoadSegment> segments,
                   List<RoadSegment> drawnSegments, int[] bounds, List<Structure> structures,
                   String source) {
        static final Reading EMPTY =
                new Reading(new RoadNetwork(), new RoadNetwork(), List.of(), List.of(), new int[0],
                        List.of(), "nothing");

        boolean isEmpty() {
            return segments.isEmpty() && structures.isEmpty();
        }
    }

    /** One vertex of one of RoadWeaver's roads, in world blocks. */
    record Point(int x, int y, int z) {
    }

    /** One of RoadWeaver's roads: an ordered polyline and what RoadWeaver says it is. */
    record Polyline(List<Point> points, int width, int roadType) {
    }

    /** One structure RoadWeaver connected, offered as a destination. */
    record Structure(int x, int y, int z, String id) {

        /**
         * The height the marker is drawn at: the stored one, or sea level where the reading carries none. Separate from y because that is also the height an alias was keyed under, and replacing it would put the label where the alias lookup cannot find it.
         */
        int markerY() {
            return y == 0 ? FALLBACK_STRUCTURE_Y : y;
        }
    }

    /**
     * Replaces the layer, if the world and the request are still the ones the reading was for.
     *
     * @return whether the reading was accepted
     */
    private static boolean publish(Reading next, int expectedEpoch, int request, boolean fromServer) {
        if (expectedEpoch != epoch) {
            return false;
        }
        if (fromServer ? request != serverRequestSeq : request != cacheRequestSeq) {
            return false;
        }
        if (!fromServer && directSource && serverReadingKnown) {
            // A cache conversion that was already running when the server-side reading arrived is the
            // part of the network RoadWeaver's map has looked at, and the reading in force is the whole
            // store. Accepting it would put the smaller reading on top of the larger one and, because
            // the cache is no longer polled once the store answers, leave it there.
            return false;
        }
        Reading previous = reading;
        if (fromServer) {
            serverReadingKnown = true;
            // Any cache work still in flight is now stale: see the guard above.
            cacheRequestSeq++;
        }
        reading = next;
        revision++;
        // A new reading may hold different structures, so the aliases are read again next tick
        // rather than waiting for the poll interval to come round.
        aliasSourceRevision = -1;
        if (previous.isEmpty() != next.isEmpty()
                || previous.network().segmentCount() != next.network().segmentCount()
                || previous.structures().size() != next.structures().size()) {
            HowToGo.diagnostic("[HowToGo] RoadWeaver | {} segment(s) with {} node(s), {} structure(s), "
                            + "read from {}",
                    next.network().segmentCount(), next.network().nodeCount(),
                    next.structures().size(), next.source());
        }
        return true;
    }

    /** Drops the reading without touching the binding, so the next source can take over cleanly. */
    private static void clearReading() {
        if (!reading.isEmpty()) {
            reading = Reading.EMPTY;
            revision++;
        }
    }

    /** Drops the binding as well: the world has gone, and so has everything held for it. */
    private static void clearBinding() {
        clearReading();
        forgetAliases();
        if (boundLevel != null) {
            boundLevel = null;
            lastCacheSnapshot = null;
            serverReadingKnown = false;
            serverReadingSignature = null;
            directSource = false;
            epoch++;
        }
    }


    /**
     * Re-reads the aliases RoadWeaver's map holds for this reading's structures, at most once a second and immediately after a rebuild. Runs on the client thread, where getAlias is safe. A version without the notes has no aliases, and the lookup is optional.
     */
    private static void pollAliases() {
        if (aliasSourceRevision != revision) {
            aliasPollCooldown = 0;
        } else if (aliasPollCooldown > 0) {
            aliasPollCooldown--;
            return;
        }
        aliasSourceRevision = revision;
        aliasPollCooldown = ALIAS_POLL_TICKS;
        List<Structure> structures = reading.structures();
        if (structures.isEmpty()) {
            if (!aliases.isEmpty()) {
                aliases = Map.of();
                aliasRevision++;
            }
            return;
        }
        if (aliasMethod == null) {
            return;
        }
        Map<Long, String> next = new HashMap<>();
        try {
            for (Structure structure : structures) {
                Object alias = aliasMethod.invoke(null,
                        new BlockPos(structure.x(), 0, structure.z()));
                if (alias instanceof String text && !text.isBlank()) {
                    next.put(flattenedKey(structure.x(), structure.z()), text);
                }
            }
        } catch (ReflectiveOperationException | RuntimeException failed) {
            aliasMethod = null;
            HowToGo.LOGGER.info("[HowToGo] RoadWeaver's structure aliases are not readable here ({}); "
                    + "structures are named by their ids", failed.toString());
            return;
        }
        if (!next.equals(aliases)) {
            aliases = Map.copyOf(next);
            aliasRevision++;
        }
    }

    /** Forgets the aliases, for a world change or the layer being switched off. */
    private static void forgetAliases() {
        aliasSourceRevision = -1;
        aliasPollCooldown = 0;
        if (!aliases.isEmpty()) {
            aliases = Map.of();
            aliasRevision++;
        }
    }

    /** A position with its height flattened, which is how RoadWeaver keys and joins points by hand. */
    private static long flattenedKey(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    private static List<Structure> sortedStructures(List<Structure> structures) {
        LinkedHashMap<Long, Structure> byPosition = new LinkedHashMap<>();
        for (Structure structure : structures) {
            long key = flattenedKey(structure.x(), structure.z());
            Structure previous = byPosition.get(key);
            if (previous == null
                    || (isUnknown(previous.id()) && !isUnknown(structure.id()))) {
                byPosition.put(key, structure);
            }
        }
        List<Structure> sorted = new ArrayList<>(byPosition.values());
        sorted.sort(Comparator.comparingInt(Structure::x).thenComparingInt(Structure::z));
        return List.copyOf(sorted);
    }

    /** Whether RoadWeaver has no id for a structure, which its own data spells as unknown. */
    static boolean isUnknown(String id) {
        return id == null || id.isBlank() || "unknown".equalsIgnoreCase(id.trim());
    }

    // ------------------------------------------------------------------- reporting

    private static void reportFailure(String what, Throwable cause) {
        if (readFailureReported) {
            return;
        }
        readFailureReported = true;
        HowToGo.LOGGER.warn("[HowToGo] could not read RoadWeaver's {}; the previous layer is kept: {}",
                what, cause.toString());
    }

    private static void reportServerFailure(Throwable cause) {
        if (serverReported) {
            return;
        }
        serverReported = true;
        HowToGo.LOGGER.info("[HowToGo] RoadWeaver's road store is not readable here ({}); its client "
                + "map snapshot, if there is one, is used instead", cause.toString());
    }

    static boolean fullMapReadable() {
        return fullMapAvailable;
    }

    // ------------------------------------------------------------------- reflection

    private static boolean resolve() {
        if (resolved) {
            return available;
        }
        resolved = true;
        if (!modLoaded()) {
            return false;
        }
        bind();
        return available;
    }

    /**
     * Looks up every class and method this reads, and remembers whether it worked. Split from resolve() so the same lookups can be made with no game running, for the harness's checks against a real RoadWeaver jar. The client shapes are required; the server-side store is an extra allowed to be missing, so its failure is reported at info and does not make the layer unavailable. Classes are loaded without being initialised: this needs their shapes, not their state.
     */
    static boolean bind() {
        try {
            Class<?> cache = load("net.shiroha233.roadweaver.client.map.data.MapSnapshotCache");
            Class<?> snapshot = load("net.shiroha233.roadweaver.client.map.data.MapSnapshot");
            peekMethod = cache.getMethod("peek", ResourceLocation.class);
            roadPolylinesMethod = snapshot.getMethod("roadPolylines");
            structuresMethod = snapshot.getMethod("structures");
            structureNameMethod = snapshot.getMethod("structureName", BlockPos.class);
            available = true;
            // Everything below is an addition to the reading rather than a reason for it not to
            // work: a version that has moved its connections or its map notes still gets the roads.
            snapshotConnectionsMethod = optionalMethod(snapshot, "connections");
            bindConnectionShape();
            aliasMethod = optionalMethod(loadOrNull(
                    "net.shiroha233.roadweaver.client.map.data.ClientMapNotes"),
                    "getAlias", BlockPos.class);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException failed) {
            available = false;
            fullMapAvailable = false;
            warnUnavailable(failed);
            return false;
        }

        try {
            Class<?> storage = loadFirst(
                    "net.shiroha233.roadweaver.persistence.sharded.RoadShardStorage",
                    "net.shiroha233.roadweaver.persistence.files.RoadFileStorage");
            Class<?> road = load("net.shiroha233.roadweaver.core.model.RoadData");
            Class<?> placement = load("net.shiroha233.roadweaver.core.model.RoadSegmentPlacement");
            Class<?> structures = load("net.shiroha233.roadweaver.persistence.files.StructureFileStorage");
            Class<?> locationData = load("net.shiroha233.roadweaver.core.model.StructureLocationData");
            Class<?> info = load("net.shiroha233.roadweaver.core.model.StructureInfo");

            loadAllMethod = storage.getMethod("loadAll", ServerLevel.class);
            roadWidthMethod = road.getMethod("width");
            roadTypeMethod = road.getMethod("roadType");
            roadSegmentsMethod = road.getMethod("roadSegmentList");
            placementMiddleMethod = placement.getMethod("middlePos");
            ownerAMethod = optionalMethod(road, "ownerA2dKey");
            ownerBMethod = optionalMethod(road, "ownerB2dKey");
            // 2.3.1 and later hand over a structure snapshot that also says where each structure came
            // from; earlier 2.3 releases hand over the location data itself, which is all this reads.
            // The reader wants the list of structures either way, so either entry point will do.
            Method snapshot = optionalMethod(structures, "getStructureSnapshot", ServerLevel.class);
            if (snapshot != null) {
                structureSnapshotMethod = snapshot;
                structureLocationsOfSnapshotMethod = optionalMethod(snapshot.getReturnType(),
                        "locations");
            } else {
                structureSnapshotMethod =
                        structures.getMethod("getStructureLocations", ServerLevel.class);
                structureLocationsOfSnapshotMethod = null;
            }
            structureInfosMethod = locationData.getMethod("structureInfos");
            structurePosMethod = info.getMethod("pos");
            structureIdMethod = info.getMethod("structureId");
            // Optional like the map notes: the store's own connection list is what closes the trim
            // gap without the client having opened RoadWeaver's map, and a release whose storage
            // names moved loses only that head start.
            bindConnectionShape();
            structureConnectionsMethod =
                    optionalMethod(structures, "getStructureConnections", ServerLevel.class);
            fullMapAvailable = true;
            HowToGo.LOGGER.info("[HowToGo] RoadWeaver's road store is reachable through {}",
                    storage.getName());
        } catch (ReflectiveOperationException | LinkageError | RuntimeException failed) {
            fullMapAvailable = false;
            if (!serverReported) {
                serverReported = true;
                HowToGo.LOGGER.info("[HowToGo] RoadWeaver's road store is not readable under this "
                        + "version ({}); its client map snapshot is used instead", failed.toString());
            }
        }
        HowToGo.LOGGER.info("[HowToGo] RoadWeaver's roads are readable");
        return available;
    }

    /**
     * Whether RoadWeaver's client map classes are on the classpath at all.
     *
     * Asked by the harness before it checks the handshake, so that a machine which has never seen
     * RoadWeaver skips the check rather than failing it -- the same arrangement MtrClientData
     * uses for MTR.
     */
    static boolean classesPresent() {
        try {
            load("net.shiroha233.roadweaver.client.map.data.MapSnapshotCache");
            load("net.shiroha233.roadweaver.client.map.data.MapSnapshot");
            return true;
        } catch (ClassNotFoundException | LinkageError absent) {
            return false;
        }
    }

    /** A class by name, loaded but not initialised. */
    private static Class<?> load(String name) throws ClassNotFoundException {
        return Class.forName(name, false, RoadWeaverLayer.class.getClassLoader());
    }

    /** The first of several spellings of one class that is present. */
    private static Class<?> loadFirst(String... names) throws ClassNotFoundException {
        ClassNotFoundException last = null;
        for (String name : names) {
            try {
                return load(name);
            } catch (ClassNotFoundException absent) {
                last = absent;
            }
        }
        throw last == null ? new ClassNotFoundException() : last;
    }

    /** A class by name, or null when this version does not have it. */
    private static Class<?> loadOrNull(String name) {
        try {
            return load(name);
        } catch (ClassNotFoundException | LinkageError absent) {
            return null;
        }
    }

    /**
     * Resolves the shape of one structure connection, under either of the two spellings RoadWeaver has used: core.model.StructureConnection from 2.2 on, and helpers.Records StructureConnection in 2.1.
     */
    private static void bindConnectionShape() {
        Class<?> connection = loadOrNull("net.shiroha233.roadweaver.core.model.StructureConnection");
        if (connection == null) {
            connection = loadOrNull(
                    "net.shiroha233.roadweaver.helpers.Records$StructureConnection");
        }
        if (connection == null) {
            return;
        }
        connectionFromMethod = optionalMethod(connection, "from");
        connectionToMethod = optionalMethod(connection, "to");
        connectionStatusMethod = optionalMethod(connection, "status");
    }

    private static Method optionalMethod(Class<?> owner, String name, Class<?>... parameters) {
        try {
            return owner.getMethod(name, parameters);
        } catch (NoSuchMethodException | RuntimeException absent) {
            return null;
        }
    }

    /** Whether RoadWeaver is installed, asked without loading any of its classes when it is not. */
    private static boolean modLoaded() {
        try {
            //? if fabric {
            /*return net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded(MOD_ID);
            *///?} else {
            return net.neoforged.fml.ModList.get() != null
                    && net.neoforged.fml.ModList.get().isLoaded(MOD_ID);
            //?}
        } catch (RuntimeException | LinkageError notBootstrapped) {
            return false;
        }
    }

    /** Reports a failure once, then stays quiet. */
    private static void warnUnavailable(Throwable cause) {
        if (warned) {
            return;
        }
        warned = true;
        HowToGo.LOGGER.warn("[HowToGo] RoadWeaver's roads are unavailable under this version ({}); "
                + "nothing is offered from it, and nothing else changes", cause.toString());
    }

    // ------------------------------------------------------------------- helpers

    private static Iterable<?> elements(Object value) {
        if (value instanceof Iterable<?> iterable) {
            return iterable;
        }
        if (value instanceof Object[] array) {
            return List.of(array);
        }
        return Collections.emptyList();
    }

    /** A reflected number as an int, or zero when it is not a number. */
    private static int number(Object value) {
        return value instanceof Number counted ? counted.intValue() : 0;
    }
}

