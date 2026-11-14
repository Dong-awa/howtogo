package bili.dongsz.howtogo.client;

/**
 * The parts of a road's label that every map draws the same way.
 *
 * <h2>Why this is shared rather than written three times</h2>
 * A sign card is drawn on four maps -- Xaero's World Map, the destination picker's map, the main
 * screen's map and the destination page on MCphone -- and the things that make it read as a sign
 * rather than as a coloured rectangle are the same on all four: the room around the number that
 * stops the plate being a box drawn round it, and the ink, which has to be chosen from the card's
 * own colour because a player may recolour any category. The <em>sizes</em> are not shared, and
 * deliberately: the world map scales its card with the zoom and the other three draw it at a fixed
 * fraction of the font, so only the proportions and the contrast rule live here.
 *
 * <p>The room is in the card's own font units on the world map (where the whole card is scaled) and
 * in pixels on the fixed-size maps; the numbers are the same because the world map's card is
 * measured at a factor of one unit, which is what keeps one road's shield the same shape on every
 * screen it appears on.
 *
 * <h2>The two rules a fixed-size map cannot get from the map itself</h2>
 * A map that scales its text with its zoom decides when a label is too crowded or too large by
 * watching the scale; a map that draws every label at one size has no such signal, so the two
 * decisions are constants here: how much room a label claims around itself
 * ({@link #LABEL_CLEARANCE_PX}), and the zoom below which a plate steps back to the road's name
 * ({@link #LABEL_MIN_SCALE}). A name's size on such a map is capped by the road under it
 * ({@link #nameFactor}).
 */
public final class RoadSigns {

    /** Room either side of the number inside a card. */
    public static final int PAD_X = 3;

    /** Room above and below the card's lines. */
    public static final int PAD_Y = 2;

    /** Room between the number and the mixed card's second line. */
    public static final int LINE_GAP = 1;

    /**
     * How much room a road label keeps from every label already on the map, in screen pixels.
     *
     * <p>Kept as an inflated claim rather than as the painted rectangle: every fit test puts this
     * much round the box it paints, so two labels whose boxes merely miss each other still stand a
     * gap apart, and a pair that would read as one long word reads as two. The world map does the
     * same with its own clearance and two pixels; the maps that draw at a fixed size take three,
     * because they are a tenth of its size -- the same two pixels that separate two names on a full
     * screen leave them touching in a panel or a phone's map area this small.
     */
    public static final double LABEL_CLEARANCE_PX = 3.0;

    /**
     * The least zoom, in screen pixels per block, at which a fixed-size map writes road labels at all.
     *
     * <p>Neither a name nor a card follows the zoom on these maps -- both are words drawn at a size
     * that can be read -- so a zoomed-out view is the one on which labels multiply: an opaque plate
     * for every classified road in the region, or a name on every road, laid over the strokes the
     * player zoomed out to look at. Below this fraction of a pixel per block the map spans a couple of
     * thousand blocks and the labels are what covers it.
     *
     * <p>The value is not picked to taste: it is the zoom at which ordinary roads stop being drawn
     * (see the map filter's own threshold). A label names a road, and a zoom that has already given up
     * on the road has nothing for the label to stand on. A card uses it too, and for the same reason --
     * with one further step: a classified road past this zoom falls back to its name rather than to
     * nothing, so the road keeps a label while the road is still on the map.
     */
    public static final double LABEL_MIN_SCALE = 0.22;

    /** The two inks a card's text and border may be drawn in. */
    public static final int INK_LIGHT = 0xFFFFFFFF;
    public static final int INK_DARK = 0xFF10141A;

    /**
     * The scale a sign card is drawn at on the maps whose text does not follow their zoom.
     *
     * <p>The destination picker, the main screen's viewer and the phone page draw every label at one
     * size, so the card there is a fixed fraction of the font. It used to be 0.72 of it, which read as
     * a plate bigger than the streets it stood on once the card's border and padding were counted;
     * 0.58 keeps the number legible and the plate a detail of the road rather than an object the map
     * is about. The three maps share the number so the same road's shield is the same shield on all
     * of them, which is the reason the world map's card is a fraction of its own label zoom instead
     * of a size of its own.
     */
    public static final float FIXED_CARD_SCALE = 0.58F;

    /**
     * How far a card's corners are rounded, in screen pixels.
     *
     * <p>Small enough that a card a dozen pixels tall still reads as a card rather than as a pill,
     * and large enough that the corners stop being the sharpest thing on the map -- a sign is a
     * printed plate, and a printed plate has rounded corners.
     */
    public static final int CARD_CORNER_PX = 2;

    /**
     * How far a rounded box's row is inset from its left and right edges, in pixels.
     *
     * <p>The whole of the rounding is this one function: a box drawn row by row with this inset has
     * quarter-circle corners, which is how the maps that can only fill rectangles draw a rounded
     * card without a shader or a texture. {@code radius} is clamped to half the shorter side, so
     * passing {@code height / 2} draws a capsule -- the shape a line's name carries, so that it
     * cannot be mistaken for a road's sign.
     *
     * <p>Lives here rather than in each map for the same reason the card's proportions do: the
     * world map, the viewer, the picker and the phone page all draw the same shape, and four copies
     * of this arithmetic would drift apart about what a rounded corner is.
     *
     * @param row    the row being drawn, in the same space as {@code top}
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

    /** The luminance above which a card counts as light and is lettered in the dark ink. */
    private static final int DARK_INK_ABOVE = 140;

    private RoadSigns() {
    }

    /**
     * The factor a road's name is drawn at on a map whose strokes do not scale with its zoom.
     *
     * <p>A name lies on its road, so its height is capped by the road's drawn width: a word taller
     * than the street it names reads as a word standing beside the street. The world map states that
     * as the whole of the rule, because its strokes widen with the zoom and a road too thin to carry
     * a name is simply named once the player zooms in. A map whose strokes are a fixed handful of
     * pixels never gets that closer zoom, so the cap stops at {@code floor} instead: the name is
     * drawn at the smallest legible size rather than being left off at every zoom.
     *
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
