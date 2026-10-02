package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.road.RoadNetwork;
import bili.dongsz.howtogo.route.TransitPlanner;
import bili.dongsz.howtogo.transit.LineStop;

import java.util.ArrayList;
import java.util.List;

/**
 * Every place a public transport journey may be boarded or left.
 *
 * <h2>Two sources, because the world holds two kinds of station</h2>
 * A place the player marked as a station is a node of the road network and is found by walking it. A
 * station Create's track graph reports is not: it arrives as an ordinary rail vertex with a name
 * attached, so nothing in the network tells it apart from any other point of track and no search over
 * that network could pick it out. Only the track layer knows, which is why the second kind is added
 * here rather than found there.
 *
 * <p>Both are needed even when only one is populated. A player who built stations and marked none is
 * the ordinary case; one who marked a stop on a road, with no station block in sight, is the case the
 * marks exist for.
 *
 * <p>The two lists are merged with the player's own places first, so a station that was both marked
 * and built is offered once, as the editable place rather than as the read-only station.
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
        // MTR's stations come last, so that a place the player marked at the same block -- and then a
        // station Create reports there -- keeps the editable one: the first of a pair is the one kept.
        stops.addAll(MtrTransit.stops());
        return LineStop.distinct(stops);
    }
}
