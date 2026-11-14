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
 * A line's marks arrive a window at a time, so the known track is stretches with gaps; reading them as one polyline would join the end of one stretch to the start of the next with a chord across whatever lies between -- track belonging to no railway, and on a city's worth of lines a map full of stray straight scratches.
 * Segments come out of the network's hash map in hashing order, so each piece is written along the walk that joins it to its neighbours (RoadChains), and a piece whose vertices run the other way is written backwards. A shared point is written once: two pieces meeting at a node are one place.
 * The walk's order is real, but which way round each piece is written is separate: a node is where two pieces meet, and two pieces both starting there are the arms of a corner. So a piece is written whichever way brings its nearer end to the end of the stretch so far, and joined only when that end really is there.
 * A piece whose nearer end is further off than PIECE_JOIN starts a new stretch: the map shows the track it has and invents none.
 */
public final class TrackRuns {

    /**
     * How close the end of one piece and the start of the next must be to be the same place, in blocks. The scale the marks themselves are joined at (MtrLineTracks JOIN_DISTANCE), the same number rather than a copy.
     * Applied at the seam between two pieces of one stretch and nowhere else: interior vertices are written as they are, since a threshold applied throughout quietly halved the resolution of every curve.
     */
    private static final double SEAM_SLACK = MtrLineTracks.JOIN_DISTANCE;

    /**
     * How far apart the nearest ends of two pieces of one walk may be and still be one place, in blocks. Twice SEAM_SLACK, because two pieces meet through a marks-made node and each end may be that far from it; joining at this distance keeps every join the marks made and refuses only the ones they did not.
     */
    private static final double PIECE_JOIN = 2.0 * MtrLineTracks.JOIN_DISTANCE;

    private TrackRuns() {
    }

    /**
     * The stretches of a track, in no particular order, each in the order it is travelled. track is the line's marks, or null when the track is not known; returns one polyline per stretch, each of at least two points, empty when there is no track.
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
                // Which way round the piece is written: towards the end the stretch has reached. The first piece of a walk has nothing to be consistent with and is written as it is.
                boolean forward = true;
                if (!run.isEmpty()) {
                    double[] end = run.get(run.size() - 1);
                    double toFirst = Math.hypot(end[0] - part.x(0), end[1] - part.z(0));
                    double toLast = Math.hypot(end[0] - part.x(count - 1), end[1] - part.z(count - 1));
                    forward = toFirst <= toLast;
                    if (Math.min(toFirst, toLast) > PIECE_JOIN) {
                        // The walk says these two continue one another and the geometry says they do not: the stretch so far is finished and this piece begins its own. Drawing across the difference is the chord this class removes.
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
     * The same for a line of the player's own, out of the routes planned between its neighbouring stops.
     * A pair with no route is a gap, not a straight line between its stops: on a city's worth of such lines the straight lines become a web of claims about ground the line does not run over, and the real lines are lost in it. The stops are marked on the map either way.
     * Two routes are joined only where they actually meet; a pair whose route does not start where the last one ended is no more joined than one that has no route at all. stretches is one planned route per neighbouring pair, in line order; null or a short list is a gap.
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
            // Where the two meet, which is one place and must be one vertex: the stop both routes call at.
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
     * Adds one point to a stretch, unless it is where the stretch already is. seam marks the first point of a piece, where a shared node may land a block or so away from rounding.
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
