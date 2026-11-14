package bili.dongsz.howtogo.client;

import java.util.ArrayList;
import java.util.List;

/**
 * Where a public transport line's name is offered on a map, and where it may stand.
 *
 * <h2>What a line's name is, and what it is not</h2>
 * A road's name is drawn as ink lying along the road, once per stretch of screen the road crosses.
 * A line's name is not that: it is a capsule in the line's own colour with the name printed in it,
 * so it reads as a badge for the service rather than as a second road name -- the same
 * coloured-object language the mod already uses for a line in a list, on a platform sign and on the
 * place card. A capsule is horizontal on every map, because the plate is an object that names the
 * line rather than a word belonging to its geometry.
 *
 * <h2>Why the positions are a fixed distance apart</h2>
 * The player asked for one every 160 blocks, and for one just outside each end of the line. A screen
 * distance would put a name on every line every time the map was zoomed, and a name per stop would
 * be a name per station -- the line would read as its timetable. A world distance means the names
 * stay put while the map moves, so the same plate is in the same place next to the same stretch of
 * track, and the count is the length of the line rather than the size of the screen.
 *
 * <h2>Why it is allowed to fail</h2>
 * A name is not worth covering another label for. Each candidate is offered to the map's own
 * occupied-space test; a taken place makes the placer step along the line in alternating directions
 * and try again, and when every step is taken the place is simply left unnamed. The capsule does not
 * shrink, wrap or move somewhere unrelated to its line -- an unreadable or unrelated plate is worse
 * than a stretch of track with no plate on it.
 *
 * <p>The geometry lives here, once, so the world map, the main screen's viewer, the destination
 * picker, the HUD's small map, Xaero's minimap overlay and the phone page cannot drift apart about
 * where a line's name goes or which spots are worth trying.
 */
public final class LineNameLabels {

    /** One name per this many blocks of drawn line, the spacing the player asked for. */
    public static final double SPACING_BLOCKS = 160.0;

    /**
     * How far outside a line's two ends the end candidates sit, in blocks.
     *
     * <p>The player asked for the ends to be named as well, in the free room just past them: a
     * terminus is the one place on a line a passenger reads a name from, and it is where the track
     * itself stops taking up the room a plate needs.
     */
    public static final double END_MARGIN_BLOCKS = 24.0;

    /** How far a name steps along its line when the first place is taken, in blocks. */
    public static final double OFFSET_STEP_BLOCKS = 24.0;

    /** How many steps either way a name may hunt for a free place before the spot is left unnamed. */
    public static final int OFFSET_STEPS = 6;

    /**
     * The font factor a line's name is drawn at, on every map.
     *
     * <p>Not tied to a road's label zoom, and not the sign card's smaller fraction either: the
     * capsule is the one thing on the map that is about the service rather than about the ground,
     * so it is drawn at a readable size of its own and the plate is sized around it. All six maps
     * use the same factor so the same line's badge is the same badge wherever it appears.
     */
    public static final float NAME_FACTOR = 0.8F;

    /** Room inside the capsule either side of the name, in unscaled font units. */
    public static final int PAD_X = 4;

    /** Room above and below the name inside the capsule, in unscaled font units. */
    public static final int PAD_Y = 2;

    private LineNameLabels() {
    }

    /**
     * The screen the placer is placing on: the projection of one map, and its own fit test.
     *
     * <p>A small interface rather than a map-specific callback so the search below is written once.
     * The fit test is the map's business because it is where the map's already-placed labels, its
     * panels and its frame live -- the placer only knows what box it needs and how to move it.
     */
    public interface Screen {

        /** Screen x of a world point. */
        double screenX(double worldX, double worldZ);

        /** Screen y of a world point. */
        double screenY(double worldX, double worldZ);

        /** Whether a painted capsule's rectangle is wholly usable on this map. */
        boolean fits(double left, double top, double right, double bottom);
    }

    /**
     * The distances along a line's own polyline where its name is offered, in blocks.
     *
     * <p>Every {@link #SPACING_BLOCKS} of the drawn line, plus one just outside either end. The
     * distances may be negative or greater than the line's length: {@link #pointAt} extrapolates
     * along the first and last edges, which is what makes the end candidates sit outside the line
     * rather than on its last block.
     */
    public static List<Double> candidates(List<double[]> points) {
        List<Double> distances = new ArrayList<>();
        if (points == null || points.size() < 2) {
            return distances;
        }
        double length = length(points);
        distances.add(-END_MARGIN_BLOCKS);
        for (double at = SPACING_BLOCKS; at < length; at += SPACING_BLOCKS) {
            distances.add(at);
        }
        distances.add(length + END_MARGIN_BLOCKS);
        return distances;
    }

    /** One offered place: its world point, the distance along the line, and the line's direction there. */
    public record Candidate(double x, double z, double distance, double dirX, double dirZ) {
    }

    /**
     * The offered places as world points, worked out in one walk of the line.
     *
     * <p>The distance form above is enough for the small maps, where a line is a handful of points
     * and a dozen candidates. The world map draws lines that are tens of thousands of points long,
     * and there a point per candidate means walking the whole polyline once per candidate -- the
     * square of the line's length, every frame. This walk produces the points themselves, so the
     * caller can cull them in world space before asking {@link #find} about the handful that are
     * anywhere near the screen.
     */
    public static List<Candidate> candidatePoints(List<double[]> points) {
        List<Candidate> candidates = new ArrayList<>();
        if (points == null || points.size() < 2) {
            return candidates;
        }
        double length = length(points);
        // The two ends first: they sit outside the line and are extrapolated, which is why they are
        // not part of the walk below.
        double[] start = extrapolate(points.get(1), points.get(0), END_MARGIN_BLOCKS);
        if (start != null) {
            candidates.add(new Candidate(start[0], start[1], -END_MARGIN_BLOCKS, start[2], start[3]));
        }
        double travelled = 0;
        double nextAt = SPACING_BLOCKS;
        for (int i = 1; i < points.size(); i++) {
            double ax = points.get(i - 1)[0];
            double az = points.get(i - 1)[1];
            double bx = points.get(i)[0];
            double bz = points.get(i)[1];
            double edge = Math.hypot(bx - ax, bz - az);
            if (edge < 1.0E-9) {
                continue;
            }
            while (nextAt <= travelled + edge && nextAt < length) {
                double along = (nextAt - travelled) / edge;
                candidates.add(new Candidate(ax + (bx - ax) * along, az + (bz - az) * along, nextAt,
                        (bx - ax) / edge, (bz - az) / edge));
                nextAt += SPACING_BLOCKS;
            }
            travelled += edge;
        }
        double[] end = extrapolate(points.get(points.size() - 2), points.get(points.size() - 1),
                END_MARGIN_BLOCKS);
        if (end != null) {
            candidates.add(new Candidate(end[0], end[1], length + END_MARGIN_BLOCKS, end[2], end[3]));
        }
        return candidates;
    }

    /** The drawn length of a polyline, in blocks. */
    public static double length(List<double[]> points) {
        double total = 0;
        for (int i = 1; i < points.size(); i++) {
            total += Math.hypot(points.get(i)[0] - points.get(i - 1)[0],
                    points.get(i)[1] - points.get(i - 1)[1]);
        }
        return total;
    }

    /**
     * The point a given distance along a polyline, with the direction the line runs there.
     *
     * <p>Distances outside the polyline are extrapolated along its first and last edges, which is
     * what places the two end candidates just outside the line: so a line whose name has already
     * been placed at a terminus still offers a spot further out rather than one on top of it.
     *
     * @return {@code {x, z, dirX, dirZ}}, or null when the polyline has no usable edge
     */
    public static double[] pointAt(List<double[]> points, double distance) {
        if (points == null || points.size() < 2) {
            return null;
        }
        double total = length(points);
        if (distance <= 0) {
            return extrapolate(points.get(1), points.get(0), -distance);
        }
        if (distance >= total) {
            int last = points.size() - 1;
            return extrapolate(points.get(last - 1), points.get(last), distance - total);
        }
        double travelled = 0;
        for (int i = 1; i < points.size(); i++) {
            double ax = points.get(i - 1)[0];
            double az = points.get(i - 1)[1];
            double bx = points.get(i)[0];
            double bz = points.get(i)[1];
            double edge = Math.hypot(bx - ax, bz - az);
            if (edge < 1.0E-9) {
                continue;
            }
            if (distance <= travelled + edge) {
                double along = (distance - travelled) / edge;
                return new double[]{ax + (bx - ax) * along, az + (bz - az) * along,
                        (bx - ax) / edge, (bz - az) / edge};
            }
            travelled += edge;
        }
        return null;
    }

    /**
     * One point and its direction, extrapolated {@code beyond} blocks past {@code from} away from
     * {@code towards}.
     */
    private static double[] extrapolate(double[] towards, double[] from, double beyond) {
        double dx = from[0] - towards[0];
        double dz = from[1] - towards[1];
        double edge = Math.hypot(dx, dz);
        if (edge < 1.0E-9) {
            return null;
        }
        return new double[]{from[0] + dx / edge * beyond, from[1] + dz / edge * beyond,
                dx / edge, dz / edge};
    }

    /**
     * Looks for a place for one capsule, starting at the candidate and stepping along the line when
     * the place is taken.
     *
     * <p>The steps alternate directions so a spot just past the first choice is preferred over one
     * a long way down the line, which keeps the name near the stretch it was offered for. Every step
     * tests the painted rectangle against the map's own fit test, and the first that fits wins; none
     * fitting is the answer "this spot is left unnamed".
     *
     * @param points  the line's polyline, in blocks
     * @param distance the candidate's distance along that polyline
     * @param width   the painted capsule's width in screen pixels
     * @param height  the painted capsule's height in screen pixels
     * @param risePx  how far above the line the capsule's centre sits, in screen pixels
     * @param screen  the map's projection and fit test
     * @return {@code {centreX, centreY}} in screen pixels, or null when every step is taken
     */
    public static double[] find(List<double[]> points, double distance, double width, double height,
                                double risePx, Screen screen) {
        for (int step = 0; step <= OFFSET_STEPS * 2; step++) {
            double at = distance + offsetAt(step);
            double[] point = pointAt(points, at);
            if (point == null) {
                continue;
            }
            double centreX = screen.screenX(point[0], point[1]);
            double centreY = screen.screenY(point[0], point[1]) - risePx;
            double halfWidth = width / 2.0;
            double halfHeight = height / 2.0;
            if (screen.fits(centreX - halfWidth, centreY - halfHeight,
                    centreX + halfWidth, centreY + halfHeight)) {
                return new double[]{centreX, centreY};
            }
        }
        return null;
    }

    /**
     * The same search, for a candidate whose world point has already been worked out.
     *
     * <p>The distance form walks the polyline to find each trial's point, which is fine for the
     * small maps' short polylines and the wrong shape for a line read out of MTR: a railway's
     * geometry is sampled every few blocks, so one trial per step would be a walk of the whole line.
     * This form steps along the line's local direction from the candidate's own point instead --
     * the same place to within a couple of blocks, in constant time.
     */
    public static double[] find(Candidate candidate, double width, double height, double risePx,
                                Screen screen) {
        if (candidate == null) {
            return null;
        }
        double halfWidth = width / 2.0;
        double halfHeight = height / 2.0;
        for (int step = 0; step <= OFFSET_STEPS * 2; step++) {
            double along = offsetAt(step);
            double worldX = candidate.x() + candidate.dirX() * along;
            double worldZ = candidate.z() + candidate.dirZ() * along;
            double centreX = screen.screenX(worldX, worldZ);
            double centreY = screen.screenY(worldX, worldZ) - risePx;
            if (screen.fits(centreX - halfWidth, centreY - halfHeight,
                    centreX + halfWidth, centreY + halfHeight)) {
                return new double[]{centreX, centreY};
            }
        }
        return null;
    }

    /**
     * The parts of a world-space polyline that land on a map's frame, each as its own run of points.
     *
     * <p>For a caller that draws every frame over a line whose geometry may be tens of thousands of
     * points: the candidates and the placer are only worth running over the stretch that is on the
     * map, and this is the one walk that decides which stretches those are. A run keeps the point
     * before the first one inside and the point after the last, so a line that crosses the frame in
     * one long edge still hands the placer both ends of the crossing.
     *
     * <p>The screen is passed in rather than a projection function so the same object is used for
     * the culling and for the placing: a point kept here and a point rejected there would be two
     * readings of one frame.
     */
    public static List<List<double[]>> visibleRuns(List<double[]> points, Screen screen,
                                                   double left, double top, double right,
                                                   double bottom) {
        List<List<double[]>> runs = new ArrayList<>();
        if (points == null || points.size() < 2) {
            return runs;
        }
        double previousX = screen.screenX(points.get(0)[0], points.get(0)[1]);
        double previousY = screen.screenY(points.get(0)[0], points.get(0)[1]);
        List<double[]> current = inside(previousX, previousY, left, top, right, bottom)
                ? new ArrayList<>() : null;
        if (current != null) {
            current.add(points.get(0));
        }
        for (int i = 1; i < points.size(); i++) {
            double x = screen.screenX(points.get(i)[0], points.get(i)[1]);
            double y = screen.screenY(points.get(i)[0], points.get(i)[1]);
            boolean visible = touches(previousX, previousY, x, y, left, top, right, bottom);
            if (visible) {
                if (current == null) {
                    current = new ArrayList<>();
                    current.add(points.get(i - 1));
                }
                current.add(points.get(i));
            } else if (current != null) {
                runs.add(current);
                current = null;
            }
            previousX = x;
            previousY = y;
        }
        if (current != null) {
            runs.add(current);
        }
        return runs;
    }

    /** Whether a point is inside a screen rectangle. */
    private static boolean inside(double x, double y, double left, double top, double right,
                                  double bottom) {
        return x >= left && x <= right && y >= top && y <= bottom;
    }

    /** Whether a screen-space edge has any part inside a screen rectangle. */
    private static boolean touches(double x1, double y1, double x2, double y2,
                                   double left, double top, double right, double bottom) {
        return Math.max(x1, x2) >= left && Math.min(x1, x2) <= right
                && Math.max(y1, y2) >= top && Math.min(y1, y2) <= bottom;
    }

    /** The step a trial takes, in blocks: 0, +1, -1, +2, -2 ... times the step distance. */
    private static double offsetAt(int step) {
        if (step == 0) {
            return 0;
        }
        int magnitude = (step + 1) / 2;
        double signed = (step % 2 == 1) ? magnitude : -magnitude;
        return signed * OFFSET_STEP_BLOCKS;
    }

    /** The painted width of a capsule holding the given name, in screen pixels. */
    public static double width(double nameWidthUnits) {
        return (nameWidthUnits + PAD_X * 2.0) * NAME_FACTOR;
    }

    /** The painted height of a capsule, in screen pixels. */
    public static double height(double lineHeightUnits) {
        return (lineHeightUnits + PAD_Y * 2.0) * NAME_FACTOR;
    }
}
