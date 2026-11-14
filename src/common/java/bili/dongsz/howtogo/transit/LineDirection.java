package bili.dongsz.howtogo.transit;

import java.util.Locale;

/**
 * Which way along a line a rider may travel.
 * The stop order is the travel order, so a one-way line runs from the first stop to the last only; a line with no direction is two-way, as every line was before this existed.
 * Not RoadDirection: a road's one-way is per segment and depends on the end approached, while a line has one order and no geometry of its own.
 */
public enum LineDirection {

    /** Travel in either order -- the default, and what a line file without a direction reads back as. */
    TWO_WAY,

    /**
     * Travel only from the first stop in the list to the last; to reverse, the player reorders the stops.
     */
    ONE_WAY;

    /** Whether travel is restricted to the list's own order. */
    public boolean isOneWay() {
        return this == ONE_WAY;
    }

    /** Stable identifier, used in the saved file and in lang keys rather than the constant name. */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Parses an id, falling back to TWO_WAY for anything unrecognised.
     * TWO_WAY cannot be wrong: an unknown direction was written by a later version, and reading it as unrestricted loses a restriction rather than inventing one.
     */
    public static LineDirection byId(String raw) {
        if (raw != null) {
            for (LineDirection direction : values()) {
                if (direction.id().equalsIgnoreCase(raw.trim())) {
                    return direction;
                }
            }
        }
        return TWO_WAY;
    }
}
