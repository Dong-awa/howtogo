package bili.dongsz.howtogo.place;

import bili.dongsz.howtogo.road.RoadChains;
import bili.dongsz.howtogo.road.RoadClass;
import bili.dongsz.howtogo.road.RoadNetwork;
import bili.dongsz.howtogo.road.RoadSegment;
import bili.dongsz.howtogo.road.RoadSpatialIndex;

/**
 * Where a place is, said the way a person says it: the road it is on, which side of it, and how far.
 * Derived, never stored, so it cannot go stale when the roads change. Three rules in order: the
 * nearest road of any kind wins, named by the chain it belongs to; otherwise the nearest named road
 * within the same radius; otherwise the nearest segment's class, side and distance with an empty
 * name. Address.NONE only when no segment is within the radius.
 * Geometry and string selection only -- no Minecraft classes -- so the routing harness can check it
 * without a game.
 */
public final class PlaceAddress {

    private PlaceAddress() {
    }

    /** Which side of the road the place is on, from the road's point of view. */
    public enum Side {
        NORTH,
        EAST,
        SOUTH,
        WEST,
        /** Neither side: on the road itself, or on the diagonal where naming one would be a guess. */
        NONE
    }

    /**
     * One address: road, class, side and distance in blocks. The constructor enforces the three states:
     * no road (NONE), unnamed road (empty name), named road.
     */
    public record Address(String roadName, RoadClass roadClass, Side side, int distanceBlocks) {

        /** No road anywhere within the radius; distance -1, since zero would mean standing on the road. */
        public static final Address NONE = new Address("", null, Side.NONE, -1);

        public Address {
            roadName = roadName == null ? "" : roadName.strip();
            side = side == null ? Side.NONE : side;
            if (roadClass == null) {
                roadName = "";
                side = Side.NONE;
                distanceBlocks = -1;
            } else {
                distanceBlocks = Math.max(0, distanceBlocks);
            }
        }

        /** Whether the nearest road of any kind within the radius has a name. */
        public boolean onRoad() {
            return !roadName.isEmpty();
        }

        /** Whether there is any road within the radius, named or not; the interface needs both. */
        public boolean nearRoad() {
            return roadClass != null;
        }
    }

    /** Within this many blocks of a road point the place counts as on the road. */
    private static final double ON_ROAD_EPSILON = 1.0E-6;

    /**
     * Address of (x, z) in this network, or Address.NONE when no road is within maxDistance world
     * blocks. maxDistance is where that number comes from: the interface passes 64.
     */
    public static Address nearest(RoadNetwork network, int x, int z, double maxDistance) {
        if (network == null || !(maxDistance > 0) || network.segmentCount() == 0) {
            return Address.NONE;
        }
        RoadSpatialIndex index = RoadSpatialIndex.of(network);
        RoadSpatialIndex.SegmentHit nearest =
                index.nearestOnSegment(x, z, maxDistance, circle(maxDistance));
        if (nearest == null) {
            return Address.NONE;
        }

        // Built after the cheap no-road answer: a distant place needs no grouping of the whole network.
        RoadChains.Grouping grouping = RoadChains.group(network);
        String name = cleanName(grouping.nameOf(nearest.segment()));
        if (!name.isEmpty()) {
            return fromHit(name, nearest, x, z);
        }
        Named named = nearestNamed(network, grouping, x, z, maxDistance);
        if (named != null) {
            return new Address(named.name(), named.segment().roadClass(),
                    sideOf(x, z, named.x(), named.z()), roundDistance(x, z, named.x(), named.z()));
        }
        // Only an unnamed road in range: keep its class, side and distance with an empty name.
        return fromHit("", nearest, x, z);
    }

    /** The address one hit on a road produces, with the name its chain carries. */
    private static Address fromHit(String name, RoadSpatialIndex.SegmentHit hit, int x, int z) {
        return new Address(name, hit.segment().roadClass(), sideOf(x, z, hit.x(), hit.z()),
                roundDistance(x, z, hit.x(), hit.z()));
    }

    /**
     * Nearest point on a segment of a named chain, or null when none is in range. Walks the named
     * chains' segments because the index cannot answer with a predicate. Projection and tie-break are
     * the index's own: lower segment id, then vertex index.
     */
    private static Named nearestNamed(RoadNetwork network, RoadChains.Grouping grouping, int x, int z,
                                      double maxDistance) {
        double radiusSq = maxDistance * maxDistance;
        double bestDistanceSq = Double.MAX_VALUE;
        Named best = null;
        for (RoadSegment segment : network.segments()) {
            if (segment == null) {
                continue;
            }
            String name = cleanName(grouping.nameOf(segment));
            if (name.isEmpty()) {
                continue;
            }
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
                boolean better = best == null || d < bestDistanceSq
                        || (d == bestDistanceSq
                        && earlier(segment.id(), i, best.segment().id(), best.edgeIndex()));
                if (better) {
                    bestDistanceSq = d;
                    best = new Named(name, segment, i, px, pz);
                }
            }
        }
        return best;
    }

    /** Whether one (segment, vertex span) precedes another in the tie-break both passes use. */
    private static boolean earlier(int segmentId, int edgeIndex, int otherSegmentId,
                                   int otherEdgeIndex) {
        return segmentId != otherSegmentId
                ? segmentId < otherSegmentId
                : edgeIndex < otherEdgeIndex;
    }

    /**
     * Side of the nearest road point by dominant axis. On an axis the axis wins; on the diagonal or on
     * the road, Side.NONE. Minecraft axes: +X is east, +Z is south.
     */
    private static Side sideOf(int x, int z, double nearestX, double nearestZ) {
        double dx = x - nearestX;
        double dz = z - nearestZ;
        double ax = Math.abs(dx);
        double az = Math.abs(dz);
        if (ax <= ON_ROAD_EPSILON && az <= ON_ROAD_EPSILON) {
            return Side.NONE;
        }
        if (ax > az) {
            return dx > 0 ? Side.EAST : Side.WEST;
        }
        if (az > ax) {
            return dz > 0 ? Side.SOUTH : Side.NORTH;
        }
        return Side.NONE;
    }

    /** Whole blocks from the place to the road point, rounded to the nearest one. */
    private static int roundDistance(int x, int z, double roadX, double roadZ) {
        return (int) Math.round(Math.hypot(x - roadX, z - roadZ));
    }

    /**
     * The shape the radius means: a circle around the place. Stated separately because the radius
     * handed to the index is a search budget, not the acceptance test.
     */
    private static RoadSpatialIndex.Coverage circle(double radius) {
        double radiusSq = radius * radius;
        return (dx, dz) -> dx * dx + dz * dz <= radiusSq;
    }

    /** A chain name, or empty for null, blank or whitespace. */
    private static String cleanName(String name) {
        return name == null ? "" : name.strip();
    }

    /** One point on a named segment, as the second pass found it. */
    private record Named(String name, RoadSegment segment, int edgeIndex, double x, double z) {
    }
}
