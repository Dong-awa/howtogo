package bili.dongsz.howtogo.place;

/**
 * Where a place came from, and what that source allows.
 *
 * <h2>Why the ids live here rather than in the sources</h2>
 * The destination sources own the strings -- {@code PoiDestinationSource.ID} and friends -- but the
 * place storage is in the portable source root and knows nothing about Minecraft, so it cannot hold
 * a reference to a client class. The literals are repeated here, and a self-check asserts that each
 * one still equals the source's own id: if a source is renamed, that check fails rather than the
 * details of every place from it silently becoming "unlinked".
 *
 * <h2>Manual versus automatic</h2>
 * The distinction the whole edit model rests on. A manual place is a node in the player's own road
 * network: the player can delete it, and deleting it deletes its details with it. Every other source
 * describes something that belongs to the world or to another mod -- a station, a waypoint, a
 * generated structure -- and this mod only records what the player wrote about it. Such a place is
 * never deletable through this mod: there is nothing here to delete it from, and offering a delete
 * button would be offering to remove a copy of somebody else's data.
 *
 * <h2>Addon sources</h2>
 * A source another mod registered has an id this mod has never seen, and there is no list of them to
 * put in an enum. It is treated as automatic: registered destinations carry an id from their own
 * source, and the only thing that could delete one is the mod that owns it.
 */
public final class PlaceSources {

    /** The player's own hand-placed landmarks; {@code PoiDestinationSource.ID}. */
    public static final String MANUAL = "poi";

    /** MTR stations; {@code MtrStationSource.ID}. */
    public static final String MTR = "mtr_station";

    /** Xaero's Minimap waypoints; {@code XaeroWaypointSource.ID}. */
    public static final String XAERO = "xaero_waypoint";

    /** Create's train stations; {@code CreateStationSource.ID}. */
    public static final String CREATE = "create_station";

    /** Structures RoadWeaver connected; {@code RoadWeaverStructureSource.ID}. */
    public static final String ROADWEAVER = "roadweaver";

    private PlaceSources() {
    }

    /** Whether the source is the player's own road network. */
    public static boolean isManual(String sourceId) {
        return MANUAL.equals(sourceId);
    }

    /** Whether the source is anything other than the player's own road network. */
    public static boolean isAutomatic(String sourceId) {
        return !isManual(sourceId);
    }

    /** Whether a place from this source may be deleted by this mod. Only the player's own may. */
    public static boolean deletable(String sourceId) {
        return isManual(sourceId);
    }

    /**
     * The translation key of the source's full name, or empty for a source this mod does not know.
     *
     * <p>Empty means "ask the registered source for its own display name" (see
     * {@code Destinations#sourceLabel}), which is the only honest answer for an addon.
     */
    public static String labelKey(String sourceId) {
        if (sourceId == null) {
            return "";
        }
        return switch (sourceId) {
            case MANUAL -> "hud.howtogo.place.source.manual";
            case MTR -> "hud.howtogo.place.source.mtr";
            case XAERO -> "hud.howtogo.place.source.xaero";
            case CREATE -> "hud.howtogo.place.source.create";
            case ROADWEAVER -> "hud.howtogo.place.source.roadweaver";
            default -> "";
        };
    }

    /**
     * The translation key of the source's short name, for the badge on a list row or a map marker.
     *
     * <p>Separate from {@link #labelKey} because a badge is a few characters wide: "机械动力" is four
     * characters where the full name is six, and on a list row of a 176-unit column that difference is
     * the name the row exists to show.
     */
    public static String shortLabelKey(String sourceId) {
        if (sourceId == null) {
            return "";
        }
        return switch (sourceId) {
            case MANUAL -> "hud.howtogo.place.source.manual.short";
            case MTR -> "hud.howtogo.place.source.mtr.short";
            case XAERO -> "hud.howtogo.place.source.xaero.short";
            case CREATE -> "hud.howtogo.place.source.create.short";
            case ROADWEAVER -> "hud.howtogo.place.source.roadweaver.short";
            default -> "";
        };
    }

    /**
     * Whether a source id is one of the five this mod ships.
     *
     * <p>For a caller that wants to know whether an unknown id is an addon rather than a mistake:
     * the destination registry is the authority on whether a source exists, this is only the
     * built-in half of the answer.
     */
    public static boolean isBuiltIn(String sourceId) {
        return !labelKey(sourceId).isEmpty();
    }
}
