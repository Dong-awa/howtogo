package bili.dongsz.howtogo.route;

import bili.dongsz.howtogo.road.PlaceKind;

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
 * @param kind   what kind of place it is, which the map's filters read: a shop may be left off a map
 *               zoomed far out where a station may not, and that is a question about the place rather
 *               than about the source it arrived from
 */
public record Destination(String name, int x, int y, int z, String source, int color,
                          PlaceKind kind) {

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
     * can supply one by reaching for the canonical constructor instead. An ordinary landmark is the
     * default kind, which is what a point picked on the map is.
     */
    public Destination(String name, int x, int y, int z, String source) {
        this(name, x, y, z, source, NO_COLOR, PlaceKind.PLACE);
    }

    /** The same, with a colour of its own, and still an ordinary landmark. */
    public Destination(String name, int x, int y, int z, String source, int color) {
        this(name, x, y, z, source, color, PlaceKind.PLACE);
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
