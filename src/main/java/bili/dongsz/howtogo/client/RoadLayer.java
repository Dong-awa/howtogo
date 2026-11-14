package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.HowToGo;
//? if fabric {
/*import net.fabricmc.loader.api.FabricLoader;
*///?} else {
import net.neoforged.fml.ModList;
//?}

/** Wires the road layer into Xaero's map; no Xaero type here, so a missing mod fails soft, not hard. */
public final class RoadLayer {

    private static boolean attempted;

    private RoadLayer() {
    }

    /** Polls until Xaero has built its render handler, then installs the layer once. */
    public static void onClientTick() {
        if (attempted) {
            return;
        }
        //? if fabric {
        /*if (!FabricLoader.getInstance().isModLoaded("xaeroworldmap")) {
        *///?} else {
        if (!ModList.get().isLoaded("xaeroworldmap")) {
        //?}
            attempted = true;
            HowToGo.LOGGER.error("[HowToGo] Xaero's World Map is not loaded; road layer disabled");
            return;
        }
        try {
            if (RoadLayerXaero.install()) {
                attempted = true;
            }
        } catch (Throwable t) {
            attempted = true;
            HowToGo.LOGGER.error("[HowToGo] failed to register road layer", t);
        }
    }
}