package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.route.Destination;
import bili.dongsz.howtogo.route.DestinationSource;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Destinations from Create's train stations.
 *
 * <h2>Where the names come from</h2>
 * Create's own graph carries them: a station is an edge point on the graph, its name is a field on
 * {@code GlobalStation}, and Create's map integration labels stations with it. So when the layer read
 * Create's graph, a station is offered under the name the player gave it.
 *
 * <p>When the layer came from the block scan instead there is no name to be had: a station's name is
 * not in the world's blocks, only in Create's railway data. Those stations are named from where they
 * are, with the coordinates the picker's search can be used on, and the source note beside them says
 * where they came from.
 *
 * <p>The positions come from {@link RailTrackStore}, which is the only thing that knows what the
 * layer currently holds. No Create class is involved on this side, so with Create absent this source
 * reports itself unavailable and the picker shows only the sources that do have something.
 */
public final class CreateStationSource implements DestinationSource {

    public static final String ID = "create_station";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public boolean isAvailable() {
        return !destinations().isEmpty();
    }

    @Override
    public String displayName() {
        return "hud.howtogo.source.create";
    }

    @Override
    public List<Destination> destinations() {
        List<RailTrackStore.Station> stations = RailTrackStore.stations();
        List<Destination> result = new ArrayList<>(stations.size());
        for (RailTrackStore.Station station : stations) {
            result.add(new Destination(nameOf(station), station.x(), station.y(), station.z(), ID));
        }
        return result;
    }

    /**
     * What to call a station: Create's own name when the layer read it from Create's graph, and its
     * position otherwise.
     *
     * <p>The name is the one string the route, the spoken announcement, the HUD and the search all
     * share, so the fallback is a whole translated sentence rather than a prefix glued on here. The
     * blocks in the world carry no station name at all -- it lives in Create's railway data -- so a
     * block-scan station genuinely has nothing to offer and its coordinates are the honest answer.
     *
     * <p>Public because the map draws the same label: a station's name on the map and its entry in
     * the picker being two independently built strings is exactly how they drift apart.
     */
    public static String nameOf(RailTrackStore.Station station) {
        String name = station.name();
        if (name != null && !name.isBlank()) {
            return name.trim();
        }
        return Component.translatable("hud.howtogo.station.name",
                String.valueOf(station.x()), String.valueOf(station.z())).getString();
    }
}
