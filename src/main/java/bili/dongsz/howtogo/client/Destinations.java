package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.api.DestinationSources;
import bili.dongsz.howtogo.route.Destination;
import bili.dongsz.howtogo.route.DestinationSource;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Aggregates every registered {@link DestinationSource}.
 *
 * <h2>Where the sources come from</h2>
 * The four this mod ships and every source an addon has contributed, as one ordered list owned by
 * {@link DestinationSources}. This class used to hold the list itself, which meant "add a source"
 * meant "edit this file", and meant the picker, the map and the HUD could only ever draw what was
 * compiled in. It is now a view over the registry, and every one of those callers sees the same
 * sources in the same order -- which is what stops a source that one view draws from being a source
 * another view has never heard of.
 */
public final class Destinations {

    private Destinations() {
    }

    /** Every source, built-in and registered, in the picker's order. */
    public static List<DestinationSource> sources() {
        return DestinationSources.all();
    }

    /** Every destination from every available source, in source order. */
    public static List<Destination> all() {
        List<Destination> result = new ArrayList<>();
        for (DestinationSource source : DestinationSources.all()) {
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
     * <p>Which sources are places is the source's own answer -- {@link DestinationSource#marksPlaces()}
     * -- rather than a list of ids kept here. Waypoints are the case that makes the difference: they
     * are destinations, but they belong to Xaero and carry their own colours, so painting them in the
     * place colour would contradict the list.
     */
    public static List<Destination> places() {
        List<Destination> result = new ArrayList<>();
        for (DestinationSource source : DestinationSources.all()) {
            if (source.marksPlaces() && source.isAvailable()) {
                result.addAll(source.destinations());
            }
        }
        return result;
    }

    /**
     * Whether a source's entries are places, and so get a marker wherever a map is drawn.
     *
     * <p>For a caller that has a destination's {@code source} id and not the source itself, which is
     * every caller that iterates a list of destinations.
     */
    public static boolean isPlaceSource(String sourceId) {
        return DestinationSources.marksPlaces(sourceId);
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
