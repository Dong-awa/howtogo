package bili.dongsz.howtogo.road;

import java.util.Locale;

/**
 * Which way along a single segment travel is allowed.
 *
 * <h2>Why a direction and not a flag</h2>
 * A one-way street has two possible one-way directions, and a boolean can only say one of them. With a
 * flag, "this road is one-way" would mean "one-way the way it happens to be stored", and a player who
 * drew a street from the wrong end would have to draw it again to get the direction they meant. The
 * direction the segment happens to be stored in is an artefact of which click came first, so it is the
 * wrong thing to make a player's intent depend on: this records the intent and leaves the geometry
 * alone.
 *
 * <p>{@link #FORWARD} is travel from {@link RoadSegment#fromNode()} to {@code toNode} and
 * {@link #BACKWARD} is the other way, both read from the segment's own endpoints. Nothing here depends
 * on how the road was drawn, only on which of its two ends a journey arrives at.
 *
 * <h2>What it applies to</h2>
 * Everything that travels, walking included. A street is one-way because of what it is -- a narrow
 * alley, a station exit, a one-lane bridge -- and the mod has no way to know which of those it is, so
 * guessing that pedestrians may ignore it would be inventing a rule the player did not ask for. A
 * player who wants a road that only vehicles are restricted on can draw the footpath beside it.
 */
public enum RoadDirection {

    /** Travel both ways, which is what every road is until it is told otherwise. */
    TWO_WAY,

    /** Travel only from the segment's from-node to its to-node. */
    FORWARD,

    /** Travel only from the segment's to-node to its from-node. */
    BACKWARD;

    /** Whether travel is restricted to one direction. */
    public boolean isOneWay() {
        return this != TWO_WAY;
    }

    /** The same restriction the other way round, for a caller that walks a road end to end. */
    public RoadDirection reversed() {
        return switch (this) {
            case TWO_WAY -> TWO_WAY;
            case FORWARD -> BACKWARD;
            case BACKWARD -> FORWARD;
        };
    }

    /** The next state a click on the switch moves to: two-way, one way, the other way, two-way. */
    public RoadDirection next() {
        return switch (this) {
            case TWO_WAY -> FORWARD;
            case FORWARD -> BACKWARD;
            case BACKWARD -> TWO_WAY;
        };
    }

    /** Stable identifier, used for the saved file and for lang keys rather than the constant name. */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Parses an id, falling back to {@link #TWO_WAY} for anything unrecognised.
     *
     * <p>{@code TWO_WAY} is the right fallback because it is the only value that cannot be wrong: a file
     * with a direction this version does not know is a file written by a later one, and reading it as
     * unrestricted travel loses a restriction rather than inventing one in a direction nobody chose.
     */
    public static RoadDirection byId(String raw) {
        if (raw != null) {
            for (RoadDirection direction : values()) {
                if (direction.id().equalsIgnoreCase(raw.trim())) {
                    return direction;
                }
            }
        }
        return TWO_WAY;
    }
}
