package bili.dongsz.howtogo.place;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * What the player wrote about one place: description, tags, one category value, favourite flag.
 * The category is a separate field, not a tag: one value, not sorted among tags or removed twice,
 * and storage must not read it as an anonymous tag. It is a length-limited string, not an enum --
 * shop types are an open vocabulary. Empty means none.
 * Limits are enforced here so edit screen, storage and web map agree; truncation counts code points.
 * Immutable, read several times a frame: every change returns a new value.
 */
public final class PlaceDetails {

    /** Longest description in characters (code points), newlines included. */
    public static final int MAX_DESCRIPTION = 512;

    /** Most tags one place may carry. */
    public static final int MAX_TAGS = 16;

    /** Longest single tag in characters (code points). */
    public static final int MAX_TAG_LENGTH = 8;

    /**
     * The category when there is none: "". Also what storage writes as field absent, so the format
     * stays additive.
     */
    public static final String EMPTY_CATEGORY = "";

    /** Nothing written yet; what a place with no detail record reads back as. */
    public static final PlaceDetails EMPTY = new PlaceDetails("", List.of(), EMPTY_CATEGORY, false, 0L);

    private final String description;
    private final List<String> tags;
    private final String category;
    private final boolean favorite;
    private final long updatedAt;

    /**
     * A detail with every field, including category.
     * Stores values as given -- normalize() trims and shortens -- except null, which becomes the empty
     * string because every reader would otherwise have to defend against it.
     */
    public PlaceDetails(String description, List<String> tags, String category, boolean favorite,
                        long updatedAt) {
        this.description = description == null ? "" : description;
        this.tags = tags == null ? List.of() : List.copyOf(tags);
        this.category = category == null ? EMPTY_CATEGORY : category;
        this.favorite = favorite;
        this.updatedAt = updatedAt;
    }

    /** The four-argument form, kept for callers written before places had a category -- an empty one. */
    public PlaceDetails(String description, List<String> tags, boolean favorite, long updatedAt) {
        this(description, tags, EMPTY_CATEGORY, favorite, updatedAt);
    }

    /** A detail with the content given and no modification time yet; the store stamps it on save. */
    public static PlaceDetails of(String description, List<String> tags, String category,
                                  boolean favorite) {
        return new PlaceDetails(description, tags, category, favorite, 0L);
    }

    /**
     * The three-argument of, for a place whose category is not being edited; no category is what
     * those callers mean.
     */
    public static PlaceDetails of(String description, List<String> tags, boolean favorite) {
        return new PlaceDetails(description, tags, EMPTY_CATEGORY, favorite, 0L);
    }

    /** An empty detail, for a caller that wants to start writing one. */
    public static PlaceDetails empty() {
        return EMPTY;
    }

    public String description() {
        return description;
    }

    public List<String> tags() {
        return tags;
    }

    /** The place's type or resource type, or EMPTY_CATEGORY when none; never null. */
    public String category() {
        return category;
    }

    public boolean favorite() {
        return favorite;
    }

    public long updatedAt() {
        return updatedAt;
    }

    /**
     * Whether there is nothing to store: no description, tags, category and not a favourite.
     * The store deletes such a record, so "clear everything" equals "never wrote anything". The category
     * counts, or a type-only record would be written and deleted on the next read.
     */
    public boolean isEmpty() {
        return description.isBlank() && tags.isEmpty() && category.isBlank() && !favorite;
    }

    public PlaceDetails withDescription(String text) {
        return new PlaceDetails(text, tags, category, favorite, updatedAt);
    }

    public PlaceDetails withTags(List<String> newTags) {
        return new PlaceDetails(description, newTags, category, favorite, updatedAt);
    }

    /**
     * This detail with the category replaced, everything else carried along; it is normalised by
     * normalize().
     */
    public PlaceDetails withCategory(String text) {
        return new PlaceDetails(description, tags, text, favorite, updatedAt);
    }

    public PlaceDetails withFavorite(boolean favoured) {
        return new PlaceDetails(description, tags, category, favoured, updatedAt);
    }

    public PlaceDetails withUpdatedAt(long millis) {
        return new PlaceDetails(description, tags, category, favorite, millis);
    }

    /**
     * This detail with description and tags trimmed and shortened, tags de-duplicated and capped; a
     * blank category becomes EMPTY_CATEGORY. Lossy one way only, so it is idempotent.
     */
    public PlaceDetails normalize() {
        String text = description
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .strip();
        text = truncate(text, MAX_DESCRIPTION);

        List<String> clean = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String tag : tags) {
            if (tag == null) {
                continue;
            }
            String trimmed = truncate(tag.strip(), MAX_TAG_LENGTH);
            if (trimmed.isEmpty()) {
                continue;
            }
            // Case-insensitive de-duplication, keeping the first spelling: "MTR" and "mtr" are one tag.
            if (seen.add(trimmed.toLowerCase(Locale.ROOT))) {
                clean.add(trimmed);
            }
            if (clean.size() >= MAX_TAGS) {
                break;
            }
        }
        // A category is one short label: trimmed and cut to MAX_TAG_LENGTH; whitespace-only is absent.
        // Truncated rather than refused, so a hand-edited file still reads.
        String cleanCategory = truncate(category.strip(), MAX_TAG_LENGTH);
        return new PlaceDetails(text, List.copyOf(clean), cleanCategory, favorite, updatedAt);
    }

    /**
     * A one-line preview: whitespace runs and newlines collapsed, shortened to maxChars.
     * Callers draw it where there is room for exactly one line; a preview starting with a newline would
     * draw nothing.
     * @param maxChars most characters before the caller adds an ellipsis
     */
    public PlaceSummary summary(int maxChars) {
        String flat = description.replaceAll("\\s+", " ").strip();
        if (flat.isEmpty()) {
            return new PlaceSummary("", false);
        }
        int limit = Math.max(1, maxChars);
        int count = flat.codePointCount(0, flat.length());
        if (count <= limit) {
            return new PlaceSummary(flat, false);
        }
        return new PlaceSummary(truncate(flat, limit), true);
    }

    /**
     * The whole description as a truncated payload string for the browser map.
     * Unlike summary(), newlines survive so the page's card can wrap; the page asks for the full text
     * only when opened.
     */
    public PlaceSummary truncated(int maxChars) {
        String text = description.strip();
        int count = text.codePointCount(0, text.length());
        int limit = Math.max(1, maxChars);
        return count <= limit
                ? new PlaceSummary(text, false)
                : new PlaceSummary(truncate(text, limit), true);
    }

    /**
     * text shortened to at most limit code points, never splitting a surrogate pair.
     * substring counts UTF-16 units, and a cut pair is not text that JSON, a font or a file can handle.
     */
    public static String truncate(String text, int limit) {
        if (text == null || text.isEmpty() || limit <= 0) {
            return "";
        }
        int count = text.codePointCount(0, text.length());
        if (count <= limit) {
            return text;
        }
        return text.substring(0, text.offsetByCodePoints(0, limit));
    }

    /** Characters counted the way the limits are: code points. */
    public static int lengthInCodePoints(String text) {
        return text == null ? 0 : text.codePointCount(0, text.length());
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof PlaceDetails that)) {
            return false;
        }
        return favorite == that.favorite && updatedAt == that.updatedAt
                && description.equals(that.description) && tags.equals(that.tags)
                && category.equals(that.category);
    }

    @Override
    public int hashCode() {
        int result = description.hashCode();
        result = 31 * result + tags.hashCode();
        result = 31 * result + category.hashCode();
        result = 31 * result + Boolean.hashCode(favorite);
        result = 31 * result + Long.hashCode(updatedAt);
        return result;
    }

    @Override
    public String toString() {
        return "PlaceDetails[description=" + lengthInCodePoints(description) + "cp, tags=" + tags
                + ", category=" + (category.isEmpty() ? "-" : category)
                + ", favorite=" + favorite + "]";
    }
}
