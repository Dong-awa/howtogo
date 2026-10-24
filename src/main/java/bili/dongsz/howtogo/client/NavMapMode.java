package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.HowToGo;
import bili.dongsz.howtogo.RoadConfig;
import bili.dongsz.howtogo.client.xaero.XaeroMinimapBridge;

/**
 * Which map display mode is actually in force, and why.
 *
 * <p>The config says which mode the player wants; this class says which one is possible. The two
 * differ in exactly one case, and it is the case the whole fallback exists for: Xaero's Minimap has
 * been chosen but cannot take the map half, either because it is not installed or because this build
 * has no integration for it. Then the in-HUD map is used, and the reason is written to the log once
 * -- not once a frame, and not silently.
 *
 * <h2>Why this is a class and not two lines in the HUD renderer</h2>
 * The same answer is asked for by two different consumers: {@link NavHudRenderer}, which must decide
 * whether to draw a map or a compact card, and the Xaero overlay itself, which only draws while the
 * mode is really in force. A single resolution point means the two can never disagree -- a half
 * second where the panel has stopped drawing its map and Xaero has not started would be a screen
 * with no map on it at all -- and the fallback line is written once rather than by whichever caller
 * happened to notice first.
 *
 * <h2>The window before the question is settled</h2>
 * Xaero's Minimap builds its minimap handler during its own client initialisation, which happens
 * after this mod is initialised, so for the first moments of a session "chosen but not yet possible"
 * is not the same thing as "chosen and unavailable". {@link XaeroMinimapBridge#isSettled()} tells the
 * two apart, and this class reports the in-HUD map during that window without calling it a fallback:
 * a warning that something has fallen back, printed a moment before it comes up, is worse than no
 * warning at all.
 *
 * <h2>When the mode can change</h2>
 * Per frame, deliberately: NeoForge's config screen writes the spec's values in place, so a player
 * switching the mode there sees the other one on the next frame without a restart or a reload of the
 * world. On Fabric the JSON file is read when the client starts, so the switch there is a restart;
 * that is the same as every other entry in that file and is the config's behaviour rather than this
 * class's.
 */
public final class NavMapMode {

    /**
     * The mode last reported to the log, so a session writes one line per change rather than one per
     * frame. {@code null} until something has been reported.
     */
    private static MapDisplayMode reported;

    private NavMapMode() {
    }

    /**
     * The display mode a frame should be drawn in.
     *
     * <p>Cheap by construction -- one config read and one boolean -- because the HUD asks once per
     * frame and the answer is the same all frame.
     */
    public static MapDisplayMode effective() {
        if (RoadConfig.mapDisplayMode() != MapDisplayMode.XAERO_MINIMAP) {
            return MapDisplayMode.HUD;
        }
        // Registered rather than merely chosen: see the class javadoc. While Xaero is still coming up
        // this is false and the panel keeps its map, which is the safe half of the transition.
        return XaeroMinimapBridge.isRegistered() ? MapDisplayMode.XAERO_MINIMAP : MapDisplayMode.HUD;
    }

    /**
     * Drives the overlay's registration and writes the one line that says what happened.
     *
     * <p>Called from the client tick. The bridge is polled first, so the answer this tick sees is the
     * answer the frame after it will draw with.
     */
    public static void onClientTick() {
        XaeroMinimapBridge.onClientTick();
        MapDisplayMode mode = effective();
        if (mode == reported) {
            return;
        }
        // Nothing to report while Xaero is still coming up and nothing is being denied: the panel is
        // drawing its map because that is the mode in force, not because of a fallback.
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
            // The one line this class exists for. A player who asked for the minimap mode and got the
            // panel instead must be able to find out why from the log alone.
            HowToGo.LOGGER.warn("[HowToGo] map display mode xaero_minimap is unavailable ({}); "
                    + "falling back to the in-HUD navigation map", XaeroMinimapBridge.diagnosis());
        }
    }

    /**
     * The corner and offsets the compact card hangs from, read through the config.
     *
     * <p>Delegated so the HUD's renderers never read the config keys themselves: the keys' names and
     * ranges are the config's business, and {@link HudAnchor} is the type every caller wants.
     */
    public static HudAnchor compactAnchor() {
        return RoadConfig.compactHudAnchor();
    }

    /** The horizontal offset from the anchor's edge, in pixels. See {@link #compactAnchor()}. */
    public static int compactOffsetX() {
        return RoadConfig.compactHudOffsetX();
    }

    /** The vertical offset from the anchor's edge, in pixels. See {@link #compactAnchor()}. */
    public static int compactOffsetY() {
        return RoadConfig.compactHudOffsetY();
    }
}
