package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.RoadConfig;
import bili.dongsz.howtogo.api.DestinationSources;
import bili.dongsz.howtogo.place.PlaceDetails;
import bili.dongsz.howtogo.place.PlaceSources;
import bili.dongsz.howtogo.road.RoadChains;
import bili.dongsz.howtogo.road.RoadClass;
import bili.dongsz.howtogo.road.RoadNetwork;
import bili.dongsz.howtogo.road.RoadSegment;
import bili.dongsz.howtogo.route.Destination;
import bili.dongsz.howtogo.route.LinePlanner;
import bili.dongsz.howtogo.route.Route;
import bili.dongsz.howtogo.route.RouteOption;
import bili.dongsz.howtogo.route.RoutePreferences;
import bili.dongsz.howtogo.route.RouteStrategy;
import bili.dongsz.howtogo.route.TravelMode;
import bili.dongsz.howtogo.store.RoutePreferenceStore;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
// 1.21.6 replaced the GUI's PoseStack with JOML's 2D matrix stack.
//? if >=1.21.6 {
/*import org.joml.Matrix3x2fStack;
*///?}
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
// 1.21.9 bundled mouse coordinates and the button into a record.
//? if >=1.21.9 {
/*import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
*///?}
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Destination picker: search, a pickable map, and the saved places.
 * Opened by the K screen's destination button and /howtogo terminal. The map is this mod's own road
 * data; picking a point is two-step -- a click drops a pin, the action button commits -- so a stray
 * click cannot start a trip. A row or marker chooses on one click and brings its details card, which
 * shares the list band with the rows.
 * Plans are a band under the readout, paid for by folding the four control rows into the bar.
 */
public final class DestinationScreen extends Screen {

    private static final int PANEL_WIDTH = 268;
    private static final int MAX_PANEL_WIDTH = 334;
    private static final int MARGIN = 8;
    private static final int MIN_MAP_PANE_WIDTH = 125;
    private static final int MIN_LIST_PANE_WIDTH = 140;
    private static final int MAP_PANE_SHARE = 44;
    private static final int COLUMN_GAP = 5;
    /**
     * Narrowest window that still gets two columns, found by asking fitsAsColumns at growing widths
     * so the advertised floor is the number the rule itself produces.
     */
    private static final int MIN_SIDE_BY_SIDE_WINDOW = smallestWindowForColumns();

    private static int smallestWindowForColumns() {
        for (int window = MIN_PANEL_WIDTH; window <= MAX_PANEL_WIDTH; window++) {
            if (fitsAsColumns(window)) {
                return window;
            }
        }
        return Integer.MAX_VALUE;
    }

    private static boolean fitsAsColumns(int window) {
        int content = Math.min(MAX_PANEL_WIDTH, Math.max(MIN_PANEL_WIDTH, window - MARGIN * 2))
                - PADDING * 2;
        return mapPaneWidth(content) >= MIN_MAP_PANE_WIDTH
                && listPaneWidth(content) >= MIN_LIST_PANE_WIDTH;
    }

    private static int mapPaneWidth(int content) {
        return (content - COLUMN_GAP) * MAP_PANE_SHARE / 100;
    }

    private static int listPaneWidth(int content) {
        return content - COLUMN_GAP - mapPaneWidth(content);
    }
    /**
     * Narrowest usable stacked width: the control rows put three abutting chips in a row and six in
     * the class row, so ~220 units of content plus padding is the floor. Below that the panel may
     * reach the screen edges rather than be clipped.
     */
    private static final int MIN_PANEL_WIDTH = 236;
    private static final int ROW_HEIGHT = 24;
    private static final int MIN_LIST_ROW = 24;
    private static final int DETAILS_BAND_SHARE = 55;
    private static final int MIN_DETAILS_HEIGHT = 84;
    private static final int MIN_DETAILS_COMPACT = 70;
    private static final int MIN_PANEL_HEIGHT = 232;
    private static final int MAP_BAND_SHARE = 42;
    private static final int MAX_VISIBLE_ROWS = 12;
    private static final int PADDING = 8;
    private static final int HEADER_HEIGHT = 16;
    private static final int SEARCH_HEIGHT = 16;
    private static final int GAP = 5;
    private static final int GAP_PACKED = 3;
    private static final int FOOTER_HEIGHT = 22;
    private static final int MODE_ROW_HEIGHT = 18;
    private static final int PREFERENCE_ROW_HEIGHT = 16;
    private static final int PREFERENCE_ROW_PACKED = 14;
    private static final int CLASS_ROW_HEIGHT = 16;
    private static final int CLASS_ROW_PACKED = 14;
    private static final int PREVIEW_STATE_HEIGHT = 9;
    private static final int PREVIEW_LINE_HEIGHT = 19;
    private static final int PLAN_ROW_HEIGHT = 22;
    private static final int PLAN_ROW_PACKED = 18;
    private static final int MAX_PLAN_ROWS = 3;
    private static final int CONTROLS_BAR_HEIGHT = 16;
    private static final int CONTROLS_BAR_PACKED = 14;
    private static final int CORNER_RADIUS = 5;
    private static final int MIN_MAP_HEIGHT = 42;

    private static final double DEFAULT_VIEW_RADIUS = 260.0;
    private static final double MIN_VIEW_RADIUS = 40.0;
    private static final double MAX_VIEW_RADIUS = 3000.0;

    private static final int RECENTER_SIZE = 11;

    /*
     * The controls' motion is a pool indexed by slot rather than by object: a chip is a rectangle the
     * drawing and the hit test rebuild from the layout every frame. Each row owns a block wider than any
     * row needs, and every access is doubled -- the cursor arriving and the control being switched on.
     */
    private static final int CHIP_MODE = 0;
    private static final int CHIP_PREFERENCE = 16;
    private static final int CHIP_CLASS = 32;
    private static final int CHIP_TRANSIT = 48;
    private static final int CHIP_BAR = 64;
    private static final int CHIP_FOOTER = 80;

    private final Screen parent;

    private float open = 1.0F;

    private float surface = 1.0F;

    /**
     * How far the four control rows are open this frame, from 0 folded to 1 open.
     * A slide rather than a boolean because folding is a layout change; its target is re-read from
     * controlsExpanded every frame.
     */
    private float fold = 1.0F;

    private final UiMotion.Slide controlsFold =
            new UiMotion.Slide(UiMotion.Easing.IN_OUT_CUBIC, 1.0F, UiMotion.EXPAND);

    private final UiMotion.Slides rowSlides = new UiMotion.Slides();

    private final UiMotion.Slides planSlides = new UiMotion.Slides();

    private final UiMotion.Slides chipSlides = new UiMotion.Slides();

    private int chipSlot;

    private float rowArrival = 1.0F;

    private List<Destination> all = List.of();
    private List<Destination> shown = List.of();
    private int scroll;
    private int hoveredRow = -1;

    private EditBox searchField;
    private String lastQuery = "";

    private double[] pendingPick;

    private static final int PLACE_HIT_PX = 5;

    private static final int LABEL_HEIGHT = 9;

    private static final int SIGN_CARD_PAD_X = RoadSigns.PAD_X;
    private static final int SIGN_CARD_PAD_Y = RoadSigns.PAD_Y;
    private static final int SIGN_CARD_LINE_GAP = RoadSigns.LINE_GAP;
    /**
     * How much of the font a sign card is drawn at on this map, as RoadSigns#FIXED_CARD_SCALE.
     * A full-size card would be one of the loudest things on a map this size, and the whole card
     * scales, not just its text; the border stays one screen pixel, a rim rather than a share.
     */
    private static final float SIGN_CARD_SCALE = RoadSigns.FIXED_CARD_SCALE;
    private static final double SIGN_CARD_NAME_FLOOR = 0.7;

    private static final double LABEL_CLEARANCE_PX = RoadSigns.LABEL_CLEARANCE_PX;

    /**
     * The least zoom, in screen pixels per block, at which this map draws a sign card. Below it a
     * card would be plates over strokes, so it gives way to the road's name; shared with the main
     * screen's map, which has the same problem and answer.
     */
    private static final double LABEL_MIN_SCALE = RoadSigns.LABEL_MIN_SCALE;

    /**
     * The widest view, in blocks across, at which this map writes road names at all.
     * In blocks because the two maps are very different sizes: 900 blocks across is a journey seen end
     * to end, and past it names are a wall of words over the plan. The default view is 520.
     */
    private static final double NAME_MAX_VIEW_BLOCKS = 900.0;

    private static final double ROAD_NAME_END_MARGIN = 4.0;

    /**
     * The widest view, in blocks from the map's centre to its edge, at which this map names a line.
     * 100 blocks of radius: past this the visible area holds several 160-block candidates and the map
     * becomes a band of capsules. Written as a radius because that is what the zoom control moves.
     */
    private static final double LINE_NAME_MAX_VIEW_RADIUS = 100.0;

    private static final double LINE_NAME_RISE_PX = 7.0;

    private static final int MAX_LINE_NAMES = 2;

    private static final int ROW_MARKER = 6;
    private static final int ROW_MARKER_STEP = 9;

    private Destination previewTarget;
    private Navigation.RoutePreview preview;

    /**
     * The plans the picker is offering, quickest first, or an empty list, in band order: planIndex
     * indexes this list and the map, readout and footer all read the same index, so the line picked
     * out and the line started cannot disagree.
     */
    private List<RouteOption> plans = List.of();
    private int planIndex;
    private int hoveredPlan = -1;

    /**
     * Whether the four control rows are open, or folded under the one-line bar. Open whenever there
     * is no destination to compare plans for; choosing one folds them, and the bar opens them again.
     */
    private boolean controlsExpanded = true;

    private int planRowSize;
    private int planTop;
    private int controlsY;
    private int summaryRowHeight;
    private int barY;
    private int stackTop;
    private int stackBottom;
    /**
     * The fold value the current geometry was measured from. Two is a sentinel no eased slide
     * produces, so the first frame always measures.
     */
    private float laidOutFold = 2.0F;

    /**
     * The plan band's height this frame, in pixels, followed rather than set: the plan count can change
     * while the fold does not, and setting it outright would jump the band the map and list are measured
     * against.
     */
    private float plansBlockShown;

    private static final float PLAN_BLOCK_FOLLOW_SECONDS = 0.09F;

    /**
     * The details card, and the place it is about, or null while the list has the whole band. Not a
     * widget: it draws and reports clicks, and the store is asked every frame.
     */
    private final PlaceDetailPanel detailPanel = new PlaceDetailPanel();
    private Destination detailsPlace;

    private int detailsHeight;
    private int detailsY;

    private int listBandHeight;

    /**
     * The places the current search matched through their description or tags; a row in this set
     * shows the summary that explains the hit.
     */
    private final Set<Destination> descriptionMatches = new HashSet<>();

    private double mapCenterX;
    private double mapCenterZ;
    private double viewRadius = DEFAULT_VIEW_RADIUS;

    /*
     * The camera is steered rather than set: during a drag the shown value follows the cursor and
     * the target keeps the momentum; everything else is aimed, and the shown value travels to the
     * target over CAMERA_FOLLOW_SECONDS.
     */
    private double targetCenterX;
    private double targetCenterZ;
    private double targetViewRadius = DEFAULT_VIEW_RADIUS;
    private double momentumX;
    private double momentumZ;
    private long lastDragNanos;
    private static final float CAMERA_FOLLOW_SECONDS = 0.075F;
    private static final float CAMERA_INERTIA_SECONDS = 0.16F;
    private static final double CAMERA_RADIUS_SNAP = 0.001;

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
    private int transitY;
    private int footerY;
    private int previewY;
    private int panelW;
    private int mapPaneW;
    private int listPaneW;
    private int listPaneX;
    private boolean columnsSideBySide;
    private int rowX;
    private int rowW;
    private int gap;
    private int preferenceRowHeight;
    private int classRowHeight;
    private int transitRowHeight;
    private int visibleRows;
    private int rowHeight;
    private int rowRemainder;
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
     * Keep the world running behind the picker: a destination is often chosen while travelling, so
     * only the keyboard and mouse are captured.
     */
    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        all = Destinations.all();
        dropVanishedSelection();
        scroll = 0;
        if (previewTarget == null) {
            controlsExpanded = true;
        }
        replanPlans();

        computePanelLayout();
        plansBlockShown = planBlockTarget();
        computeVerticalLayout(columnsSideBySide, fold);
        placeBands();
        measureControls();
        laidOutFold = fold;

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
        // A screen being opened is not a movement: the first frame shows the view where it is aimed.
        snapCamera();
        applyFilter();
    }

    /**
     * Places every band from the two anchors the panel has, once the vertical metrics are known.
     * The top anchor fixes the header, search box and column tops; the footer's bottom edge is the panel
     * bottom, so the bar, the shared block and the readout are placed from the bottom and the columns
     * get what is left -- which makes the fold a change of height.
     */
    private void placeBands() {
        mapX = panelX + PADDING;
        searchY = panelY + PADDING + HEADER_HEIGHT;
        mapY = searchY + SEARCH_HEIGHT + gap;
        listTop = columnsSideBySide ? mapY : mapY + mapH + gap;
        detailsY = listTop + listBandHeight - detailsHeight;
        previewY = mapY + bandHeight;

        footerY = panelY + panelHeight - PADDING - FOOTER_HEIGHT;
        barY = footerY - gap - summaryRowHeight;
        stackBottom = barY - gap;
        int plansBlock = plansBlockHeight();
        int stack = Math.round(fold * controlsBlockHeight()
                + (1.0F - fold) * plansBlock);
        stackTop = stackBottom - stack;

        // The four rows, in the place the open layout gives them. Their bottom is the bottom of the
        // shared block when the controls are open, and the fold only takes height off the top.
        controlsY = stackBottom - controlsBlockHeight();
        modeY = controlsY;
        preferenceY = modeY + MODE_ROW_HEIGHT + gap;
        classY = preferenceY + preferenceRowHeight + gap;
        // The transit guidance switch has a row of its own rather than a fourth chip beside the
        // other three: at the panel's narrowest the three already only just fit, and a fourth
        // would cut every label on the row down to two or three letters. One row of its own
        // reads in both languages and leaves room for the next switch that belongs to one mode
        // rather than to every plan.
        transitY = classY + classRowHeight + gap;
        // The plans are bottom-aligned in the same block, so they sit directly above the bar when
        // the controls are folded and cannot move as the block above them grows.
        planTop = stackBottom - plansBlock;
    }

    /**
     * Measures the four control rows' buttons from the panel's width and the gap in force: a caption,
     * then equal buttons filling the rest. Called on every relayout, because a short window packs a
     * different gap and a row measured against the other one overflows the panel.
     */
    private void measureControls() {
        int captionWidth = font.width(modeCaption()) + gap;
        modeButtonWidth = Math.max(36, (rowW - captionWidth - gap * 2) / 3);
        modeButtonsX = rowX + captionWidth;

        int preferenceCaption = Math.max(font.width(metricCaption()), font.width(majorRoadsCaption()))
                + gap;
        // Three switches share this row now: the metric, the major-road preference and the voice
        // toggle. The rule is (row - caption - (n - 1) gaps) / n, the same one the class row uses
        // with n = 6; keeping the two-control divisor here pushed the voice chip past the panel's
        // right edge, which is the same mistake that once put the ice chip off the side.
        preferenceButtonWidth = Math.max(30, (rowW - preferenceCaption - gap * 2) / 3);
        // Six chips need six gaps, not two: one after the caption and five between them. Dividing by
        // six while reserving only two gaps pushed the last chip past the panel's right edge, which
        // is what put the ice toggle off the side of the screen.
        classButtonWidth = Math.max(24, (rowW - font.width(avoidCaption()) - gap * 6) / 6);
    }

    /**
     * Re-measures and re-places the whole panel after the content changed under a fixed window: only
     * the vertical layout can move, so the search box stays where init built it.
     */
    private void relayout() {
        computePanelLayout();
        placeBands();
        measureControls();
        laidOutFold = fold;
        // The bands have moved, so the hover results the last frame recorded no longer describe
        // anything. They are clear until the next draw sets them again, which stops a click in the
        // frame the layout changed from landing on whatever used to be under the cursor.
        hoveredRow = -1;
        hoveredPlan = -1;
        // The list band may have grown or shrunk, so the wheel's clamp is re-applied from the same
        // row metrics the drawing will use.
        int maxScroll = Math.max(0, shown.size() - visibleRows);
        scroll = Math.max(0, Math.min(scroll, maxScroll));
    }

    /**
     * Re-measures the vertical layout for a fold or plan-band value that has moved since last frame.
     * Called from render, once per frame that either moved; with ui_animations off both land on their
     * targets in the click's frame. No hover reset (the drawing recomputes it); the wheel's clamp is
     * re-applied because the list band may have changed.
     */
    private void relayoutForFold(float openness) {
        computeVerticalLayout(columnsSideBySide, openness);
        placeBands();
        laidOutFold = openness;
        int maxScroll = Math.max(0, shown.size() - visibleRows);
        scroll = Math.max(0, Math.min(scroll, maxScroll));
    }

    /**
     * Opens or folds the four control rows at the player's request and re-places the bands: one
     * entry point because the bar is both directions.
     */
    private void setControlsExpanded(boolean expanded) {
        if (controlsExpanded == expanded) {
            return;
        }
        controlsExpanded = expanded;
        relayout();
    }

    /**
     * Re-plans after a control that changes the plans was used, and folds the controls so the answer
     * is visible: the row just used would otherwise disappear under the plans.
     */
    private void replanAndShowPlans() {
        controlsExpanded = false;
        replanPlans();
        relayout();
    }

    /**
     * Chooses the arrangement and measures both columns from the same three numbers.
     * Side by side needs MIN_SIDE_BY_SIDE_WINDOW units; below that the original stack of map over list
     * is used. The threshold asks mapPaneWidth and listPaneWidth themselves, so the advertised floor
     * and the drawn columns cannot drift apart.
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
        computeVerticalLayout(sideBySide, fold);
        panelY = Math.max(4, (height - panelHeight) / 2);
    }

    /**
     * Sizes and stacks the panel's vertical bands.
     * Not a run of constants because the GUI is often only ~240 units tall: fixed at the top (padding,
     * header, search), anchored at the bottom (readout, shared block, bar, footer as one group), and
     * the columns stretch between. The bottom group is measured from the frame's fold value, so the
     * columns grow exactly as fast as it shrinks.
     * @param sideBySide whether the map and the list share a band or are stacked
     * @param openness how far the four control rows are open, 0 folded to 1 open
     */
    private void computeVerticalLayout(boolean sideBySide, float openness) {
        gap = GAP;
        preferenceRowHeight = PREFERENCE_ROW_HEIGHT;
        classRowHeight = CLASS_ROW_HEIGHT;
        transitRowHeight = PREFERENCE_ROW_HEIGHT;
        summaryRowHeight = CONTROLS_BAR_HEIGHT;
        planRowSize = PLAN_ROW_HEIGHT;
        int panel = clamp(height - MARGIN * 2, MIN_PANEL_HEIGHT, Math.max(MIN_PANEL_HEIGHT, height));
        // The bottom group is measured first and then anchored from the panel's bottom; the columns get
        // what is left above it. The short-window test uses the taller fold end and the plan count, so an
        // animation cannot repack the panel under it.
        int band = panel - (headerHeight()
                + bottomGroupFor(Math.max(controlsBlockHeight(), planBlockTarget())) + gap);
        if (band < MIN_MAP_HEIGHT + MIN_LIST_ROW) {
            // A window too short for comfortable rows: tighten every gap, the control rows, the bar
            // and the plan rows, then measure the band again from the same expression rather than
            // from a second guess at it.
            gap = GAP_PACKED;
            preferenceRowHeight = PREFERENCE_ROW_PACKED;
            classRowHeight = CLASS_ROW_PACKED;
            transitRowHeight = PREFERENCE_ROW_PACKED;
            summaryRowHeight = CONTROLS_BAR_PACKED;
            planRowSize = PLAN_ROW_PACKED;
        }
        // The band for the fold in hand: the taller the bottom group, the shorter the columns.
        band = panel - (headerHeight() + bottomGroupHeight(openness) + gap);

        // Side by side both columns fill the band; stacked, the band is split -- the list keeps a share
        // floored at one row and the map takes the rest, so they cannot overlap.
        int listBand;
        int mapHeight;
        if (sideBySide) {
            listBand = band;
            mapHeight = band;
        } else {
            int room = Math.max(0, band - gap);
            if (room >= MIN_MAP_HEIGHT + MIN_LIST_ROW) {
                int share = room * (100 - MAP_BAND_SHARE) / 100;
                listBand = Math.max(MIN_LIST_ROW, Math.min(room - MIN_MAP_HEIGHT, share));
                mapHeight = room - listBand;
            } else {
                // Too short for both minimums: the list keeps its row and the map takes the
                // remainder, small but honest -- a shrunken map is visible, an overlapping readout
                // is a bug.
                listBand = Math.min(room, MIN_LIST_ROW);
                mapHeight = Math.max(0, room - listBand);
            }
        }
        listBandHeight = listBand;
        // The rows and the details card are measured from the same band by the same method, so the
        // toggle path can re-run it when a place is selected without recomputing the whole layout.
        applyListBand(listBand);
        // The map runs from the top of the band down to the top of the list, so the two always meet
        // exactly with only the column gap between them. Nothing else caps the map: the pane ends up
        // taller than it is wide, which is what stretching it to its band means. MAP_BAND_SHARE
        // decides the width split and the stacked height split, not a limit on either.
        mapH = mapHeight;
        // The band's height is kept because the placement needs its bottom to start the readout --
        // the line both columns run down to, whichever of them happens to be the taller one.
        bandHeight = band;

        panelHeight = panel;
    }

    /**
     * The height of everything below the columns: readout, the shared block, the bar, the footer.
     * The one expression the vertical layout derives from, so placement cannot measure a different
     * group than the band was taken out of. The shared block is the controls' and plans' heights
     * interpolated by openness, which is the figure the map and the list breathe with.
     * @param openness how far the four control rows are open, 0 folded to 1 open
     */
    private int bottomGroupHeight(float openness) {
        float open = UiMotion.clamp01(openness);
        int block = Math.round(open * controlsBlockHeight()
                + (1.0F - open) * plansBlockHeight());
        return bottomGroupFor(block);
    }

    private int bottomGroupFor(int block) {
        return PREVIEW_LINE_HEIGHT + PADDING + summaryRowHeight + FOOTER_HEIGHT
                + block + gap * 3;
    }

    private int controlsBlockHeight() {
        return MODE_ROW_HEIGHT + preferenceRowHeight + classRowHeight + transitRowHeight + gap * 3;
    }

    /**
     * Height of the plan band in the current layout: one row per plan, zero when there are none, read
     * from plansBlockShown so the drawing, click tests and band above agree.
     */
    private int plansBlockHeight() {
        return Math.round(plansBlockShown);
    }

    /**
     * The height the plan band is heading for: one row per plan, zero when there are none.
     * The target, not the current value, so the short-window test is a property of the window and the
     * plan count rather than of where the follow has got to.
     */
    private int planBlockTarget() {
        if (plans.isEmpty()) {
            return 0;
        }
        return Math.min(plans.size(), MAX_PLAN_ROWS) * planRowSize;
    }

    /**
     * Advances the plan band one frame towards its target height and reports whether it moved: every
     * frame it moves has to be re-measured. With ui_animations off it lands in one call.
     */
    private boolean settlePlansBlock() {
        float before = plansBlockShown;
        plansBlockShown = UiMotion.follow(plansBlockShown, planBlockTarget(),
                PLAN_BLOCK_FOLLOW_SECONDS);
        return plansBlockShown != before;
    }

    /**
     * Splits one list band between the rows and the details card, and applies the row metrics.
     * One function for both re-measure moments so the wheel's row count and the drawing's rows cannot
     * drift. The card takes a proportion, raised to MIN_DETAILS_HEIGHT or given the whole band when no
     * row fits.
     * @param band the height of the whole list band, before the card takes its share
     */
    private void applyListBand(int band) {
        int room = Math.max(0, band);
        detailsHeight = 0;
        if (detailsPlace != null) {
            int share = room * DETAILS_BAND_SHARE / 100;
            int card = Math.max(share, MIN_DETAILS_HEIGHT);
            if (card > room - MIN_LIST_ROW) {
                card = room - MIN_LIST_ROW;
            }
            if (card < MIN_DETAILS_COMPACT) {
                // Too short for both a card and a row: the card keeps the band and the list waits
                // behind its collapse control, which the card always offers.
                card = room;
            }
            detailsHeight = Math.max(0, card);
            room -= detailsHeight;
        }
        int[] metrics = listRowMetrics(room);
        visibleRows = metrics[0];
        rowHeight = metrics[1];
        // What the row height division left over. It is spread one unit at a time over the first rows
        // rather than dropped, so the rows between them end exactly where the card begins.
        rowRemainder = Math.max(0, room - visibleRows * rowHeight);
        if (room < MIN_LIST_ROW) {
            // No row fits: drawing one at a fraction of its height would put its text over whatever
            // is above the band. The card above covers the band in that case, so nothing is lost.
            visibleRows = 0;
            rowHeight = ROW_HEIGHT;
            rowRemainder = 0;
        }
    }

    private void relayoutList() {
        applyListBand(listBandHeight);
        detailsY = listTop + listBandHeight - detailsHeight;
        int maxScroll = Math.max(0, shown.size() - visibleRows);
        scroll = Math.max(0, Math.min(scroll, maxScroll));
    }

    private static int headerHeight() {
        return PADDING + HEADER_HEIGHT + SEARCH_HEIGHT;
    }

    /**
     * How many whole rows the list band holds and how tall each should be, chosen together: as many
     * as fit at the standard height, then the height that makes them end at the band's bottom.
     * @return {rows, rowHeight}
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
     * The note is a decoration derived from the source id, never baked into the name -- the route
     * instructions, announcements, HUD header and search all read that name. Its width is reserved
     * unconditionally and the name is cut to what is left; a column too narrow for the note drops it
     * whole. Selection changes the background, not the colour.
     */
    private void drawNameLine(GuiGraphics graphics, Destination destination, int x, int y, int width) {
        String name = destination.name();
        String note = sourceNote(destination.source());
        int noteWidth = note.isEmpty() ? 0 : font.width(note);
        boolean noteFits = noteWidth > 0 && noteWidth <= width;

        int nameRoom = noteFits ? width - noteWidth : width;
        String shownName = font.plainSubstrByWidth(name, nameRoom);
        // Both lines are faded by how far the row has arrived, so the name and its note come in
        // together with the row they belong to rather than a frame ahead of it.
        graphics.drawString(font, shownName, x, y,
                UiMotion.fade(readableColor(destination), rowArrival), false);

        if (noteFits) {
            // A shade lighter than the row's other text: the note shares the name's line, beside a
            // name that many sources colour, so it has to read as an aside without competing with it.
            graphics.drawString(font, note, x + font.width(shownName), y,
                    UiMotion.fade(UiTheme.sourceNote(), rowArrival), false);
        }
    }

    /**
     * The colour to draw a name in: the source's own when it reads on this panel.
     * Drawn faithfully, the end of the palette nearest the panel is invisible, so a colour too close
     * falls back to the text colour; the threshold is per theme, because one number would fix black
     * and break white.
     */
    private static int readableColor(Destination destination) {
        if (!destination.hasColor()) {
            return UiTheme.textStrong();
        }
        int color = destination.color();
        int luminance = (299 * ((color >> 16) & 0xFF)
                + 587 * ((color >> 8) & 0xFF)
                + 114 * (color & 0xFF)) / 1000;
        boolean tooClose = UiTheme.light() ? luminance > 190 : luminance < 40;
        return tooClose ? UiTheme.textStrong() : color;
    }

    /**
     * The short note a source wants beside its places, or an empty string.
     * Keyed on the source id by convention (xaero_waypoint gives hud.howtogo.source.xaero.short), so a
     * new source brings its note by shipping that key; a registered source may name its own key
     * through DestinationSource#noteKey().
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
     * The lang-key middle of a source id: the part before the first underscore, since
     * xaero_waypoint and the mod's own label share it.
     */
    private static String noteSuffix(String source) {
        int underscore = source.indexOf('_');
        return underscore < 0 ? source : source.substring(0, underscore);
    }

    /**
     * Top of the given visible row, in screen units. The slot is index - scroll, the same offset the
     * drawing and the hit test use; each row also picks up one unit of the remainder.
     */
    private int rowTop(int index) {
        int slot = index - scroll;
        return listTop + slot * rowHeight + Math.min(slot, rowRemainder);
    }

    private int listBottom() {
        return rowTop(scroll + visibleRows);
    }

    /**
     * Height of the list pane, asked of listBottom() rather than kept separately: a height field went
     * stale once the rows became adaptive, and the wheel stopped scrolling.
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

            // Two values per row, not one: how far the row has arrived as part of the list's
            // entrance, and how far the cursor has moved onto it. They multiply below, because a row
            // the cursor is already on when the screen opens still has to arrive before it can be
            // tinted. Both slides live in one pool keyed by the row's position in the list, which is
            // what a highlight belongs to: it marks the row under the cursor, not the place.
            float arrived = UiMotion.stagger(i, open);
            float lit = rowSlides.get(i * 2).to(hovered ? 1.0F : 0.0F).eased();
            float picked = rowSlides.get(i * 2 + 1)
                    .to(isActive || isPreview ? 1.0F : 0.0F).eased();

            if (hovered) {
                hoveredRow = i;
            }
            // The row's two states are the two overlays it has always had, chosen between by a mix
            // rather than by an if: a row that gains the cursor travels from the picked-out tint to
            // the cursor tint instead of swapping one for the other, and a row that stops being
            // picked out fades rather than blinking. The strength drawn is the larger of the two,
            // which is the precedence the hover test had when it was an else-if.
            float shownTint = Math.max(lit, picked);
            if (shownTint * arrived > 0.004F) {
                graphics.fill(listPaneX, rowY, listPaneX + listPaneW, rowBottom - 1,
                        UiMotion.fade(UiMotion.mix(UiTheme.rowActiveTint(),
                                UiTheme.rowHoverTint(), lit), shownTint * arrived));
            }

            int textX = listPaneX + 6 + ROW_MARKER_STEP;
            // The distance column sits at the far edge of the list, so it needs its width before
            // the name is measured against what is left; the reserve covers it and the scroll bar.
            String distance = distanceLabel(destination);
            int distanceWidth = font.width(distance);
            int nameWidth = Math.max(24, listPaneW - 12 - distanceWidth - 6 - ROW_MARKER_STEP);
            // Every row says where its place came from, as a pill at the far right of its second
            // line. Measured before that line is laid out, because the summary sharing it has to
            // stop where the pill starts -- the same rule the name follows for the distance.
            String badge = Destinations.sourceShortLabel(destination.source());
            int badgeWidth = badgeWidth(badge);
            int secondWidth = Math.max(24, listPaneW - 12 - badgeWidth - 6 - ROW_MARKER_STEP);
            // Two lines inside a row whose height is the band's share: the pair is centred in the row
            // rather than pinned to its top, so stretched rows do not leave their text stranded.
            int nameY = rowY + (rowBottom - rowY - 17) / 2;
            int secondY = nameY + 10;
            // The marker is the place's own colour where its source gives one -- a Xaero waypoint's
            // colour, say -- and the mod's place colour where it does not. Drawn after the row's
            // background so a hovered or active row does not paint over it, and it is the same mark
            // the maps draw for a place, sized down to a list.
            HudDraw.drawRowMarker(graphics, listPaneX + 3, nameY + 2, ROW_MARKER,
                    UiMotion.fade(destination.hasColor() ? destination.color() : UiTheme.place(),
                            arrived));
            int badgeX = listPaneX + listPaneW - 6 - badgeWidth;
            if (badgeWidth > 0) {
                // Shapes before strings: a string flushes the batch the fills written after it would
                // have joined, so the pill's face is painted with the row's other fills.
                HudDraw.fillRounded(graphics, badgeX, secondY - 1, badgeX + badgeWidth,
                        secondY + 9, 2, UiMotion.fade(UiTheme.rowMarkedTint(), arrived));
            }
            // No selection flag: the name keeps the source's colour whether or not the row is
            // picked out, and the row's background is what marks it. The name line is told how far
            // the row has arrived rather than handed colours to fade, because the colours it picks
            // are the place's own and only it knows them.
            rowArrival = arrived;
            drawNameLine(graphics, destination, textX, nameY, nameWidth);

            // The second line is the coordinates normally, and the player's own summary when the
            // search is what found the row: the row is on screen because of what was written about
            // it, so that is the line that should say so.
            graphics.drawString(font, secondLine(destination, secondWidth), textX, secondY,
                    UiMotion.fade(UiTheme.textMuted(), arrived), false);

            graphics.drawString(font, distance,
                    listPaneX + listPaneW - 6 - distanceWidth, nameY,
                    UiMotion.fade(UiTheme.textMuted(), arrived), false);

            if (badgeWidth > 0) {
                String shownBadge = PlaceText.ellipsis(font, badge, Math.max(1, badgeWidth - 4));
                graphics.drawString(font, shownBadge,
                        badgeX + Math.max(2, (badgeWidth - font.width(shownBadge)) / 2),
                        secondY, UiMotion.fade(UiTheme.textSecondary(), arrived), false);
            }
        }

        if (shown.size() > visibleRows && listHeight() > 0) {
            int trackX = listPaneX + listPaneW - 2;
            int bottom = listBottom();
            int maxScroll = shown.size() - visibleRows;
            int thumbHeight = Math.max(12, (bottom - listTop) * visibleRows / shown.size());
            int thumbY = listTop + (int) ((bottom - listTop - thumbHeight)
                    * ((double) scroll / maxScroll));
            graphics.fill(trackX, listTop, trackX + 2, bottom,
                    UiMotion.fade(UiTheme.scrollTrack(), open));
            graphics.fill(trackX, thumbY, trackX + 2, thumbY + thumbHeight,
                    UiMotion.fade(UiTheme.scrollThumb(), open));
        }
    }

    /**
     * The width of a row's source badge, or zero when the source has no short name. Capped at a
     * third of the list column.
     */
    private int badgeWidth(String badge) {
        if (badge == null || badge.isEmpty()) {
            return 0;
        }
        return Math.min(font.width(badge) + 6, Math.max(20, listPaneW / 3));
    }

    /**
     * The second line of a list row: the description that matched a search, or the coordinates
     * every other row keeps.
     */
    private String secondLine(Destination destination, int width) {
        if (descriptionMatches.contains(destination)) {
            String summary = PlaceText.summary(font,
                    PlaceDetailStore.detailsOf(destination).description(), width);
            if (!summary.isEmpty()) {
                return summary;
            }
        }
        return PlaceText.ellipsis(font, destination.coordinates(), width);
    }

    private int footerHeight() {
        return PREVIEW_LINE_HEIGHT + MODE_ROW_HEIGHT + preferenceRowHeight + classRowHeight
                + transitRowHeight + FOOTER_HEIGHT + PADDING;
    }

    /**
     * Puts the player back in the middle of the picker map at the default zoom, without a cut: the
     * view travels there, unlike init, which snaps.
     */
    private void recenterOnPlayer() {
        Minecraft mc = Minecraft.getInstance();
        double x = mapCenterX;
        double z = mapCenterZ;
        if (mc.player != null) {
            x = mc.player.getX();
            z = mc.player.getZ();
        }
        targetCenterX = x;
        targetCenterZ = z;
        targetViewRadius = DEFAULT_VIEW_RADIUS;
        momentumX = 0;
        momentumZ = 0;
    }

    private void snapCamera() {
        mapCenterX = targetCenterX;
        mapCenterZ = targetCenterZ;
        viewRadius = targetViewRadius;
        momentumX = 0;
        momentumZ = 0;
        lastDragNanos = 0;
    }

    /**
     * One frame of camera motion: the coast after a drag, then the shown value's travel to the aim.
     * Called at the top of render and the only place the shown camera is written; the map, labels,
     * markers, preview and hit tests all read the same three numbers. With ui_animations off both
     * halves collapse into one frame.
     */
    private void stepCamera() {
        if (!UiMotion.on()) {
            snapCamera();
            return;
        }
        float dt = UiMotion.frameDelta();
        if (!mapPressed && (momentumX != 0 || momentumZ != 0)) {
            targetCenterX += momentumX * dt;
            targetCenterZ += momentumZ * dt;
            double decay = Math.exp(-dt / CAMERA_INERTIA_SECONDS);
            momentumX *= decay;
            momentumZ *= decay;
            // Under a tenth of a block a second is invisible, and letting it run would keep the map
            // creeping for seconds after the throw looked finished.
            if (Math.abs(momentumX) + Math.abs(momentumZ) < 0.1) {
                momentumX = 0;
                momentumZ = 0;
            }
        }
        mapCenterX = UiMotion.chase(mapCenterX, targetCenterX, CAMERA_FOLLOW_SECONDS, 0.01);
        mapCenterZ = UiMotion.chase(mapCenterZ, targetCenterZ, CAMERA_FOLLOW_SECONDS, 0.01);
        // Zoom is chased on the radius's logarithm: a radius is a span, so the same step means a
        // different visual change at 40 and at 3000 blocks, and interpolating the number itself
        // would make the far zoom crawl and the near zoom jump.
        double logRadius = Math.log(viewRadius);
        double logTarget = Math.log(targetViewRadius);
        double logNext = UiMotion.chase(logRadius, logTarget, CAMERA_FOLLOW_SECONDS,
                CAMERA_RADIUS_SNAP);
        viewRadius = Math.exp(logNext);
    }

    /**
     * Aims the view at the plans' own points, with room to read them: the first selection is usually
     * at a zoom too wide for one trip, so the aim comes from the lines the map is about to draw.
     */
    private void aimAtPlans() {
        double minX = Double.MAX_VALUE;
        double minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxZ = -Double.MAX_VALUE;
        boolean any = false;
        for (RouteOption plan : plans) {
            Route route = plan.route();
            if (route == null || !route.isPresent()) {
                continue;
            }
            for (double[] point : route.points()) {
                any = true;
                minX = Math.min(minX, point[0]);
                maxX = Math.max(maxX, point[0]);
                minZ = Math.min(minZ, point[1]);
                maxZ = Math.max(maxZ, point[1]);
            }
        }
        Minecraft mc = Minecraft.getInstance();
        if (!any) {
            return;
        }
        if (mc.player != null) {
            // The player is where every plan starts, even when the route's own first point is the
            // road it was snapped to: the view that leaves them off screen would be a route they
            // cannot see themselves start.
            minX = Math.min(minX, mc.player.getX());
            maxX = Math.max(maxX, mc.player.getX());
            minZ = Math.min(minZ, mc.player.getZ());
            maxZ = Math.max(maxZ, mc.player.getZ());
        }
        // Room to read: a route drawn against the very edge of the map reads as clipped. The margin
        // is a share of the span rather than a number of blocks, so a short trip is not given the
        // same fix as a long one.
        double spanX = Math.max(1.0, maxX - minX);
        double spanZ = Math.max(1.0, maxZ - minZ);
        // The map is wider than it is tall in the two-column layout and the opposite when stacked,
        // so the span is measured against the shape of the frame it has to fit into. A route that
        // runs mostly north-south needs a bigger radius than the same route running east-west,
        // because the frame is wider than it is tall.
        double aspect = mapH > 0 && mapW > 0 ? (double) mapH / mapW : 1.0;
        double needed = Math.max(spanX, spanZ / Math.max(0.2, aspect)) * 0.62;
        targetCenterX = (minX + maxX) * 0.5;
        targetCenterZ = (minZ + maxZ) * 0.5;
        targetViewRadius = Math.max(MIN_VIEW_RADIUS,
                Math.min(MAX_VIEW_RADIUS, needed * 1.15 + 24.0));
        momentumX = 0;
        momentumZ = 0;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * Filters the list to the search box. The name, coordinates and what the player wrote are all
     * matched; a row found through its description is remembered so its second line can show the
     * summary that explains the hit.
     */
    private void applyFilter() {
        descriptionMatches.clear();
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
                    continue;
                }
                if (matchesWrittenText(destination, query)) {
                    matches.add(destination);
                    descriptionMatches.add(destination);
                }
            }
            shown = matches;
        }
        scroll = 0;
    }

    private static boolean matchesWrittenText(Destination destination, String query) {
        PlaceDetails details = PlaceDetailStore.detailsOf(destination);
        if (details.description().toLowerCase(Locale.ROOT).contains(query)) {
            return true;
        }
        for (String tag : details.tags()) {
            if (tag.toLowerCase(Locale.ROOT).contains(query)) {
                return true;
            }
        }
        return false;
    }


    @Override
    // 1.20.2 added the mouse position and the partial tick to this override; 1.20.1 takes only the
    // graphics. Both do nothing here -- the dimming is handled in render so the panel sits between
    // it and the widgets -- so the bodies are the same and only the signature differs.
    //? if >=1.20.2 {
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    //?} else {
    /*public void renderBackground(GuiGraphics graphics) {
    *///?}
        // Dimming is handled in render so the panel sits between it and the widgets, and so the
        // dimming, the sheet and everything on it are faded by one opacity; see #open.
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // The motion state first, before a pixel is drawn: UiMotion.of advances the entrance and the
        // exit once per frame -- it is also what pumps a deferred screen swap, which is why nothing
        // else in this screen calls it -- and every pass below reads the opacity it answers with.
        UiMotion.ScreenState motion = UiMotion.of(this);
        open = motion.opacity();
        // The camera first, for the same reason: every pass below -- the map, the labels, the route
        // preview -- draws through the one set of numbers this advances, and the hit tests read the
        // same numbers, so a frame of an animated pan cannot show one thing and offer another.
        stepCamera();
        // The whole frame travels as one surface before the first pixel, so the entrance is not a cut;
        // popped last so the pose handed in comes back. Row displacement has its own pose (foldRowY).
        UiMotion.pushFrame(graphics, 0.0F, motion.offsetY(UiMotion.RISE, UiMotion.FALL));
        // The fold follows the panel's own state rather than being written where that state is, so
        // the rows and the plans animate for whichever of the several paths changed it. The
        // duration is chosen per direction from the tokens, from wherever the value currently is.
        fold = controlsFold
                .duration(controlsExpanded ? UiMotion.EXPAND : UiMotion.COLLAPSE)
                .to(controlsExpanded ? 1.0F : 0.0F)
                .eased();
        // Measure from the fold, not from the flag it heads for, so the map and list grow with the fold;
        // the plan band is followed for the case where the fold is still and the plan count changes.
        boolean blockMoved = settlePlansBlock();
        if (fold != laidOutFold || blockMoved) {
            relayoutForFold(fold);
        }

        // The scrim reaches past the frame on every side, because the frame above is displaced by up
        // to UiMotion.RISE while the screen arrives: a dimming drawn from the frame's own corner
        // would leave an undimmed strip along the edge the entrance uncovered.
        graphics.fill(-UiMotion.SCRIM_MARGIN, -UiMotion.SCRIM_MARGIN,
                width + UiMotion.SCRIM_MARGIN, height + UiMotion.SCRIM_MARGIN,
                UiMotion.fade(UiTheme.dim(), surface));

        HudDraw.fillRounded(graphics, panelX, panelY, panelX + panelW, panelY + panelHeight,
                CORNER_RADIUS, UiMotion.fade(UiTheme.panel(), surface));
        HudDraw.outlineRounded(graphics, panelX, panelY, panelX + panelW, panelY + panelHeight,
                CORNER_RADIUS, UiMotion.fade(UiTheme.panelEdge(), surface));

        graphics.drawString(font, title, panelX + PADDING, panelY + PADDING,
                UiMotion.fade(UiTheme.header(), open), false);

        drawPickerMap(graphics, mouseX, mouseY);
        drawList(graphics, mouseX, mouseY);
        drawDetailsPanel(graphics, mouseX, mouseY);
        drawPreviewSummary(graphics);
        // Under the readout: the plans while the controls are folded, then the four rows while they
        // are open, then the one bar that toggles between the two, then the footer. The plans are
        // clipped to the shared block too: as the fold opens they leave through its top, and without
        // the clip they would be drawn across the readout on the way out.
        boolean clipPlans = stackBottom > stackTop;
        if (clipPlans) {
            UiMotion.clip(graphics, panelX, stackTop, panelX + panelW, stackBottom);
        }
        drawPlanList(graphics, mouseX, mouseY);
        if (clipPlans) {
            graphics.disableScissor();
        }
        // Both forms of the controls are drawn off the one fold value: rows leave upward while fading,
        // plans come down into the space they give up, and the shared block's height changes on the same
        // value. Rows are drawn wherever the fold is off its collapsed end, clipped to the block.
        if (fold > 0.004F && stackBottom > stackTop) {
            UiMotion.clip(graphics, panelX, stackTop, panelX + panelW, stackBottom);
            drawModeSelector(graphics, mouseX, mouseY);
            drawPreferenceControls(graphics, mouseX, mouseY);
            drawClassControls(graphics, mouseX, mouseY);
            drawTransitControls(graphics, mouseX, mouseY);
            graphics.disableScissor();
        }
        // The bar is always there, in both forms, because it is the one control for the fold: what
        // it says on the right changes, where it is does not.
        drawControlsSummary(graphics, mouseX, mouseY);
        drawFooter(graphics, mouseX, mouseY);

        super.render(graphics, mouseX, mouseY, partialTick);
        // Last statement of the frame: nothing above drew outside the displacement, and the pose is
        // back where the game left it for whatever is drawn after this screen.
        UiMotion.popFrame(graphics);
    }

    /**
     * The place list, in whichever column it was given, always visible. The hovered row is worked out
     * here and read by mouseClicked.
     */
    private void drawList(GuiGraphics graphics, int mouseX, int mouseY) {
        hoveredRow = -1;
        if (shown.isEmpty()) {
            graphics.drawString(font,
                    Component.translatable(all.isEmpty()
                            ? "screen.howtogo.no_destinations"
                            : "screen.howtogo.no_matches").getString(),
                    listPaneX + 4, listTop + 4, UiMotion.fade(UiTheme.textMuted(), open), false);
            return;
        }
        drawRows(graphics, mouseX, mouseY);
    }

    /**
     * The plan band: up to three plans, one row each, the chosen one picked out.
     * Between the readout and the bar, so the places stay visible; it fades and travels with the fold
     * rather than being re-placed. Rows are bottom-aligned, full panel width, equal height because the
     * band is measured from the rows; each is clickable across its width.
     */
    private void drawPlanList(GuiGraphics graphics, int mouseX, int mouseY) {
        hoveredPlan = -1;
        if (plans.isEmpty() || fold >= 0.996F) {
            // No band to draw: there is nothing to compare, or the fold has finished opening and the
            // band's own share of it is gone. The reset above still matters -- a stale row index
            // would take a click meant for the rows below.
            return;
        }
        int rows = Math.min(plans.size(), MAX_PLAN_ROWS);
        // The band is what folding the controls uncovers, so its rows arrive with the fold rather
        // than with the screen: a plan appears as the row holding it does, not in the frame the
        // place was clicked. Multiplied with the entrance, which has long finished by then.
        float bandArrival = 1.0F - fold;
        // The band does not travel. It used to slide out of the way, and a sliding row being cut by
        // the block's own edge -- which is what clips it -- flicked its own text as the edge crossed
        // it. A plain fade has nothing to cut: the block's edge only ever passes over rows that are
        // rising into the space, and they are drawn over the band, not the other way round.
        for (int i = 0; i < rows; i++) {
            // The row is hovered where the layout put it: the click that chooses a plan is decided
            // from planRowTop, so a band in mid-fold cannot move what is under the pointer (rule 1
            // of docs/UI_MOTION.md).
            int laidOut = planRowTop(i);
            int top = laidOut;
            int bottom = top + planRowSize;
            boolean hovered = mouseX >= rowX && mouseX <= rowX + rowW
                    && mouseY >= laidOut && mouseY < laidOut + planRowSize;
            boolean selected = i == planIndex;
            // The same two values a place row carries: the cursor's arrival and being the chosen
            // one, both eased, so choosing a plan moves the mark rather than moving it at once.
            float arrived = UiMotion.stagger(i, open) * bandArrival;
            float lit = planSlides.get(i * 2).to(hovered ? 1.0F : 0.0F).eased();
            float chosen = planSlides.get(i * 2 + 1).to(selected ? 1.0F : 0.0F).eased();

            if (hovered) {
                hoveredPlan = i;
            }
            float shownTint = Math.max(lit, chosen);
            if (shownTint * arrived > 0.004F) {
                graphics.fill(rowX, top, rowX + rowW, bottom - 1,
                        UiMotion.fade(UiMotion.mix(UiTheme.rowActiveTint(),
                                UiTheme.rowHoverTint(), lit), shownTint * arrived));
            }
            if (chosen * arrived > 0.004F) {
                // Edge as well as fill. A tint alone is easy to miss on a row the reader's eye is
                // not on, while the accent edge says which row the footer's start button will start
                // -- which is the one thing the row is selected for. Shapes belong before the text
                // pass of the same row; these are GuiGraphics fills, so neither ordering nor the
                // vertex batches elsewhere in this screen are involved.
                HudDraw.outlineRounded(graphics, rowX, top, rowX + rowW, bottom - 1, 3,
                        UiMotion.fade(UiTheme.accent(), chosen * arrived));
            }
            RouteOption plan = plans.get(i);
            String title = planTitle(i, plan);
            String time = Route.formatDuration(plan.seconds());
            int timeWidth = font.width(time);
            int textX = rowX + 6;
            int titleWidth = Math.max(16, rowW - 12 - timeWidth - 4);
            // Two lines, always: a plan row is a title and a facts line, and both heights the
            // layout picks are tall enough for the pair. They are centred as a pair rather than
            // pinned to the row's top, so a row stretched on a tall window looks the same.
            int lineY = top + (planRowSize - 17) / 2;
            // The chosen row's three colours are mixed rather than picked between, so a plan that
            // stops being the chosen one gives the accent back over the same slide that took it.
            graphics.drawString(font, font.plainSubstrByWidth(title, titleWidth), textX, lineY,
                    UiMotion.fade(UiMotion.mix(UiTheme.textStrong(), UiTheme.accent(), chosen),
                            arrived), false);
            graphics.drawString(font, time, rowX + rowW - 6 - timeWidth, lineY,
                    UiMotion.fade(UiMotion.mix(UiTheme.textMuted(), UiTheme.accent(), chosen),
                            arrived), false);
            graphics.drawString(font, font.plainSubstrByWidth(planFacts(plan), rowW - 12), textX,
                    lineY + 9,
                    UiMotion.fade(UiMotion.mix(UiTheme.textMuted(), UiTheme.textSecondary(), chosen),
                            arrived), false);
        }
    }

    /**
     * Top of plan row index: one function for drawing and click tests. Every row is planRowSize tall,
     * so there is no remainder and the last ends where the controls begin.
     */
    private int planRowTop(int index) {
        return planTop + index * planRowSize;
    }

    /**
     * The first line of a result row: which plan it is and the strategy that produced it -- the ordinal
     * is the question, the strategy the answer, since rows are time-sorted.
     */
    private String planTitle(int index, RouteOption plan) {
        return Component.translatable("screen.howtogo.plan.title", planOrdinal(index),
                plan.label()).getString();
    }

    private static String planOrdinal(int index) {
        String key = switch (index) {
            case 0 -> "screen.howtogo.plan.first";
            case 1 -> "screen.howtogo.plan.second";
            default -> "screen.howtogo.plan.third";
        };
        return Component.translatable(key).getString();
    }

    /**
     * The second line of a result row: the numbers that separate one plan from another.
     * A world journey gives distance and turns, a transit one changes and walking; asking the plan
     * keeps the shown figure and the followed figure the same calculation. Keyed on the journey, since
     * the walk offered in transit's place has no changes to report.
     */
    private static String planFacts(RouteOption plan) {
        String distance = Route.formatDistance(plan.length());
        if (plan.trip() != null) {
            return Component.translatable("screen.howtogo.plan.transit_facts", distance,
                    plan.transfers(), Route.formatDistance(plan.walked())).getString();
        }
        return Component.translatable("screen.howtogo.plan.facts", distance, plan.turns())
                .getString();
    }

    /**
     * The plan the map, readout and footer are all about, or null. The index is clamped, since a
     * read a frame behind a re-plan must show a plan rather than throw.
     */
    private RouteOption selectedPlan() {
        if (plans.isEmpty()) {
            return null;
        }
        return plans.get(Math.max(0, Math.min(planIndex, plans.size() - 1)));
    }

    /**
     * The selected place's details, in the band the list left under its rows.
     * Drawn after the list and outside the map's quad sequence, because its strings flush the batch.
     * Asked for the details every frame rather than handed a copy, since the favourite toggle writes
     * the store. Always drawn while a place is selected.
     */
    private void drawDetailsPanel(GuiGraphics graphics, int mouseX, int mouseY) {
        if (detailsPlace == null || detailsHeight <= 0) {
            return;
        }
        detailPanel.render(graphics, font, listPaneX, detailsY, listPaneW, detailsHeight,
                detailsPlace, PlaceDetailStore.detailsOf(detailsPlace),
                PlaceDetailPanel.Options.STANDARD, false, mouseX, mouseY);
    }


    /**
     * Road names on the picker map, so a place can be recognised by the roads around it.
     * Named roads only, dropped on collision; labels are gated by MapFilter#shows, so a class not
     * drawn at this zoom gets no name either. The player's road_label_style picks a name, a sign card,
     * or number and name together -- one label per road, one shared collision list.
     * A card gives way to the name at LABEL_MIN_SCALE; nothing is written past NAME_MAX_VIEW_BLOCKS.
     * Line names are offered what is left, last -- see drawLineNames.
     */
    private void drawMapLabels(GuiGraphics graphics, Minecraft mc, RoadNetwork network, double scale,
                               double centerX, double centerY,
                               double minX, double minY, double maxX, double maxY) {
        // One rule for the whole pass, read once: see NAME_MAX_VIEW_BLOCKS. A name drawn at one size on
        // a map that zooms has to stop somewhere, and the map showing a whole journey is where.
        if (viewRadius * 2.0 > NAME_MAX_VIEW_BLOCKS) {
            return;
        }
        List<double[]> placed = new ArrayList<>();
        RoadChains.Grouping grouping = RoadChains.cachedGrouping(network);
        RoadLabelStyle style = RoadConfig.roadLabelStyle();
        boolean signMode = style != RoadLabelStyle.NAME;
        boolean mixed = style == RoadLabelStyle.MIXED;
        // Read once for the pass, like the style: a card is a fixed-size plate on a map that zooms, so
        // the zoom at which plates take over the view is one answer for the frame rather than one per
        // road. See LABEL_MIN_SCALE.
        boolean signZoom = scale >= LABEL_MIN_SCALE;
        // A label that fits inside the map is at most as large as the map, so a road whose midpoint is
        // a whole map's dimension away from it cannot produce a box that passes the test below. Wider
        // than any label, and no assumption about the font: a cheap guard, not the rule.
        double padX = maxX - minX;
        double padY = maxY - minY;
        // The frame cuts what reaches past it, the way a paper map does: a name whose last letter met
        // the border used to be dropped whole and come back a notch later, which is flicker rather than
        // a rule. The claim each label registers is still its whole painted box.
        UiMotion.clip(graphics, (int) minX, (int) minY, (int) maxX, (int) maxY);
        for (RoadSegment segment : network.segments()) {
            String name = segment.name();
            boolean named = name != null && !name.isBlank();
            // A sign needs a number and a category that still exists: a deleted category draws the road as
            // unclassified, and a card is only drawn above LABEL_MIN_SCALE.
            boolean sign = signMode && signZoom && segment.hasSign()
                    && RoadCategoryStore.has(segment.category());
            if (!named && !sign) {
                continue;
            }
            // A label follows the same visibility the roads do, which is the rule the K screen and
            // the world map have always followed: a name over a class this map is not drawing at
            // this zoom would point at nothing. This pass was the one that skipped the test, so a
            // path or a waterway the player had switched off -- or one the zoom had left out
            // everywhere else -- still had its name written here. See MapFilter.shows.
            if (!MapFilter.shows(segment.roadClass(), scale)) {
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
            if (sign) {
                drawSignCard(graphics, mc.font, segment, x, y, mixed, minX, minY, maxX, maxY, placed);
                continue;
            }

            // Along the line, from the road's own world direction. This map is north-up, so the world
            // direction is the screen direction and no rotation of the map has to be undone first.
            int last = segment.vertexCount() - 1;
            float angle = HudDraw.labelAngle(segment.x(last) - segment.x(0),
                    segment.z(last) - segment.z(0));

            int width = mc.font.width(name);
            // The name has to fit along the road it names: a word wider than its road reaches past both
            // of the road's ends and reads as a label for whatever is beside it. The same test the phone
            // page makes, in this map's pixels and with the same few pixels of road showing either side.
            if (width > chainScreenPx(network, grouping.chainOf(segment), scale)
                    - ROAD_NAME_END_MARGIN * 2.0) {
                continue;
            }
            double[] extents = HudDraw.rotatedExtents(width, LABEL_HEIGHT, angle);
            // The box actually painted, centred on the point the label has always been centred on, so
            // rotating it does not shift it off the road.
            double centreY = y - 4 + LABEL_HEIGHT / 2.0;
            double boxX = x - extents[0] / 2.0;
            double boxY = centreY - extents[1] / 2.0;
            // Anchored where the road is on screen: the map's frame cuts the rest (see the clip this
            // pass runs inside). A label that had to fit whole was dropped the moment its last letter
            // met the border and came back a notch later.
            if (x < minX || x >= maxX || centreY < minY || centreY >= maxY) {
                continue;
            }
            // What is reserved is the painted box grown by the label clearance, not the ink alone:
            // see LABEL_CLEARANCE_PX. Measuring the inflated claim once here means the test and the
            // reservation cannot disagree about how much room this label took.
            double[] claim = new double[] {
                    boxX - LABEL_CLEARANCE_PX, boxY - LABEL_CLEARANCE_PX,
                    extents[0] + LABEL_CLEARANCE_PX * 2.0, extents[1] + LABEL_CLEARANCE_PX * 2.0};
            boolean clash = false;
            for (double[] other : placed) {
                if (claim[0] < other[0] + other[2] && other[0] < claim[0] + claim[2]
                        && claim[1] < other[1] + other[3] && other[1] < claim[1] + claim[3]) {
                    clash = true;
                    break;
                }
            }
            if (clash) {
                continue;
            }
            placed.add(claim);

            // A rotated label. 1.21.6 moved the GUI matrix stack from a PoseStack to JOML's
            // Matrix3x2fStack: for a 2D translate-and-rotate the two are the same operation under
            // the new name, and the stack is part of GuiGraphics either way.
            //? if >=1.21.6 {
            /*Matrix3x2fStack pose = graphics.pose();
            pose.pushMatrix();
            pose.translate((float) x, (float) centreY);
            pose.rotate((float) angle);
            graphics.drawString(mc.font, name, -width / 2, -4, UiTheme.mapLabel(), false);
            pose.popMatrix();
            *///?} else {
            PoseStack pose = graphics.pose();
            pose.pushPose();
            pose.translate(x, centreY, 0.0);
            pose.mulPose(Axis.ZP.rotation(angle));
            graphics.drawString(mc.font, name, -width / 2, -4, UiTheme.mapLabel(), false);
            pose.popPose();
            //?}
        }
        // The public transport lines' names, last: they are opaque plates in the lines' own colours,
        // so they are offered the room the place names and the road labels have already claimed
        // rather than being allowed to displace one. Inside the map's clip, because a plate is cut at
        // the frame like every other label here.
        drawLineNames(graphics, mc, placed, minX, minY, maxX, maxY, centerX, centerY, scale);
        graphics.disableScissor();
    }

    /**
     * A public transport line's name, as a capsule in the line's own colour above the line.
     * A road's name is ink along the ground; a line's name names a service, so it is horizontal with
     * rounding of half its height, like the same badge in the list and on a place card. Positions come
     * from LineNameLabels, gated by LINE_NAME_MAX_VIEW_RADIUS and the player's lines switch, and the
     * pass is last so the plate takes what the established labels left.
     */
    private void drawLineNames(GuiGraphics graphics, Minecraft mc, List<double[]> placed,
                               double minX, double minY, double maxX, double maxY,
                               double centerX, double centerY, double scale) {
        if (viewRadius > LINE_NAME_MAX_VIEW_RADIUS || !MapFilter.showsLines()) {
            return;
        }
        List<RoadElementRenderer.NamedTransitStroke> strokes =
                RoadElementRenderer.transitLineStrokesFor(Navigation.linesInPlay());
        double height = LineNameLabels.height(mc.font.lineHeight);
        if (strokes.isEmpty() || !(height > 0.0)) {
            return;
        }
        int drawn = 0;
        // One projection and one fit test for the whole pass: the map does not move between two
        // candidates and the reservation list is the same object throughout. The box the fit test
        // approved is handed back through this array rather than measured again by the caller: a
        // second measurement could round a corner differently and reserve a box that was not tested.
        double[][] claimed = new double[1][];
        LineNameLabels.Screen screen = new LineNameLabels.Screen() {
            @Override
            public double screenX(double worldX, double worldZ) {
                return centerX + (worldX - mapCenterX) * scale;
            }

            @Override
            public double screenY(double worldX, double worldZ) {
                return centerY + (worldZ - mapCenterZ) * scale;
            }

            @Override
            public boolean fits(double left, double top, double right, double bottom) {
                // A plate that reaches past the frame is not offered at all: the clip would cut it, and
                // half a capsule is not a plate.
                if (left < minX + 1 || right > maxX - 1 || top < minY + 1 || bottom > maxY - 1) {
                    return false;
                }
                double[] box = new double[] {
                        left - LABEL_CLEARANCE_PX, top - LABEL_CLEARANCE_PX,
                        right - left + LABEL_CLEARANCE_PX * 2.0,
                        bottom - top + LABEL_CLEARANCE_PX * 2.0};
                for (double[] other : placed) {
                    if (box[0] < other[0] + other[2] && other[0] < box[0] + box[2]
                            && box[1] < other[1] + other[3] && other[1] < box[1] + box[3]) {
                        return false;
                    }
                }
                claimed[0] = box;
                return true;
            }
        };
        for (RoadElementRenderer.NamedTransitStroke stroke : strokes) {
            double width = LineNameLabels.width(mc.font.width(stroke.name()));
            if (!(width > 0.0)) {
                continue;
            }
            // The candidate points are worked out in one walk of the line, and each is then placed in
            // constant time -- see LineNameLabels, which states why the distance form is the wrong
            // shape for a line read out of MTR.
            for (LineNameLabels.Candidate candidate : LineNameLabels.candidatePoints(stroke.points())) {
                claimed[0] = null;
                double[] spot = LineNameLabels.find(candidate, width, height, LINE_NAME_RISE_PX,
                        screen);
                if (spot == null) {
                    continue;
                }
                placed.add(claimed[0]);
                drawLineNamePlate(graphics, mc.font, stroke.name(), spot[0], spot[1], width, height,
                        stroke.colour());
                if (++drawn >= MAX_LINE_NAMES) {
                    return;
                }
            }
        }
    }

    /**
     * One line's name plate: a capsule in the line's colour, rounding half its height -- deliberately
     * not a sign card's rectangle. Fills go through the GUI's batches before the text, and the ink
     * comes from the sign cards' contrast rule.
     */
    private void drawLineNamePlate(GuiGraphics graphics, Font font, String name, double centreX,
                                   double centreY, double width, double height, int colour) {
        int x1 = (int) Math.round(centreX - width / 2.0);
        int y1 = (int) Math.round(centreY - height / 2.0);
        int x2 = (int) Math.round(centreX + width / 2.0);
        int y2 = (int) Math.round(centreY + height / 2.0);
        if (x2 <= x1 || y2 <= y1) {
            return;
        }
        int ink = RoadSigns.ink(colour);
        int radius = Math.max(1, (y2 - y1) / 2);
        HudDraw.fillRounded(graphics, x1, y1, x2, y2, radius, colour);
        HudDraw.outlineRounded(graphics, x1, y1, x2, y2, radius, ink);
        drawScaledCardText(graphics, font, name, centreX,
                centreY - font.lineHeight * (double) LineNameLabels.NAME_FACTOR / 2.0, ink,
                LineNameLabels.NAME_FACTOR);
    }

    /**
     * How long a chain is on this map, in pixels: the room a name has to lie in, walked along the
     * chain's vertices because a curved road is longer than the straight line across it.
     */
    private static double chainScreenPx(RoadNetwork network, int[] chain, double scale) {
        double total = 0.0;
        for (int id : chain) {
            RoadSegment member = network.segment(id);
            if (member == null) {
                continue;
            }
            for (int i = 1; i < member.vertexCount(); i++) {
                total += Math.hypot((member.x(i) - member.x(i - 1)) * scale,
                        (member.z(i) - member.z(i - 1)) * scale);
            }
        }
        return total;
    }

    /**
     * One road's sign card on the picker map: category colour, one-pixel contrasting border, the
     * number centred, and in mixed mode the name shrunk beneath. Upright and centred on the road,
     * because a sign stands beside the road rather than lying along it; everything is measured in the
     * card's units and drawn at SIGN_CARD_SCALE.
     * The card is sized from the number; the name shrinks no further than SIGN_CARD_NAME_FLOOR and
     * past that widens the plate.
     */
    private void drawSignCard(GuiGraphics graphics, Font font, RoadSegment segment, double x, double y,
                              boolean withName, double minX, double minY, double maxX, double maxY,
                              List<double[]> placed) {
        String number = segment.number();
        if (number == null || number.isBlank()) {
            return;
        }
        String name = withName ? segment.name() : null;
        boolean named = name != null && !name.isBlank();
        int numberWidth = font.width(number);
        int nameWidth = named ? font.width(name) : 0;
        // The name line's share of the card's scale, and the width the plate has to give it, both in
        // the card's unscaled units: the world map's mixed rule, one map's pixels being font units.
        double nameFactor = 1.0;
        int innerWidth = numberWidth;
        if (named) {
            double raw = (double) numberWidth / nameWidth;
            nameFactor = Math.min(1.0, Math.max(SIGN_CARD_NAME_FLOOR, raw));
            // The hair is subtracted before the ceil for the reason the world map's copy states:
            // raw was formed by a division and is multiplied back out here, so a name that fits the
            // number's line exactly must not round up a pixel and widen a plate it already fits.
            innerWidth = Math.max(numberWidth,
                    (int) Math.ceil(nameWidth * nameFactor - 1.0E-6));
        }
        double nameLineUnits = named
                ? font.lineHeight * nameFactor + SIGN_CARD_LINE_GAP
                : 0.0;
        // Everything in unscaled units up to here, and one scaling at the end: that is what keeps the
        // plate's proportions the same as on the other two maps.
        int cardWidth = Math.max(1, (int) Math.round(
                (innerWidth + SIGN_CARD_PAD_X * 2) * (double) SIGN_CARD_SCALE));
        int cardHeight = Math.max(1, (int) Math.round(
                (font.lineHeight + nameLineUnits + SIGN_CARD_PAD_Y * 2) * (double) SIGN_CARD_SCALE));
        int left = (int) Math.round(x - cardWidth / 2.0);
        // Centred on the road's midpoint rather than sitting above it: the plate is the sign standing
        // on the road, so the stroke enters it and leaves it on both sides. See the method's note.
        int top = (int) Math.round(y - cardHeight / 2.0);
        int bottom = top + cardHeight;
        // The same test the name path makes: a label that would reach outside the map is dropped
        // rather than painted across the panel around it. The scaled rectangle is the one tested and
        // the one reserved below, so what the card occupies and what it reserves cannot differ.
        if (left < minX + 1 || left + cardWidth > maxX - 1
                || top < minY + 1 || bottom > maxY - 1) {
            return;
        }
        // And the same collision rule, against the boxes the names have already taken: one list for
        // both kinds of label is what keeps a card and a name from covering each other. The card
        // claims its painted rectangle grown by the label clearance, so two plates of neighbouring
        // roads stand apart instead of reading as one band of colour.
        double[] claim = new double[] {
                left - LABEL_CLEARANCE_PX, top - LABEL_CLEARANCE_PX,
                cardWidth + LABEL_CLEARANCE_PX * 2.0, cardHeight + LABEL_CLEARANCE_PX * 2.0};
        for (double[] other : placed) {
            if (claim[0] < other[0] + other[2] && other[0] < claim[0] + claim[2]
                    && claim[1] < other[1] + other[3] && other[1] < claim[1] + claim[3]) {
                return;
            }
        }
        placed.add(claim);

        int background = RoadCategoryStore.color(segment.category());
        int ink = RoadSigns.ink(background);
        // Rounded with the shared radius, clamped inside the fill to half the card's height so a short
        // card does not become a pill. Both fills come before the two strings below: a string flushes the
        // buffer it writes to. The rim is one pixel on the fill's own outline, so it follows the rounding.
        HudDraw.fillRounded(graphics, left, top, left + cardWidth, bottom,
                RoadSigns.CARD_CORNER_PX, background);
        HudDraw.outlineRounded(graphics, left, top, left + cardWidth, bottom,
                RoadSigns.CARD_CORNER_PX, ink);

        // Both lines are drawn at the card's own scale through the pose, centred on the plate's
        // middle: a scaled font written at an absolute position would be a small word floating in a
        // corner of the plate instead of the plate's content.
        double centreX = left + cardWidth / 2.0;
        double numberTop = top + SIGN_CARD_PAD_Y * (double) SIGN_CARD_SCALE;
        drawScaledCardText(graphics, font, number, centreX, numberTop, ink, SIGN_CARD_SCALE);
        if (named) {
            double nameTop = top + (SIGN_CARD_PAD_Y + font.lineHeight + SIGN_CARD_LINE_GAP)
                    * (double) SIGN_CARD_SCALE;
            drawScaledCardText(graphics, font, name, centreX, nameTop, ink,
                    SIGN_CARD_SCALE * (float) nameFactor);
        }
    }

    /**
     * Writes one line of a sign card at a given scale, centred on a point.
     * The draw call takes no size, so the pose is pushed, moved to the line's centre, scaled, and the
     * string written at the origin offset by half its width. 1.21.6 replaced PoseStack with JOML's
     * Matrix3x2fStack; for translate-and-scale the operation is the same.
     */
    private static void drawScaledCardText(GuiGraphics graphics, Font font, String text,
                                           double centreX, double top, int colour, float scale) {
        int width = font.width(text);
        //? if >=1.21.6 {
        /*Matrix3x2fStack pose = graphics.pose();
        pose.pushMatrix();
        pose.translate((float) centreX, (float) top);
        pose.scale(scale, scale);
        graphics.drawString(font, text, -width / 2, 0, colour, false);
        pose.popMatrix();
        *///?} else {
        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(centreX, top, 0.0);
        pose.scale(scale, scale, 1.0F);
        graphics.drawString(font, text, -width / 2, 0, colour, false);
        pose.popPose();
        //?}
    }

    private void drawPickerMap(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.fill(mapX - 1, mapY - 1, mapX + mapW + 1, mapY + mapH + 1,
                UiMotion.fade(UiTheme.panelEdge(), surface));
        graphics.fill(mapX, mapY, mapX + mapW, mapY + mapH,
                UiMotion.fade(UiTheme.mapBackdrop(), surface));

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        double playerX = mc.player.getX();
        double playerZ = mc.player.getZ();
        double scale = mapW / (viewRadius * 2.0);
        double centerX = mapX + mapW / 2.0;
        double centerY = mapY + mapH / 2.0;

        // North-up, unlike the travelling HUD: a consistent orientation matters more than facing when
        // choosing a place. One drawing block, opened the same way on either side of 1.21.6.
        HudDraw.Drawing drawing = HudDraw.beginBlock(graphics);
        HudDraw.VertexSink last = drawing.sink;

        double minX = mapX;
        double minY = mapY;
        double maxX = mapX + mapW;
        double maxY = mapY + mapH;
        double reach = viewRadius * 1.5;
        // Every line on this map carries its own alpha rather than a colour with one baked in, so
        // the screen's opacity is applied here, once: the map's roads and its routes arrive with the
        // sheet instead of being painted at full strength over it.
        int roadAlpha = (int) (0x9A * open);
        int routeAlpha = (int) (0xE0 * open);

        // Where every place sits on this map, worked out once and handed to both the marker pass and
        // the hover readout below. The two used to ask for it separately, which meant asking every
        // source -- a town's worth of stations among them -- for its destinations twice a frame.
        List<PlaceHit> places = placeHits();

        RoadNetwork network = RoadStore.get();
        // One affine map for every line this map draws, so the stroke pass can miter the bends of a
        // road instead of leaving a notch at each vertex of a railway sampled once per block.
        double m00 = scale;
        double m02 = centerX - mapCenterX * scale;
        double m11 = scale;
        double m12 = centerY - mapCenterZ * scale;
        HudDraw.emitNetwork(last, network, m00, 0, m02, 0, m11, m12,
                0.7, roadAlpha, minX, minY, maxX, maxY);

        // The machine-read rails -- Create's tracks and MTR's -- drawn with the roads and before the
        // labels: a station on the list should be a station the player can see the line to, and for a
        // station that exists only in MTR that line is the one MTR's own rails run along. The whole
        // quad sequence stays ahead of the name pass below, which is what keeps the emit order legal.
        HudDraw.emitNetwork(last, RailTrackStore.network(), m00, 0, m02, 0, m11, m12,
                0.7, roadAlpha, minX, minY, maxX, maxY);
        HudDraw.emitNetwork(last, MtrTransit.railLayer(), m00, 0, m02, 0, m11, m12,
                0.7, roadAlpha, minX, minY, maxX, maxY);

        // The roads read out of RoadWeaver, drawn with the roads: a route to one of its villages is
        // planned partly over them, and a preview that omitted them would show a line across ground
        // it cannot explain. Drawn chain by chain so a long road is one continuous stroke, and boxed
        // by the view rectangle -- the layer can hold a whole region's worth.
        HudDraw.emitNetworkInBox(last, RoadWeaverLayer.drawnNetwork(), m00, 0, m02, 0, m11, m12,
                0.7, roadAlpha, minX, minY, maxX, maxY,
                mapCenterX - reach, mapCenterZ - reach, mapCenterX + reach, mapCenterZ + reach);

        // Public transport lines, above the ground and below the route; drawn through the stops since
        // this map has no track. Half a footpath's width, floored, and hidden by the player's switch.
        if (MapFilter.showsLines()) {
            HudDraw.emitTransitLines(last, Navigation.linesInPlay(), m00, 0, m02, 0, m11, m12,
                    Math.max(0.5, 0.7 * 0.5), (int) (0xE0 * open), minX, minY, maxX, maxY);
        }

        // The live route, in the ink each of its stretches calls for: the map is a plan, and a
        // journey's walking and riding have to be told apart on it or the plan is the one thing the
        // picker shows that the map would not.
        HudDraw.RouteInk liveInk = Navigation.routeInk();
        if (liveInk.isPresent()) {
            HudDraw.emitInk(last, liveInk, m00, 0, m02, 0, m11, m12,
                    1.1, routeAlpha, minX, minY, maxX, maxY);
        }

        // The preview goes down in the same ink as the live route, so a route looked at before
        // committing and the same route afterwards read as the same thing. Drawn after it because
        // a preview is only ever the newer of the two, and would otherwise hide under the line it
        // is being compared with.
        if (!plans.isEmpty()) {
            // Every plan at once, so the map compares what the list compares: each in its own ink, the chosen
            // one at live width and strength, the others thin and faded. Unchosen lines first, so the chosen
            // is never hidden.
            for (int i = 0; i < plans.size(); i++) {
                if (i != planIndex) {
                    drawPlanLine(last, plans.get(i), false, m00, m02, m11, m12,
                            minX, minY, maxX, maxY);
                }
            }
            if (planIndex >= 0 && planIndex < plans.size()) {
                drawPlanLine(last, plans.get(planIndex), true, m00, m02, m11, m12,
                        minX, minY, maxX, maxY);
            }
        } else {
            Navigation.RoutePreview planned = preview;
            if (planned != null && planned.isPresent()) {
                // No plans to compare, so this is the one line the panel has: the walker's fallback,
                // a walk or a drive. Its own journey decides the colours when there is one -- a
                // public transport journey has to be drawn in its stretches rather than as one line,
                // and a mode is not the answer here, the plan that was actually made is.
                HudDraw.RouteInk previewInk = Navigation.inkFor(planned.route(), planned.trip());
                if (previewInk.isPresent()) {
                    HudDraw.emitInk(last, previewInk, m00, 0, m02, 0, m11, m12,
                            1.1, routeAlpha, minX, minY, maxX, maxY);
                }
            }
        }

        double originX = Navigation.tripOriginX();
        double originZ = Navigation.tripOriginZ();
        if (!Double.isNaN(originX)) {
            marker(last, centerX + (originX - mapCenterX) * scale,
                    centerY + (originZ - mapCenterZ) * scale, UiTheme.origin(), minX, minY, maxX, maxY);
        }
        // A preview starts at the player rather than at a pinned trip origin, so without a live
        // trip its start has to be marked or the route line begins nowhere. The old preview carried
        // that position itself; a results list does not -- every plan starts at the player -- so the
        // player's own position stands in for it there.
        if (Double.isNaN(originX)) {
            if (preview != null) {
                marker(last, centerX + (preview.originX() - mapCenterX) * scale,
                        centerY + (preview.originZ() - mapCenterZ) * scale, UiTheme.origin(),
                        minX, minY, maxX, maxY);
            } else if (!plans.isEmpty()) {
                marker(last, centerX + (playerX - mapCenterX) * scale,
                        centerY + (playerZ - mapCenterZ) * scale, UiTheme.origin(),
                        minX, minY, maxX, maxY);
            }
        }
        Destination target = Navigation.target();
        if (target != null) {
            marker(last, centerX + (target.x() - mapCenterX) * scale,
                    centerY + (target.z() - mapCenterZ) * scale, UiTheme.destination(),
                    minX, minY, maxX, maxY);
        }
        if (pendingPick != null) {
            marker(last, centerX + (pendingPick[0] - mapCenterX) * scale,
                    centerY + (pendingPick[1] - mapCenterZ) * scale, UiTheme.accent(),
                    minX, minY, maxX, maxY);
        }

        // The player is drawn only when the whole dot falls inside the map: panning leaves them far
        // off the visible area, and a dot painted regardless ended up on the panel around the map.
        // Every other mark in this method clips for the same reason; this one was the exception.
        emitDiscIfInside(last, centerX + (playerX - mapCenterX) * scale,
                centerY + (playerZ - mapCenterZ) * scale, 3.0, UiTheme.player(), minX, minY, maxX, maxY);

        // Every place gets its marker on the preview as well, so the list and the map agree about
        // where the places are: drawn through the same hit list the hover and click use.
        drawPlaceMarkers(places, last, minX, minY, maxX, maxY);

        // Names go last, after every quad: GuiGraphics.drawString flushes the batch it writes to,
        // which ends the QUADS BufferBuilder behind vc. Emitting even one more quad after this
        // throws "Not building!", so the label pass must stay at the tail of the quad sequence.
        drawMapLabels(graphics, mc, network, scale, centerX, centerY, minX, minY, maxX, maxY);

        HudDraw.endBlock(graphics, drawing);

        // Hover crosshair and the coordinates under it, so a point can be chosen precisely. The
        // crosshair is drawn on the exact cursor pixel, so at the map's edge half of it would reach
        // outside; it is dropped unless it fits, and the label is then placed on whichever side has
        // room for it. Both belong to the map and neither may be painted on the panel around it.
        if (insideMap(mouseX, mouseY)) {
            if (crosshairFits(mouseX, mouseY)) {
                graphics.fill((int) mouseX - 4, (int) mouseY, (int) mouseX + 5, (int) mouseY + 1,
                        UiMotion.fade(UiTheme.crosshair(), open));
                graphics.fill((int) mouseX, (int) mouseY - 4, (int) mouseX + 1, (int) mouseY + 5,
                        UiMotion.fade(UiTheme.crosshair(), open));
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
            // What the player wrote about the place, if anything, on a line under the name: the map
            // says which place a marker is, and this is the one line that says what it is like.
            // Clipped to the map's own width so a long note cannot reach past its edge, and dropped
            // whole when the readout has no room for a second line.
            String summary = hovered == null ? "" : PlaceText.summary(font,
                    PlaceDetailStore.detailsOf(hovered.destination()).description(), mapW - 12);
            int labelLines = summary.isEmpty() ? 1 : 2;
            // Above the cursor when there is room, otherwise below it; only if neither side fits is
            // the readout dropped, which can only happen on a map shorter than the text itself.
            int labelY = (int) mouseY - 12;
            if (labelY < minY + 1) {
                labelY = (int) mouseY + 6;
            }
            if (labelY + labelLines * LABEL_HEIGHT > maxY - 1) {
                labelLines = 1;
            }
            if (labelY >= minY + 1 && labelY + labelLines * LABEL_HEIGHT <= maxY - 1) {
                int labelX = (int) mouseX + 5;
                int width = font.width(label);
                if (labelX + width > maxX - 1) {
                    labelX = (int) mouseX - 5 - width;
                }
                if (labelX >= minX + 1 && labelX + width <= maxX - 1) {
                    graphics.drawString(font, label, labelX, labelY,
                            UiMotion.fade(UiTheme.textStrong(), open), true);
                    if (labelLines > 1) {
                        // Clamped rather than hung off the name's own position: the name may have
                        // been flipped to the cursor's left, and the summary is measured against the
                        // map's width instead of the label's.
                        int summaryWidth = mapW - 12;
                        int summaryX = (int) Math.max(minX + 1,
                                Math.min(labelX, maxX - 1 - summaryWidth));
                        graphics.drawString(font, summary, summaryX, labelY + LABEL_HEIGHT,
                                UiMotion.fade(UiTheme.textSecondary(), open), true);
                    }
                }
            }
        }

        // "Back to me": after panning, the player marker can be far off the visible area.
        int bx = mapX + mapW - RECENTER_SIZE - 2;
        int by = mapY + 2;
        HudDraw.fillRounded(graphics, bx, by, bx + RECENTER_SIZE, by + RECENTER_SIZE, 2,
                UiMotion.fade(UiTheme.mapButton(), open));
        int iconColor = UiMotion.fade(UiTheme.icon(), open);
        graphics.fill(bx + 2, by + RECENTER_SIZE / 2, bx + RECENTER_SIZE - 2,
                by + RECENTER_SIZE / 2 + 1, iconColor);
        graphics.fill(bx + RECENTER_SIZE / 2, by + 2, bx + RECENTER_SIZE / 2 + 1,
                by + RECENTER_SIZE - 2, iconColor);
    }

    /**
     * One plan's line on the picker map.
     * Drawn in its own ink -- walking blue, the line's colour, driving green -- never this screen's
     * accent: the map has to say what the plan is. The chosen plan is told apart by weight, not hue:
     * full width and strength, others thinner and faded per stretch by alpha so hues stay distinct.
     * Emitted through the shared sink, so it must be called before the name pass.
     */
    private static void drawPlanLine(HudDraw.VertexSink out, RouteOption plan, boolean selected,
                                     double m00, double m02, double m11, double m12,
                                     double minX, double minY, double maxX, double maxY) {
        if (plan == null || !plan.isPresent()) {
            return;
        }
        HudDraw.RouteInk ink = Navigation.inkFor(plan.route(), plan.trip());
        if (!ink.isPresent()) {
            return;
        }
        HudDraw.emitInk(out, ink, m00, 0, m02, 0, m11, m12,
                selected ? 1.1 : 0.7, selected ? 0xE0 : 0x70, minX, minY, maxX, maxY);
    }

    /**
     * Draws a mark only when the whole of it falls inside the map: testing only the centre let its
     * rim paint over the border.
     */
    private static void emitDiscIfInside(HudDraw.VertexSink out, double x, double y,
                                         double radius, int argb,
                                         double minX, double minY, double maxX, double maxY) {
        if (x - radius < minX || x + radius > maxX || y - radius < minY || y + radius > maxY) {
            return;
        }
        HudDraw.emitDisc(out, x, y, radius, argb, 0xFF);
    }

    private static void marker(HudDraw.VertexSink out, double x, double y,
                               int color, double minX, double minY, double maxX, double maxY) {
        emitDiscIfInside(out, x, y, 2.6, color, minX, minY, maxX, maxY);
    }

    private record PlaceHit(Destination destination, int x, int y) {
    }

    /**
     * Every place's position on the preview, in the drawing's own transform, computed rather than
     * remembered so input handling and drawing agree within the same frame.
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
     * The place whose marker is under a screen point, or null, with a generous catch area because
     * the marker is under four pixels. Works out the positions itself for the input handlers; the
     * drawing passes use the overload with their own list.
     */
    private PlaceHit placeAt(double mouseX, double mouseY) {
        return placeAt(placeHits(), mouseX, mouseY);
    }

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
     * Every place gets its marker, so the list and the map agree where the places are.
     * Before the name pass, because a string flushes the batch these were written into. Positions come
     * from the caller -- the same ones the hover readout uses.
     */
    private void drawPlaceMarkers(List<PlaceHit> places, HudDraw.VertexSink out,
                                  double minX, double minY, double maxX, double maxY) {
        for (PlaceHit hit : places) {
            if (!emitPlaceMarker(out, hit.x(), hit.y(), minX, minY, maxX, maxY)) {
                continue;
            }
            // A favourite's corner mark, in the same block as the marker it decorates: a block of
            // its own would be a second vertex batch, and the name pass after this one flushes the
            // first -- the star has to be written before that happens.
            if (PlaceDetailStore.isFavorite(hit.destination())) {
                HudDraw.emitStar(out, hit.x() + 3, hit.y() - 3, 2.6, UiTheme.accent());
            }
        }
    }

    /**
     * A place's marker, skipped entirely when it falls outside the map.
     *
     * @return whether the marker was drawn, so its decorations are skipped with it
     */
    private static boolean emitPlaceMarker(HudDraw.VertexSink out, double x, double y,
                                           double minX, double minY, double maxX, double maxY) {
        if (x < minX || x > maxX || y < minY || y > maxY) {
            return false;
        }
        HudDraw.emitPlaceMarker(out, x, y, HudDraw.PLACE_MARKER_SMALL_PX * 0.5,
                UiTheme.place());
        return true;
    }

    private boolean crosshairFits(double mouseX, double mouseY) {
        return mouseX - 4 >= mapX && mouseX + 5 <= mapX + mapW
                && mouseY - 4 >= mapY && mouseY + 5 <= mapY + mapH;
    }

    private boolean insideMap(double mouseX, double mouseY) {
        return mouseX >= mapX && mouseX <= mapX + mapW && mouseY >= mapY && mouseY <= mapY + mapH;
    }

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


    private static String distanceLabel(Destination destination) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return "";
        }
        double distance = Math.hypot(mc.player.getX() - destination.x(),
                mc.player.getZ() - destination.z());
        return Route.formatDistance(distance);
    }


    /**
     * Where one of the four control rows is drawn this frame: its laid-out top, lowered by the fold.
     * Only the drawing half: hit tests use the layout's positions, so a row halfway out is still where
     * the pointer found it. Rows rise from behind the bar and are clipped by the block, which reads as
     * the block growing upwards out of it.
     */
    private int foldRowY(int rowY) {
        return rowY + Math.round((1.0F - fold) * controlsBlockHeight());
    }

    /**
     * Travel-mode row: one button per mode, the active one filled with the selection colour; all
     * three shown because which one is on is the point of the row.
     */
    private void drawModeSelector(GuiGraphics graphics, int mouseX, int mouseY) {
        int y = foldRowY(modeY);
        graphics.drawString(font, modeCaption(), rowX,
                y + (MODE_ROW_HEIGHT - 8) / 2,
                UiMotion.fade(UiTheme.textMuted(), open * fold), false);

        TravelMode active = Navigation.mode();
        int x = modeButtonsX;
        int slot = 0;
        for (TravelMode mode : TravelMode.values()) {
            // A segmented control is a row of chips like any other, so each of them is drawn as one:
            // the only difference is that exactly one of them is always found to be on.
            chipSlot = CHIP_MODE + slot;
            control(graphics, mouseX, mouseY, x, y, modeButtonWidth, MODE_ROW_HEIGHT,
                    mode.label(), mode == active);
            slot++;
            x += modeButtonWidth + gap;
        }
    }

    private static String modeCaption() {
        return Component.translatable("screen.howtogo.mode").getString();
    }

    private static boolean samePlace(Destination a, Destination b) {
        return a != null && b != null && a.name().equals(b.name())
                && a.x() == b.x() && a.z() == b.z();
    }

    /**
     * What the readout says: the chosen plan, the one plan there is, or why there is no plan.
     * Two lines: with plans, which one is compared and its cost; without, the state line and the trip
     * or the router's sentence explaining why there is none. The walk offered because no line is worth
     * riding shows the reason instead of a cost -- see walkInsteadOfRiding.
     */
    private void drawPreviewSummary(GuiGraphics graphics) {
        if (previewTarget == null) {
            // The band is reserved either way, so it says what to do with it rather than sitting
            // empty -- the preview is the whole reason the picker no longer commits on one click.
            graphics.drawString(font, Component.translatable("screen.howtogo.preview_hint")
                            .getString(),
                    rowX, previewY, UiMotion.fade(UiTheme.textMuted(), open), false);
            return;
        }
        RouteOption selected = selectedPlan();
        if (selected != null) {
            // The state line names the comparison's chosen plan, in the same words as its row, so
            // the readout and the list are read as one statement rather than two.
            graphics.drawString(font, planTitle(planIndex, selected), rowX, previewY,
                    UiMotion.fade(UiTheme.textMuted(), open), false);
            // A walk offered in public transport's place is not a plan with a cost to compare: its
            // distance and time are on the row's own facts line, and the question this line answers
            // is why there is no ride. It reads as an explanation rather than as a warning -- a
            // destination being near is not a fault -- so it takes the state line's muted ink.
            boolean walkingInstead = walkInsteadOfRiding(selected);
            String text = walkingInstead
                    ? Component.translatable("hud.howtogo.close_enough_to_walk").getString()
                    : Component.translatable("screen.howtogo.preview_route",
                            Route.formatDistance(selected.length()),
                            Route.formatDuration(selected.seconds())).getString();
            graphics.drawString(font, font.plainSubstrByWidth(text, rowW),
                    rowX, previewY + PREVIEW_STATE_HEIGHT,
                    UiMotion.fade(walkingInstead ? UiTheme.textMuted() : UiTheme.accent(), open),
                    false);
            return;
        }
        TravelMode active = Navigation.mode();
        // The state line reads the same whether or not the plan worked, so a failed combination
        // still says which one failed -- the reason below it is the router's own sentence.
        graphics.drawString(font, Component.translatable("screen.howtogo.preview_state",
                        active.label()).getString(),
                rowX, previewY, UiMotion.fade(UiTheme.textMuted(), open), false);

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
        // A planned route reads as an answer, in the accent colour; a combination with no route at
        // all reads as a warning.
        graphics.drawString(font, font.plainSubstrByWidth(text, rowW),
                rowX, previewY + PREVIEW_STATE_HEIGHT,
                UiMotion.fade(present ? UiTheme.accent() : UiTheme.warn(), open), false);
    }

    /**
     * Whether the selected plan is the walk offered in transit's place because the destination is
     * near enough to walk to -- the reason the readout's second line gives instead of a cost.
     * Asked of the same distance the planner refuses a ride for (LinePlanner#closeEnoughToWalk), so
     * the sentence and the list cannot disagree.
     */
    private boolean walkInsteadOfRiding(RouteOption plan) {
        if (plan == null || plan.strategy() != RouteStrategy.WALK || previewTarget == null) {
            return false;
        }
        LocalPlayer player = Minecraft.getInstance().player;
        double x = player != null ? player.getX() : previewTarget.x();
        double z = player != null ? player.getZ() : previewTarget.z();
        return LinePlanner.closeEnoughToWalk(x, z, previewTarget.x(), previewTarget.z());
    }

    /**
     * The metric, the major-road preference and the voice switch, as buttons that take effect on the
     * spot. The first two re-plan from mouseClicked, since a setting that waits for a reopen is not a
     * control; speech re-plans nothing, so the voice switch is here rather than on a settings page.
     */
    private void drawPreferenceControls(GuiGraphics graphics, int mouseX, int mouseY) {
        RoutePreferences preferences = RoutePreferenceStore.preferences();
        int y = foldRowY(preferenceY);

        graphics.drawString(font, metricCaption(), rowX,
                y + (preferenceRowHeight - 8) / 2,
                UiMotion.fade(UiTheme.textMuted(), open * fold), false);

        chipSlot = CHIP_PREFERENCE;
        control(graphics, mouseX, mouseY, preferenceButtonX(0), y, preferenceButtonWidth,
                preferenceRowHeight, preferences.metric().label(), true);
        chipSlot = CHIP_PREFERENCE + 1;
        control(graphics, mouseX, mouseY, preferenceButtonX(1), y, preferenceButtonWidth,
                preferenceRowHeight, majorRoadsCaption(), preferences.preferMajorRoads());
        chipSlot = CHIP_PREFERENCE + 2;
        control(graphics, mouseX, mouseY, preferenceButtonX(2), y, preferenceButtonWidth,
                preferenceRowHeight, voiceCaption(), RoutePreferenceStore.voiceAnnouncements());
    }

    /**
     * One toggle per road class, lighting up the classes kept out. All six are always shown: a row
     * that grew as classes were avoided would answer the wrong question.
     */
    private void drawClassControls(GuiGraphics graphics, int mouseX, int mouseY) {
        RoutePreferences preferences = RoutePreferenceStore.preferences();
        int y = foldRowY(classY);

        graphics.drawString(font, avoidCaption(), rowX,
                y + (classRowHeight - 8) / 2,
                UiMotion.fade(UiTheme.textMuted(), open * fold), false);

        RoadClass[] classes = RoadClass.values();
        for (int i = 0; i < classes.length; i++) {
            chipSlot = CHIP_CLASS + i;
            control(graphics, mouseX, mouseY, classButtonX(i), y, classButtonWidth, classRowHeight,
                    classCaption(classes[i]), preferences.avoids(classes[i]));
        }
    }

    private static String classCaption(RoadClass roadClass) {
        return Component.translatable(
                "screen.howtogo.road_class." + roadClass.name().toLowerCase(Locale.ROOT)).getString();
    }

    /**
     * The transit guidance switch: whether a journey is guided by boarding and alighting rather than
     * turns. Its own row and caption because it belongs to one mode; nothing is re-planned, as with
     * the voice switch.
     */
    private void drawTransitControls(GuiGraphics graphics, int mouseX, int mouseY) {
        int y = foldRowY(transitY);
        graphics.drawString(font, transitCaption(), rowX,
                y + (transitRowHeight - 8) / 2,
                UiMotion.fade(UiTheme.textMuted(), open * fold), false);
        int[] rect = transitRect();
        chipSlot = CHIP_TRANSIT;
        control(graphics, mouseX, mouseY, rect[0], y, rect[2], transitRowHeight,
                boardOnlyCaption(), RoutePreferenceStore.transitBoardOnly());
    }

    /**
     * Draws one toggle in its on or off state.
     * A plain button rather than a checkbox, so there is no widget to keep in step with a value that
     * lives outside it. Its two states are mixed into the face -- and so into the label -- then the
     * cursor's face is mixed over by drawButton.
     */
    private void control(GuiGraphics graphics, int mouseX, int mouseY, int x, int y, int w, int h,
                         String label, boolean on) {
        boolean hovered = isInside(mouseX, mouseY, x, y, w, h);
        float chosen = chipSlides.get(chipSlot * 2 + 1).to(on ? 1.0F : 0.0F).eased();
        int resting = UiMotion.mix(UiTheme.button(), UiTheme.rowActiveTint(), chosen);
        int text = UiMotion.mix(UiTheme.textStrong(), UiTheme.accent(), chosen);
        // Faded by the fold here rather than inside the button: the fold belongs to this row of the
        // panel, while the same button draws the footer, which never folds.
        drawButton(graphics, x, y, w, h, font.plainSubstrByWidth(label, w - 4),
                UiMotion.fade(resting, fold), UiMotion.fade(UiTheme.buttonHover(), fold),
                UiMotion.fade(text, fold), hovered);
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

    private int[] transitRect() {
        int captionWidth = font.width(transitCaption()) + gap;
        return new int[]{rowX + captionWidth, transitY, Math.max(30, rowW - captionWidth)};
    }

    /**
     * The class toggle at the given index, as an x coordinate, derived from the same numbers the
     * drawing uses.
     */
    private int classButtonX(int index) {
        return rowX + font.width(avoidCaption()) + gap
                + index * (classButtonWidth + gap);
    }

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

    private boolean metricAt(double mouseX, double mouseY) {
        return isInside((int) mouseX, (int) mouseY, preferenceButtonX(0), preferenceY,
                preferenceButtonWidth, preferenceRowHeight);
    }

    private boolean majorRoadsAt(double mouseX, double mouseY) {
        return isInside((int) mouseX, (int) mouseY, preferenceButtonX(1), preferenceY,
                preferenceButtonWidth, preferenceRowHeight);
    }

    private boolean voiceAt(double mouseX, double mouseY) {
        return isInside((int) mouseX, (int) mouseY, preferenceButtonX(2), preferenceY,
                preferenceButtonWidth, preferenceRowHeight);
    }

    private boolean transitAt(double mouseX, double mouseY) {
        int[] rect = transitRect();
        return isInside((int) mouseX, (int) mouseY, rect[0], rect[1], rect[2], transitRowHeight);
    }


    /**
     * The one bar that folds the four control rows away and opens them again.
     * Always at the bottom of the group above the footer, and the only control for the fold in either
     * direction -- one that works one way is a trap. Drawn as text on a hover tint, summary on the
     * left and the hint right, with the summary cut to leave the hint its room.
     */
    private void drawControlsSummary(GuiGraphics graphics, int mouseX, int mouseY) {
        float lit = chipSlides.get(CHIP_BAR * 2).to(barAt(mouseX, mouseY) ? 1.0F : 0.0F).eased();
        if (lit > 0.004F) {
            graphics.fill(rowX, barY, rowX + rowW, barY + summaryRowHeight,
                    UiMotion.fade(UiTheme.rowHoverTint(), lit * open));
        }
        String hint = Component.translatable(controlsExpanded
                ? "screen.howtogo.controls.hide" : "screen.howtogo.controls.show").getString();
        int hintWidth = font.width(hint);
        int lineY = barY + (summaryRowHeight - 8) / 2;
        graphics.drawString(font,
                font.plainSubstrByWidth(controlsSummaryText(), rowW - 18 - hintWidth),
                rowX + 6, lineY, UiMotion.fade(UiTheme.textMuted(), open), false);
        graphics.drawString(font, hint, rowX + rowW - 6 - hintWidth, lineY,
                UiMotion.fade(UiTheme.text(), open), false);
    }

    /**
     * What the controls are set to, in the order the rows ask, so a folded panel still shows why the
     * plans look as they do. Only switches that are on are named.
     */
    private String controlsSummaryText() {
        RoutePreferences preferences = RoutePreferenceStore.preferences();
        List<String> parts = new ArrayList<>();
        parts.add(Navigation.mode().label());
        parts.add(preferences.metric().label());
        if (preferences.preferMajorRoads()) {
            parts.add(majorRoadsCaption());
        }
        if (preferences.avoidsAny()) {
            List<String> avoided = new ArrayList<>();
            for (RoadClass roadClass : RoadClass.values()) {
                if (preferences.avoids(roadClass)) {
                    avoided.add(classCaption(roadClass));
                }
            }
            parts.add(Component.translatable("screen.howtogo.avoiding",
                    String.join("/", avoided)).getString());
        }
        if (RoutePreferenceStore.transitBoardOnly()) {
            parts.add(Component.translatable("screen.howtogo.controls.board_only").getString());
        }
        return String.join(" · ", parts);
    }

    private boolean barAt(double mouseX, double mouseY) {
        return isInside((int) mouseX, (int) mouseY, rowX, barY, rowW, summaryRowHeight);
    }

    /**
     * The metric, major-road or voice toggle at the given index, as an x coordinate, derived from
     * the wider caption so the drawing and the hit tests agree.
     */
    private int preferenceButtonX(int index) {
        return rowX + Math.max(font.width(metricCaption()),
                font.width(majorRoadsCaption())) + gap + index * (preferenceButtonWidth + gap);
    }

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

    /**
     * The footer: start the chosen plan, cancel the choice, or leave the picker. Without plans the
     * old pair -- action or stop, and cancel -- stands, since there is no choice to cancel separately.
     */
    private void drawFooter(GuiGraphics graphics, int mouseX, int mouseY) {
        int count = footerButtonCount();
        int buttonWidth = footerButtonWidth(count);
        if (count == 3) {
            // A three-button footer divides the row into 80-unit buttons on the narrowest panel this
            // screen builds, so the start label is the short one: "Start navigating" is the long
            // form used where a single wide button has room for it.
            chipSlot = CHIP_FOOTER;
            drawButton(graphics, footerButtonX(0), footerY, buttonWidth,
                    Component.translatable("screen.howtogo.plan.start").getString(),
                    UiTheme.accent(), UiTheme.accentHover(), UiTheme.onSelection(),
                    isInside(mouseX, mouseY, footerButtonX(0), footerY, buttonWidth));
            chipSlot = CHIP_FOOTER + 1;
            drawButton(graphics, footerButtonX(1), footerY, buttonWidth,
                    Component.translatable("screen.howtogo.plan.clear").getString(),
                    UiTheme.button(), UiTheme.buttonHover(), UiTheme.onButton(),
                    isInside(mouseX, mouseY, footerButtonX(1), footerY, buttonWidth));
            chipSlot = CHIP_FOOTER + 2;
            drawButton(graphics, footerButtonX(2), footerY, buttonWidth,
                    Component.translatable("gui.cancel").getString(),
                    UiTheme.button(), UiTheme.buttonHover(), UiTheme.onButton(),
                    isInside(mouseX, mouseY, footerButtonX(2), footerY, buttonWidth));
            return;
        }
        Destination action = actionDestination();
        boolean navigating = Navigation.target() != null;

        int leftX = rowX;
        int rightX = count == 2 ? leftX + buttonWidth + PADDING : leftX;

        // Cancel is the last slot in every division, so a footer that loses or gains a button does
        // not hand its hover value to a different label.
        if (action != null) {
            // Offered whenever something is picked, whether or not it routes: a trip that cannot be
            // planned is still the one the player asked for, and the preview line above has already
            // said why it will not help.
            chipSlot = CHIP_FOOTER;
            drawButton(graphics, leftX, footerY, buttonWidth,
                    Component.translatable("screen.howtogo.go_here").getString(),
                    UiTheme.accent(), UiTheme.accentHover(), UiTheme.onSelection(),
                    isInside(mouseX, mouseY, leftX, footerY, buttonWidth));
        } else if (navigating) {
            chipSlot = CHIP_FOOTER;
            drawButton(graphics, leftX, footerY, buttonWidth,
                    Component.translatable("screen.howtogo.stop").getString(),
                    UiTheme.danger(), UiTheme.dangerHover(), UiTheme.onSelection(),
                    isInside(mouseX, mouseY, leftX, footerY, buttonWidth));
        }
        chipSlot = CHIP_FOOTER + 2;
        drawButton(graphics, rightX, footerY, buttonWidth,
                Component.translatable("gui.cancel").getString(),
                UiTheme.button(), UiTheme.buttonHover(), UiTheme.onButton(),
                isInside(mouseX, mouseY, rightX, footerY, buttonWidth));
    }

    /**
     * How many footer buttons there are: three while plans are compared, two otherwise, one when
     * there is nothing to act on. Asked by the drawing and the click test alike.
     */
    private int footerButtonCount() {
        if (!plans.isEmpty()) {
            return 3;
        }
        return actionDestination() != null || Navigation.target() != null ? 2 : 1;
    }

    /**
     * Width of each footer button so buttons and gaps add up to rowW; the three-button case is the
     * two-button rule with two gaps, so the left button never moves.
     */
    private int footerButtonWidth(int count) {
        return count == 1 ? rowW : Math.max(1, (rowW - PADDING * (count - 1)) / count);
    }

    /**
     * Left edge of a footer button: the same division its width came from, asked by both the
     * drawing and the click test.
     */
    private int footerButtonX(int index) {
        return rowX + index * (footerButtonWidth(footerButtonCount()) + PADDING);
    }

    private Destination actionDestination() {
        if (pendingPick != null) {
            int x = (int) Math.round(pendingPick[0]);
            int z = (int) Math.round(pendingPick[1]);
            return new Destination("(" + x + ", " + z + ")", x, playerHeight(), z, "map");
        }
        return previewTarget;
    }

    /**
     * A footer button.
     * The label colour follows the face -- white on accent and danger, the panel ink on neutral -- and
     * is not interpolated, since legibility was chosen against the resting colour. The hover lives
     * here for every control in the panel, mixed over the slot's own slide.
     */
    private void drawButton(GuiGraphics graphics, int x, int y, int w,
                            String label, int base, int hover, int textColor, boolean hovered) {
        drawButton(graphics, x, y, w, FOOTER_HEIGHT, label, base, hover, textColor, hovered);
    }

    private void drawButton(GuiGraphics graphics, int x, int y, int w, int h,
                            String label, int base, int hover, int textColor, boolean hovered) {
        float lit = chipSlides.get(chipSlot * 2).to(hovered ? 1.0F : 0.0F).eased();
        HudDraw.fillRounded(graphics, x, y, x + w, y + h, 3,
                UiMotion.fade(UiMotion.mix(base, hover, lit), open));
        graphics.drawString(font, label, x + (w - font.width(label)) / 2,
                y + (h - 8) / 2, UiMotion.fade(textColor, open), false);
    }

    private static boolean isInside(int mouseX, int mouseY, int x, int y, int w) {
        return isInside(mouseX, mouseY, x, y, w, FOOTER_HEIGHT);
    }

    private static boolean isInside(int mouseX, int mouseY, int x, int y, int w, int h) {
        return mouseX >= x && mouseX <= x + w && mouseY >= y && mouseY <= y + h;
    }


    @Override
    // 1.21.9 turned key input into a record; the body only needs the key code, so it is unpacked on
    // entry and reads the same on either side. ESC is intercepted here, before the screen's own
    // handler, which closes the screen: it walks out one layer at a time -- the details card, then
    // the choice it was made for, and only then the picker itself. A picker that closed on the first
    // ESC after a place was chosen would take the choice away as if it had never been made.
    //? if >=1.21.9 {
    /*public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        int keyCode = event.key();
    *///?} else {
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
    //?}
        if (keyCode == InputConstants.KEY_ESCAPE) {
            if (detailsPlace != null) {
                setDetailsPlace(null);
                return true;
            }
            if (previewTarget != null || pendingPick != null) {
                clearSelection();
                return true;
            }
        }
        if (!controlsExpanded && !plans.isEmpty()) {
            // 1/2/3 choose a plan. Number keys rather than arrows because the band is at most three
            // rows and already on screen: one press per plan instead of walking a cursor. Only while
            // the band is visible -- a key that selects something hidden would change what the
            // footer's start button starts without showing the change.
            int chosen = chosenPlanKey(keyCode);
            if (chosen >= 0) {
                planIndex = chosen;
                return true;
            }
        }
        //? if >=1.21.9 {
        /*return super.keyPressed(event);
        *///?} else {
        return super.keyPressed(keyCode, scanCode, modifiers);
        //?}
    }

    /**
     * The plan index a number key selects, or -1. Out-of-range numbers are ignored rather than
     * clamped: a key aimed at a list that is not there.
     */
    private int chosenPlanKey(int keyCode) {
        int index = keyCode - InputConstants.KEY_1;
        return index >= 0 && index < plans.size() ? index : -1;
    }

    @Override
    //? if >=1.21.9 {
    /*public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        double mouseX = event.x();
        double mouseY = event.y();
        int button = event.button();
    *///?} else {
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
    //?}
        if (button != MouseButton.LEFT) {
            //? if >=1.21.9 {
            /*return super.mouseClicked(new MouseButtonEvent(mouseX, mouseY,
                    new MouseButtonInfo(button, 0)), false);
            *///?} else {
            return super.mouseClicked(mouseX, mouseY, button);
            //?}
        }
        // The details card comes before everything else: it lies inside the list column, so a click
        // on one of its controls would otherwise be read as a click on the row behind it, and a
        // click on the card's empty space as the beginning of a map drag or a pin below it. It is
        // drawn whenever a place is selected, whether or not the plans are showing, so there is no
        // state in which it must be skipped.
        if (detailsPlace != null) {
            PlaceDetailPanel.Action action = detailPanel.actionAt(mouseX, mouseY);
            if (action != PlaceDetailPanel.Action.NONE) {
                handleDetailAction(action);
                return true;
            }
            if (detailPanel.contains(mouseX, mouseY)) {
                return true;
            }
        }
        if (insideMap(mouseX, mouseY)) {
            if (insideRecenter(mouseX, mouseY)) {
                recenterOnPlayer();
                return true;
            }
            // A place marker chooses its place on one click, exactly as its row in the list does.
            // This used to take two clicks and let the first fall through to a pin; the list has
            // always taken one, and a marker that needed two was a rule nothing explained. The
            // place's details open with it, which is the point of the one click.
            PlaceHit hit = placeAt(mouseX, mouseY);
            if (hit != null) {
                pendingPick = null;
                selectPreview(hit.destination());
                return true;
            }
            // Remember the press only. The pin is dropped on release, so panning the map does not
            // leave a pin behind at the end of every drag. Grabbing the map stops a coast in its
            // tracks: the hand on the map is the authority, and a view that keeps drifting under it
            // is the one thing that makes a drag feel out of control.
            mapPressed = true;
            mapPressX = mouseX;
            mapPressY = mouseY;
            momentumX = 0;
            momentumZ = 0;
            lastDragNanos = 0;
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
        if (!controlsExpanded && hoveredPlan >= 0 && hoveredPlan < plans.size()) {
            // Chooses a plan, does not start it: the footer's start button is what commits, and the
            // map and the readout follow the choice on the next frame. Re-planning here would
            // answer a question the click did not ask.
            planIndex = hoveredPlan;
            return true;
        }

        // The bar is the one control for the fold, in both directions: while the rows are folded it
        // opens them, while they are open it folds them away again and gives the plans their room
        // back. It sits under the rows in both forms and never overlaps one, so it is tested before
        // them rather than as an else branch of the open state.
        if (barAt(mouseX, mouseY)) {
            setControlsExpanded(!controlsExpanded);
            return true;
        }

        if (controlsExpanded) {
            TravelMode clickedMode = modeAt(mouseX, mouseY);
            if (clickedMode != null) {
                // Switches the mode and re-plans the plans in one go, so the band and the map redraw
                // with the routes the mode implies while the choice is still being made. The rows
                // fold afterwards (see replanAndShowPlans) so the answer is not hidden by them.
                Navigation.setMode(clickedMode);
                replanAndShowPlans();
                return true;
            }
            if (metricAt(mouseX, mouseY)) {
                RoutePreferenceStore.toggleMetric();
                replanAndShowPlans();
                return true;
            }
            if (majorRoadsAt(mouseX, mouseY)) {
                RoutePreferenceStore.togglePreferMajorRoads();
                replanAndShowPlans();
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
                replanAndShowPlans();
                return true;
            }
        }

        Destination action = actionDestination();
        boolean navigating = Navigation.target() != null;
        if (!plans.isEmpty()) {
            int buttonWidth = footerButtonWidth(3);
            if (isInside((int) mouseX, (int) mouseY, footerButtonX(0), footerY, buttonWidth)) {
                // The chosen plan, not "the fastest one": the row the player picked is the row the
                // readout named and the map drew in the accent colour.
                commitPlan(planIndex);
                return true;
            }
            if (isInside((int) mouseX, (int) mouseY, footerButtonX(1), footerY, buttonWidth)) {
                clearSelection();
                return true;
            }
            if (isInside((int) mouseX, (int) mouseY, footerButtonX(2), footerY, buttonWidth)) {
                onClose();
                return true;
            }
            //? if >=1.21.9 {
            /*return super.mouseClicked(new MouseButtonEvent(mouseX, mouseY,
                    new MouseButtonInfo(button, 0)), false);
            *///?} else {
            return super.mouseClicked(mouseX, mouseY, button);
            //?}
        }
        int buttonWidth = footerButtonWidth(footerButtonCount());
        int leftX = rowX;
        int rightX = footerButtonCount() == 2 ? leftX + buttonWidth + PADDING : leftX;

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
        //? if >=1.21.9 {
            /*return super.mouseClicked(new MouseButtonEvent(mouseX, mouseY,
                    new MouseButtonInfo(button, 0)), false);
            *///?} else {
            return super.mouseClicked(mouseX, mouseY, button);
            //?}
    }

    /**
     * Chooses a destination to compare plans for, and opens its details.
     * A real place opens the card; a map-picked point has no record, so it closes the card rather than
     * opening an empty one. The controls fold with the choice, or the plans they produced would be
     * hidden; the bar opens them again.
     */
    private void selectPreview(Destination destination) {
        previewTarget = destination;
        setDetailsPlace(Destinations.isPlace(destination) ? destination : null);
        replanAndShowPlans();
        // After the relayout, not before: the fit reads the shape of the map frame, and folding the
        // controls is what changed its height. Only a new choice aims the view -- a control that
        // re-plans the same place must not yank it back from wherever the player has panned to.
        aimAtPlans();
    }

    /**
     * Forgets the chosen destination and gives the panel back to the places and the open controls:
     * what the footer's cancel and ESC-after-the-card do. The map pin and the old single preview go
     * with the choice; the details card belongs to the places and is left alone.
     */
    private void clearSelection() {
        plans = List.of();
        planIndex = 0;
        preview = null;
        previewTarget = null;
        pendingPick = null;
        controlsExpanded = true;
        relayout();
    }

    /**
     * Opens or closes the details card, taking the list's band when it opens. Compared by value,
     * because sources rebuild Destination instances every frame and identity would re-lay the list.
     */
    private void setDetailsPlace(Destination place) {
        Destination next = Destinations.isPlace(place) ? place : null;
        boolean wasOpen = detailsPlace != null;
        if (wasOpen == (next != null) && (next == null || sameDestination(detailsPlace, next))) {
            return;
        }
        detailsPlace = next;
        relayoutList();
    }

    private void handleDetailAction(PlaceDetailPanel.Action action) {
        Destination place = detailsPlace;
        if (place == null) {
            return;
        }
        switch (action) {
            case FAVORITE -> PlaceDetailStore.toggleFavorite(place);
            // Plan one while there are plans. The card is beside the plan band, not over it, so this
            // is reachable in the folded state; its contract is that a navigate press commits the
            // best plan on offer rather than planning a fourth one behind the band. Without plans
            // the old behaviour stands: hand the destination over and let the live planning do what
            // it always did.
            case NAVIGATE -> {
                if (!plans.isEmpty()) {
                    commitPlan(0);
                } else {
                    commit(place);
                }
            }
            case EDIT -> editDetailsPlace(place);
            case BACK -> setDetailsPlace(null);
            default -> {
                // NONE cannot arrive here: actionAt only reports a control that was drawn. DELETE is
                // not offered by a details card any more: a place is deleted in the world map's edit
                // mode, which is where the node, the undo history and the roads attached to it live.
            }
        }
    }

    /**
     * Opens the editor for the selected place: the player's own place is a road-network node and gets
     * the place editor; every other place can only have its detail record edited.
     */
    private void editDetailsPlace(Destination place) {
        int nodeId = manualNodeId(place);
        if (nodeId >= 0 && PlaceSources.isManual(place.source())) {
            RoadEditSession.openPlaceEditorFromPanel(nodeId);
            return;
        }
        PlaceDetailScreen.openForStore(Screens.current(), place);
    }

    /**
     * Drops a selection whose place a rebuilt list no longer offers, before the layout asks the
     * details card for its height; called from init, where the list is rebuilt. A place that vanishes
     * mid-session is noticed when the snapshot is next taken, not by polling every source each frame.
     */
    private void dropVanishedSelection() {
        if (detailsPlace != null && !all.contains(detailsPlace)) {
            // The layout below is computed after this runs, so the card simply does not open again.
            detailsPlace = null;
        }
        if (Destinations.isPlace(previewTarget) && !all.contains(previewTarget)) {
            previewTarget = null;
            preview = null;
            // Nothing is being compared any more, so the panel goes back to the state it opens in:
            // the four control rows, open. The layout below is measured after this runs.
            controlsExpanded = true;
        }
    }

    /**
     * The road node id a manual place stands for, or -1 when its id is not a node id. Parsed
     * rather than trusted, so a malformed record falls back to the details dialog.
     */
    private static int manualNodeId(Destination place) {
        if (place == null || place.placeId() == null) {
            return -1;
        }
        try {
            return Integer.parseInt(place.placeId().trim());
        } catch (NumberFormatException notANode) {
            return -1;
        }
    }

    private static boolean sameDestination(Destination a, Destination b) {
        return a == null ? b == null : a.equals(b);
    }

    /**
     * Re-plans the plans for the current destination, mode and preferences.
     * Called on a destination choice, from every control that changes one, and from init. The chosen
     * row resets to the first plan on every re-plan, since rows are time-sorted and keeping the index
     * would silently switch the picked plan. When alternatives are empty, the old single preview is
     * planned too: it carries the router's no-route sentence.
     */
    private void replanPlans() {
        planIndex = 0;
        if (previewTarget == null) {
            plans = List.of();
            preview = null;
            return;
        }
        plans = Navigation.plans(previewTarget);
        preview = plans.isEmpty()
                ? Navigation.preview(previewTarget, Navigation.mode(),
                        RoutePreferenceStore.preferences())
                : null;
    }

    /**
     * Starts the trip along the chosen plan, handed over whole rather than the destination alone --
     * planning again would answer a question the player did not ask. The index is clamped defensively.
     */
    private void commitPlan(int index) {
        if (previewTarget == null || plans.isEmpty()) {
            return;
        }
        RouteOption plan = plans.get(Math.max(0, Math.min(index, plans.size() - 1)));
        pendingPick = null;
        Navigation.setTarget(previewTarget, plan);
        onClose();
    }

    /**
     * Starts navigating to the chosen destination, planning from the destination alone: the path kept
     * for when there is no comparison, so the live planning decides and can fall back to walking.
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
    //? if >=1.21.9 {
    /*public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        double mouseX = event.x();
        double mouseY = event.y();
        int button = event.button();
    *///?} else {
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
    //?}
        if (button == MouseButton.LEFT && mapPressed) {
            // Move the centre opposite to the drag, so the map follows the cursor. Both the shown
            // value and the aim move: during a drag the map is the player's hand, so there is nothing
            // to smooth -- what the drag leaves behind is the speed, which becomes the coast.
            double scale = mapW / (viewRadius * 2.0);
            double blocksX = dragX / scale;
            double blocksZ = dragY / scale;
            mapCenterX -= blocksX;
            mapCenterZ -= blocksZ;
            targetCenterX = mapCenterX;
            targetCenterZ = mapCenterZ;
            long now = System.nanoTime();
            if (lastDragNanos != 0) {
                double seconds = (now - lastDragNanos) / 1.0E9;
                // A step under a millisecond is the same frame's mouse events, not a speed: dividing
                // by it would turn one pixel into a throw.
                if (seconds > 0.001) {
                    momentumX = -blocksX / seconds;
                    momentumZ = -blocksZ / seconds;
                }
            }
            lastDragNanos = now;
            return true;
        }
        //? if >=1.21.9 {
            /*return super.mouseDragged(new MouseButtonEvent(mouseX, mouseY,
                    new MouseButtonInfo(button, 0)), dragX, dragY);
            *///?} else {
            return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
            //?}
    }

    @Override
    //? if >=1.21.9 {
    /*public boolean mouseReleased(MouseButtonEvent event) {
        double mouseX = event.x();
        double mouseY = event.y();
        int button = event.button();
    *///?} else {
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
    //?}
        if (button == MouseButton.LEFT && mapPressed) {
            mapPressed = false;
            // A throw that ended with the hand still has no speed: the last drag step is only a
            // velocity if it was the last thing that happened, so a press and release with the
            // cursor held still must not send the map anywhere.
            if (System.nanoTime() - lastDragNanos > 120_000_000L) {
                momentumX = 0;
                momentumZ = 0;
            }
            lastDragNanos = 0;
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
        //? if >=1.21.9 {
            /*return super.mouseReleased(new MouseButtonEvent(mouseX, mouseY,
                    new MouseButtonInfo(button, 0)));
            *///?} else {
            return super.mouseReleased(mouseX, mouseY, button);
            //?}
    }

    @Override
    // 1.20.2 split the wheel delta into a horizontal and a vertical component; 1.20.1 passes one.
    // Every use in this mod reads the vertical one, so only the signature and the super call differ.
    //? if >=1.20.2 {
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
    //?} else {
    /*public boolean mouseScrolled(double mouseX, double mouseY, double scrollY) {
    *///?}
        if (insideMap(mouseX, mouseY)) {
            // Zoom about the centre, aimed rather than applied: the radius is chased by
            // stepCamera, so a wheel notch is a movement instead of a cut. The centre is
            // pinned to where it is so the travel is pure zoom.
            double factor = scrollY > 0 ? 0.85 : 1.0 / 0.85;
            targetViewRadius = Math.max(MIN_VIEW_RADIUS,
                    Math.min(MAX_VIEW_RADIUS, targetViewRadius * factor));
            targetCenterX = mapCenterX;
            targetCenterZ = mapCenterZ;
            momentumX = 0;
            momentumZ = 0;
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

    private boolean insideRecenter(double mouseX, double mouseY) {
        return mouseX >= mapX + mapW - RECENTER_SIZE - 2 && mouseX <= mapX + mapW - 2
                && mouseY >= mapY + 2 && mouseY <= mapY + RECENTER_SIZE + 2;
    }

    @Override
    public void onClose() {
        Screens.set(parent);
    }

}
