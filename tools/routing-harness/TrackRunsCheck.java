package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.road.RoadClass;
import bili.dongsz.howtogo.road.RoadNetwork;
import bili.dongsz.howtogo.road.RoadSegment;

import java.util.List;

/**
 * Checks how a line's track is turned into the polylines the map draws: that a track known in several
 * places is drawn as several stretches, that nothing is drawn across the stretches that are missing,
 * that a stretch is written the way the track runs rather than the way it happens to be stored, and
 * that two pieces a walk joins on paper are joined only where they meet on the ground.
 *
 * <h2>What was wrong</h2>
 * The drawn shape used to be one polyline built by concatenating every stretch of a line's track. On a
 * reading that arrives a window at a time a line's track is known in pieces, so concatenating them
 * drew a straight line from the end of each piece to the start of the next -- a chord across whatever
 * lies between, belonging to no track, on a map where the line otherwise followed its rails. The
 * checks below are the shape of the fix: the gap is a gap, and the only lines drawn are the ones whose
 * geometry the reading actually has.
 *
 * <p>The same defect arrived later by the other door, and {@link #aCornerIsNotAChord} is what holds it
 * shut: the walk that orders a line's pieces is an order and nothing more, so a piece on it may have to
 * be written backwards to follow it, and a piece that was written as stored instead left the stretch at
 * an end the piece after it does not meet -- read as a join, the two were drawn across each other.
 *
 * <p>In the {@code client} package because that is where the walk lives; no rendering is involved, so
 * this runs without a game.
 */
public final class TrackRunsCheck {

    /** Longest step a stretch of this fixture may contain: its pieces are 100 blocks long. */
    private static final double LONGEST_PIECE = 100.5;

    private static int checks;
    private static int failures;

    private TrackRunsCheck() {
    }

    /**
     * Runs the checks.
     *
     * @return the number of checks made and the number that failed, in that order
     */
    public static int[] run() {
        checks = 0;
        failures = 0;
        System.out.println("== drawing a line's track ==");

        aGapIsDrawnAsAGap();
        oneStretchIsOnePolyline();
        aForkIsStretchesThatMeet();
        aCornerIsNotAChord();
        pointsAreKeptAndOrdered();
        nothingToDraw();
        aPairWithNoRouteIsAGap();
        plannedStretchesJoinOnlyWhereTheyMeet();

        return new int[] {checks, failures};
    }

    /** A track known near the player and again far away: two stretches, and no line between them. */
    private static void aGapIsDrawnAsAGap() {
        RoadNetwork track = new RoadNetwork();
        // Near: 0,0 -> 100,0 -> 200,0, one stretch of two pieces through a pass-through node.
        piece(track, 10, 1, 2, 0, 0, 100, 0);
        piece(track, 11, 2, 3, 100, 0, 200, 0);
        // Far: 2000,0 -> 2100,0. The stretch between the two is track MTR has not sent.
        piece(track, 12, 4, 5, 2000, 0, 2100, 0);

        List<List<double[]>> runs = TrackRuns.of(track);
        expect("a track known in two places is two stretches", runs.size() == 2);
        if (runs.size() == 2) {
            expect("one of them holding the near track and the other the far one",
                    holds(runs.get(0), 0, 0) != holds(runs.get(1), 0, 0)
                            && holds(runs.get(0), 2000, 0) != holds(runs.get(1), 2000, 0));
        }
        expect("and no stretch steps across the one that is missing",
                longestStep(runs) <= LONGEST_PIECE);
    }

    /** Pieces joined end to end are one polyline, and the point they share is written once. */
    private static void oneStretchIsOnePolyline() {
        RoadNetwork track = new RoadNetwork();
        piece(track, 10, 1, 2, 0, 0, 100, 0);
        // Stored from its far end back, nodes and all: the router writes a piece pointing whichever way
        // the ride went, so a track's own storage says nothing about which way along the line it runs.
        piece(track, 11, 3, 2, 200, 0, 100, 0);
        piece(track, 12, 3, 4, 200, 0, 300, 0);

        List<List<double[]>> runs = TrackRuns.of(track);
        expect("pieces joined end to end are one stretch", runs.size() == 1);
        if (runs.size() != 1) {
            return;
        }
        List<double[]> run = runs.get(0);
        expect("with the two shared ends written once each, not twice", run.size() == 4);
        expect("and every piece written the way the track runs rather than the way it was stored",
                xs(run).equals("0,100,200,300"));
    }

    /** A fork cannot be passed through, so it is two stretches -- which still meet at the fork. */
    private static void aForkIsStretchesThatMeet() {
        RoadNetwork track = new RoadNetwork();
        piece(track, 10, 1, 2, 0, 0, 100, 0);
        piece(track, 11, 2, 3, 100, 0, 100, 100);
        piece(track, 12, 2, 4, 100, 0, 200, 0);

        List<List<double[]>> runs = TrackRuns.of(track);
        expect("three arms at one node are three stretches", runs.size() == 3);
        boolean meet = true;
        for (List<double[]> run : runs) {
            meet = meet && holds(run, 100, 0);
        }
        expect("and all of them are drawn through the node they fork at, so the strokes meet",
                meet);
    }

    /**
     * Two pieces that both <em>start</em> at one node are two arms of a corner, and the walk that
     * orders a line's pieces is free to list them either way round.
     *
     * <p>This is the defect the map showed as stray straight lines: the walk is an order and nothing
     * else, so the first piece of it used to be written the way it was stored. Written that way, a
     * stretch can be left standing at the far end of its first piece, and the piece after it -- which
     * shares the node the two arms leave from, not the end the stretch has reached -- then matches
     * neither of that stretch's ends and used to be written as stored too, drawing a straight line
     * between two ends that lie however far apart the arms are. On a whole railway that is a handful of
     * scratches hundreds or thousands of blocks long, each of them belonging to no track, over a map
     * whose real lines are otherwise drawn along their rails.
     *
     * <p>What is checked is that the join is the geometry's and not the walk's: a piece whose nearer end
     * is not at the end of the stretch so far is a new stretch, and neither is drawn across the other.
     */
    private static void aCornerIsNotAChord() {
        // Two arms leaving one node, both stored from it: a short one and one sampled every 100 blocks.
        RoadNetwork arms = new RoadNetwork();
        piece(arms, 10, 1, 2, 0, 0, 10, 0);
        polyline(arms, 11, 1, 3, 0, 0, 0, 100, 0, 200, 0, 300);

        List<List<double[]>> armRuns = TrackRuns.of(arms);
        expect("two arms leaving one node are two stretches", armRuns.size() == 2);
        expect("and neither is drawn across the other: the longest step is the arm's own sampling, "
                        + "not the 300 blocks between the two far ends",
                longestStep(armRuns) <= LONGEST_PIECE);

        // The same, as a reading hands it over: the second arm's end was within the join distance of
        // the node the first arm made, so the marks gave both arms that node while their own first
        // vertices stand a block apart.
        RoadNetwork snapped = new RoadNetwork();
        polyline(snapped, 20, 1, 2, 65, 147, 66, 147, 66, 155);
        polyline(snapped, 21, 1, 3, 66, 147, 74, 147, 82, 147, 90, 147, 98, 147, 106, 147, 114, 147);

        List<List<double[]>> snappedRuns = TrackRuns.of(snapped);
        expect("a node two arms share is not a join between their far ends", snappedRuns.size() == 2);
        expect("so the 49 blocks between those ends are not drawn as track",
                longestStep(snappedRuns) <= 8.5);

        // And the join a corner really is still happens: one arm stored ending at the node the next one
        // leaves from is one stretch through the corner, and the walk's order is kept.
        RoadNetwork corner = new RoadNetwork();
        piece(corner, 30, 1, 2, 0, 0, 100, 0);
        piece(corner, 31, 2, 3, 100, 0, 100, 100);

        List<List<double[]>> cornerRuns = TrackRuns.of(corner);
        expect("an arm meeting the last one end to end is still one stretch",
                cornerRuns.size() == 1);
        expect("running through the corner in the order the walk gave",
                cornerRuns.size() == 1 && xs(cornerRuns.get(0)).equals("0,100,100"));
    }

    /** A stretch keeps its own vertices, at the resolution the reading gave them. */
    private static void pointsAreKeptAndOrdered() {
        RoadNetwork track = new RoadNetwork();
        // A rail sampled every block, which is what MTR's own rails look like.
        polyline(track, 10, 1, 2, 0, 0, 1, 0, 2, 0, 3, 0, 4, 0, 5, 0, 6, 0, 7, 0, 8, 0, 9, 0, 10, 0);
        // And a second rail meeting it at 10,0, to have a seam inside the stretch.
        polyline(track, 11, 2, 3, 10, 0, 11, 0, 12, 0);

        List<List<double[]>> runs = TrackRuns.of(track);
        expect("a rail and the rail it meets are one stretch", runs.size() == 1);
        if (runs.size() != 1) {
            return;
        }
        expect("a rail sampled every block keeps every point", runs.get(0).size() == 13);
        expect("and the point the two rails meet at is written once",
                xs(runs.get(0)).equals("0,1,2,3,4,5,6,7,8,9,10,11,12"));
    }

    /** No track at all is nothing to draw, not a point at the origin. */
    private static void nothingToDraw() {
        expect("no track is no stretch", TrackRuns.of(null).isEmpty());
        expect("and an empty track is no stretch", TrackRuns.of(new RoadNetwork()).isEmpty());

        RoadNetwork piece = new RoadNetwork();
        piece(piece, 10, 1, 2, 0, 0, 100, 0);
        expect("while one piece on its own is one stretch of two points",
                TrackRuns.of(piece).size() == 1 && TrackRuns.of(piece).get(0).size() == 2);
    }

    // ----------------------------------------------------------------- helpers

    /**
     * A line of the player's own, whose neighbouring pairs are planned one at a time: a pair with no
     * route must be left as a gap.
     *
     * <p>This is the shape of the defect that was reported: a line whose stops could not be joined by
     * the router was drawn as a straight line from one stop to the next, so a world with a city's worth
     * of such lines filled its map with straight lines that belonged to no path -- and the lines that
     * were real were lost among them.
     */
    private static void aPairWithNoRouteIsAGap() {
        List<double[]> first = line(0, 0, 100, 0);
        List<double[]> third = line(2000, 0, 2100, 0);

        List<List<double[]>> runs = TrackRuns.ofPlanned(
                java.util.Arrays.asList(first, null, third));
        expect("a pair with no route leaves the stretches either side of it apart", runs.size() == 2);
        expect("and neither stretch steps across the pair that could not be planned",
                longestStep(runs) <= LONGEST_PIECE + 0.5);
        expect("with the first stop of the line still drawn where the path begins",
                runs.isEmpty() || holds(runs.get(0), 0, 0));

        expect("a line with no planned pair at all draws nothing",
                TrackRuns.ofPlanned(java.util.Arrays.asList(null, null)).isEmpty());
        expect("as does a line whose routes came back as points rather than paths",
                TrackRuns.ofPlanned(java.util.Arrays.asList(null, List.of(), List.of(new double[]{1, 2})))
                        .isEmpty());
    }

    /** Two routes that meet at a stop are one stretch, and the stop is written once. */
    private static void plannedStretchesJoinOnlyWhereTheyMeet() {
        List<List<double[]>> meeting = TrackRuns.ofPlanned(List.of(
                line(0, 0, 100, 0, 200, 0),
                line(200, 0, 300, 0)));
        expect("routes meeting at a stop are one stretch", meeting.size() == 1);
        if (meeting.size() == 1) {
            expect("with the stop written once", meeting.get(0).size() == 4);
            expect("and the stretch running through the stops in order",
                    xs(meeting.get(0)).equals("0,100,200,300"));
        }

        // Two routes that do not meet: concatenating them would draw a straight line between them,
        // which is the same defect as a pair with no route.
        List<List<double[]>> apart = TrackRuns.ofPlanned(List.of(
                line(0, 0, 100, 0),
                line(2000, 0, 2100, 0)));
        expect("routes that do not meet are left apart", apart.size() == 2);
        expect("and no stretch is drawn between them", longestStep(apart) <= LONGEST_PIECE + 0.5);
    }

    /** A planned route, as the router hands one over: its points in order. */
    private static List<double[]> line(int... xz) {
        List<double[]> points = new java.util.ArrayList<>(xz.length / 2);
        for (int i = 0; i < xz.length; i += 2) {
            points.add(new double[] {xz[i], xz[i + 1]});
        }
        return points;
    }

    /** One piece of track between two nodes, with its vertices in the order given. */
    private static void piece(RoadNetwork net, int id, int fromNode, int toNode, int... xz) {
        polyline(net, id, fromNode, toNode, xz);
    }

    private static void polyline(RoadNetwork net, int id, int fromNode, int toNode, int... xz) {
        RoadSegment segment = RoadSegment.of(id, RoadClass.RAIL, 64, xz);
        segment.setFromNode(fromNode);
        segment.setToNode(toNode);
        net.addSegment(segment);
    }

    /** The longest step any stretch takes between two of its own points, in blocks. */
    private static double longestStep(List<List<double[]>> runs) {
        double longest = 0;
        for (List<double[]> run : runs) {
            for (int i = 1; i < run.size(); i++) {
                double[] before = run.get(i - 1);
                double[] here = run.get(i);
                longest = Math.max(longest, Math.hypot(here[0] - before[0], here[1] - before[1]));
            }
        }
        return longest;
    }

    private static boolean holds(List<double[]> run, int x, int z) {
        for (double[] point : run) {
            if (point[0] == x && point[1] == z) {
                return true;
            }
        }
        return false;
    }

    /** A stretch's x coordinates as a string, for the checks that are about order. */
    private static String xs(List<double[]> run) {
        StringBuilder text = new StringBuilder();
        for (double[] point : run) {
            if (text.length() > 0) {
                text.append(',');
            }
            text.append((long) point[0]);
        }
        return text.toString();
    }

    private static void expect(String what, boolean condition) {
        checks++;
        if (!condition) {
            failures++;
        }
        System.out.println((condition ? "  ok   " : "  FAIL ") + what);
    }
}
