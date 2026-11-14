package bili.dongsz.howtogo.route;

import bili.dongsz.howtogo.road.PlaceKind;

/**
 * A navigable place: a name and a position a route can end at.
 * @param name display name only; instructions, HUD and search read it
 * @param source id of the DestinationSource this came from
 * @param color ARGB colour for the name, or NO_COLOR
 * @param kind place kind, read by the map filters
 * @param placeId source-stable id, or null when it has none
 */
public record Destination(String name, int x, int y, int z, String source, int color,
                          PlaceKind kind, String placeId) {

    /** Sentinel for "no colour of its own"; zero cannot collide with a drawable colour. */
    public static final int NO_COLOR = 0;

    /** Convenience constructor for places with no colour; an ordinary landmark. */
    public Destination(String name, int x, int y, int z, String source) {
        this(name, x, y, z, source, NO_COLOR, PlaceKind.PLACE, null);
    }

    /** The same, with a colour of its own. */
    public Destination(String name, int x, int y, int z, String source, int color) {
        this(name, x, y, z, source, color, PlaceKind.PLACE, null);
    }

    /** The same, with a kind and no source-stated id; PlaceKeys.stableId derives one. */
    public Destination(String name, int x, int y, int z, String source, int color, PlaceKind kind) {
        this(name, x, y, z, source, color, kind, null);
    }

    /** Whether this place has a colour of its own. */
    public boolean hasColor() {
        return color != NO_COLOR;
    }

    /** Compact "x, z" label for lists and the HUD. */
    public String coordinates() {
        return x + ", " + z;
    }

    /** This destination with a stable id attached, for a source that learns it after construction. */
    public Destination withPlaceId(String id) {
        return new Destination(name, x, y, z, source, color, kind, id);
    }
}
