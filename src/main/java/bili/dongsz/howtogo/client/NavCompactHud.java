package bili.dongsz.howtogo.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;

import static bili.dongsz.howtogo.client.HudDraw.fillRounded;
import static bili.dongsz.howtogo.client.HudDraw.outlineRounded;

/**
 * The readout-only card drawn in {@code xaero_minimap} mode: the same trip readout as the panel,
 * without the map half of it.
 *
 * <h2>Why this card exists</h2>
 * The navigation panel has always been two cards in one: a rotating mini-map of the roads, the route
 * and the places, and the trip readout under it. On an install with Xaero's Minimap the map half is a
 * second map beside the first -- same world, same corner of the screen, same scale the player expects
 * -- so the mode this card belongs to lets Xaero draw the map and keeps only the half that a map
 * cannot state: the turn, the distance to it, what is left of the trip, the progress bar. The mini-map
 * is not hidden here, it is simply not this card's job; nothing that belongs to the map is drawn on
 * it -- no map, no map labels, no north marker, no player arrow, no place markers.
 *
 * <h2>What it draws, and in what order</h2>
 * The rows are the panel's own, in the panel's own order, and at the panel's own offsets: the title
 * row carries the destination and the travel mode (the panel puts that pair on its header row), then
 * the turn arrow, the distance in the strong colour, the instruction on one line, the progress bar,
 * the remaining line, and -- when they apply -- the walking-fallback note and the text-to-speech
 * note. The offsets are the panel's constants on purpose: a player switching display mode should see
 * the same readout in a smaller card, not a readout that has been redesigned, and keeping the row
 * rhythm identical is what makes the two layouts read as one feature.
 *
 * <p>The arrow, the stop marker and the progress bar are not drawn here. They are in
 * {@link NavHudShapes}, shared with the panel, so the iconography cannot differ between modes.
 *
 * <h2>Size and placement</h2>
 * The card is as wide as its content, with a hard cap: past about 200 px a HUD card is not a HUD card
 * any more, so the instruction -- the one line that can be arbitrarily long, because it names the road
 * being entered -- is truncated to what fits. Height is likewise what the content needs, which is why
 * the narrator note adds rows rather than being clipped: it only appears when speech is unavailable,
 * and a note about a feature being broken must not be the thing that is silently cut off.
 *
 * <p>The corner comes from the config ({@link NavMapMode#compactAnchor()} and the two offsets), with
 * the default bottom-left chosen against Xaero's top-left default. A corner plus an offset rather
 * than absolute coordinates keeps the card the same distance from the edge at any window size and any
 * GUI scale, which is what a HUD element has to do.
 */
final class NavCompactHud {

    // ------------------------------------------------------------------- layout

    /** Distance from the screen edge; the full panel keeps the same margin. */
    private static final int MARGIN = 6;
    /** Padding between the card's edge and its content, the same as the panel's. */
    private static final int PADDING = 6;
    /** Corner rounding, the same as the panel's. */
    private static final int CORNER_RADIUS = 4;
    /** The title row: the font's nine pixels plus the panel's three-pixel gap under its header. */
    private static final int TITLE_HEIGHT = 12;
    /** Gap kept between the destination and the mode label on the title row, as on the panel's header. */
    private static final int TITLE_GAP = 6;
    /** Left gutter of the text column, left clear for the turn arrow -- the panel's READOUT_TEXT_X. */
    private static final int READOUT_TEXT_X = 26;
    /** Centre of the arrow inside that gutter, the panel's own x + 11. */
    private static final int ARROW_CENTRE_X = 11;
    /** Centre of the arrow down the body, the panel's own y + 12. */
    private static final int ARROW_CENTRE_Y = 12;
    /** Bottom of the arrow's cell, measured from the top of the body: the centre row plus its shaft. */
    private static final int ARROW_BOTTOM = 20;
    /** Top of the distance row, in the panel's readout block. */
    private static final int DISTANCE_Y = 2;
    /** Top of the instruction row, in the panel's readout block. */
    private static final int INSTRUCTION_Y = 14;
    /** Top of the arrival / no-route line, which the panel draws six pixels into its readout block. */
    private static final int STATUS_Y = 6;
    /** Top of the progress bar, in the panel's readout block. */
    private static final int BAR_Y = 30;
    private static final int BAR_HEIGHT = 5;
    /** Top of the remaining line: the panel's bar top plus nine. */
    private static final int REMAINING_Y = 39;
    /** Top of the walking-fallback note: the panel's bar top plus twenty. */
    private static final int FALLBACK_Y = 50;
    /** Height of the font's line, used for the content bottom of every row. */
    private static final int LINE_HEIGHT = 9;
    /** Gap between the last row of the body and the narrator note, as in the panel. */
    private static final int HINT_GAP = 2;
    private static final int HINT_LINE_HEIGHT = 11;
    /** The panel gives the narrator note two lines; so does this card. */
    private static final int HINT_LINES = 2;
    /**
     * Widest the whole card may get. A HUD element shares the screen with the game and with whatever
     * map is being drawn beside it, and the one row that can run long -- the instruction, which names
     * the road -- is truncated rather than allowed to grow the card without bound.
     */
    private static final int MAX_WIDTH = 200;
    /** Widest the text column may be: the cap less the padding either side of it and the arrow gutter. */
    private static final int MAX_TEXT_WIDTH = MAX_WIDTH - PADDING * 2 - READOUT_TEXT_X;

    private NavCompactHud() {
    }

    // --------------------------------------------------------------------- draw

    /**
     * One frame of the compact card.
     *
     * <p>Positioning is the anchor resolved from the screen edge with {@link #MARGIN}, then the
     * configured offsets. A positive offset moves the card further into the screen, away from the edge
     * it hangs from -- the meaning the config documents -- so it is applied toward the middle on every
     * side rather than as a raw addition that would mean a different thing in each corner.
     */
    static void draw(GuiGraphics graphics, Minecraft mc, NavHudReadout readout) {
        if (readout.destinationName() == null) {
            return;
        }
        Font font = mc.font;

        // Width first, because the narrator note is wrapped to it and the height then follows from the
        // rows that exist. The cap applies to the card, so the text room is the cap less the padding
        // and the arrow gutter.
        int modeWidth = font.width(readout.modeLabel());
        int titleWidth = font.width(readout.headerLabel()) + TITLE_GAP + modeWidth;
        int textWidth = Math.min(naturalTextWidth(font, readout), MAX_TEXT_WIDTH);
        int innerWidth = Math.min(MAX_WIDTH - PADDING * 2,
                Math.max(titleWidth, READOUT_TEXT_X + textWidth));

        List<FormattedCharSequence> hintLines = readout.narratorHint() == null
                ? List.of()
                : font.split(readout.narratorHint(), innerWidth);
        int hintCount = Math.min(HINT_LINES, hintLines.size());

        // The two states that are not a live reading say one line beside the marker and no trip rows:
        // there is no progress to show when there is no route, and none left to show once arrived.
        boolean status = readout.arrived() || !readout.hasRoute();
        int contentBottom;
        if (status) {
            // The arrow reaches further down than the line of text beside it.
            contentBottom = ARROW_BOTTOM;
        } else if (readout.fallbackHint() != null) {
            contentBottom = FALLBACK_Y + LINE_HEIGHT;
        } else {
            contentBottom = REMAINING_Y + LINE_HEIGHT;
        }
        int hintTop = contentBottom + HINT_GAP;
        int bodyHeight = hintCount == 0
                ? contentBottom
                : hintTop + (hintCount - 1) * HINT_LINE_HEIGHT + LINE_HEIGHT;
        int cardWidth = innerWidth + PADDING * 2;
        int cardHeight = PADDING * 2 + TITLE_HEIGHT + bodyHeight;

        HudAnchor anchor = NavMapMode.compactAnchor();
        int screenWidth = mc.getWindow().getGuiScaledWidth();
        int screenHeight = mc.getWindow().getGuiScaledHeight();
        int cardX = anchor.isRight()
                ? screenWidth - MARGIN - cardWidth - NavMapMode.compactOffsetX()
                : MARGIN + NavMapMode.compactOffsetX();
        int cardY = anchor.isBottom()
                ? screenHeight - MARGIN - cardHeight - NavMapMode.compactOffsetY()
                : MARGIN + NavMapMode.compactOffsetY();
        // Floored at the margin the way the panel's own x is: an offset large enough to push the card
        // past the opposite edge must still leave it on the screen rather than off it.
        cardX = Math.max(MARGIN, cardX);
        cardY = Math.max(MARGIN, cardY);

        // The theme's sheet with its soft edge, rounded the same way the panel is. Translucent in both
        // styles, because a HUD card sits over the world rather than in a window.
        fillRounded(graphics, cardX, cardY, cardX + cardWidth, cardY + cardHeight,
                CORNER_RADIUS, UiTheme.panel());
        outlineRounded(graphics, cardX, cardY, cardX + cardWidth, cardY + cardHeight,
                CORNER_RADIUS, UiTheme.panelEdge());

        int innerX = cardX + PADDING;
        int innerY = cardY + PADDING;

        // Title row: the destination on the left and the travel mode hard right in the accent colour.
        // The same pairing the panel puts on its header row -- the mode belongs beside the heading,
        // not at the end of a sentence that may not fit.
        int titleRoom = Math.max(0, innerWidth - modeWidth - TITLE_GAP);
        graphics.drawString(font, font.plainSubstrByWidth(readout.headerLabel(), titleRoom),
                innerX, innerY, UiTheme.header(), false);
        graphics.drawString(font, readout.modeLabel(), innerX + innerWidth - modeWidth, innerY,
                UiTheme.accent(), false);

        int bodyTop = innerY + TITLE_HEIGHT;
        if (status) {
            drawStatus(graphics, font, readout, innerX, innerWidth, bodyTop);
        } else {
            drawTrip(graphics, font, readout, innerX, innerWidth, bodyTop);
        }

        // Last, and text: nothing here opens a drawing block, so it simply overwrites the rows' band.
        for (int i = 0; i < hintCount; i++) {
            graphics.drawString(font, hintLines.get(i), innerX,
                    bodyTop + hintTop + i * HINT_LINE_HEIGHT, UiTheme.warn(), false);
        }
    }

    /**
     * The arrival or no-route reading: a straight arrow, and the one line that says which of the two
     * it is, in the same colour as the arrow -- the marker and the words are one statement.
     */
    private static void drawStatus(GuiGraphics graphics, Font font, NavHudReadout readout,
                                   int innerX, int innerWidth, int bodyTop) {
        NavHudShapes.drawTurnArrow(graphics, innerX + ARROW_CENTRE_X, bodyTop + ARROW_CENTRE_Y,
                readout.arrowDegrees(), readout.arrowColor());
        String line = readout.arrived() ? readout.arrivalLine() : readout.noRouteLine();
        graphics.drawString(font,
                font.plainSubstrByWidth(line, Math.max(0, innerWidth - READOUT_TEXT_X)),
                innerX + READOUT_TEXT_X, bodyTop + STATUS_Y, readout.arrowColor(), false);
    }

    /**
     * The live reading: the turn (or the stop marker while a transit cue is up), the distance to it,
     * the instruction, the progress bar and what is left of the trip.
     */
    private static void drawTrip(GuiGraphics graphics, Font font, NavHudReadout readout,
                                 int innerX, int innerWidth, int bodyTop) {
        if (readout.transit()) {
            NavHudShapes.drawTransitMarker(graphics, innerX + ARROW_CENTRE_X,
                    bodyTop + ARROW_CENTRE_Y);
        } else {
            NavHudShapes.drawTurnArrow(graphics, innerX + ARROW_CENTRE_X, bodyTop + ARROW_CENTRE_Y,
                    readout.arrowDegrees(), readout.arrowColor());
        }

        // Distance to the turn is the number that matters, so it gets the strong colour. "Now" has
        // already been decided by the readout, at the distance the spoken announcement uses.
        graphics.drawString(font, readout.distance(), innerX + READOUT_TEXT_X, bodyTop + DISTANCE_Y,
                UiTheme.textStrong(), false);
        // One line only: the instruction names the road, so it is the row that can run long, and the
        // card is width-capped. Truncating it is the same compromise the panel makes at its own width.
        graphics.drawString(font,
                font.plainSubstrByWidth(readout.instruction(),
                        Math.max(0, innerWidth - READOUT_TEXT_X - 2)),
                innerX + READOUT_TEXT_X, bodyTop + INSTRUCTION_Y, UiTheme.textSecondary(), false);

        NavHudShapes.drawProgressBar(graphics, innerX, bodyTop + BAR_Y, innerWidth, BAR_HEIGHT,
                readout.progress());

        int remainingColor = readout.offRoute() ? UiTheme.warn() : UiTheme.textSecondary();
        graphics.drawString(font, readout.remaining(), innerX, bodyTop + REMAINING_Y,
                remainingColor, false);

        // Why this is a walking route when another mode is selected. Kept even though it is not one of
        // the four trip rows: in this mode the card is the only readout left, so dropping the note
        // would hide the explanation entirely rather than move it into the panel.
        String fallback = readout.fallbackHint();
        if (fallback != null) {
            graphics.drawString(font, font.plainSubstrByWidth(fallback, innerWidth), innerX,
                    bodyTop + FALLBACK_Y, UiTheme.warn(), false);
        }
    }

    /**
     * Width of the widest line the body will draw, before the card's cap is applied.
     *
     * <p>The instruction is measured in full on purpose: a short instruction should make a short card,
     * and only one that runs past the cap is truncated. The title row is measured separately by the
     * caller, because it is one row of its own rather than part of the arrow's text column.
     */
    private static int naturalTextWidth(Font font, NavHudReadout readout) {
        int width = 0;
        if (readout.arrived()) {
            width = font.width(readout.arrivalLine());
        } else if (!readout.hasRoute()) {
            width = font.width(readout.noRouteLine());
        }
        if (readout.distance() != null) {
            width = Math.max(width, font.width(readout.distance()));
        }
        if (readout.instruction() != null) {
            width = Math.max(width, font.width(readout.instruction()));
        }
        if (readout.remaining() != null) {
            width = Math.max(width, font.width(readout.remaining()));
        }
        return width;
    }
}
