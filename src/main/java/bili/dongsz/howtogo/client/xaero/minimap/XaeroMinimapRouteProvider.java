package bili.dongsz.howtogo.client.xaero.minimap;

import bili.dongsz.howtogo.client.MtrTransit;
import bili.dongsz.howtogo.client.Navigation;
import bili.dongsz.howtogo.client.RailTrackStore;
import bili.dongsz.howtogo.client.RoadRenderContext;
import bili.dongsz.howtogo.client.RoadStore;
import bili.dongsz.howtogo.client.RoadWeaverLayer;
import bili.dongsz.howtogo.client.UiTheme;
import bili.dongsz.howtogo.route.Destination;
import bili.dongsz.howtogo.route.Route;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import xaero.hud.minimap.element.render.MinimapElementRenderLocation;
import xaero.hud.minimap.element.render.MinimapElementRenderProvider;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the navigation overlay's element list for one frame.
 *
 * <p>The buffer is rebuilt in {@link #begin} and handed over through {@link #hasNext}/{@link #getNext}
 * until it runs out; Xaero walks that same list in order. The order is not incidental: the probe
 * elements come first so the renderer has measured the frame's projection by the time the first
 * layer element arrives -- the pipeline gives a renderer no way to read a future element, so the
 * transform has to be sampled from elements that are themselves in the list.
 *
 * <h2>The order, and why it is that order</h2>
 * The centre probe first, because it carries the parent pose's linear part and the anchor's screen
 * position; then the six axis probes; then the four edge probes; then the layers, wide to narrow
 * (roads, rails, MTR, RoadWeaver, then the two halves of the route); then the markers. Xaero gives
 * each drawn element a slightly larger depth than the one before it, so that order is also the
 * paint order -- road network under rails under the route under the markers -- which is what the
 * navigation panel draws.
 *
 * <h2>What needs a trip and what does not</h2>
 * The road network is a property of the world, so it is in the list whenever the overlay draws at
 * all; a player who has never set a destination still sees their roads on the minimap. The route and
 * the destination need a trip, the origin needs a trip that has started, and each is simply absent
 * from the list when it does not apply. Xaero skips an element the reader reports as hidden and an
 * empty list is an empty frame, so there is no "overlay active but nothing to show" state to manage.
 *
 * <h2>The layer payloads</h2>
 * A layer element carries the network or route it draws rather than the renderer reaching into the
 * stores again. The reference was read at the start of the frame, which is when the layer snapshot
 * belongs to; a store replaced wholesale between then and the renderer (a layer re-read, a route
 * re-planned) would make the renderer draw something different from what the rest of the frame
 * saw. The lists are read-only views or replaced whole, never mutated under the element.
 */
public final class XaeroMinimapRouteProvider
        extends MinimapElementRenderProvider<XaeroMinimapRouteElement, RoadRenderContext> {

    /** The elements of the current pass, in drawing order. */
    private final List<XaeroMinimapRouteElement> buffer = new ArrayList<>();

    private int index;

    @Override
    public void begin(MinimapElementRenderLocation location, RoadRenderContext context) {
        buffer.clear();
        index = 0;

        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null) {
            // No player means no anchor and no minimap to draw on. Xaero should not call the
            // renderer in that state either, but the provider must not depend on ordering.
            return;
        }
        double anchorX = player.getX();
        double anchorZ = player.getZ();
        // Snapshot the anchor before anything else: every world coordinate in this frame is
        // expressed relative to it, so it has to be the same number for the probes and the layers.
        XaeroMinimapProjection.beginFrame(anchorX, anchorZ);

        appendProbes(anchorX, anchorZ);
        appendLayers(anchorX, anchorZ);
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
     * Ends the pass.
     *
     * <p>Only the cursor is reset. The list is cleared where it is filled, in {@link #begin}, so a
     * frame that threw before {@code end} cannot hand the next frame a list it did not build.
     */
    @Override
    public void end(MinimapElementRenderLocation location, RoadRenderContext context) {
        index = 0;
    }

    /**
     * The probes: one centre, six calibration and four edge elements.
     *
     * <p>All of these are invisible. They are the only way to learn the projection from inside the
     * element API -- see {@link XaeroMinimapProjection} for what is measured from them and why. The
     * six calibration probes are two per distance (X and Z), largest distance first, and the four
     * edge probes are a hundred thousand blocks out in each world direction so Xaero has to clamp
     * them to the minimap's edge.
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
     * The road network and the machine-read layers, whether or not a trip is active.
     *
     * <p>The same four layers in the same order as {@code NavHudRenderer.drawMiniMap}: the player's
     * own roads, Create's tracks, MTR's track, then the roads read out of RoadWeaver. They are
     * separate elements rather than one because each is one drawing block and the pipeline draws
     * one element at a time; a single element that drew four networks would work, but then the
     * RoadWeaver layer's world-space cull box would be in a different place from the code that
     * reads the same four layers for the panel, and the two maps would drift.
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
                RoadWeaverLayer.network()));
    }

    /**
     * The route, split where the player is into the travelled and still-ahead halves.
     *
     * <p>The same split as the navigation panel makes, over the same numbers: walk the polyline and
     * accumulate drawn length until it passes {@link Navigation#travelled()}, then put the whole
     * route in the "ahead" colour if nothing has been walked, everything in the "done" colour if
     * the player is past the end, and otherwise two lists sharing an interpolated split point. The
     * two maps then colour the route identically, which is the point of computing it here instead
     * of once per map.
     *
     * <p>The lists are carried by the element and are not read by anything else, so they are
     * ordinary mutable ArrayLists built for this frame.
     */
    private void appendRoute(double anchorX, double anchorZ) {
        Route route = Navigation.route();
        if (!route.isPresent()) {
            return;
        }
        List<double[]> points = route.points();
        double travelled = Navigation.travelled();
        double cumulative = 0;
        int splitAt = -1;
        double splitX = 0;
        double splitZ = 0;
        for (int i = 1; i < points.size(); i++) {
            double[] a = points.get(i - 1);
            double[] b = points.get(i);
            double segmentLength = Math.hypot(b[0] - a[0], b[1] - a[1]);
            if (segmentLength < 1.0E-9) {
                continue;
            }
            if (cumulative + segmentLength >= travelled) {
                splitAt = i;
                double t = (travelled - cumulative) / segmentLength;
                splitX = a[0] + (b[0] - a[0]) * t;
                splitZ = a[1] + (b[1] - a[1]) * t;
                break;
            }
            cumulative += segmentLength;
        }
        List<double[]> done = new ArrayList<>();
        List<double[]> ahead = new ArrayList<>();
        if (splitAt < 0) {
            if (travelled > 0) {
                done.addAll(points);
            } else {
                ahead.addAll(points);
            }
        } else if (splitAt <= 0) {
            ahead.addAll(points);
        } else {
            done.addAll(points.subList(0, splitAt));
            done.add(new double[] {splitX, splitZ});
            ahead.add(new double[] {splitX, splitZ});
            ahead.addAll(points.subList(splitAt, points.size()));
        }
        if (done.size() >= 2) {
            buffer.add(XaeroMinimapRouteElement.routeLayer(
                    XaeroMinimapRouteElement.Kind.ROUTE_DONE, anchorX, anchorZ,
                    UiTheme.routeDone(), done));
        }
        if (ahead.size() >= 2) {
            buffer.add(XaeroMinimapRouteElement.routeLayer(
                    XaeroMinimapRouteElement.Kind.ROUTE_AHEAD, anchorX, anchorZ,
                    UiTheme.route(), ahead));
        }
    }

    /**
     * The destination and the trip origin.
     *
     * <p>These are the only elements anchored at their own place rather than at the frame anchor,
     * because a marker is a dot where it is and Xaero has already projected it. The destination is
     * drawn whenever one is set, with or without a route, so a failed plan still shows where the
     * player is heading. The origin is dropped when it is NaN, which is how an unset trip origin is
     * spelled.
     *
     * <p>The next turn used to be drawn here as one more dot. It is not any more: the turn is where
     * the route line already draws attention, and a solid accent dot on top of the line at exactly
     * the junction the driver is looking for read as a blip that hid the corner of the route rather
     * than as a marker -- reported from a real session, removed at the player's request. The compact
     * HUD's arrow is the next-turn reading; the minimap's job is the geometry.
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
