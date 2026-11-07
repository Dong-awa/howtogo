package bili.dongsz.howtogo.place;

import bili.dongsz.howtogo.road.PlaceKind;

import java.util.List;

/**
 * What a place of each kind shows, and in what order.
 *
 * <h2>Why this is a table rather than an if in each view</h2>
 * The user asked for the kinds to differ: a station's card shows the lines it belongs to, a shop's
 * shows its type, an ordinary place shows neither -- and the order differs too, because the first
 * chip is the one a reader uses to recognise the place. Both views that draw details (the read-only
 * card and the edit dialog) plus any future one have to agree on that order, or the same place will
 * list the same facts in two orders in two places. A single function in the portable source root is
 * what makes them agree by construction, and it is pure Java, so the self-checks can hold the order
 * without a game running.
 *
 * <h2>Why the order is what it is</h2>
 * The kinds put their most identifying entry first. A station is identified by the lines calling at
 * it -- that is what a station <em>is</em> to a traveller -- so LINES leads. A shop or a resource
 * point is identified by what it sells or yields, which is the type, so TYPE leads. An ordinary
 * place has no such field, so its source leads: it is the one thing about it the player did not
 * write. The description is always last because it is the long, wrapping part; putting it anywhere
 * else would push the chips below the fold on a card that is already short.
 *
 * <h2>Why the sections are a list of constants</h2>
 * These are asked for once per frame by a card that is being drawn. Returning a freshly built
 * {@code List.of(...)} each time would hand the garbage collector a list per frame for an answer
 * that cannot change, so each kind's order is built once and handed out as the same immutable list.
 * The caller must not modify it -- it is shared -- which is the same rule every other constant in
 * this mod follows.
 */
public final class PlaceSections {

    /** One block of a place's details; the order of the enum is not the display order. */
    public enum Section {
        /** The public transport lines calling at a station. */
        LINES,
        /** Where the place came from: hand-placed, MTR, Xaero, Create, RoadWeaver, an addon. */
        SOURCE,
        /** The player's free-form tags. */
        TAGS,
        /** A shop's type or a resource point's resource type; never shown for other kinds. */
        TYPE,
        /** The long text, always last. */
        DESCRIPTION
    }

    /**
     * The lang key of the "type" caption, used for a shop's category.
     *
     * <p>A constant here rather than in the screen that draws it: the same word labels the field in
     * the dialog and would label it in any future view, and two literals is how a caption and its
     * translation drift apart.
     */
    public static final String TYPE_LABEL_KEY = "screen.howtogo.place_sections.type";

    /** The lang key of the "resource type" caption, which is a different word on purpose. */
    public static final String RESOURCE_TYPE_LABEL_KEY =
            "screen.howtogo.place_sections.resource_type";

    private static final List<Section> PLACE_SECTIONS =
            List.of(Section.SOURCE, Section.TAGS, Section.DESCRIPTION);
    private static final List<Section> STATION_SECTIONS =
            List.of(Section.LINES, Section.SOURCE, Section.TAGS, Section.DESCRIPTION);
    private static final List<Section> SHOP_SECTIONS =
            List.of(Section.TYPE, Section.TAGS, Section.SOURCE, Section.DESCRIPTION);
    private static final List<Section> RESOURCE_SECTIONS =
            List.of(Section.TYPE, Section.TAGS, Section.SOURCE, Section.DESCRIPTION);

    private PlaceSections() {
    }

    /**
     * The blocks this kind of place shows, in the order they are drawn.
     *
     * <p>A null kind -- a caller that has not resolved one yet -- is treated as {@link
     * PlaceKind#PLACE}, which is the same fallback {@link PlaceKind#byId} uses and for the same
     * reason: every place that predates kinds was an ordinary place, and an empty card would be a
     * worse answer than the plain one.
     *
     * @return an immutable list; do not modify it
     */
    public static List<Section> of(PlaceKind kind) {
        if (kind == null) {
            return PLACE_SECTIONS;
        }
        return switch (kind) {
            case PLACE -> PLACE_SECTIONS;
            case STATION -> STATION_SECTIONS;
            case SHOP -> SHOP_SECTIONS;
            case RESOURCE -> RESOURCE_SECTIONS;
        };
    }

    /**
     * The caption of the {@link Section#TYPE} block for this kind.
     *
     * <p>Two words, not one: a shop has a "type" and a resource point has a "resource type", and the
     * user asked for those two labels. The distinction is only meaningful for the kinds that show
     * the block; for the others this answers the generic word, which is what a caller that asks
     * anyway (a future view that shows a type on every kind) would want.
     */
    public static String labelKey(PlaceKind kind) {
        return kind == PlaceKind.RESOURCE ? RESOURCE_TYPE_LABEL_KEY : TYPE_LABEL_KEY;
    }
}
