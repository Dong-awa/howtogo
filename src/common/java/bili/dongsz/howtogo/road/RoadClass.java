package bili.dongsz.howtogo.road;

/**
 * Road grades. The colour and width here drive rendering; what a grade is worth in time depends on
 * which vehicle is on it, so the pace lives with the {@code TravelMode} rather than here.
 *
 * <p>The colour is the class's <em>default</em>: a player may override it per class in the client
 * config's {@code road_colors} section, which is why drawing code asks {@code RoadConfig.roadColor}
 * rather than {@link #color()}. The enumeration's own value stays the one that answers "what is this
 * class when nothing has been configured", which the config's own defaults are written from.
 */
public enum RoadClass {

    HIGHWAY(0xFF3FA9F5, 7.0),
    ROAD(0xFF3FD07A, 5.0),
    PATH(0xFFC8A24A, 3.0),
    RAIL(0xFFFFA028, 4.0),
    WATER(0xFF2F6FE0, 6.0),
    ICE(0xFF9FE4FF, 5.0);

    private static final RoadClass[] VALUES = values();

    private final int color;
    private final double width;

    RoadClass(int color, double width) {
        this.color = color;
        this.width = width;
    }

    /** Default ARGB colour of this class of road; the configured colour may differ. */
    public int color() {
        return color;
    }

    /** Nominal width in blocks. */
    public double width() {
        return width;
    }

    /**
     * Class for a configured id, or null when the id names no class.
     *
     * <p>Null rather than a default so a typo in the avoid list is ignored instead of silently
     * banning a road the player never asked to avoid.
     */
    public static RoadClass byId(String id) {
        if (id != null) {
            for (RoadClass roadClass : VALUES) {
                if (roadClass.name().equalsIgnoreCase(id.trim())) {
                    return roadClass;
                }
            }
        }
        return null;
    }
}
