package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.road.RoadChains;
import bili.dongsz.howtogo.road.RoadClass;
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

    /** Minimum stroke width in screen pixels, so zoomed-out roads stay visible. */
    private static final double MIN_STROKE_PX = 1.5;

    /**
     * Below this map scale (pixels per block) minor roads are dropped, the way real maps shed
     * detail as you zoom out. Set to 0 to always draw everything.
     */
    private static final double MIN_SCALE_FOR_PATHS = 0.35;

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

    /** Names are only drawn once the map is zoomed in enough for them not to collide. */
    private static final double LABEL_MIN_SCALE = 0.75;

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
            renderLabels(graphics, pose, info, vc);
            return true;
        }

        RoadSegment segment = element.segment();
        if (segment == null || segment.vertexCount() < 2) {
            return false;
        }

        // Level of detail: shed minor roads when zoomed out.
        if (segment.roadClass() == RoadClass.PATH && Math.abs(info.scale) < MIN_SCALE_FOR_PATHS) {
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
                              VertexConsumer vc) {
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
        drawPlaceMarkers(pose.last(), vc, margin, viewRight, viewBottom);

        // Names need a zoom at which they can be read at all; at lower zoom they overlap into a
        // smear. Only the text is gated, never the markers above.
        if (Math.abs(info.scale) >= LABEL_MIN_SCALE) {
            for (RoadSegment segment : network.segments()) {
                String name = segment.name();
                if (name == null) {
                    // Only named roads are labelled. Most of a fresh network is unnamed, and a
                    // placeholder on every one of them buries the names that do exist.
                    continue;
                }
                // One label per road. A road with bends is several segments all carrying the same
                // name, so without this the label would repeat at every corner.
                List<Integer> chain = RoadChains.chainContaining(network, segment.id());
                if (RoadChains.middleSegment(chain) != segment.id()) {
                    continue;
                }
                double[] mid = segment.midpoint();
                int x = (int) Math.round(MapViewState.toScreenX(mid[0]));
                int y = (int) Math.round(MapViewState.toScreenZ(mid[1]));
                if (x < -margin || x > viewRight || y < -margin || y > viewBottom) {
                    continue;
                }
                // A name longer than the road it belongs to would hang off both its ends and read as
                // a label for whatever is beside it, so it is dropped rather than written.
                if (font.width(name) > chainLengthPx(network, chain)) {
                    continue;
                }
                drawLineLabel(graphics, pose, font, name, segment, COLOR_LABEL_ROAD);
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
                if (font.width(name) > chainLengthPx(layer,
                        RoadChains.chainContaining(layer, segment.id()))) {
                    continue;
                }
                double[] mid = segment.midpoint();
                int x = (int) Math.round(MapViewState.toScreenX(mid[0]));
                int y = (int) Math.round(MapViewState.toScreenZ(mid[1]));
                if (x < -margin || x > viewRight || y < -margin || y > viewBottom) {
                    continue;
                }
                drawLineLabel(graphics, pose, font, name, segment, COLOR_LABEL_RAIL);
            }

            // Place names, under their markers. A station's name comes from
            // CreateStationSource.nameOf, the same call the picker lists it under, so the label on
            // the map and the entry in the list cannot become two names for one station.
            for (RoadNode node : network.nodes()) {
                if (node.type() != RoadNode.Type.POI || node.name() == null) {
                    continue;
                }
                int x = (int) Math.round(MapViewState.toScreenX(node.x()));
                int y = (int) Math.round(MapViewState.toScreenZ(node.z()));
                if (x < -margin || x > viewRight || y < -margin || y > viewBottom) {
                    continue;
                }
                graphics.drawString(font, node.name(), x - font.width(node.name()) / 2, y + 8,
                        HudDraw.COLOR_PLACE, true);
            }

            for (RailTrackStore.Station station : RailTrackStore.stations()) {
                String name = CreateStationSource.nameOf(station);
                int x = (int) Math.round(MapViewState.toScreenX(station.x()));
                int y = (int) Math.round(MapViewState.toScreenZ(station.z()));
                if (x < -margin || x > viewRight || y < -margin || y > viewBottom) {
                    continue;
                }
                graphics.drawString(font, name, x - font.width(name) / 2, y + 8,
                        HudDraw.COLOR_PLACE, true);
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
    private static double chainLengthPx(RoadNetwork network, List<Integer> chain) {
        double total = 0;
        for (int id : chain) {
            RoadSegment member = network.segment(id);
            if (member == null) {
                continue;
            }
            for (int i = 1; i < member.vertexCount(); i++) {
                total += Math.hypot(
                        MapViewState.toScreenX(member.x(i)) - MapViewState.toScreenX(member.x(i - 1)),
                        MapViewState.toScreenZ(member.z(i)) - MapViewState.toScreenZ(member.z(i - 1)));
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
     * the half-width offset puts its middle on the line. The perpendicular offset stays, so the name
     * still sits beside the line rather than on top of it.
     */
    private void drawLineLabel(GuiGraphics graphics, PoseStack pose, Font font, String name,
                               RoadSegment segment, int color) {
        double[] mid = segment.midpoint();
        double x = MapViewState.toScreenX(mid[0]);
        double y = MapViewState.toScreenZ(mid[1]);

        pose.pushPose();
        pose.translate(x, y, 0.0);
        pose.mulPose(Axis.ZP.rotation((float) screenAngle(segment)));
        graphics.drawString(font, name, -font.width(name) / 2, -4, color, true);
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
    private static double screenAngle(RoadSegment segment) {
        int last = segment.vertexCount() - 1;
        double dx = MapViewState.toScreenX(segment.x(last)) - MapViewState.toScreenX(segment.x(0));
        double dy = MapViewState.toScreenZ(segment.z(last)) - MapViewState.toScreenZ(segment.z(0));
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
    private void drawPlaceMarkers(PoseStack.Pose screenPose, VertexConsumer vc,
                                  int margin, int viewRight, int viewBottom) {
        double half = HudDraw.PLACE_MARKER_PX * 0.5;
        for (Destination place : Destinations.places()) {
            double x = MapViewState.toScreenX(place.x());
            double y = MapViewState.toScreenZ(place.z());
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
