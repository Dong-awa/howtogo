package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.RoadConfig;
import bili.dongsz.howtogo.road.RoadChains;
import bili.dongsz.howtogo.road.RoadNetwork;
import bili.dongsz.howtogo.road.RoadSegment;
import bili.dongsz.howtogo.route.Destination;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
// 26.1 moved the vertex-format types into the renderpearl API, and this file only ever carried the
// import -- nothing here names VertexFormat. Dropped rather than moved.
//? if <26.1 {
import com.mojang.blaze3d.vertex.VertexFormat;
//?}
// 1.21.6 replaced the GUI's PoseStack with JOML's 2D matrix stack.
//? if >=1.21.6 {
/*import org.joml.Matrix3x2fStack;
*///?}
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
// 1.21.9 dissolved RenderStateShard: what a render type used to be assembled from is the render
// pipeline's business now, and this file has no use for the class beyond the import it carried.
//? if <1.21.9 {
import net.minecraft.client.renderer.RenderStateShard;
//?}
import net.minecraft.client.renderer.RenderType;
import net.minecraft.util.FormattedCharSequence;
//? if neoforge {
import net.neoforged.neoforge.client.event.RenderGuiEvent;
//?}

import static bili.dongsz.howtogo.client.HudDraw.*;

import java.util.ArrayList;
import java.util.List;

/**
 * In-game navigation overlay: a corner panel with a mini-map and the trip readout under it.
 *
 * <p>Shown whenever a destination is set and no screen is open, so navigation keeps working after
 * the fullscreen map is closed -- the fullscreen map already carries its own readout.
 *
 * <p>The mini-map is drawn from this mod's own road data rather than borrowing Xaero's rendering:
 * the Minimap is an optional dependency and may not be installed at all, so there would otherwise
 * be nothing to draw into.
 *
 * <h2>The two layouts</h2>
 * The panel is one of two ways the HUD can look. In {@code hud} display mode -- see
 * {@link MapDisplayMode} -- it draws the map and the readout together, as it always has. In
 * {@code xaero_minimap} mode the map half is Xaero's and this class draws no map at all: what is left
 * is {@link NavCompactHud}, a small card with the readout only. Both layouts take one
 * {@link NavHudReadout}, read once at the top of the frame, so switching display modes cannot make
 * the trip read differently, and the arrow, the stop marker and the progress bar live in
 * {@link NavHudShapes} for the same reason.
 *
 * <h2>Motion, without a screen to hang it on</h2>
 * The panel is not a screen: it is never constructed, the game draws it whenever a destination is
 * set and no window is open, and there is no instance for {@link UiMotion#of} to key a state on. So
 * its presence is one static {@link UiMotion.Slide}, driven to 1 while a trip exists and to 0 once it
 * does not, and the fade is applied to the theme's own colours rather than to a second set of them.
 * The consequence worth knowing is that the panel keeps drawing for the few frames after a trip ends
 * -- that is what the fade is -- and then stops drawing anything at all, including an invisible sheet
 * over the corner of the screen: see {@link #VANISHED}.
 */
public final class NavHudRenderer {

    // ------------------------------------------------------------------ layout

    private static final int MARGIN = 6;
    private static final int PADDING = 6;
    private static final int HEADER_HEIGHT = 12;
    /** Side of the rotating mini-map. */
    private static final int MAP_SIZE = 120;
    /** The panel is exactly as wide as the map it holds, plus the padding either side of it. */
    private static final int PANEL_WIDTH = MAP_SIZE + PADDING * 2;
    /** Left gutter of the readout, left clear for the turn arrow. */
    private static final int READOUT_TEXT_X = 26;
    /**
     * Height of the readout block when nothing extra is being said: the rows down to the journey
     * line, with room under them for the fallback hint.
     */
    private static final int READOUT_HEIGHT = 62;
    /**
     * Height of the readout block while the narrator hint is up, which needs the two rows below the
     * fallback one. Only then does the panel reach this far down.
     */
    private static final int READOUT_HINT_HEIGHT = 84;
    /** Top of the narrator hint line inside the readout block. */
    private static final int READOUT_HINT_Y = 61;
    /** The narrator hint is wrapped, and two lines is all the block has room for. */
    private static final int NARRATOR_HINT_LINES = 2;
    private static final int NARRATOR_HINT_LINE_HEIGHT = 11;
    private static final int CORNER_RADIUS = 4;

    /** Half the world span the mini-map covers, in blocks. */
    private static final double VIEW_RADIUS = 170.0;

    /** Height of one line of the panel's font, for the label boxes. */
    private static final int LABEL_HEIGHT = 9;

    // ----------------------------------------------------------------- strokes

    private static final double ROAD_THICKNESS = 1.0;
    private static final double ROUTE_THICKNESS = 2.6;

    /**
     * Half the width of a public transport line's stroke, in GUI pixels.
     *
     * <p>Thinner than a road's half pixel, because a line marks a service rather than a road and the
     * roads it runs beside have to stay readable under it. The same figure Xaero's minimap overlay
     * uses, so the two small maps draw a line at the same weight.
     */
    private static final double LINE_HALF_WIDTH_PX = 0.35;

    /** A line is map content rather than the trip, so it is drawn a little softer than the route. */
    private static final int LINE_ALPHA = 0xC8;

    // ------------------------------------------------------------------- motion

    /**
     * Below this the panel has no visible pixel left, so the frame stops drawing it outright.
     *
     * <p>Not a rounding guard. It is what makes an overlay nobody is navigating with cost one null
     * check per frame instead of a mini-map's worth of geometry, and it is what keeps a panel at
     * alpha two from painting over whatever else shares that corner.
     */
    static final float VANISHED = 0.01F;

    /**
     * Whether the panel is on screen, as a value travelling between 0 and 1.
     *
     * <p>Static because a HUD is not a screen: there is no instance to hang a {@link
     * UiMotion.ScreenState} on and nothing to construct. One slide for the whole overlay, driven by
     * the one question the renderer asks -- is there a trip -- and read once per frame.
     *
     * <p>Out-cubic on {@link UiMotion#HUD}: a HUD is furniture rather than a window, so it arrives and
     * leaves more softly than a screen does. It does move, but only vertically and only by
     * {@link UiMotion#HUD_RISE}: a card this small reads as sliding into its corner, while a
     * horizontal travel would read as the layout breaking -- the panel shares that corner with
     * Xaero's map, whose own position this overlay must not appear to disturb.
     */
    private static final UiMotion.Slide presence =
            new UiMotion.Slide(UiMotion.Easing.OUT_CUBIC, 0.0F, UiMotion.HUD);

    /**
     * How far the panel has come this frame, 0 to 1; set once by {@link #draw} and read by every
     * colour below.
     *
     * <p>A field rather than one more parameter: the fade reaches the sheet, its edge, the mini-map's
     * geometry, a dozen strings and the two shapes {@link NavHudShapes} draws, and threading one
     * number through nine private methods would change nine signatures to say one thing once. There
     * is exactly one reader -- the render thread, in the frame that set it -- so there is no second
     * reading to disagree with.
     */
    private static float open = 1.0F;

    /**
     * The last reading that had a trip behind it, kept for the frames in which the trip has ended but
     * the panel has not finished leaving.
     *
     * <p>The readout describes the current trip; once that is gone there is nothing to describe, and
     * a fade-out would then be an emptied sheet rather than the panel leaving with its last words
     * still on it. One reference, replaced on every frame that has a trip and dropped the moment the
     * panel is fully gone -- which is also what lets the idle frames below return before anything
     * else is asked.
     */
    private static NavHudReadout lastReadout;

    /**
     * Where the progress bar is drawn, which chases {@link NavHudReadout#progress()} rather than
     * being it: the fraction of a trip moves in steps as waypoints are passed, and a bar that jumped
     * by those steps reads as a counter. See {@link #smoothProgress}.
     */
    private static float progressShown;

    private NavHudRenderer() {
    }

    // The two loaders hand the HUD pass different things. NeoForge posts an event carrying the
// graphics object; Fabric's HudRenderCallback takes the graphics object and the partial tick as
// arguments. Only the parameter list and where the graphics object comes from differ, so the body
// is shared.
// Fabric's callback took a float partial tick through 1.20.x and a DeltaTracker from 1.21 on, so the
// second parameter is a third axis. The three conditions are independent rather than nested.
//? if fabric && <1.21 {
/*public static void onRenderGui(GuiGraphics graphics, float tickDelta) {
*///?}
//? if fabric && >=1.21 {
/*public static void onRenderGui(GuiGraphics graphics, net.minecraft.client.DeltaTracker tickDelta) {
*///?}
//? if neoforge {
public static void onRenderGui(RenderGuiEvent.Post event) {
//?}
        Minecraft mc = Minecraft.getInstance();
        if (Screens.of(mc) != null || mc.player == null || mc.level == null) {
            return;
        }
        // A trip that has just ended is not a reason to stop drawing: the panel fades out over the
        // next few frames, and whether there is anything left to draw is decided in draw() once the
        // presence slide has advanced. What must not be deferred is the world having gone away, and
        // that is the guard above.
        //? if fabric {
        /*draw(graphics, mc);
        *///?} else {
        draw(event.getGuiGraphics(), mc);
        //?}
    }

    // -------------------------------------------------------------------- panel

    /**
     * How far this frame's layout has faded in, which layout it is, and the readout it draws.
     *
     * <p>The decision is taken once, here, rather than inside either layout: the panel and the compact
     * card are alternatives, and a frame that started drawing one must not change its mind halfway
     * down. {@link NavMapMode#effective()} is also the only thing that knows why the answer might not
     * be the configured one -- Xaero's Minimap missing, or not yet registered -- so the fallback is
     * not re-derived here and its log line is not duplicated.
     *
     * <p>Presence is decided before either layout is entered, which is what produces the frames after
     * a trip has ended: the reading is kept from the last frame that had one, the slide travels to
     * closed, and the moment there is nothing left to see the frame stops drawing altogether.
     */
    private static void draw(GuiGraphics graphics, Minecraft mc) {
        boolean navigating = Navigation.target() != null;
        if (navigating) {
            // Read before the branch: both layouts need it, and one value means the arrow, the words
            // and the progress bar describe the same instant whichever layout draws them.
            lastReadout = NavHudReadout.of();
        }
        NavHudReadout readout = lastReadout;
        if (readout == null) {
            // No trip has been read out yet in this session, so neither layout has anything to fade
            // out of and there is nothing to draw. This is the whole cost of a game with no
            // navigation running: one null check.
            return;
        }
        MapDisplayMode mode = NavMapMode.effective();
        if (mode == MapDisplayMode.HUD) {
            open = presence.to(navigating ? 1.0F : 0.0F).eased();
            if (open < VANISHED) {
                // Gone, and asked for nothing: no geometry is projected, no text is measured, and
                // nothing is drawn over whatever else shares the corner. The bar is put back to
                // nothing as well, so the next trip starts from empty instead of running backwards
                // down from the finish of the last one, and the reading is dropped so the frames
                // after this one are as cheap as the ones before the first trip.
                forgetProgress();
                lastReadout = null;
                return;
            }
            drawPanel(graphics, mc, readout);
        } else {
            // The compact card owns the slide for its own corner: this frame only decides that the
            // card is the layout in force, and the card decides whether it is still visible at all.
            NavCompactHud.draw(graphics, mc, readout);
        }
    }

    private static void drawPanel(GuiGraphics graphics, Minecraft mc, NavHudReadout readout) {
        int screenWidth = mc.getWindow().getGuiScaledWidth();
        int panelHeight = panelHeight(readout);
        // Floored at MARGIN so a very narrow window cannot push the panel off the left edge.
        int panelX = Math.max(MARGIN, screenWidth - PANEL_WIDTH - MARGIN);
        int panelY = MARGIN;

        // The card slides up into its corner while it arrives, and the push is taken here rather
        // than in draw() on purpose: draw() is where the frame decides the panel is gone, and a
        // push taken before that early return would have to be unwound on the path that draws
        // nothing. Vertical only -- the panel hangs beside Xaero's own map, and a card that came in
        // sideways would be fighting that overlay's layout every time a trip started.
        // HudDraw.beginBlock inside the mini-map pushes its own pose; the game's matrix nests, so
        // that block is free to open and close inside this one.
        UiMotion.pushFrame(graphics, 0.0F, (1.0F - open) * UiMotion.HUD_RISE);

        // Panel: the theme's sheet colour with a soft edge, drawn as rounded spans so the corners do
        // not look like a raw rectangle. The sheet is translucent in both styles -- over the world it
        // has to be, or the panel would be a hole punched in the scene rather than a card on it.
        // The fade is applied to the theme's own colour rather than to a second copy of it, so a
        // fully open panel is drawn in exactly the ink it was drawn in before there was any motion.
        fillRounded(graphics, panelX, panelY, panelX + PANEL_WIDTH, panelY + panelHeight,
                CORNER_RADIUS, UiMotion.fade(UiTheme.panel(), open));
        outlineRounded(graphics, panelX, panelY, panelX + PANEL_WIDTH, panelY + panelHeight,
                CORNER_RADIUS, UiMotion.fade(UiTheme.panelEdge(), open));

        int contentX = panelX + PADDING;
        int headerY = panelY + PADDING;
        drawHeader(graphics, mc, contentX, headerY, readout);

        int mapX = contentX;
        int mapY = headerY + HEADER_HEIGHT;
        graphics.fill(mapX - 1, mapY - 1, mapX + MAP_SIZE + 1, mapY + MAP_SIZE + 1,
                UiMotion.fade(UiTheme.panelEdge(), open));
        graphics.fill(mapX, mapY, mapX + MAP_SIZE, mapY + MAP_SIZE,
                UiMotion.fade(UiTheme.mapBackdrop(), open));
        drawMiniMap(graphics, mc, mapX, mapY);

        drawInfo(graphics, mc, contentX, mapY + MAP_SIZE + PADDING, readout);

        // Last, and unconditional within this method: every early exit that means "nothing to draw"
        // is taken in draw() before drawPanel is entered, so this pairing cannot be skipped.
        UiMotion.popFrame(graphics);
    }

    /**
     * Height of the whole panel.
     *
     * <p>The map and the header are fixed; the readout under them is as tall as what it actually
     * holds. Only the narrator hint's two rows are ever in doubt -- they are up when the speech
     * engine failed and down the rest of the time -- so the panel is the shape it has always been
     * unless that hint is being shown, rather than carrying two empty rows under the journey line
     * for the whole session. The readout carries that fact so the height and the note itself cannot
     * disagree about whether the rows exist.
     */
    private static int panelHeight(NavHudReadout readout) {
        int readoutHeight = readout.narratorHint() != null ? READOUT_HINT_HEIGHT : READOUT_HEIGHT;
        return PADDING * 2 + HEADER_HEIGHT + MAP_SIZE + readoutHeight;
    }

    private static void drawHeader(GuiGraphics graphics, Minecraft mc, int x, int y,
                                   NavHudReadout readout) {
        if (readout.destinationName() == null) {
            return;
        }
        // Always the destination. Leading with the road the player is standing on reads as the
        // panel reporting a position the player never asked about, and the road being entered is
        // already named on the instruction line below, so nothing is lost by dropping it here.
        String label = readout.headerLabel();
        // The mode shares this row rather than the readout below: the readout is already tight on
        // width at this panel size, and the mode belongs next to the heading, not buried at the end
        // of a sentence that may not fit.
        String mode = readout.modeLabel();
        int modeWidth = mc.font.width(mode);
        graphics.drawString(mc.font, mc.font.plainSubstrByWidth(label, MAP_SIZE - modeWidth - 6),
                x, y, UiMotion.fade(UiTheme.header(), open), false);
        graphics.drawString(mc.font, mode, x + MAP_SIZE - modeWidth, y,
                UiMotion.fade(UiTheme.accent(), open), false);
    }

    // ------------------------------------------------------------------ mini-map

    /**
     * The rotating mini-map: the roads and rails, the transit line strokes, the route and the places.
     *
     * <p>No transit line names are drawn on it. A line's name is a fixed-size capsule, and this map is
     * 120 pixels across: a plate that fits at all covers a large share of the ground the map exists to
     * show, which is what the player reported after seeing it in game. The two big maps keep their
     * plates -- there is room for them there -- and this pass draws the lines themselves as strokes.
     */
    private static void drawMiniMap(GuiGraphics graphics, Minecraft mc, int mapX, int mapY) {
        double playerX = mc.player.getX();
        double playerZ = mc.player.getZ();
        double scale = MAP_SIZE / (VIEW_RADIUS * 2.0);
        double centerX = mapX + MAP_SIZE / 2.0;
        double centerY = mapY + MAP_SIZE / 2.0;

        // Rotate so the direction the player faces points up, the way a navigation app does.
        // Minecraft yaw 0 faces south (+Z) with forward (-sin y, cos y); rotating world deltas by
        // (PI - yaw) sends that forward vector to screen (0, -1), i.e. straight up.
        double theta = Math.PI - Math.toRadians(mc.player.getYRot());
        double cos = Math.cos(theta);
        double sin = Math.sin(theta);

        double minX = mapX;
        double minY = mapY;
        double maxX = mapX + MAP_SIZE;
        double maxY = mapY + MAP_SIZE;

        // Everything in this block is geometry rather than a theme colour, so its share of the fade
        // is the alpha each emitter already takes -- the same fade, one layer down. Rounded rather
        // than truncated, so a half-open panel's map is not a shade darker than the sheet around it.
        int solid = (int) (0xFF * open + 0.5F);
        int pale = (int) (0x9A * open + 0.5F);

        // The mini-map is one drawing block. Before 1.21.6 that meant a live vertex consumer reached
        // through the pose; from 1.21.6 on the same block collects into a deferred batch that the GUI
        // renderer plays back at the end of the frame. HudDraw.beginBlock hides which, and the
        // emitters below take the sink it returns either way.
        HudDraw.Drawing drawing = HudDraw.beginBlock(graphics);
        HudDraw.VertexSink last = drawing.sink;

        // The map is rotated to the player's heading, so a corner of it is sqrt(2) times its
        // half-width away in world blocks. The guard has to cover the corners: at the old bound of
        // half-width plus forty -- which is *less* than the corner distance -- a road visible in a
        // corner of the panel was thrown away for being too far from the player.
        double reach = VIEW_RADIUS * Math.sqrt(2.0) + 8;
        // The same projection the old per-edge loops did, as one affine map, so the stroke pass can
        // join each road's bends instead of leaving a notch at every one of them.
        double m00 = cos * scale;
        double m01 = -sin * scale;
        double m10 = sin * scale;
        double m11 = cos * scale;
        double m02 = centerX - playerX * m00 - playerZ * m01;
        double m12 = centerY - playerX * m10 - playerZ * m11;
        RoadNetwork network = RoadStore.get();
        emitNetwork(last, network, m00, m01, m02, m10, m11, m12,
                ROAD_THICKNESS * 0.5, pale, minX, minY, maxX, maxY);

        // The machine-read rails -- Create's tracks and MTR's -- drawn in with the roads and culled by
        // the same reach: the map is a view of the ground, and a rail the player can see on the world
        // map belongs on this one too. Each layer is drawn chain by chain, so a long rail is one
        // stroke rather than one per sampled piece.
        emitNetwork(last, RailTrackStore.network(), m00, m01, m02, m10, m11, m12,
                ROAD_THICKNESS * 0.5, pale, minX, minY, maxX, maxY);
        emitNetwork(last, MtrTransit.railLayer(), m00, m01, m02, m10, m11, m12,
                ROAD_THICKNESS * 0.5, pale, minX, minY, maxX, maxY);

        // The roads read out of RoadWeaver, drawn with the same two passes and the same reach: they
        // are drawn on the world map and planned over, so the mini-map has to show them too. They are
        // drawn chain by chain -- whole roads rather than one piece at a time -- so a long road is one
        // continuous stroke. The layer holds a whole region's worth of roads, so chains outside this
        // window are skipped before anything is projected.
        emitNetworkInBox(last, RoadWeaverLayer.drawnNetwork(), m00, m01, m02, m10, m11, m12,
                ROAD_THICKNESS * 0.5, pale, minX, minY, maxX, maxY,
                playerX - reach, playerZ - reach, playerX + reach, playerZ + reach);

        // The public transport lines, with the ground they run on and under the route: the panel draws
        // the strokes so a journey can be read off the mini-map without opening the full map. No line
        // names are drawn here -- a name plate is a fixed-size capsule and this map is 120 pixels
        // across, so a plate that fits at all covers the ground the map exists to show; the two big
        // maps keep theirs. Same strokes the world map and the two viewers draw -- see
        // RoadElementRenderer.transitStrokesFor -- thinned to the zoom the same way. The player's own
        // switch hides them, as it does everywhere the filter is asked.
        if (MapFilter.showsLines()) {
            emitTransitLines(last, Navigation.linesInPlay(), m00, m01, m02, m10, m11, m12,
                    LINE_HALF_WIDTH_PX, (int) (LINE_ALPHA * open + 0.5F), minX, minY, maxX, maxY);
        }

        // The live route, in the ink each of its stretches calls for: the part behind the player
        // grey, and what is left ahead blue where it is walked, the line's own colour where it is
        // ridden. The split is made per stretch inside the drawing, so a journey is coloured the
        // whole way along instead of one mode's colour hiding the others; a walk or a drive is one
        // stretch and comes out exactly as it always did.
        HudDraw.RouteInk ink = Navigation.routeInk();
        if (ink.isPresent()) {
            HudDraw.emitRouteInk(last, ink, Navigation.travelled(), UiTheme.routeDone(),
                    m00, m01, m02, m10, m11, m12, ROUTE_THICKNESS * 0.5, solid,
                    minX, minY, maxX, maxY);
        }

        double originX = Navigation.tripOriginX();
        double originZ = Navigation.tripOriginZ();
        if (!Double.isNaN(originX)) {
            emitMarker(last, screenX(originX - playerX, originZ - playerZ, cos, sin, scale, centerX),
                    screenY(originX - playerX, originZ - playerZ, cos, sin, scale, centerY),
                    UiMotion.fade(UiTheme.origin(), open), minX, minY, maxX, maxY);
        }
        Destination destination = Navigation.target();
        if (destination != null) {
            emitMarker(last,
                    screenX(destination.x() - playerX, destination.z() - playerZ, cos, sin, scale, centerX),
                    screenY(destination.x() - playerX, destination.z() - playerZ, cos, sin, scale, centerY),
                    UiMotion.fade(UiTheme.destination(), open), minX, minY, maxX, maxY);
        }

        drawPlayerArrow(last, centerX, centerY);

        // Every place gets its marker on the mini-map too, so places can be seen while travelling
        // without opening anything. The same list and marker shape as the world map, from
        // Destinations and HudDraw, in the theme's place colour, so the two views read as one map --
        // at this map's smaller size, because a world-map marker covers a visible share of a panel
        // this size.
        for (Destination place : Destinations.places()) {
            emitPlaceMarkerClipped(last,
                    screenX(place.x() - playerX, place.z() - playerZ, cos, sin, scale, centerX),
                    screenY(place.x() - playerX, place.z() - playerZ, cos, sin, scale, centerY),
                    minX, minY, maxX, maxY);
        }

        drawMiniMapLabels(graphics, mc, network, playerX, playerZ, cos, sin, scale, centerX, centerY,
                minX, minY, maxX, maxY);

        // North indicator: with the map rotated to the player's heading, "up" is not north, so the
        // direction has to be shown explicitly.
        double northX = sin;
        double northY = -cos;
        double markerX = centerX + northX * (MAP_SIZE / 2.0 - 7);
        double markerY = centerY + northY * (MAP_SIZE / 2.0 - 7);
        if (markerX >= minX && markerX <= maxX && markerY >= minY && markerY <= maxY) {
            drawNorthMarker(graphics, mc, markerX, markerY);
        }

        HudDraw.endBlock(graphics, drawing);
    }

    /**
     * Road names on the mini-map, so a route can be read without opening the full map.
     *
     * <p>Only named roads: most of a fresh network is unnamed, and a placeholder on all of them
     * would bury the names that exist. Labels that do not fit inside the panel, or that would
     * overprint one already placed, are dropped -- the map is 120 px across and two names in the
     * same corner would smear into each other.
     *
     * <p>Whether a road is the piece that carries the name comes from the grouping, and the road is
     * culled against the panel before it is asked: this pass runs every frame the panel is up, and
     * asking {@link RoadChains#chainContaining} per named road -- which builds an adjacency index of
     * the whole network to answer -- was the panel's dominant cost and its largest source of garbage.
     */
    private static void drawMiniMapLabels(GuiGraphics graphics, Minecraft mc, RoadNetwork network,
                                          double playerX, double playerZ, double cos, double sin,
                                          double scale, double centerX, double centerY,
                                          double minX, double minY, double maxX, double maxY) {
        List<double[]> placed = new ArrayList<>();
        RoadChains.Grouping grouping = RoadChains.cachedGrouping(network);
        for (RoadSegment segment : network.segments()) {
            String name = segment.name();
            if (name == null) {
                continue;
            }
            double[] mid = segment.midpoint();
            if (Math.abs(mid[0] - playerX) > VIEW_RADIUS || Math.abs(mid[1] - playerZ) > VIEW_RADIUS) {
                continue;
            }
            if (!grouping.carriesLabel(segment)) {
                continue;
            }
            double x = screenX(mid[0] - playerX, mid[1] - playerZ, cos, sin, scale, centerX);
            double y = screenY(mid[0] - playerX, mid[1] - playerZ, cos, sin, scale, centerY);

            // Along the line, and the angle has to be taken through this map's own rotation: the panel
            // turns with the player, so a road's direction on screen is not its direction in the
            // world. The linear part of the map transform applied to the road's own world direction
            // gives the screen direction; the centre and the scale cancel out of an angle.
            int last = segment.vertexCount() - 1;
            double wdx = segment.x(last) - segment.x(0);
            double wdz = segment.z(last) - segment.z(0);
            float angle = HudDraw.labelAngle(wdx * cos - wdz * sin, wdx * sin + wdz * cos);

            int width = mc.font.width(name);
            double[] extents = HudDraw.rotatedExtents(width, LABEL_HEIGHT, angle);
            // The box that is actually painted: the text is centred on the same point it was before,
            // so rotating it does not shift it off the road.
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

            // Road names take the map-label colour rather than the readout's: the map is the
            // backdrop, so a name on it has to sit under the trip text in weight.
            //
            // A rotated label. 1.21.6 moved the GUI matrix stack from a PoseStack to JOML's
            // Matrix3x2fStack: for a 2D translate-and-rotate the two are the same operation under
            // the new name, and the stack belongs to GuiGraphics either way.
            //? if >=1.21.6 {
            /*Matrix3x2fStack pose = graphics.pose();
            pose.pushMatrix();
            pose.translate((float) x, (float) centreY);
            pose.rotate((float) angle);
            graphics.drawString(mc.font, name, -width / 2, -4,
                    UiMotion.fade(UiTheme.mapLabel(), open), false);
            pose.popMatrix();
            *///?} else {
            PoseStack pose = graphics.pose();
            pose.pushPose();
            pose.translate(x, centreY, 0.0);
            pose.mulPose(Axis.ZP.rotation(angle));
            graphics.drawString(mc.font, name, -width / 2, -4,
                    UiMotion.fade(UiTheme.mapLabel(), open), false);
            pose.popPose();
            //?}
        }
    }

    /**
     * Whether a one-block piece of a road is wholly outside the reach box around the player.
     *
     * <h2>Why "wholly", and why the box is generous</h2>
     * This is only a guard to keep far-away geometry out of the clipper; {@code emitClipped} is what
     * actually clips. The previous test asked whether <em>either</em> end was beyond the bound, which
     * threw away a road running in from outside the map even though the part inside it belonged on
     * screen -- a road at the edge of the map vanishing. A guard that is too generous costs a handful
     * of clipped quads; one that is too strict costs a visible road, so the bound is set at the map's
     * own corner distance rather than at its half-width.
     */
    private static boolean outsideReach(double ax, double az, double bx, double bz,
                                        double playerX, double playerZ, double reach) {
        return Math.min(ax, bx) > playerX + reach || Math.max(ax, bx) < playerX - reach
                || Math.min(az, bz) > playerZ + reach || Math.max(az, bz) < playerZ - reach;
    }

    /** A filled dart showing which way the player is facing: always up, given the rotation. */
    private static void drawPlayerArrow(HudDraw.VertexSink out,
                                        double cx, double cy) {
        double size = 4.5;
        emitQuad(out, 
                cx, cy - size,
                cx + size * 0.75, cy + size * 0.75,
                cx, cy + size * 0.3,
                cx - size * 0.75, cy + size * 0.75,
                UiMotion.fade(UiTheme.player(), open));
    }

    private static void drawNorthMarker(GuiGraphics graphics, Minecraft mc, double x, double y) {
        graphics.drawString(mc.font, "N", (int) Math.round(x) - 2, (int) Math.round(y) - 4,
                UiMotion.fade(UiTheme.text(), open), false);
    }

    // ------------------------------------------------------------------- info

    /**
     * The readout under the map, drawn from the value both layouts share.
     *
     * <p>The three branches are the readout's three answers, in the order they have always been asked:
     * the arrival banner, then the absence of a route, then the live trip. The two dead readings draw
     * a straight arrow in their status colour -- the arrow keeps the marker in the same cell so the
     * block does not move when the state changes, and the colour is the one the readout picked for
     * both the marker and the line.
     */
    private static void drawInfo(GuiGraphics graphics, Minecraft mc, int x, int y,
                                 NavHudReadout readout) {
        Font font = mc.font;
        if (readout.destinationName() == null) {
            return;
        }

        if (readout.arrived()) {
            NavHudShapes.drawTurnArrow(graphics, x + 11, y + 12,
                    readout.arrowDegrees(), UiMotion.fade(readout.arrowColor(), open));
            graphics.drawString(font, readout.arrivalLine(),
                    x + READOUT_TEXT_X, y + 6, UiMotion.fade(readout.arrowColor(), open), false);
            graphics.drawString(font, readout.destinationName(), x + READOUT_TEXT_X, y + 18,
                    UiMotion.fade(UiTheme.textSecondary(), open), false);
        } else if (!readout.hasRoute()) {
            NavHudShapes.drawTurnArrow(graphics, x + 11, y + 12,
                    readout.arrowDegrees(), UiMotion.fade(readout.arrowColor(), open));
            graphics.drawString(font, readout.noRouteLine(),
                    x + READOUT_TEXT_X, y + 6, UiMotion.fade(readout.arrowColor(), open), false);
        } else {
            drawRouteReadout(graphics, mc, x, y, readout);
        }

        // Last, and text, so nothing follows it through the quad consumer the arrow opened.
        drawNarratorHint(graphics, font, x, y + READOUT_HINT_Y, readout);
    }

    /**
     * The turn, the distance, the progress bar and the remaining line: the live trip readout.
     *
     * <p>Transit and turn used to be two methods because each found its own words and its own number.
     * That work is in {@link NavHudReadout} now, and the only difference left between the two readings
     * is the marker at the left: a stop square for a transit cue, a turn arrow otherwise. One method
     * draws both from one row layout, so the two cannot drift into different spacing -- which is what
     * the panel's two readouts were one small edit away from doing.
     */
    private static void drawRouteReadout(GuiGraphics graphics, Minecraft mc, int x, int y,
                                         NavHudReadout readout) {
        Font font = mc.font;
        if (readout.transit()) {
            // A square rather than an arrow: an arrow would be a claim about a junction, and there is
            // no junction being asked about -- the marker says "a stop" and nothing more.
            NavHudShapes.drawTransitMarker(graphics, x + 11, y + 12, open);
        } else {
            NavHudShapes.drawTurnArrow(graphics, x + 11, y + 12,
                    readout.arrowDegrees(), UiMotion.fade(readout.arrowColor(), open));
        }

        // Distance to the turn is the number that matters, so it gets the large treatment. "Now" has
        // already been decided by the readout, at the same distance the spoken announcement uses.
        graphics.drawString(font, readout.distance(), x + READOUT_TEXT_X, y + 2,
                UiMotion.fade(UiTheme.textStrong(), open), false);
        graphics.drawString(font,
                font.plainSubstrByWidth(readout.instruction(), MAP_SIZE - READOUT_TEXT_X - 2),
                x + READOUT_TEXT_X, y + 14, UiMotion.fade(UiTheme.textSecondary(), open), false);

        drawTripProgress(graphics, font, x, y, y + 30, readout);
    }

    /**
     * The progress bar, the remaining line and the walking-fallback note: everything under the
     * instruction, drawn from the one readout value so the live readings cannot report the trip
     * differently.
     */
    private static void drawTripProgress(GuiGraphics graphics, Font font, int x, int y, int barY,
                                         NavHudReadout readout) {
        // The bar is fed the eased fraction rather than the readout's own: a trip's progress moves in
        // steps, and a bar that jumped by those steps is the one part of this readout that would read
        // as a counter instead of as a journey. See smoothProgress.
        NavHudShapes.drawProgressBar(graphics, x, barY, MAP_SIZE, 5,
                smoothProgress(readout.progress()));

        int color = readout.offRoute() ? UiTheme.warn() : UiTheme.textSecondary();
        graphics.drawString(font, readout.remaining(), x, barY + 9, UiMotion.fade(color, open), false);

        // Why this is a walking route when another mode is selected.
        String fallback = readout.fallbackHint();
        if (fallback != null) {
            graphics.drawString(font, font.plainSubstrByWidth(fallback, MAP_SIZE),
                    x, barY + 20, UiMotion.fade(UiTheme.warn(), open), false);
        }
    }

    // ---------------------------------------------------------------- progress

    /**
     * The fraction the progress bar is drawn at, chasing the readout's own.
     *
     * <p>It lives here rather than in either layout because both draw the same bar from the same
     * trip: a copy per layout would step whenever the player switched the map display mode, and
     * there is no reason for the bar to behave differently in a smaller card.
     *
     * <p>Read exactly once per frame, by whichever layout drew the bar. A follow advances by this
     * frame's delta each time it is read, so reading it twice would run the bar at double speed.
     */
    static float smoothProgress(double target) {
        progressShown = UiMotion.follow(progressShown, (float) target, UiMotion.FOLLOW_SECONDS);
        return progressShown;
    }

    /**
     * Puts the bar back at nothing, for a layout that has finished leaving the screen.
     *
     * <p>Without this the next trip's bar would run backwards down from the last one's finish during
     * its fade-in, which reads as the progress being undone rather than as a new journey starting.
     */
    static void forgetProgress() {
        progressShown = 0.0F;
    }

    /**
     * Says why nothing is being spoken, when the player has asked for speech and cannot have it.
     *
     * <p>Only a speech engine that failed to load can cause this, so it is rare -- but a toggle that
     * looks on and produces nothing reads as a broken feature, and the log is not where a player
     * looks. Wrapped rather than clipped: it is worded to fit one line at the panel's width, and the
     * second line is there so a translation that runs longer cannot be cut in half. The note is part
     * of the readout value because the panel's height depends on whether the rows exist; the wrapping
     * stays here because it is the panel's width that decides where they break.
     */
    private static void drawNarratorHint(GuiGraphics graphics, Font font, int x, int y,
                                         NavHudReadout readout) {
        if (readout.narratorHint() == null) {
            return;
        }
        List<FormattedCharSequence> lines = font.split(readout.narratorHint(), MAP_SIZE);
        int count = Math.min(NARRATOR_HINT_LINES, lines.size());
        for (int i = 0; i < count; i++) {
            graphics.drawString(font, lines.get(i), x, y + i * NARRATOR_HINT_LINE_HEIGHT,
                    UiMotion.fade(UiTheme.warn(), open), false);
        }
    }

    // ---------------------------------------------------------------- geometry

    private static double screenX(double dx, double dz, double cos, double sin,
                                  double scale, double centerX) {
        return centerX + (dx * cos - dz * sin) * scale;
    }

    private static double screenY(double dx, double dz, double cos, double sin,
                                  double scale, double centerY) {
        return centerY + (dx * sin + dz * cos) * scale;
    }

    /** Draws a marker, skipping it entirely when it falls outside the map. */
    private static void emitMarker(HudDraw.VertexSink out, double x, double y,
                                   int argb, double minX, double minY, double maxX, double maxY) {
        if (x < minX || x > maxX || y < minY || y > maxY) {
            return;
        }
        emitDisc(out, x, y, 2.6, argb, 0xFF);
    }

    /** A place's marker, skipped entirely when it falls outside the map. */
    private static void emitPlaceMarkerClipped(HudDraw.VertexSink out, double x,
                                              double y, double minX, double minY, double maxX,
                                              double maxY) {
        if (x < minX || x > maxX || y < minY || y > maxY) {
            return;
        }
        HudDraw.emitPlaceMarker(out, x, y, HudDraw.PLACE_MARKER_SMALL_PX * 0.5,
                UiMotion.fade(UiTheme.place(), open));
    }
}
