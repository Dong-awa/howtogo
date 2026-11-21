package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.road.RoadClass;
import bili.dongsz.howtogo.road.RoadCurve;
import bili.dongsz.howtogo.road.RoadNetwork;
import bili.dongsz.howtogo.road.RoadNode;
import bili.dongsz.howtogo.road.RoadSegment;
import bili.dongsz.howtogo.route.LinePlanner;
import bili.dongsz.howtogo.route.RoadRouter;
import bili.dongsz.howtogo.route.Route;
import bili.dongsz.howtogo.route.RoutePreferences;
import bili.dongsz.howtogo.route.TravelMode;
import bili.dongsz.howtogo.transit.LineStop;
import bili.dongsz.howtogo.transit.TransitLine;

import java.util.ArrayList;
import java.util.List;

/**
 * The track one line read out of MTR runs along, as roads of this mod's own.
 *
 * Two ways to know it: stated (the reading carries each route's rail ids, exact) or planned
 * (MTR's client data does not join routes to rails, so the ride between neighbouring stops is
 * planned over MTR's own rails). Planned reach is limited to sent rails, so unwalked lines come
 * out as straight hops.
 *
 * Each line's marks are built separately, so the editor switch decides whether that line's track
 * exists. Connectors are trimmed; an unplannable pair contributes nothing; a shared workspace is
 * handed in because it copies the rails on first use.
 */
final class MtrLineTracks {

    /**
     * How close two mark ends must be to count as the same point, in blocks.
     * Joins neighbouring pairs at their shared stop. Shared with the drawing code so seams match.
     */
    static final double JOIN_DISTANCE = 1.5;

    /** Slack when a connector is trimmed, in blocks: enough for arithmetic, not enough to keep a hop. */
    private static final double TRIM_SLACK = 1.0E-6;

    /**
     * How far apart two rails of one route may end and still be one continuous track, in blocks.
     * Anything further is a stretch the reading does not have; joining it would draw a straight line.
     */
    private static final double RUN_JOIN = 4.0;

    /** How far to look for a rail to take a mark's height from. */
    private static final double HEIGHT_SEARCH = 64.0;

    /** Height used when there is no rail near a mark, which cannot happen for a planned ride. */
    private static final int FALLBACK_HEIGHT = 64;

    private MtrLineTracks() {
    }

    /**
     * The roads one line runs along.
     * @param rails MTR's rails; the line's marks are cut out of this
     * @param line the imported line, whose stops bound the ride
     * @param nextId id counter shared by every line of one reading
     * @return one road per plannable neighbouring pair, empty when none could be
     */
    static RoadNetwork of(RoadNetwork rails, TransitLine line, int[] nextId) {
        return of(new RoadRouter.Workspace(rails), rails, line, nextId, Extent.of(rails));
    }

    /**
     * The track a line runs along, as the reading itself says.
     *
     * MTR Map Overlay's snapshot carries each route's own rail ids in path order: exact, no reach limit
     * (a planned ride only sees rails near the player), and no router or workspace cost.
     *
     * One road per stretch the named rails form, in named order. A rail's geometry may run either way,
     * so each is appended forwards or backwards to meet the run's end; concatenating as given would
     * zig-zag. A named rail may lack geometry, so a run ends where the next rail does not start where
     * the last ended -- the gap is unknown track and is not crossed.
     *
     * @param rails the line's rails in reading order, with anything missing already left out
     * @return the line's track, empty when the named rails carry no geometry
     */
    static RoadNetwork stated(List<MtrClientData.Track> rails, RoadClass kind, int[] nextId) {
        RoadNetwork marks = new RoadNetwork();
        List<double[]> run = new ArrayList<>();
        int height = 0;
        for (MtrClientData.Track rail : rails) {
            int count = rail.vertexCount();
            if (count < 2) {
                continue;
            }
            double[] first = {rail.xs()[0], rail.zs()[0]};
            double[] last = {rail.xs()[count - 1], rail.zs()[count - 1]};
            if (!run.isEmpty() && apart(run.get(run.size() - 1), first, last)) {
                addRun(marks, kind, run, height, nextId);
                run = new ArrayList<>();
            }
            if (run.isEmpty()) {
                // The first rail's height is the run's; see MtrMapOverlay.
                height = rail.y();
            }
            boolean backwards = !run.isEmpty()
                    && distance(run.get(run.size() - 1), last) <= distance(run.get(run.size() - 1), first);
            for (int i = 0; i < count; i++) {
                int index = backwards ? count - 1 - i : i;
                double[] point = {rail.xs()[index], rail.zs()[index]};
                if (!run.isEmpty() && distance(run.get(run.size() - 1), point) < JOIN_DISTANCE) {
                    // The meeting point is one place: writing it twice would leave a zero-length step in the track.
                    continue;
                }
                run.add(point);
            }
        }
        addRun(marks, kind, run, height, nextId);
        return marks;
    }

    /** Whether the next rail of a run starts somewhere other than where the run ends. */
    private static boolean apart(double[] end, double[] first, double[] last) {
        return Math.min(distance(end, first), distance(end, last)) > RUN_JOIN;
    }

    private static double distance(double[] one, double[] other) {
        return Math.hypot(one[0] - other[0], one[1] - other[1]);
    }

    /** One stitched stretch of track, as a road of the line. */
    private static void addRun(RoadNetwork marks, RoadClass kind, List<double[]> run, int height,
                               int[] nextId) {
        if (run.size() < 2) {
            return;
        }
        RoadSegment segment = new RoadSegment(nextId[0]++, kind, height, run.size());
        for (double[] point : run) {
            segment.addVertex((int) Math.round(point[0]), (int) Math.round(point[1]));
        }
        RoadNode from = endNode(marks, nextId, run.get(0), height);
        RoadNode to = endNode(marks, nextId, run.get(run.size() - 1), height);
        segment.setFromNode(from.id());
        segment.setToNode(to.id());
        // MTR's own samples are kept for the display line so a curved track is not a staircase.
        List<double[]> display = new ArrayList<>(run);
        display.set(0, new double[]{from.x(), from.z()});
        display.set(display.size() - 1, new double[]{to.x(), to.z()});
        segment.setCurve(RoadCurve.precise(display));
        marks.putSegment(segment);
    }

    /** The node at one end of a run, making one only when nothing already made is there. */
    private static RoadNode endNode(RoadNetwork marks, int[] nextId, double[] point, int height) {
        int x = (int) Math.round(point[0]);
        int z = (int) Math.round(point[1]);
        RoadNode existing = marks.nearestNode(x, z, JOIN_DISTANCE);
        if (existing != null) {
            return existing;
        }
        RoadNode made = new RoadNode(nextId[0]++, x, height, z, RoadNode.Type.ENDPOINT, null);
        marks.putNode(made);
        return made;
    }

    /** The same, with the rails' extent handed in -- asked once per reading and reused per line. */
    static RoadNetwork of(RoadNetwork rails, TransitLine line, int[] nextId, Extent extent) {
        return of(new RoadRouter.Workspace(rails), rails, line, nextId, extent);
    }

    /**
     * The same, over a router workspace the caller owns and shares.
     * A workspace copies the rails on first query, so one per line copies the whole layer per line.
     * Sharing is safe: each split is additive, and marks come from the ride the router returns, not
     * from the workspace's network.
     * @param workspace router working copy of rails, shared by every line of one reading
     */
    static RoadNetwork of(RoadRouter.Workspace workspace, RoadNetwork rails, TransitLine line,
                          int[] nextId, Extent extent) {
        RoadNetwork marks = new RoadNetwork();
        if (rails.segmentCount() == 0 || line.stopCount() < 2) {
            return marks;
        }
        RoadClass kind = line.kind();
        TravelMode mode = LinePlanner.rideMode(kind);
        // Default policy, not the player's: the ride uses the line's own class only, so a mark stays a
        // function of the reading.
        RoutePreferences policy = LinePlanner.ridePreferences(kind, RoutePreferences.DEFAULTS);
        // A station's marked point can stand well off the rails its line runs on, so each stop is placed
        // on the rails first: what is drawn is the track the ride really uses, and the gap from the mark
        // to the track is a walk rather than a chord under the line. One reach for the editor and the
        // planner alike -- see RoadRouter.stationReach.
        double judgement = RoadRouter.stationReach();
        double[] anchors = new double[line.stopCount() * 2];
        for (int i = 0; i < line.stopCount(); i++) {
            LineStop stop = line.stops().get(i);
            double[] at = extent != null && !extent.holds(stop.x(), stop.z(), judgement)
                    ? new double[]{stop.x(), stop.z()}
                    : workspace.placedStop(stop.x(), stop.z(), mode, policy, judgement);
            anchors[i * 2] = at[0];
            anchors[i * 2 + 1] = at[1];
        }
        for (int i = 1; i < line.stopCount(); i++) {
            Route ride = RoadRouter.findRoute(workspace, anchors[(i - 1) * 2],
                    anchors[(i - 1) * 2 + 1], anchors[i * 2], anchors[i * 2 + 1], "", mode, policy);
            // A hop longer than the mode allows is a line across open country, not a hop onto the track;
            // the fallback nearest-node search does not refuse it, so the rule is applied here.
            if (!ride.isPresent() || ride.startConnector() > mode.maxConnectorDistance()
                    || ride.goalConnector() > mode.maxConnectorDistance()) {
                continue;
            }
            List<double[]> track = onTrack(ride);
            if (track.size() >= 2) {
                addRoad(marks, rails, kind, track, nextId);
            }
        }
        return marks;
    }

    /**
     * The part of a ride on the track: the polyline between the two connectors.
     * A route is a hop on, the roads, a hop off; only the middle is track. Hops are trimmed by their
     * measured lengths. An all-connector ride is dropped by the caller.
     */
    private static List<double[]> onTrack(Route ride) {
        List<double[]> points = ride.points();
        if (!ride.isPresent()) {
            return List.of();
        }
        double[] travelled = new double[points.size()];
        for (int i = 1; i < points.size(); i++) {
            double[] before = points.get(i - 1);
            double[] here = points.get(i);
            travelled[i] = travelled[i - 1] + Math.hypot(here[0] - before[0], here[1] - before[1]);
        }
        double joins = ride.startConnector();
        double leaves = travelled[points.size() - 1] - ride.goalConnector();

        int first = 0;
        while (first + 1 < points.size() && travelled[first + 1] <= joins + TRIM_SLACK) {
            first++;
        }
        int last = points.size() - 1;
        while (last - 1 > first && travelled[last - 1] >= leaves - TRIM_SLACK) {
            last--;
        }
        return points.subList(first, last + 1);
    }

    /**
     * Stamps one planned stretch of track as a road of this line.
     * The class is the line's own (rail, water), so the mark draws in the line's colour and a ride of
     * that kind may use it. The height comes from the nearest rail, so it joins roads the player drew.
     *
     * <p>The block tolerance, not precise: a planned ride is returned by the router, which works on the
     * rails' block vertices, so this geometry is block-sampled like a RoadWeaver polyline even though the
     * rails themselves arrived as world-coordinate samples. The stated path (see addRun) is the one that
     * keeps MTR's own samples, because it never goes through the router.
     */
    private static void addRoad(RoadNetwork marks, RoadNetwork rails, RoadClass kind,
                                List<double[]> track, int[] nextId) {
        RoadNode from = markNode(marks, rails, nextId, track.get(0));
        RoadNode to = markNode(marks, rails, nextId, track.get(track.size() - 1));
        RoadSegment segment = new RoadSegment(nextId[0]++, kind, from.y(), track.size());
        for (double[] point : track) {
            segment.addVertex((int) Math.round(point[0]), (int) Math.round(point[1]));
        }
        segment.setFromNode(from.id());
        segment.setToNode(to.id());
        segment.setCurve(RoadCurve.of(segment));
        marks.putSegment(segment);
    }

    /** The mark's node at a point, making one only when nothing already made is there. */
    private static RoadNode markNode(RoadNetwork marks, RoadNetwork rails, int[] nextId,
                                     double[] point) {
        int x = (int) Math.round(point[0]);
        int z = (int) Math.round(point[1]);
        RoadNode existing = marks.nearestNode(x, z, JOIN_DISTANCE);
        if (existing != null) {
            return existing;
        }
        RoadNode made = new RoadNode(nextId[0]++, x, heightAt(rails, point), z,
                RoadNode.Type.JUNCTION, null);
        marks.putNode(made);
        return made;
    }

    /** The height of the rail nearest a point, which is the height the mark belongs at. */
    private static int heightAt(RoadNetwork rails, double[] point) {
        RoadNode nearest = rails.nearestNode(point[0], point[1], HEIGHT_SEARCH);
        return nearest == null ? FALLBACK_HEIGHT : nearest.y();
    }

    /**
     * The box a layer of track reaches; a stop outside it cannot be joined to any rail in it.
     * Planning every pair of a whole railway over rails that only exist near the player was measured
     * at 79 ms for 400 lines on the render thread. The bound is exact: a stop is placed on a rail within
     * the station judgement reach (see RoadRouter.stationReach), so a stop further than that from every
     * rail could only fail.
     */
    record Extent(int minX, int minZ, int maxX, int maxZ) {

        /**
         * The extent of a layer, or an extent holding nothing when the layer is empty. Null means
         * nothing to mark, not nothing is near.
         */
        static Extent of(RoadNetwork rails) {
            int minX = Integer.MAX_VALUE;
            int minZ = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE;
            int maxZ = Integer.MIN_VALUE;
            for (RoadSegment segment : rails.segmentsSnapshot()) {
                for (int i = 0; i < segment.vertexCount(); i++) {
                    minX = Math.min(minX, segment.x(i));
                    maxX = Math.max(maxX, segment.x(i));
                    minZ = Math.min(minZ, segment.z(i));
                    maxZ = Math.max(maxZ, segment.z(i));
                }
            }
            return minX > maxX ? null : new Extent(minX, minZ, maxX, maxZ);
        }

        /** Whether a point is close enough to the box for a rail in it to be reached. */
        boolean holds(double x, double z, double reach) {
            return x >= minX - reach && x <= maxX + reach
                    && z >= minZ - reach && z <= maxZ + reach;
        }
    }
}
