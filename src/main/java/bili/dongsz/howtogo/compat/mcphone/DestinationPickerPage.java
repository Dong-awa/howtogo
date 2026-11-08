package bili.dongsz.howtogo.compat.mcphone;

import bili.dongsz.howtogo.HowToGo;
import bili.dongsz.howtogo.client.Destinations;
import bili.dongsz.howtogo.client.HudDraw;
import bili.dongsz.howtogo.client.Navigation;
import bili.dongsz.howtogo.client.MouseButton;
import bili.dongsz.howtogo.client.MtrTransit;
import bili.dongsz.howtogo.client.RailLayers;
import bili.dongsz.howtogo.client.RailNameStore;
import bili.dongsz.howtogo.client.RailTrackStore;
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
 *
 * <h2>Shape of the screen</h2>
 * A map on top and a sheet along the bottom, the way a maps app does it: the sheet is a handle and
 * the current pick when collapsed, and the search box and the place list when expanded. A place can
 * be picked either by tapping the map or by tapping the list, and the footer button commits.
 *
 * <h2>There is no drag-panning, and that is the API's doing</h2>
 * {@code IPhonePage} publishes {@code mouseClicked}, {@code mouseScrolled}, {@code keyPressed} and
 * {@code charTyped} -- and no {@code mouseDragged} or {@code mouseReleased}. A page is therefore never
 * told that a button is being held, so dragging the map cannot be implemented here at all. What is
 * left is a tap to pick, the wheel to zoom, and the arrow keys to pan. That is a real limitation and
 * not a decision: if MCphone later publishes a drag callback, panning becomes a few lines.
 *
 * <h2>Coordinates</h2>
 * Everything is in absolute GUI coordinates, which is the only reading that holds together:
 * {@link PhoneCanvas#graphics()} is the game's own {@link GuiGraphics}, so drawing is absolute by
 * construction, and {@code canvas.x()/y()/width()/height()} say where the content area sits within
 * it. Hover is asked of {@link PhoneCanvas#hovered}, so the host owns that question.
 *
 * <h2>Size</h2>
 * No width or height is written down anywhere; every rectangle is derived from the canvas it is
 * given, and the sheet's height is a fraction of it, so a phone (120x200) and a tablet (226x158) both
 * get a usable map and a usable list.
 *
 * <h2>Drawing order</h2>
 * The map is emitted inside one {@link HudDraw#beginBlock drawing block} and every quad of it goes
 * out before any string anywhere on the page: drawing a string ends the builder the older side
 * writes into (and a deferred batch has to be closed before the GUI renderer replays it), so
 * {@link #drawMap} runs first, opens and closes that block, and only emits geometry; the sheet, its
 * labels and the list follow it.
 *
 * <h2>Motion</h2>
 * The page is not one of this mod's screens, so it cannot use {@link UiMotion#of} -- that map is keyed
 * on {@code Screen} instances and this is a page drawn inside another mod's window. Its entrance is
 * therefore its own {@link UiMotion.Slide}, restarted by {@link #onOpen()}, and every piece of ink on
 * the page is faded by it: the phone's own chrome stays where it is and the sheet fades up over it.
 *
 * <p>The two states a finger can actually change -- the row under the cursor, and a chip or switch
 * being the choice -- are eased rather than switched, because a tapped control that answers on the
 * same frame as the tap reads as the interface ticking rather than as a decision. Both are slides this
 * page owns, so a frame of this page allocates nothing.
 *
 * <p>Hit testing is untouched by any of it: {@link #mouseClicked} works from the same rectangles the
 * layout methods produce, never from what a slide happens to be showing. A control fades in where it
 * is, and is pressable where it is drawn.
 */
public final class DestinationPickerPage implements IPhonePage {

    private final List<Destination> all;

    private List<Destination> shown = List.of();
    private String query = "";
    private int scroll;
    private boolean expanded;

    /**
     * Whether the mode and preference rows are being shown.
     *
     * <p>Set by committing to a place, not before it: the order the player asked for is pick, then
     * "go here", then choose how to travel. Showing the options before there is anywhere to go asks
     * the question in the wrong order.
     */
    private boolean options;

    /** Whether the search box has been tapped, which is drawn and nothing more. */
    private boolean searchFocused;

    /**
     * The trip being previewed: the place the preview was planned to, and the plan itself.
     *
     * <p>Separate from {@link Navigation#target()} on purpose. Committing to a place on this screen
     * shows what the trip would be; it does not start it. Only the second button hands the target to
     * the navigation, which is the same two-step the desktop picker uses.
     */
    private Destination previewTarget;
    private Navigation.RoutePreview preview;

    /** The place the player has marked but not yet committed to. */
    private Destination marked;

    /** The place clicked last in the list, and when, so a second click reads as a double click. */
    private Destination lastClick;
    private long lastClickAt;

    private static final long DOUBLE_CLICK_MS = 400L;

    // Map view: the world point at the centre of the map area, and screen pixels per block.
    private double mapCenterX;
    private double mapCenterZ;
    private double pixelsPerBlock = 1.0;
    private static final double MIN_PPB = 0.05;
    private static final double MAX_PPB = 6.0;
    /** How close a tap has to land to a place to pick that place instead of a bare coordinate. */
    private static final double TAP_SNAP_PX = 9.0;

    // Offsets rather than sizes: the bands they describe are measured from the canvas every frame.
    private static final int PAD = 4;
    private static final int SEARCH_H = 14;
    private static final int CTRL_H = 13;
    private static final int ROW_H = 22;
    private static final int GAP = 3;
    /** The sheet before a place is committed to: a handle, the pick, and one button. */
    private static final int SHEET_PICK_H = 52;
    /** The sheet once a place is committed to and the mode and preferences are being chosen. */
    private static final int SHEET_COLLAPSED_H = 96;
    /** The map is never allowed to shrink past this, however tall the sheet wants to be. */
    private static final int MIN_MAP_H = 52;
    /** The side of the small square buttons the map carries -- currently the recentre button. */
    private static final int PAD_BUTTON = 13;

    /**
     * Below this many screen pixels per block, no names are drawn on the map.
     *
     * <p>At a wider zoom the visible area spans hundreds of blocks and every label would collide with
     * the next; the world map suppresses labels for the same reason.
     */
    private static final double MIN_LABEL_PPB = 0.5;

    // The content area of the canvas being drawn, so the input methods -- which MCphone does not
    // hand a canvas -- can work out the same rectangles the renderer did.
    private int canvasX;
    private int canvasY;
    private int canvasWidth;
    private int canvasHeight;

    /** Decided during render, where the font is available, and read by the input methods after. */
    private int controlRows = 3;

    // ------------------------------------------------------------------ motion

    /**
     * The page arriving, restarted every time it is opened.
     *
     * <p>Out-quint over {@link UiMotion#ENTER}: the same entrance every screen in this mod uses, so
     * the phone's pages and the mod's own windows are cut from one animation. Fading rather than
     * sliding, because a page that moved would move under a finger that is already on the screen --
     * the map is a touch surface from the first frame.
     */
    private final UiMotion.Slide entrance =
            new UiMotion.Slide(UiMotion.Easing.OUT_QUINT, 0.0F, UiMotion.ENTER);

    /**
     * One hover value per row of the visible window, so a tint grows under the cursor instead of
     * appearing.
     *
     * <p>Indexed by the row's position in the window rather than by the place it holds: the value
     * belongs to the band of the screen the finger is over, and a list that re-filters must not leave
     * the highlight on whatever place happened to move into that band.
     */
    private final UiMotion.Slides rowHovers = new UiMotion.Slides();

    /** One glow per option row, taken in the order the controls are drawn; see {@link #drawButton}. */
    private final UiMotion.Slides controlGlow = new UiMotion.Slides();

    /**
     * Which control this frame is drawing.
     *
     * <p>Reset at the top of {@link #drawControls} and taken by {@link #drawButton}, which is the only
     * caller: the controls and their order are the same every frame, so a slot number is as stable an
     * identity as the control it belongs to, and it costs an int instead of a map.
     */
    private int controlSlot;

    /**
     * How far the page has come this frame, 0 to 1; see {@link #render}.
     *
     * <p>A field rather than a parameter because the entrance reaches the sheet, the map's geometry,
     * every label, the rows and the footer button. At full strength {@link UiMotion#fade} returns the
     * colour it was given, so a settled page is drawn in exactly the ink it used before it had motion.
     */
    private float open = 1.0F;

    public DestinationPickerPage() {
        this.all = Destinations.all();
        refilter();
        recentre();
        restoreRunningTrip();
        // Started here as well as in onOpen(): the host calls openPage() and then onOpen(), but this
        // class is only ever shown through its own openPage(), so the frame that builds it is a frame
        // it is being opened in. Starting the entrance here means a host that never calls onOpen()
        // still gets a page that draws at all, and the two call sites are the same instant.
        entrance.open();
    }

    /**
     * The page being opened, which is the point its entrance restarts from.
     *
     * <p>MCphone calls this on the page instance it installs, once per opening. Restarting rather than
     * merely starting matters for the case the host reuses the instance: the page would otherwise be
     * a table of contents that came up instantly the second time. With the animations switched off
     * {@code jump} and {@code open} both land on the end state in the same call, so this costs
     * nothing when the player has turned motion off.
     */
    @Override
    public void onOpen() {
        entrance.jump(0.0F);
        entrance.open();
    }

    /**
     * Comes up matching a trip that is already running.
     *
     * <h2>Why this cannot be left to the page's own state</h2>
     * A trip outlives the page. {@link HowToGoPhoneApp#openPage()} builds a new page every time the
     * app is opened, and the bottom button decides what it is from the preview target -- so without
     * this, closing and reopening the app put it back in the pick state while a route was still
     * running, and the stop button was simply gone. A navigation you cannot cancel from the app that
     * started it is the worst version of this bug.
     *
     * <p>Called from the constructor rather than from {@code onOpen()}: the constructor is the hook
     * this class controls, since it is this class's own {@code openPage()} that builds it. Whether the
     * host also calls {@code onOpen()} then makes no difference.
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
            // The trip keeps running and the stop button is still drawn from the target alone, so this
            // only costs the previewed line on the map -- a far better outcome than the app failing to
            // open at all because a route could not be replanned.
            HowToGo.LOGGER.error("[HowToGo] could not redraw the preview for the running trip", t);
        }
    }

    // ------------------------------------------------------------------ layout

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
     *
     * <p>The sheet holds one of two things there, never both: the search box and the list when it is
     * expanded, the option rows when it is collapsed. Sharing the offset is what keeps the two states
     * from drifting apart, and hiding the options while the list is up is what leaves the list any
     * rows at all on a screen 200 pixels tall.
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
        // The list stops above the commit row whenever that row is being drawn, in both sheet states.
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

    /** One cell of a row of {@code count} equal buttons spanning the content width. */
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
     * The rectangle of each preference control, as {@code {x, y, width}}.
     *
     * <p>One definition, read by both the drawing and the hit testing. Two copies of this arithmetic
     * -- one for what the player sees, one for where the click lands -- is exactly how a control ends
     * up drawn in one place and pressable in another.
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
     *
     * <p>Its own line because it is the only switch here that belongs to one mode: the three above are
     * what any plan is made of, and this one changes only what a public transport journey is told
     * about itself. A fourth cell on their row would cut every label on it, and the phone's sheet is
     * fixed at a height that already has room for one more line -- see {@link #sheetHeight()}.
     */
    private int[] transitRect() {
        return new int[] {contentLeft(), transitY(), contentWidth()};
    }

    /** The first row under the preference block, whatever shape that block ended up taking. */
    private int transitY() {
        return prefY() + (controlRows == 2 ? CTRL_H + GAP : 2 * (CTRL_H + GAP));
    }

    // ------------------------------------------------------------------ render

    @Override
    public void render(PhoneCanvas canvas) {
        canvasX = canvas.x();
        canvasY = canvas.y();
        canvasWidth = canvas.width();
        canvasHeight = canvas.height();
        controlRows = canvas.font() == null ? 3 : (metricFitsThird(canvas.font()) ? 2 : 3);

        GuiGraphics graphics = canvas.graphics();
        Font font = canvas.font();

        // Read once and shared: the map, the sheet and the rows are one page arriving, and a second
        // read of the same slide in the same frame would be the same value anyway.
        open = entrance.eased();

        // First, and geometry only: see the note on drawing order in the class javadoc.
        drawMap(canvas);
        // Then the map's own text, still clipped to the map, and still before the sheet: a label that
        // ran under the sheet would be painted over by it a moment later anyway.
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

    /**
     * Roads, rails and places, clipped to the map area.
     *
     * <p>Emits geometry only -- no strings -- because every quad has to be out before the first
     * label anywhere on the page. See the class javadoc.
     */
    private void drawMap(PhoneCanvas canvas) {
        int minX = mapLeft();
        int minY = mapTop();
        int maxX = mapRight();
        int maxY = mapBottom();
        if (maxY <= minY) {
            // A sheet taller than the screen: nothing to draw a map on, and emitting quads into a
            // zero-height clip would be pure waste.
            return;
        }

        GuiGraphics graphics = canvas.graphics();
        // One drawing block, opened the same way on either side of 1.21.6: a live consumer through
        // the pose before, a deferred batch the GUI renderer plays back after. The emitters only
        // know the sink, so nothing below this line depends on which of the two is in use.
        HudDraw.Drawing drawing = HudDraw.beginBlock(graphics);
        HudDraw.VertexSink out = drawing.sink;

        // The map is geometry, so its share of the entrance is the alpha each emitter already takes:
        // the page's fade, one layer down. Rounded rather than truncated so a half-arrived map is not
        // a shade darker than the sheet arriving over it.
        int solid = (int) (0xFF * open + 0.5F);
        int pale = (int) (0x9A * open + 0.5F);
        int routeAlpha = (int) (0xE0 * open + 0.5F);

        canvas.clipped(minX, minY, maxX, maxY, () -> {
            // One stroke per road (a chain of segments), not one per segment: the joints between
            // pieces are what read as jagged on a small map, whichever mod the road came from.
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
            for (Destination place : all) {
                double px = toScreenX(place.x());
                double pz = toScreenZ(place.z());
                // Bounds-checked here, not left to the clip. The scissor is set by the host and the
                // desktop picker carries the same explicit guard for the same reason: a marker left
                // to the clip alone is drawn at whatever coordinate the map maths produced, which for
                // a place far outside the view is off the screen entirely -- and a square that big a
                // distance out is what the player sees as places flying across the phone.
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

            // The player, which is the one mark a map is useless without: everything else here is a
            // choice, and this is where the choices are being made from. A rim under the dot so it
            // reads against a road as well as against a field.
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

            // The previewed route, in the same ink the desktop picker and the HUD use for a route, so
            // the line looked at before committing and the line drawn afterwards read as one thing.
            // The journey travels with the preview for exactly this: a public transport plan is a
            // walk to a stop, a line ridden, and a walk at the far end, and drawn from the route alone
            // it would be one blue line whatever it rode.
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
            // after the clip came off would paint the roads and markers over the sheet.
            HudDraw.endBlock(graphics, drawing);
        });

        // Outside the clip: this is chrome sitting on the map, and a control half cut off by the
        // scissor is a control the player cannot see well enough to press.
        drawRecentre(graphics);
    }

    // --------------------------------------------------------------- recentre

    /**
     * A single button that puts the map back on the player.
     *
     * <p>There is no drag-panning in this API -- MCphone's screen tracks dragging for its own HUD and
     * never forwards it to a page, so a page is never told that a button is held. Tapping to pan is
     * the only panning there is, and tapping is very good at losing your own position, so getting
     * back has to be one press rather than a hunt.
     */
    private int[] recentreRect() {
        // Top-left, because the top-right corner is where the notice about this integration goes.
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

    // ------------------------------------------------------------ map labels

    /**
     * Names on the map, and the notice in the corner.
     *
     * <p>A separate pass from {@link #drawMap} because that one holds an open drawing block: a string
     * ends the builder behind it, so every quad has to be out before the first character is drawn.
     * Both passes are clipped to the map area.
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
            // Below this zoom the visible area is a couple of hundred blocks and every name would sit
            // on top of the next one -- the same reasoning as the world map's own label scale.
            if (pixelsPerBlock >= MIN_LABEL_PPB) {
                drawRoadNames(graphics, font, minX, minY, maxX, maxY);
            }
            drawNotice(graphics, font, maxX, minY);
        });
    }

    /**
     * Road and railway names, by the same two rules the world map uses: one label per named road, and
     * only when the name fits along the road it belongs to.
     *
     * <p>Reusing those rules rather than inventing a second set is the point: the same map read on the
     * phone and on the world map should not disagree about how many names a road gets, or about which
     * roads are named at all.
     */
    private void drawRoadNames(GuiGraphics graphics, Font font, int minX, int minY, int maxX,
                               int maxY) {
        RoadNetwork network = RoadStore.get();
        // One reading of the roads for the whole pass: which road each piece belongs to, and which
        // piece carries the name. Asking for a chain per named road walks the network per name, and
        // this page redraws on every frame the phone app is open.
        RoadChains.Grouping grouping = RoadChains.cachedGrouping(network);
        for (RoadSegment segment : network.segments()) {
            String name = segment.name();
            if (name == null || segment.vertexCount() < 2) {
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

    /** A name drawn along its line, turned through half a turn rather than left upside down. */
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
     * The corner notice, wrapped because it is wider than the phone is.
     *
     * <p>It is this mod's own assessment of its phone support, put in front of the player rather than
     * left for them to discover by finding that dragging the map does nothing.
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
        // Shadowed, so it reads on top of whatever the map has underneath at that spot -- a road, a
        // field or water, depending on where the player is standing.
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
        // Wider than it was drawn: the grip is how the sheet announces that it can be pulled, and at
        // eighteen by two pixels it read as a divider rather than as a handle.
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
        // slot. See the field's own note for why a slot number is a stable identity here.
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
        // The chosen face is named independently of whether the switch is on right now -- see
        // drawButton -- so that turning it off eases back to the resting face instead of jumping to
        // whichever face the "off" state names.
        drawButton(graphics, font, x, y, width, CTRL_H, label, on, true);
    }

    private void drawButton(GuiGraphics graphics, Font font, int x, int y, int width, int height,
                            String label, boolean active, boolean lit) {
        // Every chip and switch owns one slide, taken in the order the controls are drawn: the set and
        // the order are the same every frame, so the slot number is as stable as the layout it belongs
        // to, and nothing is allocated after the first pass over the controls.
        float glow = controlGlow.get(controlSlot++).to(active ? 1.0F : 0.0F).eased();
        // Mixed from the resting face to the chosen one rather than picked between them: a chip that
        // becomes the travel mode is a decision the player just made, and the button should settle into
        // its colour. At either end of the slide this is exactly the colour it was before.
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
     *
     * <p>Clipped through {@link PhoneCanvas#clipped} and never through
     * {@code graphics().enableScissor}: the host's scissor state is shared and outlives the frame, so
     * an addon that opens one and does not close it crops the whole game's interface afterwards --
     * and it only shows up when the GUI scale is not 100%, which is exactly what an author testing at
     * 100% never sees.
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
                // The row's own share of the page's entrance, so the list is revealed from the top
                // down instead of arriving as one block. A page that has fully arrived is treated as
                // fully arrived row by row as well: UiMotion.stagger's later rows are still a few
                // percent short when the entrance ends, and a row left at ninety-odd percent is not a
                // stagger, it is a row that is permanently duller than the theme says it should be.
                float rowIn = open >= 1.0F ? 1.0F : UiMotion.stagger(i, open);
                // Hover is eased by position in the visible window rather than by place: the band the
                // finger is over is the band that lights up, whatever it now holds.
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
            // The preview's own length, measured from the polyline rather than asked of the router:
            // the two would be the same number, and this one cannot drift from the line on the map.
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
        // "am I going here" without a second indicator crammed into the same row. The word form of
        // the colour, because a name is not a marker.
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
        /** A place is picked: this makes the preview, and starts nothing. */
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

    // ------------------------------------------------------------------- input

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != MouseButton.LEFT) {
            return false;
        }

        // The sheet's header toggles it, and the target is deliberately much larger than the grip is
        // drawn: the grip is nine pixels tall, and a nine-pixel target on a phone screen is one that
        // looks broken. In the pick state there is nothing else in the header, so all of it up to the
        // button counts; once the option rows are up they get their own presses and only the grip
        // strip toggles.
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

        // The search box is a hit target in its own right: without this a tap on it did nothing at
        // all, which reads as a dead field rather than as an unfocused one.
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
                    // Marks the trip out; starts nothing. The button changes to "start" and the mode
                    // and preference rows appear, which is the order the player asked for.
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
            // Once a trip is planned the map stops taking picks. Tapping it then would silently move
            // the destination out from under a route the player is already reading, and the button
            // below is the only thing that should change what is being planned.
            if (previewTarget == null) {
                pickOnMap(mouseX, mouseY);
            }
            return true;
        }
        return false;
    }

    /**
     * Picks whatever is under a tap on the map.
     *
     * <p>A place close to the tap wins, because a tap aimed at a marker is aimed at the marker and not
     * at the empty pixels beside it. With nothing near, the tap becomes its own destination at the
     * coordinates under the cursor -- the same thing the desktop picker's Ctrl+click does, so a point
     * needs no place to exist before it can be navigated to.
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
        // Only while the options are actually on screen. The search state has its own controls -- the
        // search box and the list -- and the pick state has none, so a press there must not land on a
        // row that is not being drawn.
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
            // And neither is the guidance: it says different things about the same journey.
            RoutePreferenceStore.toggleTransitBoardOnly();
            return true;
        }
        return false;
    }

    /**
     * Re-plans the current trip under a changed mode or preference.
     *
     * <p>{@code setMode} and the toggles only record the choice; nothing re-routes until a destination
     * is handed over again. Re-stating the live target is the same call the desktop picker makes after
     * its own controls change, so a route cannot end up planned under settings already moved away from.
     */
    private void replan() {
        // Only ever a preview. The trip itself is started by the second button, so changing the mode
        // or a preference redraws what the trip would be without touching the navigation.
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
        // Over the map the wheel zooms, over the sheet it scrolls the list. One control, two meanings,
        // decided by where the cursor is -- which is what a maps app does and what makes the wheel
        // useful on a screen with no drag.
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
            // Panning, because the page API has no drag callback: the arrow keys are the only place
            // continuous input is available at all. A step is a tenth of the visible map, so a few
            // presses cross the screen at any zoom.
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
     *
     * <ol>
     *   <li>from planning a trip back to picking a place;</li>
     *   <li>from the open sheet back to the map;</li>
     *   <li>otherwise false, so the phone closes the app as it did before.</li>
     * </ol>
     *
     * <h2>Why a live trip is not stepped out of</h2>
     * The trip already handed to the navigation is not the page's to cancel, and clearing the preview
     * target while one is running would also take away the stop button: the bottom button decides what
     * it is from that field. Back leaves a running trip alone and only closes the app -- the route and
     * its prompts live on in the HUD, which is where they were always going to be.
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
     *
     * <p>Left at its default this page received no keystrokes at all, which is why the search box
     * could not be typed into. Keys are wanted in both sheet states: expanded, they are the query and
     * the list; collapsed, the arrow keys are the only way to pan the map, because the page API has no
     * drag callback. Back is a separate callback, so claiming the keyboard does not take it away.
     */
    @Override
    public boolean capturesKeyboard() {
        return true;
    }

    // ----------------------------------------------------------------- helpers

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
