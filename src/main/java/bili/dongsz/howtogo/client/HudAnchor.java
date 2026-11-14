package bili.dongsz.howtogo.client;

/**
 * The screen corner the compact navigation card hangs from.
 * Only the compact card is configurable. Default is bottom-left, the edge least likely to
 * be covered by a minimap (Xaero defaults to top-left); a corner plus offset keeps the card
 * off the resized edges that absolute coordinates would leave it on.
 * Ids follow MapDisplayMode: lower case with underscores on disk, constant names accepted
 * case-insensitively.
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

    public String id() {
        return id;
    }

    public boolean isRight() {
        return this == TOP_RIGHT || this == BOTTOM_RIGHT;
    }

    public boolean isBottom() {
        return this == BOTTOM_LEFT || this == BOTTOM_RIGHT;
    }

    /**
     * The corner an id names, or null when it names none.
     * null rather than the default so the config reader decides; unknown ids from a newer
     * version read the same way and fall back to defaultAnchor().
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

    /** The corner used when nothing is configured: bottom-left, opposite Xaero's default. */
    public static HudAnchor defaultAnchor() {
        return BOTTOM_LEFT;
    }
}
