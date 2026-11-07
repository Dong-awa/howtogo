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
