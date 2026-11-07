package bili.dongsz.howtogo.place;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * What the player wrote about one place: a description, a set of tags, one single-value tag (the
 * place's <em>category</em>), and a favourite flag.
 *
 * <h2>What the category is, and why it is not a tag</h2>
 * The user asked for different kinds of place to carry different entries: a shop shows its type, a
 * resource point shows its resource type, a station shows the lines it belongs to -- and those are
 * one value each, not a list. Putting them in {@link #tags()} would have made the type one of the
 * tags: it would be sorted among them, could be removed twice by the same command, and the card
 * would have no way to know which chip was the type and which was an ordinary label. A field of its
 * own is also what the storage needs: an older build reading the file must not see the type as an
 * anonymous tag and draw it grey.
 *
 * <p>Deliberately <em>one</em> value with the same length limit as a tag, rather than an enum.
 * "Shop type" is as open a vocabulary as the names on the player's map -- 便利店, 药房, 银行 -- and
 * an enumeration here would make the feature a fixed list that every new kind of place has to be
 * added to. Empty means "this place has no type to show", which is also what a place that is not a
 * shop or a resource point reads as.
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
 * drawn -- so every change goes through {@link #withDescription}, {@link #withTags},
 * {@link #withCategory} or {@link #withFavorite}, and the store is told separately. The modification
 * time is part of the value for the same reason the content is: it is what a later session would
 * compare.
 */
public final class PlaceDetails {

    /** Longest description in characters (code points), newlines included. */
    public static final int MAX_DESCRIPTION = 512;

    /** Most tags one place may carry. */
    public static final int MAX_TAGS = 16;

    /** Longest single tag in characters (code points). */
    public static final int MAX_TAG_LENGTH = 8;

    /**
     * The category when there is none: {@code ""}.
     *
     * <p>A constant rather than a sprinkle of empty-string literals so the one meaning -- "no type
     * written" -- is stated once, the way {@link #EMPTY} states "nothing written at all". It is also
     * what the storage writes as "field absent", which is what makes the format additive: a record
     * from a build without this field is a record with no category, not an error.
     */
    public static final String EMPTY_CATEGORY = "";

    /** Nothing written yet. The value a place with no detail record reads back as. */
    public static final PlaceDetails EMPTY = new PlaceDetails("", List.of(), EMPTY_CATEGORY, false, 0L);

    private final String description;
    private final List<String> tags;
    private final String category;
    private final boolean favorite;
    private final long updatedAt;

    /**
     * A detail with every field, including the category.
     *
     * <p>The full constructor. {@code category} is stored as given -- blank is trimmed and shortened
     * by {@link #normalize()}, not here -- for the same reason the description and the tags are: a
     * value object that silently rewrites what a caller handed it is a value object whose caller
     * cannot predict what it will read back. The only substitution made here is {@code null} to the
     * empty string, because a null category is the one thing every reader would otherwise have to
     * defend against.
     */
    public PlaceDetails(String description, List<String> tags, String category, boolean favorite,
                        long updatedAt) {
        this.description = description == null ? "" : description;
        this.tags = tags == null ? List.of() : List.copyOf(tags);
        this.category = category == null ? EMPTY_CATEGORY : category;
        this.favorite = favorite;
        this.updatedAt = updatedAt;
    }

    /**
     * The four-argument form, kept for the call sites written before places had a category.
     *
     * <p>Every one of those callers -- the details store, the draft editor, the self-checks -- means
     * "nothing changes about the category", and for all of them that is the empty category. Keeping
     * the overload rather than editing a dozen call sites is what makes adding a field to this class
     * a change to this file rather than a change to the whole mod.
     */
    public PlaceDetails(String description, List<String> tags, boolean favorite, long updatedAt) {
        this(description, tags, EMPTY_CATEGORY, favorite, updatedAt);
    }

    /** A detail with the content given and no modification time yet; the store stamps it on save. */
    public static PlaceDetails of(String description, List<String> tags, String category,
                                  boolean favorite) {
        return new PlaceDetails(description, tags, category, favorite, 0L);
    }

    /**
     * The three-argument {@code of}, for a place whose category is not being edited.
     *
     * <p>Kept for the same reason as the four-argument constructor: existing callers -- and the
     * Harness -- construct details without a category, and "no category" is exactly what they mean.
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

    /**
     * The place's type or resource type, or {@link #EMPTY_CATEGORY} when none was written.
     *
     * <p>The value the card draws as an orange chip and the dialog's type box edits. Never null, so
     * a caller can compare it with the empty string without a null check -- a null category and an
     * empty one mean the same thing, and two spellings of one meaning is how a UI ends up drawing a
     * grey chip with nothing in it.
     */
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
     * Whether there is nothing to store: no description, no tags, no category and not a favourite.
     *
     * <p>The store deletes a record that becomes empty rather than keeping a row of blanks, which is
     * what makes "clear everything" mean the same thing as "never wrote anything" -- including for
     * the unlinked-details screen, where an empty record would be a row with nothing to identify it.
     * The category counts because a record that is only a type would otherwise be written as an
     * otherwise-empty row and deleted on the next read, which would look like the type was lost.
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
     * This detail with the category replaced, everything else carried along.
     *
     * <p>The category is normalised by {@link #normalize()} like every other field; a caller that
     * clears the type passes {@code ""} (or null, which the constructor turns into it) and the next
     * save drops the field.
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
     * This detail with everything brought inside the limits: description trimmed and shortened,
     * tags trimmed, de-duplicated, shortened and capped, category trimmed and shortened.
     *
     * <p>The one normalisation every writer goes through. It is deliberately lossy in one direction
     * only -- it never adds anything -- so calling it twice is the same as calling it once, which is
     * what a store that normalises on both read and write needs.
     *
     * <p>The category has no de-duplication and no cap beyond its length: there is exactly one, and
     * a blank one -- which is how a typed space-only value normalises -- becomes
     * {@link #EMPTY_CATEGORY} rather than a one-space type.
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
        // A category is one short label: trimmed and cut to a tag's length, and whitespace-only is
        // the same as absent. Truncated rather than refused so a hand-edited file reads as the
        // first eight characters of what it said instead of as no category at all.
        String cleanCategory = truncate(category.strip(), MAX_TAG_LENGTH);
        return new PlaceDetails(text, List.copyOf(clean), cleanCategory, favorite, updatedAt);
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
