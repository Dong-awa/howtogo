package bili.dongsz.howtogo.api;

import bili.dongsz.howtogo.HowToGo;
import bili.dongsz.howtogo.client.CreateStationSource;
import bili.dongsz.howtogo.client.MtrStationSource;
import bili.dongsz.howtogo.client.PoiDestinationSource;
import bili.dongsz.howtogo.client.XaeroWaypointSource;
import bili.dongsz.howtogo.route.DestinationSource;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Every destination source the picker knows about: the four this mod ships, plus any an addon
 * registers.
 *
 * <h2>What was wrong before</h2>
 * The list used to be a {@code List.of(...)} inside the picker's aggregator, so "add a source" meant
 * "edit this mod" -- and a source registered by another mod could not have existed even in principle.
 * The set of sources is now a registry, and the aggregator asks it like any other caller.
 *
 * <h2>Why the built-ins live here</h2>
 * Because the question is "what can be a destination", and the answer has to include the mod's own
 * four whether or not anybody registered anything. Keeping them beside the registry rather than in
 * the picker means there is one list, in one order, for the picker, the map, the HUD and the self-test
 * -- which is what stops a source that one view draws from being a source another view has never
 * heard of.
 *
 * <h2>Order and duplicates</h2>
 * Sources are ordered by {@link DestinationSource#priority()} and, within one priority, by
 * registration order. The four built-ins carry priorities 0 to 30, which is the order they have always
 * been listed in, so nothing moves for a player who has installed no addons. An id already in use is
 * refused rather than replacing what is there: two sources answering to one id would make every
 * destination's {@code source} field ambiguous, and the picker's source notes are keyed on that field.
 *
 * <h2>Threading</h2>
 * Registration is safe from any thread -- the registries are concurrent and the ordering is rebuilt
 * lazily -- so an addon may register from its own constructor, from the registration event, or from a
 * config callback without having to know when this mod's client setup ran.
 */
public final class DestinationSources {

    /** The four sources this mod ships, in their long-standing order. */
    private static final List<DestinationSource> BUILT_IN = List.of(
            new PoiDestinationSource(),
            new CreateStationSource(),
            new MtrStationSource(),
            new XaeroWaypointSource());

    private static final List<DestinationSource> REGISTERED = new CopyOnWriteArrayList<>();

    /** Every id in use, built-in or registered, so a collision is refused rather than absorbed. */
    private static final Set<String> IDS = ConcurrentHashMap.newKeySet();

    /** Rebuilt on the next {@link #all()} after a registration, and never mutated in place. */
    private static volatile List<DestinationSource> ordered;

    static {
        for (DestinationSource source : BUILT_IN) {
            IDS.add(source.id());
        }
    }

    private DestinationSources() {
    }

    /**
     * Adds a source to the picker, the maps and the destination list.
     *
     * <p>Idempotent in the only sense that matters: registering the same id twice changes nothing the
     * second time, and the reason is written to the log. It does not replace, and it cannot be undone
     * -- a source is a caller in this process, not a resource with a lifetime.
     *
     * @throws NullPointerException if the source is null, which is a bug in the caller rather than a
     *                              condition to carry on from
     */
    public static void register(DestinationSource source) {
        if (source == null) {
            throw new NullPointerException("destination source");
        }
        String id = source.id();
        if (id == null || id.isBlank()) {
            HowToGo.LOGGER.warn("[HowToGo] api | a destination source with no id was refused");
            return;
        }
        if (!IDS.add(id)) {
            HowToGo.LOGGER.warn("[HowToGo] api | destination source id '{}' is already in use; "
                    + "the later registration was refused", id);
            return;
        }
        REGISTERED.add(source);
        ordered = null;
        HowToGo.LOGGER.info("[HowToGo] api | destination source registered: {}", id);
    }

    /** Every source, built-in and registered, in priority order. Never null, never empty. */
    public static List<DestinationSource> all() {
        List<DestinationSource> local = ordered;
        if (local == null) {
            local = order();
            ordered = local;
        }
        return local;
    }

    /** The source with this id, or null when nothing has that id. */
    public static DestinationSource byId(String id) {
        if (id == null) {
            return null;
        }
        for (DestinationSource source : all()) {
            if (id.equals(source.id())) {
                return source;
            }
        }
        return null;
    }

    /**
     * Whether a source's entries are places, and so get a marker wherever a map is drawn.
     *
     * <p>An unknown id answers false: a destination whose source is not registered is one this mod
     * cannot draw a note or a marker for, and guessing that it is a place would put a marker on the
     * map for something no source claims.
     */
    public static boolean marksPlaces(String sourceId) {
        DestinationSource source = byId(sourceId);
        return source != null && source.marksPlaces();
    }

    /**
     * Whether a source's entries may be found by typing in the search box.
     *
     * <p>An unknown id answers true, which is the opposite of {@link #marksPlaces(String)} and
     * deliberately so: the search looks through destinations that are already on screen, so the
     * failure mode of a wrong "yes" is a matchable row, while the failure mode of a wrong "no" is a
     * row the player can see and cannot find.
     */
    public static boolean searchable(String sourceId) {
        DestinationSource source = byId(sourceId);
        return source == null || source.searchable();
    }

    /**
     * The translation key a source wants drawn after its names in the list, or empty for "none".
     *
     * <p>Empty is also the answer for an id no source owns, and for a source that left
     * {@link DestinationSource#noteKey()} at its default: the caller then falls back to the
     * conventional key, which is what the mod's own sources were built with. The fallback lives with
     * the drawing rather than here because it is a property of the list's layout, not of the source.
     */
    public static String noteKey(String sourceId) {
        DestinationSource source = byId(sourceId);
        return source == null ? "" : source.noteKey();
    }

    /** How many addon sources have been registered; the four built-ins are not counted. */
    public static int registeredCount() {
        return REGISTERED.size();
    }

    private static List<DestinationSource> order() {
        List<DestinationSource> combined = new ArrayList<>(BUILT_IN.size() + REGISTERED.size());
        combined.addAll(BUILT_IN);
        combined.addAll(REGISTERED);
        // Stable: equal priorities keep the order they were built in, which is the built-ins' own
        // long-standing order followed by registration order.
        combined.sort(Comparator.comparingInt(DestinationSource::priority));
        return List.copyOf(combined);
    }
}
