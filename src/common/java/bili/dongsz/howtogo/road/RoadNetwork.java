package bili.dongsz.howtogo.road;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The road graph of a single dimension. Nodes and segments are stored separately and reference each other by id, which keeps topology edits cheap.
 */
public final class RoadNetwork {

    private final Map<Integer, RoadNode> nodes = new HashMap<>();
    private final Map<Integer, RoadSegment> segments = new HashMap<>();
    private int nextNodeId = 1;
    private int nextSegmentId = 1;

    /**
     * Bumped whenever a node or segment is added, removed or moved, so a geometry cache can tell the same network object from the same network, unchanged.
     * Not bumped for changes that cannot alter a reading: a rename, or a one-way flag (the segment keeps its own counter). A re-class does bump it -- which pieces count as one road is a grouping cached against this counter.
     */
    private int revision;

    public int revision() {
        return revision;
    }

    /**
     * Marks the geometry as changed, for a mutation made through an object this network handed out. Vertex moves go through the segment (router splits, editor drags), so the network cannot see them.
     */
    public void touch() {
        revision++;
    }

    /**
     * Live view of the nodes. Do not add or remove while iterating -- structural changes throw ConcurrentModificationException; use nodesSnapshot() when the loop mutates.
     */
    public Collection<RoadNode> nodes() {
        return nodes.values();
    }

    /** Live view of the segments. Same iteration rule as nodes(); use segmentsSnapshot() when mutating. */
    public Collection<RoadSegment> segments() {
        return segments.values();
    }

    public List<RoadNode> nodesSnapshot() {
        return new ArrayList<>(nodes.values());
    }

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
        revision++;
        return node;
    }

    public RoadSegment addSegment(RoadSegment segment) {
        segments.put(segment.id(), segment);
        revision++;
        return segment;
    }

    public RoadSegment newSegment(RoadClass roadClass, int y, int capacity) {
        return new RoadSegment(nextSegmentId++, roadClass, y, capacity);
    }

    public boolean removeNode(int id) {
        boolean removed = nodes.remove(id) != null;
        if (removed) {
            revision++;
        }
        return removed;
    }

    public boolean removeSegment(int id) {
        boolean removed = segments.remove(id) != null;
        if (removed) {
            revision++;
        }
        return removed;
    }

    /** Node nearest to (x, z) within maxDistance blocks, or null. */
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

    public void putNode(RoadNode node) {
        nodes.put(node.id(), node);
        nextNodeId = Math.max(nextNodeId, node.id() + 1);
        revision++;
    }

    public void putSegment(RoadSegment segment) {
        segments.put(segment.id(), segment);
        nextSegmentId = Math.max(nextSegmentId, segment.id() + 1);
        revision++;
    }

    /** Deep copy. The editor keeps these on an undo stack, so no state may be shared. */
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
        revision++;
    }

    /**
     * Builds a small test pattern centred on the given world position, so P0 can be verified visually without any editing UI.
     */
    public static RoadNetwork demo(int centerX, int centerZ) {
        RoadNetwork net = new RoadNetwork();

        // A square ring road plus a diagonal cross, all centred on the player.
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

        // A straight highway running exactly EAST, ending 1000 blocks east of the centre, so its length can be checked against Xaero's coordinate readout.
        RoadSegment highway = RoadSegment.of(net.nextSegmentId++, RoadClass.HIGHWAY, 64,
                centerX, centerZ,
                centerX + 250, centerZ,
                centerX + 500, centerZ,
                centerX + 750, centerZ,
                centerX + 1000, centerZ);
        highway.setFromNode(center.id());
        net.addSegment(highway);

        // Reference marker exactly at the centre, so misalignment is obvious.
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
