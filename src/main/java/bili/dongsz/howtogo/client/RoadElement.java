package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.road.RoadSegment;

/**
 * One map element.
 *
 * <p>Normally this wraps a single road polyline, so a whole road is drawn in one call and the
 * per-element overhead is paid once per road rather than once per vertex.
 *
 * <p>There are also three singleton overlays. Xaero calls renderers in order and we are the only
 * renderer in our layer, so appending these last draws them on top of the roads without needing a
 * second renderer/reader pair:
 * <ul>
 *   <li>{@link #EDIT_UI} - node handles, snap indicator and rubber band, only while editing</li>
 *   <li>{@link #ROUTE} - the active navigation route, drawn whenever a destination is set</li>
 *   <li>{@link #LABELS} - road and place names, always, and last so text is never painted over</li>
 * </ul>
 */
public final class RoadElement {

    public enum Kind {
        ROAD,
        EDIT_UI,
        ROUTE,
        LABELS
    }

    private final Kind kind;
    private final RoadSegment segment;

    /** Anchor for overlays; kept at the map camera so pose translations stay small. */
    private double overlayAnchorX;
    private double overlayAnchorZ;

    private RoadElement(Kind kind, RoadSegment segment) {
        this.kind = kind;
        this.segment = segment;
    }

    public static RoadElement of(RoadSegment segment) {
        return new RoadElement(Kind.ROAD, segment);
    }

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

    /** Anchor X used for culling and for Xaero's element placement. */
    public double anchorX() {
        return kind == Kind.ROAD ? segment.x(0) : overlayAnchorX;
    }

    /** Anchor Z used for culling and for Xaero's element placement. */
    public double anchorZ() {
        return kind == Kind.ROAD ? segment.z(0) : overlayAnchorZ;
    }
}
