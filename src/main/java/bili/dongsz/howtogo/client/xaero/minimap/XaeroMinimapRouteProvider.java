package bili.dongsz.howtogo.client.xaero.minimap;

import bili.dongsz.howtogo.client.HudDraw;
import bili.dongsz.howtogo.client.MtrTransit;
import bili.dongsz.howtogo.client.Navigation;
import bili.dongsz.howtogo.client.RailTrackStore;
import bili.dongsz.howtogo.client.RoadRenderContext;
import bili.dongsz.howtogo.client.RoadStore;
import bili.dongsz.howtogo.client.RoadWeaverLayer;
import bili.dongsz.howtogo.client.UiTheme;
import bili.dongsz.howtogo.route.Destination;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import xaero.hud.minimap.element.render.MinimapElementRenderLocation;
import xaero.hud.minimap.element.render.MinimapElementRenderProvider;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the navigation overlay's element list for one frame.
 *
 * begin rebuilds the buffer and hasNext/getNext hand it over in order; Xaero walks the same list. The order is load-bearing: probes first so the renderer has measured the frame's projection before layers arrive (the API cannot read a future element), then layers wide to narrow (roads, rails, MTR, RoadWeaver, transit strokes, route halves), then markers. That is also the paint order -- each element gets a slightly larger depth than the one before.
 * The minimap draws transit lines as strokes with no name plates: a fixed-size plate would cover much of a map only a few dozen blocks across. The larger maps keep their plates.
 * Roads are world properties and always listed; route and destination need a trip, origin needs a started trip, and Xaero skips hidden elements, so there is no "active but empty" state.
 * Layers carry the network or route read at frame start, so a store replaced mid-frame cannot make the renderer draw something the rest of the frame did not see. Lists are read-only views or replaced whole.
 */
public final class XaeroMinimapRouteProvider
        extends MinimapElementRenderProvider<XaeroMinimapRouteElement, RoadRenderContext> {

    /** The current pass's elements, in drawing order. */
    private final List<XaeroMinimapRouteElement> buffer = new ArrayList<>();

    private int index;

    @Override
    public void begin(MinimapElementRenderLocation location, RoadRenderContext context) {
        buffer.clear();
        index = 0;

        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null) {
            // No player means no anchor and no minimap to draw on; Xaero should not call the renderer then, but
            // do not depend on ordering.
            return;
        }
        double anchorX = player.getX();
        double anchorZ = player.getZ();
        // Snapshot the anchor first: every world coordinate in this frame is relative to it, so probes and
        // layers must see the same number.
        XaeroMinimapProjection.beginFrame(anchorX, anchorZ);

        appendProbes(anchorX, anchorZ);
        appendLayers(anchorX, anchorZ);
        appendTransitLines(anchorX, anchorZ);
        appendRoute(anchorX, anchorZ);
        appendMarkers();
    }

    @Override
    public boolean hasNext(MinimapElementRenderLocation location, RoadRenderContext context) {
        return index < buffer.size();
    }

    @Override
    public XaeroMinimapRouteElement getNext(MinimapElementRenderLocation location,
                                            RoadRenderContext context) {
        return buffer.get(index++);
    }

    /**
     * Ends the pass. Only the cursor is reset; the list is cleared in begin, so a frame that threw before end cannot hand the next frame a list it did not build.
     */
    @Override
    public void end(MinimapElementRenderLocation location, RoadRenderContext context) {
        index = 0;
    }

    /**
     * One centre, six calibration and four edge elements, all invisible.
     *
     * They are the only way to learn the projection from inside the element API (see XaeroMinimapProjection). Calibration probes are two per distance (X and Z), largest distance first; edge probes sit a hundred thousand blocks out so Xaero clamps them to the minimap's edge.
     */
    private void appendProbes(double anchorX, double anchorZ) {
        buffer.add(XaeroMinimapRouteElement.probe(
                XaeroMinimapRouteElement.Kind.CAL_CENTRE, anchorX, anchorZ, 0));
        for (double distance : XaeroMinimapProjection.PROBE_DISTANCES) {
            buffer.add(XaeroMinimapRouteElement.probe(
                    XaeroMinimapRouteElement.Kind.CAL_X, anchorX + distance, anchorZ, distance));
            buffer.add(XaeroMinimapRouteElement.probe(
                    XaeroMinimapRouteElement.Kind.CAL_Z, anchorX, anchorZ + distance, distance));
        }
        double reach = XaeroMinimapProjection.EDGE_PROBE_DISTANCE;
        buffer.add(XaeroMinimapRouteElement.probe(
                XaeroMinimapRouteElement.Kind.EDGE_XP, anchorX + reach, anchorZ, 0));
        buffer.add(XaeroMinimapRouteElement.probe(
                XaeroMinimapRouteElement.Kind.EDGE_XN, anchorX - reach, anchorZ, 0));
        buffer.add(XaeroMinimapRouteElement.probe(
                XaeroMinimapRouteElement.Kind.EDGE_ZP, anchorX, anchorZ + reach, 0));
        buffer.add(XaeroMinimapRouteElement.probe(
                XaeroMinimapRouteElement.Kind.EDGE_ZN, anchorX, anchorZ - reach, 0));
    }

    /**
     * The road network and machine-read layers, whether or not a trip is active.
     *
     * The same four layers in the same order as NavHudRenderer.drawMiniMap: player's roads, Create's tracks, MTR's track, Roads read out of RoadWeaver. Separate elements rather than one because each is one drawing block; merging them would put the cull box in different code from the panel that reads the same layers, and the two maps would drift.
     */
    private void appendLayers(double anchorX, double anchorZ) {
        buffer.add(XaeroMinimapRouteElement.networkLayer(
                XaeroMinimapRouteElement.Kind.ROADS, anchorX, anchorZ, RoadStore.get()));
        buffer.add(XaeroMinimapRouteElement.networkLayer(
                XaeroMinimapRouteElement.Kind.RAILS, anchorX, anchorZ,
                RailTrackStore.network()));
        buffer.add(XaeroMinimapRouteElement.networkLayer(
                XaeroMinimapRouteElement.Kind.MTR_LAYER, anchorX, anchorZ,
                MtrTransit.railLayer()));
        buffer.add(XaeroMinimapRouteElement.networkLayer(
                XaeroMinimapRouteElement.Kind.ROADWEAVER, anchorX, anchorZ,
                RoadWeaverLayer.drawnNetwork()));
    }

    /**
     * The route, split at the player into the covered and still-ahead halves.
     *
     * The split is no longer worked out here: the elements carry the route's ink and the distance covered, and the renderer asks HudDraw for the half it draws -- the same table the panel and the two viewers use. A half is added only when it would draw something, since an empty drawing block still costs the frame a block.
     */
    private void appendRoute(double anchorX, double anchorZ) {
        HudDraw.RouteInk ink = Navigation.routeInk();
        if (!ink.isPresent()) {
            return;
        }
        double travelled = Navigation.travelled();
        if (travelled > 0) {
            buffer.add(XaeroMinimapRouteElement.routeInkLayer(
                    XaeroMinimapRouteElement.Kind.ROUTE_DONE, anchorX, anchorZ,
                    UiTheme.routeDone(), ink, travelled));
        }
        if (travelled < ink.length()) {
            buffer.add(XaeroMinimapRouteElement.routeInkLayer(
                    XaeroMinimapRouteElement.Kind.ROUTE_AHEAD, anchorX, anchorZ,
                    0, ink, travelled));
        }
    }


    /**
     * The public transport lines, one element per stretch, in each line's own colour.
     *
     * Between the road layers and the route because a line is part of the map rather than the trip; the route must stay readable over it. Same shapes as the two larger self-drawn maps (RoadElementRenderer.transitStrokesFor).
     */
    private void appendTransitLines(double anchorX, double anchorZ) {
        for (bili.dongsz.howtogo.client.RoadElementRenderer.TransitStroke stroke :
                bili.dongsz.howtogo.client.RoadElementRenderer.transitStrokesFor(
                        Navigation.linesInPlay())) {
            buffer.add(XaeroMinimapRouteElement.routeLayer(
                    XaeroMinimapRouteElement.Kind.TRANSIT_LINES, anchorX, anchorZ,
                    stroke.colour(), stroke.points()));
        }
    }

    /**
     * The destination and the trip origin.
     *
     * The only elements anchored at their own place rather than the frame anchor, because a marker is a dot where it is and Xaero has already projected it. The destination is drawn whenever set, with or without a route, so a failed plan still shows where the player is heading; the origin is dropped when NaN, which is how an unset origin is spelled.
     */
    private void appendMarkers() {
        Destination destination = Navigation.target();
        if (destination != null) {
            buffer.add(XaeroMinimapRouteElement.marker(
                    XaeroMinimapRouteElement.Kind.DESTINATION,
                    destination.x(), destination.z(), UiTheme.destination()));
        }
        double originX = Navigation.tripOriginX();
        double originZ = Navigation.tripOriginZ();
        if (!Double.isNaN(originX) && !Double.isNaN(originZ)) {
            buffer.add(XaeroMinimapRouteElement.marker(
                    XaeroMinimapRouteElement.Kind.ORIGIN, originX, originZ, UiTheme.origin()));
        }
    }
}
