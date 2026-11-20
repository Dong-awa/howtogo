package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.HowToGo;
import bili.dongsz.howtogo.place.PlaceKeys;
import bili.dongsz.howtogo.road.PlaceKind;
import bili.dongsz.howtogo.route.Destination;
import bili.dongsz.howtogo.route.DestinationSource;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
//? if fabric {
/*import net.fabricmc.loader.api.FabricLoader;
*///?} else {
import net.neoforged.fml.ModList;
//?}

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Destinations from Xaero's own waypoints, read reflectively out of the Minimap mod.
 * Waypoint storage lives in the optional Minimap jar (xaero.common.minimap.waypoints.*), not in the World Map, so it is bound by name at runtime: this mod compiles and runs with the Minimap absent and takes no build dependency on it. When present the chain is walked once and the handles cached; when absent or renamed the source reports itself unavailable and the picker falls back to PoiDestinationSource.
 * Chain, verified against xaerominimap-neoforge-1.21.1-25.3.13.jar: XaeroMinimapSession.getCurrentSession().getWaypointsManager().getCurrentWorld().getCurrentSet().getList().
 * Each waypoint answers getName(), getX(), getZ(), isTemporary() (skipped) and getWaypointColor().getHex(). The name is handed on exactly as stored: the "(From XMM)" note is a row decoration drawn from the source id, never part of the name.
 */
public final class XaeroWaypointSource implements DestinationSource {

    public static final String ID = "xaero_waypoint";

    /** Mod id of Xaero's Minimap, which owns the waypoint classes. */
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
     * Waypoints are destinations, not places, and are deliberately not marked: they belong to Xaero and carry Xaero's own colours, so this mod's place colour would contradict the list they appear in.
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
        // Never throws: this runs while the picker is open, so anything unexpected must come back
        try {
            if (!isAvailable()) {
                return List.of();
            }
            Object session = getCurrentSession.invoke(null);
            if (session == null) {
                // The Minimap is loaded but no world is open yet; not an error, and not worth
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
                    // The getters were looked up on the waypoint class, so anything else
                    continue;
                }
                String name = (String) waypointGetName.invoke(waypoint);
                if (name == null || name.isBlank()) {
                    continue;
                }
                // Temporary waypoints are ignored: a death marker or a just-dropped pin is not
                if ((Boolean) waypointIsTemporary.invoke(waypoint)) {
                    continue;
                }
                int x = (Integer) waypointGetX.invoke(waypoint);
                int z = (Integer) waypointGetZ.invoke(waypoint);
                // Y is deliberately ignored: waypoints carry the height they were made
                result.add(new Destination(name, x, 0, z, ID, displayColor(waypoint),
                        PlaceKind.PLACE, PlaceKeys.xaeroId(name, x, z)));
            }
            return result;
        } catch (ReflectiveOperationException | RuntimeException e) {
            // ReflectiveOperationException covers a handle that will not invoke and a lookup
            warnUnavailable(e);
            return List.of();
        }
    }

    /**
     * The waypoint's own colour, or Destination.NO_COLOR.
     * Use getWaypointColor().getHex(): getColor() returns the colour enum's ordinal, not a colour, and getActualColor() is a field of that same kind. The constants are Minecraft text colours packed as ARGB with full alpha; the result is masked to 24 bits and given full alpha so a future packing change still yields a visible colour, and bare 0 is treated as none.
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
     * Whether the world Xaero is showing is the dimension the player is in; if not, its waypoints are withheld rather than offered as a route across dimensions.
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
     * Read every frame the picker is open, and class loading is far too expensive for that; a failure is permanent for the session, so one attempt is enough.
     */
    private static synchronized void resolve() {
        if (resolved) {
            return;
        }
        resolved = true;
        try {
            //? if fabric {
        /*if (!FabricLoader.getInstance().isModLoaded(MINIMAP_MOD_ID)) {
        *///?} else {
        if (!ModList.get().isLoaded(MINIMAP_MOD_ID)) {
        //?}
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
            HowToGo.logInfo("[HowToGo] Xaero waypoint source bound reflectively ({})",
                    MINIMAP_SESSION_CLASS);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            available = false;
            warnUnavailable(e);
        }
    }

    /**
     * The named method from the class or the nearest class above it that declares it.
     * getDimId is declared on MinimapWorld rather than the WaypointWorld the chain hands back; walking the hierarchy keeps the intent visible and survives a move between the two. No setAccessible: every method wanted is public.
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
