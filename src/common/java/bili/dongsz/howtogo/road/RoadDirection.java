package bili.dongsz.howtogo.road;

import java.util.Locale;

/**
 * Which way along a single segment travel is allowed.
 * A direction rather than a one-way flag, so the player's intent does not depend on which end the segment was drawn from. FORWARD is fromNode -> toNode, BACKWARD the reverse, both read from the segment's own endpoints.
 * Applies to everything that travels, walking included: the mod cannot tell a narrow alley from a one-lane bridge, so it does not guess an exemption. Draw a footpath for a vehicle-only restriction.
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
     * Parses an id, falling back to TWO_WAY for anything unrecognised. TWO_WAY is the only value that cannot be wrong: an unknown id from a later version must not invent a restriction in a direction nobody chose.
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
