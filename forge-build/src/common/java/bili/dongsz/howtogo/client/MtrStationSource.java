package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.road.PlaceKind;
import bili.dongsz.howtogo.route.Destination;
import bili.dongsz.howtogo.route.DestinationSource;

import java.util.ArrayList;
import java.util.List;

/**
 * Destinations from MTR's stations.
 *
 * <h2>Why a station MTR owns belongs in this list</h2>
 * The picker offers every place a journey can end at, and a station read out of MTR is such a place in
 * the same way a station Create reports is: it stands somewhere in the world, it has a name, and a
 * public transport journey is planned to it. Without this source the picker offered the two station
 * kinds the mod draws itself and none of MTR's, so a player could ride a line read out of MTR and could
 * not ask to be taken to one of its stations -- which is the one thing the integration is for.
 *
 * <p>Read-only by construction, exactly as {@link CreateStationSource} is: the station's name and
 * position belong to MTR, nothing here is ever saved with the player's places, and MTR sends only what
 * is near the player, so the list is the part of the network around them rather than all of it.
 *
 * <p>With MTR absent -- or switched off, or nothing in range -- the source reports itself unavailable
 * and the picker shows only the sources that do have something.
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

    /** Read-only or not, a station MTR owns stands somewhere and is worth marking. */
    @Override
    public boolean marksPlaces() {
        return true;
    }

    /** After Create's stations, as it has always been listed. */
    @Override
    public int priority() {
        return 20;
    }

    @Override
    public List<Destination> destinations() {
        List<MtrTransit.Station> stations = MtrTransit.stations();
        List<Destination> result = new ArrayList<>(stations.size());
        for (MtrTransit.Station station : stations) {
            result.add(new Destination(Destinations.stationName(station.name(), station.x(), station.z()),
                    station.x(), station.y(), station.z(), ID, Destination.NO_COLOR,
                    PlaceKind.STATION));
        }
        return result;
    }
}
