package bili.dongsz.howtogo.route;

/**
 * A navigable place: something with a name and a position that a route can end at.
 *
 * @param name   display name, and only that: route instructions, spoken announcements, the HUD and
 *               the search all read this, so no source may decorate it
 * @param x      block X
 * @param y      block Y
 * @param z      block Z
 * @param source id of the {@link DestinationSource} this came from
 * @param color  ARGB colour to draw the name in, or {@link #NO_COLOR} for the picker's own
 */
public record Destination(String name, int x, int y, int z, String source, int color) {

    /**
     * "No colour of its own", as a sentinel rather than a null.
     *
     * <p>Zero cannot collide with a real colour: a fully transparent one would be invisible, so no
     * source has any reason to supply it.
     */
    public static final int NO_COLOR = 0;

    /**
     * The places that have no colour to bring: this mod's own landmarks and points picked on the map.
     *
     * <p>Kept so those call sites did not have to learn about colour at all, and so a future source
     * can supply one by reaching for the canonical constructor instead.
     */
    public Destination(String name, int x, int y, int z, String source) {
        this(name, x, y, z, source, NO_COLOR);
    }

    /** Whether this place asked to be drawn in a colour of its own. */
    public boolean hasColor() {
        return color != NO_COLOR;
    }

    /** Compact "x, z" label for lists and the HUD. */
    public String coordinates() {
        return x + ", " + z;
    }
}
