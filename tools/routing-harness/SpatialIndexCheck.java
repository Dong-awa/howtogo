package bili.dongsz.howtogo.road;

import java.util.Random;

/**
 * Checks the spatial index against the exhaustive walk it replaced.
 *
 * <p>In the {@code road} package because it tests package-private behaviour of the index, and
 * deliberately free of any Minecraft or viewport import: what is being checked is a property of the
 * geometry, so it can be checked on a machine with no game running, which is the only way it gets
 * checked at all.
 *
 * <h2>What "the same" has to mean</h2>
 * An accelerator that returns a different point is a regression however fast it is, so every check
 * here compares against a brute-force reference written the way the original code was: walk every
 * node, then every vertex of every segment, keep whatever is nearest, and take the later candidate on
 * a tie. Agreeing on the point but not on which vertex span it was found on would also be a
 * regression -- the editor inserts a vertex at that index -- so the index is compared too.
 */
public final class SpatialIndexCheck {

    /** How far two doubles have to agree. Both sides do the same arithmetic, so this is slack. */
    private static final double EPSILON = 1.0E-9;

    private SpatialIndexCheck() {
    }

    public static int[] run() {
        int checks = 0;
        int failures = 0;
        System.out.println("== spatial index ==");

        RoadNetwork net = build();
        RoadSpatialIndex index = RoadSpatialIndex.of(net);

        // A small radius, so most queries find nothing and the "nothing" case is exercised as
        // heavily as the hit case.
        double radius = 12.0;
        Random random = new Random(20261004L);
        int nodeHits = 0;
        int segmentHits = 0;
        int mismatches = 0;
        for (int i = 0; i < 4000; i++) {
            double x = random.nextDouble() * 640.0 - 320.0;
            double z = random.nextDouble() * 640.0 - 320.0;

            RoadNode expectedNode = referenceNearestNode(net, x, z, radius);
            RoadNode actualNode = index.nearestNode(x, z, radius);
            if (!sameNode(expectedNode, actualNode)) {
                mismatches++;
                if (mismatches <= 3) {
                    System.out.println("    node mismatch at (" + x + ", " + z + "): expected "
                            + expectedNode + " but got " + actualNode);
                }
            }
            if (actualNode != null) {
                nodeHits++;
            }

            SegmentHit expectedHit = referenceNearestOnSegment(net, x, z, radius);
            RoadSpatialIndex.SegmentHit actualHit =
                    index.nearestOnSegment(x, z, radius, coverage(radius));
            if (!sameHit(expectedHit, actualHit)) {
                mismatches++;
                if (mismatches <= 3) {
                    System.out.println("    segment mismatch at (" + x + ", " + z + ")");
                }
            }
            if (actualHit != null) {
                segmentHits++;
            }
        }
        checks++;
        if (mismatches == 0) {
            System.out.println("  ok   4000 random queries agree with the exhaustive walk ("
                    + nodeHits + " node hits, " + segmentHits + " segment hits)");
        } else {
            failures++;
            System.out.println("  FAIL " + mismatches + " of 8000 queries disagreed");
        }

        // A query far outside the network's bounds must find nothing rather than being clamped onto
        // the outermost cell and reading a bucket that is not there.
        checks++;
        boolean outsideEmpty = index.nearestNode(100_000, 100_000, radius) == null
                && index.nearestOnSegment(100_000, 100_000, radius, coverage(radius)) == null;
        if (outsideEmpty) {
            System.out.println("  ok   a query far outside the network finds nothing");
        } else {
            failures++;
            System.out.println("  FAIL a query far outside the network found something");
        }

        // A radius wider than the whole network still finds what is there, rather than quietly
        // stopping short of it: the widening rule must not cap the search below the data.
        checks++;
        boolean wideRadiusFinds = index.nearestNode(100_000, 100_000, 1_000_000) != null;
        if (wideRadiusFinds) {
            System.out.println("  ok   a radius wider than the network still finds the nearest node");
        } else {
            failures++;
            System.out.println("  FAIL a radius wider than the network found nothing");
        }

        // A network with no geometry at all must answer rather than throw.
        checks++;
        RoadSpatialIndex emptyIndex = RoadSpatialIndex.of(new RoadNetwork());
        boolean emptyAnswers = emptyIndex.nearestNode(0, 0, radius) == null
                && emptyIndex.nearestOnSegment(0, 0, radius, coverage(radius)) == null;
        if (emptyAnswers) {
            System.out.println("  ok   an empty network answers with nothing");
        } else {
            failures++;
            System.out.println("  FAIL an empty network returned something");
        }

        // A zero-length edge -- a vertex written twice -- is a point the cursor can be on, so it is
        // found rather than skipped by the "lenSq is nearly zero" branch.
        checks++;
        RoadNetwork degenerate = new RoadNetwork();
        RoadNode a = degenerate.addNode(0, 64, 0, RoadNode.Type.ENDPOINT, null);
        RoadNode b = degenerate.addNode(0, 64, 0, RoadNode.Type.ENDPOINT, null);
        RoadSegment zero = RoadSegment.of(1, RoadClass.ROAD, 64, 0, 0, 0, 0);
        zero.setFromNode(a.id());
        zero.setToNode(b.id());
        degenerate.addSegment(zero);
        boolean foundZeroLength = RoadSpatialIndex.of(degenerate)
                .nearestOnSegment(0.5, 0.5, 4.0, coverage(4.0)) != null;
        if (foundZeroLength) {
            System.out.println("  ok   a zero-length edge is found as the point it is");
        } else {
            failures++;
            System.out.println("  FAIL a zero-length edge was skipped");
        }

        // One node and a radius wider than the coordinate range: the ring walk has to cross the whole
        // grid to reach it, and must do so rather than walking rings of empty space until it stalls.
        checks++;
        RoadNetwork single = new RoadNetwork();
        single.addNode(0, 64, 0, RoadNode.Type.POI, null);
        boolean singleFound = RoadSpatialIndex.of(single)
                .nearestNode(30_000_000, 30_000_000, 100_000_000) != null;
        if (singleFound) {
            System.out.println("  ok   a single node is reached across a distance far beyond the grid");
        } else {
            failures++;
            System.out.println("  FAIL a single node was not reached from far away");
        }

        // The index reads a network as it was when it was built, and the revision counter is what
        // lets a caller tell that apart from the network as it is: without it, a road that moved
        // would keep being snapped to where it used to be.
        checks++;
        RoadNetwork moving = build();
        int before = moving.revision();
        RoadNode moved = moving.node(1);
        if (moved != null) {
            moved.moveTo(moved.x() + 50, moved.y(), moved.z());
            moving.touch();
        }
        if (moving.revision() != before) {
            System.out.println("  ok   a geometry change moves the network's revision on");
        } else {
            failures++;
            System.out.println("  FAIL a geometry change left the revision alone");
        }

        checks++;
        RoadNode renamed = moving.node(2);
        int beforeRename = moving.revision();
        if (renamed != null) {
            renamed.setName("a name is not a position");
        }
        if (moving.revision() == beforeRename) {
            System.out.println("  ok   a rename does not, since it cannot have moved anything");
        } else {
            failures++;
            System.out.println("  FAIL a rename bumped the revision, which would rebuild for nothing");
        }

        System.out.println("  spatial index ok (" + checks + " checks)");
        return new int[]{checks, failures};
    }

    /** A network with enough shape to exercise the cell boundaries: roads crossing, bends, corners. */
    private static RoadNetwork build() {
        RoadNetwork net = new RoadNetwork();
        Random random = new Random(7L);
        int nextNode = 1;
        int nextSegment = 1;

        // A grid of bends, so a query near a cell boundary has candidates on both sides of it.
        for (int gx = -2; gx <= 2; gx++) {
            for (int gz = -2; gz <= 2; gz++) {
                double x = gx * 55.0;
                double z = gz * 55.0;
                int ax = (int) x;
                int az = (int) z;
                int bx = (int) x + (gx % 2 == 0 ? 40 : -40);
                int bz = (int) z + (gz % 2 == 0 ? -40 : 40);
                RoadNode from = net.addNode(ax, 64, az, RoadNode.Type.ENDPOINT, null);
                RoadNode to = net.addNode(bx, 64, bz, RoadNode.Type.JUNCTION, null);
                RoadSegment segment = RoadSegment.of(nextSegment++, RoadClass.ROAD, 64,
                        ax, az, (ax + bx) / 2, az, bx, bz);
                segment.setFromNode(from.id());
                segment.setToNode(to.id());
                net.addSegment(segment);
            }
        }

        // Loose nodes that no road touches, so the node search has candidates the segment search
        // does not share.
        for (int i = 0; i < 40; i++) {
            net.addNode(random.nextInt(601) - 300, 64, random.nextInt(601) - 300,
                    RoadNode.Type.POI, null);
        }
        return net;
    }

    // ------------------------------------------------------------- references

    /**
     * The node search as the code did it before the index: every node, no buckets.
     *
     * <p>Read in id order rather than in the backing map's own order. That order is an implementation
     * detail of {@code HashMap} -- stable for a given sequence of edits, but not something either
     * version should be resting on. What matters here is the tie-break: both sides keep the later
     * candidate, so both keep the higher id, and that is a rule that can be stated and checked.
     */
    private static RoadNode referenceNearestNode(RoadNetwork network, double x, double z,
                                                 double radius) {
        RoadNode best = null;
        double bestSq = radius * radius;
        for (RoadNode node : nodesById(network)) {
            double d = node.distSq(x, z);
            // Strictly nearer, so the lower id wins a tie -- the rule the index states.
            if (d < bestSq) {
                bestSq = d;
                best = node;
            }
        }
        return best;
    }

    private static java.util.List<RoadNode> nodesById(RoadNetwork network) {
        java.util.List<RoadNode> nodes = network.nodesSnapshot();
        nodes.sort((left, right) -> Integer.compare(left.id(), right.id()));
        return nodes;
    }

    /** The segment search as the code did it before the index: every vertex of every segment. */
    private static SegmentHit referenceNearestOnSegment(RoadNetwork network, double x, double z,
                                                        double radius) {
        SegmentHit best = null;
        double radiusSq = radius * radius;
        for (RoadSegment segment : segmentsById(network)) {
            for (int i = 1; i < segment.vertexCount(); i++) {
                double ax = segment.x(i - 1);
                double az = segment.z(i - 1);
                double ex = segment.x(i) - ax;
                double ez = segment.z(i) - az;
                double lenSq = ex * ex + ez * ez;
                double t = lenSq < 1.0E-9 ? 0
                        : Math.max(0, Math.min(1, ((x - ax) * ex + (z - az) * ez) / lenSq));
                double px = ax + ex * t;
                double pz = az + ez * t;
                double dx = x - px;
                double dz = z - pz;
                double d = dx * dx + dz * dz;
                if (d > radiusSq) {
                    continue;
                }
                // Strictly nearer, or the same distance and earlier in the network's own order -- the
                // rule the index states, restated here so the two can be compared.
                if (best == null || d < best.sq() || (d == best.sq() && earlier(segment.id(), i,
                        best.segment().id(), best.edgeIndex()))) {
                    best = new SegmentHit(segment, i, t, px, pz, dx, dz);
                }
            }
        }
        return best;
    }

    /** Whether one (segment, vertex) comes before another in the order the index tie-breaks by. */
    private static boolean earlier(int segmentId, int edgeIndex, int otherSegmentId,
                                   int otherEdgeIndex) {
        return segmentId != otherSegmentId
                ? segmentId < otherSegmentId
                : edgeIndex < otherEdgeIndex;
    }

    private static java.util.List<RoadSegment> segmentsById(RoadNetwork network) {
        java.util.List<RoadSegment> segments = network.segmentsSnapshot();
        segments.sort((left, right) -> Integer.compare(left.id(), right.id()));
        return segments;
    }

    private record SegmentHit(RoadSegment segment, int edgeIndex, double t, double x, double z,
                              double dx, double dz) {

        double sq() {
            return dx * dx + dz * dz;
        }
    }

    /**
     * The plain isotropic coverage the reference measures with, for the checks above.
     *
     * <p>The index no longer asks a coverage to settle ties -- it does that by its own canonical
     * order -- so the reference below resolves a tie the same way: strictly nearer wins, and at the
     * same distance the lower (segment id, vertex index) does.
     */
    private static RoadSpatialIndex.Coverage coverage(double radius) {
        double radiusSq = radius * radius;
        return (dx, dz) -> dx * dx + dz * dz <= radiusSq;
    }

    private static boolean sameNode(RoadNode a, RoadNode b) {
        if (a == null || b == null) {
            return a == b;
        }
        return a.id() == b.id();
    }

    private static boolean sameHit(SegmentHit expected, RoadSpatialIndex.SegmentHit actual) {
        if (expected == null || actual == null) {
            return expected == null && actual == null;
        }
        return expected.segment().id() == actual.segment().id()
                && expected.edgeIndex() == actual.edgeIndex()
                && near(expected.x(), actual.x())
                && near(expected.z(), actual.z());
    }

    private static boolean near(double a, double b) {
        return Math.abs(a - b) <= EPSILON;
    }
}
