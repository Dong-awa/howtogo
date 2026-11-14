package bili.dongsz.howtogo.transit;

import java.util.Locale;

/**
 * Which way along a line a rider may travel.
 *
 * <h2>Why a line has this at all</h2>
 * A line used to be a service in both senses at once: the order of {@link TransitLine#stops()} said
 * what the line calls at, and the planner was free to ride those stops in either direction, because a
 * train that runs from A to B also runs from B to A. That is true of most lines and false of a real
 * one: a one-way loop, a ferry that only crosses one way before the evening, a bus on a pair of
 * one-way streets. A player who built such a line could describe it and the mod would then route
 * journeys over it that the line cannot actually carry, which is the one thing a planner must not do.
 *
 * <h2>Why the order is the direction</h2>
 * The stops are already stored in travel order -- see {@link TransitLine} -- so there is nothing to
 * add but the decision not to go back along it. A one-way line is travelled from the first stop in
 * the list to the last and nowhere else; a two-way line is travelled either way, which is what every
 * line was before this existed and what a line without a direction still is.
 *
 * <h2>Why this is not {@code RoadDirection}</h2>
 * A road's one-way is a property of each piece of the road, with two possible directions that depend
 * on which end of the piece a traveller arrives at -- see {@link bili.dongsz.howtogo.road.RoadDirection}.
 * A line has one order and no geometry of its own, so its answer is a switch rather than a bearing,
 * and a line whose direction was expressed as a road's would be a line that could run backwards on
 * half of itself.
 */
public enum LineDirection {

    /**
     * Travel in either order along the stops, which is what every line is until it is told otherwise.
     *
     * <p>The default for the same reason {@code RoadDirection.TWO_WAY} is: it is the only value that
     * cannot invent a restriction nobody asked for, so a line file written before this existed reads
     * back as the line it was.
     */
    TWO_WAY,

    /**
     * Travel only from the first stop in the list to the last.
     *
     * <p>The list order is the direction, which is why there is no second one-way value here: a player
     * who drew the line the other way round moves its stops, and the map draws what was drawn rather
     * than quietly reversing a line to match a flag.
     */
    ONE_WAY;

    /** Whether travel is restricted to the list's own order. */
    public boolean isOneWay() {
        return this == ONE_WAY;
    }

    /** Stable identifier, used for the saved file and for lang keys rather than the constant name. */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Parses an id, falling back to {@link #TWO_WAY} for anything unrecognised.
     *
     * <p>{@code TWO_WAY} is the right fallback because it is the only value that cannot be wrong: a
     * file with a direction this version does not know is a file written by a later one, and reading
     * it as unrestricted travel loses a restriction rather than inventing one in a direction nobody
     * chose.
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
