package bili.dongsz.howtogo.client;

/**
 * How a road's labels are drawn: its name, its number sign, or both.
 *
 * A sign is a card in the category colour carrying the road number; name and sign are
 * alternatives because both together cover the map. Mixed puts number and name in one card.
 * The id is the on-disk lowercase spelling; the reader also accepts the constant name.
 */
public enum RoadLabelStyle {

    /** Name only -- the original behaviour and the default. */
    NAME("name"),

    /** The sign when the road has a number, else the name. A category without a number is no sign. */
    SIGN("sign"),

    /** Both in one card: number on top, name below, scaled to card width. Replaces the name label. */
    MIXED("mixed");

    private final String id;

    RoadLabelStyle(String id) {
        this.id = id;
    }

    /** The spelling used in a config file. */
    public String id() {
        return id;
    }

    /** The style the id names, or null. Case-insensitive on both spellings. */
    public static RoadLabelStyle byId(String id) {
        if (id == null) {
            return null;
        }
        for (RoadLabelStyle style : values()) {
            if (style.id.equalsIgnoreCase(id) || style.name().equalsIgnoreCase(id)) {
                return style;
            }
        }
        return null;
    }

    /** The style when nothing is configured: the map as it always looked; no number means no change. */
    public static RoadLabelStyle defaultStyle() {
        return NAME;
    }
}
