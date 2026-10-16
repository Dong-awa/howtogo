package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.HowToGo;
//? if fabric {
/*import net.fabricmc.loader.api.FabricLoader;
*///?} else {
import net.neoforged.fml.ModList;
//?}
//? if neoforge {
import net.neoforged.neoforge.client.event.ClientTickEvent;
//?}
import xaero.map.WorldMap;
import xaero.map.element.MapElementRenderHandler;

/**
 * Wires the road layer into Xaero's world map.
 *
 * <p>{@code WorldMap.mapElementRenderHandler} is a public static field and
 * {@code MapElementRenderHandler.add} is public, so no mixin or access transformer is needed.
 * The only trick is timing: the handler does not exist until Xaero has initialised, so we poll
 * from the client tick until it shows up.
 */
public final class RoadLayer {

    private static boolean attempted;

    private RoadLayer() {
    }

    // The two loaders hand a tick callback different things: NeoForge passes an event object the
    // handler may ignore, Fabric passes the client and this one is registered as a lambda that
    // drops it. Neither is used here, so only the signature differs.
    //? if fabric {
    /*public static void onClientTick() {
    *///?} else {
    public static void onClientTick(ClientTickEvent.Post event) {
    //?}
        if (attempted) {
            return;
        }
        //? if fabric {
        /*if (!FabricLoader.getInstance().isModLoaded("xaeroworldmap")) {
        *///?} else {
        if (!ModList.get().isLoaded("xaeroworldmap")) {
        //?}
            // Should not happen (declared as a required dependency), but fail soft rather than
            // spamming the log every tick.
            attempted = true;
            HowToGo.LOGGER.error("[HowToGo] Xaero's World Map is not loaded; road layer disabled");
            return;
        }
        try {
            MapElementRenderHandler handler = WorldMap.mapElementRenderHandler;
            if (handler == null) {
                return; // Xaero has not finished initialising yet.
            }
            handler.add(new RoadElementRenderer(
                    new RoadRenderContext(),
                    new RoadElementProvider(),
                    new RoadElementReader()));
            attempted = true;
            HowToGo.LOGGER.info("[HowToGo] road layer registered with Xaero's World Map");
        } catch (Throwable t) {
            attempted = true;
            HowToGo.LOGGER.error("[HowToGo] failed to register road layer", t);
        }
    }
}
