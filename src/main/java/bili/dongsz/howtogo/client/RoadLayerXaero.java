package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.HowToGo;
import xaero.map.WorldMap;
import xaero.map.element.MapElementRenderHandler;

/**
 * The one small part of the road layer that names Xaero's classes.
 *
 * <p>Loaded only after {@link RoadLayer} has confirmed the mod is present. Keeping these references
 * here is what lets the rest of the client start without Xaero's World Map.
 */
final class RoadLayerXaero {

    private RoadLayerXaero() {
    }

    static boolean install() {
        MapElementRenderHandler handler = WorldMap.mapElementRenderHandler;
        if (handler == null) {
            return false; // Xaero has not finished initialising yet.
        }
        handler.add(new RoadElementRenderer(
                new RoadRenderContext(),
                new RoadElementProvider(),
                new RoadElementReader()));
        HowToGo.LOGGER.info("[HowToGo] road layer registered with Xaero's World Map");
        return true;
    }
}