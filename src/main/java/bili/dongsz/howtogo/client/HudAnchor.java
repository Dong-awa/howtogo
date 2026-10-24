package bili.dongsz.howtogo.client;

/**
 * The screen corner the compact navigation card hangs from.
 *
 * <p>Only the compact card has a configurable corner, because only it has a reason to move: the full
 * navigation panel is a fixed card in the top-right and has been that since it existed, while the
 * compact one is a small card that shares the screen with whatever map the player has arranged around
 * it. The default is the bottom-left: the edge of the screen least likely to be covered by a minimap
 * -- Xaero's own default is the top-left, so the two stand at opposite ends of the screen from each
 * other, and the bottom edge keeps the card clear of everything at eye level. A player whose chat
 * history is long or whose Xaero's Minimap has been moved there is the reason the corner is a setting
 * rather than this default.
 *
 * <p>An enum rather than two free coordinates: a card positioned by a corner and an offset inside it
 * keeps the same distance from the edge on any window size and any GUI scale, which is what a HUD
 * element has to do. A pair of absolute screen coordinates would put the card in the wrong place the
 * moment the window is resized, and would put it off the screen on a smaller one.
 *
 * <p>The ids follow the same convention as {@link MapDisplayMode}: lower case with underscores for
 * the JSON arm, constant names accepted in any case for hand-edited files.
 */
public enum HudAnchor {

    TOP_LEFT("top_left"),
    TOP_RIGHT("top_right"),
    BOTTOM_LEFT("bottom_left"),
    BOTTOM_RIGHT("bottom_right");

    private final String id;

    HudAnchor(String id) {
        this.id = id;
    }

    /** The spelling this corner is written as in a config file. */
    public String id() {
        return id;
    }

    /** Whether the card hangs from the right edge rather than the left. */
    public boolean isRight() {
        return this == TOP_RIGHT || this == BOTTOM_RIGHT;
    }

    /** Whether the card hangs from the bottom edge rather than the top. */
    public boolean isBottom() {
        return this == BOTTOM_LEFT || this == BOTTOM_RIGHT;
    }

    /**
     * The corner an id names, or {@code null} when it names none.
     *
     * <p>{@code null} rather than the default, so the config's reader can decide: a misspelled entry
     * costs the player the default corner, which {@link #defaultAnchor()} names, and a value written
     * by a newer version is read the same way.
     */
    public static HudAnchor byId(String id) {
        if (id == null) {
            return null;
        }
        for (HudAnchor anchor : values()) {
            if (anchor.id.equalsIgnoreCase(id) || anchor.name().equalsIgnoreCase(id)) {
                return anchor;
            }
        }
        return null;
    }

    /**
     * The corner the compact card uses when nothing has been configured.
     *
     * <p>Bottom-left: the opposite end of the screen from Xaero's Minimap's own default corner, so a
     * fresh install of both does not stack the card on the map it is describing. See the class's own
     * note for why it is a setting as well as a default.
     */
    public static HudAnchor defaultAnchor() {
        return BOTTOM_LEFT;
    }
}
