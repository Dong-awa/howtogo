package bili.dongsz.howtogo.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;

import static bili.dongsz.howtogo.client.HudDraw.fillRounded;
import static bili.dongsz.howtogo.client.HudDraw.outlineRounded;

/**
 * The readout-only card drawn in xaero_minimap mode: the panel's trip readout without the map.
 * On an install with Xaero's Minimap the panel's map half is a second map beside the first, so this
 * mode leaves the map to Xaero and keeps only what a map cannot state: the turn, distance, what is
 * left, the progress bar.
 * The rows, order and offsets are the panel's own, so switching mode shows the same readout in a
 * smaller card; the arrow and bar come from NavHudShapes, shared with the panel. Width is capped and
 * the instruction truncated; height grows with content so the narrator note is never clipped.
 */
final class NavCompactHud {


    private static final int MARGIN = 6;
    private static final int PADDING = 6;
    private static final int CORNER_RADIUS = 4;
    private static final int TITLE_HEIGHT = 12;
    private static final int TITLE_GAP = 6;
    private static final int READOUT_TEXT_X = 26;
    private static final int ARROW_CENTRE_X = 11;
    private static final int ARROW_CENTRE_Y = 12;
    private static final int ARROW_BOTTOM = 20;
    private static final int DISTANCE_Y = 2;
    private static final int INSTRUCTION_Y = 14;
    private static final int STATUS_Y = 6;
    private static final int BAR_Y = 30;
    private static final int BAR_HEIGHT = 5;
    private static final int REMAINING_Y = 39;
    private static final int FALLBACK_Y = 50;
    private static final int LINE_HEIGHT = 9;
    private static final int HINT_GAP = 2;
    private static final int HINT_LINE_HEIGHT = 11;
    private static final int HINT_LINES = 2;
    /**
     * Widest the whole card may get: a HUD element shares the screen with the game and with the map
     * beside it, and the instruction is truncated rather than allowed to grow the card without bound.
     */
    private static final int MAX_WIDTH = 200;
    /** Widest the text column may be: the cap less the padding either side and the arrow gutter. */
    private static final int MAX_TEXT_WIDTH = MAX_WIDTH - PADDING * 2 - READOUT_TEXT_X;


    /**
     * Whether the card is on screen, as a value travelling between 0 and 1.
     * Its own slide, not the panel's: the two layouts are alternatives, so switching mode makes the
     * card arrive rather than appear at whatever the panel had reached.
     */
    private static final UiMotion.Slide presence =
            new UiMotion.Slide(UiMotion.Easing.OUT_CUBIC, 0.0F, UiMotion.HUD);

    /**
     * How far the card has come this frame, 0 to 1.
     * A field because the fade reaches the sheet, the edge, five rows of text and the arrow, and
     * passing it down four private methods would change four signatures to say one thing.
     */
    private static float open = 1.0F;

    private NavCompactHud() {
    }


    /**
     * One frame of the compact card.
     * Positioning is the anchor resolved from the screen edge with MARGIN, then the configured
     * offsets, which move the card further into the screen -- applied toward the middle on every
     * side rather than as a raw addition that would mean a different thing in each corner.
     */
    static void draw(GuiGraphics graphics, Minecraft mc, NavHudReadout readout) {
        if (readout.destinationName() == null) {
            return;
        }
        // Driven from the live question rather than from the readout it was handed: the readout is
        // kept for the frames of a fade-out, when there is no longer a trip to ask about.
        open = presence.to(Navigation.target() != null ? 1.0F : 0.0F).eased();
        if (open < NavHudRenderer.VANISHED) {
            // Nothing left to see: no rows measured, no bar drawn, and its value reset so the next trip
            // starts from empty rather than running back down from this one's finish.
            NavHudRenderer.forgetProgress();
            return;
        }
        Font font = mc.font;

        // Width first, because the narrator note is wrapped to it and the height follows from the rows
        // that exist: the cap applies to the card, so the text room is the cap less padding and gutter.
        int modeWidth = font.width(readout.modeLabel());
        int titleWidth = font.width(readout.headerLabel()) + TITLE_GAP + modeWidth;
        int textWidth = Math.min(naturalTextWidth(font, readout), MAX_TEXT_WIDTH);
        int innerWidth = Math.min(MAX_WIDTH - PADDING * 2,
                Math.max(titleWidth, READOUT_TEXT_X + textWidth));

        List<FormattedCharSequence> hintLines = readout.narratorHint() == null
                ? List.of()
                : font.split(readout.narratorHint(), innerWidth);
        int hintCount = Math.min(HINT_LINES, hintLines.size());

        // The two states that are not a live reading say one line beside the marker and no trip rows.
        boolean status = readout.arrived() || !readout.hasRoute();
        int contentBottom;
        if (status) {
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
        // Floored at the margin as the panel's own x is: an offset pushing the card past the opposite
        // edge must still leave it on screen.
        cardX = Math.max(MARGIN, cardX);
        cardY = Math.max(MARGIN, cardY);

        // The card slides up into its corner as it arrives, taken here rather than at the top of the
        // method: the early exits above draw nothing and a push before them would need unwinding on both.
        // Vertical only -- a horizontal slide would fight Xaero's layout.
        UiMotion.pushFrame(graphics, 0.0F, (1.0F - open) * UiMotion.HUD_RISE);

        // The theme's sheet with its soft edge, translucent in both styles because a HUD card sits over the world; faded through the theme colour.
        fillRounded(graphics, cardX, cardY, cardX + cardWidth, cardY + cardHeight,
                CORNER_RADIUS, UiMotion.fade(UiTheme.panel(), open));
        outlineRounded(graphics, cardX, cardY, cardX + cardWidth, cardY + cardHeight,
                CORNER_RADIUS, UiMotion.fade(UiTheme.panelEdge(), open));

        int innerX = cardX + PADDING;
        int innerY = cardY + PADDING;

        // Title row: the destination on the left and the travel mode hard right in the accent colour --
        // the pairing the panel puts on its header row.
        int titleRoom = Math.max(0, innerWidth - modeWidth - TITLE_GAP);
        graphics.drawString(font, font.plainSubstrByWidth(readout.headerLabel(), titleRoom),
                innerX, innerY, UiMotion.fade(UiTheme.header(), open), false);
        graphics.drawString(font, readout.modeLabel(), innerX + innerWidth - modeWidth, innerY,
                UiMotion.fade(UiTheme.accent(), open), false);

        int bodyTop = innerY + TITLE_HEIGHT;
        if (status) {
            drawStatus(graphics, font, readout, innerX, innerWidth, bodyTop);
        } else {
            drawTrip(graphics, font, readout, innerX, innerWidth, bodyTop);
        }

        // Last, and text: nothing here opens a drawing block, so it simply overwrites the rows' band.
        for (int i = 0; i < hintCount; i++) {
            graphics.drawString(font, hintLines.get(i), innerX,
                    bodyTop + hintTop + i * HINT_LINE_HEIGHT, UiMotion.fade(UiTheme.warn(), open),
                    false);
        }

        // Last, and unconditional once the card is drawn: the matrix must come back unshifted or the
        // next overlay in this frame is drawn six pixels low.
        UiMotion.popFrame(graphics);
    }

    /**
     * The arrival or no-route reading: a straight arrow and the one line saying which of the two it
     * is, in the arrow's colour -- marker and words are one statement.
     */
    private static void drawStatus(GuiGraphics graphics, Font font, NavHudReadout readout,
                                   int innerX, int innerWidth, int bodyTop) {
        NavHudShapes.drawTurnArrow(graphics, innerX + ARROW_CENTRE_X, bodyTop + ARROW_CENTRE_Y,
                readout.arrowDegrees(), UiMotion.fade(readout.arrowColor(), open));
        String line = readout.arrived() ? readout.arrivalLine() : readout.noRouteLine();
        graphics.drawString(font,
                font.plainSubstrByWidth(line, Math.max(0, innerWidth - READOUT_TEXT_X)),
                innerX + READOUT_TEXT_X, bodyTop + STATUS_Y,
                UiMotion.fade(readout.arrowColor(), open), false);
    }

    /**
     * The live reading: the turn (or stop marker while a transit cue is up), the distance, the
     * instruction, the progress bar and what is left.
     */
    private static void drawTrip(GuiGraphics graphics, Font font, NavHudReadout readout,
                                 int innerX, int innerWidth, int bodyTop) {
        if (readout.transit()) {
            NavHudShapes.drawTransitMarker(graphics, innerX + ARROW_CENTRE_X,
                    bodyTop + ARROW_CENTRE_Y, open);
        } else {
            NavHudShapes.drawTurnArrow(graphics, innerX + ARROW_CENTRE_X, bodyTop + ARROW_CENTRE_Y,
                    readout.arrowDegrees(), UiMotion.fade(readout.arrowColor(), open));
        }

        // Distance to the turn is the number that matters, so it gets the strong colour; "Now" was
        // decided by the readout at the distance the spoken announcement uses.
        graphics.drawString(font, readout.distance(), innerX + READOUT_TEXT_X, bodyTop + DISTANCE_Y,
                UiMotion.fade(UiTheme.textStrong(), open), false);
        graphics.drawString(font,
                font.plainSubstrByWidth(readout.instruction(),
                        Math.max(0, innerWidth - READOUT_TEXT_X - 2)),
                innerX + READOUT_TEXT_X, bodyTop + INSTRUCTION_Y,
                UiMotion.fade(UiTheme.textSecondary(), open), false);

        NavHudShapes.drawProgressBar(graphics, innerX, bodyTop + BAR_Y, innerWidth, BAR_HEIGHT,
                NavHudRenderer.smoothProgress(readout.progress()));

        int remainingColor = readout.offRoute() ? UiTheme.warn() : UiTheme.textSecondary();
        graphics.drawString(font, readout.remaining(), innerX, bodyTop + REMAINING_Y,
                UiMotion.fade(remainingColor, open), false);

        // Why this is a walking route when another mode is selected. Kept even though it is not a trip
        // row: in this mode the card is the only readout left, so dropping it hides the explanation.
        String fallback = readout.fallbackHint();
        if (fallback != null) {
            graphics.drawString(font, font.plainSubstrByWidth(fallback, innerWidth), innerX,
                    bodyTop + FALLBACK_Y, UiMotion.fade(UiTheme.warn(), open), false);
        }
    }

    /**
     * Width of the widest line the body will draw, before the cap.
     * The instruction is measured in full on purpose: a short instruction makes a short card, and only
     * one past the cap is truncated.
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
