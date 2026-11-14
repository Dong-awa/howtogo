package bili.dongsz.howtogo.client;

/**
 * The outline of one road stroke: a screen-space polyline in, quads out.
 * Invariant: every point within halfWidth of the line is painted exactly once. Neither a bend, a
 * joint nor an end may thin the stroke, and no piece may be painted twice -- translucent colours
 * make a doubled pixel a brighter bead. A mitred joint shares one offset between its edges; every
 * fan piece covers its ground once.
 * A vertex offset always takes the same side of the direction of travel; the reversed perpendicular
 * made the last quad of every segment a bow-tie and left that edge half unpainted.
 * Screen space throughout; a triangle is a degenerate quad with the third corner repeated.
 */
public final class StrokeOutline {

    /**
     * How far a mitred joint may reach past the half width, as a multiple of it.
     * Two, not four: a mitre grows as halfWidth / cos(turn/2), reaching nearly three half widths at
     * 140 degrees, which reads as a spike once zoomed in. Past this limit the joint is rounded.
     */
    public static final double MITER_LIMIT = 2.0;

    /** Corners in one quad, and doubles in one quad's coordinates (x, y per corner). */
    public static final int CORNERS_PER_QUAD = 4;
    public static final int VALUES_PER_QUAD = CORNERS_PER_QUAD * 2;

    /** How many segments a round join's arc is drawn with. */
    private static final int JOIN_SEGMENTS = 6;

    /** The most points roundJoinWedge writes: one per arc end, plus the arc's own pieces. */
    public static final int MAX_JOIN_POINTS = JOIN_SEGMENTS + 1;

    /** How many segments an end cap is drawn with. */
    private static final int DISC_SEGMENTS = 10;

    /** A joint whose mitre would reach past MITER_LIMIT is joined round instead. */
    private static final double SHARP_JOINT = 1.0 / MITER_LIMIT;

    /** Below this a length is rounding noise rather than an edge, and is skipped. */
    private static final double EPSILON = 1.0E-9;

    private double[] values = new double[VALUES_PER_QUAD * 64];
    private int quads;

    // Per-vertex offsets into the scratch arrays: the cross-section of the edge arriving at the
    // vertex and of the edge leaving it. Equal wherever a mitre can serve; render-thread only, and
    // grown rather than reallocated when a longer road comes along.
    private double[] arrivingX = new double[64];
    private double[] arrivingY = new double[64];
    private double[] leavingX = new double[64];
    private double[] leavingY = new double[64];

    // One round join's boundary, filled and consumed one joint at a time; render-thread only.
    private final double[] joinXs = new double[MAX_JOIN_POINTS];
    private final double[] joinYs = new double[MAX_JOIN_POINTS];

    /** The quads this outline consists of: quadCount() of them, VALUES_PER_QUAD each. */
    public double[] coordinates() {
        return values;
    }

    /** How many quads the last build produced. */
    public int quadCount() {
        return quads;
    }

    /**
     * Builds the outline of one stroke.
     * The point arrays are read, never written, and must have at least count entries. A second call
     * replaces the previous outline: one scratch pad per frame avoids garbage the frame does not need.
     * @param halfWidth half the width to paint, in the same unit as the points
     */
    public void build(double[] xs, double[] zs, int count, double halfWidth) {
        quads = 0;
        if (xs == null || zs == null || count < 2 || !(halfWidth > 0.0)) {
            return;
        }
        count = Math.min(count, Math.min(xs.length, zs.length));
        if (count < 2) {
            return;
        }
        ensureCapacity(count);

        int firstEdge = firstDistinct(xs, zs, count, true);
        int lastEdge = firstDistinct(xs, zs, count, false);
        if (firstEdge < 0 || lastEdge < 0) {
            // Every point on top of every other: a dot, not a line.
            return;
        }

        // The two ends take the perpendicular of their own edge, on the same side as everything else.
        setOffset(0, xs[firstEdge] - xs[0], zs[firstEdge] - zs[0], halfWidth);
        leavingX[0] = arrivingX[0];
        leavingY[0] = arrivingY[0];
        setOffset(count - 1, xs[count - 1] - xs[lastEdge], zs[count - 1] - zs[lastEdge],
                halfWidth);
        leavingX[count - 1] = arrivingX[count - 1];
        leavingY[count - 1] = arrivingY[count - 1];

        for (int vertex = 1; vertex < count - 1; vertex++) {
            jointAt(xs, zs, vertex, halfWidth);
        }

        for (int i = 0; i < count - 1; i++) {
            double dx = xs[i + 1] - xs[i];
            double dy = zs[i + 1] - zs[i];
            if (Math.hypot(dx, dy) < EPSILON) {
                continue;
            }
            quad(
                    xs[i] + leavingX[i], zs[i] + leavingY[i],
                    xs[i + 1] + arrivingX[i + 1], zs[i + 1] + arrivingY[i + 1],
                    xs[i + 1] - arrivingX[i + 1], zs[i + 1] - arrivingY[i + 1],
                    xs[i] - leavingX[i], zs[i] - leavingY[i]);
        }
        cap(xs[0], zs[0], xs[0] - xs[firstEdge], zs[0] - zs[firstEdge], halfWidth);
        cap(xs[count - 1], zs[count - 1], xs[count - 1] - xs[lastEdge],
                zs[count - 1] - zs[lastEdge], halfWidth);
    }

    /** The offset of one edge at one of its ends, on the one side the whole stroke uses. */
    private void setOffset(int vertex, double dx, double dy, double halfWidth) {
        double length = Math.hypot(dx, dy);
        if (length < EPSILON) {
            arrivingX[vertex] = 0.0;
            arrivingY[vertex] = 0.0;
            return;
        }
        arrivingX[vertex] = -dy / length * halfWidth;
        arrivingY[vertex] = dx / length * halfWidth;
    }

    /**
     * The cross-section at one interior vertex: the mitre of both edges when it can serve, or one
     * along each edge's own perpendicular plus a round join when it cannot.
     */
    private void jointAt(double[] xs, double[] zs, int vertex, double halfWidth) {
        double previousX = xs[vertex] - xs[vertex - 1];
        double previousY = zs[vertex] - zs[vertex - 1];
        double nextX = xs[vertex + 1] - xs[vertex];
        double nextY = zs[vertex + 1] - zs[vertex];
        double previousLength = Math.hypot(previousX, previousY);
        double nextLength = Math.hypot(nextX, nextY);
        if (previousLength < EPSILON && nextLength < EPSILON) {
            arrivingX[vertex] = 0.0;
            arrivingY[vertex] = 0.0;
            leavingX[vertex] = 0.0;
            leavingY[vertex] = 0.0;
            return;
        }
        if (previousLength < EPSILON) {
            previousX = nextX;
            previousY = nextY;
            previousLength = nextLength;
        }
        if (nextLength < EPSILON) {
            nextX = previousX;
            nextY = previousY;
            nextLength = previousLength;
        }
        double normalPreviousX = -previousY / previousLength;
        double normalPreviousY = previousX / previousLength;
        double normalNextX = -nextY / nextLength;
        double normalNextY = nextX / nextLength;
        double sumX = normalPreviousX + normalNextX;
        double sumY = normalPreviousY + normalNextY;
        double sumLength = Math.hypot(sumX, sumY);
        if (sumLength >= 1.0E-6) {
            // The mitre direction is the bisector of the two normals; its length is the half width over the
            // cosine of half the turn.
            double mitreX = sumX / sumLength;
            double mitreY = sumY / sumLength;
            double cosine = mitreX * normalNextX + mitreY * normalNextY;
            if (cosine >= SHARP_JOINT) {
                double length = halfWidth / cosine;
                arrivingX[vertex] = mitreX * length;
                arrivingY[vertex] = mitreY * length;
                leavingX[vertex] = arrivingX[vertex];
                leavingY[vertex] = arrivingY[vertex];
                return;
            }
        }
        // Too sharp to mitre: each edge keeps its own perpendicular, and the wedge is filled by a round
        // join -- also the exact-reversal case, where the normals are the same line and no bisector exists.
        arrivingX[vertex] = normalPreviousX * halfWidth;
        arrivingY[vertex] = normalPreviousY * halfWidth;
        leavingX[vertex] = normalNextX * halfWidth;
        leavingY[vertex] = normalNextY * halfWidth;
        roundJoin(xs[vertex], zs[vertex], previousX, previousY, nextX, nextY, halfWidth);
    }

    /** Paints the ground one rounded joint leaves uncovered, one fan piece at a time. */
    private void roundJoin(double cx, double cy, double previousX, double previousY,
                           double nextX, double nextY, double halfWidth) {
        int points = roundJoinWedge(cx, cy, previousX, previousY, nextX, nextY, halfWidth,
                joinXs, joinYs);
        for (int i = 0; i + 1 < points; i++) {
            // A triangle as a degenerate quad with the third corner repeated: the second triangle has no
            // area, so the piece is painted once.
            quad(cx, cy, joinXs[i], joinYs[i], joinXs[i + 1], joinYs[i + 1],
                    joinXs[i + 1], joinYs[i + 1]);
        }
    }

    /**
     * The boundary of the ground a joint too sharp to mitre leaves uncovered.
     * A circular sector of radius halfWidth between the two cross-sections, not a whole disc: a disc
     * would cover ground already under both quads, and a translucent road mixed twice is a bead. It is
     * swept by the turn -- anticlockwise left, clockwise right -- and at an exact reversal the sweep is
     * half a turn. Points are written to the caller's arrays.
     * @param halfWidth the stroke's half width, and the sector's radius
     * @param outXs,outYs arrays of at least MAX_JOIN_POINTS doubles each
     * @return how many points were written, in fan order; fan them from (cx, cy)
     */
    public static int roundJoinWedge(double cx, double cy,
                                     double previousX, double previousY,
                                     double nextX, double nextY, double halfWidth,
                                     double[] outXs, double[] outYs) {
        double previousLength = Math.hypot(previousX, previousY);
        double nextLength = Math.hypot(nextX, nextY);
        if (previousLength < EPSILON || nextLength < EPSILON || !(halfWidth > 0.0)) {
            return 0;
        }
        double previousUnitX = previousX / previousLength;
        double previousUnitY = previousY / previousLength;
        double nextUnitX = nextX / nextLength;
        double nextUnitY = nextY / nextLength;
        double turn = previousUnitX * nextUnitY - previousUnitY * nextUnitX;
        // The side the corner falls away from: the outer cross-section rays, opposite the quad normals
        // for a left turn and the same side for a right one.
        double side = turn >= 0.0 ? -1.0 : 1.0;
        double startX = side * -previousUnitY;
        double startY = side * previousUnitX;
        double sweep = Math.atan2(turn, previousUnitX * nextUnitX + previousUnitY * nextUnitY);
        if (!(Math.abs(sweep) > 1.0E-6)) {
            return 0;
        }
        double start = Math.atan2(startY, startX);
        int pieces = Math.max(1, Math.min(JOIN_SEGMENTS,
                (int) Math.ceil(Math.abs(sweep) / (Math.PI / 6.0))));
        for (int i = 0; i <= pieces; i++) {
            double angle = start + sweep * i / (double) pieces;
            outXs[i] = cx + Math.cos(angle) * halfWidth;
            outYs[i] = cy + Math.sin(angle) * halfWidth;
        }
        return pieces + 1;
    }

    /** The index of the first (or last) point that is not on top of the one it is measured from. */
    private static int firstDistinct(double[] xs, double[] zs, int count, boolean fromStart) {
        if (fromStart) {
            for (int i = 1; i < count; i++) {
                if (Math.hypot(xs[i] - xs[0], zs[i] - zs[0]) > EPSILON) {
                    return i;
                }
            }
        } else {
            for (int i = count - 2; i >= 0; i--) {
                if (Math.hypot(xs[count - 1] - xs[i], zs[count - 1] - zs[i]) > EPSILON) {
                    return i;
                }
            }
        }
        return -1;
    }

    /**
     * A semicircular cap at one end, pointing outward along (dx, dy).
     * Half a disc, not a whole one: the cap owns only the ground past the end, so it never doubles up
     * on its own stroke; at a joint the two caps are the two halves of one round cap.
     */
    private void cap(double x, double y, double dx, double dy, double halfWidth) {
        double length = Math.hypot(dx, dy);
        if (length < EPSILON) {
            return;
        }
        double base = Math.atan2(dy / length, dx / length);
        for (int i = 0; i < DISC_SEGMENTS; i++) {
            double from = base - Math.PI / 2.0 + i * Math.PI / DISC_SEGMENTS;
            double to = base - Math.PI / 2.0 + (i + 1) * Math.PI / DISC_SEGMENTS;
            double x0 = x + Math.cos(from) * halfWidth;
            double y0 = y + Math.sin(from) * halfWidth;
            double x1 = x + Math.cos(to) * halfWidth;
            double y1 = y + Math.sin(to) * halfWidth;
            // The third corner repeated: one triangle, painted once -- repeating the second fills it twice.
            quad(x, y, x0, y0, x1, y1, x1, y1);
        }
    }

    private void quad(double ax, double ay, double bx, double by, double cx, double cy,
                      double dx, double dy) {
        if (values.length < (quads + 1) * VALUES_PER_QUAD) {
            double[] grown = new double[Math.max(values.length * 2, (quads + 1) * VALUES_PER_QUAD)];
            System.arraycopy(values, 0, grown, 0, quads * VALUES_PER_QUAD);
            values = grown;
        }
        int at = quads * VALUES_PER_QUAD;
        values[at] = ax;
        values[at + 1] = ay;
        values[at + 2] = bx;
        values[at + 3] = by;
        values[at + 4] = cx;
        values[at + 5] = cy;
        values[at + 6] = dx;
        values[at + 7] = dy;
        quads++;
    }

    private void ensureCapacity(int count) {
        if (count <= arrivingX.length) {
            return;
        }
        int capacity = Math.max(count, arrivingX.length * 2);
        arrivingX = new double[capacity];
        arrivingY = new double[capacity];
        leavingX = new double[capacity];
        leavingY = new double[capacity];
    }
}
