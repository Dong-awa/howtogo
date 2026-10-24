package bili.dongsz.howtogo.client.xaero.minimap;

import bili.dongsz.howtogo.HowToGo;
import bili.dongsz.howtogo.client.RoadRenderContext;
import xaero.common.HudMod;
import xaero.hud.minimap.Minimap;
import xaero.hud.minimap.element.render.over.MinimapElementOverMapRendererHandler;

/**
 * Wires the navigation overlay into Xaero's Minimap, once, from the client tick.
 *
 * <h2>The extension point</h2>
 * Xaero's Minimap exposes a public element-render pipeline of its own, and its minimap object hangs
 * the one this overlay needs off a public getter: {@code HudMod.INSTANCE} is a public static field,
 * {@code getMinimap()} returns the minimap, {@code getOverMapRendererHandler()} returns the
 * handler that draws elements over it, and {@code add} on that handler is public. No mixin, no
 * access transformer and no write to Xaero's data is needed -- the overlay is an ordinary
 * participant in a list Xaero itself walks every frame.
 *
 * <h2>Why the registration is polled</h2>
 * Xaero builds its minimap while it is initialising, which happens after this mod's own client
 * setup has run, so at the first tick the handler may not exist yet. Rather than guessing a delay,
 * the tick asks: {@link #tryRegister()} returns false while Xaero is still coming up and is called
 * again on the next tick. The attempt is idempotent -- the first success sets a flag and every
 * later call is a field read -- so the caller can call it unconditionally from the client tick
 * without deciding anything.
 *
 * <h2>Giving up</h2>
 * There has to be an end to the polling, or a Xaero build that never finishes initialising would
 * leave the display-mode router permanently undecided and no fallback ever reported. After
 * {@link #MAX_ATTEMPTS} ticks an exception is thrown instead of returning false: the always-compiled
 * bridge that calls this class through reflection treats a throw as a permanent failure, reports it
 * as one line and stops asking, whereas a false answer means "not yet" and would keep the question
 * open forever. The throw is therefore the failure report, not an error escaping into Xaero.
 *
 * <h2>What this class is not</h2>
 * It does not read the display mode and does not draw. Registration is a property of the session --
 * it succeeds when Xaero is present and stays succeeded when the player switches modes -- while
 * whether the overlay paints on a given frame is {@link XaeroMinimapRouteRenderer}'s question, asked
 * once per frame. Registering only in the minimap mode would leave the renderer missing when a
 * player switched modes, and drawing while the panel is also drawing the same route is exactly the
 * bug the mode exists to prevent.
 */
public final class XaeroMinimapRouteLayer {

    /**
     * How many client ticks the layer waits for Xaero's minimap to appear before giving up.
     *
     * <p>Two hundred ticks is about ten seconds: far longer than Xaero's initialisation takes even on
     * a slow client, and short enough that a genuinely broken install reports itself within the first
     * moments of the session rather than at some arbitrary later point. The wait itself is free --
     * three field reads per tick.
     */
    private static final int MAX_ATTEMPTS = 200;

    /** The one renderer this layer adds; non-null exactly when {@link #registered} is true. */
    private static XaeroMinimapRouteRenderer renderer;

    /**
     * Whether the renderer has been added; read by the tick, by the bridge and by diagnostics.
     *
     * <p>Volatile because the two questions are asked from different threads: registration happens
     * on the client tick thread, and {@link #isRegistered()} may be asked from a render pass. The
     * write happens once, so the only cost is the read barrier on a boolean, and the alternative --
     * a frame that does not see a registration that has already happened -- is exactly the kind of
     * once-a-session tear that is impossible to reproduce from a bug report.
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
     * <p>Safe to call every client tick, from the tick thread, and as many times as the caller
     * likes: the first call that finds Xaero ready registers, every later call is a boolean read,
     * and a call made before Xaero is ready answers false so the caller tries again.
     *
     * @return true once the renderer has been added, false while Xaero is still initialising
     * @throws IllegalStateException when {@link #MAX_ATTEMPTS} ticks have passed without Xaero
     *     becoming ready, which the reflective bridge reports as a permanent failure
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
            // Deliberately thrown rather than logged and swallowed: see the class javadoc. The
            // message is what the bridge's single warning will quote.
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

        // Built before the add so a failure to construct the renderer leaves the handler untouched;
        // after a successful add the handler owns the renderer exactly as it owns Xaero's own, and
        // there is no removal API to call on a world unload -- which is correct, because the minimap
        // lives for the whole client session.
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
     * Whether the overlay's renderer has been added to Xaero's minimap.
     *
     * <p>Separate from {@link #tryRegister()} because it is asked far more often than registration
     * is attempted, by the display-mode router on every frame, and because the answer to "is it
     * there" is not the same question as "should we try to put it there".
     */
    public static boolean isRegistered() {
        return registered;
    }

    /**
     * The renderer this layer registered, or null when it has not registered.
     *
     * <p>Held for diagnostics and for keeping the reference reachable from this mod's side; Xaero's
     * handler keeps its own reference for drawing, so this is not what makes the overlay work.
     */
    public static XaeroMinimapRouteRenderer renderer() {
        return renderer;
    }
}
