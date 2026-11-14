package bili.dongsz.howtogo.client;

import java.util.ArrayList;
import java.util.List;

/**
 * Where a public transport line's name is offered on a map, and where it may stand.
 * A line's name is a capsule in the line's colour, horizontal like a badge, not ink along the
 * road. Candidates sit one every SPACING_BLOCKS of world distance and just outside each end, so
 * names stay put while the map moves and the count follows the line, not the screen.
 * The plate is never moved along the line: an earlier version stepped along it in blocks and at
 * close zoom threw plates hundreds of pixels off the line. It sits on the line raised by risePx,
 * tries one plate-height higher, and otherwise leaves the stretch unnamed. Shared by the world
 * map, viewer, picker and phone page so they cannot drift apart.
 */
public final class LineNameLabels {

    /** One name per this many blocks of drawn line, the spacing the player asked for. */
    public static final double SPACING_BLOCKS = 160.0;

    /** How many places one candidate offers in screen space: on the line, and one plate-height higher. */
    public static final int PLACES = 2;

    /** Room between the two places a candidate offers, in screen pixels. */
    public static final double PLACE_GAP_PX = 3.0;

    /**
     * The font factor a line's name is drawn at, on every map.
     * Not tied to a road's label zoom: the capsule is about the service, not the ground, and every
     * surface uses the same factor so a line's badge looks the same everywhere. 0.65 rather than
     * the original 0.8 because the player asked for one step smaller.
     */
    public static final float NAME_FACTOR = 0.65F;

    /** Room inside the capsule either side of the name, in unscaled font units. */
    public static final int PAD_X = 4;

    /** Room above and below the name inside the capsule, in unscaled font units. */
    public static final int PAD_Y = 2;

    private LineNameLabels() {
    }

    /**
     * The screen the placer is placing on: one map's projection and its own fit test. The fit test is the map's business, because that is where its other labels and panels live.
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
     * One offered place: world point, distance along the line, and the line's direction there.
     * Distance and direction say which way the stroke runs under the plate; the placer itself no
     * longer needs them to move it.
     */
    public record Candidate(double x, double z, double distance, double dirX, double dirZ) {
    }

    /**
     * The places a line's name is offered at, worked out in one walk of the line.
     * Every SPACING_BLOCKS of drawn line plus both ends exactly -- the terminus is where a passenger
     * reads a name. One walk keeps the world map's per-frame cost a function of the screen, not the railway.
     */
    public static List<Candidate> candidatePoints(List<double[]> points) {
        List<Candidate> candidates = new ArrayList<>();
        if (points == null || points.size() < 2) {
            return candidates;
        }
        double length = 0.0;
        for (int i = 1; i < points.size(); i++) {
            length += Math.hypot(points.get(i)[0] - points.get(i - 1)[0],
                    points.get(i)[1] - points.get(i - 1)[1]);
        }
        if (!(length > 0.0)) {
            return candidates;
        }
        // The two ends first, as the ends of the drawn line.
        double[] startDirection = directionOf(points.get(0), points.get(1));
        if (startDirection != null) {
            candidates.add(new Candidate(points.get(0)[0], points.get(0)[1], 0.0,
                    startDirection[0], startDirection[1]));
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
        int last = points.size() - 1;
        double[] endDirection = directionOf(points.get(last - 1), points.get(last));
        if (endDirection != null) {
            candidates.add(new Candidate(points.get(last)[0], points.get(last)[1], length,
                    endDirection[0], endDirection[1]));
        }
        return candidates;
    }

    /**
     * Looks for a place for one capsule, starting at the candidate and lifting it one plate-height
     * when that place is taken; the plate is never moved along the line. A candidate whose places are
     * both taken is left to the next one or not drawn.
     * @param width the painted capsule's width in screen pixels
     * @param height the painted capsule's height in screen pixels
     * @param risePx how far above the line the capsule's centre sits, in screen pixels
     * @param screen the map's projection and fit test
     */
    public static double[] find(Candidate candidate, double width, double height, double risePx,
                                Screen screen) {
        if (candidate == null || screen == null) {
            return null;
        }
        double centreX = screen.screenX(candidate.x(), candidate.z());
        double baseY = screen.screenY(candidate.x(), candidate.z()) - risePx;
        double halfWidth = width / 2.0;
        double halfHeight = height / 2.0;
        for (int place = 0; place < PLACES; place++) {
            double centreY = baseY - place * (height + PLACE_GAP_PX);
            if (screen.fits(centreX - halfWidth, centreY - halfHeight,
                    centreX + halfWidth, centreY + halfHeight)) {
                return new double[]{centreX, centreY};
            }
        }
        return null;
    }

    /** The unit direction from one point to another, or null when they are the same place. */
    private static double[] directionOf(double[] from, double[] to) {
        double dx = to[0] - from[0];
        double dz = to[1] - from[1];
        double edge = Math.hypot(dx, dz);
        if (edge < 1.0E-9) {
            return null;
        }
        return new double[]{dx / edge, dz / edge};
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
