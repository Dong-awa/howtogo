package bili.dongsz.howtogo.client.xaero.minimap;

import bili.dongsz.howtogo.HowToGo;
import bili.dongsz.howtogo.client.RoadRenderContext;
import xaero.common.HudMod;
import xaero.hud.minimap.Minimap;
import xaero.hud.minimap.element.render.over.MinimapElementOverMapRendererHandler;

/**
 * Wires the navigation overlay into Xaero's Minimap, once, from the client tick.
 * Xaero exposes a public element-render pipeline (HudMod.INSTANCE, getMinimap(),
 * getOverMapRendererHandler(), add), so the overlay is an ordinary participant: no mixin,
 * no access widener.
 * Registration is polled because Xaero initialises after this mod's client setup; tryRegister
 * returns false while Xaero is coming up and is idempotent after success. After MAX_ATTEMPTS it
 * throws, because the reflective bridge treats a throw as permanent failure and stops asking,
 * whereas false means "not yet".
 * Registration is session-wide, not per mode, so switching modes keeps the renderer.
 */
public final class XaeroMinimapRouteLayer {

    /**
     * How many client ticks to wait for Xaero's minimap before giving up: 200 is about ten seconds, and the wait costs three field reads per tick.
     */
    private static final int MAX_ATTEMPTS = 200;

    /** The one renderer this layer adds; non-null exactly when registered is true. */
    private static XaeroMinimapRouteRenderer renderer;

    /**
     * Whether the renderer has been added; read by the tick, the bridge and diagnostics.
     * Volatile: written on the client tick thread and possibly read from a render pass; the write
     * happens once, so only the read barrier on a boolean costs anything.
     */
    private static volatile boolean registered;

    /** Whether the wait has ended without a registration; makes the give-up a one-time event. */
    private static boolean abandoned;

    /** How many ticks have asked so far; only counted while neither flag above is set. */
    private static int attempts;

    private XaeroMinimapRouteLayer() {
    }

    /**
     * Tries to add the overlay to Xaero's minimap, and reports whether it is now registered.
     *
     * Safe to call every client tick: the first call that finds Xaero ready registers, later calls
     * are a boolean read.
     * @return true once added, false while Xaero is still initialising
     * @throws IllegalStateException after MAX_ATTEMPTS ticks, reported as a permanent failure
     */
    public static boolean tryRegister() {
        if (registered) {
            return true;
        }
        if (abandoned) {
            return false;
        }
        attempts++;
        if (attempts > MAX_ATTEMPTS) {
            abandoned = true;
            // Thrown rather than logged and swallowed: see the class javadoc; the message is what the bridge's warning quotes.
            throw new IllegalStateException("Xaero's Minimap did not finish initialising after "
                    + MAX_ATTEMPTS + " client ticks; the navigation overlay was not registered");
        }

        HudMod mod = HudMod.INSTANCE;
        if (mod == null || !mod.isLoadedClient()) {
            return false;
        }
        Minimap minimap = mod.getMinimap();
        if (minimap == null) {
            return false;
        }
        MinimapElementOverMapRendererHandler handler = minimap.getOverMapRendererHandler();
        if (handler == null) {
            return false;
        }

        // Built before the add so a failed construction leaves the handler untouched. There is no removal API, and none is needed: the minimap lives for the session.
        XaeroMinimapRouteRenderer candidate = new XaeroMinimapRouteRenderer(
                new XaeroMinimapRouteReader(),
                new XaeroMinimapRouteProvider(),
                new RoadRenderContext());
        handler.add(candidate);
        renderer = candidate;
        registered = true;
        HowToGo.LOGGER.info("[HowToGo] navigation route overlay registered with Xaero's Minimap");
        return true;
    }

    /**
     * Whether the overlay's renderer has been added; asked every frame, hence separate from tryRegister().
     */
    public static boolean isRegistered() {
        return registered;
    }

    /** The registered renderer, or null; held for diagnostics and reachability, not for drawing. */
    public static XaeroMinimapRouteRenderer renderer() {
        return renderer;
    }
}
