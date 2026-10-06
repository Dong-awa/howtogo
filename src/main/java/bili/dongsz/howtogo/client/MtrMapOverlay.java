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
 * <h2>Why this reading exists when the client's own does</h2>
 * MTR sends a client the stations and lines within a couple of hundred blocks of it. That window is
 * what {@link MtrClientData} reads and {@link MtrKnown} keeps, and on a server somebody else is
 * running there is nothing else to read: {@link MtrWholeMap} reaches the copy of the network this
 * process simulates, and on a remote server this process simulates none of it. So a railway the
 * player has not walked is a railway this mod does not know, and a journey to a station they have
 * never been near cannot be planned.
 *
 * <p>MTR Map Overlay answers exactly that. It asks the server -- which must have it installed too --
 * for a snapshot of every line, station and rail of MTR's network, and keeps it on the client. That
 * snapshot is the whole railway, on any server, and this class reads it.
 *
 * <h2>Where it is, and what is in it</h2>
 * <pre>
 * com.lx862.mtrmap.mapdata.MapDataCache
 *   .hasServerData(String dimension)     // static: is there a fetched snapshot for this dimension?
 *   .get(String dimension)               // static: the fetched snapshot, or the radius-limited one
 *     .dimensionId                       // "minecraft/overworld" -- MTR's own spelling
 *     .version                           // the snapshot's own content hash
 *     .routes                            // List&lt;MapRoute&gt;  -- every line
 *     .tracks                            // List&lt;MapTrack&gt;  -- every rail
 *     .landmarks                         // List&lt;MapLandmark&gt; -- every station, platform, depot
 * MapRoute    .id .name .color .stops    ; MapRoute.Stop  .x .z .stationName .destination
 * MapTrack    .id .points (List&lt;double[]{x, z}&gt;)
 * MapLandmark .id() .type() .x() .y() .z() .name() .symbol()      // a record
 * </pre>
 *
 * <h2>Only the fetched snapshot, never the fallback</h2>
 * With the mod installed on the client alone, or on a server that does not have it, that cache holds
 * MTR's own radius-limited data -- the very window {@link MtrClientData} already reads. Reading it
 * here as well would offer the same stations twice, through two readers that could disagree. So the
 * snapshot is only read when {@code hasServerData} says the server answered, and nothing at all is
 * read otherwise.
 *
 * <h2>Why the reading is cached</h2>
 * A whole railway is a lot of geometry: every rail of it arrives as a list of points that has to be
 * copied into this mod's own arrays. So the conversion is done once per snapshot -- keyed by the
 * snapshot's own content hash, which changes only when the server's railway does -- and every tick
 * between those changes reads the same reading.
 *
 * <h2>Why the rails are taken whole, and why that is not a cost</h2>
 * An earlier version of this class took only the rails within a radius of the player, because the
 * rails are what a line's marks are cut from and cutting them meant planning a ride between every
 * pair of neighbouring stops over the whole network. That is no longer what happens, and the radius
 * was actively harmful: it left most of the railway without geometry, so a line whose rails it had
 * taken away was drawn as straight hops between its stations -- which is the one thing the fetched
 * snapshot exists to fix. See {@link MtrMapOverlay} on {@code MapRoute.trackIds} and
 * {@code MtrLineTracks.stated}: the snapshot says which rails each route runs along, so a line's
 * track is a lookup rather than a plan, and there is nothing left for a radius to bound.
 *
 * <h2>What the snapshot does not say</h2>
 * Two things, and both are answered from what it does say rather than guessed at:
 * <ul>
 *   <li><b>the lines' kind</b> -- there is no transport mode anywhere in it. It does not need one:
 *       the overlay samples a rail only when its transport mode is {@code TRAIN}, so a snapshot's
 *       track is train track, and a route whose track could not be sampled is left out of it
 *       entirely. What arrives is therefore the train network, and that is what it is read as. A boat
 *       line is reached the way it always was: through MTR's own client data, whose stations carry a
 *       mode.</li>
 *   <li><b>the rails' height</b> -- the geometry is sampled into X and Z alone, so a rail stands at
 *       the height of the nearest station or platform of the snapshot, and at
 *       {@link #FALLBACK_TRACK_Y} where there is none near. It is a hint and is documented as one:
 *       {@link MtrClientData#merge} lets MTR's own copy of a rail -- which carries its real height --
 *       win over this one wherever MTR has sent it, so only a rail MTR never sent stands on the
 *       hint.</li>
 * </ul>
 *
 * <h2>Why nothing here is a dependency</h2>
 * The same arrangement {@link MtrClientData} and {@link MtrWholeMap} use, for the same reasons: the
 * mod is not installed on every copy of this one, it documents no API of its own for this, and its
 * shape is free to move. So no type of it appears in a signature, every handle is looked up by name
 * once and guarded, and a version whose shapes have moved reports itself unavailable -- at which
 * point the windowed reading above is what the mod offers, exactly as before.
 */
final class MtrMapOverlay {

    private static final String MOD_ID = "mtrmap";

    private static final String CACHE = "com.lx862.mtrmap.mapdata.MapDataCache";
    private static final String DIMENSION_DATA = CACHE + "$DimensionData";
    private static final String ROUTE = "com.lx862.mtrmap.mapdata.MapRoute";
    private static final String ROUTE_STOP = ROUTE + "$Stop";
    private static final String TRACK = "com.lx862.mtrmap.mapdata.MapTrack";
    private static final String LANDMARK = "com.lx862.mtrmap.mapdata.MapLandmark";

    /**
     * The kind of a line or a rail read out of the snapshot.
     *
     * <p>The snapshot only ever carries train data -- see the class comment -- so this is the one
     * answer it can give. Giving it is what lets the reading go through the same conversion as MTR's
     * own without a second rule for where a line's kind comes from.
     */
    private static final String TRAIN = "TRAIN";

    /**
     * The height a rail of the snapshot stands at when no station or platform of it is near.
     *
     * <p>The snapshot's geometry is flat and a rail's own height is not in it, so a rail is placed at
     * the height of the nearest landmark it has, and here when it has none near. Sea level is what the
     * game itself calls the middle of the world, and it is a better answer than nothing for the rails
     * of a line laid across open country.
     */
    private static final int FALLBACK_TRACK_Y = 64;

    /** How far a rail looks for a landmark to take its height from, in blocks. */
    private static final int HEIGHT_HINT_RADIUS = 48;

    /** The grid cell a height hint is filed under, in blocks. */
    private static final int HINT_CELL = 32;

    private static boolean resolved;
    private static boolean available;
    private static boolean warned;

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

    /** The snapshot the conversion below was last run for, and the reading it produced. */
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
     * The whole railway the overlay fetched for the dimension the player is in, or an empty reading.
     *
     * <p>An empty reading covers every case in which this has nothing to say and the other two
     * readings stand alone: the mod absent or moved, the reading switched off, no world, no player, no
     * server that answered, a snapshot for another dimension, and a snapshot whose shapes are not the
     * ones read here.
     *
     * <p>Never throws: this runs on a client tick beside {@link MtrClientData#read()}, so anything
     * unexpected has to come back as "nothing to read" rather than as a crash.
     */
    static MtrClientData.Snapshot read() {
        resolve();
        if (!available) {
            return MtrClientData.Snapshot.EMPTY;
        }
        if (!enabled()) {
            // Switched off means forgotten rather than merely not read again: a whole railway left
            // lying about would go on being offered after the player asked for it not to.
            reset();
            return MtrClientData.Snapshot.EMPTY;
        }
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return MtrClientData.Snapshot.EMPTY;
        }
        // MTR names its world "minecraft/overworld" and the snapshot is keyed the same way, which is
        // the spelling MtrWholeMap matches its own simulators by.
        String dimension = player.level().dimension().location().toString().replace(':', '/');
        try {
            if (!Boolean.TRUE.equals(hasServerDataMethod.invoke(null, dimension))) {
                // Either the server does not run the overlay, or it has not answered yet. What the
                // client holds in that case is MTR's own window, which MtrClientData already reads.
                return MtrClientData.Snapshot.EMPTY;
            }
            Object data = getMethod.invoke(null, dimension);
            if (data == null || !dimension.equals(dimensionIdField.get(data))) {
                // The snapshot was dropped between the two calls, and what came back is the
                // radius-limited fallback -- which is not what this class is for.
                return MtrClientData.Snapshot.EMPTY;
            }
            String key = dimension + '@' + (versionField == null
                    ? System.identityHashCode(data) : versionField.getLong(data));
            if (!key.equals(convertedKey)) {
                MtrClientData.Snapshot reading = convert(data);
                converted = reading;
                convertedKey = key;
                HowToGo.LOGGER.info("[HowToGo] MTR Map Overlay | {} | {} station(s), {} platform(s), "
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

    // ---------------------------------------------------------------- the reading

    /**
     * One snapshot as this mod's own reading.
     *
     * <p>Everything is placed from the landmarks rather than from the routes' own stop coordinates,
     * because that is what MTR's own reading does: a stop stands at the middle of its station's
     * platforms, and a station built a platform at a time reaches either reader as several stations
     * that {@link MtrTransit} then groups into one place. Offering the overlay's stops at the
     * coordinates the overlay computed for them instead would place the same journey at two points
     * depending on which reader answered.
     *
     * <p>The links the snapshot does not carry are rebuilt by name and distance, which is the only
     * join it supports: a landmark's id says it is a station or a platform but never which station a
     * platform is in, and a route's stop names its station but does not identify it. A name found
     * twice is settled by which of the two the stop or platform stands nearest, which is the same
     * rule the grouping uses and the right answer for the case the grouping exists for -- one station
     * of MTR's drawn as several areas a few blocks apart.
     */
    private static MtrClientData.Snapshot convert(Object data) throws ReflectiveOperationException {
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

        // Platforms, each hung on the nearest station of its own name. A platform whose station the
        // snapshot does not hold at all is left out, which is the same answer MTR's own reading gives
        // for a platform whose station is out of its window: nothing is a better answer than a
        // boarding point on a station nobody built.
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
     * One station of the snapshot.
     *
     * <p>The extent MTR gives a station -- the box the player dragged -- is not in the snapshot, so a
     * station stands at a point and its extent is that point. Nothing downstream reads the extent of
     * an imported station: what a stop is placed on is the middle of the station's platforms, which
     * the platforms supply.
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
     * One platform of the snapshot, on the nearest station of its own name.
     *
     * <p>The station is looked up by name because that is the only link the snapshot has between the
     * two, and by distance within that name because a name is not unique -- a station built as
     * several areas arrives as several stations that share one, which is exactly the case the
     * distance settles.
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
     * One route of the snapshot, with the stops whose station the snapshot holds.
     *
     * <p>A stop's station is found the same way a platform's is: by name, then by whichever station
     * of that name the stop stands nearest. A stop whose station is not in the snapshot is left out,
     * which is what {@link MtrClientData.Snapshot#stopPosition} answers for MTR's own reading when a
     * station is out of the client's window -- a stop that cannot be placed is not placed.
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
                // The platform id is left at zero: a route's stop does not name the platform it calls
                // at, and nothing downstream asks for one -- what a stop is placed on is its station.
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
                    railsOf(route));
        } catch (ReflectiveOperationException | RuntimeException e) {
            noteUnreadable("line", e);
            return null;
        }
    }

    /**
     * The rails one route of the snapshot runs along, in the order it runs along them.
     *
     * <p>The overlay's own answer to the question MTR never answers -- MTR's client data joins a route
     * to its platforms and to nothing else -- and it is an exact one: the ids are the rails its own
     * collector walked, in path order, so a line drawn from them follows the railway instead of
     * joining its stations with straight hops. Handed to {@code MtrLineTracks}, which turns them into
     * the line's track without working anything out.
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
                // An id is a rail's hex id, which is what the snapshot's tracks are keyed by. An
                // element that is not one cannot be looked up and is left out rather than hashed into
                // something that would never match a rail and would silently shorten the track.
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
     * The rails of the snapshot, as this mod's own track.
     *
     * <p>The height is the one thing the snapshot does not carry, so it is taken from the nearest
     * station or platform of the snapshot within {@link #HEIGHT_HINT_RADIUS}, and left at
     * {@link #FALLBACK_TRACK_Y} when there is none.
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
                boolean usable = true;
                for (int i = 0; i < list.size(); i++) {
                    if (!(list.get(i) instanceof double[] point) || point.length < 2
                            || !Double.isFinite(point[0]) || !Double.isFinite(point[1])) {
                        usable = false;
                        break;
                    }
                    xs[i] = point[0];
                    zs[i] = point[1];
                }
                if (!usable) {
                    // The overlay's own codec refuses a non-finite point on the way in, so this is a
                    // shape this mod has not seen rather than data it has: the rail is left out and
                    // counted, like every other unreadable element.
                    noteUnreadable("rail", null);
                    continue;
                }
                int middle = list.size() / 2;
                tracks.add(new MtrClientData.Track(hexId, TRAIN,
                        heightNear(hints, xs[middle], zs[middle]), xs, zs));
            } catch (RuntimeException e) {
                noteUnreadable("rail", e);
            }
        }
        return List.copyOf(tracks);
    }

    // ------------------------------------------------------------------ the joins

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
     * <p>Searched cell by cell rather than landmark by landmark, because the landmarks of a whole
     * railway outnumber nothing here and the rails outnumber them: a rail asks this once, and asking
     * every landmark each time would be the square of the network for a number that is a hint.
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

    // ------------------------------------------------------------------- reading

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
     *
     * <p>The overlay writes every id it carries as {@code Long.toHexString} of MTR's own, behind a
     * kind -- {@code "station:1a2b"}, {@code "platform:..."}, a route's bare hex, and
     * {@code "depot:..."} for a stretch of depot path no route could be resolved for. Parsing the hex
     * back is what lets a station or a line read here be the same station or line MTR's own reading
     * calls it, which is what makes {@link MtrClientData#merge} able to tell that it has heard about
     * one thing twice rather than about two things.
     *
     * <p>A string that is not hex is a string this mod has not seen. Rather than throw the element
     * away it is keyed by a hash of itself, which is stable from one reading to the next and so
     * behaves exactly as an id should -- it simply cannot be matched against MTR's own, which is the
     * honest answer for an id that is not MTR's.
     *
     * <p>Package-private because the harness checks it: this is the join every merge across the three
     * readings rests on, and it is the one part of the conversion that nothing in the game can show
     * going wrong -- a station keyed wrongly is simply two stations, and looks like a station.
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
     * <p>One unreadable element must not cost the reading every other one, so each is read in its own
     * guard and a failure returns nothing for that element. Returning nothing <em>quietly</em> is what
     * once made this mod's MTR reader look like it was working while it threw away every line MTR
     * offered -- so the first few failures are named and the rest counted.
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

    // ---------------------------------------------------------------- resolving

    private static synchronized void resolve() {
        if (resolved) {
            return;
        }
        resolved = true;
        // Asked only as a shortcut, and without touching another mod's classes when it says no: this
        // mod is not installed on every copy of this one, and a session without it should not load
        // its classes at all. Whether the names below are still the overlay's is a separate question,
        // which bind() answers on its own -- so the harness can check the whole handshake against a
        // real jar with no game running.
        if (!overlayLoaded()) {
            return;
        }
        bind();
    }

    /**
     * Looks up every class, field and method this reads, and remembers whether it worked.
     *
     * <p>Every one of them is public API of the overlay, so no privileged access is asked for. A
     * version that has moved any of them simply reports itself unavailable, which is the honest
     * answer and leaves the rest of the mod untouched.
     *
     * <p>The classes are loaded without being initialised: this needs their shapes, not their state.
     *
     * @return whether the overlay's shapes are the ones this class reads
     */
    static boolean bind() {
        try {
            Class<?> cache = load(CACHE);
            Class<?> dimensionData = load(DIMENSION_DATA);
            Class<?> route = load(ROUTE);
            Class<?> routeStop = load(ROUTE_STOP);
            Class<?> track = load(TRACK);
            Class<?> landmark = load(LANDMARK);

            hasServerDataMethod = cache.getMethod("hasServerData", String.class);
            getMethod = cache.getMethod("get", String.class);
            routesField = dimensionData.getField("routes");
            tracksField = dimensionData.getField("tracks");
            landmarksField = dimensionData.getField("landmarks");
            dimensionIdField = dimensionData.getField("dimensionId");
            // Not required: it is what says whether the snapshot has changed at all, and without it
            // the reading is converted again on every tick, which is slower but not wrong.
            versionField = optionalField(dimensionData, "version");

            routeIdField = route.getField("id");
            routeNameField = route.getField("name");
            routeColorField = route.getField("color");
            routeStopsField = route.getField("stops");
            // The rails the route runs along, in the order it runs along them. The one thing MTR's
            // own data never says about a line, and the reason a line read out of the overlay is drawn
            // along its railway rather than between its stations. Not required: a version without it
            // falls back to working the track out, which is what this mod did before.
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

            available = true;
            HowToGo.LOGGER.info("[HowToGo] MTR Map Overlay's fetched railway is readable ({})", CACHE);
            return true;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            available = false;
            warnUnavailable(e);
            return false;
        }
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
     * Whether the overlay's classes are on the classpath at all.
     *
     * <p>Asked by the harness before it checks the handshake, so that a machine which has never seen
     * the mod skips the check rather than failing it -- the same arrangement {@link MtrClientData}
     * uses for MTR. Answered by loading the one class, and never by {@link #overlayLoaded()}, which
     * needs a loader that has been started and a harness has not.
     */
    static boolean classesPresent() {
        try {
            load(CACHE);
            return true;
        } catch (ClassNotFoundException | LinkageError absent) {
            return false;
        }
    }

    /**
     * Whether MTR Map Overlay is installed.
     *
     * <p>Answered without assuming the loader has been asked anything yet: a class of this mod can
     * reach here from the harness or a tool that never started the game, where the loader's own state
     * is absent. "Not installed" and "no loader to ask" are the same answer to everything that
     * follows, so they are made the same answer here rather than each caller guarding separately.
     */
    private static boolean overlayLoaded() {
        try {
            return net.neoforged.fml.ModList.get() != null
                    && net.neoforged.fml.ModList.get().isLoaded(MOD_ID);
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
        HowToGo.LOGGER.warn("[HowToGo] MTR Map Overlay's fetched railway is unavailable ({}); its "
                        + "stations and lines will not be offered, and nothing else changes",
                cause.toString());
    }
}
