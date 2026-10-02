package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.HowToGo;
import bili.dongsz.howtogo.RoadConfig;
import bili.dongsz.howtogo.road.RoadClass;
import net.neoforged.fml.ModList;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * MTR's client-side data, read reflectively.
 *
 * <h2>What MTR puts on the client</h2>
 * MTR keeps its world on its own server and sends a client what is near it: its data request carries
 * a {@code requestRadius} of 192 blocks by default and a list of the ids the client already holds, so
 * the answer is "the stations, platforms, routes and rails around you", not the whole network. That is
 * the shape of this reading: whatever is in range now, re-read as the player moves, never a complete
 * picture of the railway. The mod's own documentation says so in as many words, and it is why
 * {@link #tick()} re-reads rather than reading once.
 *
 * <h2>Why nothing here is a dependency</h2>
 * MTR documents this class as internal working with no stable API, changing between versions, and the
 * mod is not installed on every install of this one. So no MTR type appears in a signature, every
 * handle is looked up by name once and guarded, and with MTR absent -- or with a version whose shapes
 * have moved -- this reports itself unavailable and the rest of the mod behaves exactly as before.
 * This is the same arrangement {@link CreateTrackGraph} uses, for the same reasons.
 *
 * <h2>The chain, read out of MTR 4.0.5's own bytecode</h2>
 * <pre>
 * MinecraftClientData.getInstance()            // static, client only; null until it has synced
 *   .stations            (from Data)           // Set&lt;Station&gt;
 *   .simplifiedRoutes    (from ClientData)     // Set&lt;SimplifiedRoute&gt; -- the client's lines
 *   .railWrapperList                           // Map&lt;String, RailWrapper&gt; -- rails by hex id
 * NameColorDataBase.getId() / getName() / getColor() / getTransportMode()
 * AreaBase.getCenter() / getMinX() .. getMaxZ()          // a station's area
 * SimplifiedRoute.getPlatforms()                          // the stops, in order
 * SimplifiedRoutePlatform.getPlatformId() / getStationId() / getStationName()
 * RailWrapper.getRail() / .hexId ; Rail.railMath ; RailMath.getLength() / getPosition(double, boolean)
 * </pre>
 *
 * <h2>Where a line's kind comes from</h2>
 * Not from the line. {@code SimplifiedRoute} carries an id, a name, a colour, a circular state and its
 * platforms, and <b>no transport mode at all</b> -- so the client's own line data cannot say whether a
 * line is a train or a boat. Its stations can: every {@code NameColorDataBase} has a transport mode,
 * stations included. A line's kind is therefore taken from the first of its stops whose station is in
 * range, which is the only answer available on the client and the right one whenever it is available.
 *
 * <h2>What the client is not sent, and why nothing here reads it</h2>
 * MTR's own data request carries a radius and a list of the ids the client already holds, and the
 * answer to it is built from {@code DataResponseSchema}: stations, platforms, sidings,
 * <b>simplified routes</b>, depots and rails. A full {@code Route} is not among them, so
 * {@code Data.routes} and {@code Data.routeIdMap} stay empty on a client and the route type
 * ({@code NORMAL} / {@code LIGHT_RAIL} / {@code HIGH_SPEED}) is <b>not obtainable here at all</b>.
 * That is worth stating in the code because it is the obvious next idea -- a per-line weight taken
 * from the route's own type -- and it cannot work: a reader for it would return nothing for ever, and
 * a weight table built on it would silently weigh every line the same. The vehicle type is the one
 * type signal the client is given, and it is the one read here.
 */
public final class MtrClientData {

    private static final String MOD_ID = "mtr";

    /**
     * The client data class, under each name MTR has given it.
     *
     * <p>4.1 moved the mod's own classes from {@code org.mtr.mod.*} to {@code org.mtr.*}, so the one
     * class this reads by name is not in the package it was. Both names are tried, newest first: a
     * version that has moved it is not a version to refuse, since everything that matters about the
     * class -- the fields it holds, the simulation core it extends -- is unchanged, and it is the
     * handshake check against a real jar that confirms that rather than a guess from a changelog.
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
    /** How many unreadable elements are named before the rest are only counted. */
    private static final int MAX_REPORTED_FAILURES = 4;

    private static boolean resolved;
    private static boolean available;
    private static boolean warned;

    private static Method getInstanceMethod;
    private static Field stationsField;
    private static Field platformsField;
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
    /** How many elements this session could not read, so that a wrong shape cannot stay quiet. */
    private static int unreadable;

    private MtrClientData() {
    }

    /** A station area, with the extent MTR gave it. */
    public record Station(long id, String name, String mode, int color, int centerX, int centerY,
                          int centerZ, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
    }

    /**
     * One platform: where a vehicle actually stops, and which station area it belongs to.
     *
     * <p>Kept because a station's centre is the centre of its whole area, which for a large station is
     * nowhere near the track. The platforms are the boarding points, so a stop placed from them is a
     * stop a route can reach.
     */
    public record Platform(long id, long stationId, String name, String mode, int x, int y, int z) {
    }

    /** One stop of a line: which platform, which station, and what it is called. */
    public record Stop(long platformId, long stationId, String stationName, String destination) {
    }

    /** A line as the client has it, with its kind deduced from the stations it calls at. */
    public record Line(long id, String name, String mode, int color, List<Stop> stops) {

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
         * Where a station's vehicles stop: the middle of its platforms when it has any, and the middle
         * of its area when it has none.
         *
         * <p>The average of the platforms rather than any one of them, because a station with platforms
         * either side of its tracks has its boarding point between them -- and a point between two
         * platforms is on the railway, which is where a stop has to be for a route to reach it. With no
         * platform in range there is nothing better than the area's own centre, which is what MTR
         * itself draws the station's label at.
         */
        public int[] stopPosition(long stationId) {
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
     * The kind of this mod's line an MTR transport mode becomes, or null when it becomes none.
     *
     * <p>Train and cable car are both on rails -- a cable car runs on a track with a cable rather than
     * a locomotive, and this mod has no separate class for one -- and a boat is on water. An aeroplane
     * becomes nothing: there is no class for a flight path, and a line nothing can be routed along
     * would be a line that only ever answers "no journey". Anything a later MTR adds that is not named
     * here is left alone rather than guessed at.
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
     * Re-reads the client's data once a second, and reports it when it changes.
     *
     * <p>On the tick rather than in a plan because the answer changes as the player moves: MTR sends
     * what is near them, so a reading is a reading of a place, and there is nothing to be gained by
     * making a plan wait for one. Reported only when it changes, because the one thing worse than no
     * diagnostic is a diagnostic that fills the log -- which is what this mod's own rail report did
     * once a second until it was noticed.
     */
    public static void tick() {
        if (++ticks % REREAD_TICKS != 0) {
            return;
        }
        if (!mtrLoaded() || !RoadConfig.mtrTransit()) {
            return;
        }
        Snapshot reading = read();
        String signature = signatureOf(reading);
        if (signature.equals(reported)) {
            return;
        }
        reported = signature;
        latest = reading;
        HowToGo.LOGGER.info("[HowToGo] MTR | {} | {}", signature, summaryOf(reading));
    }

    /**
     * Reads what MTR is offering this client, or an empty reading when it cannot be read.
     *
     * <p>Never throws: this runs on a client tick and inside a plan, so anything unexpected has to come
     * back as "nothing to read" rather than as a crash.
     */
    public static Snapshot read() {
        resolve();
        if (!available) {
            return Snapshot.EMPTY;
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

    // ------------------------------------------------------------------ elements

    private static Station station(Object raw) {
        try {
            // The centre is a Position, whose coordinates are whole blocks as longs; the rail sampling
            // below reads a Vector, whose coordinates are doubles. Two types, two sets of accessors.
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
     * One platform, with the station it belongs to.
     *
     * <p>A platform reaches its station through the {@code area} field every saved rail carries, which
     * is the station object itself rather than an id -- so the id is read back off it. That is the only
     * link the client data has between the two.
     */
    private static Platform platform(Object raw) {
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
                    (int) number(raw, routeGetColorMethod), List.copyOf(stops));
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

    /** The name of an enum constant, or null when the value is not one. */
    private static String modeName(Object value) {
        return value instanceof Enum<?> constant ? constant.name() : null;
    }

    /**
     * Reports an element that could not be read, and how many have been reported.
     *
     * <p>One unreadable station must not cost the reading every other one, so each element is read in
     * its own guard and a failure returns nothing for that element. Returning nothing <em>quietly</em>
     * is what made this reader look like it was working while it threw away every line MTR offered:
     * the stations came through and the lines did not, and there was nothing anywhere saying why. The
     * first few failures are therefore logged, and the rest counted, so the shape being wrong is
     * visible in the log rather than only in the absence of lines.
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

    // ------------------------------------------------------------------- report

    private static String signatureOf(Snapshot reading) {
        StringBuilder signature = new StringBuilder();
        signature.append(reading.stations().size()).append('/')
                .append(reading.platforms().size()).append('/')
                .append(reading.lines().size()).append('/')
                .append(reading.tracks().size());
        for (Line line : reading.lines()) {
            signature.append('|').append(line.id()).append(':').append(line.stops().size())
                    .append(':').append(line.mode());
        }
        return signature.toString();
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

    // ---------------------------------------------------------------- resolving

    /**
     * Looks every class, field and method up once and keeps the result.
     *
     * <p>Guarded because this is read on a client tick, and a failure is permanent: a missing class
     * does not appear later.
     */
    private static synchronized void resolve() {
        if (resolved) {
            return;
        }
        resolved = true;
        // Asked only as a shortcut: with MTR absent there is nothing to look up, and a session without
        // it should not load MTR's classes at all. Whether the names below are still MTR's is a
        // separate question, and one {@link #bind()} answers on its own -- which is what lets the
        // regression harness check the whole handshake against a real MTR jar with no game running,
        // where otherwise only a player in a world can find out.
        if (!mtrLoaded()) {
            return;
        }
        bind();
    }

    /**
     * Looks up every class, field and method this reads, and remembers whether it worked.
     *
     * <p>Every lookup is public API of MTR or of the simulation core it bundles, so no privileged access
     * is asked for -- which also means this cannot break an MTR that keeps its API but changes its
     * innards. A version that has moved any of these names simply reports itself unavailable, which is
     * the honest answer and leaves the rest of the mod untouched.
     *
     * <p>The classes are loaded without being initialised: this needs their shapes, not their state,
     * and running another mod's static initialisers from here would be asking for trouble in a session
     * where the game has not finished starting.
     *
     * @return whether MTR's shapes are the ones this class reads
     */
    static boolean bind() {
        try {
            Class<?> clientData = null;
            String boundName = null;
            for (String name : CLIENT_DATA_NAMES) {
                try {
                    clientData = load(name);
                    boundName = name;
                    break;
                } catch (ClassNotFoundException next) {
                    // Not this name; the next one is the older or newer spelling of the same class.
                }
            }
            if (clientData == null) {
                throw new ClassNotFoundException(String.join(" or ", CLIENT_DATA_NAMES));
            }
            Class<?> station = load(STATION);
            Class<?> platform = load(PLATFORM);
            Class<?> simplified = load(SIMPLIFIED_ROUTE);
            Class<?> simplifiedPlatform = load(SIMPLIFIED_PLATFORM);
            // The rail wrapper is a nested class of the client data, so it moves with it and needs no
            // name of its own to keep in step.
            Class<?> railWrapper = load(boundName + "$RailWrapper");
            Class<?> rail = load(RAIL);
            Class<?> railMath = load(RAIL_MATH);
            Class<?> vector = load(VECTOR);
            Class<?> position = load(POSITION);

            getInstanceMethod = clientData.getMethod("getInstance");
            // Fields on the class or on whichever of its parents declares them: MTR keeps its stations
            // on the simulation core's Data and its client lines on that class's ClientData parent, and
            // getField walks the chain for us.
            stationsField = clientData.getField("stations");
            platformsField = clientData.getField("platforms");
            simplifiedRoutesField = clientData.getField("simplifiedRoutes");
            railWrapperListField = clientData.getField("railWrapperList");

            // Every handle below is taken from the class it will actually be invoked on, and never from
            // a base class it merely shares. getMethod finds inherited methods, so a lookup from the
            // concrete class either succeeds and is usable, or fails and is reported. Resolving from a
            // base class instead is what broke this reader once: SimplifiedRoute is not a
            // NameColorDataBase, so a getName taken from there invoked on a line threw, the throw was
            // swallowed per element, and every line read out of MTR was silently dropped while the
            // stations -- which are a NameColorDataBase -- came through perfectly.
            //
            // The four name-and-colour accessors are the exception in appearance only: they are declared
            // once, on NameColorDataBase, and both stations and platforms inherit them, so the one
            // lookup serves both. A line does not, which is why it has its own three.
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

            // A platform's station, and where it is. Both are inherited from SavedRailBase, and both
            // are looked up on the platform for the reason above.
            savedRailAreaField = platform.getField("area");
            getMidPositionMethod = platform.getMethod("getMidPosition");

            routeGetIdMethod = simplified.getMethod("getId");
            routeGetNameMethod = simplified.getMethod("getName");
            routeGetColorMethod = simplified.getMethod("getColor");
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
            warnUnavailable(e);
            return false;
        }
    }

    /** A class by name, loaded but not initialised. */
    private static Class<?> load(String name) throws ClassNotFoundException {
        return Class.forName(name, false, MtrClientData.class.getClassLoader());
    }

    /**
     * Whether MTR's client data class is on the classpath at all, under any of its names.
     *
     * <p>Kept here rather than written out again by a caller: the names are the fragile part of this
     * class, and a second copy of them somewhere else is a copy that stops being updated -- which is
     * exactly what happened to the harness check, whose own copy went on looking for the 4.0 spelling
     * and quietly reported that there was nothing to check.
     */
    static boolean classesPresent() {
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
     * Whether MTR is installed.
     *
     * <p>Answered without assuming the loader has been asked anything yet: a class of this mod can
     * reach here from a test harness or a tool that never started the game, where the loader's own
     * state is absent. "Not installed" and "no loader to ask" are the same answer to everything that
     * follows, so they are made the same answer here rather than each caller guarding separately.
     */
    private static boolean mtrLoaded() {
        try {
            return ModList.get() != null && ModList.get().isLoaded(MOD_ID);
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
