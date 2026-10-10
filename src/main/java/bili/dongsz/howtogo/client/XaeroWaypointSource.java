package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.HowToGo;
import bili.dongsz.howtogo.route.Destination;
import bili.dongsz.howtogo.route.DestinationSource;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.fabricmc.loader.api.FabricLoader;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Destinations from Xaero's own waypoints, read reflectively out of the Minimap mod.
 *
 * <h2>Why the reading is reflective</h2>
 * Waypoint storage is <b>not</b> part of Xaero's World Map: neither the World Map jar nor the
 * {@code xaerolib} it bundles has a single class under {@code waypoints/}. The storage, and every
 * class needed to reach it, lives in the Minimap mod ({@code xaero.common.minimap.waypoints.*}),
 * which is only an <em>optional</em> dependency of the World Map and may not be installed at all.
 *
 * <p>The adapter is therefore bound by name at runtime rather than against the jar, so this mod
 * keeps compiling and running with the Minimap absent and does not gain a dependency on it in
 * {@code build.gradle}. When the Minimap is present the chain is walked once and the handles are
 * cached; when it is absent, or when the mod has renamed anything, the source reports itself
 * unavailable and the picker falls back to {@link PoiDestinationSource}.
 *
 * <p>The chain, verified against {@code xaerominimap-neoforge-1.21.1-25.3.13.jar}:
 * <pre>
 * XaeroMinimapSession.getCurrentSession()   // static, null before a world is joined
 *   .getWaypointsManager()
 *   .getCurrentWorld()                      // WaypointWorld, null outside a world
 *   .getCurrentSet()
 *   .getList()                              // ArrayList&lt;Waypoint&gt;
 * </pre>
 *
 * <p>Each waypoint then answers {@code getName()}, {@code getX()}, {@code getZ()},
 * {@code isTemporary()} -- temporary ones are skipped, since a death marker or a just-dropped pin is
 * not a place to navigate to -- and its colour through {@code getWaypointColor().getHex()}. The name
 * is handed on exactly as Xaero stores it: the picker's "(From XMM)" note is a row decoration drawn
 * from the destination's source id, never part of the name, so the route instructions, the spoken
 * announcements and the search all still see the bare name.
 */
public final class XaeroWaypointSource implements DestinationSource {

    public static final String ID = "xaero_waypoint";

    /** The mod id of Xaero's Minimap, which owns the waypoint classes. */
    private static final String MINIMAP_MOD_ID = "xaerominimap";
    private static final String MINIMAP_SESSION_CLASS = "xaero.common.XaeroMinimapSession";
    private static final String WAYPOINTS_MANAGER_CLASS =
            "xaero.common.minimap.waypoints.WaypointsManager";
    private static final String WAYPOINT_WORLD_CLASS = "xaero.common.minimap.waypoints.WaypointWorld";
    private static final String WAYPOINT_SET_CLASS = "xaero.common.minimap.waypoints.WaypointSet";
    private static final String WAYPOINT_CLASS = "xaero.common.minimap.waypoints.Waypoint";
    private static final String WAYPOINT_COLOR_CLASS = "xaero.hud.minimap.waypoint.WaypointColor";

    /** Whether the reflective handles have been looked up; the lookup happens once. */
    private static boolean resolved;
    private static boolean available;
    private static Class<?> waypointClass;
    private static Method getCurrentSession;
    private static Method getWaypointsManager;
    private static Method getCurrentWorld;
    private static Method getCurrentSet;
    private static Method getList;
    private static Method getDimId;
    private static Method waypointGetName;
    private static Method waypointGetX;
    private static Method waypointGetZ;
    private static Method waypointIsTemporary;
    private static Method waypointGetWaypointColor;
    private static Method waypointColorGetHex;

    /** Guards the one-off log lines, so a failure is reported once rather than every frame. */
    private static boolean warnedUnavailable;
    private static boolean warnedDimension;

    @Override
    public String id() {
        return ID;
    }

    @Override
    public boolean isAvailable() {
        resolve();
        return available;
    }

    @Override
    public String displayName() {
        return "hud.howtogo.source.xaero";
    }

    /**
     * Waypoints are destinations, not places, and are deliberately not marked.
     *
     * <p>They belong to Xaero and carry Xaero's own colours, so drawing one in this mod's place colour
     * would contradict the list it appears in. Stated rather than left to the interface default: the
     * default exists for sources that have not thought about it, and this one has.
     */
    @Override
    public boolean marksPlaces() {
        return false;
    }

    /** Last of the mod's own sources: waypoints are the least like what this mod is about. */
    @Override
    public int priority() {
        return 30;
    }

    @Override
    public List<Destination> destinations() {
        // Never throws: this runs while the picker is open, so anything unexpected has to come back
        // as "nothing to offer" rather than as a crashed frame.
        try {
            if (!isAvailable()) {
                return List.of();
            }
            Object session = getCurrentSession.invoke(null);
            if (session == null) {
                // The Minimap is loaded but no world is open yet; not an error, and not worth a line.
                return List.of();
            }
            Object manager = getWaypointsManager.invoke(session);
            if (manager == null) {
                return List.of();
            }
            Object world = getCurrentWorld.invoke(manager);
            if (world == null) {
                return List.of();
            }
            if (!sameDimension(world)) {
                return List.of();
            }
            Object set = getCurrentSet.invoke(world);
            if (set == null) {
                return List.of();
            }
            Object list = getList.invoke(set);
            if (!(list instanceof List<?> waypoints)) {
                return List.of();
            }

            List<Destination> result = new ArrayList<>(waypoints.size());
            for (Object waypoint : waypoints) {
                if (!waypointClass.isInstance(waypoint)) {
                    // The getters were looked up on the waypoint class, so anything that is not one is
                    // left alone rather than risking an invoke against a type that cannot answer.
                    // Checked by assignment rather than by exact class, so a Minimap that models a
                    // special kind of waypoint as a subclass is still read.
                    continue;
                }
                String name = (String) waypointGetName.invoke(waypoint);
                if (name == null || name.isBlank()) {
                    continue;
                }
                // The human asked for temporary waypoints to be ignored: a death marker or a
                // just-dropped pin is not a place to navigate to.
                if ((Boolean) waypointIsTemporary.invoke(waypoint)) {
                    continue;
                }
                int x = (Integer) waypointGetX.invoke(waypoint);
                int z = (Integer) waypointGetZ.invoke(waypoint);
                // Y is deliberately ignored: waypoints carry the height they were made at and
                // destinations are 2D, so the coordinate shown and routed to is the column.
                result.add(new Destination(name, x, 0, z, ID, displayColor(waypoint)));
            }
            return result;
        } catch (ReflectiveOperationException | RuntimeException e) {
            // ReflectiveOperationException covers both a handle that will not invoke and a lookup
            // that fails late; RuntimeException covers a Minimap that answers with an unexpected
            // shape, such as a null where a primitive was expected. All of them mean the same thing
            // here: no waypoints from this source.
            warnUnavailable(e);
            return List.of();
        }
    }

    /**
     * The waypoint's own colour as the picker wants it, or {@link Destination#NO_COLOR}.
     *
     * <h2>Which getter, and why not {@code getColor()}</h2>
     * The Minimap has three colour getters and only one of them is a colour. Read out of its
     * bytecode:
     * <ul>
     *   <li>{@code getColor()} returns <b>the enum ordinal</b>, not a colour at all -- it is
     *       {@code getWaypointColor().ordinal()}, used as the index the GUI lists colours by;</li>
     *   <li>{@code getActualColor()} returns a field of the same kind, set from that same ordinal
     *       (the {@code int} constructor writes its index argument straight into it);</li>
     *   <li>{@code getWaypointColor().getHex()} is the colour, and is what the Minimap's own
     *       renderer draws with -- {@code WaypointMapRenderer.renderElement} calls it and then
     *       extracts channels as {@code (hex >> 16) & 0xFF} for red.</li>
     * </ul>
     *
     * <h2>Format</h2>
     * The enum constants are standard Minecraft text colours packed as <b>ARGB with full alpha</b>:
     * BLACK is {@code -16777216} = {@code 0xFF000000}, DARK_AQUA {@code -16733526} = {@code 0xFF00AAAA},
     * DARK_RED {@code -5636096} = {@code 0xFFAA0000}, and so on. They are converted defensively
     * anyway -- masked to the low 24 bits and given full alpha -- so a future release that packs
     * them without alpha, or with a different one, still yields a visible colour instead of an
     * invisible one. A colour that comes back as bare {@code 0} is treated as none, which also keeps
     * this from ever storing the sentinel by accident.
     */
    private static int displayColor(Object waypoint) {
        try {
            Object colour = waypointGetWaypointColor.invoke(waypoint);
            if (colour == null) {
                return Destination.NO_COLOR;
            }
            int hex = (Integer) waypointColorGetHex.invoke(colour);
            return (hex & 0xFFFFFF) == 0 ? Destination.NO_COLOR : (hex & 0xFFFFFF) | 0xFF000000;
        } catch (ReflectiveOperationException | RuntimeException e) {
            // A colour is decoration; losing it must not cost the waypoint.
            return Destination.NO_COLOR;
        }
    }

    /**
     * Whether the world Xaero is showing is the dimension the player is actually in.
     *
     * <p>If they disagree, the waypoints in hand belong to somewhere the player is not, so they are
     * withheld rather than offered as a route across dimensions.
     */
    private static boolean sameDimension(Object world) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft == null ? null : minecraft.level;
        if (level == null) {
            return false;
        }
        Object dimension;
        try {
            dimension = getDimId.invoke(world);
        } catch (IllegalAccessException | InvocationTargetException e) {
            warnUnavailable(e);
            return false;
        }
        if (dimension == null) {
            return false;
        }
        ResourceKey<Level> expected = level.dimension();
        if (expected.equals(dimension)) {
            return true;
        }
        if (!warnedDimension) {
            warnedDimension = true;
            HowToGo.LOGGER.warn("[HowToGo] Xaero waypoints are for {} but the player is in {}; "
                            + "the waypoint source is skipped until they agree",
                    String.valueOf(dimension), String.valueOf(expected));
        }
        return false;
    }

    /**
     * Looks every class and method up once and caches the result.
     *
     * <p>Guarded rather than re-run per call: this is read for every frame the picker is open, and
     * class loading is far too expensive for that. A failure here is permanent for the session --
     * a missing class does not appear later -- so one attempt is enough.
     */
    private static synchronized void resolve() {
        if (resolved) {
            return;
        }
        resolved = true;
        try {
            if (!FabricLoader.getInstance().isModLoaded(MINIMAP_MOD_ID)) {
                return;
            }
            getCurrentSession = Class.forName(MINIMAP_SESSION_CLASS)
                    .getMethod("getCurrentSession");
            waypointClass = Class.forName(WAYPOINT_CLASS);
            getWaypointsManager = findMethod(MINIMAP_SESSION_CLASS, "getWaypointsManager");
            getCurrentWorld = findMethod(WAYPOINTS_MANAGER_CLASS, "getCurrentWorld");
            getCurrentSet = findMethod(WAYPOINT_WORLD_CLASS, "getCurrentSet");
            getList = findMethod(WAYPOINT_SET_CLASS, "getList");
            getDimId = findMethod(WAYPOINT_WORLD_CLASS, "getDimId");
            waypointGetName = findMethod(WAYPOINT_CLASS, "getName");
            waypointGetX = findMethod(WAYPOINT_CLASS, "getX");
            waypointGetZ = findMethod(WAYPOINT_CLASS, "getZ");
            waypointIsTemporary = findMethod(WAYPOINT_CLASS, "isTemporary");
            waypointGetWaypointColor = findMethod(WAYPOINT_CLASS, "getWaypointColor");
            waypointColorGetHex = findMethod(WAYPOINT_COLOR_CLASS, "getHex");
            available = true;
            HowToGo.LOGGER.info("[HowToGo] Xaero waypoint source bound reflectively ({})",
                    MINIMAP_SESSION_CLASS);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            available = false;
            warnUnavailable(e);
        }
    }

    /**
     * The named method from the class or the nearest class above it that declares it.
     *
     * <p>{@code getDimId} is declared on {@code MinimapWorld} rather than on the {@code WaypointWorld}
     * the chain hands back, and {@code Class.getMethod} would find that anyway -- but walking the
     * hierarchy explicitly keeps the intent visible and covers a method moving between those two
     * classes in a later Minimap release.
     *
     * <p>No {@code setAccessible} call: every method wanted here is public, and asking for privileged
     * access to a method that does not need it can only add a way to fail.
     */
    private static Method findMethod(String className, String methodName)
            throws ReflectiveOperationException {
        Class<?> type = Class.forName(className);
        NoSuchMethodException failure = null;
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                return current.getMethod(methodName);
            } catch (NoSuchMethodException e) {
                failure = e;
            }
        }
        throw failure == null ? new NoSuchMethodException(className + "#" + methodName) : failure;
    }

    /** Reports a failure once, then stays quiet. */
    private static void warnUnavailable(Throwable cause) {
        if (warnedUnavailable) {
            return;
        }
        warnedUnavailable = true;
        HowToGo.LOGGER.warn("[HowToGo] Xaero waypoint source unavailable ({}); "
                + "the picker will show only this mod's own places", cause.toString());
    }
}
