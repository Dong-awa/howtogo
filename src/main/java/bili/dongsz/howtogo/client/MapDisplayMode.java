package bili.dongsz.howtogo.client;

/**
 * Where the map half of navigation is drawn.
 * The panel carries a map plus a trip readout; only the map has an alternative, because
 * Xaero's Minimap already draws the same world where a player expects it. Both arrangements
 * are defensible, so it is a setting. Not taste: if XAERO_MINIMAP is chosen and Xaero cannot
 * take the map half, HUD is used and the fallback is logged -- see NavMapMode.
 * The id is the on-disk spelling: lower case in Fabric's JSON, constant name in NeoForge's
 * spec; both are accepted case-insensitively.
 */
public enum MapDisplayMode {

    /** The panel draws the mini-map, the route and the readout itself; the declared default. */
    HUD("hud"),

    /**
     * The panel draws no map; route, turn markers and destination go on Xaero's Minimap and the card keeps only the readout.
     */
    XAERO_MINIMAP("xaero_minimap");

    private final String id;

    MapDisplayMode(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    /**
     * The mode an id names, or null when it names none.
     * Case-insensitive on id and constant name; null rather than the default so the caller can
     * decide whether to report or fall back.
     */
    public static MapDisplayMode byId(String id) {
        if (id == null) {
            return null;
        }
        for (MapDisplayMode mode : values()) {
            if (mode.id.equalsIgnoreCase(id) || mode.name().equalsIgnoreCase(id)) {
                return mode;
            }
        }
        return null;
    }

    /** The mode in force when nothing is configured: HUD, the behaviour every existing player has. */
    public static MapDisplayMode defaultMode() {
        return HUD;
    }
}
