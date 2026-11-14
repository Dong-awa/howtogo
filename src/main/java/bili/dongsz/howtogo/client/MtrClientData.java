package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.HowToGo;
import bili.dongsz.howtogo.RoadConfig;
import bili.dongsz.howtogo.mtr.Mtr3Data;
import bili.dongsz.howtogo.mtr.MtrSnapshotData;
import bili.dongsz.howtogo.road.RoadClass;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MTR's client-side data, read reflectively.
 * MTR sends a client only what is near it -- a requestRadius of about 192 blocks plus the ids already held -- so a reading is a window around the player, re-read as they move, never the whole railway. That is why tick() re-reads rather than reading once.
 * When the player hosts the world, MTR's server is a thread in this process and its graph is the whole railway; MtrWholeMap reaches it on MTR's own thread and merge() combines the two: the window for the track marks are cut from, the whole map for stations and lines.
 * MTR documents this class as internal with no stable API and it is not installed everywhere, so no MTR type appears in a signature, every handle is resolved once by name and guarded, and an absent or moved version reports itself unavailable. Same arrangement as CreateTrackGraph.
 * The client's line data carries no transport mode, so a line's kind is taken from the first of its stops whose station is in range. A full Route is never sent to a client (Data.routes stays empty), so the route type is unobtainable and no per-line weight may be derived from it.
 * The chain read out of MTR 4.0.5: MinecraftClientData.getInstance().stations / .simplifiedRoutes / .railWrapperList; NameColorDataBase.getId/getName/getColor/getTransportMode; AreaBase.getCenter/getMinX..getMaxZ; SimplifiedRoute.getPlatforms/getCircularState; RailWrapper.getRail/.hexId with RailMath.getLength/getPosition.
 */
public final class MtrClientData {

    private static final String MOD_ID = "mtr";

    /**
     * The client data class, under each name MTR has given it. 4.1 moved org.mtr.mod.* to org.mtr.*, so both names are tried newest first; a moved class is not a version to refuse, since everything that matters about it is unchanged -- the handshake against a real jar confirms that.
     */
    private static final String[] CLIENT_DATA_NAMES = {
            "org.mtr.client.MinecraftClientData",
            "org.mtr.mod.client.MinecraftClientData",
    };

    private static final String STATION = "org.mtr.core.data.Station";
    private static final String PLATFORM = "org.mtr.core.data.Platform";
    private static final String SIMPLIFIED_ROUTE = "org.mtr.core.data.SimplifiedRoute";
    private static final String SIMPLIFIED_PLATFORM = "org.mtr.core.data.SimplifiedRoutePlatform";
    private static final String RAIL = "org.mtr.core.data.Rail";
    private static final String RAIL_MATH = "org.mtr.core.data.RailMath";
    private static final String VECTOR = "org.mtr.core.tool.Vector";
    private static final String POSITION = "org.mtr.core.data.Position";

    /** How far apart a rail's geometry is sampled, in metres. */
    private static final double TRACK_SAMPLE_METRES = 4.0;
    /** Ceiling on the vertices one rail contributes, so a long one cannot flood the layer. */
    private static final int MAX_TRACK_VERTICES = 64;
    /** How often the client's data is re-read, in client ticks. */
    private static final int REREAD_TICKS = 20;
    /**
     * How many unreadable elements are named before the rest are only counted. Shared with MtrMapOverlay: one budget for the whole integration, so a session that has said its four says no more.
     */
    static final int MAX_REPORTED_FAILURES = 4;

    private static boolean resolved;
    private static boolean available;
    private static boolean warned;
    /** Whether the model bound is MTR 3.x's, which has its own reader in Mtr3Data. */
    private static boolean threeX;
    /** What 4.x's binding failed with, kept so the report names it when neither model binds. */
    private static Throwable fourXFailure;

    private static Method getInstanceMethod;
    private static Field stationsField;
    private static Field platformsField;
    private static Field routesField;
    private static Field simplifiedRoutesField;
    private static Field railWrapperListField;
    private static Field savedRailAreaField;
    private static Method getMidPositionMethod;
    private static Method getIdMethod;
    private static Method getNameMethod;
    private static Method getColorMethod;
    private static Method getTransportModeMethod;
    /** MTR's own names for a line's parts, which do not inherit the four above. */
    private static Method routeGetIdMethod;
    private static Method routeGetNameMethod;
    private static Method routeGetColorMethod;
    /** The route's circular state, optional: a version without it reads every line two-way. */
    private static Method routeGetCircularStateMethod;
    private static Method railGetTransportModeMethod;
    private static Method getCenterMethod;
    private static Method getMinXMethod;
    private static Method getMinYMethod;
    private static Method getMinZMethod;
    private static Method getMaxXMethod;
    private static Method getMaxYMethod;
    private static Method getMaxZMethod;
    private static Method routeGetPlatformsMethod;
    private static Method stopGetPlatformIdMethod;
    private static Method stopGetStationIdMethod;
    private static Method stopGetStationNameMethod;
    private static Method stopGetDestinationMethod;
    private static Method railWrapperGetRailMethod;
    private static Field railWrapperHexIdField;
    private static Field railMathField;
    private static Method railMathGetLengthMethod;
    private static Method railMathGetPositionMethod;
    private static Method positionGetXMethod;
    private static Method positionGetYMethod;
    private static Method positionGetZMethod;
    private static Method vectorXMethod;
    private static Method vectorYMethod;
    private static Method vectorZMethod;

    private static long ticks;
    private static String reported = "";
    private static Snapshot latest = Snapshot.EMPTY;
    /** How many elements this session could not read, so a wrong shape cannot stay quiet. */
    private static int unreadable;

    private MtrClientData() {
    }

    /** A station area, with the extent MTR gave it. */
    public record Station(long id, String name, String mode, int color, int centerX, int centerY,
                          int centerZ, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
    }

    /**
     * One platform: where a vehicle actually stops, and which station area it belongs to. Kept because a large station's centre is nowhere near its track, while a stop placed from a platform is one a route can reach.
     */
    public record Platform(long id, long stationId, String name, String mode, int x, int y, int z) {
    }

    /** One stop of a line: which platform, which station, and what it is called. */
    public record Stop(long platformId, long stationId, String stationName, String destination) {
    }

    /**
     * A line as a reading has it, with its kind deduced where the reading does not say.
     * @param rails MTR's own rail ids, in running order, or an empty list when the reading does not say
     * @param circular MTR's own fact -- it is a loop, ridden one way round. Not this mod's direction: the translation happens once in MtrTransit.buildLines. False when the reading cannot say, which is the answer that cannot invent a restriction.
     */
    public record Line(long id, String name, String mode, int color, List<Stop> stops,
                       List<String> rails, boolean circular) {

        /** The rails are absent more often than not, and an absent list is not a null one. */
        public Line {
            rails = rails == null ? List.of() : List.copyOf(rails);
        }

        /**
         * A line from a reading that does not say which rails it runs along -- most of them, because MTR's client data joins a route to its platforms and nothing else; what a line runs along is worked out in MtrLineTracks.
         */
        public Line(long id, String name, String mode, int color, List<Stop> stops) {
            this(id, name, mode, color, stops, List.of(), false);
        }

        /**
         * A line from a reading that states its rails but says nothing about a circular state. The two absences are separate: the 3.x and overlay readings name rails and both can be silent about the loop.
         */
        public Line(long id, String name, String mode, int color, List<Stop> stops,
                    List<String> rails) {
            this(id, name, mode, color, stops, rails, false);
        }

        /** The kind of this mod's line this one becomes, or null when it becomes no line. */
        public RoadClass kind() {
            return roadClassFor(mode);
        }
    }

    /** One rail, as a flat polyline, with the height it sits at. */
    public record Track(String hexId, String mode, int y, double[] xs, double[] zs) {

        public int vertexCount() {
            return xs.length;
        }
    }

    /** One reading of what MTR is offering this client. */
    public record Snapshot(List<Station> stations, List<Platform> platforms, List<Line> lines,
                           List<Track> tracks) {

        public static final Snapshot EMPTY = new Snapshot(List.of(), List.of(), List.of(), List.of());

        public boolean isEmpty() {
            return stations.isEmpty() && platforms.isEmpty() && lines.isEmpty() && tracks.isEmpty();
        }

        /** The id MTR gives a station, as MTR gives it: for keying imports against. */
        public Station station(long id) {
            for (Station station : stations) {
                if (station.id() == id) {
                    return station;
                }
            }
            return null;
        }

        /**
         * Where a station's vehicles stop: the middle of its platforms when it has any, the middle of its area when it has none. A point between two platforms is on the railway, which is where a stop must be for a route to reach it.
         * An id of zero is not a station: a platform's station is resolved against the stations the client currently holds, so a platform sent on its own carries zero -- treating it as a station gathered every orphan platform into one stop in open country.
         */
        public int[] stopPosition(long stationId) {
            if (stationId == 0) {
                return null;
            }
            long sumX = 0;
            long sumZ = 0;
            int count = 0;
            for (Platform platform : platforms) {
                if (platform.stationId() == stationId) {
                    sumX += platform.x();
                    sumZ += platform.z();
                    count++;
                }
            }
            if (count > 0) {
                return new int[]{(int) Math.round((double) sumX / count),
                        (int) Math.round((double) sumZ / count)};
            }
            Station station = station(stationId);
            return station == null ? null : new int[]{station.centerX(), station.centerZ()};
        }
    }

    /**
     * The kind of this mod's line an MTR transport mode becomes, or null when it becomes none. Train and cable car are both on rails -- there is no separate cable car class -- and a boat is on water. An aeroplane becomes nothing: a line nothing can be routed along would only ever answer no journey. Unknown modes are left alone rather than guessed at.
     */
    public static RoadClass roadClassFor(String mode) {
        if (mode == null) {
            return null;
        }
        return switch (mode) {
            case "TRAIN", "CABLE_CAR" -> RoadClass.RAIL;
            case "BOAT" -> RoadClass.WATER;
            default -> null;
        };
    }

    /** Whether MTR's client data can be read at all in this session. */
    public static boolean available() {
        resolve();
        return available;
    }

    /** The most recent reading, or an empty one before the first. */
    public static Snapshot snapshot() {
        return latest;
    }

    /**
     * Forgets the reading, because the world or the dimension it was read in is gone.
     * A window read in one dimension must not be merged into the memory of another when the player
     * changes world: the rail ids of the two are unrelated, and the marks planned from both would be
     * a railway that does not exist. The next tick re-reads whatever MTR has for the new place.
     */
    static void forgetReading() {
        latest = Snapshot.EMPTY;
        ticks = 0;
        reported = "";
        MtrWholeMap.reset();
    }

    /**
     * Re-reads the network once a second, and reports it when it changes.
     * On the tick rather than in a plan because a reading is a reading of a place, and there is nothing to gain from making a plan wait for one. Reported only when it changes: a diagnostic that fills the log is worse than no diagnostic.
     * Four readings meet here: the window MTR sent this client (the only one with rails, so marks are cut from it), the whole railway the local server simulates when the player hosts it (MtrWholeMap), the whole railway the server read and sent when it runs this mod (MtrClientSync), and -- only when neither whole reading exists -- MTR Map Overlay's fetched snapshot. Preferring our own means the server that simulated the railway outranks another mod's snapshot of it, and reading both would offer the same stations twice.
     */
    public static void tick() {
        // The rail memory's file is bound to the loaded world and dimension, and the full railway a
        // player walked stays worth more than the window MTR sends on the next visit; the store is
        // ticked before the switch below so leaving a world still writes it out.
        MtrRailStore.tick();
        if (!RoadConfig.mtrTransit()) {
            // Switched off means forgotten rather than merely not read again: a reading left lying about would go on being offered after the player asked for it not to.
            MtrClientSync.reset();
            return;
        }
        // The server sync's handshake runs on the same client tick as the rest of the MTR path, which is the only part of it that needs a tick. Called before the re-read cadence check, because its hello cadence is counted in ticks.
        MtrClientSync.tick();
        if (++ticks % REREAD_TICKS != 0) {
            return;
        }
        boolean local = mtrLoaded();
        Snapshot whole = Snapshot.EMPTY;
        Snapshot window = Snapshot.EMPTY;
        Snapshot overlay = Snapshot.EMPTY;
        if (local) {
            if (!RoadConfig.mtrFullMap()) {
                // Switched off means forgotten rather than merely not read again, as above.
                MtrWholeMap.reset();
            } else {
                MtrWholeMap.poll();
            }
            whole = MtrWholeMap.snapshot();
            window = read();
            // The overlay is read only when neither own reading exists.
            if (whole.isEmpty() && MtrClientSync.snapshot().isEmpty()) {
                overlay = MtrMapOverlay.read();
            }
        }
        // The server's reading is used whether or not MTR is installed here: it is a complete reading in this mod's own terms, so a player on a server that has both mods needs no MTR client to see, pick and plan over the railway.
        Snapshot reading = merge(window, whole, MtrClientSync.snapshot(), overlay);
        String signature = signatureOf(reading);
        if (signature.equals(reported)) {
            return;
        }
        reported = signature;
        latest = reading;
        HowToGo.diagnostic("[HowToGo] MTR | {} | {}", signature, summaryOf(reading));
        // Handed to the conversion now rather than left for whoever first asks for it: see MtrTransit.warmUp, which keeps a reading that has just changed from being answered with the one before it in the middle of a map draw.
        MtrTransit.warmUp();
    }

    /**
     * The readings there are, side by side, as one.
     * Merged by MTR's own ids, so the same thing cannot be told apart twice. The parameters are ordered weakest first: window (rails with real heights and modes), whole map (the local simulation: every station and line, no rails), server sync (every station, line and rail), fetched overlay (every station and line, rails flattened to X and Z; passed empty whenever an own reading exists).
     * Stations and platforms take the strongest reading's copy, because the strongest is the complete one -- a platform resolved against a fuller station set knows which station it is actually in.
     * Lines keep whichever placed more of its stops, the strongest of those on a tie.
     * Rails keep the window's copy first and any other reading's only where the window has not sent that rail, matched by MTR's rail id: the window's has a real height and a transport mode, the others are flattened, and the rest are how a railway the player has never been near gets its track drawn at all.
     */
    static Snapshot merge(Snapshot window, Snapshot whole, Snapshot synced, Snapshot overlay) {
        boolean noWhole = whole.isEmpty();
        boolean noSynced = synced.isEmpty();
        boolean noOverlay = overlay.isEmpty();
        if (noWhole && noSynced && noOverlay) {
            return window;
        }
        // Per-call collections rather than shared ones: this runs at most once a second, and a static scratch map would race anything else that asked for one, for the sake of a few thousand short-lived entries.
        Map<Long, Station> stations = new LinkedHashMap<>();
        Map<Long, Platform> platforms = new LinkedHashMap<>();
        Map<Long, Line> lines = new LinkedHashMap<>();
        Map<String, Track> rails = new LinkedHashMap<>();
        List<Track> unnamedRails = new ArrayList<>();
        for (Snapshot source : List.of(window, whole, synced, overlay)) {
            for (Station station : source.stations()) {
                stations.put(station.id(), station);
            }
            for (Platform platform : source.platforms()) {
                platforms.put(platform.id(), platform);
            }
            for (Line line : source.lines()) {
                // Weakest first and at least as many stops wins, so a later reading takes a tie and the fuller readings outrank the window on everything they hold.
                lines.merge(line.id(), line, MtrClientData::better);
            }
            for (Track rail : source.tracks()) {
                // First reading to name a rail wins it, which is the window whenever it has one: MTR's own copy carries the height and transport mode the overlay's flattened one does not. A rail whose id could not be read cannot be matched against another reading's, so it is kept and never deduplicated.
                if (rail.hexId() == null) {
                    unnamedRails.add(rail);
                } else {
                    rails.putIfAbsent(rail.hexId(), rail);
                }
            }
        }
        List<Track> tracks = new ArrayList<>(rails.values());
        tracks.addAll(unnamedRails);
        return new Snapshot(List.copyOf(stations.values()), List.copyOf(platforms.values()),
                List.copyOf(lines.values()), List.copyOf(tracks));
    }

    /**
     * The same merge without the server-synced reading, for a caller with only the three older readings.
     */
    static Snapshot merge(Snapshot window, Snapshot whole, Snapshot overlay) {
        return merge(window, whole, Snapshot.EMPTY, overlay);
    }

    /**
     * The same merge for a caller that has no server-synced reading. Kept because the older two-reading case is what the conversion has always been checked against, and a check that has to name two empty readings says less about what it is checking.
     */
    static Snapshot merge(Snapshot window, Snapshot whole) {
        return merge(window, whole, Snapshot.EMPTY, Snapshot.EMPTY);
    }

    /**
     * Which of two readings' copies of one line to keep: the one with more of its stops placed, and the later of the two on a tie -- the stronger reading, since these are merged weakest first.
     * Rails and colour are knowledge about a line rather than about its stops, and the stronger reading is not always the one that has them: MTR's window never says which rails a line runs along, the overlay always does. So a winner with no rails takes the other copy's, or the line would be drawn as straight hops between its stations; colour is inherited by the same rule, zero meaning this reading has no colour rather than black.
     * The circular state is ORed for the same reason: a reading that knows the route is a loop must not be overruled by one that says nothing, and false is also what an unreadable field reports.
     */
    private static Line better(Line kept, Line offered) {
        Line winner = offered.stops().size() >= kept.stops().size() ? offered : kept;
        Line other = winner == offered ? kept : offered;
        boolean needsRails = winner.rails().isEmpty() && !other.rails().isEmpty();
        boolean needsColour = (winner.color() & 0xFFFFFF) == 0 && (other.color() & 0xFFFFFF) != 0;
        boolean needsCircular = !winner.circular() && other.circular();
        if (!needsRails && !needsColour && !needsCircular) {
            return winner;
        }
        return new Line(winner.id(), winner.name(), winner.mode(),
                needsColour ? other.color() : winner.color(), winner.stops(),
                needsRails ? other.rails() : winner.rails(),
                winner.circular() || other.circular());
    }

    /**
     * Reads what MTR is offering this client, or an empty reading when it cannot be read. Never throws: this runs on a client tick and inside a plan, so anything unexpected has to come back as nothing to read.
     */
    public static Snapshot read() {
        resolve();
        if (!available) {
            return Snapshot.EMPTY;
        }
        if (threeX) {
            // The 3.x model has its own reader, and its reading comes back in these records so everything downstream -- the merge, the memory, the map, the planner -- reads one shape and cannot tell which model produced it.
            return toSnapshot(Mtr3Data.windowDimension());
        }
        try {
            Object data = getInstanceMethod.invoke(null);
            if (data == null) {
                // Not synced yet, or the player is not in a world: MTR has nothing to say.
                return Snapshot.EMPTY;
            }

            List<Station> stations = new ArrayList<>();
            Map<Long, String> modeByStation = new HashMap<>();
            for (Object raw : elements(stationsField.get(data))) {
                Station station = station(raw);
                if (station != null) {
                    stations.add(station);
                    if (station.mode() != null) {
                        modeByStation.put(station.id(), station.mode());
                    }
                }
            }

            List<Platform> platforms = new ArrayList<>();
            for (Object raw : elements(platformsField.get(data))) {
                Platform platform = platform(raw);
                if (platform != null) {
                    platforms.add(platform);
                }
            }

            List<Line> lines = new ArrayList<>();
            for (Object raw : elements(simplifiedRoutesField.get(data))) {
                Line line = line(raw, modeByStation);
                if (line != null) {
                    lines.add(line);
                }
            }

            List<Track> tracks = new ArrayList<>();
            Object wrappers = railWrapperListField.get(data);
            if (wrappers instanceof Map<?, ?> map) {
                for (Object wrapper : map.values()) {
                    Track track = track(wrapper);
                    if (track != null) {
                        tracks.add(track);
                    }
                }
            }
            return new Snapshot(List.copyOf(stations), List.copyOf(platforms), List.copyOf(lines),
                    List.copyOf(tracks));
        } catch (ReflectiveOperationException | RuntimeException e) {
            warnUnavailable(e);
            return Snapshot.EMPTY;
        }
    }


    /**
     * One station, with the extent MTR gave it. Package-private because the same elements are read from two places -- the client's own copy and, through MtrWholeMap, the local server's -- which hold the same classes, so one reading serves both.
     */
    static Station station(Object raw) {
        try {
            // The centre is a Position, whose coordinates are whole blocks as longs; the rail sampling below reads a Vector, whose coordinates are doubles. Two types, two sets of accessors.
            Object center = getCenterMethod.invoke(raw);
            return new Station(number(raw, getIdMethod),
                    text(raw, getNameMethod),
                    modeName(getTransportModeMethod.invoke(raw)),
                    (int) number(raw, getColorMethod),
                    (int) Math.round(coordinate(center, positionGetXMethod)),
                    (int) Math.round(coordinate(center, positionGetYMethod)),
                    (int) Math.round(coordinate(center, positionGetZMethod)),
                    (int) number(raw, getMinXMethod), (int) number(raw, getMinYMethod),
                    (int) number(raw, getMinZMethod), (int) number(raw, getMaxXMethod),
                    (int) number(raw, getMaxYMethod), (int) number(raw, getMaxZMethod));
        } catch (ReflectiveOperationException | RuntimeException e) {
            // One unreadable station must not cost the reading every other one.
            return null;
        }
    }

    /**
     * One platform, with the station it belongs to. A platform reaches its station through the area field every saved rail carries, which is the station object itself rather than an id -- the only link the client data has between the two. Package-private for the same reason station is.
     */
    static Platform platform(Object raw) {
        try {
            Object area = savedRailAreaField.get(raw);
            Object mid = getMidPositionMethod.invoke(raw);
            return new Platform(number(raw, getIdMethod),
                    area == null ? 0 : number(area, getIdMethod),
                    text(raw, getNameMethod),
                    modeName(getTransportModeMethod.invoke(raw)),
                    (int) Math.round(coordinate(mid, positionGetXMethod)),
                    (int) Math.round(coordinate(mid, positionGetYMethod)),
                    (int) Math.round(coordinate(mid, positionGetZMethod)));
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private static Line line(Object raw, Map<Long, String> modeByStation) {        try {
            List<Stop> stops = new ArrayList<>();
            String mode = null;
            for (Object platform : elements(routeGetPlatformsMethod.invoke(raw))) {
                long stationId = number(platform, stopGetStationIdMethod);
                stops.add(new Stop(number(platform, stopGetPlatformIdMethod), stationId,
                        text(platform, stopGetStationNameMethod),
                        text(platform, stopGetDestinationMethod)));
                if (mode == null) {
                    mode = modeByStation.get(stationId);
                }
            }
            return new Line(number(raw, routeGetIdMethod), text(raw, routeGetNameMethod), mode,
                    (int) number(raw, routeGetColorMethod), List.copyOf(stops), List.of(),
                    circular(raw));
        } catch (ReflectiveOperationException | RuntimeException e) {
            noteUnreadable("line", e);
            return null;
        }
    }

    private static Track track(Object wrapper) {
        try {
            Object rail = railWrapperGetRailMethod.invoke(wrapper);
            if (rail == null) {
                return null;
            }
            Object math = railMathField.get(rail);
            if (math == null) {
                return null;
            }
            double length = number(math, railMathGetLengthMethod);
            if (length <= 0) {
                return null;
            }
            int steps = (int) Math.min(MAX_TRACK_VERTICES - 1,
                    Math.max(1, Math.ceil(length / TRACK_SAMPLE_METRES)));
            double[] xs = new double[steps + 1];
            double[] zs = new double[steps + 1];
            double startY = 0;
            for (int i = 0; i <= steps; i++) {
                Object vector = railMathGetPositionMethod.invoke(math, length * i / steps, false);
                xs[i] = coordinate(vector, vectorXMethod);
                zs[i] = coordinate(vector, vectorZMethod);
                if (i == 0) {
                    startY = coordinate(vector, vectorYMethod);
                }
            }
            Object hexId = railWrapperHexIdField.get(wrapper);
            return new Track(hexId instanceof String text ? text : null,
                    modeName(railGetTransportModeMethod.invoke(rail)), (int) Math.round(startY), xs, zs);
        } catch (ReflectiveOperationException | RuntimeException e) {
            noteUnreadable("rail", e);
            return null;
        }
    }

    /** A collection as an iterable of its elements, or nothing when it is not one. */
    static Iterable<?> elements(Object collection) {
        return collection instanceof Collection<?> items ? items : List.of();
    }

    static long number(Object target, Method method) throws ReflectiveOperationException {
        Object value = method.invoke(target);
        return value instanceof Number counted ? counted.longValue() : 0;
    }

    static String text(Object target, Method method) throws ReflectiveOperationException {
        Object value = method.invoke(target);
        return value instanceof String string ? string : null;
    }

    private static double coordinate(Object target, Method method) throws ReflectiveOperationException {
        Object value = target == null ? null : method.invoke(target);
        return value instanceof Number counted ? counted.doubleValue() : 0;
    }

    /** The name of an enum constant, or null when the value is not one. */
    static String modeName(Object value) {
        return value instanceof Enum<?> constant ? constant.name() : null;
    }

    /**
     * Whether MTR calls this route circular, read through a bound accessor, or false when there is none or it does not answer.
     * MTR states it as a CircularState of NONE, CLOCKWISE or ANTICLOCKWISE, so the question is only whether it is the none of them -- an enum value a later MTR adds reads as circular rather than as a state never seen.
     * Takes the accessor because the client's field is a SimplifiedRoute's and the whole-map and server readers reach a Route's: one rule, three bound methods.
     */
    static boolean circular(Object route, Method accessor) {
        if (accessor == null) {
            return false;
        }
        try {
            String state = modeName(accessor.invoke(route));
            return state != null && !"NONE".equals(state);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    /** The same, for the route of MTR's own client data. */
    private static boolean circular(Object route) {
        return circular(route, routeGetCircularStateMethod);
    }


    /**
     * Whether MTR's client data can be read at all, resolving it first. Asked by MtrWholeMap before it looks anything up: with MTR absent there is nothing for it to reach for either.
     */
    static boolean ready() {
        resolve();
        return available;
    }

    /** MTR's own id for a station, platform or line of its client data. */
    static long idOf(Object nameColorData) {
        try {
            return number(nameColorData, getIdMethod);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return 0;
        }
    }

    /** What MTR calls it, or null when it has no name. */
    static String nameOf(Object nameColorData) {
        try {
            return text(nameColorData, getNameMethod);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /**
     * The station area a saved rail belongs to, or null when the data does not resolve one. The one link between a platform and its station, and why a platform whose station the client was never sent belongs to no station rather than to station zero.
     */
    static Object areaOf(Object savedRail) {
        try {
            return savedRailAreaField.get(savedRail);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /** One field of the network, as an iterable of its elements. */
    static Iterable<?> stationsOf(Object data) {
        return elementsOf(stationsField, data);
    }

    /** The same, for the platforms. */
    static Iterable<?> platformsOf(Object data) {
        return elementsOf(platformsField, data);
    }

    /** The same, for the routes -- empty on the client, every line of the railway on the server. */
    static Iterable<?> routesOf(Object data) {
        return elementsOf(routesField, data);
    }

    private static Iterable<?> elementsOf(Field field, Object data) {
        if (field == null) {
            return List.of();
        }
        try {
            return elements(field.get(data));
        } catch (ReflectiveOperationException | RuntimeException e) {
            return List.of();
        }
    }

    /**
     * Reports an element that could not be read. Each element is read in its own guard and a failure returns nothing for it; returning nothing quietly once made this reader look like it was working while it threw away every line MTR offered. The first few failures are logged and the rest counted, so a wrong shape is visible in the log rather than only in the absence of lines.
     */
    private static void noteUnreadable(String what, Throwable cause) {
        unreadable++;
        if (unreadable <= MAX_REPORTED_FAILURES) {
            HowToGo.LOGGER.warn("[HowToGo] could not read an MTR {} ({}); it is left out of the "
                    + "reading", what, cause.toString());
        } else if (unreadable == MAX_REPORTED_FAILURES + 1) {
            HowToGo.LOGGER.warn("[HowToGo] further unreadable MTR elements will not be reported");
        }
    }


    private static String signatureOf(Snapshot reading) {
        long hash = 17;
        hash = hash * 31 + reading.stations().size();
        hash = hash * 31 + reading.platforms().size();
        hash = hash * 31 + reading.lines().size();
        hash = hash * 31 + reading.tracks().size();
        // The records hash every component; Line includes stops, rails and the circular state, Stop includes both names.
        for (Station station : reading.stations()) {
            hash = hash * 31 + station.hashCode();
        }
        for (Platform platform : reading.platforms()) {
            hash = hash * 31 + platform.hashCode();
        }
        for (Line line : reading.lines()) {
            hash = hash * 31 + line.hashCode();
        }
        for (Track track : reading.tracks()) {
            hash = hash * 31 + (track.hexId() == null ? 0 : track.hexId().hashCode());
            hash = hash * 31 + (track.mode() == null ? 0 : track.mode().hashCode());
            hash = hash * 31 + track.y();
            // Record arrays are content-hashed explicitly here, so geometry cannot be missed.
            hash = hash * 31 + arrayHash(track.xs());
            hash = hash * 31 + arrayHash(track.zs());
        }
        return reading.stations().size() + "/" + reading.platforms().size() + "/"
                + reading.lines().size() + "/" + reading.tracks().size() + "/"
                + Long.toHexString(hash);
    }

    private static int arrayHash(double[] values) {
        return values == null ? 0 : java.util.Arrays.hashCode(values);
    }

    private static String summaryOf(Snapshot reading) {
        StringBuilder summary = new StringBuilder();
        summary.append(reading.stations().size()).append(" station(s), ")
                .append(reading.platforms().size()).append(" platform(s), ")
                .append(reading.tracks().size()).append(" rail(s), ")
                .append(reading.lines().size()).append(" line(s)");
        int skipped = 0;
        for (Line line : reading.lines()) {
            if (line.kind() == null) {
                skipped++;
                continue;
            }
            summary.append(" | ").append(line.name() == null ? "?" : line.name())
                    .append(' ').append(line.kind().name())
                    .append(" (").append(line.stops().size()).append(" stops)");
        }
        if (skipped > 0) {
            summary.append(" | ").append(skipped).append(" line(s) skipped: no kind of line here");
        }
        return summary.toString();
    }


    /**
     * Looks every class, field and method up once and keeps the result. Guarded because this is read on a client tick, and a failure is permanent: a missing class does not appear later.
     */
    private static synchronized void resolve() {
        if (resolved) {
            return;
        }
        resolved = true;
        // Asked only as a shortcut: with MTR absent there is nothing to look up, and a session without it should not load MTR's classes at all. Whether the names below are still MTR's is a separate question that bind() answers on its own -- which is what lets the regression harness check the whole handshake against a real MTR jar with no game running.
        if (!mtrLoaded()) {
            return;
        }
        bind();
    }

    /**
     * Looks up every class, field and method this reads, and remembers whether it worked.
     * Every lookup is public API of MTR or of the simulation core it bundles, so no privileged access is asked for, and an MTR that keeps its API but changes its innards cannot break this. A version that has moved any of these names reports itself unavailable and leaves the rest of the mod untouched.
     * The classes are loaded without being initialised: this needs their shapes, not their state, and running another mod's static initialisers from here would be asking for trouble.
     * @return whether MTR's 4.x shapes are the ones this class reads
     */
    private static boolean bindFourX() {
        try {
            Class<?> clientData = null;
            String boundName = null;
            for (String name : CLIENT_DATA_NAMES) {
                try {
                    clientData = load(name);
                    boundName = name;
                    break;
                } catch (ClassNotFoundException next) {
                    // Not this name; the next one is the other spelling of the same class.
                }
            }
            if (clientData == null) {
                throw new ClassNotFoundException(String.join(" or ", CLIENT_DATA_NAMES));
            }
            Class<?> station = load(STATION);
            Class<?> platform = load(PLATFORM);
            Class<?> simplified = load(SIMPLIFIED_ROUTE);
            Class<?> simplifiedPlatform = load(SIMPLIFIED_PLATFORM);
            // The rail wrapper is a nested class of the client data, so it moves with it and needs no name of its own to keep in step.
            Class<?> railWrapper = load(boundName + "$RailWrapper");
            Class<?> rail = load(RAIL);
            Class<?> railMath = load(RAIL_MATH);
            Class<?> vector = load(VECTOR);
            Class<?> position = load(POSITION);

            getInstanceMethod = clientData.getMethod("getInstance");
            // Fields on the class or on whichever parent declares them: MTR keeps stations on the simulation core's Data and its client lines on that class's ClientData parent, and getField walks the chain.
            stationsField = clientData.getField("stations");
            platformsField = clientData.getField("platforms");
            simplifiedRoutesField = clientData.getField("simplifiedRoutes");
            railWrapperListField = clientData.getField("railWrapperList");
            try {
                // Declared on the simulation core's Data, which both the client's copy of the network and the server's simulator extend, so the one handle reads either. The client's own routes are always empty; the simulator's are the whole railway.
                // Asked for apart from the four above and allowed to be missing: only the whole-map read wants it, so a renamed field costs the whole-map read rather than every station and line.
                routesField = clientData.getField("routes");
            } catch (NoSuchFieldException | RuntimeException absent) {
                routesField = null;
            }

            // Every handle below is taken from the class it will actually be invoked on, never from a base class it merely shares.
            // Resolving from a base class once broke this reader: SimplifiedRoute is not a NameColorDataBase, so a getName taken from there threw on a line, the throw was swallowed per element, and every line was silently dropped while the stations came through perfectly.
            // The four name-and-colour accessors are the exception in appearance only: they are declared once on NameColorDataBase and both stations and platforms inherit them. A line does not, which is why it has its own three.
            getIdMethod = station.getMethod("getId");
            getNameMethod = station.getMethod("getName");
            getColorMethod = station.getMethod("getColor");
            getTransportModeMethod = station.getMethod("getTransportMode");

            getCenterMethod = station.getMethod("getCenter");
            getMinXMethod = station.getMethod("getMinX");
            getMinYMethod = station.getMethod("getMinY");
            getMinZMethod = station.getMethod("getMinZ");
            getMaxXMethod = station.getMethod("getMaxX");
            getMaxYMethod = station.getMethod("getMaxY");
            getMaxZMethod = station.getMethod("getMaxZ");

            // A platform's station, and where it is. Both are inherited from SavedRailBase and looked up on the platform for the reason above.
            savedRailAreaField = platform.getField("area");
            getMidPositionMethod = platform.getMethod("getMidPosition");

            routeGetIdMethod = simplified.getMethod("getId");
            routeGetNameMethod = simplified.getMethod("getName");
            routeGetColorMethod = simplified.getMethod("getColor");
            // Optional, like the fields this reader treats as additions rather than requirements: a version that has moved this name loses the line's direction, not the line. It then reads two-way, which cannot invent a restriction -- and the handshake against a real jar notices the moved name rather than a silent two-way railway.
            routeGetCircularStateMethod = optionalMethod(simplified, "getCircularState");
            routeGetPlatformsMethod = simplified.getMethod("getPlatforms");
            stopGetPlatformIdMethod = simplifiedPlatform.getMethod("getPlatformId");
            stopGetStationIdMethod = simplifiedPlatform.getMethod("getStationId");
            stopGetStationNameMethod = simplifiedPlatform.getMethod("getStationName");
            stopGetDestinationMethod = simplifiedPlatform.getMethod("getDestination");

            railWrapperGetRailMethod = railWrapper.getMethod("getRail");
            railWrapperHexIdField = railWrapper.getField("hexId");
            railMathField = rail.getField("railMath");
            // A rail carries its own transport mode, and is not a NameColorDataBase either.
            railGetTransportModeMethod = rail.getMethod("getTransportMode");
            railMathGetLengthMethod = railMath.getMethod("getLength");
            railMathGetPositionMethod = railMath.getMethod("getPosition", double.class, boolean.class);
            positionGetXMethod = position.getMethod("getX");
            positionGetYMethod = position.getMethod("getY");
            positionGetZMethod = position.getMethod("getZ");
            vectorXMethod = vector.getMethod("x");
            vectorYMethod = vector.getMethod("y");
            vectorZMethod = vector.getMethod("z");

            available = true;
            HowToGo.LOGGER.info("[HowToGo] MTR client data bound reflectively ({})", boundName);
            return true;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            available = false;
            fourXFailure = e;
            return false;
        }
    }

    /**
     * Binds MTR's data, whichever of its two models is installed.
     * The 4.x model is tried first and left entirely alone; only when those names are absent is 3.x looked for -- MTR 3.x keeps its objects on the client and in the world's saved data instead of a simulation core, so it has a reader of its own in Mtr3Data whose output is handed back in these records.
     * A version that has moved every one of these names simply reports itself unavailable, which leaves the rest of the mod untouched.
     * @return whether one of MTR's data models is readable here
     */
    static boolean bind() {
        if (bindFourX()) {
            threeX = false;
            return true;
        }
        if (Mtr3Data.bindClient()) {
            threeX = true;
            available = true;
            HowToGo.LOGGER.info("[HowToGo] MTR's 3.x data model is in use; the 4.x names are not on "
                    + "the classpath");
            return true;
        }
        available = false;
        warnUnavailable(fourXFailure == null
                ? new IllegalStateException("no known MTR data model is present") : fourXFailure);
        return false;
    }

    /** A class by name, loaded but not initialised. */
    private static Class<?> load(String name) throws ClassNotFoundException {
        return Class.forName(name, false, MtrClientData.class.getClassLoader());
    }

    /** A no-argument accessor, or null when the version in use does not have it. */
    private static Method optionalMethod(Class<?> owner, String name) {
        try {
            return owner.getMethod(name);
        } catch (NoSuchMethodException | RuntimeException absent) {
            return null;
        }
    }

    /**
     * Whether MTR's client data class is on the classpath at all, under any of its names. Kept here rather than written out again, because the names are the fragile part: a second copy stops being updated, which is exactly what happened to the harness check that went on looking for the 4.0 spelling.
     */
    static boolean classesPresent() {
        return classesPresentFourX() || Mtr3Data.clientPresent();
    }

    /**
     * Whether MTR 4.x's client data class is on the classpath, without the 3.x fallback. Asked by the regression harness before it checks the 4.x handshake: an install has one data model or the other, and a 3.x jar on the classpath is not a reason to expect 4.x names.
     */
    static boolean classesPresentFourX() {
        for (String name : CLIENT_DATA_NAMES) {
            try {
                load(name);
                return true;
            } catch (ClassNotFoundException | LinkageError absent) {
                // Not that name; the next one is the other spelling of the same class.
            }
        }
        return false;
    }

    /**
     * A 3.x reading, in this mod's own client records. The two readings are the same fields under different owners, so this is a copy and not a conversion: nothing here decides anything.
     */
    public static Snapshot toSnapshot(MtrSnapshotData.Dimension dimension) {
        if (dimension == null) {
            return Snapshot.EMPTY;
        }
        List<Station> stations = new ArrayList<>(dimension.stations().size());
        for (MtrSnapshotData.Station station : dimension.stations()) {
            stations.add(new Station(station.id(), station.name(), station.mode(), station.color(),
                    station.centerX(), station.centerY(), station.centerZ(), station.minX(),
                    station.minY(), station.minZ(), station.maxX(), station.maxY(),
                    station.maxZ()));
        }
        List<Platform> platforms = new ArrayList<>(dimension.platforms().size());
        for (MtrSnapshotData.Platform platform : dimension.platforms()) {
            platforms.add(new Platform(platform.id(), platform.stationId(), platform.name(),
                    platform.mode(), platform.x(), platform.y(), platform.z()));
        }
        List<Line> lines = new ArrayList<>(dimension.lines().size());
        for (MtrSnapshotData.Line line : dimension.lines()) {
            List<Stop> stops = new ArrayList<>(line.stops().size());
            for (MtrSnapshotData.Stop stop : line.stops()) {
                stops.add(new Stop(stop.platformId(), stop.stationId(), stop.stationName(),
                        stop.destination()));
            }
            lines.add(new Line(line.id(), line.name(), line.mode(), line.color(), List.copyOf(stops),
                    line.rails(), line.circular()));
        }
        List<Track> tracks = new ArrayList<>(dimension.tracks().size());
        for (MtrSnapshotData.Track track : dimension.tracks()) {
            tracks.add(new Track(track.hexId(), track.mode(), track.y(), track.xs(), track.zs()));
        }
        return new Snapshot(List.copyOf(stations), List.copyOf(platforms), List.copyOf(lines),
                List.copyOf(tracks));
    }

    /** Whether the model in use is MTR 3.x's rather than 4.x's. */
    static boolean isThreeX() {
        resolve();
        return threeX;
    }

    /**
     * Whether MTR is installed, answered without assuming the loader has been asked anything yet: a class of this mod can reach here from a test harness or a tool that never started the game. Not installed and no loader to ask are the same answer to everything that follows.
     */
    private static boolean mtrLoaded() {
        try {
            //? if fabric {
        /*return FabricLoader.getInstance().isModLoaded(MOD_ID);
        *///?} else {
        return ModList.get() != null && ModList.get().isLoaded(MOD_ID);
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
        HowToGo.LOGGER.warn("[HowToGo] MTR's client data is unavailable ({}); its stations and lines "
                + "will not be offered, and nothing else changes", cause.toString());
    }
}
