package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.road.RoadSegment;

import java.util.Comparator;
import java.util.List;

/**
 * One map element.
 *
 * Normally wraps a single road polyline, so per-element overhead is paid once per road rather than once per vertex.
 * Four singleton overlays are appended last -- we are the only renderer in our layer, so Xaero's render order paints them on top: MARKS (MTR lines as track polylines), EDIT_UI (handles, only while editing), ROUTE (active route), LABELS (names, always last so text is never covered).
 */
public final class RoadElement {

    public enum Kind {
        ROAD,
        MARKS,
        EDIT_UI,
        ROUTE,
        LABELS
    }

    private final Kind kind;
    private final RoadSegment segment;

    /** Overlay anchor, kept at the map camera so pose translations stay small. */
    private double overlayAnchorX;
    private double overlayAnchorZ;

    private RoadElement(Kind kind, RoadSegment segment) {
        this.kind = kind;
        this.segment = segment;
    }

    public static RoadElement of(RoadSegment segment) {
        return new RoadElement(Kind.ROAD, segment);
    }

    public static final RoadElement MARKS = new RoadElement(Kind.MARKS, null);
    public static final RoadElement EDIT_UI = new RoadElement(Kind.EDIT_UI, null);
    public static final RoadElement ROUTE = new RoadElement(Kind.ROUTE, null);
    public static final RoadElement LABELS = new RoadElement(Kind.LABELS, null);

    public Kind kind() {
        return kind;
    }

    public boolean isRoad() {
        return kind == Kind.ROAD;
    }

    public RoadSegment segment() {
        return segment;
    }

    public void setOverlayAnchor(double x, double z) {
        this.overlayAnchorX = x;
        this.overlayAnchorZ = z;
    }

    /** Anchor X for culling and Xaero element placement. */
    public double anchorX() {
        return kind == Kind.ROAD ? segment.x(0) : overlayAnchorX;
    }

    /** Anchor Z for culling and Xaero element placement. */
    public double anchorZ() {
        return kind == Kind.ROAD ? segment.z(0) : overlayAnchorZ;
    }

    /**
     * Paint order key: the storey of the road drawn, or after every road for overlays.
     * Storey is the only depth cue a flat map has -- lower roads paint first.
     */
    private int drawOrder() {
        return kind == Kind.ROAD ? segment.layer() : Integer.MAX_VALUE;
    }

    /**
     * Reorders a frame's elements in place: roads by storey low to high, overlays still last.
     *
     * Stable sort, so same-storey roads keep provider order and overlays (one shared key) stay after every road. The network hands segments out in HashMap id order, which has nothing to do with height; sorting by storey makes paint order a fact about the network.
     */
    public static void sortForDraw(List<RoadElement> elements) {
        elements.sort(Comparator.comparingInt(RoadElement::drawOrder));
    }
}
