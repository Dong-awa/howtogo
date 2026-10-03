package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.road.RoadClass;
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

import java.util.List;

/**
 * The track one line read out of MTR runs along, as roads of this mod's own.
 *
 * <h2>Why the track is worked out rather than read</h2>
 * MTR's client data has no answer to "which rails are this line's": a route ({@code Route}) is a list
 * of platforms with a colour and a name, a rail ({@code Rail}) is geometry with a type and a couple of
 * flags, and nothing joins the two -- MTR works a route's path out over the whole rail network as it
 * runs. So the track a line runs along is found here, by planning the ride between each pair of
 * neighbouring stops over MTR's own rails, exactly as the router will plan it when the player rides.
 * What is marked is therefore the track the line is actually travelled along, which is also the only
 * definition of "this line's track" the data supports.
 *
 * <h2>One line's ride is its own roads, and only its own</h2>
 * Each line's marks are built separately, so the switch beside a line in the editor decides whether
 * that line's track exists at all -- not merely whether some shared layer is consulted. A line whose
 * marks are off contributes nothing, and its stops are matched to the roads the player drew by the
 * rule that was in force before this mod knew anything about MTR.
 *
 * <h2>What is left out</h2>
 * Two things, both deliberately. The connectors at either end of a planned ride -- the straight hops
 * from a stop to the nearest rail -- are trimmed off, because they are not track: a station whose
 * platform sits a little off the rails would otherwise be marked with a short road across whatever is
 * between them. And a pair that cannot be planned at all contributes nothing, so a line whose track
 * MTR has not sent the client is marked where it can be and not invented where it cannot.
 */
final class MtrLineTracks {

    /**
     * How close two mark ends have to be to count as the same point, in blocks.
     *
     * <p>Neighbouring pairs of one line share a stop, so their marks share an end; joining them there is
     * what makes a line one road rather than a row of pieces. A little over half a block, which is what
     * a rounded coordinate can be out by, and no more.
     */
    private static final double JOIN_DISTANCE = 1.5;

    /** Slack when a connector is trimmed, in blocks: enough for arithmetic, not enough to keep a hop. */
    private static final double TRIM_SLACK = 1.0E-6;

    /** How far to look for a rail to take a mark's height from. */
    private static final double HEIGHT_SEARCH = 64.0;

    /** Height used when there is no rail near a mark to ask, which cannot happen for a planned ride. */
    private static final int FALLBACK_HEIGHT = 64;

    private MtrLineTracks() {
    }

    /**
     * The roads one line runs along.
     *
     * @param rails  MTR's rails, which the ride is planned over; the line's marks are cut out of this
     * @param line   the imported line, whose stops say where the ride starts and ends
     * @param nextId the id counter to draw from, shared by every line of one reading so that no two
     *               marks of it can share an id
     * @return this line's marks, one road per neighbouring pair that could be planned, and empty when
     *         none could
     */
    static RoadNetwork of(RoadNetwork rails, TransitLine line, int[] nextId) {
        RoadNetwork marks = new RoadNetwork();
        if (rails.segmentCount() == 0 || line.stopCount() < 2) {
            return marks;
        }
        RoadClass kind = line.kind();
        TravelMode mode = LinePlanner.rideMode(kind);
        // The default policy as the base and not the player's: this decides which class the ride may
        // run on -- the line's own -- and the metric only chooses between two tracks, which are the same
        // track. Reading a config here would make one reading produce a different mark on a different
        // client, and this has to stay a function of the reading to be checkable at all.
        RoutePreferences policy = LinePlanner.ridePreferences(kind, RoutePreferences.DEFAULTS);
        // One workspace for the line: every pair is anchored to the same handful of stops, so the
        // segment splits behind those anchors are paid once rather than once per pair.
        RoadRouter.Workspace workspace = new RoadRouter.Workspace(rails);
        for (int i = 1; i < line.stopCount(); i++) {
            LineStop from = line.stops().get(i - 1);
            LineStop to = line.stops().get(i);
            Route ride = RoadRouter.findRoute(workspace, from.x(), from.z(), to.x(), to.z(), "", mode,
                    policy);
            // A hop longer than the mode allows is not a hop onto the track, it is a line across open
            // country: the ride the router fell back to would reach the rails from a stop that is
            // nowhere near them, and marking that would put a piece of rail under a line that does not
            // run there. The anchored search refuses this on its own; the fallback between nearest nodes
            // does not, so the rule is applied here for both.
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
     * The part of a ride that is on the track: the polyline between the two connectors.
     *
     * <p>A route is three parts -- a hop onto the network, the roads, a hop off it -- and only the
     * middle is track. The hops are measured, so they are trimmed by their own lengths rather than
     * guessed at: the first point kept is the first one at or past the start connector, which is the
     * point the ride joins the rails at, and the last is the one at or before the goal connector.
     *
     * <p>When the ride is all connector -- no rail path between the two stops at all -- what is left is
     * shorter than a road and is dropped by the caller.
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
     *
     * <p>The class is the line's own -- rail for a train, water for a boat -- so the mark is drawn in
     * the colour and at the pace of the line it belongs to, and so a ride along the line may use it
     * while a ride of any other kind may not.
     *
     * <p>The height is the rail's own, taken from the nearest rail of the layer the ride was planned
     * over, rather than a number from nowhere: a mark at the wrong height would not join the rails the
     * player drew beside it.
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
}
