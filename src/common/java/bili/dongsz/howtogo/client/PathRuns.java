package bili.dongsz.howtogo.client;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * A path as the map draws it: the stretches it is known along, the box each fits in, and the points
 * each of them needs at the zoom it is being drawn at.
 *
 * <h2>Why a drawn path is thinned at all</h2>
 * A railway read out of another mod is sampled along its own curve -- a point every few blocks, tens of
 * thousands of points for one network -- and the map draws every one of those lines every frame, at
 * whatever zoom the player has chosen. Emitting a stroke per step of that sampling is a cost that grows
 * with the railway and not with what is on the screen, and at any zoom where a map is readable most of
 * those steps are a fraction of a pixel: the quads are paid for and nobody sees them. So each stretch
 * is thinned to the zoom: a point is kept only where dropping it would move the drawn line by more than
 * a fraction of a pixel, which is the most a line may be wrong by and still be the same line to look at.
 *
 * <h2>What is guaranteed</h2>
 * The thinning keeps the first and last point of a stretch -- a path that stopped short of its own end
 * would leave a line that does not reach the place it is known to -- and every point it drops is within
 * the tolerance of the line that is drawn instead. Both are checked by the harness, because both are
 * invisible: a line drawn a pixel away from its track looks like a line.
 *
 * <h2>Why the box</h2>
 * A whole railway's worth of stretches is mostly off the screen, and a stretch that is off it costs
 * nothing to draw but still costs a walk of its points to find that out. The box each stretch fits in
 * is worked out once, when the stretch is built, so the question "is any of this on the screen" is four
 * comparisons rather than a walk of thousands of points.
 */
public final class PathRuns {

    /**
     * The most a drawn line may be moved off the track it is drawn from, in screen pixels.
     *
     * <p>Three quarters of a pixel, which is narrower than the line's own stroke: at that distance the
     * drawn line and the track are the same line to look at, and the points that were dropped are the
     * ones that were paying for nothing.
     */
    public static final double THIN_PX = 0.75;

    /**
     * The ends of the zoom range a thinning is worked out for, as powers of two pixels per block.
     *
     * <p>Below the lowest, the whole world is less than a pixel across and one point per line is the
     * honest answer; above the highest, a block covers thousands of pixels and nothing may be dropped.
     * They bound the tolerance the arithmetic produces at either end rather than being zoom levels
     * anyone reaches.
     */
    public static final int LOWEST_BAND = -12;
    public static final int HIGHEST_BAND = 14;

    private PathRuns() {
    }

    /**
     * The band of zoom a pixels-per-block figure is in.
     *
     * <p>A power of two, so that one tolerance serves a whole band and the same band is asked for again
     * and again while the map is panned: <em>exponent</em> rather than a logarithm because the exponent
     * of a double is exactly this number and a logarithm is an approximation of it.
     */
    public static int bandOf(double pixelsPerBlock) {
        if (!(pixelsPerBlock > 0.0) || !Double.isFinite(pixelsPerBlock)) {
            return LOWEST_BAND;
        }
        return Math.max(LOWEST_BAND, Math.min(HIGHEST_BAND, Math.getExponent(pixelsPerBlock)));
    }

    /**
     * How far a drawn line may be moved off the track at a zoom, in blocks.
     *
     * <p>The top of the band rather than the zoom itself: every zoom in the band covers at most that
     * many pixels per block, so a tolerance worked out from it is never coarser than the fraction of a
     * pixel the drawing allows -- which is the direction the error has to be wrong in, since the drawn
     * line is what the player reads the position of the track off. Worked out from the bottom of the
     * band instead, one band of zoom would be drawn up to twice as far off its track as the one below
     * it, which is a line that visibly changes shape as the map is zoomed.
     */
    public static double toleranceFor(double pixelsPerBlock) {
        return THIN_PX / Math.pow(2.0, bandOf(pixelsPerBlock) + 1);
    }

    /** One stretch of a path: its points, and the box they fit in. */
    public record Run(List<double[]> points, double minX, double minZ, double maxX, double maxZ) {

        /** How many points the stretch has, before or after thinning. */
        public int size() {
            return points.size();
        }
    }

    /** A stretch of a path, with the box it fits in worked out. */
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
     * Whether any part of a stretch can be on a screen showing this box of the world.
     *
     * <p>True for a box that grazes the view without the line entering it, which is the cheap
     * direction to be wrong in: one stroke wasted rather than a line missing.
     */
    public static boolean onScreen(Run run, double minX, double minZ, double maxX, double maxZ) {
        return run.maxX() >= minX && run.minX() <= maxX && run.maxZ() >= minZ && run.minZ() <= maxZ;
    }

    /**
     * The same stretch with the points this zoom needs, and no more.
     *
     * @param tolerance how far the drawn line may be moved off the track, in blocks -- which is the
     *                  thinning allowance in pixels divided by the pixels one block covers
     */
    public static Run thinned(Run run, double tolerance) {
        List<double[]> thinned = thin(run.points(), tolerance);
        return thinned == run.points() ? run : of(thinned);
    }

    /**
     * The points a line needs to stay within {@code tolerance} of the path they came from.
     *
     * <p>Douglas and Peucker's: the point furthest from the line joining the ends of a stretch is kept
     * whenever it is further off than the tolerance, and the two halves either side of it are then the
     * same question, which is what makes the answer keep a corner and drop a straight run of any length.
     * Iterative rather than recursive because the stretch is a read of another mod's data and its length
     * is not something this may assume.
     *
     * <p>Returns the list it was given when nothing can be dropped, so a line drawn in its entirety
     * costs no copy.
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
