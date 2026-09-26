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
        Map<Integer, Integer> degrees = degrees(network);
        RoadClass roadClass = seed.roadClass();

        List<Integer> forward = walk(network, degrees, seed, seed.toNode(), roadClass);
        List<Integer> backward = walk(network, degrees, seed, seed.fromNode(), roadClass);

        List<Integer> chain = new ArrayList<>(forward.size() + backward.size() + 1);
        for (int i = backward.size() - 1; i >= 0; i--) {
            chain.add(backward.get(i));
        }
        chain.add(seedSegmentId);
        chain.addAll(forward);
        return chain;
    }

    /**
     * Segments reachable from {@code startNode}, walking away from {@code from}.
     *
     * @return the segments in the order they are met
     */
    private static List<Integer> walk(RoadNetwork network, Map<Integer, Integer> degrees,
                                      RoadSegment from, int startNode, RoadClass roadClass) {
        List<Integer> result = new ArrayList<>();
        Set<Integer> visited = new HashSet<>();
        visited.add(from.id());

        int nodeId = startNode;
        int previousSegment = from.id();
        for (int guard = 0; guard < MAX_CHAIN; guard++) {
            int nextId = passThroughNeighbour(network, degrees, nodeId, previousSegment, roadClass);
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

    /**
     * The single other segment continuing through a pass-through node, or -1 when the node is a
     * junction, an endpoint, or a change of road class.
     */
    private static int passThroughNeighbour(RoadNetwork network, Map<Integer, Integer> degrees,
                                            int nodeId, int excludeSegmentId, RoadClass roadClass) {
        if (nodeId == RoadSegment.NO_NODE || degrees.getOrDefault(nodeId, 0) != 2) {
            return -1;
        }
        for (RoadSegment segment : network.segmentsSnapshot()) {
            if (segment.id() == excludeSegmentId) {
                continue;
            }
            if (segment.fromNode() == nodeId || segment.toNode() == nodeId) {
                return segment.roadClass() == roadClass ? segment.id() : -1;
            }
        }
        return -1;
    }

    private static int otherEnd(RoadSegment segment, int nodeId) {
        return segment.fromNode() == nodeId ? segment.toNode() : segment.fromNode();
    }

    /** How many segment endpoints each node carries. */
    public static Map<Integer, Integer> degrees(RoadNetwork network) {
        Map<Integer, Integer> degrees = new HashMap<>();
        for (RoadSegment segment : network.segmentsSnapshot()) {
            if (segment.fromNode() != RoadSegment.NO_NODE) {
                degrees.merge(segment.fromNode(), 1, Integer::sum);
            }
            if (segment.toNode() != RoadSegment.NO_NODE) {
                degrees.merge(segment.toNode(), 1, Integer::sum);
            }
        }
        return degrees;
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
}
