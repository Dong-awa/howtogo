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
 *
 * <p>The screen the main screen's destination button (K) and {@code /howtogo terminal} open, and the
 * one the old navigator item used to open before the mod stopped registering items.
 *
 * <p>The map is drawn from this mod's own road data and can be clicked to choose a point, the way
 * tapping a map works in a navigation app. Picking is two-step -- a click drops a pin, a second
 * click on the action button commits -- so a stray click does not silently start a trip.
 *
 * <h2>Details of a place</h2>
 * A list row and a place marker both choose on a single click, and a chosen place brings its details
 * with it: the list keeps the top of its band for its rows and the {@link PlaceDetailPanel} takes the
 * bottom, so what the player wrote and the controls that act on it are the same click away as the
 * route. A point picked on the map is not a place -- there is no record behind it -- so it keeps the
 * pin and the two-step commit and shows no card. The card is drawn rather than given a screen of its
 * own: a modal over a picker whose whole point is comparing places would hide the comparison.
 *
 * <h2>Where the plans live</h2>
 * Comparing plans does not take the list band -- the places stay visible, which is the point of
 * choosing from them. The plans are a band of their own under the readout, paid for by folding the
 * four control rows into a one-line bar. That bar is the one control that folds and opens them:
 * choosing a place folds them so its plans can be seen, and cancelling the choice opens them again.
 * It is the only control for the fold -- in both directions -- and the map and the list change
 * height with it rather than after it. See {@link #bottomGroupHeight} for how both forms are
 * measured and {@link #placeBands} for how the bands are then placed.
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
     * The details card's share of the list band while a place is selected, in per cent.
     *
     * <p>"About half": enough of the card to read a description in, while the list above it keeps
     * showing the rows around the selected place. Below {@link #MIN_DETAILS_HEIGHT} the share is
     * raised to it, because a card shorter than that cannot lay its own controls out at all.
     */
    private static final int DETAILS_BAND_SHARE = 55;
    /**
     * Shortest a details card may be and still draw its title, source and action row inside itself.
     *
     * <p>{@link PlaceDetailPanel#preferredHeight} bottoms out at this figure for the same reason. A
     * band too short to give the card this much and the list a row as well is given to the card
     * whole: a card squeezed below this point would paint its buttons over the list above them.
     */
    private static final int MIN_DETAILS_HEIGHT = 84;
    /**
     * Shortest a card may be squeezed to and still keep a list row above it.
     *
     * <p>Less than {@link #MIN_DETAILS_HEIGHT}, because the band is often too short for a full card
     * and dropping the list to keep one would cost more than a clipped description: this is the
     * height at which the title, the source pill and the four-button action row still sit inside the
     * card's own edge. Below it the card takes the whole band and the list waits behind the
     * panel's collapse button.
     */
    private static final int MIN_DETAILS_COMPACT = 70;
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
    /**
     * Height of one plan row, and the tighter height a short window gets.
     *
     * <p>22 is two lines of the font with a unit of breathing room either side, which the results
     * band needs because a plan is a title line and a facts line. 18 is not two comfortable lines
     * but still two, and it is the floor: below this the facts line would be clipped through its
     * middle, and a clipped row reads as broken rather than as smaller. The band's height is the
     * number of plans times this figure, so a short window shows shorter rows rather than fewer of
     * them -- three plans are the whole point of the band.
     */
    private static final int PLAN_ROW_HEIGHT = 22;
    private static final int PLAN_ROW_PACKED = 18;
    /** Most plan rows the band may hold; the planner never offers more than three. */
    private static final int MAX_PLAN_ROWS = 3;
    /**
     * Height of the one bar that folds the four control rows away and opens them again.
     *
     * <p>One control, always in the same place, rather than the two there used to be: a button row
     * saying "路线选项" while the rows were folded, and the folded summary line above it, which also
     * opened them. Both did the same job, so the button row is gone and the bar itself is the
     * control -- it carries the summary on its left and a hint at its right saying what pressing it
     * does. While the rows are open it stays where it is and says how to fold them again, which is
     * what makes the fold reachable in both directions without a second row.
     *
     * <p>The packed figure is the floor the whole bottom group is measured from when the window is
     * short, and it is still tall enough to click.
     */
    private static final int CONTROLS_BAR_HEIGHT = 16;
    private static final int CONTROLS_BAR_PACKED = 14;
    private static final int CORNER_RADIUS = 5;
    private static final int MIN_MAP_HEIGHT = 42;

    /** Half the world span shown in the picker map, in blocks. Zoomable. */
    private static final double DEFAULT_VIEW_RADIUS = 260.0;
    private static final double MIN_VIEW_RADIUS = 40.0;
    private static final double MAX_VIEW_RADIUS = 3000.0;

    private static final int RECENTER_SIZE = 11;

    /*
     * The controls' motion is a pool indexed by slot rather than by object: a chip is a rectangle
     * both the drawing and the hit test rebuild from the layout every frame, so there is nothing to
     * hang a slide on. Each row of controls owns a block far wider than any row can need, so a row
     * that grows a chip never collides with the row below it. Two slides per slot -- the cursor
     * arriving and the control being switched on -- which is why every access is doubled.
     */
    private static final int CHIP_MODE = 0;
    private static final int CHIP_PREFERENCE = 16;
    private static final int CHIP_CLASS = 32;
    private static final int CHIP_TRANSIT = 48;
    private static final int CHIP_BAR = 64;
    private static final int CHIP_FOOTER = 80;

    private final Screen parent;

    /**
     * The opacity the whole window is drawn at this frame: the entrance and the exit multiplied.
     *
     * <p>Advanced once at the top of {@link #render} and read by every pass below it, so a screen
     * arriving fades as one surface rather than in the order its bands happen to be drawn -- and so
     * that the exit, which is the same value falling again, needs no second code path.
     */
    private float open = 1.0F;

    /**
     * The opacity of the window's own surface this frame: the dimming, the sheet and the empty map.
     *
     * <p>The same number as {@link #open} everywhere except during a hand-off to another page, where
     * the surface is held at full while the content cross-fades -- see
     * {@link UiMotion.ScreenState#surface()}. The empty map counts as surface rather than as content:
     * a map that faded would be a hole in the sheet with the world showing through it.
     */
    private float surface = 1.0F;

    /**
     * How far the four control rows are open this frame, from 0 folded to 1 open.
     *
     * <p>A slide rather than a boolean because folding is a change of layout, and a layout that
     * changes in one frame reads as a glitch. Its target is re-read from {@link #controlsExpanded}
     * every frame instead of being written where that state is: the bar, a chosen place, a
     * cancelled one and {@code init} all write the state directly, and none of them has to know
     * that the fold animates. The value is also what the vertical layout is measured from (see
     * {@link #relayoutForFold}), because the band above the controls has to grow as they fold, not
     * after they have finished folding.
     */
    private float fold = 1.0F;

    /** The slide {@link #fold} is read off, resting open because that is how the panel opens. */
    private final UiMotion.Slide controlsFold =
            new UiMotion.Slide(UiMotion.Easing.IN_OUT_CUBIC, 1.0F, UiMotion.EXPAND);

    /** Every place row's cursor arrival (even slot) and its picked-out state (the next slot). */
    private final UiMotion.Slides rowSlides = new UiMotion.Slides();

    /** The same pair for the plan rows, which are hovered and chosen the same way. */
    private final UiMotion.Slides planSlides = new UiMotion.Slides();

    /** Every control's cursor arrival and switched-on state, in the blocks the constants name. */
    private final UiMotion.Slides chipSlides = new UiMotion.Slides();

    /**
     * The slot of the control being drawn this instant.
     *
     * <p>A field for the reason {@code SettingsScreen} keeps its pointer position in one: the
     * easing has to happen inside {@link #drawButton}, which takes no slot of its own, and the only
     * alternative is another parameter on every control in the panel. Each row writes its own slot
     * on the line before it draws, so a slot is never read across two controls.
     */
    private int chipSlot;

    /**
     * How far the row being drawn has arrived, for {@link #drawNameLine}.
     *
     * <p>The name is the one part of a row that another method writes, and it is written in colours
     * the row itself picks; a field rather than a parameter for the same reason as
     * {@link #chipSlot}.
     */
    private float rowArrival = 1.0F;

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

    /** The side of a row's colour marker, and the room it and its gap take out of the row's text. */
    private static final int ROW_MARKER = 6;
    private static final int ROW_MARKER_STEP = 9;

    /** The destination the preview belongs to, or null when nothing has been chosen yet. */
    private Destination previewTarget;
    /** The plan for {@link #previewTarget}, or null when there is nothing to preview. */
    private Navigation.RoutePreview preview;

    /**
     * The plans the picker is offering, quickest first, or an empty list.
     *
     * <p>Drawn as a band of its own under the readout, not in the place list's band: the list has to
     * stay visible while a route is chosen -- covering it with the results was the whole complaint
     * this layout answers. See {@link #drawPlanList} for the band and {@link #bottomGroupHeight} for
     * how its height is reserved.
     *
     * <p>Held in band order: {@link #planIndex} is an index into this list, and the map, the readout
     * and the footer all read the same index, so the line picked out and the line started cannot
     * disagree.
     */
    private List<RouteOption> plans = List.of();
    /** Which of {@link #plans} the player has picked out; the map and the readout follow it. */
    private int planIndex;
    /**
     * The plan row the cursor is over, or -1 while it is over none.
     *
     * <p>Worked out by the drawing and read by {@code mouseClicked}, the same arrangement the place
     * rows use: it is the single place the row geometry is turned into a hit result, so the drawn
     * rows and the clickable rows cannot drift apart.
     */
    private int hoveredPlan = -1;

    /**
     * Whether the four control rows are open, or folded under the one-line bar that replaces them.
     *
     * <p>Open whenever there is no destination to compare plans for, because then the controls are
     * the bottom of the panel and the collapsed bar would hide what the panel is for. Choosing a
     * destination folds them so the plans it produces can be seen; the bar, which is the one
     * control for both directions, opens them again. See {@link #setControlsExpanded} for why the
     * layout is measured again rather than the rows simply not being drawn.
     */
    private boolean controlsExpanded = true;

    /** Height of one plan row in the current layout: {@link #PLAN_ROW_HEIGHT} or its packed twin. */
    private int planRowSize;
    /** Top of the plan band: the first line under the preview readout. */
    private int planTop;
    /** Top of the four control rows, in the place the open layout gives them. */
    private int controlsY;
    /** Height of the toggle bar in the current layout. */
    private int summaryRowHeight;
    /** Top of the bar that folds the four control rows and opens them again. */
    private int barY;
    /** The block the fold trades: its top and bottom are what the band above is measured from. */
    private int stackTop;
    private int stackBottom;
    /**
     * The fold value the current geometry was measured from.
     *
     * <p>Layout is a function of the fold while it is moving (see {@link #relayoutForFold}), and
     * this is what says whether the frame in hand has to measure it again. A value of two is a
     * sentinel no eased slide can produce, so the first frame always measures.
     */
    private float laidOutFold = 2.0F;

    /**
     * The plan band's height this frame, in pixels, followed rather than set.
     *
     * <p>The band's height is the number of plans times the row height, and the number of plans can
     * change under the screen without the fold moving at all -- choosing a second place while one is
     * already being compared is the ordinary case, and the planner offers one, two or three rows.
     * Assigned outright, that is a jump of up to 44 pixels in the band the map and the list are
     * measured against, which is the same visible fault the fold had. So it is followed: the block
     * height the layout reads is this value, and every frame it moves the layout is re-measured (see
     * {@link #settlePlansBlock}).
     */
    private float plansBlockShown;

    /**
     * How quickly the plan band catches up with a change in the number of plans, in seconds.
     *
     * <p>Shorter than a fold on purpose: this is a correction rather than a journey, and the thing
     * being corrected is a number of rows, so it has to be over before the player reads the new
     * rows. Same shape as {@link UiMotion#FOLLOW_SECONDS} for the same reason -- it chases a target
     * that may move again rather than travelling to a fixed one.
     */
    private static final float PLAN_BLOCK_FOLLOW_SECONDS = 0.09F;

    /**
     * The details card, and the place it is about, or null while the list has the whole band.
     *
     * <p>Not a widget and not a screen: {@link PlaceDetailPanel} only draws and reports clicks, so
     * it lives as long as this screen does and is told where the list band left room for it. A
     * separate {@code PlaceDetails} value is deliberately not kept here -- the store is asked every
     * frame, so a favourite toggled from anywhere, or from the card itself, shows up on the next one.
     */
    private final PlaceDetailPanel detailPanel = new PlaceDetailPanel();
    private Destination detailsPlace;

    /** Height of the details card, and where its top edge is; zero when no card is shown. */
    private int detailsHeight;
    private int detailsY;

    /** The list band the vertical layout produced, before the details card took its share of it. */
    private int listBandHeight;

    /**
     * The places the current search matched through their description or tags.
     *
     * <p>Kept beside {@link #shown} rather than tested in the drawing, because the drawing would
     * have to look up the store again and would still not know whether the <em>name</em> also
     * matched. A row in this set shows the one-line summary that explains the hit.
     */
    private final Set<Destination> descriptionMatches = new HashSet<>();

    /**
     * World position at the centre of the picker map.
     *
     * <p>Kept separate from the player's position so the map can be panned around; the player
     * marker is then drawn wherever they actually are, on or off the visible area.
     */
    private double mapCenterX;
    private double mapCenterZ;
    private double viewRadius = DEFAULT_VIEW_RADIUS;

    /*
     * The camera is steered rather than set: the two pairs of numbers below are the same statement
     * "where the view is" at two moments in time.
     *
     * During a drag the shown value is moved directly by the cursor, because a map that lags the hand
     * holding it is not smooth, it is late -- the whole point of a drag is that it is the player's.
     * What the drag leaves behind is momentum: on release the target keeps travelling and decays, and
     * the shown value chases it, which is the "ring" a map has when it is thrown. Everything else --
     * the wheel, the back-to-me button, fitting the view to a planned route -- is aimed: the target
     * is set and the shown value travels to it over {@link #CAMERA_FOLLOW_SECONDS}.
     */
    private double targetCenterX;
    private double targetCenterZ;
    private double targetViewRadius = DEFAULT_VIEW_RADIUS;
    /** Blocks per second the view is still coasting at, after a drag; zero when it has stopped. */
    private double momentumX;
    private double momentumZ;
    /** When the last drag step arrived, for turning pixel deltas into blocks per second. */
    private long lastDragNanos;
    /**
     * How fast the shown camera catches up with an aimed one, in seconds.
     *
     * <p>Short: long enough that a wheel notch or a route fit is a movement rather than a cut, short
     * enough that a player who lets go of the map is not left watching it settle.
     */
    private static final float CAMERA_FOLLOW_SECONDS = 0.075F;
    /** Time constant of the post-drag coast; a throw is over in about a third of a second. */
    private static final float CAMERA_INERTIA_SECONDS = 0.16F;
    /** Zoom is chased on a logarithmic radius, so this is the snap in that space (about 0.1%). */
    private static final double CAMERA_RADIUS_SNAP = 0.001;

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
        dropVanishedSelection();
        scroll = 0;
        // With nothing chosen the bottom of the panel is the controls themselves, so they are open;
        // a resize of a panel that had a choice keeps the state the player left it in, because the
        // resize is not a choice.
        if (previewTarget == null) {
            controlsExpanded = true;
        }
        // The window may have been resized since the choice was made, and the road data may have
        // moved on with it. Re-plan rather than trusting a list measured in pixels ago -- the
        // navigation's plan cache makes that cheap when the question itself did not change, and
        // with nothing chosen this simply clears the plans back to their empty state.
        replanPlans();

        computePanelLayout();
        // A screen is measured, not animated, when it is opened or resized: the plan band starts at
        // the height the plans on offer ask for instead of following up to it from zero. Re-measure
        // the band once with that value so the columns are laid out against the band they will keep.
        plansBlockShown = planBlockTarget();
        computeVerticalLayout(columnsSideBySide, fold);
        placeBands();
        measureControls();
        // The geometry above was measured from the fold the slide is at now; a resize is not a
        // reason to measure it again on the first frame.
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
     *
     * <p>Split out of {@link #init} because the band heights depend on state that can change while
     * the screen is open -- whether the controls are folded, how far the fold has got, and how many
     * plans there are -- and every such change has to move the bands below it. Measuring and
     * placing are kept apart because they read different things: the heights come from constants
     * and counts (see {@link #bottomGroupHeight}), while placement needs the panel's top, the
     * band's height and the gap in force, so only placement can happen once the panel height is
     * settled.
     *
     * <p>Two anchors, not one. The top anchor is the panel's top edge, which fixes the header, the
     * search box and the top of the columns. The bottom anchor is the footer, whose bottom edge is
     * the panel's bottom edge in every state -- so the bar above it and the block above that are
     * placed from the bottom, and the band the columns get is whatever is left between the two.
     * That is what lets the fold be a change of *height*: as the fold moves, the block the controls
     * and the plans share changes size (see {@link #bottomGroupHeight}), the readout directly above
     * it follows, and the columns stretch or give up exactly that much on the same frame. Placed
     * from the top down instead, the readout would stay put and the bar and footer would have to
     * move, which is a layout that changes shape rather than height.
     *
     * <p>The footer and the bar therefore do not move at all, and the four rows keep the place the
     * open layout gives them rather than being re-placed every frame: the fold fades and clips one
     * block out of the shared space while the other comes in, and a block that was also re-placed
     * would slide through the readout on its way out.
     */
    private void placeBands() {
        mapX = panelX + PADDING;
        searchY = panelY + PADDING + HEADER_HEIGHT;
        mapY = searchY + SEARCH_HEIGHT + gap;
        // The columns start on the same line when they are side by side, and the list gets its own
        // line under the map when they are stacked. Both run down to the bottom of the band, which is
        // where the readout begins -- so the columns' bottoms are the band's bottom by construction,
        // and the bands below start exactly there.
        listTop = columnsSideBySide ? mapY : mapY + mapH + gap;
        // The details card sits at the bottom of the list band, directly under the rows it left room
        // for. Both numbers come from the layout above, so the drawing, the wheel and the hit tests
        // of the rows and of the card all agree about where one ends and the other begins.
        detailsY = listTop + listBandHeight - detailsHeight;
        previewY = mapY + bandHeight;

        // The bottom anchor: footer on the panel's bottom edge, the bar directly above it, and the
        // shared block above that.
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
     * Measures the four control rows' buttons from the panel's width and the gap in force.
     *
     * <p>The mode buttons share one row with a short caption, and the widths are derived here rather
     * than where the rows are drawn so the drawing and the hit tests cannot disagree about where the
     * controls are. The preference and class rows use the same rule: a caption, then equal buttons
     * filling what is left.
     *
     * <p>Called on every relayout, not only from {@link #init}, because the gap can change when the
     * controls are opened or folded -- a short window packs -- and a row measured against the other
     * gap puts its last chip past the panel's edge. That is the mistake that once put the ice toggle
     * off the side of the screen.
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
     * Re-measures and re-places the whole panel after the content changed under a fixed window.
     *
     * <p>Called when the plans change, when the controls open or close, and when a choice is
     * cleared: all three change how much of the panel the bottom group needs, and the band above it
     * is whatever is left. Only the vertical layout can move -- the window, the panel's width and
     * its height are the same -- so the search box stays where {@link #init} built it and only the
     * bands and the scroll clamp are recomputed.
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
     *
     * <p>This is what makes the map and the list change height as the controls fold rather than in
     * the frame the fold started: the bottom group is measured from the fold (see
     * {@link #bottomGroupHeight}) and the band the columns get is the remainder, so the columns grow
     * exactly as fast as the fold gives the space back. The plan band's own height is followed in
     * the same way for the case where the fold is still and the number of plans changed; see
     * {@link #settlePlansBlock}. Called from {@link #render}, once per frame that either of them
     * actually moved; with {@code ui_animations} off both land on their targets in the frame the
     * click happened and this is the state change it replaced.
     *
     * <p>No hover reset here, unlike {@link #relayout}: the cursor is over something and the rows
     * are being drawn this frame anyway, so the drawing recomputes the hover result from the geometry
     * that is in force. The wheel's clamp is re-applied because the list band may have changed while
     * the wheel is still being turned.
     *
     * @param openness how far the four control rows are open this frame, 0 folded to 1 open
     */
    private void relayoutForFold(float openness) {
        computeVerticalLayout(columnsSideBySide, openness);
        placeBands();
        laidOutFold = openness;
        int maxScroll = Math.max(0, shown.size() - visibleRows);
        scroll = Math.max(0, Math.min(scroll, maxScroll));
    }

    /**
     * Opens or folds the four control rows at the player's request, and re-places the bands.
     *
     * <p>One entry point because the bar is both controls -- opening the rows and folding them away
     * again -- and a state written in two places is a state that can disagree with itself.
     */
    private void setControlsExpanded(boolean expanded) {
        if (controlsExpanded == expanded) {
            return;
        }
        controlsExpanded = expanded;
        relayout();
    }

    /**
     * Re-plans after a control that changes the plans was used, and folds the controls so the plans
     * it produced can be seen.
     *
     * <p>Folding is not the control's own choice but the panel's: the row the player just used is
     * about to disappear under the plans, and leaving the panel open would hide the answer to the
     * change they made. The bar gets the four rows back in one press, which is why the fold has to
     * be reachable in both directions.
     */
    private void replanAndShowPlans() {
        controlsExpanded = false;
        replanPlans();
        relayout();
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
        computeVerticalLayout(sideBySide, fold);
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
     * bottom: the readout, the shared block the controls and the plans trade, the bar and the
     * footer, measured as one group so the band above can be derived from what is left rather than
     * guessed -- which is what keeps those pieces on the panel and off the columns. Stretchy in
     * between: the columns, which take the whole band. Side by side the map and the list share it
     * and both run to its bottom; stacked the map takes its share and the list has a band of its own
     * below, stretched by the same rule.
     *
     * <p>The bottom group is a function of the fold, not only of the state: the four rows and the
     * plan band trade places over the fold (see {@link #bottomGroupHeight}), so the group is
     * measured from the fold value of the frame in hand. That is the whole of what makes the map and
     * the list change height smoothly when an option is used -- they are given the band that is
     * left, so the band grows exactly as fast as the group shrinks.
     *
     * <p>Whether the window is short, on the other hand, is decided from the two ends of the fold
     * rather than from where it has got to: a packing rule that read the animated height could
     * tighten the window halfway through the fold and make the panel jump, which is the one thing
     * the animation exists to avoid.
     *
     * @param sideBySide whether the map and the list share a band or are stacked
     * @param openness   how far the four control rows are open, 0 folded to 1 open
     */
    private void computeVerticalLayout(boolean sideBySide, float openness) {
        gap = GAP;
        preferenceRowHeight = PREFERENCE_ROW_HEIGHT;
        classRowHeight = CLASS_ROW_HEIGHT;
        transitRowHeight = PREFERENCE_ROW_HEIGHT;
        summaryRowHeight = CONTROLS_BAR_HEIGHT;
        planRowSize = PLAN_ROW_HEIGHT;
        int panel = clamp(height - MARGIN * 2, MIN_PANEL_HEIGHT, Math.max(MIN_PANEL_HEIGHT, height));
        // The bottom group -- the readout and everything under it -- is measured first and then
        // anchored: the columns get whatever is left above it. That is what puts the bar against the
        // panel's bottom edge rather than just under the list, and it is also what keeps the bottom
        // group on the panel, since the band it is subtracted from is the panel itself. The short-
        // window test uses the taller of the two ends of the fold, and the number of plans rather
        // than where the plan band's follow currently is: both are properties of the state, so the
        // decision cannot change halfway through an animation and repack the panel under it.
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

        // Side by side both columns share the band, so both fill it and end on the same line. Stacked
        // they cannot both fill one band, so the band is split between them: the list keeps its
        // share (a proportion, floored at one row) and the map takes what is left. That is what keeps
        // the two from overlapping when the window is too short for both minimums -- before, the map
        // held its own minimum and pushed the list past the band's bottom, and the rows were painted
        // over the readout below.
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
     *
     * <p>The one expression the vertical layout is derived from, so the placement cannot measure a
     * different group than the band was taken out of. Its content, top to bottom:
     *
     * <ul>
     *   <li>the two-line readout, always;</li>
     *   <li>the block the controls and the plans trade -- {@link #controlsBlockHeight} at the open
     *       end, {@link #plansBlockHeight} at the folded one, interpolated by {@code openness} in
     *       between. This is the figure the map and the list breathe with: measured from a flag
     *       instead, it would change in one frame when the flag did;</li>
     *   <li>the bar that toggles the fold, which never moves;</li>
     *   <li>the footer, and the padding under it;</li>
     *   <li>three gaps: readout to block, block to bar, bar to footer. The gaps *inside* the block
     *       belong to the block's own two heights, not here, so the block can be interpolated as one
     *       number.</li>
     * </ul>
     *
     * <p>This is the figure that would silently grow if a band were added and only the placement
     * were taught about it, and it is why every change to the bottom of the panel has to be made
     * here as well.
     *
     * @param openness how far the four control rows are open, 0 folded to 1 open
     */
    private int bottomGroupHeight(float openness) {
        float open = UiMotion.clamp01(openness);
        int block = Math.round(open * controlsBlockHeight()
                + (1.0F - open) * plansBlockHeight());
        return bottomGroupFor(block);
    }

    /** The bottom group's height for a given height of the block the controls and the plans share. */
    private int bottomGroupFor(int block) {
        return PREVIEW_LINE_HEIGHT + PADDING + summaryRowHeight + FOOTER_HEIGHT
                + block + gap * 3;
    }

    /** The four control rows and the gaps between them: what the fold trades for the plans. */
    private int controlsBlockHeight() {
        return MODE_ROW_HEIGHT + preferenceRowHeight + classRowHeight + transitRowHeight + gap * 3;
    }

    /**
     * Height of the plan band in the current layout: one row per plan, zero when there are none.
     *
     * <p>Read from {@link #plansBlockShown} rather than computed from the list, because the list can
     * change in one frame and the space it takes must not: see that field for why. The rounded value
     * is what every caller uses, so the drawing, the click tests and the band above all agree on a
     * whole-pixel height.
     */
    private int plansBlockHeight() {
        return Math.round(plansBlockShown);
    }

    /**
     * The height the plan band is heading for: one row per plan, zero when there are none.
     *
     * <p>The target, not the current value, and asked by the vertical layout's short-window test --
     * that decision has to be a property of the window and the number of plans, not of where the
     * follow happens to be, or the panel would repack itself halfway through a change and jump.
     *
     * <p>The band is measured from the rows rather than the rows from the band, the opposite of the
     * place list's rule. There are at most three plans and every one of them has to stay visible, so
     * the count is the fixed thing and the band follows; every row is then the same height, and the
     * remainder the place list has to spread out does not arise.
     */
    private int planBlockTarget() {
        if (plans.isEmpty()) {
            return 0;
        }
        return Math.min(plans.size(), MAX_PLAN_ROWS) * planRowSize;
    }

    /**
     * Advances the plan band one frame towards its target height, and reports whether it moved.
     *
     * <p>The one thing that makes a change in the number of plans a change of height rather than a
     * jump: the vertical layout reads {@link #plansBlockShown}, so every frame it moves has to be
     * re-measured, and that is what the answer is for. With {@code ui_animations} off the follow
     * lands on the target in one call, which is exactly the jump the setting asks for.
     */
    private boolean settlePlansBlock() {
        float before = plansBlockShown;
        plansBlockShown = UiMotion.follow(plansBlockShown, planBlockTarget(),
                PLAN_BLOCK_FOLLOW_SECONDS);
        return plansBlockShown != before;
    }

    /**
     * Splits one list band between the rows and the details card, and applies the row metrics.
     *
     * <p>One function for the two moments the band has to be re-measured -- the vertical layout
     * itself and a place being selected or collapsed in the middle of a frame -- because the two
     * must produce exactly the same geometry. Written twice, the row count the wheel believes in
     * and the rows the drawing believes in would drift the first time the card's share was tuned.
     *
     * <p>The card's share is a proportion of the band, raised to {@link #MIN_DETAILS_HEIGHT} when the
     * band is short, and given the whole band when even that leaves no room for a list row. The
     * short-window case is a compromise, not the design: on the smallest window the band is about
     * one row tall, and a card squeezed below its own action row would paint its buttons on top of
     * the list instead of shrinking further.
     *
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

    /** Re-measures the list band after the details card opened or closed, and re-clamps the scroll. */
    private void relayoutList() {
        applyListBand(listBandHeight);
        detailsY = listTop + listBandHeight - detailsHeight;
        int maxScroll = Math.max(0, shown.size() - visibleRows);
        scroll = Math.max(0, Math.min(scroll, maxScroll));
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
     * The colour to draw a name in: the source's own, when it will read on this panel.
     *
     * <p>The list is drawn on the panel -- near-black in the black style, white in the white one --
     * and the colour palette a source may use runs from black to white. Drawn faithfully, the end of
     * that range nearest the panel is invisible, which is worse than not honouring the colour at all,
     * so a colour too close to the panel falls back to the ordinary text colour.
     *
     * <p>Which end is "too close" is the theme's to say, not a single number's: black and the darkest
     * greys disappear on the black sheet (below 40), white and the pale yellows on the white one
     * (above 190). A single threshold would fix one theme and break the other -- a mid-grey reads on
     * both, and that is the range the two thresholds leave alone.
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
     * The width of a row's source badge, or zero when the source has no short name to show.
     *
     * <p>Capped at a third of the list column: an addon that registered a long display name would
     * otherwise take the room the second line needs for the summary that explains a search hit.
     */
    private int badgeWidth(String badge) {
        if (badge == null || badge.isEmpty()) {
            return 0;
        }
        return Math.min(font.width(badge) + 6, Math.max(20, listPaneW / 3));
    }

    /**
     * The second line of a list row: the description that matched a search, or the coordinates.
     *
     * <p>Only rows the search matched through their description or tags get the summary; every
     * other row keeps the coordinates it has always shown, so the second line means one thing at a
     * time and a reader is never told a name is a location.
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

    /** The preview readout, the four control rows, the footer and the padding under them. */
    private int footerHeight() {
        return PREVIEW_LINE_HEIGHT + MODE_ROW_HEIGHT + preferenceRowHeight + classRowHeight
                + transitRowHeight + FOOTER_HEIGHT + PADDING;
    }

    /**
     * Puts the player back in the middle of the picker map, at the default zoom, without a cut.
     *
     * <p>The button is a jump in intent rather than in space: the view is aimed at the player and
     * travels there, so a map panned a long way away is not replaced by another map -- it is pulled
     * back. {@link #init} uses the same numbers, but it also snaps them (see {@link #snapCamera}),
     * because the first frame of a screen is not a movement.
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

    /** Puts the shown camera on its target, for a view that is being established rather than moved. */
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
     *
     * <p>Called at the top of {@link #render}, before anything is drawn, and it is the only place the
     * shown camera is written. The map, its labels, the place markers, the route preview and every
     * hit test read the same three numbers ({@link #mapCenterX}, {@link #mapCenterZ},
     * {@link #viewRadius}), so what is under the pointer is what was drawn under it at every point of
     * an animated pan -- the difference from a still frame is only that the numbers are still moving.
     *
     * <p>With {@code ui_animations} off both halves collapse: the coast is not applied and the chase
     * lands on its target in the same call, which is the camera this screen had before it moved.
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
     * Aims the view at a set of points, with room to read them.
     *
     * <p>What a selection asks for: the plans it produced are only worth offering if the player can
     * see them, and the first place selected is usually chosen at the default zoom, which is far too
     * wide for one trip and far too narrow for a long one. The aim is computed from the points the
     * plans would be drawn along, not from the place list, so the view that comes back holds exactly
     * the lines the map is about to draw.
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
     * Filters the list to the search box.
     *
     * <p>The name and the coordinates were the only things matched when the list was just places;
     * a place now carries what the player wrote about it, and a search for "parking" that skipped
     * the note saying where to park would be searching everything but the part the player wrote. A
     * row found that way is remembered in {@link #descriptionMatches}, so its second line can show
     * the summary that explains the hit instead of repeating coordinates the query did not contain.
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

    /** Whether the player's own notes about a place are what a lower-cased query found. */
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

    // ------------------------------------------------------------------ render

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
        // The whole frame travels as one surface, before the first pixel: a sheet that fades in where
        // it already is reads as a cut, and ten pixels of rise over the two hundred of the entrance
        // is what turns that cut into a window arriving. Pressed here so the dimming, the panel, the
        // map, the rows -- and the widgets {@code super.render} draws at the end -- move together,
        // and popped as the last statement, so the pose the game handed in is the pose it gets back.
        // The game builds a fresh graphics object every frame, so there is nothing to unwind if a
        // frame is skipped. Row displacement inside the panel is a pose of its own (see
        // {@link #foldRowY}); this one is only the entrance and the exit.
        UiMotion.pushFrame(graphics, 0.0F, motion.offsetY(UiMotion.RISE, UiMotion.FALL));
        // The fold follows the panel's own state rather than being written where that state is, so
        // the rows and the plans animate for whichever of the several paths changed it. The
        // duration is chosen per direction from the tokens, from wherever the value currently is.
        fold = controlsFold
                .duration(controlsExpanded ? UiMotion.EXPAND : UiMotion.COLLAPSE)
                .to(controlsExpanded ? 1.0F : 0.0F)
                .eased();
        // The vertical layout is measured from the fold, not from the flag the fold is heading for:
        // the band above the controls is the remainder of the panel, so measuring it here is what
        // makes the map and the list grow and shrink *with* the fold instead of after it. The plan
        // band's own height is followed on the same principle, for the case where the fold is not
        // moving at all and the number of plans changes -- choosing a second place while one is
        // already being compared. Both are cheap arithmetic, and this only runs on a frame where one
        // of them actually moved.
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
        // Both forms of the controls are drawn off the one fold value, and that is the whole of the
        // hand-over: the rows leave upward while fading, the plans come down into the space they
        // give up while arriving, and the block they share changes height on the same value -- so
        // the band above them moves with the hand-over rather than before or after it.
        //
        // The rows are drawn whenever the fold is off its collapsed end, not only while the controls
        // are logically open: a fold that stopped drawing them the instant the state changed was a
        // cut from four rows to one line, with nothing in between. Nothing here decides what is
        // clickable -- every row hit test still asks the layout and the logical state.
        //
        // Clipped to the block the fold has left them. That block's top is the line the band above
        // is growing down to, so without the clip the rows would be painted across the readout for
        // the length of the fold; with it, the band appears to close over them, which is the same
        // motion read as the rows being pushed out of their room.
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
     * The place list, in whichever column it was given, always visible.
     *
     * <p>The hovered row is worked out here and read by {@code mouseClicked}, so the list column's
     * own bounds are the only thing that decides what is under the cursor. The plans no longer take
     * this band: they are drawn below the readout (see {@link #drawPlanList}), because covering the
     * list the player is choosing from was the complaint the new layout answers.
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
     *
     * <p>It sits between the readout and the bar rather than in the list's band, so the places stay
     * visible while a route is compared. It exists while the controls are folded and there is at
     * least one plan -- and while the fold is travelling, because it is the other half of the block
     * the four rows leave: it comes down into the space they give up and rises back out of it when
     * they are opened again. Folding the controls is what gives the plans their room, and the bar is
     * what folds them.
     *
     * <p>The rows are bottom-aligned in that block, so the band does not move while the fold changes
     * the block's height; the fold is expressed by its own alpha and travel, not by re-placing it.
     * Rows are the full panel width and every one of them is the same height, because the band is
     * measured from the rows (see {@link #plansBlockHeight}) rather than the other way round. Each is
     * clickable across its whole width, which takes nothing from the place list's hit tests: the
     * band lies entirely below them.
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
     * Top of plan row {@code index}.
     *
     * <p>One function for the drawing and the click test, so the row under the cursor is the row
     * that was painted under it -- the same rule the place list's {@link #rowTop} exists for. Every
     * row is {@link #planRowSize} tall, because the band was measured from them, so there is no
     * remainder to carry and the last row ends exactly where the controls begin.
     */
    private int planRowTop(int index) {
        return planTop + index * planRowSize;
    }

    /**
     * The first line of a result row: which plan it is, and the strategy that produced it.
     *
     * <p>The ordinal is the question ("one of three"), the strategy is the answer ("why this one"),
     * and both are needed: the rows are sorted by time, so the second row is not "the slower
     * strategy" but "the strategy asked for second that came out slower". Both strings are localised
     * through translation keys like every other picker string.
     */
    private String planTitle(int index, RouteOption plan) {
        return Component.translatable("screen.howtogo.plan.title", planOrdinal(index),
                plan.label()).getString();
    }

    /** "Plan one", "plan two", "plan three", localised, for the position in the list. */
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
     *
     * <p>For a world journey they are the distance and the turns; for a public transport journey
     * turns are not what is compared -- the journey's own facts are the changes and the walking, and
     * those are what the numbers say. Asking the plan rather than the route for them is what keeps
     * the shown figure and the followed figure the same calculation; see {@link RouteOption}.
     *
     * <p>Keyed on the journey rather than on the mode: the walk offered in public transport's place
     * is handed over under the transit mode but is not a journey -- it has no changes and no
     * boarding, and asking it for its walking would print "0 blocks on foot" about a plan that is
     * nothing else.
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
     * The plan the map, the readout and the footer are all about, or null when there are none.
     *
     * <p>Clamped rather than trusted: the index is written by several paths, and a read a frame
     * behind a re-plan must show a plan rather than throw. There is no case where the clamp changes
     * an answer -- every writer stores an index into the list it was written beside.
     */
    private RouteOption selectedPlan() {
        if (plans.isEmpty()) {
            return null;
        }
        return plans.get(Math.max(0, Math.min(planIndex, plans.size() - 1)));
    }

    /**
     * The selected place's details, in the band the list left under its rows.
     *
     * <p>Drawn after the list and after the map's own block has been closed: the panel writes text
     * and emits its star, and a string flushes whatever vertex batch it finds open, so it must not
     * be called from inside the map's quad sequence. It is asked for the details every frame rather
     * than handed a copy, because the favourite toggle writes the store and the star has to follow
     * without this screen knowing that it changed.
     *
     * <p>Always drawn while a place is selected, whether or not plans are being compared: the card
     * belongs to the list band and the plans were moved out of that band precisely so it can stay.
     */
    private void drawDetailsPanel(GuiGraphics graphics, int mouseX, int mouseY) {
        if (detailsPlace == null || detailsHeight <= 0) {
            return;
        }
        detailPanel.render(graphics, font, listPaneX, detailsY, listPaneW, detailsHeight,
                detailsPlace, PlaceDetailStore.detailsOf(detailsPlace),
                PlaceDetailPanel.Options.STANDARD, false, mouseX, mouseY);
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

        // North-up, unlike the travelling HUD: when choosing a place, a consistent orientation
        // matters more than knowing which way the player happens to be facing.
        //
        // The picker map is one drawing block, opened the same way on either side of 1.21.6 -- a live
        // consumer through the pose before, a deferred batch the GUI renderer plays back after. The
        // emitters take the sink, so nothing below this line knows which.
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

        // Public transport lines, above the ground they run on and below the route: this map has no
        // track to follow a line along, so it is drawn through the stops it calls at. Half a footpath's
        // width -- a line marks a service, not a road -- with a floor because this map draws every
        // road at one thin weight and half of that rounds away to nothing.
        HudDraw.emitTransitLines(last, Navigation.linesInPlay(), m00, 0, m02, 0, m11, m12,
                Math.max(0.5, 0.7 * 0.5), (int) (0xE0 * open), minX, minY, maxX, maxY);

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
            // Every plan at once, so the comparison is made on the map as well as in the list: each in
            // its own ink, the chosen one at the live route's width and strength, the others thin and
            // faded so they recede without disappearing. The unchosen lines are emitted first, so the
            // chosen one is never hidden under a line it is being compared with.
            //
            // These keep their own fixed alphas rather than the screen's: a plan can only exist
            // once a destination has been chosen, which is long after the entrance has finished, so
            // there is nothing here for the opacity to scale.
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
     *
     * <p>Every plan is drawn in <em>its own</em> ink -- the walking blue on the stretches walked, the
     * line's own colour on the stretch ridden, the driving green on a drive -- and not in a colour of
     * this screen's choosing. The accent is what the rows and the readout use to say which plan is
     * being compared; the map has to say what the plan <em>is</em>, and a public transport plan drawn
     * in one colour looks like a walk with a line's length, which is worse than saying nothing.
     *
     * <p>The chosen plan is told apart by weight rather than by hue: it is drawn at the width the live
     * route uses and at full strength, the others thinner and faded. That was the other way round in
     * the first version -- accent for the chosen line -- and it made the one plan the player was
     * looking at the only line on the map that could not say whether it rode anything.
     *
     * <p>The fading of an unchosen plan is per stretch and by alpha, not by replacing the colour with
     * a paler one: the hue is what carries the meaning, and mixing each stretch toward the panel would
     * make two different lines' colours converge at exactly the alpha this band uses to recede.
     *
     * <p>Emitted through the shared block's sink like every other line on this map, so it must be
     * called before the name pass: a string written by {@code GuiGraphics} flushes the batch these
     * quads are written to.
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
     * Draws a mark only when the whole of it falls inside the map.
     *
     * <p>Fully inside rather than merely centred inside: a disc is a couple of units across, and
     * testing only its centre let its rim paint over the map's border and the panel beyond it.
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
     * Where one of the four control rows is drawn this frame: its laid-out top, lowered by the fold.
     *
     * <p>The drawing half of the fold, and only the drawing half. The rows keep the top the layout
     * gave them and are moved by this every frame, so the animation never has to write a position:
     * the places a click is tested against are the ones the layout computed, and a row that is
     * halfway out of the panel is still exactly where it was when the pointer found it.
     *
     * <p>They rise from behind the bar, which is the direction the drawer opens in: the bar is the
     * bottom of the panel, so the options have to come up out of it rather than down from under the
     * readout. While they are still low they are drawn below the block's bottom edge and are clipped
     * away by it (see {@link #render}), so the ones that have not arrived yet are simply not on
     * screen -- the block looks like it is growing upwards out of the bar.
     *
     * <p>The travel is the whole height of the block the rows occupy, so a row is fully below its
     * place at the folded end rather than a token distance short of it.
     */
    private int foldRowY(int rowY) {
        return rowY + Math.round((1.0F - fold) * controlsBlockHeight());
    }

    /**
     * Travel-mode row: one button per mode, the active one filled with the selection colour.
     *
     * <p>All three are shown at once rather than cycled through, because the choice changes the
     * route as well as the estimate, and seeing which one is on is the point of the row.
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

    /** True when two picked places are the same place, by name and position. */
    private static boolean samePlace(Destination a, Destination b) {
        return a != null && b != null && a.name().equals(b.name())
                && a.x() == b.x() && a.z() == b.z();
    }

    /**
     * What the readout says: the chosen plan, the one plan there is, or why there is no plan.
     *
     * <p>Laid out as two lines because the two kinds of answer say different kinds of thing. With
     * plans, the first line names the one being compared -- which of the three and the strategy
     * that produced it -- and the second gives its cost, so the row, the map's chosen line and this
     * readout all describe one plan. Without plans, the old single preview keeps its old two lines:
     * the state line says which mode the answer is for, and the second line is the trip or the
     * router's sentence explaining why there is none.
     *
     * <p>The second line gives the plan's cost except for the walk offered because no line is worth
     * riding: there it gives the reason instead, since "how far and how long" is already on the row
     * and the question left is why there is no ride at all. See {@link #walkInsteadOfRiding}.
     *
     * <p>A failed combination is a sentence from the router, which is the only thing that turns
     * "there is no route" into something the player can act on, so it gets a line of its own to be
     * read in rather than being clipped to make room for a distance and a time that do not exist.
     * Showing nothing at all would look like the picker had simply stopped working.
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
     * Whether the selected plan is the walk offered in public transport's place because the
     * destination is near enough to walk to.
     *
     * <p>The row's own title already says what the plan is -- a walk; this answers the different
     * question the readout's second line exists for: why there is no ride at all. It is true only
     * when that is the reason, because a destination further off keeps the plan's own cost there and
     * "there is no line here" is not what explains that row. It is asked of the same distance the
     * planner refuses a ride for -- see {@link LinePlanner#closeEnoughToWalk} -- so the sentence and
     * the list cannot disagree about what "near" means. The origin is the player, which is where the
     * list was planned from; a player who has wandered while the panel was open changes nothing that
     * matters here, since the HUD says the same sentence after the trip starts.
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
     * The metric, the major-road preference and the voice switch, as buttons that take effect on
     * the spot.
     *
     * <p>The first two change what "best" means, so both re-plan the comparison from
     * {@code mouseClicked}: a toggle whose effect only appears after the screen is reopened is a
     * setting, not a control. Speech changes nothing about the routes, so the voice switch has
     * nothing to re-plan -- it is here rather than on a settings page because a toggle the player
     * cannot find is a toggle that does not exist.
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
     * One toggle per road class, lighting up the classes kept out of the route.
     *
     * <p>All six are always shown rather than only the avoided ones, because the list is what the
     * player is choosing from: a row that grows as classes are added answers "what can I avoid"
     * with "what have I avoided", which is not the same question.
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
     *
     * <p>A plain button and its state rather than a checkbox: the same hover feedback as every
     * other control in the panel, and no widget to keep in step with a value that lives outside it.
     * Where it was clicked is decided in {@code mouseClicked}, which uses the same coordinates.
     *
     * <p>Its two states are mixed into the face rather than picked between, because a toggle that
     * is switched on is a change the player made: the chosen face travels from {@link UiTheme#button()}
     * to the accent wash this panel has always used for a chosen control, and the label travels with
     * it, over its own slide. The cursor's face is then mixed over that by {@link #drawButton}, which
     * is where the hover lives for every control in the panel.
     *
     * <p>Both endpoints are the colours the panel used before it animated -- the travel is the only
     * new thing, so a chip resting at the far end of the mix is pixel-for-pixel the chip it used to be.
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

    // ------------------------------------------------------------ the fold bar

    /**
     * The one bar that folds the four control rows away and opens them again.
     *
     * <p>Always in the same place, in both forms: at the bottom of the group, directly above the
     * footer. While the rows are folded it says what they are set to, and a hint at its right end
     * says that pressing it opens them; while they are open it says the same setting and the hint
     * says that pressing it folds them. That is the whole of the control -- the separate button that
     * used to sit here saying "路线选项" was a second way of doing what the summary above it already
     * did, and a control that only works in one direction is not an affordance, it is a trap: a
     * player who opened the rows to look at the options had no way to give the plans their room back.
     *
     * <p>Drawn as text on a hover tint rather than as a filled button so it reads as a statement
     * about the state that happens to be clickable, which is what the folded summary always did; the
     * hint is in the panel's body ink rather than the muted one so that it is the part read as the
     * instruction. The summary text is cut to leave the hint its room, because a summary at its
     * longest would otherwise be written under it.
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
     * What the controls are set to: the mode, the metric, and whichever preferences are on.
     *
     * <p>The four rows are four questions -- how to travel, what "best" means, which classes to keep
     * out and whether to hear stops or turns -- and the bar answers them in the order the rows ask
     * them, so a player who folded the panel can still see why the plans look the way they do
     * without opening it. Only the switches that are on are named: a summary that listed "major
     * roads: off" as well would be longer without saying anything more.
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

    /** True when the cursor is on the bar that folds and opens the four control rows. */
    private boolean barAt(double mouseX, double mouseY) {
        return isInside((int) mouseX, (int) mouseY, rowX, barY, rowW, summaryRowHeight);
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

    /**
     * The footer: start the chosen plan, cancel the choice, or leave the picker.
     *
     * <p>Three buttons while there are plans to start, in the order the choice is made: the action
     * on the left where the picker has always put it, "cancel the choice" in the middle, and the way
     * out of the screen on the right. Without plans the old behaviour stands unchanged -- the action
     * (or "stop") and cancel -- because there is no choice to cancel separately from the destination
     * itself; the readout above already says the single answer.
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
     * How many buttons the footer has, which is the only thing that decides its division.
     *
     * <p>Three while plans are compared, two for the old behaviour -- the action (or "stop") and
     * cancel -- and one when there is nothing to act on, so cancel takes the whole row. Asked by
     * both the drawing and the click test, so the two cannot disagree about where the buttons are.
     */
    private int footerButtonCount() {
        if (!plans.isEmpty()) {
            return 3;
        }
        return actionDestination() != null || Navigation.target() != null ? 2 : 1;
    }

    /**
     * Width of each footer button, so the buttons and their gaps add up to {@link #rowW}.
     *
     * <p>The two-button figure is the one the picker has always used -- a gap of {@link #PADDING}
     * between the pair -- and the three-button case is the same rule with two gaps, so switching
     * between the results footer and the old one does not move the left button. A floor of one unit
     * avoids dividing by a width that no window can produce.
     */
    private int footerButtonWidth(int count) {
        return count == 1 ? rowW : Math.max(1, (rowW - PADDING * (count - 1)) / count);
    }

    /**
     * Left edge of a footer button: the same division its width came from, not a second copy.
     *
     * <p>Both the drawing and the click test ask this, so a click on a footer button cannot land
     * where a different rule put the label.
     */
    private int footerButtonX(int index) {
        return rowX + index * (footerButtonWidth(footerButtonCount()) + PADDING);
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

    /**
     * A footer button.
     *
     * <p>The label colour follows the face rather than the other way round: a button filled with the
     * accent or with the danger colour carries white text in both styles, and the neutral cancel
     * button carries the panel's own text colour, which is what makes it read as neutral rather than
     * as a second action. That is also why the label is not mixed with the face: it is chosen for
     * contrast against the resting colour and interpolating it would pass through shades nobody
     * picked for legibility, so only the face travels.
     *
     * <p>The face is the one thing every control in this panel draws through, so the hover lives
     * here and not at each call site: it mixes from the resting colour to the cursor colour over the
     * slot's own slide, which is why a chip and the button under the cursor next to it are never at
     * different points in the same fade.
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

    // ------------------------------------------------------------------- input

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
     * The plan index a number key selects, or -1.
     *
     * <p>Out-of-range numbers are ignored rather than clamped: a "3" pressed when there are two
     * plans is not a request for the second one, it is a key aimed at a list that is not there.
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
     *
     * <p>A real place opens its details card, because the list and the map both choose on one click
     * and the details are what that click was for; the card stays visible, because the plans are
     * drawn below the list rather than over it. A point picked on the map is not a place -- it has no
     * record to show and nothing to edit -- so it closes the card rather than opening an empty one.
     *
     * <p>The controls fold with the choice: the four rows are what the plan band takes its room
     * from, so leaving them open would leave the plans they produced hidden. The bar opens them
     * again, and clicking it once more folds them away.
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
     * Forgets the chosen destination, and gives the panel back to the places and the open controls.
     *
     * <p>What the footer's "cancel the choice" does, and what ESC does once the details card is
     * closed. The place list and its card are untouched -- they were never hidden -- and the four
     * control rows come back with the space the plans had taken: a selection cancelled is a panel
     * with nothing being compared, which is exactly the state the panel opens in.
     *
     * <p>The map pin goes with the choice: a pin with no plan under it is a point the player has
     * already stopped choosing. The old single preview is cleared too, so the readout says what to
     * do rather than describing a route nothing is about. The details card is not cleared -- it
     * belongs to the places, not to the choice, and is closed by its own controls.
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
     * Opens or closes the details card, taking the list's band when it opens and giving it back
     * when it closes.
     *
     * <p>Compared by value rather than by reference: the sources build a fresh {@link Destination}
     * every time they are asked, so the same place arrives as a different object on every frame and
     * an identity test would re-lay the list out on each one.
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

    /** Runs one of the details card's controls, as reported by its last {@link PlaceDetailPanel#render}. */
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
     * Opens the editor for the selected place.
     *
     * <p>Two editors, because the two kinds of place are stored differently. The player's own place
     * is a node of the road network, and the place editor writes name, kind and details to it; every
     * other place belongs to another source, so only the detail record can be edited, through the
     * details dialog.
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
     * details card for its height.
     *
     * <p>Called from {@link #init}, which is where the list is rebuilt from the sources: a place
     * that disappears while the picker is open is still in the snapshot being displayed, so it is
     * noticed when that snapshot is next taken rather than by re-reading every source each frame.
     * A source such as Xaero's is read through reflective calls that build a whole list per ask, and
     * polling it for every selected frame would cost far more than the rare deletion it would catch
     * sooner. Deleting a place is the world map's edit mode's job, so this screen only has to survive
     * one disappearing under it.
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
     * The road node id a manual place stands for, or -1 when its id is not a node id.
     *
     * <p>Parsed rather than trusted: the id comes from the source and is a string, and a record
     * whose id is not a number names no node this session can find. The caller then falls back to
     * the details dialog instead of failing the click.
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

    /** Value equality that also treats two nulls as the same, for the selection fields. */
    private static boolean sameDestination(Destination a, Destination b) {
        return a == null ? b == null : a.equals(b);
    }

    /**
     * Re-plans the plans for the current destination, mode and preferences.
     *
     * <p>Called when a destination is chosen and from every control that changes one of the three --
     * through {@link #replanAndShowPlans}, which also folds the controls so the answer is visible --
     * and from {@code init}, so the band and the map always show the answer to the settings on
     * screen. The chosen row goes back to the first plan on every re-plan: the rows are sorted by
     * time, so row two of a new list is not the row two the player was looking at, and keeping the
     * index would silently switch the plan they had picked.
     *
     * <p>Everything this reads is the live session, and neither {@link Navigation#plans} nor
     * {@link Navigation#preview} writes any of it, so comparing routes here cannot disturb a trip
     * that is already running.
     *
     * <p>When the alternatives list is empty -- the mode has one answer, or none -- the old single
     * preview is planned as well. That is not a second opinion: it is the only thing that can carry
     * the router's sentence explaining why there is no route, and it is what the screen drew before
     * there was a comparison. It costs one extra search only in the case where there is nothing to
     * compare.
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
     * Starts the trip along the chosen plan.
     *
     * <p>The plan is handed over whole rather than the destination alone: see
     * {@link Navigation#setTarget(Destination, RouteOption)} for why planning again on commit would
     * answer a question the player did not ask. The index is clamped defensively even though the
     * list can only be read through the same index that was set beside it.
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
     * Starts navigating to the chosen destination, planning the route from the destination alone.
     *
     * <p>The path kept for the case with no plans: the mode has one answer or none, so there is no
     * comparison to honour and the live planning decides -- under the mode and policy in force, and
     * with the walking baseline of {@link Navigation#setTarget} applying as it always has. That is
     * why the HUD can end up saying the trip was planned on foot when the preview showed a drive;
     * the plan-band path, which hands the chosen plan over whole, does not have that gap.
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
            // {@link #stepCamera}, so a wheel notch is a movement instead of a cut. The centre is
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

    /** Top-right corner of the map, where the "back to me" button sits. */
    private boolean insideRecenter(double mouseX, double mouseY) {
        return mouseX >= mapX + mapW - RECENTER_SIZE - 2 && mouseX <= mapX + mapW - 2
                && mouseY >= mapY + 2 && mouseY <= mapY + RECENTER_SIZE + 2;
    }

    @Override
    public void onClose() {
        Screens.set(parent);
    }

}
