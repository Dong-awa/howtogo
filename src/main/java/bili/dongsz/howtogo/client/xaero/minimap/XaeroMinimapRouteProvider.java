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
 * <p>The buffer is rebuilt in {@link #begin} and handed over through {@link #hasNext}/{@link #getNext}
 * until it runs out; Xaero walks that same list in order. The order is not incidental: the probe
 * elements come first so the renderer has measured the frame's projection by the time the first
 * layer element arrives -- the pipeline gives a renderer no way to read a future element, so the
 * transform has to be sampled from elements that are themselves in the list.
 *
 * <h2>The order, and why it is that order</h2>
 * The centre probe first, because it carries the parent pose's linear part and the anchor's screen
 * position; then the six axis probes; then the four edge probes; then the layers, wide to narrow
 * (roads, rails, MTR, RoadWeaver, the transit line strokes, then the two halves of the route); then
 * the markers. Xaero gives each drawn element a slightly larger depth than the one before it, so
 * that order is also the paint order -- road network under rails under the route under the markers --
 * which is what the navigation panel draws.
 *
 * <p>The transit lines are drawn as strokes and are not named here. A line's name plate is a
 * fixed-size capsule, and this map is a few dozen blocks across at most: a plate that fits at all
 * covers a large share of the map it is naming, which is what the player reported after seeing it in
 * game. The two big maps keep their plates -- there is room for them there -- and the minimap draws
 * the line itself.
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
                RoadWeaverLayer.drawnNetwork()));
    }

    /**
     * The route, split where the player is into the travelled and still-ahead halves.
     *
     * <p>The split is no longer worked out here. It used to be: this provider walked the polyline,
     * built two lists and handed them over, which made the minimap the third place in the mod that
     * knew how a route is cut in two -- and, once a route could be several colours, the wrong shape
     * of thing entirely: the ahead half could no longer be one list in one colour. The two elements
     * now carry the route's ink and the distance covered, and the renderer asks {@code HudDraw} for
     * the half it is drawing, which is the same table the navigation panel and the two viewers use.
     * The frame therefore builds no lists for the route at all.
     *
     * <p>The conditions for adding each element are the same as the old lists' sizes were: there is
     * a covered half once anything has been travelled, and an ahead half while there is line left.
     * An element that would draw nothing is not added, because a drawing block that emits no
     * vertices still costs the frame a block.
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
     * <p>Between the road layers and the route, because a line is part of the map rather than part of
     * the trip: the route the player is following has to stay readable over it. The shapes are the
     * same ones the two larger self-drawn maps use -- see RoadElementRenderer.transitStrokesFor --
     * so a line bends where the line bends, even on a map this small.
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
