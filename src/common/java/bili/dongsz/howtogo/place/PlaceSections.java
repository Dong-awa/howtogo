package bili.dongsz.howtogo.place;

import bili.dongsz.howtogo.road.PlaceKind;

import java.util.List;

/**
 * The blocks a place of each kind shows, and their display order.
 *
 * A station leads with its lines, a shop or resource point with its type, an ordinary place with its
 * source; the description is always last because it wraps. Shared by both detail views and pure Java,
 * so self-checks can hold the order.
 *
 * Each kind's list is built once and shared; callers must not modify it -- it is asked for per frame.
 */
public final class PlaceSections {

    /** One block of a place's details; enum order is not display order. */
    public enum Section {
        /** Public transport lines calling at a station. */
        LINES,
        /** Where the place came from: hand-placed, MTR, Xaero, Create, RoadWeaver, an addon. */
        SOURCE,
        /** The player's free-form tags. */
        TAGS,
        /** A shop's type or a resource point's resource type. */
        TYPE,
        /** The long text, always last. */
        DESCRIPTION
    }

    /** Lang key of the type caption; shared by the dialog and any future view so captions do not drift. */
    public static final String TYPE_LABEL_KEY = "screen.howtogo.place_sections.type";

    /** Lang key of the resource type caption -- a different word on purpose. */
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
     * The blocks this kind shows, in draw order.
     * A null kind is treated as PlaceKind#PLACE, matching PlaceKind#byId: every pre-kind place was ordinary.
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
     * The caption of the TYPE block for this kind.
     * A shop has a type and a resource point a resource type; other kinds get the generic word.
     */
    public static String labelKey(PlaceKind kind) {
        return kind == PlaceKind.RESOURCE ? RESOURCE_TYPE_LABEL_KEY : TYPE_LABEL_KEY;
    }
}
