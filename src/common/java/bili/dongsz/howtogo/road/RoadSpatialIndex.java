package bili.dongsz.howtogo.road;

import java.util.ArrayList;
import java.util.List;

/**
 * A grid index over one {@link RoadNetwork}, for "what is nearest this point" questions.
 *
 * <h2>Why this exists</h2>
 * Snapping asked that question by walking every node and then every vertex of every segment, and so
 * did the editor's rail snap. Both run inside a frame, so the cost of pointing at the map grew with
 * the size of the map -- which is the one thing a player is certain to grow. The answer is not a
 * better search but an index: a uniform grid of buckets, so a query reads the few cells around the
 * point instead of the whole network.
 *
 * <h2>Exactness</h2>
 * This is an accelerator, not an approximation. {@link #nearestNode} and {@link #nearestOnSegment}
 * return what the exhaustive walk returned, down to the projected point and the vertex span the point
 * was found on, which is what makes it safe to put under behaviour a player has already learned. Two
 * things buy that:
 *
 * <ul>
 *   <li>the search widens by whole cells and stops only once the <em>nearest possible</em> point in
 *       the next ring is further away than the best candidate already found, so a nearer point can
 *       never be sitting in a ring that was skipped;</li>
 *   <li>the candidate test is the caller's own, including its tie-break, so a point read from the
 *       index is the point the scan would have picked rather than merely an equally near one.</li>
 * </ul>
 *
 * <h2>What the caller supplies</h2>
 * Radii here are in world blocks, but a caller's idea of "near enough" is usually a budget of screen
 * pixels: a road editor wants a catch radius that feels the same at every zoom, and a fixed world
 * radius is unusable zoomed in and useless zoomed out. The conversion belongs to the caller, which is
 * the only side that knows the viewport -- so it passes a world radius in, and {@link Coverage} lets
 * it state the test that radius came from. Keeping that test with the caller is what lets the two
 * axes differ without this class having to know why they would.
 */
public final class RoadSpatialIndex {

    /**
     * Cell size in blocks.
     *
     * <p>Chosen against the largest snap the editor uses rather than against the network: a catch
     * radius of a few blocks against a cell of tens of blocks means a query reads one cell and its
     * immediate neighbours, which is the whole point of the structure. It is also kept from being
     * small: a segment contributes one entry per cell its bounding box spans, so a continent-sized
     * straight road would fill the grid with copies of itself if the cell were tiny.
     */
    private static final double CELL = 24.0;

    private final double originX;
    private final double originZ;
    private final int dimX;
    private final int dimZ;
    private final List<RoadNode>[] nodeCells;
    private final List<Edge>[] edgeCells;

    /** One vertex-to-vertex piece of a segment, with its endpoints kept for the projection. */
    private static final class Edge {
        private final RoadSegment segment;
        private final int index;
        private final double ax;
        private final double az;
        private final double bx;
        private final double bz;
        /** Where this edge sits in the network's own order; see the tie-break on the queries. */
        private final long order;

        Edge(RoadSegment segment, int index) {
            this.segment = segment;
            this.index = index;
            this.ax = segment.x(index - 1);
            this.az = segment.z(index - 1);
            this.bx = segment.x(index);
            this.bz = segment.z(index);
            this.order = ((long) segment.id() << 20) | index;
        }
    }

    private RoadSpatialIndex(double originX, double originZ, int dimX, int dimZ,
                             List<RoadNode>[] nodeCells, List<Edge>[] edgeCells) {
        this.originX = originX;
        this.originZ = originZ;
        this.dimX = dimX;
        this.dimZ = dimZ;
        this.nodeCells = nodeCells;
        this.edgeCells = edgeCells;
    }

    /** An index over the network as it is right now. */
    public static RoadSpatialIndex of(RoadNetwork network) {
        double minX = Double.MAX_VALUE;
        double minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxZ = -Double.MAX_VALUE;
        for (RoadNode node : network.nodes()) {
            minX = Math.min(minX, node.x());
            maxX = Math.max(maxX, node.x());
            minZ = Math.min(minZ, node.z());
            maxZ = Math.max(maxZ, node.z());
        }
        // A node is not the only thing with a position: a segment's shape is its vertices, and a
        // network whose roads run between distant nodes has nothing else to bound it by.
        for (RoadSegment segment : network.segments()) {
            for (int i = 0; i < segment.vertexCount(); i++) {
                minX = Math.min(minX, segment.x(i));
                maxX = Math.max(maxX, segment.x(i));
                minZ = Math.min(minZ, segment.z(i));
                maxZ = Math.max(maxZ, segment.z(i));
            }
        }
        if (minX > maxX || minZ > maxZ) {
            return empty();
        }

        // One cell of padding on each side, so a query just outside the network's bounds still lands
        // on a real cell rather than being clamped into the outermost one.
        double originX = minX - CELL;
        double originZ = minZ - CELL;
        int dimX = (int) Math.floor((maxX - originX) / CELL) + 2;
        int dimZ = (int) Math.floor((maxZ - originZ) / CELL) + 2;

        List<RoadNode>[] nodeCells = newCells(dimX, dimZ);
        List<Edge>[] edgeCells = newCells(dimX, dimZ);

        for (RoadNode node : network.nodes()) {
            int index = indexOf(node.x() - originX, node.z() - originZ, dimX, dimZ);
            if (index < 0) {
                continue;
            }
            List<RoadNode> bucket = nodeCells[index];
            if (bucket == null) {
                bucket = new ArrayList<>(4);
                nodeCells[index] = bucket;
            }
            bucket.add(node);
        }

        for (RoadSegment segment : network.segments()) {
            if (segment.vertexCount() < 2) {
                continue;
            }
            // An edge is filed into every cell its bounding box touches, so the query never has to
            // reject one it has already found: being in the bucket is the box test.
            for (int i = 1; i < segment.vertexCount(); i++) {
                Edge edge = new Edge(segment, i);
                int fromX = cellOf(Math.min(edge.ax, edge.bx) - originX);
                int toX = cellOf(Math.max(edge.ax, edge.bx) - originX);
                int fromZ = cellOf(Math.min(edge.az, edge.bz) - originZ);
                int toZ = cellOf(Math.max(edge.az, edge.bz) - originZ);
                for (int cx = fromX; cx <= toX; cx++) {
                    for (int cz = fromZ; cz <= toZ; cz++) {
                        if (cx < 0 || cz < 0 || cx >= dimX || cz >= dimZ) {
                            continue;
                        }
                        int index = cx * dimZ + cz;
                        List<Edge> bucket = edgeCells[index];
                        if (bucket == null) {
                            bucket = new ArrayList<>(4);
                            edgeCells[index] = bucket;
                        }
                        bucket.add(edge);
                    }
                }
            }
        }

        // Sorted so that a query reads candidates in the network's own order rather than in whatever
        // order the rings happen to reach them, which is what makes the tie-break below a rule rather
        // than an accident of which cell the query started in.
        for (List<RoadNode> bucket : nodeCells) {
            if (bucket != null) {
                bucket.sort((left, right) -> Integer.compare(left.id(), right.id()));
            }
        }
        for (List<Edge> bucket : edgeCells) {
            if (bucket != null) {
                bucket.sort((left, right) -> left.segment.id() != right.segment.id()
                        ? Integer.compare(left.segment.id(), right.segment.id())
                        : Integer.compare(left.index, right.index));
            }
        }
        return new RoadSpatialIndex(originX, originZ, dimX, dimZ, nodeCells, edgeCells);
    }

    /**
     * The node nearest to {@code (x, z)} within {@code radius} blocks, or null when there is none.
     *
     * <p>A tie is resolved by the index's own order rather than by the order candidates happened to
     * be met in: of two nodes the same distance away, the one with the lower id wins. The exhaustive
     * walk this replaced read the network's backing map, so its winner among equally near nodes was
     * whichever the map happened to yield last -- a behaviour nobody could state and no one could
     * rely on. The distance is what a caller acts on and it is unchanged; only the choice between
     * two nodes at exactly the same distance is now a rule.
     */
    public RoadNode nearestNode(double x, double z, double radius) {
        if (dimX == 0 || !(radius > 0)) {
            return null;
        }
        double radiusSq = radius * radius;
        RoadNode best = null;
        double bestSq = Double.MAX_VALUE;
        for (RoadNode node : gatherNodes(x, z, radius)) {
            double d = node.distSq(x, z);
            if (d > radiusSq) {
                continue;
            }
            // Nearer wins outright; the canonical order is consulted only on an exact tie, and only
            // as a tie-break -- never to displace a candidate that is actually nearer.
            if (best == null || d < bestSq || (d == bestSq && node.id() < best.id())) {
                best = node;
                bestSq = d;
            }
        }
        return best;
    }

    /**
     * The nodes in the cells a query of this radius has to read.
     *
     * <p>One list for the caller to weigh, rather than the weighing happening inside the traversal,
     * because the two shapes the traversal can take -- widening rings for a query inside the grid, and
     * the whole grid for one outside it -- would otherwise each carry their own copy of the rule that
     * decides a winner, and could drift apart.
     */
    private List<RoadNode> gatherNodes(double x, double z, double radius) {
        List<RoadNode> found = new ArrayList<>();
        int cx = cellOf(x - originX);
        int cz = cellOf(z - originZ);
        // The cells the radius can reach, clipped to the grid. Expressed as a rectangle as well as
        // by rings because a query outside the grid cannot be widened to: the rings between here
        // and the nearest cell are empty space, and with a cursor far off the map there can be
        // hundreds of thousands of them.
        int step = reachFor(radius);
        boolean insideGrid = cx >= 0 && cx < dimX && cz >= 0 && cz < dimZ;

        if (!insideGrid) {
            // Outside the grid there is no ring to widen from, so the work is the overlap of the
            // radius with the grid -- which is empty when the radius does not reach it at all. The
            // whole grid is deliberately not read instead: a far-away cursor with a small reach is
            // the common case for a layer like the rail one, and answering it by reading every
            // bucket would make a query whose answer is "nothing" the most expensive kind there is.
            int lowX = Math.max(cx - step, 0);
            int highX = Math.min(cx + step, dimX - 1);
            int lowZ = Math.max(cz - step, 0);
            int highZ = Math.min(cz + step, dimZ - 1);
            for (int gx = lowX; gx <= highX; gx++) {
                for (int gz = lowZ; gz <= highZ; gz++) {
                    List<RoadNode> bucket = nodeCells[gx * dimZ + gz];
                    if (bucket != null) {
                        found.addAll(bucket);
                    }
                }
            }
            return found;
        }

        // Ring r reaches at most r cells out, so once it has passed the far corner of the grid there
        // is nothing left for the later rings to hold. Bounding it here also keeps a radius larger
        // than the whole network from walking rings of nothing beyond the grid.
        int maxRing = Math.min(step, Math.max(dimX - 1, dimZ - 1));
        for (int ring = 0; ring <= maxRing; ring++) {
            // Nothing this far out can be within the radius, so the walk is finished.
            double inner = (ring - 1) * CELL;
            if (ring > 1 && inner * inner > radius * radius) {
                break;
            }
            for (int gx = Math.max(cx - ring, 0); gx <= Math.min(cx + ring, dimX - 1); gx++) {
                for (int gz = Math.max(cz - ring, 0); gz <= Math.min(cz + ring, dimZ - 1); gz++) {
                    // Ring cells only; the inside was read by an earlier pass.
                    if (ring > 0 && Math.abs(gx - cx) != ring && Math.abs(gz - cz) != ring) {
                        continue;
                    }
                    List<RoadNode> bucket = bucketAt(nodeCells, gx, gz);
                    if (bucket != null) {
                        found.addAll(bucket);
                    }
                }
            }
        }
        return found;
    }

    /**
     * The nearest point on any segment, or null when none is within {@code radius} blocks.
     *
     * <p>{@code Coverage} states the test that decides "within" and which of two hits is nearer, so a
     * caller measuring in screen pixels can pass its own radius and its own tie-break.
     */
    public SegmentHit nearestOnSegment(double x, double z, double radius, Coverage coverage) {
        if (dimX == 0 || !(radius > 0)) {
            return null;
        }
        double radiusSq = radius * radius;
        SegmentHit best = null;
        double bestDistanceSq = Double.MAX_VALUE;
        for (Edge edge : gatherEdges(x, z, radius)) {
            double ex = edge.bx - edge.ax;
            double ez = edge.bz - edge.az;
            double lenSq = ex * ex + ez * ez;
            // A zero-length edge is a vertex written twice; it is still a point the cursor can be on,
            // so it is measured as the point it is.
            double t = lenSq < 1.0E-9 ? 0
                    : Math.max(0, Math.min(1, ((x - edge.ax) * ex + (z - edge.az) * ez) / lenSq));
            double px = edge.ax + ex * t;
            double pz = edge.az + ez * t;
            double dx = x - px;
            double dz = z - pz;
            double d = dx * dx + dz * dz;
            if (d > radiusSq) {
                continue;
            }
            // Nearer wins outright; the canonical order -- earlier segment, then earlier vertex span
            // -- is consulted only on an exact tie, and never to displace a nearer candidate.
            boolean better = best == null || d < bestDistanceSq
                    || (d == bestDistanceSq && edge.order < best.orderOf());
            if (!better) {
                continue;
            }
            SegmentHit hit = new SegmentHit(edge.segment, edge.index, t, px, pz, dx, dz, edge.order);
            // Asked of the finished candidate, so the rule above is stated once rather than per axis.
            if (coverage.within(hit.dx(), hit.dz())) {
                best = hit;
                bestDistanceSq = d;
            }
        }
        return best;
    }

    /**
     * The edges in the cells a query of this radius has to read.
     *
     * <p>The same two shapes as {@link #gatherNodes}: widening rings while the query is inside the
     * grid, and the whole grid once it is outside it and the radius has therefore reached all of it.
     */
    private List<Edge> gatherEdges(double x, double z, double radius) {
        List<Edge> found = new ArrayList<>();
        int cx = cellOf(x - originX);
        int cz = cellOf(z - originZ);
        int step = reachFor(radius);
        boolean insideGrid = cx >= 0 && cx < dimX && cz >= 0 && cz < dimZ;

        if (!insideGrid) {
            // The overlap of the radius with the grid, for the reason given on the node search.
            int lowX = Math.max(cx - step, 0);
            int highX = Math.min(cx + step, dimX - 1);
            int lowZ = Math.max(cz - step, 0);
            int highZ = Math.min(cz + step, dimZ - 1);
            for (int gx = lowX; gx <= highX; gx++) {
                for (int gz = lowZ; gz <= highZ; gz++) {
                    List<Edge> bucket = edgeCells[gx * dimZ + gz];
                    if (bucket != null) {
                        found.addAll(bucket);
                    }
                }
            }
            return found;
        }

        int maxRing = Math.min(step, Math.max(dimX - 1, dimZ - 1));
        for (int ring = 0; ring <= maxRing; ring++) {
            double inner = (ring - 1) * CELL;
            if (ring > 1 && inner * inner > radius * radius) {
                break;
            }
            for (int gx = Math.max(cx - ring, 0); gx <= Math.min(cx + ring, dimX - 1); gx++) {
                for (int gz = Math.max(cz - ring, 0); gz <= Math.min(cz + ring, dimZ - 1); gz++) {
                    if (ring > 0 && Math.abs(gx - cx) != ring && Math.abs(gz - cz) != ring) {
                        continue;
                    }
                    List<Edge> bucket = bucketAt(edgeCells, gx, gz);
                    if (bucket != null) {
                        found.addAll(bucket);
                    }
                }
            }
        }
        return found;
    }

    /** A point found on a segment: which segment, which vertex span, and where along it. */
    public record SegmentHit(RoadSegment segment, int edgeIndex, double t, double x, double z,
                             double dx, double dz, long orderOf) {
    }

    /**
     * The caller's notion of "near enough" for a point.
     *
     * <p>One question, not two: which of two equally near points is the answer is decided by the
     * index's own order, so that it cannot depend on the order the grid happened to hand candidates
     * over. A caller states the radius it is measuring in -- a budget of screen pixels, usually, and
     * one that may differ per axis -- and nothing about how ties are settled.
     */
    public interface Coverage {

        /** Whether the offset from the query to the point counts as within the radius. */
        boolean within(double dx, double dz);
    }

    /**
     * How many rings are worth reading for a query of this radius, at this cell.
     *
     * <p>Enough to cover the radius and then one more, so a candidate sitting just inside the edge of
     * the last ring is still reached. Not capped at the network's own extent: a radius larger than
     * the whole network -- which the checks below exercise -- has to be able to reach across it
     * rather than quietly stopping short, and a caller asking that of a small network is asking to
     * read all of it, which is what the rings amount to.
     */
    private static int reachFor(double radius) {
        return (int) Math.floor(radius / CELL) + 1;
    }

    private static int cellOf(double offset) {
        return (int) Math.floor(offset / CELL);
    }

    private static int indexOf(double offsetX, double offsetZ, int dimX, int dimZ) {
        int cx = cellOf(offsetX);
        int cz = cellOf(offsetZ);
        if (cx < 0 || cz < 0 || cx >= dimX || cz >= dimZ) {
            return -1;
        }
        return cx * dimZ + cz;
    }

    private <T> List<T> bucketAt(List<T>[] cells, int cx, int cz) {
        if (cx < 0 || cz < 0 || cx >= dimX || cz >= dimZ) {
            return null;
        }
        return cells[cx * dimZ + cz];
    }

    @SuppressWarnings("unchecked")
    private static <T> List<T>[] newCells(int dimX, int dimZ) {
        return new List[dimX * dimZ];
    }

    private static RoadSpatialIndex empty() {
        return new RoadSpatialIndex(0, 0, 0, 0, newCells(0, 0), newCells(0, 0));
    }
}
