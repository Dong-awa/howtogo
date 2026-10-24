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
 * MTR's own network, read on the server that is simulating it.
 *
 * <h2>Why the server reads MTR at all</h2>
 * MTR keeps its railway on its server and sends a client only the part of it within a couple of hundred
 * blocks -- see {@code MtrClientData}. Everything this mod offers on a client connected to somebody
 * else's server is therefore a window, unless one of two other readings exists: the simulation running
 * in the same process (single player), or MTR Map Overlay's snapshot. This class is the server's side
 * of a third answer, the one that needs nothing but this mod on the server: the server holds the whole
 * railway in memory, reads it here and hands it to {@link MtrServerSync}, which sends it to clients.
 *
 * <h2>The chain</h2>
 * The same objects MTR's own server simulates, reached the same way the client reaches them when the
 * player hosts the world ({@code MtrWholeMap}):
 * <pre>
 * org.mtr.MTR / org.mtr.Init / org.mtr.mod.Init    (whichever spelling this MTR uses)
 *   .main                        static, org.mtr.core.Main, set once MTR's server has started
 *     .simulators                one per dimension
 *       .dimension               "minecraft/overworld"
 *       .stations / .platforms / .routes / .rails / .railIdMap
 * </pre>
 *
 * <h2>Why every name is looked up and never assumed</h2>
 * MTR documents this as internal working with no stable API and has already moved its own classes once
 * (4.1 renamed {@code org.mtr.mod.*} to {@code org.mtr.*}). No MTR type appears in a signature here,
 * every handle is looked up by name once and guarded, and a version whose shapes have moved reports
 * itself unavailable -- at which point the client keeps its window and nothing else changes.
 *
 * <h2>Threading</h2>
 * A simulator mutates its sets on its own thread. {@link #read} is therefore only ever called from a
 * task queued with {@link #runOn}, which is MTR's own {@code Simulator.run}; the result is an immutable
 * {@link MtrSnapshotData.Dimension}, and nothing of MTR's is held on to afterwards.
 */
public final class MtrServerData {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** The class MTR keeps its running server in, under each name it has given it. */
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

    /** How far apart a rail's geometry is sampled, in metres, when the rail is short. */
    private static final double TRACK_SAMPLE_METRES = 4.0;
    /** The most points one rail may carry; longer rails sample coarser rather than at 4 metres. */
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
     *
     * <p>Resolved once and remembered: a missing class does not appear later, and this is asked from
     * every server tick while a reading exists.
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

    /** Whether MTR's classes are on the classpath at all, without looking up any of the shapes. */
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
                // Not that name; the next one is the other spelling of the same class.
            }
        }
        return false;
    }

    /**
     * The running MTR server, or null before it has started (or on a server without MTR).
     *
     * <p>This is the same object graph {@code MtrWholeMap} reads from on a client that hosts the world;
     * on a dedicated server it is the only copy there is.
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
     *
     * <p>Found by what a field holds rather than by its name: MTR calls it {@code simulators} today and
     * is free to rename it, while one simulator per dimension is the part that is not free to change.
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
     * Queues a task on one simulator's own thread.
     *
     * <p>The only safe way to touch the sets: they are mutated by that thread, and reading them from
     * the server thread is how a mod that merely meant to look at MTR gets a torn reading.
     *
     * @return false when there is no simulator to queue on, or MTR refused the task
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

    /** The dimension a simulator simulates, in MTR's own spelling ("minecraft/overworld"). */
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
     * A cheap fingerprint of a simulator's network, for deciding whether a full read is worth it.
     *
     * <p>Counts and ids only: no geometry and no names. A reading is expensive -- every rail's curve is
     * sampled -- and a server whose railway has not changed should not pay for one every ten seconds.
     * A rename costs one rebuild that the ids alone would miss, which is the same bargain
     * {@code MtrClientData.signatureOf} makes on the client.
     *
     * <p>Runs on the simulator's own thread, like {@link #read}.
     */
    public static long signatureOf(Object simulator) {
        if (simulator == null || !available()) {
            return 0L;
        }
        long hash = 17;
        hash = hash * 31 + countOf(stationsField, simulator);
        hash = hash * 31 + countOf(platformsField, simulator);
        hash = hash * 31 + countOf(routesField, simulator);
        hash = hash * 31 + countOf(railsField, simulator);
        return hash;
    }

    /**
     * Every station, platform, line and rail a simulator holds, as a value that can leave MTR's thread.
     *
     * <p>Runs on the simulator's thread -- see {@link #runOn} -- and keeps nothing of MTR's. One
     * unreadable element costs that element and nothing else; the reader is a mirror of
     * {@code MtrClientData}'s, for the same reason: MTR's shapes are not a contract.
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

    // ------------------------------------------------------------------ elements

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
            // A platform's station comes from the {@code area} field every saved rail carries; it is
            // the station object itself rather than an id, so the id is read back off it.
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
                // A route MTR itself does not show is not a line this mod offers either.
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
                    // The same fallback the client reader uses for a route whose own kind cannot be
                    // read: the stations it calls at are the only other thing that knows.
                    mode = modeByStation.get(stationId);
                }
                index++;
            }
            return new MtrSnapshotData.Line(number(raw, routeIdMethod),
                    text(raw, routeNameMethod), mode,
                    (int) number(raw, routeColorMethod) & 0xFFFFFF, stops, List.of());
        } catch (ReflectiveOperationException | RuntimeException e) {
            // One unreadable route must not cost the reading every other one.
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

    private static List<MtrSnapshotData.Track> tracks(Object simulator) {
        List<MtrSnapshotData.Track> tracks = new ArrayList<>();
        try {
            Object rails = field(railsField, simulator);
            Object idMap = field(railIdMapField, simulator);
            // MTR keys its rails by their hex id in a map of String -> Rail. A rail also answers its
            // own id through the schema's getter, which is preferred; the map is the fallback for a
            // version that keeps the id only as the map key.
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
            // No rails is a worse reading, not a broken one: the stations and lines still stand.
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

    // ------------------------------------------------------------------ helpers

    private static Object field(Field field, Object target) throws ReflectiveOperationException {
        return field == null ? null : field.get(target);
    }

    private static int countOf(Field field, Object target) {
        Object value;
        try {
            value = field(field, target);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return 0;
        }
        return value instanceof Collection<?> items ? items.size() : 0;
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

    // ---------------------------------------------------------------- resolving

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
                    // Not this name; the next one is the other spelling of the same class.
                }
            }
            if (entry == null) {
                throw new ClassNotFoundException(String.join(" or ", ENTRY_NAMES));
            }
            // The field holding the running server, recognised by the type it holds rather than by
            // the name MTR gave it: the name is the part an update is free to change, the type is not.
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
            // Not required: it is what names a dimension in the reading, and a reading without one is
            // still usable when the client has a single dimension to match it against.
            dimensionField = optionalField(simulator, "dimension");

            // The four collections live on the simulation core's Data, which Simulator extends; getField
            // walks the chain.
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

            // Looked up on the class each is invoked on, never on a base class: getMethod finds
            // inherited methods, so a lookup from the concrete class either works or fails loudly,
            // while a lookup from a base class can succeed and then fail at the call site.
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
