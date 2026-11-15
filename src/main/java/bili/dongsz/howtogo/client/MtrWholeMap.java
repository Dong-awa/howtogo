package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.HowToGo;
import bili.dongsz.howtogo.RoadConfig;
import bili.dongsz.howtogo.mtr.Mtr3Data;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The whole railway, read out of the copy of it this process is already simulating.
 * MtrClientData sees only the stations and lines within a couple of hundred blocks of the player.
 * When this process hosts the world, MTR's server is a plain object graph: one Simulator per
 * dimension holding every station, platform and route, with no network involved.
 * MTR documents this as internal with no stable API, so every class, field and method is looked up
 * reflectively and never appears in a signature; anything that moved reports itself unavailable and
 * the windowed reading stands alone.
 * The read runs on MTR's own threads (Simulator.run), so a ticking simulator cannot yield a torn
 * reading. Nothing is ever written to MTR's data.
 */
final class MtrWholeMap {

    /** The class MTR keeps its running server in, under each name it has given it. */
    private static final String[] INIT_NAMES = {
            // 4.1 renamed the entry class to org.mtr.MTR and moved classes from org.mtr.mod.* to org.mtr.*;
            // found by handshake against 4.1.0-beta.2, not from a changelog.
            "org.mtr.MTR",
            "org.mtr.Init",
            "org.mtr.mod.Init",
    };

    private static final String MAIN = "org.mtr.core.Main";
    private static final String SIMULATOR = "org.mtr.core.simulation.Simulator";
    private static final String ROUTE = "org.mtr.core.data.Route";
    private static final String ROUTE_PLATFORM = "org.mtr.core.data.RoutePlatformData";

    /**
     * How often the whole railway is read again, in the seconds poll is called on. Cheap but not free,
     * and nobody needs a station built a second ago to appear immediately.
     */
    private static final int REREAD_SECONDS = 10;

    /**
     * How long a queued read may stay queued before another is allowed. The simulator's thread does not
     * tick while paused, so waiting minutes rather than seconds stops a pause menu queueing a task a
     * second.
     */
    private static final int PENDING_SECONDS = 300;

    private static boolean resolved;
    private static boolean available;
    private static boolean warned;
    private static boolean reported;
    private static boolean threeX;

    private static Field mainField;
    private static Field dimensionField;
    private static Field routePlatformField;
    private static Method runMethod;
    private static Method routePlatformsMethod;
    private static Method routeIdMethod;
    private static Method routeNameMethod;
    private static Method routeColorMethod;
    private static Method routeCircularMethod;
    private static Method routeModeMethod;
    private static Method routeDestinationMethod;
    private static Method routeDestinationByIndexMethod;
    private static Method routeHiddenMethod;

    private static volatile MtrClientData.Snapshot latest = MtrClientData.Snapshot.EMPTY;
    private static volatile boolean pending;
    private static int ticks;
    private static int pendingSince;

    private MtrWholeMap() {
    }

    static MtrClientData.Snapshot snapshot() {
        return latest;
    }

    static void reset() {
        if (!latest.isEmpty()) {
            latest = MtrClientData.Snapshot.EMPTY;
        }
    }

    /**
     * Asks for a reading when the last one has gone stale; called once a second from the client tick.
     * The first call asks immediately.
     */
    static void poll() {
        if (pending && ++pendingSince < PENDING_SECONDS) {
            return;
        }
        pending = false;
        if (ticks++ % REREAD_SECONDS != 0 && !latest.isEmpty()) {
            return;
        }
        request();
    }

    private static void request() {
        if (!resolve()) {
            return;
        }
        if (threeX) {
            requestThreeX();
            return;
        }
        Object simulator = localSimulator();
        if (simulator == null) {
            return;
        }
        pending = true;
        pendingSince = 0;
        try {
            runMethod.invoke(simulator, (Runnable) () -> {
                try {
                    MtrClientData.Snapshot reading = read(simulator);
                    latest = reading;
                    if (!reported && !reading.isEmpty()) {
                        reported = true;
                        HowToGo.diagnostic("[HowToGo] MTR whole map | {} station(s), {} platform(s), "
                                        + "{} line(s) read from the simulated network",
                                reading.stations().size(), reading.platforms().size(),
                                reading.lines().size());
                    }
                } catch (ReflectiveOperationException | RuntimeException e) {
                    warnUnavailable(e);
                } finally {
                    pending = false;
                }
            });
        } catch (ReflectiveOperationException | RuntimeException e) {
            pending = false;
            warnUnavailable(e);
        }
    }

    /**
     * Queues one read of the 3.x world's data on the server's own thread, through
     * MinecraftServer.execute. A player on somebody else's server gets nothing here: there is no
     * server in this process to read.
     */
    private static void requestThreeX() {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        MinecraftServer server = minecraft.getSingleplayerServer();
        if (player == null || server == null || !server.isRunning()) {
            return;
        }
        ServerLevel level = server.getLevel(player.level().dimension());
        if (level == null) {
            return;
        }
        pending = true;
        pendingSince = 0;
        server.execute(() -> {
            try {
                Object data = Mtr3Data.railwayData(level);
                if (data == null) {
                    return;
                }
                MtrClientData.Snapshot reading = MtrClientData.toSnapshot(Mtr3Data.whole(data));
                latest = reading;
                if (!reported && !reading.isEmpty()) {
                    reported = true;
                    // The rail count is the number a player reporting missing tracks can be asked for: tracks mean the
                    // network was read.
                    HowToGo.diagnostic("[HowToGo] MTR whole map | {} station(s), {} platform(s), "
                                    + "{} line(s), {} rail(s) read from the simulated network",
                            reading.stations().size(), reading.platforms().size(),
                            reading.lines().size(), reading.tracks().size());
                }
            } catch (RuntimeException e) {
                warnUnavailable(e);
            } finally {
                pending = false;
            }
        });
    }


    /**
     * The simulator for the player's dimension, or null when there is none to read: MTR absent or
     * moved, no world, a remote server, or no simulator for this dimension.
     */
    private static Object localSimulator() {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        MinecraftServer server = minecraft.getSingleplayerServer();
        if (player == null || server == null || !server.isRunning()) {
            // Somebody else's server: MTR's client data is all there is.
            return null;
        }
        try {
            Object main = mainField.get(null);
            if (main == null) {
                return null;
            }
            String wanted = player.level().dimension().location().toString().replace(':', '/');
            Object only = null;
            int count = 0;
            for (Object simulator : simulators(main)) {
                count++;
                only = simulator;
                String dimension = dimensionOf(simulator);
                if (dimension == null) {
                    continue;
                }
                if (dimension.equals(wanted)) {
                    return simulator;
                }
            }
            return count == 1 ? only : null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            warnUnavailable(e);
            return null;
        }
    }

    private static List<Object> simulators(Object main) throws ReflectiveOperationException {
        List<Object> found = new ArrayList<>();
        Class<?> simulator = load(SIMULATOR);
        for (Class<?> type = main.getClass(); type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
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
        return found;
    }

    /** What MTR calls the dimension a simulator holds, or null when it cannot be read. */
    private static String dimensionOf(Object simulator) {
        if (dimensionField == null) {
            return null;
        }
        try {
            Object value = dimensionField.get(simulator);
            return value instanceof String text ? text : null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }


    private static MtrClientData.Snapshot read(Object simulator) throws ReflectiveOperationException {
        List<MtrClientData.Station> stations = new ArrayList<>();
        Map<Long, String> modeByStation = new HashMap<>();
        for (Object raw : MtrClientData.stationsOf(simulator)) {
            MtrClientData.Station station = MtrClientData.station(raw);
            if (station == null) {
                continue;
            }
            stations.add(station);
            if (station.mode() != null) {
                modeByStation.put(station.id(), station.mode());
            }
        }

        List<MtrClientData.Platform> platforms = new ArrayList<>();
        for (Object raw : MtrClientData.platformsOf(simulator)) {
            MtrClientData.Platform platform = MtrClientData.platform(raw);
            if (platform != null) {
                platforms.add(platform);
            }
        }

        List<MtrClientData.Line> lines = new ArrayList<>();
        for (Object raw : MtrClientData.routesOf(simulator)) {
            MtrClientData.Line line = line(raw, modeByStation);
            if (line != null) {
                lines.add(line);
            }
        }
        return new MtrClientData.Snapshot(List.copyOf(stations), List.copyOf(platforms),
                List.copyOf(lines), List.of());
    }

    /**
     * One route of MTR's data, as this mod's kind of line. The server's Route carries its transport mode
     * outright, unlike the SimplifiedRoute a client is sent, so no mode inference is needed; both read
     * into the same record.
     */
    private static MtrClientData.Line line(Object route, Map<Long, String> modeByStation) {
        try {
            if (routeHiddenMethod != null && Boolean.TRUE.equals(routeHiddenMethod.invoke(route))) {
                // A route MTR itself does not show.
                return null;
            }
            String mode = routeModeMethod == null ? null
                    : MtrClientData.modeName(routeModeMethod.invoke(route));
            List<MtrClientData.Stop> stops = new ArrayList<>();
            int index = 0;
            for (Object routePlatform : MtrClientData.elements(routePlatformsMethod.invoke(route))) {
                Object platform = routePlatformField.get(routePlatform);
                Object station = platform == null ? null : MtrClientData.areaOf(platform);
                long stationId = station == null ? 0 : MtrClientData.idOf(station);
                stops.add(new MtrClientData.Stop(
                        platform == null ? 0 : MtrClientData.idOf(platform),
                        stationId,
                        station == null ? "" : nameOrEmpty(station),
                        destinationOf(routePlatform, route, index)));
                if (mode == null && stationId != 0) {
                    // The fallback the windowed reader uses, for a route whose own kind cannot be read: its stations are
                    // the other thing that knows.
                    mode = modeByStation.get(stationId);
                }
                index++;
            }
            return new MtrClientData.Line(MtrClientData.number(route, routeIdMethod),
                    MtrClientData.text(route, routeNameMethod), mode,
                    (int) MtrClientData.number(route, routeColorMethod) & 0xFFFFFF,
                    List.copyOf(stops), List.of(),
                    MtrClientData.circular(route, routeCircularMethod));
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private static String nameOrEmpty(Object station) throws ReflectiveOperationException {
        String name = MtrClientData.nameOf(station);
        return name == null ? "" : name;
    }

    /** What MTR says a stop's destination is, by whichever of its two accessors this version has. */
    private static String destinationOf(Object routePlatform, Object route, int index)
            throws ReflectiveOperationException {
        if (routeDestinationMethod != null) {
            return MtrClientData.text(routePlatform, routeDestinationMethod);
        }
        if (routeDestinationByIndexMethod != null) {
            Object value = routeDestinationByIndexMethod.invoke(route, index);
            return value instanceof String text ? text : null;
        }
        return null;
    }


    private static synchronized boolean resolve() {
        if (resolved) {
            return available;
        }
        resolved = true;
        if (!MtrClientData.ready()) {
            return false;
        }
        if (MtrClientData.isThreeX()) {
            return bindThreeX();
        }
        return bind();
    }

    /**
     * Binds MTR 3.x's simulated network. None of this class's 4.x chain exists there; what is bound is
     * whether Mtr3Data can answer at all.
     */
    private static boolean bindThreeX() {
        threeX = Mtr3Data.bindServer();
        available = threeX;
        if (!threeX) {
            HowToGo.logInfo("[HowToGo] MTR 3.x's simulated network is not reachable here; the "
                    + "windowed reading is what remains");
        }
        return threeX;
    }

    /**
     * Looks up every class, field and method the whole-map read needs and remembers whether it worked.
     * Split out of resolve() so the harness can check the lookups against a real MTR jar. Classes are
     * loaded without being initialised: this needs their shapes, not their state.
     * @return whether MTR's shapes are the ones this reads
     */
    static boolean bind() {
        try {
            Class<?> main = load(MAIN);
            Class<?> simulator = load(SIMULATOR);
            Class<?> init = null;
            for (String name : INIT_NAMES) {
                try {
                    init = load(name);
                    break;
                } catch (ClassNotFoundException next) {
                    // Not this name; the next one is the other spelling of the same class.
                }
            }
            if (init == null) {
                throw new ClassNotFoundException(String.join(" or ", INIT_NAMES));
            }

            // The field holding the running server, recognised by the type it holds rather than by its name.
            for (Field field : init.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())
                        && field.getType() == main) {
                    field.setAccessible(true);
                    mainField = field;
                    break;
                }
            }
            if (mainField == null) {
                throw new NoSuchFieldException("no " + MAIN + " field on " + init.getName());
            }

            runMethod = simulator.getMethod("run", Runnable.class);
            // Not required: without it the read falls back to the only simulator there is, right for a single
            // dimension and wrong for several.
            dimensionField = optionalField(simulator, "dimension");

            Class<?> route = load(ROUTE);
            Class<?> routePlatform = load(ROUTE_PLATFORM);
            routePlatformsMethod = route.getMethod("getRoutePlatforms");
            routePlatformField = routePlatform.getField("platform");
            routeIdMethod = route.getMethod("getId");
            routeNameMethod = route.getMethod("getName");
            routeColorMethod = route.getMethod("getColor");
            routeCircularMethod = optionalMethod(route, "getCircularState");
            routeModeMethod = optionalMethod(route, "getTransportMode");
            routeHiddenMethod = optionalMethod(route, "getHidden");
            routeDestinationMethod = optionalMethod(routePlatform, "getDestination");
            routeDestinationByIndexMethod = routeDestinationMethod == null
                    ? optionalMethod(route, "getDestination", int.class) : null;

            available = true;
            HowToGo.logInfo("[HowToGo] MTR's simulated network is reachable through {}",
                    init.getName());
            return true;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            available = false;
            warnUnavailable(e);
            return false;
        }
    }

    /**
     * Whether MTR's server-side classes are on the classpath at all, under any of their names; a machine
     * that has never seen MTR should skip the check rather than fail it.
     */
    static boolean classesPresent() {
        if (MtrClientData.isThreeX()) {
            // The 3.x whole map needs the world's data class and nothing of the 4.x chain. Whether the
            // Minecraft types it mentions can load is a question of the running Minecraft version.
            return Mtr3Data.serverPresent();
        }
        try {
            load(MAIN);
            load(SIMULATOR);
        } catch (ClassNotFoundException | LinkageError absent) {
            return false;
        }
        for (String name : INIT_NAMES) {
            try {
                load(name);
                return true;
            } catch (ClassNotFoundException | LinkageError absent) {
                // Not that name; the next one is the other spelling of the same class.
            }
        }
        return false;
    }

    private static Class<?> load(String name) throws ClassNotFoundException {
        return Class.forName(name, false, MtrWholeMap.class.getClassLoader());
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

    /** Reports a failure once, then stays quiet. */
    private static void warnUnavailable(Throwable cause) {
        if (warned) {
            return;
        }
        warned = true;
        HowToGo.logInfo("[HowToGo] MTR's whole network is not readable here ({}); its stations and "
                + "lines are still read from what the server sends, as before", cause.toString());
    }
}
