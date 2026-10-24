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
 *
 * <h2>Why this is reflection and not a plain call</h2>
 * Xaero's Minimap is an <em>optional</em> mod here, and one that may be absent not only from a
 * player's install but from the build: its minimap element API is version-specific and, unlike the
 * world map's, is not something this repository can compile against on every node. The integration's
 * own classes are therefore only compiled on the nodes whose {@code libs/} holds a minimap jar, and
 * this class -- which is compiled everywhere -- cannot name them. It finds them by name at run time
 * instead, exactly as this mod reaches MTR, Create and RoadWeaver.
 *
 * <p>That also makes the dependency direction right: nothing in the compile-everywhere source refers
 * to a type that only exists when a particular jar was present at build time, so a node without the
 * jar loses the overlay and nothing else. What it loses is not silent: {@link #diagnosis()} answers
 * why the overlay is unavailable, and {@link NavMapMode} turns that into one log line when the player
 * has asked for the display mode that needs it.
 *
 * <h2>The three ways it can be unavailable, and why they are told apart</h2>
 * A player who selects the new display mode and does not get it needs to be able to tell which of
 * three quite different things is wrong:
 *
 * <ul>
 *   <li>Xaero's Minimap is not installed -- the usual case, and the one the fallback exists for;</li>
 *   <li>it is installed but this build was compiled without it (no minimap jar in {@code libs/} at
 *       build time), which is a packaging problem rather than a player's mistake;</li>
 *   <li>it is installed and this build supports it, but registering with it failed or never became
 *       possible -- which is a bug worth reporting with the log line in hand.</li>
 * </ul>
 *
 * <p>The three are kept separate in {@link #diagnosis()}, in words rather than in a status number,
 * because the only consumer is a log line a human reads.
 *
 * <h2>When registration is attempted</h2>
 * From the client tick, like the world map's road layer: Xaero builds its minimap handler during its
 * own client initialisation and the handler is not there before that. The attempt is made once per
 * tick until it succeeds or reports a permanent failure, and after that it is one boolean read, so
 * the cost of polling is a field read per tick for the rest of the session.
 */
public final class XaeroMinimapBridge {

    /** The mod id of Xaero's Minimap, which is not the world map's id. */
    private static final String MINIMAP_MOD_ID = "xaerominimap";

    /**
     * The integration's entry class, compiled only on the nodes that had a minimap jar.
     *
     * <p>Named as a string for the reason the class javadoc gives. Kept in step with the class in
     * {@code client/xaero/minimap} by hand, which is why that class's own name is written into its
     * javadoc as well.
     */
    private static final String LAYER_CLASS =
            "bili.dongsz.howtogo.client.xaero.minimap.XaeroMinimapRouteLayer";

    /** The one method of it this class calls; see the class above. */
    private static final String REGISTER_METHOD = "tryRegister";

    /**
     * How many ticks to keep asking before calling it a failure.
     *
     * <p>Xaero builds its minimap during its own client initialisation, which is a moment's work; ten
     * seconds is a hundredfold margin and still short enough that a player who selected the minimap
     * mode finds the reason in the log rather than waiting for a fallback that never gets reported.
     * Without a bound, a minimap that never appears would leave the in-HUD map in use silently -- which
     * is the one outcome the fallback rule exists to prevent.
     */
    private static final int GIVE_UP_TICKS = 200;

    /** Ticks spent asking so far; only counts the ones where asking was actually possible. */
    private static int attempts;

    /** Resolved once: present when the build compiled the integration in, missing when it did not. */
    private static Method register;

    /** Whether {@link #register} has been looked for; separate from the field so a miss is not retried. */
    private static boolean looked;

    /** Set once the integration has registered its renderer; the mode router reads this every frame. */
    private static volatile boolean registered;

    /** Set once there is no point trying again, with {@link #diagnosis} saying why. */
    private static volatile boolean failed;

    /** Why the overlay is not available, in one readable sentence; see the class javadoc. */
    private static volatile String diagnosis = "registration has not been attempted yet";

    private XaeroMinimapBridge() {
    }

    /**
     * Polls for registration, and does nothing at all once the question is settled.
     *
     * <p>Called from the client tick on both loaders. Not called from any render pass: registering a
     * renderer mid-frame would be a change to a list the frame's iterator may already be walking.
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
            // The mod is there but this build has no integration for it. One attempt was enough:
            // whether a class exists cannot change during a session.
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
            // false means Xaero has not finished building its minimap yet. Not a failure: the tick
            // tries again. The integration gives up on its own if that never happens.
        } catch (Throwable t) {
            // NoClassDefFoundError included: the integration can be present and still fail to link
            // against the installed Xaero, which is exactly the mismatch this catch is for.
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
     * Whether the question has an answer, one way or the other.
     *
     * <p>While this is false the overlay might still come up -- Xaero's own initialisation is not
     * finished -- so the mode router keeps using the in-HUD map without calling that a fallback and
     * without logging one. See {@link NavMapMode}.
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
     * The integration's registration method, or {@code null} when this build does not carry it.
     *
     * <p>Looked for once. A {@code NoClassDefFoundError} is caught as well as the missing class
     * itself: on a node without the minimap jar the class file is not in the mod at all, but a class
     * whose own supertypes fail to link reports the same way, and both mean the same thing here.
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
