package bili.dongsz.howtogo.client;

/**
 * How a road's own labels are drawn on the map: its name, its sign, or both.
 *
 * <h2>Why the sign and the name are alternatives</h2>
 * A road carries two identifiers and they are not the same kind of thing. The name is what the
 * player called the road -- "滨江大道" -- and reads as a word lying along the line; the sign is what
 * the road is numbered and classified as -- a small card in its category's colour with "G318" in
 * it, the way a Chinese road sign marks a highway. A map with both on every road is a map where the
 * cards cover the names; a map with neither is the map this mod always drew. So which one a road
 * shows is the player's setting, with the mod's original behaviour as the default.
 *
 * <p>Mixed keeps both in one object: the number on the card's first line and the road's name under
 * it, inside the same card, shrunk to the card's width. That is the only way both can be read on a
 * road without one covering the other.
 *
 * <p>The id is the on-disk spelling rather than the constant name, for the same reason
 * {@link MapDisplayMode} has one: the Fabric arm of the config is a hand-edited file written in
 * lower case, while NeoForge's spec writes the constant name. The reader accepts both.
 */
public enum RoadLabelStyle {

    /**
     * The name only: the behaviour every existing map has, and the default.
     *
     * <p>A sign is still not drawn, but nothing about an unclassified road changes -- a road with no
     * category and no number has always had only a name to show.
     */
    NAME("name"),

    /**
     * The sign when the road has one, and the name when it does not.
     *
     * <p>"Has one" is a number: the card's whole content is the number, so a category chosen without
     * one is not a sign yet and the road keeps its name.
     */
    SIGN("sign"),

    /**
     * Both, in one card: the number on top and the name below it, scaled to the card's width.
     *
     * <p>For a road that has a sign, the card replaces the name label rather than sitting beside it;
     * for a road without one, this mode draws exactly what {@link #NAME} draws.
     */
    MIXED("mixed");

    private final String id;

    RoadLabelStyle(String id) {
        this.id = id;
    }

    /** The spelling this style is written as in a config file. */
    public String id() {
        return id;
    }

    /** The style the id names, or null when it names none. Case-insensitive on both spellings. */
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

    /**
     * The style in force when nothing has been configured: the map as it has always looked.
     *
     * <p>A category with no number draws nothing either way, so a player who never fills one in sees
     * no change from this default at all -- the setting only starts to matter once signs exist.
     */
    public static RoadLabelStyle defaultStyle() {
        return NAME;
    }
}
