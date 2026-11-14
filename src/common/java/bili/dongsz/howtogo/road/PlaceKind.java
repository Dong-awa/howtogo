package bili.dongsz.howtogo.road;

import java.util.Locale;

/**
 * What kind of place a RoadNode.Type POI node is.
 * Independent of the node type, which answers what the vertex does in the graph -- a station is a place and sits on a road. Separate so RoadEditor reclassifyNodes leaves places alone.
 * PLACE is the default, so files written before this field existed read back as PLACE.
 */
public enum PlaceKind {

    /** An ordinary landmark, the default. */
    PLACE,

    /** Somewhere resources are gathered. */
    RESOURCE,

    /** Somewhere things are bought and sold. */
    SHOP,

    /**
     * A boarding point for public transit. Must stand on a road -- a boarding point no route passes through cannot be reached; see RoadEditor stationAllowed.
     */
    STATION;

    /** Stable identifier, used for lang and config keys rather than the enum constant name. */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Parses an id, falling back to PLACE for anything unrecognised. PLACE is the pre-field default for POIs; a node that is not a place carries no value here.
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
