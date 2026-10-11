package bili.dongsz.howtogo.api;

import bili.dongsz.howtogo.HowToGo;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Posts {@link HowToGoRegistrationEvent} once, on the first client tick.
 *
 * <p>Called by this mod's own tick listener, so nothing here is part of the addon API: an addon either
 * receives the event or calls the registries directly, and never has to know this class exists.
 *
 * <h2>Why once, and why guarded here rather than in the event</h2>
 * A tick listener runs every tick, and posting the event on each of them would re-run every addon's
 * handler sixty times a second -- which the id checks would turn into a log full of refusals rather
 * than into a bug, but a log full of refusals is a bug of its own. The guard is a plain field read and
 * write on the client thread, which is the only thread that calls it.
 */
public final class ApiBootstrap {

    private static boolean fired;

    private ApiBootstrap() {
    }

    /** Posts the registration event on the first call and does nothing on every later one. */
    public static void fireOnce() {
        if (fired) {
            return;
        }
        fired = true;
        try {
            NeoForge.EVENT_BUS.post(new HowToGoRegistrationEvent());
            HowToGo.LOGGER.info("[HowToGo] api | v{} ready; {} destination source(s) and {} self "
                            + "check(s) contributed by other mods",
                    HowToGoApi.API_VERSION, DestinationSources.registeredCount(),
                    SelfChecks.count());
        } catch (Throwable threw) {
            // The event bus reports listener failures itself; this catch is for the bus failing, and
            // either way a broken addon handler must not take the client down on its first tick.
            HowToGo.LOGGER.error("[HowToGo] api | posting the registration event failed", threw);
        }
    }
}
