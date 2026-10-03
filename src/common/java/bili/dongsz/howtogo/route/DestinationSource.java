package bili.dongsz.howtogo.route;

import java.util.List;

/**
 * Supplies navigation destinations.
 *
 * <p>Pluggable because the preferred source -- Xaero's own waypoints -- lives in the Minimap mod,
 * which is only an optional dependency of the World Map and may not be installed at all. Sources
 * must report {@link #isAvailable()} rather than throw, so the UI can simply skip them.
 */
public interface DestinationSource {

    /** Stable id, also stored on each {@link Destination} it produces. */
    String id();

    /** Whether this source can currently produce anything. */
    boolean isAvailable();

    /** Human-readable name for source groups in the picker. */
    String displayName();

    List<Destination> destinations();
}
