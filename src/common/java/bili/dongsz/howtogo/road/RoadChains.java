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
 * A road drawn with bends is stored as several segments meeting at pass-through nodes; treating each as its own road would name only one straight piece and repeat the label at every corner. A node joins two segments only when it has exactly two connections and both sides have the same road class -- a highway flowing into a footpath is a change of road.
 * An adjacency index built in one pass drives the walk, so a chain walk and whole-network grouping are linear; scanning per step was quadratic and allocated lists at every step, on the client thread behind a button press.
 */
public final class RoadChains {

    /** Guard against a malformed network producing an unbounded walk. */
    private static final int MAX_CHAIN = 4096;

    private RoadChains() {
    }

    /**
     * All segments joined to the seed through pass-through nodes, ordered end to end. Walked in both directions and then stitched, because consecutive segments are not guaranteed to be stored pointing the same way.
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
     * chainContaining per segment is the obvious way to label a route and walks every road once per segment of it; this walks each road once and hands back what labelling needs -- the road key, name, junction degrees, member segments, and which one carries the label.
     * The last two are here because all three map views need them every frame; chainContaining built a whole adjacency index per named road, costing network size times names per frame.
     */
    public static Grouping group(RoadNetwork network) {
        Index index = new Index(network);
        Map<Integer, Integer> keys = new HashMap<>();
        Map<Integer, String> names = new HashMap<>();
        Map<Integer, int[]> chains = new HashMap<>();
        Set<Integer> carriers = new HashSet<>();
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
            chains.put(key, members(chain, segment));
            // One label per road, at the middle of its chain.
            int middle = middleSegment(chain);
            if (middle != RoadSegment.NO_SEGMENT) {
                carriers.add(middle);
            }
        }
        return new Grouping(Collections.unmodifiableMap(keys),
                Collections.unmodifiableMap(names), index.degrees(),
                Collections.unmodifiableMap(chains), Collections.unmodifiableSet(carriers));
    }

    /** A chain as a plain array: the shape the per-frame callers can walk without allocating. */
    private static int[] members(List<Integer> chain, RoadSegment seed) {
        if (chain.isEmpty()) {
            return new int[] {seed.id()};
        }
        int[] members = new int[chain.size()];
        for (int i = 0; i < members.length; i++) {
            members[i] = chain.get(i);
        }
        return members;
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

    /** Segments reachable from startNode, walking away from from, in the order they are met. */
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

    /**
     * How many segment endpoints each node carries. Cached against the network revision: the navigation panel's wrong-way call asks once per tick exactly when it must stay responsive, and building it fresh was two hash maps and a list per node twenty times a second.
     */
    public static Map<Integer, Integer> degrees(RoadNetwork network) {
        return indexFor(network).degrees();
    }

    /**
     * The segments meeting a node, which is what a walk asks at every step. Out of the same cached reading as degrees, since the walk asks both once per step.
     */
    public static List<Integer> segmentsAt(RoadNetwork network, int nodeId) {
        return indexFor(network).segmentsAt(nodeId);
    }

    /** The reading of the network both queries above come out of, kept while it is current. */
    private static Index indexFor(RoadNetwork network) {
        if (cachedIndex == null || cachedIndexNetwork != network
                || cachedIndexRevision != network.revision()) {
            cachedIndex = new Index(network);
            cachedIndexNetwork = network;
            cachedIndexRevision = network.revision();
        }
        return cachedIndex;
    }

    private static RoadNetwork cachedIndexNetwork;
    private static int cachedIndexRevision = Integer.MIN_VALUE;
    private static Index cachedIndex;

    /**
     * The segment at the middle of a chain, used to place one label per road rather than one per straight piece.
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
     * The network read once into the two shapes the chain walk asks about: how many segment ends a node carries, and which segments touch it. Built from one snapshot, so it neither allocates per step nor sees the network change under it.
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
         * The segments meeting a node, in the order the network keeps them, or none. The order is the network's own, so an answer here is the same a full scan would give; a caller that walks a road may rely on that.
         */
        List<Integer> segmentsAt(int nodeId) {
            List<Integer> found = byNode.get(nodeId);
            return found == null ? List.of() : found;
        }

        /**
         * The single other segment continuing through a pass-through node, or -1 for a junction, an endpoint, or a change of road class. Answers in the same order the old full scan did, so the chain a segment is placed in is unchanged.
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
     * One road per segment, as the things a route and a map both need to know about it. A plain value computed once per network revision, so labelling a route or drawing a map is a map lookup rather than a walk.
     */
    public static final class Grouping {

        private final Map<Integer, Integer> keys;
        private final Map<Integer, String> names;
        private final Map<Integer, Integer> degrees;
        private final Map<Integer, int[]> chains;
        private final Set<Integer> carriers;

        private Grouping(Map<Integer, Integer> keys, Map<Integer, String> names,
                         Map<Integer, Integer> degrees, Map<Integer, int[]> chains,
                         Set<Integer> carriers) {
            this.keys = keys;
            this.names = names;
            this.degrees = degrees;
            this.chains = chains;
            this.carriers = carriers;
        }

        /**
         * Identifies which road a segment belongs to: the first segment of the chain, the same value for every segment because the walk always runs end to end. Manoeuvres are announced where this changes.
         */
        public int keyOf(RoadSegment segment) {
            return keys.getOrDefault(segment.id(), segment.id());
        }

        /** Name of the road a segment belongs to, or null when it has none. */
        public String nameOf(RoadSegment segment) {
            return names.get(segment.id());
        }

        /**
         * Every segment of the road this one belongs to, in chain order.
         * Handed out as an array rather than a list so a caller drawing a map can measure the road without allocating or walking its chain again, which is what group() exists to make unnecessary. The array belongs to the grouping: do not modify it or keep it across a rebuild.
         */
        public int[] chainOf(RoadSegment segment) {
            int[] members = chains.get(keys.getOrDefault(segment.id(), segment.id()));
            // Only reachable for a segment added since the grouping was built; answering with the segment alone is what the walk would have said.
            return members == null ? new int[] {segment.id()} : members;
        }

        /**
         * Whether this segment is the one that draws its road's label: the middle of the chain, so a bent road is named once. A property of the geometry, so a rename or a re-class does not move it.
         */
        public boolean carriesLabel(RoadSegment segment) {
            return carriers.contains(segment.id());
        }

        /**
         * Whether the vertex at this index is a junction the road forks at. Only segment ends are nodes at all, and a node is a junction only when three or more segment ends meet there.
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

    /**
     * How many networks cachedGrouping keeps a reading of.
     * A single world-map frame labels three layers -- the player's roads, Create's rails and MTR's rails -- and one slot would rebuild a reading of the layer it is not holding every pass, which on a large rail layer is the cost this cache exists to avoid. Four is every layer a frame can ask about, with room to spare.
     */
    private static final int CACHE_SLOTS = 4;

    private static final RoadNetwork[] cachedNetworks = new RoadNetwork[CACHE_SLOTS];
    private static final int[] cachedRevisions = new int[CACHE_SLOTS];
    private static final Grouping[] cachedGroupings = new Grouping[CACHE_SLOTS];
    private static int nextSlot;

    /**
     * The grouping of a network, rebuilt only when the network it describes has changed.
     * The three maps ask the same questions every frame -- which road does this segment belong to, does it carry the name, how long is it. Walking a chain per segment is quadratic and built a fresh index of the whole network each time: megabytes of garbage per frame, and on larger networks the frame budget. The reading is a property of the geometry, so it is kept until the geometry changes.
     * Keyed on revision, not reference: the editor mutates one network in place, and RoadNetwork revision() ignores changes that cannot move anything (a rename, a re-class, a one-way flag), which is exactly what this reading does not depend on.
     * Rotation rather than an LRU: the callers are the long-lived networks a session has, a slot is lost only when a fourth network is asked about, and a hit never needs moving -- four reference comparisons on the hit path instead of three array copies.
     * Render thread, where every caller draws. Not synchronized on purpose: a lock on a per-frame path to guard a value recomputed once per edit is the wrong trade.
     */
    public static Grouping cachedGrouping(RoadNetwork network) {
        for (int i = 0; i < CACHE_SLOTS; i++) {
            if (cachedNetworks[i] == network && cachedRevisions[i] == network.revision()) {
                return cachedGroupings[i];
            }
        }
        Grouping built = group(network);
        int slot = nextSlot;
        nextSlot = (nextSlot + 1) % CACHE_SLOTS;
        cachedNetworks[slot] = network;
        cachedRevisions[slot] = network.revision();
        cachedGroupings[slot] = built;
        return built;
    }
}
