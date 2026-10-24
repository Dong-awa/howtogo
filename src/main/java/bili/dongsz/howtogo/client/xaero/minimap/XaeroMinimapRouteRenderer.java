package bili.dongsz.howtogo.client.xaero.minimap;

import bili.dongsz.howtogo.RoadConfig;
import bili.dongsz.howtogo.client.HudDraw;
import bili.dongsz.howtogo.client.MapDisplayMode;
import bili.dongsz.howtogo.client.RoadRenderContext;
import bili.dongsz.howtogo.client.Screens;
import bili.dongsz.howtogo.road.RoadNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.MultiBufferSource;
import org.joml.Matrix4f;
import xaero.common.graphics.renderer.multitexture.MultiTextureRenderTypeRendererProvider;
import xaero.hud.minimap.element.render.MinimapElementRenderInfo;
import xaero.hud.minimap.element.render.MinimapElementRenderLocation;
import xaero.hud.minimap.element.render.MinimapElementRenderer;

import java.util.List;

/**
 * Draws the navigation overlay onto Xaero's Minimap: the road network, the route, and the trip's
 * markers.
 *
 * <h2>What the pipeline gives, and what it does not</h2>
 * Xaero calls this renderer once per element, with the element already projected: the pose has been
 * pushed and translated so that pose-local {@code (0, 0)} is the element's screen position. The
 * handler adds no scale and no rotation of its own -- measured in its
 * {@code transformAndRenderForRenderer}: a {@code pushPose}, the {@code translatePosition} call and
 * a {@code popPose} around the renderer -- but it hands over no zoom, no rotation and no minimap
 * origin either, so a renderer cannot place a point that is not its own anchor.
 *
 * <p>That is why the frame's transform is measured rather than read: the provider emits probe
 * elements before the layers, the renderer records what Xaero's projection did to each of them, and
 * {@link XaeroMinimapProjection} turns the samples into the world-to-local matrix that the polyline
 * emitters take. This class's job is then simple: feed the transform and the layer to
 * {@link HudDraw}, and clip everything to the minimap rectangle the edge probes measured.
 *
 * <h2>Element-local, not element-relative</h2>
 * Every layer element is anchored at the frame anchor (the player's position at the start of the
 * frame), so the local coordinate system the emitters are given is "blocks from the anchor", with
 * one matrix for all layers. A marker is the exception: it is anchored at its own world position,
 * which is the only place a fixed-size dot needs to be.
 *
 * <h2>The screen-pixel sizes</h2>
 * The pose's own local-to-screen scale {@code s} is measured per frame; every size below that the
 * panel would call a pixel is divided by it before being handed to an emitter. The mapping panel
 * sizes are kept exactly -- road thickness 1.0 px, route thickness 2.6 px, the panel's marker radii
 * -- so the minimap and the panel draw the same map at the same weights. The road alpha is the
 * panel's {@code 0x9A}, the route and markers are solid, and every colour comes from
 * {@link bili.dongsz.howtogo.client.UiTheme} or from the road data itself.
 */
public final class XaeroMinimapRouteRenderer
        extends MinimapElementRenderer<XaeroMinimapRouteElement, RoadRenderContext> {

    /**
     * Where this renderer sits among the minimap's element renderers.
     *
     * <p>Xaero sorts renderers by this value and gives the elements of later renderers a slightly
     * larger depth, so a larger order draws on top. Xaero's own waypoint renderer uses 100; the
     * roads and the route are above it deliberately -- a waypoint that happens to sit on the road
     * the player is following must not hide the line they are following, and the destination marker
     * must stay visible over both.
     */
    private static final int ORDER = 150;

    /** Half the width of a road stroke, in GUI pixels; the panel draws 1.0 px wide. */
    private static final double ROAD_HALF_WIDTH_PX = 0.5;

    /** Half the width of the route stroke, in GUI pixels; the panel draws 2.6 px wide. */
    private static final double ROUTE_HALF_WIDTH_PX = 1.3;

    /** The alpha the navigation panel passes for the road network. */
    private static final int ROAD_ALPHA = 0x9A;

    /** The route is opaque so the two halves read as one line where they meet. */
    private static final int ROUTE_ALPHA = 0xFF;

    /** Markers are opaque: there is at most one of each on the map at a time. */
    private static final int MARKER_ALPHA = 0xFF;

    /** The origin marker's radius in GUI pixels. */
    private static final double ORIGIN_RADIUS_PX = 2.0;

    /** The destination marker's radius in GUI pixels. */
    private static final double DESTINATION_RADIUS_PX = 3.2;

    /**
     * How far around the anchor the RoadWeaver layer is asked for, in blocks.
     *
     * <p>That layer holds a whole region's roads read out of another mod, so it is the one layer
     * that has to be culled in world space before any projection: the minimap shows a few dozen
     * blocks across at most, and a few hundred of margin covers the map's corners at every zoom and
     * the frame's one frame of latency. The other layers are small enough to be projected and
     * clipped by the emitters, exactly as the navigation panel does.
     */
    private static final double ROADWEAVER_BOX_RADIUS = 512.0;

    public XaeroMinimapRouteRenderer(XaeroMinimapRouteReader reader,
                                     XaeroMinimapRouteProvider provider,
                                     RoadRenderContext context) {
        super(reader, provider, context);
    }

    /**
     * Whether this renderer draws in this pass at all.
     *
     * <p>Checked once per frame, before the provider is even asked to build a buffer. Note what is
     * not in the condition: a destination. The road network belongs to the map, not to a trip, and
     * a player who has never navigated still expects their roads on their minimap; the route and
     * the markers are simply absent from the element list when there is no trip. In either of the
     * two ways the overlay can be off -- the other display mode, or a screen open -- the cost is
     * one config read and nothing else.
     */
    @Override
    public boolean shouldRender(MinimapElementRenderLocation location) {
        return location == MinimapElementRenderLocation.OVER_MINIMAP && drawing();
    }

    /**
     * Consumes one element: a probe is recorded, a layer is drawn, a marker is a dot.
     *
     * <p>The probe kinds come first in the list (see {@link XaeroMinimapRouteProvider}) and are the
     * only place the frame's projection is read. They return false because nothing was drawn;
     * Xaero uses the return value to decide whether an element consumes a depth slot, so claiming
     * one without drawing would make later elements sit a fraction of a unit in front for no
     * reason.
     *
     * <p>The second boolean is Xaero's "was this position clamped to the edge of the minimap". It
     * means two different things here: for a probe it marks the reading as unusable, and for a
     * marker it is the feature -- a destination off the map is drawn at the rim, which is exactly
     * the direction information the player wants.
     */
    @Override
    public boolean renderElement(XaeroMinimapRouteElement element, boolean highlighted,
                                 boolean clamped, double depth, float optionalScale,
                                 double translateX, double translateY,
                                 MinimapElementRenderInfo info, GuiGraphics graphics,
                                 MultiBufferSource.BufferSource bufferSource) {
        if (!drawing()) {
            // shouldRender normally keeps this from being reached; checked again so the conditions
            // that decide whether the overlay draws live in one place rather than in a contract.
            return false;
        }
        switch (element.kind()) {
            case CAL_CENTRE -> {
                if (clamped) {
                    return false;
                }
                Matrix4f pose = pose(graphics);
                XaeroMinimapProjection.readCentre(pose,
                        screenX(pose, translateX, translateY),
                        screenY(pose, translateX, translateY));
                return false;
            }
            case CAL_X, CAL_Z -> {
                Matrix4f pose = pose(graphics);
                XaeroMinimapProjection.readAxisProbe(
                        element.kind() == XaeroMinimapRouteElement.Kind.CAL_X,
                        element.probe(),
                        screenX(pose, translateX, translateY),
                        screenY(pose, translateX, translateY),
                        clamped);
                return false;
            }
            case EDGE_XP, EDGE_XN, EDGE_ZP, EDGE_ZN -> {
                Matrix4f pose = pose(graphics);
                XaeroMinimapProjection.readEdge(
                        screenX(pose, translateX, translateY),
                        screenY(pose, translateX, translateY),
                        clamped);
                return false;
            }
            default -> {
                // A layer or a marker; handled below once the frame's transform is known.
            }
        }

        if (!XaeroMinimapProjection.ready()) {
            // Nothing has been measured that can place a world point this frame. Drawing nothing
            // is invisible; drawing with a guessed transform would paint over the whole HUD.
            return false;
        }
        if (clamped && isLayer(element.kind())) {
            // A layer is anchored at the player, which is the centre of the map and can never be
            // clamped. If one is, the frame is not the one the projection describes; drawing into
            // it would put the network somewhere arbitrary, so it is skipped instead.
            return false;
        }
        return switch (element.kind()) {
            case ROADS, RAILS, MTR_LAYER -> drawNetwork(element.network(), graphics);
            case ROADWEAVER -> drawNetworkInBox(element, graphics);
            case ROUTE_DONE, ROUTE_AHEAD -> drawRoute(element, graphics);
            case DESTINATION -> drawMarker(DESTINATION_RADIUS_PX, element.argb(), graphics);
            case ORIGIN -> clamped ? false : drawMarker(ORIGIN_RADIUS_PX, element.argb(), graphics);
            default -> false;
        };
    }

    /**
     * Draws one road-network layer with the measured transform.
     *
     * <p>The emitters do the clipping per segment against the minimap rectangle, so the network is
     * handed over whole -- exactly as the navigation panel hands it over for its own square -- and
     * the chains are stroked and joined inside {@code HudDraw}. A layer whose network is null or
     * empty is skipped before the drawing block, so an install with no rails pays nothing for the
     * rail layer.
     */
    private static boolean drawNetwork(RoadNetwork network, GuiGraphics graphics) {
        if (network == null || network.segmentCount() == 0) {
            return false;
        }
        HudDraw.Drawing drawing = HudDraw.beginBlock(graphics);
        HudDraw.emitNetwork(drawing.sink, network,
                XaeroMinimapProjection.m00(), XaeroMinimapProjection.m01(),
                XaeroMinimapProjection.m02(), XaeroMinimapProjection.m10(),
                XaeroMinimapProjection.m11(), XaeroMinimapProjection.m12(),
                ROAD_HALF_WIDTH_PX * XaeroMinimapProjection.localPerScreenPx(), ROAD_ALPHA,
                XaeroMinimapProjection.localClipMinX(), XaeroMinimapProjection.localClipMinY(),
                XaeroMinimapProjection.localClipMaxX(), XaeroMinimapProjection.localClipMaxY());
        HudDraw.endBlock(graphics, drawing);
        return true;
    }

    /**
     * Draws the RoadWeaver layer, which needs its world-space cull box first.
     *
     * <p>The box is around the frame anchor, in the same local coordinate space as everything else:
     * only chains whose world bounding box comes near the map are handed to the projection, so a
     * whole region's roads do not have to be walked every frame.
     */
    private static boolean drawNetworkInBox(XaeroMinimapRouteElement element, GuiGraphics graphics) {
        RoadNetwork network = element.network();
        if (network == null || network.segmentCount() == 0) {
            return false;
        }
        double anchorX = element.x();
        double anchorZ = element.z();
        HudDraw.Drawing drawing = HudDraw.beginBlock(graphics);
        HudDraw.emitNetworkInBox(drawing.sink, network,
                XaeroMinimapProjection.m00(), XaeroMinimapProjection.m01(),
                XaeroMinimapProjection.m02(), XaeroMinimapProjection.m10(),
                XaeroMinimapProjection.m11(), XaeroMinimapProjection.m12(),
                ROAD_HALF_WIDTH_PX * XaeroMinimapProjection.localPerScreenPx(), ROAD_ALPHA,
                XaeroMinimapProjection.localClipMinX(), XaeroMinimapProjection.localClipMinY(),
                XaeroMinimapProjection.localClipMaxX(), XaeroMinimapProjection.localClipMaxY(),
                anchorX - ROADWEAVER_BOX_RADIUS, anchorZ - ROADWEAVER_BOX_RADIUS,
                anchorX + ROADWEAVER_BOX_RADIUS, anchorZ + ROADWEAVER_BOX_RADIUS);
        HudDraw.endBlock(graphics, drawing);
        return true;
    }

    /**
     * Draws one half of the route.
     *
     * <p>One drawing block per half, with the stroke's joins handled by the same path pass the
     * panel uses, so the two halves meet at the split point without a notch. The clip rectangle is
     * the minimap's, which matters more for the route than for anything else: a route is usually
     * longer than the map is wide.
     */
    private static boolean drawRoute(XaeroMinimapRouteElement element, GuiGraphics graphics) {
        List<double[]> points = element.routePoints();
        if (points == null || points.size() < 2) {
            return false;
        }
        HudDraw.Drawing drawing = HudDraw.beginBlock(graphics);
        HudDraw.emitPath(drawing.sink, points,
                XaeroMinimapProjection.m00(), XaeroMinimapProjection.m01(),
                XaeroMinimapProjection.m02(), XaeroMinimapProjection.m10(),
                XaeroMinimapProjection.m11(), XaeroMinimapProjection.m12(),
                ROUTE_HALF_WIDTH_PX * XaeroMinimapProjection.localPerScreenPx(),
                element.argb(), ROUTE_ALPHA,
                XaeroMinimapProjection.localClipMinX(), XaeroMinimapProjection.localClipMinY(),
                XaeroMinimapProjection.localClipMaxX(), XaeroMinimapProjection.localClipMaxY());
        HudDraw.endBlock(graphics, drawing);
        return true;
    }

    /**
     * Draws a marker as one disc at the element's own origin.
     *
     * <p>Xaero has already placed the element, so there is nothing to project: the disc's centre is
     * {@code (0, 0)} and only its size needs converting, because the pose's local units are not
     * pixels. {@code radiusPx} is the size the navigation panel uses for the same marker, so a
     * destination is the same dot on both maps.
     */
    private static boolean drawMarker(double radiusPx, int argb, GuiGraphics graphics) {
        HudDraw.Drawing drawing = HudDraw.beginBlock(graphics);
        HudDraw.emitDisc(drawing.sink, 0, 0,
                radiusPx * XaeroMinimapProjection.localPerScreenPx(), argb, MARKER_ALPHA);
        HudDraw.endBlock(graphics, drawing);
        return true;
    }

    /**
     * The pose matrix at the moment an element is rendered.
     *
     * <p>Xaero has pushed this element's translation before calling the renderer, so the matrix's
     * translation is the element's position in GUI pixels and its linear part is the parent
     * transform. That is the whole basis of the measured projection; see
     * {@link XaeroMinimapProjection}.
     *
     * <p>The pre-1.21.6 spelling of the GUI pose: {@code PoseStack.last().pose()} is a JOML
     * {@link Matrix4f} here, while 1.21.6 replaced the GUI pose with a 2D matrix stack. This class
     * is compiled only on nodes whose {@code libs/} supplied a minimap jar for that generation, so
     * a newer node needs its own port of this file together with its own jar -- the same rule the
     * build gating states.
     */
    private static Matrix4f pose(GuiGraphics graphics) {
        return graphics.pose().last().pose();
    }

    /**
     * An element's on-screen X, in GUI pixels.
     *
     * <p>The pose translation is already the element's position rounded to whole pose units, and
     * Xaero hands the renderer the fractions it dropped as the two translate arguments. Putting
     * them back matters for the calibration probes: the shortest fallback distance is four blocks,
     * so a half-unit of rounding would be a dozen percent of the basis vector. This is the exact
     * position the translation would have had, transformed by the pose's linear part.
     */
    private static double screenX(Matrix4f pose, double residualX, double residualY) {
        return pose.m30() + pose.m00() * residualX + pose.m01() * residualY;
    }

    /** The same for on-screen Y. */
    private static double screenY(Matrix4f pose, double residualX, double residualY) {
        return pose.m31() + pose.m10() * residualX + pose.m11() * residualY;
    }

    /** Whether an element kind draws a layer anchored at the frame anchor. */
    private static boolean isLayer(XaeroMinimapRouteElement.Kind kind) {
        return switch (kind) {
            case ROADS, RAILS, MTR_LAYER, ROADWEAVER, ROUTE_DONE, ROUTE_AHEAD -> true;
            default -> false;
        };
    }

    /**
     * Whether the overlay should be drawing at all right now.
     *
     * <p>Three conditions, in the order they cost: the configured mode must be the minimap one, no
     * screen may be open, and there must be a player in a level. The mode is read from the config
     * rather than from the resolved mode router on purpose -- this class exists only on nodes that
     * were compiled with the minimap API and only registers successfully when Xaero is there, so
     * "registered" is already true by construction; what it must not do is draw in the other mode,
     * where the in-HUD panel is drawing the same map. The screen condition keeps the overlay out of
     * the inventory and out of the full-screen map, neither of which is what this mode is for.
     */
    private static boolean drawing() {
        if (RoadConfig.mapDisplayMode() != MapDisplayMode.XAERO_MINIMAP) {
            return false;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (Screens.of(minecraft) != null) {
            return false;
        }
        return minecraft.player != null && minecraft.level != null;
    }

    /** Nothing is prepared before the elements; the strokes need no texture or depth state. */
    @Override
    public void preRender(MinimapElementRenderInfo info,
                          MultiBufferSource.BufferSource bufferSource,
                          MultiTextureRenderTypeRendererProvider rendererProvider) {
    }

    /** Nothing is released after the elements; {@link HudDraw#endBlock} closes every drawing. */
    @Override
    public void postRender(MinimapElementRenderInfo info,
                           MultiBufferSource.BufferSource bufferSource,
                           MultiTextureRenderTypeRendererProvider rendererProvider) {
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
