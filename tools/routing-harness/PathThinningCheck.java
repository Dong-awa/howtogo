package bili.dongsz.howtogo.client;

import java.util.ArrayList;
import java.util.List;

/**
 * Checks the thinning a drawn line is put through, which is what keeps a railway's worth of lines
 * affordable on the map: a whole network is tens of thousands of points sampled along its own curves,
 * and the map draws all of it every frame at whatever zoom the player chose.
 *
 * <h2>What has to be true of a thinned line</h2>
 * Two things, and both are invisible when they are wrong -- a line drawn a pixel off its track is a
 * line, and a line missing its last hundred blocks is a line that stops short:
 * <ul>
 *   <li><b>the ends are kept</b>, because a path whose drawn line did not reach its own ends would not
 *       reach the places it is known to run to;</li>
 *   <li><b>nothing is moved further than the tolerance</b>, which is the whole claim: every point that
 *       was dropped is within that distance of the line drawn instead of it. Checked against the drawn
 *       polyline rather than against the chord each point was dropped for, because that is what the
 *       player sees.</li>
 * </ul>
 *
 * <p>The tolerance itself is checked too: it is a power-of-two band of zoom, and it may never be
 * coarser than the pixels the drawing asked to be accurate within -- a band picked the other way round
 * would thin the geometry for a zoom the player is not at, which is a line that visibly disagrees with
 * its own track at every zoom in the band.
 *
 * <p>No game and no rendering: this is pure arithmetic over lists of points, so it runs anywhere.
 */
public final class PathThinningCheck {

    private static int checks;
    private static int failures;

    private PathThinningCheck() {
    }

    /**
     * Runs the checks.
     *
     * @return the number of checks made and the number that failed, in that order
     */
    public static int[] run() {
        checks = 0;
        failures = 0;
        System.out.println("== thinning a drawn line ==");
        straightRunsCollapse();
        endsAndCornersAreKept();
        nothingIsMovedFurtherThanTheTolerance();
        theToleranceFollowsTheZoom();
        boxesAndCulling();
        return new int[] {checks, failures};
    }

    /** A straight run of any length is two points, which is the whole reason the thinning exists. */
    private static void straightRunsCollapse() {
        List<double[]> line = new ArrayList<>();
        for (int i = 0; i <= 500; i++) {
            line.add(new double[]{i * 8.0, 3.0});
        }
        List<double[]> thinned = PathRuns.thin(line, 1.0);
        expect("a straight run of five hundred points is two points", thinned.size() == 2);
        expect("and they are the two ends of it",
                thinned.get(0)[0] == 0.0 && thinned.get(1)[0] == 4000.0);
        expect("while a curve is not flattened away", PathRuns.thin(arc(64, 40.0), 0.5).size() > 8);
        expect("and two points are left exactly as they are",
                PathRuns.thin(List.of(new double[]{0, 0}, new double[]{1, 1}), 100.0).size() == 2);
    }

    /** The ends of a path, and the corners no line may cut, are what the thinning has to keep. */
    private static void endsAndCornersAreKept() {
        List<double[]> corner = new ArrayList<>();
        for (int i = 0; i <= 100; i++) {
            corner.add(new double[]{i, 0});
        }
        for (int i = 1; i <= 100; i++) {
            corner.add(new double[]{100, i});
        }
        List<double[]> thinned = PathRuns.thin(corner, 0.5);
        expect("a right angle is not cut off", thinned.size() == 3);
        expect("with the corner itself among the points kept",
                holds(thinned, 100, 0) && holds(thinned, 0, 0) && holds(thinned, 100, 100));

        // A single point far off a straight run is a spike rather than a straight line, and it is the
        // one place where dropping a point would move the line by the whole distance.
        List<double[]> spike = List.of(new double[]{0, 0}, new double[]{50, 40}, new double[]{100, 0});
        expect("a point forty blocks off a straight run is kept",
                PathRuns.thin(spike, 1.0).size() == 3);
    }

    /**
     * The claim the thinning rests on: every point it drops is within the tolerance of the line drawn
     * in its place.
     */
    private static void nothingIsMovedFurtherThanTheTolerance() {
        List<double[]> track = arc(512, 900.0);
        double tolerance = 0.75;
        List<double[]> thinned = PathRuns.thin(track, tolerance);
        expect("a curve is thinned, which is what makes this worth checking at all",
                thinned.size() < track.size());
        double furthest = 0;
        for (double[] point : track) {
            furthest = Math.max(furthest, distanceToPolyline(point, thinned));
        }
        expect("and no point of it is further than the tolerance from the line drawn instead",
                furthest <= tolerance + 1.0E-9);

        // The same claim on a path with a corner and a wobble in it, where the answer is not a circle.
        List<double[]> mixed = new ArrayList<>();
        for (int i = 0; i <= 200; i++) {
            mixed.add(new double[]{i, Math.sin(i / 9.0) * 30.0});
        }
        List<double[]> thinMixed = PathRuns.thin(mixed, 2.0);
        double worst = 0;
        for (double[] point : mixed) {
            worst = Math.max(worst, distanceToPolyline(point, thinMixed));
        }
        expect("a wobbling path is thinned within its tolerance as well", worst <= 2.0 + 1.0E-9);
    }

    /** The tolerance a zoom asks for, and the banding that keeps it worth caching. */
    private static void theToleranceFollowsTheZoom() {
        for (double pixelsPerBlock : new double[]{0.002, 0.05, 0.5, 1.0, 3.0, 40.0, 900.0}) {
            double error = PathRuns.toleranceFor(pixelsPerBlock) * pixelsPerBlock;
            expect("at " + pixelsPerBlock + " px/block the error is within the fraction of a pixel "
                            + "the drawing allows", error <= PathRuns.THIN_PX + 1.0E-9);
        }
        expect("a zoom coarser than any map is banded rather than unbounded",
                PathRuns.toleranceFor(0.0) == PathRuns.toleranceFor(Math.pow(2.0, PathRuns.LOWEST_BAND)));
        expect("and the band is the same for two zooms inside it",
                PathRuns.bandOf(0.5) == PathRuns.bandOf(0.9));
        expect("while the next band out is twice as coarse",
                PathRuns.toleranceFor(0.5) == 2.0 * PathRuns.toleranceFor(1.0));
    }

    /** The box a stretch fits in, which is what keeps the ones off the screen from being walked. */
    private static void boxesAndCulling() {
        PathRuns.Run run = PathRuns.of(List.of(
                new double[]{10, 20}, new double[]{30, 5}, new double[]{12, 40}));
        expect("a stretch's box is the box of its points",
                run.minX() == 10 && run.maxX() == 30 && run.minZ() == 5 && run.maxZ() == 40);
        expect("a stretch the screen covers is drawn",
                PathRuns.onScreen(run, 0, 0, 100, 100));
        expect("a stretch the screen touches is drawn too",
                PathRuns.onScreen(run, 25, 30, 100, 100));
        expect("a stretch nowhere near the screen is not",
                !PathRuns.onScreen(run, 100, 100, 200, 200));
        expect("and neither is one beside it, past the edge", !PathRuns.onScreen(run, 31, 0, 100, 100));
    }

    // ----------------------------------------------------------------- helpers

    /** A stretch of a circle, sampled the way a rail's own curve is: every few blocks. */
    private static List<double[]> arc(int steps, double radius) {
        List<double[]> points = new ArrayList<>(steps + 1);
        for (int i = 0; i <= steps; i++) {
            double angle = i * Math.PI / steps;
            points.add(new double[]{Math.cos(angle) * radius, Math.sin(angle) * radius});
        }
        return points;
    }

    private static boolean holds(List<double[]> points, double x, double z) {
        for (double[] point : points) {
            if (point[0] == x && point[1] == z) {
                return true;
            }
        }
        return false;
    }

    /** How far a point is from a whole polyline, in blocks. */
    private static double distanceToPolyline(double[] point, List<double[]> polyline) {
        double best = Double.MAX_VALUE;
        for (int i = 1; i < polyline.size(); i++) {
            best = Math.min(best, distanceToSegment(point, polyline.get(i - 1), polyline.get(i)));
        }
        return best;
    }

    private static double distanceToSegment(double[] point, double[] start, double[] end) {
        double dx = end[0] - start[0];
        double dz = end[1] - start[1];
        double lengthSquared = dx * dx + dz * dz;
        double along = lengthSquared <= 0 ? 0
                : Math.max(0, Math.min(1, ((point[0] - start[0]) * dx + (point[1] - start[1]) * dz)
                        / lengthSquared));
        return Math.hypot(start[0] + dx * along - point[0], start[1] + dz * along - point[1]);
    }

    private static void expect(String what, boolean condition) {
        checks++;
        if (!condition) {
            failures++;
        }
        System.out.println((condition ? "  ok   " : "  FAIL ") + what);
    }
}
