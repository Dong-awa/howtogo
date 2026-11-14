package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.road.RoadNetwork;
import bili.dongsz.howtogo.route.TransitPlanner;
import bili.dongsz.howtogo.transit.LineStop;

import java.util.ArrayList;
import java.util.List;

/**
 * Every place a public transport journey may be boarded or left.
 * Two sources: stations the player marked are road-network nodes; stations Create's track graph reports are ordinary rail vertices only the track layer can identify. Both are needed even when only one is populated.
 * Player places come first, so a station that was both marked and built is offered once, as the editable place.
 */
public final class TransitStops {

    private TransitStops() {
    }

    /** The stops available in the given network, in no particular order. */
    public static List<LineStop> all(RoadNetwork network) {
        List<LineStop> stops = new ArrayList<>(TransitPlanner.markedStations(network));
        for (RailTrackStore.Station station : RailTrackStore.stations()) {
            stops.add(LineStop.ofStation(station.name(), station.x(), station.z()));
        }
        // MTR stations last, so the player's editable place for the same block wins.
        stops.addAll(MtrTransit.stops());
        return LineStop.distinct(stops);
    }
}
