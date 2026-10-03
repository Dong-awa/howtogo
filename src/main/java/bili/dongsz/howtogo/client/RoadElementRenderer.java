package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.HowToGo;
import bili.dongsz.howtogo.road.PlaceKind;
import bili.dongsz.howtogo.road.RoadChains;
import bili.dongsz.howtogo.road.RoadClass;
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
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.Component;
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
 * <h2>Coordinate spaces</h2>
 * Reconstructed from {@code MapElementRenderHandler.transformAndRenderElement}:
 * <pre>
 *   local  = (renderX / dimScale - cameraX) * info.scale
 *   pose   = translate(round(local), round(local), 0)     // applied by Xaero
 *   frac   = local - round(local)                          // handed to renderElement
 * </pre>
 * The pose Xaero leaves on the stack carries no map zoom of its own (its {@code m00} stays at a
 * constant {@code 1/guiScale} while the map scale sweeps over orders of magnitude) and Xaero has
 * already moved the origin to this element, so a vertex is drawn at
 * {@code frac + (worldOffset * info.scale)} and the pose does the rest.
 */
public final class RoadElementRenderer extends ElementRenderer<RoadElement, RoadRenderContext, RoadElementRenderer> {

    /** Set {@code -Dhowtogo.debug=true} to dump projection data once a second. */
    private static final boolean DEBUG = Boolean.getBoolean("howtogo.debug");

    /** Minimum stroke width in screen pixels, so zoomed-out roads stay visible without going fat. */
    private static final double MIN_STROKE_PX = 1.0;

    // What is drawn at all, by zoom and by the player's own switches, is MapFilter's business: the scale
    // thresholds used to live here as a single footpath rule, and they are now one question asked in one
    // place so that the panel and the zoom cannot disagree.

    // Editing UI palette.
    private static final int COLOR_ENDPOINT = 0xFFE0E0E0;
    private static final int COLOR_JUNCTION = 0xFFFFFFFF;
    private static final int COLOR_SELECTED = 0xFFFF4DE0;
    private static final int COLOR_SNAP_NODE = 0xFF35E0FF;
    private static final int COLOR_SNAP_SEGMENT = 0xFFFF9A2E;
    private static final int COLOR_SNAP_ANGLE = 0xFFB06BFF;
    /** Snapped onto a rail: the place colour, since a rail the editor can only land on is a landmark. */
    private static final int COLOR_SNAP_RAIL = HudDraw.COLOR_PLACE;
    private static final int COLOR_SNAP_FREE = 0x80FFFFFF;
    private static final int COLOR_RUBBER_BAND = 0xCCFF4DE0;

    private static final double NODE_HANDLE_PX = 3.0;
    private static final double SELECTED_HANDLE_PX = 4.5;
    private static final double SNAP_RING_PX = 6.0;

    /** Circle resolution for round joins and caps. */
    private static final int DISC_SEGMENTS = 10;
    private static final double TWO_PI = Math.PI * 2.0;

    // Editing HUD.
    private static final int HUD_LEFT_INSET = 36;
    /** Mirrors the left inset, so the navigation and editing blocks sit balanced on the screen. */
    private static final int HUD_RIGHT_INSET = HUD_LEFT_INSET;
    private static final int HUD_MARGIN = 6;
    private static final int HUD_LINE_HEIGHT = 11;
    private static final int HUD_BG = 0xA0000000;
    private static final int HUD_FG = 0xFFE8E8E8;
    private static final int COLOR_LABEL_ROAD = 0xFFF0F0F0;
    /** Railway names, in a cool tint so an automatic line reads apart from a hand-drawn road. */
    private static final int COLOR_LABEL_RAIL = 0xFF9FD2FF;

    /**
     * The editing controls, one lang key per line.
     *
     * <p>Separate keys rather than one joined sentence split on its separators: the separators are
     * part of the language, so a split tuned to English would carve a Chinese hint in half, and a
     * vertical list wants a line per control in any case.
     */
    private static final List<String> EDIT_HINT_KEYS = List.of(
            "hud.howtogo.edit.place",
            "hud.howtogo.edit.free_placement",
            "hud.howtogo.edit.finish",
            "hud.howtogo.edit.select",
            "hud.howtogo.edit.class",
            "hud.howtogo.edit.name",
            "hud.howtogo.edit.poi",
            "hud.howtogo.edit.navigate",
            "hud.howtogo.edit.lines",
            "hud.howtogo.edit.delete",
            "hud.howtogo.edit.undo");

    // Active navigation route.
    private static final int COLOR_ROUTE = 0xFF2FD0FF;
    /** The part of the route already walked. */
    private static final int COLOR_ROUTE_DONE = 0xFF6E6E6E;
    private static final int COLOR_ROUTE_START = 0xFF44FF88;
    private static final int COLOR_ROUTE_END = 0xFFFF4444;
    private static final double ROUTE_STROKE_PX = 4.0;
    private static final double ROUTE_MARKER_PX = 5.0;

    /**
     * Names are only drawn once the map is zoomed in enough for them not to collide.
     *
     * <p>Lower than it was, because names now shrink with the map: the reason for the gate was that a
     * screenful of full-sized words smears into itself when the map is far out, and smaller words smear
     * far less. The floor on their size is what keeps them readable at the edge of this.
     */
    private static final double LABEL_MIN_SCALE = 0.5;

    /**
     * The map scale a name's size is measured from, the size it is drawn at there, and the bounds on how
     * far it may grow or shrink from there.
     *
     * <h2>Why the base is below the font's own size</h2>
     * The font is made for reading a line of text at the top of a screen, and a name laid on a map is
     * read at a glance and in company with a hundred others: at its own size it is a shout. Everything
     * here is therefore a fraction of the font, and the largest it ever gets is barely above it.
     *
     * <p>Zoomed out from the reference a name shrinks in proportion to the map until it stops being
     * legible; zoomed in it grows far more slowly, over the whole of the range rather than in one step.
     */
    private static final double LABEL_NATURAL_SCALE = 0.75;
    /** The size at the reference: four fifths of the font. */
    private static final double LABEL_BASE_FACTOR = 0.8;
    private static final double LABEL_MIN_FACTOR = 0.55;
    private static final double LABEL_MAX_FACTOR = 1.1;
    /**
     * How far past the reference the growth from the base size to the largest is spread.
     *
     * <p>Fifty times the reference, which is the whole of the range a player ever zooms through, rather
     * than the first step of it: growing in proportion to the map reaches the largest size a little past
     * the reference, so every ordinary zoom looks the same and the close-in range has nothing left to
     * give.
     */
    private static final double LABEL_GROWTH_SPAN = 50.0;
    /**
     * How far below a place's marker its name is written, before the name's own size scales it.
     *
     * <p>Scaled with the name: a gap of a fixed number of pixels is a name floating away from its marker
     * when the text is small and touching it when the text is large, and the gap is there to keep the two
     * apart rather than to be a distance of its own. Small, because the marker already has a size.
     */
    private static final double PLACE_NAME_GAP_PX = 6.0;
    /**
     * How far above a line's own centre its name is written, before the name's own size scales it.
     *
     * <p>Half of the font's nine-pixel line height, so the road runs through the middle of the word
     * rather than under or beside it. A name that is set clear of its road is a name that has drifted
     * away from the thing it labels, which is what these read as when the offset was larger.
     */
    private static final int LINE_LABEL_RISE_PX = 4;

    /**
     * Where a world position lands on screen, through the pose the map is drawing with right now.
     *
     * <h2>Why not {@link MapViewState}</h2>
     * The map texture, the roads and the route are all placed by Xaero's own pose for the frame, and
     * Xaero builds that pose from its camera as it stands at the moment of the draw. The cached view
     * state is a different reading of the same thing -- the camera from the render info, kept for the
     * mouse handlers, which run outside the render pass -- and the two are not the same number within a
     * frame: the map moves between them. Placing a marker from the cached camera while the map under it
     * is drawn from the pose is what made the transit lines, the stop markers and the place markers
     * trail behind the map while it was being panned -- exact while standing still, dragging while
     * moving, which is the signature of two readings of one frame.
     *
     * <p>So the arithmetic here is the pose's own: the anchor is the world point Xaero built the pose
     * around, so subtracting it and applying the pose's scale and translate gives exactly the position
     * the map has just drawn the same point at. {@code scale} is Xaero's units per block, the same value
     * the roads are placed with.
     *
     * @param anchorX world x the pose was built around
     * @param anchorZ world z the pose was built around
     * @param scale   xaero's units per block for this frame
     * @param m00     the pose's x scale
     * @param m11     the pose's z scale
     * @param m30     the pose's x translate
     * @param m31     the pose's z translate
     */
    private record Projection(double anchorX, double anchorZ, double scale, double m00, double m11,
                              double m30, double m31) {

        double screenX(double worldX) {
            return (worldX - anchorX) * scale * m00 + m30;
        }

        double screenZ(double worldZ) {
            return (worldZ - anchorZ) * scale * m11 + m31;
        }
    }

    /**
     * How much bigger or smaller than the font a name is drawn at this map scale.
     *
     * <p>Zoomed out from the reference, a name shrinks in proportion to the map until it stops being
     * legible. Zoomed in, it grows far more slowly, over the whole of the range rather than in one step:
     * a name is a label rather than a measurement, and one that is already at its largest while the map is
     * still half way out makes every zoom look the same.
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
     * One name on the map, centred on a point and drawn at the size the map is zoomed to.
     *
     * <p>Text is drawn after the pose has been flattened to screen space, so the scaling has to be put
     * back for the text alone: the pose is pushed, moved to the point and scaled, and the name is written
     * at the origin of that frame. The offsets inside it are in the name's own units, so a centred name
     * stays centred at every size.
     */
    private static void drawMapLabel(GuiGraphics graphics, Font font, String name, double x, double y,
                                     int colour, float factor) {
        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(x, y, 0.0);
        pose.scale(factor, factor, 1.0F);
        graphics.drawString(font, name, -font.width(name) / 2, 0, colour, true);
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
     * Our reader hands out raw world block coordinates, so the dimension scaling Xaero would
     * otherwise apply must stay at 1.0, keeping its anchor maths in the same convention.
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
                                    GuiGraphics graphics, MultiBufferSource.BufferSource buffers,
                                    MultiTextureRenderTypeRendererProvider textureProvider) {
    }

    @Override
    public boolean renderElement(RoadElement element, boolean hovered, double depth, float screenSizeBasedScale,
                                 double fracX, double fracY, ElementRenderInfo info,
                                 GuiGraphics graphics, MultiBufferSource.BufferSource buffers,
                                 MultiTextureRenderTypeRendererProvider textureProvider) {
        PoseStack pose = graphics.pose();
        PoseStack.Pose last = pose.last();
        Matrix4f m = last.pose();

        // Cache the viewport so mouse handlers can invert this projection later in the frame.
        MapViewState.update(info, m);
        // Raw cursor position, for every element: cursor-dependent actions must work with the
        // editor closed too. Snapping is recomputed separately, once per frame, in the edit branch.
        RoadEditSession.setMouse(info.mouseX, info.mouseZ);

        // Xaero's local unit step per block. Verified in-game against a line of known length
        // (a 1000-block highway measured 4000 blocks when this was over-scaled, pinning the
        // factor at exactly 4) and confirmed independently by the fracX residual check, which
        // drops to ~0.001 with this value and stays erratic for any other.
        double p10 = info.scale;
        if (!(p10 > 0.0) || !Double.isFinite(p10)) {
            p10 = 1.0;
        }

        if (DEBUG) {
            RoadDebug.logProjection(info, m, fracX, fracY, p10, element.anchorX(), element.anchorZ());
        }

        double m00 = m.m00();
        // Pose units per screen pixel.
        double posePerPixel = Math.abs(m00) > 1.0E-6 ? 1.0 / Math.abs(m00) : 1.0;

        VertexConsumer vc = buffers.getBuffer(RenderType.debugQuads());

        if (element.kind() == RoadElement.Kind.ROUTE) {
            renderRoute(element, last, vc, fracX, fracY, p10, posePerPixel);
            // The edit UI is appended after this one, so when it is present it owns the HUD and
            // drawing it here too would double up.
            if (!RoadEditSession.isActive()) {
                renderHud(graphics, pose);
            }
            return true;
        }

        if (element.kind() == RoadElement.Kind.EDIT_UI) {
            // Xaero already hands us the cursor in world coordinates, so there is no need for a
            // mouse-move listener; refreshing here also keeps dragging exactly in step with the
            // frame being rendered. MapViewState was updated above, which snapping depends on.
            RoadEditSession.updateMouse(info.mouseX, info.mouseZ, Screen.hasAltDown());
            RoadEditSession.updateDrag();
            renderEditOverlay(element, info, last, vc, fracX, fracY, p10, posePerPixel);
            renderHud(graphics, pose);
            return true;
        }

        if (element.kind() == RoadElement.Kind.LABELS) {
            // Reached once per frame, from the trailing element. Previously the names were drawn in
            // the edit branch, so turning editing off made every label vanish.
            //
            // Not while one of this mod's own panels is up. The names are drawn here rather than by the
            // map, so an opaque panel is not enough to keep them out of it: a road name lands across a
            // list of stops and reads as part of the list. The map itself still shows through, which is
            // the context these panels want.
            net.minecraft.client.Minecraft minecraft = net.minecraft.client.Minecraft.getInstance();
            // Where the cursor is, on every frame the map draws and not only while editing: Ctrl+click
            // sets the destination, and the map's own panel is clicked through it. The raw position only:
            // snapping costs a walk of the whole network and is the editor's business.
            RoadEditSession.setMouse(info.mouseX, info.mouseZ);
            if (!(minecraft.screen instanceof TransitLineScreen)
                    && !(minecraft.screen instanceof RoadNameScreen)) {
                // Shapes before text, and the lines before the names: a line name drawn under a stop
                // marker is a name nobody can read. The line names go last of all, after the place
                // markers renderLabels emits, for the same reason.
                int lineMargin = 64;
                // Worked out once and handed to both passes: the stops draw an interchange as one orange
                // marker, and the place markers stand aside where one is drawn, since a station that is
                // also an interchange is a place whose whole point is that colour. Two passes each
                // working it out would be two answers that could disagree.
                java.util.List<TransitInterchanges.Interchange> interchanges =
                        TransitInterchanges.of(Navigation.linesInPlay());
                // Everything drawn in screen coordinates in this pass goes through this, and not through
                // the cached view state: the pose is what the map under it was just drawn with, so the
                // two cannot disagree while the map is moving.
                Projection projection = new Projection(element.anchorX(), element.anchorZ(), p10,
                        m00, m.m11(), m.m30(), m.m31());
                drawTransitLines(pose, vc, lineMargin, graphics.guiWidth() + lineMargin,
                        graphics.guiHeight() + lineMargin, Math.abs(info.scale), interchanges,
                        projection);
                renderLabels(graphics, pose, info, vc, interchanges, projection);
                // The map's switches are not drawn here. They are not part of the map: drawn from this
                // pass they came out under the lines, the markers and the names however late they were
                // emitted, because those are written through this renderer's own vertex buffers and the
                // game flushes them when it flushes them. They are a screen overlay and are drawn from
                // the screen's render event, after the map has finished -- see MapFilterOverlay.
            }
            return true;
        }

        RoadSegment segment = element.segment();
        if (segment == null || segment.vertexCount() < 2) {
            return false;
        }

        // Level of detail, and the player's own switches: one question, asked in one place, so that what
        // is shed as the map is zoomed out and what the panel hides cannot disagree. See MapFilter.
        if (!MapFilter.shows(segment.roadClass(), info.scale)) {
            return false;
        }

        int argb = segment.roadClass().color();
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        int a = (argb >>> 24) & 0xFF;
        if (a == 0) {
            a = 0xC0;
        }

        // Stroke width is a screen-space quantity: below the per-class cap it tracks the road's
        // true block width at the current zoom, above it the stroke stops widening. Only the
        // perpendicular thickness is affected; vertex positions, and therefore length, are not.
        double halfWidth = strokeHalfWidthPx(segment.roadClass(), info.scale) * posePerPixel;

        double anchorX = element.anchorX();
        double anchorZ = element.anchorZ();

        // A hand-drawn road is selected by id in the editor's network; a rail is selected by shape
        // key in the rail layer, which has no stable ids at all. Both answer the same question here,
        // which is why the two lookups sit in one expression rather than behind a flag.
        boolean selected = RoadEditSession.editor().selectedChain().contains(segment.id())
                || RailNameStore.isSelected(segment);
        if (selected) {
            // Translucent halo underneath, so the selected road reads clearly without hiding the
            // class colour that is painted over it.
            double haloHalf = halfWidth + 2.5 * posePerPixel;
            for (int i = 1; i < segment.vertexCount(); i++) {
                emitStroke(last, vc,
                        localX(segment.x(i - 1), anchorX, fracX, p10),
                        localY(segment.z(i - 1), anchorZ, fracY, p10),
                        localX(segment.x(i), anchorX, fracX, p10),
                        localY(segment.z(i), anchorZ, fracY, p10),
                        haloHalf,
                        (COLOR_SELECTED >> 16) & 0xFF, (COLOR_SELECTED >> 8) & 0xFF,
                        COLOR_SELECTED & 0xFF, 0x70);
            }
        }

        for (int i = 1; i < segment.vertexCount(); i++) {
            double x1 = localX(segment.x(i - 1), anchorX, fracX, p10);
            double y1 = localY(segment.z(i - 1), anchorZ, fracY, p10);
            double x2 = localX(segment.x(i), anchorX, fracX, p10);
            double y2 = localY(segment.z(i), anchorZ, fracY, p10);
            emitStroke(last, vc, x1, y1, x2, y2, halfWidth, r, g, b, a);
        }

        // Round joins (and round caps at the ends). Without these, consecutive segments leave a
        // wedge-shaped gap on the outside of every corner and the road looks broken.
        for (int i = 0; i < segment.vertexCount(); i++) {
            emitDisc(last, vc,
                    localX(segment.x(i), anchorX, fracX, p10),
                    localY(segment.z(i), anchorZ, fracY, p10),
                    halfWidth, r, g, b, a);
        }

        // TEMPORARY rail diagnostic: one of the auto-detected layer's segments was actually stroked,
        // past the level-of-detail filter, which is the difference between "never handed over" and
        // "handed over and dropped".
        if (RailTrackStore.isOurs(segment)) {
            RailTrackStore.noteElementStroked();
        }

        return true;
    }

    // ------------------------------------------------------------- editing UI

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
        // A place is drawn at the size every other view draws it at, converted into pose units: the
        // marker is a screen size, so nothing about it scales with the map.
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

        // Create's stations, as places and nothing more. The editor does not own that geometry, so
        // there is no handle to grab and no node to select -- only the mark every other place gets.
        // Drawn here so a station is visible while editing, rather than only on the map.
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

    // ------------------------------------------------------------- helpers

    /**
     * Road and place names, drawn in screen space above the map.
     *
     * <p>Only shown past {@link #LABEL_MIN_SCALE}; at lower zoom the labels would overlap into an
     * unreadable smear.
     */
    private void renderLabels(GuiGraphics graphics, PoseStack pose, ElementRenderInfo info,
                              VertexConsumer vc,
                              java.util.List<TransitInterchanges.Interchange> interchanges,
                              Projection projection) {
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        RoadNetwork network = RoadStore.get();

        // Labels are positioned from the view transform rather than from the culled element list, so
        // a line can land far outside the window. Skip those instead of asking the font renderer to
        // lay out text nobody will see.
        int margin = 64;
        int viewRight = graphics.guiWidth() + margin;
        int viewBottom = graphics.guiHeight() + margin;

        pose.pushPose();
        pose.last().pose().identity();
        pose.last().normal().identity();

        // Every place gets its marker first, and at every zoom. The marker is what makes a place
        // findable when the map is zoomed out, which is exactly when the names below are suppressed
        // for being unreadable -- so gating the two together would remove the marker exactly when it
        // is needed. Shapes before text: see the warning on HudDraw.
        drawPlaceMarkers(pose.last(), vc, margin, viewRight, viewBottom, interchanges,
                Math.abs(info.scale), projection);

        // Names need a zoom at which they can be read at all; at lower zoom they overlap into a
        // smear. Only the text is gated, never the markers above. Their size follows the zoom: the
        // font's own size at the reference scale, shrinking or growing with the map either side of it.
        float labelFactor = labelScale(info.scale);
        if (Math.abs(info.scale) >= LABEL_MIN_SCALE) {
            for (RoadSegment segment : network.segments()) {
                String name = segment.name();
                if (name == null) {
                    // Only named roads are labelled. Most of a fresh network is unnamed, and a
                    // placeholder on every one of them buries the names that do exist.
                    continue;
                }
                // A road that is not drawn is not named either, by the same rule that decided it: a name
                // left behind by the road it belongs to is a word pointing at nothing.
                if (!MapFilter.shows(segment.roadClass(), info.scale)) {
                    continue;
                }
                // Culled before the chain walk, not after. Walking the chain is the expensive part
                // -- RoadChains finds the continuation through a node by scanning the whole network,
                // so it is linear in the size of the network per step -- and doing it for a road that
                // is off screen is work whose result is thrown away one line later. On a large
                // network this was the label pass's dominant cost.
                double[] mid = segment.midpoint();
                int x = (int) Math.round(projection.screenX(mid[0]));
                int y = (int) Math.round(projection.screenZ(mid[1]));
                if (x < -margin || x > viewRight || y < -margin || y > viewBottom) {
                    continue;
                }
                // One label per road. A road with bends is several segments all carrying the same
                // name, so without this the label would repeat at every corner.
                List<Integer> chain = RoadChains.chainContaining(network, segment.id());
                if (RoadChains.middleSegment(chain) != segment.id()) {
                    continue;
                }
                // A name longer than the road it belongs to would hang off both its ends and read as
                // a label for whatever is beside it, so it is dropped rather than written.
                if (font.width(name) * labelFactor > chainLengthPx(network, chain, projection)) {
                    continue;
                }
                drawLineLabel(graphics, pose, font, name, segment, COLOR_LABEL_ROAD, projection,
                        labelFactor);
            }
            // Railway names, one per line. Which segment of a chain carries the label was decided
            // when the layer was rebuilt, so this asks a map instead of walking every chain a frame.
            for (RoadSegment segment : RailTrackStore.segments()) {
                String name = RailNameStore.labelAt(segment);
                if (name == null) {
                    continue;
                }
                RoadNetwork layer = RailTrackStore.network();
                // The same rule as for a road, over the whole railway. The chain is walked here rather
                // than remembered: only the one segment per railway that carries the label gets this
                // far, so it runs for a handful of segments a frame and not for the whole layer.
                if (font.width(name) * labelFactor > chainLengthPx(layer,
                        RoadChains.chainContaining(layer, segment.id()), projection)) {
                    continue;
                }
                double[] mid = segment.midpoint();
                int x = (int) Math.round(projection.screenX(mid[0]));
                int y = (int) Math.round(projection.screenZ(mid[1]));
                if (x < -margin || x > viewRight || y < -margin || y > viewBottom) {
                    continue;
                }
                drawLineLabel(graphics, pose, font, name, segment, COLOR_LABEL_RAIL, projection,
                        labelFactor);
            }

            // Place names, under their markers. A station's name comes from
            // CreateStationSource.nameOf, the same call the picker lists it under, so the label on
            // the map and the entry in the list cannot become two names for one station.
            for (RoadNode node : network.nodes()) {
                if (node.type() != RoadNode.Type.POI || node.name() == null) {
                    continue;
                }
                if (!MapFilter.shows(node.placeKind(), info.scale)) {
                    continue;
                }
                int x = (int) Math.round(projection.screenX(node.x()));
                int y = (int) Math.round(projection.screenZ(node.z()));
                if (x < -margin || x > viewRight || y < -margin || y > viewBottom) {
                    continue;
                }
                drawMapLabel(graphics, font, node.name(), x, y + PLACE_NAME_GAP_PX * labelFactor,
                        HudDraw.COLOR_PLACE, labelFactor);
            }

            if (MapFilter.shows(PlaceKind.STATION, info.scale)) {
                for (RailTrackStore.Station station : RailTrackStore.stations()) {
                    String name = CreateStationSource.nameOf(station);
                    int x = (int) Math.round(projection.screenX(station.x()));
                    int y = (int) Math.round(projection.screenZ(station.z()));
                    if (x < -margin || x > viewRight || y < -margin || y > viewBottom) {
                        continue;
                    }
                    drawMapLabel(graphics, font, name, x, y + PLACE_NAME_GAP_PX * labelFactor,
                            HudDraw.COLOR_PLACE, labelFactor);
                }
            }
        }

        pose.popPose();
    }

    /**
     * The total on-screen length of a run of segments, in pixels.
     *
     * <p>Measured over the whole run rather than one segment, because that is what a name belongs to:
     * a road with bends is several segments sharing one name, and a name that reaches across two of
     * its own pieces is exactly right. Measuring a single piece would drop the name of a road drawn
     * with many short clicks even though there is plenty of road to write it on.
     */
    private static double chainLengthPx(RoadNetwork network, List<Integer> chain,
                                     Projection projection) {
        double total = 0;
        for (int id : chain) {
            RoadSegment member = network.segment(id);
            if (member == null) {
                continue;
            }
            for (int i = 1; i < member.vertexCount(); i++) {
                total += Math.hypot(
                        projection.screenX(member.x(i)) - projection.screenX(member.x(i - 1)),
                        projection.screenZ(member.z(i)) - projection.screenZ(member.z(i - 1)));
            }
        }
        return total;
    }

    /**
     * Draws a name along its line rather than across it.
     *
     * <h2>Why rotated</h2>
     * A road is a line and its name belongs to the line; a horizontal word laid over a diagonal road
     * crosses it and reads as a separate object standing there, which is what looked out of place.
     * Rotating the label to the line's own direction is what every map does and what makes a name
     * read as belonging to the road it sits on.
     *
     * <h2>How the rotation is applied</h2>
     * The label pass has already flattened the pose to screen space, so the translation is in pixels
     * and the rotation is about the label's own centre -- the text is then drawn at the origin and
     * the half-width offset puts its middle on the line.
     *
     * <h2>The perpendicular offset</h2>
     * The text is centred on the line rather than pushed clear of it: a name set a few pixels off its
     * road is a name that has floated away from the road, which is the fault that made these look
     * placed by hand. Half a line of text is the whole of the offset, so the road runs through the
     * middle of the word at every size.
     */
    private void drawLineLabel(GuiGraphics graphics, PoseStack pose, Font font, String name,
                               RoadSegment segment, int color, Projection projection,
                               float factor) {
        double[] mid = segment.midpoint();
        double x = projection.screenX(mid[0]);
        double y = projection.screenZ(mid[1]);

        pose.pushPose();
        pose.translate(x, y, 0.0);
        pose.mulPose(Axis.ZP.rotation((float) screenAngle(segment, projection)));
        pose.scale(factor, factor, 1.0F);
        graphics.drawString(font, name, -font.width(name) / 2, -LINE_LABEL_RISE_PX, color, true);
        pose.popPose();
    }

    /**
     * The direction of a segment on screen, in radians, clamped so a name is never upside down.
     *
     * <p>Read from the segment's own two ends: the label sits at the segment's middle, and a segment
     * of a road is short enough that its overall direction is its direction there. Past a right angle
     * the label would be readable only by tilting one's head, so it is turned the other way instead --
     * the same choice a printed map makes.
     */
    private static double screenAngle(RoadSegment segment, Projection projection) {
        int last = segment.vertexCount() - 1;
        double dx = projection.screenX(segment.x(last)) - projection.screenX(segment.x(0));
        double dy = projection.screenZ(segment.z(last)) - projection.screenZ(segment.z(0));
        double angle = Math.atan2(dy, dx);
        if (angle > Math.PI / 2 || angle < -Math.PI / 2) {
            angle += Math.PI;
        }
        return angle;
    }

    /**
     * A yellow square at every place.
     *
     * <h2>Which pose these are emitted with</h2>
     * The marker pass runs <b>after</b> the pose has been flattened, so its coordinates are screen
     * pixels and the pose it is handed must be the flattened one -- {@code pose.last()} as it stands
     * inside this call, not the map pose captured before {@code pushPose}. Emitting with the map pose
     * and screen coordinates applies the map transform a second time, which is what put the first
     * version of these markers off to one side of the places they were marking.
     *
     * <p>The places come from {@link Destinations#places()}, the same list the panel and the picker
     * mark, so the three views cannot disagree about what a place is or what it is called.
     *
     * <p>Shapes before text: see the warning on HudDraw.
     */
    private static final double LINE_STROKE_PX = 2.5;
    /** Narrowest a line is drawn at, so a line never disappears however far the map is zoomed out. */
    private static final double MIN_LINE_STROKE_PX = 0.9;
    /** Map scale at which a line is drawn at its full width: below it, the stroke thins with the map. */
    private static final double FULL_LINE_STROKE_SCALE = 0.5;
    private static final double LINE_STOP_PX = 5.0;
    /**
     * The map scale at which a marker is drawn at {@link HudDraw#PLACE_MARKER_PX} across, and the bounds
     * on how far it may grow or shrink from there.
     *
     * <p>A marker is a place on the ground, so it is drawn at the size of a place on the ground: a fixed
     * number of pixels is a marker that covers half a village when the map is zoomed in and is invisible
     * when it is zoomed out. The bounds are what keep it from becoming a wall or a speck at the extremes.
     */
    private static final double MARKER_NATURAL_SCALE = 0.5;
    private static final double MARKER_MIN_HALF_PX = 1.4;
    /**
     * The most a marker may grow to, in half-width pixels.
     *
     * <p>About a third of what a marker reaches if it is left to follow the scale all the way up: a place
     * is a dot on a map, and one that grows to a fifth of the screen while the map is zoomed in is a
     * building, not a marker. The scale still moves it between this and the floor below.
     */
    private static final double MARKER_MAX_HALF_PX = 2.2;
    /**
     * How close two interchange markers have to land to be drawn as one, in pixels.
     *
     * <p>Twice the marker's radius, which is where two of them are just touching: any closer and they
     * overlap, which is the case the fused marker exists for. Further apart they are two markers, at
     * whatever zoom that happens to be.
     */
    private static final int COLOR_LINE_TRANSFER = 0xFFFF7A3C;
    /**
     * The dark backing under a stop marker.
     *
     * <p>A stop is drawn in its own line's colour so that which line it belongs to can be read off the
     * map, and a coloured square on pale ground is otherwise invisible. This was the place marker's
     * yellow to begin with, which made a stop and an ordinary place look like the same thing.
     */
    private static final int COLOR_LINE_STOP_EDGE = 0xFF10141A;

    /**
     * The public transport lines, drawn over everything else and at every zoom.
     *
     * <p>Straight from stop to stop, which is the service rather than the track: the rails and roads a
     * line runs along are already drawn underneath by the layer that owns them, and a line that
     * redrew them would be claiming to know the route better than the rails do. What this adds is the
     * thing nothing else on the map can say -- which stops belong to which line, in which order, and
     * where two lines meet.
     *
     * <p>An interchange is marked by counting, not by comparing: a stop two lines call at is something
     * the player cannot see from either line alone, so it gets its own colour and is drawn after the
     * ordinary stops so that it is never hidden by one.
     */
    /** The path each line actually runs along, one shape per line, and the lines it was built for. */
    private static String lineShapeSignature = "";
    private static List<List<double[]>> lineShapes = List.of();
    /** The line count the diagnostic last reported, so it speaks when that changes and not per frame. */
    private static int reportedLineCount = -1;
    /**
     * One line's shape, kept until that line's own stops or kind change.
     *
     * <p>Per line rather than for all of them, because the lines are no longer all the player's: the
     * ones read out of MTR arrive a window at a time and change as the player walks, and rebuilding
     * every line's shape because one imported line gained a stop would be a hitch in the middle of
     * walking -- on the render thread, which is the worst place for one.
     */
    private static final java.util.Map<String, List<double[]>> lineShapeCache =
            new java.util.HashMap<>();

    /**
     * Works out the path each line runs along, by planning each pair of neighbouring stops exactly as
     * the router will.
     *
     * <p>A straight hop between two stops is not the line: a railway between two stations may run a long
     * way round, and drawing the chord instead of the track hides the one thing the map is for -- where
     * the line actually goes. Planning the pairs costs a route each, so the result is cached and rebuilt
     * only when a line's kind or its stops change.
     *
     * <p>A pair that cannot be planned still gets its straight hop, so a mis-typed line is visible as a
     * line rather than as a gap.
     */
    private static void refreshLineShapes(List<TransitLine> lines) {
        StringBuilder signature = new StringBuilder();
        for (TransitLine line : lines) {
            signature.append(shapeKey(line));
        }
        if (signature.toString().equals(lineShapeSignature)) {
            return;
        }
        lineShapeSignature = signature.toString();

        // One workspace per network rather than per line: a workspace copies the network and repairs
        // its joins before the first query through it, and every line that routes on the same roads --
        // the same kind, and with or without the imported rails -- reuses one.
        java.util.Map<String, bili.dongsz.howtogo.route.RoadRouter.Workspace> workspaces =
                new java.util.HashMap<>();
        java.util.Map<String, List<double[]>> rebuilt = new java.util.HashMap<>();
        List<List<double[]>> shapes = new java.util.ArrayList<>(lines.size());
        for (TransitLine line : lines) {
            String key = shapeKey(line);
            List<double[]> points = lineShapeCache.get(key);
            if (points == null) {
                points = planLine(line, workspaces);
            }
            rebuilt.put(key, points);
            shapes.add(points);
        }
        // Only what this pass asked for is kept, so a line that is gone does not keep its shape alive.
        lineShapeCache.clear();
        lineShapeCache.putAll(rebuilt);
        lineShapes = shapes;
    }

    /** What makes a line's shape its own: which line, of which kind, calling where, over which roads. */
    private static String shapeKey(TransitLine line) {
        StringBuilder key = new StringBuilder();
        key.append(line.id()).append(line.kind().name());
        // Whether the line rides its own marks is part of the shape: turning the imported rails off has
        // to redraw the line along the roads it will now be ridden over, not leave the old drawing up.
        key.append(MtrTransit.marksEnabled(line) ? "+marks" : "-marks");
        for (LineStop stop : line.stops()) {
            key.append('|').append(stop.x()).append(',').append(stop.z());
        }
        return key.toString();
    }

    /**
     * One line's path: its own track where it has one, and a planned route over the roads where it does
     * not.
     *
     * <p>A line read out of MTR is drawn along the track it runs on, which is known and is not a question
     * about anybody's roads -- and is drawn that way whether or not the line's marks are switched on. The
     * switch decides whether that track is also added to the road network as rail or water roads; it has
     * nothing to do with where the line is drawn. Planning an imported line over the roads instead, which
     * is what this used to do, is what made a line switched off collapse into straight hops between its
     * stops and read as having gone missing.
     *
     * <p>A line of the player's own has no track of its own, so it keeps the planning it always had: the
     * pairs of neighbouring stops are planned over the network the line is ridden on, and a pair that
     * cannot be planned gets its straight hop so that a mis-typed line is visible as a line rather than as
     * a gap.
     */
    private static List<double[]> planLine(TransitLine line,
                                           java.util.Map<String,
                                                   bili.dongsz.howtogo.route.RoadRouter.Workspace>
                                                   workspaces) {
        List<double[]> track = ownTrack(line);
        if (!track.isEmpty()) {
            return track;
        }
        List<double[]> points = new java.util.ArrayList<>();
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
            if (ride.isPresent()) {
                points.addAll(ride.points());
            } else {
                points.add(new double[]{from.x(), from.z()});
                points.add(new double[]{to.x(), to.z()});
            }
        }
        return points;
    }

    /**
     * The track of an imported line, as one polyline, or an empty list for a line that has none.
     *
     * <p>The pieces are walked one after another rather than in any order of the line's stops, because
     * drawing does not care: a pair whose track is missing leaves the two pieces on either side of it
     * joined by the straight hop between them, which is the same thing the planning fallback draws and is
     * the honest answer for a stretch whose track is not known.
     */
    private static List<double[]> ownTrack(TransitLine line) {
        bili.dongsz.howtogo.road.RoadNetwork track = MtrTransit.trackOf(line);
        if (track == null || track.segmentCount() == 0) {
            return List.of();
        }
        List<double[]> points = new java.util.ArrayList<>();
        for (RoadSegment segment : track.segmentsSnapshot()) {
            for (int i = 0; i < segment.vertexCount(); i++) {
                points.add(new double[]{segment.x(i), segment.z(i)});
            }
        }
        return points.size() >= 2 ? points : List.of();
    }

    private List<LineLabel> drawTransitLines(PoseStack pose, VertexConsumer vc, int margin,
                                             int viewRight, int viewBottom, double scale,
                                             java.util.List<TransitInterchanges.Interchange>
                                                     interchanges,
                                             Projection projection) {
        List<LineLabel> labels = new java.util.ArrayList<>();
        // The player's lines and the ones read out of MTR: a line the mod will plan a journey over is
        // a line whose route the map should show, whichever of the two it came from. Never shed by zoom:
        // a line is what this map is for, and it is the one thing on it the roads do not already imply.
        if (!MapFilter.showsLines()) {
            return labels;
        }
        List<TransitLine> lines = Navigation.linesInPlay();
        reportLines(lines);
        if (lines.isEmpty()) {
            return labels;
        }
        // The screen-space transform, taken exactly as renderLabels takes it: the coordinates below come
        // from MapViewState, which is already screen space, so they may only be emitted under an identity
        // pose. Emitting them under the map's own transform applies that transform a second time, and a
        // line that floats away from its own stops is what that looks like.
        pose.pushPose();
        pose.last().pose().identity();
        pose.last().normal().identity();
        PoseStack.Pose screenPose = pose.last();

        // Which of these stops are places two lines meet at, handed in rather than worked out here:
        // the place markers stand aside for exactly the same set, so the two passes have to be looking
        // at one answer. See TransitInterchanges, which carries the rule itself.
        refreshLineShapes(lines);
        if (lineShapes.size() != lines.size()) {
            // Should not happen -- the shapes are rebuilt whenever the lines change -- and it is checked
            // because the failure it would cause is silent and total: indexing past the end throws, the
            // whole overlay is lost for that frame, and what the player sees is every line disappearing
            // at once, which reads as the mod having forgotten them. Rebuilding is cheap next to that.
            lineShapeSignature = "";
            refreshLineShapes(lines);
        }
        for (int index = 0; index < lines.size(); index++) {
            TransitLine line = lines.get(index);
            List<double[]> shape = index < lineShapes.size() ? lineShapes.get(index) : List.of();
            if (shape.size() < 2) {
                // A line is drawn as a line: a pair that could not be planned at all still gets the
                // straight hop between its ends, so a line nobody can ride is visible as a line rather
                // than as nothing at all.
                shape = hop(line);
            }
            for (int i = 1; i < shape.size(); i++) {
                double x1 = projection.screenX(shape.get(i - 1)[0]);
                double y1 = projection.screenZ(shape.get(i - 1)[1]);
                double x2 = projection.screenX(shape.get(i)[0]);
                double y2 = projection.screenZ(shape.get(i)[1]);
                if (Math.max(x1, x2) < -margin || Math.min(x1, x2) > viewRight
                        || Math.max(y1, y2) < -margin || Math.min(y1, y2) > viewBottom) {
                    continue;
                }
                HudDraw.emitLine(screenPose, vc, x1, y1, x2, y2, lineStrokePx(scale), lineColour(line),
                        0xFF);
            }
            if (shape.size() >= 2) {
                // No name on the map. It was tried at the middle stop and at the middle of the path and
                // was wrong in both places -- on a line that curves or loops, no single point along it is
                // the middle a reader means, and a name written over the stroke or beside a station
                // marker is worse than no name. Read off the line editor instead, which lists a line's
                // stops in order and has room to say what it is called.
            }
        }

        // The stops, each at the size a place on the ground covers at this zoom, and each only while the
        // place it stands at is being drawn at all: a stop marker left behind by a station that the zoom
        // (or the panel) has taken off the map is a dot pointing at nothing.
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
                HudDraw.emitPlaceMarker(screenPose, vc, x, y, stopHalf + 1.0,
                        COLOR_LINE_STOP_EDGE);
                HudDraw.emitPlaceMarker(screenPose, vc, x, y, stopHalf, lineColour(line));
            }
        }

        // One marker per place two lines meet at, and one marker per group of its stops that land on top
        // of each other: at a zoom where the two stops are far apart they are drawn separately, which is
        // the map showing what it knows rather than fusing them at every scale. A group of one is that
        // stop's own marker, in the interchange colour, because a stop two lines call at is an
        // interchange whether or not its marker happens to touch the other's.
        //
        // An interchange is a station, so it goes the way the stations go: the zoom that takes the station
        // markers off the map takes these with them.
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
                        HudDraw.emitPlaceMarker(screenPose, vc, x, y, stopHalf + 1.0,
                                COLOR_LINE_STOP_EDGE);
                        HudDraw.emitPlaceMarker(screenPose, vc, x, y, stopHalf, COLOR_LINE_TRANSFER);
                    } else {
                        // A square, and the same size as a stop's round marker: the fused place is a thing
                        // of its own rather than a stop of either line, and the shape is what says so.
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

    /**
     * Whether the place a stop stands at is being drawn, which is what the stop marker follows.
     *
     * <p>A stop made at a place the player marked is that place's kind; a stop at a station this mod read
     * out of another mod is a station. The two answers differ in what the map's switches do to them: a
     * line calling at a shop is hidden with the shops, and one calling at a station with the stations.
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
     * <p>The map's own transform is not applied to markers -- they are emitted in screen space -- so the
     * size has to be worked out from the scale by hand. What it buys is a marker that keeps its size on
     * the ground: the same place covers more pixels when the map is zoomed in, and fewer when it is
     * zoomed out, until the bounds stop it.
     */
    private static double markerHalfPx(double fullSizePx, double scale) {
        double natural = fullSizePx * 0.5 * Math.abs(scale) / MARKER_NATURAL_SCALE;
        return Math.max(MARKER_MIN_HALF_PX, Math.min(MARKER_MAX_HALF_PX, natural));
    }

    /**
     * How wide a transit line is drawn, in screen pixels.
     *
     * <p>A line's stroke is emitted in screen space, so a constant number is a stroke that grows
     * <em>relative to the map</em> as the map is zoomed out: at a scale where a road is a hairline, a
     * line was still two and a half pixels of solid colour and read as a rope laid over the map. It
     * thins with the map down to a floor, so it is never invisible and never fat.
     */
    private static double lineStrokePx(double scale) {
        double thinned = LINE_STROKE_PX * Math.abs(scale) / FULL_LINE_STROKE_SCALE;
        return Math.max(MIN_LINE_STROKE_PX, Math.min(LINE_STROKE_PX, thinned));
    }

    /**
     * How many lines the map is drawing, written when that changes.
     *
     * <p>One line per change and none otherwise, and only while a map is open. It is here because "the
     * line disappeared" has two very different causes that look identical from outside -- the line
     * leaving the list the map draws from, and the line being in the list but drawn nowhere -- and this
     * is the number that tells them apart without a guess: a count that stays put while the stroke goes
     * is the second, and a count that drops is the first.
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
        HowToGo.LOGGER.info("[HowToGo] drawing {} transit line(s), {} of them read out of MTR, "
                + "{} stops between them; {} line(s) and {} station(s) remembered", lines.size(),
                imported, stops, MtrTransit.rememberedLines(), MtrTransit.rememberedStations());
    }

    /** A line's two ends as a straight hop, for a line whose path could not be worked out at all. */
    private static List<double[]> hop(TransitLine line) {
        if (line.stopCount() < 2) {
            return List.of();
        }
        LineStop first = line.stops().get(0);
        LineStop last = line.stops().get(line.stopCount() - 1);
        return List.of(new double[] {first.x(), first.z()}, new double[] {last.x(), last.z()});
    }

    /** Whether a place is one of the stops an interchange marker already stands for. */
    private static boolean inAnInterchange(
            java.util.List<TransitInterchanges.Interchange> interchanges, int x, int z) {
        for (TransitInterchanges.Interchange interchange : interchanges) {
            if (interchange.holds(x, z)) {
                return true;
            }
        }
        return false;
    }

    /** A line's name and where to write it, kept until every shape has been emitted. */
    private record LineLabel(String name, double x, double y, int colour) {
    }

    /**
     * The line names, written after the shapes.
     *
     * <p>Last because drawing text flushes the vertex batch: a name written before the polyline and the
     * stop markers would leave them being emitted into a buffer that had already been sent, which is
     * how a map ends up with no shapes on it and an exception in the log.
     */
    private void drawLineNames(GuiGraphics graphics, List<LineLabel> labels) {
        net.minecraft.client.gui.Font font = net.minecraft.client.Minecraft.getInstance().font;
        int margin = 64;
        int viewRight = graphics.guiWidth() + margin;
        int viewBottom = graphics.guiHeight() + margin;
        for (LineLabel label : labels) {
            if (label.x() < -margin || label.x() > viewRight || label.y() < -margin
                    || label.y() > viewBottom) {
                continue;
            }
            graphics.drawString(font, label.name(),
                    (int) Math.round(label.x()) - font.width(label.name()) / 2,
                    (int) Math.round(label.y()), label.colour(), true);
        }
    }

    /** A line's colour, from the kind of road it runs on. */
    private static int lineColour(TransitLine line) {
        return switch (line.kind()) {
            case RAIL -> 0xFF8AB4FF;
            case WATER -> 0xFF3FA9F5;
            case ICE -> 0xFF9FE8FF;
            default -> 0xFFB8E986;
        };
    }

    private void drawPlaceMarkers(PoseStack.Pose screenPose, VertexConsumer vc,
                                  int margin, int viewRight, int viewBottom,
                                  java.util.List<TransitInterchanges.Interchange> interchanges,
                                  double scale, Projection projection) {
        double half = markerHalfPx(HudDraw.PLACE_MARKER_PX, scale);
        for (Destination place : Destinations.places()) {
            // The panel's switches and the map's zoom, through the one rule: a shop is shed before a
            // station, and a resource point -- worth travelling to from far away -- last of all.
            if (!MapFilter.shows(place.kind(), scale)) {
                continue;
            }
            // A place a line stops at is drawn twice -- once as a place, once as the stop -- and the two
            // markers are the same size in the same spot, so the later one hides the earlier. That is the
            // wrong way round for an interchange, whose whole point is its colour: the place marker
            // stands aside there and lets the orange marker be the one that is seen.
            if (inAnInterchange(interchanges, place.x(), place.z())) {
                continue;
            }
            double x = projection.screenX(place.x());
            double y = projection.screenZ(place.z());
            if (x < -margin || x > viewRight || y < -margin || y > viewBottom) {
                continue;
            }
            HudDraw.emitPlaceMarker(screenPose, vc, x, y, half, HudDraw.COLOR_PLACE);
        }
    }

    /**
     * The active navigation route, drawn over the roads.
     *
     * <p>Coloured by progress the way a navigation app does: the part already walked is grey, the
     * part still ahead is cyan. A segment straddling the player's position is split at the exact
     * point rather than being coloured whole, so the boundary does not lurch a segment at a time.
     */
    private void renderRoute(RoadElement element, PoseStack.Pose pose, VertexConsumer vc,
                             double fracX, double fracY, double p10, double posePerPixel) {
        Route route = Navigation.route();
        double anchorX = element.anchorX();
        double anchorZ = element.anchorZ();
        double marker = ROUTE_MARKER_PX * posePerPixel;

        if (!route.isPresent()) {
            // Still show where the trip started and where it is trying to get to, so a failed
            // route does not look like the destination was never set.
            drawMarker(pose, vc, anchorX, anchorZ, fracX, fracY, p10,
                    Navigation.tripOriginX(), Navigation.tripOriginZ(), marker, COLOR_ROUTE_START);
            Destination destination = Navigation.target();
            if (destination != null) {
                drawMarker(pose, vc, anchorX, anchorZ, fracX, fracY, p10,
                        destination.x(), destination.z(), marker, COLOR_ROUTE_END);
            }
            return;
        }

        List<double[]> points = route.points();
        double halfWidth = ROUTE_STROKE_PX * 0.5 * posePerPixel;
        double travelled = Navigation.travelled();

        int aheadR = (COLOR_ROUTE >> 16) & 0xFF;
        int aheadG = (COLOR_ROUTE >> 8) & 0xFF;
        int aheadB = COLOR_ROUTE & 0xFF;
        int doneR = (COLOR_ROUTE_DONE >> 16) & 0xFF;
        int doneG = (COLOR_ROUTE_DONE >> 8) & 0xFF;
        int doneB = COLOR_ROUTE_DONE & 0xFF;

        double cumulative = 0;
        double[] pointDistance = new double[points.size()];
        for (int i = 1; i < points.size(); i++) {
            double[] a = points.get(i - 1);
            double[] b = points.get(i);
            double segmentLength = Math.hypot(b[0] - a[0], b[1] - a[1]);

            double ax = localX(a[0], anchorX, fracX, p10);
            double ay = localY(a[1], anchorZ, fracY, p10);
            double bx = localX(b[0], anchorX, fracX, p10);
            double by = localY(b[1], anchorZ, fracY, p10);

            if (segmentLength < 1.0E-9) {
                pointDistance[i] = cumulative;
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
            pointDistance[i] = cumulative;
        }

        for (int i = 0; i < points.size(); i++) {
            double[] p = points.get(i);
            boolean behind = pointDistance[i] < travelled;
            emitDisc(pose, vc,
                    localX(p[0], anchorX, fracX, p10), localY(p[1], anchorZ, fracY, p10), halfWidth,
                    behind ? doneR : aheadR, behind ? doneG : aheadG, behind ? doneB : aheadB, 0xFF);
        }

        double[] end = points.get(points.size() - 1);
        // The origin marker is pinned to where the trip began, not to the current route's first
        // point, so re-planning after a detour does not drag it along.
        drawMarker(pose, vc, anchorX, anchorZ, fracX, fracY, p10,
                Navigation.tripOriginX(), Navigation.tripOriginZ(), marker, COLOR_ROUTE_START);
        drawMarker(pose, vc, anchorX, anchorZ, fracX, fracY, p10,
                end[0], end[1], marker, COLOR_ROUTE_END);
    }

    /** Draws one of the round route endpoint markers. */
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
     * Bottom-of-screen readout: the trip readout with the editing headline on the left, the road
     * editing controls on the right.
     *
     * <p>The map pose is flattened to identity first, so this draws in plain GUI coordinates the
     * same way any other screen text would. Each block is sized to its own text rather than to the
     * full width, and is inset from its own edge, so neither sits on top of Xaero's own bars.
     *
     * <p>The split is by how often each line is read. The class being drawn, the swatch standing for
     * it and the live totals belong with the readout, where the player is already looking; the
     * control list is a reference consulted once and then ignored, so it is worth a block of its own
     * out of the way rather than a long line crowded in beside everything else.
     *
     * <p>Every quad goes out before any string. A text draw flushes the buffer source, so a quad
     * emitted after one would have to go through a consumer fetched afresh; keeping the fills in a
     * single pass removes the question, and this method borrows no consumer of its own.
     */
    private void renderHud(GuiGraphics graphics, PoseStack pose) {
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

                // Says so on the map readout too: the mode is named just above, and a route that
                // quietly ignores it would look like the estimate had gone wrong.
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

        // The editing headline follows the readout in the same block: the caption names the class,
        // the swatch beside it is that name in colour, and the totals below are the same subject.
        // Only the control scheme is elsewhere, one control per line.
        List<String> hintLines = new ArrayList<>();
        int swatchColor = 0;
        int swatchLine = 0;
        if (RoadEditSession.isActive()) {
            RoadClass roadClass = RoadEditSession.cycleTarget();
            boolean segmentSelected =
                    RoadEditSession.editor().selectedSegmentId() != RoadSegment.NO_SEGMENT;
            swatchLine = leftLines.size();
            leftLines.add(Component.translatable(
                    segmentSelected ? "hud.howtogo.selected" : "hud.howtogo.drawing",
                    roadClass.name()).getString());
            leftLines.add(editStats());
            hintLines.addAll(controlHints());
            swatchColor = 0xFF000000 | (roadClass.color() & 0xFFFFFF);
        }

        if (leftLines.isEmpty() && hintLines.isEmpty()) {
            return;
        }

        int screenWidth = mc.getWindow().getGuiScaledWidth();
        int screenHeight = mc.getWindow().getGuiScaledHeight();

        pose.pushPose();
        pose.last().pose().identity();
        pose.last().normal().identity();

        // The swatch sits in the indent left of the text, on the caption line it stands for, so the
        // whole block moves right by that indent and the caption keeps its place in the column.
        int leftIndent = swatchColor != 0 ? 11 : 0;
        int leftWidth = widestLine(font, leftLines);
        int leftTop = blockTop(hudBottom(screenHeight), leftLines.size());
        int leftPanelRight = HUD_LEFT_INSET + leftWidth + leftIndent + 6;

        // The hints carry no swatch, so their panel is the text with the same padding on both sides,
        // and it is the text that is aligned: its right edge lands on the inset the left block starts
        // from. Floored at the screen margin rather than left to run negative, so a hint too wide for
        // the gap between the insets slides the block rightwards instead of off the screen -- which
        // these strings are nowhere near, but which should not depend on them staying short.
        int hintWidth = widestLine(font, hintLines);
        int hintTextRight = Math.max(screenWidth - HUD_RIGHT_INSET, HUD_MARGIN + hintWidth + 4);
        int hintPanelLeft = hintTextRight - hintWidth - 4;
        int hintTextLeft = hintPanelLeft + 4;

        // Both blocks live in the bottom band, and a long navigation or stats line can be wider than
        // the gap between the two insets, which would print them over each other. The readout, the
        // caption and the totals are the ones anchored to their corner, so the hint list is what
        // moves: it steps up above the other panel when, and only when, the two would meet. The 3
        // and 2 are the panels' own overhangs above their first line and below their last, which the
        // gap has to clear as well as the margin.
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
            graphics.drawString(font, line, HUD_LEFT_INSET + leftIndent, y, HUD_FG, true);
            y += HUD_LINE_HEIGHT;
        }
        y = hintTop;
        for (String line : hintLines) {
            graphics.drawString(font, line, hintTextLeft, y, HUD_FG, true);
            y += HUD_LINE_HEIGHT;
        }

        pose.popPose();
    }

    /** Live totals for the road being drawn. */
    private static String editStats() {
        String stats = Component.translatable("hud.howtogo.stats",
                (int) Math.round(RoadEditSession.pendingLength()),
                (int) Math.round(RoadEditSession.totalLength()),
                RoadStore.get().nodeCount(),
                RoadStore.get().segmentCount()).getString();
        if (RoadEditSession.isFreePlacementActive()) {
            // The mode being on is state, while the control hint only says the key exists.
            stats = stats + "   " + Component.translatable("hud.howtogo.free").getString();
        }
        return stats;
    }

    /** The editing controls, one per line, in the order they are worth learning. */
    private static List<String> controlHints() {
        List<String> hints = new ArrayList<>(EDIT_HINT_KEYS.size());
        for (String key : EDIT_HINT_KEYS) {
            hints.add(Component.translatable(key).getString());
        }
        return hints;
    }

    /** Width of the widest line in a block, which is what its backing panel is sized to. */
    private static int widestLine(Font font, List<String> lines) {
        int widest = 0;
        for (String line : lines) {
            widest = Math.max(widest, font.width(line));
        }
        return widest;
    }

    /**
     * Line a HUD block's bottom is anchored to.
     *
     * <p>The 4 px covers the panel's own 2 px overhang below its last line and keeps that overhang
     * off the margin line, so a block never quite touches the screen edge.
     */
    private static int hudBottom(int screenHeight) {
        return screenHeight - HUD_MARGIN - 4;
    }

    /**
     * Top of a block of the given line count sitting on the given bottom.
     *
     * <p>Nothing is clamped here because nothing needs to be: the worst case is a six-line left block
     * -- four navigation lines, the caption and the totals -- with the ten-line control list stepped
     * above it, whose panel top still lands at 40 px of a scaled space Minecraft never makes shorter
     * than 240. A longer list would run off the top and be clipped by the renderer, which is the
     * point at which this would need a scroll or a second column rather than more lines.
     */
    private static int blockTop(int bottom, int lineCount) {
        return bottom - HUD_LINE_HEIGHT * lineCount;
    }

    /** Human-readable phrase for a signed turn angle, positive being a right turn. */
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
     * <p>Below the cap the width is the road's true block width at the current zoom, so the map
     * stays metrically honest when zoomed out. Above it the stroke stops widening, which keeps
     * high zoom levels readable instead of turning every road into a solid band.
     */
    private static double strokeHalfWidthPx(RoadClass roadClass, double scale) {
        double naturalHalf = roadClass.width() * 0.5 * Math.abs(scale);
        double cappedHalf = (3.0 + roadClass.width() * 0.7) * 0.5;
        return Math.max(MIN_STROKE_PX * 0.5, Math.min(naturalHalf, cappedHalf));
    }

    /** Emits one thick line segment as a single quad in the map's plane. */
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

    /** Emits an axis-aligned filled box centred on the given point. */
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
     * Emits a filled circle as a triangle fan.
     *
     * <p>The target render type is {@code QUADS}, so each triangle is written as a degenerate
     * quad with its last corner repeated (triangle strip winding is not available here).
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
            vertex(pose, vc, x0, y0, r, g, b, a);
        }
    }

    private static void vertex(PoseStack.Pose pose, VertexConsumer vc, double x, double y,
                               int r, int g, int b, int a) {
        vc.addVertex(pose, (float) x, (float) y, 0.0F).setColor(r, g, b, a);
    }
}
