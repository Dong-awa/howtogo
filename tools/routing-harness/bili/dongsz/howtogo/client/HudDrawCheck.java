package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.road.RoadClass;
import bili.dongsz.howtogo.road.RoadNetwork;
import bili.dongsz.howtogo.road.RoadNode;
import bili.dongsz.howtogo.road.RoadSegment;

import java.util.ArrayList;
import java.util.List;

/**
 * Measures the stroke this mod's own maps draw: the main screen's viewer, the destination picker's
 * preview, the navigation panel's mini-map and the phone page all go through {@link HudDraw}.
 *
 * <h2>Why this is checked without a game</h2>
 * The stroke geometry is pure screen-space arithmetic -- points in, quads out -- and the two faults it
 * has been carrying are arithmetic, not taste. The first is that every triangle of a cap or a disc was
 * written as a degenerate quad with the <em>second</em> corner repeated, which fills the same triangle
 * once forwards and once backwards; these maps draw roads translucent, so the whole semicircle was
 * blended twice and read as a brighter patch at every stroke end and junction. The second is that a
 * bend too sharp to mitre used to have its mitre clamped instead of rounded, which leaves a point up to
 * three half widths long sticking out of the corner -- on the roads read out of other mods that is the
 * "spike" that appears as soon as the map is zoomed in far enough to draw the road at its full width.
 *
 * <p>So the checks are the two properties those faults broke: every point of the stroke is painted
 * once (not twice), and nothing is painted further from the centreline than a rounded joint may reach.
 * A screenshot would only add whether the result is liked.
 *
 * <p>{@link HudDraw} may be loaded here because its shared layer is built on first use rather than in
 * its class initialiser; see the note on {@code quads()} there. Nothing else in this check touches the
 * game.
 */
public final class HudDrawCheck {

    /** Half the width a road is stroked at on the viewer; the mid value of the four call sites. */
    private static final double HALF_WIDTH = 2.4;

    /** The colour and alpha the viewer passes, which is the translucent case the checks are about. */
    private static final int COLOUR = 0xFF3FD07A;
    private static final int ALPHA = 0xD0;

    /** How many times a point may be painted before a translucent road reads as a doubled edge. */
    private static final int ONCE = 1;

    /**
     * How far past the centreline a corner may reach, in half widths.
     *
     * <p>Two is the design limit -- see {@link StrokeOutline#MITER_LIMIT}, which states the same number
     * -- chosen because a mitre up to two half widths keeps a right angle square without becoming a
     * spike, and anything sharper is rounded instead. It is written out here rather than read from the
     * constant on purpose: this check exists because that limit was four once, and a check that follows
     * the value it is supposed to bound cannot notice it changing.
     */
    private static final double MAX_REACH_HALF_WIDTHS = 2.0;

    private static int checks;
    private static int failures;

    private HudDrawCheck() {
    }

    public static int[] run() {
        checks = 0;
        failures = 0;
        System.out.println("== the self-drawn maps' road stroke ==");
        straight();
        rightAngle();
        sharpTurn();
        hairpin();
        chain();
        degenerate();
        System.out.println(failures == 0 ? "  self-drawn stroke ok (" + checks + " checks)"
                : "  self-drawn stroke FAILED: " + failures + " of " + checks);
        return new int[]{checks, failures};
    }

    /** A straight road: one quad per edge, a cap at each end, and no pixel painted twice. */
    private static void straight() {
        System.out.println("   a straight road");
        Capture capture = stroke(path(0, 0, 200, 0));
        expect("the road is drawn at all", capture.vertexCount() > 0);
        expect("one quad per edge and six cap triangles at each end ("
                + capture.vertexCount() + " vertices)", capture.vertexCount() == 4 + 2 * 6 * 4);
        expect("every pixel of it is painted once (" + worstCoverage(capture, path(0, 0, 200, 0))
                + ")", worstCoverage(capture, path(0, 0, 200, 0)) == ONCE);
        expect("the width is the full width along it (" + percent(fullWidth(capture, path(0, 0, 200, 0)))
                + ")", fullWidth(capture, path(0, 0, 200, 0)) >= 0.95);
    }

    /**
     * A right-angled bend, which is a mitre: the two edges share one cross-section, so the corner stays
     * square, the stroke stays one width and nothing is painted twice.
     */
    private static void rightAngle() {
        System.out.println("   a right-angled bend");
        List<double[]> bend = path(0, 0, 200, 0, 200, 200);
        Capture capture = stroke(bend);
        expect("the bend is mitred, so the stroke is continuous and one width ("
                + percent(fullWidth(capture, bend)) + ")", fullWidth(capture, bend) >= 0.95);
        expect("nothing at the bend is painted twice (" + worstCoverage(capture, bend) + ")",
                worstCoverage(capture, bend) == ONCE);
        expect("and nothing reaches past the mitre limit ("
                + round(farthest(capture, bend) / HALF_WIDTH) + " half widths)",
                farthest(capture, bend) <= MAX_REACH_HALF_WIDTHS * HALF_WIDTH + 0.01);
    }

    /**
     * A hundred and forty degrees: the angle at which a mitre is nearly three half widths long, which is
     * the spike that appeared at sharp bends in the player's own road data once the map was zoomed in.
     * The corner has to be rounded instead, and every direction around the joint still painted, or the
     * rounding trades the spike for a notch.
     */
    private static void sharpTurn() {
        System.out.println("   a bend too sharp to mitre");
        List<double[]> bend = path(0, 0, 200, 0, 200 - 200 * Math.cos(Math.toRadians(40)),
                200 * Math.sin(Math.toRadians(40)));
        Capture capture = stroke(bend);
        expect("the corner is drawn at all", capture.vertexCount() > 0);
        expect("nothing sticks out further than a rounded joint may reach ("
                        + round(farthest(capture, bend) / HALF_WIDTH) + " half widths)",
                farthest(capture, bend) <= MAX_REACH_HALF_WIDTHS * HALF_WIDTH + 0.01);
        expect("the ground around the joint is filled in every direction, so rounding it left no notch",
                jointIsFilled(capture, bend.get(1)[0], bend.get(1)[1], HALF_WIDTH));
        expect("and the bend keeps the stroke's width (" + percent(fullWidth(capture, bend)) + ")",
                fullWidth(capture, bend) >= 0.95);
    }

    /** The shape with no bisector at all: the road doubles straight back on itself. */
    private static void hairpin() {
        System.out.println("   a hairpin");
        List<double[]> bend = path(0, 0, 200, 0, 2, 6);
        Capture capture = stroke(bend);
        expect("a hairpin has no spike either (" + round(farthest(capture, bend) / HALF_WIDTH)
                        + " half widths)",
                farthest(capture, bend) <= MAX_REACH_HALF_WIDTHS * HALF_WIDTH + 0.01);
        expect("and its point is filled in (" + percent(fullWidth(capture, bend)) + ")",
                fullWidth(capture, bend) >= 0.95);
    }

    /**
     * Two segments of one road meeting at a pass-through junction: what the maps actually hand over is a
     * network, and a road drawn in several clicks has to be stroked as one line, not two abutted ones.
     * One stroke means one cap at each end of the whole road and a mitre at the bend in between.
     */
    private static void chain() {
        System.out.println("   one road out of two segments");
        RoadNetwork network = new RoadNetwork();
        RoadNode start = network.addNode(0, 64, 0, RoadNode.Type.ENDPOINT, null);
        RoadNode middle = network.addNode(200, 64, 0, RoadNode.Type.JUNCTION, null);
        RoadNode end = network.addNode(200, 64, 200, RoadNode.Type.ENDPOINT, null);
        addSegment(network, RoadClass.ROAD, start, middle, 0, 0, 200, 0);
        addSegment(network, RoadClass.ROAD, middle, end, 200, 0, 200, 200);

        Capture capture = new Capture();
        HudDraw.emitNetwork(capture, network, 1, 0, 0, 0, 1, 0, HALF_WIDTH, ALPHA,
                -10_000, -10_000, 10_000, 10_000);
        expect("the two segments are stroked as one road, not two abutted ones ("
                        + capture.vertexCount() + " vertices)",
                capture.vertexCount() == 2 * 4 + 2 * 6 * 4);
        List<double[]> whole = path(0, 0, 200, 0, 200, 200);
        expect("and that one stroke is painted once (" + worstCoverage(capture, whole) + ")",
                worstCoverage(capture, whole) == ONCE);
    }

    /** Nothing to draw must draw nothing rather than throw. */
    private static void degenerate() {
        System.out.println("   degenerate input");
        expect("one point is not a line", stroke(path(5, 5)).vertexCount() == 0);
        Capture empty = new Capture();
        HudDraw.emitPath(empty, List.of(), 1, 0, 0, 0, 1, 0, HALF_WIDTH, COLOUR, ALPHA,
                -10, -10, 10, 10);
        expect("no points is not a line either", empty.vertexCount() == 0);
        Capture repeated = new Capture();
        HudDraw.emitPath(repeated,
                path(5, 5, 5, 5), 1, 0, 0, 0, 1, 0, HALF_WIDTH, COLOUR, ALPHA, -10, -10, 10, 10);
        expect("every point on top of every other is a dot, not a line", repeated.vertexCount() == 0);
        Capture noWidth = new Capture();
        HudDraw.emitPath(noWidth, path(0, 0, 100, 0), 1, 0, 0, 0, 1, 0, 0.0, COLOUR, ALPHA,
                -1000, -1000, 1000, 1000);
        expect("no width paints nothing", noWidth.vertexCount() == 0);
    }

    // -------------------------------------------------------------- drawing it

    /** A road's points as the maps hand them over: a list of (x, y) pairs. */
    private static List<double[]> path(double... xz) {
        List<double[]> points = new ArrayList<>(xz.length / 2);
        for (int i = 0; i + 1 < xz.length; i += 2) {
            points.add(new double[]{xz[i], xz[i + 1]});
        }
        return points;
    }

    /** Strokes one polyline with the viewer's own parameters and returns what was emitted. */
    private static Capture stroke(List<double[]> points) {
        Capture capture = new Capture();
        HudDraw.emitPath(capture, points, 1, 0, 0, 0, 1, 0, HALF_WIDTH, COLOUR, ALPHA,
                -10_000, -10_000, 10_000, 10_000);
        return capture;
    }

    private static void addSegment(RoadNetwork network, RoadClass roadClass,
                                   RoadNode from, RoadNode to, double... xz) {
        RoadSegment segment = network.newSegment(roadClass, from.y(), xz.length / 2);
        for (int i = 0; i + 1 < xz.length; i += 2) {
            segment.addVertex((int) xz[i], (int) xz[i + 1]);
        }
        segment.setFromNode(from.id());
        segment.setToNode(to.id());
        network.addSegment(segment);
    }

    /** What the stroke pass wrote: every vertex, in the order the buffer will draw it. */
    private static final class Capture implements HudDraw.VertexSink {

        private double[] values = new double[8 * 64];
        private int vertices;

        @Override
        public void vertex(double x, double y, int r, int g, int b, int a) {
            if (vertices * 2 + 2 > values.length) {
                values = java.util.Arrays.copyOf(values, values.length * 2);
            }
            values[vertices * 2] = x;
            values[vertices * 2 + 1] = y;
            vertices++;
        }

        int vertexCount() {
            return vertices;
        }

        /** The quads as the buffer holds them: four corners of (x, y) each. */
        double[] coordinates() {
            return java.util.Arrays.copyOf(values, vertices * 2);
        }
    }

    // ---------------------------------------------------------------- measuring

    /**
     * The least-covered cross-section along the line, as a fraction of the full width.
     *
     * <p>Sampled the same way the world map's check samples its stroke: several points across each edge,
     * away from the bends, so what is measured is one edge's own quad and not the join at its end.
     */
    private static double fullWidth(Capture capture, List<double[]> points) {
        double[] quads = capture.coordinates();
        double worst = 1.0;
        for (int edge = 0; edge + 1 < points.size(); edge++) {
            double x1 = points.get(edge)[0];
            double y1 = points.get(edge)[1];
            double x2 = points.get(edge + 1)[0];
            double y2 = points.get(edge + 1)[1];
            double dx = x2 - x1;
            double dy = y2 - y1;
            double length = Math.hypot(dx, dy);
            if (length < 1.0E-9) {
                continue;
            }
            double nx = -dy / length;
            double ny = dx / length;
            for (double along : new double[]{0.2, 0.4, 0.6, 0.8}) {
                int covered = 0;
                final int samples = 21;
                for (int sample = 0; sample < samples; sample++) {
                    double side = -0.96 + 1.92 * sample / (samples - 1.0);
                    double x = x1 + dx * along + nx * side * HALF_WIDTH;
                    double y = y1 + dy * along + ny * side * HALF_WIDTH;
                    if (StrokeCoverage.paints(quads, x, y)) {
                        covered++;
                    }
                }
                worst = Math.min(worst, covered / (double) samples);
            }
        }
        return worst;
    }

    /** How far the furthest emitted corner is from the centreline: a spike, in half widths. */
    private static double farthest(Capture capture, List<double[]> points) {
        double[] quads = capture.coordinates();
        double[] xs = new double[points.size()];
        double[] ys = new double[points.size()];
        for (int i = 0; i < points.size(); i++) {
            xs[i] = points.get(i)[0];
            ys[i] = points.get(i)[1];
        }
        double worst = 0.0;
        for (int i = 0; i + 1 < quads.length; i += 2) {
            worst = Math.max(worst,
                    StrokeCoverage.distanceToPath(xs, ys, xs.length, quads[i], quads[i + 1]));
        }
        return worst;
    }

    /**
     * Whether the ground all the way round a joint is painted.
     *
     * <p>Sixteen directions at nine tenths of a half width: a stroke that mitred the corner and one that
     * rounded it both cover the whole ring, but a join that painted only the outside of the turn -- or
     * only the two edges' own perpendiculars -- leaves a hole in it. That hole is what "rounded" must not
     * turn the spike into.
     */
    private static boolean jointIsFilled(Capture capture, double cx, double cy, double halfWidth) {
        double[] quads = capture.coordinates();
        for (int i = 0; i < 16; i++) {
            double angle = i * Math.PI * 2 / 16;
            double x = cx + Math.cos(angle) * halfWidth * 0.9;
            double y = cy + Math.sin(angle) * halfWidth * 0.9;
            if (!StrokeCoverage.paints(quads, x, y)) {
                return false;
            }
        }
        return true;
    }

    /** The most times any point in the stroke's box is painted. */
    private static int worstCoverage(Capture capture, List<double[]> points) {
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        for (double[] point : points) {
            minX = Math.min(minX, point[0]);
            minY = Math.min(minY, point[1]);
            maxX = Math.max(maxX, point[0]);
            maxY = Math.max(maxY, point[1]);
        }
        double margin = StrokeOutline.MITER_LIMIT * HALF_WIDTH + 2.0;
        return StrokeCoverage.worstCoverage(capture.coordinates(),
                minX - margin, minY - margin, maxX + margin, maxY + margin);
    }

    // ---------------------------------------------------------------- reporting

    private static void expect(String what, boolean ok) {
        checks++;
        if (ok) {
            System.out.println("   ok: " + what);
        } else {
            failures++;
            System.out.println("   FAIL: " + what);
        }
    }

    private static String percent(double fraction) {
        return Math.round(fraction * 1000.0) / 10.0 + "%";
    }

    private static String round(double value) {
        return Double.toString(Math.round(value * 100.0) / 100.0);
    }
}
