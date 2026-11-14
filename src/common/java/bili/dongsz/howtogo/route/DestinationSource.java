package bili.dongsz.howtogo.route;

import java.util.List;

/**
 * Supplies navigation destinations.
 *
 * Pluggable because the preferred source, Xaero waypoints, lives in the Minimap mod, an optional dependency that may be absent; sources report isAvailable() rather than throw and the UI skips them.
 * Register from HowToGoRegistrationEvent or any time after load via bili.dongsz.howtogo.api.HowToGoApi#registerDestinationSource(DestinationSource). The picker, maps and search then include the source, gated by marksPlaces() and searchable().
 * isAvailable() and destinations() are called while a map is drawn, once per frame per view: keep them cheap, free of blocking world access and exception-free -- a throw takes the frame with it. Nothing to offer means an empty list.
 */
public interface DestinationSource {

    /** Stable id, also stored on each Destination it produces. */
    String id();

    /** Whether this source can currently produce anything. */
    boolean isAvailable();

    /** Human-readable name for this source's group in the picker. */
    String displayName();

    List<Destination> destinations();

    /**
     * Whether entries are places and get a marker on every map: the picker map, the HUD minimap and the world map.
     *
     * False by default: Xaero waypoints belong to Xaero and carry its colours, so painting them in this mod's place colour would contradict the list. Sources of ordinary places (stations, shops, landmarks) want true.
     */
    default boolean marksPlaces() {
        return false;
    }

    /**
     * Position in the picker's list; lower first. The mod's own five sources use 0 to 40, so the default puts an addon after all of them.
     */
    default int priority() {
        return 100;
    }

    /**
     * Whether entries are found by the search box. True by default; false suits a long, untyped list that is better browsed by hand.
     */
    default boolean searchable() {
        return true;
    }

    /**
     * Translation key of the short note drawn after a name, or empty for the conventional key hud.howtogo.source.<id up to the first underscore>.short.
     *
     * The note decorates the row only; route instructions, narration and search read the name verbatim. A missing translation draws nothing, so the default is silently harmless.
     */
    default String noteKey() {
        return "";
    }
}

