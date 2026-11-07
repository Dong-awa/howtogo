package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.road.RoadClass;
import bili.dongsz.howtogo.road.RoadNetwork;
import bili.dongsz.howtogo.road.RoadNode;
import bili.dongsz.howtogo.road.RoadSegment;

import java.util.List;

/**
 * The strokes the maps draw, checked for a line the network does not contain.
 *
 * <h2>What went wrong, and why it is checked without a game</h2>
 * The maps that stroke whole roads -- the main screen ({@code K}), the destination picker's preview,
 * the navigation panel's mini-map and the Xaero's-Minimap overlay -- used to decide which end of each
 * piece to start from by asking which end was nearer the last point the drawing pass had written.
 * That is right for pieces appended at the far end of the stroke and <em>wrong for pieces prepended
 * at the near end</em>: a prepended piece has no "previous point" yet when the decision is made, so
 * a piece stored pointing away from the joint was drawn joint-to-far-end and the next piece then
 * started at the joint again, painting a straight chord of the whole piece across whatever the road
 * bends around. Xaero's world map draws segment by segment and so never showed it, which is why the
 * defect could be reported as "only the world map is clean".
 *
 * <p>The geometry is pure: a network in, a polyline out, and the property that the polyline follows
 * the road is arithmetic. So it is checked here rather than by looking at a map. The property is
 * stated as what a screenshot shows: a stroke must not step from one piece to the next without the
 * two ends being the same point -- see {@link DisplayStrokes.Jump}.
 */
public final class DisplayStrokesCheck {

    private static int checks;
    private static int failures;

    private DisplayStrokesCheck() {
    }

    public static int[] run() {
        checks = 0;
        failures = 0;
        System.out.println("== the maps' road strokes ==");
        forwardJoins();
        backwardJoins();
        mixedDirectionJoins();
        sharpElbowEndsTheStroke();
        jointIsWrittenOnce();
        jumpIsMeasured();
        cacheFollowsTheNetwork();
        System.out.println(failures == 0 ? "  road strokes ok (" + checks + " checks)"
                : "  road strokes FAILED: " + failures + " of " + checks);
        return new int[]{checks, failures};
    }

    /**
     * Two pieces stored in the direction the stroke runs, joined at a junction: one stroke, no step.
     *
     * <p>The ordinary case, kept as the floor the other cases are read against: if this one jumped,
     * the measurement itself would be wrong.
     */
    private static void forwardJoins() {
        System.out.println("   two pieces stored along the stroke");
        RoadNetwork network = new RoadNetwork();
        RoadNode start = network.addNode(-200, 64, 0, RoadNode.Type.ENDPOINT, null);
        RoadNode middle = network.addNode(0, 64, 0, RoadNode.Type.JUNCTION, null);
        RoadNode end = network.addNode(0, 64, 200, RoadNode.Type.ENDPOINT, null);
        addSegment(network, RoadClass.ROAD, start, middle, -200, 0, 0, 0);
        addSegment(network, RoadClass.ROAD, middle, end, 0, 0, 0, 200);

        List<DisplayStrokes.Stroke> strokes = DisplayStrokes.build(network);
        expect("the two pieces are one stroke", strokes.size() == 1);
        expect("with two pieces", strokes.get(0).segmentCount() == 2);
        expect("and it does not jump", noJumps(network));
        expect("and it is drawn as three points", points(network, strokes.get(0)).size() == 3);
    }

    /**
     * The case that drew the ghost line: a piece prepended at the near end, stored pointing away
     * from the joint.
     *
     * <p>{@code N -> A} is prepended in front of a stroke that leaves {@code N} towards {@code B}.
     * Drawn in stored order it runs from {@code N} out to {@code A} and the stroke then starts at
     * {@code N} again -- a chord the length of the piece, which is the straight line a player sees.
     */
    private static void backwardJoins() {
        System.out.println("   a prepended piece stored away from the joint");
        RoadNetwork network = new RoadNetwork();
        RoadNode joint = network.addNode(0, 64, 0, RoadNode.Type.JUNCTION, null);
        RoadNode west = network.addNode(-400, 64, 0, RoadNode.Type.ENDPOINT, null);
        RoadNode north = network.addNode(0, 64, 400, RoadNode.Type.ENDPOINT, null);
        // Stored N -> A, so the piece points away from the joint it will be prepended at.
        addSegment(network, RoadClass.ROAD, joint, west, 0, 0, -400, 0);
        addSegment(network, RoadClass.ROAD, joint, north, 0, 0, 0, 400);

        List<DisplayStrokes.Stroke> strokes = DisplayStrokes.build(network);
        expect("the two pieces are one stroke", strokes.size() == 1);
        DisplayStrokes.Stroke stroke = strokes.get(0);
        expect("and the stroke does not jump", noJumps(network));

        // Whichever free end the stroke starts from, it has to run free end -> joint -> free end:
        // the prepended piece is drawn from its far end, so the joint is never used as a turning
        // point for a chord.
        List<double[]> drawn = points(network, stroke);
        expect("three points: the two free ends and the joint once", drawn.size() == 3);
        boolean endsAreFree = (same(drawn.get(0), -400, 0) && same(drawn.get(2), 0, 400))
                || (same(drawn.get(0), 0, 400) && same(drawn.get(2), -400, 0));
        expect("the ends are the two free ends", endsAreFree);
        expect("and the joint is written in the middle", same(drawn.get(1), 0, 0));
    }

    /**
     * Four pieces around one junction, stored in every combination of directions: no arrangement
     * may produce a step that is not a piece's own geometry.
     */
    private static void mixedDirectionJoins() {
        System.out.println("   four pieces around one junction, stored every way round");
        for (int mask = 0; mask < 16; mask++) {
            RoadNetwork network = new RoadNetwork();
            RoadNode joint = network.addNode(0, 64, 0, RoadNode.Type.JUNCTION, null);
            RoadNode north = network.addNode(0, 64, 100, RoadNode.Type.ENDPOINT, null);
            RoadNode east = network.addNode(100, 64, 0, RoadNode.Type.ENDPOINT, null);
            RoadNode south = network.addNode(0, 64, -100, RoadNode.Type.ENDPOINT, null);
            RoadNode west = network.addNode(-100, 64, 0, RoadNode.Type.ENDPOINT, null);
            arm(network, joint, north, 0, 100, (mask & 1) != 0);
            arm(network, joint, east, 100, 0, (mask & 2) != 0);
            arm(network, joint, south, 0, -100, (mask & 4) != 0);
            arm(network, joint, west, -100, 0, (mask & 8) != 0);
            boolean ok = true;
            for (DisplayStrokes.Stroke stroke : DisplayStrokes.build(network)) {
                ok &= DisplayStrokes.jumps(network, stroke).isEmpty();
            }
            expect("orientation " + mask + " draws no jump", ok);
        }
    }

    /** A turn too sharp to be one road ends the stroke; neither stroke jumps. */
    private static void sharpElbowEndsTheStroke() {
        System.out.println("   a sharp elbow");
        RoadNetwork network = new RoadNetwork();
        RoadNode joint = network.addNode(0, 64, 0, RoadNode.Type.JUNCTION, null);
        RoadNode east = network.addNode(100, 64, 0, RoadNode.Type.ENDPOINT, null);
        // Leaving the joint towards the north-east, against the way the first piece arrives from
        // the east: a turn of about 174 degrees, which is a hairpin and not a continuation.
        RoadNode back = network.addNode(100, 64, 10, RoadNode.Type.ENDPOINT, null);
        addSegment(network, RoadClass.ROAD, east, joint, 100, 0, 0, 0);
        addSegment(network, RoadClass.ROAD, joint, back, 0, 0, 100, 10);

        expect("a hairpin is two strokes", DisplayStrokes.build(network).size() == 2);
        expect("and neither jumps", noJumps(network));
    }

    /** A joint shared by two pieces is drawn once: twice would double the translucent paint. */
    private static void jointIsWrittenOnce() {
        System.out.println("   the joint between two pieces");
        RoadNetwork network = new RoadNetwork();
        RoadNode start = network.addNode(0, 64, 0, RoadNode.Type.ENDPOINT, null);
        RoadNode middle = network.addNode(100, 64, 0, RoadNode.Type.JUNCTION, null);
        RoadNode end = network.addNode(200, 64, 0, RoadNode.Type.ENDPOINT, null);
        addSegment(network, RoadClass.ROAD, start, middle, 0, 0, 100, 0);
        addSegment(network, RoadClass.ROAD, middle, end, 100, 0, 200, 0);

        List<double[]> drawn = points(network, DisplayStrokes.build(network).get(0));
        expect("three points, not four", drawn.size() == 3);
        expect("the shared joint is the middle one", same(drawn.get(1), 100, 0));
    }

    /** The jump measurement itself: a stroke that steps across a gap must report that gap. */
    private static void jumpIsMeasured() {
        System.out.println("   the measurement");
        RoadNetwork network = new RoadNetwork();
        RoadNode start = network.addNode(0, 64, 0, RoadNode.Type.ENDPOINT, null);
        RoadNode middle = network.addNode(100, 64, 0, RoadNode.Type.JUNCTION, null);
        RoadNode end = network.addNode(900, 64, 0, RoadNode.Type.ENDPOINT, null);
        addSegment(network, RoadClass.ROAD, start, middle, 0, 0, 100, 0);
        addSegment(network, RoadClass.ROAD, middle, end, 100, 0, 900, 0);
        DisplayStrokes.Stroke sound = DisplayStrokes.build(network).get(0);
        expect("a sound stroke reports no jump", noJumps(network));

        // The far piece drawn from its far end: the wrong orientation the old pass could choose,
        // and what it would have painted is exactly the ghost line.
        DisplayStrokes.Stroke wrong = new DisplayStrokes.Stroke(List.of(
                new DisplayStrokes.Piece(sound.pieces().get(0).segmentId(), false),
                new DisplayStrokes.Piece(sound.pieces().get(1).segmentId(), true)));
        List<DisplayStrokes.Jump> jumps = DisplayStrokes.jumps(network, wrong);
        expect("a piece drawn backwards across the joint is one jump", jumps.size() == 1);
        expect("and it is the whole piece long", jumps.size() == 1
                && Math.abs(jumps.get(0).distance() - 800) < 0.5);
    }

    /** The cache answers for the network it was given, and notices a change. */
    private static void cacheFollowsTheNetwork() {
        System.out.println("   the cached reading");
        RoadNetwork network = new RoadNetwork();
        RoadNode start = network.addNode(0, 64, 0, RoadNode.Type.ENDPOINT, null);
        RoadNode end = network.addNode(100, 64, 0, RoadNode.Type.ENDPOINT, null);
        addSegment(network, RoadClass.ROAD, start, end, 0, 0, 100, 0);

        List<DisplayStrokes.Stroke> first = DisplayStrokes.cached(network);
        List<DisplayStrokes.Stroke> second = DisplayStrokes.cached(network);
        expect("the same reading comes back for an unchanged network", first == second);

        RoadNode extra = network.addNode(200, 64, 0, RoadNode.Type.ENDPOINT, null);
        addSegment(network, RoadClass.ROAD, end, extra, 100, 0, 200, 0);
        List<DisplayStrokes.Stroke> third = DisplayStrokes.cached(network);
        expect("a changed network is read again", third != first);
    }

    // ---------------------------------------------------------------------- helpers

    private static boolean noJumps(RoadNetwork network) {
        for (DisplayStrokes.Stroke stroke : DisplayStrokes.build(network)) {
            if (!DisplayStrokes.jumps(network, stroke).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private static List<double[]> points(RoadNetwork network, DisplayStrokes.Stroke stroke) {
        return DisplayStrokes.points(network, stroke);
    }

    private static boolean same(double[] point, double x, double z) {
        return Math.abs(point[0] - x) < 0.01 && Math.abs(point[1] - z) < 0.01;
    }

    /** One arm of a junction, stored either joint-to-endpoint or endpoint-to-joint. */
    private static void arm(RoadNetwork network, RoadNode joint, RoadNode end,
                            int endX, int endZ, boolean jointFirst) {
        if (jointFirst) {
            addSegment(network, RoadClass.ROAD, joint, end, 0, 0, endX, endZ);
        } else {
            addSegment(network, RoadClass.ROAD, end, joint, endX, endZ, 0, 0);
        }
    }

    /** Adds a piece whose geometry runs from the {@code from} node to the {@code to} node. */
    private static void addSegment(RoadNetwork network, RoadClass roadClass, RoadNode from, RoadNode to,
                                   double... xz) {
        RoadSegment segment = network.newSegment(roadClass, from.y(), xz.length / 2);
        for (int i = 0; i + 1 < xz.length; i += 2) {
            segment.addVertex((int) xz[i], (int) xz[i + 1]);
        }
        segment.setFromNode(from.id());
        segment.setToNode(to.id());
        network.putSegment(segment);
    }

    private static void expect(String what, boolean ok) {
        checks++;
        if (ok) {
            System.out.println("     ok: " + what);
        } else {
            failures++;
            System.out.println("     FAIL: " + what);
        }
    }
}
