package bili.dongsz.howtogo.road;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Groups segments into whole roads.
 *
 * <p>A road drawn with bends is stored as several segments meeting at pass-through nodes. Those
 * nodes are not junctions -- nothing branches there -- so treating each segment as its own road
 * would mean a name covering only one straight piece of it, and a label repeated at every corner.
 *
 * <p>A node only joins two segments if it has exactly two connections <em>and</em> both sides have
 * the same road class. A highway flowing into a footpath is a change of road, not one road with a
 * kink in it.
 *
 * <h2>The index</h2>
 * Finding the segment that continues through a node used to mean scanning every segment of the
 * network, and copying them all into a fresh list at every step of the walk. A route build asks the
 * same question once per segment of the route, so a hundred-segment route over a five-thousand
 * segment network cost hundreds of thousands of comparisons and a hundred list allocations of five
 * thousand entries -- and this ran on the client thread behind a button press. The walk is now
 * driven by an adjacency index built in one pass, which makes both a single chain walk and
 * {@link #group(RoadNetwork) the whole-network grouping} linear in the network.
 */
public final class RoadChains {

    /** Guard against a malformed network producing an unbounded walk. */
    private static final int MAX_CHAIN = 4096;

    private RoadChains() {
    }

    /**
     * All segments joined to the seed through pass-through nodes, ordered end to end.
     *
     * <p>The walk is done in both directions from the seed and then stitched, because nothing
     * guarantees that consecutive segments were stored pointing the same way.
     */
    public static List<Integer> chainContaining(RoadNetwork network, int seedSegmentId) {
        RoadSegment seed = network.segment(seedSegmentId);
        if (seed == null) {
            return List.of();
        }
        return chainContaining(network, new Index(network), seed);
    }

    /**
     * Every segment's road, in one pass over the network.
     *
     * <p>Asking for the chain of each segment in turn is the obvious way to label a route, and it
     * walks every road once per segment of it. This walks each road exactly once and hands back what
     * the labelling needs: the key a whole road is identified by, the name it carries, and the
     * junction degrees that say where it forks.
     */
    public static Grouping group(RoadNetwork network) {
        Index index = new Index(network);
        Map<Integer, Integer> keys = new HashMap<>();
        Map<Integer, String> names = new HashMap<>();
        for (RoadSegment segment : network.segmentsSnapshot()) {
            if (keys.containsKey(segment.id())) {
                continue;
            }
            List<Integer> chain = chainContaining(network, index, segment);
            int key = chain.isEmpty() ? segment.id() : chain.get(0);
            String name = null;
            for (int id : chain) {
                RoadSegment part = network.segment(id);
                if (part != null && part.name() != null) {
                    name = part.name();
                    break;
                }
            }
            for (int id : chain) {
                keys.put(id, key);
                names.put(id, name);
            }
        }
        return new Grouping(Collections.unmodifiableMap(keys),
                Collections.unmodifiableMap(names), index.degrees());
    }

    private static List<Integer> chainContaining(RoadNetwork network, Index index, RoadSegment seed) {
        RoadClass roadClass = seed.roadClass();

        List<Integer> forward = walk(network, index, seed, seed.toNode(), roadClass);
        List<Integer> backward = walk(network, index, seed, seed.fromNode(), roadClass);

        List<Integer> chain = new ArrayList<>(forward.size() + backward.size() + 1);
        for (int i = backward.size() - 1; i >= 0; i--) {
            chain.add(backward.get(i));
        }
        chain.add(seed.id());
        chain.addAll(forward);
        return chain;
    }

    /**
     * Segments reachable from {@code startNode}, walking away from {@code from}.
     *
     * @return the segments in the order they are met
     */
    private static List<Integer> walk(RoadNetwork network, Index index, RoadSegment from, int startNode,
                                      RoadClass roadClass) {
        List<Integer> result = new ArrayList<>();
        Set<Integer> visited = new HashSet<>();
        visited.add(from.id());

        int nodeId = startNode;
        int previousSegment = from.id();
        for (int guard = 0; guard < MAX_CHAIN; guard++) {
            int nextId = index.passThroughNeighbour(nodeId, previousSegment, roadClass);
            if (nextId < 0 || !visited.add(nextId)) {
                break;
            }
            RoadSegment next = network.segment(nextId);
            if (next == null) {
                break;
            }
            result.add(nextId);
            nodeId = otherEnd(next, nodeId);
            previousSegment = nextId;
        }
        return result;
    }

    private static int otherEnd(RoadSegment segment, int nodeId) {
        return segment.fromNode() == nodeId ? segment.toNode() : segment.fromNode();
    }

    /** How many segment endpoints each node carries. */
    public static Map<Integer, Integer> degrees(RoadNetwork network) {
        return new Index(network).degrees();
    }

    /**
     * The segment at the middle of a chain, used to place one label per road rather than one per
     * straight piece.
     */
    public static int middleSegment(List<Integer> chain) {
        if (chain.isEmpty()) {
            return RoadSegment.NO_SEGMENT;
        }
        return chain.get(chain.size() / 2);
    }

    /** Every segment of a chain, as an unmodifiable set. */
    public static Set<Integer> asSet(List<Integer> chain) {
        return Collections.unmodifiableSet(new java.util.LinkedHashSet<>(chain));
    }

    /**
     * The network read once into the two shapes the chain walk asks questions of: how many segment
     * ends a node carries, and which segments touch it.
     *
     * <p>Built from one snapshot, so a walk through it neither allocates a list per step nor sees the
     * network change under it.
     */
    private static final class Index {

        private final RoadNetwork network;
        private final Map<Integer, Integer> degrees = new HashMap<>();
        private final Map<Integer, List<Integer>> byNode = new HashMap<>();

        Index(RoadNetwork network) {
            this.network = network;
            for (RoadSegment segment : network.segmentsSnapshot()) {
                attach(segment.fromNode(), segment.id());
                attach(segment.toNode(), segment.id());
            }
        }

        private void attach(int nodeId, int segmentId) {
            if (nodeId == RoadSegment.NO_NODE) {
                return;
            }
            degrees.merge(nodeId, 1, Integer::sum);
            byNode.computeIfAbsent(nodeId, k -> new ArrayList<>()).add(segmentId);
        }

        Map<Integer, Integer> degrees() {
            return Collections.unmodifiableMap(degrees);
        }

        /**
         * The single other segment continuing through a pass-through node, or -1 when the node is a
         * junction, an endpoint, or a change of road class.
         *
         * <p>Answers in the same order the old full scan did -- the first segment stored against the
         * node that is not the one arrived on -- so the chain a segment is placed in is unchanged.
         */
        int passThroughNeighbour(int nodeId, int excludeSegmentId, RoadClass roadClass) {
            if (nodeId == RoadSegment.NO_NODE || degrees.getOrDefault(nodeId, 0) != 2) {
                return -1;
            }
            for (int id : byNode.getOrDefault(nodeId, List.of())) {
                if (id == excludeSegmentId) {
                    continue;
                }
                RoadSegment segment = network.segment(id);
                if (segment == null) {
                    continue;
                }
                return segment.roadClass() == roadClass ? id : -1;
            }
            return -1;
        }
    }

    /**
     * One road per segment, as the three things the route needs to know about it.
     *
     * <p>A plain value computed once per network revision, so labelling a route is a map lookup
     * rather than a walk of the road for every piece of it.
     */
    public static final class Grouping {

        private final Map<Integer, Integer> keys;
        private final Map<Integer, String> names;
        private final Map<Integer, Integer> degrees;

        private Grouping(Map<Integer, Integer> keys, Map<Integer, String> names,
                         Map<Integer, Integer> degrees) {
            this.keys = keys;
            this.names = names;
            this.degrees = degrees;
        }

        /**
         * Identifies which road a segment belongs to.
         *
         * <p>The first segment of the chain, which is the same value for every segment of that road
         * because the chain walk always runs end to end. Manoeuvres are announced where this changes.
         */
        public int keyOf(RoadSegment segment) {
            return keys.getOrDefault(segment.id(), segment.id());
        }

        /** Name of the road a segment belongs to, or null when it has none. */
        public String nameOf(RoadSegment segment) {
            return names.get(segment.id());
        }

        /**
         * Whether the vertex at this index is a junction the road forks at.
         *
         * <p>Only the two ends of a segment are nodes at all -- everything between is the polyline of
         * one piece of road -- and a node counts as a junction only when three or more segment ends
         * meet there. Two is a road carrying on, and one is a road stopping.
         */
        public boolean isBranch(RoadSegment segment, int vertexIndex) {
            int nodeId;
            if (vertexIndex == 0) {
                nodeId = segment.fromNode();
            } else if (vertexIndex == segment.vertexCount() - 1) {
                nodeId = segment.toNode();
            } else {
                return false;
            }
            return nodeId != RoadSegment.NO_NODE && degrees.getOrDefault(nodeId, 0) >= 3;
        }
    }
}
