package bili.dongsz.howtogo.client;

/**
 * Where the map half of navigation is drawn.
 *
 * <p>The navigation panel has always carried two kinds of content in one card: a small map of the
 * roads, the route and the places, and a readout of the trip -- the turn, the distance, what is
 * left. Only the first of those has an alternative. Xaero's Minimap is already drawing a map of the
 * same world in the corner of the same screen, in the same place and at the same scale a player
 * expects, so on an install that has it the panel's own map is a second map beside the first, and
 * the useful half of the panel is the readout that sat under it.
 *
 * <p>Which of the two arrangements is in force is a setting rather than a guess, because both are
 * defensible: the panel is self-contained and works with nothing else installed, and a player who
 * has Xaero's Minimap has usually arranged their screen around it. So the file holds a mode id and
 * the mod obeys it, with one rule that is not a matter of taste -- if the second mode is chosen and
 * Xaero's Minimap cannot take the map half, the first mode is used instead and the fallback is said
 * out loud in the log. See {@link NavMapMode}, which is where that decision is actually taken.
 *
 * <p>The id is the on-disk spelling rather than the constant name, for the same reason
 * {@link UiTheme.Mode} has one: the Fabric arm of the config is a hand-edited JSON file in the
 * lower-case spellings a player would type, while NeoForge's spec writes the constant name. The
 * reader accepts both, in any case, so a value copied from one file into the other still reads.
 */
public enum MapDisplayMode {

    /**
     * The navigation panel draws everything itself: the mini-map, the route on it, and the readout
     * under it. This is the mod's own behaviour and the declared default, so an install that says
     * nothing about this setting sees exactly the panel it has always seen.
     */
    HUD("hud"),

    /**
     * The panel draws no map at all. The route, the turn markers and the destination are drawn on
     * Xaero's Minimap instead, and what is left on screen is a small card carrying only the
     * information a map cannot show: the turn, the distance to it, what is left of the trip.
     */
    XAERO_MINIMAP("xaero_minimap");

    private final String id;

    MapDisplayMode(String id) {
        this.id = id;
    }

    /** The spelling this mode is written as in a config file. */
    public String id() {
        return id;
    }

    /**
     * The mode an id names, or {@code null} when it names none.
     *
     * <p>Case-insensitive on both the id and the constant name, and {@code null} rather than the
     * default for an id that matches neither: the caller is the one that knows whether an
     * unreadable value should cost the player the default mode or be reported, and the answer is
     * different for a config file that a newer version wrote than for one a player mistyped.
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

    /**
     * The mode in force when nothing has been configured.
     *
     * <p>{@link #HUD}, deliberately: it is the behaviour every existing player already has, and it
     * is the one mode that cannot be defeated by another mod being absent or changing. A player who
     * wants the other one is asking for something and will say so.
     */
    public static MapDisplayMode defaultMode() {
        return HUD;
    }
}
