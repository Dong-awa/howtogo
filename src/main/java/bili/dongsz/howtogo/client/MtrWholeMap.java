package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.HowToGo;
import bili.dongsz.howtogo.RoadConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.server.MinecraftServer;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The whole railway, read out of the copy of it this process is already simulating.
 *
 * <h2>Why a second reading exists at all</h2>
 * MTR sends a client the stations and lines within a couple of hundred blocks of it, and that is the
 * whole of what {@link MtrClientData} can see on its own. The window is kept and added to as the player
 * travels -- see {@link MtrKnown} -- but a railway nobody has walked end to end is a railway this mod
 * only half knows, and the half it knows is decided by where the player happens to have been. Asking
 * MTR for a wider window instead is not an option: the radius is MTR's own constant, the answer carries
 * every rail of the network with it, and a client that asked for a thousand blocks would be sent a
 * payload it could not afford to receive.
 *
 * <h2>What is read, and from where</h2>
 * When the player hosts the world, MTR's server is a thread in this process and its network is a plain
 * object graph: {@code Simulator} per dimension, each holding every station, platform and route of that
 * dimension. That is the whole railway, in memory, with no network involved and nothing to serialize.
 * The chain is:
 * <pre>
 * org.mtr.Init (or org.mtr.mod.Init)   // whichever spelling this MTR uses
 *   .main                    (static)  // org.mtr.core.Main, set when the server started
 *     .simulators            (private) // one Simulator per dimension, matched by name
 *       .stations / .platforms / .routes
 * </pre>
 *
 * <h2>Why every name above is looked up and never assumed</h2>
 * The same arrangement as {@link MtrClientData}, for the same reasons: MTR documents this as internal
 * working with no stable API, it is not installed on every copy of this mod, and 4.1 has already moved
 * its own classes once. So no MTR type appears in a signature, the {@code Main} field is recognised by
 * the type it holds rather than by its name, and anything that has moved simply reports itself
 * unavailable -- at which point the windowed reading above is what the mod offers, exactly as before.
 *
 * <h2>Why the read is taken on MTR's thread</h2>
 * The simulator mutates its sets on its own thread, and reading them from the render thread while it
 * ticks is how a client gets a torn reading or a concurrent-modification failure in a mod it merely
 * meant to look at. {@code Simulator.run} is MTR's own answer to that -- it queues a task for the
 * simulator's thread, which is what its HTTP servlets and its embedding mod both use -- so the whole
 * read happens there and only an immutable result is handed back. Nothing is ever written to MTR's
 * data.
 */
final class MtrWholeMap {

    /** The class MTR keeps its running server in, under each name it has given it. */
    private static final String[] INIT_NAMES = {
            "org.mtr.Init",
            "org.mtr.mod.Init",
    };

    private static final String MAIN = "org.mtr.core.Main";
    private static final String SIMULATOR = "org.mtr.core.simulation.Simulator";
    private static final String ROUTE = "org.mtr.core.data.Route";
    private static final String ROUTE_PLATFORM = "org.mtr.core.data.RoutePlatformData";

    /**
     * How often the whole railway is read again, in the seconds {@link #poll} is called on.
     *
     * <p>Ten seconds. It is an in-memory walk of every station and every route, so it is cheap but not
     * free -- and the thing it is looking for is a station or a line the player has just built, which
     * nobody needs to see within the second.
     */
    private static final int REREAD_SECONDS = 10;

    /**
     * How long a queued read may stay queued before another is allowed, in the same seconds.
     *
     * <p>The task runs on the simulator's thread, which does not tick while the game is paused, so a
     * read asked for with the pause menu open waits -- and asked for again every second, it would queue
     * one task per second for as long as the player stayed in a menu, all of which would then run in one
     * go. Waiting minutes rather than seconds between attempts is what keeps that from being a stall on
     * the way back into the world.
     */
    private static final int PENDING_SECONDS = 300;

    private static boolean resolved;
    private static boolean available;
    private static boolean warned;
    private static boolean reported;

    private static Field mainField;
    private static Field dimensionField;
    private static Field routePlatformField;
    private static Method runMethod;
    private static Method routePlatformsMethod;
    private static Method routeIdMethod;
    private static Method routeNameMethod;
    private static Method routeColorMethod;
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

    /** The last completed reading of the whole railway, or an empty one before the first. */
    static MtrClientData.Snapshot snapshot() {
        return latest;
    }

    /** Forgets the reading, which is what switching the whole-map read off does. */
    static void reset() {
        if (!latest.isEmpty()) {
            latest = MtrClientData.Snapshot.EMPTY;
        }
    }

    /**
     * Asks for a reading when the last one has gone stale, and keeps quiet otherwise.
     *
     * <p>Called once a second from the client tick -- see {@code MtrClientData}, which re-reads MTR at
     * that cadence -- so the numbers here are seconds. The first call asks immediately rather than
     * waiting out the interval, so a world that is already built is known from the first second of the
     * session.
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

    /** Queues one read of the local simulator's network, on the simulator's own thread. */
    private static void request() {
        if (!resolve()) {
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

    // ------------------------------------------------------------------ the chain

    /**
     * The simulator for the dimension the player is standing in, or null when there is none to read.
     *
     * <p>Null covers every case in which this reading has nothing to say and the windowed one stands
     * alone: MTR absent or moved, no world, a server that is not this process (a remote server, where
     * there is no simulator here to read), and a world this process hosts but whose dimension MTR has
     * no simulator for.
     */
    private static Object localSimulator() {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        MinecraftServer server = minecraft.getSingleplayerServer();
        if (player == null || server == null || !server.isRunning()) {
            // Somebody else's server. MTR's own client data is all there is, and it is what is offered.
            return null;
        }
        try {
            Object main = mainField.get(null);
            if (main == null) {
                // MTR's server has not started in this process -- usually the world is still loading.
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
                    // No dimension to compare: with exactly one simulator there is nothing to choose
                    // between, and with several there is a right answer this cannot find.
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

    /**
     * The simulators a {@code Main} holds, found by what the field holds rather than by its name.
     *
     * <p>MTR calls it {@code simulators} today and is free to call it something else tomorrow; the type
     * it holds -- one simulator per dimension -- is the part that is not free to change, so that is
     * what is looked for.
     */
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

    // ---------------------------------------------------------------- the reading

    /**
     * Every station, platform and line the local server holds for this dimension.
     *
     * <p>Runs on the simulator's thread -- see the class comment -- and returns an immutable reading, so
     * nothing of MTR's is held on to afterwards.
     *
     * <p>Rails are deliberately not read. The whole railway's geometry is the one part of this that is
     * genuinely large, the marks are only ever cut for the lines around the player, and the windowed
     * reading already has those.
     */
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
     * One route of MTR's own data, as this mod's kind of line.
     *
     * <p>The same shape the windowed reading builds, from the fuller object behind it: a client is sent
     * a {@code SimplifiedRoute}, which drops the route's own transport mode -- which is why the windowed
     * reader has to infer a line's kind from the stations it calls at -- while the server's own
     * {@code Route} carries it outright. Both are read into the same record, so nothing downstream can
     * tell which of the two a line came from.
     */
    private static MtrClientData.Line line(Object route, Map<Long, String> modeByStation) {
        try {
            if (routeHiddenMethod != null && Boolean.TRUE.equals(routeHiddenMethod.invoke(route))) {
                // A route MTR itself does not show: not a line this mod should offer either.
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
                    // The fallback the windowed reader uses, kept for a route whose own kind cannot be
                    // read: the stations it calls at are the only other thing that knows.
                    mode = modeByStation.get(stationId);
                }
                index++;
            }
            return new MtrClientData.Line(MtrClientData.number(route, routeIdMethod),
                    MtrClientData.text(route, routeNameMethod), mode,
                    (int) MtrClientData.number(route, routeColorMethod) & 0xFFFFFF,
                    List.copyOf(stops));
        } catch (ReflectiveOperationException | RuntimeException e) {
            // One unreadable route must not cost the reading every other one.
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

    // ---------------------------------------------------------------- resolving

    private static synchronized boolean resolve() {
        if (resolved) {
            return available;
        }
        resolved = true;
        // Asked first because it is the cheaper question and the one that covers MTR not being
        // installed at all: with no client data to read there is no server data to read either.
        if (!MtrClientData.ready()) {
            return false;
        }
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

            // The field holding the running server, recognised by the type it holds rather than by the
            // name MTR gave it: the name is the part an update is free to change, and the type is not.
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
            // Not required: it is what picks the right dimension out of MTR's several simulators, and
            // without it the read falls back to "the only one there is", which is the right answer in a
            // world with a single dimension and no answer at all in one with several.
            dimensionField = optionalField(simulator, "dimension");

            Class<?> route = load(ROUTE);
            Class<?> routePlatform = load(ROUTE_PLATFORM);
            routePlatformsMethod = route.getMethod("getRoutePlatforms");
            routePlatformField = routePlatform.getField("platform");
            routeIdMethod = route.getMethod("getId");
            routeNameMethod = route.getMethod("getName");
            routeColorMethod = route.getMethod("getColor");
            routeModeMethod = optionalMethod(route, "getTransportMode");
            routeHiddenMethod = optionalMethod(route, "getHidden");
            routeDestinationMethod = optionalMethod(routePlatform, "getDestination");
            routeDestinationByIndexMethod = routeDestinationMethod == null
                    ? optionalMethod(route, "getDestination", int.class) : null;

            available = true;
            HowToGo.LOGGER.info("[HowToGo] MTR's simulated network is reachable through {}",
                    init.getName());
            return true;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            available = false;
            warnUnavailable(e);
            return false;
        }
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
        HowToGo.LOGGER.info("[HowToGo] MTR's whole network is not readable here ({}); its stations and "
                + "lines are still read from what the server sends, as before", cause.toString());
    }
}
