package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.HowToGo;
//? if fabric {
/*import net.fabricmc.loader.api.FabricLoader;
*///?} else {
import net.neoforged.fml.ModList;
//?}

/**
 * Wires the road layer into Xaero's world map.
 *
 * <p>This class deliberately has no Xaero type in its signature or method body. The class that does
 * is {@link RoadLayerXaero}; it is loaded only after the loader check here has succeeded. That
 * separation is what makes a missing Xaero a soft failure: loading this class cannot resolve
 * {@code ElementRenderer}, so the first client tick reports the missing mod instead of throwing
 * {@code NoClassDefFoundError}.
 */
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