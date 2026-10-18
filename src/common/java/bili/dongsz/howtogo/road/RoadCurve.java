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
 * <ol>
 *   <li><b>Simplify with the block grid in mind.</b> A staircase is not a shape; it is the shadow of a
 *       diagonal on a grid. Every point of it is within {@link #GRID_TOLERANCE} of the diagonal it
 *       represents, so a Douglas-Peucker pass at that tolerance drops the whole staircase and keeps a
 *       single straight edge -- while every corner a road really has is far further from its chord
 *       than the tolerance and is kept. This is what stops the old failure: a staircase that survived
 *       simplification and then had every one of its one-block corners rounded into a ripple.</li>
 *   <li><b>Round the corners it kept, at a radius that is a size, not a fraction.</b> Corner cutting
 *       is done with a fixed world-space radius ({@link #CORNER_RADIUS} blocks) rather than a fraction
 *       of the adjacent edge. A fraction is what made a long straight road swing several blocks wide
 *       at a right-angled bend: the quarter of a two-hundred-block edge is fifty blocks. The radius is
 *       capped by half of each adjacent edge, so two corners close together still meet.</li>
 *   <li><b>Only round real corners.</b> A turn below {@link #CORNER_MIN_DEGREES} is part of a gentle
 *       curve and is left exactly as simplified; a switchback past {@link #CORNER_MAX_DEGREES} is left
 *       sharp, because the fillet there would cut blocks off the bend and turn a tight elbow into a
 *       shortcut. The stroke's own round join closes the notch at such a turn.</li>
 * </ol>
 * The two steps are deliberately in that order: rounding before simplifying would let a rounded
 * corner be mistaken for the railway's own curve and keep it, which is exactly the ripple this class
 * exists to remove.
 *
 * <h2>Precision</h2>
 * Machine-read geometry is often already a smooth polyline in world coordinates -- Create rasterises
 * its beziers, MTR samples its curves -- and rounding that to whole blocks before smoothing is what
 * used to add a half-block wobble to every diagonal and every bend. {@link #precise} therefore takes
 * those doubles as they are and only drops the points a display does not need; {@link #of} is for the
 * block-sampled case, where the staircase <i>is</i> the input.
 */
public record RoadCurve(double[] xs, double[] zs) {

    /**
     * How far the simplified line may differ from a block-sampled line, in blocks.
     *
     * <p>Large enough to swallow the whole of a one-block staircase: a 45-degree block staircase is at
     * most half a diagonal -- about 0.71 blocks -- from the line it represents, and the grid wobble of
     * a road laid out by a pathfinder is of that order. Every real corner is metres away from its
     * chord, so the tolerance cannot mistake one for grid noise.
     */
    public static final double GRID_TOLERANCE = 0.9;

    /**
     * The same for geometry that is already in world coordinates: only points that add nothing are
     * dropped, so the drawn line keeps the source's own curve.
     */
    public static final double PRECISE_TOLERANCE = 0.2;

    /** The radius a kept corner is rounded at, in blocks. */
    private static final double CORNER_RADIUS = 1.25;

    /** A turn smaller than this is part of a curve rather than a corner and is not rounded. */
    private static final double CORNER_MIN_DEGREES = 8.0;

    /**
     * A turn past this is a switchback, not a corner a road is driven round. A fillet at such an angle
     * would cut several blocks off the bend -- more than the width of the road it is drawn on -- so
     * those turns are left sharp for the stroke's own round join, which closes the notch without
     * pretending the road takes a wider line than it does.
     */
    private static final double CORNER_MAX_DEGREES = 100.0;

    /** Samples per 90 degrees of arc, so every rounded corner is drawn as a smooth arc. */
    private static final double ARC_DEGREES_PER_STEP = 15.0;

    /** Hard cap on a curve's points, so a pathological polyline cannot grow without bound. */
    private static final int MAX_POINTS = 4096;

    /** Coordinates are rounded to this many decimals, which is far below anything a map can show. */
    private static final double COORDINATE_SCALE = 1000.0;

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
     * The smoothed display line for a block-sampled segment, or null when the segment is too short.
     *
     * <p>Used by every reader of another mod's blocks: the vertices are what the world contains, and
     * the staircase they form is what {@link #GRID_TOLERANCE} removes.
     */
    public static RoadCurve of(RoadSegment segment) {
        if (segment == null || segment.vertexCount() < 2) {
            return null;
        }
        List<double[]> points = new ArrayList<>(segment.vertexCount());
        for (int i = 0; i < segment.vertexCount(); i++) {
            points.add(new double[]{segment.x(i), segment.z(i)});
        }
        return smooth(points, GRID_TOLERANCE);
    }

    /**
     * The smoothed display line for geometry already sampled in world coordinates.
     *
     * <p>Create's {@code BezierConnection.rasterise()} and MTR's own track vertices are double
     * precision; this keeps that precision instead of rounding to blocks first. Callers pin the first
     * and last points themselves (they are the segment's own ends) so a curve still ends where the
     * network says the road ends.
     */
    public static RoadCurve precise(List<double[]> points) {
        return smooth(points, PRECISE_TOLERANCE);
    }

    /**
     * A smoothed line through the given world points.
     *
     * <p>Simplify at the given tolerance, then round the corners that are left. The result keeps the
     * first and last point exactly, which is what makes two curves that name the same junction meet at
     * it.
     *
     * @param tolerance how far, in blocks, the simplified line may differ; zero keeps every point
     * @return the curve, or null when there are fewer than two distinct points
     */
    public static RoadCurve smooth(List<double[]> points, double tolerance) {
        List<double[]> clean = clean(points);
        if (clean.size() < 2) {
            return null;
        }
        List<double[]> simplified = simplify(clean, Math.max(0.0, tolerance));
        if (simplified.size() < 2) {
            return null;
        }
        if (simplified.size() == 2) {
            // A straight run has no corner to round.
            return from(simplified);
        }
        List<double[]> rounded = roundCorners(simplified);
        if (rounded.size() > MAX_POINTS) {
            rounded = decimate(rounded, MAX_POINTS);
        }
        return from(rounded);
    }

    /** A curve from raw points, dropping consecutive duplicates and refusing a degenerate line. */
    public static RoadCurve from(List<double[]> points) {
        List<double[]> kept = clean(points);
        if (kept.size() < 2) {
            return null;
        }
        double[] xs = new double[kept.size()];
        double[] zs = new double[kept.size()];
        for (int i = 0; i < kept.size(); i++) {
            xs[i] = roundCoordinate(kept.get(i)[0]);
            zs[i] = roundCoordinate(kept.get(i)[1]);
        }
        return new RoadCurve(xs, zs);
    }

    /** Drops null, non-finite and consecutive duplicate points. */
    private static List<double[]> clean(List<double[]> points) {
        if (points == null) {
            return List.of();
        }
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
        return kept;
    }

    private static double roundCoordinate(double value) {
        return Math.round(value * COORDINATE_SCALE) / COORDINATE_SCALE;
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

    /**
     * Replaces every real corner with a circular arc of {@link #CORNER_RADIUS}, leaving gentle turns
     * and hairpins alone.
     *
     * <p>The arc is written as a quadratic Bezier between the two points where it touches the two
     * edges, with the corner as its control point. That is the same curve a tangent circle gives to
     * within a thousandth of the radius at these sizes, and it meets each straight edge with the same
     * direction, so a rounded corner is continuous with the road on both sides.
     */
    private static List<double[]> roundCorners(List<double[]> points) {
        List<double[]> out = new ArrayList<>(points.size() * 2);
        out.add(points.get(0));
        for (int i = 1; i < points.size() - 1; i++) {
            double[] previous = points.get(i - 1);
            double[] here = points.get(i);
            double[] next = points.get(i + 1);
            double inX = here[0] - previous[0];
            double inZ = here[1] - previous[1];
            double outX = next[0] - here[0];
            double outZ = next[1] - here[1];
            double inLength = Math.hypot(inX, inZ);
            double outLength = Math.hypot(outX, outZ);
            if (inLength < 1.0E-9 || outLength < 1.0E-9) {
                addPoint(out, here);
                continue;
            }
            double inUnitX = inX / inLength;
            double inUnitZ = inZ / inLength;
            double outUnitX = outX / outLength;
            double outUnitZ = outZ / outLength;
            double cross = inUnitX * outUnitZ - inUnitZ * outUnitX;
            double dot = inUnitX * outUnitX + inUnitZ * outUnitZ;
            double turn = Math.toDegrees(Math.atan2(Math.abs(cross), dot));
            if (turn < CORNER_MIN_DEGREES || turn > CORNER_MAX_DEGREES) {
                addPoint(out, here);
                continue;
            }
            // Where a circle of the chosen radius touches each edge. tan(turn/2) is safe here: the
            // hairpin case that would make it huge was left alone above.
            double cut = Math.min(Math.min(inLength * 0.5, outLength * 0.5),
                    CORNER_RADIUS * Math.tan(Math.toRadians(turn) * 0.5));
            if (cut < 1.0E-6) {
                addPoint(out, here);
                continue;
            }
            double aX = here[0] - inUnitX * cut;
            double aZ = here[1] - inUnitZ * cut;
            double bX = here[0] + outUnitX * cut;
            double bZ = here[1] + outUnitZ * cut;
            addPoint(out, new double[]{aX, aZ});
            int steps = Math.max(2, Math.min(8, (int) Math.ceil(turn / ARC_DEGREES_PER_STEP)));
            for (int step = 1; step <= steps; step++) {
                double t = (double) step / steps;
                double inverse = 1.0 - t;
                addPoint(out, new double[]{
                        inverse * inverse * aX + 2.0 * inverse * t * here[0] + t * t * bX,
                        inverse * inverse * aZ + 2.0 * inverse * t * here[1] + t * t * bZ});
            }
        }
        addPoint(out, points.get(points.size() - 1));
        return out;
    }

    /** Adds a point unless it repeats the previous one; keeps rounding from writing a duplicate. */
    private static void addPoint(List<double[]> points, double[] point) {
        if (!points.isEmpty()) {
            double[] last = points.get(points.size() - 1);
            if (Math.abs(last[0] - point[0]) < 1.0E-9 && Math.abs(last[1] - point[1]) < 1.0E-9) {
                return;
            }
        }
        points.add(point);
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
