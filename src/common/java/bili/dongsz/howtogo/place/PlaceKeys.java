package bili.dongsz.howtogo.place;

import bili.dongsz.howtogo.route.Destination;

/**
 * The identity of a place, derived from the destination that stands for it.
 *
 * <h2>Why every source brings its own id</h2>
 * A name is not an identity: it can be edited, two stations can share one, and a waypoint can be
 * renamed. A position is not one either: a place can be moved, and two places can stand on the same
 * block in different dimensions. So each source says what its stable id is -- a node id, an MTR
 * station id, a structure id -- and {@link Destination} carries it. This class is the one place that
 * knows what to do when a source did not say: it derives an id from the name and position, which is
 * stable for a source whose entries have nothing better.
 *
 * <h2>Why the fallback is a hash of name and position</h2>
 * It is the only thing an old addon -- one compiled before destinations carried an id -- gives this
 * mod to work with. The hash is {@link String#hashCode()}, whose value is specified by Java rather
 * than left to the implementation, so an id written by one session is the same id in the next one and
 * after an update. Renaming such a place does lose its details, which is the honest trade for a
 * source that cannot say what its entries are; the mod's own five sources all say, so only an addon
 * that has not adopted the id pays it.
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

    /**
     * The source's own stable id for a destination, or a derived one when it did not carry one.
     *
     * <p>Never empty: a destination with a blank id still gets an identity, because a place the
     * player can select must be a place whose details can be found.
     */
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
     * The id a source's entries fall back to: their name and column, hashed.
     *
     * <p>{@link String#hashCode()} is used deliberately -- its value is part of the Java
     * specification, so this id does not change between runs or after an update, which a hash chosen
     * for distribution alone would not promise.
     */
    public static String fallbackId(String name, int x, int z) {
        String text = (name == null ? "" : name) + "@" + x + "," + z;
        return "n" + Integer.toHexString(text.hashCode());
    }

    /**
     * An Xaero waypoint's id: its name and position, hashed.
     *
     * <p>A waypoint has no id of its own in Xaero's storage -- the mod identifies one by where it is
     * and what it is called -- so this is the closest thing to a stable id the source can offer. The
     * comment is here rather than in the source so the rule is in one place with the other id rules.
     */
    public static String xaeroId(String name, int x, int z) {
        return fallbackId(name, x, z);
    }

    /**
     * A Create station's id: its column.
     *
     * <p>A station read from Create's graph has a name but no id this mod may use, and the station
     * that stands at a position is the one the player means. The height is deliberately not part of
     * it: a station's platform and its name are about the column, and a rebuild that moves the
     * platform a block up must not orphan what the player wrote about the stop.
     */
    public static String createStationId(int x, int z) {
        return x + "," + z;
    }
}
