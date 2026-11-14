package bili.dongsz.howtogo.client;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * A path as the map draws it: the stretches it is known along, the box each fits in, and the points each needs at the zoom it is drawn at.
 * Thinning: a railway sampled every few blocks is tens of thousands of points drawn every frame, and at any readable zoom most steps are a fraction of a pixel -- the quads are paid for and nobody sees them. A point is kept only where dropping it would move the drawn line by more than a fraction of a pixel.
 * The first and last point of a stretch always survive, and every dropped point is within tolerance of the line drawn instead; the harness checks both, because a line drawn a pixel off its track still looks like a line.
 * The box is worked out once when the stretch is built, so "is any of this on the screen" is four comparisons, not a walk of thousands of points.
 */
public final class PathRuns {

    /**
     * The most a drawn line may be moved off the track it is drawn from, in screen pixels. Narrower than the line's own stroke, so the dropped points were paying for nothing.
     */
    public static final double THIN_PX = 0.75;

    /**
     * The ends of the zoom range a thinning is worked out for, as powers of two pixels per block. Below the lowest, one point per line is the honest answer; above the highest, nothing may be dropped. They bound the arithmetic rather than being zoom levels anyone reaches.
     */
    public static final int LOWEST_BAND = -12;
    public static final int HIGHEST_BAND = 14;

    /**
     * Tolerances per zoom band, indexed by (band - LOWEST_BAND); pre-computed so a per-call Math.pow() is avoided.
     */
    private static final double[] TOLERANCE_TABLE = new double[HIGHEST_BAND - LOWEST_BAND + 1];
    static {
        for (int band = LOWEST_BAND; band <= HIGHEST_BAND; band++) {
            TOLERANCE_TABLE[band - LOWEST_BAND] = THIN_PX / Math.pow(2.0, band + 1);
        }
    }

    private PathRuns() {
    }

    /**
     * The band of zoom a pixels-per-block figure is in. A power of two, so one tolerance serves a whole band and the same band recurs while panning; the exponent of a double is exact where a logarithm is an approximation.
     */
    public static int bandOf(double pixelsPerBlock) {
        if (!(pixelsPerBlock > 0.0) || !Double.isFinite(pixelsPerBlock)) {
            return LOWEST_BAND;
        }
        return Math.max(LOWEST_BAND, Math.min(HIGHEST_BAND, Math.getExponent(pixelsPerBlock)));
    }

    /**
     * How far a drawn line may be moved off the track at a zoom, in blocks.
     * Worked out from the top of the band so the tolerance is never coarser than the fraction of a pixel allowed; from the bottom, one band would be drawn up to twice as far off as the one below it and visibly change shape while zooming.
     */
    public static double toleranceFor(double pixelsPerBlock) {
        int band = bandOf(pixelsPerBlock);
        int index = band - LOWEST_BAND;
        // Bounds check; bandOf clamps, but guard the table index.
        if (index < 0 || index >= TOLERANCE_TABLE.length) {
            return THIN_PX / Math.pow(2.0, band + 1);  // Fallback to calculation
        }
        return TOLERANCE_TABLE[index];
    }

    /** One stretch of a path: its points, and the box they fit in. */
    public record Run(List<double[]> points, double minX, double minZ, double maxX, double maxZ) {

        /** How many points the stretch has, before or after thinning. */
        public int size() {
            return points.size();
        }
    }

    public static Run of(List<double[]> points) {
        double minX = Double.POSITIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;
        for (double[] point : points) {
            minX = Math.min(minX, point[0]);
            maxX = Math.max(maxX, point[0]);
            minZ = Math.min(minZ, point[1]);
            maxZ = Math.max(maxZ, point[1]);
        }
        return new Run(points, minX, minZ, maxX, maxZ);
    }

    /**
     * Whether any part of a stretch can be on a screen showing this box of the world. True for a box that grazes the view without the line entering it -- the cheap direction to be wrong in.
     */
    public static boolean onScreen(Run run, double minX, double minZ, double maxX, double maxZ) {
        return run.maxX() >= minX && run.minX() <= maxX && run.maxZ() >= minZ && run.minZ() <= maxZ;
    }

    /**
     * The same stretch with the points this zoom needs.
     * @param tolerance how far the drawn line may be moved off the track, in blocks -- the pixel allowance divided by the pixels one block covers
     */
    public static Run thinned(Run run, double tolerance) {
        List<double[]> thinned = thin(run.points(), tolerance);
        return thinned == run.points() ? run : of(thinned);
    }

    /**
     * The points a line needs to stay within tolerance of the path they came from.
     * Douglas-Peucker: the point furthest from the chord is kept when it is further off than the tolerance, and the two halves either side are the same question, so a corner survives and a straight run of any length goes. Iterative because the stretch is another mod's data and its length may not be assumed.
     * Returns the given list when nothing can be dropped, so a fully drawn line costs no copy.
     */
    public static List<double[]> thin(List<double[]> points, double tolerance) {
        int count = points.size();
        if (count <= 2 || !(tolerance > 0.0) || !Double.isFinite(tolerance)) {
            return points;
        }
        boolean[] keep = new boolean[count];
        keep[0] = true;
        keep[count - 1] = true;
        double slack = tolerance * tolerance;
        ArrayDeque<int[]> pending = new ArrayDeque<>();
        pending.push(new int[]{0, count - 1});
        while (!pending.isEmpty()) {
            int[] span = pending.pop();
            int first = span[0];
            int last = span[1];
            int furthest = -1;
            double furthestSquared = slack;
            for (int i = first + 1; i < last; i++) {
                double distance = distanceToSegmentSquared(points.get(i), points.get(first), points.get(last));
                if (distance > furthestSquared) {
                    furthestSquared = distance;
                    furthest = i;
                }
            }
            if (furthest < 0) {
                // Everything between the two ends is within the tolerance of the line joining them.
                continue;
            }
            keep[furthest] = true;
            pending.push(new int[]{first, furthest});
            pending.push(new int[]{furthest, last});
        }
        List<double[]> kept = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            if (keep[i]) {
                kept.add(points.get(i));
            }
        }
        return kept;
    }

    /** How far a point is from a segment, squared, so nothing has to take a square root. */
    private static double distanceToSegmentSquared(double[] point, double[] start, double[] end) {
        double dx = end[0] - start[0];
        double dz = end[1] - start[1];
        double lengthSquared = dx * dx + dz * dz;
        if (lengthSquared <= 0.0) {
            double px = point[0] - start[0];
            double pz = point[1] - start[1];
            return px * px + pz * pz;
        }
        double along = ((point[0] - start[0]) * dx + (point[1] - start[1]) * dz) / lengthSquared;
        along = Math.max(0.0, Math.min(1.0, along));
        double nearestX = start[0] + dx * along - point[0];
        double nearestZ = start[1] + dz * along - point[1];
        return nearestX * nearestX + nearestZ * nearestZ;
    }
}
