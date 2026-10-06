package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.road.RoadNode;
import bili.dongsz.howtogo.road.PlaceKind;
import bili.dongsz.howtogo.route.Destination;
import bili.dongsz.howtogo.route.DestinationSource;

import java.util.ArrayList;
import java.util.List;

/**
 * Destinations from the player's own placed landmarks.
 *
 * <p>Always available, which matters because the richer source (Xaero's waypoints) needs the
 * Minimap mod, and the World Map alone ships without any waypoint storage.
 */
public final class PoiDestinationSource implements DestinationSource {

    public static final String ID = "poi";

    @Override
    public String id() {
        return ID;
    }

    /**
     * Whether the player has placed any named landmark.
     *
     * <p>Stops at the first one rather than building every destination to ask whether the list is
     * empty: this is called by {@link Destinations#places()} on every frame a map is drawn.
     */
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

    @Override
    public List<Destination> destinations() {
        List<Destination> result = new ArrayList<>();
        for (RoadNode node : RoadStore.get().nodes()) {
            if (node.type() == RoadNode.Type.POI && node.name() != null) {
                result.add(new Destination(node.name(), node.x(), node.y(), node.z(), ID, Destination.NO_COLOR,node.placeKind()));
            }
        }
        return result;
    }
}
