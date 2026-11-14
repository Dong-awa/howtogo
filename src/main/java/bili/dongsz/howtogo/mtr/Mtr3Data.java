package bili.dongsz.howtogo.mtr;

import bili.dongsz.howtogo.HowToGo;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MTR 3.x's data, read reflectively.
 * MTR 4.x keeps loader-neutral objects in org.mtr.core.data and sends SimplifiedRoutes; 3.x -- the YMTR fork line and the 1.20.1 era -- keeps objects on the client (ClientData's static sets) and in a world's RailwayData, all fields public and no getters. The two readers share no class name, so MtrClientData keeps its 4.x shapes and falls back to this one.
 * Readings: the window (ClientData's STATIONS, PLATFORMS, ROUTES and RAILS -- what was sent near the player) and a whole dimension (RailwayData.getInstance(world)). The whole read also takes rails: on 1.20.1 the server-to-client sync has no transport, so a network the player has not walked would otherwise draw as disconnected pieces.
 * Names: YMTR ships mtr.*; the official 3.x spellings org.mtr.mod.* and org.mtr.* are tried as candidates paired with the data package.
 * Every element is read through a Getter (public field when present, getter otherwise); BlockPos and Vec3d are resolved late, trying both of Minecraft's naming families.
 * 3.x rails have no id, so one is made from the endpoint blocks and mode (m3:x,y,z|x,y,z:MODE): stable across readings and unable to collide with MTR's own ids.
 * The converted rail list is cached with a cheap signature (endpoints, rail identities and lengths), because sampling a curve costs one reflective call per vertex; the cache holds nothing of MTR's and its owner is weak.
 */
public final class Mtr3Data {

    /**
     * The client data class and the package its data classes live in, newest plausible spelling first. mtr.* is YMTR's; the org.mtr spellings are tried and passed over silently, which keeps this reader harmless on a 4.x install.
     */
    private static final String[][] CLIENT_CANDIDATES = {
            {"mtr.client.ClientData", "mtr"},
            {"org.mtr.mod.client.ClientData", "org.mtr.mod"},
            {"org.mtr.client.ClientData", "org.mtr"},
    };

    /** The packages RailwayData is looked for in, the same families as above. */
    private static final String[] SERVER_BASES = {"mtr", "org.mtr.mod", "org.mtr"};

    /** How far apart a rail's geometry is sampled, in metres, as the 4.x readers do. */
    private static final double TRACK_SAMPLE_METRES = 4.0;
    /** Ceiling on the vertices one rail contributes to a window reading. */
    private static final int WINDOW_MAX_VERTICES = 64;
    /** How many unreadable elements are named before the rest are only counted. */
    private static final int MAX_REPORTED_FAILURES = 4;

    // ------------------------------------------------------------------ the shapes

    private static boolean shapesResolved;
    private static boolean shapesAvailable;
    private static String shapesBase;
    private static Class<?> stationClass;
    private static Class<?> platformClass;
    private static Class<?> routeClass;
    private static Class<?> railClass;
    private static Class<?> routePlatformClass;

    /** The four name-and-colour accessors, declared once on NameColorDataBase. */
    private static Getter idGetter;
    private static Getter nameGetter;
    private static Getter colourGetter;
    private static Getter modeGetter;

    private static Method centreMethod;
    private static Field cornerFirstField;
    private static Field cornerSecondField;
    private static Getter pairFirstGetter;
    private static Getter pairSecondGetter;
    private static Method midMethod;

    private static Getter routePlatformsGetter;
    private static Getter stopPlatformIdGetter;
    private static Getter stopDestinationGetter;
    private static Method routeDestinationByIndexMethod;
    private static Getter routeHiddenGetter;
    /**
     * The circular state of a route, as MTR 3.x might name it. Unverified against a 3.x jar, so it is optional and outside shapesAvailable; without it routes read as two-way, which cannot invent a restriction.
     */
    private static Getter routeCircularGetter;

    private static Getter railLengthGetter;
    private static Method railPositionMethod;
    private static Getter railModeGetter;

    /** Minecraft's two accessor families, resolved on the first element that needs them. */
    private static boolean blockAccessorsBound;
    private static Getter blockXGetter;
    private static Getter blockYGetter;
    private static Getter blockZGetter;
    private static boolean vectorAccessorsBound;
    private static Getter vectorXGetter;
    private static Getter vectorYGetter;
    private static Getter vectorZGetter;

    // ----------------------------------------------------------------- the client

    private static boolean clientResolved;
    private static boolean clientAvailable;
    private static boolean clientWarned;
    private static String boundClientName;
    private static Method clientInstanceMethod;
    private static Field clientStationsField;
    private static Field clientPlatformsField;
    private static Field clientRoutesField;
    private static Field clientRailsField;
    private static Field clientCacheField;

    /** The platform-to-station map, found on whichever data cache object the reading is given. */
    private static Field platformToStationField;

    // ----------------------------------------------------------------- the server

    private static boolean serverResolved;
    private static boolean serverAvailable;
    private static boolean serverWarned;
    private static Class<?> railwayDataClass;
    private static Field railwayStationsField;
    private static Field railwayPlatformsField;
    private static Field railwayRoutesField;
    private static Field railwayCacheField;
    private static Method railwayGetInstanceMethod;
    /** The whole dimension's rails: the public getter when there is one, the private field otherwise. */
    private static Method railwayRailsMethod;
    private static Field railwayRailsField;

    // ------------------------------------------------------------------ the rail cache

    /**
     * The rail list last converted for a whole-map read, with the signature of its map, guarded by the class lock. The owner is held weakly and compared by identity, so the cache cannot keep a world alive; the list itself holds nothing of MTR's.
     */
    private static java.lang.ref.WeakReference<Object> railsCacheOwner;
    private static long railsCacheSignature;
    private static boolean railsCached;
    private static List<MtrSnapshotData.Track> railsCache = List.of();

    private static int unreadable;

    private Mtr3Data() {
    }

    // ---------------------------------------------------------------- the readings

    /**
     * What MTR 3.x is offering this client, in the shape the rest of the mod reads. Built on the caller's thread from the static sets; an unsynced client has empty sets and an unreadable shape comes back empty rather than throwing.
     */
    public static MtrSnapshotData.Dimension windowDimension() {
        if (!bindClient()) {
            return empty();
        }
        try {
            Object data = clientInstanceMethod == null ? null : clientInstanceMethod.invoke(null);
            Object stations = fieldValue(clientStationsField, data);
            Object platforms = fieldValue(clientPlatformsField, data);
            Object routes = fieldValue(clientRoutesField, data);
            Object cache = fieldValue(clientCacheField, data);
            Object rails = fieldValue(clientRailsField, data);
            Map<?, ?> platformStations = platformStationsOf(cache);
            return build("", stations, platforms, routes, platformStations, platformStations != null,
                    rails, WINDOW_MAX_VERTICES);
        } catch (ReflectiveOperationException | RuntimeException e) {
            warnClient(e);
            return empty();
        }
    }

    /**
     * Every station, platform, line and rail of one simulated dimension. The rails are the point: the windowed reading alone leaves the rest of the network undrawn. The conversion is cached, and rails are an addition -- when the rail map cannot be read, the stations, platforms and lines still are.
     */
    public static MtrSnapshotData.Dimension whole(Object railwayData) {
        if (railwayData == null || !bindServer()) {
            return null;
        }
        try {
            Object stations = fieldValue(railwayStationsField, railwayData);
            Object platforms = fieldValue(railwayPlatformsField, railwayData);
            Object routes = fieldValue(railwayRoutesField, railwayData);
            Object cache = fieldValue(railwayCacheField, railwayData);
            Map<?, ?> platformStations = platformStationsOf(cache);
            // The cheap reading first: if any part of the conversion is unavailable, the rails are
            // the one part worth not paying for.
            MtrSnapshotData.Dimension reading = build("", stations, platforms, routes,
                    platformStations, platformStations != null, null, WINDOW_MAX_VERTICES);
            List<MtrSnapshotData.Track> tracks;
            try {
                tracks = wholeTracks(railsOf(railwayData));
            } catch (RuntimeException railsUnreadable) {
                // Rails are an addition to this reading: a rail map that cannot be walked costs the
                // tracks and nothing else. The reading this reader gave before the rails were read is
                // still a good reading, and it must not become no reading at all.
                noteUnreadable("rail map", railsUnreadable);
                tracks = List.of();
            }
            if (tracks.isEmpty()) {
                return reading;
            }
            return new MtrSnapshotData.Dimension(reading.id(), reading.stations(),
                    reading.platforms(), reading.lines(), tracks);
        } catch (ReflectiveOperationException | RuntimeException e) {
            warnServer(e);
            return null;
        }
    }

    /**
     * The rail map a whole dimension's data holds, or null. getRailsMap() is preferred; the private rails field is the fallback for builds without a getter (YMTR 3.6.4.2), made accessible once at bind time.
     */
    static Object railsOf(Object railwayData) {
        if (railwayData == null || !bindServer()) {
            return null;
        }
        try {
            if (railwayRailsMethod != null) {
                Object rails = railwayRailsMethod.invoke(railwayData);
                if (rails != null) {
                    return rails;
                }
            }
            if (railwayRailsField != null) {
                return railwayRailsField.get(railwayData);
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            noteUnreadable("rail map", e);
        }
        return null;
    }

    /**
     * The data a world is simulating, or null when there is none.
     *
     * RailwayData.getInstance(World) is persistent-state backed, so it must be handed a
     * server world and asked on the server thread; the caller is responsible for both.
     */
    public static Object railwayData(Object world) {
        if (world == null || !bindServer()) {
            return null;
        }
        try {
            return railwayGetInstanceMethod.invoke(null, world);
        } catch (ReflectiveOperationException | RuntimeException e) {
            warnServer(e);
            return null;
        }
    }

    // --------------------------------------------------------------- the conversion

    /**
     * One reading's collections as this mod's own records. Package-visible so a test can drive it with no game running: everything it touches arrives as arguments. The platform-to-station map may be null, in which case a platform's station is found geometrically.
     */
    static MtrSnapshotData.Dimension build(String dimensionId, Object stationsRaw, Object platformsRaw,
                                           Object routesRaw, Map<?, ?> platformStations,
                                           boolean platformStationsKnown, Object railsRaw,
                                           int maxVertices) {
        List<MtrSnapshotData.Station> stations = new ArrayList<>();
        Map<Long, String> modeByStation = new HashMap<>();
        for (Object raw : elements(stationsRaw)) {
            MtrSnapshotData.Station station = station(raw);
            if (station == null) {
                continue;
            }
            stations.add(station);
            if (station.mode() != null) {
                modeByStation.put(station.id(), station.mode());
            }
        }

        List<MtrSnapshotData.Platform> platforms = new ArrayList<>();
        for (Object raw : elements(platformsRaw)) {
            MtrSnapshotData.Platform platform = platform(raw, platformStations, platformStationsKnown,
                    stations);
            if (platform != null) {
                platforms.add(platform);
            }
        }
        // 3.x's station area is a rectangle in X and Z with no height, so a station without one is lifted to its platforms', which is where a marker is drawn.
        stations = withPlatformHeights(stations, platforms);

        List<MtrSnapshotData.Line> lines = new ArrayList<>();
        for (Object raw : elements(routesRaw)) {
            MtrSnapshotData.Line line = line(raw, platformStations, platformStationsKnown, modeByStation);
            if (line != null) {
                lines.add(line);
            }
        }
        return new MtrSnapshotData.Dimension(dimensionId, stations, platforms, lines,
                tracks(railsRaw, maxVertices));
    }

    private static MtrSnapshotData.Station station(Object raw) {
        try {
            int[] centre = blockOf(ensureBlockAccessors() && centreMethod != null
                    ? centreMethod.invoke(raw) : null);
            int[] corners = cornersOf(raw);
            if (centre == null && corners == null) {
                return null;
            }
            int centreX;
            int centreY;
            int centreZ;
            if (centre != null) {
                centreX = centre[0];
                centreY = centre[1];
                centreZ = centre[2];
            } else {
                centreX = (corners[0] + corners[2]) / 2;
                centreY = 0;
                centreZ = (corners[1] + corners[3]) / 2;
            }
            int minX = corners == null ? centreX : corners[0];
            int minZ = corners == null ? centreZ : corners[1];
            int maxX = corners == null ? centreX : corners[2];
            int maxZ = corners == null ? centreZ : corners[3];
            return new MtrSnapshotData.Station(number(raw, idGetter), text(raw, nameGetter),
                    modeName(value(raw, modeGetter)), (int) number(raw, colourGetter),
                    centreX, centreY, centreZ, minX, centreY, minZ, maxX, centreY, maxZ);
        } catch (ReflectiveOperationException | RuntimeException e) {
            noteUnreadable("station", e);
            return null;
        }
    }

    private static MtrSnapshotData.Platform platform(Object raw, Map<?, ?> platformStations,
                                                     boolean platformStationsKnown,
                                                     List<MtrSnapshotData.Station> stations) {
        try {
            long id = number(raw, idGetter);
            long stationId = 0;
            if (platformStationsKnown) {
                Object station = platformStations.get(id);
                if (station != null) {
                    stationId = number(station, idGetter);
                }
            }
            int[] mid = blockOf(ensureBlockAccessors() && midMethod != null
                    ? midMethod.invoke(raw) : null);
            if (mid == null) {
                return null;
            }
            if (stationId == 0 && !platformStationsKnown) {
                // No map to ask: the smallest station rectangle containing the platform wins.
                stationId = stationContaining(stations, mid[0], mid[2]);
            }
            return new MtrSnapshotData.Platform(id, stationId, text(raw, nameGetter),
                    modeName(value(raw, modeGetter)), mid[0], mid[1], mid[2]);
        } catch (ReflectiveOperationException | RuntimeException e) {
            noteUnreadable("platform", e);
            return null;
        }
    }

    private static MtrSnapshotData.Line line(Object raw, Map<?, ?> platformStations,
                                             boolean platformStationsKnown,
                                             Map<Long, String> modeByStation) {
        try {
            if (Boolean.TRUE.equals(value(raw, routeHiddenGetter))) {
                // A route MTR itself does not show: not a line this mod should offer either.
                return null;
            }
            List<MtrSnapshotData.Stop> stops = new ArrayList<>();
            // A 3.x route carries its own transport mode, unlike 4.x's SimplifiedRoute.
            String mode = modeName(value(raw, modeGetter));
            int index = 0;
            for (Object routePlatform : elements(routePlatformsGetter.get(raw))) {
                long platformId = number(routePlatform, stopPlatformIdGetter);
                long stationId = 0;
                String stationName = "";
                if (platformStationsKnown) {
                    Object station = platformStations.get(platformId);
                    if (station != null) {
                        stationId = number(station, idGetter);
                        stationName = orEmpty(text(station, nameGetter));
                    }
                }
                stops.add(new MtrSnapshotData.Stop(platformId, stationId, stationName,
                        destinationOf(routePlatform, raw, index)));
                if (mode == null && stationId != 0) {
                    mode = modeByStation.get(stationId);
                }
                index++;
            }
            return new MtrSnapshotData.Line(number(raw, idGetter), text(raw, nameGetter), mode,
                    (int) number(raw, colourGetter) & 0xFFFFFF, stops, List.of(), circular(raw));
        } catch (ReflectiveOperationException | RuntimeException e) {
            noteUnreadable("line", e);
            return null;
        }
    }

    /** What MTR says a stop's destination is, through whichever of its two shapes this version has. */
    private static String destinationOf(Object routePlatform, Object route, int index) {
        Object byStop = value(routePlatform, stopDestinationGetter);
        if (byStop instanceof String text) {
            return text;
        }
        if (routeDestinationByIndexMethod != null) {
            try {
                Object value = routeDestinationByIndexMethod.invoke(route, index);
                return value instanceof String text ? text : null;
            } catch (ReflectiveOperationException | RuntimeException unreadable) {
                return null;
            }
        }
        return null;
    }

    /**
     * Whether a 3.x route says it is circular, or false when the answer cannot be read. Unverified against a 3.x jar, so every other answer is two-way, which cannot invent a restriction.
     */
    private static boolean circular(Object route) {
        Object value = value(route, routeCircularGetter);
        if (value instanceof Boolean loop) {
            return loop;
        }
        if (value instanceof Enum<?> constant) {
            return !"NONE".equals(constant.name());
        }
        return false;
    }

    /**
     * A whole dimension's rails as tracks, sampling only when the network changed since the last whole-map read. Package-visible so the probe can drive it with real rails; the cached list is returned as-is because Tracks are immutable records.
     */
    static List<MtrSnapshotData.Track> wholeTracks(Object railsRaw) {
        if (!(railsRaw instanceof Map<?, ?> outer) || !ensureVectorAccessors()) {
            return List.of();
        }
        long signature = railsSignature(outer);
        synchronized (Mtr3Data.class) {
            // A weak owner compared by identity: the cache is only ever consulted for the very map it
            // was built from, so a rail map that a world let go of is not kept alive by this field --
            // and a signature is never trusted for a different map, whose identity is part of it.
            if (railsCached && railsCacheOwner != null && railsCacheOwner.get() == railsRaw
                    && railsCacheSignature == signature) {
                return railsCache;
            }
            List<MtrSnapshotData.Track> tracks = List.copyOf(tracks(railsRaw, WINDOW_MAX_VERTICES));
            railsCacheOwner = new java.lang.ref.WeakReference<>(railsRaw);
            railsCacheSignature = signature;
            railsCache = tracks;
            railsCached = true;
            return tracks;
        }
    }

    /**
     * A cheap content signature of a rail map: keys, rail identities and each rail's length, so an in-place curve edit is caught. It is not an id -- railId still does matching -- it only decides whether the cached conversion is reusable.
     */
    private static long railsSignature(Map<?, ?> outer) {
        long signature = System.identityHashCode(outer);
        for (Map.Entry<?, ?> byStart : outer.entrySet()) {
            if (!(byStart.getValue() instanceof Map<?, ?> byEnd)) {
                continue;
            }
            signature = signature * 31 + System.identityHashCode(byStart.getKey());
            for (Map.Entry<?, ?> one : byEnd.entrySet()) {
                signature = signature * 31 + System.identityHashCode(one.getValue())
                        + System.identityHashCode(one.getKey())
                        + Double.doubleToLongBits(railLengthOf(one.getValue()));
            }
        }
        return signature;
    }

    /** One rail's length, or 0 when it cannot be read -- a failure worth no cache invalidation. */
    private static double railLengthOf(Object rail) {
        if (rail == null || railLengthGetter == null) {
            return 0.0;
        }
        try {
            return decimal(rail, railLengthGetter);
        } catch (ReflectiveOperationException | RuntimeException unreadable) {
            return 0.0;
        }
    }

    private static List<MtrSnapshotData.Track> tracks(Object railsRaw, int maxVertices) {
        if (!(railsRaw instanceof Map<?, ?> outer) || !ensureVectorAccessors()) {
            return List.of();
        }
        List<MtrSnapshotData.Track> tracks = new ArrayList<>();
        // A rail's made-up id is the same whichever direction the map holds it, so a map holding both directions does not send one rail twice.
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Map.Entry<?, ?> byStart : outer.entrySet()) {
            if (!(byStart.getValue() instanceof Map<?, ?> byEnd)) {
                continue;
            }
            for (Map.Entry<?, ?> one : byEnd.entrySet()) {
                Object rail = one.getValue();
                if (rail == null) {
                    continue;
                }
                String mode = modeName(value(rail, railModeGetter));
                String id = railId(byStart.getKey(), one.getKey(), mode);
                if (id != null && !seen.add(id)) {
                    continue;
                }
                MtrSnapshotData.Track track = track(rail, id, mode, maxVertices);
                if (track != null) {
                    tracks.add(track);
                }
            }
        }
        return tracks;
    }

    /** One rail, sampled along its own curve at the same spacing the 4.x readers use. */
    private static MtrSnapshotData.Track track(Object rail, String hexId, String mode,
                                               int maxVertices) {
        try {
            double length = number(rail, railLengthGetter);
            if (!(length > 0)) {
                return null;
            }
            int steps = (int) Math.min(maxVertices - 1,
                    Math.max(1, Math.ceil(length / TRACK_SAMPLE_METRES)));
            double[] xs = new double[steps + 1];
            double[] zs = new double[steps + 1];
            double startY = 0;
            for (int i = 0; i <= steps; i++) {
                Object position = railPositionMethod.invoke(rail, length * i / steps);
                double x = decimal(position, vectorXGetter);
                double y = decimal(position, vectorYGetter);
                double z = decimal(position, vectorZGetter);
                if (i == 0) {
                    startY = y;
                }
                xs[i] = x;
                zs[i] = z;
            }
            return new MtrSnapshotData.Track(hexId, mode, (int) Math.round(startY), xs, zs);
        } catch (ReflectiveOperationException | RuntimeException e) {
            noteUnreadable("rail", e);
            return null;
        }
    }

    /** A rail's made-up id: its two endpoint blocks, order-independent, and its mode. */
    private static String railId(Object from, Object to, String mode) {
        if (!ensureBlockAccessors()) {
            return null;
        }
        int[] start;
        int[] end;
        try {
            start = blockOf(from);
            end = blockOf(to);
        } catch (ReflectiveOperationException | RuntimeException unreadable) {
            return null;
        }
        if (start == null || end == null) {
            return null;
        }
        String one = start[0] + "," + start[1] + "," + start[2];
        String two = end[0] + "," + end[1] + "," + end[2];
        return "m3:" + (one.compareTo(two) <= 0 ? one + "|" + two : two + "|" + one)
                + ":" + (mode == null ? "?" : mode);
    }

    /** The station whose rectangle a point stands in, preferring the smallest rectangle. */
    private static long stationContaining(List<MtrSnapshotData.Station> stations, int x, int z) {
        long found = 0;
        long smallest = Long.MAX_VALUE;
        for (MtrSnapshotData.Station station : stations) {
            if (x < station.minX() || x > station.maxX() || z < station.minZ() || z > station.maxZ()) {
                continue;
            }
            long area = (long) (station.maxX() - station.minX() + 1)
                    * (station.maxZ() - station.minZ() + 1);
            if (area < smallest) {
                smallest = area;
                found = station.id();
            }
        }
        return found;
    }

    /** The stations, with a height taken from their platforms where they have none of their own. */
    private static List<MtrSnapshotData.Station> withPlatformHeights(
            List<MtrSnapshotData.Station> stations, List<MtrSnapshotData.Platform> platforms) {
        Map<Long, long[]> sums = new HashMap<>();
        for (MtrSnapshotData.Platform platform : platforms) {
            if (platform.stationId() == 0) {
                continue;
            }
            long[] sum = sums.computeIfAbsent(platform.stationId(), key -> new long[2]);
            sum[0] += platform.y();
            sum[1]++;
        }
        if (sums.isEmpty()) {
            return stations;
        }
        List<MtrSnapshotData.Station> lifted = new ArrayList<>(stations.size());
        for (MtrSnapshotData.Station station : stations) {
            long[] sum = sums.get(station.id());
            if (station.centerY() != 0 || sum == null || sum[1] == 0) {
                lifted.add(station);
                continue;
            }
            int y = (int) Math.round((double) sum[0] / sum[1]);
            lifted.add(new MtrSnapshotData.Station(station.id(), station.name(), station.mode(),
                    station.color(), station.centerX(), y, station.centerZ(), station.minX(), y,
                    station.minZ(), station.maxX(), y, station.maxZ()));
        }
        return lifted;
    }

    // -------------------------------------------------------------------- binding

    /** Whether MTR 3.x's client data can be read in this session. */
    public static synchronized boolean bindClient() {
        if (clientResolved) {
            return clientAvailable;
        }
        clientResolved = true;
        for (String[] candidate : CLIENT_CANDIDATES) {
            Class<?> type;
            try {
                type = load(candidate[0]);
            } catch (ClassNotFoundException | LinkageError absent) {
                continue;
            }
            if (!bindShapes(candidate[1])) {
                continue;
            }
            try {
                boundClientName = candidate[0];
                clientInstanceMethod = staticMethod(type, "getInstance");
                clientStationsField = requiredField(type, "STATIONS", "stations");
                clientPlatformsField = requiredField(type, "PLATFORMS", "platforms");
                clientRoutesField = requiredField(type, "ROUTES", "routes");
                // Optional: the other readings do not go through the client rails.
                clientRailsField = optionalField(type, "RAILS", "rails");
                clientCacheField = optionalField(type, "DATA_CACHE", "dataCache", "cache");
                clientAvailable = true;
                HowToGo.LOGGER.info("[HowToGo] MTR 3.x client data bound reflectively ({})",
                        boundClientName);
                return true;
            } catch (ReflectiveOperationException | LinkageError | RuntimeException wrong) {
                clientWarned = true;
                HowToGo.LOGGER.info("[HowToGo] MTR 3.x client data at {} is not the shape this "
                        + "reads ({})", candidate[0], wrong.toString());
            }
        }
        return false;
    }

    /** Whether MTR 3.x's simulated data can be read in this session. */
    public static synchronized boolean bindServer() {
        if (serverResolved) {
            return serverAvailable;
        }
        serverResolved = true;
        for (String base : SERVER_BASES) {
            Class<?> type;
            try {
                type = load(base + ".data.RailwayData");
            } catch (ClassNotFoundException | LinkageError absent) {
                continue;
            }
            if (!bindShapes(base)) {
                continue;
            }
            try {
                railwayDataClass = type;
                railwayStationsField = requiredField(type, "stations");
                railwayPlatformsField = requiredField(type, "platforms");
                railwayRoutesField = requiredField(type, "routes");
                railwayCacheField = optionalField(type, "dataCache", "cache");
                railwayGetInstanceMethod = staticMethod(type, "getInstance", 1);
                if (railwayGetInstanceMethod == null) {
                    throw new NoSuchMethodException(type.getName() + ".getInstance");
                }
                // Rails are optional; the getter is preferred and the field is the fallback, so this works before and after the accessor was added.
                railwayRailsMethod = methodObject(type, "getRailsMap");
                railwayRailsField = accessibleField(type, "rails");
                if (railwayRailsMethod == null && railwayRailsField == null) {
                    HowToGo.LOGGER.info("[HowToGo] MTR 3.x at {} keeps its rails where this cannot reach "
                            + "them; the whole-map reading will have no track", type.getName());
                }
                serverAvailable = true;
                HowToGo.LOGGER.info("[HowToGo] MTR 3.x simulated data reachable through {}",
                        type.getName());
                return true;
            } catch (ReflectiveOperationException | LinkageError | RuntimeException wrong) {
                serverWarned = true;
                HowToGo.LOGGER.info("[HowToGo] MTR 3.x simulated data at {} is not the shape this "
                        + "reads ({})", type.getName(), wrong.toString());
            }
        }
        return false;
    }

    /** Whether MTR 3.x's client classes are on the classpath at all. */
    public static boolean clientPresent() {
        for (String[] candidate : CLIENT_CANDIDATES) {
            try {
                load(candidate[0]);
                // The data model is part of the answer: a class called ClientData is not MTR 3.x on its own.
                load(candidate[1] + ".data.Station");
                load(candidate[1] + ".data.Platform");
                return true;
            } catch (ClassNotFoundException | LinkageError absent) {
                // Not this family; the next one is the other spelling of the same class.
            }
        }
        return false;
    }

    /** Whether MTR 3.x's simulated data class is on the classpath at all. */
    public static boolean serverPresent() {
        for (String base : SERVER_BASES) {
            try {
                load(base + ".data.RailwayData");
                load(base + ".data.Station");
                return true;
            } catch (ClassNotFoundException | LinkageError absent) {
                // Not this family; the next one is the other spelling of the same class.
            }
        }
        return false;
    }

    /** Whether bindClient() succeeded. */
    public static boolean clientAvailable() {
        return clientAvailable;
    }

    /** Whether bindServer() succeeded. */
    public static boolean serverAvailable() {
        return serverAvailable;
    }

    /**
     * Looks up the element shapes of one package family and remembers whether it worked; nothing privileged is asked for and Minecraft's types are resolved later.
     */
    private static synchronized boolean bindShapes(String base) {
        if (shapesResolved) {
            if (base.equals(shapesBase)) {
                return shapesAvailable;
            }
            resetShapes();
        }
        shapesResolved = true;
        shapesBase = base;
        try {
            stationClass = load(base + ".data.Station");
            platformClass = load(base + ".data.Platform");
            routeClass = load(base + ".data.Route");
            railClass = load(base + ".data.Rail");
            routePlatformClass = load(base + ".data.Route$RoutePlatform");

            idGetter = fieldOrMethod(stationClass, new String[]{"id"}, "getId");
            nameGetter = fieldOrMethod(stationClass, new String[]{"name"}, "getName");
            colourGetter = fieldOrMethod(stationClass, new String[]{"color", "colour"},
                    "getColor", "getColour");
            modeGetter = fieldOrMethod(stationClass, new String[]{"transportMode", "mode"},
                    "getTransportMode");
            centreMethod = methodObject(stationClass, "getCenter");
            cornerFirstField = optionalField(stationClass, "corner1");
            cornerSecondField = optionalField(stationClass, "corner2");
            midMethod = methodObject(platformClass, "getMidPos");

            routePlatformsGetter = fieldOrMethod(routeClass,
                    new String[]{"platformIds", "platforms"}, "getPlatforms", "getRoutePlatforms");
            stopPlatformIdGetter = fieldOrMethod(routePlatformClass, new String[]{"platformId"},
                    "getPlatformId");
            stopDestinationGetter = fieldOrMethod(routePlatformClass,
                    new String[]{"customDestination", "destination"}, "getDestination",
                    "getCustomDestination");
            routeDestinationByIndexMethod = methodObject(routeClass, "getDestination", int.class);
            routeHiddenGetter = fieldOrMethod(routeClass, new String[]{"isHidden", "hidden"},
                    "getHidden", "isHidden");
            // The loop state of the route: optional and unverified, so a family without these names still reads, with two-way lines.
            routeCircularGetter = fieldOrMethod(routeClass,
                    new String[]{"circular", "isCircular", "circularState"},
                    "isCircular", "getCircularState", "getCircular");

            railLengthGetter = noArgMethod(railClass, "getLength");
            railPositionMethod = methodObject(railClass, "getPosition", double.class);
            railModeGetter = fieldOrMethod(railClass, new String[]{"transportMode", "mode"},
                    "getTransportMode");

            boolean hasStationPosition = centreMethod != null
                    || (cornerFirstField != null && cornerSecondField != null);
            shapesAvailable = idGetter != null && nameGetter != null && colourGetter != null
                    && modeGetter != null && midMethod != null && routePlatformsGetter != null
                    && stopPlatformIdGetter != null && railLengthGetter != null
                    && railPositionMethod != null && hasStationPosition;
            if (!shapesAvailable) {
                HowToGo.LOGGER.info("[HowToGo] MTR 3.x's data at {} is missing one of the shapes this "
                        + "reads; it is not used", base);
            }
            return shapesAvailable;
        } catch (ClassNotFoundException | LinkageError | RuntimeException missing) {
            shapesAvailable = false;
            return false;
        }
    }

    /** Forgets the resolved shapes so that another package family can be tried. */
    private static void resetShapes() {
        shapesResolved = false;
        stationClass = null;
        platformClass = null;
        routeClass = null;
        railClass = null;
        routePlatformClass = null;
        idGetter = null;
        nameGetter = null;
        colourGetter = null;
        modeGetter = null;
        centreMethod = null;
        cornerFirstField = null;
        cornerSecondField = null;
        pairFirstGetter = null;
        pairSecondGetter = null;
        midMethod = null;
        routePlatformsGetter = null;
        stopPlatformIdGetter = null;
        stopDestinationGetter = null;
        routeDestinationByIndexMethod = null;
        routeHiddenGetter = null;
        routeCircularGetter = null;
        railLengthGetter = null;
        railPositionMethod = null;
        railModeGetter = null;
    }

    // --------------------------------------------------- Minecraft's two name families

    /**
     * BlockPos's coordinate accessors, resolved late because a bind can happen with no game running, trying both of Minecraft's naming families.
     */
    private static boolean ensureBlockAccessors() {
        if (blockAccessorsBound) {
            return blockXGetter != null && blockYGetter != null && blockZGetter != null;
        }
        blockAccessorsBound = true;
        Class<?> blockClass = centreMethod != null ? centreMethod.getReturnType()
                : midMethod != null ? midMethod.getReturnType() : null;
        if (blockClass == null) {
            return false;
        }
        blockXGetter = noArgMethod(blockClass, "getX", "x", "method_10263");
        blockYGetter = noArgMethod(blockClass, "getY", "y", "method_10264");
        blockZGetter = noArgMethod(blockClass, "getZ", "z", "method_10260");
        return blockXGetter != null && blockYGetter != null && blockZGetter != null;
    }

    /** Vec3d's coordinates, the same way and for the same reason as ensureBlockAccessors(). */
    private static boolean ensureVectorAccessors() {
        if (vectorAccessorsBound) {
            return vectorXGetter != null && vectorYGetter != null && vectorZGetter != null;
        }
        vectorAccessorsBound = true;
        if (railPositionMethod == null) {
            return false;
        }
        Class<?> vectorClass = railPositionMethod.getReturnType();
        vectorXGetter = fieldOrMethod(vectorClass, new String[]{"x", "field_1352"},
                "getX", "x", "method_10216");
        vectorYGetter = fieldOrMethod(vectorClass, new String[]{"y", "field_1351"},
                "getY", "y", "method_10214");
        vectorZGetter = fieldOrMethod(vectorClass, new String[]{"z", "field_1350"},
                "getZ", "z", "method_10215");
        return vectorXGetter != null && vectorYGetter != null && vectorZGetter != null;
    }

    /** A block position as three ints, or null when there is no position or no way to read one. */
    private static int[] blockOf(Object blockPosition) throws ReflectiveOperationException {
        if (blockPosition == null || !ensureBlockAccessors()) {
            return null;
        }
        return new int[]{(int) number(blockPosition, blockXGetter),
                (int) number(blockPosition, blockYGetter),
                (int) number(blockPosition, blockZGetter)};
    }

    /** A station's rectangle in X and Z, as {minX, minZ, maxX, maxZ}, or null without corners. */
    private static int[] cornersOf(Object raw) throws ReflectiveOperationException {
        if (cornerFirstField == null || cornerSecondField == null) {
            return null;
        }
        Object first = cornerFirstField.get(raw);
        Object second = cornerSecondField.get(raw);
        if (first == null || second == null) {
            return null;
        }
        if (pairFirstGetter == null && !bindPairAccessors(cornerFirstField.getType())) {
            return null;
        }
        int firstX = (int) number(first, pairFirstGetter);
        int firstZ = (int) number(first, pairSecondGetter);
        int secondX = (int) number(second, pairFirstGetter);
        int secondZ = (int) number(second, pairSecondGetter);
        return new int[]{Math.min(firstX, secondX), Math.min(firstZ, secondZ),
                Math.max(firstX, secondX), Math.max(firstZ, secondZ)};
    }

    /** The two accessors of the tuple a station's corners are kept in. */
    private static boolean bindPairAccessors(Class<?> pairClass) {
        // mojmap calls a tuple's halves getA/getB, the Fabric runtime calls them method_15442/method_15441; both are tried.
        pairFirstGetter = noArgMethod(pairClass, "getA", "getFirst", "getLeft", "method_15442");
        pairSecondGetter = noArgMethod(pairClass, "getB", "getSecond", "getRight", "method_15441");
        return pairFirstGetter != null && pairSecondGetter != null;
    }

    // ------------------------------------------------------------------ the cache

    /**
     * The platform-to-station map, or null when this version has none. The only exact link between the two in 3.x; the field is looked for on whatever type the cache object is.
     */
    private static Map<?, ?> platformStationsOf(Object cache) {
        if (cache == null) {
            return null;
        }
        if (platformToStationField == null
                || !platformToStationField.getDeclaringClass().isInstance(cache)) {
            platformToStationField = optionalField(cache.getClass(), "platformIdToStation",
                    "platformIdToStationMap");
        }
        if (platformToStationField == null) {
            return null;
        }
        try {
            Object value = platformToStationField.get(cache);
            return value instanceof Map<?, ?> map ? map : null;
        } catch (ReflectiveOperationException | RuntimeException unreadable) {
            return null;
        }
    }

    // -------------------------------------------------------------------- helpers

    private static MtrSnapshotData.Dimension empty() {
        return new MtrSnapshotData.Dimension("", List.of(), List.of(), List.of(), List.of());
    }

    private static Iterable<?> elements(Object collection) {
        return collection instanceof Collection<?> items ? items : List.of();
    }

    private static Object fieldValue(Field field, Object instance)
            throws ReflectiveOperationException {
        if (field == null) {
            return null;
        }
        return Modifier.isStatic(field.getModifiers()) ? field.get(null) : field.get(instance);
    }

    private static long number(Object target, Getter getter) throws ReflectiveOperationException {
        Object value = getter.get(target);
        return value instanceof Number counted ? counted.longValue() : 0L;
    }

    private static double decimal(Object target, Getter getter) throws ReflectiveOperationException {
        Object value = getter.get(target);
        return value instanceof Number counted ? counted.doubleValue() : 0.0;
    }

    private static String text(Object target, Getter getter) throws ReflectiveOperationException {
        Object value = getter.get(target);
        return value instanceof String string ? string : null;
    }

    /** One getter's value, or null when it is absent or fails -- for the optional shapes. */
    private static Object value(Object target, Getter getter) {
        if (getter == null || target == null) {
            return null;
        }
        try {
            return getter.get(target);
        } catch (ReflectiveOperationException | RuntimeException unreadable) {
            return null;
        }
    }

    /** The name of an enum constant, or null when the value is not one. */
    private static String modeName(Object value) {
        return value instanceof Enum<?> constant ? constant.name() : null;
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    // ------------------------------------------------------- reflective lookups

    /** One member's value, whichever of the two shapes the class gives it in. */
    @FunctionalInterface
    private interface Getter {
        Object get(Object target) throws ReflectiveOperationException;
    }

    private static Getter publicField(Class<?> owner, String... names) {
        Field field = optionalField(owner, names);
        return field == null ? null : field::get;
    }

    /** One of several spellings of a method taking no arguments, first that exists. */
    private static Getter noArgMethod(Class<?> owner, String... names) {
        for (String name : names) {
            Getter found = invoke(owner, name);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static Getter invoke(Class<?> owner, String name) {
        try {
            return owner.getMethod(name)::invoke;
        } catch (NoSuchMethodException | RuntimeException absent) {
            return null;
        }
    }

    /** A method looked up and kept as one, for a caller that needs its signature or its arguments. */
    private static Method methodObject(Class<?> owner, String name, Class<?>... parameters) {
        try {
            return owner.getMethod(name, parameters);
        } catch (NoSuchMethodException | RuntimeException absent) {
            return null;
        }
    }

    /** A member by field or, when the class has no such field, by getter. */
    private static Getter fieldOrMethod(Class<?> owner, String[] fields, String... methods) {
        Getter found = publicField(owner, fields);
        return found != null ? found : noArgMethod(owner, methods);
    }

    private static Field requiredField(Class<?> owner, String... names)
            throws NoSuchFieldException {
        Field found = optionalField(owner, names);
        if (found == null) {
            throw new NoSuchFieldException(owner.getName() + "." + String.join("|", names));
        }
        return found;
    }

    private static Field optionalField(Class<?> owner, String... names) {
        for (String name : names) {
            try {
                return owner.getField(name);
            } catch (NoSuchFieldException | RuntimeException absent) {
                // Not this name; the next one is another spelling of the same field.
            }
        }
        return null;
    }

    /**
     * A field anywhere up a class chain, made accessible, or null when there is none. getField cannot see MTR's private rails map, and a JVM that refuses setAccessible is answered by null.
     */
    private static Field accessibleField(Class<?> owner, String... names) {
        for (String name : names) {
            for (Class<?> type = owner; type != null && type != Object.class;
                 type = type.getSuperclass()) {
                Field field;
                try {
                    field = type.getDeclaredField(name);
                } catch (NoSuchFieldException | SecurityException | LinkageError absent) {
                    // Not declared here; an inherited one may be declared further up the chain.
                    continue;
                }
                try {
                    field.setAccessible(true);
                } catch (RuntimeException refused) {
                    return null;
                }
                return field;
            }
        }
        return null;
    }


    /** A static method taking no arguments, or null. */
    private static Method staticMethod(Class<?> owner, String name) {
        return staticMethod(owner, name, 0);
    }

    /** A static method of the given parameter count, or null. */
    private static Method staticMethod(Class<?> owner, String name, int parameters) {
        try {
            for (Method candidate : owner.getMethods()) {
                if (candidate.getName().equals(name)
                        && candidate.getParameterCount() == parameters
                        && Modifier.isStatic(candidate.getModifiers())) {
                    return candidate;
                }
            }
        } catch (RuntimeException | LinkageError unreadable) {
            return null;
        }
        return null;
    }

    private static Class<?> load(String name) throws ClassNotFoundException {
        return Class.forName(name, false, Mtr3Data.class.getClassLoader());
    }

    // --------------------------------------------------------------------- report

    private static void noteUnreadable(String what, Throwable cause) {
        unreadable++;
        if (unreadable <= MAX_REPORTED_FAILURES) {
            HowToGo.LOGGER.warn("[HowToGo] could not read an MTR 3.x {} ({}); it is left out of the "
                    + "reading", what, cause.toString());
        } else if (unreadable == MAX_REPORTED_FAILURES + 1) {
            HowToGo.LOGGER.warn("[HowToGo] further unreadable MTR 3.x elements will not be reported");
        }
    }

    private static void warnClient(Throwable cause) {
        if (clientWarned) {
            return;
        }
        clientWarned = true;
        HowToGo.LOGGER.warn("[HowToGo] MTR 3.x's client data could not be read ({}); its stations and "
                + "lines will not be offered, and nothing else changes", cause.toString());
    }

    private static void warnServer(Throwable cause) {
        if (serverWarned) {
            return;
        }
        serverWarned = true;
        HowToGo.LOGGER.warn("[HowToGo] MTR 3.x's simulated network could not be read ({}); the windowed "
                + "reading is what remains", cause.toString());
    }
}
