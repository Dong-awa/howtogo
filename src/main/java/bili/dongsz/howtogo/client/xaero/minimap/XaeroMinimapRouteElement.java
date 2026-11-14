package bili.dongsz.howtogo.client.xaero.minimap;

import bili.dongsz.howtogo.client.HudDraw;
import bili.dongsz.howtogo.road.RoadNetwork;

import java.util.List;

/**
 * One thing the navigation overlay puts through Xaero's minimap element pipeline.
 *
 * An element carries a kind, a world position in blocks, a colour and, for some kinds, what it draws and a probe distance. The kinds fall into four groups:
 * - Probes (CAL_CENTRE, CAL_X, CAL_Z) are not drawn. The API hands a renderer no zoom, rotation or minimap origin, and Xaero projects every element before calling the renderer, so a probe at a known world offset samples the projection; XaeroMinimapProjection turns samples into a transform.
 * - Edge probes (EDGE_*) are not drawn either: they sit a hundred thousand blocks away, Xaero clamps them to the minimap's edge, and their clamped position defines the minimap rectangle.
 * - Layers (ROADS, RAILS, MTR_LAYER, ROADWEAVER, TRANSIT_LINES, ROUTE_DONE, ROUTE_AHEAD) carry the network or route they draw, one element per layer anchored at the frame anchor, so a layer is one local coordinate space and one drawing block.
 * - Markers (DESTINATION, ORIGIN) are anchored at their own world position, because a marker is a dot at exactly the place it marks.
 * One class with a kind rather than a class per kind: a hierarchy would be ten files that say nothing.
 */
public final class XaeroMinimapRouteElement {

    /** What an element means, and therefore what the renderer does with it. */
    public enum Kind {

        /**
         * The anchor point itself: a probe at the player's position.
         *
         * Its pose translation is the frame anchor's screen position and its matrix's linear part is the transform every element shares.
         */
        CAL_CENTRE,

        /**
         * A probe at anchor + d along world X, for a calibration distance d.
         *
         * If Xaero clamped it to the minimap edge it says nothing about the projection and the next smaller distance is used.
         */
        CAL_X,

        /** The same at anchor + d along world Z. */
        CAL_Z,

        /** The far-away probe along +X; Xaero clamps it to the minimap's edge. */
        EDGE_XP,

        /** The far-away probe along -X. */
        EDGE_XN,

        /** The far-away probe along +Z. */
        EDGE_ZP,

        /** The far-away probe along -Z. */
        EDGE_ZN,

        /** The player's own road network, drawn as one layer. */
        ROADS,

        /** Create's tracks, drawn in with the roads as the navigation panel does. */
        RAILS,

        /** MTR's track, from the lines whose marks are on. */
        MTR_LAYER,

        /**
         * Roads read out of RoadWeaver. A separate element because this layer needs a world-space cull box: it holds a whole region's roads and projecting all of them just to clip them would be the frame cost.
         */
        ROADWEAVER,

        /**
         * The public transport lines, one element per stretch, each in its line's own colour. Between roads and route on purpose: a line is part of the map, not the trip, so the route stays on top.
         */
        TRANSIT_LINES,

        /** The travelled part of the route. */
        ROUTE_DONE,

        /** The part of the route still ahead. */
        ROUTE_AHEAD,

        /** The end of the trip; drawn clamped to the edge when out of view. */
        DESTINATION,

        /** Where the trip started; drawn only while it is on the map. */
        ORIGIN
    }

    private final Kind kind;
    private final double x;
    private final double z;
    private final int argb;
    private final double probe;
    private final RoadNetwork network;
    private final List<double[]> points;
    private final HudDraw.RouteInk ink;
    private final double travelled;

    private XaeroMinimapRouteElement(Kind kind, double x, double z, int argb, double probe,
                                     RoadNetwork network, List<double[]> points,
                                     HudDraw.RouteInk ink, double travelled) {
        this.kind = kind;
        this.x = x;
        this.z = z;
        this.argb = argb;
        this.probe = probe;
        this.network = network;
        this.points = points;
        this.ink = ink;
        this.travelled = travelled;
    }

    /** A calibration probe at (x, z); probe is the distance in blocks, 0 for centre and edge probes. */
    static XaeroMinimapRouteElement probe(Kind kind, double x, double z, double probe) {
        return new XaeroMinimapRouteElement(kind, x, z, 0, probe, null, null, null, 0);
    }

    /** A road-network layer, anchored at the frame anchor. */
    static XaeroMinimapRouteElement networkLayer(Kind kind, double anchorX, double anchorZ,
                                                 RoadNetwork network) {
        return new XaeroMinimapRouteElement(kind, anchorX, anchorZ, 0, 0, network, null, null, 0);
    }

    /** A route layer built from explicit polyline points, anchored at the frame anchor. */
    static XaeroMinimapRouteElement routeLayer(Kind kind, double anchorX, double anchorZ, int argb,
                                               List<double[]> points) {
        return new XaeroMinimapRouteElement(kind, anchorX, anchorZ, argb, 0, null, points, null, 0);
    }

    /**
     * One half of the live route, carried as the route's ink and the distance already covered.
     *
     * The ink is the table every other surface draws from (Navigation.routeInk); the renderer walks it and draws only the half this element stands for, so both halves come from one calculation and the frame allocates no lists.
     */
    static XaeroMinimapRouteElement routeInkLayer(Kind kind, double anchorX, double anchorZ,
                                                  int argb,
                                                  HudDraw.RouteInk ink,
                                                  double travelled) {
        return new XaeroMinimapRouteElement(kind, anchorX, anchorZ, argb, 0, null, null, ink,
                travelled);
    }

    /** A marker element, anchored at the place it marks. */
    static XaeroMinimapRouteElement marker(Kind kind, double x, double z, int argb) {
        return new XaeroMinimapRouteElement(kind, x, z, argb, 0, null, null, null, 0);
    }

    public Kind kind() {
        return kind;
    }

    public double x() {
        return x;
    }

    public double z() {
        return z;
    }

    /**
     * The colour the element is drawn in, as ARGB -- carried here because the route has two colours and network layers none (they take the road data's colour), and only the provider knows which is which.
     */
    public int argb() {
        return argb;
    }

    public double probe() {
        return probe;
    }

    public RoadNetwork network() {
        return network;
    }

    public List<double[]> routePoints() {
        return points;
    }

    public HudDraw.RouteInk routeInk() {
        return ink;
    }

    public double travelled() {
        return travelled;
    }
}
