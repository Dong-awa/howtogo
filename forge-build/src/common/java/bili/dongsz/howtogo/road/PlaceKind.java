package bili.dongsz.howtogo.road;

import java.util.Locale;

/**
 * What kind of place a {@link RoadNode.Type#POI} node is.
 *
 * <h2>Why this is not a {@link RoadNode.Type}</h2>
 * The node type answers "what does this vertex do in the graph" -- junction, endpoint, or a place
 * that belongs to no road at all. This answers a different question, "what is this place", and the
 * two are independent: a station is a place <em>and</em> sits on a road, so it needs to be a
 * {@code POI} node that also has segments. Making these new node types would have forced a choice
 * between the two meanings and broken {@link RoadEditor#reclassifyNodes}, which deliberately leaves
 * places alone when it recomputes junction and endpoint from degree.
 *
 * <p>{@link #PLACE} is the default, and that is what makes the storage change backward compatible:
 * a file written before this existed has no field for it, reads back as {@code PLACE}, and every
 * place a player had already put down keeps meaning exactly what it meant.
 */
public enum PlaceKind {

    /** An ordinary landmark, and the default: a node that is not a place is one whose type is not POI. */
    PLACE,

    /** Somewhere resources are gathered. */
    RESOURCE,

    /** Somewhere things are bought and sold. */
    SHOP,

    /**
     * A boarding point for public transit.
     *
     * <p>Not a free choice of position: a station has to stand on a road, because a boarding point
     * that no route passes through is a boarding point nothing can reach. See
     * {@link RoadEditor#stationAllowed}.
     */
    STATION;

    /** Stable identifier, used for lang and config keys rather than the enum constant name. */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Parses an id, falling back to {@link #PLACE} for anything unrecognised.
     *
     * <p>{@code PLACE} is the right fallback because a file written before place kinds existed has
     * exactly one kind of node carrying this field -- a {@code POI}, which was a place and must still
     * be one. A node that is not a place does not need a value here at all: its
     * {@link RoadNode.Type} already says so.
     */
    public static PlaceKind byId(String raw) {
        if (raw == null) {
            return PLACE;
        }
        for (PlaceKind kind : values()) {
            if (kind.id().equalsIgnoreCase(raw.trim())) {
                return kind;
            }
        }
        return PLACE;
    }
}
