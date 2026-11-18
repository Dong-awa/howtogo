package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.HowToGo;
import bili.dongsz.howtogo.RoadConfig;
import bili.dongsz.howtogo.road.PlaceKind;
import bili.dongsz.howtogo.road.RoadChains;
import bili.dongsz.howtogo.road.RoadClass;
import bili.dongsz.howtogo.road.RoadCurve;
import bili.dongsz.howtogo.road.RoadDirection;
import bili.dongsz.howtogo.transit.LineStop;
import bili.dongsz.howtogo.transit.TransitLine;
import bili.dongsz.howtogo.road.RoadNetwork;
import bili.dongsz.howtogo.road.RoadNode;
import bili.dongsz.howtogo.road.RoadSegment;
import bili.dongsz.howtogo.route.Destination;
import bili.dongsz.howtogo.route.Route;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
// 1.21.6 replaced the GUI's PoseStack with JOML's 2D matrix stack for label drawing.
//? if >=1.21.6 {
/*import org.joml.Matrix3x2fStack;
*///?}
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
// 1.21.8 changed what Xaero hands its element renderer: the map supplies its own graphics interface
// rather than the vanilla GUI context. Same role and same drawing calls, only the type name differs.
//? if >=1.21.6 {
/*import xaero.map.element.MapElementGraphics;
*///?} else {
import net.minecraft.client.gui.GuiGraphics;
//?}
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;
import xaero.map.element.render.ElementRenderInfo;
import xaero.map.element.render.ElementRenderLocation;
import xaero.map.element.render.ElementRenderer;
import xaero.map.graphics.renderer.multitexture.MultiTextureRenderTypeRendererProvider;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws roads and the road editing UI on Xaero's world map.
 *
 * Coordinate spaces, reconstructed from MapElementRenderHandler.transformAndRenderElement: local = (renderX / dimScale - cameraX) * info.scale; Xaero applies translate(round(local), round(local), 0) to the pose and hands renderElement the residual frac = local - round(local).
 * The pose carries no map zoom (m00 stays at 1/guiScale while the map scale sweeps orders of magnitude) and Xaero has already moved the origin to this element, so a vertex is drawn at frac + (worldOffset * info.scale).
 */
public final class RoadElementRenderer extends ElementRenderer<RoadElement, RoadRenderContext, RoadElementRenderer> {

    private static final double MIN_STROKE_PX = 1.0;

    // What is drawn at all, by zoom and by the player's switches, is MapFilter's business: the
    // thresholds used to be a footpath rule here and are now one question asked in one place.

    // Editing UI palette.
    private static final int COLOR_ENDPOINT = 0xFFE0E0E0;
    private static final int COLOR_JUNCTION = 0xFFFFFFFF;
    private static final int COLOR_SELECTED = 0xFFFF4DE0;
    private static final int COLOR_SNAP_NODE = 0xFF35E0FF;
    private static final int COLOR_SNAP_SEGMENT = 0xFFFF9A2E;
    private static final int COLOR_SNAP_ANGLE = 0xFFB06BFF;
    private static final int COLOR_SNAP_RAIL = HudDraw.COLOR_PLACE;
    private static final int COLOR_SNAP_ROADWEAVER = 0xFF9CF06A;
    private static final int COLOR_SNAP_FREE = 0x80FFFFFF;
    private static final int COLOR_RUBBER_BAND = 0xCCFF4DE0;

    private static final double NODE_HANDLE_PX = 3.0;
    private static final double SELECTED_HANDLE_PX = 4.5;
    private static final double SNAP_RING_PX = 6.0;

    private static final int DISC_SEGMENTS = 10;

    private static final double TWO_PI = Math.PI * 2.0;

    private static final int HUD_LEFT_INSET = 36;
    private static final int HUD_RIGHT_INSET = HUD_LEFT_INSET;
    private static final int HUD_MARGIN = 6;
    private static final int HUD_LINE_HEIGHT = 11;
    private static final int HUD_BG = 0xA0000000;
    private static final int HUD_FG = 0xFFE8E8E8;
    private static final int COLOR_LABEL_ROAD = 0xFFF0F0F0;
    private static final int COLOR_LABEL_RAIL = 0xFF9FD2FF;
    // The ink a sign card's text is drawn in belongs to the card's colour and is chosen from it by
    // RoadSigns.ink, the one copy of that rule for every map that draws a card.

    /**
     * The editing controls, one lang key per line. Separate keys rather than one sentence split on separators, since separators are part of the language and a vertical list wants a line per control.
     */
    private static final List<String> EDIT_HINT_KEYS = List.of(
            "hud.howtogo.edit.place",
            "hud.howtogo.edit.free_placement",
            "hud.howtogo.edit.finish",
            "hud.howtogo.edit.select",
            "hud.howtogo.edit.class",
            "hud.howtogo.edit.layer",
            "hud.howtogo.edit.name",
            "hud.howtogo.edit.oneway",
            "hud.howtogo.edit.closed",
            "hud.howtogo.edit.poi",
            "hud.howtogo.edit.navigate",
            "hud.howtogo.edit.lines",
            "hud.howtogo.edit.delete",
            "hud.howtogo.edit.undo");

    // Active navigation route.
    private static final int COLOR_ROUTE = 0xFF2FD0FF;
    private static final int COLOR_ROUTE_DONE = 0xFF6E6E6E;
    private static final int COLOR_ROUTE_START = 0xFF44FF88;
    private static final int COLOR_ROUTE_END = 0xFFFF4444;
    private static final double ROUTE_STROKE_PX = 4.0;
    private static final double ROUTE_MARKER_PX = 5.0;

    /**
     * The map scale a name's size is measured from, the size it is drawn at there, and bounds on how far it may grow or shrink.
     *
     * The font is made for reading a line of text, while a name is read at a glance among a hundred others, so every size here is a fraction of the font and the largest is barely above it. Zoomed out a name shrinks with the map until illegible; zoomed in it grows slowly across the whole range.
     */
    private static final double LABEL_NATURAL_SCALE = 0.75;
    private static final double LABEL_BASE_FACTOR = 0.8;
    private static final double LABEL_MIN_FACTOR = 0.55;
    private static final double LABEL_MAX_FACTOR = 1.1;
    private static final double LABEL_GROWTH_SPAN = 50.0;
    /**
     * How far below a place's marker its name is written, before the name's own size scales it. Scaled with the name, since a fixed pixel gap drifts when the text changes size; small because the marker already has size.
     */
    private static final double PLACE_NAME_GAP_PX = 6.0;


    private static final double LABEL_CLEARANCE_PX = 2.0;
    /**
     * The screen distance two names of one long road keep between centres, in pixels. A screen distance because the screen is where names are read: a block distance would stack names zoomed out and leave half a visible road nameless zoomed in.
     */
    private static final double LABEL_SPACING_PX = 190.0;
    private static final int MAX_LABELS_PER_ROAD = 8;
    private static final double SIGN_LABEL_SPACING_PX = 420.0;
    private static final int MAX_SIGNS_PER_ROAD = 3;
    /**
     * The map scale at which a transit line's name is drawn, in Xaero's units per block. At twenty pixels a block a name a hundred blocks long would otherwise be a band across the line it names; sign cards' own fraction of the zoom keeps the capsule readable.
     */
    private static final double LINE_NAME_LABEL_MIN_SCALE = 20.0;
    /**
     * The least real zoom at which a transit line's name is drawn, in screen pixels per block.
     *
     * A plate is a fixed-size capsule, so what makes it worth drawing is how much ground the screen shows: at two pixels a block a plate names a stretch, further out it is a badge on a diagram. Asked as well as LINE_NAME_LABEL_MIN_SCALE so the rule can only be stricter, and the projection's own pixels per block cannot drift with Xaero's units.
     */
    private static final double LINE_NAME_MIN_PPB = 2.0;
    private static final double LINE_NAME_LABEL_RISE_PX = 7.0;
    private static final int LINE_LABEL_RISE_PX = 4;
    /**
     * How far around a place its name may be pushed, in screen pixels, when a road is in the way. Roads further than this from every place being labelled cannot affect a candidate position, so they are not read into the road grid at all.
     */
    private static final double PLACE_LABEL_CLEAR_RADIUS_PX = 260.0;
    private static final float ROAD_LABEL_MIN_FACTOR = 0.5f;

    // A road may carry a category and a number, and the road_label_style setting decides whether its
    // label is the name, a sign card, or one card holding both.
    //
    // A card is a rectangle of the category's colour with the number printed in it, laid out and drawn
    // separately below, but offered the same box on the same LabelBoard, which keeps cards and names
    // from covering each other.

    /**
     * What fraction of the map's label zoom a sign card's text is drawn at, with bounds.
     *
     * A card is an object standing on the road, not a name belonging to it: at a name's size the opaque coloured card would win the contest and a street with a card would read as a card with a street under it. The fraction keeps a sign the small thing a sign is; see the two bounds below.
     */
    private static final double SIGN_CARD_ZOOM = 0.7;
    /**
     * The smallest text factor a card is drawn at, in font units: a card below it is a coloured smudge rather than a plate with a number. A floor of its own because, unlike a name, a card is never capped by the road's width.
     */
    private static final float SIGN_CARD_MIN_FACTOR = 0.5F;
    /**
     * The largest text factor a card is drawn at, in font units. At the closest zoom the label zoom has grown past the card's fraction, so a ceiling keeps it a detail rather than a billboard.
     */
    private static final float SIGN_CARD_MAX_FACTOR = 0.8F;
    /**
     * The least share of the card's own factor the mixed card's name line may shrink to, in the card's unscaled font units. Below it the second line is unreadable, so a name needing more shrink widens the card instead. See measureSignCard.
     */
    private static final double SIGN_CARD_NAME_FLOOR = 0.7;
    // The room inside a card and the ink its text takes are the card's own proportions, held by
    // RoadSigns for every map that draws one, so the same road's shield has the same shape here as on
    // the picker.
    private static final int SIGN_CARD_PAD_X = RoadSigns.PAD_X;
    private static final int SIGN_CARD_PAD_Y = RoadSigns.PAD_Y;
    private static final int SIGN_CARD_LINE_GAP = RoadSigns.LINE_GAP;

    /**
     * The size a road's name is drawn at, in font units: its zoom size, capped by the road's width. The name sits on the road, so it must not be taller than the road's drawn width -- a word taller than the street reads as one standing beside it. Both are screen pixels: stroke width at this zoom, and the font's line height.
     */
    private static float roadLabelFactor(RoadClass roadClass, double scale, Font font) {
        float zoom = labelScale(scale);
        double roadWidthPx = 2.0 * strokeHalfWidthPx(roadClass, scale);
        float widthCap = (float) (roadWidthPx / font.lineHeight);
        return Math.min(zoom, widthCap);
    }

    /**
     * A rectangle two labels may not share, in screen pixels, already inflated by the clearance. It is what is painted rather than laid out: a rotated name is measured by the box its rotation sweeps, making diagonal and horizontal names comparable.
     */
    private record LabelBox(double minX, double minY, double maxX, double maxY) {

        boolean overlaps(LabelBox other) {
            return minX < other.maxX && other.minX < maxX && minY < other.maxY && other.minY < maxY;
        }
    }

    /** The labels already on the map this frame: what a new one has to keep clear of. */
    private static final class LabelBoard {

        private final List<LabelBox> placed = new ArrayList<>();

        boolean fits(LabelBox box) {
            for (LabelBox other : placed) {
                if (box.overlaps(other)) {
                    return false;
                }
            }
            return true;
        }

        void reserve(LabelBox box) {
            placed.add(box);
        }
    }

    /**
     * A sign card measured for the frame: its size on screen and the factor its name line is drawn at, or zero without one.
     *
     * Measured once and then drawn and offered to the board from the same numbers, so the reserved box is the rectangle actually painted. factor is kept so spacing scales without the drawing code being handed the zoom; nameFactor saves the drawing code working the name's share out again.
     */
    private record SignCard(double width, double height, float factor, float nameFactor) {
    }

    private record PlaceName(String name, double x, double y, double worldX, double worldZ,
                             double markerHalf) {
    }

    private record LinePoint(double x, double y, float angle) {
    }

    /**
     * The roads already drawn, as occupied screen rectangles, so a place's name can stand aside.
     *
     * A coarse grid of cells rather than a list of every edge: a name is tested only against roads near it, cells are far larger than a label so a query is a handful of lookups whatever the network does, and building it is one walk of the visible roads the label pass already makes.
     */
    private static final class RoadInk {

        private static final int CELL_PX = 32;

        private final java.util.Map<Long, List<double[]>> cells = new java.util.HashMap<>();

        void addEdge(double x1, double y1, double x2, double y2, double halfWidthPx) {
            double pad = halfWidthPx + LABEL_CLEARANCE_PX;
            double minX = Math.min(x1, x2) - pad;
            double maxX = Math.max(x1, x2) + pad;
            double minY = Math.min(y1, y2) - pad;
            double maxY = Math.max(y1, y2) + pad;
            double[] box = {minX, minY, maxX, maxY};
            for (int cellX = cell(minX); cellX <= cell(maxX); cellX++) {
                for (int cellY = cell(minY); cellY <= cell(maxY); cellY++) {
                    cells.computeIfAbsent(key(cellX, cellY), at -> new ArrayList<>()).add(box);
                }
            }
        }

        boolean overlaps(LabelBox box) {
            for (int cellX = cell(box.minX()); cellX <= cell(box.maxX()); cellX++) {
                for (int cellY = cell(box.minY()); cellY <= cell(box.maxY()); cellY++) {
                    List<double[]> nearby = cells.get(key(cellX, cellY));
                    if (nearby == null) {
                        continue;
                    }
                    for (double[] road : nearby) {
                        if (box.minX() < road[2] && road[0] < box.maxX()
                                && box.minY() < road[3] && road[1] < box.maxY()) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }

        private static int cell(double screen) {
            return (int) Math.floor(screen / CELL_PX);
        }

        private static long key(int cellX, int cellY) {
            return ((long) cellX << 32) ^ (cellY & 0xFFFFFFFFL);
        }
    }

    /**
     * Where a world position lands on screen through the pose the map draws with right now.
     *
     * Not MapViewState: the cached view state is the camera from the render info, kept for mouse handlers outside the render pass, and it differs from the pose within a frame -- placing markers from it made transit lines, stops and places trail behind the map while panning. The pose's own arithmetic is exact: subtract the anchor it was built around, then apply the pose's scale and translate. scale is Xaero's units per block for this frame; m00/m11 are the pose's x/z scale, m30/m31 its translate.
     */
    private record Projection(double anchorX, double anchorZ, double scale, double m00, double m11,
                              double m30, double m31) {

        double screenX(double worldX) {
            return (worldX - anchorX) * scale * m00 + m30;
        }

        double screenZ(double worldZ) {
            return (worldZ - anchorZ) * scale * m11 + m31;
        }

        /**
         * The same arithmetic backwards: the world x a screen column is showing. The line pass culls with it; taking the view from the cached state would be the second reading of the frame this record exists to avoid.
         */
        double worldX(double screenX) {
            double perBlock = scale * m00;
            return Math.abs(perBlock) > 1.0E-9 ? (screenX - m30) / perBlock + anchorX : anchorX;
        }

        double worldZ(double screenY) {
            double perBlock = scale * m11;
            return Math.abs(perBlock) > 1.0E-9 ? (screenY - m31) / perBlock + anchorZ : anchorZ;
        }
    }

    /**
     * How much bigger or smaller than the font a name is drawn at this map scale. Zoomed out it shrinks with the map until illegible; zoomed in it grows slowly over the whole range, because a label already at maximum half way out makes every zoom look the same.
     */
    private static float labelScale(double scale) {
        double magnitude = Math.abs(scale);
        double reference = LABEL_NATURAL_SCALE;
        if (magnitude <= reference) {
            return (float) Math.max(LABEL_MIN_FACTOR, LABEL_BASE_FACTOR * magnitude / reference);
        }
        double grown = LABEL_BASE_FACTOR + (LABEL_MAX_FACTOR - LABEL_BASE_FACTOR)
                * Math.min(1.0, (magnitude - reference) / (reference * LABEL_GROWTH_SPAN));
        return (float) grown;
    }

    /**
     * The factor a sign card's text is drawn at: SIGN_CARD_ZOOM of the label zoom, clamped by SIGN_CARD_MIN_FACTOR and SIGN_CARD_MAX_FACTOR for a legible floor and a no-billboard ceiling; see those constants.
     *
     * Unlike a name, a card is not capped by the road's width -- it is a plate centred on the road, not a word lying along it.
     */
    private static float signCardFactor(double scale) {
        double factor = labelScale(scale) * SIGN_CARD_ZOOM;
        return (float) Mth.clamp(factor, SIGN_CARD_MIN_FACTOR, SIGN_CARD_MAX_FACTOR);
    }

    /**
     * The map's right and bottom edges, in GUI pixels.
     *
     * Read from the window on every version because Xaero's MapElementGraphics never reports a size of its own, through 26.3; its 1.21.1 counterpart read the same numbers from the window. Asking through these two keeps the five call sites identical on every version and out of reach of the build's replacement rules (a rename rule aimed at the call sites also matched this declaration). The parameter only names which graphics type the caller holds.
     */
    //? if >=1.21.6 {
    /*private static int mapViewRight(MapElementGraphics graphics) {
        return Minecraft.getInstance().getWindow().getGuiScaledWidth();
    }

    private static int mapViewBottom(MapElementGraphics graphics) {
        return Minecraft.getInstance().getWindow().getGuiScaledHeight();
    }
    *///?}
    //? if <1.21.6 {
    private static int mapViewRight(GuiGraphics graphics) {
        return Minecraft.getInstance().getWindow().getGuiScaledWidth();
    }

    private static int mapViewBottom(GuiGraphics graphics) {
        return Minecraft.getInstance().getWindow().getGuiScaledHeight();
    }
    //?}

    /**
     * Puts the pose into screen space for a pass that projects its own screen coordinates, keeping the
     * frame's depth where the map needs it.
     *
     * Such a pass must drop the pose's x/y transform, or the map's camera transform is applied a second
     * time and the content floats away from the map. The pose's z is a different matter: Xaero draws the
     * whole map at a depth of its own, and on 26.x anything drawn at z = 0 fails the depth test against
     * that depth and never shows -- which is why the transit lines, the place markers, the one-way arrows
     * and the HUD shapes all disappeared from the world map while the roads (drawn in the map's own pose)
     * stayed. From 26.1 the depth the pose already carries is kept, putting these passes in the same
     * plane as the map they belong to. Below that the transform is dropped whole, exactly as it always
     * was: those versions draw these passes at z = 0 and that is where they have always been visible.
     */
    private static void screenSpace(PoseStack pose) {
        Matrix4f screen = pose.last().pose();
        //? if >=26.1 {
        /*float depth = screen.m32();
        screen.identity();
        screen.m32(depth);
        *///?} else {
        screen.identity();
        //?}
        pose.last().normal().identity();
    }

    /**
     * One string on the map.
     *
     * Xaero's MapElementGraphics kept drawString through 26.3 even though vanilla's graphics renamed its equivalent to text; the build's rename rule rewrites every .drawString( because it cannot tell receiver apart, so these calls live in MapLabels, one file per era.
     */
    // Two independent branches rather than an if/else, matching the viewport helpers above: an else form
    // and a whole-file fence elsewhere in this file interfered once.
    //? if >=1.21.6 {
    /*private static void mapLabel(MapElementGraphics graphics, Font font, String text, int x, int y,
                                 int colour, boolean shadow) {
        MapLabels.drawOnMap(graphics, font, text, x, y, colour, shadow);
    }
    *///?}
    //? if <1.21.6 {
    private static void mapLabel(GuiGraphics graphics, Font font, String text, int x, int y,
                                 int colour, boolean shadow) {
        MapLabels.drawOnMap(graphics, font, text, x, y, colour, shadow);
    }
    //?}

    /**
     * One name on the map, centred on a point and drawn at the size the map is zoomed to. Text is drawn after the pose is flattened to screen space, so scaling is put back for the text alone: push, move to the point, scale, and write at the origin. Offsets are in the name's own units, so a centred name stays centred at every size.
     */
    //? if >=1.21.6 {
    /*private static void drawMapLabel(MapElementGraphics graphics, Font font, String name, double x, double y,
    *///?} else {
    private static void drawMapLabel(GuiGraphics graphics, Font font, String name, double x, double y,
    //?}
                                     int colour, float factor) {
        // A PoseStack on every version here, unlike elsewhere in this file: labels are drawn through Xaero's
        // own graphics type, which kept handing out a PoseStack after vanilla moved to the 2D stack.
        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(x, y, 0.0);
        pose.scale(factor, factor, 1.0F);
        mapLabel(graphics, font, name, -font.width(name) / 2, 0, colour, true);
        pose.popPose();
    }

    public RoadElementRenderer(RoadRenderContext context, RoadElementProvider provider, RoadElementReader reader) {
        super(context, provider, reader);
    }

    @Override
    public int getOrder() {
        // Below waypoints so roads never cover player markers.
        return -100;
    }

    @Override
    public boolean shouldRender(ElementRenderLocation location, boolean inMenu) {
        return location == ElementRenderLocation.WORLD_MAP;
    }

    /**
     * Our reader hands out raw world block coordinates, so the dimension scaling Xaero would otherwise apply stays at 1.0.
     */
    @Override
    public boolean shouldBeDimScaled() {
        return false;
    }

    @Override
    public void preRender(ElementRenderInfo info, MultiBufferSource.BufferSource buffers,
                          MultiTextureRenderTypeRendererProvider textureProvider, boolean inMenu) {
    }

    @Override
    public void postRender(ElementRenderInfo info, MultiBufferSource.BufferSource buffers,
                           MultiTextureRenderTypeRendererProvider textureProvider, boolean inMenu) {
    }

    @Override
    public void renderElementShadow(RoadElement element, boolean hovered, float screenSizeBasedScale,
                                    double fracX, double fracY, ElementRenderInfo info,
                                    //? if >=1.21.6 {
                                    /*MapElementGraphics graphics,
                                    *///?} else {
                                    GuiGraphics graphics,
                                    //?}
                                    MultiBufferSource.BufferSource buffers,
                                    MultiTextureRenderTypeRendererProvider textureProvider) {
    }

    @Override
    public boolean renderElement(RoadElement element, boolean hovered, double depth, float screenSizeBasedScale,
                                 double fracX, double fracY, ElementRenderInfo info,
                                 //? if >=1.21.6 {
                                 /*MapElementGraphics graphics,
                                 *///?} else {
                                 GuiGraphics graphics,
                                 //?}
                                 MultiBufferSource.BufferSource buffers,
                                 MultiTextureRenderTypeRendererProvider textureProvider) {
        PoseStack pose = graphics.pose();
        PoseStack.Pose last = pose.last();
        Matrix4f m = last.pose();

        // Cache the viewport so mouse handlers can invert this projection later in the frame.
        MapViewState.update(info, m);
        // Raw cursor position for every element: cursor-dependent actions must work with the editor closed
        // too. Snapping is recomputed once per frame in the edit branch.
        RoadEditSession.setMouse(info.mouseX, info.mouseZ);

        // Xaero's local unit step per block. Verified in-game against a line of known length (a 1000-block
        // highway measured 4000 blocks when over-scaled, pinning the factor at 4) and confirmed by the fracX
        // residual check.
        double p10 = info.scale;
        if (!(p10 > 0.0) || !Double.isFinite(p10)) {
            p10 = 1.0;
        }

        double m00 = m.m00();
        // Pose units per screen pixel.
        double posePerPixel = Math.abs(m00) > 1.0E-6 ? 1.0 / Math.abs(m00) : 1.0;

        // The source every shape in this pass is emitted into. A name is written after a flush of it, so
        // shapes and the text meant to stand above them have to share one source.
        //
        // Before 1.21.6 that source is the parameter Xaero hands over: the map's own vanilla buffer source.
        // From 1.21.6 the graphics carries the source its text is written through, and it is the one to
        // share. From 26.2 the two stopped being the same object: the parameter became Xaero's global
        // element buffer provider -- flushed only after every renderer -- while names go through the
        // graphics' own source and are painted on the spot. The label pass's flush then flushed nothing,
        // and every shape (roads, transit lines, place markers) was painted on top of the names it belongs
        // under. Sharing the graphics' source restores the order the flush is written for.
        //? if >=1.21.6 {
        /*VertexConsumer vc = graphics.getBufferSource().getBuffer(RenderType.debugQuads());
        *///?} else {
        VertexConsumer vc = buffers.getBuffer(RenderType.debugQuads());
        //?}

        if (element.kind() == RoadElement.Kind.ROUTE) {
            renderRoute(element, last, vc, fracX, fracY, p10, posePerPixel);
            // The edit UI is appended after this one, so when it is present it owns the HUD; drawing here too
            // would double up.
            if (!RoadEditSession.isActive() && !panelOverMap()) {
                // The readout is a string painted on the spot, while the route's quads are still pending in this
                // renderer's batch; flushing keeps the road off the text.
                graphics.flush();
                renderHud(graphics, pose);
            }
            return true;
        }

        if (element.kind() == RoadElement.Kind.EDIT_UI) {
            // Xaero already hands the cursor in world coordinates, so no mouse-move listener is needed;
            // refreshing here keeps dragging in step with the frame. MapViewState was updated above, which
            // snapping depends on.
            RoadEditSession.updateMouse(info.mouseX, info.mouseZ, Screen.hasAltDown());
            RoadEditSession.updateDrag();
            renderEditOverlay(element, info, last, vc, fracX, fracY, p10, posePerPixel);
            // The HUD is the map's own control scheme and readout. Held back while one of this mod's panels is
            // up, for the same reason as labels and marks: the hint block sits at the screen foot, where the
            // panel does not reach, so the key list would look like part of the line editor.
            if (!panelOverMap()) {
                // Same reason as the ROUTE branch: the handles and rubber band are still in the vertex batch, and
                // the control list is a string painted where written.
                graphics.flush();
                renderHud(graphics, pose);
            }
            return true;
        }

        if (element.kind() == RoadElement.Kind.MARKS) {
            // MTR's tracks, drawn as polylines in their own pass because what is under them (roads) and over
            // them (route, then lines) is decided by this element's place in the map's order. Not while a panel
            // is up: this pass draws over the map.
            if (!panelOverMap()) {
                int markMargin = 64;
                Projection projection = new Projection(element.anchorX(), element.anchorZ(), p10,
                        m00, m.m11(), m.m30(), m.m31());
                drawMarks(pose, vc, markMargin, mapViewRight(graphics) + markMargin,
                        mapViewBottom(graphics) + markMargin, Math.abs(info.scale), projection);
            }
            return true;
        }

        if (element.kind() == RoadElement.Kind.LABELS) {
            // Reached once per frame from the trailing element; previously names were drawn in the edit branch,
            // so turning editing off made every label vanish.
            //
            // Not while one of this mod's own panels is up: names drawn here would land across a panel's
            // contents. Where the cursor is, on every frame, because Ctrl+click sets the destination; raw
            // position only, since snapping is the editor's business.
            RoadEditSession.setMouse(info.mouseX, info.mouseZ);
            if (!panelOverMap()) {
                // Shapes before text, and lines before names: a line name under a stop marker is unreadable. Line
                // names go last, after the place markers.
                int lineMargin = 64;
                // Worked out once and handed to both passes: the stops draw an interchange as one orange marker, and
                // place markers stand aside where one is drawn. Two passes working it out separately could disagree.
                java.util.List<TransitInterchanges.Interchange> interchanges =
                        TransitInterchanges.of(Navigation.linesInPlay());
                // Everything drawn in screen coordinates goes through this, not the cached view state: the pose is
                // what the map under it was just drawn with, so the two cannot disagree while moving.
                Projection projection = new Projection(element.anchorX(), element.anchorZ(), p10,
                        m00, m.m11(), m.m30(), m.m31());
                // The one-way marks come first, from here rather than the roads' pass: they are ink about the roads,
                // so under the route, lines, markers and names and above every road. See drawOneWayMarks.
                drawOneWayMarks(pose, vc, Math.abs(info.scale), projection,
                        -lineMargin, -lineMargin,
                        mapViewRight(graphics) + lineMargin, mapViewBottom(graphics) + lineMargin);
                // The closed badges beside them, for the same reason and from the same place in the
                // drawing order. A closure matters to a reader who is not editing: the road is drawn
                // normally and nothing else on the map says it does not lead through.
                drawClosedMarks(pose, vc, Math.abs(info.scale), projection,
                        -lineMargin, -lineMargin,
                        mapViewRight(graphics) + lineMargin, mapViewBottom(graphics) + lineMargin);
                drawTransitLines(pose, vc, lineMargin, mapViewRight(graphics) + lineMargin,
                        mapViewBottom(graphics) + lineMargin, Math.abs(info.scale), interchanges,
                        projection);
                renderLabels(graphics, pose, info, vc, interchanges, projection);
                // The map's switches are not drawn here: drawn from this pass they came out under lines, markers and
                // names however late, because those use this renderer's own buffers flushed by the game. They are a
                // screen overlay drawn from the screen render event after the map finishes -- see MapFilterOverlay.
                // This is the last of the mod's passes, hence the frame report here.
                MapPassReport.endOfFrame();
            }
            return true;
        }

        RoadSegment segment = element.segment();
        if (segment == null || segment.vertexCount() < 2) {
            return false;
        }

        // Level of detail and the player's switches, one question asked in one place so what is shed when
        // zoomed out and what the panel hides cannot disagree. See MapFilter.
        if (!MapFilter.shows(segment.roadClass(), info.scale)) {
            return false;
        }

        int argb = RoadConfig.roadColor(segment.roadClass());
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        // The alpha is taken as configured: a road made translucent stays translucent, one made invisible
        // stays invisible. Default colours are opaque and every path hands an ARGB number, so no "unset"
        // alpha is left to guess.
        int a = (argb >>> 24) & 0xFF;

        // Stroke width is a screen-space quantity: below the per-class cap it tracks the road's true block
        // width at this zoom, above it the stroke stops widening. Only perpendicular thickness is affected,
        // not vertex positions.
        double halfWidth = strokeHalfWidthPx(segment.roadClass(), info.scale) * posePerPixel;

        double anchorX = element.anchorX();
        double anchorZ = element.anchorZ();

        // The segment's smoothed display line when it has one, its block vertices otherwise. A machine-read
        // railway is stored a block at a time; that staircase is right to route on and wrong to look at
        // (per-block wobble, beads at round joints).
        int pointCount = displayPointCount(segment);

        // Hand-drawn roads are selected by id in the editor's network, rails by shape key in the rail layer,
        // which has no stable ids. Both answer the same question, hence one expression.
        boolean selected = RoadEditSession.editor().selectedChain().contains(segment.id())
                || RailNameStore.isSelected(segment);
        if (selected) {
            // Translucent halo underneath, so a selected road reads clearly without hiding its class colour.
            // Same continuous stroke as the road, so the halo follows the same joins and caps.
            strokeSegment(last, vc, segment, pointCount, anchorX, anchorZ, fracX, fracY, p10,
                    halfWidth + 2.5 * posePerPixel,
                    (COLOR_SELECTED >> 16) & 0xFF, (COLOR_SELECTED >> 8) & 0xFF,
                    COLOR_SELECTED & 0xFF, 0x70);
        }

        // One continuous stroke, not a quad per edge. StrokeOutline works out the offset sides, mitre or
        // round joins and round caps, and keeps the width honest: a per-edge quad notches the outside of
        // every gentle bend, and a cross-section from the wrong side paints half the last edge.
        strokeSegment(last, vc, segment, pointCount, anchorX, anchorZ, fracX, fracY, p10, halfWidth,
                r, g, b, a);

        // One-way marks are emitted from the trailing labels pass instead: a road drawn after this one used
        // to paint straight over them, leaving arrows only on whichever roads were drawn last.

        // Rail diagnostic: one of the auto-detected layer's segments was actually stroked, past the
        // level-of-detail filter -- the difference between "never handed over" and "handed over and
        // dropped".
        if (RailTrackStore.isOurs(segment)) {
            RailTrackStore.noteElementStroked();
        }

        return true;
    }


    /**
     * Whether one of this mod's own panels is up over the map.
     * The map keeps drawing behind these panels, so every pass drawing in screen coordinates has to hold back while one is up: a road name across a list of stops, a line's marks under the line editor, and the map HUD all read as parts of the panel they are not. Asked once so the three passes cannot disagree. Asked of ParentBackdrop rather than a list of screen classes: the panels re-render the map underneath themselves, which is exactly what that marker declares; a panel added later is covered by declaring it.
     */
    private static boolean panelOverMap() {
        Screen screen = Screens.of(Minecraft.getInstance());
        // "Is the screen on show a dialog over the map", as ParentBackdrop declares: the two panels the map
        // opens and the place-details dialog from the place panel's More button. Enumerating screen classes
        // missed the last one, and labels were drawn over the details it was editing.
        return screen instanceof ParentBackdrop;
    }

    private void renderEditOverlay(RoadElement element, ElementRenderInfo info, PoseStack.Pose pose,
                                   VertexConsumer vc, double fracX, double fracY, double p10,
                                   double posePerPixel) {
        RoadNetwork network = RoadStore.get();
        var editor = RoadEditSession.editor();
        double anchorX = element.anchorX();
        double anchorZ = element.anchorZ();

        // Node handles.
        double handleHalf = NODE_HANDLE_PX * 0.5 * posePerPixel;
        double selectedHalf = SELECTED_HANDLE_PX * 0.5 * posePerPixel;
        // A place is drawn at the size every other view draws it at, converted into pose units: it is a
        // screen size, so nothing scales with the map.
        double placeHalf = HudDraw.PLACE_MARKER_PX * 0.5 * posePerPixel;
        int selectedNode = editor.selectedNodeId();

        for (RoadNode node : network.nodes()) {
            boolean isSelected = node.id() == selectedNode;
            int color = switch (node.type()) {
                case POI -> HudDraw.COLOR_PLACE;
                case JUNCTION -> COLOR_JUNCTION;
                case ENDPOINT -> COLOR_ENDPOINT;
            };
            if (isSelected) {
                color = COLOR_SELECTED;
            }
            // Places get a larger handle so they read as landmarks rather than road vertices.
            double half = isSelected ? selectedHalf
                    : (node.type() == RoadNode.Type.POI ? placeHalf : handleHalf);
            emitBox(pose, vc,
                    localX(node.x(), anchorX, fracX, p10),
                    localY(node.z(), anchorZ, fracY, p10),
                    half, half, color);
        }

        // Create's stations, as places and nothing more. The editor does not own that geometry, so there is
        // no handle and no node to select; drawn here so a station is visible while editing.
        for (RailTrackStore.Station station : RailTrackStore.stations()) {
            emitBox(pose, vc,
                    localX(station.x(), anchorX, fracX, p10),
                    localY(station.z(), anchorZ, fracY, p10),
                    placeHalf, placeHalf, HudDraw.COLOR_PLACE);
        }

        // Rubber band from the chain head to the snapped cursor.
        RoadNode head = editor.chainNodeId() == RoadSegment.NO_NODE
                ? null : network.node(editor.chainNodeId());
        if (head != null && RoadEditSession.isMouseValid()) {
            RoadSnapper.Result snap = RoadEditSession.lastSnap();
            double x1 = localX(head.x(), anchorX, fracX, p10);
            double y1 = localY(head.z(), anchorZ, fracY, p10);
            double x2 = localX(snap.x(), anchorX, fracX, p10);
            double y2 = localY(snap.z(), anchorZ, fracY, p10);
            int bandAlpha = 0xCC;
            emitStroke(pose, vc, x1, y1, x2, y2,
                    1.0 * posePerPixel,
                    (COLOR_RUBBER_BAND >> 16) & 0xFF, (COLOR_RUBBER_BAND >> 8) & 0xFF,
                    COLOR_RUBBER_BAND & 0xFF, bandAlpha);
        }

        // Snap indicator at the cursor.
        if (RoadEditSession.isMouseValid()) {
            RoadSnapper.Result snap = RoadEditSession.lastSnap();
            int color = switch (snap.kind()) {
                case NODE -> COLOR_SNAP_NODE;
                case SEGMENT -> COLOR_SNAP_SEGMENT;
                case ANGLE -> COLOR_SNAP_ANGLE;
                case RAIL -> COLOR_SNAP_RAIL;
                case ROADWEAVER -> COLOR_SNAP_ROADWEAVER;
                case FREE -> COLOR_SNAP_FREE;
            };
            double sx = localX(snap.x(), anchorX, fracX, p10);
            double sy = localY(snap.z(), anchorZ, fracY, p10);
            double ring = SNAP_RING_PX * posePerPixel;
            double thickness = 1.5 * posePerPixel;
            // Hollow square: four thin bars around the point.
            emitBox(pose, vc, sx, sy - ring, ring, thickness, color);
            emitBox(pose, vc, sx, sy + ring, ring, thickness, color);
            emitBox(pose, vc, sx - ring, sy, thickness, ring, color);
            emitBox(pose, vc, sx + ring, sy, thickness, ring, color);
        }
    }


    /**
     * Road and place names, drawn in screen space above the map.
     * What a name may cost: a map with every name is a map with none -- overlapping words read as a smear. Every label is offered a box on the screen and undrawn if it cannot have one; the board keeps the boxes already given out, one rule for roads, railways and places. Order: places first, then railways, then roads. A place's name is about one point and has nowhere else to go; a road's name can repeat further along and is the commonest, so it yields. Two close places show one name until the boxes part when zoomed in -- see MapFilter#showsLabel.
     */
    //? if >=1.21.6 {
    /*private void renderLabels(MapElementGraphics graphics, PoseStack pose, ElementRenderInfo info,
    *///?} else {
    private void renderLabels(GuiGraphics graphics, PoseStack pose, ElementRenderInfo info,
    //?}
                              VertexConsumer vc,
                              java.util.List<TransitInterchanges.Interchange> interchanges,
                              Projection projection) {
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        RoadNetwork network = RoadStore.get();

        // Labels are positioned from the view transform, not the culled element list, so a line can land far
        // outside the window; skip those rather than laying out text nobody sees.
        int margin = 64;
        int viewRight = mapViewRight(graphics) + margin;
        int viewBottom = mapViewBottom(graphics) + margin;
        double scale = Math.abs(info.scale);
        float placeFactor = labelScale(info.scale);

        pose.pushPose();
        screenSpace(pose);

        // Every place gets its marker first, at every zoom: the marker makes a place findable exactly when
        // names are suppressed for being unreadable. Shapes before text. The marker's box is reserved as it
        // is emitted, so a name never covers its own place.
        LabelBoard board = new LabelBoard();
        drawPlaceMarkers(pose.last(), vc, margin, viewRight, viewBottom, interchanges, scale,
                projection, board);

        // Hand the shapes over before the first name is written: text is painted on the spot while roads,
        // route, lines and markers still sit in the batch a flush has yet to submit, so a name written
        // first would be painted under its own map. The flush ends the source the shapes were emitted into
        // (see renderElement: on 1.21.6+ that is the graphics' own source, the one the names are written
        // through, so this flush is what puts them under a name rather than over it); nothing is emitted
        // into vc after this point.
        graphics.flush();

        // A place name stands aside from the roads next to it, so the roads near visible places are read
        // into boxes once. Only built when there is a name to place, so a map zoomed out pays nothing.
        java.util.List<PlaceName> places = visiblePlaceNames(scale, projection, margin, viewRight,
                viewBottom);
        RoadInk ink = places.isEmpty() ? null
                : buildRoadInk(scale, projection, places, margin, viewRight, viewBottom);
        for (PlaceName place : places) {
            drawPlaceName(graphics, pose, font, place, placeFactor, viewRight, viewBottom, board, ink);
        }

        // Railway names, then road names: a railway is the rarer and more specific thing, so it gets its
        // space first.
        drawLineNames(graphics, pose, font, RailTrackStore.network(), true, scale, projection, margin,
                viewRight, viewBottom, board);
        drawLineNames(graphics, pose, font, network, false, scale, projection, margin, viewRight,
                viewBottom, board);

        // The transit lines' names last among line names: a plate belongs to a service rather than the
        // ground, so it yields to roads, railways and places. Gated far above the road labels' zoom (see
        // LINE_NAME_LABEL_MIN_SCALE).
        drawTransitLineNames(graphics, font, scale, projection, margin, viewRight, viewBottom,
                board);

        pose.popPose();
    }

    /**
     * The named places whose names this zoom draws, with their markers' screen positions.
     *
     * One list for every source (player's landmarks, Create's stations, MTR's stations, an addon's) because they are all drawn as markers from the same list and labelled by the same rule. The kind decides the zoom at which the name is drawn; the marker's own kind and switch decide whether it is drawn.
     */
    private static java.util.List<PlaceName> visiblePlaceNames(double scale, Projection projection,
                                                               int margin, int viewRight,
                                                               int viewBottom) {
        java.util.List<PlaceName> names = new ArrayList<>();
        double markerHalf = markerHalfPx(HudDraw.PLACE_MARKER_PX, scale);
        for (Destination place : Destinations.places()) {
            if (place.name() == null || place.name().isBlank()) {
                continue;
            }
            if (!MapFilter.showsLabel(place.kind(), scale)) {
                continue;
            }
            double x = projection.screenX(place.x());
            double y = projection.screenZ(place.z());
            if (onScreen(x, y, margin, viewRight, viewBottom)) {
                names.add(new PlaceName(place.name(), x, y, place.x(), place.z(), markerHalf));
            }
        }
        for (RoadNode node : AddonRoadStore.nodes()) {
            if (node.type() != RoadNode.Type.POI || node.name() == null || node.name().isBlank()) {
                continue;
            }
            if (!MapFilter.showsLabel(node.placeKind(), scale)) {
                continue;
            }
            double x = projection.screenX(node.x());
            double y = projection.screenZ(node.z());
            if (onScreen(x, y, margin, viewRight, viewBottom)) {
                names.add(new PlaceName(node.name(), x, y, node.x(), node.z(), markerHalf));
            }
        }
        return names;
    }

    /**
     * Finds a side for a place's name clear of other labels and roads.
     *
     * Candidates go round the marker: below first, then above, sides, corners; the first whose box is clear of the board and drawn roads is taken. A candidate clear of the board but not the roads is remembered, so if none is clear of both, the best at least clear of other labels wins -- a name across a road is readable, a name across another name is not.
     */
    //? if >=1.21.6 {
    /*private void drawPlaceName(MapElementGraphics graphics, PoseStack pose, Font font, PlaceName place,
    *///?} else {
    private void drawPlaceName(GuiGraphics graphics, PoseStack pose, Font font, PlaceName place,
    //?}
                               float factor, int viewRight, int viewBottom, LabelBoard board,
                               RoadInk ink) {
        double width = font.width(place.name()) * factor;
        double height = font.lineHeight * factor;
        // The gap must clear the marker as well as the text: the marker's box is on the board, and a name
        // touching it would fail its own place's test.
        double gap = place.markerHalf() + PLACE_NAME_GAP_PX * factor + LABEL_CLEARANCE_PX * 2.0;
        double across = gap * 0.7;
        double[] dx = {0, 0, width / 2.0 + gap, -(width / 2.0 + gap),
                width / 2.0 + across, -(width / 2.0 + across),
                width / 2.0 + across, -(width / 2.0 + across)};
        double[] dy = {height / 2.0 + gap, -(height / 2.0 + gap), 0, 0,
                height / 2.0 + across, height / 2.0 + across,
                -(height / 2.0 + across), -(height / 2.0 + across)};

        LabelBox fallback = null;
        double fallbackX = 0;
        double fallbackY = 0;
        for (int i = 0; i < dx.length; i++) {
            double x = place.x() + dx[i];
            double y = place.y() + dy[i];
            LabelBox box = box(x, y, width, height);
            if (box.minX() < 0 || box.maxX() > viewRight
                    || box.minY() < 0 || box.maxY() > viewBottom) {
                continue;
            }
            if (!board.fits(box)) {
                continue;
            }
            if (fallback == null) {
                fallback = box;
                fallbackX = x;
                fallbackY = y;
            }
            if (ink == null || !ink.overlaps(box)) {
                board.reserve(box);
                drawMapLabel(graphics, font, place.name(), x, y, HudDraw.COLOR_PLACE, factor);
                return;
            }
        }
        if (fallback != null) {
            board.reserve(fallback);
            drawMapLabel(graphics, font, place.name(), fallbackX, fallbackY,
                    HudDraw.COLOR_PLACE, factor);
        }
    }

    /**
     * Draws one road's or railway's name at as many places along it as the screen calls for.
     * A road crossing the screen is useful at more than its middle, so a long road is named once per LABEL_SPACING_PX of drawn length, at most MAX_LABELS_PER_ROAD times, each still winning its box on the board. A name lies on the road, so its height is capped by the road's drawn width: a thin street keeps its name off until zoomed in enough, which is why names appear class by class. A road carries two identifiers: its name is a word belonging to the line, its number a classification read off a small coloured card. road_label_style decides which a labelled road shows (see RoadLabelStyle); with a sign, the card takes the name's box rather than being drawn beside it. Positions are per visible run, and the card is drawn upright and centred (see drawSignCard).
     */
    //? if >=1.21.6 {
    /*private void drawLineNames(MapElementGraphics graphics, PoseStack pose, Font font, RoadNetwork network,
    *///?} else {
    private void drawLineNames(GuiGraphics graphics, PoseStack pose, Font font, RoadNetwork network,
    //?}
                               boolean rail, double scale, Projection projection, int margin,
                               int viewRight, int viewBottom, LabelBoard board) {
        if (network == null || network.segmentCount() == 0) {
            return;
        }
        // How roads are labelled, read once per pass rather than per segment, so a value in the config
        // cannot change halfway through the pass.
        RoadLabelStyle style = RoadConfig.roadLabelStyle();
        // A railway has no category and no number -- those are the road editor's vocabulary and the rail
        // layer's names come from elsewhere -- so sign modes are the road pass's business.
        boolean signMode = !rail && style != RoadLabelStyle.NAME;
        boolean mixed = style == RoadLabelStyle.MIXED;
        float cardFactor = signCardFactor(scale);
        // A cheap "could any class be labelled at this zoom" before touching the network: a name is capped
        // by road width, so zoomed out no class can carry one.
        boolean anyClass = false;
        for (RoadClass roadClass : RoadClass.values()) {
            if (roadLabelFactor(roadClass, scale, font) >= ROAD_LABEL_MIN_FACTOR) {
                anyClass = true;
                break;
            }
        }
        // A card is not capped by the road's width, so with signs on it is drawn at zooms where every class
        // is too thin to carry a name; the cheap check would skip exactly the frames the mode exists for.
        if (!anyClass && !signMode) {
            return;
        }
        RoadChains.Grouping grouping = RoadChains.cachedGrouping(network);
        java.util.Set<Integer> roads = new java.util.HashSet<>();
        for (RoadSegment segment : network.segments()) {
            String name = segment.name();
            boolean named = name != null && !name.isBlank();
            // A sign needs a number and a category that still exists. A deleted category is one nothing defines,
            // so the road draws as unclassified -- no card, its name as label; a road with a sign and no name is
            // still labelled, since the card is its label.
            boolean sign = signMode && segment.hasSign()
                    && RoadCategoryStore.has(segment.category());
            if (!named && !sign) {
                // Only labelled roads are drawn: most of a fresh network is unnamed and a placeholder on every one
                // buries the names that exist.
                continue;
            }
            if (!MapFilter.shows(segment.roadClass(), scale)) {
                continue;
            }
            // One walk per road, not per piece: every segment of a chain carries the same name and the panel
            // writes a sign to every piece, so the chain is named and signed as one road.
            if (!roads.add(grouping.keyOf(segment))) {
                continue;
            }
            float factor = roadLabelFactor(segment.roadClass(), scale, font);
            if (!sign && factor < ROAD_LABEL_MIN_FACTOR) {
                // The road is narrower than the smallest name that could sit on it; its name waits for a zoom where
                // it is wide enough. A card is not held to that cap -- a plate centred on the road need not fit the
                // stroke.
                continue;
            }
            java.util.List<double[]> points = chainPoints(network, grouping.chainOf(segment),
                    projection);
            if (points.size() < 2) {
                continue;
            }
            SignCard card = sign ? measureSignCard(font, segment, cardFactor, mixed) : null;
            if (sign && card == null) {
                // hasSign() is number != null, so this cannot happen through the editor; treating it as no sign
                // draws the name rather than an empty coloured box.
                sign = false;
                if (!named || factor < ROAD_LABEL_MIN_FACTOR) {
                    continue;
                }
            }
            double textWidth = !sign ? font.width(name) * factor : 0;
            // The width the run must be long enough to carry: the card's footprint when a sign is drawn, the
            // painted word's otherwise -- the same "too narrow to hang a label on" test.
            double labelWidth = sign ? card.width() : textWidth;
            // Labels go on the stretches actually on screen, not the whole road: a road that leaves the screen
            // and comes back has two stretches worth naming. A stretch shorter than the label keeps none, since
            // it would hang off both ends.
            for (java.util.List<double[]> run : visibleRuns(points, viewRight, viewBottom)) {
                double runLength = polylineLength(run);
                if (runLength < labelWidth + LABEL_CLEARANCE_PX * 2.0) {
                    continue;
                }
                double spacing = Math.max(sign ? SIGN_LABEL_SPACING_PX : LABEL_SPACING_PX,
                        labelWidth + 48.0);
                int count = Math.max(1, Math.min(sign ? MAX_SIGNS_PER_ROAD : MAX_LABELS_PER_ROAD,
                        (int) Math.floor(runLength / spacing)));
                for (int i = 0; i < count; i++) {
                    LinePoint at = pointAt(run, runLength * (i + 0.5) / count);
                    if (at == null) {
                        continue;
                    }
                    if (sign) {
                        // The card's own box is worked out and offered to the board inside this call from the size measured
                        // above, so the reserved and painted rectangles are the same, both centred on the label position.
                        drawSignCard(graphics, font, segment, card, at, viewRight, viewBottom,
                                board);
                        continue;
                    }
                    LabelBox box = rotatedBox(at.x(), at.y(), textWidth, font.lineHeight * factor,
                            at.angle());
                    // The anchor, not the painted box, has to be on the map: a name whose last letter met the edge used
                    // to vanish whole and come back, which reads as flicker. The map draws to the screen edge, so the
                    // part past it is cut by the screen itself; what is reserved is still the whole painted box.
                    if (!onScreen(at.x(), at.y(), 0, viewRight - margin, viewBottom - margin)) {
                        continue;
                    }
                    if (!board.fits(box)) {
                        continue;
                    }
                    board.reserve(box);
                    drawLineLabel(graphics, pose, font, name, at,
                            rail ? COLOR_LABEL_RAIL : COLOR_LABEL_ROAD, factor);
                }
            }
        }
    }

    /**
     * Names the public transport lines, in each line's own colour, as a horizontal capsule rather than ink turned with the line: a line is a service, and the plate is the same coloured object the line is in every other list and card, so it cannot be read as a road name.
     *
     * Only at LINE_NAME_LABEL_MIN_SCALE, and only where the geometry offers a place: every LineNameLabels#SPACING_BLOCKS blocks along the track plus the free room just outside both ends. The fit test and stepping live in LineNameLabels, shared with this mod's own maps.
     */
    //? if >=1.21.6 {
    /*private void drawTransitLineNames(MapElementGraphics graphics, Font font,
                                      double scale, Projection projection, int margin,
                                      int viewRight, int viewBottom, LabelBoard board) {
    *///?} else {
    private void drawTransitLineNames(GuiGraphics graphics, Font font,
                                      double scale, Projection projection, int margin,
                                      int viewRight, int viewBottom, LabelBoard board) {
    //?}
        if (!MapFilter.showsLines() || Math.abs(scale) < LINE_NAME_LABEL_MIN_SCALE) {
            return;
        }
        java.util.List<TransitLine> lines = Navigation.linesInPlay();
        if (lines.isEmpty()) {
            return;
        }
        double screenRight = viewRight - margin;
        double screenBottom = viewBottom - margin;
        double height = LineNameLabels.height(font.lineHeight);
        // The screen as a world rectangle for the cull below: a line is tens of thousands of points and a
        // name is offered every 160 blocks, so asking the placer about every one would be the square of the
        // line's length per frame.
        double pixelsPerBlock = Math.max(Math.abs(projection.scale() * projection.m00()),
                Math.abs(projection.scale() * projection.m11()));
        if (pixelsPerBlock < LINE_NAME_MIN_PPB) {
            // Only name a line once the zoom is close enough: a fixed-size capsule on a wider view is a badge
            // over a diagram. See LINE_NAME_MIN_PPB.
            return;
        }
        double leftWorld = Math.min(projection.worldX(0), projection.worldX(screenRight));
        double rightWorld = Math.max(projection.worldX(0), projection.worldX(screenRight));
        double topWorld = Math.min(projection.worldZ(0), projection.worldZ(screenBottom));
        double bottomWorld = Math.max(projection.worldZ(0), projection.worldZ(screenBottom));
        for (NamedTransitStroke stroke : transitLineStrokesFor(lines)) {
            double width = LineNameLabels.width(font.width(stroke.name()));
            if (!(width > 0.0 && height > 0.0)) {
                continue;
            }
            double marginBlocks = (width + 32.0) / Math.max(pixelsPerBlock, 1.0E-9);
            for (LineNameLabels.Candidate candidate : LineNameLabels.candidatePoints(stroke.points())) {
                if (candidate.x() < leftWorld - marginBlocks || candidate.x() > rightWorld + marginBlocks
                        || candidate.z() < topWorld - marginBlocks
                        || candidate.z() > bottomWorld + marginBlocks) {
                    continue;
                }
                LabelBox[] claimed = new LabelBox[1];
                double[] spot = LineNameLabels.find(candidate, width, height,
                        LINE_NAME_LABEL_RISE_PX, new LineNameLabels.Screen() {
                            @Override
                            public double screenX(double worldX, double worldZ) {
                                return projection.screenX(worldX);
                            }

                            @Override
                            public double screenY(double worldX, double worldZ) {
                                return projection.screenZ(worldZ);
                            }

                            @Override
                            public boolean fits(double left, double top, double right,
                                                double bottom) {
                                if (left < 0 || right > screenRight
                                        || top < 0 || bottom > screenBottom) {
                                    return false;
                                }
                                LabelBox box = new LabelBox(left - LABEL_CLEARANCE_PX,
                                        top - LABEL_CLEARANCE_PX, right + LABEL_CLEARANCE_PX,
                                        bottom + LABEL_CLEARANCE_PX);
                                if (!board.fits(box)) {
                                    return false;
                                }
                                claimed[0] = box;
                                return true;
                            }
                        });
                if (spot == null) {
                    continue;
                }
                board.reserve(claimed[0]);
                drawLineNamePlate(graphics, font, stroke.name(), spot[0], spot[1], width, height,
                        stroke.colour());
            }
        }
    }

    /**
     * One line's name plate: a capsule in the line's colour with the name inside it. The rounding is the plate's height, so the ends are semicircles -- deliberately not a sign card. Ink comes from the single contrast rule the sign cards use.
     */
    //? if >=1.21.6 {
    /*private void drawLineNamePlate(MapElementGraphics graphics, Font font, String name,
                                   double centreX, double centreY, double width, double height,
                                   int colour) {
    *///?} else {
    private void drawLineNamePlate(GuiGraphics graphics, Font font, String name,
                                   double centreX, double centreY, double width, double height,
                                   int colour) {
    //?}
        int x1 = (int) Math.round(centreX - width / 2.0);
        int y1 = (int) Math.round(centreY - height / 2.0);
        int x2 = (int) Math.round(centreX + width / 2.0);
        int y2 = (int) Math.round(centreY + height / 2.0);
        if (x2 <= x1 || y2 <= y1) {
            return;
        }
        int ink = RoadSigns.ink(colour);
        int radius = Math.max(1, (y2 - y1) / 2);
        MapLabels.fillRoundedOnMap(graphics, x1, y1, x2, y2, radius, colour);
        MapLabels.outlineRoundedOnMap(graphics, x1, y1, x2, y2, radius, ink);
        drawMapLabel(graphics, font, name, centreX,
                centreY - font.lineHeight * LineNameLabels.NAME_FACTOR / 2.0, ink,
                LineNameLabels.NAME_FACTOR);
    }

    /**
     * One chain's vertices as one screen-space polyline in travel order.
     *
     * A chain is stored as ordered segments but each segment's vertices may run either way, so each is appended the way round that continues from the previous one, the shared corner not written twice; without that a label could land on a line that jumps from one end of the road to the other.
     */
    private static java.util.List<double[]> chainPoints(RoadNetwork network, int[] chain,
                                                       Projection projection) {
        java.util.List<double[]> points = new ArrayList<>();
        for (int id : chain) {
            RoadSegment segment = network.segment(id);
            if (segment == null || segment.vertexCount() < 2) {
                continue;
            }
            // The drawn shape, so a name sits on the line the road is painted as rather than on the block
            // staircase.
            int count = displayPointCount(segment);
            boolean reverse = false;
            if (!points.isEmpty()) {
                double[] last = points.get(points.size() - 1);
                double toStart = Math.hypot(last[0] - projection.screenX(displayX(segment, 0)),
                        last[1] - projection.screenZ(displayZ(segment, 0)));
                double toEnd = Math.hypot(last[0] - projection.screenX(displayX(segment, count - 1)),
                        last[1] - projection.screenZ(displayZ(segment, count - 1)));
                reverse = toEnd < toStart;
            }
            for (int step = 0; step < count; step++) {
                int i = reverse ? count - 1 - step : step;
                double x = projection.screenX(displayX(segment, i));
                double y = projection.screenZ(displayZ(segment, i));
                if (!points.isEmpty()) {
                    double[] last = points.get(points.size() - 1);
                    if (Math.hypot(x - last[0], y - last[1]) < 0.5) {
                        continue;
                    }
                }
                points.add(new double[]{x, y});
            }
        }
        return points;
    }

    /**
     * The stretches of a polyline that touch the window, each as its own run of points.
     *
     * A road may cross the screen, leave and come back; naming by distance along the whole length would spend names off screen. A point stays in the current run while the edge leaving it has any part in the window -- the cheap test, with label boxes culled exactly afterwards.
     */
    private static java.util.List<java.util.List<double[]>> visibleRuns(
            java.util.List<double[]> points, int viewRight, int viewBottom) {
        java.util.List<java.util.List<double[]>> runs = new ArrayList<>();
        java.util.List<double[]> current = null;
        for (int i = 1; i < points.size(); i++) {
            double[] before = points.get(i - 1);
            double[] here = points.get(i);
            boolean visible = Math.max(before[0], here[0]) >= 0
                    && Math.min(before[0], here[0]) <= viewRight
                    && Math.max(before[1], here[1]) >= 0
                    && Math.min(before[1], here[1]) <= viewBottom;
            if (visible) {
                if (current == null) {
                    current = new ArrayList<>();
                    current.add(before);
                }
                current.add(here);
            } else if (current != null) {
                runs.add(current);
                current = null;
            }
        }
        if (current != null) {
            runs.add(current);
        }
        return runs;
    }

    /**
     * The on-screen length of a polyline, in pixels.
     */    private static double polylineLength(java.util.List<double[]> points) {
        double total = 0;
        for (int i = 1; i < points.size(); i++) {
            total += Math.hypot(points.get(i)[0] - points.get(i - 1)[0],
                    points.get(i)[1] - points.get(i - 1)[1]);
        }
        return total;
    }

    /**
     * The point a given distance along a polyline, with the direction of the line there. The direction is the edge the point is on, so a bent road is named along the piece the name sits on.
     */
    private static LinePoint pointAt(java.util.List<double[]> points, double distance) {
        if (points.size() < 2) {
            return null;
        }
        double travelled = 0;
        for (int i = 1; i < points.size(); i++) {
            double dx = points.get(i)[0] - points.get(i - 1)[0];
            double dy = points.get(i)[1] - points.get(i - 1)[1];
            double length = Math.hypot(dx, dy);
            if (length < 1.0E-9) {
                continue;
            }
            if (distance <= travelled + length || i == points.size() - 1) {
                double along = Math.max(0, Math.min(1, (distance - travelled) / length));
                return new LinePoint(points.get(i - 1)[0] + dx * along,
                        points.get(i - 1)[1] + dy * along, HudDraw.labelAngle(dx, dy));
            }
            travelled += length;
        }
        return null;
    }

    /**
     * The roads already drawn, as occupied boxes, for a place name to stand aside from.
     * Built once per frame and only when a place name is placed. Every network drawing roads here contributes -- the player's, Create's rails, an addon's layers -- because a name over any of them is over a road. Segments are first culled in world space by bounding box near a named place, and only survivors are projected, so cost is proportional to roads around the visible places rather than to the whole railway.
     */
    private static RoadInk buildRoadInk(double scale, Projection projection,
                                        java.util.List<PlaceName> places, int margin, int viewRight,
                                        int viewBottom) {
        RoadInk ink = new RoadInk();
        double reachBlocks = PLACE_LABEL_CLEAR_RADIUS_PX / Math.max(scale, 1.0E-3) + 8.0;
        addRoadInk(ink, RoadStore.get().segments(), scale, projection, places, reachBlocks, margin,
                viewRight, viewBottom);
        addRoadInk(ink, RailTrackStore.segments(), scale, projection, places, reachBlocks, margin,
                viewRight, viewBottom);
        addRoadInk(ink, AddonRoadStore.segments(), scale, projection, places, reachBlocks, margin,
                viewRight, viewBottom);
        addRoadInk(ink, RoadWeaverLayer.drawnSegments(), scale, projection, places, reachBlocks, margin,
                viewRight, viewBottom);
        return ink;
    }

    private static void addRoadInk(RoadInk ink, Iterable<RoadSegment> segments, double scale,
                                   Projection projection, java.util.List<PlaceName> places,
                                   double reachBlocks, int margin, int viewRight, int viewBottom) {
        for (RoadSegment segment : segments) {
            if (segment == null || segment.vertexCount() < 2
                    || !MapFilter.shows(segment.roadClass(), scale)) {
                continue;
            }
            if (!nearAnyPlace(segment, places, reachBlocks)) {
                continue;
            }
            double halfWidth = strokeHalfWidthPx(segment.roadClass(), scale);
            double previousX = 0;
            double previousY = 0;
            int count = displayPointCount(segment);
            for (int i = 0; i < count; i++) {
                double x = projection.screenX(displayX(segment, i));
                double y = projection.screenZ(displayZ(segment, i));
                if (i > 0
                        && Math.max(previousX, x) >= -margin - 8
                        && Math.min(previousX, x) <= viewRight + 8
                        && Math.max(previousY, y) >= -margin - 8
                        && Math.min(previousY, y) <= viewBottom + 8) {
                    ink.addEdge(previousX, previousY, x, y, halfWidth);
                }
                previousX = x;
                previousY = y;
            }
        }
    }

    private static boolean nearAnyPlace(RoadSegment segment, java.util.List<PlaceName> places,
                                        double reach) {
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (int i = 0; i < segment.vertexCount(); i++) {
            minX = Math.min(minX, segment.x(i));
            maxX = Math.max(maxX, segment.x(i));
            minZ = Math.min(minZ, segment.z(i));
            maxZ = Math.max(maxZ, segment.z(i));
        }
        for (PlaceName place : places) {
            if (maxX >= place.worldX() - reach && minX <= place.worldX() + reach
                    && maxZ >= place.worldZ() - reach && minZ <= place.worldZ() + reach) {
                return true;
            }
        }
        return false;
    }

    private static boolean onScreen(double x, double y, int margin, int viewRight, int viewBottom) {
        return x >= -margin && x <= viewRight && y >= -margin && y <= viewBottom;
    }

    private static LabelBox box(double centreX, double centreY, double width, double height) {
        double halfWidth = width / 2.0 + LABEL_CLEARANCE_PX;
        double halfHeight = height / 2.0 + LABEL_CLEARANCE_PX;
        return new LabelBox(centreX - halfWidth, centreY - halfHeight,
                centreX + halfWidth, centreY + halfHeight);
    }

    private static LabelBox rotatedBox(double centreX, double centreY, double width, double height,
                                       float angle) {
        double[] extents = HudDraw.rotatedExtents(width, height, angle);
        return box(centreX, centreY, extents[0], extents[1]);
    }

    /**
     * Draws a name along its line rather than across it.
     * A road is a line and its name belongs to it; a horizontal word over a diagonal road reads as a separate object. Rotating it to the line's direction makes the name read as belonging to the road. The label pass has already flattened the pose to screen space, so the translation is in pixels and the rotation is about the label's centre; the text is drawn at the origin with half-width and half-height offsets.
     */
    //? if >=1.21.6 {
    /*private void drawLineLabel(MapElementGraphics graphics, PoseStack pose, Font font, String name,
    *///?} else {
    private void drawLineLabel(GuiGraphics graphics, PoseStack pose, Font font, String name,
    //?}
                               LinePoint at, int color, float factor) {
        // At the point the label pass walked to, not the segment's middle: the tested box and the painted
        // word used to be at different places, so a long road's labels could sit past its ends or off the
        // approved stretch. The point also carries the run's angle.
        pose.pushPose();
        pose.translate(at.x(), at.y(), 0.0);
        // 26.1 gave PoseStack a rotate(Axis, angle) taking the angle directly, so the quaternion built and
        // multiplied in is no longer needed; same rotation, one step. The two-argument form is what older
        // nodes still spell out.
        //? if >=26.3 {
        /*pose.rotate(Axis.ZP, at.angle());
        *///?} else {
        pose.mulPose(Axis.ZP.rotation(at.angle()));
        //?}
        pose.scale(factor, factor, 1.0F);
        mapLabel(graphics, font, name, -font.width(name) / 2, -LINE_LABEL_RISE_PX, color, true);
        pose.popPose();
    }


    /**
     * Measures one road's sign card at the given factor, so the pass has a size before it has a position.
     * The card is sized by the number, not the road or the name: a sign's width is the word printed on it, and a card sized by a long street name would be a plate as long as the street. The name line gives way -- shrunk to the width the number's line leaves, never enlarged -- so a mixed card stays one object. The name has a floor (SIGN_CARD_NAME_FLOOR of the card's factor); below it a name is unreadable, so the card widens instead and the number stays centred at its own size. That is the only case where the card is wider than the number plus padding. Everything is worked out in the card's own unscaled font units and multiplied by the factor once, so proportions are the same at every zoom. Returns null when there is no number to print.
     */
    private static SignCard measureSignCard(Font font, RoadSegment segment, float factor,
                                            boolean withName) {
        String number = segment.number();
        if (number == null || number.isBlank()) {
            return null;
        }
        int numberWidth = font.width(number);
        String name = withName ? segment.name() : null;
        boolean named = name != null && !name.isBlank();
        // The name line's factor relative to the card's, and the width its plate must give it. Zero means
        // "no second line", which is how the drawing code tells the two card kinds apart.
        double nameUnitFactor = 0.0;
        double nameLineUnits = 0.0;
        int innerWidth = numberWidth;
        if (named) {
            int nameWidth = font.width(name);
            double raw = (double) numberWidth / nameWidth;
            nameUnitFactor = Math.min(1.0, Math.max(SIGN_CARD_NAME_FLOOR, raw));
            nameLineUnits = font.lineHeight * nameUnitFactor + SIGN_CARD_LINE_GAP;
            // The hair is subtracted before the ceil because raw came from a division and is multiplied back
            // out; without it, a name that fits exactly can round up a pixel and widen a card the floor did not
            // ask to widen.
            innerWidth = Math.max(numberWidth,
                    (int) Math.ceil(nameWidth * nameUnitFactor - 1.0E-6));
        }
        double width = (innerWidth + SIGN_CARD_PAD_X * 2) * (double) factor;
        double height = (font.lineHeight + nameLineUnits + SIGN_CARD_PAD_Y * 2) * (double) factor;
        return new SignCard(width, height, factor, factor * (float) nameUnitFactor);
    }

    /**
     * One sign card on the map: the category's colour, a one-pixel contrasting border, the number centred, and in mixed mode the road's name shrunk beneath.
     * A name belongs to the line and turns with the road; a sign is an object standing at the roadside, a plate printed straight across, and a rotated plate reads as a label lying on the road. Upright is also the only way both lines of a mixed card can be read at once. The label positions come from the visible run, as names do, so a card repeats along a long road. Its centre is the label point, not its bottom edge: a plate centred on the stroke has the road running in and out of it, like a sign standing on the road; the small size keeps the covered stretch a stretch. All in screen space after the label pass flattened the pose; fills go through MapLabels#fillOnMap so the graphics eras are one branch in one file.
     */
    //? if >=1.21.6 {
    /*private void drawSignCard(MapElementGraphics graphics, Font font, RoadSegment segment,
                              SignCard card, LinePoint at, int viewRight, int viewBottom,
                              LabelBoard board) {
    *///?} else {
    private void drawSignCard(GuiGraphics graphics, Font font, RoadSegment segment, SignCard card,
                              LinePoint at, int viewRight, int viewBottom, LabelBoard board) {
    //?}
        String number = segment.number();
        if (card == null || number == null || number.isBlank()) {
            return;
        }
        // Centred both ways on the label position: that point is the card's centre, so the stroke passes
        // through the middle of the plate labelling it.
        double left = at.x() - card.width() / 2.0;
        double top = at.y() - card.height() / 2.0;
        double bottom = top + card.height();
        // The same box the name path offers: the painted rectangle, inflated by the label clearance.
        // Reserved before drawing, so a later name cannot land on this card.
        LabelBox box = box(at.x(), at.y(), card.width(), card.height());
        // The card's anchor -- the point on the road it stands on -- must be on the map; its painted edges
        // may hang past a screen edge as a name's may. Requiring the whole plate inside made a sign vanish
        // as it approached the edge.
        if (!onScreen(at.x(), at.y(), 0, mapViewRight(graphics), mapViewBottom(graphics))) {
            return;
        }
        if (!board.fits(box)) {
            return;
        }
        board.reserve(box);

        int background = RoadCategoryStore.color(segment.category());
        int ink = RoadSigns.ink(background);
        int x1 = (int) Math.round(left);
        int y1 = (int) Math.round(top);
        int x2 = (int) Math.round(left + card.width());
        int y2 = (int) Math.round(bottom);
        // A printed plate, not a sharp rectangle: the rounding is the same as on maps that draw cards at
        // fixed size, from RoadSigns' one geometry helper.
        MapLabels.fillRoundedOnMap(graphics, x1, y1, x2, y2, RoadSigns.CARD_CORNER_PX, background);
        // The border as a one-pixel rounded rim rather than a frame of the card's own weight: a rim that
        // scaled with zoom would be a band. Same ink as the number, so the card is one contrast decision
        // from its background.
        MapLabels.outlineRoundedOnMap(graphics, x1, y1, x2, y2, RoadSigns.CARD_CORNER_PX, ink);

        // Both lines are centred through the same helper the names use, which also keeps the two graphics
        // eras out of this method.
        double numberTop = top + SIGN_CARD_PAD_Y * card.factor();
        drawMapLabel(graphics, font, number, left + card.width() / 2.0, numberTop, ink,
                card.factor());
        if (card.nameFactor() > 0.0F) {
            double nameTop = numberTop + (font.lineHeight + SIGN_CARD_LINE_GAP) * card.factor();
            drawMapLabel(graphics, font, segment.name(), left + card.width() / 2.0, nameTop, ink,
                    card.nameFactor());
        }
    }

    /**
     * A yellow square at every place.
     * Emitted with the flattened pose: the marker pass runs after the pose is flattened, so coordinates are screen pixels and pose.last() is the flattened pose. Emitting screen coordinates with the map pose applies the transform twice -- what put the first version off to one side. Places come from Destinations#places(), the same list the panel and picker mark, so the views cannot disagree. Shapes before text.
     */
    private static final double LINE_STROKE_PX = 2.5;
    private static final double MIN_LINE_STROKE_PX = 0.9;
    private static final double FULL_LINE_STROKE_SCALE = 0.5;
    /**
     * How many bands of zoom one line's thinned geometry is kept for. A band is a power of two pixels per block (see PathRuns), so it changes on zoom, not pan; a handful covers a session twice over, the oldest is displaced, and a band asked again costs one walk.
     */
    private static final int BANDS = 6;
    private static final double LINE_STOP_PX = 5.0;
    /**
     * How far apart the direction marks of a one-way line stand, in blocks.
     *
     * A world distance, unlike a one-way road's screen-spaced arrows: a road's arrows are a reading of the map at the current zoom, while a line's direction is a property of the service, so the distance must not change with the zoom. The mark's screen size is fixed at TRANSIT_ARROW_PX.
     */
    private static final double TRANSIT_ARROW_SPACING_BLOCKS = 64.0;
    /**
     * How long a stretch of a one-way line must be before it is marked, in blocks. Below it a mark would sit on both end stop markers; above it, a shorter stretch still gets one mark at its middle, so a line is never the one with nothing saying which way it runs.
     */
    private static final double TRANSIT_ARROW_MIN_RUN_BLOCKS = 24.0;
    /**
     * The most marks one stretch of one line draws. The spacing decides the count; this bounds a stretch long enough that marks stop being a reading of direction. Such a stretch is marked evenly along itself, so the bound thins rather than truncates.
     */
    private static final int TRANSIT_ARROW_MAX_PER_RUN = 8;
    /**
     * The most marks one line draws across all its stretches: the per-stretch bound says nothing about a line of a hundred stretches, and every mark is two triangles in the frame buffer.
     */
    private static final int TRANSIT_ARROW_MAX_PER_LINE = 32;
    /**
     * The length of one direction mark, in screen pixels. Half of it is the distance from the point to the tip, four to five pixels: enough beside a 2.5 px stroke, not enough to be a second marker. Fixed in pixels because it is a reading, like stroke width.
     */
    private static final double TRANSIT_ARROW_PX = 9.0;
    /**
     * The map scale at which a marker is drawn at HudDraw#PLACE_MARKER_PX across, with bounds on how far it may grow or shrink.
     *
     * A marker is a place on the ground, so it is drawn at the size of a place: a fixed pixel count would cover half a village zoomed in and vanish zoomed out, and the bounds keep it from being a wall or a speck.
     */
    private static final double MARKER_NATURAL_SCALE = 0.5;
    private static final double MARKER_MIN_HALF_PX = 1.4;
    private static final double MARKER_MAX_HALF_PX = 2.2;
    /**
     * How close two interchange markers must land to be drawn as one, in pixels: twice the marker radius, where they are just touching.
     */
    private static final int COLOR_LINE_TRANSFER = 0xFFFF7A3C;
    /**
     * The dark backing under a stop marker. A stop is drawn in its line's colour, and a coloured square on pale ground is otherwise invisible; this used to be the place marker's yellow, which made a stop look like an ordinary place.
     */
    private static final int COLOR_LINE_STOP_EDGE = 0xFF10141A;

    /**
     * The public transport lines, drawn over everything else and at every zoom.
     * Straight from stop to stop, which is the service rather than the track: the rails and roads a line runs along are already drawn by the layer that owns them. This adds what nothing else can say -- which stops belong to which line, in which order, and where two lines meet. An interchange is marked by counting, not comparing: it gets its own colour and is drawn after ordinary stops so it is never hidden.
     */
    private static String lineShapeSignature = "";
    private static List<LineShape> lineShapes = List.of();
    /**
     * The same track, flattened into the strokes the mod's own small maps draw.
     *
     * One entry per stretch of one line with the line's colour: the main screen's viewer and the picker's map project these themselves and have no Xaero buffer, so they need geometry and colour. Rebuilt on a change of lines, not per frame, because the two small maps ask every frame.
     */
    private static List<TransitStroke> transitStrokes = List.of();
    /**
     * The direction marks of the one-way lines, one list per line and in step with lineShapes.
     * Worked out with the shapes, since a mark stands every TRANSIT_ARROW_SPACING_BLOCKS along the path -- a walk of the path and the same answer until the line changes. Planning in the rebuild turns a per-frame walk of every line into a walk of the changed ones. A two-way line's entry is an empty list rather than absent: the drawing pass walks by line index and a hole would need explaining there.
     */
    private static List<List<LineArrow>> lineArrows = List.of();

    /**
     * One direction mark on a one-way line: where it stands in the world and the unit direction a rider travels through it. World blocks, not pixels -- the zoom moves under it, so the place and bearing are cached and projected each frame.
     */
    private record LineArrow(double x, double z, double dirX, double dirZ) {
    }

    public record TransitStroke(List<double[]> points, int colour) {
    }

    /**
     * The track each line runs along, in world blocks, one stroke per stretch, for the maps this mod draws itself.
     *
     * The world map draws the same shapes in its own pass; these project them with their own camera, so they ask for geometry. The shapes are the planner's -- each neighbouring pair routed as the router will route it -- so a line bends where it bends instead of cutting the corner.
     */
    public static List<TransitStroke> transitStrokesFor(List<TransitLine> lines) {
        shapeLines(lines);
        return transitStrokes;
    }

    public record NamedTransitStroke(String name, int colour, List<double[]> points) {
    }

    /**
     * The same strokes as transitStrokesFor, each carrying its line's name, for the name plates. The geometry is the cached shapes' own point lists -- nothing is copied.
     */
    public static List<NamedTransitStroke> transitLineStrokesFor(List<TransitLine> lines) {
        shapeLines(lines);
        List<NamedTransitStroke> named = new java.util.ArrayList<>();
        for (int index = 0; index < lines.size() && index < lineShapes.size(); index++) {
            TransitLine line = lines.get(index);
            String name = line.name();
            if (name == null || name.isBlank()) {
                continue;
            }
            // A name plate is part of the line's presence on the map, so it uses the colour the line itself is
            // drawn in.
            int colour = line.colorOr(RoadConfig.transitLineColor(line.kind()));
            for (PathRuns.Run run : lineShapes.get(index).runs()) {
                named.add(new NamedTransitStroke(name, colour, run.points()));
            }
        }
        return named;
    }
    private static int reportedLineCount = -1;
    /**
     * One line's shape, kept until that line's own stops, kind or track change.
     *
     * Per line rather than for all of them: imported MTR lines arrive a window at a time and change as the player walks, and rebuilding every line because one gained a stop would hitch on the render thread.
     */
    private static final java.util.Map<String, LineShape> lineShapeCache =
            new java.util.HashMap<>();

    /**
     * One line's drawn shape: the known stretches, each with its box, thinned once per zoom band and kept.
     *
     * A line of several stretches is several polylines, not one: joining them would draw a straight line across every gap, which is either missing track or stops the router could not join (see TrackRuns).
     * No straight hop is drawn for a line with no stretch at all: it would be a line across everything between two stations, belonging to no path. Its stops are marked anyway.
     * Thinning is kept per zoom band: an MTR line is tens of thousands of points sampled every few blocks and redrawn every frame, and most steps are a fraction of a pixel at readable zooms. Each stretch is thinned to the zoom (see PathRuns) and kept, since the zoom moves in steps.
     */
    private static final class LineShape {

        private final List<PathRuns.Run> runs;
        private final Band[] bands = new Band[BANDS];
        private int nextBand;

        static final LineShape EMPTY = new LineShape(List.of());

        private LineShape(List<PathRuns.Run> runs) {
            this.runs = runs;
        }

        static LineShape of(List<List<double[]>> runs) {
            List<PathRuns.Run> wrapped = new java.util.ArrayList<>(runs.size());
            for (List<double[]> run : runs) {
                if (run.size() >= 2) {
                    wrapped.add(PathRuns.of(run));
                }
            }
            return wrapped.isEmpty() ? EMPTY : new LineShape(List.copyOf(wrapped));
        }

        List<PathRuns.Run> runs() {
            return runs;
        }

        /**
         * The stretches as this zoom draws them. pixelsPerBlock is how many screen pixels one block covers, which is the zoom the thinning must be fine enough for.
         */
        List<PathRuns.Run> runsAt(double pixelsPerBlock) {
            int band = PathRuns.bandOf(pixelsPerBlock);
            for (Band kept : bands) {
                if (kept != null && kept.band() == band) {
                    return kept.runs();
                }
            }
            double tolerance = PathRuns.toleranceFor(pixelsPerBlock);
            List<PathRuns.Run> thinned = new java.util.ArrayList<>(runs.size());
            for (PathRuns.Run run : runs) {
                thinned.add(PathRuns.thinned(run, tolerance));
            }
            Band made = new Band(band, List.copyOf(thinned));
            bands[nextBand] = made;
            nextBand = (nextBand + 1) % bands.length;
            return made.runs();
        }
    }

    private record Band(int band, List<PathRuns.Run> runs) {
    }

    /**
     * Works out the path each line runs along, planning each pair of neighbouring stops exactly as the router will.
     *
     * A straight hop is not the line: a railway between two stations may run a long way round, and the chord would hide where the line goes. Planning costs a route per pair, so the result is cached and rebuilt only when a line's kind or stops change. A pair that cannot be planned gets no hop -- a gap, not a line across ground it may not run over.
     */
    private static void refreshLineShapes(List<TransitLine> lines) {
        StringBuilder signature = new StringBuilder();
        for (TransitLine line : lines) {
            signature.append(shapeKey(line));
            // The direction marks are built from the direction as well as the geometry, so a line whose
            // direction switched must rebuild them. The direction is deliberately not in shapeKey, which the
            // shape cache also uses: it would re-plan every route of the line for a switch that does not move
            // the geometry.
            signature.append(line.oneWay() ? "+oneway" : "+twoway");
        }
        if (signature.toString().equals(lineShapeSignature)) {
            return;
        }
        lineShapeSignature = signature.toString();

        // One workspace per network rather than per line: a workspace copies the network before its first
        // query, and every line routing on the same roads reuses one.
        java.util.Map<String, bili.dongsz.howtogo.route.RoadRouter.Workspace> workspaces =
                new java.util.HashMap<>();
        java.util.Map<String, LineShape> rebuilt = new java.util.HashMap<>();
        List<LineShape> shapes = new java.util.ArrayList<>(lines.size());
        for (TransitLine line : lines) {
            String key = shapeKey(line);
            LineShape shape = lineShapeCache.get(key);
            if (shape == null) {
                shape = planLine(line, workspaces);
            }
            rebuilt.put(key, shape);
            shapes.add(shape);
        }
        // Only what this pass asked for is kept, so a line that is gone does not keep its shape alive.
        lineShapeCache.clear();
        lineShapeCache.putAll(rebuilt);
        lineShapes = shapes;
        // The same shapes flattened for the small maps: one stroke per stretch with the line's colour.
        // Points are the runs' own lists, nothing copied.
        List<TransitStroke> strokes = new java.util.ArrayList<>();
        for (int index = 0; index < lines.size() && index < shapes.size(); index++) {
            TransitLine line = lines.get(index);
            int colour = line.colorOr(RoadConfig.transitLineColor(line.kind()));
            for (PathRuns.Run run : shapes.get(index).runs()) {
                strokes.add(new TransitStroke(run.points(), colour));
            }
        }
        transitStrokes = strokes;
        // The one-way lines' direction marks, built beside the same shapes: a mark is a world position and
        // bearing taken from the unthinned run. Rebuilt only when this method rebuilds, so the per-frame
        // pass projects a prepared list.
        List<List<LineArrow>> arrows = new java.util.ArrayList<>(lines.size());
        for (int index = 0; index < lines.size() && index < shapes.size(); index++) {
            arrows.add(planLineArrows(lines.get(index), shapes.get(index)));
        }
        lineArrows = arrows;
        reportLongSteps(lines, shapes);
    }

    /**
     * How long a step between neighbouring points of a drawn line must be before it is worth reporting, in blocks.
     *
     * A rail's geometry is sampled every few blocks, so a longer step inside a stretch is either a genuinely straight rail or a straight line drawn between places the geometry does not join -- and the two look identical on the map.
     */
    private static final double LONG_STEP_BLOCKS = 64.0;

    private static final int MAX_REPORTED_LONG_STEPS = 12;

    /**
     * Names the lines whose drawn shape takes a long step between two of its own points.
     *
     * Written on rebuild, not per frame, and only for lines with such a step. A two-point stretch a kilometre long is a straight line; a hundred-point stretch with a twenty-block longest step is a railway. The difference is invisible on the map, which is why it is worth a log line.
     */
    private static void reportLongSteps(List<TransitLine> lines, List<LineShape> shapes) {
        int offenders = 0;
        int named = 0;
        for (int index = 0; index < lines.size() && index < shapes.size(); index++) {
            LineShape shape = shapes.get(index);
            double longest = 0;
            double[] from = null;
            double[] to = null;
            int points = 0;
            for (PathRuns.Run run : shape.runs()) {
                List<double[]> runPoints = run.points();
                points = Math.max(points, runPoints.size());
                for (int i = 1; i < runPoints.size(); i++) {
                    double[] before = runPoints.get(i - 1);
                    double[] here = runPoints.get(i);
                    double step = Math.hypot(here[0] - before[0], here[1] - before[1]);
                    if (step > longest) {
                        longest = step;
                        from = before;
                        to = here;
                    }
                }
            }
            if (longest <= LONG_STEP_BLOCKS) {
                continue;
            }
            offenders++;
            if (named >= MAX_REPORTED_LONG_STEPS) {
                continue;
            }
            named++;
            TransitLine line = lines.get(index);
            HowToGo.diagnostic("[HowToGo] line '{}' ({}) draws a straight step of {} blocks, from "
                            + "({}, {}) to ({}, {}), in a stretch of {} point(s) of {} in all; {} "
                            + "stop(s), {}",
                    line.id(), line.kind(), Math.round(longest),
                    Math.round(from[0]), Math.round(from[1]), Math.round(to[0]), Math.round(to[1]),
                    points, shape.runs().size(), line.stopCount(),
                    MtrTransit.isImported(line) ? "read out of MTR" : "the player's own");
        }
        if (offenders > 0) {
            HowToGo.diagnostic("[HowToGo] {} of {} line(s) draw a step longer than {} blocks",
                    offenders, lines.size(), Math.round(LONG_STEP_BLOCKS));
        }
    }

    /**
     * What makes a line's shape its own: line, kind, stops, roads, and track.
     *
     * A windowed reading adds track a piece at a time without changing the stops, so a key built from stops alone would say "same line" while the track fills in, and a shape from the first window would be drawn for the rest of the session. The track's own reading -- segment count and revision -- is therefore part of the key.
     */
    private static String shapeKey(TransitLine line) {
        StringBuilder key = new StringBuilder();
        key.append(line.id()).append(line.kind().name());
        // Whether the line rides its own marks is part of the shape: turning imported rails off must redraw
        // the line along the roads it is now ridden over.
        key.append(MtrTransit.marksEnabled(line) ? "+marks" : "-marks");
        for (LineStop stop : line.stops()) {
            key.append('|').append(stop.x()).append(',').append(stop.z());
        }
        bili.dongsz.howtogo.road.RoadNetwork track = MtrTransit.trackOf(line);
        key.append("*/").append(track == null
                ? "none"
                : track.segmentCount() + ":" + track.revision());
        return key.toString();
    }

    /**
     * One line's path: its own track where it has one, a planned route over the roads where it does not.
     * An MTR line is drawn along its track whether or not its marks are on -- the switch decides only whether that track joins the road network as rail or water roads, not where the line is drawn. Planning it over roads instead is what made a switched-off line collapse into straight hops and look lost. An imported line whose track is unknown is drawn as nothing: its stations are marked, and a hop would claim ground it may not cross. It reappears when a window brings its rails. A player's line has no track, so its planned route is drawn; an unplannable pair is a gap rather than a hop, the same rule as an imported line. Hops used to produce a web of straight lines across the map, each claiming ground the line does not run over. Every stop is still marked.
     */
    private static LineShape planLine(TransitLine line,
                                      java.util.Map<String,
                                                   bili.dongsz.howtogo.route.RoadRouter.Workspace>
                                                   workspaces) {
        List<List<double[]>> runs = TrackRuns.of(MtrTransit.trackOf(line));
        if (!runs.isEmpty()) {
            return LineShape.of(runs);
        }
        if (MtrTransit.isImported(line)) {
            return LineShape.EMPTY;
        }
        List<List<double[]>> stretches = new java.util.ArrayList<>();
        RoadClass kind = line.kind();
        bili.dongsz.howtogo.route.TravelMode mode =
                bili.dongsz.howtogo.route.LinePlanner.rideMode(kind);
        bili.dongsz.howtogo.route.RoutePreferences policy =
                bili.dongsz.howtogo.route.LinePlanner.ridePreferences(kind,
                        bili.dongsz.howtogo.store.RoutePreferenceStore.preferences());
        boolean marks = MtrTransit.marksEnabled(line);
        String workspaceKey = kind.name() + (marks ? "+marks" : "");
        bili.dongsz.howtogo.route.RoadRouter.Workspace workspace = workspaces.get(workspaceKey);
        if (workspace == null) {
            workspace = new bili.dongsz.howtogo.route.RoadRouter.Workspace(
                    RailTrackStore.forRouting(mode, policy, marks));
            workspaces.put(workspaceKey, workspace);
        }
        for (int i = 1; i < line.stopCount(); i++) {
            LineStop from = line.stops().get(i - 1);
            LineStop to = line.stops().get(i);
            bili.dongsz.howtogo.route.Route ride = bili.dongsz.howtogo.route.RoadRouter.findRoute(
                    workspace, from.x(), from.z(), to.x(), to.z(), "", mode, policy);
            // Null for a pair with no route: a gap in the drawing, not a straight line across it.
            stretches.add(ride.isPresent() ? ride.points() : null);
        }
        return LineShape.of(TrackRuns.ofPlanned(stretches));
    }

    /**
     * Works out where the direction marks of one line go, from its stops and drawn shape.
     * One mark every TRANSIT_ARROW_SPACING_BLOCKS of drawn path, on each stretch of a one-way line; a two-way line gets an empty list. A run's stored order is not the travel order -- a player's line is planned stop to stop, while an MTR track is drawn in chain-walk order. So a run is oriented by the line's stops: nearest stop at the run's first and last points, larger index meaning travel towards the list's end (see LineDirection). A closed loop has the same stop at both ends, so it is asked again at a quarter and three quarters of its length: a loop passes its stops in list order, so those samples stand next to different stops. Only when those indices are equal too is the stretch left unmarked rather than given an invented direction. Nearest stop is by distance alone, which assumes a stretch begins and ends near the stops it serves; the one miss is a line passing close to a stop it does not call at, and there is no other evidence -- the direction is not stored on the geometry.
     */
    private static List<LineArrow> planLineArrows(TransitLine line, LineShape shape) {
        if (!line.oneWay()) {
            return List.of();
        }
        List<LineStop> stops = line.stops();
        if (stops.size() < 2) {
            return List.of();
        }
        List<LineArrow> arrows = new java.util.ArrayList<>();
        for (PathRuns.Run run : shape.runs()) {
            if (arrows.size() >= TRANSIT_ARROW_MAX_PER_LINE) {
                break;
            }
            addRunArrows(stops, run.points(), arrows, TRANSIT_ARROW_MAX_PER_LINE - arrows.size());
        }
        return arrows.isEmpty() ? List.of() : List.copyOf(arrows);
    }

    /**
     * Adds the marks of one run of a one-way line, evenly spaced along it.
     *
     * Evenly spaced once the per-run bound applies: a run longer than the bound gets marks at length / count blocks, so the whole run is marked. Below the bound the spacing is the world spacing. Direction is runTravelSign's answer; an unorientable run is left unmarked.
     */
    private static void addRunArrows(List<LineStop> stops, List<double[]> points,
                                     List<LineArrow> arrows, int budget) {
        if (points.size() < 2 || budget <= 0) {
            return;
        }
        double length = 0.0;
        for (int i = 1; i < points.size(); i++) {
            length += Math.hypot(points.get(i)[0] - points.get(i - 1)[0],
                    points.get(i)[1] - points.get(i - 1)[1]);
        }
        if (!(length > 0.0)) {
            return;
        }
        double travel = runTravelSign(stops, points, length);
        if (travel == 0.0) {
            // Neither the run's ends nor a sample a quarter and three quarters along it could tell which way it
            // is ridden: a mark there would be a guess. See planLineArrows.
            return;
        }
        int count = (int) Math.floor(length / TRANSIT_ARROW_SPACING_BLOCKS);
        if (count == 0 && length >= TRANSIT_ARROW_MIN_RUN_BLOCKS) {
            count = 1;
        }
        count = Math.min(count, Math.min(budget, TRANSIT_ARROW_MAX_PER_RUN));
        if (count <= 0) {
            return;
        }
        double step = length / count;
        double nextAt = step * 0.5;
        double travelled = 0.0;
        int placed = 0;
        for (int i = 1; i < points.size() && placed < count; i++) {
            double fromX = points.get(i - 1)[0];
            double fromZ = points.get(i - 1)[1];
            double deltaX = points.get(i)[0] - fromX;
            double deltaZ = points.get(i)[1] - fromZ;
            double edge = Math.hypot(deltaX, deltaZ);
            if (edge < 1.0E-9) {
                continue;
            }
            while (placed < count && nextAt <= travelled + edge) {
                double at = (nextAt - travelled) / edge;
                // The bearing is the local edge's, so a mark on a bend follows the bend rather than all pointing the
                // way the run began.
                arrows.add(new LineArrow(fromX + deltaX * at, fromZ + deltaZ * at,
                        travel * deltaX / edge, travel * deltaZ / edge));
                placed++;
                nextAt += step;
            }
            travelled += edge;
        }
    }

    /**
     * Which way round a run is written relative to the line's travel order.
     *
     * The run's own ends give the first answer; a closed loop has the same stop at both ends, so it is asked at a quarter and three quarters of its length instead. Returns +1 when stored order is travel order, -1 when against it, 0 when neither pair of samples could tell.
     */
    private static double runTravelSign(List<LineStop> stops, List<double[]> points, double length) {
        int fromIndex = nearestStop(stops, points.get(0)[0], points.get(0)[1]);
        double[] last = points.get(points.size() - 1);
        int toIndex = nearestStop(stops, last[0], last[1]);
        if (fromIndex != toIndex) {
            return toIndex > fromIndex ? 1.0 : -1.0;
        }
        // A quarter and three quarters along rather than the ends, which for a loop are the same place; a
        // loop passes its stops in list order, so the samples stand next to different stops unless the list
        // crowds one stretch.
        double[] quarter = pointAt(points, length, 0.25);
        double[] threeQuarters = pointAt(points, length, 0.75);
        int quarterIndex = nearestStop(stops, quarter[0], quarter[1]);
        int threeQuarterIndex = nearestStop(stops, threeQuarters[0], threeQuarters[1]);
        if (quarterIndex == threeQuarterIndex) {
            return 0.0;
        }
        return threeQuarterIndex > quarterIndex ? 1.0 : -1.0;
    }

    /**
     * The point a given fraction along a run, interpolated inside the edge it falls on. Used only to orient a closed loop, and only on rebuild -- never per frame.
     */
    private static double[] pointAt(List<double[]> points, double length, double fraction) {
        double target = length * fraction;
        double travelled = 0.0;
        for (int i = 1; i < points.size(); i++) {
            double fromX = points.get(i - 1)[0];
            double fromZ = points.get(i - 1)[1];
            double deltaX = points.get(i)[0] - fromX;
            double deltaZ = points.get(i)[1] - fromZ;
            double edge = Math.hypot(deltaX, deltaZ);
            if (edge > 0.0 && travelled + edge >= target) {
                double at = (target - travelled) / edge;
                return new double[] {fromX + deltaX * at, fromZ + deltaZ * at};
            }
            travelled += edge;
        }
        double[] last = points.get(points.size() - 1);
        return new double[] {last[0], last[1]};
    }

    private static int nearestStop(List<LineStop> stops, double x, double z) {
        int nearest = 0;
        double best = Double.POSITIVE_INFINITY;
        for (int index = 0; index < stops.size(); index++) {
            LineStop stop = stops.get(index);
            double deltaX = stop.x() - x;
            double deltaZ = stop.z() - z;
            double distance = deltaX * deltaX + deltaZ * deltaZ;
            if (distance < best) {
                best = distance;
                nearest = index;
            }
        }
        return nearest;
    }

    private List<LineLabel> drawTransitLines(PoseStack pose, VertexConsumer vc, int margin,
                                             int viewRight, int viewBottom, double scale,
                                             java.util.List<TransitInterchanges.Interchange>
                                                     interchanges,
                                             Projection projection) {
        List<LineLabel> labels = new java.util.ArrayList<>();
        // The player's lines and the ones read out of MTR: a line the mod will plan a journey over is one
        // whose route the map should show, whichever it came from.
        List<TransitLine> lines = Navigation.linesInPlay();
        reportLines(lines);
        if (lines.isEmpty()) {
            return labels;
        }
        // The screen-space transform, taken exactly as renderLabels takes it: coordinates below are already
        // screen space, so the pose may carry no x/y transform -- the map's own transform applied twice
        // floats a line away from its stops. Its depth is kept; see screenSpace.
        pose.pushPose();
        screenSpace(pose);
        PoseStack.Pose screenPose = pose.last();

        // The shapes the lines are drawn from, brought up to date here rather than kept by the line list:
        // the track under a line arrives a window at a time.
        shapeLines(lines);

        // The zoom and the screen's piece of the world, both from this frame's own projection so culling and
        // drawing cannot disagree.
        double pixelsPerBlock = Math.max(Math.abs(projection.scale() * projection.m00()),
                Math.abs(projection.scale() * projection.m11()));
        double leftEdge = projection.worldX(-margin);
        double rightEdge = projection.worldX(viewRight);
        double topEdge = projection.worldZ(-margin);
        double bottomEdge = projection.worldZ(viewBottom);
        double minWorldX = Math.min(leftEdge, rightEdge);
        double maxWorldX = Math.max(leftEdge, rightEdge);
        double minWorldZ = Math.min(topEdge, bottomEdge);
        double maxWorldZ = Math.max(topEdge, bottomEdge);
        int[] culled = {0};

        long startedAt = System.nanoTime();
        int sourcePoints = 0;
        for (int index = 0; index < lines.size(); index++) {
            for (PathRuns.Run run : shapeAt(index).runs()) {
                sourcePoints += run.size();
            }
        }

        // The lines, never shed by zoom: they are what this map is for and the one thing the roads do not
        // already imply. The thinning is what keeps a whole railway affordable.
        int stroked = 0;
        if (MapFilter.showsLines()) {
            for (int index = 0; index < lines.size(); index++) {
                TransitLine line = lines.get(index);
                // Each stretch on its own: the gaps are track this client has not been sent or a pair of stops the
                // router could not join, and a line drawn across one covers ground it does not. A line with no
                // stretch draws nothing; its stops are still marked.
                stroked += drawRuns(screenPose, vc, shapeAt(index).runsAt(pixelsPerBlock), projection,
                        margin, viewRight, viewBottom, minWorldX, minWorldZ, maxWorldX, maxWorldZ,
                        lineStrokePx(scale), lineColour(line), culled);
            }
        }
        MapPassReport.lines(System.nanoTime() - startedAt, lines.size(), stroked, sourcePoints, culled[0]);
        // A one-way line's direction, only while the road editor is open: elsewhere marking every one-way
        // line would be annotating the network rather than editing it. The marks are the editor's own arrow
        // (same ink and head, see emitLineArrows), emitted straight after the strokes so they cannot drift
        // while the map moves.
        if (MapFilter.showsLines() && RoadEditSession.isActive()) {
            HudDraw.VertexSink arrowSink = HudDraw.sink(screenPose, vc);
            for (int index = 0; index < lines.size(); index++) {
                if (!lines.get(index).oneWay()) {
                    continue;
                }
                emitLineArrows(arrowSink, arrowsAt(index), projection, margin, viewRight, viewBottom);
            }
        }
        // No name is drawn along a line: on a line that curves or loops no single point is the middle a
        // reader means. Read the name off the line editor, which lists the stops in order.
        if (!MapFilter.showsLines()) {
            // The lines, their stops and their interchanges are what this switch turns off. The railway they run
            // on answers to the switches beside roads and railways, so it stays.
            pose.popPose();
            return labels;
        }

        // The stops, each at the size a place on the ground covers at this zoom, and only while the place it
        // stands at is drawn: a stop left behind by a shed station points at nothing.
        double stopHalf = markerHalfPx(LINE_STOP_PX, scale);
        for (TransitLine line : lines) {
            for (LineStop stop : line.stops()) {
                if (!standShown(stop, scale)) {
                    continue;
                }
                if (inAnInterchange(interchanges, stop.x(), stop.z())) {
                    // Drawn once, as the interchange, below.
                    continue;
                }
                double x = projection.screenX(stop.x());
                double y = projection.screenZ(stop.z());
                if (x < -margin || x > viewRight || y < -margin || y > viewBottom) {
                    continue;
                }
                HudDraw.emitPlaceMarker(HudDraw.sink(screenPose, vc), x, y, stopHalf + 1.0,
                        COLOR_LINE_STOP_EDGE);
                HudDraw.emitPlaceMarker(HudDraw.sink(screenPose, vc), x, y, stopHalf, lineColour(line));
            }
        }

        // One marker per place two lines meet at, and one per group of its stops that land on top of each
        // other: far apart at high zoom they are drawn separately, and a group of one is that stop's own
        // marker in the interchange colour.
        //
        // An interchange is a station, so it goes when the station markers go.
        if (MapFilter.shows(PlaceKind.STATION, scale)) {
            for (TransitInterchanges.Interchange interchange : interchanges) {
                for (List<Integer> group : TransitInterchanges.overlapping(interchange,
                        x -> (int) Math.round(projection.screenX(x)),
                        z -> (int) Math.round(projection.screenZ(z)), stopHalf * 2.0)) {
                    double x = 0;
                    double y = 0;
                    for (int index : group) {
                        int[] stop = interchange.stops().get(index);
                        x += projection.screenX(stop[0]);
                        y += projection.screenZ(stop[1]);
                    }
                    x /= group.size();
                    y /= group.size();
                    if (x < -margin || x > viewRight || y < -margin || y > viewBottom) {
                        continue;
                    }
                    if (group.size() == 1) {
                        HudDraw.emitPlaceMarker(HudDraw.sink(screenPose, vc), x, y, stopHalf + 1.0,
                                COLOR_LINE_STOP_EDGE);
                        HudDraw.emitPlaceMarker(HudDraw.sink(screenPose, vc), x, y, stopHalf, COLOR_LINE_TRANSFER);
                    } else {
                        // A square, the same size as a stop's round marker: the fused place is its own thing and the shape
                        // says so.
                        emitBox(screenPose, vc, x, y, stopHalf + 1.0, stopHalf + 1.0,
                                COLOR_LINE_STOP_EDGE);
                        emitBox(screenPose, vc, x, y, stopHalf, stopHalf, COLOR_LINE_TRANSFER);
                    }
                }
            }
        }
        pose.popPose();
        return labels;
    }

    private static LineShape shapeAt(int index) {
        return index < lineShapes.size() ? lineShapes.get(index) : LineShape.EMPTY;
    }

    private static List<LineArrow> arrowsAt(int index) {
        return index < lineArrows.size() ? lineArrows.get(index) : List.of();
    }

    /**
     * Draws the direction marks of one one-way line that the screen can see.
     * Per frame this only projects and emits: placement and direction were worked out with the shape (see planLineArrows). Marks are culled one by one, because a line crossing the screen has most of its marks just off it and the point test is two comparisons. The bearing is taken by projecting a one-block step along the mark's direction and normalising, so a pose whose scale signs flip the map still draws the mark pointing where the line goes on screen. The arrowhead is emitArrowHead, the editor's own (light head over dark rim), with fixed inks legible on any line colour: a line's colour may be any value a player typed, an imported route's colour or its kind's configured colour. Not yet looked at in a running game: what needs a human eye is the marks' size on a 2.5 px stroke at real zooms, their contrast on the palest colours, and whether one mark every 64 blocks reads as direction or as texture. The three constants are where to change that.
     */
    private static void emitLineArrows(HudDraw.VertexSink out, List<LineArrow> arrows,
                                       Projection projection, int margin, int viewRight,
                                       int viewBottom) {
        for (LineArrow arrow : arrows) {
            double x = projection.screenX(arrow.x());
            double y = projection.screenZ(arrow.z());
            if (x < -margin || x > viewRight || y < -margin || y > viewBottom) {
                continue;
            }
            double directionX = projection.screenX(arrow.x() + arrow.dirX()) - x;
            double directionY = projection.screenZ(arrow.z() + arrow.dirZ()) - y;
            double length = Math.hypot(directionX, directionY);
            if (!(length > 1.0E-9)) {
                // The line is drawn so far zoomed out that a block is under a pixel, and a collapsed direction is
                // not a direction.
                continue;
            }
            emitArrowHead(out, x, y, directionX / length, directionY / length, TRANSIT_ARROW_PX);
        }
    }

    /**
     * Brings the line shapes up to date and leaves them in step with the lines they were built for.
     *
     * Asked from two passes now, so one call with the check rather than the same guard twice. A mismatch is silent and total -- indexing past the end throws, the overlay is lost for that frame and every line disappears at once.
     */
    private static void shapeLines(List<TransitLine> lines) {
        refreshLineShapes(lines);
        if (lineShapes.size() != lines.size()) {
            lineShapeSignature = "";
            refreshLineShapes(lines);
        }
    }

    /**
     * MTR's marks: the track each line read out of MTR runs along, as polylines thinned to the zoom.
     * Not one map element per rail: a whole railway is tens of thousands of elements, each a quad per sampled vertex and most a fraction of a pixel, a cost that grows with the railway while the screen does not. Here they are drawn from the very polylines the lines are drawn from -- a line's marks are its own track -- so drawing is thinned, culled by each stretch's box, and paid once per line. The line's own switch decides whether its track is here; the class switch and zoom decide whether it is drawn. Marks are rail for a train and water for a boat, asked of the line's kind rather than one class for all.
     */
    private void drawMarks(PoseStack pose, VertexConsumer vc, int margin, int viewRight, int viewBottom,
                           double scale, Projection projection) {
        // A line whose marks are off is not drawn here; one whose kind has no class is not a line this mod
        // has (see buildLines). marksEnabled is the one place the answer lives.
        List<TransitLine> lines = Navigation.linesInPlay();
        if (lines.isEmpty()) {
            return;
        }
        shapeLines(lines);

        double pixelsPerBlock = Math.max(Math.abs(projection.scale() * projection.m00()),
                Math.abs(projection.scale() * projection.m11()));
        double leftEdge = projection.worldX(-margin);
        double rightEdge = projection.worldX(viewRight);
        double topEdge = projection.worldZ(-margin);
        double bottomEdge = projection.worldZ(viewBottom);
        double minWorldX = Math.min(leftEdge, rightEdge);
        double maxWorldX = Math.max(leftEdge, rightEdge);
        double minWorldZ = Math.min(topEdge, bottomEdge);
        double maxWorldZ = Math.max(topEdge, bottomEdge);

        pose.pushPose();
        screenSpace(pose);
        PoseStack.Pose screenPose = pose.last();

        long startedAt = System.nanoTime();
        int sourcePoints = 0;
        int stroked = 0;
        int[] culled = {0};
        for (int index = 0; index < lines.size(); index++) {
            TransitLine line = lines.get(index);
            if (!MtrTransit.isImported(line) || !MtrTransit.marksEnabled(line)) {
                continue;
            }
            RoadClass kind = line.kind();
            if (kind == null || !MapFilter.shows(kind, scale)) {
                continue;
            }
            for (PathRuns.Run run : shapeAt(index).runs()) {
                sourcePoints += run.size();
            }
            stroked += drawRuns(screenPose, vc, shapeAt(index).runsAt(pixelsPerBlock), projection,
                    margin, viewRight, viewBottom, minWorldX, minWorldZ, maxWorldX, maxWorldZ,
                    strokeHalfWidthPx(kind, scale), RoadConfig.roadColor(kind), culled);
        }
        pose.popPose();
        MapPassReport.marks(System.nanoTime() - startedAt, stroked, sourcePoints, culled[0]);
    }

    /**
     * Draws the stretches of one shape the screen can see, as strokes.
     * Culling is by each stretch's box: a whole railway is mostly off screen, and a stretch nobody can see costs a walk of its points to learn that. A surviving stretch is drawn in full, since one crossing the screen has both ends outside it and is exactly what a per-point test would drop. halfWidth is half the stroke's width in screen pixels; culled counts stretches the screen does not touch; returns how many strokes were emitted.
     */
    private static int drawRuns(PoseStack.Pose screenPose, VertexConsumer vc, List<PathRuns.Run> runs,
                                Projection projection, int margin, int viewRight, int viewBottom,
                                double minWorldX, double minWorldZ, double maxWorldX, double maxWorldZ,
                                double halfWidth, int argb, int[] culled) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        // As in the road pass: the alpha is the colour's own; configured transparency must be the
        // transparency drawn.
        int a = (argb >>> 24) & 0xFF;
        int stroked = 0;
        for (PathRuns.Run run : runs) {
            if (!PathRuns.onScreen(run, minWorldX, minWorldZ, maxWorldX, maxWorldZ)) {
                culled[0]++;
                continue;
            }
            List<double[]> points = run.points();
            double previousX = 0;
            double previousY = 0;
            for (int i = 0; i < points.size(); i++) {
                double[] point = points.get(i);
                double x = projection.screenX(point[0]);
                double y = projection.screenZ(point[1]);
                if (i > 0 && Math.max(previousX, x) >= -margin && Math.min(previousX, x) <= viewRight
                        && Math.max(previousY, y) >= -margin && Math.min(previousY, y) <= viewBottom) {
                    emitStroke(screenPose, vc, previousX, previousY, x, y, halfWidth, r, g, b, a);
                    stroked++;
                }
                previousX = x;
                previousY = y;
            }
        }
        return stroked;
    }

    /**
     * Whether the place a stop stands at is being drawn, which is what the stop marker follows. A stop at a player's place is that place's kind; a stop at a read station is a station -- so a line calling at a shop is hidden with the shops.
     */
    private static boolean standShown(LineStop stop, double scale) {
        if (stop.nodeId() == LineStop.NO_NODE) {
            return MapFilter.shows(PlaceKind.STATION, scale);
        }
        RoadNode node = RoadStore.get().node(stop.nodeId());
        return MapFilter.shows(node == null ? PlaceKind.PLACE : node.placeKind(), scale);
    }

    /**
     * Half the width of a marker drawn at the given map scale, in screen pixels.
     *
     * Markers are emitted in screen space, so the map transform is not applied and the size is worked out from the scale by hand: the same place covers more pixels zoomed in, until the bounds stop it.
     */
    private static double markerHalfPx(double fullSizePx, double scale) {
        double natural = fullSizePx * 0.5 * Math.abs(scale) / MARKER_NATURAL_SCALE;
        return Math.max(MARKER_MIN_HALF_PX, Math.min(MARKER_MAX_HALF_PX, natural));
    }

    /**
     * How wide a transit line is drawn, in screen pixels.
     *
     * A constant would grow relative to the map as it is zoomed out: at a scale where a road is a hairline, a two-and-a-half-pixel line reads as a rope over the map. It thins with the map to a floor, so it is never invisible and never fat.
     */
    private static double lineStrokePx(double scale) {
        double thinned = LINE_STROKE_PX * Math.abs(scale) / FULL_LINE_STROKE_SCALE;
        return Math.max(MIN_LINE_STROKE_PX, Math.min(LINE_STROKE_PX, thinned));
    }

    /**
     * How many lines the map is drawing, written when that changes.
     *
     * "the line disappeared" has two causes that look identical: the line leaving the list, and the line in the list but drawn nowhere. A count that stays while the stroke goes is the second; a count that drops is the first.
     */
    private static void reportLines(List<TransitLine> lines) {
        if (lines.size() == reportedLineCount) {
            return;
        }
        reportedLineCount = lines.size();
        int imported = 0;
        int stops = 0;
        for (TransitLine line : lines) {
            if (MtrTransit.isImported(line)) {
                imported++;
            }
            stops += line.stopCount();
        }
        HowToGo.diagnostic("[HowToGo] drawing {} transit line(s), {} of them read out of MTR, "
                + "{} stops between them; {} line(s) and {} station(s) remembered", lines.size(),
                imported, stops, MtrTransit.rememberedLines(), MtrTransit.rememberedStations());
    }

    private static boolean inAnInterchange(
            java.util.List<TransitInterchanges.Interchange> interchanges, int x, int z) {
        for (TransitInterchanges.Interchange interchange : interchanges) {
            if (interchange.holds(x, z)) {
                return true;
            }
        }
        return false;
    }

    private record LineLabel(String name, double x, double y, int colour) {
    }

    /**
     * The line names, written after the shapes.
     *
     * Last because drawing text flushes the vertex batch: a name written before the polylines and stop markers would leave them emitted into a buffer already sent, which is how a map ends up with no shapes and an exception in the log.
     */
    //? if >=1.21.6 {
    /*private void drawLineNames(MapElementGraphics graphics, List<LineLabel> labels) {
    *///?} else {
    private void drawLineNames(GuiGraphics graphics, List<LineLabel> labels) {
    //?}
        net.minecraft.client.gui.Font font = net.minecraft.client.Minecraft.getInstance().font;
        int margin = 64;
        int viewRight = mapViewRight(graphics) + margin;
        int viewBottom = mapViewBottom(graphics) + margin;
        for (LineLabel label : labels) {
            if (label.x() < -margin || label.x() > viewRight || label.y() < -margin
                    || label.y() > viewBottom) {
                continue;
            }
            mapLabel(graphics, font, label.name(),
                    (int) Math.round(label.x()) - font.width(label.name()) / 2,
                    (int) Math.round(label.y()), label.colour(), true);
        }
    }

    /**
     * A line's colour: its own when it has one, its kind's configured colour otherwise.
     *
     * The kinds are what a map reader knows and keep a network of same-kind lines readable; a colour of the line's own (an imported route arrives with one) tells apart two lines through one interchange. The stroke, stop markers, interchange markers, list swatch and place-card chips all ask this one method. A platform shared by two lines of one kind shows the kind's colour, and one shared with an MTR line shows that line's.
     */
    private static int lineColour(TransitLine line) {
        return line.colorOr(RoadConfig.transitLineColor(line.kind()));
    }

    /**
     * A yellow square at every place, and the square's box reserved on the label board.
     * The reservation keeps a place's own name off its marker and a road's name off the place it passes. It is taken only for emitted markers, so a shed place keeps no hole in the label layout. A favourite carries a small star in the same block and sink as its marker: a shape emitted after the text pass would go into an already-flushed buffer. Only Destinations#places() can carry one -- an addon's road-data points have no Destination and no source-stable id.
     */
    private void drawPlaceMarkers(PoseStack.Pose screenPose, VertexConsumer vc,
                                  int margin, int viewRight, int viewBottom,
                                  java.util.List<TransitInterchanges.Interchange> interchanges,
                                  double scale, Projection projection, LabelBoard board) {
        double half = markerHalfPx(HudDraw.PLACE_MARKER_PX, scale);
        for (Destination place : Destinations.places()) {
            // The panel's switches and the map's zoom through the one rule: a shop is shed before a station, and
            // a resource point last.
            if (!MapFilter.shows(place.kind(), scale)) {
                continue;
            }
            // A place a line stops at is drawn twice, and the later marker hides the earlier. That is wrong for
            // an interchange, whose point is its colour: the place marker stands aside there.
            if (inAnInterchange(interchanges, place.x(), place.z())) {
                continue;
            }
            double x = projection.screenX(place.x());
            double y = projection.screenZ(place.z());
            if (x < -margin || x > viewRight || y < -margin || y > viewBottom) {
                continue;
            }
            HudDraw.VertexSink markerSink = HudDraw.sink(screenPose, vc);
            HudDraw.emitPlaceMarker(markerSink, x, y, half, HudDraw.COLOR_PLACE);
            if (PlaceDetailStore.isFavorite(place)) {
                // The star sits on the marker's top-right corner at the marker's own size: a badge, not a second
                // marker.
                HudDraw.emitStar(markerSink, x + half, y - half, Math.max(1.4, half * 0.9),
                        UiTheme.accent());
            }

            board.reserve(box(x, y, half * 2.0, half * 2.0));
        }

        // An addon's places by the same rule: screen-size markers, the panel's switches shedding a shop
        // before a station. Drawn after the player's places, since those are the ones a route can be planned
        // to.
        for (RoadNode node : AddonRoadStore.nodes()) {
            if (node.type() != RoadNode.Type.POI || !MapFilter.shows(node.placeKind(), scale)) {
                continue;
            }
            double x = projection.screenX(node.x());
            double y = projection.screenZ(node.z());
            if (x < -margin || x > viewRight || y < -margin || y > viewBottom) {
                continue;
            }
            HudDraw.emitPlaceMarker(HudDraw.sink(screenPose, vc), x, y, half, HudDraw.COLOR_PLACE);
            board.reserve(box(x, y, half * 2.0, half * 2.0));
        }
    }

    /**
     * The active navigation route, drawn over the roads.
     * Each stretch is drawn in its own colour -- blue walked, green driven, the line's colour ridden -- and everything behind the player is grey, as a navigation app colours progress. Stretches come from Navigation.routeInk, the same table the panel, minimap and viewers draw. This map draws the route as one quad per edge with a dot at every joint, unlike the stroked self-drawn maps; splitting an edge at the player's exact position keeps the grey boundary from lurching a segment at a time. Dots are a second pass after every edge, so a dot is not painted over where two stretches meet and a joint shows the arriving colour.
     */
    private void renderRoute(RoadElement element, PoseStack.Pose pose, VertexConsumer vc,
                             double fracX, double fracY, double p10, double posePerPixel) {
        double anchorX = element.anchorX();
        double anchorZ = element.anchorZ();
        double marker = ROUTE_MARKER_PX * posePerPixel;
        HudDraw.RouteInk ink = Navigation.routeInk();

        if (!ink.isPresent()) {
            // Still show where the trip started and where it is trying to get to, so a failed route does not
            // look like the destination was never set.
            drawMarker(pose, vc, anchorX, anchorZ, fracX, fracY, p10,
                    Navigation.tripOriginX(), Navigation.tripOriginZ(), marker, COLOR_ROUTE_START);
            Destination destination = Navigation.target();
            if (destination != null) {
                drawMarker(pose, vc, anchorX, anchorZ, fracX, fracY, p10,
                        destination.x(), destination.z(), marker, COLOR_ROUTE_END);
            }
            return;
        }

        List<double[]> points = ink.points();
        double halfWidth = ROUTE_STROKE_PX * 0.5 * posePerPixel;
        double travelled = Navigation.travelled();
        int doneR = (COLOR_ROUTE_DONE >> 16) & 0xFF;
        int doneG = (COLOR_ROUTE_DONE >> 8) & 0xFF;
        int doneB = COLOR_ROUTE_DONE & 0xFF;

        for (HudDraw.InkSpan span : ink.spans()) {
            int aheadR = (span.colour() >> 16) & 0xFF;
            int aheadG = (span.colour() >> 8) & 0xFF;
            int aheadB = span.colour() & 0xFF;
            double cumulative = span.fromDistance();
            for (int i = span.fromPoint() + 1; i <= span.toPoint(); i++) {
                double[] a = points.get(i - 1);
                double[] b = points.get(i);
                double segmentLength = Math.hypot(b[0] - a[0], b[1] - a[1]);

                double ax = localX(a[0], anchorX, fracX, p10);
                double ay = localY(a[1], anchorZ, fracY, p10);
                double bx = localX(b[0], anchorX, fracX, p10);
                double by = localY(b[1], anchorZ, fracY, p10);

                if (segmentLength < 1.0E-9) {
                    continue;
                }

                if (cumulative + segmentLength <= travelled) {
                    emitStroke(pose, vc, ax, ay, bx, by, halfWidth, doneR, doneG, doneB, 0xFF);
                } else if (cumulative >= travelled) {
                    emitStroke(pose, vc, ax, ay, bx, by, halfWidth, aheadR, aheadG, aheadB, 0xFF);
                } else {
                    double t = (travelled - cumulative) / segmentLength;
                    double mx = ax + (bx - ax) * t;
                    double my = ay + (by - ay) * t;
                    emitStroke(pose, vc, ax, ay, mx, my, halfWidth, doneR, doneG, doneB, 0xFF);
                    emitStroke(pose, vc, mx, my, bx, by, halfWidth, aheadR, aheadG, aheadB, 0xFF);
                }

                cumulative += segmentLength;
            }
        }

        for (HudDraw.InkSpan span : ink.spans()) {
            int aheadR = (span.colour() >> 16) & 0xFF;
            int aheadG = (span.colour() >> 8) & 0xFF;
            int aheadB = span.colour() & 0xFF;
            double cumulative = span.fromDistance();
            for (int i = span.fromPoint(); i <= span.toPoint(); i++) {
                double[] p = points.get(i);
                boolean behind = cumulative < travelled;
                emitDisc(pose, vc,
                        localX(p[0], anchorX, fracX, p10), localY(p[1], anchorZ, fracY, p10),
                        halfWidth,
                        behind ? doneR : aheadR, behind ? doneG : aheadG, behind ? doneB : aheadB,
                        0xFF);
                if (i < span.toPoint()) {
                    double[] next = points.get(i + 1);
                    cumulative += Math.hypot(next[0] - p[0], next[1] - p[1]);
                }
            }
        }

        double[] end = points.get(points.size() - 1);
        // The origin is pinned to where the trip began, not to the current route's first point, so
        // re-planning after a detour does not drag it along.
        drawMarker(pose, vc, anchorX, anchorZ, fracX, fracY, p10,
                Navigation.tripOriginX(), Navigation.tripOriginZ(), marker, COLOR_ROUTE_START);
        drawMarker(pose, vc, anchorX, anchorZ, fracX, fracY, p10,
                end[0], end[1], marker, COLOR_ROUTE_END);
    }

    private static void drawMarker(PoseStack.Pose pose, VertexConsumer vc,
                                   double anchorX, double anchorZ, double fracX, double fracY,
                                   double p10, double worldX, double worldZ,
                                   double radius, int argb) {
        if (Double.isNaN(worldX) || Double.isNaN(worldZ)) {
            return;
        }
        emitDisc(pose, vc, localX(worldX, anchorX, fracX, p10), localY(worldZ, anchorZ, fracY, p10),
                radius, (argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF, 0xFF);
    }

    /**
     * Bottom-of-screen readout: the trip readout with the editing headline on the left, the road editing controls on the right.
     * The map pose is flattened to identity first, so this draws in plain GUI coordinates. Each block is sized to its own text and inset from its own edge, so neither sits on Xaero's bars. The split is by how often each line is read: the class, its swatch and live totals belong with the readout, while the control list is a reference consulted once and kept out of the way. Every quad goes out before any string: a text draw flushes the buffer source, so keeping the fills in one pass removes the question.
     */
    //? if >=1.21.6 {
    /*private void renderHud(MapElementGraphics graphics, PoseStack pose) {
    *///?} else {
    private void renderHud(GuiGraphics graphics, PoseStack pose) {
    //?}
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;

        List<String> leftLines = new ArrayList<>();
        Destination destination = Navigation.target();
        if (destination != null) {
            if (Navigation.showArrival()) {
                leftLines.add(Component.translatable("hud.howtogo.arrived",
                        destination.name()).getString());
            } else if (Navigation.route().isPresent()) {
                String remaining = Route.formatDistance(Navigation.remainingLength());
                String eta = Route.formatDuration(Navigation.remainingSeconds());

                leftLines.add(Component.translatable("hud.howtogo.nav_with_instruction",
                        destination.name(),
                        Navigation.instructionText()).getString());
                leftLines.add(Component.translatable("hud.howtogo.nav_progress",
                        remaining, Navigation.modeLabel(), eta).getString());

                // Says so on the map readout too: the mode is named just above, and a route that quietly ignores it
                // would look like a wrong estimate.
                String fallback = Navigation.fallbackHint();
                if (fallback != null) {
                    leftLines.add(fallback);
                }

                if (Navigation.isOffRoute()) {
                    leftLines.add(Component.translatable("hud.howtogo.off_route").getString());
                }
            } else {
                leftLines.add(Component.translatable("hud.howtogo.nav_noroute",
                        destination.name()).getString());
            }
        }

        // The editing headline follows the readout in the same block: the caption names the class, the
        // swatch is that name in colour, the totals are the same subject; only the control scheme is
        // elsewhere, one control per line.
        List<String> hintLines = new ArrayList<>();
        int swatchColor = 0;
        int swatchLine = 0;
        if (RoadEditSession.isActive()) {
            RoadClass roadClass = RoadEditSession.cycleTarget();
            boolean segmentSelected =
                    RoadEditSession.editor().selectedSegmentId() != RoadSegment.NO_SEGMENT;
            swatchLine = leftLines.size();
            String headline = Component.translatable(
                    segmentSelected ? "hud.howtogo.selected" : "hud.howtogo.drawing",
                    Navigation.roadClassLabel(roadClass)).getString();
            if (segmentSelected) {
                // The selected road's one-way state spelled out: the arrows say which way it runs, this says what
                // the key will do to it.
                RoadDirection direction = RoadEditSession.editor()
                        .chainDirection(RoadEditSession.editor().selectedSegmentId());
                headline = headline + " · " + Component.translatable(
                        "hud.howtogo.direction." + direction.id()).getString();
                // Where the selected road is and how long it is, in that order: storey is what a flat map cannot
                // show, and the length is read once the road is the right one. Both are about the whole road, not
                // the piece under the cursor (see RoadEditor.chainLength).
                headline = headline + " · " + Component.translatable("hud.howtogo.layer",
                        Navigation.roadLayerLabel(RoadEditSession.selectedLayer())).getString();
                headline = headline + " · " + Component.translatable("hud.howtogo.selected_length",
                        (int) Math.round(RoadEditSession.selectedLength())).getString();
            }
            leftLines.add(headline);
            leftLines.add(editStats());
            hintLines.addAll(controlHints());
            swatchColor = RoadConfig.roadColor(roadClass);
        }

        if (leftLines.isEmpty() && hintLines.isEmpty()) {
            return;
        }

        int screenWidth = mc.getWindow().getGuiScaledWidth();
        int screenHeight = mc.getWindow().getGuiScaledHeight();

        pose.pushPose();
        screenSpace(pose);

        // The swatch sits in the indent left of the text, on the caption line it stands for.
        int leftIndent = swatchColor != 0 ? 11 : 0;
        int leftWidth = widestLine(font, leftLines);
        int leftTop = blockTop(hudBottom(screenHeight), leftLines.size());
        int leftPanelRight = HUD_LEFT_INSET + leftWidth + leftIndent + 6;

        // The hints carry no swatch, so their panel is the text with equal padding and the text is what is
        // aligned: its right edge lands on the inset the left block starts from. Floored at the screen
        // margin so a hint too wide slides the block rightwards instead of off screen.
        int hintWidth = widestLine(font, hintLines);
        int hintTextRight = Math.max(screenWidth - HUD_RIGHT_INSET, HUD_MARGIN + hintWidth + 4);
        int hintPanelLeft = hintTextRight - hintWidth - 4;
        int hintTextLeft = hintPanelLeft + 4;

        // Both blocks live in the bottom band, and a long navigation or stats line can be wider than the gap
        // between the insets. The readout, caption and totals are anchored to their corners, so the hint
        // list moves: it steps above the other panel when, and only when, the two would meet. The 3 and 2
        // are the panels' overhangs above the first line and below the last.
        int hintBottom = hudBottom(screenHeight);
        if (!leftLines.isEmpty() && !hintLines.isEmpty()
                && leftPanelRight + HUD_MARGIN > hintPanelLeft) {
            hintBottom = leftTop - 3 - HUD_MARGIN - 2;
        }
        int hintTop = blockTop(hintBottom, hintLines.size());

        if (!leftLines.isEmpty()) {
            graphics.fill(HUD_LEFT_INSET - 4, leftTop - 3, leftPanelRight,
                    leftTop + HUD_LINE_HEIGHT * leftLines.size() + 2, HUD_BG);
            if (swatchColor != 0) {
                int swatchY = leftTop + swatchLine * HUD_LINE_HEIGHT + 2;
                graphics.fill(HUD_LEFT_INSET, swatchY, HUD_LEFT_INSET + 7, swatchY + 7, swatchColor);
            }
        }
        if (!hintLines.isEmpty()) {
            graphics.fill(hintPanelLeft, hintTop - 3, hintTextRight + 6,
                    hintTop + HUD_LINE_HEIGHT * hintLines.size() + 2, HUD_BG);
        }

        int y = leftTop;
        for (String line : leftLines) {
            mapLabel(graphics, font, line, HUD_LEFT_INSET + leftIndent, y, HUD_FG, true);
            y += HUD_LINE_HEIGHT;
        }
        y = hintTop;
        for (String line : hintLines) {
            mapLabel(graphics, font, line, hintTextLeft, y, HUD_FG, true);
            y += HUD_LINE_HEIGHT;
        }

        pose.popPose();
    }

    private static String editStats() {
        String stats = Component.translatable("hud.howtogo.stats",
                (int) Math.round(RoadEditSession.pendingLength()),
                (int) Math.round(RoadEditSession.totalLength()),
                RoadStore.get().nodeCount(),
                RoadStore.get().segmentCount()).getString();
        // The storey being drawn on, always shown rather than only off the surface: it is invisible on a map
        // that cannot show height, so a player who moved one road and not the next has nothing else.
        stats = stats + "   " + Component.translatable("hud.howtogo.layer",
                Navigation.roadLayerLabel(RoadEditSession.activeLayer())).getString();
        if (RoadEditSession.isFreePlacementActive()) {
            // The mode being on is state, while the control hint only says the key exists.
            stats = stats + "   " + Component.translatable("hud.howtogo.free").getString();
        }
        return stats;
    }

    private static List<String> controlHints() {
        List<String> hints = new ArrayList<>(EDIT_HINT_KEYS.size());
        for (String key : EDIT_HINT_KEYS) {
            hints.add(Component.translatable(key).getString());
        }
        return hints;
    }

    private static int widestLine(Font font, List<String> lines) {
        int widest = 0;
        for (String line : lines) {
            widest = Math.max(widest, font.width(line));
        }
        return widest;
    }

    /**
     * Line a HUD block's bottom is anchored to. The 4 px covers the panel's 2 px overhang and keeps it off the margin line.
     */
    private static int hudBottom(int screenHeight) {
        return screenHeight - HUD_MARGIN - 4;
    }

    /**
     * Top of a block of the given line count sitting on the given bottom.
     *
     * Nothing is clamped: the worst case is a six-line left block with the thirteen-line control list stepped above it, whose top still lands around 10 px of a scaled space never shorter than 240. A longer list would need a scroll or a second column.
     */
    private static int blockTop(int bottom, int lineCount) {
        return bottom - HUD_LINE_HEIGHT * lineCount;
    }

    private static String turnPhrase(double degrees) {
        return Navigation.turnPhrase(degrees);
    }

    private static double localX(double worldX, double anchorX, double fracX, double p10) {
        return fracX + (worldX - anchorX) * p10;
    }

    private static double localY(double worldZ, double anchorZ, double fracY, double p10) {
        return fracY + (worldZ - anchorZ) * p10;
    }

    /**
     * Half the on-screen stroke width in pixels for a road of the given class.
     *
     * Below the cap it is the road's true block width at this zoom, so the map stays metrically honest; above it the stroke stops widening, keeping high zoom readable instead of a solid band.
     */
    private static double strokeHalfWidthPx(RoadClass roadClass, double scale) {
        double naturalHalf = roadClass.width() * 0.5 * Math.abs(scale);
        double cappedHalf = (3.0 + roadClass.width() * 0.7) * 0.5;
        return Math.max(MIN_STROKE_PX * 0.5, Math.min(naturalHalf, cappedHalf));
    }

    private static void emitStroke(PoseStack.Pose pose, VertexConsumer vc,
                                   double x1, double y1, double x2, double y2, double halfWidth,
                                   int r, int g, int b, int a) {
        double dx = x2 - x1;
        double dy = y2 - y1;
        double len = Math.sqrt(dx * dx + dy * dy);
        if (len < 1.0E-9) {
            return;
        }
        double nx = -dy / len * halfWidth;
        double ny = dx / len * halfWidth;

        vertex(pose, vc, x1 + nx, y1 + ny, r, g, b, a);
        vertex(pose, vc, x2 + nx, y2 + ny, r, g, b, a);
        vertex(pose, vc, x2 - nx, y2 - ny, r, g, b, a);
        vertex(pose, vc, x1 - nx, y1 - ny, r, g, b, a);
    }

    private static void emitBox(PoseStack.Pose pose, VertexConsumer vc, double cx, double cy,
                                double halfX, double halfY, int argb) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        int a = (argb >>> 24) & 0xFF;
        vertex(pose, vc, cx - halfX, cy - halfY, r, g, b, a);
        vertex(pose, vc, cx + halfX, cy - halfY, r, g, b, a);
        vertex(pose, vc, cx + halfX, cy + halfY, r, g, b, a);
        vertex(pose, vc, cx - halfX, cy + halfY, r, g, b, a);
    }


    /**
     * Screen distance between the direction arrows along a one-way road.
     *
     * A screen distance because the arrows are a reading of the map: a world spacing would stack them zoomed out and leave one arrow pointing off screen zoomed in. It is a floor too -- a road drawn wider pushes its arrows apart.
     */
    private static final double ONEWAY_ARROW_SPACING_PX = 26.0;
    /**
     * The largest an arrowhead is drawn, in screen pixels.
     *
     * A cap, not the size: the head is sized from the road it sits on (see ONEWAY_ARROW_FITS), because an arrow wider than its road reads as an object beside it. The cap holds a very wide road so one street cannot carry arrowheads the size of its junctions.
     */
    private static final double ONEWAY_ARROW_PX = 7.5;
    /**
     * How much of the road's half-width one arrowhead may be, end to end. The head is 1.24 of its length across and the road two half-widths across, so 1.5 puts the widest part at 93% of the road's width -- inside the stroke.
     */
    private static final double ONEWAY_ARROW_FITS = 1.5;
    private static final int ONEWAY_ARROW_LIMIT = 40;
    /**
     * Map scale below which a one-way road is drawn without its arrows. At a scale where a whole region is on screen, a road is part of a picture and forty arrowheads per street would be texture; the road itself is still drawn.
     */
    private static final double ONEWAY_ARROW_MIN_SCALE = 0.3;
    private static final int COLOR_ARROW = 0xFFF4F8FF;
    private static final int COLOR_ARROW_RIM = 0xB0101418;
    /** The closed badge: a no-entry disc in signal red, with the bar cut in white. */
    private static final int COLOR_CLOSED_DISC = 0xFFC62828;
    private static final int COLOR_CLOSED_BAR = 0xFFF2F2F2;
    /** Screen pixels the closed badge is drawn at, fixed: closures are rare and always worth reading. */
    private static final double CLOSED_BADGE_PX = 11.0;
    /**
     * The closure dash pattern, all in multiples of the road's drawn half-width: the dash runs
     * 2.4 half-widths, the gap 1.6, and the dashes themselves are 0.45 half-widths thick. Because
     * the road's drawn width grows as the player zooms, the pattern grows with it -- the reference
     * renderers' line-dasharray works the same way ("the lengths are later scaled by the line
     * width"), which is why the marks read as painted on the road at every zoom.
     */
    private static final double CLOSED_DASH_HALF_WIDTHS = 2.4;
    private static final double CLOSED_GAP_HALF_WIDTHS = 1.6;

    /**
     * Draws the direction arrows of every one-way road, above every road.
     * The arrows used to be emitted from each segment's pass, so any road element drawn later painted over them -- only the arrows of whichever one-way roads were drawn last survived, which looked like "only part of the one-way road has arrows". Drawn from the trailing labels element, no road can cover them. The cost is one walk of the one-way segments per frame in screen space: only as many as the player marked, gated by ONEWAY_ARROW_MIN_SCALE and the class switches, and computed from the same display line the stroke uses. Each mark is sized from the road's stroke half-width (ONEWAY_ARROW_FITS), so it fits at every zoom and class width.
     */
    private void drawOneWayMarks(PoseStack pose, VertexConsumer vc, double scale,
                                 Projection projection, double left, double top, double right,
                                 double bottom) {
        if (Math.abs(scale) < ONEWAY_ARROW_MIN_SCALE) {
            return;
        }
        pose.pushPose();
        screenSpace(pose);
        HudDraw.VertexSink sink = HudDraw.sink(pose.last(), vc);
        for (RoadSegment segment : RoadStore.get().segments()) {
            if (!segment.direction().isOneWay()) {
                continue;
            }
            if (!MapFilter.shows(segment.roadClass(), scale)) {
                continue;
            }
            int count = displayPointCount(segment);
            if (count < 2) {
                continue;
            }
            double halfWidthPx = strokeHalfWidthPx(segment.roadClass(), scale);
            double size = Math.min(ONEWAY_ARROW_PX, halfWidthPx * ONEWAY_ARROW_FITS);
            if (!(size > 0.75)) {
                // Below this a mark is a speck on a road a few pixels wide; the gate above is where a zoomed-out map
                // stops drawing them, and this is the same decision for a narrow road.
                continue;
            }
            double spacing = Math.max(ONEWAY_ARROW_SPACING_PX, size * 3.0);
            boolean backward = segment.direction() == RoadDirection.BACKWARD;
            // The same little way in from the end as before: the first mark sits clear of the junction cap.
            double nextAt = spacing * 0.75;
            double travelled = 0.0;
            int drawn = 0;
            for (int step = 1; step < count && drawn < ONEWAY_ARROW_LIMIT; step++) {
                int a = backward ? count - step : step - 1;
                int b = backward ? count - step - 1 : step;
                double ax = projection.screenX(displayX(segment, a));
                double ay = projection.screenZ(displayZ(segment, a));
                double bx = projection.screenX(displayX(segment, b));
                double by = projection.screenZ(displayZ(segment, b));
                double edge = Math.hypot(bx - ax, by - ay);
                if (edge < 1.0E-6) {
                    continue;
                }
                double ux = (bx - ax) / edge;
                double uy = (by - ay) / edge;
                // The marks are stepped whatever the edge does -- culling an edge out of the walk would make the
                // first mark back on screen land at a distance ignoring the road travelled off it -- and only
                // emission is gated on visibility.
                while (nextAt <= travelled + edge && drawn < ONEWAY_ARROW_LIMIT) {
                    double at = nextAt - travelled;
                    double cx = ax + ux * at;
                    double cy = ay + uy * at;
                    if (cx >= left && cx <= right && cy >= top && cy <= bottom) {
                        emitArrowHead(sink, cx, cy, ux, uy, size);
                        drawn++;
                    }
                    nextAt += spacing;
                }
                travelled += edge;
            }
        }
        pose.popPose();
    }

    /**
     * Draws the closures' road marks in the style the reference maps use: the road itself keeps its
     * own colour, and alternating red and white dashes run along both edges of it, so the eye reads
     * "this street is shut" without losing the road under a coat of paint. The no-entry badge sits at
     * the segment's middle as the at-a-glance summary. A closure is visible whatever the player is
     * doing -- nothing else on the map says a road does not lead through, so these marks are the one
     * honest reading of it.
     *
     * Every dash is a fixed screen size, and the pattern walks outward from the segment's middle:
     * the dash flanking the badge on each side keeps its ink at every zoom (white on the left of the
     * badge, red on the right), and zooming only adds further dashes beyond them. Nothing slides,
     * nothing swaps colour, and the count is the only thing zoom changes.
     */
    private void drawClosedMarks(PoseStack pose, VertexConsumer vc, double scale,
                                 Projection projection, double left, double top, double right,
                                 double bottom) {
        pose.pushPose();
        screenSpace(pose);
        HudDraw.VertexSink sink = HudDraw.sink(pose.last(), vc);
        for (RoadSegment segment : RoadStore.get().segments()) {
            if (!segment.closed()) {
                continue;
            }
            if (!MapFilter.shows(segment.roadClass(), scale)) {
                continue;
            }
            int count = displayPointCount(segment);
            if (count < 2) {
                continue;
            }
            double length = segment.length();
            double halfWidthPx = strokeHalfWidthPx(segment.roadClass(), scale);
            if (!(halfWidthPx > 0.75)) {
                // Below this the road is a line rather than a surface; the badge alone still says
                // closed there, but a dash pattern would be texture.
                continue;
            }
            double thickness = Math.max(1.2, halfWidthPx * 0.45);
            double dash = halfWidthPx * 2.4;
            double gap = halfWidthPx * 1.6;
            double step = dash + gap;
            int perSide = (int) Math.min(60, Math.ceil(length / step * 0.5) + 1);
            double midArc = length * 0.5;
            for (int i = 0; i < perSide; i++) {
                for (int side = -1; side <= 1; side += 2) {
                    double arc = midArc + side * ((i + 0.5) * step);
                    if (arc < 0 || arc > length) {
                        continue;
                    }
                    double[] centre = displayPointAt(segment, count, arc);
                    double cx = projection.screenX(centre[0]);
                    double cy = projection.screenZ(centre[1]);
                    if (cx < left || cx > right || cy < top || cy > bottom) {
                        continue;
                    }
                    // The dash runs along the road's screen direction at its own centre, sampled
                    // from two world points a little before and after it, so a curve bends it too.
                    double[] before = displayPointAt(segment, count, Math.max(0.0, arc - 3.0));
                    double[] after = displayPointAt(segment, count, Math.min(length, arc + 3.0));
                    double dx = projection.screenX(after[0]) - projection.screenX(before[0]);
                    double dy = projection.screenZ(after[1]) - projection.screenZ(before[1]);
                    double len = Math.hypot(dx, dy);
                    if (len < 1.0E-4) {
                        continue;
                    }
                    double ux = dx / len;
                    double uy = dy / len;
                    double nx = -uy;
                    double ny = ux;
                    double half = dash * 0.5;
                    // The ink is tied to the side and the index, not to a walk order: innermost
                    // white on the left of the badge, red on its right, alternating outward. A zoom
                    // changes how many dashes there are, never which colour an existing one is.
                    boolean inner = i % 2 == 0;
                    int ink = side > 0
                            ? (inner ? COLOR_CLOSED_BAR : COLOR_CLOSED_DISC)
                            : (inner ? COLOR_CLOSED_DISC : COLOR_CLOSED_BAR);
                    for (int road = -1; road <= 1; road += 2) {
                        double ex = cx + nx * halfWidthPx * road;
                        double ey = cy + ny * halfWidthPx * road;
                        HudDraw.emitQuad(sink,
                                ex - ux * half + nx * thickness, ey - uy * half + ny * thickness,
                                ex + ux * half + nx * thickness, ey + uy * half + ny * thickness,
                                ex + ux * half - nx * thickness, ey + uy * half - ny * thickness,
                                ex - ux * half - nx * thickness, ey - uy * half - ny * thickness,
                                ink);
                    }
                }
            }
            // The badge once, halfway along the display line and anchored to the road's own
            // geometry, so panning cannot leave it behind.
            int mid = count / 2 - 1;
            double mx = projection.screenX(
                    (displayX(segment, mid) + displayX(segment, mid + 1)) * 0.5);
            double my = projection.screenZ(
                    (displayZ(segment, mid) + displayZ(segment, mid + 1)) * 0.5);
            if (mx >= left && mx <= right && my >= top && my <= bottom) {
                emitClosedBadge(sink, mx, my, CLOSED_BADGE_PX * 0.5);
            }
        }
        pose.popPose();
    }

    /**
     * The world point on a segment's display line at a given arc length from its start, walked from
     * vertex to vertex. Arc lengths beyond the line clamp to its end.
     */
    private static double[] displayPointAt(RoadSegment segment, int count, double at) {
        for (int step = 1; step < count; step++) {
            double ax = displayX(segment, step - 1);
            double az = displayZ(segment, step - 1);
            double bx = displayX(segment, step);
            double bz = displayZ(segment, step);
            double edge = Math.hypot(bx - ax, bz - az);
            if (at <= edge && edge > 1.0E-6) {
                double t = at / edge;
                return new double[]{ax + (bx - ax) * t, az + (bz - az) * t};
            }
            at -= edge;
        }
        return new double[]{displayX(segment, count - 1), displayZ(segment, count - 1)};
    }

    /**
     * One closed badge: an eight-sided disc in signal red with the no-entry bar across it. Eight
     * sides because a four-sided diamond reads as a place marker on this map and a true circle
     * costs more triangles than a badge at eleven pixels can justify.
     */
    private static void emitClosedBadge(HudDraw.VertexSink out, double cx, double cy, double radius) {
        double[] xs = new double[8];
        double[] ys = new double[8];
        for (int k = 0; k < 8; k++) {
            double angle = Math.PI / 4.0 * k;
            xs[k] = cx + Math.cos(angle) * radius;
            ys[k] = cy + Math.sin(angle) * radius;
        }
        for (int k = 0; k < 8; k++) {
            int next = (k + 1) % 8;
            HudDraw.emitTriangle(out, cx, cy, xs[k], ys[k], xs[next], ys[next], COLOR_CLOSED_DISC);
        }
        double halfW = radius * 0.62;
        double halfH = radius * 0.24;
        HudDraw.emitTriangle(out, cx - halfW, cy - halfH, cx + halfW, cy - halfH,
                cx + halfW, cy + halfH, COLOR_CLOSED_BAR);
        HudDraw.emitTriangle(out, cx - halfW, cy - halfH, cx + halfW, cy + halfH,
                cx - halfW, cy + halfH, COLOR_CLOSED_BAR);
    }

    /**
     * One arrowhead: a dark rim with a light head on top, pointing along (ux, uy).
     *
     * size is the arrow's length in whatever unit the sink's pose is in -- screen pixels for both road and transit marks, since both are emitted from a screen-space pose. The shape is one thing on this map whichever layer it belongs to.
     */
    private static void emitArrowHead(HudDraw.VertexSink out, double cx, double cy,
                                      double ux, double uy, double size) {
        double nx = -uy;
        double ny = ux;
        double tipX = cx + ux * size * 0.5;
        double tipY = cy + uy * size * 0.5;
        double backX = cx - ux * size * 0.5;
        double backY = cy - uy * size * 0.5;
        double wingX = nx * size * 0.62;
        double wingY = ny * size * 0.62;
        double rim = size * 0.22;

        HudDraw.emitTriangle(out,
                tipX + ux * rim, tipY + uy * rim,
                backX + wingX - nx * rim, backY + wingY - ny * rim,
                backX - wingX + nx * rim, backY - wingY + ny * rim, COLOR_ARROW_RIM);
        HudDraw.emitTriangle(out,
                tipX, tipY,
                backX + wingX, backY + wingY,
                backX - wingX, backY - wingY, COLOR_ARROW);
    }

    /**
     * The number of points the map draws for this segment: the smoothed display curve when it has one, the block vertices otherwise. Kept with displayX / displayZ so a caller cannot take the count from one source and positions from the other.
     */
    private static int displayPointCount(RoadSegment segment) {
        RoadCurve curve = segment.curve();
        return curve != null && curve.size() >= 2 ? curve.size() : segment.vertexCount();
    }

    private static double displayX(RoadSegment segment, int index) {
        RoadCurve curve = segment.curve();
        return curve != null && curve.size() >= 2 ? curve.x(index) : segment.x(index);
    }

    private static double displayZ(RoadSegment segment, int index) {
        RoadCurve curve = segment.curve();
        return curve != null && curve.size() >= 2 ? curve.z(index) : segment.z(index);
    }

    // Scratch for one segment's screen-space polyline and the outline built from it. Render thread only:
    // one road is stroked at a time and the map is redrawn every frame, so per-road arrays would be
    // garbage. Grown when a longer curve comes along.
    private static double[] PATH_X = new double[128];
    private static double[] PATH_Y = new double[128];
    /**
     * The stroke geometry, kept between segments: it owns the offsets, joins and caps and the quads for the road being drawn, so the renderer only places and colours them.
     */
    private static final StrokeOutline STROKE = new StrokeOutline();

    private static void ensureStrokeCapacity(int count) {
        if (count <= PATH_X.length) {
            return;
        }
        int capacity = Math.max(count, PATH_X.length * 2);
        PATH_X = new double[capacity];
        PATH_Y = new double[capacity];
    }

    /**
     * Strokes one segment as a single continuous line of one width.
     *
     * The screen-space line is built here under the map's pose; its shape -- an offset per side, how edges meet at a bend, the two caps, a round join at a bend too sharp to mitre -- is StrokeOutline's, checked by the harness without a game. What is left is placing and colouring the quads. The outline is asked for twice when selected: once for the halo, once for the road.
     */
    private static void strokeSegment(PoseStack.Pose pose, VertexConsumer vc, RoadSegment segment,
                                      int pointCount, double anchorX, double anchorZ,
                                      double fracX, double fracY, double p10, double halfWidth,
                                      int r, int g, int b, int a) {
        if (pointCount < 2 || !(halfWidth > 0)) {
            return;
        }
        ensureStrokeCapacity(pointCount);
        for (int i = 0; i < pointCount; i++) {
            PATH_X[i] = localX(displayX(segment, i), anchorX, fracX, p10);
            PATH_Y[i] = localY(displayZ(segment, i), anchorZ, fracY, p10);
        }
        STROKE.build(PATH_X, PATH_Y, pointCount, halfWidth);
        double[] quads = STROKE.coordinates();
        int end = STROKE.quadCount() * StrokeOutline.VALUES_PER_QUAD;
        for (int at = 0; at < end; at += StrokeOutline.VALUES_PER_QUAD) {
            vertex(pose, vc, quads[at], quads[at + 1], r, g, b, a);
            vertex(pose, vc, quads[at + 2], quads[at + 3], r, g, b, a);
            vertex(pose, vc, quads[at + 4], quads[at + 5], r, g, b, a);
            vertex(pose, vc, quads[at + 6], quads[at + 7], r, g, b, a);
        }
    }

    /**
     * Emits a filled circle as a triangle fan.
     *
     * The target render type is QUADS, so each triangle is written as a degenerate quad: the third corner is repeated, leaving the second triangle with no area. Repeating the second corner instead fills the triangle a second time the other way round, and a translucent road would then have every junction dot blended twice.
     */
    private static void emitDisc(PoseStack.Pose pose, VertexConsumer vc, double cx, double cy,
                                 double radius, int r, int g, int b, int a) {
        if (radius <= 0.0) {
            return;
        }
        final int segments = DISC_SEGMENTS;
        for (int i = 0; i < segments; i++) {
            double a0 = i * TWO_PI / segments;
            double a1 = (i + 1) * TWO_PI / segments;
            double x0 = cx + Math.cos(a0) * radius;
            double y0 = cy + Math.sin(a0) * radius;
            double x1 = cx + Math.cos(a1) * radius;
            double y1 = cy + Math.sin(a1) * radius;

            vertex(pose, vc, cx, cy, r, g, b, a);
            vertex(pose, vc, x0, y0, r, g, b, a);
            vertex(pose, vc, x1, y1, r, g, b, a);
            vertex(pose, vc, x1, y1, r, g, b, a);
        }
    }

    private static void vertex(PoseStack.Pose pose, VertexConsumer vc, double x, double y,
                               int r, int g, int b, int a) {
        // VertexConsumer took a Matrix4f through 1.20.x and a PoseStack.Pose from 1.21, renamed with it:
        // vertex(...).color(...) became addVertex(...).setColor(...). The pose carries the same matrix;
        // endVertex() is the other difference and is not decoration.
        //
        // On 1.20.x BufferBuilder counts a vertex and checks or grows its buffer only inside endVertex().
        // Without it a batch was stored with vertex count 0, so nothing reached the screen and the write
        // offset advanced until putFloat ran past the fixed 131072-byte buffer -- the
        // IndexOutOfBoundsException that 1.20.1/1.20.4/1.20.6 builds crashed with after placing a node or
        // two, and why the edit overlay drew nothing there.
        //? if >=1.21 {
        vc.addVertex(pose, (float) x, (float) y, 0.0F).setColor(r, g, b, a);
        //?} else {
        /*vc.vertex(pose.pose(), (float) x, (float) y, 0.0F).color(r, g, b, a).endVertex();
        *///?}
    }
}
