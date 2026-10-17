package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.road.RoadCurve;

import java.util.ArrayList;
import java.util.List;

/**
 * Measures the world map's road stroke: is a road painted at its full width, everywhere along it?
 *
 * <p>The shape of a road stroke -- an offset for each side of the line, a join at each bend, a cap at
 * each end -- is {@link StrokeOutline}'s, which is pure screen-space geometry and can therefore be
 * checked without a game. This check rasterises what it returns the same way a {@code QUADS} buffer
 * is drawn -- each quad as its two triangles -- and samples across the stroke to see how much of the
 * nominal width actually lands on the map.
 *
 * <p>What it exists for: the stroke used to take the perpendicular of the <em>reversed</em> outgoing
 * direction at the end of every road segment, the opposite side of the same line, which turned the
 * last quad of every segment into a bow-tie. A bow-tie is drawn as two triangles that miss roughly
 * half the edge, so every segment of every road was half-width for a stretch before its junction --
 * the "roads suddenly get thinner" this class now keeps from coming back.
 *
 * <p>Same arrangement as the other checks: the mod's own code compiled without a game. What it
 * cannot say is that the result looks right; what it can say is that the geometry covers the ground
 * the line says it should, and that nothing is painted far away from it.
 */
public final class StrokeOutlineCheck {

    /** Samples across a cross-section, from one side of the stroke to the other. */
    private static final int CROSS_SAMPLES = 21;
    /**
     * How much of a cross-section has to be painted to count as full. Not quite one: the samples at
     * the very edge of the width land on the boundary of the geometry, where floating point decides.
     */
    private static final double FULL = 0.98;

    private static int checks;
    private static int failures;

    private StrokeOutlineCheck() {
    }

    public static int[] run() {
        checks = 0;
        failures = 0;
        System.out.println("== road stroke geometry ==");
        straight();
        bends();
        ends();
        bounded();
        segments();
        degenerate();
        System.out.println(failures == 0 ? "  stroke geometry ok (" + checks + " checks)"
                : "  stroke geometry FAILED: " + failures + " of " + checks);
        return new int[]{checks, failures};
    }

    /** A straight road: the full width from one end to the other, and no thinning in the middle. */
    private static void straight() {
        System.out.println("   a straight road");
        double[] xs = {0, 60, 130, 200};
        double[] ys = {0, 0, 0, 0};
        double halfWidth = 4.0;
        double[] quads = outline(xs, ys, halfWidth);
        expect("a straight road is drawn at all", quads.length > 0);
        double worst = worstCrossSection(quads, xs, ys, halfWidth);
        expect("and the width is the full width everywhere (" + percent(worst) + ")", worst >= FULL);
        expect("with one quad per edge and a round cap on each end ("
                + quadCount() + " quads)", quadCount() == (xs.length - 1) + 20);
    }

    /**
     * Every bend, from a gentle curve to a hairpin.
     *
     * <p>A mitred bend shares one offset between its two edges, which is what keeps it one width with
     * no gap and no overlap; a bend past the mitre limit takes one cross-section per edge and a round
     * join, which is the ground a round pen would cover. Both have to come out full width, and a
     * hairpin -- the shape that used to be drawn as a sliver -- has to as well.
     */
    private static void bends() {
        System.out.println("   bends of every angle");
        double halfWidth = 4.0;
        double[] turns = {5, 20, 45, 60, 90, 120, 140, 150, 155, 160, 168, 175, 178, 179.5};
        double worstOverall = 1.0;
        double worstTurn = 0.0;
        boolean allDrawn = true;
        for (double turn : turns) {
            double radians = Math.toRadians(turn);
            double[] xs = {0, 120, 120 + 120 * Math.cos(radians)};
            double[] ys = {0, 0, 120 * Math.sin(radians)};
            double[] quads = outline(xs, ys, halfWidth);
            allDrawn &= quads.length > 0;
            double worst = worstCrossSection(quads, xs, ys, halfWidth);
            if (worst < worstOverall) {
                worstOverall = worst;
                worstTurn = turn;
            }
        }
        expect("a bend is drawn at every angle", allDrawn);
        expect("and the width is full at every one (worst " + percent(worstOverall) + " at "
                + round(worstTurn) + " degrees)", worstOverall >= FULL);

        // The exact reversal: the one angle with no bisector at all. The two edges have their own
        // perpendiculars and the join is a disc, so the ground around the vertex is still covered.
        double[] xs = {0, 100, 0};
        double[] ys = {0, 0, 0};
        double[] roundTrip = outline(xs, ys, halfWidth);
        double worst = worstCrossSection(roundTrip, xs, ys, halfWidth);
        expect("a road doubling straight back keeps its width too (" + percent(worst) + ")",
                worst >= FULL);
    }

    /**
     * The two ends: the ground past each one is painted, because the next segment's end meets it.
     *
     * <p>Half a disc, not a whole one: a cap that only adds the ground past the end paints every
     * pixel once, and two of them at a joint fill the wedge on the outside of it.
     */
    private static void ends() {
        System.out.println("   the two ends");
        double halfWidth = 4.0;
        double[] xs = {0, 100};
        double[] ys = {0, 0};
        double[] quads = outline(xs, ys, halfWidth);
        expect("the ground half a half-width past the start is painted",
                painted(quads, -2.0, 0.0));
        expect("and the same past the end", painted(quads, 102.0, 0.0));
        expect("the corner a square end would leave empty is painted",
                painted(quads, -2.5, 2.5));
        expect("but the cap stops at the half width", !painted(quads, -6.0, 0.0));
        expect("and does not paint beside the stroke", !painted(quads, -2.0, 4.6));
    }

    /**
     * Nothing is painted far from the line.
     *
     * <p>A mitre may reach past the half width -- that is what makes the outside of a bend square --
     * but only as far as the mitre limit, and nothing else may go anywhere at all. A stray corner
     * here is a spike on the map.
     */
    private static void bounded() {
        System.out.println("   how far the geometry may reach");
        double halfWidth = 4.0;
        // A right-angled bend is the furthest an ordinary mitre has to reach at all, and a hairpin the
        // furthest the sharp-join case does.
        double[][] cases = {
                {0, 100, 100, 0, 0, 100},
                {0, 100, 0, 0, 0, 0.5},
        };
        double worst = 0.0;
        boolean drawn = true;
        for (double[] path : cases) {
            double[] xs = {path[0], path[1], path[2]};
            double[] ys = {path[3], path[4], path[5]};
            double[] quads = outline(xs, ys, halfWidth);
            drawn &= quads.length > 0;
            for (int at = 0; at < quads.length; at += 2) {
                worst = Math.max(worst, distanceToPath(xs, ys, quads[at], quads[at + 1]));
            }
        }
        expect("the stroke is drawn", drawn);
        expect("every corner stays inside the mitre limit of the line ("
                + round(worst / halfWidth) + " half widths)",
                worst <= StrokeOutline.MITER_LIMIT * halfWidth + 0.01);
    }

    /**
     * The shape the map actually draws: a road read out of the world one block at a time, smoothed
     * into a display curve, then split into the segments the road network breaks it at -- each of
     * which is its own map element, stroked on its own.
     *
     * <p>This is the arrangement the thinning bug lived in, and it has to be measured per segment
     * rather than over the whole road: the fault was at the far end of a segment, so a stroke that
     * stopped at the junction is what hid it.
     */
    private static void segments() {
        System.out.println("   the segments the map really draws");
        List<double[]> road = blockRoad(400);
        double halfWidth = 2.55;
        double scale = 4.0;
        int strokes = 0;
        double worst = 1.0;
        double worstAt = 0;
        for (int first = 0; first + 1 < road.size(); first += 14) {
            int last = Math.min(first + 14, road.size() - 1);
            List<double[]> blockPoints = road.subList(first, last + 1);
            RoadCurve curve = RoadCurve.smooth(blockPoints, RoadCurve.GRID_TOLERANCE);
            List<double[]> drawn = curve != null ? curvePoints(curve) : blockPoints;
            if (drawn.size() < 2) {
                continue;
            }
            double[] xs = new double[drawn.size()];
            double[] ys = new double[drawn.size()];
            for (int i = 0; i < drawn.size(); i++) {
                xs[i] = drawn.get(i)[0] * scale;
                ys[i] = drawn.get(i)[1] * scale;
            }
            double[] quads = outline(xs, ys, halfWidth);
            if (quads.length == 0) {
                continue;
            }
            strokes++;
            double worstHere = worstCrossSection(quads, xs, ys, halfWidth);
            if (worstHere < worst) {
                worst = worstHere;
                worstAt = first;
            }
        }
        expect("the road is smoothed into drawable segments (" + strokes + ")", strokes > 20);
        expect("and every one of them is drawn at the full width (worst " + percent(worst)
                + ", segment at block " + worstAt + ")", worst >= FULL);
    }

    /** Degenerate input: no points, one point, every point on top of every other, no width. */
    private static void degenerate() {
        System.out.println("   degenerate input");
        expect("two points are the least that can be a line",
                build(new double[]{3, 3}, new double[]{4, 5}, 2.0) > 0);
        expect("one point is not a line",
                build(new double[]{3}, new double[]{4}, 2.0) == 0);
        expect("every point on top of every other is not a line either",
                build(new double[]{7, 7, 7, 7}, new double[]{9, 9, 9, 9}, 2.0) == 0);
        expect("no width paints nothing", build(new double[]{0, 10}, new double[]{0, 0}, 0.0) == 0);
        expect("a negative width paints nothing",
                build(new double[]{0, 10}, new double[]{0, 0}, -2.0) == 0);

        // A stroke with a repeated point in the middle: the duplicate has no edge of its own, but the
        // two edges either side of it are still one continuous stroke of one width.
        double[] xs = {0, 0, 50, 100};
        double[] ys = {0, 0, 0, 0};
        double[] quads = outline(xs, ys, 3.0);
        double worst = worstCrossSection(quads, xs, ys, 3.0);
        expect("a repeated point does not pinch the stroke (" + percent(worst) + ")", worst >= FULL);
    }

    // ---------------------------------------------------------------- measuring

    /** The outline of one stroke, as quads of four corners each. */
    private static double[] outline(double[] xs, double[] ys, double halfWidth) {
        build(xs, ys, halfWidth);
        double[] values = new double[quadCount() * StrokeOutline.VALUES_PER_QUAD];
        System.arraycopy(LAST.coordinates(), 0, values, 0, values.length);
        return values;
    }

    private static final StrokeOutline LAST = new StrokeOutline();

    private static int build(double[] xs, double[] ys, double halfWidth) {
        LAST.build(xs, ys, xs.length, halfWidth);
        return LAST.quadCount();
    }

    private static int quadCount() {
        return LAST.quadCount();
    }

    /**
     * The least-covered cross-section found along the whole line: for each edge, several points
     * across it, and across each of those the stroke from one side to the other.
     */
    private static double worstCrossSection(double[] quads, double[] xs, double[] ys,
                                            double halfWidth) {
        double worst = 1.0;
        for (int i = 0; i < xs.length - 1; i++) {
            double dx = xs[i + 1] - xs[i];
            double dy = ys[i + 1] - ys[i];
            double length = Math.hypot(dx, dy);
            if (length < 1.0E-9) {
                continue;
            }
            double nx = -dy / length;
            double ny = dx / length;
            for (double along : new double[]{0.2, 0.4, 0.6, 0.8}) {
                int covered = 0;
                for (int sample = 0; sample < CROSS_SAMPLES; sample++) {
                    double side = -0.96 + 1.92 * sample / (CROSS_SAMPLES - 1.0);
                    double x = xs[i] + dx * along + nx * side * halfWidth;
                    double y = ys[i] + dy * along + ny * side * halfWidth;
                    if (painted(quads, x, y)) {
                        covered++;
                    }
                }
                worst = Math.min(worst, covered / (double) CROSS_SAMPLES);
            }
        }
        return worst;
    }

    /**
     * Is this point painted by any of the quads?
     *
     * <p>Exactly the triangles a {@code QUADS} buffer draws for one quad: its first three corners, and
     * its first, third and fourth. For a quad with a corner repeated that is one triangle or the same
     * triangle both ways round; for a stroke quad it is the two halves of the quad.
     */
    private static boolean painted(double[] quads, double x, double y) {
        for (int at = 0; at + StrokeOutline.VALUES_PER_QUAD <= quads.length;
             at += StrokeOutline.VALUES_PER_QUAD) {
            double ax = quads[at];
            double ay = quads[at + 1];
            double bx = quads[at + 2];
            double by = quads[at + 3];
            double cx = quads[at + 4];
            double cy = quads[at + 5];
            double dx = quads[at + 6];
            double dy = quads[at + 7];
            if (inTriangle(x, y, ax, ay, bx, by, cx, cy)
                    || inTriangle(x, y, ax, ay, cx, cy, dx, dy)) {
                return true;
            }
        }
        return false;
    }

    /** Point in triangle, boundary included, for either winding. */
    private static boolean inTriangle(double px, double py, double ax, double ay, double bx, double by,
                                      double cx, double cy) {
        double ab = (px - bx) * (ay - by) - (ax - bx) * (py - by);
        double bc = (px - cx) * (by - cy) - (bx - cx) * (py - cy);
        double ca = (px - ax) * (cy - ay) - (cx - ax) * (py - ay);
        boolean negative = ab < 0 || bc < 0 || ca < 0;
        boolean positive = ab > 0 || bc > 0 || ca > 0;
        return !(negative && positive);
    }

    private static double distanceToPath(double[] xs, double[] ys, double x, double y) {
        double best = Double.MAX_VALUE;
        for (int i = 0; i < xs.length - 1; i++) {
            best = Math.min(best, distanceToEdge(xs[i], ys[i], xs[i + 1], ys[i + 1], x, y));
        }
        return best;
    }

    private static double distanceToEdge(double ax, double ay, double bx, double by, double x,
                                         double y) {
        double dx = bx - ax;
        double dy = by - ay;
        double lengthSq = dx * dx + dy * dy;
        double along = lengthSq > 0 ? ((x - ax) * dx + (y - ay) * dy) / lengthSq : 0.0;
        along = Math.max(0.0, Math.min(1.0, along));
        return Math.hypot(ax + dx * along - x, ay + dy * along - y);
    }

    /**
     * A road as the world stores one: a winding line of whole blocks, which is a staircase of small
     * turns rather than a smooth curve.
     */
    private static List<double[]> blockRoad(int blocks) {
        List<double[]> points = new ArrayList<>(blocks + 1);
        points.add(new double[]{0, 0});
        double x = 0;
        double z = 0;
        for (int i = 0; i < blocks; i++) {
            double angle = 0.7 * Math.sin(i / 23.0) + 0.35 * Math.sin(i / 7.0);
            x += Math.cos(angle);
            z += Math.sin(angle);
            points.add(new double[]{Math.round(x), Math.round(z)});
        }
        return points;
    }

    private static List<double[]> curvePoints(RoadCurve curve) {
        List<double[]> points = new ArrayList<>(curve.size());
        for (int i = 0; i < curve.size(); i++) {
            points.add(new double[]{curve.x(i), curve.z(i)});
        }
        return points;
    }

    // ---------------------------------------------------------------- reporting

    private static void expect(String what, boolean ok) {
        checks++;
        if (!ok) {
            failures++;
            System.out.println("   FAIL: " + what);
        } else {
            System.out.println("   ok: " + what);
        }
    }

    private static String percent(double fraction) {
        return Math.round(fraction * 1000.0) / 10.0 + "%";
    }

    private static String round(double value) {
        return Double.toString(Math.round(value * 100.0) / 100.0);
    }
}
