package bili.dongsz.howtogo.road;

/**
 * Road grades. color() and width() are rendering defaults; drawing code asks RoadConfig.roadColor because players may override per class. Pace per grade lives with TravelMode.
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

    /** Default ARGB colour; the configured colour may differ. */
    public int color() {
        return color;
    }

    /** Nominal width in blocks. */
    public double width() {
        return width;
    }

    /** Configured id to class, or null for an unknown id; null bans nothing. */
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
