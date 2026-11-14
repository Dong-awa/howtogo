package bili.dongsz.howtogo.road;

import java.util.ArrayList;
import java.util.List;

/**
 * A grid index over one RoadNetwork for nearest-point queries.
 * Exact, not approximate: nearestNode and nearestOnSegment return what an exhaustive walk would,
 * including the projected point and vertex span. The search widens by whole cells and stops when
 * the next ring cannot hold anything nearer; the caller's Coverage test and tie-break decide the
 * winner.
 */
public final class RoadSpatialIndex {

    /**
     * Cell size in blocks. Chosen against the largest snap radius rather than the network: a few-block
     * radius against tens-of-block cells reads one cell and its neighbours. Not smaller, because a
     * segment files one entry per cell its bounding box spans.
     */
    private static final double CELL = 24.0;

    /**
     * Most cells one axis pair may ask for. Two arrays of cell references, so four million is already
     * about 64 MB; unbounded, a network whose ends are a million blocks apart asked for 1.7 billion
     * cells and overflowed to a negative length.
     */
    private static final long MAX_CELLS = 4_000_000L;

    private final double originX;
    private final double originZ;
    private final int dimX;
    private final int dimZ;
    private final List<RoadNode>[] nodeCells;
    private final List<Edge>[] edgeCells;

    /**
     * Whether this index is one bucket because the network was too spread out to file into cells.
     * Queries then read the whole network -- slower than a grid, exactly as correct.
     */
    private final boolean flat;

    /** One vertex-to-vertex piece of a segment, with its endpoints kept for the projection. */
    private static final class Edge {
        private final RoadSegment segment;
        private final int index;
        private final double ax;
        private final double az;
        private final double bx;
        private final double bz;
        /** Where this edge sits in the network's own order; see the query tie-break. */
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
                             List<RoadNode>[] nodeCells, List<Edge>[] edgeCells, boolean flat) {
        this.originX = originX;
        this.originZ = originZ;
        this.dimX = dimX;
        this.dimZ = dimZ;
        this.nodeCells = nodeCells;
        this.edgeCells = edgeCells;
        this.flat = flat;
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
        // Segment vertices are positions too; a network of distant nodes bounds itself by them.
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

        // One cell of padding each side, so a query just outside the bounds lands on a real cell.
        double originX = minX - CELL;
        double originZ = minZ - CELL;
        // Measured in long: a cell count that does not fit an int must be compared, not wrapped.
        long cellsX = (long) Math.floor((maxX - originX) / CELL) + 2L;
        long cellsZ = (long) Math.floor((maxZ - originZ) / CELL) + 2L;
        // One bucket for a network too spread out to file; each dimension is tested first so the product
        // cannot overflow.
        boolean flat = cellsX > MAX_CELLS || cellsZ > MAX_CELLS || cellsX * cellsZ > MAX_CELLS;
        // Cast only once the product is known to be small.
        int dimX = flat ? 1 : (int) cellsX;
        int dimZ = flat ? 1 : (int) cellsZ;

        List<RoadNode>[] nodeCells = newCells(dimX, dimZ);
        List<Edge>[] edgeCells = newCells(dimX, dimZ);

        for (RoadNode node : network.nodes()) {
            int index = flat ? 0 : indexOf(node.x() - originX, node.z() - originZ, dimX, dimZ);
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
            // Filed into every cell its bounding box touches, so being in the bucket is the box test.
            for (int i = 1; i < segment.vertexCount(); i++) {
                Edge edge = new Edge(segment, i);
                if (flat) {
                    if (edgeCells[0] == null) {
                        edgeCells[0] = new ArrayList<>(4);
                    }
                    edgeCells[0].add(edge);
                    continue;
                }
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

        // Sorted so candidates come in the network's own order, which makes the tie-break a rule.
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
        return new RoadSpatialIndex(originX, originZ, dimX, dimZ, nodeCells, edgeCells, flat);
    }

    /**
     * Node nearest to (x, z) within radius blocks, or null. Exact ties go to the lower node id: a
     * stated rule rather than the old backing-map iteration order.
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
            // Nearer wins outright; canonical order only breaks exact ties.
            if (best == null || d < bestSq || (d == bestSq && node.id() < best.id())) {
                best = node;
                bestSq = d;
            }
        }
        return best;
    }

    /** The nodes in the cells a query of this radius has to read. */
    private List<RoadNode> gatherNodes(double x, double z, double radius) {
        // Flat index: the grid walk cannot reach its single bucket.
        if (flat) {
            List<RoadNode> only = nodeCells[0];
            return only == null ? new ArrayList<>() : new ArrayList<>(only);
        }
        List<RoadNode> found = new ArrayList<>();
        int cx = cellOf(x - originX);
        int cz = cellOf(z - originZ);
        // The cells the radius can reach, clipped to the grid. A rectangle as well, because a query outside
        // the grid cannot widen rings across empty space.
        int step = reachFor(radius);
        boolean insideGrid = cx >= 0 && cx < dimX && cz >= 0 && cz < dimZ;

        if (!insideGrid) {
            // Outside the grid: intersect the radius with the grid. Reading every bucket instead would make a
            // far-away cursor with a small reach the most expensive query.
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

        // Ring r reaches at most r cells out, so past the far corner there is nothing left to read.
        int maxRing = Math.min(step, Math.max(dimX - 1, dimZ - 1));
        for (int ring = 0; ring <= maxRing; ring++) {
            // Nothing this far out can be within the radius.
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

    /** Nearest point on any segment, or null. Coverage states the caller's within test and tie-break. */
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
            // A zero-length edge is a vertex written twice; it is still a point the cursor can be on.
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
            // Nearer wins outright; canonical order -- earlier segment, then earlier vertex span -- only breaks
            // exact ties.
            boolean better = best == null || d < bestDistanceSq
                    || (d == bestDistanceSq && edge.order < best.orderOf());
            if (!better) {
                continue;
            }
            SegmentHit hit = new SegmentHit(edge.segment, edge.index, t, px, pz, dx, dz, edge.order);
            // Asked of the finished candidate, so the rule is stated once rather than per axis.
            if (coverage.within(hit.dx(), hit.dz())) {
                best = hit;
                bestDistanceSq = d;
            }
        }
        return best;
    }

    /** The edges in the cells a query of this radius has to read. */
    private List<Edge> gatherEdges(double x, double z, double radius) {
        // See gatherNodes: a flat index has no rings to widen from.
        if (flat) {
            List<Edge> only = edgeCells[0];
            return only == null ? new ArrayList<>() : new ArrayList<>(only);
        }
        List<Edge> found = new ArrayList<>();
        int cx = cellOf(x - originX);
        int cz = cellOf(z - originZ);
        int step = reachFor(radius);
        boolean insideGrid = cx >= 0 && cx < dimX && cz >= 0 && cz < dimZ;

        if (!insideGrid) {
            // Overlap of the radius with the grid, for the reason given on the node search.
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

    /** A point found on a segment: segment, vertex span, position along it, and coordinates. */
    public record SegmentHit(RoadSegment segment, int edgeIndex, double t, double x, double z,
                             double dx, double dz, long orderOf) {
    }

    /**
     * The caller's notion of near enough. One question, not two: ties are settled by the index's own
     * order.
     */
    public interface Coverage {

        /** Whether the offset from the query to the point counts as within the radius. */
        boolean within(double dx, double dz);
    }

    /**
     * Rings worth reading for this radius: enough to cover it plus one, so a candidate just inside the
     * last ring is still reached.
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
        return new RoadSpatialIndex(0, 0, 0, 0, newCells(0, 0), newCells(0, 0), false);
    }
}
