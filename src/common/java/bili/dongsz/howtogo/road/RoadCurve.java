package bili.dongsz.howtogo.road;

import java.util.ArrayList;
import java.util.List;

/**
 * A smoothed centreline for drawing a road, stored beside the block vertices.
 * Display only. The stored vertices are a block staircase -- right for routing, wrong to look at -- so routing, lengths and hit tests stay on the integer geometry: a curve can never connect two roads the data says are separate, and no route is measured along it. It is persisted with the road and absent from roads that never had one, keeping existing files byte-compatible.
 * Smoothing: Douglas-Peucker at GRID_TOLERANCE drops a whole staircase, since every point of it lies within the tolerance of the diagonal, while a real corner is far from its chord. Kept corners are then rounded at a fixed world-space radius, capped by half of each adjacent edge; a fraction of an edge would make a long straight road swing wide at a right angle. Turns below CORNER_MIN_DEGREES belong to a gentle curve and stay as simplified; switchbacks past CORNER_MAX_DEGREES stay sharp, since a fillet would cut blocks off the bend. Rounding runs after simplifying, never before -- a rounded corner would otherwise be mistaken for real geometry and kept, which is the ripple this removes.
 * precise() takes machine-read geometry (Create bezier rasterisation, MTR track samples) as doubles and avoids the half-block wobble that rounding to blocks adds; of() is for block-sampled input.
 */
public record RoadCurve(double[] xs, double[] zs) {

    /**
     * How far the simplified line may differ from a block-sampled one, in blocks.
     *
     * <p>One and a quarter, because a rasterised <em>curve</em> is not the one-block staircase a diagonal is: a step
     * corner of a path drawn in whole blocks stands up to a block off the chord of the run it belongs to -- the
     * perpendicular distance of such a corner approaches one block as a line flattens, and rounding a curve to
     * blocks adds another half block either way. At 0.9 those corners were kept, and the corner rounding below then
     * turned each kept step into its own little arc: the drawn line wobbled left and right along a road that is
     * actually a smooth arc (measured on a rasterised quarter circle of radius 300: 333 points, 62 changes of bend
     * direction, 3527 degrees of turning for a 90 degree arc). 1.25 swallows the whole staircase of any rasterised
     * line, and every real corner is still many blocks from its chord, so a turn is still kept and rounded.
     *
     * <p>Display only: the drawn line may cut a rasterised bend by at most this much, which is under a road's drawn
     * width, and nothing in routing, lengths, hit tests or saving reads it.
     */
    public static final double GRID_TOLERANCE = 1.25;

    /**
     * The same for geometry already in world coordinates: only points that add nothing are dropped, so the drawn line keeps the source's own curve.
     */
    public static final double PRECISE_TOLERANCE = 0.2;

    /** The radius a kept corner is rounded at, in blocks. */
    private static final double CORNER_RADIUS = 1.25;

    /** A turn smaller than this is part of a curve rather than a corner and is not rounded. */
    private static final double CORNER_MIN_DEGREES = 8.0;

    /**
     * A turn past this is a switchback, not a corner a road is driven round: a fillet would cut several blocks off the bend, more than the road's width, so it is left sharp for the stroke's round join.
     */
    private static final double CORNER_MAX_DEGREES = 100.0;

    /** Samples per 90 degrees of arc, so every rounded corner is drawn as a smooth arc. */
    private static final double ARC_DEGREES_PER_STEP = 15.0;

    /** Hard cap on a curve's points, so a pathological polyline cannot grow without bound. */
    private static final int MAX_POINTS = 4096;

    /** Coordinates are rounded to this many decimals, far below anything a map can show. */
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

    /** Length of the smoothed line in blocks, for a view that spaces something along it. */
    public double length() {
        double length = 0;
        for (int i = 1; i < xs.length; i++) {
            length += Math.hypot(xs[i] - xs[i - 1], zs[i] - zs[i - 1]);
        }
        return length;
    }

    /**
     * The smoothed display line for a block-sampled segment, or null when the segment is too short.
     * This is the entry point for geometry stored as whole blocks -- a block scan, a RoadWeaver polyline,
     * an addon's two points. World-coordinate samples a reader already has (Create's bezier, MTR's rails)
     * go through precise() instead: the tolerance here is sized to swallow a rasterised staircase, and a
     * source that never needed rasterising would lose accuracy to it. Every read-only layer attaches its
     * curve through one of these two, so no map has to guess how a layer should be drawn.
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
     * The smoothed display line for geometry already sampled in world coordinates, keeping the source's double precision. Callers pin the first and last points themselves, so a curve still ends where the network says the road ends.
     */
    public static RoadCurve precise(List<double[]> points) {
        return smooth(points, PRECISE_TOLERANCE);
    }

    /**
     * A smoothed line through the given world points: simplify at the tolerance, then round the corners left.
     * The first and last point are kept exactly, so two curves naming the same junction meet there.
     * @param tolerance how far the simplified line may differ, in blocks; zero keeps every point
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
     * Replaces every real corner with a circular arc of CORNER_RADIUS, leaving gentle turns and hairpins alone. Written as a quadratic Bezier between the two touch points with the corner as control point: the same curve a tangent circle gives at these sizes, and it meets each edge with the same direction.
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
            // Where a circle of the chosen radius touches each edge. tan(turn/2) is safe here: the hairpin case that would make it huge was left alone above.
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

    /** Adds a point unless it repeats the previous one, so rounding writes no duplicates. */
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
