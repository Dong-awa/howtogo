package bili.dongsz.howtogo.api;

import bili.dongsz.howtogo.road.PlaceKind;
import bili.dongsz.howtogo.road.RoadNode;

/**
 * Kind of point written through RoadDataWriter#point: one value flattening the RoadNode.Type role plus PlaceKind, so addons cannot name combinations the editor cannot produce.
 * A station is a place on a road, hence a value here rather than a node type of its own.
 */
public enum PointKind {

    /** Vertex where roads meet. */
    JUNCTION(RoadNode.Type.JUNCTION, null),

    /** Vertex where a road stops without meeting anything. */
    ENDPOINT(RoadNode.Type.ENDPOINT, null),

    /** Ordinary landmark belonging to no road. */
    PLACE(RoadNode.Type.POI, PlaceKind.PLACE),

    /** Where resources are gathered. */
    RESOURCE(RoadNode.Type.POI, PlaceKind.RESOURCE),

    /** Where things are bought and sold. */
    SHOP(RoadNode.Type.POI, PlaceKind.SHOP),

    /** Boarding point for public transit. */
    STATION(RoadNode.Type.POI, PlaceKind.STATION);

    private final RoadNode.Type nodeType;
    private final PlaceKind placeKind;

    PointKind(RoadNode.Type nodeType, PlaceKind placeKind) {
        this.nodeType = nodeType;
        this.placeKind = placeKind;
    }

    public RoadNode.Type nodeType() {
        return nodeType;
    }

    /** Place kind, or null when the point is not a place. */
    public PlaceKind placeKind() {
        return placeKind;
    }
}
