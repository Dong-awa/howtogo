package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.HowToGo;
import bili.dongsz.howtogo.RoadConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The whole railway, read out of the copy MTR Map Overlay fetched from the server.
 *
 * MTR sends a client only the stations and lines within a couple hundred blocks (what MtrClientData reads), and on a remote server this process simulates nothing, so the overlay's server-fetched snapshot of every line, station and rail is the only whole-railway source. Only the fetched snapshot is read, never its radius-limited fallback, which MtrClientData already covers.
 * Chain, all read reflectively: MapDataCache.hasServerData(dimension)/get(dimension), then .dimensionId ("minecraft/overworld"), .version (content hash), .routes (id, name, color, circular, stops: x, z, stationName, destination, trackIds), .tracks (id, points), .landmarks (record: id(), type(), x(), y(), z(), name(), symbol()).
 * The overlay moved its own classes once: 1.5.1 and earlier are com.lx862.mtrmap, 1.5.2 and later are io.github.teamcreating.mtrmap. The move renamed the package and nothing else -- no field, method or id spelling changed with it -- so this reads whichever is installed, newest name first, and a session stays on the one its handshake bound to.
 * The conversion runs once per snapshot, keyed by its version hash, because every rail arrives as a list of points that has to be copied.
 * Rails are taken whole, with no player radius: the snapshot says which rails each route runs along, so a line's track is a lookup (MtrLineTracks.stated) rather than a plan, and a radius only stripped geometry and left straight hops between stations.
 * The snapshot carries no transport mode -- the overlay samples only TRAIN rails, so this is the train network. A rail's height is the measured world Y its points carry (1.6.0 samples X, Z and Y); a rail whose points are flat, as an older snapshot's were when it stored only X and Z, takes the height of the nearest landmark within HEIGHT_HINT_RADIUS, else FALLBACK_TRACK_Y. MtrClientData#merge lets MTR's own copy win wherever MTR sent it.
 * Reflective like MtrClientData and MtrWholeMap: no overlay type appears in a signature, every handle is looked up by name and guarded, and a version whose shapes moved reports itself unavailable.
 */
final class MtrMapOverlay {

    private static final String MOD_ID = "mtrmap";

    /**
     * The overlay's package, in the order to try.
     *
     * <p>1.5.2 renamed its classes from {@code com.lx862.mtrmap} to {@code io.github.teamcreating.mtrmap}; the data model, the field names and the id spellings came across unchanged, so one handshake that tries both covers every release and neither needs its own reader.
     */
    private static final String[] PACKAGES = {
            "io.github.teamcreating.mtrmap",
            "com.lx862.mtrmap",
    };

    /** The suffix of every name this reads, so the package prefix is the only version-dependent part. */
    private static final String CACHE_CLASS = ".mapdata.MapDataCache";
    private static final String DIMENSION_DATA_CLASS = CACHE_CLASS + "$DimensionData";
    private static final String ROUTE_CLASS = ".mapdata.MapRoute";
    private static final String ROUTE_STOP_CLASS = ROUTE_CLASS + "$Stop";
    private static final String TRACK_CLASS = ".mapdata.MapTrack";
    private static final String LANDMARK_CLASS = ".mapdata.MapLandmark";

    /**
     * The kind of a line or rail read out of the snapshot: it only carries train data, and naming that lets the reading go through the same conversion as MTR's own.
     */
    private static final String TRAIN = "TRAIN";

    /**
     * The height a rail of the snapshot stands at when its points carry none and no landmark is near. A flat snapshot states no height, and sea level beats nothing for a line across open country.
     */
    private static final int FALLBACK_TRACK_Y = 64;

    /** How far a rail looks for a landmark to take its height from, in blocks. */
    private static final int HEIGHT_HINT_RADIUS = 48;

    /** The grid cell a height hint is filed under, in blocks. */
    private static final int HINT_CELL = 32;

    private static boolean resolved;
    private static boolean available;
    private static boolean warned;
    /** The package the handshake bound to, or null while none has; named in the log and used by the harness. */
    private static String boundTo;
    /** What the newest candidate failed with, kept so an unreadable overlay is reported by its own error. */
    private static Throwable failure;

    private static Method hasServerDataMethod;
    private static Method getMethod;
    private static Field routesField;
    private static Field tracksField;
    private static Field landmarksField;
    private static Field dimensionIdField;
    private static Field versionField;
    private static Field routeIdField;
    private static Field routeNameField;
    private static Field routeColorField;
    /** Whether the route is a loop, as the overlay's own boolean; optional like every addition. */
    private static Field routeCircularField;
    private static Field routeStopsField;
    private static Field routeTrackIdsField;
    private static Field stopXField;
    private static Field stopZField;
    private static Field stopStationNameField;
    private static Field stopDestinationField;
    private static Field trackIdField;
    private static Field trackPointsField;
    private static Method landmarkIdMethod;
    private static Method landmarkTypeMethod;
    private static Method landmarkXMethod;
    private static Method landmarkYMethod;
    private static Method landmarkZMethod;
    private static Method landmarkNameMethod;
    private static Method landmarkSymbolMethod;

    /** The snapshot the conversion was last run for, and the reading it produced. */
    private static String convertedKey;
    private static MtrClientData.Snapshot converted = MtrClientData.Snapshot.EMPTY;

    /** How many elements this session could not read, so that a wrong shape cannot stay quiet. */
    private static int unreadable;

    private MtrMapOverlay() {
    }

    /** Whether the snapshot can be read at all in this session. */
    static boolean available() {
        resolve();
        return available;
    }

    /** Whether this reading is switched on, asked through the one switch that owns the answer. */
    static boolean enabled() {
        return RoadConfig.mtrTransit() && RoadConfig.mtrMapOverlay();
    }

    /** Forgets the converted snapshot, which is what switching this reading off does. */
    static void reset() {
        convertedKey = null;
        converted = MtrClientData.Snapshot.EMPTY;
    }

    /**
     * The whole railway fetched for the dimension the player is in, or an empty reading.
     *
     * An empty reading covers every case where this has nothing to say: mod absent or moved, reading switched off, no world or player, no server that answered, another dimension, unreadable shapes. Never throws -- this runs on a client tick beside MtrClientData#read.
     */
    static MtrClientData.Snapshot read() {
        resolve();
        if (!available) {
            return MtrClientData.Snapshot.EMPTY;
        }
        if (!enabled()) {
            // Switched off means forgotten rather than merely not read again: a whole railway left about would
            // go on being offered.
            reset();
            return MtrClientData.Snapshot.EMPTY;
        }
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return MtrClientData.Snapshot.EMPTY;
        }
        // MTR names its world "minecraft/overworld" and the snapshot is keyed the same way, which is the
        // spelling MtrWholeMap matches its simulators by.
        String dimension = player.level().dimension().location().toString().replace(':', '/');
        try {
            if (!Boolean.TRUE.equals(hasServerDataMethod.invoke(null, dimension))) {
                // Either the server does not run the overlay or it has not answered yet; what the client holds then
                // is MTR's own window, which MtrClientData already reads.
                return MtrClientData.Snapshot.EMPTY;
            }
            Object data = getMethod.invoke(null, dimension);
            if (data == null || !dimension.equals(dimensionIdField.get(data))) {
                // The snapshot was dropped between the two calls and the radius-limited fallback came back, which is
                // not what this class is for.
                return MtrClientData.Snapshot.EMPTY;
            }
            String key = dimension + '@' + (versionField == null
                    ? System.identityHashCode(data) : versionField.getLong(data));
            if (!key.equals(convertedKey)) {
                MtrClientData.Snapshot reading = convert(data);
                converted = reading;
                convertedKey = key;
                HowToGo.diagnostic("[HowToGo] MTR Map Overlay | {} | {} station(s), {} platform(s), "
                                + "{} line(s), {} rail(s) fetched from the server", dimension,
                        reading.stations().size(), reading.platforms().size(), reading.lines().size(),
                        reading.tracks().size());
            }
            return converted;
        } catch (ReflectiveOperationException | RuntimeException e) {
            warnUnavailable(e);
            return MtrClientData.Snapshot.EMPTY;
        }
    }


    /**
     * One snapshot as this mod's own reading.
     *
     * Everything is placed from the landmarks, as MTR's own reading does: a stop stands at the middle of its station's platforms, and a station built a platform at a time arrives as several stations that MtrTransit then groups. Links the snapshot does not carry are rebuilt by name then nearest distance: a landmark's id says station or platform but not which station, and a route's stop names its station without identifying it.
     * Package-private because the harness builds one of the overlay's own {@code DimensionData}s and checks this conversion without a game -- see {@code MtrImportCheck}; the shapes are read reflectively there too, so the check is against the jar that is installed.
     */
    static MtrClientData.Snapshot convert(Object data) throws ReflectiveOperationException {
        List<Object> stationsRaw = new ArrayList<>();
        List<Object> platformsRaw = new ArrayList<>();
        for (Object landmark : MtrClientData.elements(landmarksField.get(data))) {
            String type = landmarkType(landmark);
            if ("STATION".equals(type)) {
                stationsRaw.add(landmark);
            } else if ("PLATFORM".equals(type)) {
                platformsRaw.add(landmark);
            }
        }

        List<MtrClientData.Station> stations = new ArrayList<>(stationsRaw.size());
        Map<String, List<MtrClientData.Station>> byName = new HashMap<>();
        for (Object landmark : stationsRaw) {
            MtrClientData.Station station = station(landmark);
            if (station == null) {
                continue;
            }
            stations.add(station);
            String name = placeKey(station.name());
            if (!name.isEmpty()) {
                byName.computeIfAbsent(name, key -> new ArrayList<>()).add(station);
            }
        }

        // Platforms, each hung on the nearest station of its own name. A platform whose station the snapshot
        // does not hold is left out: nothing beats a boarding point on a station nobody built.
        List<MtrClientData.Platform> platforms = new ArrayList<>(platformsRaw.size());
        Map<Long, List<Hint>> hints = new HashMap<>();
        for (Object landmark : platformsRaw) {
            MtrClientData.Platform platform = platform(landmark, byName);
            if (platform == null) {
                continue;
            }
            platforms.add(platform);
            hint(landmark, hints);
        }
        for (MtrClientData.Station station : stations) {
            hint(hints, station.centerX(), station.centerY(), station.centerZ());
        }

        List<MtrClientData.Line> lines = new ArrayList<>();
        for (Object route : MtrClientData.elements(routesField.get(data))) {
            MtrClientData.Line line = line(route, byName);
            if (line != null) {
                lines.add(line);
            }
        }

        return new MtrClientData.Snapshot(List.copyOf(stations), List.copyOf(platforms),
                List.copyOf(lines), tracks(data, hints));
    }

    /**
     * One station of the snapshot. The extent the player dragged is not in the snapshot, so the extent is the point; nothing downstream reads it.
     */
    private static MtrClientData.Station station(Object landmark) {
        try {
            Long id = idOf(string(landmarkIdMethod.invoke(landmark)));
            if (id == null) {
                noteUnreadable("station", null);
                return null;
            }
            int x = (int) Math.round(number(landmarkXMethod.invoke(landmark)));
            int y = (int) Math.round(number(landmarkYMethod.invoke(landmark)));
            int z = (int) Math.round(number(landmarkZMethod.invoke(landmark)));
            return new MtrClientData.Station(id, string(landmarkNameMethod.invoke(landmark)), TRAIN, 0,
                    x, y, z, x, y, z, x, y, z);
        } catch (ReflectiveOperationException | RuntimeException e) {
            noteUnreadable("station", e);
            return null;
        }
    }

    /**
     * One platform, on the nearest station of its own name -- the only link the snapshot has, and distance settles a name shared by several areas of one station.
     */
    private static MtrClientData.Platform platform(Object landmark,
                                                   Map<String, List<MtrClientData.Station>> byName) {
        try {
            Long id = idOf(string(landmarkIdMethod.invoke(landmark)));
            if (id == null) {
                noteUnreadable("platform", null);
                return null;
            }
            int x = (int) Math.round(number(landmarkXMethod.invoke(landmark)));
            int y = (int) Math.round(number(landmarkYMethod.invoke(landmark)));
            int z = (int) Math.round(number(landmarkZMethod.invoke(landmark)));
            MtrClientData.Station station =
                    nearest(byName.get(placeKey(string(landmarkNameMethod.invoke(landmark)))), x, z);
            if (station == null) {
                return null;
            }
            return new MtrClientData.Platform(id, station.id(),
                    string(landmarkSymbolMethod.invoke(landmark)), TRAIN, x, y, z);
        } catch (ReflectiveOperationException | RuntimeException e) {
            noteUnreadable("platform", e);
            return null;
        }
    }

    /**
     * One route, with the stops whose station the snapshot holds. A stop's station is found by name, then nearest of that name; a stop whose station is absent is left out, as MTR's own reading does for out-of-window stations.
     */
    private static MtrClientData.Line line(Object route,
                                           Map<String, List<MtrClientData.Station>> byName) {
        try {
            List<MtrClientData.Stop> stops = new ArrayList<>();
            for (Object stop : MtrClientData.elements(routeStopsField.get(route))) {
                double x = number(stopXField.get(stop));
                double z = number(stopZField.get(stop));
                String name = string(stopStationNameField.get(stop));
                MtrClientData.Station station = nearest(byName.get(placeKey(name)), x, z);
                if (station == null) {
                    continue;
                }
                // The platform id stays zero: a route's stop names no platform and nothing downstream asks for one.
                stops.add(new MtrClientData.Stop(0, station.id(), name,
                        string(stopDestinationField.get(stop))));
            }
            Long id = idOf(string(routeIdField.get(route)));
            if (id == null) {
                noteUnreadable("line", null);
                return null;
            }
            return new MtrClientData.Line(id, string(routeNameField.get(route)), TRAIN,
                    (int) number(routeColorField.get(route)) & 0xFFFFFF, List.copyOf(stops),
                    railsOf(route), circular(route));
        } catch (ReflectiveOperationException | RuntimeException e) {
            noteUnreadable("line", e);
            return null;
        }
    }

    /**
     * Whether the snapshot says this route is a loop ridden one way round. A public field, and optional like every addition: a version without it loses the direction, never the line.
     */
    private static boolean circular(Object route) {
        if (routeCircularField == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(routeCircularField.get(route));
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    /**
     * The rails one route runs along, in path order.
     *
     * The overlay's exact answer to a question MTR never answers; handed to MtrLineTracks, so a line is drawn along its railway instead of joining stations with straight hops.
     */
    private static List<String> railsOf(Object route) {
        if (routeTrackIdsField == null) {
            return List.of();
        }
        try {
            if (!(routeTrackIdsField.get(route) instanceof List<?> ids)) {
                return List.of();
            }
            List<String> rails = new ArrayList<>(ids.size());
            for (Object id : ids) {
                // An id is a rail's hex id, which is how the snapshot's tracks are keyed. Anything else cannot be
                // looked up and is left out rather than hashed into an id that would never match.
                if (id instanceof String text && !text.isEmpty()) {
                    rails.add(text);
                }
            }
            return List.copyOf(rails);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return List.of();
        }
    }

    /**
     * The rails of the snapshot as this mod's own track.
     *
     * A rail takes the measured world Y its own points carry when the snapshot samples one (1.6.0 stores X, Z and Y per point); a flat snapshot's rail is placed by the nearest station or platform within HEIGHT_HINT_RADIUS, else FALLBACK_TRACK_Y. The value is one per rail because that is what a Track is: a height, not a height per vertex.
     */
    private static List<MtrClientData.Track> tracks(Object data, Map<Long, List<Hint>> hints)
            throws ReflectiveOperationException {
        List<MtrClientData.Track> tracks = new ArrayList<>();
        for (Object raw : MtrClientData.elements(tracksField.get(data))) {
            try {
                String hexId = string(trackIdField.get(raw));
                Object points = trackPointsField.get(raw);
                if (hexId == null || !(points instanceof List<?> list) || list.size() < 2) {
                    continue;
                }
                double[] xs = new double[list.size()];
                double[] zs = new double[list.size()];
                int middle = list.size() / 2;
                double measuredY = Double.NaN;
                boolean usable = true;
                for (int i = 0; i < list.size(); i++) {
                    if (!(list.get(i) instanceof double[] point) || point.length < 2
                            || !Double.isFinite(point[0]) || !Double.isFinite(point[1])) {
                        usable = false;
                        break;
                    }
                    xs[i] = point[0];
                    zs[i] = point[1];
                    // The third component is the sampled world Y, and only the rail's own middle point is taken:
                    // the track layer holds one height for the whole polyline. An older snapshot's flat points
                    // have no third component (or a NaN one), which is what the landmark hint is still for.
                    if (i == middle && point.length >= 3 && Double.isFinite(point[2])) {
                        measuredY = point[2];
                    }
                }
                if (!usable) {
                    // The overlay's own codec refuses a non-finite point on the way in, so this is a shape this mod has
                    // not seen: the rail is left out and counted.
                    noteUnreadable("rail", null);
                    continue;
                }
                int y = Double.isNaN(measuredY)
                        ? heightNear(hints, xs[middle], zs[middle]) : (int) Math.round(measuredY);
                tracks.add(new MtrClientData.Track(hexId, TRAIN, y, xs, zs));
            } catch (RuntimeException e) {
                noteUnreadable("rail", e);
            }
        }
        return List.copyOf(tracks);
    }


    /** One station's name as the joins compare it: without case, and without surrounding spaces. */
    private static String placeKey(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }

    /** The station of a name a point stands nearest, or null when the name names no station. */
    private static MtrClientData.Station nearest(List<MtrClientData.Station> sameName, double x,
                                                 double z) {
        if (sameName == null || sameName.isEmpty()) {
            return null;
        }
        MtrClientData.Station nearest = null;
        double best = Double.MAX_VALUE;
        for (MtrClientData.Station station : sameName) {
            double dx = station.centerX() - x;
            double dz = station.centerZ() - z;
            double distance = dx * dx + dz * dz;
            if (distance < best) {
                best = distance;
                nearest = station;
            }
        }
        return nearest;
    }

    /** One landmark's height, and where it stands: a rail with no height of its own is placed by it. */
    private record Hint(int y, int x, int z) {
    }

    /** Files a landmark's height under the cell it stands in. */
    private static void hint(Object landmark, Map<Long, List<Hint>> hints) {
        try {
            hint(hints, (int) Math.round(number(landmarkXMethod.invoke(landmark))),
                    (int) Math.round(number(landmarkYMethod.invoke(landmark))),
                    (int) Math.round(number(landmarkZMethod.invoke(landmark))));
        } catch (ReflectiveOperationException | RuntimeException e) {
            // A landmark with no readable position is a landmark with no height to offer.
        }
    }

    /** The same, for a position already in hand. */
    private static void hint(Map<Long, List<Hint>> hints, int x, int y, int z) {
        hints.computeIfAbsent(cell(x, z), key -> new ArrayList<>()).add(new Hint(y, x, z));
    }

    /** The cell a position is filed under. */
    private static long cell(int x, int z) {
        return ((long) Math.floorDiv(x, HINT_CELL) << 32)
                ^ (Math.floorDiv(z, HINT_CELL) & 0xFFFFFFFFL);
    }

    /**
     * The height of the nearest landmark within reach of a point, or the fallback.
     *
     * Searched cell by cell because rails outnumber landmarks and each rail asks once; asking every landmark each time would be the square of the network for a hint.
     */
    private static int heightNear(Map<Long, List<Hint>> hints, double x, double z) {
        if (hints.isEmpty()) {
            return FALLBACK_TRACK_Y;
        }
        int reach = (int) Math.ceil((double) HEIGHT_HINT_RADIUS / HINT_CELL);
        int cx = Math.floorDiv((int) Math.round(x), HINT_CELL);
        int cz = Math.floorDiv((int) Math.round(z), HINT_CELL);
        int best = FALLBACK_TRACK_Y;
        double nearest = (double) HEIGHT_HINT_RADIUS * HEIGHT_HINT_RADIUS;
        for (int dx = -reach; dx <= reach; dx++) {
            for (int dz = -reach; dz <= reach; dz++) {
                List<Hint> inCell = hints.get(cell((cx + dx) * HINT_CELL, (cz + dz) * HINT_CELL));
                if (inCell == null) {
                    continue;
                }
                double cellX = (cx + dx) * (double) HINT_CELL;
                double cellZ = (cz + dz) * (double) HINT_CELL;
                if ((cellX - x) * (cellX - x) + (cellZ - z) * (cellZ - z) > nearest) {
                    // The cell itself is out of reach, so nothing in it can be in reach.
                    continue;
                }
                for (Hint hint : inCell) {
                    double distance = (hint.x() - x) * (double) (hint.x() - x)
                            + (hint.z() - z) * (double) (hint.z() - z);
                    if (distance < nearest) {
                        nearest = distance;
                        best = hint.y();
                    }
                }
            }
        }
        return best;
    }


    /** A string field or accessor's value, or null when it is not a string. */
    private static String string(Object value) {
        return value instanceof String text ? text : null;
    }

    /** A number's value as a double, or zero when it is not a number. */
    private static double number(Object value) {
        return value instanceof Number counted ? counted.doubleValue() : 0;
    }

    /** The kind of a landmark, or an empty string when it cannot be read. */
    private static String landmarkType(Object landmark) {
        try {
            Object type = landmarkTypeMethod.invoke(landmark);
            return type instanceof Enum<?> constant ? constant.name() : "";
        } catch (ReflectiveOperationException | RuntimeException e) {
            return "";
        }
    }

    /**
     * MTR's own id out of one of the overlay's id strings, or null when there is none.
     * The overlay writes Long.toHexString of MTR's own id behind a kind ("station:1a2b", "platform:...", a route's bare hex, "depot:..." for depot path). Parsing the hex back is what lets a station or line here be the same one MTR's reading calls it, so MtrClientData#merge can tell it has heard about one thing twice. A string that is not hex is keyed by a stable hash of itself: it behaves as an id but can never match MTR's. Package-private because the harness checks it -- this join is the one part of the conversion nothing in the game can show going wrong.
     */
    static Long idOf(String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        int colon = id.lastIndexOf(':');
        String hex = colon < 0 ? id : id.substring(colon + 1);
        if (hex.isEmpty()) {
            return null;
        }
        try {
            return Long.parseUnsignedLong(hex, 16);
        } catch (NumberFormatException notHex) {
            return hashOf(id);
        }
    }

    /** A stable 64-bit hash of a string, for an id that is not MTR's own. */
    private static long hashOf(String text) {
        long hash = 0xcbf29ce484222325L;
        for (int i = 0; i < text.length(); i++) {
            hash = (hash ^ text.charAt(i)) * 0x100000001b3L;
        }
        return hash;
    }

    /**
     * Reports an element that could not be read, and how many have been reported.
     *
     * Each element is read in its own guard so one failure cannot cost the rest; the first few are named and the rest counted, because returning nothing quietly once made this reader look like it worked while discarding every line.
     */
    private static void noteUnreadable(String what, Throwable cause) {
        unreadable++;
        if (unreadable <= MtrClientData.MAX_REPORTED_FAILURES) {
            HowToGo.LOGGER.warn("[HowToGo] could not read an MTR Map Overlay {} ({}); it is left out "
                    + "of the reading", what, cause == null ? "no id" : cause.toString());
        } else if (unreadable == MtrClientData.MAX_REPORTED_FAILURES + 1) {
            HowToGo.LOGGER.warn("[HowToGo] further unreadable MTR Map Overlay elements will not be "
                    + "reported");
        }
    }


    private static synchronized void resolve() {
        if (resolved) {
            return;
        }
        resolved = true;
        // Asked only as a shortcut, and without touching another mod's classes when it says no. Whether the
        // names below are still the overlay's is bind()'s question, so the harness can check the whole
        // handshake against a real jar.
        if (!overlayLoaded()) {
            return;
        }
        bind();
    }

    /**
     * Looks every class, field and method this reads up, and remembers whether it worked.
     *
     * Every one is public API of the overlay, so no privileged access is asked for; a version that moved any of them reports itself unavailable. Classes are loaded without being initialised -- this needs their shapes, not their state.
     *
     * <p>The package is the one part of the chain that has moved: the overlay renamed its own classes in 1.5.2 and renamed nothing else, so each package it is known by is tried in turn, newest first, and the first one that binds the whole chain is what this session reads. A trial that fails leaves nothing behind but its error, since every handle is looked up again from the next name.
     */
    static boolean bind() {
        for (String packageName : PACKAGES) {
            try {
                bind(packageName);
                boundTo = packageName;
                available = true;
                HowToGo.logInfo("[HowToGo] MTR Map Overlay's fetched railway is readable ({})",
                        packageName + CACHE_CLASS);
                return true;
            } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
                // Not this name. Keep the newest name's error for the report: it is the one an up-to-date
                // overlay was expected to answer to, and the old name's "class not found" says nothing.
                if (packageName.equals(PACKAGES[0])) {
                    failure = e;
                }
            }
        }
        available = false;
        warnUnavailable(failure);
        return false;
    }

    /** The whole chain, looked up under one of the packages the overlay has used. */
    private static void bind(String packageName) throws ReflectiveOperationException {
        Class<?> cache = load(packageName + CACHE_CLASS);
        Class<?> dimensionData = load(packageName + DIMENSION_DATA_CLASS);
        Class<?> route = load(packageName + ROUTE_CLASS);
        Class<?> routeStop = load(packageName + ROUTE_STOP_CLASS);
        Class<?> track = load(packageName + TRACK_CLASS);
        Class<?> landmark = load(packageName + LANDMARK_CLASS);

        hasServerDataMethod = cache.getMethod("hasServerData", String.class);
        getMethod = cache.getMethod("get", String.class);
        routesField = dimensionData.getField("routes");
        tracksField = dimensionData.getField("tracks");
        landmarksField = dimensionData.getField("landmarks");
        dimensionIdField = dimensionData.getField("dimensionId");
        // Not required: it says whether the snapshot changed at all; without it the reading converts again
        // every tick, slower but not wrong.
        versionField = optionalField(dimensionData, "version");

        routeIdField = route.getField("id");
        routeNameField = route.getField("name");
        routeColorField = route.getField("color");
        // The route's loop state, as the overlay's own public boolean. Not required: without it every line
        // reads two-way and the lines themselves are unaffected.
        routeCircularField = optionalField(route, "circular");
        routeStopsField = route.getField("stops");
        // The rails the route runs along, in order -- the one thing MTR's own data never says about a line,
        // and why a line is drawn along its railway. Not required: without it the track is worked out, as
        // before.
        routeTrackIdsField = optionalField(route, "trackIds");
        stopXField = routeStop.getField("x");
        stopZField = routeStop.getField("z");
        stopStationNameField = routeStop.getField("stationName");
        stopDestinationField = routeStop.getField("destination");

        trackIdField = track.getField("id");
        trackPointsField = track.getField("points");

        // A landmark is a record, so its accessors are named after its components.
        landmarkIdMethod = landmark.getMethod("id");
        landmarkTypeMethod = landmark.getMethod("type");
        landmarkXMethod = landmark.getMethod("x");
        landmarkYMethod = landmark.getMethod("y");
        landmarkZMethod = landmark.getMethod("z");
        landmarkNameMethod = landmark.getMethod("name");
        landmarkSymbolMethod = landmark.getMethod("symbol");
    }

    /** A class by name, loaded but not initialised. */
    private static Class<?> load(String name) throws ClassNotFoundException {
        return Class.forName(name, false, MtrMapOverlay.class.getClassLoader());
    }

    private static Field optionalField(Class<?> owner, String name) {
        try {
            return owner.getField(name);
        } catch (NoSuchFieldException | RuntimeException absent) {
            return null;
        }
    }

    /**
     * Whether the overlay's classes are on the classpath at all, under either of the names it has had.
     *
     * Asked by the harness so a machine that has never seen the mod skips the handshake check; answered by loading the one class, never by overlayLoaded(), which needs a loader that has been started.
     */
    static boolean classesPresent() {
        for (String packageName : PACKAGES) {
            try {
                load(packageName + CACHE_CLASS);
                return true;
            } catch (ClassNotFoundException | LinkageError absent) {
                // The older name, if this build predates the move.
            }
        }
        return false;
    }

    /**
     * The package the handshake bound to, or null while none has.
     *
     * Package-private for the harness: with the jar on its classpath it builds one of the overlay's own snapshots and checks the conversion, which means naming the classes the same way this reader does.
     */
    static String boundPackage() {
        return boundTo;
    }

    /**
     * Whether MTR Map Overlay is installed, answered without assuming the loader has been asked anything yet (a harness or tool that never started the game). "Not installed" and "no loader to ask" are the same answer here.
     */
    private static boolean overlayLoaded() {
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
        HowToGo.LOGGER.warn("[HowToGo] MTR Map Overlay's fetched railway is unavailable ({} under {}); "
                        + "its stations and lines will not be offered, and nothing else changes",
                cause == null ? "no readable class" : cause.toString(), String.join(" or ", PACKAGES));
    }
}
