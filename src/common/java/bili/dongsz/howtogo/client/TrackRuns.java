package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.road.RoadChains;
import bili.dongsz.howtogo.road.RoadNetwork;
import bili.dongsz.howtogo.road.RoadSegment;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The stretches of track a line's marks actually form, each as one polyline, ready to draw.
 *
 * <h2>Why a track is several polylines and not one</h2>
 * A line's marks are the track that is <em>known</em>, and on a reading that arrives a window at a time
 * that is not the whole line: the stretch near the player is there and the rest is not. The marks are
 * therefore built as one road per stretch, with the gaps left as gaps -- see
 * {@link MtrLineTracks}, which refuses to cross one. Reading them back as a single polyline undoes
 * that decision at the last moment: the drawing joins the end of one stretch to the start of the next
 * with a straight line, and on a line whose track is known in three places that is two chords across
 * whatever lies between them. Those chords are the defect this class exists to remove: they belong to
 * no track, they cross ground the line does not run over, and on a whole city's worth of lines they
 * read as a map full of stray straight scratches.
 *
 * <h2>Why the walk rather than the storage</h2>
 * A network keeps its segments in a hash map keyed by id, so the order they come out of it is an
 * artefact of hashing and not the order the line runs in. Concatenating them as they come out draws a
 * hop from wherever one piece happens to land to wherever the next one does. Each piece is therefore
 * written along the walk that joins it to its neighbours, which is what {@link RoadChains} answers
 * with, and a piece whose own vertices run the other way is written backwards rather than as stored.
 *
 * <p>Within a stretch, a shared point is written once: two pieces meeting at a node are one place, and
 * writing it twice leaves a zero-length step that every measurement along the track -- a label's
 * position, the length a name has to fit in -- counts as a corner that is not there.
 *
 * <h2>Why the walk is checked against the geometry</h2>
 * The walk orders the pieces and says which two are joined, and that order is a real one. Which way round
 * each piece is written is a separate question, and the node is no help with it: a node is where two
 * pieces meet, and two pieces that both <em>start</em> at one node are the two arms of a corner, which
 * the walk's order is travelled by writing the first of them backwards -- something it does not say and
 * the marks do not record. Written as stored instead, the stretch ends at the far end of that piece, and
 * the piece after it meets that end at neither of its own: the two are joined on paper and nowhere on
 * the ground, and what the map draws between them is a straight line of however far apart those ends
 * happen to be -- hundreds of blocks of track belonging to no railway, which is this class's own defect
 * arriving by the walk rather than by the concatenation.
 *
 * <p>So a piece is written whichever way round brings its nearer end to the end of the stretch so far,
 * and the two are joined only when that end really is at it. A piece whose nearer end is further off
 * than {@link #PIECE_JOIN} is not a continuation of the stretch at all -- the stretch ends and a new one
 * begins with that piece, which is the answer the class already gives to a gap in the reading, and the
 * honest one: the map shows the track it has and invents none.
 */
public final class TrackRuns {

    /**
     * How close the end of one piece and the start of the next have to be to be the same place, in
     * blocks.
     *
     * <p>The scale the marks themselves are joined at -- see {@link MtrLineTracks#JOIN_DISTANCE} -- and
     * the same number rather than a second copy of it.
     *
     * <p>Applied at the seam between two pieces of one stretch and nowhere else. At interior vertices
     * the geometry is written as it is: a rail sampled every block must keep every point, and a
     * threshold applied throughout -- which is what this replaced -- quietly halved the resolution of
     * every curve.
     */
    private static final double SEAM_SLACK = MtrLineTracks.JOIN_DISTANCE;

    /**
     * How far apart the nearest ends of two pieces of one walk may be and still be one place, in blocks.
     *
     * <p>Twice {@link #SEAM_SLACK}, because two pieces meet through a node the marks made for them and
     * each of their own ends may be that far from it: a mark's node is put at the first end that asked
     * for one and a later end within {@code JOIN_DISTANCE} of it takes that node rather than making
     * another, so the two ends can stand that far apart while being the same place. Joining at this
     * distance therefore keeps every join the marks made, and refuses only the ones they did not.
     */
    private static final double PIECE_JOIN = 2.0 * MtrLineTracks.JOIN_DISTANCE;

    private TrackRuns() {
    }

    /**
     * The stretches of a track, in no particular order, each in the order it is travelled.
     *
     * @param track the line's marks, or null when the track is not known
     * @return one polyline per stretch, each of at least two points; empty when there is no track
     */
    public static List<List<double[]>> of(RoadNetwork track) {
        List<List<double[]>> runs = new ArrayList<>();
        if (track == null || track.segmentCount() == 0) {
            return runs;
        }
        Set<Integer> written = new HashSet<>();
        for (RoadSegment seed : track.segmentsSnapshot()) {
            if (written.contains(seed.id())) {
                continue;
            }
            List<Integer> walk = RoadChains.chainContaining(track, seed.id());
            List<double[]> run = new ArrayList<>();
            for (int id : walk.isEmpty() ? List.of(seed.id()) : walk) {
                written.add(id);
                RoadSegment part = track.segment(id);
                if (part == null || part.vertexCount() == 0) {
                    continue;
                }
                int count = part.vertexCount();
                // Which way round the piece is written: towards the end the stretch has reached. A
                // piece starting where the last one ended is written as it is; one stored the other way
                // is written backwards; and the first piece of a walk, with nothing to be consistent
                // with, is written as it is. See the class comment on why this is geometry rather than
                // the walk's own ends.
                boolean forward = true;
                if (!run.isEmpty()) {
                    double[] end = run.get(run.size() - 1);
                    double toFirst = Math.hypot(end[0] - part.x(0), end[1] - part.z(0));
                    double toLast = Math.hypot(end[0] - part.x(count - 1), end[1] - part.z(count - 1));
                    forward = toFirst <= toLast;
                    if (Math.min(toFirst, toLast) > PIECE_JOIN) {
                        // The walk says these two continue one another and the geometry says they do
                        // not: the stretch so far is finished, and this piece begins its own. Drawing
                        // across the difference is the chord this whole path exists to remove.
                        if (run.size() >= 2) {
                            runs.add(run);
                        }
                        run = new ArrayList<>();
                    }
                }
                for (int i = 0; i < count; i++) {
                    int index = forward ? i : count - 1 - i;
                    append(run, part.x(index), part.z(index), i == 0);
                }
            }
            if (run.size() >= 2) {
                runs.add(run);
            }
        }
        return runs;
    }

    /**
     * The same for a line of the player's own, out of the routes planned between its neighbouring
     * stops.
     *
     * <h2>Why a pair with no route is a gap and not a hop</h2>
     * A line of the player's own has no track geometry of its own: what the map draws is the route the
     * mod plans between each neighbouring pair of its stops. A pair the mod cannot plan -- the two
     * stops are on opposite sides of a road network that does not reach them, or the line is
     * mis-typed -- used to be drawn as a straight line between the two stops, on the argument that a
     * mis-typed line should be visible as a line rather than as a gap. On a world with a handful of
     * such lines that argument produces exactly what it says; on a world with a city's worth of them
     * it produces a web of straight lines across the whole map, every one of them a claim about ground
     * the line does not run over, and the lines that are real are lost in it. A gap says the same
     * thing without the claim: the line is drawn where its path is known, and its stops are on the map
     * either way -- see {@code RoadElementRenderer}, which marks every stop of every line it draws.
     *
     * <p>Two routes are joined only where they actually meet. Concatenating them regardless would
     * draw a straight line between the end of one and the start of the next, which is the same defect
     * arriving by the other door: the routes come from the router and a pair whose route does not
     * start where the last one ended is no more joined than one that has no route at all.
     *
     * @param stretches one planned route per neighbouring pair, in the line's order; null or a short
     *                  list is a pair with no route, and is left as a gap
     * @return one polyline per stretch of the line whose path is known
     */
    public static List<List<double[]>> ofPlanned(List<List<double[]>> stretches) {
        List<List<double[]>> runs = new ArrayList<>();
        List<double[]> running = null;
        for (List<double[]> stretch : stretches) {
            if (stretch == null || stretch.size() < 2) {
                running = null;
                continue;
            }
            if (running == null || !meets(running.get(running.size() - 1), stretch.get(0))) {
                running = new ArrayList<>(stretch);
                runs.add(running);
                continue;
            }
            // Where the two meet, which is one place and must be one vertex: the stop both routes
            // call at.
            for (int i = 1; i < stretch.size(); i++) {
                running.add(stretch.get(i));
            }
        }
        return runs;
    }

    /** Whether two points are the same place, at the scale a mark's ends are joined at. */
    private static boolean meets(double[] end, double[] start) {
        return Math.hypot(end[0] - start[0], end[1] - start[1]) <= SEAM_SLACK;
    }

    /**
     * Adds one point to a stretch, unless it is where the stretch already is.
     *
     * @param seam whether this point is the first of a piece, where a shared node may land a block or
     *             so away for no better reason than rounding
     */
    private static void append(List<double[]> run, int x, int z, boolean seam) {
        if (!run.isEmpty()) {
            double[] last = run.get(run.size() - 1);
            if (last[0] == x && last[1] == z) {
                return;
            }
            if (seam && Math.hypot(last[0] - x, last[1] - z) < SEAM_SLACK) {
                return;
            }
        }
        run.add(new double[] {x, z});
    }
}
