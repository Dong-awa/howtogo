package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.HowToGo;
import bili.dongsz.howtogo.road.PlaceKind;
import bili.dongsz.howtogo.road.RoadChains;
import bili.dongsz.howtogo.road.RoadClass;
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

    /**
     * How much a polyline has to bend at a vertex before the joint needs a round cap, in degrees.
     *
     * <p>Low enough that any corner a player draws gets one -- the shallowest bend that reads as a
     * corner is far above this -- and high enough that the per-block wobble of a sampled curve does
     * not: those vertices are a fraction of a degree apart and their strokes overlap completely.
     */
    private static final double JOINT_MIN_DEGREES = 20.0;

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
            "hud.howtogo.edit.layer",
            "hud.howtogo.edit.name",
            "hud.howtogo.edit.oneway",
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

        /**
         * The same arithmetic backwards: the world x a screen column is showing.
         *
         * <p>What the line pass culls with. Taking the view from the cached view state instead would be
         * a second reading of the frame, which is the thing this record exists to avoid -- see the
         * warning on the class about the map moving between the two.
         */
        double worldX(double screenX) {
            double perBlock = scale * m00;
            return Math.abs(perBlock) > 1.0E-9 ? (screenX - m30) / perBlock + anchorX : anchorX;
        }

        /** The same for a screen row and world z. */
        double worldZ(double screenY) {
            double perBlock = scale * m11;
            return Math.abs(perBlock) > 1.0E-9 ? (screenY - m31) / perBlock + anchorZ : anchorZ;
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

        if (element.kind() == RoadElement.Kind.MARKS) {
            // The track MTR's lines run along, drawn as polylines: its own pass, because what is under
            // it (the roads) and what is over it (the route, then the lines) are decided by where this
            // element sits in the map's own order. Not while one of this mod's panels is up, for the
            // reason the labels pass gives: this pass draws over the map, and a panel is not the map.
            net.minecraft.client.Minecraft minecraft = net.minecraft.client.Minecraft.getInstance();
            if (!(minecraft.screen instanceof TransitLineScreen)
                    && !(minecraft.screen instanceof RoadNameScreen)) {
                int markMargin = 64;
                Projection projection = new Projection(element.anchorX(), element.anchorZ(), p10,
                        m00, m.m11(), m.m30(), m.m31());
                drawMarks(pose, vc, markMargin, graphics.guiWidth() + markMargin,
                        graphics.guiHeight() + markMargin, Math.abs(info.scale), projection);
            }
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
                //
                // This is the last of the mod's own passes in a frame, which is why the report of what
                // the frame cost is written from here.
                MapPassReport.endOfFrame();
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
        //
        // Only where a joint is actually needed: the two ends of the polyline, and the vertices where
        // it genuinely bends. A disc at *every* vertex was ten quads for a joint the two strokes
        // already meet at, and an automatically read railway has a vertex every block or two -- so most
        // of the geometry the map emitted was circles nobody could see, drawn along straight runs at
        // eleven times the vertex cost of the road itself. The threshold is well below any bend a
        // player draws and well above the wobble of a sampled curve, so the corners that do leave a
        // notch all still get one.
        for (int i = 0; i < segment.vertexCount(); i++) {
            if (i > 0 && i < segment.vertexCount() - 1 && !bendsAt(segment, i)) {
                continue;
            }
            emitDisc(last, vc,
                    localX(segment.x(i), anchorX, fracX, p10),
                    localY(segment.z(i), anchorZ, fracY, p10),
                    halfWidth, r, g, b, a);
        }

        // A one-way road says so on the map. Emitted here, in the segment's own pass and right after the
        // road it belongs to, so the arrows travel with the stroke: drawn from anywhere else they would
        // be a second pass that could disagree with it about the road's colour, width or visibility.
        if (segment.direction().isOneWay() && Math.abs(info.scale) >= ONEWAY_ARROW_MIN_SCALE) {
            emitOneWayArrows(last, vc, segment, anchorX, anchorZ, fracX, fracY, p10, posePerPixel);
        }

        // Rail diagnostic: one of the auto-detected layer's segments was actually stroked, past the
        // level-of-detail filter, which is the difference between "never handed over" and "handed over
        // and dropped".
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
            // Which road each segment belongs to, and which piece of it carries the name, read once
            // for the whole pass: the same reading serves every name below and every frame until the
            // roads change. Asking for a chain per named road instead -- which is what this pass used
            // to do -- rebuilt an adjacency index of the whole network per name, per frame.
            RoadChains.Grouping grouping = RoadChains.cachedGrouping(network);
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
                // Culled before the name question, not after: a road off screen has no name to read,
                // and this pass runs on every frame the map draws.
                double[] mid = segment.midpoint();
                int x = (int) Math.round(projection.screenX(mid[0]));
                int y = (int) Math.round(projection.screenZ(mid[1]));
                if (x < -margin || x > viewRight || y < -margin || y > viewBottom) {
                    continue;
                }
                // One label per road. A road with bends is several segments all carrying the same
                // name, so without this the label would repeat at every corner.
                if (!grouping.carriesLabel(segment)) {
                    continue;
                }
                // A name longer than the road it belongs to would hang off both its ends and read as
                // a label for whatever is beside it, so it is dropped rather than written.
                if (font.width(name) * labelFactor
                        > chainLengthPx(network, grouping.chainOf(segment), projection)) {
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
                // The same rule as for a road, over the whole railway. Only the one segment per
                // railway that carries the label gets this far, so the layer's grouping -- which is
                // cached per rebuild -- is asked for here rather than by every rail segment: building
                // it costs the size of the layer, and the layer is every piece of track Create has
                // within its read radius.
                if (font.width(name) * labelFactor > chainLengthPx(layer,
                        RoadChains.cachedGrouping(layer).chainOf(segment), projection)) {
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
     *
     * <p>The run arrives as the grouping's own array rather than as a chain walked here: the lengths
     * are needed once per name drawn, and a walk costs the size of the whole network.
     */
    private static double chainLengthPx(RoadNetwork network, int[] chain,
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
    /**
     * How many bands of zoom one line's thinned geometry is kept for.
     *
     * <p>The zoom moves in steps and a band is a power of two pixels per block (see {@link PathRuns}),
     * so the band a view is in changes when the player zooms and not when they pan. A handful is enough
     * to hold every band a session passes through twice over; the oldest is displaced, and a band asked
     * for again costs one walk of the line's points.
     */
    private static final int BANDS = 6;
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
    private static List<LineShape> lineShapes = List.of();
    /** The line count the diagnostic last reported, so it speaks when that changes and not per frame. */
    private static int reportedLineCount = -1;
    /**
     * One line's shape, kept until that line's own stops, kind or track change.
     *
     * <p>Per line rather than for all of them, because the lines are no longer all the player's: the
     * ones read out of MTR arrive a window at a time and change as the player walks, and rebuilding
     * every line's shape because one imported line gained a stop would be a hitch in the middle of
     * walking -- on the render thread, which is the worst place for one.
     */
    private static final java.util.Map<String, LineShape> lineShapeCache =
            new java.util.HashMap<>();

    /**
     * One line's drawn shape: the stretches of its path that are known, each with the box it fits in,
     * thinned once per zoom band and kept for the frames that follow.
     *
     * <p>A line of several stretches is several polylines and not one. Joining them would draw a
     * straight line across every gap between them, and a gap is either track the reading does not have
     * or a pair of stops the router could not join -- see {@link TrackRuns}, which is where the
     * stretches come from and where the reasoning lives.
     *
     * <p>No straight hop between a line's own ends is drawn for a line with no stretch at all. It was,
     * on the argument that a line nobody can ride should be visible as a line rather than as nothing;
     * what that is on a map is a straight line across everything between two stations, belonging to no
     * path. Its stops are marked either way.
     *
     * <h2>Why the thinning is kept per band of zoom</h2>
     * A line read out of MTR is sampled every few blocks and is tens of thousands of points long, and
     * every frame draws it again at the zoom the player has chosen. Emitting a stroke per step of that
     * sampling is a cost that grows with the railway rather than with what is on the screen -- and at
     * any zoom a map is readable at, most of those steps are a fraction of a pixel, so the strokes are
     * paid for and not seen. So each stretch is thinned to the zoom (see {@link PathRuns}) and the
     * result is kept: the zoom moves in steps, and every frame between two of them asks for exactly the
     * geometry that was worked out the first time.
     */
    private static final class LineShape {

        private final List<PathRuns.Run> runs;
        private final Band[] bands = new Band[BANDS];
        private int nextBand;

        static final LineShape EMPTY = new LineShape(List.of());

        private LineShape(List<PathRuns.Run> runs) {
            this.runs = runs;
        }

        /** A shape over bare stretches of points, each wrapped with the box it fits in. */
        static LineShape of(List<List<double[]>> runs) {
            List<PathRuns.Run> wrapped = new java.util.ArrayList<>(runs.size());
            for (List<double[]> run : runs) {
                if (run.size() >= 2) {
                    wrapped.add(PathRuns.of(run));
                }
            }
            return wrapped.isEmpty() ? EMPTY : new LineShape(List.copyOf(wrapped));
        }

        /** Every stretch as the reading gave it, before any thinning: what the diagnostics measure. */
        List<PathRuns.Run> runs() {
            return runs;
        }

        /**
         * The stretches as this zoom draws them.
         *
         * @param pixelsPerBlock how many screen pixels one block covers, which is the zoom the thinning
         *                       has to be fine enough for
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

    /** One band of zoom, with the stretches thinned for it. */
    private record Band(int band, List<PathRuns.Run> runs) {
    }

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

        // One workspace per network rather than per line: a workspace copies the network before the
        // first query through it, and every line that routes on the same roads --
        // the same kind, and with or without the imported rails -- reuses one.
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
        reportLongSteps(lines, shapes);
    }

    /**
     * How long a step between two neighbouring points of a drawn line has to be before it is worth
     * saying out loud, in blocks.
     *
     * <p>A railway's own geometry is a run of points a few blocks apart: a rail is sampled along its
     * curve. A step of this length inside a stretch is therefore either a rail that really is that
     * long and straight, or a straight line drawn between two places the geometry does not join --
     * and the two look identical on the map. This is the number that tells them apart without a guess,
     * and the coordinates say which stretch of which line to look at.
     */
    private static final double LONG_STEP_BLOCKS = 64.0;

    /** How many lines one rebuild may name, so a map of broken geometry cannot fill the log. */
    private static final int MAX_REPORTED_LONG_STEPS = 12;

    /**
     * Names the lines whose drawn shape takes a long step between two of its own points.
     *
     * <p>Written when the shapes are rebuilt -- on a change of the lines, not per frame -- and only
     * for the lines that have such a step, so a healthy map says nothing at all. A line whose stretch
     * is two points a kilometre apart is a straight line drawn across everything between two stops; a
     * line whose stretch is a hundred points with a twenty-block longest step is a railway. The
     * difference is not visible on the map, which is exactly why it is worth a log line.
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
     * What makes a line's shape its own: which line, of which kind, calling where, over which roads,
     * and along which track.
     *
     * <h2>Why the track is part of it</h2>
     * A windowed reading does not hand a line's track over in one piece: it marks the stretch near the
     * player, and every second the window slides it adds another piece to the same line. The stops do
     * not change while that happens, so a key built from the stops alone goes on saying "the same
     * line" while the track under it fills in -- and the shape worked out from the first window's
     * worth of rails is drawn for the rest of the session, straight hops and all, over track that has
     * since arrived. The track's own reading is therefore part of the key: its segment count and its
     * revision, which the network moves on every time a piece is added to it.
     */
    private static String shapeKey(TransitLine line) {
        StringBuilder key = new StringBuilder();
        key.append(line.id()).append(line.kind().name());
        // Whether the line rides its own marks is part of the shape: turning the imported rails off has
        // to redraw the line along the roads it will now be ridden over, not leave the old drawing up.
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
     * <p>An imported line whose track is not known is therefore drawn as nothing at all: its stations are
     * still marked, and a straight hop from one to the next would be a claim about ground the line may not
     * cross. It is the same answer {@link TrackRuns} gives to a gap inside a track that is partly known,
     * taken to its end -- the map shows the track it has and invents none. Such a line is not lost: a
     * window that brings its rails is a window that draws it, and until then its stops are on the map.
     *
     * <p>A line of the player's own has no track of its own, so what is drawn is the route the mod
     * plans between each neighbouring pair of its stops -- and a pair that cannot be planned is left as
     * a gap rather than drawn as a straight hop, which is the same rule an imported line follows and
     * the same reason. It used to be drawn as a hop, so that a mis-typed line was visible as a line
     * rather than as a gap; on a world with a city's worth of lines that produced a web of straight
     * lines across the whole map, each one claiming ground the line does not run over, and the real
     * lines lost in it. A gap says what is true: the path is not known here. Every stop of every line
     * is still marked, below, so a line whose path cannot be worked out at all is not a line nobody can
     * find.
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

    private List<LineLabel> drawTransitLines(PoseStack pose, VertexConsumer vc, int margin,
                                             int viewRight, int viewBottom, double scale,
                                             java.util.List<TransitInterchanges.Interchange>
                                                     interchanges,
                                             Projection projection) {
        List<LineLabel> labels = new java.util.ArrayList<>();
        // The player's lines and the ones read out of MTR: a line the mod will plan a journey over is
        // a line whose route the map should show, whichever of the two it came from.
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

        // The shapes the lines are drawn from, as they stand: what each line's own track is, stretched
        // into the polylines the map draws. Brought up to date here rather than kept by the line list,
        // because the track under a line arrives a window at a time and a shape built from the first
        // window's worth of it would be drawn for the rest of the session.
        shapeLines(lines);

        // The zoom, and the piece of the world the screen is showing: the scale a stretch is thinned
        // for, and the box it has to overlap to be drawn at all. Both are read from this frame's own
        // projection, so the culling and the drawing cannot disagree about where the map is -- which is
        // the same reason the coordinates below come from it rather than from the cached view state.
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

        // The lines. Never shed by zoom: a line is what this map is for, and it is the one thing on it
        // the roads do not already imply -- so the thinning above is what keeps a whole railway's worth
        // of them affordable, and it is the only thing that does.
        int stroked = 0;
        if (MapFilter.showsLines()) {
            for (int index = 0; index < lines.size(); index++) {
                TransitLine line = lines.get(index);
                // Each stretch on its own: the gaps between them are track this client has not been
                // sent, or a pair of stops the router could not join, and a line drawn across one is a
                // line drawn over ground the line does not cover. A line with no stretch at all is
                // therefore drawn as nothing rather than as a straight line between its ends -- its
                // stops are marked below, which is what makes it findable.
                stroked += drawRuns(screenPose, vc, shapeAt(index).runsAt(pixelsPerBlock), projection,
                        margin, viewRight, viewBottom, minWorldX, minWorldZ, maxWorldX, maxWorldZ,
                        lineStrokePx(scale), lineColour(line), culled);
            }
        }
        MapPassReport.lines(System.nanoTime() - startedAt, lines.size(), stroked, sourcePoints, culled[0]);
        // No name is drawn along a line. It was tried at the middle stop and at the middle of the path
        // and was wrong in both places -- on a line that curves or loops, no single point along it is
        // the middle a reader means, and a name written over the stroke or beside a station marker is
        // worse than no name. Read off the line editor instead, which lists a line's stops in order and
        // has room to say what it is called.
        if (!MapFilter.showsLines()) {
            // The lines, their stops and the interchanges two of them meet at are what this switch
            // turns off. The railway they run on is not theirs -- it answers to the switches beside
            // roads and railways -- so it stays, and so does everything below this.
            pose.popPose();
            return labels;
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

    /** One line's shape, or nothing when the shapes and the lines have somehow come apart. */
    private static LineShape shapeAt(int index) {
        return index < lineShapes.size() ? lineShapes.get(index) : LineShape.EMPTY;
    }

    /**
     * Brings the line shapes up to date, and leaves them in step with the lines they were built for.
     *
     * <p>Asked from two passes now -- the marks and the lines -- so it is one call with the check that
     * the two lists agree, rather than the same guard written twice. The check is not paranoia: the
     * failure it would cause is silent and total, since indexing past the end throws, the whole overlay
     * is lost for that frame, and what the player sees is every line disappearing at once, which reads
     * as the mod having forgotten them. Rebuilding is cheap next to that.
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
     *
     * <h2>Why this is not the map's element list</h2>
     * It was: one map element per rail of every line whose marks are switched on, which on a whole
     * railway is tens of thousands of elements, each drawn as a quad per vertex of its own sampling and
     * most of them a fraction of a pixel at any zoom a map is readable at. A cost that grows with the
     * railway while what is on the screen does not, which is what made a map of a whole railway slow to
     * draw. Here they are instead drawn from the very polylines the lines themselves are drawn from --
     * a line's marks <em>are</em> its own track, cut by the same code -- so the drawing is thinned,
     * culled by the box each stretch fits in, and paid for once per line rather than once per rail.
     *
     * <p>A line's own switch decides whether its track is here at all, and the class's switch (and the
     * zoom) decides whether it is drawn: a mark is rail for a train and water for a boat, so the two
     * questions are asked of the line's kind rather than of one class for all of them.
     */
    private void drawMarks(PoseStack pose, VertexConsumer vc, int margin, int viewRight, int viewBottom,
                           double scale, Projection projection) {
        // A line whose marks are off is not drawn here, and one whose kind has no class at all is not a
        // line this mod has -- see buildLines, which refuses those. Nothing to ask of the switch above
        // that: marksEnabled is the one place the answer lives.
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
        pose.last().pose().identity();
        pose.last().normal().identity();
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
                    strokeHalfWidthPx(kind, scale), kind.color(), culled);
        }
        pose.popPose();
        MapPassReport.marks(System.nanoTime() - startedAt, stroked, sourcePoints, culled[0]);
    }

    /**
     * Draws the stretches of one shape that the screen can see, as strokes.
     *
     * <p>The culling is by the box each stretch fits in, which is why it is worth having: a whole
     * railway's worth of lines is mostly off the screen, and a stretch nobody can see costs nothing to
     * draw but still costs a walk of its points to find that out. Each stretch that survives is drawn
     * in full, because a stretch that crosses the screen has both ends outside it and is exactly the
     * one a per-point test would throw away.
     *
     * @param halfWidth half the stroke's width in screen pixels -- the marks and the lines themselves
     *                  are the same drawing at two widths
     * @param culled    a one-element counter, added to for every stretch the screen does not touch, so
     *                  that the pass can report what the culling saved
     * @return how many strokes were emitted
     */
    private static int drawRuns(PoseStack.Pose screenPose, VertexConsumer vc, List<PathRuns.Run> runs,
                                Projection projection, int margin, int viewRight, int viewBottom,
                                double minWorldX, double minWorldZ, double maxWorldX, double maxWorldZ,
                                double halfWidth, int argb, int[] culled) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        int a = (argb >>> 24) & 0xFF;
        if (a == 0) {
            a = 0xC0;
        }
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
        HowToGo.diagnostic("[HowToGo] drawing {} transit line(s), {} of them read out of MTR, "
                + "{} stops between them; {} line(s) and {} station(s) remembered", lines.size(),
                imported, stops, MtrTransit.rememberedLines(), MtrTransit.rememberedStations());
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
            String headline = Component.translatable(
                    segmentSelected ? "hud.howtogo.selected" : "hud.howtogo.drawing",
                    roadClass.name()).getString();
            if (segmentSelected) {
                // The one-way state of the road the player has selected, spelled out: the arrows on the
                // map say which way it runs, and this says what pressing the key again will do to it.
                RoadDirection direction = RoadEditSession.editor()
                        .chainDirection(RoadEditSession.editor().selectedSegmentId());
                headline = headline + " · " + Component.translatable(
                        "hud.howtogo.direction." + direction.id()).getString();
                // Then where it is and how long it is, in that order: a storey is the thing a flat map
                // cannot show, so it is the first question a selected road raises, and the length is the
                // number a player reads once the road is the right one. Both are about the whole road
                // rather than the piece under the cursor -- see RoadEditor.chainLength -- because that is
                // what selecting a road selects.
                headline = headline + " · " + Component.translatable("hud.howtogo.layer",
                        Navigation.roadLayerLabel(RoadEditSession.selectedLayer())).getString();
                headline = headline + " · " + Component.translatable("hud.howtogo.selected_length",
                        (int) Math.round(RoadEditSession.selectedLength())).getString();
            }
            leftLines.add(headline);
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
        // The storey being drawn on, always shown rather than only when it is not the surface: it is
        // invisible on a map that cannot show height, so a player who has moved one road and not the
        // next has nothing else to tell them which is which.
        stats = stats + "   " + Component.translatable("hud.howtogo.layer",
                Navigation.roadLayerLabel(RoadEditSession.activeLayer())).getString();
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
     * -- four navigation lines, the caption and the totals -- with the thirteen-line control list stepped
     * above it, whose panel top still lands around 10 px of a scaled space Minecraft never makes shorter
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

    // ------------------------------------------------------------ one-way marks

    /**
     * Screen distance between the direction arrows along a one-way road.
     *
     * <p>A screen distance rather than a world one: the arrows are a reading of the map, and how many of
     * them fit along a road is a question about the screen. Zoomed out, a fixed world spacing would put
     * them all on top of each other; zoomed in, it would leave a street with one arrow on it that points
     * off the edge of the screen.
     */
    private static final double ONEWAY_ARROW_SPACING_PX = 26.0;
    /** Length of one arrowhead, which is also the size the dark rim around it is grown by. */
    private static final double ONEWAY_ARROW_PX = 7.5;
    /** The most arrowheads one segment draws, so a long road cannot fill the map with them. */
    private static final int ONEWAY_ARROW_LIMIT = 40;
    /**
     * Map scale below which a one-way road is drawn without its arrows.
     *
     * <p>The same idea as the label gate: at a scale where a whole region is on screen, a road is part of
     * a picture rather than something being read, and forty arrowheads per street would turn the picture
     * into a texture. Past the gate the road is still drawn exactly as it is -- only the marks that say
     * which way it runs are left off.
     */
    private static final double ONEWAY_ARROW_MIN_SCALE = 0.3;
    /** The arrowhead itself: white, so it stands off all six road colours. */
    private static final int COLOR_ARROW = 0xFFF4F8FF;
    /** The rim under it, which is what makes it visible on the ice road as well as the highway. */
    private static final int COLOR_ARROW_RIM = 0xB0101418;

    /**
     * Draws arrowheads along a one-way segment, pointing the way travel is allowed.
     *
     * <p>The polyline is walked in the allowed direction -- forwards for a segment that runs from its
     * from-node to its to-node, backwards for the other -- and an arrowhead is placed at every spacing,
     * each one turned to the local direction of the road so it follows a bend instead of pointing at the
     * first arrow's angle.
     *
     * <p>Everything here is in the segment's own local (map) units, the same as the stroke, so the arrows
     * pan and zoom with the road they belong to.
     */
    private static void emitOneWayArrows(PoseStack.Pose pose, VertexConsumer vc, RoadSegment segment,
                                         double anchorX, double anchorZ, double fracX, double fracY,
                                         double p10, double posePerPixel) {
        int count = segment.vertexCount();
        if (count < 2) {
            return;
        }
        double spacing = ONEWAY_ARROW_SPACING_PX * posePerPixel;
        double size = ONEWAY_ARROW_PX * posePerPixel;
        if (!(spacing > 0.0) || !(size > 0.0)) {
            return;
        }
        boolean backward = segment.direction() == RoadDirection.BACKWARD;
        // The first arrow sits a little way in from the end rather than in the very first pixel of the
        // road, where it would overlap the junction's round cap and the arrows of the road meeting it.
        double nextAt = spacing * 0.75;
        double travelled = 0.0;
        int drawn = 0;

        for (int step = 1; step < count && drawn < ONEWAY_ARROW_LIMIT; step++) {
            int a = backward ? count - step : step - 1;
            int b = backward ? count - step - 1 : step;
            double x1 = localX(segment.x(a), anchorX, fracX, p10);
            double y1 = localY(segment.z(a), anchorZ, fracY, p10);
            double x2 = localX(segment.x(b), anchorX, fracX, p10);
            double y2 = localY(segment.z(b), anchorZ, fracY, p10);
            double length = Math.hypot(x2 - x1, y2 - y1);
            if (length < 1.0E-6) {
                continue;
            }
            double ux = (x2 - x1) / length;
            double uy = (y2 - y1) / length;
            while (nextAt <= travelled + length && drawn < ONEWAY_ARROW_LIMIT) {
                double at = nextAt - travelled;
                emitArrowHead(pose, vc, x1 + ux * at, y1 + uy * at, ux, uy, size);
                drawn++;
                nextAt += spacing;
            }
            travelled += length;
        }
    }

    /** One arrowhead: a dark rim with a light head on top of it, pointing along (ux, uy). */
    private static void emitArrowHead(PoseStack.Pose pose, VertexConsumer vc, double cx, double cy,
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

        HudDraw.emitTriangle(pose, vc,
                tipX + ux * rim, tipY + uy * rim,
                backX + wingX - nx * rim, backY + wingY - ny * rim,
                backX - wingX + nx * rim, backY - wingY + ny * rim, COLOR_ARROW_RIM);
        HudDraw.emitTriangle(pose, vc,
                tipX, tipY,
                backX + wingX, backY + wingY,
                backX - wingX, backY - wingY, COLOR_ARROW);
    }

    /**
     * Whether the polyline bends at this vertex by enough to leave a visible notch.
     *
     * <p>The angle between the vertex's two spans, with a straight-through vertex -- the common case on
     * any sampled line -- answering no. A vertex whose neighbours are on top of it answers yes, because
     * there the two strokes meet at no angle at all and the cap is the only thing closing the joint.
     */
    private static boolean bendsAt(RoadSegment segment, int index) {
        double inX = segment.x(index) - segment.x(index - 1);
        double inZ = segment.z(index) - segment.z(index - 1);
        double outX = segment.x(index + 1) - segment.x(index);
        double outZ = segment.z(index + 1) - segment.z(index);
        if (Math.hypot(inX, inZ) < 1.0E-6 || Math.hypot(outX, outZ) < 1.0E-6) {
            return true;
        }
        double cross = inX * outZ - inZ * outX;
        double dot = inX * outX + inZ * outZ;
        return Math.abs(Math.toDegrees(Math.atan2(cross, dot))) >= JOINT_MIN_DEGREES;
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
