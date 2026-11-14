package bili.dongsz.howtogo.place;

import java.util.Locale;

/**
 * The identity of one place's detail record: dimension, source and the source's own stable id.
 * All three are needed -- ids are only unique within a source, and a place exists only within one dimension. The store is keyed on this, not on name or position, which are editable.
 * asText() is for log lines and test reports, never storage.
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
