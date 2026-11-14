package bili.dongsz.howtogo.client;

import net.minecraft.client.gui.GuiGraphics;

import static bili.dongsz.howtogo.client.HudDraw.*;

/**
 * Drawing primitives shared by both navigation readouts: turn arrow, U-turn arrow, transit marker,
 * progress bar.
 * Two layouts draw the same iconography; one copy keeps them from drifting. Takes plain numbers --
 * the caller owns rows and words.
 */
final class NavHudShapes {

    private NavHudShapes() {
    }


    /**
     * A navigation arrow: a shaft bending toward the turn, ending in a solid triangular head.
     * Geometry, not a texture: no asset and scales with the UI. A U-turn has its own shape (see
     * drawUturnArrow).
     * @param degrees signed turn angle, or NaN for no turn ahead
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

        // The shaft runs vertically up from the bottom, then bends into the turn.
        double cornerX = cx;
        double cornerY = cy - 1;
        double shaftBottomY = cy + 8;

        // Kept short so a left arrow fits between the readout edge and the text column.
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
     * A U-turn arrow: up one side, over the top, down the other, head at the bottom.
     * The bend shape cannot express a U-turn: at 180 degrees the leg retraces the shaft. Sized to the
     * same cell as the bend arrow so the readout does not shift.
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
        // Round joins at every corner; without them the shoulders meet in sharp wedges.
        emitDisc(out, cx + half, shoulderY, thickness, color, 0xFF);
        emitDisc(out, cx, topY, thickness, color, 0xFF);
        emitDisc(out, cx - half, shoulderY, thickness, color, 0xFF);
        emitDisc(out, cx - half, headBaseY, thickness, color, 0xFF);

        emitTriangle(out,
                cx - half, tipY,
                cx - half * 2.0, headBaseY,
                cx, headBaseY, color);
    }

    /** Screen-space direction the arrow head points, in radians. Straight on is up; positive is right. */
    private static double arrowDirection(double degrees) {
        if (Double.isNaN(degrees)) {
            return -Math.PI / 2;
        }
        double magnitude = Math.abs(degrees);
        if (magnitude <= 25) {
            return -Math.PI / 2;
        }
        // Nothing above 135 degrees arrives here; a U-turn uses drawUturnArrow, the only shape that can
        // express one.
        boolean right = degrees > 0;
        if (magnitude > 50) {
            return right ? 0 : Math.PI;
        }
        return right ? -Math.PI / 4 : -Math.PI * 3 / 4;
    }


    /**
     * The square that stands in for the turn arrow while a transit cue is shown; a stop is not a
     * junction. Accent ring around a dark centre stays legible on either sheet.
     */
    static void drawTransitMarker(GuiGraphics graphics, int cx, int cy) {
        drawTransitMarker(graphics, cx, cy, 1.0F);
    }

    /**
     * The same marker faded with its card; the theme colours are opaque, so alpha goes through
     * UiMotion#fade to avoid one square staying at full strength.
     */
    static void drawTransitMarker(GuiGraphics graphics, int cx, int cy, float alpha) {
        graphics.fill(cx - 4, cy - 4, cx + 4, cy + 4, UiMotion.fade(UiTheme.accent(), alpha));
        graphics.fill(cx - 2, cy - 2, cx + 2, cy + 2,
                UiMotion.fade(UiTheme.markerBackdrop(), alpha));
    }


    /**
     * Thin trip progress bar filled by the fraction of the route already walked.
     * The fraction is passed in, not read here: one read per frame avoids two answers if the trip is
     * replaced between them.
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
