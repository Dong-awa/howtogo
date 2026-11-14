package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.HowToGo;
import bili.dongsz.howtogo.RoadConfig;
import bili.dongsz.howtogo.client.xaero.XaeroMinimapBridge;

/**
 * Which map display mode is actually in force, and why.
 * Differs from the config only when Xaero's Minimap is chosen but cannot take the map half (not installed, or no integration): the HUD map is used and the reason is logged once. This is the single resolution point for NavHudRenderer and the Xaero overlay, so they cannot disagree.
 * Xaero builds its handler after this mod initialises; XaeroMinimapBridge.isSettled() separates "not yet possible" from "unavailable", and reports the HUD map without a fallback warning during that window.
 * Re-evaluated per frame: NeoForge config changes apply live; on Fabric the JSON is read at client start.
 */
public final class NavMapMode {

    /** Mode last reported to the log; null until something has been reported. */
    private static MapDisplayMode reported;

    private NavMapMode() {
    }

    /** Mode a frame should be drawn in. One config read and one boolean; asked once per frame. */
    public static MapDisplayMode effective() {
        if (RoadConfig.mapDisplayMode() != MapDisplayMode.XAERO_MINIMAP) {
            return MapDisplayMode.HUD;
        }
        // Registered rather than merely chosen; while Xaero is still coming up this is false
        return XaeroMinimapBridge.isRegistered() ? MapDisplayMode.XAERO_MINIMAP : MapDisplayMode.HUD;
    }

    /**
     * Polls the bridge and writes the one line that says what happened. Called from the client tick; polling first makes this tick's answer match the next frame's draw.
     */
    public static void onClientTick() {
        XaeroMinimapBridge.onClientTick();
        MapDisplayMode mode = effective();
        if (mode == reported) {
            return;
        }
        // Nothing to report while Xaero is still coming up: the panel draws its map as the mode
        if (mode == MapDisplayMode.HUD
                && RoadConfig.mapDisplayMode() == MapDisplayMode.XAERO_MINIMAP
                && !XaeroMinimapBridge.isSettled()) {
            return;
        }
        reported = mode;
        if (mode == MapDisplayMode.XAERO_MINIMAP) {
            HowToGo.LOGGER.info("[HowToGo] map display mode: navigation map content is drawn on "
                    + "Xaero's Minimap; the HUD carries the readout only");
        } else if (RoadConfig.mapDisplayMode() == MapDisplayMode.XAERO_MINIMAP) {
            // The one line this class exists for: a player who asked for the minimap must be able
            HowToGo.LOGGER.warn("[HowToGo] map display mode xaero_minimap is unavailable ({}); "
                    + "falling back to the in-HUD navigation map", XaeroMinimapBridge.diagnosis());
        }
    }

    /**
     * Corner and offsets the compact card hangs from, read through the config so renderers never read keys directly.
     */
    public static HudAnchor compactAnchor() {
        return RoadConfig.compactHudAnchor();
    }

    /** Horizontal offset from the anchor edge, in pixels. See compactAnchor(). */
    public static int compactOffsetX() {
        return RoadConfig.compactHudOffsetX();
    }

    /** Vertical offset from the anchor edge, in pixels. See compactAnchor(). */
    public static int compactOffsetY() {
        return RoadConfig.compactHudOffsetY();
    }
}
