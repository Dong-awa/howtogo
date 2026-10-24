package bili.dongsz.howtogo.client;

import net.minecraft.client.gui.GuiGraphics;

import static bili.dongsz.howtogo.client.HudDraw.*;

/**
 * The few drawing primitives every navigation readout shares: the turn arrow with its U-turn form,
 * the stop marker a transit cue uses in the arrow's place, and the trip progress bar.
 *
 * <h2>Why they are here and not in either renderer</h2>
 * There are two readout layouts now -- the full panel, which also carries the mini-map, and the
 * compact card that is drawn when Xaero's Minimap carries the map half. The number, the instruction,
 * the bar and the arrow mean exactly the same thing in both, and a second copy of the arrow geometry
 * in the compact card is precisely the kind of copy that drifts: the U-turn shape exists because a
 * single bend cannot express a U-turn, and a second implementation would sooner or later draw the
 * retracing line that shape was written to replace. Kept in one class, the iconography cannot change
 * between display modes, and the full panel's appearance is preserved by construction rather than by
 * a careful second transcription.
 *
 * <p>Everything here takes plain numbers: no readout, no navigation state, no layout. That is the
 * point of the split -- the caller owns the rows and the words, this class owns the ink.
 */
final class NavHudShapes {

    private NavHudShapes() {
    }

    // ---------------------------------------------------------------- turn arrow

    /**
     * A navigation arrow: a shaft that bends toward the turn, ending in a solid triangular head.
     *
     * <p>Drawn from geometry rather than shipped as a texture, so it needs no asset and scales with
     * the UI. The head is a filled triangle rather than two swept barbs -- barbs read as a fork.
     *
     * <p>A U-turn is not drawn by this shape at all: it has its own, because a single bend cannot
     * express one. Pointing the head straight down sent the returning leg back along the shaft it had
     * just come up, so the icon was one line drawn twice over itself with the head buried in the
     * middle. See {@link #drawUturnArrow}.
     *
     * @param degrees signed turn angle, or NaN for "no turn ahead" (drawn as straight on)
     */
    static void drawTurnArrow(GuiGraphics graphics, double cx, double cy,
                              double degrees, int color) {
        HudDraw.Drawing arrow = HudDraw.beginBlock(graphics);
        HudDraw.VertexSink last = arrow.sink;
        double thickness = 1.7;

        if (!Double.isNaN(degrees) && Math.abs(degrees) > 135) {
            drawUturnArrow(last, cx, cy, thickness, color);
            HudDraw.endBlock(graphics, arrow);
            return;
        }

        double bend = arrowDirection(degrees);
        double dirX = Math.cos(bend);
        double dirY = Math.sin(bend);

        // The shaft always runs vertically up from the bottom, then bends into the turn.
        double cornerX = cx;
        double cornerY = cy - 1;
        double shaftBottomY = cy + 8;

        // Kept short so a left arrow still fits between the readout's edge and the text column.
        double legLength = 4.0;
        double headLength = 5.5;
        double headHalf = 4.0;

        double tipX = cornerX + dirX * (legLength + headLength);
        double tipY = cornerY + dirY * (legLength + headLength);
        double baseX = tipX - dirX * headLength;
        double baseY = tipY - dirY * headLength;

        emitLine(last, cornerX, shaftBottomY, cornerX, cornerY, thickness, color, 0xFF);
        emitDisc(last, cornerX, cornerY, thickness, color, 0xFF);
        emitLine(last, cornerX, cornerY, baseX, baseY, thickness, color, 0xFF);

        double perpX = -dirY;
        double perpY = dirX;
        emitTriangle(last,
                tipX, tipY,
                baseX + perpX * headHalf, baseY + perpY * headHalf,
                baseX - perpX * headHalf, baseY - perpY * headHalf, color);

        HudDraw.endBlock(graphics, arrow);
    }

    /**
     * A U-turn arrow: up one side, over the top, down the other side, head at the bottom.
     *
     * <h2>Why a U-turn cannot use the bend shape</h2>
     * The bend draws a shaft up from the bottom and then a leg out toward the turn. At 180 degrees
     * that leg runs straight back down along the shaft, so the two lines coincide exactly and the icon
     * becomes a line drawn over itself. Which way the arrow points is the whole content of the icon,
     * and a shape that retraces itself cannot show a direction at all.
     *
     * <p>Sized to the same cell as the bend arrow -- about fifteen pixels tall and seven wide -- so
     * swapping between the two does not shift the readout beside them. The two shoulders stand in for
     * the curve, because every primitive here is a straight line.
     */
    private static void drawUturnArrow(HudDraw.VertexSink out,
                                       double cx, double cy, double thickness, int color) {
        double half = 3.5;
        double topY = cy - 6.0;
        double shoulderY = cy - 3.0;
        double rightBottomY = cy + 7.0;
        double headBaseY = cy + 1.0;
        double tipY = cy + 7.0;

        emitLine(out, cx + half, rightBottomY, cx + half, shoulderY, thickness, color, 0xFF);
        emitLine(out, cx + half, shoulderY, cx, topY, thickness, color, 0xFF);
        emitLine(out, cx, topY, cx - half, shoulderY, thickness, color, 0xFF);
        emitLine(out, cx - half, shoulderY, cx - half, headBaseY, thickness, color, 0xFF);
        emitDisc(out, cx + half, rightBottomY, thickness, color, 0xFF);
        // Round joins at every corner. Without them the three shoulders meet at sharp wedges with a
        // notch on the outside, which is the same artefact roads would have without joints -- see the
        // round caps in the road renderer's stroke.
        emitDisc(out, cx + half, shoulderY, thickness, color, 0xFF);
        emitDisc(out, cx, topY, thickness, color, 0xFF);
        emitDisc(out, cx - half, shoulderY, thickness, color, 0xFF);
        emitDisc(out, cx - half, headBaseY, thickness, color, 0xFF);

        emitTriangle(out,
                cx - half, tipY,
                cx - half * 2.0, headBaseY,
                cx, headBaseY, color);
    }

    /**
     * Screen-space direction the arrow head points, in radians, for a signed turn angle.
     *
     * <p>Straight on is up; positive angles are right turns, following Minecraft's axes.
     */
    private static double arrowDirection(double degrees) {
        if (Double.isNaN(degrees)) {
            return -Math.PI / 2;
        }
        double magnitude = Math.abs(degrees);
        if (magnitude <= 25) {
            return -Math.PI / 2;
        }
        // Nothing above 135 degrees arrives here: a U-turn is drawn by drawUturnArrow, which is the
        // only shape that can express one. It used to answer "straight down" for that case, and the
        // caller then drew a leg back along the shaft it had just come up.
        boolean right = degrees > 0;
        if (magnitude > 50) {
            return right ? 0 : Math.PI;
        }
        return right ? -Math.PI / 4 : -Math.PI * 3 / 4;
    }

    // ------------------------------------------------------------------- transit

    /**
     * The square that stands in for the turn arrow while a transit cue is being shown.
     *
     * <p>An arrow would be a claim about a junction, and a stop is not one: the marker says "a stop"
     * and nothing more. Drawn as an accent square with the readout's backdrop colour inside it, which
     * leaves a ring of accent around a dark centre and keeps the marker legible on either sheet.
     */
    static void drawTransitMarker(GuiGraphics graphics, int cx, int cy) {
        graphics.fill(cx - 4, cy - 4, cx + 4, cy + 4, UiTheme.accent());
        graphics.fill(cx - 2, cy - 2, cx + 2, cy + 2, UiTheme.markerBackdrop());
    }

    // ------------------------------------------------------------------ progress

    /**
     * Thin trip progress bar: filled by the fraction of the route already walked.
     *
     * <p>The fraction is passed in rather than read here, because the readout is the one place that
     * asks the navigation what has been travelled -- two reads of it would be two answers if the trip
     * were replaced between them, which is exactly the kind of disagreement the readout value exists
     * to prevent.
     */
    static void drawProgressBar(GuiGraphics graphics, int x, int y, int width, int height,
                                double fraction) {
        fillRounded(graphics, x, y, x + width, y + height, height / 2, UiTheme.track());
        int filled = (int) Math.round(width * fraction);
        if (filled > height) {
            fillRounded(graphics, x, y, x + filled, y + height, height / 2, UiTheme.accent());
        } else if (filled > 0) {
            graphics.fill(x, y, x + filled, y + height, UiTheme.accent());
        }
    }
}
