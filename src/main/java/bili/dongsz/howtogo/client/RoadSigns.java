package bili.dongsz.howtogo.client;

/**
 * The parts of a road's sign card that every map draws the same way.
 *
 * <h2>Why this is shared rather than written three times</h2>
 * A sign card is drawn on three maps -- Xaero's World Map, the destination picker's map and the
 * destination page on MCphone -- and the two things that make it read as a sign rather than as a
 * coloured rectangle are the same on all three: the room around the number that stops the plate
 * being a box drawn round it, and the ink, which has to be chosen from the card's own colour because
 * a player may recolour any category. The <em>sizes</em> are not shared, and deliberately: the world
 * map scales its card with the zoom and the other two draw it at the font's own size, so only the
 * proportions and the contrast rule live here.
 *
 * <p>The room is in the card's own font units on the world map (where the whole card is scaled) and
 * in pixels on the fixed-size maps; the numbers are the same because the world map's card is
 * measured at a factor of one unit, which is what keeps one road's shield the same shape on every
 * screen it appears on.
 */
public final class RoadSigns {

    /** Room either side of the number inside a card. */
    public static final int PAD_X = 3;

    /** Room above and below the card's lines. */
    public static final int PAD_Y = 2;

    /** Room between the number and the mixed card's second line. */
    public static final int LINE_GAP = 1;

    /** The two inks a card's text and border may be drawn in. */
    public static final int INK_LIGHT = 0xFFFFFFFF;
    public static final int INK_DARK = 0xFF10141A;

    /** The luminance above which a card counts as light and is lettered in the dark ink. */
    private static final int DARK_INK_ABOVE = 140;

    private RoadSigns() {
    }

    /**
     * The ink a card is lettered in, chosen from the colour the card actually paints.
     *
     * <p>Not from the category: every category's colour is the player's to change, and the card has
     * to stay readable after a recolour. The only thing that decides it is the background, and the
     * weights are the eye's -- green counts for far more than blue -- rather than a plain average,
     * which would call the amber provincial sign dark and the red national one light by accident.
     *
     * <p>The border takes the same colour as the text, so the whole card is one contrast decision:
     * a frame from a separate rule would be a second answer to "is this card dark".
     */
    public static int ink(int background) {
        int red = (background >> 16) & 0xFF;
        int green = (background >> 8) & 0xFF;
        int blue = background & 0xFF;
        int luminance = (red * 299 + green * 587 + blue * 114) / 1000;
        return luminance > DARK_INK_ABOVE ? INK_DARK : INK_LIGHT;
    }
}
