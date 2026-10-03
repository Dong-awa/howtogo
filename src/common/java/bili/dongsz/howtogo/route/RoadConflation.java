package bili.dongsz.howtogo.route;

import bili.dongsz.howtogo.HowToGo;
import bili.dongsz.howtogo.road.RoadClass;
import bili.dongsz.howtogo.road.RoadEditor;
import bili.dongsz.howtogo.road.RoadNetwork;
import bili.dongsz.howtogo.road.RoadNode;
import bili.dongsz.howtogo.road.RoadSegment;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Joins a road network up where it only <em>looks</em> joined.
 *
 * <h2>The problem this exists for</h2>
 * The editor stores a road as a polyline between two nodes, and two roads are connected only when
 * they were drawn through the same node. Nothing about clicking on the map guarantees that: a road
 * drawn up to another one and stopped a block short shares no node with it, and two roads drawn
 * across each other share none either. Both look like junctions and are none, so the router sees two
 * fragments, reports that the roads are not connected, and -- for public transport -- refuses every
 * ride that would have crossed the join.
 *
 * <p>The player's own fix is to redraw the road, and the snap radius that would have prevented the
 * mistake in the first place is a few screen pixels, which at map scale is a few blocks. So the
 * network is repaired for routing instead: a routing graph is built by breaking a segment wherever a
 * node sits on it or another segment crosses it, which leaves a node a block or two from the one it
 * should have been drawn through, and {@link RoadRouter}'s coincident-node links then join the pair as
 * the single junction they appear to be.
 *
 * <h2>What it will not do</h2>
 * Two roads are only joined when some {@link TravelMode} can travel on both of them. A railway
 * crossing a road is a bridge, not a junction: no vehicle in this mod goes from one to the other, and
 * inventing a node for it would let a ride leave the rails at a level crossing. Two roads are also
 * only joined when they are at roughly the same height, because the network records one: a road
 * crossing another on a bridge is two roads at two heights, and joining them would send a walker
 * straight up.
 *
 * <h2>How it is done</h2>
 * Every edge of every segment is filed into a grid of cells, sampled densely enough that any two
 * pieces of road that come within the join distance are looked at together. Then two questions are
 * asked: is there a node sitting on a segment that does not already end at it, and does an edge cross
 * another edge. Both answers become split points, which are applied from the far end of each road
 * inwards so that the vertex indices stay valid as the road is broken up.
 *
 * <p>This runs on the router's own working copy -- see {@link RoadRouter.Workspace} -- so the player's
 * network, the editor, the renderer and the file it is saved to are all untouched by it.
 */
final class RoadConflation {

    /**
     * How far a node may stand from a segment and still count as sitting on it, in blocks.
     *
     * <p>The same distance the graph treats two nodes as coincident at, which is what makes the repair
     * work: the split lands within it of the node, so the pair becomes one junction.
     */
    static final double JOIN_DISTANCE = 3.0;

    /**
     * How far apart two roads may be vertically and still be treated as meeting, in blocks.
     *
     * <p>One network holds roads at the height they were drawn at, so two roads at the same spot but
     * thirty blocks apart in height are a bridge or a tunnel rather than a junction.
     */
    static final double HEIGHT_TOLERANCE = 4.0;

    /** Cell size of the lookup grid, in blocks. */
    private static final double CELL = 16.0;

    /** How far apart edges are sampled when they are filed into the grid, in blocks. */
    private static final double SAMPLE_STEP = CELL / 2;

    /** How close to a vertex a split has to be before it is treated as that vertex. */
    private static final double VERTEX_EPSILON = 1.0E-3;

    /**
     * Ceiling on the number of node-and-edge and edge-and-edge pairs looked at in one repair.
     *
     * <p>A hand-drawn network is a few hundred segments and never comes near this. A railway read out
     * of the world can be: the budget is what keeps a plan over one from taking a noticeable time, and
     * past it the repair keeps what it found, stops looking and says so once.
     */
    private static final int MAX_JOIN_CHECKS = 300_000;

    /**
     * Above this many edges, crossings are not looked for at all.
     *
     * <p>Two reasons, and the second is the better one. The first is cost: Create's track is a vertex a
     * block, so a single run between junctions is one polyline with thousands of edges, every one of
     * them in the same cells as its neighbours, and the pairs to examine grow with the square of that.
     * The second is that it would be wrong. A network that big was laid by a machine that already knows
     * where its own junctions are, and tracks that cross in the world are as often a bridge, a flyover
     * or a level crossing as they are a switch -- inventing a junction at each one would offer a train
     * a turn that does not exist. A drawing by hand is the case crossings are for.
     */
    private static final int MAX_CROSSING_EDGES = 20_000;

    /** Whether the budget has already been reported, so a repair says so once and not once per plan. */
    private static boolean budgetReported;

    private RoadConflation() {
    }

    /** One edge of one segment, in absolute coordinates. */
    private record Edge(int segmentId, int edgeIndex, double ax, double az, double bx, double bz) {
    }

    /** One place a segment has to be broken, and the point to break it at. */
    private record Split(int segmentId, int edgeIndex, double t, int x, int z) {
    }

    /** Two edges, in a fixed order, so a pair is examined once however it is met. */
    private record Pair(Edge a, Edge b) {
    }

    /**
     * Repairs the network in place.
     *
     * @param editor the editor for {@code network}, which must be one that records no history: this is
     *               a working copy made for one plan, not the player's road
     * @return how many places the network was broken at, which is zero when there was nothing to
     *         repair -- and is what tells the caller whether the unrepaired network is worth asking
     */
    static int conflate(RoadNetwork network, RoadEditor editor) {
        if (network.segmentCount() == 0 || network.nodeCount() < 2) {
            return 0;
        }
        Grid grid = new Grid(network);
        List<Split> splits = new ArrayList<>();
        int[] budget = {MAX_JOIN_CHECKS};
        collectNodesOnSegments(network, grid, splits, budget);
        if (grid.edgeCount() <= MAX_CROSSING_EDGES) {
            collectCrossings(network, grid, splits, budget);
        }
        if (budget[0] <= 0 && !budgetReported) {
            budgetReported = true;
            HowToGo.LOGGER.info("[HowToGo] the road network is large enough that the join repair "
                    + "stopped early after {} pairs; the roads as drawn are routed on from there",
                    MAX_JOIN_CHECKS);
        }
        return apply(network, editor, splits);
    }

    // ------------------------------------------------------------------- what to join

    /**
     * A node standing on a segment it is not an endpoint of.
     *
     * <p>Only nodes that already belong to a road are considered. A place of interest three blocks
     * from a road is not a junction, and making it one would let the router use a landmark as a
     * shortcut between two roads that pass either side of it.
     */
    private static void collectNodesOnSegments(RoadNetwork network, Grid grid, List<Split> splits,
                                               int[] budget) {
        Map<Integer, List<Integer>> incident = new HashMap<>();
        for (RoadSegment segment : network.segmentsSnapshot()) {
            attach(incident, segment.fromNode(), segment.id());
            attach(incident, segment.toNode(), segment.id());
        }

        Set<Split> found = new HashSet<>();
        for (RoadNode node : network.nodesSnapshot()) {
            List<Integer> nearbySegments = incident.get(node.id());
            if (nearbySegments == null || nearbySegments.isEmpty()) {
                continue;
            }
            for (Edge edge : grid.edgesNear(node.x(), node.z())) {
                if (--budget[0] <= 0) {
                    splits.addAll(found);
                    return;
                }
                RoadSegment segment = network.segment(edge.segmentId());
                if (segment == null) {
                    continue;
                }
                if (segment.fromNode() == node.id() || segment.toNode() == node.id()) {
                    continue;
                }
                if (!joinable(network, incident, node.id(), segment.roadClass())) {
                    continue;
                }
                if (Math.abs(segment.y() - node.y()) > HEIGHT_TOLERANCE) {
                    continue;
                }
                Split split = footOf(segment, edge, node.x(), node.z());
                if (split != null) {
                    found.add(split);
                }
            }
        }
        splits.addAll(found);
    }

    /** The point of a segment nearest a node, as a split, when it is on the segment and near enough. */
    private static Split footOf(RoadSegment segment, Edge edge, double x, double z) {
        double ex = edge.bx() - edge.ax();
        double ez = edge.bz() - edge.az();
        double lengthSq = ex * ex + ez * ez;
        if (lengthSq < 1.0E-9) {
            return null;
        }
        double t = ((x - edge.ax()) * ex + (z - edge.az()) * ez) / lengthSq;
        t = Math.max(0, Math.min(1, t));
        double px = edge.ax() + ex * t;
        double pz = edge.az() + ez * t;
        if (Math.hypot(px - x, pz - z) > JOIN_DISTANCE) {
            return null;
        }
        return splitAt(segment, edge.edgeIndex(), t, px, pz);
    }

    /**
     * Every pair of edges that cross, as two split points.
     *
     * <p>Only edges of the same class, and only true crossings: two roads meeting end to end, or
     * sharing a vertex, are either already joined or are the node case above.
     */
    private static void collectCrossings(RoadNetwork network, Grid grid, List<Split> splits,
                                         int[] budget) {
        Set<Pair> examined = new HashSet<>();
        Set<Split> found = new HashSet<>();
        for (RoadSegment segment : network.segmentsSnapshot()) {
            if (segment.fromNode() == RoadSegment.NO_NODE || segment.toNode() == RoadSegment.NO_NODE) {
                continue;
            }
            for (int i = 1; i < segment.vertexCount(); i++) {
                double ax = segment.x(i - 1);
                double az = segment.z(i - 1);
                double bx = segment.x(i);
                double bz = segment.z(i);
                Edge edge = new Edge(segment.id(), i, ax, az, bx, bz);
                for (Edge other : grid.edgesNearPath(ax, az, bx, bz, edge)) {
                    if (--budget[0] <= 0) {
                        splits.addAll(found);
                        return;
                    }
                    if (other.segmentId() == segment.id()) {
                        continue;
                    }
                    // The cheap refusals first, and the pair only once both edges are worth it: most
                    // candidates in a railway are the next piece of the same line, or a class this one
                    // cannot meet.
                    RoadSegment otherSegment = network.segment(other.segmentId());
                    if (otherSegment == null || segment.roadClass() != otherSegment.roadClass()) {
                        continue;
                    }
                    if (Math.abs(segment.y() - otherSegment.y()) > HEIGHT_TOLERANCE) {
                        continue;
                    }
                    if (!examined.add(new Pair(edge, other))) {
                        continue;
                    }
                    crossing(segment, edge, otherSegment, other, found);
                }
            }
        }
        splits.addAll(found);
    }

    /** Where two edges cross, or nothing when they do not cross strictly inside both. */
    private static void crossing(RoadSegment segmentA, Edge a, RoadSegment segmentB, Edge b,
                                 Set<Split> splits) {
        double rx = a.bx() - a.ax();
        double rz = a.bz() - a.az();
        double sx = b.bx() - b.ax();
        double sz = b.bz() - b.az();
        double denominator = rx * sz - rz * sx;
        if (Math.abs(denominator) < 1.0E-9) {
            // Parallel, or one of them has no length: nothing to cross.
            return;
        }
        double qx = b.ax() - a.ax();
        double qz = b.az() - a.az();
        double t = (qx * sz - qz * sx) / denominator;
        double u = (qx * rz - qz * rx) / denominator;
        if (t <= VERTEX_EPSILON || t >= 1 - VERTEX_EPSILON
                || u <= VERTEX_EPSILON || u >= 1 - VERTEX_EPSILON) {
            return;
        }
        double px = a.ax() + rx * t;
        double pz = a.az() + rz * t;
        Split onA = splitAt(segmentA, a.edgeIndex(), t, px, pz);
        Split onB = splitAt(segmentB, b.edgeIndex(), u, px, pz);
        if (onA != null) {
            splits.add(onA);
        }
        if (onB != null) {
            splits.add(onB);
        }
    }

    /**
     * A split point, or nothing when the point is one of the segment's own two nodes.
     *
     * <p>A point that lands exactly on a vertex is moved onto the edge that starts there, because a
     * split is expressed as an edge index and a point on that edge: a t of one on edge j is a t of zero
     * on edge j+1, and the vertex between them is where the node goes either way.
     */
    private static Split splitAt(RoadSegment segment, int edgeIndex, double t, double px, double pz) {
        int index = edgeIndex;
        if (t >= 1 - VERTEX_EPSILON) {
            index++;
        }
        if (index <= 0 || index >= segment.vertexCount()) {
            return null;
        }
        if (t <= VERTEX_EPSILON && index == 1) {
            // The segment's own from-node: already joined.
            return null;
        }
        return new Split(segment.id(), index, t, (int) Math.round(px), (int) Math.round(pz));
    }

    /** Whether some one mode can travel on both this node's roads and the given class. */
    private static boolean joinable(RoadNetwork network, Map<Integer, List<Integer>> incident,
                                    int nodeId, RoadClass roadClass) {
        for (int segmentId : incident.getOrDefault(nodeId, List.of())) {
            RoadSegment segment = network.segment(segmentId);
            if (segment != null && sharesAMode(segment.roadClass(), roadClass)) {
                return true;
            }
        }
        return false;
    }

    /** Whether any travel mode in the mod can use both classes, which is what makes them one road. */
    private static boolean sharesAMode(RoadClass a, RoadClass b) {
        for (TravelMode mode : TravelMode.values()) {
            if (mode.allows(a) && mode.allows(b)) {
                return true;
            }
        }
        return false;
    }

    private static void attach(Map<Integer, List<Integer>> incident, int nodeId, int segmentId) {
        if (nodeId == RoadSegment.NO_NODE) {
            return;
        }
        incident.computeIfAbsent(nodeId, key -> new ArrayList<>()).add(segmentId);
    }

    // ------------------------------------------------------------------- doing it

    /**
     * Breaks each road at the points collected for it.
     *
     * <p>From the far end inwards, and always on the half that keeps the original's numbering: a split
     * at edge j leaves the piece up to j with vertices 0..j-1 untouched and the new node at the end,
     * so a point recorded at edge j' &lt; j is still at edge j' of the piece that remains. Taking them
     * the other way round would mean recomputing every index after every split.
     */
    private static int apply(RoadNetwork network, RoadEditor editor, List<Split> splits) {
        Map<Integer, List<Split>> bySegment = new HashMap<>();
        for (Split split : splits) {
            bySegment.computeIfAbsent(split.segmentId(), key -> new ArrayList<>()).add(split);
        }

        int made = 0;
        Comparator<Split> farEndFirst =
                Comparator.comparingInt(Split::edgeIndex).thenComparingDouble(Split::t).reversed();
        for (Map.Entry<Integer, List<Split>> entry : bySegment.entrySet()) {
            List<Split> points = entry.getValue();
            points.sort(farEndFirst);

            int remaining = entry.getKey();
            for (Split point : points) {
                RoadSegment current = network.segment(remaining);
                if (current == null) {
                    break;
                }
                RoadEditor.Split split = editor.splitSegmentInto(remaining, point.edgeIndex(),
                        point.x(), current.y(), point.z());
                if (split == null) {
                    continue;
                }
                remaining = split.firstSegment();
                made++;
            }
        }
        return made;
    }

    // ------------------------------------------------------------------- the grid

    /**
     * The network's edges filed by position, so a join is looked for among the handful of pieces of
     * road that could possibly be near it rather than among all of them.
     */
    private static final class Grid {

        private final Map<Long, List<Edge>> cells = new HashMap<>();
        private int edgeCount;

        Grid(RoadNetwork network) {
            for (RoadSegment segment : network.segmentsSnapshot()) {
                if (segment.fromNode() == RoadSegment.NO_NODE || segment.toNode() == RoadSegment.NO_NODE) {
                    continue;
                }
                for (int i = 1; i < segment.vertexCount(); i++) {
                    file(segment, i);
                    edgeCount++;
                }
            }
        }

        /** How many edges the network has, which is what says whether crossings are worth looking for. */
        int edgeCount() {
            return edgeCount;
        }

        /**
         * Files one edge into every cell it passes through.
         *
         * <p>Sampled rather than inserted only at its ends: a road a thousand blocks long crosses many
         * cells, and a join in the middle of it has to be found from the cell it is actually in. Half a
         * cell apart leaves no point of the edge more than a quarter cell from a sample, so anything
         * within the join distance of the edge is in a cell that was filed or one next to it -- which
         * is what the three-by-three lookups below cover.
         */
        private void file(RoadSegment segment, int edgeIndex) {
            double ax = segment.x(edgeIndex - 1);
            double az = segment.z(edgeIndex - 1);
            double bx = segment.x(edgeIndex);
            double bz = segment.z(edgeIndex);
            Edge edge = new Edge(segment.id(), edgeIndex, ax, az, bx, bz);
            Set<Long> filed = new HashSet<>();
            for (double[] point : samples(ax, az, bx, bz)) {
                long cell = key(cell(point[0]), cell(point[1]));
                if (filed.add(cell)) {
                    cells.computeIfAbsent(cell, k -> new ArrayList<>()).add(edge);
                }
            }
        }

        /** Every edge filed in the nine cells around a point. */
        List<Edge> edgesNear(double x, double z) {
            List<Edge> found = new ArrayList<>();
            int cx = cell(x);
            int cz = cell(z);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    found.addAll(cells.getOrDefault(key(cx + dx, cz + dz), List.of()));
                }
            }
            return found;
        }

        /**
         * Every edge filed in the nine cells around any point of a path, other than the path's own.
         *
         * <p>Asked along the whole path rather than at its middle, because a long edge's middle can be
         * a mile from where it crosses something. Edges of the segment the path belongs to are left
         * out here rather than rejected by the caller: a polyline with a vertex every block, which is
         * what a railway read out of the world is, files thousands of its own edges in the cells it
         * passes through, and every one of them is a candidate that cannot be a crossing.
         */
        List<Edge> edgesNearPath(double ax, double az, double bx, double bz, Edge path) {
            Set<Edge> found = new HashSet<>();
            for (double[] point : samples(ax, az, bx, bz)) {
                for (Edge edge : edgesNear(point[0], point[1])) {
                    if (edge.segmentId() != path.segmentId()) {
                        found.add(edge);
                    }
                }
            }
            return new ArrayList<>(found);
        }

        /** Points along an edge, close enough together that no part of it is far from one. */
        private static List<double[]> samples(double ax, double az, double bx, double bz) {
            double length = Math.hypot(bx - ax, bz - az);
            int count = Math.max(1, (int) Math.ceil(length / SAMPLE_STEP));
            List<double[]> points = new ArrayList<>(count + 1);
            for (int s = 0; s <= count; s++) {
                double t = (double) s / count;
                points.add(new double[]{ax + (bx - ax) * t, az + (bz - az) * t});
            }
            return points;
        }

        private static int cell(double coordinate) {
            return (int) Math.floor(coordinate / CELL);
        }

        private static long key(int cellX, int cellZ) {
            return ((long) (cellX & 0xFFFFFF) << 24) | (cellZ & 0xFFFFFF);
        }
    }
}
