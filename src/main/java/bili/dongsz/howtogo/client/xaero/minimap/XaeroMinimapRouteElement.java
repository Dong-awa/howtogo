package bili.dongsz.howtogo.client.xaero.minimap;

import bili.dongsz.howtogo.road.RoadNetwork;

import java.util.List;

/**
 * One thing the navigation overlay puts through Xaero's minimap element pipeline.
 *
 * <p>An element carries a kind, a world position in blocks, the colour it is drawn in, and -- for
 * some kinds -- what it draws and a probe distance. The kinds fall into four groups with quite
 * different jobs, which is worth knowing before reading any of the rendering code:
 *
 * <ul>
 *   <li><b>Probes</b> ({@link Kind#CAL_CENTRE}, {@link Kind#CAL_X}, {@link Kind#CAL_Z}) are not
 *       drawn at all. They exist because the element API hands a renderer no zoom, rotation or
 *       minimap origin, and a polyline cannot be drawn without them. Xaero projects every element
 *       before calling the renderer, so a probe at a known world offset is one sample of the
 *       projection; {@link XaeroMinimapProjection} turns the samples into a transform.</li>
 *   <li><b>Edge probes</b> ({@link Kind#EDGE_XP} and friends) are not drawn either. They sit a
 *       hundred thousand blocks away, Xaero clamps them to the minimap's edge, and their clamped
 *       position is where the minimap's rectangle comes from.</li>
 *   <li><b>Layers</b> ({@link Kind#ROADS}, {@link Kind#RAILS}, {@link Kind#MTR_LAYER},
 *       {@link Kind#ROADWEAVER}, {@link Kind#ROUTE_DONE}, {@link Kind#ROUTE_AHEAD}) carry the
 *       network or the route they draw. One element per layer, each anchored at the frame anchor,
 *       so everything a layer draws is in one local coordinate space and the whole layer is one
 *       drawing block.</li>
 *   <li><b>Markers</b> ({@link Kind#DESTINATION}, {@link Kind#ORIGIN}) are the
 *       only elements anchored at their own world position, because a marker is a dot at exactly
 *       the place it marks and needs no transform beyond the one Xaero already applied.</li>
 * </ul>
 *
 * <p>One class with a kind rather than a class per kind: all of these are the same few numbers with
 * a different interpretation, and a hierarchy would be ten files to say nothing. The renderer
 * switches on {@link Kind}, which is where the interpretation lives.
 */
public final class XaeroMinimapRouteElement {

    /** What an element means, and therefore what the renderer does with it. */
    public enum Kind {

        /**
         * The anchor point itself: one probe at the player's position.
         *
         * <p>Its pose translation is the screen position of the frame anchor, and its matrix's
         * linear part is the parent transform every element of the frame shares. Both facts are
         * needed before any other probe can be interpreted.
         */
        CAL_CENTRE,

        /**
         * A probe at {@code anchor + d} along world X, for a calibrated distance {@code d}.
         *
         * <p>Tells the transform where one block of +X lands on screen. If Xaero clamped it to the
         * minimap's edge, it says nothing about the projection and the next smaller distance is
         * used.
         */
        CAL_X,

        /** The same at {@code anchor + d} along world Z. */
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
         * Roads read out of RoadWeaver. A separate element because this layer needs a world-space
         * cull box: it holds a whole region's worth of roads, and projecting all of them only to
         * clip them would be the frame cost.
         */
        ROADWEAVER,

        /** The travelled part of the route. */
        ROUTE_DONE,

        /** The part of the route still ahead. */
        ROUTE_AHEAD,

        /** The end of the trip; drawn clamped to the edge when it is out of view. */
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

    private XaeroMinimapRouteElement(Kind kind, double x, double z, int argb, double probe,
                                     RoadNetwork network, List<double[]> points) {
        this.kind = kind;
        this.x = x;
        this.z = z;
        this.argb = argb;
        this.probe = probe;
        this.network = network;
        this.points = points;
    }

    /**
     * A probe element.
     *
     * @param kind the probe kind
     * @param x world X
     * @param z world Z
     * @param probe the calibration distance in blocks, or 0 for the centre and edge probes
     */
    static XaeroMinimapRouteElement probe(Kind kind, double x, double z, double probe) {
        return new XaeroMinimapRouteElement(kind, x, z, 0, probe, null, null);
    }

    /**
     * A road-network layer, anchored at the frame anchor.
     *
     * @param kind which layer this is
     * @param anchorX the frame anchor's world X
     * @param anchorZ the frame anchor's world Z
     * @param network the network to draw, read at the start of the frame
     */
    static XaeroMinimapRouteElement networkLayer(Kind kind, double anchorX, double anchorZ,
                                                 RoadNetwork network) {
        return new XaeroMinimapRouteElement(kind, anchorX, anchorZ, 0, 0, network, null);
    }

    /**
     * A route layer, anchored at the frame anchor.
     *
     * @param kind which half of the route this is
     * @param anchorX the frame anchor's world X
     * @param anchorZ the frame anchor's world Z
     * @param argb the colour the half is drawn in
     * @param points the polyline's world points
     */
    static XaeroMinimapRouteElement routeLayer(Kind kind, double anchorX, double anchorZ, int argb,
                                               List<double[]> points) {
        return new XaeroMinimapRouteElement(kind, anchorX, anchorZ, argb, 0, null, points);
    }

    /**
     * A marker element, anchored at the place it marks.
     *
     * @param kind the marker kind
     * @param x the marked place's world X
     * @param z the marked place's world Z
     * @param argb the marker's colour
     */
    static XaeroMinimapRouteElement marker(Kind kind, double x, double z, int argb) {
        return new XaeroMinimapRouteElement(kind, x, z, argb, 0, null, null);
    }

    /** What this element means. */
    public Kind kind() {
        return kind;
    }

    /** World X of the element's anchor, in blocks. */
    public double x() {
        return x;
    }

    /** World Z of the element's anchor, in blocks. */
    public double z() {
        return z;
    }

    /**
     * The colour the element is drawn in, as ARGB.
     *
     * <p>Carried on the element rather than chosen by the renderer from the kind because the route
     * has two colours and the network layers have none -- those take their colour from the road
     * data -- and only the provider knows which is which when it builds them.
     */
    public int argb() {
        return argb;
    }

    /** The calibration distance in blocks, or 0 for an element that is not a calibration probe. */
    public double probe() {
        return probe;
    }

    /** The network a layer element draws, or null for anything that is not a network layer. */
    public RoadNetwork network() {
        return network;
    }

    /** The polyline a route layer draws, or null for anything that is not a route layer. */
    public List<double[]> routePoints() {
        return points;
    }
}
