package bili.dongsz.howtogo.client.xaero;

import bili.dongsz.howtogo.HowToGo;
//? if fabric {
/*import net.fabricmc.loader.api.FabricLoader;
*///?} else {
import net.neoforged.fml.ModList;
//?}

import java.lang.reflect.Method;

/**
 * The seam between this mod and the optional Xaero's Minimap integration.
 * Xaero's minimap element API is version-specific and not something every node can compile against, so this class -- compiled everywhere -- reaches the integration by reflection, as this mod reaches MTR, Create and RoadWeaver. A node without the jar loses only the overlay.
 * diagnosis() keeps the three failure cases apart in words because a player who selected the mode must be able to tell "not installed" from "built without the jar" from "registration failed".
 * Registration is attempted from the client tick, never a render pass; one attempt per tick until it succeeds or fails, then it is one boolean read.
 */
public final class XaeroMinimapBridge {

    /** The mod id of Xaero's Minimap, which is not the world map's id. */
    private static final String MINIMAP_MOD_ID = "xaerominimap";

    /** The integration's entry class, compiled only on the nodes that had a minimap jar. */
    private static final String LAYER_CLASS =
            "bili.dongsz.howtogo.client.xaero.minimap.XaeroMinimapRouteLayer";

    /** The one method of it this class calls. */
    private static final String REGISTER_METHOD = "tryRegister";

    /**
     * Ticks to keep asking before calling it a failure. Ten seconds is a hundredfold margin over Xaero's own client init and short enough to get the reason into the log; an unbounded wait would leave the in-HUD map in use silently.
     */
    private static final int GIVE_UP_TICKS = 200;

    /** Ticks spent asking so far; counts only the ones where asking was possible. */
    private static int attempts;

    /** Resolved once: present when the build compiled the integration in, missing when it did not. */
    private static Method register;

    /** Whether register has been looked for; separate from the field so a miss is not retried. */
    private static boolean looked;

    /** Set once the integration has registered its renderer; the mode router reads this every frame. */
    private static volatile boolean registered;

    /** Set once there is no point trying again, with diagnosis saying why. */
    private static volatile boolean failed;

    /** Why the overlay is not available, in one readable sentence. */
    private static volatile String diagnosis = "registration has not been attempted yet";

    private XaeroMinimapBridge() {
    }

    /**
     * Polls for registration, and does nothing at all once the question is settled. Called from the client tick on both loaders, never from a render pass: registering mid-frame would mutate a list the frame may already be iterating.
     */
    public static void onClientTick() {
        if (registered || failed) {
            return;
        }
        if (!isMinimapLoaded()) {
            failed = true;
            diagnosis = "Xaero's Minimap is not installed";
            return;
        }
        Method entry = entryPoint();
        if (entry == null) {
            // The mod is there but this build has no integration for it; a class cannot start existing during a session.
            failed = true;
            diagnosis = "this build was compiled without Xaero's Minimap support"
                    + " (no minimap jar in libs/ for this node)";
            return;
        }
        if (++attempts > GIVE_UP_TICKS) {
            failed = true;
            diagnosis = "Xaero's Minimap did not finish initialising its minimap in time";
            HowToGo.LOGGER.warn("[HowToGo] Xaero's Minimap is installed but its minimap never became "
                    + "available; the navigation overlay will not be registered");
            return;
        }
        try {
            Object answer = entry.invoke(null);
            if (Boolean.TRUE.equals(answer)) {
                registered = true;
                diagnosis = "registered with Xaero's Minimap";
            }
            // false means Xaero has not finished building its minimap yet; the tick tries again.
        } catch (Throwable t) {
            // NoClassDefFoundError included: the integration can be present and still fail to link against the installed Xaero.
            failed = true;
            diagnosis = "registering with Xaero's Minimap failed: " + t;
            HowToGo.LOGGER.warn("[HowToGo] could not register the navigation overlay with "
                    + "Xaero's Minimap; the in-HUD map will be used", t);
        }
    }

    /** Whether the overlay's renderer is registered and drawing, read per frame by the mode router. */
    public static boolean isRegistered() {
        return registered;
    }

    /**
     * Whether the question has an answer, one way or the other. While false the overlay may still come up, so the mode router keeps the in-HUD map without calling it a fallback.
     */
    public static boolean isSettled() {
        return registered || failed;
    }

    /** Why the overlay is unavailable, or that it is registered. One sentence, for the log. */
    public static String diagnosis() {
        return diagnosis;
    }

    /** Whether Xaero's Minimap itself is loaded, asked of whichever loader this build runs on. */
    private static boolean isMinimapLoaded() {
        //? if fabric {
        /*return FabricLoader.getInstance().isModLoaded(MINIMAP_MOD_ID);
        *///?} else {
        return ModList.get().isLoaded(MINIMAP_MOD_ID);
        //?}
    }

    /**
     * The integration's registration method, or null when this build does not carry it. Looked for once; NoClassDefFoundError is caught as well as a missing class.
     */
    private static Method entryPoint() {
        if (!looked) {
            looked = true;
            try {
                register = Class.forName(LAYER_CLASS).getMethod(REGISTER_METHOD);
            } catch (Throwable missing) {
                register = null;
            }
        }
        return register;
    }
}
