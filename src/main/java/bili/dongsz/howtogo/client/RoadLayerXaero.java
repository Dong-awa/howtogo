package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.HowToGo;
import xaero.map.WorldMap;
import xaero.map.element.MapElementRenderHandler;

/** Names Xaero's types; loaded after RoadLayer's presence check, so the client starts without World Map. */
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