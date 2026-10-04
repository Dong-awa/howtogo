package bili.dongsz.howtogo.road;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * Editing session over a {@link RoadNetwork}.
 * Undo is snapshot based. Road networks are small (hundreds of segments at most) and snapshot
 * copies are far harder to get wrong than hand-written inverse operations, which matters more here
 * than the memory.
 * The editor owns no geometry maths beyond the network itself; snapping lives in the client
 * layer because it needs the map viewport.
 */
public final class RoadEditor {

    private static final int MAX_UNDO = 64;

    private final RoadNetwork network;
    private final Deque<RoadNetwork> undoStack = new ArrayDeque<>();
    private final Deque<RoadNetwork> redoStack = new ArrayDeque<>();

    /**
     * Whether what happens here is the player's editing, and so worth a history entry.
     * False for the router's workspace, where a split is only how a stop gets a node of its own.
     * That network is a throwaway copy that nobody can undo anything on, and the history is not free:
     * every {@link #pushUndo()} copies the whole network, so a plan anchoring fifty stops would
     * allocate fifty further copies of a network that may hold an entire railway, and the reconciliation
     * pass below would walk the whole network once per stop as well.
     */
    private final boolean undoable;

    private RoadClass activeClass = RoadClass.ROAD;

    /** Last node of the polyline currently being drawn, or {@link RoadSegment#NO_NODE}. */
    private int chainNodeId = RoadSegment.NO_NODE;
    private int selectedNodeId = RoadSegment.NO_NODE;
    private int selectedSegmentId = RoadSegment.NO_SEGMENT;

    /**
     * Every segment of the road the player has selected.
     * A road with bends is stored as several segments, so selecting only the clicked one would
     * highlight and rename a single straight piece of it.
     */
    private final java.util.Set<Integer> selectedChain = new java.util.LinkedHashSet<>();

    public RoadEditor(RoadNetwork network) {
        this(network, true);
    }

    private RoadEditor(RoadNetwork network, boolean undoable) {
        this.network = network;
        this.undoable = undoable;
    }

    /**
     * An editor for topology rather than for editing: it splits segments and keeps no history.
     * For the router, which needs a node exactly where a stop is, on a working copy it owns, and
     * has nothing to undo. Nothing else about the operations changes -- the split itself is the same
     * topological one.
     */
    public static RoadEditor withoutUndo(RoadNetwork network) {
        return new RoadEditor(network, false);
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

    /**
     * Which way the whole road the given segment belongs to runs.
     *
     * <p>The direction of the road the player is looking at, rather than of the one piece of it the
     * cursor happens to be over: a street that bends is several segments, and asking only the piece under
     * the cursor would report "two-way" for a street whose other half is one-way.
     *
     * <h2>Why the chain is walked rather than the segments compared</h2>
     * Two pieces of one street need not be stored pointing the same way -- see
     * {@link #setChainDirection} -- so their raw directions can differ while the street itself runs
     * cleanly one way. Comparing the stored values would call such a street two-way, and the way to avoid
     * that is to ask the same question the marking wrote: walk the street from one end and see whether
     * every piece lets travel in where the previous one let it out, and whether they all run with the
     * walk or all against it.
     *
     * <p>{@link RoadDirection#TWO_WAY} is also the answer for a road whose pieces disagree, or half of
     * which nobody has marked yet, because that is the state a switch has to move on from: a half-marked
     * road is not one-way in either sense, and the next press makes it one-way in the first sense rather
     * than flipping a coin.
     */
    public RoadDirection chainDirection(int segmentId) {
        java.util.List<Integer> chain = RoadChains.chainContaining(network, segmentId);
        if (chain.isEmpty()) {
            return RoadDirection.TWO_WAY;
        }
        int node = chainStartNode(chain, segmentId);
        Boolean along = null;
        boolean any = false;

        for (int id : chain) {
            RoadSegment segment = network.segment(id);
            if (segment == null) {
                continue;
            }
            boolean enteredAtFrom = segment.fromNode() == node;
            if (!enteredAtFrom && segment.toNode() != node) {
                continue;
            }
            if (!segment.direction().isOneWay()) {
                // A piece with no restriction on it: whatever the rest of the street says, this road is
                // not one-way, and the switch has something to do.
                return RoadDirection.TWO_WAY;
            }
            boolean pieceAlong = (segment.direction() == RoadDirection.FORWARD) == enteredAtFrom;
            if (along == null) {
                along = pieceAlong;
            } else if (along != pieceAlong) {
                return RoadDirection.TWO_WAY;
            }
            any = true;
            node = enteredAtFrom ? segment.toNode() : segment.fromNode();
        }
        if (!any || along == null) {
            return RoadDirection.TWO_WAY;
        }
        return along ? RoadDirection.FORWARD : RoadDirection.BACKWARD;
    }

    /**
     * Sets the whole road the given segment belongs to one-way, in the given direction.
     *
     * <h2>Why the chain and not the segment</h2>
     * A player marking a street is marking the street. A road drawn with bends is one chain of segments,
     * and marking only the piece under the cursor would leave a street that is one-way for fifty blocks
     * and then two-way, which is not a road anybody built on purpose.
     *
     * <h2>Why each segment is oriented rather than copied</h2>
     * Nothing guarantees that the segments of a chain are stored pointing the same way: two polylines
     * joined end to end can have been drawn from opposite ends, and a road that was split by a crossing
     * keeps the original's numbering. So {@code forward} cannot simply be written onto every segment --
     * that would make half of a bent street one-way against the other half. The chain is walked from one
     * end, and each segment is told the direction it runs <em>in that walk</em>, which is what makes the
     * street one-way rather than its pieces.
     *
     * @param direction the direction to set, with {@link RoadDirection#FORWARD} meaning along the chain
     * @return whether anything changed
     */
    public boolean setChainDirection(int segmentId, RoadDirection direction) {
        if (network.segment(segmentId) == null || direction == null) {
            return false;
        }
        java.util.List<Integer> chain = RoadChains.chainContaining(network, segmentId);
        if (chain.isEmpty()) {
            return false;
        }
        pushUndo();

        // The node the walk starts from: the end of the first segment that the second one is not attached
        // to. A chain of one segment has no second one, and then the segment's own from-node is the start
        // -- so FORWARD on a single piece of road is the direction it was drawn in, which is the answer
        // that needs no explaining to the player who drew it.
        int node = chainStartNode(chain, segmentId);

        for (int id : chain) {
            RoadSegment segment = network.segment(id);
            if (segment == null) {
                continue;
            }
            if (!direction.isOneWay()) {
                segment.setDirection(RoadDirection.TWO_WAY);
                continue;
            }
            boolean along = segment.fromNode() == node;
            if (!along && segment.toNode() != node) {
                // The chain index and the geometry disagree, which a hand-edited or half-repaired file
                // can produce. The segment is left alone rather than given a direction that means
                // nothing about it.
                continue;
            }
            segment.setDirection(along ? direction : direction.reversed());
            node = along ? segment.toNode() : segment.fromNode();
        }
        return true;
    }

    /**
     * The node a chain walk starts from, as the chain walk produced it.
     *
     * <p>{@link RoadChains#chainContaining} walks both ways from the seed and stitches the far half in
     * front of it, so the seed itself is traversed from its own from-node to its to-node and the chain's
     * start is the free end of the first segment. Falling back to the seed keeps a one-segment chain
     * meaningful.
     */
    private int chainStartNode(java.util.List<Integer> chain, int seedSegmentId) {
        RoadSegment seed = network.segment(seedSegmentId);
        if (chain.size() < 2 || seed == null) {
            return seed == null ? RoadSegment.NO_NODE : seed.fromNode();
        }
        RoadSegment first = network.segment(chain.get(0));
        RoadSegment second = network.segment(chain.get(1));
        if (first == null || second == null) {
            return seed.fromNode();
        }
        boolean firstTouchesSecond = first.fromNode() == second.fromNode()
                || first.fromNode() == second.toNode();
        return firstTouchesSecond ? first.toNode() : first.fromNode();
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

    /**
     * Whether a node may be a transit station.
     * Why degree is the whole test
     * The rule is that a station stands on a road or at one of its ends. In this model a node is
     * always a segment's endpoint -- there is no such thing as a node in the middle of a segment;
     * landing one there splits the segment and creates a node, which is exactly what makes those two
     * cases the same case. So "on a road, or at either end of one" is precisely "at least one segment
     * touches this node", and no distance test is involved or needed.
     * A free-standing place fails it, and that is the point: a boarding point that no route passes
     * through is a boarding point nothing can reach.
     */
    public boolean stationAllowed(int nodeId) {
        if (segmentDegrees().getOrDefault(nodeId, 0) > 0) {
            return true;
        }
        return stationOverride != null && stationOverride.test(nodeId);
    }

    /** The client's half of the station rule, or null when there is none. */
    private java.util.function.IntPredicate stationOverride;

    /**
     * Adds a second rule to the station test, for the roads this class cannot see.
     *
     * <p>A station stands on a road, and the roads this class knows are the saved ones. The railway
     * layer is not saved -- it is read out of the world by the client, and changes whenever the player
     * lays track -- so only the client can say whether a place stands beside one. Handing that half in
     * keeps the station rule in one place; the version that asked a wrapper and then let this method
     * ask its own made the screen say yes and the save silently refuse, which the player sees as "the
     * name was not saved".
     */
    public void setStationOverride(java.util.function.IntPredicate stationOverride) {
        this.stationOverride = stationOverride;
    }

    /**
     * Applies a place's name and kind together, refusing a station that is not on a road.
     *
     * <p>One method rather than two calls so that one edit is one undo step: naming a place and
     * setting its type are a single thing the player did in a single screen, and having to press undo
     * twice to take it back would be a lie about how many changes were made.
     *
     * @return whether the change was made, so a caller can say why it was not
     */
    public boolean setPlace(int nodeId, String name, PlaceKind kind) {
        RoadNode node = network.node(nodeId);
        if (node == null || node.type() != RoadNode.Type.POI) {
            return false;
        }
        // Checked before the undo snapshot: a refused change must leave nothing behind, not an undo
        // entry for a change that did not happen.
        if (kind == PlaceKind.STATION && !stationAllowed(nodeId)) {
            return false;
        }
        pushUndo();
        node.setName(name);
        node.setPlaceKind(kind);
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
        // The vertices were written through the segments, which the network cannot see.
        network.touch();
        return true;
    }

    /**
     * A split's two halves and the node between them.
     *
     * <p>{@code firstSegment} is the piece from the original's from-node up to the new junction, and
     * {@code secondSegment} the piece from the junction on to the original's to-node.
     */
    public record Split(int firstSegment, int secondSegment, int junctionNode) {
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
        Split split = splitSegmentInto(segmentId, vertexIndex, x, y, z);
        return split == null ? -1 : split.junctionNode();
    }

    /**
     * The same split, handing back both halves as well as the junction.
     *
     * <p>For a caller that has to break one road at several places: applying the points from the far
     * end inwards always lands on {@code firstSegment}, and that half keeps the original's vertex
     * numbering, so the indices the caller computed its points from stay valid for every split after
     * the first.
     *
     * @return the two halves and the junction, or null when the index names no interior vertex
     */
    public Split splitSegmentInto(int segmentId, int vertexIndex, int x, int y, int z) {
        RoadSegment original = network.segment(segmentId);
        if (original == null || vertexIndex <= 0 || vertexIndex >= original.vertexCount()) {
            return null;
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
        first.setDirection(original.direction());

        RoadSegment second = network.newSegment(original.roadClass(), y,
                original.vertexCount() - vertexIndex + 1);
        second.addVertex(x, z);
        for (int i = vertexIndex; i < original.vertexCount(); i++) {
            second.addVertex(original.x(i), original.z(i));
        }
        second.setFromNode(junction.id());
        second.setToNode(original.toNode());
        second.setName(original.name());
        second.setDirection(original.direction());

        network.removeSegment(segmentId);
        network.addSegment(first);
        network.addSegment(second);

        if (selectedSegmentId == segmentId) {
            selectedSegmentId = first.id();
        }
        if (undoable) {
            // Derived state that only the player's editing needs: the node types the editor draws and
            // the orphan stations it refuses to lose. On a routing copy there is one new node, it was
            // created as a junction, and nobody is looking.
            reclassifyNodes();
        }
        return new Split(first.id(), second.id(), junction.id());
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
        // The demotion runs first so that the state the pruning sees is already the settled one: a
        // station here is always a place node, and places are exempt from the loop below, so the
        // landmark survives its road either way -- but demoting after a deletion would be demoting
        // something that had already gone.
        demoteOrphanStations(usage);
        for (RoadNode node : network.nodesSnapshot()) {
            if (node.type() == RoadNode.Type.POI) {
                continue;
            }
            if (!usage.containsKey(node.id())) {
                network.removeNode(node.id());
            }
        }
    }

    /**
     * Turns a station that has lost its road into an ordinary place.
     *
     * <p>A station is a boarding point on a road, so when the road is deleted the point stops being
     * one. It is demoted rather than removed because the position and the name are the player's work:
     * deleting a road is not a reason to throw away a landmark somebody put down and named.
     *
     * <p>Only the kind changes. The node stays a {@link RoadNode.Type#POI}, which is both what makes
     * it survive pruning -- a place is exempt from it -- and what keeps it a place rather than a bare
     * road vertex.
     */
    private void demoteOrphanStations(Map<Integer, Integer> degree) {
        for (RoadNode node : network.nodesSnapshot()) {
            if (node.placeKind() == PlaceKind.STATION && degree.getOrDefault(node.id(), 0) == 0) {
                node.setPlaceKind(PlaceKind.PLACE);
            }
        }
    }

    /**
     * Recomputes node types from how many segments touch them, and demotes a station that has lost
     * its road.
     *
     * <h2>Why the station rule is re-checked here</h2>
     * A station is a boarding point <em>on a road</em>, and a point stops being one the moment the
     * road under it is deleted. Its name and position are still the player's work, so it becomes an
     * ordinary place rather than being removed: deleting a road is not a reason to throw away a
     * landmark somebody put down and named.
     *
     * <p>This is the pass that reconciles everything derived from the topology, and "is this node
     * still on a road" is exactly that kind of state. Doing it here rather than at each call site also
     * means every deletion path gets it for free -- segment deletion, node deletion, pruning and
     * finishing a chain all end up in this method already.
     */
    public void reclassifyNodes() {
        Map<Integer, Integer> degree = segmentDegrees();
        // Also here, not only in the pruning pass: deleting a segment runs this and does not prune, so
        // a station orphaned that way would otherwise sit there as an invalid station until the next
        // road was finished.
        demoteOrphanStations(degree);
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
        if (!undoable) {
            return;
        }
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
