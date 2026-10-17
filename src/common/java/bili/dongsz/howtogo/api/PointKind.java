package bili.dongsz.howtogo.api;

import bili.dongsz.howtogo.road.PlaceKind;
import bili.dongsz.howtogo.road.RoadNode;

/**
 * What kind of thing a point written through {@link RoadDataWriter#point} is.
 *
 * <h2>One enum, two internal fields</h2>
 * Internally a vertex has two independent facts about it: its {@link RoadNode.Type} -- what it does in
 * the graph, which is junction, endpoint or a place belonging to no road -- and, for a place, its
 * {@link PlaceKind}, which is what kind of place it is. An addon should not have to know that, and
 * should not be able to name a combination the mod's own editor cannot produce, so this enum is the
 * flattened list of the combinations that mean something: an addon picks one value and the mod works
 * out the two fields.
 *
 * <p>A station is a place <em>and</em> sits on a road, which is why it is here rather than being a node
 * type of its own: see {@link PlaceKind}.
 */
public enum PointKind {

    /** A vertex where roads meet. */
    JUNCTION(RoadNode.Type.JUNCTION, null),

    /** A vertex where a road stops without meeting anything. */
    ENDPOINT(RoadNode.Type.ENDPOINT, null),

    /** An ordinary landmark, belonging to no road. */
    PLACE(RoadNode.Type.POI, PlaceKind.PLACE),

    /** Somewhere resources are gathered. */
    RESOURCE(RoadNode.Type.POI, PlaceKind.RESOURCE),

    /** Somewhere things are bought and sold. */
    SHOP(RoadNode.Type.POI, PlaceKind.SHOP),

    /** A boarding point for public transit. */
    STATION(RoadNode.Type.POI, PlaceKind.STATION);

    private final RoadNode.Type nodeType;
    private final PlaceKind placeKind;

    PointKind(RoadNode.Type nodeType, PlaceKind placeKind) {
        this.nodeType = nodeType;
        this.placeKind = placeKind;
    }

    /** The graph role this point is written as. */
    public RoadNode.Type nodeType() {
        return nodeType;
    }

    /**
     * The kind of place this point is written as, or null when it is not a place at all.
     *
     * <p>Null rather than a default because the field means nothing for a node that is not a
     * {@code POI}, and a caller that asks is one that is about to decide something by it.
     */
    public PlaceKind placeKind() {
        return placeKind;
    }
}
