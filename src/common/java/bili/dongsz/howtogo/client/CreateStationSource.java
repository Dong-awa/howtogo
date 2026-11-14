package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.place.PlaceKeys;
import bili.dongsz.howtogo.road.PlaceKind;
import bili.dongsz.howtogo.route.Destination;
import bili.dongsz.howtogo.route.DestinationSource;

import java.util.ArrayList;
import java.util.List;

/**
 * Destinations from Create's train stations.
 * Names come from Create's graph (GlobalStation name); stations found by the block scan have no name and are named by position, with a source note.
 * Positions come from RailTrackStore. No Create class is touched here, so with Create absent this source reports itself unavailable.
 */
public final class CreateStationSource implements DestinationSource {

    public static final String ID = "create_station";

    @Override
    public String id() {
        return ID;
    }

    /**
     * Whether the layer has any station at all. Asked of the layer rather than destinations(), which would build one destination per station -- this runs every frame a map is drawn.
     */
    @Override
    public boolean isAvailable() {
        return !RailTrackStore.stations().isEmpty();
    }

    @Override
    public String displayName() {
        return "hud.howtogo.source.create";
    }

    /** A station is a place the player can see and travel to, so it is marked on every map. */
    @Override
    public boolean marksPlaces() {
        return true;
    }

    /** After the player's own places and before MTR's. */
    @Override
    public int priority() {
        return 10;
    }

    @Override
    public List<Destination> destinations() {
        List<RailTrackStore.Station> stations = RailTrackStore.stations();
        List<Destination> result = new ArrayList<>(stations.size());
        for (RailTrackStore.Station station : stations) {
            // Create gives no usable id, so the block column is the identity: the station at a position is the one the player means.
            result.add(new Destination(nameOf(station), station.x(), station.y(), station.z(), ID,
                    Destination.NO_COLOR, PlaceKind.STATION,
                    PlaceKeys.createStationId(station.x(), station.z())));
        }
        return result;
    }

    /**
     * Create's own station name when the layer read it from Create's graph, and its position otherwise. One rule shared with Destinations stationName and the map label, so the picker and the map cannot drift apart.
     */
    public static String nameOf(RailTrackStore.Station station) {
        return Destinations.stationName(station.name(), station.x(), station.z());
    }
}
