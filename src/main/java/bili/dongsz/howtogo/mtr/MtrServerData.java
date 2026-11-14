package bili.dongsz.howtogo.mtr;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * MTR's own network, read on the server that simulates it.
 *
 * MTR sends a client only the railway near the player, so the server reads its whole network here
 * and MtrServerSync sends it to clients.
 *
 * Every handle is looked up by name and guarded -- MTR documents this as internal -- so moved
 * shapes report unavailable. A simulator mutates its sets on its own thread: read only via runOn.
 */
public final class MtrServerData {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** The class MTR keeps its running server in, under each name it has used. */
    private static final String[] ENTRY_NAMES = {
            "org.mtr.MTR",
            "org.mtr.Init",
            "org.mtr.mod.Init",
    };

    private static final String MAIN = "org.mtr.core.Main";
    private static final String SIMULATOR = "org.mtr.core.simulation.Simulator";
    private static final String STATION = "org.mtr.core.data.Station";
    private static final String PLATFORM = "org.mtr.core.data.Platform";
    private static final String ROUTE = "org.mtr.core.data.Route";
    private static final String ROUTE_PLATFORM = "org.mtr.core.data.RoutePlatformData";
    private static final String RAIL = "org.mtr.core.data.Rail";
    private static final String RAIL_MATH = "org.mtr.core.data.RailMath";
    private static final String VECTOR = "org.mtr.core.tool.Vector";
    private static final String POSITION = "org.mtr.core.data.Position";

    private static final double TRACK_SAMPLE_METRES = 4.0;
    private static final int MAX_TRACK_VERTICES = 256;

    private static boolean resolved;
    private static boolean available;
    private static boolean warned;
    private static String boundEntry;

    private static Field mainField;
    private static Field dimensionField;
    private static Field stationsField;
    private static Field platformsField;
    private static Field routesField;
    private static Field railsField;
    private static Field railIdMapField;
    private static Field railMathField;
    private static Field routePlatformField;
    private static Method runMethod;

    private static Method stationIdMethod;
    private static Method stationNameMethod;
    private static Method stationColorMethod;
    private static Method stationModeMethod;
    private static Method stationCenterMethod;
    private static Method stationMinXMethod;
    private static Method stationMinYMethod;
    private static Method stationMinZMethod;
    private static Method stationMaxXMethod;
    private static Method stationMaxYMethod;
    private static Method stationMaxZMethod;
    private static Field platformAreaField;
    private static Method platformMidMethod;
    private static Method positionXMethod;
    private static Method positionYMethod;
    private static Method positionZMethod;

    private static Method routeIdMethod;
    private static Method routeNameMethod;
    private static Method routeColorMethod;
    private static Method routeCircularMethod;
    private static Method routeModeMethod;
    private static Method routeHiddenMethod;
    private static Method routePlatformsMethod;
    private static Method routeDestinationMethod;
    private static Method routeDestinationByIndexMethod;

    private static Method railModeMethod;
    private static Method railHexIdMethod;
    private static Method railMathLengthMethod;
    private static Method railMathPositionMethod;
    private static Method vectorXMethod;
    private static Method vectorYMethod;
    private static Method vectorZMethod;

    private MtrServerData() {
    }

    /**
     * Whether MTR's server-side shapes are the ones this reads.
     * Resolved once and remembered; asked every server tick while a reading exists.
     */
    public static synchronized boolean available() {
        if (resolved) {
            return available;
        }
        resolved = true;
        if (!classesPresent()) {
            return false;
        }
        return bind();
    }

    public static boolean classesPresent() {
        try {
            load(MAIN);
            load(SIMULATOR);
        } catch (ClassNotFoundException | LinkageError absent) {
            return false;
        }
        for (String name : ENTRY_NAMES) {
            try {
                load(name);
                return true;
            } catch (ClassNotFoundException | LinkageError absent) {
            }
        }
        return false;
    }

    /**
     * The running MTR server, or null before it has started or without MTR. On a dedicated
     * server it is the only copy there is.
     */
    public static Object main() {
        if (!available()) {
            return null;
        }
        try {
            return mainField.get(null);
        } catch (ReflectiveOperationException | RuntimeException e) {
            warnUnavailable(e);
            return null;
        }
    }

    /**
     * One simulator per dimension, in whatever order MTR holds them.
     * Found by field type rather than name: the name can change, one per dimension cannot.
     */
    public static List<Object> simulators(Object main) {
        List<Object> found = new ArrayList<>();
        if (main == null || !available()) {
            return found;
        }
        try {
            Class<?> simulator = load(SIMULATOR);
            for (Class<?> type = main.getClass(); type != null; type = type.getSuperclass()) {
                for (Field field : type.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers())) {
                        continue;
                    }
                    field.setAccessible(true);
                    Object value = field.get(main);
                    if (value instanceof Iterable<?> items) {
                        for (Object item : items) {
                            if (simulator.isInstance(item)) {
                                found.add(item);
                            }
                        }
                    }
                    if (!found.isEmpty()) {
                        return found;
                    }
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            warnUnavailable(e);
        }
        return found;
    }

    /**
     * Queues a task on one simulator's own thread -- the only safe way to touch its sets.
     * @return false when there is no simulator, or MTR refused the task
     */
    public static boolean runOn(Object simulator, Runnable task) {
        if (simulator == null || !available() || runMethod == null) {
            return false;
        }
        try {
            runMethod.invoke(simulator, task);
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            warnUnavailable(e);
            return false;
        }
    }

    public static String dimensionOf(Object simulator) {
        if (simulator == null || dimensionField == null) {
            return null;
        }
        try {
            Object value = dimensionField.get(simulator);
            return value instanceof String text ? text : null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Reads one simulator once and returns the dimension and its full fingerprint.
     * Runs on the simulator thread; MtrServerSync uses this so both describe one read.
     */
    public static Sample readWithSignature(Object simulator) {
        MtrSnapshotData.Dimension dimension = read(simulator);
        return dimension == null ? null : new Sample(dimension, signatureOf(dimension));
    }

    /**
     * Full fingerprint of a simulator's network: every station/platform/line field and rail curve.
     * Not count-only (renames and geometry edits would hide); runs on the ten-second poll.
     */
    public static long signatureOf(Object simulator) {
        MtrSnapshotData.Dimension dimension = read(simulator);
        return dimension == null ? 0L : signatureOf(dimension);
    }

    public record Sample(MtrSnapshotData.Dimension dimension, long signature) {
    }

    private static long signatureOf(MtrSnapshotData.Dimension dimension) {
        long hash = 17;
        hash = hash * 31 + textHash(dimension.id());
        hash = hash * 31 + dimension.stations().size();
        hash = hash * 31 + dimension.platforms().size();
        hash = hash * 31 + dimension.lines().size();
        hash = hash * 31 + dimension.tracks().size();
        hash = hash * 31 + dimension.stations().hashCode();
        hash = hash * 31 + dimension.platforms().hashCode();
        hash = hash * 31 + dimension.lines().hashCode();
        for (MtrSnapshotData.Track track : dimension.tracks()) {
            hash = hash * 31 + textHash(track.hexId());
            hash = hash * 31 + textHash(track.mode());
            hash = hash * 31 + track.y();
            hash = hash * 31 + arrayHash(track.xs());
            hash = hash * 31 + arrayHash(track.zs());
        }
        return hash;
    }

    private static int textHash(String value) {
        return value == null ? 0 : value.hashCode();
    }

    private static int arrayHash(double[] values) {
        return values == null ? 0 : java.util.Arrays.hashCode(values);
    }
    /**
     * Every station, platform, line and rail a simulator holds, as a value that can leave MTR's thread.
     * Runs on the simulator thread (see runOn) and keeps nothing of MTR's; one unreadable element
     * costs only itself.
     */
    public static MtrSnapshotData.Dimension read(Object simulator) {
        if (simulator == null || !available()) {
            return null;
        }
        try {
            List<MtrSnapshotData.Station> stations = new ArrayList<>();
            Map<Long, String> modeByStation = new HashMap<>();
            for (Object raw : elements(field(stationsField, simulator))) {
                MtrSnapshotData.Station station = station(raw);
                if (station != null) {
                    stations.add(station);
                    if (station.mode() != null) {
                        modeByStation.put(station.id(), station.mode());
                    }
                }
            }
            List<MtrSnapshotData.Platform> platforms = new ArrayList<>();
            for (Object raw : elements(field(platformsField, simulator))) {
                MtrSnapshotData.Platform platform = platform(raw);
                if (platform != null) {
                    platforms.add(platform);
                }
            }
            List<MtrSnapshotData.Line> lines = new ArrayList<>();
            for (Object raw : elements(field(routesField, simulator))) {
                MtrSnapshotData.Line line = line(raw, modeByStation);
                if (line != null) {
                    lines.add(line);
                }
            }
            List<MtrSnapshotData.Track> tracks = tracks(simulator);
            return new MtrSnapshotData.Dimension(dimensionOf(simulator), stations, platforms,
                    lines, tracks);
        } catch (ReflectiveOperationException | RuntimeException e) {
            warnUnavailable(e);
            return null;
        }
    }


    private static MtrSnapshotData.Station station(Object raw) {
        try {
            Object center = stationCenterMethod.invoke(raw);
            return new MtrSnapshotData.Station(number(raw, stationIdMethod),
                    text(raw, stationNameMethod), modeName(stationModeMethod.invoke(raw)),
                    (int) number(raw, stationColorMethod),
                    (int) Math.round(coordinate(center, positionXMethod)),
                    (int) Math.round(coordinate(center, positionYMethod)),
                    (int) Math.round(coordinate(center, positionZMethod)),
                    (int) number(raw, stationMinXMethod), (int) number(raw, stationMinYMethod),
                    (int) number(raw, stationMinZMethod), (int) number(raw, stationMaxXMethod),
                    (int) number(raw, stationMaxYMethod), (int) number(raw, stationMaxZMethod));
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private static MtrSnapshotData.Platform platform(Object raw) {
        try {
            // A platform's station is read from the area field every saved rail carries.
            Object area = platformAreaField.get(raw);
            Object mid = platformMidMethod.invoke(raw);
            return new MtrSnapshotData.Platform(number(raw, stationIdMethod),
                    area == null ? 0 : number(area, stationIdMethod),
                    text(raw, stationNameMethod), modeName(stationModeMethod.invoke(raw)),
                    (int) Math.round(coordinate(mid, positionXMethod)),
                    (int) Math.round(coordinate(mid, positionYMethod)),
                    (int) Math.round(coordinate(mid, positionZMethod)));
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private static MtrSnapshotData.Line line(Object raw, Map<Long, String> modeByStation) {
        try {
            if (routeHiddenMethod != null && Boolean.TRUE.equals(routeHiddenMethod.invoke(raw))) {
                return null;
            }
            String mode = routeModeMethod == null ? null
                    : modeName(routeModeMethod.invoke(raw));
            List<MtrSnapshotData.Stop> stops = new ArrayList<>();
            int index = 0;
            for (Object routePlatform : elements(routePlatformsMethod.invoke(raw))) {
                Object platform = routePlatformField.get(routePlatform);
                Object station = platform == null ? null : platformAreaField.get(platform);
                long stationId = station == null ? 0 : number(station, stationIdMethod);
                stops.add(new MtrSnapshotData.Stop(
                        platform == null ? 0 : number(platform, stationIdMethod), stationId,
                        station == null ? "" : nameOrEmpty(station),
                        destinationOf(routePlatform, raw, index)));
                if (mode == null && stationId != 0) {
                    mode = modeByStation.get(stationId);
                }
                index++;
            }
            return new MtrSnapshotData.Line(number(raw, routeIdMethod),
                    text(raw, routeNameMethod), mode,
                    (int) number(raw, routeColorMethod) & 0xFFFFFF, stops, List.of(),
                    circular(raw));
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private static String nameOrEmpty(Object station) throws ReflectiveOperationException {
        String name = text(station, stationNameMethod);
        return name == null ? "" : name;
    }

    private static String destinationOf(Object routePlatform, Object route, int index)
            throws ReflectiveOperationException {
        if (routeDestinationMethod != null) {
            return text(routePlatform, routeDestinationMethod);
        }
        if (routeDestinationByIndexMethod != null) {
            Object value = routeDestinationByIndexMethod.invoke(route, index);
            return value instanceof String text ? text : null;
        }
        return null;
    }

    /**
     * Whether MTR calls this route circular, or false when the name is missing or does not answer.
     * CircularState is NONE/CLOCKWISE/ANTICLOCKWISE; a missing accessor means lines are read
     * two-way, which cannot invent a restriction.
     */
    private static boolean circular(Object route) {
        if (routeCircularMethod == null) {
            return false;
        }
        try {
            String state = modeName(routeCircularMethod.invoke(route));
            return state != null && !"NONE".equals(state);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    private static List<MtrSnapshotData.Track> tracks(Object simulator) {
        List<MtrSnapshotData.Track> tracks = new ArrayList<>();
        try {
            Object rails = field(railsField, simulator);
            Object idMap = field(railIdMapField, simulator);
            // The schema getter is preferred; the map is the fallback for a version that keeps the id only as the key.
            Map<Object, String> hexIds = new IdentityHashMap<>();
            if (idMap instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (entry.getKey() instanceof String hex && entry.getValue() != null) {
                        hexIds.put(entry.getValue(), hex);
                    }
                }
            }
            for (Object rail : elements(rails)) {
                String hexId = hexIdOf(rail);
                if (hexId == null) {
                    hexId = hexIds.get(rail);
                }
                MtrSnapshotData.Track track = track(rail, hexId);
                if (track != null) {
                    tracks.add(track);
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            warnOnce("[HowToGo] could not read MTR's rails on the server ({})", e.toString());
        }
        return tracks;
    }

    private static String hexIdOf(Object rail) {
        if (railHexIdMethod == null) {
            return null;
        }
        try {
            Object value = railHexIdMethod.invoke(rail);
            return value instanceof String text && !text.isEmpty() ? text : null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private static MtrSnapshotData.Track track(Object rail, String hexId) {
        try {
            Object math = railMathField.get(rail);
            if (math == null) {
                return null;
            }
            double length = number(math, railMathLengthMethod);
            if (length <= 0) {
                return null;
            }
            int steps = (int) Math.max(1, Math.min(MAX_TRACK_VERTICES - 1,
                    Math.ceil(length / TRACK_SAMPLE_METRES)));
            double[] xs = new double[steps + 1];
            double[] zs = new double[steps + 1];
            double startY = 0;
            for (int i = 0; i <= steps; i++) {
                Object vector = railMathPositionMethod.invoke(math, length * i / steps, false);
                xs[i] = coordinate(vector, vectorXMethod);
                zs[i] = coordinate(vector, vectorZMethod);
                if (i == 0) {
                    startY = coordinate(vector, vectorYMethod);
                }
            }
            return new MtrSnapshotData.Track(hexId, modeName(railModeMethod.invoke(rail)),
                    (int) Math.round(startY), xs, zs);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }


    private static Object field(Field field, Object target) throws ReflectiveOperationException {
        return field == null ? null : field.get(target);
    }

    private static Iterable<?> elements(Object collection) {
        return collection instanceof Collection<?> items ? items : List.of();
    }

    private static long number(Object target, Method method) throws ReflectiveOperationException {
        Object value = method.invoke(target);
        return value instanceof Number counted ? counted.longValue() : 0;
    }

    private static String text(Object target, Method method) throws ReflectiveOperationException {
        Object value = method.invoke(target);
        return value instanceof String string ? string : null;
    }

    private static double coordinate(Object target, Method method) throws ReflectiveOperationException {
        Object value = target == null ? null : method.invoke(target);
        return value instanceof Number counted ? counted.doubleValue() : 0;
    }

    private static String modeName(Object value) {
        return value instanceof Enum<?> constant ? constant.name() : null;
    }


    private static synchronized boolean bind() {
        try {
            Class<?> main = load(MAIN);
            Class<?> simulator = load(SIMULATOR);
            Class<?> entry = null;
            for (String name : ENTRY_NAMES) {
                try {
                    entry = load(name);
                    boundEntry = name;
                    break;
                } catch (ClassNotFoundException next) {
                }
            }
            if (entry == null) {
                throw new ClassNotFoundException(String.join(" or ", ENTRY_NAMES));
            }
            // The field holding the running server, recognised by type rather than name.
            for (Field field : entry.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) && field.getType() == main) {
                    field.setAccessible(true);
                    mainField = field;
                    break;
                }
            }
            if (mainField == null) {
                throw new NoSuchFieldException("no " + MAIN + " field on " + entry.getName());
            }

            runMethod = simulator.getMethod("run", Runnable.class);
            // Not required: it names the dimension; without it a single-dimension client still matches.
            dimensionField = optionalField(simulator, "dimension");

            // The collections live on the simulation core's Data; Simulator extends it, so getField walks.
            stationsField = simulator.getField("stations");
            platformsField = simulator.getField("platforms");
            routesField = optionalField(simulator, "routes");
            railsField = optionalField(simulator, "rails");
            railIdMapField = optionalField(simulator, "railIdMap");

            Class<?> station = load(STATION);
            Class<?> platform = load(PLATFORM);
            Class<?> route = load(ROUTE);
            Class<?> routePlatform = load(ROUTE_PLATFORM);
            Class<?> rail = load(RAIL);
            Class<?> railMath = load(RAIL_MATH);
            Class<?> vector = load(VECTOR);
            Class<?> position = load(POSITION);

            // Looked up on the concrete class: getMethod finds inherited methods, so a base lookup can succeed and fail later.
            stationIdMethod = station.getMethod("getId");
            stationNameMethod = station.getMethod("getName");
            stationColorMethod = station.getMethod("getColor");
            stationModeMethod = station.getMethod("getTransportMode");
            stationCenterMethod = station.getMethod("getCenter");
            stationMinXMethod = station.getMethod("getMinX");
            stationMinYMethod = station.getMethod("getMinY");
            stationMinZMethod = station.getMethod("getMinZ");
            stationMaxXMethod = station.getMethod("getMaxX");
            stationMaxYMethod = station.getMethod("getMaxY");
            stationMaxZMethod = station.getMethod("getMaxZ");

            platformAreaField = platform.getField("area");
            platformMidMethod = platform.getMethod("getMidPosition");

            positionXMethod = position.getMethod("getX");
            positionYMethod = position.getMethod("getY");
            positionZMethod = position.getMethod("getZ");

            routeIdMethod = route.getMethod("getId");
            routeNameMethod = route.getMethod("getName");
            routeColorMethod = route.getMethod("getColor");
            // Optional, like the mode and hidden flag: a moved name reads the line two-way instead of losing
            // it; the real-jar handshake notices the move.
            routeCircularMethod = optionalMethod(route, "getCircularState");
            routeModeMethod = optionalMethod(route, "getTransportMode");
            routeHiddenMethod = optionalMethod(route, "getHidden");
            routePlatformsMethod = route.getMethod("getRoutePlatforms");
            routePlatformField = routePlatform.getField("platform");
            routeDestinationMethod = optionalMethod(routePlatform, "getDestination");
            routeDestinationByIndexMethod = routeDestinationMethod == null
                    ? optionalMethod(route, "getDestination", int.class) : null;

            railModeMethod = rail.getMethod("getTransportMode");
            railHexIdMethod = optionalMethod(rail, "getHexId");
            railMathField = rail.getField("railMath");
            railMathLengthMethod = railMath.getMethod("getLength");
            railMathPositionMethod = railMath.getMethod("getPosition", double.class, boolean.class);
            vectorXMethod = vector.getMethod("x");
            vectorYMethod = vector.getMethod("y");
            vectorZMethod = vector.getMethod("z");

            available = true;
            LOGGER.info("[HowToGo] MTR's server-side network is reachable through {}", entry.getName());
            return true;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            available = false;
            warnUnavailable(e);
            return false;
        }
    }

    private static Class<?> load(String name) throws ClassNotFoundException {
        return Class.forName(name, false, MtrServerData.class.getClassLoader());
    }

    private static Method optionalMethod(Class<?> owner, String name, Class<?>... parameters) {
        try {
            return owner.getMethod(name, parameters);
        } catch (NoSuchMethodException | RuntimeException absent) {
            return null;
        }
    }

    private static Field optionalField(Class<?> owner, String name) {
        try {
            return owner.getField(name);
        } catch (NoSuchFieldException | RuntimeException absent) {
            return null;
        }
    }

    private static void warnOnce(String format, Object argument) {
        if (warned) {
            return;
        }
        warned = true;
        LOGGER.warn(format, argument);
    }

    private static void warnUnavailable(Throwable cause) {
        if (warned) {
            return;
        }
        warned = true;
        LOGGER.warn("[HowToGo] MTR's server-side network is not readable here ({}); clients keep the "
                + "windowed reading and the overlay, as before", cause.toString());
    }
}
