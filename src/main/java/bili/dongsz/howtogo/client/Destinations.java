package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.route.Destination;
import bili.dongsz.howtogo.route.DestinationSource;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Aggregates every registered {@link DestinationSource}.
 */
public final class Destinations {

    private static final List<DestinationSource> SOURCES = List.of(
            new PoiDestinationSource(),
            new CreateStationSource(),
            new MtrStationSource(),
            new XaeroWaypointSource());

    private Destinations() {
    }

    public static List<DestinationSource> sources() {
        return SOURCES;
    }

    /** Every destination from every available source, in source order. */
    public static List<Destination> all() {
        List<Destination> result = new ArrayList<>();
        for (DestinationSource source : SOURCES) {
            if (source.isAvailable()) {
                result.addAll(source.destinations());
            }
        }
        return result;
    }

    /**
     * The destinations that are places, in source order.
     *
     * <p>The three views that draw the map each mark these, and they must mark the same set: a place
     * with a marker in one view and none in another is a place the player cannot rely on. Built from
     * the sources rather than from the raw nodes and stations so that the name on the map is the same
     * string the list shows, by construction.
     *
     * <p>Waypoints are not places. They are destinations, but they belong to Xaero and carry their own
     * colours, so painting them in the place colour would contradict the list.
     */
    public static List<Destination> places() {
        List<Destination> result = new ArrayList<>();
        for (DestinationSource source : SOURCES) {
            if (isPlaceSource(source.id()) && source.isAvailable()) {
                result.addAll(source.destinations());
            }
        }
        return result;
    }

    /** Whether a source's entries are places, and so get a marker wherever a map is drawn. */
    public static boolean isPlaceSource(String sourceId) {
        return PoiDestinationSource.ID.equals(sourceId)
                || CreateStationSource.ID.equals(sourceId)
                || MtrStationSource.ID.equals(sourceId);
    }

    /**
     * What to call a station that has no name of its own: where it is.
     *
     * <p>The name is the one string the route, the spoken announcement, the HUD and the search all
     * share, so the fallback is a whole translated sentence rather than a prefix glued on wherever a
     * source happens to build it. Shared by the two station sources because a station read from Create
     * and one read from MTR are nameless for the same reason -- the name lives in the other mod's data,
     * not in the world -- and two independently written fallbacks is exactly how the same nameless
     * station ends up listed two different ways.
     */
    public static String stationName(String name, int x, int z) {
        if (name != null && !name.isBlank()) {
            return name.trim();
        }
        return Component.translatable("hud.howtogo.station.name",
                String.valueOf(x), String.valueOf(z)).getString();
    }
}
