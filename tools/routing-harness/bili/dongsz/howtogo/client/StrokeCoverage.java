package bili.dongsz.howtogo.client;

import java.util.Random;

/**
 * How much of the map a set of stroke quads paints, measured the way a {@code QUADS} buffer draws it.
 *
 * <p>Shared by the two stroke checks -- the world map's {@link StrokeOutline} and this mod's own maps'
 * {@link HudDraw} -- because both need the same two questions answered. Does a point get painted at all,
 * so that a stroke has no notch and keeps its width along the whole line? And does a point get painted
 * more than once, which on a translucent road reads as a brighter or darker edge: the bead the stroke
 * classes exist to avoid, and the one a fan whose triangles are written twice produces.
 *
 * <p>The geometry both classes emit is a flat list of quads, four corners of two doubles each, and the
 * buffer draws each quad as the triangles {@code (0,1,2)} and {@code (0,2,3)}. This class is that rule
 * in one place, so neither check can disagree with the other about what the game draws.
 *
 * <p>Zero-area triangles paint nothing. A fan's second half is emitted as a triangle with its third
 * corner repeated, which the GPU rasterises to nothing; counting it as painted would let one such
 * triangle cover the whole map and hide everything else.
 */
final class StrokeCoverage {

    /** How many doubles one quad occupies: four corners of (x, y). */
    static final int VALUES_PER_QUAD = 8;

    /** Samples used by {@link #worstCoverage}; enough to find a cap-sized patch, and quick. */
    static final int SAMPLES = 40000;

    /** A fixed seed, so a failure is the same failure twice and can be looked at. */
    private static final long SEED = 0x5EEDL;

    private StrokeCoverage() {
    }

    /** Whether any of the quads paints this point. */
    static boolean paints(double[] quads, double x, double y) {
        return coverage(quads, x, y) > 0;
    }

    /**
     * How many triangles paint this point: 0 unpainted, 1 painted once, 2 or more blended twice.
     *
     * <p>Counted per triangle, not per quad: a quad drawn as {@code (0,1,2)} and {@code (0,2,3)} is two
     * pieces of fill, and a quad whose corners fold over itself -- the bow-tie a badly joined stroke
     * makes -- has those two pieces landing on the same pixels, which is exactly the double blend this
     * is looking for.
     */
    static int coverage(double[] quads, double x, double y) {
        int painted = 0;
        for (int at = 0; at + VALUES_PER_QUAD <= quads.length; at += VALUES_PER_QUAD) {
            if (triangle(quads[at], quads[at + 1], quads[at + 2], quads[at + 3],
                    quads[at + 4], quads[at + 5], x, y)) {
                painted++;
            }
            if (triangle(quads[at], quads[at + 1], quads[at + 4], quads[at + 5],
                    quads[at + 6], quads[at + 7], x, y)) {
                painted++;
            }
        }
        return painted;
    }

    /**
     * The most times any point in the given box is painted.
     *
     * <p>A lattice of pseudo-random points rather than every pixel: the areas a doubled fan covers are
     * large (a cap is a half disc of the stroke's own width), so a fixed sample of the box finds them,
     * and a full-resolution scan would spend a second per case on arithmetic that says the same thing.
     */
    static int worstCoverage(double[] quads, double minX, double minY, double maxX, double maxY) {
        Random random = new Random(SEED);
        int worst = 0;
        for (int sample = 0; sample < SAMPLES; sample++) {
            double x = minX + random.nextDouble() * (maxX - minX);
            double y = minY + random.nextDouble() * (maxY - minY);
            int painted = coverage(quads, x, y);
            if (painted > worst) {
                worst = painted;
            }
        }
        return worst;
    }

    /** How far a point is from a polyline, which is how a spike beyond the stroke is read. */
    static double distanceToPath(double[] xs, double[] ys, int count, double x, double y) {
        double best = Double.MAX_VALUE;
        for (int i = 0; i + 1 < count; i++) {
            best = Math.min(best, distanceToEdge(xs[i], ys[i], xs[i + 1], ys[i + 1], x, y));
        }
        return best;
    }

    /** How far a point is from one edge. */
    static double distanceToEdge(double ax, double ay, double bx, double by, double x, double y) {
        double dx = bx - ax;
        double dy = by - ay;
        double lengthSq = dx * dx + dy * dy;
        double along = lengthSq > 0 ? ((x - ax) * dx + (y - ay) * dy) / lengthSq : 0.0;
        along = Math.max(0.0, Math.min(1.0, along));
        return Math.hypot(ax + dx * along - x, ay + dy * along - y);
    }

    /**
     * Point in one triangle, boundary included, for either winding.
     *
     * <p>A triangle with no area is never painted: the three corners of a degenerate fan half are
     * colinear, every cross product is zero, and the inclusive test alone would call every point
     * inside.
     */
    private static boolean triangle(double ax, double ay, double bx, double by,
                                    double cx, double cy, double px, double py) {
        double area = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax);
        if (area == 0.0) {
            return false;
        }
        double ab = (px - bx) * (ay - by) - (ax - bx) * (py - by);
        double bc = (px - cx) * (by - cy) - (bx - cx) * (py - cy);
        double ca = (px - ax) * (cy - ay) - (cx - ax) * (py - ay);
        boolean negative = ab < 0 || bc < 0 || ca < 0;
        boolean positive = ab > 0 || bc > 0 || ca > 0;
        return !(negative && positive);
    }
}
