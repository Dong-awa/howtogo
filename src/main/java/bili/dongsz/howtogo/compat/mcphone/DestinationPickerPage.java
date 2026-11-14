package bili.dongsz.howtogo.compat.mcphone;

import bili.dongsz.howtogo.HowToGo;
import bili.dongsz.howtogo.RoadConfig;
import bili.dongsz.howtogo.client.Destinations;
import bili.dongsz.howtogo.client.HudDraw;
import bili.dongsz.howtogo.client.LineNameLabels;
import bili.dongsz.howtogo.client.MapFilter;
import bili.dongsz.howtogo.client.Navigation;
import bili.dongsz.howtogo.client.MouseButton;
import bili.dongsz.howtogo.client.MtrTransit;
import bili.dongsz.howtogo.client.RailLayers;
import bili.dongsz.howtogo.client.RailNameStore;
import bili.dongsz.howtogo.client.RailTrackStore;
import bili.dongsz.howtogo.client.RoadCategoryStore;
import bili.dongsz.howtogo.client.RoadElementRenderer;
import bili.dongsz.howtogo.client.RoadLabelStyle;
import bili.dongsz.howtogo.client.RoadSigns;
import bili.dongsz.howtogo.client.RoadStore;
import bili.dongsz.howtogo.client.UiMotion;
import bili.dongsz.howtogo.client.UiTheme;
import bili.dongsz.howtogo.road.RoadChains;
import bili.dongsz.howtogo.road.RoadNetwork;
import bili.dongsz.howtogo.road.RoadSegment;
import bili.dongsz.howtogo.route.Destination;
import bili.dongsz.howtogo.route.Route;
import bili.dongsz.howtogo.route.RoutePreferences;
import bili.dongsz.howtogo.route.TravelMode;
import bili.dongsz.howtogo.store.RoutePreferenceStore;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.november.mcphone.api.client.ui.IPhonePage;
import com.november.mcphone.api.client.ui.PhoneCanvas;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Choosing a destination, drawn inside the phone.
 * Layout: a map on top and a bottom sheet -- collapsed it is a handle plus the current pick, expanded it holds the search box and place list. A place is picked by tapping the map or the list, and the footer button commits.
 * No drag-panning: IPhonePage publishes no mouseDragged or mouseReleased, so a page is never told a button is held. Tapping picks, the wheel zooms and the arrow keys pan; that is a limitation of the API, not a decision.
 * Everything is in absolute GUI coordinates: PhoneCanvas.graphics() is the game's GuiGraphics, and canvas x/y/width/height place the content area. Hover is asked of PhoneCanvas.hovered.
 * No width or height is written down: every rectangle derives from the canvas and the sheet height is a fraction, so phone (120x200) and tablet (226x158) both get a usable map and list.
 * Drawing order: drawMap runs first and emits geometry only, because a string ends the builder and every quad must be out before the first label; labels, sheet and list follow.
 * Motion: the page is not a mod Screen, so it cannot use UiMotion.of. It owns an entrance slide restarted by onOpen(), plus row-hover and control-glow slides; hit testing always uses the layout rectangles, never the eased values.
 */
public final class DestinationPickerPage implements IPhonePage {

    private final List<Destination> all;

    private List<Destination> shown = List.of();
    private String query = "";
    private int scroll;
    private boolean expanded;

    /**
     * Whether the mode and preference rows are shown.
     * Set on commit, not before: pick, then "go here", then choose how to travel.
     */
    private boolean options;

    /** Whether the search box has been tapped; drawing only. */
    private boolean searchFocused;

    /**
     * The place the preview was planned to, and the plan itself.
     * Separate from Navigation.target() on purpose: committing shows the trip, the second button starts it -- the desktop picker's two steps.
     */
    private Destination previewTarget;
    private Navigation.RoutePreview preview;

    /** The place marked but not yet committed. */
    private Destination marked;

    /** The place clicked last and when, so a second click reads as a double click. */
    private Destination lastClick;
    private long lastClickAt;

    private static final long DOUBLE_CLICK_MS = 400L;

    // Map view: the world point at the centre of the map area, and screen pixels per block.
    private double mapCenterX;
    private double mapCenterZ;
    private double pixelsPerBlock = 1.0;
    private static final double MIN_PPB = 0.05;
    private static final double MAX_PPB = 6.0;
    /** How close a tap must land to a place to pick that place instead of a bare coordinate. */
    private static final double TAP_SNAP_PX = 9.0;

    // Offsets rather than sizes: the bands they describe are measured from the canvas every frame.
    private static final int PAD = 4;
    private static final int SEARCH_H = 14;
    private static final int CTRL_H = 13;
    private static final int ROW_H = 22;
    private static final int GAP = 3;
    /** The sheet before a place is committed: a handle, the pick and one button. */
    private static final int SHEET_PICK_H = 52;
    /** The sheet once a place is committed and the mode and preferences are being chosen. */
    private static final int SHEET_COLLAPSED_H = 96;
    /** The map is never allowed to shrink past this, however tall the sheet wants to be. */
    private static final int MIN_MAP_H = 52;
    /** The side of the small square buttons the map carries, currently the recentre button. */
    private static final int PAD_BUTTON = 13;

    /**
     * Below this many screen pixels per block, no names are drawn: at a wider zoom every label would collide.
     */
    private static final double MIN_LABEL_PPB = 0.5;

    /**
     * How much of the font a sign card is drawn at, box and text alike (RoadSigns.FIXED_CARD_SCALE of the font).
     * The shared fraction keeps the same road shield the same object on the main screen, the picker and this page. The whole card scales, not just its text, or it would be a box of padding round a small word; the border stays one screen pixel.
     */
    private static final float SIGN_CARD_SCALE = RoadSigns.FIXED_CARD_SCALE;
    /** The least share of the card's scale the mixed card's name line may shrink to. */
    private static final double SIGN_CARD_NAME_FLOOR = 0.7;

    /**
     * Half the width of a transit line's stroke, in GUI pixels -- thinner than the roads, because a line marks a service.
     */
    private static final double LINE_HALF_WIDTH_PX = 0.35;

    /** A line is map content rather than the trip, so it is drawn softer than the route. */
    private static final int LINE_ALPHA = 0xC8;

    /**
     * How many screen pixels per block the map must show before a line's name plate is allowed.
     * A plate is a fixed-size opaque capsule; at the default zoom a phone map spans a couple of hundred blocks and the plates' 160-block spacing would offer one on every line at once. From 1.5 ppb the map shows under a hundred blocks, so a plate appears only once the player has zoomed in to read one line.
     */
    private static final double LINE_NAME_MIN_PPB = 1.5;

    /**
     * How far above a line its name plate floats, in screen pixels; screen pixels because the plate does not grow with the zoom.
     */
    private static final double LINE_NAME_RISE_PX = 7.0;

    /**
     * The most line name plates this map draws at once. Two: the phone map is the smallest of the maps and a plate is a fixed-size opaque object.
     */
    private static final int MAX_LINE_NAMES = 2;

    // The content area of the canvas being drawn, so the input methods (which MCphone gives no
    private int canvasX;
    private int canvasY;
    private int canvasWidth;
    private int canvasHeight;

    /** Decided during render, where the font is available, and read by the input methods after. */
    private int controlRows = 3;


    /**
     * The page arriving, restarted every time it is opened.
     * Out-quint over UiMotion.ENTER, the same entrance every screen in this mod uses. Fading rather than sliding, because a page that moved would move under a finger already on the screen.
     */
    private final UiMotion.Slide entrance =
            new UiMotion.Slide(UiMotion.Easing.OUT_QUINT, 0.0F, UiMotion.ENTER);

    /**
     * One hover value per row of the visible window, so the tint grows under the cursor.
     * Indexed by position in the window rather than by the place it holds: a list that re-filters must not leave the highlight on whatever moved into that band.
     */
    private final UiMotion.Slides rowHovers = new UiMotion.Slides();

    /** One glow per option row, taken in the order the controls are drawn; see drawButton. */
    private final UiMotion.Slides controlGlow = new UiMotion.Slides();

    /**
     * Which control this frame is drawing.
     * Reset at drawControls and taken by drawButton, its only caller; the order is stable every frame, so a slot number is as stable an identity as a map.
     */
    private int controlSlot;

    /**
     * How far the page has come this frame, 0 to 1; see render.
     * A field because the entrance reaches the sheet, the map geometry, every label, the rows and the footer. At full strength fade returns the colour unchanged, so a settled page draws in exactly the ink it used before it had motion.
     */
    private float open = 1.0F;

    public DestinationPickerPage() {
        this.all = Destinations.all();
        refilter();
        recentre();
        restoreRunningTrip();
        // Started here as well as in onOpen(): the host calls openPage() and then onOpen()
        entrance.open();
    }

    /**
     * The page being opened, which is the point its entrance restarts from.
     * Restarting matters when the host reuses the instance; with animations off jump and open land on the end state in the same call.
     */
    @Override
    public void onOpen() {
        entrance.jump(0.0F);
        entrance.open();
    }

    /**
     * Comes up matching a trip that is already running.
     * A trip outlives the page and HowToGoPhoneApp.openPage() builds a new one every time, so without this, closing and reopening the app while a route ran put the page back in the pick state with the stop button gone. Called from the constructor, the hook this class controls.
     */
    private void restoreRunningTrip() {
        Destination target = Navigation.target();
        if (target == null) {
            return;
        }
        previewTarget = target;
        marked = target;
        options = true;
        centreOn(target.x(), target.z());
        try {
            replan();
        } catch (Throwable t) {
            // The trip keeps running and the stop button is still drawn from the target alone
            HowToGo.LOGGER.error("[HowToGo] could not redraw the preview for the running trip", t);
        }
    }


    private int contentLeft() {
        return canvasX + PAD;
    }

    private int contentRight() {
        return canvasX + canvasWidth - PAD;
    }

    private int contentWidth() {
        return contentRight() - contentLeft();
    }

    private int sheetHeight() {
        if (expanded) {
            int wanted = canvasHeight * 70 / 100;
            return Math.max(SHEET_COLLAPSED_H, Math.min(wanted, canvasHeight - MIN_MAP_H));
        }
        // Tall enough for the option rows only once there is something to configure.
        return Math.min(options ? SHEET_COLLAPSED_H : SHEET_PICK_H, canvasHeight);
    }

    private int sheetTop() {
        return canvasY + canvasHeight - sheetHeight();
    }

    /** The handle's own band, which is what toggles the sheet. */
    private int handleBottom() {
        return sheetTop() + 9;
    }

    /** Where the pick line starts, and therefore where whatever follows it starts too. */
    private int pickLineY() {
        return sheetTop() + 9;
    }

    /**
     * The first row below the pick line.
     * The sheet holds either the search box and list or the option rows, never both; sharing the offset keeps the two states from drifting, and hiding the options while the list is up is what leaves the list rows on a 200-pixel screen.
     */
    private int contentTop() {
        return pickLineY() + 12;
    }

    private int searchY() {
        return contentTop();
    }

    private int modeY() {
        return contentTop();
    }

    private int prefY() {
        return modeY() + CTRL_H + GAP;
    }

    /** Expanded, the sheet holds only the search box and the list. */
    private int listTop() {
        return searchY() + SEARCH_H + GAP;
    }

    private int commitY() {
        return canvasY + canvasHeight - ROW_H - PAD;
    }

    private int listBottom() {
        // The list stops above the commit row whenever that row is drawn, in both sheet states.
        return (expanded ? commitY() : sheetTop()) - PAD;
    }

    private int visibleRows() {
        return Math.max(0, (listBottom() - listTop()) / ROW_H);
    }

    private int mapLeft() {
        return canvasX;
    }

    private int mapRight() {
        return canvasX + canvasWidth;
    }

    private int mapTop() {
        return canvasY;
    }

    private int mapBottom() {
        return sheetTop();
    }

    private double mapCentreScreenX() {
        return (mapLeft() + mapRight()) / 2.0;
    }

    private double mapCentreScreenZ() {
        return (mapTop() + mapBottom()) / 2.0;
    }

    private double toScreenX(double worldX) {
        return mapCentreScreenX() + (worldX - mapCenterX) * pixelsPerBlock;
    }

    private double toScreenZ(double worldZ) {
        return mapCentreScreenZ() + (worldZ - mapCenterZ) * pixelsPerBlock;
    }

    private double toWorldX(double screenX) {
        return mapCenterX + (screenX - mapCentreScreenX()) / pixelsPerBlock;
    }

    private double toWorldZ(double screenZ) {
        return mapCenterZ + (screenZ - mapCentreScreenZ()) / pixelsPerBlock;
    }

    /** One cell of a row of count equal buttons spanning the content width. */
    private int cellX(int index, int count) {
        return contentLeft() + index * (contentWidth() + GAP) / count;
    }

    private int cellW(int count) {
        return (contentWidth() + GAP) / count - GAP;
    }

    /** Whether the metric's label fits in a third of a row, so it can share one with the switches. */
    private boolean metricFitsThird(Font font) {
        String label = Component.translatable("hud.howtogo.preference."
                + RoutePreferenceStore.preferences().metric().id()).getString();
        return font.width(label) + 4 <= cellW(3);
    }

    /**
     * The rectangle of each preference control, as {x, y, width}.
     * One definition read by both drawing and hit testing; two copies of this arithmetic is how a control ends up drawn in one place and pressable in another.
     */
    private int[] metricRect() {
        return controlRows == 2
                ? new int[] {cellX(0, 3), prefY(), cellW(3)}
                : new int[] {contentLeft(), prefY(), contentWidth()};
    }

    private int[] majorRect() {
        return controlRows == 2
                ? new int[] {cellX(1, 3), prefY(), cellW(3)}
                : new int[] {cellX(0, 2), prefY() + CTRL_H + GAP, cellW(2)};
    }

    private int[] voiceRect() {
        return controlRows == 2
                ? new int[] {cellX(2, 3), prefY(), cellW(3)}
                : new int[] {cellX(1, 2), prefY() + CTRL_H + GAP, cellW(2)};
    }

    /**
     * The transit guidance switch, on a line of its own under the preference block.
     * It is the only switch here that belongs to one mode: the three above are what any plan is made of. A fourth cell on their row would cut every label, and the sheet has room for one more line.
     */
    private int[] transitRect() {
        return new int[] {contentLeft(), transitY(), contentWidth()};
    }

    /** The first row under the preference block, whatever shape that block ended up taking. */
    private int transitY() {
        return prefY() + (controlRows == 2 ? CTRL_H + GAP : 2 * (CTRL_H + GAP));
    }


    @Override
    public void render(PhoneCanvas canvas) {
        canvasX = canvas.x();
        canvasY = canvas.y();
        canvasWidth = canvas.width();
        canvasHeight = canvas.height();
        controlRows = canvas.font() == null ? 3 : (metricFitsThird(canvas.font()) ? 2 : 3);

        GuiGraphics graphics = canvas.graphics();
        Font font = canvas.font();

        // Read once and shared: the map, the sheet and the rows are one page arriving.
        open = entrance.eased();

        // First, and geometry only: see the drawing-order note in the class javadoc.
        drawMap(canvas);
        // Then the map's own text, still clipped to the map and still before the sheet.
        drawMapLabels(canvas, graphics, font);

        drawSheetBackground(graphics);
        drawHandle(graphics);
        drawPickLine(graphics, font);
        if (expanded) {
            drawSearch(graphics, font);
            if (!shown.isEmpty()) {
                drawList(canvas, graphics, font);
            }
        } else if (options) {
            drawControls(graphics, font);
        }
        drawCommit(graphics, font);
    }

    /** Roads, rails and places, clipped to the map area. Emits geometry only -- no strings. */
    private void drawMap(PhoneCanvas canvas) {
        int minX = mapLeft();
        int minY = mapTop();
        int maxX = mapRight();
        int maxY = mapBottom();
        if (maxY <= minY) {
            // A sheet taller than the screen: nothing to draw a map on, and emitting quads into
            return;
        }

        GuiGraphics graphics = canvas.graphics();
        // One drawing block, opened the same way on either side of 1.21.6: a live consumer through
        HudDraw.Drawing drawing = HudDraw.beginBlock(graphics);
        HudDraw.VertexSink out = drawing.sink;

        // The map is geometry, so its share of the entrance is the alpha each emitter takes.
        int solid = (int) (0xFF * open + 0.5F);
        int pale = (int) (0x9A * open + 0.5F);
        int routeAlpha = (int) (0xE0 * open + 0.5F);

        canvas.clipped(minX, minY, maxX, maxY, () -> {
            // One stroke per road chain, not per segment: the joints between pieces are what read
            HudDraw.emitNetwork(out, RoadStore.get(),
                    pixelsPerBlock, 0, mapCentreScreenX() - mapCenterX * pixelsPerBlock,
                    0, pixelsPerBlock, mapCentreScreenZ() - mapCenterZ * pixelsPerBlock,
                    0.7, pale, minX, minY, maxX, maxY);
            HudDraw.emitNetwork(out, RailTrackStore.network(),
                    pixelsPerBlock, 0, mapCentreScreenX() - mapCenterX * pixelsPerBlock,
                    0, pixelsPerBlock, mapCentreScreenZ() - mapCenterZ * pixelsPerBlock,
                    0.7, pale, minX, minY, maxX, maxY);
            HudDraw.emitNetwork(out, MtrTransit.railLayer(),
                    pixelsPerBlock, 0, mapCentreScreenX() - mapCenterX * pixelsPerBlock,
                    0, pixelsPerBlock, mapCentreScreenZ() - mapCenterZ * pixelsPerBlock,
                    0.7, pale, minX, minY, maxX, maxY);
            // The transit lines, with the ground they run on, under the route.
            if (MapFilter.showsLines()) {
                HudDraw.emitTransitLines(out, Navigation.linesInPlay(),
                        pixelsPerBlock, 0, mapCentreScreenX() - mapCenterX * pixelsPerBlock,
                        0, pixelsPerBlock, mapCentreScreenZ() - mapCenterZ * pixelsPerBlock,
                        LINE_HALF_WIDTH_PX, (int) (LINE_ALPHA * open + 0.5F),
                        minX, minY, maxX, maxY);
            }
            for (Destination place : all) {
                double px = toScreenX(place.x());
                double pz = toScreenZ(place.z());
                // Bounds-checked here, not left to the clip: a marker far outside the view would
                if (outside(px, pz, minX, minY, maxX, maxY, HudDraw.PLACE_MARKER_SMALL_PX)) {
                    continue;
                }
                HudDraw.emitPlaceMarker(out, px, pz,
                        HudDraw.PLACE_MARKER_SMALL_PX * 0.5, UiMotion.fade(UiTheme.place(), open));
            }
            Destination pick = marked;
            if (pick != null) {
                double px = toScreenX(pick.x());
                double pz = toScreenZ(pick.z());
                if (!outside(px, pz, minX, minY, maxX, maxY, HudDraw.PLACE_MARKER_SMALL_PX)) {
                    HudDraw.emitPlaceMarker(out, px, pz, HudDraw.PLACE_MARKER_SMALL_PX * 0.7,
                            UiMotion.fade(UiTheme.destination(), open));
                }
            }

            // The player: everything else here is a choice, and this is where the choices are made
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft != null && minecraft.player != null) {
                double playerX = toScreenX(minecraft.player.getX());
                double playerZ = toScreenZ(minecraft.player.getZ());
                if (!outside(playerX, playerZ, minX, minY, maxX, maxY, 8.0)) {
                    HudDraw.emitDisc(out, playerX, playerZ, 3.6,
                            UiMotion.fade(UiTheme.playerHalo(), open), solid);
                    HudDraw.emitDisc(out, playerX, playerZ, 2.4,
                            UiMotion.fade(UiTheme.player(), open), solid);
                }
            }

            // The previewed route in the same ink the desktop picker and the HUD use, so the line
            Navigation.RoutePreview planned = preview;
            if (planned != null && planned.isPresent()) {
                HudDraw.RouteInk ink = Navigation.inkFor(planned.route(), planned.trip());
                if (ink.isPresent()) {
                    HudDraw.emitInk(out, ink,
                            pixelsPerBlock, 0, mapCentreScreenX() - mapCenterX * pixelsPerBlock,
                            0, pixelsPerBlock, mapCentreScreenZ() - mapCenterZ * pixelsPerBlock,
                            1.1, routeAlpha, minX, minY, maxX, maxY);
                }
            }
            // Closed while the scissor is still on: the pre-1.21.6 path flushes here, and flushing
            HudDraw.endBlock(graphics, drawing);
        });

        // Outside the clip: this is chrome on the map, and a control half cut by the scissor
        drawRecentre(graphics);
    }


    /**
     * A single button that puts the map back on the player.
     * The API has no drag-panning, so tapping is the only panning and tapping easily loses your own position; getting back has to be one press rather than a hunt.
     */
    private int[] recentreRect() {
        // Top-left, because the top-right corner holds the integration notice.
        return new int[] {mapLeft() + 3, mapTop() + 3, PAD_BUTTON, PAD_BUTTON};
    }

    private void drawRecentre(GuiGraphics graphics) {
        int[] rect = recentreRect();
        graphics.fill(rect[0], rect[1], rect[0] + rect[2], rect[1] + rect[3],
                UiMotion.fade(UiTheme.mapButton(), open));
        int centreX = rect[0] + rect[2] / 2;
        int centreY = rect[1] + rect[3] / 2;
        // A ring and a dot: the crosshair every map uses for "you are here".
        graphics.fill(centreX - 3, centreY - 1, centreX + 4, centreY + 2,
                UiMotion.fade(UiTheme.crosshair(), open));
        graphics.fill(centreX - 1, centreY - 3, centreX + 2, centreY + 4,
                UiMotion.fade(UiTheme.crosshair(), open));
        graphics.fill(centreX - 2, centreY - 2, centreX + 3, centreY + 3,
                UiMotion.fade(UiTheme.playerHalo(), open));
        graphics.fill(centreX - 1, centreY - 1, centreX + 2, centreY + 2,
                UiMotion.fade(UiTheme.destination(), open));
    }


    /**
     * Names on the map, and the notice in the corner.
     * Separate from drawMap because that one holds an open drawing block and a string ends the builder; both passes are clipped to the map area.
     */
    private void drawMapLabels(PhoneCanvas canvas, GuiGraphics graphics, Font font) {
        int minX = mapLeft();
        int minY = mapTop();
        int maxX = mapRight();
        int maxY = mapBottom();
        if (maxY <= minY) {
            return;
        }
        canvas.clipped(minX, minY, maxX, maxY, () -> {
            // Below this zoom the visible area is a couple of hundred blocks and every name would
            if (pixelsPerBlock >= MIN_LABEL_PPB) {
                drawRoadNames(graphics, font, minX, minY, maxX, maxY);
            }
            // The lines' names in their own pass and at their own zoom gate: a plate is fixed-size
            drawLineNamePlates(graphics, font, minX, minY, maxX, maxY);
            drawNotice(graphics, font, maxX, minY);
        });
    }

    /**
     * A transit line's name as a capsule in the line's own colour above the line.
     * The capsule is the same object the list and place card use: horizontal, rounded by half its height so the ends are semicircles, which keeps it from being mistaken for a road sign card. It floats LINE_NAME_RISE_PX above the stroke.
     * Positions come from LineNameLabels, the helper the world map and other self-drawn maps use -- every 160 blocks of drawn track plus one just outside each end -- so they belong to the line rather than this phone map. The pass waits for LINE_NAME_MIN_PPB and for the player's lines switch.
     * Plates are kept clear of the map frame and other plates only: road names and sign cards here may overprint, and giving plates a claim table would make them dodge while names overlap.
     */
    private void drawLineNamePlates(GuiGraphics graphics, Font font, int minX, int minY, int maxX,
                                    int maxY) {
        if (pixelsPerBlock < LINE_NAME_MIN_PPB || !MapFilter.showsLines()) {
            return;
        }
        List<RoadElementRenderer.NamedTransitStroke> strokes =
                RoadElementRenderer.transitLineStrokesFor(Navigation.linesInPlay());
        double height = LineNameLabels.height(font.lineHeight);
        if (strokes.isEmpty() || !(height > 0.0)) {
            return;
        }
        List<double[]> claimedBoxes = new ArrayList<>();
        int drawn = 0;
        // One projection and one fit test for the whole pass; the approved box is handed back
        double[][] claimed = new double[1][];
        LineNameLabels.Screen screen = new LineNameLabels.Screen() {
            @Override
            public double screenX(double worldX, double worldZ) {
                return toScreenX(worldX);
            }

            @Override
            public double screenY(double worldX, double worldZ) {
                return toScreenZ(worldZ);
            }

            @Override
            public boolean fits(double left, double top, double right, double bottom) {
                if (left < minX + 1 || right > maxX - 1 || top < minY + 1 || bottom > maxY - 1) {
                    return false;
                }
                double[] box = new double[] {
                        left - RoadSigns.LABEL_CLEARANCE_PX,
                        top - RoadSigns.LABEL_CLEARANCE_PX,
                        right - left + RoadSigns.LABEL_CLEARANCE_PX * 2.0,
                        bottom - top + RoadSigns.LABEL_CLEARANCE_PX * 2.0};
                for (double[] other : claimedBoxes) {
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
            double width = LineNameLabels.width(font.width(stroke.name()));
            if (!(width > 0.0)) {
                continue;
            }
            // The candidate points are worked out in one walk of the line and each is placed
            for (LineNameLabels.Candidate candidate : LineNameLabels.candidatePoints(stroke.points())) {
                claimed[0] = null;
                double[] spot = LineNameLabels.find(candidate, width, height, LINE_NAME_RISE_PX,
                        screen);
                if (spot == null) {
                    continue;
                }
                claimedBoxes.add(claimed[0]);
                drawLineNamePlate(graphics, font, stroke.name(), spot[0], spot[1], width, height,
                        stroke.colour());
                if (++drawn >= MAX_LINE_NAMES) {
                    return;
                }
            }
        }
    }

    /**
     * One line's name plate: a capsule in the line's colour with the name printed in it.
     * Rounded by half its height so the ends are semicircles, deliberately not the road sign card's rounded rectangle. Both are fills through the GUI's batches before their text; the ink comes from the sign cards' contrast rule, and every colour fades with the page.
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
        int background = UiMotion.fade(colour, open);
        int ink = UiMotion.fade(RoadSigns.ink(colour), open);
        int radius = Math.max(1, (y2 - y1) / 2);
        HudDraw.fillRounded(graphics, x1, y1, x2, y2, radius, background);
        HudDraw.outlineRounded(graphics, x1, y1, x2, y2, radius, ink);
        drawScaledCardText(graphics, font, name, centreX,
                centreY - font.lineHeight * (double) LineNameLabels.NAME_FACTOR / 2.0, ink,
                LineNameLabels.NAME_FACTOR);
    }

    /**
     * Road and railway names by the same two rules the world map uses: one label per named road, and only when the name fits along it. The same map read on the phone and the world map should not disagree about how many names a road gets.
     */
    private void drawRoadNames(GuiGraphics graphics, Font font, int minX, int minY, int maxX,
                               int maxY) {
        RoadNetwork network = RoadStore.get();
        // One reading of the roads for the whole pass: which chain each piece belongs
        RoadChains.Grouping grouping = RoadChains.cachedGrouping(network);
        // How the player wants roads labelled, read once for the pass; the world map
        RoadLabelStyle style = RoadConfig.roadLabelStyle();
        boolean signMode = style != RoadLabelStyle.NAME;
        boolean mixed = style == RoadLabelStyle.MIXED;
        for (RoadSegment segment : network.segments()) {
            String name = segment.name();
            boolean named = name != null && !name.isBlank();
            // A sign needs a number and a category that still exists to print it on, so a deleted
            boolean sign = signMode && segment.hasSign()
                    && RoadCategoryStore.has(segment.category());
            if ((!named && !sign) || segment.vertexCount() < 2) {
                continue;
            }
            // The same visibility rule the K screen and the world map ask: a name over a road
            if (!MapFilter.shows(segment.roadClass(), pixelsPerBlock)) {
                continue;
            }
            double[] mid = segment.midpoint();
            double x = toScreenX(mid[0]);
            double z = toScreenZ(mid[1]);
            if (outside(x, z, minX, minY, maxX, maxY, 4.0)) {
                continue;
            }
            if (!grouping.carriesLabel(segment)) {
                continue;
            }
            if (sign) {
                // The card takes the label's place rather than joining it: one road gets one
                drawSignCard(graphics, font, segment, x, z, mixed, minX, minY, maxX, maxY);
                continue;
            }
            if (font.width(name) > chainScreenPx(network, grouping.chainOf(segment))) {
                continue;
            }
            drawAlongLine(graphics, font, name, segment, x, z,
                    UiMotion.fade(UiTheme.mapLabel(), open));
        }

        for (RoadSegment segment : RailTrackStore.segments()) {
            String name = RailNameStore.labelAt(segment);
            if (name == null || segment.vertexCount() < 2) {
                continue;
            }
            double[] mid = segment.midpoint();
            double x = toScreenX(mid[0]);
            double z = toScreenZ(mid[1]);
            if (outside(x, z, minX, minY, maxX, maxY, 4.0)) {
                continue;
            }
            RoadNetwork layer = RailTrackStore.network();
            if (font.width(name)
                    > chainScreenPx(layer, RoadChains.cachedGrouping(layer).chainOf(segment))) {
                continue;
            }
            drawAlongLine(graphics, font, name, segment, x, z,
                    UiMotion.fade(UiTheme.accentSoft(), open));
        }
    }

    /** A name drawn along its line, turned half a turn rather than left upside down. */
    private void drawAlongLine(GuiGraphics graphics, Font font, String name, RoadSegment segment,
                               double x, double y, int colour) {
        int last = segment.vertexCount() - 1;
        float angle = HudDraw.labelAngle((segment.x(last) - segment.x(0)) * pixelsPerBlock,
                (segment.z(last) - segment.z(0)) * pixelsPerBlock);
        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(x, y, 0.0);
        pose.mulPose(Axis.ZP.rotation(angle));
        graphics.drawString(font, name, -font.width(name) / 2, -4, colour, false);
        pose.popPose();
    }

    /**
     * One road's sign card on the phone's map: category colour, a one-pixel contrasting border, number centred, and in mixed mode the name shrunk under it.
     * The same object the world map and the picker draw, and the same rule: upright because a sign stands beside the road, centred on the midpoint rather than lifted so the stroke enters and leaves it, ink chosen from the painted colour (see RoadSigns). Room, ink rule and mixed shrink-with-floor are shared; placement and scale are this page's, because its canvas is fixed-size.
     * The whole card scales at SIGN_CARD_SCALE of the font because the phone map is the smallest of the three. It is measured from the number: in mixed mode the name shrinks to the width left but only to SIGN_CARD_NAME_FLOOR, below which the plate widens instead. The pass is already clipped, so cards over an edge are cut there; this bound check drops only wholly off-map ones.
     */
    private void drawSignCard(GuiGraphics graphics, Font font, RoadSegment segment, double x,
                              double y, boolean withName, int minX, int minY, int maxX, int maxY) {
        String number = segment.number();
        if (number == null || number.isBlank()) {
            return;
        }
        String name = withName ? segment.name() : null;
        boolean named = name != null && !name.isBlank();
        int numberWidth = font.width(number);
        int nameWidth = named ? font.width(name) : 0;
        // The name line's share of the card's scale and the width the plate must give
        double nameFactor = 1.0;
        int innerWidth = numberWidth;
        if (named) {
            double raw = (double) numberWidth / nameWidth;
            nameFactor = Math.min(1.0, Math.max(SIGN_CARD_NAME_FLOOR, raw));
            // The hair is subtracted before the ceil: raw came from a division and is multiplied
            innerWidth = Math.max(numberWidth,
                    (int) Math.ceil(nameWidth * nameFactor - 1.0E-6));
        }
        double nameLineUnits = named
                ? font.lineHeight * nameFactor + RoadSigns.LINE_GAP
                : 0.0;
        // Everything in unscaled units up to here, and one scaling at the end, so proportions
        int cardWidth = Math.max(1, (int) Math.round(
                (innerWidth + RoadSigns.PAD_X * 2) * (double) SIGN_CARD_SCALE));
        int cardHeight = Math.max(1, (int) Math.round(
                (font.lineHeight + nameLineUnits + RoadSigns.PAD_Y * 2)
                        * (double) SIGN_CARD_SCALE));
        int left = (int) Math.round(x - cardWidth / 2.0);
        // Centred on the road's midpoint rather than above it: the plate is the sign standing
        int top = (int) Math.round(y - cardHeight / 2.0);
        int bottom = top + cardHeight;
        if (left < minX + 1 || left + cardWidth > maxX - 1
                || top < minY + 1 || bottom > maxY - 1) {
            return;
        }

        int background = RoadCategoryStore.color(segment.category());
        int ink = RoadSigns.ink(background);
        // Faded with the page like every other colour this page draws.
        int card = UiMotion.fade(background, open);
        int lettering = UiMotion.fade(ink, open);
        // Rounded rather than square, with the shared radius clamped to half the card's height
        HudDraw.fillRounded(graphics, left, top, left + cardWidth, bottom,
                RoadSigns.CARD_CORNER_PX, card);
        HudDraw.outlineRounded(graphics, left, top, left + cardWidth, bottom,
                RoadSigns.CARD_CORNER_PX, lettering);

        // Both lines are drawn at the card's own scale, centred on the plate's middle; a scaled
        double centreX = left + cardWidth / 2.0;
        double numberTop = top + RoadSigns.PAD_Y * (double) SIGN_CARD_SCALE;
        drawScaledCardText(graphics, font, number, centreX, numberTop, lettering,
                SIGN_CARD_SCALE);
        if (named) {
            double nameTop = top + (RoadSigns.PAD_Y + font.lineHeight + RoadSigns.LINE_GAP)
                    * (double) SIGN_CARD_SCALE;
            drawScaledCardText(graphics, font, name, centreX, nameTop, lettering,
                    SIGN_CARD_SCALE * (float) nameFactor);
        }
    }

    /**
     * Writes one line of a sign card at a given scale, centred on a point.
     * The font's draw call takes no size, so the pose is pushed, translated to the centre, scaled, and the string written at the origin offset by half its width -- that keeps the line centred and the scale about the point.
     * A PoseStack, like drawAlongLine: this page targets the GUI era its phone API jar does, so it keeps one branch rather than the shipped source's two.
     */
    private static void drawScaledCardText(GuiGraphics graphics, Font font, String text,
                                           double centreX, double top, int colour, float scale) {
        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(centreX, top, 0.0);
        pose.scale(scale, scale, 1.0F);
        graphics.drawString(font, text, -font.width(text) / 2, 0, colour, false);
        pose.popPose();
    }

    /** The on-screen length of a run of segments, which is what a name has to fit inside. */
    private double chainScreenPx(RoadNetwork network, int[] chain) {
        double total = 0;
        for (int id : chain) {
            RoadSegment member = network.segment(id);
            if (member == null) {
                continue;
            }
            for (int i = 1; i < member.vertexCount(); i++) {
                total += Math.hypot((member.x(i) - member.x(i - 1)) * pixelsPerBlock,
                        (member.z(i) - member.z(i - 1)) * pixelsPerBlock);
            }
        }
        return total;
    }

    /**
     * The corner notice, wrapped because it is wider than the phone. It is this mod's own assessment of its phone support, shown rather than left to be discovered.
     */
    private void drawNotice(GuiGraphics graphics, Font font, int right, int top) {
        String text = Component.translatable("screen.howtogo.phone_incomplete").getString();
        int maxWidth = Math.max(40, (mapRight() - mapLeft()) * 3 / 4);
        int y = top + 2;
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char character = text.charAt(i);
            if (line.length() > 0 && font.width(line.toString() + character) > maxWidth) {
                y = drawNoticeLine(graphics, font, line.toString(), right, y);
                line.setLength(0);
            }
            line.append(character);
        }
        if (line.length() > 0) {
            drawNoticeLine(graphics, font, line.toString(), right, y);
        }
    }

    private int drawNoticeLine(GuiGraphics graphics, Font font, String line, int right, int y) {
        // Shadowed, so it reads over road, field or water wherever the player stands.
        graphics.drawString(font, line, right - font.width(line) - 2, y,
                UiMotion.fade(UiTheme.warn(), open), true);
        return y + 10;
    }

    /** Whether a marker centred at a point falls outside the map area, allowing for its own size. */
    private static boolean outside(double x, double y, int minX, int minY, int maxX, int maxY,
                                   double size) {
        double half = size * 0.7;
        return x + half < minX || x - half > maxX || y + half < minY || y - half > maxY;
    }

    private void drawSheetBackground(GuiGraphics graphics) {
        graphics.fill(canvasX, sheetTop(), canvasX + canvasWidth, canvasY + canvasHeight,
                UiMotion.fade(UiTheme.panel(), open));
        graphics.fill(canvasX, sheetTop(), canvasX + canvasWidth, sheetTop() + 1,
                UiMotion.fade(UiTheme.panelEdge(), open));
    }

    /** The pull-up affordance: the line a player reads as "this comes up". */
    private void drawHandle(GuiGraphics graphics) {
        int midX = canvasX + canvasWidth / 2;
        int y = sheetTop() + 4;
        // Wider than it was: at eighteen by two pixels the grip read as a divider rather
        graphics.fill(midX - 12, y, midX + 12, y + 3,
                expanded ? UiMotion.fade(UiTheme.header(), open)
                        : UiMotion.fade(UiTheme.textStrong(), open));
    }

    /** The search box's rectangle, which is also its hit target. */
    private int[] searchRect() {
        return new int[] {contentLeft(), searchY(), contentWidth(), SEARCH_H};
    }

    private void drawSearch(GuiGraphics graphics, Font font) {
        int left = contentLeft();
        int right = contentRight();
        int y = searchY();
        graphics.fill(left, y, right, y + SEARCH_H, UiMotion.fade(UiTheme.searchBox(), open));
        graphics.fill(left, y, right, y + 1,
                searchFocused ? UiMotion.fade(UiTheme.accent(), open)
                        : UiMotion.fade(UiTheme.panelEdge(), open));

        boolean empty = query.isEmpty();
        String text = (empty ? Component.translatable("screen.howtogo.search_hint").getString()
                : query) + "_";
        graphics.drawString(font, font.plainSubstrByWidth(text, contentWidth() - 6), left + 3,
                y + 3, empty ? UiMotion.fade(UiTheme.textFaint(), open)
                        : UiMotion.fade(UiTheme.textStrong(), open), false);
    }

    private void drawControls(GuiGraphics graphics, Font font) {
        // The glow pool is read by drawButton in drawing order, so the frame starts from its first
        controlSlot = 0;
        TravelMode[] modes = TravelMode.values();
        TravelMode active = Navigation.mode();
        for (int i = 0; i < modes.length; i++) {
            drawButton(graphics, font, cellX(i, modes.length), modeY(), cellW(modes.length), CTRL_H,
                    Component.translatable("hud.howtogo.mode." + modes[i].id()).getString(),
                    modes[i] == active, false);
        }

        RoutePreferences preferences = RoutePreferenceStore.preferences();
        int[] metric = metricRect();
        drawButton(graphics, font, metric[0], metric[1], metric[2], CTRL_H,
                Component.translatable("hud.howtogo.preference."
                        + preferences.metric().id()).getString(), false, false);

        int[] major = majorRect();
        drawSwitch(graphics, font, major[0], major[1], major[2],
                Component.translatable("screen.howtogo.prefer_major_roads").getString(),
                preferences.preferMajorRoads());

        int[] voice = voiceRect();
        drawSwitch(graphics, font, voice[0], voice[1], voice[2],
                Component.translatable("screen.howtogo.voice").getString(),
                RoutePreferenceStore.voiceAnnouncements());

        int[] transit = transitRect();
        drawSwitch(graphics, font, transit[0], transit[1], transit[2],
                Component.translatable("screen.howtogo.transit_guidance").getString() + " · "
                        + Component.translatable("screen.howtogo.transit_board_only").getString(),
                RoutePreferenceStore.transitBoardOnly());
    }

    private void drawSwitch(GuiGraphics graphics, Font font, int x, int y, int width, String label,
                            boolean on) {
        // The chosen face is named independently of whether the switch is on right now, so turning
        drawButton(graphics, font, x, y, width, CTRL_H, label, on, true);
    }

    private void drawButton(GuiGraphics graphics, Font font, int x, int y, int width, int height,
                            String label, boolean active, boolean lit) {
        // Every chip and switch owns one slide, taken in drawing order; the set and order
        float glow = controlGlow.get(controlSlot++).to(active ? 1.0F : 0.0F).eased();
        // Mixed from the resting face to the chosen one so the button settles into its decision.
        int face = UiMotion.mix(UiTheme.searchBox(),
                lit ? UiTheme.selection() : UiTheme.buttonOn(), glow);
        graphics.fill(x, y, x + width, y + height, UiMotion.fade(face, open));
        String shown = font.plainSubstrByWidth(label, width - 2);
        int ink = UiMotion.mix(UiTheme.textSecondary(),
                lit ? UiTheme.onSelection() : UiTheme.onButtonOn(), glow);
        graphics.drawString(font, shown, x + Math.max(0, (width - font.width(shown)) / 2), y + 3,
                UiMotion.fade(ink, open), false);
    }

    /**
     * The scrolling list.
     * Clipped through PhoneCanvas.clipped, never graphics.enableScissor: the host's scissor state is shared and outlives the frame, so an addon that opens one and does not close it crops the whole interface afterwards -- and it only shows when GUI scale is not 100%, which is what an author testing at 100% never sees.
     */
    private void drawList(PhoneCanvas canvas, GuiGraphics graphics, Font font) {
        int left = contentLeft();
        int right = contentRight();
        int width = contentWidth();
        int top = listTop();
        int bottom = listBottom();
        int rows = visibleRows();
        if (rows <= 0) {
            return;
        }
        int first = Math.min(scroll, Math.max(0, shown.size() - 1));

        canvas.clipped(left, top, right, bottom, () -> {
            for (int i = 0; i < rows; i++) {
                int index = first + i;
                if (index >= shown.size()) {
                    break;
                }
                Destination place = shown.get(index);
                int rowY = top + i * ROW_H;
                // The row's own share of the entrance, so the list is revealed top-down rather
                float rowIn = open >= 1.0F ? 1.0F : UiMotion.stagger(i, open);
                // Hover is eased by position in the visible window, not by place: the band
                float hover = rowHovers.get(i)
                        .to(canvas.hovered(left, rowY, width, ROW_H - 1) ? 1.0F : 0.0F).eased();
                graphics.fill(left, rowY, right, rowY + ROW_H - 1,
                        UiMotion.fade(UiTheme.rowHoverTint(), hover * rowIn));
                if (place.equals(Navigation.target())) {
                    graphics.fill(left, rowY, right, rowY + ROW_H - 1,
                            UiMotion.fade(UiTheme.placeTint(), rowIn));
                } else if (place.equals(marked)) {
                    graphics.fill(left, rowY, right, rowY + ROW_H - 1,
                            UiMotion.fade(UiTheme.rowMarkedTint(), rowIn));
                }
                graphics.fill(left + 3, rowY + 7, left + 9, rowY + 13,
                        UiMotion.fade(UiTheme.place(), rowIn));
                graphics.drawString(font, font.plainSubstrByWidth(place.name(), width - 34),
                        left + 13, rowY + 2, UiMotion.fade(UiTheme.textStrong(), rowIn), false);
                graphics.drawString(font, place.coordinates(), left + 13, rowY + 11,
                        UiMotion.fade(UiTheme.header(), rowIn), false);
            }
        });

        if (shown.size() > rows) {
            int trackH = bottom - top;
            int thumbH = Math.max(6, trackH * rows / shown.size());
            int thumbY = top + (trackH - thumbH) * first / Math.max(1, shown.size() - rows);
            graphics.fill(right - 1, top, right, bottom, UiMotion.fade(UiTheme.scrollTrack(), open));
            graphics.fill(right - 1, thumbY, right, thumbY + thumbH,
                    UiMotion.fade(UiTheme.scrollThumb(), open));
        }
    }

    /** What is picked, or what the previewed trip amounts to. */
    private void drawPickLine(GuiGraphics graphics, Font font) {
        Destination target = Navigation.target();
        String label;
        if (preview != null && preview.isPresent()) {
            // The preview's own length, measured from the polyline rather than asked
            List<double[]> points = preview.route().points();
            double length = 0;
            for (int i = 1; i < points.size(); i++) {
                double[] a = points.get(i - 1);
                double[] b = points.get(i);
                length += Math.hypot(b[0] - a[0], b[1] - a[1]);
            }
            label = Route.formatDistance(length) + "  " + Navigation.modeLabel();
        } else if (marked == null) {
            label = Component.translatable("screen.howtogo.preview_hint").getString();
        } else {
            label = marked.name() + "  " + marked.coordinates();
        }
        // Drawn in the place colour when this is the trip already in progress, so the line answers
        boolean active = target != null && previewTarget != null && target.equals(previewTarget);
        int colour = active ? UiTheme.placeText()
                : (marked == null ? UiTheme.header() : UiTheme.textStrong());
        graphics.drawString(font, font.plainSubstrByWidth(label, contentWidth()), contentLeft(),
                pickLineY(), UiMotion.fade(colour, open), false);
    }

    /** What the single button at the bottom of the sheet does, given what is known so far. */
    private enum Step {
        /** Nothing picked yet. */
        NOTHING,
        /** A place is picked: this makes the preview and starts nothing. */
        PREVIEW,
        /** A preview exists: this is the press that actually starts the trip. */
        START,
        /** This is the trip in progress. */
        STOP
    }

    private Step step() {
        Destination target = Navigation.target();
        if (target != null && previewTarget != null && target.equals(previewTarget)) {
            return Step.STOP;
        }
        if (previewTarget != null) {
            return Step.START;
        }
        return marked != null ? Step.PREVIEW : Step.NOTHING;
    }

    private void drawCommit(GuiGraphics graphics, Font font) {
        int left = contentLeft();
        int right = contentRight();
        int width = contentWidth();
        int y = commitY();
        Step step = step();

        String key = switch (step) {
            case PREVIEW -> "screen.howtogo.go_here";
            case START -> "screen.howtogo.start_nav";
            case STOP -> "screen.howtogo.stop";
            case NOTHING -> "screen.howtogo.go_here";
        };
        String label = Component.translatable(key).getString();
        int background = switch (step) {
            case STOP -> UiTheme.badgeStop();
            case PREVIEW, START -> UiTheme.badgeActive();
            case NOTHING -> UiTheme.badgeIdle();
        };

        graphics.fill(left, y, right, y + ROW_H - 1, UiMotion.fade(background, open));
        String shown = font.plainSubstrByWidth(label, width - 4);
        graphics.drawString(font, shown, left + Math.max(0, (width - font.width(shown)) / 2), y + 6,
                UiMotion.fade(step == Step.NOTHING ? UiTheme.textFaint() : UiTheme.onSelection(),
                        open), false);
    }


    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != MouseButton.LEFT) {
            return false;
        }

        // The sheet's header toggles it, and the target is deliberately much larger than the drawn
        int toggleBottom = options ? handleBottom() + 6 : commitY() - 2;
        if (mouseY >= sheetTop() && mouseY < toggleBottom) {
            expanded = !expanded;
            scroll = 0;
            searchFocused = expanded;
            return true;
        }

        if (hitControl(mouseX, mouseY)) {
            return true;
        }

        // The search box is a hit target in its own right: without this a tap did nothing
        if (expanded) {
            int[] search = searchRect();
            if (inside(mouseX, mouseY, search[0], search[1], search[2], search[3])) {
                searchFocused = true;
                return true;
            }
        }

        if (inside(mouseX, mouseY, contentLeft(), commitY(), contentWidth(), ROW_H - 1)) {
            switch (step()) {
                case PREVIEW -> {
                    // Marks the trip out and starts nothing; the button becomes start
                    previewTarget = marked;
                    expanded = false;
                    options = true;
                    replan();
                }
                case START -> Navigation.setTarget(previewTarget);
                case STOP -> {
                    Navigation.clear();
                    previewTarget = null;
                    preview = null;
                    options = false;
                }
                case NOTHING -> {
                    // Nothing picked: the button is drawn inactive and pressing it does nothing.
                }
            }
            return true;
        }

        int index = rowUnderCursor(mouseX, mouseY);
        if (index >= 0) {
            Destination place = shown.get(index);
            long now = System.currentTimeMillis();
            boolean doubleClick = place.equals(lastClick) && now - lastClickAt <= DOUBLE_CLICK_MS;
            lastClick = place;
            lastClickAt = now;
            marked = place;
            centreOn(place.x(), place.z());
            if (doubleClick) {
                // Second click on the same place commits, so the footer button is not the only way.
                lastClick = null;
                Navigation.setTarget(place);
            }
            // One click marks only: a tap made while reading the list must not start a trip.
            return true;
        }

        if (mouseY >= mapTop() && mouseY < mapBottom()) {
            int[] recentre = recentreRect();
            if (inside(mouseX, mouseY, recentre[0], recentre[1], recentre[2], recentre[3])) {
                recentre();
                return true;
            }
            // Once a trip is planned the map stops taking picks: a tap would silently move
            if (previewTarget == null) {
                pickOnMap(mouseX, mouseY);
            }
            return true;
        }
        return false;
    }

    /**
     * Picks whatever is under a tap on the map.
     * A place close to the tap wins, because a tap aimed at a marker is aimed at the marker. With nothing near, the tap becomes its own destination at the coordinates under the cursor -- the desktop picker's Ctrl+click, so a point needs no place to exist.
     */
    private void pickOnMap(double mouseX, double mouseY) {
        Destination nearest = null;
        double bestSq = TAP_SNAP_PX * TAP_SNAP_PX;
        for (Destination place : all) {
            double dx = toScreenX(place.x()) - mouseX;
            double dz = toScreenZ(place.z()) - mouseY;
            double distanceSq = dx * dx + dz * dz;
            if (distanceSq <= bestSq) {
                bestSq = distanceSq;
                nearest = place;
            }
        }
        if (nearest != null) {
            marked = nearest;
            lastClick = null;
            return;
        }
        int x = (int) Math.round(toWorldX(mouseX));
        int z = (int) Math.round(toWorldZ(mouseY));
        int y = Minecraft.getInstance().player != null
                ? (int) Math.floor(Minecraft.getInstance().player.getY()) : 64;
        marked = new Destination("(" + x + ", " + z + ")", x, y, z, "map");
        lastClick = null;
    }

    private boolean hitControl(double mouseX, double mouseY) {
        // Only while the options are actually on screen: the search state has its own controls
        if (expanded || !options) {
            return false;
        }
        TravelMode[] modes = TravelMode.values();
        for (int i = 0; i < modes.length; i++) {
            if (inside(mouseX, mouseY, cellX(i, modes.length), modeY(), cellW(modes.length))) {
                Navigation.setMode(modes[i]);
                replan();
                return true;
            }
        }
        if (inside(mouseX, mouseY, metricRect())) {
            RoutePreferenceStore.toggleMetric();
            replan();
            return true;
        }
        if (inside(mouseX, mouseY, majorRect())) {
            RoutePreferenceStore.togglePreferMajorRoads();
            replan();
            return true;
        }
        if (inside(mouseX, mouseY, voiceRect())) {
            // No re-plan: speech is not an input to a route, so the map has nothing to redraw.
            RoutePreferenceStore.toggleVoiceAnnouncements();
            return true;
        }
        if (inside(mouseX, mouseY, transitRect())) {
            // Nor is the guidance: it says different things about the same journey.
            RoutePreferenceStore.toggleTransitBoardOnly();
            return true;
        }
        return false;
    }

    /**
     * Re-plans the current trip under a changed mode or preference.
     * setMode and the toggles only record the choice; nothing re-routes until a destination is handed over again. The same call the desktop picker makes after its own controls change, so a route cannot stay planned under settings already moved away from.
     */
    private void replan() {
        // Only ever a preview: changing the mode or a preference redraws what the trip would
        if (previewTarget == null) {
            return;
        }
        preview = Navigation.preview(previewTarget, Navigation.mode(),
                RoutePreferenceStore.preferences());
    }

    private int rowUnderCursor(double mouseX, double mouseY) {
        if (!expanded) {
            return -1;
        }
        int left = contentLeft();
        int right = contentRight();
        int top = listTop();
        int bottom = listBottom();
        if (mouseX < left || mouseX >= right || mouseY < top || mouseY >= bottom) {
            return -1;
        }
        int index = scroll + (int) ((mouseY - top) / ROW_H);
        return index >= 0 && index < shown.size() ? index : -1;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        // Over the map the wheel zooms, over the sheet it scrolls the list: one control, two
        if (mouseY < mapBottom()) {
            double factor = amount > 0 ? 1.15 : 1 / 1.15;
            double next = Math.max(MIN_PPB, Math.min(MAX_PPB, pixelsPerBlock * factor));
            if (next == pixelsPerBlock) {
                return false;
            }
            pixelsPerBlock = next;
            return true;
        }
        if (!expanded) {
            return false;
        }
        int max = Math.max(0, shown.size() - visibleRows());
        int next = Math.max(0, Math.min(max, scroll - (int) Math.signum(amount)));
        if (next == scroll) {
            return false;
        }
        scroll = next;
        return true;
    }

    @Override
    public boolean charTyped(char character, int modifiers) {
        if (!expanded || character < 32 || character == 127) {
            return false;
        }
        query += character;
        refilter();
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        switch (keyCode) {
            case GLFW.GLFW_KEY_BACKSPACE -> {
                if (!expanded || query.isEmpty()) {
                    return false;
                }
                query = query.substring(0, query.length() - 1);
                refilter();
                return true;
            }
            // Panning, because the page API has no drag callback: the arrow keys are the only
            case GLFW.GLFW_KEY_LEFT -> {
                mapCenterX -= mapSpanX() / 10.0;
                return true;
            }
            case GLFW.GLFW_KEY_RIGHT -> {
                mapCenterX += mapSpanX() / 10.0;
                return true;
            }
            case GLFW.GLFW_KEY_UP -> {
                mapCenterZ -= mapSpanZ() / 10.0;
                return true;
            }
            case GLFW.GLFW_KEY_DOWN -> {
                mapCenterZ += mapSpanZ() / 10.0;
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    /** How much world the map area covers, in blocks. */
    private double mapSpanX() {
        return Math.max(1.0, (mapRight() - mapLeft()) / pixelsPerBlock);
    }

    private double mapSpanZ() {
        return Math.max(1.0, (mapBottom() - mapTop()) / pixelsPerBlock);
    }

    /**
     * Back steps out of the page one layer at a time, and only then out of the app.
     * From planning back to picking; from the open sheet back to the map; otherwise false, so the phone closes the app. A running trip is never stepped out of: it is not the page's to cancel, and clearing the preview target would take away the stop button. Back only closes the app; the route and its prompts live on in the HUD.
     */
    @Override
    public boolean onBack() {
        boolean planning = previewTarget != null || options;
        if (planning && Navigation.target() == null) {
            previewTarget = null;
            preview = null;
            options = false;
            expanded = false;
            searchFocused = false;
            return true;
        }
        if (expanded) {
            expanded = false;
            searchFocused = false;
            return true;
        }
        return false;
    }

    /**
     * This page wants the keyboard.
     * At the default it received no keystrokes, which is why the search box could not be typed into. Keys are wanted in both sheet states: expanded they are the query and list, collapsed the arrows pan. Back is a separate callback, so claiming the keyboard does not take it away.
     */
    @Override
    public boolean capturesKeyboard() {
        return true;
    }


    /** Centres the map on the player, or on the first place when there is no player yet. */
    private void recentre() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft != null && minecraft.player != null) {
            mapCenterX = minecraft.player.getX();
            mapCenterZ = minecraft.player.getZ();
        } else if (!all.isEmpty()) {
            mapCenterX = all.get(0).x();
            mapCenterZ = all.get(0).z();
        }
    }

    private void centreOn(double x, double z) {
        mapCenterX = x;
        mapCenterZ = z;
    }

    private void refilter() {
        String needle = query.trim().toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) {
            shown = List.copyOf(all);
        } else {
            List<Destination> matches = new ArrayList<>();
            for (Destination place : all) {
                if (place.name().toLowerCase(Locale.ROOT).contains(needle)
                        || place.coordinates().contains(needle)) {
                    matches.add(place);
                }
            }
            shown = matches;
        }
        scroll = 0;
    }

    private static boolean inside(double mouseX, double mouseY, int[] rect) {
        return inside(mouseX, mouseY, rect[0], rect[1], rect[2], CTRL_H);
    }

    private static boolean inside(double mouseX, double mouseY, int x, int y, int width) {
        return inside(mouseX, mouseY, x, y, width, CTRL_H);
    }

    private static boolean inside(double mouseX, double mouseY, int x, int y, int width, int height) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }
}
