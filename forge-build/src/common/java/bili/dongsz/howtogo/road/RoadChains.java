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
     * the labelling needs: the key a whole road is identified by, the name it carries, the junction
     * degrees that say where it forks, the segments each road is made of, and which one of them
     * carries the road's label.
     *
     * <p>The last two are here rather than in the three views that draw a map -- the world map, the
     * navigation panel and the picker -- because all three need them every frame, and every one of
     * them used to ask {@link #chainContaining} once per named road to get them. That call builds a
     * whole adjacency index before it answers, so a map pass cost the size of the network times the
     * number of names on it per frame, and about a megabyte of short-lived garbage per frame on a
     * network of a few hundred segments. Asking once per network revision instead is the whole point
     * of this shape.
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
            // One label per road, at the middle of its chain. The same rule the label pass used to
            // work out for itself, by walking the chain of every named segment it was about to draw.
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

    /**
     * How many segment endpoints each node carries.
     *
     * <p>Cached against the network's revision, because of who asks: the navigation panel's wrong-way
     * call walks from the road underfoot to the first junction that can be turned round at, and it
     * asks this once per tick while the player is travelling the wrong way -- which is exactly when the
     * panel must stay responsive. Built fresh each time, that was two hash maps and a list per node,
     * twenty times a second, for an answer that only changes when the roads do.
     */
    public static Map<Integer, Integer> degrees(RoadNetwork network) {
        return indexFor(network).degrees();
    }

    /**
     * The segments meeting a node, which is what a walk along a road asks at every step it takes.
     *
     * <p>Out of the same cached reading as {@link #degrees}: the walk asks both, once per step, and
     * building the reading twice would be building it for nothing. Answering "which other segment
     * continues here" from this is a handful of lookups, where scanning every segment of the network
     * per step was the same answer at a cost that grows with the map.
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
         * The segments meeting a node, in the order the network keeps them, or none.
         *
         * <p>The order is the network's own, which is what makes an answer taken from here the same
         * answer a scan of every segment would have given: the first stored against the node that is
         * not the one arrived on. A caller that walks a road may rely on that.
         */
        List<Integer> segmentsAt(int nodeId) {
            List<Integer> found = byNode.get(nodeId);
            return found == null ? List.of() : found;
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
     * One road per segment, as the things a route and a map both need to know about it.
     *
     * <p>A plain value computed once per network revision, so labelling a route -- or drawing a map
     * -- is a map lookup rather than a walk of the road for every piece of it.
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
         * Every segment of the road this one belongs to, in the order the chain walk gives them.
         *
         * <p>Handed out as an array rather than a list so a caller drawing a map can measure the road
         * without allocating anything, and without walking its chain again -- which is what
         * {@link #group(RoadNetwork)} exists to make unnecessary.
         *
         * <p>The array belongs to the grouping and must not be modified or kept across a rebuild of
         * the network; both are why this is only ever read during the pass that asked for it.
         */
        public int[] chainOf(RoadSegment segment) {
            int[] members = chains.get(keys.getOrDefault(segment.id(), segment.id()));
            // Only reachable for a segment added since the grouping was built. Answering with the
            // segment on its own is what the walk would have said about a road that is one piece.
            return members == null ? new int[] {segment.id()} : members;
        }

        /**
         * Whether this segment is the one that draws its road's label.
         *
         * <p>The middle of the chain, so a road drawn with bends is named once rather than at every
         * corner. A property of the geometry, so a rename or a re-class does not move it.
         */
        public boolean carriesLabel(RoadSegment segment) {
            return carriers.contains(segment.id());
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

    /**
     * How many networks {@link #cachedGrouping} keeps a reading of.
     *
     * <p>More than one because a single frame of the world map labels three layers: the player's own
     * roads, Create's rails and MTR's rails. One slot would rebuild a reading of the layer it is not
     * currently holding on every pass, which on a large rail layer is the cost this cache exists to
     * avoid. Four is every layer a frame can ask about at once, with room to spare.
     */
    private static final int CACHE_SLOTS = 4;

    private static final RoadNetwork[] cachedNetworks = new RoadNetwork[CACHE_SLOTS];
    private static final int[] cachedRevisions = new int[CACHE_SLOTS];
    private static final Grouping[] cachedGroupings = new Grouping[CACHE_SLOTS];
    private static int nextSlot;

    /**
     * The grouping of a network, rebuilt only when the network it describes has changed.
     *
     * <h2>What this is for</h2>
     * The three maps this mod draws ask the same questions of the same networks every frame -- which
     * road does this segment belong to, is it the piece that carries the name, how long is that road.
     * Answering them by walking a chain per segment is quadratic in the size of the network and, worse,
     * every one of those walks built a fresh adjacency index of the whole network: on a network of a
     * few hundred segments that was megabytes of garbage per frame, and on a larger one it was the
     * frame budget itself. The reading is a property of the geometry, so it is kept until the geometry
     * changes.
     *
     * <h2>Why the revision and not just the reference</h2>
     * The editor mutates one network in place for a whole session, so the same object is a different
     * network after an edit. {@link RoadNetwork#revision()} is what says so, and it is deliberately
     * left alone by the changes that cannot move anything -- a rename, a re-class, a one-way flag --
     * which is exactly the set of changes this reading does not depend on.
     *
     * <p>Held on to until displaced, which is why the callers are the long-lived networks a session
     * has: the player's roads and the two rail layers. A per-plan copy of a network is not asked for
     * here and would only cost a rebuild.
     *
     * <h2>Threads</h2>
     * The render thread, where every caller draws. Not synchronized on purpose: taking a lock on a
     * per-frame path to guard a value recomputed once per edit is the wrong trade.
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
