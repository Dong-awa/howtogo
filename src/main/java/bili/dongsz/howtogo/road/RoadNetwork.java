package bili.dongsz.howtogo.road;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The road graph of a single dimension.
 *
 * <p>Nodes and segments are stored separately and reference each other by id, which keeps
 * topology edits (splitting, merging, snapping) cheap.
 */
public final class RoadNetwork {

    private final Map<Integer, RoadNode> nodes = new HashMap<>();
    private final Map<Integer, RoadSegment> segments = new HashMap<>();
    private int nextNodeId = 1;
    private int nextSegmentId = 1;

    /**
     * Live view of the nodes. <b>Do not add or remove while iterating</b> -- this is the backing
     * map's value view and structural changes during iteration throw
     * {@link java.util.ConcurrentModificationException}. Take {@link #nodesSnapshot()} first if the
     * loop needs to mutate.
     */
    public Collection<RoadNode> nodes() {
        return nodes.values();
    }

    /**
     * Live view of the segments. <b>Do not add or remove while iterating</b> -- see
     * {@link #nodes()} for why, and use {@link #segmentsSnapshot()} when mutation is needed.
     */
    public Collection<RoadSegment> segments() {
        return segments.values();
    }

    /** Copy of the nodes, safe to iterate while modifying the network. */
    public List<RoadNode> nodesSnapshot() {
        return new ArrayList<>(nodes.values());
    }

    /** Copy of the segments, safe to iterate while modifying the network. */
    public List<RoadSegment> segmentsSnapshot() {
        return new ArrayList<>(segments.values());
    }

    public RoadNode node(int id) {
        return nodes.get(id);
    }

    public RoadSegment segment(int id) {
        return segments.get(id);
    }

    public RoadNode addNode(int x, int y, int z, RoadNode.Type type, String name) {
        RoadNode node = new RoadNode(nextNodeId++, x, y, z, type, name);
        nodes.put(node.id(), node);
        return node;
    }

    public RoadSegment addSegment(RoadSegment segment) {
        segments.put(segment.id(), segment);
        return segment;
    }

    public RoadSegment newSegment(RoadClass roadClass, int y, int capacity) {
        return new RoadSegment(nextSegmentId++, roadClass, y, capacity);
    }

    public boolean removeNode(int id) {
        return nodes.remove(id) != null;
    }

    public boolean removeSegment(int id) {
        return segments.remove(id) != null;
    }

    /** Finds the node nearest to {@code (x, z)} within {@code maxDistance} blocks, or null. */
    public RoadNode nearestNode(double x, double z, double maxDistance) {
        double bestSq = maxDistance * maxDistance;
        RoadNode best = null;
        for (RoadNode node : nodes.values()) {
            double d = node.distSq(x, z);
            if (d <= bestSq) {
                bestSq = d;
                best = node;
            }
        }
        return best;
    }

    public int nodeCount() {
        return nodes.size();
    }

    public int segmentCount() {
        return segments.size();
    }

    /** Inserts a pre-built node, keeping the id counter ahead of it. */
    public void putNode(RoadNode node) {
        nodes.put(node.id(), node);
        nextNodeId = Math.max(nextNodeId, node.id() + 1);
    }

    /** Inserts a pre-built segment, keeping the id counter ahead of it. */
    public void putSegment(RoadSegment segment) {
        segments.put(segment.id(), segment);
        nextSegmentId = Math.max(nextSegmentId, segment.id() + 1);
    }

    /** Deep copy. The editor keeps these on an undo stack, so it must not share state. */
    public RoadNetwork deepCopy() {
        RoadNetwork c = new RoadNetwork();
        c.nextNodeId = nextNodeId;
        c.nextSegmentId = nextSegmentId;
        for (RoadNode n : nodes.values()) {
            c.nodes.put(n.id(), n.copy());
        }
        for (RoadSegment s : segments.values()) {
            c.segments.put(s.id(), s.copy());
        }
        return c;
    }

    /** Replaces this network's contents with a deep copy of {@code other}. */
    public void replaceWith(RoadNetwork other) {
        nodes.clear();
        segments.clear();
        nextNodeId = other.nextNodeId;
        nextSegmentId = other.nextSegmentId;
        for (RoadNode n : other.nodes.values()) {
            nodes.put(n.id(), n.copy());
        }
        for (RoadSegment s : other.segments.values()) {
            segments.put(s.id(), s.copy());
        }
    }

    /**
     * Builds a small test pattern so P0 can be verified visually without any editing UI.
     * Centred on the given world position.
     */
    public static RoadNetwork demo(int centerX, int centerZ) {
        RoadNetwork net = new RoadNetwork();

        // A square "ring road" plus a diagonal cross, all centred on the player.
        int r = 120;
        RoadNode nw = net.addNode(centerX - r, 64, centerZ - r, RoadNode.Type.JUNCTION, "NW");
        RoadNode ne = net.addNode(centerX + r, 64, centerZ - r, RoadNode.Type.JUNCTION, "NE");
        RoadNode se = net.addNode(centerX + r, 64, centerZ + r, RoadNode.Type.JUNCTION, "SE");
        RoadNode sw = net.addNode(centerX - r, 64, centerZ + r, RoadNode.Type.JUNCTION, "SW");
        RoadNode center = net.addNode(centerX, 64, centerZ, RoadNode.Type.POI, "Centre");

        addLink(net, nw, ne, RoadClass.ROAD);
        addLink(net, ne, se, RoadClass.ROAD);
        addLink(net, se, sw, RoadClass.ROAD);
        addLink(net, sw, nw, RoadClass.ROAD);
        addLink(net, nw, se, RoadClass.PATH);
        addLink(net, ne, sw, RoadClass.PATH);

        // A straight highway running exactly EAST so its length can be measured against Xaero's
        // coordinate readout: it ends precisely 1000 blocks east of the centre.
        RoadSegment highway = RoadSegment.of(net.nextSegmentId++, RoadClass.HIGHWAY, 64,
                centerX, centerZ,
                centerX + 250, centerZ,
                centerX + 500, centerZ,
                centerX + 750, centerZ,
                centerX + 1000, centerZ);
        highway.setFromNode(center.id());
        net.addSegment(highway);

        // Reference marker exactly at the centre so misalignment is obvious.
        RoadSegment mark = RoadSegment.of(net.nextSegmentId++, RoadClass.ICE, 64,
                centerX - 8, centerZ, centerX + 8, centerZ);
        mark.setFromNode(center.id());
        net.addSegment(mark);

        return net;
    }

    private static void addLink(RoadNetwork net, RoadNode a, RoadNode b, RoadClass cls) {
        RoadSegment seg = RoadSegment.of(net.nextSegmentId++, cls, a.y(),
                a.x(), a.z(), b.x(), b.z());
        seg.setFromNode(a.id());
        seg.setToNode(b.id());
        net.addSegment(seg);
    }
}
