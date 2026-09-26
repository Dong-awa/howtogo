package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.HowToGo;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.event.ClientTickEvent;
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

    public static void onClientTick(ClientTickEvent.Post event) {
        if (attempted) {
            return;
        }
        if (!ModList.get().isLoaded("xaeroworldmap")) {
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
