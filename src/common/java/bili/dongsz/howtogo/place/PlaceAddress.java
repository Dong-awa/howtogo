package bili.dongsz.howtogo.place;

import bili.dongsz.howtogo.road.RoadChains;
import bili.dongsz.howtogo.road.RoadClass;
import bili.dongsz.howtogo.road.RoadNetwork;
import bili.dongsz.howtogo.road.RoadSegment;
import bili.dongsz.howtogo.road.RoadSpatialIndex;

/**
 * Where a place is, said the way a person says it: the road it is on, which side of it, and how far.
 *
 * <h2>Why not the coordinates</h2>
 * A coordinate is exact and unreadable. "人民路 东侧 12 格" tells a player which road to walk to and
 * which way to turn once there; "x 1234, z -567" tells them a number they have to compare against a
 * debug screen. The user asked for the former, and the mod already has the data for it: every place
 * sits in, beside or nowhere near the player's road network, and a road in this mod carries a name.
 * So the address is derived, never stored -- it changes when the roads change, and a stored copy
 * would be the one thing in the details file that could go stale without anybody editing it.
 *
 * <h2>What "nearest" means here</h2>
 * Three rules, in this order, because each one is a separate answer to the player's question:
 *
 * <ol>
 *   <li><b>The nearest road of any kind</b> is found first, through {@link RoadSpatialIndex}. When
 *       that road belongs to a named chain, the answer is that name: the chain the road is part of
 *       is the road, and a name on any of its segments names all of it -- a road drawn with bends is
 *       one road with one name.</li>
 *   <li><b>If the nearest road is unnamed, the nearest named road within the same radius wins.</b>
 *       A player standing beside a nameless driveway next to 人民路 should be told about 人民路, not
 *       about a road with no name they cannot give anyone. This second pass walks the segments the
 *       chain grouping says carry a name; it is only reached when the first answer was unusable, and
 *       it is bounded by the same radius, so it is a fallback rather than a second full search of
 *       the map on every question.</li>
 *   <li><b>If nothing named is in range</b> the answer keeps the nearest segment's class, side and
 *       distance with an empty name, so the interface can say "an unnamed road, 12 blocks east"
 *       rather than pretend there is no road at all. Only when no segment is within the radius at
 *       all is the answer {@link Address#NONE}.</li>
 * </ol>
 *
 * <h2>Why this is in the portable source root</h2>
 * Everything it does is geometry and string selection: no Minecraft class, no client state, no
 * drawing. That is deliberate, because the whole feature -- which road is nearest, which chain owns
 * it, which side the place is on -- is the part worth checking, and it can then be checked by the
 * routing harness on a machine with no game running. The radius and the caching belong to the
 * interface layer, which is the only side that knows how often it is asked and how big the number
 * should feel to a player.
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
        /**
         * Neither side: the place is on the road itself, or exactly on the diagonal between two
         * sides, where naming one would be a guess.
         */
        NONE
    }

    /**
     * One address: the road, its class, the side the place is on, and the distance in blocks.
     *
     * <p>Three states are possible, and the constructor is what makes them the only three: no road
     * at all ({@link #NONE}: no class, no name, no side, distance -1), a road with no name (class,
     * side and distance, empty name), and a named road (everything). A record whose class was
     * cleared but whose distance survived would read as "0 blocks from nothing", which is why a
     * null class clears the rest rather than being stored as-is.
     */
    public record Address(String roadName, RoadClass roadClass, Side side, int distanceBlocks) {

        /**
         * No road anywhere within the radius.
         *
         * <p>A constant rather than a null return, so callers -- and the checks -- have one thing to
         * compare against. The distance is {@code -1} because there is no distance to give: zero
         * would mean "standing on the road", which is the opposite of what this says.
         */
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

        /**
         * Whether there is any road within the radius, named or not.
         *
         * <p>The distinction the interface needs: {@link #onRoad()} says "I can name this road",
         * this says "there is a road here". A card that used only {@code onRoad()} would print "no
         * road nearby" beside a driveway it is standing on, which is exactly the sort of lie that
         * makes a player stop trusting the readout.
         */
        public boolean nearRoad() {
            return roadClass != null;
        }
    }

    /** Within this many blocks of the nearest road point the place counts as on the road. */
    private static final double ON_ROAD_EPSILON = 1.0E-6;

    /**
     * The address of the point {@code (x, z)} in this network, or {@link Address#NONE} when no road
     * is within {@code maxDistance} blocks of it.
     *
     * <p>{@code maxDistance} is in world blocks -- the same unit as the distance it reports -- and
     * is the only place the number comes from. The interface passes its own constant (64, about the
     * length of a city block); the checks pass small numbers so their geometry is easy to read.
     *
     * @param network     the roads to look at; null or empty answers {@link Address#NONE}
     * @param maxDistance how far a road may be and still be this place's address
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

        // Built after the cheap "nothing is near" answer, because a place far from every road has no
        // use for a grouping of the whole network -- and a grouping is the one expensive thing here.
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
        // Only an unnamed road in range: keep its class, side and distance so the interface can say
        // "an unnamed road" without having to invent a name for it.
        return fromHit("", nearest, x, z);
    }

    /** The address one hit on a road produces, with the name that road's chain carries. */
    private static Address fromHit(String name, RoadSpatialIndex.SegmentHit hit, int x, int z) {
        return new Address(name, hit.segment().roadClass(), sideOf(x, z, hit.x(), hit.z()),
                roundDistance(x, z, hit.x(), hit.z()));
    }

    /**
     * The nearest point on a segment belonging to a named chain, or null when none is in range.
     *
     * <p>This is the second pass described in the class comment, and it does not go through
     * {@link RoadSpatialIndex} because the index can only answer "the nearest segment", not "the
     * nearest segment satisfying a predicate" -- it offers no way to exclude the unnamed road that
     * just answered. Walking the named chains' segments is the honest way to state that rule, and it
     * reads only the segments whose chain has a name, so on a normal map it touches the named roads
     * rather than the whole network.
     *
     * <p>Distance, projection and tie-break are the index's own: a vertex-to-vertex piece, the
     * projection clamped to it, a zero-length piece measured as the point it is, and at an exact tie
     * the lower (segment id, vertex index) wins. The tie-break matters for the checks, which need the
     * same answer twice in a row, and for a player rotating around a corner between two equally near
     * roads, who should not see the address flicker between them.
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

    /** Whether one (segment, vertex span) comes before another in the tie-break both passes use. */
    private static boolean earlier(int segmentId, int edgeIndex, int otherSegmentId,
                                   int otherEdgeIndex) {
        return segmentId != otherSegmentId
                ? segmentId < otherSegmentId
                : edgeIndex < otherEdgeIndex;
    }

    /**
     * Which side of the nearest road point the place is on, by the dominant axis.
     *
     * <p>Dominant axis rather than a precise half-plane test: the address is read in a card, and
     * "north-east" is a fourth word in a line that already has three. A place exactly on one axis
     * gets that axis; a place exactly on the diagonal, or exactly on the road, gets
     * {@link Side#NONE} -- a guess between north and east is worse than no answer.
     *
     * <p>Minecraft's axes are the world's: +X is east, +Z is south, which is why the comparisons
     * below read the way they do.
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

    /** How many whole blocks from the place to the road point, rounded to the nearest one. */
    private static int roundDistance(int x, int z, double roadX, double roadZ) {
        return (int) Math.round(Math.hypot(x - roadX, z - roadZ));
    }

    /**
     * The shape the radius means: a circle around the place.
     *
     * <p>Stated here rather than left to the index's own {@code radius} because the two are
     * different statements -- the number handed to the index is a search budget, this is the test
     * the answer has to pass. For a circular budget the two coincide and this never rejects, which
     * is the right answer; keeping it explicit is what makes "how far is 12 blocks" one definition
     * rather than something each caller assumes.
     */
    private static RoadSpatialIndex.Coverage circle(double radius) {
        double radiusSq = radius * radius;
        return (dx, dz) -> dx * dx + dz * dz <= radiusSq;
    }

    /** A chain name, or the empty string for "no name" -- null, blank and whitespace all mean that. */
    private static String cleanName(String name) {
        return name == null ? "" : name.strip();
    }

    /** One point on a named segment, as the second pass found it. */
    private record Named(String name, RoadSegment segment, int edgeIndex, double x, double z) {
    }
}
