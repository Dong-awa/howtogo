package bili.dongsz.howtogo.place;

import bili.dongsz.howtogo.route.Destination;

/**
 * Identity of a place, derived from the destination that stands for it.
 *
 * A name or a position is not an identity -- names can be edited or shared, a place can be moved, and two places can share a block in different dimensions -- so each source supplies its own stable id (node id, MTR station id, structure id) on Destination. This class derives one from name and position when a source did not say.
 * The fallback hashes with String.hashCode(), whose value is fixed by the Java spec, so ids survive sessions and updates; renaming such a place loses its details, which only affects addons that have not adopted ids.
 */
public final class PlaceKeys {

    private PlaceKeys() {
    }

    /** The key of one destination in one dimension. */
    public static PlaceKey of(String dimension, Destination destination) {
        if (destination == null) {
            return new PlaceKey(dimension, "", "");
        }
        String source = destination.source() == null || destination.source().isBlank()
                ? "unknown"
                : destination.source();
        return new PlaceKey(dimension, source, stableId(destination));
    }

    /** The source's stable id for a destination, or a derived one when it carried none. Never empty. */
    public static String stableId(Destination destination) {
        if (destination == null) {
            return "";
        }
        String id = destination.placeId();
        if (id != null && !id.isBlank()) {
            return id.trim();
        }
        return fallbackId(destination.name(), destination.x(), destination.z());
    }

    /**
     * Fallback id: name and column hashed with String.hashCode(), stable across runs and updates because Java specifies it.
     */
    public static String fallbackId(String name, int x, int z) {
        String text = (name == null ? "" : name) + "@" + x + "," + z;
        return "n" + Integer.toHexString(text.hashCode());
    }

    /**
     * Xaero waypoint id: name and position hashed. Xaero stores no id of its own, and this is the closest stable identity the source offers.
     */
    public static String xaeroId(String name, int x, int z) {
        return fallbackId(name, x, z);
    }

    /**
     * Create station id: its column only. The station read from Create's graph has a name but no usable id, and height is excluded so moving a platform up does not orphan player data about the stop.
     */
    public static String createStationId(int x, int z) {
        return x + "," + z;
    }
}
