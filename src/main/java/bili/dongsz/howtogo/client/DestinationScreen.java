package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.api.DestinationSources;
import bili.dongsz.howtogo.road.RoadChains;
import bili.dongsz.howtogo.road.RoadClass;
import bili.dongsz.howtogo.road.RoadNetwork;
import bili.dongsz.howtogo.road.RoadSegment;
import bili.dongsz.howtogo.route.Destination;
import bili.dongsz.howtogo.route.Route;
import bili.dongsz.howtogo.route.RoutePreferences;
import bili.dongsz.howtogo.route.TravelMode;
import bili.dongsz.howtogo.store.RoutePreferenceStore;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Destination picker opened by the navigator item: search, a pickable map, and the saved places.
 *
 * <p>The map is drawn from this mod's own road data and can be clicked to choose a point, the way
 * tapping a map works in a navigation app. Picking is two-step -- a click drops a pin, a second
 * click on the action button commits -- so a stray click does not silently start a trip.
 */
public final class DestinationScreen extends Screen {

    /** Width of the panel when the map and the list have to be stacked, one above the other. */
    private static final int PANEL_WIDTH = 268;
    /**
     * Widest the two-column panel may get, however wide the window is.
     *
     * <p>Only a little wider than the stacked panel's 268: the point of the side-by-side
     * arrangement is to give the list a column of its own next to a square-ish map, not to fill the
     * display. At this width the split gives the map column 137 units and the list column 176, which
     * is a place name, its coordinates and its distance comfortably; a panel any wider spends the
     * extra room on empty map.
     */
    private static final int MAX_PANEL_WIDTH = 334;
    /** Screen edge left clear of the panel, so it never touches the side of the display. */
    private static final int MARGIN = 8;
    /**
     * Narrowest the map column may become before the two columns stop being worth having.
     *
     * <p>Matched to the split below: the map column is 138 at {@link #MAX_PANEL_WIDTH} and shrinks
     * to this only at the window width where the arrangement is abandoned for the stacked one.
     */
    private static final int MIN_MAP_PANE_WIDTH = 125;
    /** Narrowest the list column may become: a little more than the name and the distance. */
    private static final int MIN_LIST_PANE_WIDTH = 140;
    /** The map's share of the space left after the column gap, in per cent. */
    private static final int MAP_PANE_SHARE = 44;
    /**
     * Gap between the two columns.
     *
     * <p>A constant of its own rather than the row gap, which tightens when the window is short:
     * the column split is decided before that and is not something the vertical fit should move.
     */
    private static final int COLUMN_GAP = 5;
    /**
     * Narrowest window that still gets the two-column arrangement, measured rather than assumed.
     *
     * <p>Found by asking {@link #fitsAsColumns} at growing widths, so the number this class
     * advertises is the number its own rule produces: the test is the same one {@code
     * computePanelLayout} makes, calling the same {@link #mapPaneWidth} and {@link #listPaneWidth}
     * the columns are then built from. A hand-computed threshold would be a second copy of the
     * split, and the second copy is the one that drifts.
     */
    private static final int MIN_SIDE_BY_SIDE_WINDOW = smallestWindowForColumns();

    private static int smallestWindowForColumns() {
        for (int window = MIN_PANEL_WIDTH; window <= MAX_PANEL_WIDTH; window++) {
            if (fitsAsColumns(window)) {
                return window;
            }
        }
        // No window in range can hold both columns at their minimums, so the arrangement is off
        // entirely and the stacked one is always used.
        return Integer.MAX_VALUE;
    }

    /** Whether the columns the split describes at this window width still respect both minimums. */
    private static boolean fitsAsColumns(int window) {
        int content = Math.min(MAX_PANEL_WIDTH, Math.max(MIN_PANEL_WIDTH, window - MARGIN * 2))
                - PADDING * 2;
        return mapPaneWidth(content) >= MIN_MAP_PANE_WIDTH
                && listPaneWidth(content) >= MIN_LIST_PANE_WIDTH;
    }

    /**
     * Width of the map column for a given content width.
     *
     * <p>One function rather than the same expression written twice, because the decision to use two
     * columns is a question about the answer it gives: written twice, one of the copies is the one
     * that drifts.
     */
    private static int mapPaneWidth(int content) {
        return (content - COLUMN_GAP) * MAP_PANE_SHARE / 100;
    }

    /** Width of the list column: whatever the map column and the gap leave of the content. */
    private static int listPaneWidth(int content) {
        return content - COLUMN_GAP - mapPaneWidth(content);
    }
    /**
     * Narrowest usable width the stacked panel can be held to without its own rows spilling.
     *
     * <p>The control rows put three abutting chips in a row and the class row six, so roughly 220
     * units of content plus the panel padding is the floor below which the stacked arrangement
     * would start pushing buttons off its own edge. Below even that the window is smaller than
     * Minecraft's GUI normally gets; the panel is then allowed to reach the screen edges rather
     * than being clipped by them, because a control just inside the edge can still be clicked.
     */
    private static final int MIN_PANEL_WIDTH = 236;
    private static final int ROW_HEIGHT = 24;
    /** Narrowest a list row may be squeezed to when a band has to be shared out. */
    private static final int MIN_LIST_ROW = 24;
    /**
     * Shortest the panel may become, in units.
     *
     * <p>The window is usually the taller of the two, and the panel simply fills it; this is the
     * floor for a window too short to hold the header, the controls and one row of list at once. A
     * panel that reaches the screen edges can still be read and clicked, whereas bands squeezed past
     * this point would start to overlap.
     */
    private static final int MIN_PANEL_HEIGHT = 232;
    /**
     * The map column's share of the two-column content width.
     *
     * <p>A width figure only. The map's *height* is not a share of anything: the columns are
     * stretched to the bottom of the band they sit in, so this decides how wide the map pane is and
     * nothing else.
     */
    private static final int MAP_BAND_SHARE = 42;
    /**
     * Most rows the list may draw.
     *
     * <p>Not the figure that decides the list's height: the band does that, and the band is the panel
     * less everything fixed. This only stops a very tall window from asking for more rows than
     * anyone would scroll through; below it, the row count follows the band exactly.
     */
    private static final int MAX_VISIBLE_ROWS = 12;
    private static final int PADDING = 8;
    private static final int HEADER_HEIGHT = 16;
    private static final int SEARCH_HEIGHT = 16;
    private static final int GAP = 5;
    /** When the window is too short for the comfortable layout, every gap shrinks to this. */
    private static final int GAP_PACKED = 3;
    private static final int FOOTER_HEIGHT = 22;
    /** Row of travel-mode buttons, between the place list and the preference controls. */
    private static final int MODE_ROW_HEIGHT = 18;
    /**
     * The two control rows under the mode row: the metric and major-road toggles, then one toggle
     * per {@link RoadClass} for the avoid list. Heights rather than one number because the metric
     * buttons have to stay tall enough to read while the class row can give up a pixel or two.
     */
    private static final int PREFERENCE_ROW_HEIGHT = 16;
    private static final int PREFERENCE_ROW_PACKED = 14;
    private static final int CLASS_ROW_HEIGHT = 16;
    private static final int CLASS_ROW_PACKED = 14;
    /**
     * The preview readout under the list: one short line naming the state, one for the answer.
     *
     * <p>Two lines are reserved whatever the answer is, so the panel does not resize as the player
     * compares a route that exists with one that does not.
     */
    private static final int PREVIEW_STATE_HEIGHT = 9;
    private static final int PREVIEW_LINE_HEIGHT = 19;
    private static final int CORNER_RADIUS = 5;
    private static final int MIN_MAP_HEIGHT = 42;

    /** Half the world span shown in the picker map, in blocks. Zoomable. */
    private static final double DEFAULT_VIEW_RADIUS = 260.0;
    private static final double MIN_VIEW_RADIUS = 40.0;
    private static final double MAX_VIEW_RADIUS = 3000.0;

    private static final int RECENTER_SIZE = 11;

    private static final int COLOR_DIM = 0x90101010;
    private static final int COLOR_PANEL = 0xF012171C;
    private static final int COLOR_PANEL_EDGE = 0xFF2C3540;
    private static final int COLOR_MAP_BACKDROP = 0xFF0B0F13;
    private static final int COLOR_HEADER = 0xFF8FA0B0;
    private static final int COLOR_PRIMARY = 0xFFF2F6FA;
    private static final int COLOR_SECONDARY = 0xFF7E8B98;
    /**
     * The grey a source note after a name is drawn in.
     *
     * <p>Lighter than {@link #COLOR_SECONDARY}: the note sits on the name's own line, next to a name
     * that many sources colour, so it has to read as an aside without competing with it.
     */
    private static final int COLOR_SOURCE_NOTE = 0xFFAAB4BE;
    private static final int COLOR_ACCENT = 0xFF2FD0FF;
    private static final int COLOR_ROW_HOVER = 0x28FFFFFF;
    private static final int COLOR_ROW_ACTIVE = 0x382FD0FF;
    private static final int COLOR_BUTTON = 0xFF232B34;
    private static final int COLOR_BUTTON_HOVER = 0xFF303A45;
    private static final int COLOR_DANGER = 0xFFB4553F;
    private static final int COLOR_DANGER_HOVER = 0xFFC96A52;
    private static final int COLOR_PIN = 0xFFFF5555;
    private static final int COLOR_ROUTE = 0xFF2FD0FF;
    private static final int COLOR_ORIGIN = 0xFF44FF88;
    /** Road names on the picker map. */
    private static final int COLOR_MAP_LABEL = 0xFFDCE3EA;
    /** The preview readout when the chosen combination has no route at all. */
    private static final int COLOR_WARN = 0xFFFFB454;

    private final Screen parent;

    private List<Destination> all = List.of();
    private List<Destination> shown = List.of();
    private int scroll;
    private int hoveredRow = -1;

    private EditBox searchField;
    private String lastQuery = "";

    /** Map point chosen but not yet committed, as {x, z}, or null. */
    private double[] pendingPick;

    /**
     * How close in pixels the cursor has to be to a place marker to count as pointing at it.
     *
     * <p>Generous on purpose: the marker is under four pixels across, and a hit test that matched it
     * exactly would need a steady hand to use at all.
     */
    private static final int PLACE_HIT_PX = 5;

    /** Height of one line of the font, for the label boxes. */
    private static final int LABEL_HEIGHT = 9;

    /** How soon a second click on the same place marker counts as a double click. */
    private static final long PLACE_DOUBLE_CLICK_MS = 400L;

    /** The place marker clicked last, and when, so a second click on it can be told apart. */
    private Destination lastPlaceClick;
    private long lastPlaceClickAt;

    /** The destination the preview belongs to, or null when nothing has been chosen yet. */
    private Destination previewTarget;
    /** The plan for {@link #previewTarget}, or null when there is nothing to preview. */
    private Navigation.RoutePreview preview;

    /**
     * World position at the centre of the picker map.
     *
     * <p>Kept separate from the player's position so the map can be panned around; the player
     * marker is then drawn wherever they actually are, on or off the visible area.
     */
    private double mapCenterX;
    private double mapCenterZ;
    private double viewRadius = DEFAULT_VIEW_RADIUS;

    /** Where the mouse went down inside the map, so a click can be told apart from a drag. */
    private boolean mapPressed;
    private double mapPressX;
    private double mapPressY;

    private int panelX;
    private int panelY;
    private int panelHeight;
    private int searchY;
    private int mapX;
    private int mapY;
    private int mapW;
    private int mapH;
    private int listTop;
    private int modeY;
    private int preferenceY;
    private int classY;
    /** The transit guidance switch's own row, under the avoid-class row. */
    private int transitY;
    private int footerY;
    private int previewY;
    /**
     * The chosen geometry: the panel width actually used, the width of each column, and where the
     * list column starts.
     *
     * <p>One set of numbers, read by the drawing and the hit tests alike, so the two arrangements
     * cannot disagree about where anything is. In the two-column arrangement the list sits to the
     * right of the map and shares its band; in the stacked one it sits below, and {@link #listPaneW}
     * and the full row width are the same thing.
     */
    private int panelW;
    private int mapPaneW;
    private int listPaneW;
    private int listPaneX;
    /**
     * Whether the map and the list are side by side.
     *
     * <p>Kept because the arrangement decides where the list starts as well as how wide it is: side
     * by side it begins on the map's own top line, stacked it begins under the map. Read from one
     * field rather than inferred again from a coordinate, so the two cannot disagree.
     */
    private boolean columnsSideBySide;
    /** Left edge and width of the full-width rows: the controls and the footer. */
    private int rowX;
    private int rowW;
    /** Gap in force for this layout, which is the comfortable one until the window is too short. */
    private int gap;
    private int preferenceRowHeight;
    private int classRowHeight;
    /** Height of the transit guidance row, which follows the other two through the packing rule. */
    private int transitRowHeight;
    private int visibleRows;
    /**
     * Height of one list row, which the band's remainder is shared out over.
     *
     * <p>Never below {@link #ROW_HEIGHT}; the row count and this figure are produced together by
     * {@link #listRowMetrics}, so the rows fill their band exactly and the drawing, the wheel and the
     * hit tests all walk them at the same pitch.
     */
    private int rowHeight;
    /**
     * Units of the list band that the row height division did not use, shared one per row.
     *
     * <p>Spread over the first {@code rowRemainder} rows by {@link #rowTop}, so the rows between them
     * add up to the band exactly and nothing is left under the last place.
     */
    private int rowRemainder;
    /**
     * Height of the band the columns share, which is the panel less the fixed top and bottom groups.
     *
     * <p>Kept because {@code init} places the readout at the band's bottom -- the line both columns
     * run down to -- and the columns may differ in height, so neither of them can stand in for it.
     */
    private int bandHeight;
    private int modeButtonsX;
    private int modeButtonWidth;
    private int preferenceButtonWidth;
    private int classButtonWidth;

    public DestinationScreen(Screen parent) {
        super(Component.translatable("screen.howtogo.destinations"));
        this.parent = parent;
    }

    /**
     * Keep the world running behind the picker.
     *
     * <p>A destination is often chosen while travelling, so freezing the world to read the map is
     * the wrong trade -- the player would step into lava and find out on closing the screen. Only
     * the keyboard and mouse are captured, the world ticks on.
     */
    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        all = Destinations.all();
        scroll = 0;
        if (previewTarget != null) {
            // The window may have been resized since the choice was made, and the road data may
            // have moved on with it. Re-plan rather than trusting a preview measured in pixels ago.
            preview = Navigation.preview(previewTarget, Navigation.mode(),
                    RoutePreferenceStore.preferences());
        }

        computePanelLayout();

        mapX = panelX + PADDING;
        searchY = panelY + PADDING + HEADER_HEIGHT;
        mapY = searchY + SEARCH_HEIGHT + gap;
        // The columns start on the same line when they are side by side, and the list gets its own
        // line under the map when they are stacked. Both run down to the bottom of the band, which is
        // where the readout begins -- so the columns' bottoms are the band's bottom by construction,
        // and the controls below start exactly there.
        listTop = columnsSideBySide ? mapY : mapY + mapH + gap;
        previewY = mapY + bandHeight;
        modeY = previewY + PREVIEW_LINE_HEIGHT + gap;
        preferenceY = modeY + MODE_ROW_HEIGHT + gap;
        classY = preferenceY + preferenceRowHeight + gap;
        // The transit guidance switch has a row of its own rather than a fourth chip beside the other
        // three: at the panel's narrowest the three already only just fit, and a fourth would cut every
        // label on the row down to two or three letters. One row of its own reads in both languages and
        // leaves room for the next switch that belongs to one mode rather than to every plan.
        transitY = classY + classRowHeight + gap;
        footerY = transitY + transitRowHeight + gap;

        // The mode buttons share one row with a short caption; the widths are derived here, once,
        // so the drawing and the hit tests cannot disagree about where the controls are. The
        // preference and class rows use the same rule: a caption, then equal buttons filling what
        // is left.
        int captionWidth = font.width(modeCaption()) + gap;
        modeButtonWidth = Math.max(36, (rowW - captionWidth - gap * 2) / 3);
        modeButtonsX = rowX + captionWidth;

        int preferenceCaption = Math.max(font.width(metricCaption()), font.width(majorRoadsCaption()))
                + gap;
        // Three switches share this row now: the metric, the major-road preference and the voice
        // toggle. The rule is (row - caption - (n - 1) gaps) / n, the same one the class row uses
        // with n = 6; keeping the two-control divisor here pushed the voice chip past the panel's
        // right edge, which is the same mistake that once put the ice chip off the side.
        preferenceButtonWidth = Math.max(30,
                (rowW - preferenceCaption - gap * 2) / 3);
        // Six chips need six gaps, not two: one after the caption and five between them. Dividing by
        // six while reserving only two gaps pushed the last chip past the panel's right edge, which
        // is what put the ice toggle off the side of the screen.
        classButtonWidth = Math.max(24, (rowW - font.width(avoidCaption()) - gap * 6) / 6);

        searchField = new EditBox(font, rowX, searchY, rowW, SEARCH_HEIGHT,
                Component.translatable("screen.howtogo.search"));
        searchField.setHint(Component.translatable("screen.howtogo.search"));
        searchField.setResponder(value -> {
            if (!value.equals(lastQuery)) {
                lastQuery = value;
                applyFilter();
            }
        });
        addRenderableWidget(searchField);
        setInitialFocus(searchField);

        recenterOnPlayer();
        applyFilter();
    }

    /**
     * Chooses the arrangement and measures both columns from the same three numbers.
     *
     * <p>The map and the list are wanted side by side, which needs a window a little wider than the
     * stacked panel does. The rule is a window of {@link #MIN_SIDE_BY_SIDE_WINDOW} units or more;
     * below that the two columns are abandoned and the original stack of map over list is used
     * instead, at the 268-wide panel it has always had. The threshold is not guessed: the test asks
     * {@link #mapPaneWidth} and {@link #listPaneWidth} -- the very functions the arrangement is then
     * built from -- whether the split they describe at that width still respects both minimums, so
     * the advertised floor and the drawn columns cannot drift apart.
     *
     * <p>At the threshold the map column is 125 and the list column 160; at any wider window the
     * panel reaches {@link #MAX_PANEL_WIDTH} and they are 137 and 176. The invariant {@code
     * mapPaneW + COLUMN_GAP + listPaneW == content} holds by construction, because the list column is
     * whatever the map column and the gap leave.
     */
    private void computePanelLayout() {
        int usable = Math.max(MIN_PANEL_WIDTH, width - MARGIN * 2);
        int twoColumnWidth = Math.min(MAX_PANEL_WIDTH, usable) - PADDING * 2;
        // Both minimums are only meaningful for the width they are applied to: a share of a narrow
        // content area can land under its own floor, so the test is made on the columns the share
        // actually produces rather than on the minimums alone.
        boolean sideBySide = mapPaneWidth(twoColumnWidth) >= MIN_MAP_PANE_WIDTH
                && listPaneWidth(twoColumnWidth) >= MIN_LIST_PANE_WIDTH;

        // The column split always uses COLUMN_GAP, whatever the row gap has been squeezed to, so
        // the test above and the arithmetic below are made against the same number.
        if (sideBySide) {
            panelW = twoColumnWidth + PADDING * 2;
            mapPaneW = mapPaneWidth(panelW - PADDING * 2);
            listPaneW = listPaneWidth(panelW - PADDING * 2);
        } else {
            panelW = Math.max(MIN_PANEL_WIDTH, Math.min(PANEL_WIDTH, usable));
            mapPaneW = panelW - PADDING * 2;
            listPaneW = mapPaneW;
        }

        panelX = Math.max(MARGIN, (width - panelW) / 2);
        rowX = panelX + PADDING;
        rowW = panelW - PADDING * 2;
        listPaneX = sideBySide ? rowX + mapPaneW + COLUMN_GAP : rowX;
        columnsSideBySide = sideBySide;
        mapW = mapPaneW;
        computeVerticalLayout(sideBySide);
        panelY = Math.max(4, (height - panelHeight) / 2);
    }

    /**
     * Sizes and stacks the panel's vertical bands.
     *
     * <p>Laid out here rather than as a run of constants because the GUI is often only ~240 units
     * tall. The comfortable layout is tried first and, when it does not fit, the gaps and the
     * control rows then tighten as one -- the bands are measured from the values chosen here, so no
     * two of them can be computed from different assumptions and end up overlapping.
     *
     * <p>Three parts. Fixed at the top: the padding, the header and the search box. Anchored at the
     * bottom: the readout and every control under it, measured as one group so the band above can be
     * derived from what is left rather than guessed -- which is what keeps those controls on the
     * panel and off the columns. Stretchy in between: the columns, which take the whole band. Side by
     * side the map and the list share it and both run to its bottom; stacked the map takes its share
     * and the list has a band of its own below, stretched by the same rule.
     *
     * @param sideBySide whether the map and the list share a band or are stacked
     */
    private void computeVerticalLayout(boolean sideBySide) {
        gap = GAP;
        preferenceRowHeight = PREFERENCE_ROW_HEIGHT;
        classRowHeight = CLASS_ROW_HEIGHT;
        transitRowHeight = PREFERENCE_ROW_HEIGHT;
        // The bottom group -- the readout and everything under it -- is measured first and then
        // anchored: the columns get whatever is left above it. That is what puts the controls against
        // the panel's bottom edge rather than just under the list, and it is also what keeps them on
        // the panel, since the band they are subtracted from is the panel itself. Five gaps between
        // the six elements of the group, and the footer's own padding below it.
        int bottomGroup = PREVIEW_LINE_HEIGHT + MODE_ROW_HEIGHT + preferenceRowHeight
                + classRowHeight + transitRowHeight + FOOTER_HEIGHT + PADDING + gap * 5;
        int panel = clamp(height - MARGIN * 2, MIN_PANEL_HEIGHT, Math.max(MIN_PANEL_HEIGHT, height));
        int band = panel - (headerHeight() + bottomGroup + gap);
        if (band < MIN_MAP_HEIGHT + MIN_LIST_ROW) {
            // A window too short for comfortable rows: tighten every gap and both control rows, then
            // measure the band again from the same expression rather than from a second guess at it.
            gap = GAP_PACKED;
            preferenceRowHeight = PREFERENCE_ROW_PACKED;
            classRowHeight = CLASS_ROW_PACKED;
            transitRowHeight = PREFERENCE_ROW_PACKED;
            bottomGroup = PREVIEW_LINE_HEIGHT + MODE_ROW_HEIGHT + preferenceRowHeight
                    + classRowHeight + transitRowHeight + FOOTER_HEIGHT + PADDING + gap * 5;
            band = panel - (headerHeight() + bottomGroup + gap);
        }

        // Side by side both columns share the band, so both fill it and end on the same line. Stacked
        // they cannot both fill one band, so the band is split between them -- the map's share, then
        // the list's with the column gap taken out -- and each is then stretched to its own share.
        // Measuring the list from its share rather than from "whatever the map leaves" is what stops
        // the map eating the list, which it would now that it is no longer capped.
        int listBand = sideBySide ? band : (band - gap) * (100 - MAP_BAND_SHARE) / 100;
        int[] metrics = listRowMetrics(listBand);
        visibleRows = metrics[0];
        rowHeight = metrics[1];
        // What the row height division left over. It is spread one unit at a time over the first rows
        // rather than dropped, so the rows between them end exactly at the bottom of the band.
        rowRemainder = Math.max(0, Math.max(0, listBand) - visibleRows * rowHeight);
        // The map runs from the top of the band down to the top of the list, so the two always meet
        // exactly with only the column gap between them -- the split is a proportion, and letting it
        // round on both sides left a pixel of dead space. Nothing else caps the map: the pane ends up
        // taller than it is wide, which is what stretching it to its band means. MAP_BAND_SHARE
        // decides the width split and the stacked height split, not a limit on either.
        mapH = sideBySide ? band : Math.max(MIN_MAP_HEIGHT, band - listBand - gap);
        if (mapH > band) {
            mapH = band;
        }
        // The band's height is kept because `init` needs its bottom to place the readout: that is the
        // line both columns run down to, whichever of them happens to be the taller one.
        bandHeight = band;

        panelHeight = panel;
    }

    /** Header, search box and the padding above them: everything down to the columns. */
    private static int headerHeight() {
        return PADDING + HEADER_HEIGHT + SEARCH_HEIGHT;
    }

    /**
     * How many whole rows the list band holds and how tall each should be, the two chosen together.
     *
     * <p>Rows first: as many as fit at the standard height, and never more than {@link
     * #MAX_VISIBLE_ROWS} or than there are places to show. Then the height, which is whatever makes
     * those rows add up to the band -- floored at the standard row height, because a row taller than
     * that is fine (the text centres in it) but a shorter one is not. Picking the height from the
     * count this way is what makes the rows end exactly at the bottom of the band instead of leaving
     * up to a whole row of empty list under the last place.
     *
     * <p>If the floor and the exact fit disagree -- only possible in a band too short for even one
     * normal row -- the exact fit wins, so the list still stops where the band does.
     *
     * @return {@code {rows, rowHeight}}
     */
    private int[] listRowMetrics(int band) {
        int room = Math.max(0, band);
        int counted = Math.min(all.size(), Math.max(1, room / ROW_HEIGHT));
        int rows = clamp(counted, 1, MAX_VISIBLE_ROWS);
        int exact = room / rows;
        int rowHeight = exact <= ROW_HEIGHT ? ROW_HEIGHT : exact;
        if (rowHeight > room) {
            rowHeight = room;
        }
        return new int[]{rows, rowHeight};
    }

    /**
     * The name line of a list row: the place in its own colour, then the source note after it.
     *
     * <p>The note is a decoration of this line and nothing else. It is derived from the destination's
     * source id, never from its name, which stays exactly as the source supplied it -- the route
     * instructions, the spoken announcements, the HUD header and the search all read that name, and
     * baking "(From XMM)" into it would have the voice read the note aloud and make searching for
     * the note succeed.
     *
     * <p>The note's width is reserved unconditionally and the name is cut to what is left, so the
     * note appears on every row that has one. That is the opposite of the first attempt, which let a
     * name long enough to fill the column push the note out; the note is the smaller, more
     * informative mark, so it keeps its room and the name gives way.
     *
     * <p>Degenerate case: a column too narrow for the note itself. A note is then worth nothing --
     * half of "(From XMM)" reads as corrupt text -- so it is dropped whole and the name takes the
     * entire column instead. This is unreachable at any width the picker actually builds, where the
     * name column is at least 100 units and the note is 66, but it is handled so that no width can
     * produce a half-written note. The name is never cut to a negative room either.
     *
     * <p>Selection deliberately does not change the colour. The name is drawn in the source's colour
     * whether or not the row is picked out; what marks a picked row is its background, drawn by
     * {@code drawRows}.
     */
    private void drawNameLine(GuiGraphics graphics, Destination destination, int x, int y, int width) {
        String name = destination.name();
        String note = sourceNote(destination.source());
        int noteWidth = note.isEmpty() ? 0 : font.width(note);
        boolean noteFits = noteWidth > 0 && noteWidth <= width;

        int nameRoom = noteFits ? width - noteWidth : width;
        String shownName = font.plainSubstrByWidth(name, nameRoom);
        graphics.drawString(font, shownName, x, y, readableColor(destination), false);

        if (noteFits) {
            graphics.drawString(font, note, x + font.width(shownName), y, COLOR_SOURCE_NOTE, false);
        }
    }

    /**
     * The colour to draw a name in: the source's own, when it is light enough to read on this panel.
     *
     * <p>The list is drawn on a near-black card, and the colour palette a source may use includes
     * black and dark grey. Drawn faithfully those would be invisible, which is worse than not
     * honouring the colour at all -- so a colour too dark to separate from the panel falls back to
     * the ordinary text colour. The luminance threshold is deliberately low: it only catches the
     * colours that would genuinely disappear.
     */
    private static int readableColor(Destination destination) {
        if (!destination.hasColor()) {
            return COLOR_PRIMARY;
        }
        int color = destination.color();
        int luminance = (299 * ((color >> 16) & 0xFF)
                + 587 * ((color >> 8) & 0xFF)
                + 114 * (color & 0xFF)) / 1000;
        return luminance < 40 ? COLOR_PRIMARY : color;
    }

    /**
     * The short note a source wants beside its places, or an empty string for one that wants none.
     *
     * <p>Keyed on the source id rather than on the source object, because the list holds destinations
     * and not their sources. The key is built from the id by convention -- {@code xaero_waypoint}
     * gives {@code hud.howtogo.source.xaero.short}, matching the full label's
     * {@code hud.howtogo.source.xaero} -- so a new source brings its own note simply by shipping that
     * key, and one without the key gets nothing drawn.
     *
     * <p>A registered source may name its own key instead, through
     * {@link bili.dongsz.howtogo.route.DestinationSource#noteKey()}: the convention is keyed on the
     * first word of the id, which is a fact about this mod's own ids rather than about anybody's. The
     * source's own answer wins where it gave one; the convention is what the four built-in sources
     * were built with and what an id that owns no source at all falls back to.
     */
    private static String sourceNote(String source) {
        if (source == null || source.isEmpty()) {
            return "";
        }
        String key = DestinationSources.noteKey(source);
        if (key.isEmpty()) {
            key = "hud.howtogo.source." + noteSuffix(source) + ".short";
        }
        String note = Component.translatable(key).getString();
        // Component.translatable falls back to the key itself when nothing is defined for it, and a
        // raw key printed at the end of a name is worse than printing nothing at all.
        return note.equals(key) ? "" : note;
    }

    /**
     * The lang-key middle of a source id: the part before the first underscore.
     *
     * <p>{@code xaero_waypoint} names the Xaero <em>waypoint</em> source but shares the
     * {@code xaero} label with the mod it comes from, so the display keys are keyed on that first
     * word rather than on the whole id.
     */
    private static String noteSuffix(String source) {
        int underscore = source.indexOf('_');
        return underscore < 0 ? source : source.substring(0, underscore);
    }

    /**
     * Top of the given visible row, in screen units.
     *
     * <p>The caller passes the absolute list index; the row's slot is {@code index - scroll}, which is
     * the same offset the drawing and the hit test use, so they cannot disagree. Each row also picks
     * up one unit of the band's remainder until that is used up, which is what makes the rows add up
     * to the band rather than leaving a strip of up to {@code rows - 1} units below the last place.
     */
    private int rowTop(int index) {
        int slot = index - scroll;
        return listTop + slot * rowHeight + Math.min(slot, rowRemainder);
    }

    /** Bottom of the whole list: the last row plus the last of the shared-out remainder. */
    private int listBottom() {
        return rowTop(scroll + visibleRows);
    }

    /**
     * Height of the list pane, from the top of its first row to the bottom of its last.
     *
     * <p>Asked of {@link #listBottom()} rather than kept as its own figure. A separate height field
     * maintained alongside the row geometry is what went wrong once already: when the rows became
     * adaptive it stopped being written, and the wheel hit test that read it rejected every position
     * below the list's top edge, which is why the list could not be scrolled.
     */
    private int listHeight() {
        return listBottom() - listTop;
    }

    private void drawRows(GuiGraphics graphics, int mouseX, int mouseY) {
        Destination active = Navigation.target();
        int last = Math.min(shown.size(), scroll + visibleRows);

        for (int i = scroll; i < last; i++) {
            Destination destination = shown.get(i);
            int rowY = rowTop(i);
            int rowBottom = rowTop(i + 1);
            boolean hovered = mouseX >= listPaneX && mouseX <= listPaneX + listPaneW
                    && mouseY >= rowY && mouseY < rowBottom;
            // The row being previewed is picked out as well as the one being navigated: with the
            // route now drawn before it is committed, the list has to show which route that is.
            boolean isActive = samePlace(active, destination);
            boolean isPreview = samePlace(previewTarget, destination);

            if (hovered) {
                hoveredRow = i;
                graphics.fill(listPaneX, rowY, listPaneX + listPaneW,
                        rowBottom - 1, COLOR_ROW_HOVER);
            } else if (isActive || isPreview) {
                graphics.fill(listPaneX, rowY, listPaneX + listPaneW,
                        rowBottom - 1, COLOR_ROW_ACTIVE);
            }

            int textX = listPaneX + 6;
            // The distance column sits at the far edge of the list, so it needs its width before
            // the name is measured against what is left; the reserve covers it and the scroll bar.
            String distance = distanceLabel(destination);
            int distanceWidth = font.width(distance);
            int nameWidth = Math.max(24, listPaneW - 12 - distanceWidth - 6);
            // Two lines inside a row whose height is the band's share: the pair is centred in the row
            // rather than pinned to its top, so stretched rows do not leave their text stranded.
            int nameY = rowY + (rowBottom - rowY - 17) / 2;
            // No selection flag: the name keeps the source's colour whether or not the row is
            // picked out, and the row's background is what marks it.
            drawNameLine(graphics, destination, textX, nameY, nameWidth);

            graphics.drawString(font, destination.coordinates(), textX, nameY + 10,
                    COLOR_SECONDARY, false);

            graphics.drawString(font, distance,
                    listPaneX + listPaneW - 6 - distanceWidth, nameY, COLOR_SECONDARY, false);
        }

        if (shown.size() > visibleRows) {
            int trackX = listPaneX + listPaneW - 2;
            int bottom = listBottom();
            int maxScroll = shown.size() - visibleRows;
            int thumbHeight = Math.max(12, (bottom - listTop) * visibleRows / shown.size());
            int thumbY = listTop + (int) ((bottom - listTop - thumbHeight)
                    * ((double) scroll / maxScroll));
            graphics.fill(trackX, listTop, trackX + 2, bottom, 0x30FFFFFF);
            graphics.fill(trackX, thumbY, trackX + 2, thumbY + thumbHeight, 0x80FFFFFF);
        }
    }

    /** The preview readout, the four control rows, the footer and the padding under them. */
    private int footerHeight() {
        return PREVIEW_LINE_HEIGHT + MODE_ROW_HEIGHT + preferenceRowHeight + classRowHeight
                + transitRowHeight + FOOTER_HEIGHT + PADDING;
    }

    /** Puts the player back in the middle of the picker map at the default zoom. */
    private void recenterOnPlayer() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mapCenterX = mc.player.getX();
            mapCenterZ = mc.player.getZ();
        }
        viewRadius = DEFAULT_VIEW_RADIUS;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private void applyFilter() {
        String query = lastQuery == null ? "" : lastQuery.trim().toLowerCase(Locale.ROOT);
        if (query.isEmpty()) {
            shown = all;
        } else {
            List<Destination> matches = new ArrayList<>();
            for (Destination destination : all) {
                // A source may ask not to be searched: its list is long, untyped and better browsed
                // by hand, and matching it would bury the names the player meant to find. Not being
                // searchable is not being hidden -- with an empty query the list is shown whole.
                if (!DestinationSources.searchable(destination.source())) {
                    continue;
                }
                if (destination.name().toLowerCase(Locale.ROOT).contains(query)
                        || destination.coordinates().contains(query)) {
                    matches.add(destination);
                }
            }
            shown = matches;
        }
        scroll = 0;
    }

    // ------------------------------------------------------------------ render

    /**
     * 1.20.1 passes the graphics object alone; the mouse position and partial tick the 1.21 signature
     * carried were added in a later version. Nothing is drawn here either way: dimming is handled in
     * render so the panel sits between it and the widgets.
     */
    @Override
    public void renderBackground(GuiGraphics graphics) {
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, COLOR_DIM);

        HudDraw.fillRounded(graphics, panelX, panelY, panelX + panelW, panelY + panelHeight,
                CORNER_RADIUS, COLOR_PANEL);
        HudDraw.outlineRounded(graphics, panelX, panelY, panelX + panelW, panelY + panelHeight,
                CORNER_RADIUS, COLOR_PANEL_EDGE);

        graphics.drawString(font, title, panelX + PADDING, panelY + PADDING, COLOR_HEADER, false);

        drawPickerMap(graphics, mouseX, mouseY);
        drawList(graphics, mouseX, mouseY);
        drawPreviewSummary(graphics);
        drawModeSelector(graphics, mouseX, mouseY);
        drawPreferenceControls(graphics, mouseX, mouseY);
        drawClassControls(graphics, mouseX, mouseY);
        drawTransitControls(graphics, mouseX, mouseY);
        drawFooter(graphics, mouseX, mouseY);

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    /**
     * The destination list, in whichever column it was given.
     *
     * <p>The hovered row is worked out here and read by {@code mouseClicked}, so the list column's
     * own bounds are the only thing that decides what is under the cursor.
     */
    private void drawList(GuiGraphics graphics, int mouseX, int mouseY) {
        hoveredRow = -1;
        if (shown.isEmpty()) {
            graphics.drawString(font,
                    Component.translatable(all.isEmpty()
                            ? "screen.howtogo.no_destinations"
                            : "screen.howtogo.no_matches").getString(),
                    listPaneX + 4, listTop + 4, COLOR_SECONDARY, false);
            return;
        }
        drawRows(graphics, mouseX, mouseY);
    }

    // ------------------------------------------------------------------ map

    /**
     * Road names on the picker map, so a place can be recognised by the roads around it.
     *
     * <p>Named roads only, and dropped on collision: the map is a couple of hundred pixels wide and
     * a dense network would otherwise overprint into an unreadable smear.
     *
     * <h2>What this pass used to cost</h2>
     * Every named road was asked for its chain -- {@link RoadChains#chainContaining} -- to find out
     * whether it was the piece that carries the name, and that call builds an adjacency index of the
     * whole network before it answers. One per named road, per frame, before any check that the road
     * was anywhere near this little map: on a network of a few hundred segments with fifty names that
     * was about a millisecond of work and five megabytes of garbage every frame, and it grew with the
     * product of the two (a network of three thousand segments with seven hundred names measured at a
     * hundred and twenty milliseconds a frame). The answer is a property of the geometry, so it now
     * comes from the grouping, which is rebuilt only when the roads change -- and the roads are culled
     * against the map before they are asked about at all.
     */
    private void drawMapLabels(GuiGraphics graphics, Minecraft mc, RoadNetwork network, double scale,
                               double centerX, double centerY,
                               double minX, double minY, double maxX, double maxY) {
        List<double[]> placed = new ArrayList<>();
        RoadChains.Grouping grouping = RoadChains.cachedGrouping(network);
        // A label that fits inside the map is at most as large as the map, so a road whose midpoint is
        // a whole map's dimension away from it cannot produce a box that passes the test below. Wider
        // than any label, and no assumption about the font: a cheap guard, not the rule.
        double padX = maxX - minX;
        double padY = maxY - minY;
        for (RoadSegment segment : network.segments()) {
            String name = segment.name();
            if (name == null) {
                continue;
            }
            double[] mid = segment.midpoint();
            double x = centerX + (mid[0] - mapCenterX) * scale;
            double y = centerY + (mid[1] - mapCenterZ) * scale;
            if (x < minX - padX || x > maxX + padX || y < minY - padY || y > maxY + padY) {
                continue;
            }
            if (!grouping.carriesLabel(segment)) {
                continue;
            }

            // Along the line, from the road's own world direction. This map is north-up, so the world
            // direction is the screen direction and no rotation of the map has to be undone first.
            int last = segment.vertexCount() - 1;
            float angle = HudDraw.labelAngle(segment.x(last) - segment.x(0),
                    segment.z(last) - segment.z(0));

            int width = mc.font.width(name);
            double[] extents = HudDraw.rotatedExtents(width, LABEL_HEIGHT, angle);
            // The box actually painted, centred on the point the label has always been centred on, so
            // rotating it does not shift it off the road.
            double centreY = y - 4 + LABEL_HEIGHT / 2.0;
            double boxX = x - extents[0] / 2.0;
            double boxY = centreY - extents[1] / 2.0;
            if (boxX < minX + 1 || boxX + extents[0] > maxX - 1
                    || boxY < minY + 1 || boxY + extents[1] > maxY - 1) {
                continue;
            }
            boolean clash = false;
            for (double[] other : placed) {
                if (boxX < other[0] + other[2] && other[0] < boxX + extents[0]
                        && boxY < other[1] + other[3] && other[1] < boxY + extents[1]) {
                    clash = true;
                    break;
                }
            }
            if (clash) {
                continue;
            }
            placed.add(new double[] {boxX, boxY, extents[0], extents[1]});

            PoseStack pose = graphics.pose();
            pose.pushPose();
            pose.translate(x, centreY, 0.0);
            pose.mulPose(Axis.ZP.rotation(angle));
            graphics.drawString(mc.font, name, -width / 2, -4, COLOR_MAP_LABEL, false);
            pose.popPose();
        }
    }

    private void drawPickerMap(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.fill(mapX - 1, mapY - 1, mapX + mapW + 1, mapY + mapH + 1, COLOR_PANEL_EDGE);
        graphics.fill(mapX, mapY, mapX + mapW, mapY + mapH, COLOR_MAP_BACKDROP);

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        double playerX = mc.player.getX();
        double playerZ = mc.player.getZ();
        double scale = mapW / (viewRadius * 2.0);
        double centerX = mapX + mapW / 2.0;
        double centerY = mapY + mapH / 2.0;

        // North-up, unlike the travelling HUD: when choosing a place, a consistent orientation
        // matters more than knowing which way the player happens to be facing.
        PoseStack pose = graphics.pose();
        pose.pushPose();
        VertexConsumer vc = HudDraw.consumer(graphics);
        PoseStack.Pose last = pose.last();

        double minX = mapX;
        double minY = mapY;
        double maxX = mapX + mapW;
        double maxY = mapY + mapH;
        double reach = viewRadius * 1.5;

        // Where every place sits on this map, worked out once and handed to both the marker pass and
        // the hover readout below. The two used to ask for it separately, which meant asking every
        // source -- a town's worth of stations among them -- for its destinations twice a frame.
        List<PlaceHit> places = placeHits();

        RoadNetwork network = RoadStore.get();
        for (RoadSegment segment : network.segments()) {
            for (int i = 1; i < segment.vertexCount(); i++) {
                double ax = segment.x(i - 1);
                double az = segment.z(i - 1);
                double bx = segment.x(i);
                double bz = segment.z(i);
                if (Math.min(ax, bx) > mapCenterX + reach || Math.max(ax, bx) < mapCenterX - reach
                        || Math.min(az, bz) > mapCenterZ + reach
                        || Math.max(az, bz) < mapCenterZ - reach) {
                    continue;
                }
                int color = 0xFF000000 | (segment.roadClass().color() & 0xFFFFFF);
                HudDraw.emitClipped(last, vc,
                        centerX + (ax - mapCenterX) * scale, centerY + (az - mapCenterZ) * scale,
                        centerX + (bx - mapCenterX) * scale, centerY + (bz - mapCenterZ) * scale,
                        0.7, color, 0x9A, minX, minY, maxX, maxY);
            }
        }

        // The machine-read rails -- Create's tracks and MTR's -- drawn with the roads and before the
        // labels: a station on the list should be a station the player can see the line to, and for a
        // station that exists only in MTR that line is the one MTR's own rails run along. The whole
        // quad sequence stays ahead of the name pass below, which is what keeps the emit order legal.
        for (RoadSegment segment : RailLayers.all()) {
            for (int i = 1; i < segment.vertexCount(); i++) {
                double ax = segment.x(i - 1);
                double az = segment.z(i - 1);
                double bx = segment.x(i);
                double bz = segment.z(i);
                if (Math.min(ax, bx) > mapCenterX + reach || Math.max(ax, bx) < mapCenterX - reach
                        || Math.min(az, bz) > mapCenterZ + reach
                        || Math.max(az, bz) < mapCenterZ - reach) {
                    continue;
                }
                int color = 0xFF000000 | (segment.roadClass().color() & 0xFFFFFF);
                HudDraw.emitClipped(last, vc,
                        centerX + (ax - mapCenterX) * scale, centerY + (az - mapCenterZ) * scale,
                        centerX + (bx - mapCenterX) * scale, centerY + (bz - mapCenterZ) * scale,
                        0.7, color, 0x9A, minX, minY, maxX, maxY);
            }
        }

        Route route = Navigation.route();
        if (route.isPresent()) {
            List<double[]> points = route.points();
            for (int i = 1; i < points.size(); i++) {
                double[] a = points.get(i - 1);
                double[] b = points.get(i);
                HudDraw.emitClipped(last, vc,
                        centerX + (a[0] - mapCenterX) * scale, centerY + (a[1] - mapCenterZ) * scale,
                        centerX + (b[0] - mapCenterX) * scale, centerY + (b[1] - mapCenterZ) * scale,
                        1.1, COLOR_ROUTE, 0xE0, minX, minY, maxX, maxY);
            }
        }

        // The preview goes down in the same ink as the live route, so a route looked at before
        // committing and the same route afterwards read as the same thing. Drawn after it because
        // a preview is only ever the newer of the two, and would otherwise hide under the line it
        // is being compared with.
        Navigation.RoutePreview planned = preview;
        if (planned != null && planned.isPresent()) {
            List<double[]> points = planned.route().points();
            for (int i = 1; i < points.size(); i++) {
                double[] a = points.get(i - 1);
                double[] b = points.get(i);
                HudDraw.emitClipped(last, vc,
                        centerX + (a[0] - mapCenterX) * scale, centerY + (a[1] - mapCenterZ) * scale,
                        centerX + (b[0] - mapCenterX) * scale, centerY + (b[1] - mapCenterZ) * scale,
                        1.1, COLOR_ROUTE, 0xE0, minX, minY, maxX, maxY);
            }
        }

        double originX = Navigation.tripOriginX();
        double originZ = Navigation.tripOriginZ();
        if (!Double.isNaN(originX)) {
            marker(last, vc, centerX + (originX - mapCenterX) * scale,
                    centerY + (originZ - mapCenterZ) * scale, COLOR_ORIGIN, minX, minY, maxX, maxY);
        }
        // A preview starts at the player rather than at a pinned trip origin, so without a live
        // trip its start has to be marked from the preview itself or the cyan line begins nowhere.
        if (Double.isNaN(originX) && planned != null) {
            marker(last, vc, centerX + (planned.originX() - mapCenterX) * scale,
                    centerY + (planned.originZ() - mapCenterZ) * scale, COLOR_ORIGIN,
                    minX, minY, maxX, maxY);
        }
        Destination target = Navigation.target();
        if (target != null) {
            marker(last, vc, centerX + (target.x() - mapCenterX) * scale,
                    centerY + (target.z() - mapCenterZ) * scale, COLOR_PIN, minX, minY, maxX, maxY);
        }
        if (pendingPick != null) {
            marker(last, vc, centerX + (pendingPick[0] - mapCenterX) * scale,
                    centerY + (pendingPick[1] - mapCenterZ) * scale, COLOR_ACCENT, minX, minY, maxX, maxY);
        }

        // The player is drawn only when the whole dot falls inside the map: panning leaves them far
        // off the visible area, and a dot painted regardless ended up on the panel around the map.
        // Every other mark in this method clips for the same reason; this one was the exception.
        emitDiscIfInside(last, vc, centerX + (playerX - mapCenterX) * scale,
                centerY + (playerZ - mapCenterZ) * scale, 3.0, 0xFFFFFFFF, minX, minY, maxX, maxY);

        // Every place gets its marker on the preview as well, so the list and the map agree about
        // where the places are: drawn through the same hit list the hover and double click use.
        drawPlaceMarkers(places, last, vc, minX, minY, maxX, maxY);

        // Names go last, after every quad: GuiGraphics.drawString flushes the batch it writes to,
        // which ends the QUADS BufferBuilder behind vc. Emitting even one more quad after this
        // throws "Not building!", so the label pass must stay at the tail of the quad sequence.
        drawMapLabels(graphics, mc, network, scale, centerX, centerY, minX, minY, maxX, maxY);

        pose.popPose();
        graphics.flush();

        // Hover crosshair and the coordinates under it, so a point can be chosen precisely. The
        // crosshair is drawn on the exact cursor pixel, so at the map's edge half of it would reach
        // outside; it is dropped unless it fits, and the label is then placed on whichever side has
        // room for it. Both belong to the map and neither may be painted on the panel around it.
        if (insideMap(mouseX, mouseY)) {
            if (crosshairFits(mouseX, mouseY)) {
                graphics.fill((int) mouseX - 4, (int) mouseY, (int) mouseX + 5, (int) mouseY + 1, 0x90FFFFFF);
                graphics.fill((int) mouseX, (int) mouseY - 4, (int) mouseX + 1, (int) mouseY + 5, 0x90FFFFFF);
            }

            double worldX = mapCenterX + (mouseX - centerX) / scale;
            double worldZ = mapCenterZ + (mouseY - centerY) / scale;
            String coordinates = Math.round(worldX) + ", " + Math.round(worldZ);
            // Pointing at a place marker names it. The list can be long and the map crowded, so this
            // is how a marker is tied to the entry it stands for without having to click it. The
            // coordinates stay, because they are what makes a point choosable precisely.
            PlaceHit hovered = placeAt(places, mouseX, mouseY);
            String label = hovered == null
                    ? coordinates
                    : Component.translatable("screen.howtogo.hover_place",
                            hovered.destination().name(), coordinates).getString();
            // Above the cursor when there is room, otherwise below it; only if neither side fits is
            // the readout dropped, which can only happen on a map shorter than the text itself.
            int labelY = (int) mouseY - 12;
            if (labelY < minY + 1) {
                labelY = (int) mouseY + 6;
            }
            if (labelY >= minY + 1 && labelY + 9 <= maxY - 1) {
                int labelX = (int) mouseX + 5;
                int width = font.width(label);
                if (labelX + width > maxX - 1) {
                    labelX = (int) mouseX - 5 - width;
                }
                if (labelX >= minX + 1 && labelX + width <= maxX - 1) {
                    graphics.drawString(font, label, labelX, labelY, COLOR_PRIMARY, true);
                }
            }
        }

        // "Back to me": after panning, the player marker can be far off the visible area.
        int bx = mapX + mapW - RECENTER_SIZE - 2;
        int by = mapY + 2;
        HudDraw.fillRounded(graphics, bx, by, bx + RECENTER_SIZE, by + RECENTER_SIZE, 2, 0xA010171C);
        int iconColor = 0xFFC0CCD8;
        graphics.fill(bx + 2, by + RECENTER_SIZE / 2, bx + RECENTER_SIZE - 2,
                by + RECENTER_SIZE / 2 + 1, iconColor);
        graphics.fill(bx + RECENTER_SIZE / 2, by + 2, bx + RECENTER_SIZE / 2 + 1,
                by + RECENTER_SIZE - 2, iconColor);
    }

    /**
     * Draws a mark only when the whole of it falls inside the map.
     *
     * <p>Fully inside rather than merely centred inside: a disc is a couple of units across, and
     * testing only its centre let its rim paint over the map's border and the panel beyond it.
     */
    private static void emitDiscIfInside(PoseStack.Pose pose, VertexConsumer vc, double x, double y,
                                         double radius, int argb,
                                         double minX, double minY, double maxX, double maxY) {
        if (x - radius < minX || x + radius > maxX || y - radius < minY || y + radius > maxY) {
            return;
        }
        HudDraw.emitDisc(pose, vc, x, y, radius, argb, 0xFF);
    }

    private static void marker(PoseStack.Pose pose, VertexConsumer vc, double x, double y,
                               int color, double minX, double minY, double maxX, double maxY) {
        emitDiscIfInside(pose, vc, x, y, 2.6, color, minX, minY, maxX, maxY);
    }

    /** Every place's marker on the preview as it was last drawn, and the destination it stands for. */
    private record PlaceHit(Destination destination, int x, int y) {
    }

    /**
     * Every place's position on the preview, in the same transform the drawing uses.
     *
     * <p>Computed rather than remembered from the last draw, so input handling and drawing agree
     * within the same frame instead of one behind it. The transform is entirely fields this screen
     * already holds -- the map rect, the pan and the zoom -- so this is the same arithmetic
     * {@code drawPickerMap} does, asked by the other caller.
     */
    private List<PlaceHit> placeHits() {
        double centerX = mapX + mapW / 2.0;
        double centerY = mapY + mapH / 2.0;
        double scale = mapW / (viewRadius * 2.0);
        List<PlaceHit> hits = new ArrayList<>();
        for (Destination place : Destinations.places()) {
            int x = (int) Math.round(centerX + (place.x() - mapCenterX) * scale);
            int y = (int) Math.round(centerY + (place.z() - mapCenterZ) * scale);
            if (x < mapX || x > mapX + mapW || y < mapY || y > mapY + mapH) {
                continue;
            }
            hits.add(new PlaceHit(place, x, y));
        }
        return hits;
    }

    /**
     * The place whose marker is under a screen point, or null.
     *
     * <p>A generous catch area on purpose: the marker is under four pixels across, and a hit test
     * that matched it exactly would make the feature unusable without a steady hand.
     *
     * <p>Asks for the positions itself, for the input handlers: a click and a release arrive on their
     * own, and neither has a frame's worth of positions to hand. The drawing passes hold the list they
     * already built and use the overload below.
     */
    private PlaceHit placeAt(double mouseX, double mouseY) {
        return placeAt(placeHits(), mouseX, mouseY);
    }

    /** The same test against positions the caller has already worked out for this frame. */
    private static PlaceHit placeAt(List<PlaceHit> places, double mouseX, double mouseY) {
        for (PlaceHit hit : places) {
            if (Math.abs(mouseX - hit.x()) <= PLACE_HIT_PX
                    && Math.abs(mouseY - hit.y()) <= PLACE_HIT_PX) {
                return hit;
            }
        }
        return null;
    }

    /**
     * Every place gets its marker on the preview as well, so the list and the map agree about where
     * the places are. Before the name pass, for the reason the whole quad sequence is: a string
     * flushes the batch these were written into. Same list, colour and shape as the other two maps,
     * at this map's smaller size.
     *
     * <p>The positions come from the caller: they are the same ones the hover readout below is
     * measured against, and working them out again here would be the second answer the class's own
     * rule about one set of numbers exists to prevent.
     */
    private void drawPlaceMarkers(List<PlaceHit> places, PoseStack.Pose pose, VertexConsumer vc,
                                  double minX, double minY, double maxX, double maxY) {
        for (PlaceHit hit : places) {
            emitPlaceMarker(pose, vc, hit.x(), hit.y(), minX, minY, maxX, maxY);
        }
    }

    /** A place's marker, skipped entirely when it falls outside the map. */
    private static void emitPlaceMarker(PoseStack.Pose pose, VertexConsumer vc, double x, double y,
                                        double minX, double minY, double maxX, double maxY) {
        if (x < minX || x > maxX || y < minY || y > maxY) {
            return;
        }
        HudDraw.emitPlaceMarker(pose, vc, x, y, HudDraw.PLACE_MARKER_SMALL_PX * 0.5,
                HudDraw.COLOR_PLACE);
    }

    /** Whether the whole hover crosshair falls inside the map rect. */
    private boolean crosshairFits(double mouseX, double mouseY) {
        return mouseX - 4 >= mapX && mouseX + 5 <= mapX + mapW
                && mouseY - 4 >= mapY && mouseY + 5 <= mapY + mapH;
    }

    private boolean insideMap(double mouseX, double mouseY) {
        return mouseX >= mapX && mouseX <= mapX + mapW && mouseY >= mapY && mouseY <= mapY + mapH;
    }

    /** Converts a click inside the map into a world position. */
    private double[] worldAt(double mouseX, double mouseY) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return null;
        }
        double scale = mapW / (viewRadius * 2.0);
        double centerX = mapX + mapW / 2.0;
        double centerY = mapY + mapH / 2.0;
        return new double[]{
                mapCenterX + (mouseX - centerX) / scale,
                mapCenterZ + (mouseY - centerY) / scale};
    }

    // ------------------------------------------------------------------ list

    private static String distanceLabel(Destination destination) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return "";
        }
        double distance = Math.hypot(mc.player.getX() - destination.x(),
                mc.player.getZ() - destination.z());
        return Route.formatDistance(distance);
    }

    // ------------------------------------------------------------------ footer

    /**
     * Travel-mode row: one button per mode, the active one picked out in the accent colour.
     *
     * <p>All three are shown at once rather than cycled through, because the choice changes the
     * route as well as the estimate, and seeing which one is on is the point of the row.
     */
    private void drawModeSelector(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(font, modeCaption(), rowX,
                modeY + (MODE_ROW_HEIGHT - 8) / 2, COLOR_SECONDARY, false);

        TravelMode active = Navigation.mode();
        int x = modeButtonsX;
        for (TravelMode mode : TravelMode.values()) {
            boolean selected = mode == active;
            drawButton(graphics, x, modeY, modeButtonWidth, MODE_ROW_HEIGHT, mode.label(),
                    selected ? COLOR_ROW_ACTIVE : COLOR_BUTTON,
                    COLOR_BUTTON_HOVER,
                    selected ? COLOR_ACCENT : COLOR_PRIMARY,
                    isInside(mouseX, mouseY, x, modeY, modeButtonWidth, MODE_ROW_HEIGHT));
            x += modeButtonWidth + gap;
        }
    }

    private static String modeCaption() {
        return Component.translatable("screen.howtogo.mode").getString();
    }

    /** True when two picked places are the same place, by name and position. */
    private static boolean samePlace(Destination a, Destination b) {
        return a != null && b != null && a.name().equals(b.name())
                && a.x() == b.x() && a.z() == b.z();
    }

    /**
     * What the preview says: the trip, or why there is no trip.
     *
     * <p>Laid out as two lines because the two cases say different kinds of thing. A trip is the
     * mode and its cost, and never changes shape. A failed combination is a sentence from the
     * router, which is the only thing that turns "there is no route" into something the player can
     * act on -- so it gets a line of its own to be read in, rather than being clipped to make room
     * for a distance and a time that do not exist. Showing nothing at all would look like the
     * picker had simply stopped working.
     */
    private void drawPreviewSummary(GuiGraphics graphics) {
        if (previewTarget == null) {
            // The band is reserved either way, so it says what to do with it rather than sitting
            // empty -- the preview is the whole reason the picker no longer commits on one click.
            graphics.drawString(font, Component.translatable("screen.howtogo.preview_hint")
                            .getString(),
                    rowX, previewY, COLOR_SECONDARY, false);
            return;
        }
        TravelMode active = Navigation.mode();
        // The state line reads the same whether or not the plan worked, so a failed combination
        // still says which one failed -- the reason below it is the router's own sentence.
        graphics.drawString(font, Component.translatable("screen.howtogo.preview_state",
                        active.label()).getString(),
                rowX, previewY, COLOR_SECONDARY, false);

        boolean present = preview != null && preview.isPresent();
        String text;
        if (present) {
            text = Component.translatable("screen.howtogo.preview_route",
                    Route.formatDistance(preview.route().totalLength()),
                    Route.formatDuration(preview.route().estimatedSeconds())).getString();
        } else {
            text = preview == null || preview.note() == null
                    ? Component.translatable("screen.howtogo.preview_none").getString()
                    : preview.note();
        }
        graphics.drawString(font, font.plainSubstrByWidth(text, rowW),
                rowX, previewY + PREVIEW_STATE_HEIGHT,
                present ? COLOR_ACCENT : COLOR_WARN, false);
    }

    /**
     * The metric, the major-road preference and the voice switch, as buttons that take effect on
     * the spot.
     *
     * <p>The first two change what "best" means, so both re-plan the preview from
     * {@code mouseClicked}: a toggle whose effect only appears after the screen is reopened is a
     * setting, not a control. Speech changes nothing about the route, so the voice switch has no
     * preview to re-plan -- it is here rather than on a settings page because a toggle the player
     * cannot find is a toggle that does not exist.
     */
    private void drawPreferenceControls(GuiGraphics graphics, int mouseX, int mouseY) {
        RoutePreferences preferences = RoutePreferenceStore.preferences();

        graphics.drawString(font, metricCaption(), rowX,
                preferenceY + (preferenceRowHeight - 8) / 2, COLOR_SECONDARY, false);

        control(graphics, mouseX, mouseY, preferenceButtonX(0), preferenceY, preferenceButtonWidth,
                preferenceRowHeight, preferences.metric().label(), true);
        control(graphics, mouseX, mouseY, preferenceButtonX(1), preferenceY, preferenceButtonWidth,
                preferenceRowHeight, majorRoadsCaption(), preferences.preferMajorRoads());
        control(graphics, mouseX, mouseY, preferenceButtonX(2), preferenceY, preferenceButtonWidth,
                preferenceRowHeight, voiceCaption(), RoutePreferenceStore.voiceAnnouncements());
    }

    /**
     * One toggle per road class, lighting up the classes kept out of the route.
     *
     * <p>All six are always shown rather than only the avoided ones, because the list is what the
     * player is choosing from: a row that grows as classes are added answers "what can I avoid"
     * with "what have I avoided", which is not the same question.
     */
    private void drawClassControls(GuiGraphics graphics, int mouseX, int mouseY) {
        RoutePreferences preferences = RoutePreferenceStore.preferences();

        graphics.drawString(font, avoidCaption(), rowX,
                classY + (classRowHeight - 8) / 2, COLOR_SECONDARY, false);

        RoadClass[] classes = RoadClass.values();
        for (int i = 0; i < classes.length; i++) {
            control(graphics, mouseX, mouseY, classButtonX(i), classY, classButtonWidth, classRowHeight,
                    classCaption(classes[i]), preferences.avoids(classes[i]));
        }
    }

    /** Localised name for one road class, for the avoid toggles. */
    private static String classCaption(RoadClass roadClass) {
        return Component.translatable(
                "screen.howtogo.road_class." + roadClass.name().toLowerCase(Locale.ROOT)).getString();
    }

    /**
     * The transit guidance switch: whether a public transport journey is guided by boarding and
     * alighting rather than by turns.
     *
     * <p>Its own row rather than a chip on the preference row, and its own caption rather than a
     * shorter label on one: the row is where a switch that belongs to one mode can say what it is,
     * and the caption beside it is what says which mode that is. Like the voice switch it changes
     * nothing about the route, so nothing is re-planned when it is pressed.
     */
    private void drawTransitControls(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(font, transitCaption(), rowX,
                transitY + (transitRowHeight - 8) / 2, COLOR_SECONDARY, false);
        int[] rect = transitRect();
        control(graphics, mouseX, mouseY, rect[0], rect[1], rect[2], transitRowHeight,
                boardOnlyCaption(), RoutePreferenceStore.transitBoardOnly());
    }

    /**
     * Draws one toggle in its on or off state.
     *
     * <p>A plain button and its state rather than a checkbox: the same hover feedback as every
     * other control in the panel, and no widget to keep in step with a value that lives outside it.
     * Where it was clicked is decided in {@code mouseClicked}, which uses the same coordinates.
     */
    private void control(GuiGraphics graphics, int mouseX, int mouseY, int x, int y, int w, int h,
                         String label, boolean on) {
        drawButton(graphics, x, y, w, h, font.plainSubstrByWidth(label, w - 4),
                on ? COLOR_ROW_ACTIVE : COLOR_BUTTON, COLOR_BUTTON_HOVER,
                on ? COLOR_ACCENT : COLOR_PRIMARY, isInside(mouseX, mouseY, x, y, w, h));
    }

    private static String metricCaption() {
        return Component.translatable("screen.howtogo.preference").getString();
    }

    private static String majorRoadsCaption() {
        return Component.translatable("screen.howtogo.prefer_major_roads").getString();
    }

    private static String avoidCaption() {
        return Component.translatable("screen.howtogo.avoid_road_classes").getString();
    }

    private static String voiceCaption() {
        return Component.translatable("screen.howtogo.voice").getString();
    }

    private static String transitCaption() {
        return Component.translatable("screen.howtogo.transit_guidance").getString();
    }

    private static String boardOnlyCaption() {
        return Component.translatable("screen.howtogo.transit_board_only").getString();
    }

    /** The rectangle of the transit guidance switch, as {@code {x, y, width}}. */
    private int[] transitRect() {
        int captionWidth = font.width(transitCaption()) + gap;
        return new int[]{rowX + captionWidth, transitY, Math.max(30, rowW - captionWidth)};
    }

    /**
     * The class toggle at the given index, as an x coordinate.
     *
     * <p>Derived from the same three numbers the drawing uses, so a click cannot land on a button
     * the player is not looking at.
     */
    private int classButtonX(int index) {
        return rowX + font.width(avoidCaption()) + gap
                + index * (classButtonWidth + gap);
    }

    /** The class toggle under the cursor, or null when the cursor is not on one. */
    private RoadClass classAt(double mouseX, double mouseY) {
        RoadClass[] classes = RoadClass.values();
        for (int i = 0; i < classes.length; i++) {
            if (isInside((int) mouseX, (int) mouseY, classButtonX(i), classY, classButtonWidth,
                    classRowHeight)) {
                return classes[i];
            }
        }
        return null;
    }

    /** True when the cursor is on the metric toggle. */
    private boolean metricAt(double mouseX, double mouseY) {
        return isInside((int) mouseX, (int) mouseY, preferenceButtonX(0), preferenceY,
                preferenceButtonWidth, preferenceRowHeight);
    }

    /** True when the cursor is on the major-road toggle. */
    private boolean majorRoadsAt(double mouseX, double mouseY) {
        return isInside((int) mouseX, (int) mouseY, preferenceButtonX(1), preferenceY,
                preferenceButtonWidth, preferenceRowHeight);
    }

    /** True when the cursor is on the voice switch. */
    private boolean voiceAt(double mouseX, double mouseY) {
        return isInside((int) mouseX, (int) mouseY, preferenceButtonX(2), preferenceY,
                preferenceButtonWidth, preferenceRowHeight);
    }

    /** True when the cursor is on the transit board-and-alight switch. */
    private boolean transitAt(double mouseX, double mouseY) {
        int[] rect = transitRect();
        return isInside((int) mouseX, (int) mouseY, rect[0], rect[1], rect[2], transitRowHeight);
    }

    /**
     * The metric, major-road or voice toggle at the given index, as an x coordinate.
     *
     * <p>All three are derived from the wider of the metric and major-road captions, which is also
     * how the width was computed, so the drawing and the hit tests cannot disagree about where they
     * are.
     */
    private int preferenceButtonX(int index) {
        return rowX + Math.max(font.width(metricCaption()),
                font.width(majorRoadsCaption())) + gap + index * (preferenceButtonWidth + gap);
    }

    /** The mode button under the cursor, or null when the cursor is not on one. */
    private TravelMode modeAt(double mouseX, double mouseY) {
        int x = modeButtonsX;
        for (TravelMode mode : TravelMode.values()) {
            if (isInside((int) mouseX, (int) mouseY, x, modeY, modeButtonWidth, MODE_ROW_HEIGHT)) {
                return mode;
            }
            x += modeButtonWidth + gap;
        }
        return null;
    }

    private void drawFooter(GuiGraphics graphics, int mouseX, int mouseY) {
        Destination action = actionDestination();
        boolean navigating = Navigation.target() != null;
        boolean twoButtons = action != null || navigating;

        int buttonWidth = twoButtons ? (rowW - PADDING) / 2 : rowW;
        int leftX = rowX;
        int rightX = twoButtons ? leftX + buttonWidth + PADDING : leftX;

        if (action != null) {
            // Offered whenever something is picked, whether or not it routes: a trip that cannot be
            // planned is still the one the player asked for, and the preview line above has already
            // said why it will not help.
            drawButton(graphics, leftX, footerY, buttonWidth,
                    Component.translatable("screen.howtogo.go_here").getString(),
                    COLOR_ACCENT, 0xFF52DBFF,
                    isInside(mouseX, mouseY, leftX, footerY, buttonWidth));
        } else if (navigating) {
            drawButton(graphics, leftX, footerY, buttonWidth,
                    Component.translatable("screen.howtogo.stop").getString(),
                    COLOR_DANGER, COLOR_DANGER_HOVER,
                    isInside(mouseX, mouseY, leftX, footerY, buttonWidth));
        }
        drawButton(graphics, rightX, footerY, buttonWidth,
                Component.translatable("gui.cancel").getString(),
                COLOR_BUTTON, COLOR_BUTTON_HOVER,
                isInside(mouseX, mouseY, rightX, footerY, buttonWidth));
    }

    /** Where the action button would commit to: a fresh pin first, then the previewed choice. */
    private Destination actionDestination() {
        if (pendingPick != null) {
            int x = (int) Math.round(pendingPick[0]);
            int z = (int) Math.round(pendingPick[1]);
            return new Destination("(" + x + ", " + z + ")", x, playerHeight(), z, "map");
        }
        return previewTarget;
    }

    private void drawButton(GuiGraphics graphics, int x, int y, int w,
                            String label, int base, int hover, boolean hovered) {
        drawButton(graphics, x, y, w, FOOTER_HEIGHT, label, base, hover, COLOR_PRIMARY, hovered);
    }

    private void drawButton(GuiGraphics graphics, int x, int y, int w, int h,
                            String label, int base, int hover, int textColor, boolean hovered) {
        HudDraw.fillRounded(graphics, x, y, x + w, y + h, 3, hovered ? hover : base);
        graphics.drawString(font, label, x + (w - font.width(label)) / 2,
                y + (h - 8) / 2, textColor, false);
    }

    private static boolean isInside(int mouseX, int mouseY, int x, int y, int w) {
        return isInside(mouseX, mouseY, x, y, w, FOOTER_HEIGHT);
    }

    private static boolean isInside(int mouseX, int mouseY, int x, int y, int w, int h) {
        return mouseX >= x && mouseX <= x + w && mouseY >= y && mouseY <= y + h;
    }

    // ------------------------------------------------------------------- input

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) {
            return super.mouseClicked(mouseX, mouseY, button);
        }
        if (insideMap(mouseX, mouseY)) {
            if (insideRecenter(mouseX, mouseY)) {
                recenterOnPlayer();
                return true;
            }
            // Remember the press only. The pin is dropped on release, so panning the map does not
            // leave a pin behind at the end of every drag.
            PlaceHit hit = placeAt(mouseX, mouseY);
            if (hit != null) {
                long now = System.currentTimeMillis();
                if (hit.destination().equals(lastPlaceClick)
                        && now - lastPlaceClickAt <= PLACE_DOUBLE_CLICK_MS) {
                    // The second click chooses it, which is what clicking its row in the list does.
                    // A single click is deliberately not swallowed: it still falls through to the
                    // pin below, so a marker is not a dead patch of map.
                    lastPlaceClick = null;
                    pendingPick = null;
                    selectPreview(hit.destination());
                    return true;
                }
                lastPlaceClick = hit.destination();
                lastPlaceClickAt = now;
            }
            mapPressed = true;
            mapPressX = mouseX;
            mapPressY = mouseY;
            return true;
        }
        if (hoveredRow >= 0 && hoveredRow < shown.size()) {
            // Selects rather than commits: the route for this row is planned and drawn straight
            // away, and the footer button starts it. Opening and closing the screen was the only
            // way to find out which mode was better before the preview existed.
            Destination chosen = shown.get(hoveredRow);
            pendingPick = null;
            selectPreview(chosen);
            return true;
        }

        TravelMode clickedMode = modeAt(mouseX, mouseY);
        if (clickedMode != null) {
            // Switches the mode and re-plans the preview in one go, so the map redraws with the
            // route the mode implies while the choice is still being made.
            Navigation.setMode(clickedMode);
            replanPreview();
            return true;
        }
        if (metricAt(mouseX, mouseY)) {
            RoutePreferenceStore.toggleMetric();
            replanPreview();
            return true;
        }
        if (majorRoadsAt(mouseX, mouseY)) {
            RoutePreferenceStore.togglePreferMajorRoads();
            replanPreview();
            return true;
        }
        if (voiceAt(mouseX, mouseY)) {
            // No re-plan: speech is not an input to a route, so the map has nothing to redraw.
            RoutePreferenceStore.toggleVoiceAnnouncements();
            return true;
        }
        if (transitAt(mouseX, mouseY)) {
            // No re-plan either: the guidance says different things about the same journey.
            RoutePreferenceStore.toggleTransitBoardOnly();
            return true;
        }
        RoadClass clickedClass = classAt(mouseX, mouseY);
        if (clickedClass != null) {
            RoutePreferenceStore.toggleAvoided(clickedClass);
            replanPreview();
            return true;
        }

        Destination action = actionDestination();
        boolean navigating = Navigation.target() != null;
        boolean twoButtons = action != null || navigating;
        int buttonWidth = twoButtons ? (rowW - PADDING) / 2 : rowW;
        int leftX = rowX;
        int rightX = twoButtons ? leftX + buttonWidth + PADDING : leftX;

        if (action != null && isInside((int) mouseX, (int) mouseY, leftX, footerY, buttonWidth)) {
            commit(action);
            return true;
        }
        if (action == null && navigating
                && isInside((int) mouseX, (int) mouseY, leftX, footerY, buttonWidth)) {
            Navigation.clear();
            onClose();
            return true;
        }
        if (isInside((int) mouseX, (int) mouseY, rightX, footerY, buttonWidth)) {
            onClose();
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /** Chooses a destination to preview, planning the route it implies. */
    private void selectPreview(Destination destination) {
        previewTarget = destination;
        replanPreview();
    }

    /**
     * Re-plans the preview for the current destination, mode and preferences.
     *
     * <p>Called from every control that changes one of the three, so what the map shows is always
     * the answer to the settings currently on screen. Everything this reads is the live session, and
     * {@link Navigation#preview} writes none of it, so comparing routes here cannot disturb a trip
     * that is already running.
     */
    private void replanPreview() {
        if (previewTarget == null) {
            preview = null;
            return;
        }
        preview = Navigation.preview(previewTarget, Navigation.mode(),
                RoutePreferenceStore.preferences());
    }

    /**
     * Starts navigating to the chosen destination.
     *
     * <p>Only the destination is handed over; {@link Navigation#setTarget} plans the route itself,
     * under the mode and policy in force. During the preview it is the selected mode that is shown
     * rather than the walking baseline, and the live plan is where that baseline applies -- which is
     * why the HUD can end up saying the trip was planned on foot when the preview showed a drive.
     */
    private void commit(Destination destination) {
        pendingPick = null;
        Navigation.setTarget(destination);
        onClose();
    }

    private static int playerHeight() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null ? (int) mc.player.getY() : 64;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (button == 0 && mapPressed) {
            // Move the centre opposite to the drag, so the map follows the cursor.
            double scale = mapW / (viewRadius * 2.0);
            mapCenterX -= dragX / scale;
            mapCenterZ -= dragY / scale;
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && mapPressed) {
            mapPressed = false;
            double moved = Math.hypot(mouseX - mapPressX, mouseY - mapPressY);
            if (moved < 3 && insideMap(mouseX, mouseY)) {
                double[] world = worldAt(mouseX, mouseY);
                if (world != null) {
                    // Two-step: the pin is placed here, the route to it is previewed, and the
                    // action button commits. A clicked point supersedes a list row, so the footer
                    // button always acts on the most recent choice.
                    pendingPick = world;
                    int x = (int) Math.round(world[0]);
                    int z = (int) Math.round(world[1]);
                    selectPreview(new Destination("(" + x + ", " + z + ")", x, playerHeight(), z,
                            "map"));
                }
            }
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    // 1.20.1's mouseScrolled carries the vertical wheel only; the horizontal component scrollX was
    // split out of it in a later version, so this override lost an argument.
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollY) {
        if (insideMap(mouseX, mouseY)) {
            // Zoom about the centre. Anchoring on the centre rather than the cursor is simpler and
            // predictable enough at this size.
            double factor = scrollY > 0 ? 0.85 : 1.0 / 0.85;
            viewRadius = Math.max(MIN_VIEW_RADIUS, Math.min(MAX_VIEW_RADIUS, viewRadius * factor));
            return true;
        }
        if (mouseX < listPaneX || mouseX > listPaneX + listPaneW
                || mouseY < listTop || mouseY > listBottom()) {
            // Somewhere else in the panel: consumed so a wheel turn over a control, or over the map
            // column, does not scroll a list that is not under the cursor. The list's own extent is
            // asked of the row geometry, which is the same thing the rows are drawn and hit-tested
            // against -- a stored height kept beside it is what broke scrolling before.
            return true;
        }
        int maxScroll = Math.max(0, shown.size() - visibleRows);
        if (maxScroll > 0) {
            scroll = Math.max(0, Math.min(maxScroll, scroll - (int) Math.signum(scrollY)));
        }
        return true;
    }

    /** Top-right corner of the map, where the "back to me" button sits. */
    private boolean insideRecenter(double mouseX, double mouseY) {
        return mouseX >= mapX + mapW - RECENTER_SIZE - 2 && mouseX <= mapX + mapW - 2
                && mouseY >= mapY + 2 && mouseY <= mapY + RECENTER_SIZE + 2;
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }
}
