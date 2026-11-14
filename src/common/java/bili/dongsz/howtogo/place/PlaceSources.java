package bili.dongsz.howtogo.place;

/**
 * Where a place came from, and what that source allows.
 * The ids are literals repeated from the destination sources, because the portable storage root cannot reference client classes; a self-check asserts each still equals the source's own id.
 * A manual place (source poi) is a node in the player's own road network and may be deleted. Every other source describes something owned by the world or another mod: this mod only stores the player's notes about it and never deletes it. Addon sources are treated as automatic for the same reason.
 */
public final class PlaceSources {

    /** The player's own hand-placed landmarks; PoiDestinationSource.ID. */
    public static final String MANUAL = "poi";

    /** MTR stations; MtrStationSource.ID. */
    public static final String MTR = "mtr_station";

    /** Xaero's Minimap waypoints; XaeroWaypointSource.ID. */
    public static final String XAERO = "xaero_waypoint";

    /** Create's train stations; CreateStationSource.ID. */
    public static final String CREATE = "create_station";

    /** Structures RoadWeaver connected; RoadWeaverStructureSource.ID. */
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
     * The translation key of the source's full name, or empty for an unknown source.
     * Empty means ask the registered source for its own name via Destinations.sourceLabel.
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

    /** The translation key of the source's short name, for badges on a list row or map marker. */
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
     * Whether the id is one of the five sources this mod ships; the destination registry is the authority on whether a source exists.
     */
    public static boolean isBuiltIn(String sourceId) {
        return !labelKey(sourceId).isEmpty();
    }
}
