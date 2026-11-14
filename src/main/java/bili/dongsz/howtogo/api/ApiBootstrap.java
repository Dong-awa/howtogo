package bili.dongsz.howtogo.api;

import bili.dongsz.howtogo.HowToGo;
// NeoForge posts through a central bus; Fabric has no bus, so only this side needs it.
//? if neoforge {
import net.neoforged.neoforge.common.NeoForge;
//?}

/**
 * Posts HowToGoRegistrationEvent once, on the first client tick. Called by this mod's own tick listener, so it is not addon API. The guard is a plain field read and write on the client thread; posting every tick would re-run each addon handler 60 times a second.
 */
public final class ApiBootstrap {

    private static boolean fired;

    private ApiBootstrap() {
    }

    /** Posts the registration event on the first call only. */
    public static void fireOnce() {
        if (fired) {
            return;
        }
        fired = true;
        try {
            //? if fabric {
            /*HowToGoRegistrationEvent.EVENT.invoker().onRegistration(new HowToGoRegistrationEvent());
            *///?} else {
            NeoForge.EVENT_BUS.post(new HowToGoRegistrationEvent());
            //?}
            HowToGo.LOGGER.info("[HowToGo] api | v{} ready; {} destination source(s) and {} self "
                            + "check(s) contributed by other mods",
                    HowToGoApi.API_VERSION, DestinationSources.registeredCount(),
                    SelfChecks.count());
        } catch (Throwable threw) {
            // The bus reports listener failures itself; this catch keeps a broken addon handler from killing the client.
            HowToGo.LOGGER.error("[HowToGo] api | posting the registration event failed", threw);
        }
    }
}
