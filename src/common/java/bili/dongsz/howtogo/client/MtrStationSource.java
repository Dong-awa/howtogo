package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.road.PlaceKind;
import bili.dongsz.howtogo.route.Destination;
import bili.dongsz.howtogo.route.DestinationSource;

import java.util.ArrayList;
import java.util.List;

/**
 * Destinations from MTR's stations. Read-only: station names and positions belong to MTR and are never saved with the player's places. MTR sends only nearby stations, so the list is local to the player.
 */
public final class MtrStationSource implements DestinationSource {

    public static final String ID = "mtr_station";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public boolean isAvailable() {
        return !MtrTransit.stations().isEmpty();
    }

    @Override
    public String displayName() {
        return "hud.howtogo.source.mtr";
    }

    /** A station MTR owns stands somewhere and is worth marking. */
    @Override
    public boolean marksPlaces() {
        return true;
    }

    /** Listed after Create's stations. */
    @Override
    public int priority() {
        return 20;
    }

    @Override
    public List<Destination> destinations() {
        List<MtrTransit.Station> stations = MtrTransit.stations();
        List<Destination> result = new ArrayList<>(stations.size());
        for (MtrTransit.Station station : stations) {
            // MTR's station id is stable across sessions and rebuilds, so player notes survive.
            result.add(new Destination(Destinations.stationName(station.name(), station.x(), station.z()),
                    station.x(), station.y(), station.z(), ID, Destination.NO_COLOR,
                    PlaceKind.STATION, String.valueOf(station.id())));
        }
        return result;
    }
}
