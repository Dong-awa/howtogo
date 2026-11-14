package bili.dongsz.howtogo.mtr;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Drives the real {@link Mtr3Data} against real MTR 3.x objects, with no game running.
 *
 * <p>The probe beside this one checks that the names the reader looks up exist. This checks the other
 * half -- that the reader, bound against a real jar, turns real MTR 3.x objects into the records the
 * rest of the mod reads. It builds a two-station, one-line, one-rail railway out of MTR's own classes
 * with reflection, feeds it through {@link Mtr3Data#build}, and reads the result back.
 *
 * <p>It is in the reader's own package on purpose: {@code build} is package-private so that the
 * conversion stays a function of a reading rather than of MTR being installed, and this is the closest
 * thing to "MTR being installed" that can be had without starting a game.
 *
 * <p>Compiled together with {@code Mtr3Data.java}, {@code MtrSnapshotData.java} and a stub
 * {@code HowToGo} (the real one is a game entrypoint). Run by {@code bind.ps1} on the intermediary
 * classpath, which is the namespace a running Fabric game has.
 */
public final class BindCheck {

    private static int checks;
    private static int failures;

    private static Class<?> blockPosClass;
    private static Class<?> tupleClass;
    private static Class<?> transportModeClass;
    private static Class<?> stationClass;
    private static Class<?> platformClass;
    private static Class<?> routeClass;
    private static Class<?> routePlatformClass;
    private static Class<?> railClass;
    private static Class<?> railAngleClass;
    private static Class<?> railTypeClass;

    public static void main(String[] args) {
        try {
            System.out.println("== MTR 3.x reader bind ==");
            blockPosClass = load("net.minecraft.class_2338");
            tupleClass = load("net.minecraft.class_3545");
            transportModeClass = load("mtr.data.TransportMode");
            stationClass = load("mtr.data.Station");
            platformClass = load("mtr.data.Platform");
            routeClass = load("mtr.data.Route");
            routePlatformClass = load("mtr.data.Route$RoutePlatform");
            railClass = load("mtr.data.Rail");
            railAngleClass = load("mtr.data.RailAngle");
            railTypeClass = load("mtr.data.RailType");

            check("MTR 3.x client data binds", Mtr3Data.bindClient());
            check("MTR 3.x simulated data binds", Mtr3Data.bindServer());

            checkReading();
            checkGeometricStations();
            checkRailDedupe();
            checkWholeMapRails();
        } catch (Throwable thrown) {
            failures++;
            System.out.println("  FAIL the check itself threw: " + thrown);
            thrown.printStackTrace(System.out);
        }
        System.out.println();
        System.out.println((failures == 0 ? "ALL OK (" : "FAILED: ") + checks + " checks"
                + (failures == 0 ? ")" : ", " + failures + " failed)"));
        if (failures != 0) {
            System.exit(1);
        }
    }

    /** A reading made of real MTR objects: two stations, one platform each, one line, one rail. */
    private static void checkReading() throws Exception {
        Object train = transportMode("TRAIN");
        Object boat = transportMode("BOAT");

        List<Object> stations = new ArrayList<>();
        stations.add(station(1L, "Alpha", 0xFF0000, 0, 0, 10, 10));
        stations.add(station(2L, "Beta", 0x00FF00, 100, 0, 110, 10));

        List<Object> platforms = new ArrayList<>();
        platforms.add(platform(11L, train, 5, 64, 5, 5, 64, 7));
        platforms.add(platform(21L, train, 105, 64, 5, 105, 64, 7));

        Map<Long, Object> platformStations = new LinkedHashMap<>();
        platformStations.put(11L, stations.get(0));
        platformStations.put(21L, stations.get(1));

        List<Object> routes = new ArrayList<>();
        routes.add(route(31L, "Line 1", train, 0x123456, 11L, 21L));

        Map<Object, Map<Object, Object>> rails = new LinkedHashMap<>();
        Object rail = rail(block(0, 64, 5), block(10, 64, 5), train);
        Map<Object, Object> ends = new LinkedHashMap<>();
        ends.put(block(10, 64, 5), rail);
        rails.put(block(0, 64, 5), ends);

        MtrSnapshotData.Dimension reading = Mtr3Data.build("", stations, platforms, routes,
                platformStations, true, rails, 64);

        check("two stations read", reading.stations().size() == 2);
        MtrSnapshotData.Station alpha = reading.stations().get(0);
        check("a station's id is MTR's", alpha.id() == 1L);
        check("a station's name is MTR's", "Alpha".equals(alpha.name()));
        check("a station's mode is MTR's", "TRAIN".equals(alpha.mode()));
        check("a station's colour is MTR's", alpha.color() == 0xFF0000);
        check("a station's centre is the middle of its corners", alpha.centerX() == 5
                && alpha.centerZ() == 5);
        check("a station's rectangle is the one MTR drew", alpha.minX() == 0 && alpha.minZ() == 0
                && alpha.maxX() == 10 && alpha.maxZ() == 10);
        check("a station's height is lifted from its platform", alpha.centerY() == 64);

        check("two platforms read", reading.platforms().size() == 2);
        MtrSnapshotData.Platform platform = reading.platforms().get(0);
        check("a platform's id is MTR's", platform.id() == 11L);
        check("a platform's station comes from the cache", platform.stationId() == 1L);
        check("a platform's position is the middle of its two ends", platform.x() == 5
                        && platform.y() == 64 && platform.z() == 6,
                "actual=" + platform.x() + "," + platform.y() + "," + platform.z());

        check("one line read", reading.lines().size() == 1);
        MtrSnapshotData.Line line = reading.lines().get(0);
        check("a line's id is MTR's", line.id() == 31L);
        check("a line's name is MTR's", "Line 1".equals(line.name()));
        check("a line's mode is its route's", "TRAIN".equals(line.mode()));
        check("a line's colour is its route's, without the alpha byte", line.color() == 0x123456);
        check("a line has both of its stops", line.stops().size() == 2);
        check("a stop names its station", line.stops().get(0).stationId() == 1L
                && "Alpha".equals(line.stops().get(0).stationName()));
        check("a stop names its platform", line.stops().get(1).platformId() == 21L);

        check("one rail read", reading.tracks().size() == 1);
        MtrSnapshotData.Track track = reading.tracks().get(0);
        check("a rail carries a made-up id from its endpoints",
                "m3:0,64,5|10,64,5:TRAIN".equals(track.hexId()));
        check("a rail carries its mode", "TRAIN".equals(track.mode()));
        check("a rail carries the height it starts at", track.y() == 64);
        // MTR's own rail geometry is a half block off the block positions it is keyed by: the curve is
        // the centre line between them, which is where a train runs. The reader keeps MTR's geometry
        // as it stands, as the 4.x readers do.
        check("a rail is sampled from its start", track.xs().length > 1
                        && Math.abs(track.xs()[0] - 0.5) < 0.001
                        && Math.abs(track.zs()[0] - 5.5) < 0.001,
                "first=" + track.xs()[0] + "," + track.zs()[0]);
        check("a rail is sampled to its end", Math.abs(track.xs()[track.xs().length - 1] - 10.5)
                        < 0.001 && Math.abs(track.zs()[track.zs().length - 1] - 5.5) < 0.001,
                "last=" + track.xs()[track.xs().length - 1] + "," + track.zs()[track.zs().length - 1]);
    }

    /** Without a platform-to-station map, a platform is placed in the station it stands in. */
    private static void checkGeometricStations() throws Exception {
        Object train = transportMode("TRAIN");
        List<Object> stations = new ArrayList<>();
        stations.add(station(1L, "Alpha", 0, 0, 0, 10, 10));
        stations.add(station(2L, "Beta", 0, 100, 0, 110, 10));
        List<Object> platforms = new ArrayList<>();
        platforms.add(platform(11L, train, 5, 64, 5, 5, 64, 7));
        platforms.add(platform(21L, train, 105, 64, 5, 105, 64, 7));

        MtrSnapshotData.Dimension reading = Mtr3Data.build("", stations, platforms, List.of(),
                null, false, null, 64);
        check("without a cache a platform's station is found geometrically",
                reading.platforms().get(0).stationId() == 1L
                        && reading.platforms().get(1).stationId() == 2L);

        // A line with no stops in this reading is still a line, and is not invented into one.
        check("a reading with no routes has no lines", reading.lines().isEmpty());
    }

    /** A map that holds one rail in both directions is still one rail. */
    private static void checkRailDedupe() throws Exception {
        Object train = transportMode("TRAIN");
        Object rail = rail(block(0, 64, 0), block(10, 64, 0), train);
        Map<Object, Map<Object, Object>> rails = new LinkedHashMap<>();
        Map<Object, Object> forward = new LinkedHashMap<>();
        forward.put(block(10, 64, 0), rail);
        rails.put(block(0, 64, 0), forward);
        Map<Object, Object> backward = new LinkedHashMap<>();
        backward.put(block(0, 64, 0), rail);
        rails.put(block(10, 64, 0), backward);

        MtrSnapshotData.Dimension reading = Mtr3Data.build("", List.of(), List.of(), List.of(),
                null, false, rails, 64);
        check("a rail held in both directions is read once", reading.tracks().size() == 1);
    }

    /**
     * The whole map's rail conversion: several rails, a connection MTR keeps in both directions, the
     * height and endpoints of each, and the cache that keeps a poll over an unchanged network from
     * sampling every rail again.
     *
     * <p>This drives {@link Mtr3Data#wholeTracks}, which is what {@code whole()} converts a
     * dimension's rail map with; {@code whole()} itself needs a live {@code RailwayData}, which needs a
     * running world, so this is the closest the check can get to the real path.
     */
    private static void checkWholeMapRails() throws Exception {
        Object train = transportMode("TRAIN");
        Object boat = transportMode("BOAT");

        Object first = rail(block(0, 64, 0), block(10, 64, 0), train);
        Object second = rail(block(10, 64, 0), block(20, 64, 0), boat);
        Object high = rail(block(0, 70, 5), block(10, 70, 5), train);

        // MTR keeps a connection in both directions, as the same Rail object on both sides.
        Map<Object, Map<Object, Object>> rails = new LinkedHashMap<>();
        Map<Object, Object> firstEnds = new LinkedHashMap<>();
        firstEnds.put(block(10, 64, 0), first);
        rails.put(block(0, 64, 0), firstEnds);
        Map<Object, Object> secondForward = new LinkedHashMap<>();
        secondForward.put(block(20, 64, 0), second);
        rails.put(block(10, 64, 0), secondForward);
        Map<Object, Object> secondBackward = new LinkedHashMap<>();
        secondBackward.put(block(10, 64, 0), second);
        rails.put(block(20, 64, 0), secondBackward);
        Map<Object, Object> highEnds = new LinkedHashMap<>();
        highEnds.put(block(10, 70, 5), high);
        rails.put(block(0, 70, 5), highEnds);

        List<MtrSnapshotData.Track> tracks = Mtr3Data.wholeTracks(rails);
        check("a whole-dimension map yields every rail once, reverse storage included",
                tracks.size() == 3, "actual=" + tracks.size());
        MtrSnapshotData.Track firstTrack = trackById(tracks, "m3:0,64,0|10,64,0:TRAIN");
        MtrSnapshotData.Track secondTrack = trackById(tracks, "m3:10,64,0|20,64,0:BOAT");
        MtrSnapshotData.Track highTrack = trackById(tracks, "m3:0,70,5|10,70,5:TRAIN");
        check("a whole-map track keeps both endpoints' id and mode",
                firstTrack != null && secondTrack != null && highTrack != null);
        check("a whole-map track carries the height it starts at",
                highTrack != null && highTrack.y() == 70);
        check("a whole-map track is sampled from its start block's centre",
                firstTrack != null && Math.abs(firstTrack.xs()[0] - 0.5) < 0.001
                        && Math.abs(firstTrack.zs()[0] - 0.5) < 0.001);
        check("a whole-map track is sampled to its end block's centre",
                firstTrack != null
                        && Math.abs(firstTrack.xs()[firstTrack.xs().length - 1] - 10.5) < 0.001
                        && Math.abs(firstTrack.zs()[firstTrack.zs().length - 1] - 0.5) < 0.001);
        check("a reverse-stored connection is sampled along its own geometry",
                secondTrack != null
                        && Math.abs(secondTrack.xs()[secondTrack.xs().length - 1] - 20.5) < 0.001,
                "last x=" + (secondTrack == null ? "missing"
                        : String.valueOf(secondTrack.xs()[secondTrack.xs().length - 1])));

        List<MtrSnapshotData.Track> unchanged = Mtr3Data.wholeTracks(rails);
        check("an unchanged network reuses the converted rails", unchanged == tracks,
                "the conversion ran again on a poll that should have been free");

        Map<Object, Object> thirdEnds = new LinkedHashMap<>();
        thirdEnds.put(block(30, 64, 0), rail(block(20, 64, 0), block(30, 64, 0), train));
        rails.put(block(20, 64, 0), thirdEnds);
        List<MtrSnapshotData.Track> extended = Mtr3Data.wholeTracks(rails);
        check("a network with a rail added is converted again", extended != unchanged
                && extended.size() == 4, "actual=" + extended.size());
        check("the conversion after a change keeps the rails it already had",
                trackById(extended, "m3:0,64,0|10,64,0:TRAIN") != null
                        && trackById(extended, "m3:20,64,0|30,64,0:TRAIN") != null);

        rails.remove(block(0, 64, 0));
        List<MtrSnapshotData.Track> pruned = Mtr3Data.wholeTracks(rails);
        check("a removed rail leaves the cached conversion too", pruned.size() == 3
                        && trackById(pruned, "m3:0,64,0|10,64,0:TRAIN") == null,
                "actual=" + pruned.size());
    }

    /** The track with one made-up id, or null when the reading does not hold it. */
    private static MtrSnapshotData.Track trackById(List<MtrSnapshotData.Track> tracks, String id) {
        for (MtrSnapshotData.Track track : tracks) {
            if (id.equals(track.hexId())) {
                return track;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ the objects

    private static Object station(long id, String name, int colour, int minX, int minZ, int maxX,
                                  int maxZ) throws Exception {
        Constructor<?> constructor = stationClass.getConstructor(long.class);
        Object station = constructor.newInstance(id);
        set(station, "name", name);
        set(station, "color", colour);
        set(station, "corner1", tuple(minX, minZ));
        set(station, "corner2", tuple(maxX, maxZ));
        return station;
    }

    private static Object platform(long id, Object mode, int x, int y, int z, int x2, int y2, int z2)
            throws Exception {
        Constructor<?> constructor = platformClass.getConstructor(long.class, transportModeClass,
                blockPosClass, blockPosClass);
        return constructor.newInstance(id, mode, block(x, y, z), block(x2, y2, z2));
    }

    private static Object route(long id, String name, Object mode, int colour, long... platformIds)
            throws Exception {
        Constructor<?> constructor = routeClass.getConstructor(long.class, transportModeClass);
        Object route = constructor.newInstance(id, mode);
        set(route, "name", name);
        set(route, "color", colour);
        @SuppressWarnings("unchecked")
        List<Object> ids = (List<Object>) routeClass.getField("platformIds").get(route);
        Constructor<?> stop = routePlatformClass.getConstructor(long.class);
        for (long platformId : platformIds) {
            ids.add(stop.newInstance(platformId));
        }
        return route;
    }

    private static Object rail(Object from, Object to, Object mode) throws Exception {
        Constructor<?> constructor = railClass.getConstructor(blockPosClass, railAngleClass,
                blockPosClass, railAngleClass, railTypeClass, transportModeClass);
        Object east = railAngle("E");
        Object iron = railType("IRON");
        return constructor.newInstance(from, east, to, east, iron, mode);
    }

    private static Object block(int x, int y, int z) throws Exception {
        return blockPosClass.getConstructor(int.class, int.class, int.class).newInstance(x, y, z);
    }

    private static Object tuple(Object first, Object second) throws Exception {
        return tupleClass.getConstructor(Object.class, Object.class).newInstance(first, second);
    }

    private static Object transportMode(String name) throws Exception {
        return Enum.valueOf(transportModeClass.asSubclass(Enum.class), name);
    }

    private static Object railAngle(String name) throws Exception {
        return Enum.valueOf(railAngleClass.asSubclass(Enum.class), name);
    }

    private static Object railType(String name) throws Exception {
        return Enum.valueOf(railTypeClass.asSubclass(Enum.class), name);
    }

    private static void set(Object target, String field, Object value) throws Exception {
        Field member = target.getClass().getField(field);
        member.set(target, value);
    }

    private static Class<?> load(String name) throws ClassNotFoundException {
        return Class.forName(name, false, BindCheck.class.getClassLoader());
    }

    private static void check(String what, boolean condition) {
        check(what, condition, null);
    }

    private static void check(String what, boolean condition, String detail) {
        checks++;
        if (condition) {
            System.out.println("  ok   " + what);
        } else {
            failures++;
            System.out.println("  FAIL " + what + (detail == null ? "" : "  (" + detail + ")"));
        }
    }
}
