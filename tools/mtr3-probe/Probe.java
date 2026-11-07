import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Verifies, by reflection and with no game running, every class, field and method a reader for
 * MTR 3.x (the {@code mtr.*} lineage, of which YMTR is a fork) intends to touch.
 *
 * <h2>Why the classpath is the argument</h2>
 * The jar this checks is a Fabric <em>production</em> jar: it was remapped for release and so names
 * Minecraft with intermediary names ({@code net.minecraft.class_2338}), not the official names this
 * project compiles against ({@code net.minecraft.core.BlockPos}). Those are the names that exist in
 * a running Fabric game, and they are the names a reflective reader has to use. They are, however,
 * <b>not</b> on a Loom node's development runtime classpath, which is remapped to the official
 * names. So the probe runs twice, with two classpaths, and both answers matter:
 *
 * <ul>
 *   <li>{@code dev} -- the node runtime classpath plus the MTR jar. MTR's own classes load here and
 *       every {@code mtr.*} name in the plan can be checked; the {@code net.minecraft.class_*}
 *       types cannot, because Loom put the official names on this classpath instead.</li>
 *   <li>{@code production} -- the same, plus Loom's cached intermediary Minecraft jar. Now
 *       {@code class_2338}, {@code class_243} and {@code class_3545} exist and the field types of
 *       MTR's own classes resolve. This is the namespace a running Fabric game has.</li>
 * </ul>
 *
 * <h2>What a row means</h2>
 * <pre>
 *   ok    mtr.data.Route#getDestination         (int) -> String    public java.lang.String
 *   MISS  mtr.data.AreaBase#getCenter           () -> BlockPos     NoClassDefFoundError: ...
 * </pre>
 * "ok" means the lookup itself resolved on this classpath; the last column is the real descriptor
 * and, for a field, its modifiers and generic type. "MISS" prints the exception that lookup threw,
 * so a renamed member and a class that is not on this classpath are told apart by the reason, not
 * only by the colour of the row.
 *
 * <p>An argument of {@code dev} or {@code production} only labels the report; the probe never
 * branches on it. Whatever is on the classpath is what gets checked, which is what makes a third
 * pass (a different MTR build, say) as simple as a different {@code -cp}.
 *
 * <p>Exit code is always 0. The counts at the end are the verdict: how many planned names resolved
 * and how many did not, per pass. A tool that exits non-zero because one expected-MISSING
 * intermediary name is absent on the dev classpath would be lying about what it found.
 */
public final class Probe {

    private static int found;
    private static int missing;
    private static String passLabel = "runtime";

    private static final int NAME_WIDTH = 42;
    private static final int EXPECTED_WIDTH = 34;

    private Probe() {
    }

    public static void main(String[] args) {
        String pass = args.length > 0 ? args[0] : "runtime";
        passLabel = pass;
        System.out.println("=== MTR 3.x reflection probe ===");
        System.out.println("pass: " + pass + "   java: " + System.getProperty("java.version"));
        String[] entries = System.getProperty("java.class.path")
                .split(Pattern.quote(File.pathSeparator));
        System.out.println("classpath entries: " + entries.length);

        environment();
        plannedClasses();
        clientData();
        dataModelFields();
        areaAndSavedRail();
        dataCache();
        rail();
        route();
        railwayData();
        minecraftAccessors();
        summary();
    }

    // ------------------------------------------------------------------ sections

    /**
     * Which jar the MTR classes come from, and which Minecraft namespace this classpath carries.
     *
     * <p>Both questions are answered before anything is looked up, because both decide how to read
     * every row after them: an {@code mtr.*} row can only be a real finding if the MTR jar is on
     * this classpath at all, and a {@code class_*} miss on a classpath whose MTR classes load is a
     * namespace fact rather than a version fact.
     */
    private static void environment() {
        section("environment");
        Class<?> clientData = tryLoad("mtr.client.ClientData");
        if (clientData == null) {
            row("MISS", "mtr.client.ClientData", "MTR jar on classpath", "not loadable: no MTR 3.x jar");
        } else {
            row("ok", "mtr jar", "on classpath", codeSource(clientData));
        }
        boolean intermediary = present("net.minecraft.class_2338");
        boolean named = present("net.minecraft.core.BlockPos");
        row(intermediary ? "ok" : "MISS", "intermediary Minecraft (class_2338)", "present or absent",
                intermediary ? "present: the production namespace resolves here"
                        : "absent: no intermediary-named Minecraft on this classpath");
        row(named ? "ok" : "MISS", "official Minecraft (core.BlockPos)", "present or absent",
                named ? "present: the Loom development namespace resolves here"
                        : "absent: no official-named Minecraft on this classpath");
        if (intermediary && named) {
            // The production pass is built this way on purpose: the dev classpath's named jar stays
            // where Gradle put it and the intermediary jar is appended. The two name sets do not
            // overlap, so both spellings resolve and MTR's own descriptors resolve against the
            // class_* one.
            System.out.println("        both namespaces are on this classpath: the named jar comes from "
                    + "Gradle, the class_* jar was appended for this pass");
        }
    }

    /**
     * The classes the planned reader names, loaded the way the reader will load them: by name, with
     * {@code initialise = false}, through the same class loader.
     *
     * <p>This is the outermost question the rest of the report hangs from. A class that cannot be
     * loaded here cannot be read in a game either, and the {@code NoClassDefFoundError} in that row
     * names the class that was missing on this classpath rather than in MTR.
     */
    private static void plannedClasses() {
        section("classes (Class.forName(name, false, loader))");
        String[] names = {
                // the client's copy of the network
                "mtr.client.ClientData",
                "mtr.client.ClientCache",
                // the data model
                "mtr.data.Station",
                "mtr.data.Platform",
                "mtr.data.Route",
                "mtr.data.Rail",
                "mtr.data.DataCache",
                "mtr.data.RailwayData",
                "mtr.data.Route$RoutePlatform",
                // the lineage the accessors below are declared on
                "mtr.data.NameColorDataBase",
                "mtr.data.AreaBase",
                "mtr.data.SavedRailBase",
        };
        for (String name : names) {
            classRow(name);
        }
    }

    /** The five static fields of the client data: the one entry point a client-side reader has. */
    private static void clientData() {
        section("mtr.client.ClientData static fields");
        Class<?> clientData = tryLoad("mtr.client.ClientData");
        if (clientData == null) {
            row("MISS", "mtr.client.ClientData", "class", "not loadable here; fields not checked");
            return;
        }
        fieldRow(clientData, "STATIONS", "Set<Station>");
        fieldRow(clientData, "PLATFORMS", "Set<Platform>");
        fieldRow(clientData, "ROUTES", "Set<Route>");
        fieldRow(clientData, "RAILS", "Map<BlockPos, Map<BlockPos, Rail>>");
        fieldRow(clientData, "DATA_CACHE", "ClientCache (extends DataCache)");
        // The declared type of DATA_CACHE is the client cache, and platformIdToStation is inherited
        // from the simulation core's DataCache. Whether getField finds it through the client cache
        // is the difference between one handle and two.
        Class<?> clientCache = tryLoad("mtr.client.ClientCache");
        if (clientCache != null && clientCache.getSuperclass() != null) {
            row("ok", "ClientCache superclass", "mtr.data.DataCache",
                    clientCache.getSuperclass().getName());
        }
    }

    /**
     * id / name / colour / transport mode, both declared on the lineage and found from each concrete
     * class.
     *
     * <p>Two checks, because they answer different questions. The declared row says which class owns
     * the field, and therefore which descriptor a reader should take its handle from. The inherited
     * row says {@code getField} on the class the reader actually holds finds it -- and a reader that
     * took a handle from a base class and invoked it on a class that does not inherit would throw
     * at invoke time, which is a failure no descriptor check sees.
     */
    private static void dataModelFields() {
        section("NameColorDataBase fields and inheritance");
        Class<?> base = tryLoad("mtr.data.NameColorDataBase");
        if (base != null) {
            fieldRow(base, "id", "long");
            fieldRow(base, "name", "String");
            fieldRow(base, "color", "int");
            fieldRow(base, "transportMode", "TransportMode");
            // The field's type is the enum a reader needs to interpret in MTR's own terms (a line's
            // kind comes from it), so its constants are reported rather than assumed.
            Class<?> transportMode = tryLoad("mtr.data.TransportMode");
            if (transportMode != null && transportMode.isEnum()) {
                StringBuilder constants = new StringBuilder();
                for (Object constant : transportMode.getEnumConstants()) {
                    if (constants.length() > 0) {
                        constants.append(", ");
                    }
                    constants.append(((Enum<?>) constant).name());
                }
                row("ok", "TransportMode constants", "enum values", constants.toString());
                found++;
            } else {
                row("MISS", "TransportMode constants", "enum values",
                        transportMode == null ? "mtr.data.TransportMode not loadable" : "not an enum");
                missing++;
            }
        } else {
            row("MISS", "mtr.data.NameColorDataBase", "class", "not loadable here");
        }
        for (String concrete : new String[]{"mtr.data.Station", "mtr.data.Platform", "mtr.data.Route"}) {
            Class<?> c = tryLoad(concrete);
            if (c == null) {
                row("MISS", concrete, "class", "not loadable here");
                continue;
            }
            for (String field : new String[]{"id", "name", "color", "transportMode"}) {
                fieldRow(c, field, "inherited from NameColorDataBase");
            }
        }
    }

    /**
     * A station's corners and a saved rail's middle.
     *
     * <p>Both accessors are Minecraft types in MTR's signatures rather than MTR's own, which is why
     * this is the part a dev-classpath-only check cannot answer: the field exists and its
     * descriptor is {@code Lnet/minecraft/class_3545;}, but whether that class resolves decides
     * whether {@code getType()} -- and therefore the lookup every reader does -- works.
     *
     * <p>The corner type has two spellings in the two namespaces: {@code method_15442}/
     * {@code method_15441} in production, {@code getA}/{@code getB} under the official mappings.
     * The plan's {@code getLeft}/{@code getRight} is neither: {@code net.minecraft.util.Tuple} is
     * what {@code class_3545} is, and it has never had left/right accessors. This section reports
     * every spelling rather than assuming one, so a reader can be written to try them in order.
     */
    private static void areaAndSavedRail() {
        section("AreaBase / SavedRailBase");
        Class<?> area = tryLoad("mtr.data.AreaBase");
        if (area != null) {
            fieldRow(area, "corner1", "Tuple<Integer,Integer> (class_3545)");
            fieldRow(area, "corner2", "Tuple<Integer,Integer> (class_3545)");
            Class<?> tuple = null;
            try {
                tuple = field(area, "corner1").getType();
                row("ok", "corner1 field type", "net.minecraft.class_3545", tuple.getName());
            } catch (Throwable t) {
                row("MISS", "corner1 field type", "net.minecraft.class_3545", error(t));
            }
            if (tuple == null) {
                // The field's own type did not resolve here, but the namespace's spelling of the
                // corner type can still be found and its accessors checked -- which is what lets the
                // reader's "try intermediary, then official" order be verified from both passes.
                tuple = firstLoad("net.minecraft.class_3545", "net.minecraft.util.Tuple");
                if (tuple != null) {
                    row("--", "corners checked on", "class_3545 or named Tuple",
                            tuple.getName() + " (the field type did not resolve here; this is the "
                                    + "namespace's own spelling, so the fallback order is still checked)");
                }
            }
            if (tuple != null) {
                pairAccessors(tuple);
            }
            methodRow(area, "getCenter", "(BlockPos)", new Class<?>[0]);
        } else {
            row("MISS", "mtr.data.AreaBase", "class", "not loadable here");
        }

        Class<?> savedRail = tryLoad("mtr.data.SavedRailBase");
        if (savedRail != null) {
            methodRow(savedRail, "getMidPos", "(BlockPos)", new Class<?>[0]);
            // A second overload with a boolean flag; reported so a reader cannot silently bind the
            // wrong one if it ever asks for a method by name alone.
            methodRow(savedRail, "getMidPos", "(boolean) -> BlockPos", new Class<?>[]{boolean.class});
        } else {
            row("MISS", "mtr.data.SavedRailBase", "class", "not loadable here");
        }
    }

    /**
     * The two corners' accessors, under every spelling either namespace has given them.
     *
     * <p>Read by constructing a tuple and calling what resolved: a method that exists but returns
     * the wrong half is a bug a name check cannot see, and the values make the report say which
     * accessor is which instead of trusting the name.
     */
    private static void pairAccessors(Class<?> tuple) {
        row("", "corner type", "class_3545 = named Tuple", tuple.getName() + access(tuple));
        String[][] spellings = {
                {"method_15442", "intermediary A/first"},
                {"method_15441", "intermediary B/second"},
                {"getLeft", "planned"},
                {"getRight", "planned"},
                {"getA", "official A/first"},
                {"getB", "official B/second"},
        };
        Object instance = null;
        try {
            Constructor<?> ctor = tuple.getConstructor(Object.class, Object.class);
            instance = ctor.newInstance(Integer.valueOf(7), Integer.valueOf(9));
        } catch (Throwable t) {
            row("--", "new Tuple(7, 9)", "instance for value check", error(t));
        }
        for (String[] spelling : spellings) {
            String name = spelling[0];
            try {
                Method m = tuple.getMethod(name);
                String value = instance == null ? "no instance"
                        : String.valueOf(m.invoke(instance));
                row("ok", tuple.getSimpleName() + "#" + name, spelling[1], descriptor(m) + " = " + value);
                found++;
            } catch (Throwable t) {
                row("MISS", tuple.getSimpleName() + "#" + name, spelling[1], error(t));
                missing++;
            }
        }
    }

    /**
     * A platform's station, resolved through the client cache: the mapping the reader needs so that a
     * platform read out of MTR's rails can be attributed to the station it belongs to.
     */
    private static void dataCache() {
        section("DataCache.platformIdToStation");
        Class<?> cache = tryLoad("mtr.data.DataCache");
        if (cache != null) {
            fieldRow(cache, "platformIdToStation", "Map<Long, Station>");
        } else {
            row("MISS", "mtr.data.DataCache", "class", "not loadable here");
        }
        // The same field, but asked for on the type ClientData.DATA_CACHE actually hands a reader.
        Class<?> clientCache = tryLoad("mtr.client.ClientCache");
        if (clientCache != null) {
            fieldRow(clientCache, "platformIdToStation", "inherited from DataCache");
        }
    }

    /** A rail's length, its point at a parameter and its transport mode. */
    private static void rail() {
        section("mtr.data.Rail");
        Class<?> rail = tryLoad("mtr.data.Rail");
        if (rail == null) {
            row("MISS", "mtr.data.Rail", "class", "not loadable here");
            return;
        }
        fieldRow(rail, "transportMode", "TransportMode");
        methodRow(rail, "getLength", "() -> double", new Class<?>[0]);
        methodRow(rail, "getPosition", "(double) -> class_243", new Class<?>[]{double.class});
        // MTR 3.x has no RailMath wrapper: the maths lives on the rail itself, which is why the
        // two methods above are the whole of what a track reader needs here.
    }

    /** A route's stops and the fields the map's line labels read. */
    private static void route() {
        section("mtr.data.Route and Route$RoutePlatform");
        Class<?> route = tryLoad("mtr.data.Route");
        if (route != null) {
            fieldRow(route, "platformIds", "List<RoutePlatform>");
            fieldRow(route, "isHidden", "boolean");
            methodRow(route, "getDestination", "(int) -> String", new Class<?>[]{int.class});
        } else {
            row("MISS", "mtr.data.Route", "class", "not loadable here");
        }
        Class<?> routePlatform = tryLoad("mtr.data.Route$RoutePlatform");
        if (routePlatform != null) {
            fieldRow(routePlatform, "platformId", "long");
            fieldRow(routePlatform, "customDestination", "String");
        } else {
            row("MISS", "mtr.data.Route$RoutePlatform", "class", "not loadable here");
        }
    }

    /**
     * The server-side network and the rails map.
     *
     * <p>{@code getInstance(Level)} is looked up as "the public static method named getInstance
     * taking exactly one world", not by parameter class: the class of that parameter is
     * {@code class_1937} in production, and naming it here would make the check depend on the very
     * classpath it is meant to measure. Every candidate is printed, so a changed signature shows up
     * as a different descriptor rather than as a bare miss.
     *
     * <p>The rails field is private, and the plan proposed reaching it with {@code setAccessible}.
     * Both the field and a public getter are reported: if {@code getRailsMap()} exists, a reader
     * that uses it asks for no privileged access at all, which is the better shape on a modern JVM.
     */
    private static void railwayData() {
        section("mtr.data.RailwayData");
        Class<?> railwayData;
        try {
            railwayData = load("mtr.data.RailwayData");
        } catch (Throwable t) {
            // On a classpath without MTR's intermediary Minecraft classes this class cannot even be
            // loaded: its superclass is mtr.mappings.PersistentStateMapper, which extends
            // net.minecraft.class_18. The reason is printed rather than the word "missing".
            row("MISS", "mtr.data.RailwayData", "class", error(t));
            missing++;
            return;
        }
        getInstanceRow(railwayData);
        fieldRow(railwayData, "stations", "Set<Station>");
        fieldRow(railwayData, "platforms", "Set<Platform>");
        fieldRow(railwayData, "routes", "Set<Route>");
        fieldRow(railwayData, "dataCache", "mtr.data.DataCache");
        privateFieldRow(railwayData, "rails", "Map<BlockPos, Map<BlockPos, Rail>>");
        methodRow(railwayData, "getRailsMap", "() -> Map<BlockPos, Map<BlockPos, Rail>>",
                new Class<?>[0]);
    }

    /** The static instance lookup, matched by shape and printed by descriptor. */
    private static void getInstanceRow(Class<?> owner) {
        String label = owner.getSimpleName() + "#getInstance";
        List<Method> candidates = new ArrayList<>();
        try {
            for (Method m : owner.getMethods()) {
                if (m.getName().equals("getInstance")) {
                    candidates.add(m);
                }
            }
        } catch (Throwable t) {
            row("MISS", label, "static (World) -> RailwayData", error(t));
            missing++;
            return;
        }
        Method match = null;
        for (Method m : candidates) {
            if (m.getParameterCount() != 1 || !Modifier.isStatic(m.getModifiers())) {
                continue;
            }
            String parameter = m.getParameterTypes()[0].getName();
            if (parameter.equals("net.minecraft.class_1937")
                    || parameter.equals("net.minecraft.world.level.Level")) {
                match = m;
                break;
            }
        }
        if (match != null) {
            row("ok", label, "static (class_1937) -> RailwayData", descriptor(match));
            found++;
        } else {
            row("MISS", label, "static (class_1937) -> RailwayData",
                    candidates.isEmpty() ? "no method named getInstance" : "no one-world overload");
            missing++;
        }
        for (Method m : candidates) {
            System.out.println("        candidate: " + descriptor(m)
                    + (Modifier.isStatic(m.getModifiers()) ? "  static" : "  instance"));
        }
    }

    /**
     * The Minecraft classes MTR's own signatures name, and the accessors on them.
     *
     * <p>This section is written against both namespaces on purpose. On the dev classpath only the
     * official spelling exists; on the production classpath only the {@code class_*} one does; a
     * reader that has to run in both needs to know which spelling belongs to which. Where an
     * instance can be built, the accessor is invoked and the value printed -- an accessor that exists
     * but answers the wrong coordinate is the one failure a descriptor check cannot catch.
     */
    private static void minecraftAccessors() {
        section("Minecraft accessors");
        blockPosAccessors();
        vec3Accessors();
    }

    /** BlockPos: x/y/z, inherited from Vec3i. Intermediary {@code class_2338} / {@code class_2382}. */
    private static void blockPosAccessors() {
        Class<?> blockPos = firstLoad("net.minecraft.class_2338", "net.minecraft.core.BlockPos");
        Class<?> vec3i = firstLoad("net.minecraft.class_2382", "net.minecraft.core.Vec3i");
        row(blockPos == null ? "MISS" : "ok", "BlockPos class",
                "class_2338 or core.BlockPos", blockPos == null ? "neither present" : blockPos.getName());
        row(vec3i == null ? "MISS" : "ok", "Vec3i class",
                "class_2382 or core.Vec3i", vec3i == null ? "neither present" : vec3i.getName());
        if (blockPos == null) {
            return;
        }
        Object pos = null;
        try {
            Constructor<?> ctor = blockPos.getConstructor(int.class, int.class, int.class);
            pos = ctor.newInstance(Integer.valueOf(11), Integer.valueOf(22), Integer.valueOf(33));
            row("ok", "new BlockPos(11, 22, 33)", "instance for value check",
                    blockPos.getSimpleName() + "(int,int,int)");
        } catch (Throwable t) {
            row("--", "new BlockPos(11, 22, 33)", "instance for value check", error(t));
        }
        coordinateRow("x", 11, blockPos, new String[]{"method_10263", "getX"}, pos);
        coordinateRow("y", 22, blockPos, new String[]{"method_10264", "getY"}, pos);
        coordinateRow("z", 33, blockPos, new String[]{"method_10260", "getZ"}, pos);
        // A no-argument int accessor that exists on both namespaces; reported because "does the
        // reader need a handle per coordinate, or one per method name" is the question it answers.
        if (vec3i != null) {
            reportDeclaringClass(vec3i, "method_10263");
        }
    }

    /**
     * One coordinate: whichever spelling resolves, invoked and checked against the constructed
     * position. Both spellings are printed so a missing one is visibly a namespace fact.
     */
    private static void coordinateRow(String axis, int expected, Class<?> owner, String[] spellings,
                                      Object instance) {
        for (String name : spellings) {
            String label = owner.getSimpleName() + "#" + name;
            try {
                Method m = owner.getMethod(name);
                String value = instance == null ? "no instance" : String.valueOf(m.invoke(instance));
                String verdict = instance == null || String.valueOf(expected).equals(value) ? "" : "  WRONG AXIS";
                row("ok", label, axis + " accessor", descriptor(m) + " = " + value + verdict
                        + "  (declared by " + m.getDeclaringClass().getSimpleName() + ")");
                found++;
            } catch (Throwable t) {
                row("MISS", label, axis + " accessor", error(t));
                missing++;
            }
        }
    }

    /**
     * Vec3d: which of {@code field_1352}/{@code field_1351}/{@code field_1350} is x, y and z.
     *
     * <p>Answered by constructing {@code new Vec3d(1, 2, 3)} and reading each field, so the report
     * states the coordinate rather than the field's position in the class file. MTR's
     * {@code Rail.getPosition(double)} returns one of these and a track reader takes it apart, so
     * getting x and z the wrong way round draws every railway mirrored.
     */
    private static void vec3Accessors() {
        Class<?> vec3 = firstLoad("net.minecraft.class_243", "net.minecraft.world.phys.Vec3");
        row(vec3 == null ? "MISS" : "ok", "Vec3d class",
                "class_243 or world.phys.Vec3", vec3 == null ? "neither present" : vec3.getName());
        if (vec3 == null) {
            return;
        }
        Object instance = null;
        try {
            Constructor<?> ctor = vec3.getConstructor(double.class, double.class, double.class);
            instance = ctor.newInstance(Double.valueOf(1.5), Double.valueOf(2.5), Double.valueOf(3.5));
            row("ok", "new Vec3d(1.5, 2.5, 3.5)", "instance for value check",
                    vec3.getSimpleName() + "(double,double,double)");
        } catch (Throwable t) {
            row("--", "new Vec3d(1.5, 2.5, 3.5)", "instance for value check", error(t));
        }
        String[][] fields = {
                {"field_1352", "x"},
                {"field_1351", "y"},
                {"field_1350", "z"},
                {"x", "x (official)"},
                {"y", "y (official)"},
                {"z", "z (official)"},
        };
        for (String[] candidate : fields) {
            String name = candidate[0];
            try {
                Field f = field(vec3, name);
                String value = instance == null ? "no instance" : String.valueOf(f.get(instance));
                row("ok", vec3.getSimpleName() + "#" + name, candidate[1],
                        Modifier.toString(f.getModifiers()) + " double = " + value
                                + "  (declared by " + f.getDeclaringClass().getSimpleName() + ")");
                found++;
            } catch (Throwable t) {
                row("MISS", vec3.getSimpleName() + "#" + name, candidate[1], error(t));
                missing++;
            }
        }
    }

    // ------------------------------------------------------------------ rows

    private static void section(String title) {
        System.out.println();
        System.out.println("== " + title + " ==");
    }

    private static void row(String status, String name, String expected, String actual) {
        System.out.printf("  %-4s  %-" + NAME_WIDTH + "s  %-" + EXPECTED_WIDTH + "s  %s%n",
                status, name, expected, actual);
    }

    /** A class, loaded the way the reader loads it: by name, not initialised. */
    private static void classRow(String name) {
        try {
            Class<?> c = Class.forName(name, false, loader());
            row("ok", name, "class", kind(c) + ", loaded without initialising");
            found++;
        } catch (Throwable t) {
            row("MISS", name, "class", error(t));
            missing++;
        }
    }

    /** A field found public (inherited or not) or declared up the chain; type and generic reported. */
    private static void fieldRow(Class<?> owner, String name, String expected) {
        String label = owner.getSimpleName() + "#" + name;
        try {
            Field f = field(owner, name);
            row("ok", label, expected, Modifier.toString(f.getModifiers()) + " "
                    + generic(f) + "  (declared by " + f.getDeclaringClass().getSimpleName() + ")");
            found++;
        } catch (Throwable t) {
            row("MISS", label, expected, error(t));
            missing++;
        }
    }

    /** A public field on a class that is expected to keep it; reports if it is not public. */
    private static void privateFieldRow(Class<?> owner, String name, String expected) {
        String label = owner.getSimpleName() + "#" + name;
        try {
            Field f = field(owner, name);
            row("ok", label, expected, Modifier.toString(f.getModifiers()) + " " + generic(f));
            found++;
            try {
                f.setAccessible(true);
                row("ok", label + ".setAccessible", "allowed", "allowed, value readable");
                found++;
            } catch (Throwable t) {
                row("MISS", label + ".setAccessible", "allowed", error(t));
                missing++;
            }
        } catch (Throwable t) {
            row("MISS", label, expected, error(t));
            missing++;
        }
    }

    /** A method by exact name and parameters; descriptor and owning class reported. */
    private static void methodRow(Class<?> owner, String name, String expected, Class<?>[] parameters) {
        String label = owner.getSimpleName() + "#" + name;
        try {
            Method m = method(owner, name, parameters);
            row("ok", label, expected, descriptor(m) + "  (declared by "
                    + m.getDeclaringClass().getSimpleName() + ")");
            found++;
        } catch (Throwable t) {
            row("MISS", label, expected, error(t));
            missing++;
        }
    }

    /** Where a named no-argument method is declared, if it exists at all. */
    private static void reportDeclaringClass(Class<?> owner, String name) {
        try {
            Method m = owner.getMethod(name);
            row("ok", owner.getSimpleName() + "#" + name, "declaration site",
                    descriptor(m) + "  (declared by " + m.getDeclaringClass().getSimpleName() + ")");
            found++;
        } catch (Throwable t) {
            row("MISS", owner.getSimpleName() + "#" + name, "declaration site", error(t));
            missing++;
        }
    }

    private static void summary() {
        System.out.println();
        System.out.println("== summary ==");
        System.out.println("  pass: " + passLabel + "   resolved: " + found + "   unresolved: " + missing);
        if (passLabel.equals("production")) {
            System.out.println("  (production: the class_*/method_*/field_* rows are the names a reader "
                    + "must use at runtime; getX/getA/Vec3.x rows are the official spelling and are "
                    + "expected to miss here)");
        } else {
            System.out.println("  (dev: net.minecraft.class_* rows cannot resolve -- this classpath is "
                    + "remapped to official names. Member lookups on MTR classes fail here too, because "
                    + "verifying them needs the MC classes their signatures name)");
        }
    }

    // ------------------------------------------------------------------ helpers

    private static ClassLoader loader() {
        return ClassLoader.getSystemClassLoader();
    }

    /** A class by name, not initialised -- the reader's own {@code load}. */
    private static Class<?> load(String name) throws ClassNotFoundException {
        return Class.forName(name, false, loader());
    }

    private static Class<?> tryLoad(String name) {
        try {
            return load(name);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Class<?> firstLoad(String... names) {
        for (String name : names) {
            Class<?> c = tryLoad(name);
            if (c != null) {
                return c;
            }
        }
        return null;
    }

    private static boolean present(String name) {
        return tryLoad(name) != null;
    }

    /** A field: public and inherited first ({@code getField}), then declared up the chain. */
    private static Field field(Class<?> owner, String name) throws NoSuchFieldException {
        try {
            return owner.getField(name);
        } catch (NoSuchFieldException missing) {
            for (Class<?> c = owner; c != null; c = c.getSuperclass()) {
                try {
                    return c.getDeclaredField(name);
                } catch (NoSuchFieldException ignored) {
                    // keep walking: inherited members are not declared on the concrete class
                }
            }
            throw missing;
        }
    }

    /** A method: public and inherited first, then declared up the chain. */
    private static Method method(Class<?> owner, String name, Class<?>[] parameters)
            throws NoSuchMethodException {
        try {
            return owner.getMethod(name, parameters);
        } catch (NoSuchMethodException missing) {
            for (Class<?> c = owner; c != null; c = c.getSuperclass()) {
                try {
                    return c.getDeclaredMethod(name, parameters);
                } catch (NoSuchMethodException ignored) {
                    // keep walking
                }
            }
            throw missing;
        }
    }

    /** Which jar a class was loaded from, or its own description when there is no code source. */
    private static String codeSource(Class<?> c) {
        try {
            java.security.CodeSource source = c.getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null) {
                return "<no code source>";
            }
            return new File(source.getLocation().toURI()).getName();
        } catch (Throwable t) {
            return "<code source unreadable: " + t + ">";
        }
    }

    private static String kind(Class<?> c) {
        if (c.isInterface()) {
            return "interface";
        }
        if (c.isEnum()) {
            return "enum";
        }
        String shape = Modifier.isAbstract(c.getModifiers()) ? "abstract class" : "class";
        Class<?> parent = c.getSuperclass();
        return parent == null ? shape : shape + " extends " + parent.getName();
    }

    private static String access(Class<?> c) {
        return " " + Modifier.toString(c.getModifiers());
    }

    /** A field's generic type, or the reason it could not be resolved on this classpath. */
    private static String generic(Field f) {
        try {
            return f.getGenericType().getTypeName();
        } catch (Throwable t) {
            return "<type unresolved: " + error(t) + ">";
        }
    }

    private static String error(Throwable t) {
        StringBuilder out = new StringBuilder();
        for (Throwable c = t; c != null && out.length() < 220; c = c.getCause()) {
            if (out.length() > 0) {
                out.append(" <- ");
            }
            out.append(c.getClass().getSimpleName());
            if (c.getMessage() != null) {
                out.append(": ").append(c.getMessage());
            }
        }
        return out.toString();
    }

    /** A JVM descriptor, so a row can be compared with the plan rather than with its prose. */
    private static String descriptor(Method m) {
        StringBuilder out = new StringBuilder("(");
        for (Class<?> parameter : m.getParameterTypes()) {
            out.append(descriptor(parameter));
        }
        return out.append(')').append(descriptor(m.getReturnType())).toString();
    }

    private static String descriptor(Class<?> c) {
        if (c.isArray()) {
            return "[" + descriptor(c.getComponentType());
        }
        if (c == void.class) {
            return "V";
        }
        if (c == boolean.class) {
            return "Z";
        }
        if (c == byte.class) {
            return "B";
        }
        if (c == char.class) {
            return "C";
        }
        if (c == short.class) {
            return "S";
        }
        if (c == int.class) {
            return "I";
        }
        if (c == long.class) {
            return "J";
        }
        if (c == float.class) {
            return "F";
        }
        if (c == double.class) {
            return "D";
        }
        return "L" + c.getName().replace('.', '/') + ";";
    }
}
