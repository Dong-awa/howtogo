package bili.dongsz.howtogo.road;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * Editing session over a {@link RoadNetwork}.
 *
 * <p>Undo is snapshot based. Road networks are small (hundreds of segments at most) and snapshot
 * copies are far harder to get wrong than hand-written inverse operations, which matters more here
 * than the memory.
 *
 * <p>The editor owns no geometry maths beyond the network itself; snapping lives in the client
 * layer because it needs the map viewport.
 */
public final class RoadEditor {

    private static final int MAX_UNDO = 64;

    private final RoadNetwork network;
    private final Deque<RoadNetwork> undoStack = new ArrayDeque<>();
    private final Deque<RoadNetwork> redoStack = new ArrayDeque<>();

    private RoadClass activeClass = RoadClass.ROAD;

    /** Last node of the polyline currently being drawn, or {@link RoadSegment#NO_NODE}. */
    private int chainNodeId = RoadSegment.NO_NODE;
    private int selectedNodeId = RoadSegment.NO_NODE;
    private int selectedSegmentId = RoadSegment.NO_SEGMENT;

    /**
     * Every segment of the road the player has selected.
     *
     * <p>A road with bends is stored as several segments, so selecting only the clicked one would
     * highlight and rename a single straight piece of it.
     */
    private final java.util.Set<Integer> selectedChain = new java.util.LinkedHashSet<>();

    public RoadEditor(RoadNetwork network) {
        this.network = network;
    }

    public RoadNetwork network() {
        return network;
    }

    public RoadClass activeClass() {
        return activeClass;
    }

    public void setActiveClass(RoadClass activeClass) {
        this.activeClass = activeClass;
    }

    /** Re-classes the whole road the given segment belongs to, leaving the drawing class alone. */
    public boolean setSegmentClass(int segmentId, RoadClass roadClass) {
        if (network.segment(segmentId) == null) {
            return false;
        }
        pushUndo();
        for (int id : RoadChains.chainContaining(network, segmentId)) {
            RoadSegment segment = network.segment(id);
            if (segment != null) {
                segment.setRoadClass(roadClass);
            }
        }
        return true;
    }

    /** Creates a point of interest and selects it. */
    public int addPoi(int x, int y, int z) {
        pushUndo();
        RoadNode node = network.addNode(x, y, z, RoadNode.Type.POI, null);
        selectedNodeId = node.id();
        selectedSegmentId = RoadSegment.NO_SEGMENT;
        chainNodeId = RoadSegment.NO_NODE;
        return node.id();
    }

    public boolean setNodeName(int nodeId, String name) {
        RoadNode node = network.node(nodeId);
        if (node == null) {
            return false;
        }
        pushUndo();
        node.setName(name);
        return true;
    }

    public boolean setSegmentName(int segmentId, String name) {
        RoadSegment segment = network.segment(segmentId);
        if (segment == null) {
            return false;
        }
        pushUndo();
        segment.setName(name);
        return true;
    }

    /**
     * Names the whole road the given segment belongs to.
     *
     * <p>Naming a single segment would leave a road with bends called one thing for one straight
     * piece and something else for the next.
     */
    public boolean setRoadName(int segmentId, String name) {
        if (network.segment(segmentId) == null) {
            return false;
        }
        pushUndo();
        for (int id : RoadChains.chainContaining(network, segmentId)) {
            RoadSegment segment = network.segment(id);
            if (segment != null) {
                segment.setName(name);
            }
        }
        return true;
    }

    public int chainNodeId() {
        return chainNodeId;
    }

    public int selectedNodeId() {
        return selectedNodeId;
    }

    public int selectedSegmentId() {
        return selectedSegmentId;
    }

    /** Every segment of the selected road; empty when nothing is selected. */
    public java.util.Set<Integer> selectedChain() {
        return selectedChain;
    }

    public boolean canUndo() {
        return !undoStack.isEmpty();
    }

    public boolean canRedo() {
        return !redoStack.isEmpty();
    }

    // ---------------------------------------------------------------- drawing

    /**
     * Appends a point to the polyline being drawn.
     *
     * @param reuseNodeId an existing node to snap onto, or {@link RoadSegment#NO_NODE} to create one
     * @return the id of the node that is now the chain head
     */
    public int addChainPoint(int x, int y, int z, int reuseNodeId) {
        pushUndo();

        int nodeId = reuseNodeId;
        if (nodeId == RoadSegment.NO_NODE || network.node(nodeId) == null) {
            nodeId = network.addNode(x, y, z, RoadNode.Type.ENDPOINT, null).id();
        }

        if (chainNodeId != RoadSegment.NO_NODE && chainNodeId != nodeId) {
            RoadNode from = network.node(chainNodeId);
            RoadNode to = network.node(nodeId);
            if (from != null && to != null) {
                RoadSegment segment = network.newSegment(activeClass, y, 2);
                segment.addVertex(from.x(), from.z());
                segment.addVertex(to.x(), to.z());
                segment.setFromNode(from.id());
                segment.setToNode(to.id());
                network.addSegment(segment);
            }
        }

        chainNodeId = nodeId;
        selectedNodeId = nodeId;
        selectedSegmentId = RoadSegment.NO_SEGMENT;
        reclassifyNodes();
        return nodeId;
    }

    /**
     * Ends the current polyline so the next click starts a new one.
     *
     * <p>Also drops any node that ended up with no road attached, which is the common result of
     * starting a road and then abandoning it.
     */
    public void finishChain() {
        if (chainNodeId != RoadSegment.NO_NODE) {
            pruneOrphanNodes();
            reclassifyNodes();
        }
        chainNodeId = RoadSegment.NO_NODE;
    }

    // ---------------------------------------------------------------- selection

    /**
     * Selects a node by id.
     *
     * <h2>Why this does not arm the drawing chain</h2>
     * It used to: the chain head was set to the selected node, so that selecting a node was also
     * "carry on drawing from here". That made a selection impossible to make without side effects --
     * pointing at a node to rename the place on it armed a rubber band from that node, and the next
     * click drew a road nobody asked for. Drawing from an existing node still works, and always did,
     * through the ordinary click path: {@link #addChainPoint} is what sets the chain head, and
     * clicking a node passes that node to it.
     */
    public boolean selectNode(int nodeId) {
        if (network.node(nodeId) == null) {
            return false;
        }
        selectedNodeId = nodeId;
        selectedSegmentId = RoadSegment.NO_SEGMENT;
        selectedChain.clear();
        return true;
    }

    public void selectSegment(int segmentId) {
        RoadSegment segment = network.segment(segmentId);
        selectedSegmentId = segment != null ? segmentId : RoadSegment.NO_SEGMENT;
        selectedNodeId = RoadSegment.NO_NODE;
        chainNodeId = RoadSegment.NO_NODE;
        selectedChain.clear();
        if (segment != null) {
            selectedChain.addAll(RoadChains.chainContaining(network, segmentId));
        }
    }

    /**
     * Drops every part of the selection, including the chain head.
     *
     * <p>The chain head is selection state like the rest: leaving it set meant "nothing is selected"
     * could still be drawing from a node.
     */
    public void clearSelection() {
        selectedNodeId = RoadSegment.NO_NODE;
        selectedSegmentId = RoadSegment.NO_SEGMENT;
        chainNodeId = RoadSegment.NO_NODE;
        selectedChain.clear();
    }

    // ---------------------------------------------------------------- mutations

    /** Moves a node, dragging every segment endpoint that references it. */
    public boolean moveNode(int nodeId, int x, int y, int z) {
        RoadNode node = network.node(nodeId);
        if (node == null) {
            return false;
        }
        pushUndo();
        node.moveTo(x, y, z);
        for (RoadSegment segment : network.segments()) {
            if (segment.fromNode() == nodeId && segment.vertexCount() > 0) {
                segment.moveVertex(0, x, z);
            }
            if (segment.toNode() == nodeId && segment.vertexCount() > 0) {
                segment.moveVertex(segment.vertexCount() - 1, x, z);
            }
        }
        return true;
    }

    /**
     * Splits a segment at {@code vertexIndex}, returning the new junction node's id, or -1.
     *
     * <p>This is a <b>topological</b> split, not just a geometric one: the original segment is
     * replaced by two segments meeting at a new node. Merely inserting a vertex would leave the
     * original segment still wired to its old endpoints, so the new node would have degree 0 and
     * the graph would stay disconnected -- roads would look joined on screen while routing still
     * saw two unrelated fragments.
     */
    public int splitSegment(int segmentId, int vertexIndex, int x, int y, int z) {
        RoadSegment original = network.segment(segmentId);
        if (original == null || vertexIndex <= 0 || vertexIndex >= original.vertexCount()) {
            return -1;
        }
        pushUndo();

        RoadNode junction = network.addNode(x, y, z, RoadNode.Type.JUNCTION, null);

        RoadSegment first = network.newSegment(original.roadClass(), y, vertexIndex + 1);
        for (int i = 0; i < vertexIndex; i++) {
            first.addVertex(original.x(i), original.z(i));
        }
        first.addVertex(x, z);
        first.setFromNode(original.fromNode());
        first.setToNode(junction.id());
        first.setName(original.name());
        first.setOneWay(original.oneWay());

        RoadSegment second = network.newSegment(original.roadClass(), y,
                original.vertexCount() - vertexIndex + 1);
        second.addVertex(x, z);
        for (int i = vertexIndex; i < original.vertexCount(); i++) {
            second.addVertex(original.x(i), original.z(i));
        }
        second.setFromNode(junction.id());
        second.setToNode(original.toNode());
        second.setName(original.name());
        second.setOneWay(original.oneWay());

        network.removeSegment(segmentId);
        network.addSegment(first);
        network.addSegment(second);

        if (selectedSegmentId == segmentId) {
            selectedSegmentId = first.id();
        }
        reclassifyNodes();
        return junction.id();
    }

    /** Deletes the current selection, removing any segments left dangling. */
    public boolean deleteSelection() {
        if (selectedSegmentId != RoadSegment.NO_SEGMENT) {
            pushUndo();
            network.removeSegment(selectedSegmentId);
            selectedSegmentId = RoadSegment.NO_SEGMENT;
            pruneOrphanNodes();
            reclassifyNodes();
            return true;
        }
        if (selectedNodeId != RoadSegment.NO_NODE) {
            pushUndo();
            int nodeId = selectedNodeId;

            // Collect first, then remove. RoadNetwork.segments() is a live view of the backing
            // map, so removing inside the loop throws ConcurrentModificationException.
            java.util.List<Integer> attached = new java.util.ArrayList<>();
            for (RoadSegment segment : network.segments()) {
                if (segment.fromNode() == nodeId || segment.toNode() == nodeId) {
                    attached.add(segment.id());
                }
            }
            for (int segmentId : attached) {
                network.removeSegment(segmentId);
            }

            network.removeNode(nodeId);
            selectedNodeId = RoadSegment.NO_NODE;
            if (chainNodeId == nodeId) {
                chainNodeId = RoadSegment.NO_NODE;
            }
            reclassifyNodes();
            return true;
        }
        return false;
    }

    /** How many segment endpoints each node carries. */
    private Map<Integer, Integer> segmentDegrees() {
        Map<Integer, Integer> degrees = new HashMap<>();
        for (RoadSegment segment : network.segments()) {
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
     * Removes nodes that no longer belong to any segment.
     *
     * <h2>Places are exempt, and must stay exempt</h2>
     * A hand-placed place is a node with <b>no segment on purpose</b> -- that is exactly what makes it
     * a landmark rather than a road vertex. Under this method's test every place is therefore an
     * orphan, and without the guard below, finishing a road or dragging one of its nodes deleted
     * every place in the network. That is data loss, not tidying.
     *
     * <p>{@link #reclassifyNodes} has always skipped places; this method did not, which is the whole
     * bug. The two ask the same question -- "does this node still count as part of the roads?" -- and
     * they have to give the same answer, so the guard is written the same way in both. If a third
     * caller is ever added, it needs the same one.
     *
     * <p>Note that this runs inside operations that do not push their own undo snapshot, so a place
     * removed here is not necessarily recoverable with Ctrl+Z either.
     */
    public void pruneOrphanNodes() {
        Map<Integer, Integer> usage = segmentDegrees();
        for (RoadNode node : network.nodesSnapshot()) {
            if (node.type() == RoadNode.Type.POI) {
                continue;
            }
            if (!usage.containsKey(node.id())) {
                network.removeNode(node.id());
            }
        }
    }

    /** Recomputes node types from how many segments touch them. */
    public void reclassifyNodes() {
        Map<Integer, Integer> degree = segmentDegrees();
        for (RoadNode node : network.nodes()) {
            if (node.type() == RoadNode.Type.POI) {
                continue;
            }
            int d = degree.getOrDefault(node.id(), 0);
            node.setType(d >= 3 ? RoadNode.Type.JUNCTION : RoadNode.Type.ENDPOINT);
        }
    }

    // ---------------------------------------------------------------- history

    public void undo() {
        if (undoStack.isEmpty()) {
            return;
        }
        redoStack.push(network.deepCopy());
        network.replaceWith(undoStack.pop());
        clampSelection();
    }

    public void redo() {
        if (redoStack.isEmpty()) {
            return;
        }
        undoStack.push(network.deepCopy());
        network.replaceWith(redoStack.pop());
        clampSelection();
    }

    private void pushUndo() {
        undoStack.push(network.deepCopy());
        while (undoStack.size() > MAX_UNDO) {
            undoStack.removeLast();
        }
        redoStack.clear();
    }

    /** Drops selection ids that no longer exist after an undo/redo swapped the network out. */
    private void clampSelection() {
        if (network.node(selectedNodeId) == null) {
            selectedNodeId = RoadSegment.NO_NODE;
        }
        selectedChain.clear();
        if (network.segment(selectedSegmentId) == null) {
            selectedSegmentId = RoadSegment.NO_SEGMENT;
        } else {
            selectedChain.addAll(RoadChains.chainContaining(network, selectedSegmentId));
        }
        if (network.node(chainNodeId) == null) {
            chainNodeId = RoadSegment.NO_NODE;
        }
    }
}
