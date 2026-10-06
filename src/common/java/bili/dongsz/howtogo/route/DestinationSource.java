package bili.dongsz.howtogo.route;

import java.util.List;

/**
 * Supplies navigation destinations.
 *
 * <p>Pluggable because the preferred source -- Xaero's own waypoints -- lives in the Minimap mod,
 * which is only an optional dependency of the World Map and may not be installed at all. Sources
 * must report {@link #isAvailable()} rather than throw, so the UI can simply skip them.
 *
 * <h2>Adding a source from another mod</h2>
 * Implement this and register it -- either from {@code HowToGoRegistrationEvent} or any time after
 * this mod has loaded, through
 * {@link bili.dongsz.howtogo.api.HowToGoApi#registerDestinationSource(DestinationSource)}. Nothing
 * else is needed: the picker lists the source's destinations, the map and the HUD mark them if
 * {@link #marksPlaces()} says they are places, and the search finds them if {@link #searchable()} says
 * they are searchable.
 *
 * <h2>What a source must not do</h2>
 * {@link #isAvailable()} and {@link #destinations()} are called while a map is being drawn, once per
 * frame per view. They must be cheap, must not touch the world through anything that can block, and
 * must not throw -- a source that throws while the map draws is a source that takes the frame with it.
 * A source with nothing to offer says so with an empty list.
 */
public interface DestinationSource {

    /** Stable id, also stored on each {@link Destination} it produces. */
    String id();

    /** Whether this source can currently produce anything. */
    boolean isAvailable();

    /** Human-readable name for source groups in the picker. */
    String displayName();

    List<Destination> destinations();

    /**
     * Whether this source's entries are places, and so get a marker wherever a map is drawn.
     *
     * <p>A place is marked in three views that must agree: the picker's own map, the navigation HUD's
     * small map, and the world map. Exactly which sources qualify used to be a hard-coded list of ids
     * inside the mod, so a source from another mod could be listed and never appear on any map. It is
     * now the source's own answer.
     *
     * <p>False by default, and the reason is not symmetry: Xaero's waypoints are destinations that
     * belong to Xaero and carry Xaero's own colours, so painting them in this mod's place colour would
     * contradict the list. A source that brings ordinary places -- stations, shops, landmarks -- wants
     * true.
     */
    default boolean marksPlaces() {
        return false;
    }

    /**
     * Where this source sits in the picker's list.
     *
     * <p>Lower first. The mod's own four sources use 0 to 30, which is the order they have always been
     * listed in, so an addon that leaves this alone appears after all of them; an addon that wants to
     * sit among the station sources gives itself a number in that range.
     */
    default int priority() {
        return 100;
    }

    /**
     * Whether this source's entries may be found by typing in the search box.
     *
     * <p>True by default. False suits a source whose list is long, untyped and better browsed by hand:
     * searching it would bury the handful of names the player meant to find under hundreds of
     * coordinate-generated ones.
     */
    default boolean searchable() {
        return true;
    }

    /**
     * The translation key of the short note drawn after a name in the list, or empty for none.
     *
     * <p>The note is a decoration of the row and nothing else -- it is never part of the name, which
     * the route instructions, the spoken announcements and the search all read verbatim.
     *
     * <p>Empty means "use the conventional key", {@code hud.howtogo.source.<id up to the first
     * underscore>.short}, which is what the mod's own sources were built with; a source whose id does
     * not lend itself to that convention returns its key here instead. A key with no translation
     * behind it draws nothing, so the default is never a mistake, only a silent one.
     */
    default String noteKey() {
        return "";
    }
}
