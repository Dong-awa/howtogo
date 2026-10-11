package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.road.RoadSegment;

import java.util.Comparator;
import java.util.List;

/**
 * One map element.
 *
 * <p>Normally this wraps a single road polyline, so a whole road is drawn in one call and the
 * per-element overhead is paid once per road rather than once per vertex.
 *
 * <p>There are also four singleton overlays. Xaero calls renderers in order and we are the only
 * renderer in our layer, so appending these last draws them on top of the roads without needing a
 * second renderer/reader pair:
 * <ul>
 *   <li>{@link #MARKS} - the track MTR's lines run along, drawn as the polylines the lines are drawn
 *       from rather than as one element per rail</li>
 *   <li>{@link #EDIT_UI} - node handles, snap indicator and rubber band, only while editing</li>
 *   <li>{@link #ROUTE} - the active navigation route, drawn whenever a destination is set</li>
 *   <li>{@link #LABELS} - road and place names, always, and last so text is never painted over</li>
 * </ul>
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

    /** Anchor X used for culling and for Xaero's element placement. */
    public double anchorX() {
        return kind == Kind.ROAD ? segment.x(0) : overlayAnchorX;
    }

    /** Anchor Z used for culling and for Xaero's element placement. */
    public double anchorZ() {
        return kind == Kind.ROAD ? segment.z(0) : overlayAnchorZ;
    }

    /**
     * Where this element sits in the paint order: the storey of the road it draws, or after every road
     * for the overlay elements.
     *
     * <p>A storey is a height, and a flat map has exactly one way to show which of two roads is
     * overhead: paint the lower one first. So this is the key the roads are sorted by, and it is the
     * only thing about an element's place in the frame that is not simply "the order it was added".
     */
    private int drawOrder() {
        return kind == Kind.ROAD ? segment.layer() : Integer.MAX_VALUE;
    }

    /**
     * Puts a frame's elements in the order the map paints them: road by road, from the lowest storey up,
     * with the overlays still last.
     *
     * <h2>Why the sort is stable, and why the overlays are in the list at all</h2>
     * Two roads on the same storey have no reason to be reordered, and Xaero draws the elements in the
     * order the provider hands them over, so a stable sort keeps the frame identical to what it was
     * before this existed -- same storey, same order, same picture -- while moving only the roads that
     * were being painted on the wrong level. The overlays all share the same key, so the sort leaves
     * them exactly where the provider put them: after every road, in their documented order. That is
     * what keeps this one change from turning rubber bands, routes or names into things a bridge can
     * cover.
     *
     * <h2>What it fixes</h2>
     * The network hands out its segments in the order its id-keyed {@code HashMap} iterates, which has
     * nothing to do with height: whether a bridge came out over the road it crosses or under it was
     * decided by which of the two segment ids hashed first. Sorting by storey makes the paint order a
     * fact about the network rather than about the enumerator.
     *
     * @param elements the frame's elements, reordered in place
     */
    public static void sortForDraw(List<RoadElement> elements) {
        elements.sort(Comparator.comparingInt(RoadElement::drawOrder));
    }
}
