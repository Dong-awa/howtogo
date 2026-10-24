package bili.dongsz.howtogo.place;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * What the player wrote about one place: a description, a set of tags, and a favourite flag.
 *
 * <h2>Why the limits are enforced here</h2>
 * The edit screen clamps what it accepts, the storage reads what it finds, and the web map truncates
 * what it sends -- three places, one rule. Putting the rule in the value object means the three
 * cannot disagree: a hand-edited file with a 4000-character description is reduced to the same thing
 * a typed one would be, and the self-checks that pin the boundaries call exactly the code the game
 * does. Truncation is measured in <em>code points</em> rather than in {@code char}s so a limit of
 * 512 never cuts an emoji or a rare CJK character in half.
 *
 * <h2>Immutable on purpose</h2>
 * A detail record is read from the store several times a frame by screens that are drawing, and
 * written by dialogs. A mutable object shared between those two would be edited while it is being
 * drawn -- so every change goes through {@link #withDescription}, {@link #withTags} or
 * {@link #withFavorite}, and the store is told separately. The modification time is part of the
 * value for the same reason the content is: it is what a later session would compare.
 */
public final class PlaceDetails {

    /** Longest description in characters (code points), newlines included. */
    public static final int MAX_DESCRIPTION = 512;

    /** Most tags one place may carry. */
    public static final int MAX_TAGS = 16;

    /** Longest single tag in characters (code points). */
    public static final int MAX_TAG_LENGTH = 8;

    /** Nothing written yet. The value a place with no detail record reads back as. */
    public static final PlaceDetails EMPTY = new PlaceDetails("", List.of(), false, 0L);

    private final String description;
    private final List<String> tags;
    private final boolean favorite;
    private final long updatedAt;

    public PlaceDetails(String description, List<String> tags, boolean favorite, long updatedAt) {
        this.description = description == null ? "" : description;
        this.tags = tags == null ? List.of() : List.copyOf(tags);
        this.favorite = favorite;
        this.updatedAt = updatedAt;
    }

    /** A detail with the content given and no modification time yet; the store stamps it on save. */
    public static PlaceDetails of(String description, List<String> tags, boolean favorite) {
        return new PlaceDetails(description, tags, favorite, 0L);
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

    public boolean favorite() {
        return favorite;
    }

    public long updatedAt() {
        return updatedAt;
    }

    /**
     * Whether there is nothing to store: no description, no tags and not a favourite.
     *
     * <p>The store deletes a record that becomes empty rather than keeping a row of blanks, which is
     * what makes "clear everything" mean the same thing as "never wrote anything" -- including for
     * the unlinked-details screen, where an empty record would be a row with nothing to identify it.
     */
    public boolean isEmpty() {
        return description.isBlank() && tags.isEmpty() && !favorite;
    }

    public PlaceDetails withDescription(String text) {
        return new PlaceDetails(text, tags, favorite, updatedAt);
    }

    public PlaceDetails withTags(List<String> newTags) {
        return new PlaceDetails(description, newTags, favorite, updatedAt);
    }

    public PlaceDetails withFavorite(boolean favoured) {
        return new PlaceDetails(description, tags, favoured, updatedAt);
    }

    public PlaceDetails withUpdatedAt(long millis) {
        return new PlaceDetails(description, tags, favorite, millis);
    }

    /**
     * This detail with everything brought inside the limits: description trimmed and shortened,
     * tags trimmed, de-duplicated, shortened and capped.
     *
     * <p>The one normalisation every writer goes through. It is deliberately lossy in one direction
     * only -- it never adds anything -- so calling it twice is the same as calling it once, which is
     * what a store that normalises on both read and write needs.
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
            // Case-insensitive de-duplication, keeping the spelling first written: "MTR" and "mtr" are
            // one tag to a reader, and two chips that differ only in case look like a mistake.
            if (seen.add(trimmed.toLowerCase(Locale.ROOT))) {
                clean.add(trimmed);
            }
            if (clean.size() >= MAX_TAGS) {
                break;
            }
        }
        return new PlaceDetails(text, List.copyOf(clean), favorite, updatedAt);
    }

    /**
     * A one-line preview of the description: whitespace runs and newlines collapsed, shortened to
     * {@code maxChars} characters.
     *
     * <p>One line rather than a prefix of the raw text because every caller of this draws a preview
     * in a place that has room for exactly one line -- a map tooltip, a search result row, a card's
     * collapsed body -- and a preview that began with a newline would draw nothing at all.
     *
     * @param maxChars most characters the preview may show, before the caller adds an ellipsis
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
     * The whole description as a truncated payload string, for the browser map.
     *
     * <p>Different from {@link #summary} on purpose: the web page has a card that can wrap, so a
     * multi-line description is worth sending with its lines intact for the truncated view, and the
     * page asks for the full text only when the player opens it. Newlines survive here; the card's
     * CSS decides how many of them it shows.
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
     * {@code text} shortened to at most {@code limit} code points, never splitting a surrogate pair.
     *
     * <p>{@code String.substring} counts UTF-16 units: a description cut at 512 that way can end in
     * half of a surrogate pair, which is not text any more and which every later consumer -- JSON,
     * a font, a file -- would have to defend against separately.
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
                && description.equals(that.description) && tags.equals(that.tags);
    }

    @Override
    public int hashCode() {
        int result = description.hashCode();
        result = 31 * result + tags.hashCode();
        result = 31 * result + Boolean.hashCode(favorite);
        result = 31 * result + Long.hashCode(updatedAt);
        return result;
    }

    @Override
    public String toString() {
        return "PlaceDetails[description=" + lengthInCodePoints(description) + "cp, tags=" + tags
                + ", favorite=" + favorite + "]";
    }
}
