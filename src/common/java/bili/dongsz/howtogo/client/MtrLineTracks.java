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

import java.util.ArrayList;
import java.util.List;

/**
 * The track one line read out of MTR runs along, as roads of this mod's own.
 *
 * <h2>Two ways to know it, and which one a line takes</h2>
 * <b>As the reading says</b> -- see {@link #stated}. A reading may carry each route's own rail ids, in
 * path order, which is an exact answer and needs nothing worked out. MTR's own client data does not,
 * and MTR Map Overlay's fetched snapshot does, which is why the two are both here.
 *
 * <p><b>By planning it</b> -- the rest of this class, and what every line used to take. MTR's client
 * data has no answer to "which rails are this line's": a route ({@code Route}) is a list of platforms
 * with a colour and a name, a rail ({@code Rail}) is geometry with a type and a couple of flags, and
 * nothing joins the two -- MTR works a route's path out over the whole rail network as it runs. So the
 * track is found by planning the ride between each pair of neighbouring stops over MTR's own rails,
 * exactly as the router will plan it when the player rides. What is marked is therefore the track the
 * line is actually travelled along, which is the only definition of "this line's track" that data
 * supports. It has a reach, though -- the rails it plans over are the ones the client has been sent --
 * which is why a line nobody has walked to comes out of it as straight hops between its stations.
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
 *
 * <h2>Why the router's workspace is handed in</h2>
 * The class takes the workspace a batch of lines shares rather than making one per call: a workspace
 * copies the rails on first use, so one per line is the whole rail layer copied once per line. See
 * {@link #of(RoadRouter.Workspace, RoadNetwork, TransitLine,
 * int[], Extent)}, which is what the reading calls. Only the planning path needs it at all -- a line
 * the reading names the rails of never touches the router.
 */
final class MtrLineTracks {

    /**
     * How close two mark ends have to be to count as the same point, in blocks.
     *
     * <p>Neighbouring pairs of one line share a stop, so their marks share an end; joining them there is
     * what makes a line one road rather than a row of pieces. A little over half a block, which is what
     * a rounded coordinate can be out by, and no more.
     *
     * <p>Not private because the drawing joins the same two ends the same way: {@link TrackRuns} writes
     * a stretch of this track out for the map, and a seam there is the same half-block question. One
     * number rather than two, because the second copy is the one that drifts.
     */
    static final double JOIN_DISTANCE = 1.5;

    /** Slack when a connector is trimmed, in blocks: enough for arithmetic, not enough to keep a hop. */
    private static final double TRIM_SLACK = 1.0E-6;

    /**
     * How far apart two rails of one route may end and still be the same continuous track, in blocks.
     *
     * <p>Generous, because the order the rails arrive in is the route's own and the only question a
     * run boundary answers is whether the geometry between two of them is missing. Rails that MTR
     * joins meet at a shared node to within rounding, so anything past this is not a rounding error,
     * it is a stretch the reading does not have -- and joining it anyway would draw a straight line
     * across whatever is between them, which is the defect this whole path exists to remove.
     */
    private static final double RUN_JOIN = 4.0;

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
        return of(new RoadRouter.Workspace(rails), rails, line, nextId, Extent.of(rails));
    }

    /**
     * The track a line runs along, as the reading itself says it does.
     *
     * <h2>Why this is not the planning above</h2>
     * The rest of this class exists because MTR does not say which rails a line uses: a route is a
     * list of platforms, a rail is geometry, and nothing joins the two, so the ride has to be planned
     * and the planned path is the only answer available. A reading that <em>does</em> say -- MTR Map
     * Overlay's fetched snapshot carries each route's own rail ids, in path order, for exactly this
     * -- makes all of that unnecessary and worse than unnecessary:
     *
     * <ul>
     *   <li><b>it is exact.</b> The rails named are the rails the route's vehicles drive, so the mark
     *       is the railway rather than a ride that happens to be reachable over it;</li>
     *   <li><b>it has no reach.</b> A planned ride needs the rails it runs over to be in hand, which
     *       for MTR means within a couple of hundred blocks of the player. A line anywhere on the
     *       network therefore used to be drawn as straight hops between its stations, which is
     *       precisely the defect a whole-network reading exists to fix;</li>
     *   <li><b>it costs nothing.</b> No router, no workspace, no planning per pair of neighbouring
     *       stops -- a walk of the rails named and their own vertices, which is the size of the track
     *       and not the size of the network.</li>
     * </ul>
     *
     * <h2>What comes out, and why it is stitched</h2>
     * One road per stretch of track the named rails actually form, in the order they were named. Two
     * things have to be handled on the way:
     *
     * <p>A rail's geometry is its own curve, stored from its own start to its own end, and the order
     * the route runs along them has nothing to do with which way round that is -- the overlay samples
     * a rail once and reuses it for every route and every vehicle that drives it. So each rail is
     * appended to the run either forwards or backwards, whichever end meets the end of the run so far.
     * Concatenating them in the order given without that would draw each rail as a hop from its far
     * end back to its near one, which is a zig-zag along the line rather than a line.
     *
     * <p>And a named rail may have no geometry in the reading at all -- the snapshot says the route
     * uses it, and the rail itself was not sent, or was dropped for having no usable points. A run is
     * therefore finished where the next rail does not begin where the last one ended, and a new one
     * begun: the gap is a stretch whose track is not known, and it must be left as a gap rather than
     * crossed with a straight line, for the same reason the planning path refuses a pair it cannot
     * plan.
     *
     * @param rails the line's rails, in the order the reading gave them, with anything it does not
     *              hold already left out
     * @return the line's track, empty when the rails it names carry no geometry at all
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
                // The height is the overlay's hint for the rail the run starts at, and a run is one
                // stretch of one track, so the first rail's is the run's. See MtrMapOverlay.
                height = rail.y();
            }
            boolean backwards = !run.isEmpty()
                    && distance(run.get(run.size() - 1), last) <= distance(run.get(run.size() - 1), first);
            for (int i = 0; i < count; i++) {
                int index = backwards ? count - 1 - i : i;
                double[] point = {rail.xs()[index], rail.zs()[index]};
                if (!run.isEmpty() && distance(run.get(run.size() - 1), point) < JOIN_DISTANCE) {
                    // Where the two rails meet, which is one place and must be one vertex: writing it
                    // twice would leave a zero-length step in the middle of the track, and every
                    // measurement taken along it -- the mark's own length, a label's position -- would
                    // count a corner that is not there.
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
        segment.setFromNode(endNode(marks, nextId, run.get(0), height).id());
        segment.setToNode(endNode(marks, nextId, run.get(run.size() - 1), height).id());
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

    /**
     * The same, with the extent of the rails handed in.
     *
     * <p>For the caller that works out every line of a reading at once: the extent is a walk of the
     * whole layer, so it is asked for once and reused rather than once per line. See {@link Extent} for
     * what it is for.
     */
    static RoadNetwork of(RoadNetwork rails, TransitLine line, int[] nextId, Extent extent) {
        return of(new RoadRouter.Workspace(rails), rails, line, nextId, extent);
    }

    /**
     * The same, over a router workspace the caller owns and shares.
     *
     * <h2>Why the workspace is the caller's</h2>
     * A workspace makes its own copy of the rails on the first query -- so one workspace per line is one
     * copy of the whole layer per line, which is the
     * cost of the rail layer multiplied by the number of lines that reach it. A reading of the whole
     * railway is hundreds of lines over a rail layer that a fetched snapshot can make thousands of
     * segments long, and multiplying those two is what this signature exists to stop.
     *
     * <p>Sharing one is what a workspace is built for: its own documentation says one belongs to one
     * batch of queries, and every split it makes is additive -- subdividing one segment and adding one
     * node, leaving the rest of the graph exactly as it was -- so a line planned after another reads a
     * refinement of the network and never a corrupted one. What each line's marks are built from is the
     * ride the router returns, not the workspace's own network, so no line can be affected by another
     * beyond that refinement.
     *
     * @param workspace the router's working copy of {@code rails}, shared by every line of one reading
     */
    static RoadNetwork of(RoadRouter.Workspace workspace, RoadNetwork rails, TransitLine line,
                          int[] nextId, Extent extent) {
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
        double reach = mode.maxConnectorDistance();
        for (int i = 1; i < line.stopCount(); i++) {
            LineStop from = line.stops().get(i - 1);
            LineStop to = line.stops().get(i);
            if (extent != null && (!extent.holds(from.x(), from.z(), reach)
                    || !extent.holds(to.x(), to.z(), reach))) {
                // A ride's connectors are capped at this distance, so a pair with a stop beyond it is a
                // pair that cannot be planned however the router is asked. That is the whole reason the
                // extent is asked first: a whole map's worth of lines arrive with all of their stops, and
                // almost all of those pairs are nowhere near the track in hand.
                continue;
            }
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

    /**
     * The box a layer of track reaches, which is what says whether a stop could be joined to it at all.
     *
     * <h2>Why marking needs to know this</h2>
     * A reading used to be a couple of lines, so planning every neighbouring pair of them over the rails
     * in hand cost nothing. A whole railway is every line with all of its stops, and the rails in hand
     * are still only the ones around the player: nearly every pair is then a ride between two places
     * hundreds or thousands of blocks away from any rail here. Each of those was planned, found nothing,
     * and was thrown away -- measured at seventy-nine milliseconds for four hundred lines, on the render
     * thread, which is five frames of a map that will not draw.
     *
     * <p>The bound is exact rather than a guess. A ride's two connectors are each capped at the mode's own
     * reach, so a stop further than that from every rail cannot be joined to one whatever the router
     * does; and a stop inside that distance of a rail is necessarily inside that distance of the box the
     * rails fit in. A pair that fails this test is therefore a pair that could only have failed, and the
     * ones that pass are planned exactly as before.
     */
    record Extent(int minX, int minZ, int maxX, int maxZ) {

        /**
         * The extent of a layer, or an extent that holds nothing when the layer is empty.
         *
         * <p>Null is the honest answer for a layer with no geometry, and callers treat it as "nothing to
         * mark" -- but not as "nothing is near", which would be the opposite claim.
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
