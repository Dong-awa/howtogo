package bili.dongsz.howtogo.road;

import java.util.ArrayList;
import java.util.List;

/**
 * A smoothed centreline for drawing a road, stored beside the block vertices.
 *
 * <h2>Why a road needs a second shape</h2>
 * The vertices a road is stored with are the blocks the player clicked or the ones another mod
 * sampled: a staircase of block centres. That is the right data to route on -- a junction is a node
 * and a node is a block position -- but it is the wrong data to look at. Drawn as it is, a bend is a
 * series of corners, and a line read out of another mod can be hundreds of one-block steps, which
 * reads as a jagged, broken road however thick it is drawn.
 *
 * <p>So a road may carry this record as well: a smoothed list of world points, worked out once when
 * the road is built, that the views draw instead of the staircase. It is <b>display only</b>. Routing,
 * lengths, hit tests and the stored block vertices all stay on the integer geometry, so a curve can
 * never make two roads connect that the data says do not, and no route is measured along it. The
 * record is persisted with the road (see {@code RoadStorage}) and absent from every road that never
 * had one, which keeps every existing file byte-compatible.
 *
 * <h2>How the smoothing keeps the road's shape</h2>
 * Two steps, deliberately: simplify, then round. Simplifying with Douglas-Peucker at a fraction of a
 * block drops the points that only encode the block grid, while keeping every point that turns; then
 * one or two Chaikin passes replace each corner with two points at a quarter and three quarters along
 * its two edges, which rounds it without moving the line away from the road by more than that
 * fraction of the corner. The tolerance is small on purpose -- this is a road, not a sketch -- and the
 * endpoints are pinned so a curve never stops short of the junction it belongs to.
 */
public record RoadCurve(double[] xs, double[] zs) {

    /** How far the simplified line may differ from the block line, in blocks. */
    private static final double SIMPLIFY_TOLERANCE = 0.45;

    /** Chaikin's corner-cutting ratio: where along each edge the two new corner points sit. */
    private static final double CHAIKIN_RATIO = 0.25;

    /** Rounds per corner. Two is enough to read as a curve at any zoom the maps are used at. */
    private static final int CHAIKIN_PASSES = 2;

    /** Hard cap on a curve's points, so a pathological polyline cannot grow without bound. */
    private static final int MAX_POINTS = 4096;

    public RoadCurve {
        if (xs == null || zs == null || xs.length != zs.length) {
            throw new IllegalArgumentException("a curve needs one z per x");
        }
        if (xs.length < 2) {
            throw new IllegalArgumentException("a curve needs at least two points");
        }
    }

    public int size() {
        return xs.length;
    }

    public double x(int index) {
        return xs[index];
    }

    public double z(int index) {
        return zs[index];
    }

    /** Length of the smoothed line in blocks. For a view that wants to space something along it. */
    public double length() {
        double length = 0;
        for (int i = 1; i < xs.length; i++) {
            length += Math.hypot(xs[i] - xs[i - 1], zs[i] - zs[i - 1]);
        }
        return length;
    }

    /**
     * The smoothed display line for a segment, or null when the segment is too short to smooth.
     *
     * <p>The default tolerance and number of passes, which is what the road-reading layers use.
     */
    public static RoadCurve of(RoadSegment segment) {
        if (segment == null || segment.vertexCount() < 2) {
            return null;
        }
        List<double[]> points = new ArrayList<>(segment.vertexCount());
        for (int i = 0; i < segment.vertexCount(); i++) {
            points.add(new double[]{segment.x(i), segment.z(i)});
        }
        return smooth(points, SIMPLIFY_TOLERANCE, CHAIKIN_PASSES);
    }

    /**
     * A smoothed line through the given world points.
     *
     * @param tolerance how far, in blocks, the simplified line may differ; zero keeps every point
     * @param passes    Chaikin passes, clamped to 0..4
     * @return the curve, or null when there are fewer than two distinct points
     */
    public static RoadCurve smooth(List<double[]> points, double tolerance, int passes) {
        if (points == null || points.size() < 2) {
            return null;
        }
        List<double[]> simplified = simplify(points, Math.max(0.0, tolerance));
        if (simplified.size() < 2) {
            return null;
        }
        List<double[]> rounded = simplified;
        int rounds = Math.max(0, Math.min(4, passes));
        if (simplified.size() < 3) {
            // A straight run has no corner to round; rewriting it as four points on the same line
            // would only be a longer way to say the same thing.
            rounds = 0;
        }
        for (int pass = 0; pass < rounds; pass++) {
            // Stop before a pass that would overshoot the cap: every Chaikin pass roughly doubles
            // the point count, and the cap exists so a pathological polyline cannot grow forever.
            if (rounded.size() * 2 > MAX_POINTS) {
                break;
            }
            rounded = chaikin(rounded);
        }
        if (rounded.size() > MAX_POINTS) {
            rounded = decimate(rounded, MAX_POINTS);
        }
        return from(rounded);
    }

    /** Drops points evenly to fit the cap, always keeping both ends. */
    private static List<double[]> decimate(List<double[]> points, int cap) {
        int count = points.size();
        if (count <= cap) {
            return points;
        }
        List<double[]> out = new ArrayList<>(cap);
        for (int i = 0; i < cap; i++) {
            out.add(points.get((int) Math.round((double) i * (count - 1) / (cap - 1))));
        }
        return out;
    }

    /** A curve from raw points, dropping consecutive duplicates and refusing a degenerate line. */
    public static RoadCurve from(List<double[]> points) {
        List<double[]> kept = new ArrayList<>(points.size());
        for (double[] point : points) {
            if (point == null || !Double.isFinite(point[0]) || !Double.isFinite(point[1])) {
                continue;
            }
            if (!kept.isEmpty()) {
                double[] last = kept.get(kept.size() - 1);
                if (Math.abs(last[0] - point[0]) < 1.0E-6 && Math.abs(last[1] - point[1]) < 1.0E-6) {
                    continue;
                }
            }
            kept.add(point);
        }
        if (kept.size() < 2) {
            return null;
        }
        double[] xs = new double[kept.size()];
        double[] zs = new double[kept.size()];
        for (int i = 0; i < kept.size(); i++) {
            xs[i] = kept.get(i)[0];
            zs[i] = kept.get(i)[1];
        }
        return new RoadCurve(xs, zs);
    }

    /** Douglas-Peucker, iterative so a very long polyline cannot overflow the stack. */
    private static List<double[]> simplify(List<double[]> points, double tolerance) {
        int count = points.size();
        boolean[] keep = new boolean[count];
        keep[0] = true;
        keep[count - 1] = true;
        java.util.ArrayDeque<int[]> pending = new java.util.ArrayDeque<>();
        pending.push(new int[]{0, count - 1});
        double toleranceSq = tolerance * tolerance;
        while (!pending.isEmpty()) {
            int[] range = pending.pop();
            int first = range[0];
            int last = range[1];
            if (last <= first + 1) {
                continue;
            }
            double[] start = points.get(first);
            double[] end = points.get(last);
            int furthest = -1;
            double furthestSq = toleranceSq;
            for (int i = first + 1; i < last; i++) {
                double distanceSq = distanceToSegmentSquared(points.get(i), start, end);
                if (distanceSq > furthestSq) {
                    furthestSq = distanceSq;
                    furthest = i;
                }
            }
            if (furthest >= 0) {
                keep[furthest] = true;
                pending.push(new int[]{first, furthest});
                pending.push(new int[]{furthest, last});
            }
        }
        List<double[]> kept = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            if (keep[i]) {
                kept.add(points.get(i));
            }
        }
        return kept;
    }

    /** One Chaikin pass: each interior corner becomes two points, the ends stay where they are. */
    private static List<double[]> chaikin(List<double[]> points) {
        List<double[]> out = new ArrayList<>(points.size() * 2);
        out.add(points.get(0));
        for (int i = 1; i < points.size(); i++) {
            double[] previous = points.get(i - 1);
            double[] here = points.get(i);
            out.add(new double[]{
                    previous[0] + (here[0] - previous[0]) * CHAIKIN_RATIO,
                    previous[1] + (here[1] - previous[1]) * CHAIKIN_RATIO});
            out.add(new double[]{
                    previous[0] + (here[0] - previous[0]) * (1.0 - CHAIKIN_RATIO),
                    previous[1] + (here[1] - previous[1]) * (1.0 - CHAIKIN_RATIO)});
        }
        out.add(points.get(points.size() - 1));
        return out;
    }

    private static double distanceToSegmentSquared(double[] point, double[] start, double[] end) {
        double dx = end[0] - start[0];
        double dz = end[1] - start[1];
        double lengthSq = dx * dx + dz * dz;
        if (lengthSq <= 0.0) {
            double px = point[0] - start[0];
            double pz = point[1] - start[1];
            return px * px + pz * pz;
        }
        double along = ((point[0] - start[0]) * dx + (point[1] - start[1]) * dz) / lengthSq;
        along = Math.max(0.0, Math.min(1.0, along));
        double nearestX = start[0] + dx * along - point[0];
        double nearestZ = start[1] + dz * along - point[1];
        return nearestX * nearestX + nearestZ * nearestZ;
    }
}
