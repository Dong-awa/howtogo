package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.RoadConfig;
import bili.dongsz.howtogo.road.PlaceKind;
import bili.dongsz.howtogo.road.RoadClass;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.IntConsumer;
import java.util.function.IntPredicate;

/**
 * The switches the map carries: what it never draws, and whether the panel is rolled up.
 *
 * <h2>Why the layout is worked out once and used twice</h2>
 * {@link #toggles} answers where every box is and what pressing it does, and both the drawing pass and
 * the click handler use that one answer. A panel drawn in one place and hit-tested in another works
 * until the first time a label changes width, and then the box under the cursor is not the box that gets
 * flipped.
 *
 * <h2>Where it sits, and when</h2>
 * Top right, where the map's own furniture is not. It is drawn while the world map is open and the editor
 * is not: hiding footpaths is a way of reading the map rather than a way of changing it, so it belongs to
 * the map's ordinary mode.
 *
 * <h2>Unfolding, and why the switches do not move</h2>
 * Rolled up and open are two ends of one slide rather than two layouts: the edge that is drawn travels
 * between the title row's height and the panel's own, and the switches fade in behind it. Every hit test
 * still goes through {@link #toggles} and therefore through the logical state, because a press on a row
 * that is still arriving has to land on the row the player aimed at and not on the one the edge has
 * reached.
 */
final class MapFilterPanel {

    private static final int PAD = 4;
    private static final int ROW_HEIGHT = 11;
    private static final int BOX = 8;
    private static final int BOX_GAP = 3;
    private static final int COLUMN_GAP = 7;
    /** Three roads to a row and two places to a row: as narrow as the labels allow. */
    private static final int ROAD_COLUMNS = 3;
    private static final int PLACE_COLUMNS = 2;

    /**
     * How far the panel is unfolded, 0 for rolled up and 1 for fully open.
     *
     * <p>One slide for the whole panel rather than one per row: this is a change of layout, and a
     * layout that arrives in pieces reads as several boxes resizing rather than as one box opening.
     * It chases {@link MapFilter#isCollapsed()} every frame instead of being told when the title is
     * pressed, so a state changed from anywhere -- this panel, the config file, a stale screen -- is
     * unfolded towards without the press having to report it.
     */
    private static final UiMotion.Slide unfold =
            new UiMotion.Slide(UiMotion.Easing.IN_OUT_CUBIC, 0.0F, UiMotion.EXPAND);

    /**
     * The tall bottom edge this panel last drew while it was open.
     *
     * <p>Kept because {@link #toggles} answers for the logical state, and the logical state changes
     * on the frame the title is pressed: rolled up, the switch rows are not in the list at all, so
     * that frame has no tall height left to interpolate down from and the panel would simply be
     * short. Remembering the last open height is what lets the edge roll up over {@link
     * UiMotion#COLLAPSE} instead of snapping.
     */
    private static int openBottom;

    /** One hover value per switch, so a row's outlines grow under the cursor instead of appearing. */
    private static final UiMotion.Slides hover = new UiMotion.Slides();

    private MapFilterPanel() {
    }

    /** One box: where it is, what it says, whether it is on, and what pressing it does. */
    record Toggle(int x, int y, int width, int height, String label, boolean on, int colour,
                  Runnable flip) {

        boolean holds(double mouseX, double mouseY) {
            return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
        }
    }

    /**
     * Every box of the panel, in drawing order, as it stands for the given screen width.
     *
     * <p>Rolled up, this is the title row alone -- which is also what tells a press on the title from a
     * press on a switch: the switches are simply not there to be hit.
     */
    static List<Toggle> toggles(Font font) {
        List<String> roads = new ArrayList<>();
        List<Integer> roadColours = new ArrayList<>();
        for (RoadClass roadClass : roadList()) {
            roads.add(Component.translatable("screen.howtogo.road_class."
                    + roadClass.name().toLowerCase(Locale.ROOT)).getString());
            roadColours.add(RoadConfig.roadColor(roadClass));
        }
        List<String> places = new ArrayList<>();
        List<Integer> placeColours = new ArrayList<>();
        for (PlaceKind kind : placeList()) {
            places.add(Component.translatable("screen.howtogo.place_kind."
                    + kind.name().toLowerCase(Locale.ROOT)).getString());
            placeColours.add(UiTheme.place());
        }

        int width = Math.max(font.width(title()),
                Math.max(Math.max(gridWidth(font, roads, ROAD_COLUMNS),
                                gridWidth(font, places, PLACE_COLUMNS)),
                        BOX + BOX_GAP + font.width(linesLabel()))) + PAD * 2;
        int left = MapFilter.panelX();

        List<Toggle> boxes = new ArrayList<>();
        int row = MapFilter.panelY() + PAD;
        boxes.add(new Toggle(left, row, width, ROW_HEIGHT, title(), !MapFilter.isCollapsed(),
                UiTheme.text(), MapFilter::toggleCollapsed));
        row += ROW_HEIGHT + 1;
        if (MapFilter.isCollapsed()) {
            return boxes;
        }

        row = grid(boxes, font, roads, roadColours, row, left, ROAD_COLUMNS,
                index -> MapFilter.isHidden(roadList().get(index)),
                index -> MapFilter.toggleRoad(roadList().get(index)));
        row += 1;
        boxes.add(new Toggle(left + PAD, row, BOX + BOX_GAP + font.width(linesLabel()), ROW_HEIGHT,
                linesLabel(), !MapFilter.linesHidden(), UiTheme.accent(), MapFilter::toggleLines));
        row += ROW_HEIGHT + 1;
        grid(boxes, font, places, placeColours, row, left, PLACE_COLUMNS,
                index -> MapFilter.isHidden(placeList().get(index)),
                index -> MapFilter.togglePlace(placeList().get(index)));
        return boxes;
    }

    /** The roads the panel offers, in the order it offers them. */
    private static List<RoadClass> roadList() {
        return List.of(RoadClass.HIGHWAY, RoadClass.ROAD, RoadClass.PATH, RoadClass.WATER,
                RoadClass.RAIL, RoadClass.ICE);
    }

    /** The kinds of place the panel offers. */
    private static List<PlaceKind> placeList() {
        return List.of(PlaceKind.SHOP, PlaceKind.STATION, PlaceKind.PLACE, PlaceKind.RESOURCE);
    }

    /** Lays one grid of switches out, and answers the row below it. */
    private static int grid(List<Toggle> boxes, Font font, List<String> labels,
                            List<Integer> colours, int startRow, int left, int columns,
                            IntPredicate hidden, IntConsumer flip) {
        int row = startRow;
        for (int i = 0; i < labels.size(); i++) {
            int x = left + PAD + cellOffset(font, labels, i, columns);
            int width = cellWidth(font, labels, i, columns);
            int index = i;
            boxes.add(new Toggle(x, row, width, ROW_HEIGHT, labels.get(i), !hidden.test(index),
                    colours.get(index), () -> flip.accept(index)));
            if ((i + 1) % columns == 0 || i == labels.size() - 1) {
                row += ROW_HEIGHT;
            }
        }
        return row;
    }

    /** The panel's box, for the caller that has to keep a drag inside the screen. */
    static int[] bounds(Font font) {
        List<Toggle> boxes = toggles(font);
        return new int[] {panelLeft(boxes), panelTop(boxes), panelWidth(boxes),
                panelBottom(boxes) - panelTop(boxes)};
    }

    /**
     * Draws the panel, and unfolds or rolls it up towards the state it is in.
     *
     * <p>The box's top and left never move and nothing here is hit-tested: {@link #toggles} answers
     * that, from the logical state, so a press during the two hundred milliseconds of an unfold lands
     * on the row the player can already see rather than on a moving target. What animates is the
     * bottom edge -- from the title row's height to the whole panel's -- and the switches fade in
     * behind it as the edge passes them.
     */
    static void draw(GuiGraphics graphics, int mouseX, int mouseY) {
        Font font = Minecraft.getInstance().font;
        List<Toggle> boxes = toggles(font);
        if (boxes.isEmpty()) {
            return;
        }
        int left = panelLeft(boxes);
        int top = panelTop(boxes);
        int right = left + panelWidth(boxes);
        int bottom = panelBottom(boxes);
        boolean expanded = !MapFilter.isCollapsed();
        if (expanded) {
            // Taken while the rows are still there to measure: see openBottom for why it has to be
            // remembered rather than read on the frame the panel is rolled up.
            openBottom = bottom;
        }
        // The rolled-up height is the title row plus the padding above and below it. Written out
        // rather than read from toggles(), which by now answers for the state being animated towards.
        int foldedBottom = top + PAD + ROW_HEIGHT + PAD;
        float t = unfold.duration(expanded ? UiMotion.EXPAND : UiMotion.COLLAPSE)
                .to(expanded ? 1.0F : 0.0F).eased();
        int tall = Math.max(foldedBottom, openBottom);
        int animatedBottom = foldedBottom + Math.round((tall - foldedBottom) * t);
        graphics.fill(left - 1, top - 1, right + 1, animatedBottom + 1, UiTheme.overlayEdge());
        graphics.fill(left, top, right, animatedBottom, UiTheme.overlayPanel());

        // Where the switches come from when the panel unfolds: the line under the title row. Each row
        // is drawn on the straight line between that line and its own place, so the whole grid slides
        // out from under the title instead of appearing at its final position -- which is what makes
        // this read as a panel opening rather than a box growing around content that was always there.
        // The travel is inside the panel the whole way, so nothing has to be clipped.
        int unfoldedFrom = top + PAD + ROW_HEIGHT;
        for (int i = 0; i < boxes.size(); i++) {
            Toggle box = boxes.get(i);
            // The title row is the panel and is always there; the switches are what unfolds.
            float shown = i == 0 ? 1.0F : t;
            int drawnY = i == 0 ? box.y()
                    : unfoldedFrom + Math.round((box.y() - unfoldedFrom) * t);
            if (drawnY >= animatedBottom) {
                // Past the unfolding edge: skipped rather than clipped, because the game's scissor
                // cannot be nested and the map's own drawing may already hold one.
                continue;
            }
            int boxY = drawnY + (ROW_HEIGHT - BOX) / 2;
            graphics.fill(box.x(), boxY, box.x() + BOX, boxY + BOX,
                    UiMotion.fade(UiTheme.checkboxEdge(), shown));
            graphics.fill(box.x() + 1, boxY + 1, box.x() + BOX - 1, boxY + BOX - 1,
                    UiMotion.fade(box.on() ? box.colour() : UiTheme.checkboxOff(), shown));
            float h = hover.get(i).to(box.holds(mouseX, mouseY) ? 1.0F : 0.0F).eased();
            if (h > 0.0F) {
                int hoverEdge = UiMotion.fade(UiTheme.overlayHoverEdge(), h * shown);
                graphics.fill(box.x() - 1, boxY - 1, box.x() + BOX + 1, boxY + 1, hoverEdge);
                graphics.fill(box.x() - 1, boxY + BOX, box.x() + BOX + 1, boxY + BOX + 1,
                        hoverEdge);
                graphics.fill(box.x() - 1, boxY, box.x(), boxY + BOX, hoverEdge);
                graphics.fill(box.x() + BOX, boxY, box.x() + BOX + 1, boxY + BOX, hoverEdge);
            }
            graphics.drawString(font, box.label(), box.x() + BOX + BOX_GAP, drawnY + 2,
                    UiMotion.fade(box.on() ? UiTheme.text() : UiTheme.textDim(), shown), false);
        }
    }

    /** The box a press lands on, or null when it lands on the panel but not on a switch. */
    static Toggle pressed(Font font, double mouseX, double mouseY) {
        for (Toggle box : toggles(font)) {
            if (box.holds(mouseX, mouseY)) {
                return box;
            }
        }
        return null;
    }

    /** The panel's title switch, which toggles the roll-up rather than moving the panel. */
    static Toggle titleToggle(Font font, double mouseX, double mouseY) {
        List<Toggle> boxes = toggles(font);
        if (boxes.isEmpty() || !boxes.get(0).holds(mouseX, mouseY)) {
            return null;
        }
        return boxes.get(0);
    }

    /**
     * Whether a press is anywhere on the panel.
     *
     * <p>Asked before the map is given the click: a press that fell through the panel would move the map
     * or place a road under the thing the player was aiming at, which is worse than the press doing
     * nothing.
     */
    static boolean covers(Font font, double mouseX, double mouseY) {
        List<Toggle> boxes = toggles(font);
        if (boxes.isEmpty()) {
            return false;
        }
        return mouseX >= panelLeft(boxes) - 1 && mouseX < panelLeft(boxes) + panelWidth(boxes) + 1
                && mouseY >= panelTop(boxes) - 1 && mouseY < panelBottom(boxes) + 1;
    }

    // ------------------------------------------------------------------ layout

    private static int panelLeft(List<Toggle> boxes) {
        return boxes.get(0).x();
    }

    private static int panelTop(List<Toggle> boxes) {
        return boxes.get(0).y() - PAD;
    }

    private static int panelWidth(List<Toggle> boxes) {
        return boxes.get(0).width();
    }

    private static int panelBottom(List<Toggle> boxes) {
        int bottom = 0;
        for (Toggle box : boxes) {
            bottom = Math.max(bottom, box.y() + box.height());
        }
        return bottom + PAD;
    }

    /** The width of a grid, which is the width of its widest row. */
    private static int gridWidth(Font font, List<String> labels, int columns) {
        int rows = (labels.size() + columns - 1) / columns;
        int widest = 0;
        for (int row = 0; row < rows; row++) {
            int width = 0;
            for (int column = 0; column < columns; column++) {
                int index = row * columns + column;
                if (index < labels.size()) {
                    width += cellWidth(font, labels, index, columns);
                }
            }
            widest = Math.max(widest, width);
        }
        return widest;
    }

    /**
     * One cell's width, so that the columns of a grid line up down the panel.
     *
     * <p>The widest label in a column decides it and every cell in that column is that wide: a grid whose
     * cells each fitted only their own label would step in and out down the panel, and the boxes would
     * not line up -- which is the whole reason for a grid.
     */
    private static int cellWidth(Font font, List<String> labels, int index, int columns) {
        int column = index % columns;
        int widest = 0;
        for (int i = column; i < labels.size(); i += columns) {
            widest = Math.max(widest, font.width(labels.get(i)));
        }
        return BOX + BOX_GAP + widest + COLUMN_GAP;
    }

    /** Where a cell starts, from the cells before it in its own row. */
    private static int cellOffset(Font font, List<String> labels, int index, int columns) {
        int offset = 0;
        for (int before = index - (index % columns); before < index; before++) {
            offset += cellWidth(font, labels, before, columns);
        }
        return offset;
    }

    private static String linesLabel() {
        return Component.translatable("screen.howtogo.map_lines").getString();
    }

    private static String title() {
        return Component.translatable("screen.howtogo.map_filter").getString();
    }
}
