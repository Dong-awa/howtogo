package bili.dongsz.howtogo.client;

/**
 * The outline of one road stroke: a screen-space polyline in, quads out.
 *
 * <h2>Why the arithmetic lives in its own class</h2>
 * The world map draws a road as a thick line, and the shape of that line -- where the two sides go,
 * how two edges meet at a bend, what happens past the two ends -- used to be worked out inside the
 * method that also wrote the vertices. Nothing that writes vertices can be checked without a game,
 * and the shape had a bug a check would have caught, so the arithmetic is here: the renderer
 * transforms a road to screen coordinates, hands the points over, and writes whatever quads come
 * back. The harness measures these quads directly (see {@code StrokeOutlineCheck}).
 *
 * <h2>The invariant: one width, painted once</h2>
 * Every point within {@code halfWidth} of the line is painted, and painted once. Neither a bend, nor
 * a joint, nor an end may thin the stroke, and no part of it may be painted twice: road colours can
 * be configured translucent, and a doubled pixel of the same colour reads as a darker bead. That
 * second half is why the joins are mitred rather than patched: two edges that share one offset meet
 * along a common edge instead of overlapping.
 *
 * <h2>One side of the road, for the whole road</h2>
 * A vertex offset is always taken as the same side of the direction of travel -- the same side of
 * the road, in other words. That sounds like a formality and was the bug: an end vertex used to take
 * the perpendicular of the <em>reversed</em> outgoing direction, which is the same line but the
 * opposite side of it. Paired with the mitre of the vertex before it, that made the final quad of
 * every segment a bow-tie, and a bow-tie drawn through a {@code QUADS} buffer as its two triangles
 * leaves roughly half of that edge unpainted -- read on the map as the road suddenly narrowing a
 * little before every junction.
 *
 * <h2>Joints</h2>
 * An ordinary bend shares one offset between its two edges -- the mitre, {@code halfWidth /
 * cos(turn/2)} long, longer than the half width so the outside of the bend stays square. Two edges
 * too sharp for that (past {@link #MITER_LIMIT}) are given a cross-section each, along their own
 * perpendiculars, and a round join is painted into the wedge between them. A mitre at such an angle
 * would be a spike several widths long, and forcing a shared cross-section from either edge would
 * leave the other edge measured across its own length -- which is the sliver the old code drew at
 * hairpins. Two separate cross-sections plus a disc is exactly the ground a round pen would cover,
 * and is what a hairpin is.
 *
 * <h2>Ends, and the joints between strokes</h2>
 * Each road segment is its own map element, so it has two butt ends that another stroke meets. The
 * ends are capped with a semicircle pointing outward, never a full disc: a full one would lie half
 * on top of its own stroke, and that overlap was the line of darker beads a translucent road used to
 * have. A half disc only adds the ground past the end, so the two of them at a joint cover the wedge
 * on the outside of it while every pixel they paint is painted once.
 *
 * <h2>Coordinates</h2>
 * Everything here is in the renderer's local screen space: the points are where the road lands on the
 * map, and {@code halfWidth} is the same unit. A quad is four corners in the winding a {@code QUADS}
 * buffer triangulates as {@code (0,1,2)} and {@code (0,2,3)}; a degenerate quad (its last corner
 * repeated) therefore draws one triangle or fills the same fan both ways round, which is how the
 * round joins and caps are written.
 */
public final class StrokeOutline {

    /** How far a mitred joint may reach past the half width, as a multiple of it. */
    public static final double MITER_LIMIT = 4.0;

    /** Corners in one quad, and doubles in one quad's coordinates (x, y per corner). */
    public static final int CORNERS_PER_QUAD = 4;
    public static final int VALUES_PER_QUAD = CORNERS_PER_QUAD * 2;

    /** How many segments a round join or an end cap is drawn with. */
    private static final int DISC_SEGMENTS = 10;

    /** A joint whose mitre would reach past {@link #MITER_LIMIT} is joined round instead. */
    private static final double SHARP_JOINT = 1.0 / MITER_LIMIT;

    /** Below this a length is rounding noise rather than an edge, and is skipped. */
    private static final double EPSILON = 1.0E-9;

    private double[] values = new double[VALUES_PER_QUAD * 64];
    private int quads;

    // Per-vertex offsets into the scratch arrays: the cross-section of the edge arriving at the
    // vertex, and of the edge leaving it. They are equal at every joint a mitre can serve, and the
    // two differ only at a joint too sharp to mitre. Render-thread only, like the arrays they are
    // filled from; grown rather than reallocated when a longer road comes along.
    private double[] arrivingX = new double[64];
    private double[] arrivingY = new double[64];
    private double[] leavingX = new double[64];
    private double[] leavingY = new double[64];
    private boolean[] roundJoin = new boolean[64];

    /** The quads this outline consists of: {@link #quadCount()} of them, {@link #VALUES_PER_QUAD} each. */
    public double[] coordinates() {
        return values;
    }

    /** How many quads the last {@link #build} produced. */
    public int quadCount() {
        return quads;
    }

    /**
     * Builds the outline of one stroke.
     *
     * <p>The point arrays are read, never written, and must have at least {@code count} entries in
     * each. A second call replaces the previous outline: the class is one scratch pad for one road
     * at a time, because the map redraws the same roads every frame and allocating an outline per
     * road would be garbage the frame does not need.
     *
     * @param xs        screen x of each point
     * @param zs        screen y of each point
     * @param count     how many points of the arrays belong to this stroke
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
        roundJoin[0] = false;
        setOffset(count - 1, xs[count - 1] - xs[lastEdge], zs[count - 1] - zs[lastEdge],
                halfWidth);
        leavingX[count - 1] = arrivingX[count - 1];
        leavingY[count - 1] = arrivingY[count - 1];
        roundJoin[count - 1] = false;

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
        for (int vertex = 1; vertex < count - 1; vertex++) {
            if (roundJoin[vertex]) {
                disc(xs[vertex], zs[vertex], halfWidth);
            }
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
     * The cross-section at one interior vertex: the mitre of the two edges when it can serve both,
     * or one along each edge's own perpendicular when it cannot.
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
            roundJoin[vertex] = false;
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
            // The mitre direction is the bisector of the two normals; the length that puts it on both
            // edges' outer side is the half width divided by the cosine of half the turn.
            double mitreX = sumX / sumLength;
            double mitreY = sumY / sumLength;
            double cosine = mitreX * normalNextX + mitreY * normalNextY;
            if (cosine >= SHARP_JOINT) {
                double length = Math.min(halfWidth / cosine, halfWidth * MITER_LIMIT);
                arrivingX[vertex] = mitreX * length;
                arrivingY[vertex] = mitreY * length;
                leavingX[vertex] = arrivingX[vertex];
                leavingY[vertex] = arrivingY[vertex];
                roundJoin[vertex] = false;
                return;
            }
        }
        // Too sharp to mitre: each edge keeps its own perpendicular, and the wedge between them is
        // filled by a round join. This is also the exact-reversal case, where the two normals are the
        // same line and no bisector exists at all.
        arrivingX[vertex] = normalPreviousX * halfWidth;
        arrivingY[vertex] = normalPreviousY * halfWidth;
        leavingX[vertex] = normalNextX * halfWidth;
        leavingY[vertex] = normalNextY * halfWidth;
        roundJoin[vertex] = true;
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

    /** A filled circle: the round join of a joint too sharp to mitre. */
    private void disc(double cx, double cy, double radius) {
        for (int i = 0; i < DISC_SEGMENTS; i++) {
            double from = i * 2.0 * Math.PI / DISC_SEGMENTS;
            double to = (i + 1) * 2.0 * Math.PI / DISC_SEGMENTS;
            double x0 = cx + Math.cos(from) * radius;
            double y0 = cy + Math.sin(from) * radius;
            double x1 = cx + Math.cos(to) * radius;
            double y1 = cy + Math.sin(to) * radius;
            // The last corner repeated twice: one triangle, drawn both ways round.
            quad(cx, cy, x0, y0, x1, y1, x0, y0);
        }
    }

    /**
     * A semicircular cap at one end of the stroke, pointing outward along (dx, dy).
     *
     * <p>Half a disc, not a whole one: the cap owns only the ground past the end, so it never doubles
     * up on the stroke it belongs to. At a joint the two ends' caps are the two halves of one round
     * cap, which is exactly what a line drawn end to end looks like.
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
            quad(x, y, x0, y0, x1, y1, x0, y0);
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
        roundJoin = new boolean[capacity];
    }
}
