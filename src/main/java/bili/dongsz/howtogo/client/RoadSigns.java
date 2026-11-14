package bili.dongsz.howtogo.client;

/**
 * The parts of a road's label that every map draws the same way.
 * Four maps draw the same sign card (Xaero's world map, the destination picker's map, the main screen's map, MCphone's destination page); only the proportions and the ink rule are shared. The world map scales its card with the zoom, the other three draw it at a fixed fraction of the font. Room constants are in font units on the world map and in pixels on the fixed-size maps, the same numbers because the world map measures its card at a unit factor.
 * LABEL_CLEARANCE_PX and LABEL_MIN_SCALE are constants here because a fixed-size map has no scale signal to derive them from.
 */
public final class RoadSigns {

    /** Room either side of the number inside a card. */
    public static final int PAD_X = 3;

    /** Room above and below the card's lines. */
    public static final int PAD_Y = 2;

    /** Room between the number and the mixed card's second line. */
    public static final int LINE_GAP = 1;

    /**
     * Room a label keeps from every label already on the map, in screen pixels. Every fit test inflates the painted box by this much, so two labels whose boxes merely miss still stand apart; the world map uses two pixels, these maps three because they are a tenth of its size.
     */
    public static final double LABEL_CLEARANCE_PX = 3.0;

    /**
     * Least zoom, in screen pixels per block, at which a fixed-size map writes road labels. It is the zoom at which ordinary roads stop being drawn, so a label never outlives its road. A classified road past this zoom falls back to its name rather than to nothing.
     */
    public static final double LABEL_MIN_SCALE = 0.22;

    /** The two inks a card's text and border may be drawn in. */
    public static final int INK_LIGHT = 0xFFFFFFFF;
    public static final int INK_DARK = 0xFF10141A;

    /**
     * Scale a sign card is drawn at on maps whose text does not follow their zoom. The three maps share it so one road's shield looks the same everywhere.
     */
    public static final float FIXED_CARD_SCALE = 0.58F;

    /**
     * Corner rounding of a card, in screen pixels; small enough that a short card stays a card, not a pill.
     */
    public static final int CARD_CORNER_PX = 2;

    /**
     * How far a rounded box's row is inset from its left and right edges, in pixels. Drawing rows with this inset gives quarter-circle corners, so a card can be filled without a shader or a texture. radius is clamped to half the shorter side, so height / 2 gives a capsule -- the shape a line's name carries.
     * @param row    the row being drawn, in the same space as top
     * @param top    the box's top row
     * @param bottom the box's bottom row, exclusive
     * @param radius the rounding radius in pixels, clamped to half the box's height
     */
    public static int roundedInset(int row, int top, int bottom, int radius) {
        int height = bottom - top;
        int r = Math.max(0, Math.min(radius, height / 2));
        if (r <= 0) {
            return 0;
        }
        int edge = Math.min(row - top, bottom - 1 - row);
        if (edge >= r) {
            return 0;
        }
        double dy = r - edge - 0.5;
        return (int) Math.round(r - Math.sqrt(Math.max(0.0, (double) r * r - dy * dy)));
    }

    /** Luminance above which a card is lettered in the dark ink. */
    private static final int DARK_INK_ABOVE = 140;

    private RoadSigns() {
    }

    /**
     * Factor a road's name is drawn at on a map whose strokes do not scale with its zoom. The name's height is capped by the road's drawn width; where the world map names a thin road after zooming in, a fixed-stroke map cannot, so the cap stops at floor instead.
     * @param roadWidthPx the width the road is painted at, in screen pixels
     * @param lineHeight  the font's line height, in screen pixels
     * @param floor       the smallest factor a name may be drawn at
     */
    public static float nameFactor(double roadWidthPx, double lineHeight, float floor) {
        if (!(lineHeight > 0.0)) {
            return 1.0F;
        }
        float fit = (float) (roadWidthPx / lineHeight);
        return Math.min(1.0F, Math.max(floor, fit));
    }

    /**
     * The ink a card is lettered in, chosen from the colour it actually paints rather than from the category, which the player may recolour. The weights are perceptual (green counts for far more than blue), and the border takes the same colour so the card is one contrast decision.
     */
    public static int ink(int background) {
        int red = (background >> 16) & 0xFF;
        int green = (background >> 8) & 0xFF;
        int blue = background & 0xFF;
        int luminance = (red * 299 + green * 587 + blue * 114) / 1000;
        return luminance > DARK_INK_ABOVE ? INK_DARK : INK_LIGHT;
    }
}
