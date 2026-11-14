package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.road.RoadNode;
import bili.dongsz.howtogo.road.PlaceKind;
import bili.dongsz.howtogo.route.Destination;
import bili.dongsz.howtogo.route.DestinationSource;

import java.util.ArrayList;
import java.util.List;

/**
 * Destinations from the player's own placed landmarks. Always available; Xaero's
 * waypoints need the Minimap mod and the World Map alone has no waypoint storage.
 */
public final class PoiDestinationSource implements DestinationSource {

    public static final String ID = "poi";

    @Override
    public String id() {
        return ID;
    }

    /** Whether any named landmark exists. Short-circuits; called every frame a map is drawn. */
    @Override
    public boolean isAvailable() {
        for (RoadNode node : RoadStore.get().nodes()) {
            if (node.type() == RoadNode.Type.POI && node.name() != null) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String displayName() {
        return "hud.howtogo.source.poi";
    }

    /** Player places get a marker on every map this mod draws. */
    @Override
    public boolean marksPlaces() {
        return true;
    }

    /** First in the list: deliberate destinations come before anything read from another mod. */
    @Override
    public int priority() {
        return 0;
    }

    @Override
    public List<Destination> destinations() {
        List<Destination> result = new ArrayList<>();
        for (RoadNode node : RoadStore.get().nodes()) {
            if (node.type() == RoadNode.Type.POI && node.name() != null) {
                // The node id is the place's stable id, keyed by the detail store and used by the editor to delete.
                result.add(new Destination(node.name(), node.x(), node.y(), node.z(), ID,
                        Destination.NO_COLOR, node.placeKind(), String.valueOf(node.id())));
            }
        }
        return result;
    }
}
