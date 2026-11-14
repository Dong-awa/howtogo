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
 * <h2>Why the plate never wanders down the line</h2>
 * An earlier version hunted for a free place by stepping <em>along the line in blocks</em>, and at a
 * close zoom that is the worst possible unit: twenty pixels a block turns one twenty-four-block step
 * into nearly five hundred pixels, so a plate whose first place was taken ended up over empty ground
 * hundreds of pixels from the line it names -- reported from play as "the name is floating far away;
 * at least keep it near the line". A block is the wrong unit for a screen-space object, and stepping
 * along a stored direction is the wrong geometry for a line that bends.
 *
 * <p>So the rule is now the one a reader assumes: the plate sits on its line, raised just clear of
 * the stroke ({@code risePx}, the caller's own). If that place is taken it is tried one plate-height
 * higher -- still beside the line -- and if that is taken too the stretch is simply left unnamed. A
 * name is a badge for a service, and a plate over ground the service does not run along is worse than
 * no plate at all. The cost of this is that a crowded map shows fewer line names; the alternative is
 * a name nobody can attach to a line.
 *
 * <p>The geometry lives here, once, so the world map, the main screen's viewer, the destination
 * picker and the phone page cannot drift apart about where a line's name goes or which spots are
 * worth trying. (The minimaps do not name lines at all: a fixed-size capsule on a hundred-pixel map
 * is most of the map, so those two surfaces carry the coloured line and nothing else.)
 */
public final class LineNameLabels {

    /** One name per this many blocks of drawn line, the spacing the player asked for. */
    public static final double SPACING_BLOCKS = 160.0;

    /**
     * How many places one candidate offers, in screen space.
     *
     * <p>Two, and both of them on the line: the plate's own place above the stroke, and one
     * plate-height higher. More than that would be the old hunt again -- a plate that has left the
     * line's neighbourhood is a label for whatever it ended up over.
     */
    public static final int PLACES = 2;

    /** Room between the two places a candidate offers, in screen pixels. */
    public static final double PLACE_GAP_PX = 3.0;

    /**
     * The font factor a line's name is drawn at, on every map.
     *
     * <p>Not tied to a road's label zoom, and not the sign card's smaller fraction either: the
     * capsule is the one thing on the map that is about the service rather than about the ground,
     * so it is drawn at a readable size of its own and the plate is sized around it. All the maps
     * that draw one use the same factor so the same line's badge is the same badge wherever it
     * appears.
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
     * panels and its frame live -- the placer only knows what box it needs and whether that box is
     * free on this screen.
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
     * One offered place: its world point, the distance along the line, and the line's direction there.
     *
     * <p>The distance and the direction are kept because a candidate is a place <em>on</em> the line
     * rather than merely a point near it: the direction says which way the stroke runs under the
     * plate, which is what lets a future map draw the capsule turned with a steep line if it ever
     * wants to. Nothing in the placer needs them to move the plate any more -- see the class comment.
     */
    public record Candidate(double x, double z, double distance, double dirX, double dirZ) {
    }

    /**
     * The places a line's name is offered at, as world points, worked out in one walk of the line.
     *
     * <p>Every {@link #SPACING_BLOCKS} of the drawn line, plus both of its ends exactly -- the
     * terminus is the one place on a line a passenger reads a name from. The end places used to sit
     * 24 blocks <em>outside</em> the line, which is invisible on a zoomed-out map and absurd on a
     * close one: at twenty pixels a block the "just past the terminus" plate was nearly five hundred
     * pixels past it. A plate centred on the end already reaches half its own width past the line,
     * which is the look the end places were for.
     *
     * <p>One walk rather than one walk per candidate, because a line read out of MTR is sampled every
     * few blocks and tens of thousands of points long: the places can be refused in world space
     * before the placer is asked about them, which is what keeps the world map's cost per frame a
     * function of the screen rather than of the railway.
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
     * Looks for a place for one capsule, starting at the candidate itself and lifting it one row
     * when that place is taken.
     *
     * <p>The plate is never moved along the line: see the class comment for the play-reported drift
     * that rule replaced. Every place tried is within one plate-height of the line, so a name that is
     * drawn at all is drawn next to the thing it names, and one whose places are both taken is left
     * for the next candidate or not drawn at all.
     *
     * @param candidate the offered place, in world blocks
     * @param width     the painted capsule's width in screen pixels
     * @param height    the painted capsule's height in screen pixels
     * @param risePx    how far above the line the capsule's centre sits, in screen pixels
     * @param screen    the map's projection and fit test
     * @return {@code {centreX, centreY}} in screen pixels, or null when every place is taken
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
