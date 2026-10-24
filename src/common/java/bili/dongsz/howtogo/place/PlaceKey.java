package bili.dongsz.howtogo.place;

import java.util.Locale;

/**
 * The identity of one place's detail record: dimension, source and the source's own stable id.
 *
 * <h2>Why the key is three parts</h2>
 * A place is only a place inside one dimension -- the same node id means nothing in another -- and
 * the id only identifies a place within the source that produced it: MTR's station 12 and the
 * player's node 12 have nothing to do with each other. So all three are needed before a detail
 * record can be found, and the store is keyed on this rather than on a name or a position: a name
 * is editable and a position can be moved, and a detail that disappeared because the player renamed
 * their own landmark would be data loss.
 *
 * <h2>Why a record rather than a joined string</h2>
 * The parts are read back out for display (the "unlinked details" screen lists source and id) and
 * for the source policy (only the player's own places are deletable), so keeping them apart is what
 * makes those questions answerable without parsing. {@link #asText()} exists for log lines and test
 * reports, never as storage.
 */
public record PlaceKey(String dimension, String source, String id) {

    public PlaceKey {
        dimension = dimension == null || dimension.isBlank() ? "unknown" : dimension;
        source = source == null ? "" : source;
        id = id == null ? "" : id;
    }

    /** Whether this is one of the player's own hand-placed nodes. */
    public boolean isManual() {
        return PlaceSources.isManual(source);
    }

    /** The key as one human-readable string, for log lines and reports. */
    public String asText() {
        return dimension + "|" + source + "|" + id;
    }

    @Override
    public String toString() {
        return asText();
    }

    /** The id lower-cased, for grouping and comparison where a source may spell it either way. */
    public String idLowerCase() {
        return id.toLowerCase(Locale.ROOT);
    }
}
