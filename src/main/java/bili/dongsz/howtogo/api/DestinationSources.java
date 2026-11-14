package bili.dongsz.howtogo.api;

import bili.dongsz.howtogo.HowToGo;
import bili.dongsz.howtogo.client.CreateStationSource;
import bili.dongsz.howtogo.client.MtrStationSource;
import bili.dongsz.howtogo.client.PoiDestinationSource;
import bili.dongsz.howtogo.client.RoadWeaverStructureSource;
import bili.dongsz.howtogo.client.XaeroWaypointSource;
import bili.dongsz.howtogo.route.DestinationSource;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Registry of every destination source the picker knows about: the five built-ins plus addons.
 * Ordered by priority, then registration order; ids are unique and a duplicate is refused. Registration is safe from any thread.
 */
public final class DestinationSources {

    /** The five built-in sources, in their long-standing order. */
    private static final List<DestinationSource> BUILT_IN = List.of(
            new PoiDestinationSource(),
            new CreateStationSource(),
            new MtrStationSource(),
            new XaeroWaypointSource(),
            new RoadWeaverStructureSource());

    private static final List<DestinationSource> REGISTERED = new CopyOnWriteArrayList<>();

    /** Every id in use, so a collision is refused rather than absorbed. */
    private static final Set<String> IDS = ConcurrentHashMap.newKeySet();

    /** Rebuilt on the next all() after a registration; never mutated in place. */
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
     * Registering an existing id changes nothing and logs why; a source cannot be replaced or removed.
     * @throws NullPointerException if source is null
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

    /** Every source in priority order; never null, never empty. */
    public static List<DestinationSource> all() {
        List<DestinationSource> local = ordered;
        if (local == null) {
            local = order();
            ordered = local;
        }
        return local;
    }

    /** The source with this id, or null when none has it. */
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
     * Whether a source's entries are places and get a map marker.
     * An unknown id answers false: an unregistered source's entries cannot be drawn, and guessing place would put a marker on something no source claims.
     */
    public static boolean marksPlaces(String sourceId) {
        DestinationSource source = byId(sourceId);
        return source != null && source.marksPlaces();
    }

    /**
     * Whether a source's entries are searchable.
     * An unknown id answers true, deliberately the opposite of marksPlaces: a wrong yes shows a matchable row, a wrong no hides a row the player can see.
     */
    public static boolean searchable(String sourceId) {
        DestinationSource source = byId(sourceId);
        return source == null || source.searchable();
    }

    /**
     * Translation key to draw after a source's names, or empty for none.
     * Empty for unknown sources and for the default, so the caller falls back to the conventional key.
     */
    public static String noteKey(String sourceId) {
        DestinationSource source = byId(sourceId);
        return source == null ? "" : source.noteKey();
    }

    /** How many addon sources are registered; built-ins are not counted. */
    public static int registeredCount() {
        return REGISTERED.size();
    }

    private static List<DestinationSource> order() {
        List<DestinationSource> combined = new ArrayList<>(BUILT_IN.size() + REGISTERED.size());
        combined.addAll(BUILT_IN);
        combined.addAll(REGISTERED);
        // Stable sort: equal priorities keep build order, built-ins then registration order.
        combined.sort(Comparator.comparingInt(DestinationSource::priority));
        return List.copyOf(combined);
    }
}

