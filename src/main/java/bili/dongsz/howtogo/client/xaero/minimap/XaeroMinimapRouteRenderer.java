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
 * Draws the navigation overlay onto Xaero's Minimap: road network, transit strokes, route and markers.
 *
 * The pipeline projects each element before calling the renderer and gives no zoom, rotation or minimap origin, so the frame transform is measured: the provider emits probes first, the renderer records what Xaero's projection did to each, and XaeroMinimapProjection turns the samples into the world-to-local matrix the emitters take. Everything is clipped to the minimap rectangle the edge probes measured.
 * Layer elements are all anchored at the frame anchor (the player's position at frame start) so one matrix serves every layer; a marker is the exception, anchored at its own world position.
 * Pose local-to-screen scale is measured per frame and every pixel size is divided by it, so the minimap and the panel draw the same weights: road 1.0 px, route 2.6 px, the panel's marker radii and its 0x9A road alpha. Colours come from UiTheme or the road data.
 * No transit line name plates here: a fixed-size capsule would cover much of a map a few dozen blocks wide, so the larger maps keep their plates and this one draws the line.
 */
public final class XaeroMinimapRouteRenderer
        extends MinimapElementRenderer<XaeroMinimapRouteElement, RoadRenderContext> {

    /**
     * Where this renderer sits among the minimap's element renderers.
     *
     * Xaero sorts renderers by this value and gives later ones slightly larger depth; Xaero's waypoint renderer uses 100, and roads plus route sit above it so a waypoint cannot hide the line the player follows.
     */
    private static final int ORDER = 150;

    private static final double ROAD_HALF_WIDTH_PX = 0.5;

    private static final double ROUTE_HALF_WIDTH_PX = 1.3;

    private static final int ROAD_ALPHA = 0x9A;

    private static final int ROUTE_ALPHA = 0xFF;

    private static final double LINE_HALF_WIDTH_PX = 0.35;

    private static final int LINE_ALPHA = 0xC8;

    /** Markers are opaque; there is at most one of each on the map at a time. */
    private static final int MARKER_ALPHA = 0xFF;

    /** The origin marker's radius in GUI pixels. */
    private static final double ORIGIN_RADIUS_PX = 2.0;

    /** The destination marker's radius in GUI pixels. */
    private static final double DESTINATION_RADIUS_PX = 3.2;

    /**
     * How far around the anchor the RoadWeaver layer is asked for, in blocks.
     *
     * That layer holds a whole region's roads, so it is the one layer culled in world space before projection: a few hundred blocks of margin covers the map's corners at every zoom plus the frame's latency. The other layers are small enough to project and clip.
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
     * Checked once per frame before the provider builds a buffer. Not conditioned on a destination: the road network belongs to the map, and route and markers are simply absent without a trip. Off costs one config read.
     */
    @Override
    public boolean shouldRender(MinimapElementRenderLocation location) {
        return location == MinimapElementRenderLocation.OVER_MINIMAP && drawing();
    }

    /**
     * Consumes one element: a probe is recorded, a layer drawn, a marker a dot.
     * Probe kinds come first (see XaeroMinimapRouteProvider) and are the only place the frame's projection is read. They return false because nothing was drawn and Xaero uses the return value to decide depth slots. The clamped boolean means two things: for a probe the reading is unusable, for a marker it is the feature -- an off-map destination draws at the rim, which is the direction information wanted.
     */
    @Override
    public boolean renderElement(XaeroMinimapRouteElement element, boolean highlighted,
                                 boolean clamped, double depth, float optionalScale,
                                 double translateX, double translateY,
                                 MinimapElementRenderInfo info, GuiGraphics graphics,
                                 MultiBufferSource.BufferSource bufferSource) {
        if (!drawing()) {
            // shouldRender normally keeps this from being reached; checked again so the draw conditions live in
            // one place.
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
            // Nothing measured that can place a world point this frame. Drawing nothing is invisible; a guessed
            // transform would paint over the whole HUD.
            return false;
        }
        if (clamped && isLayer(element.kind())) {
            // A layer is anchored at the player, the map centre, and can never be clamped. If one is, the frame
            // is not the one the projection describes, so skip it rather than draw the network somewhere
            // arbitrary.
            return false;
        }
        return switch (element.kind()) {
            case ROADS, RAILS, MTR_LAYER -> drawNetwork(element.network(), graphics);
            case ROADWEAVER -> drawNetworkInBox(element, graphics);
            case TRANSIT_LINES -> drawLineStroke(element, graphics);
            case ROUTE_DONE, ROUTE_AHEAD -> drawRoute(element, graphics);
            case DESTINATION -> drawMarker(DESTINATION_RADIUS_PX, element.argb(), graphics);
            case ORIGIN -> clamped ? false : drawMarker(ORIGIN_RADIUS_PX, element.argb(), graphics);
            default -> false;
        };
    }

    /**
     * Draws one road-network layer with the measured transform.
     *
     * The emitters clip per segment against the minimap rectangle, so the network is handed over whole, exactly as the panel hands it over for its own square. A null or empty network is skipped before the drawing block.
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
                XaeroMinimapProjection.localClipMaxX(), XaeroMinimapProjection.localClipMaxY(),
                // The emitter units here are the pose's, not pixels: the one-way marks inside the stroke pass are
                // sized in pixels, so tell it how many pixels one local unit is worth. See HudDraw#emitNetwork.
                screenPxPerLocalUnit());
        HudDraw.endBlock(graphics, drawing);
        return true;
    }

    /**
     * Draws the RoadWeaver layer, which needs its world-space cull box first: only chains whose world bounding box comes near the map are projected, so a whole region's roads are not walked every frame.
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
                anchorX + ROADWEAVER_BOX_RADIUS, anchorZ + ROADWEAVER_BOX_RADIUS,
                screenPxPerLocalUnit());
        HudDraw.endBlock(graphics, drawing);
        return true;
    }

    private static double screenPxPerLocalUnit() {
        double localPerPx = XaeroMinimapProjection.localPerScreenPx();
        return localPerPx > 1.0E-6 ? 1.0 / localPerPx : 1.0;
    }

    /**
     * Draws one half of the route.
     *
     * The element carries the route's ink and the distance covered; one drawing block draws the half it stands for and HudDraw does the rest -- grey for the covered half, each stretch's own colour ahead, cut where the player is. Same call as the panel's mini-map, so both small maps colour a journey identically, and the frame builds no point lists. The clip rectangle is the minimap's, which matters most here: a route is usually longer than the map is wide.
     */
    private static boolean drawRoute(XaeroMinimapRouteElement element, GuiGraphics graphics) {
        HudDraw.RouteInk ink = element.routeInk();
        if (ink == null || !ink.isPresent()) {
            return false;
        }
        HudDraw.Drawing drawing = HudDraw.beginBlock(graphics);
        HudDraw.emitInkPart(drawing.sink, ink, element.travelled(),
                element.kind() == XaeroMinimapRouteElement.Kind.ROUTE_DONE,
                element.argb(),
                XaeroMinimapProjection.m00(), XaeroMinimapProjection.m01(),
                XaeroMinimapProjection.m02(), XaeroMinimapProjection.m10(),
                XaeroMinimapProjection.m11(), XaeroMinimapProjection.m12(),
                ROUTE_HALF_WIDTH_PX * XaeroMinimapProjection.localPerScreenPx(),
                ROUTE_ALPHA,
                XaeroMinimapProjection.localClipMinX(), XaeroMinimapProjection.localClipMinY(),
                XaeroMinimapProjection.localClipMaxX(), XaeroMinimapProjection.localClipMaxY());
        HudDraw.endBlock(graphics, drawing);
        return true;
    }

    /**
     * Draws one stretch of a public transport line as a thin stroke in the line's own colour.
     *
     * The element carries the world points; the emitters clip against the minimap rectangle as for a road layer or a route. Lines are map content rather than the trip, hence thinner and softer than the route; no name plate here (see the class note).
     */
    private static boolean drawLineStroke(XaeroMinimapRouteElement element, GuiGraphics graphics) {
        List<double[]> points = element.routePoints();
        if (points == null || points.size() < 2) {
            return false;
        }
        HudDraw.Drawing drawing = HudDraw.beginBlock(graphics);
        HudDraw.emitPath(drawing.sink, points,
                XaeroMinimapProjection.m00(), XaeroMinimapProjection.m01(),
                XaeroMinimapProjection.m02(), XaeroMinimapProjection.m10(),
                XaeroMinimapProjection.m11(), XaeroMinimapProjection.m12(),
                LINE_HALF_WIDTH_PX * XaeroMinimapProjection.localPerScreenPx(),
                element.argb(), LINE_ALPHA,
                XaeroMinimapProjection.localClipMinX(), XaeroMinimapProjection.localClipMinY(),
                XaeroMinimapProjection.localClipMaxX(), XaeroMinimapProjection.localClipMaxY());
        HudDraw.endBlock(graphics, drawing);
        return true;
    }

    /**
     * Draws a marker as one disc at the element's own origin. Xaero has already placed it, so only the size needs converting from panel pixels; radiusPx is the panel's size for the same marker.
     */
    private static boolean drawMarker(double radiusPx, int argb, GuiGraphics graphics) {
        HudDraw.Drawing drawing = HudDraw.beginBlock(graphics);
        HudDraw.emitDisc(drawing.sink, 0, 0,
                radiusPx * XaeroMinimapProjection.localPerScreenPx(), argb, MARKER_ALPHA);
        HudDraw.endBlock(graphics, drawing);
        return true;
    }

    /**
     * The pose matrix at the moment an element is rendered: its translation is the element's position in GUI pixels, its linear part the parent transform. That is the whole basis of the measured projection.
     *
     * Pre-1.21.6 spelling of the GUI pose: PoseStack.last().pose() is a JOML Matrix4f here, while 1.21.6 replaced the GUI pose with a 2D matrix stack. This class is compiled only on nodes whose libs/ supplied a minimap jar for that generation; a newer node needs its own port with its own jar.
     */
    private static Matrix4f pose(GuiGraphics graphics) {
        return graphics.pose().last().pose();
    }

    /**
     * An element's on-screen X, in GUI pixels.
     *
     * The pose translation is already the position rounded to whole pose units and Xaero passes the dropped fractions as the two translate arguments. Putting them back matters for probes: the shortest fallback distance is four blocks, so half a unit of rounding is a dozen percent of the basis vector.
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
            case ROADS, RAILS, MTR_LAYER, ROADWEAVER, TRANSIT_LINES,
                    ROUTE_DONE, ROUTE_AHEAD -> true;
            default -> false;
        };
    }

    /**
     * Whether the overlay should be drawing at all right now.
     *
     * Three conditions in cost order: the minimap mode is configured, no screen is open, and there is a player in a level. The mode is read from the config rather than the resolved router because this class only registers when Xaero and its API are present; it must not draw in the other mode, where the in-HUD panel draws the same map. The screen condition keeps it out of the inventory and the full-screen map.
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

    /** Nothing is released after the elements; HudDraw#endBlock closes every drawing. */
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
