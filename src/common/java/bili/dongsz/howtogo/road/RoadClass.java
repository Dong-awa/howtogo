package bili.dongsz.howtogo.road;

/**
 * Road grades. The colour and width here drive rendering; what a grade is worth in time depends on
 * which vehicle is on it, so the pace lives with the {@code TravelMode} rather than here.
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

    /** ARGB colour used when drawing this class of road. */
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
