package bili.dongsz.howtogo.road;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * Editing session over a RoadNetwork. Undo is snapshot based: networks are small (hundreds of
 * segments) and snapshots are harder to get wrong than hand-written inverse operations. Snapping
 * lives in the client layer because it needs the map viewport.
 */
public final class RoadEditor {

    private static final int MAX_UNDO = 64;

    private final RoadNetwork network;
    private final Deque<RoadNetwork> undoStack = new ArrayDeque<>();
    private final Deque<RoadNetwork> redoStack = new ArrayDeque<>();

    /**
     * Whether what happens here is the player's editing, and so worth a history entry. False for the
     * router's workspace: every pushUndo copies the whole network, so anchoring fifty stops would
     * allocate fifty copies of a network that may hold a whole railway.
     */
    private final boolean undoable;

    private RoadClass activeClass = RoadClass.ROAD;

    /** Last node of the polyline currently being drawn, or RoadSegment.NO_NODE. */
    private int chainNodeId = RoadSegment.NO_NODE;
    private int selectedNodeId = RoadSegment.NO_NODE;
    private int selectedSegmentId = RoadSegment.NO_SEGMENT;

    /**
     * Every segment of the selected road. A road with bends is several segments, so selecting only the
     * clicked one would highlight and rename one straight piece.
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
     * An editor for topology rather than editing: it splits segments and keeps no history, for the
     * router's working copy. The split itself is the same topological one.
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

    /**
     * The storey a newly drawn road goes on, carried on the editor: a tunnel is a dozen pieces, and
     * setting the storey per piece would be a thing to remember. Setting it on an existing road moves
     * that road and also becomes the storey for what is drawn next.
     */
    private int activeLayer;

    public int activeLayer() {
        return activeLayer;
    }

    public void setActiveLayer(int layer) {
        this.activeLayer = Math.max(RoadSegment.MIN_LAYER, Math.min(RoadSegment.MAX_LAYER, layer));
    }

    /**
     * Moves every selected road onto the given storey and makes it the storey new roads go on. Whole
     * roads, so a bent street cannot change storey halfway, and several roads move in one undo step. A
     * selection naming no segment still moves the road of segmentId.
     */
    public boolean setChainLayer(int segmentId, int layer) {
        java.util.List<java.util.List<Integer>> chains = chainsToEdit(segmentId);
        if (chains.isEmpty()) {
            return false;
        }
        pushUndo();
        for (java.util.List<Integer> chain : chains) {
            for (int id : chain) {
                RoadSegment segment = network.segment(id);
                if (segment != null) {
                    segment.setLayer(layer);
                }
            }
        }
        setActiveLayer(layer);
        return true;
    }

    /**
     * How long the whole road the given segment belongs to is, in blocks: the chain, not the piece,
     * matching what the map highlights.
     * @return the summed length, or 0 when the id names no segment
     */
    public double chainLength(int segmentId) {
        if (network.segment(segmentId) == null) {
            return 0.0;
        }
        double total = 0.0;
        for (int id : RoadChains.chainContaining(network, segmentId)) {
            RoadSegment segment = network.segment(id);
            if (segment != null) {
                total += segment.length();
            }
        }
        return total;
    }

    /**
     * How long everything selected is, in blocks: the sum of the whole roads the selection touches.
     * Every bulk edit works on whole roads, so a readout of only the highlighted pieces would disagree;
     * two segments of one road count it once, and an empty selection is 0.
     */
    public double selectedLength() {
        if (selectedChain.isEmpty()) {
            return chainLength(selectedSegmentId);
        }
        double total = 0.0;
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (int seed : selectedChain) {
            if (!seen.add(seed)) {
                continue;
            }
            java.util.List<Integer> chain = RoadChains.chainContaining(network, seed);
            if (chain.isEmpty()) {
                RoadSegment alone = network.segment(seed);
                if (alone != null) {
                    total += alone.length();
                }
                continue;
            }
            seen.addAll(chain);
            for (int id : chain) {
                RoadSegment segment = network.segment(id);
                if (segment != null) {
                    total += segment.length();
                }
            }
        }
        return total;
    }

    /**
     * The storey of the main selected segment, or the surface when nothing is selected. One number
     * despite a mixed selection; the main segment is the last one the player touched.
     */
    public int selectedLayer() {
        RoadSegment segment = network.segment(selectedSegmentId);
        return segment != null ? segment.layer() : 0;
    }

    /**
     * Re-classes every selected road, leaving the drawing class alone. A road, not a piece: a chain is
     * pieces of one class, so re-classing one segment would cut the street in two. Several roads in one
     * undo step; a selection naming no segment re-classes the road of segmentId.
     */
    public boolean setSegmentClass(int segmentId, RoadClass roadClass) {
        if (roadClass == null) {
            return false;
        }
        java.util.List<java.util.List<Integer>> chains = chainsToEdit(segmentId);
        if (chains.isEmpty()) {
            return false;
        }
        pushUndo();
        for (java.util.List<Integer> chain : chains) {
            for (int id : chain) {
                RoadSegment segment = network.segment(id);
                if (segment != null) {
                    segment.setRoadClass(roadClass);
                }
            }
        }
        // A class is not geometry, but grouping and the router's graph depend on it, so the caches must see
        // the change.
        network.touch();
        return true;
    }

    /**
     * Which way the whole road the segment belongs to runs. The chain is walked rather than its stored
     * directions compared: two pieces may be stored pointing opposite ways while the street runs
     * cleanly one way. TWO_WAY is also the answer for pieces that disagree or are half-marked, the
     * state a switch moves on from.
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
     * Sets the whole road the segment belongs to one-way in the given direction. A street is one chain,
     * so marking only the piece under the cursor would leave it half one-way. Each piece is oriented
     * along the chain walk, because stored directions need not agree.
     * @param direction the direction to set, with FORWARD meaning along the chain
     * @return whether anything changed
     */
    public boolean setChainDirection(int segmentId, RoadDirection direction) {
        if (direction == null) {
            return false;
        }
        java.util.List<java.util.List<Integer>> chains = chainsToEdit(segmentId);
        if (chains.isEmpty()) {
            return false;
        }
        pushUndo();
        for (java.util.List<Integer> chain : chains) {
            applyChainDirection(chain, direction);
        }
        return true;
    }

    /**
     * Flips one segment's closure, one undo step. A closure is marked per segment rather than per
     * street -- a construction site shuts one block, and the rest of the street keeps routing.
     * @return whether the segment is closed after the call
     */
    public boolean toggleClosed(int segmentId) {
        RoadSegment segment = network.segment(segmentId);
        if (segment == null) {
            return false;
        }
        pushUndo();
        segment.setClosed(!segment.closed());
        return segment.closed();
    }

    /**
     * Turns every selected road one step round the one-way states, each from its own state, so the
     * fourth press brings a mixed selection back to two-way. Each road is oriented along its own walk;
     * one undo step.
     * @return whether anything was selected and switched
     */
    public boolean cycleSelectedDirection() {
        java.util.List<java.util.List<Integer>> chains = chainsToEdit(selectedSegmentId);
        if (chains.isEmpty()) {
            return false;
        }
        pushUndo();
        for (java.util.List<Integer> chain : chains) {
            applyChainDirection(chain, chainDirection(chain.get(0)).next());
        }
        return true;
    }

    private void applyChainDirection(java.util.List<Integer> chain, RoadDirection direction) {
        // The walk starts at the end of the first segment the second is not attached to; a one-segment chain
        // starts at its own from-node, so FORWARD is the direction it was drawn in.
        int node = chainStartNode(chain, chain.get(0));

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
                continue;
            }
            segment.setDirection(along ? direction : direction.reversed());
            node = along ? segment.toNode() : segment.fromNode();
        }
    }

    /**
     * The node a chain walk starts from. RoadChains.chainContaining stitches the far half in front of
     * the seed, so the start is the free end of the first segment; falling back to the seed keeps a
     * one-segment chain meaningful.
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

    /**
     * How far apart, in blocks vertically, two places on the same block may be and still be the same
     * place. A place is created at the player's feet height, which a step or jump moves by a couple of
     * blocks; only the height is rounded so generously.
     */
    private static final int SAME_PLACE_HEIGHT = 2;

    /**
     * What placePoi did: the place's node and whether the press created it. The fact has to come back
     * with the id: a caller cannot tell a just-created place from one that was always there, and
     * cancelling only has something to take back when it created one.
     * @param nodeId  the place's node, created or found
     * @param created whether this call added it to the network
     */
    public record PoiPlacement(int nodeId, boolean created) {
    }

    /**
     * Creates a point of interest and selects it. A second press on the same block selects the existing
     * place rather than stacking another, which would be indistinguishable on the map and could make an
     * edit land on the wrong node. See PoiPlacement.
     */
    public PoiPlacement placePoi(int x, int y, int z) {
        for (RoadNode existing : network.nodesSnapshot()) {
            if (existing.type() == RoadNode.Type.POI
                    && existing.x() == x
                    && existing.z() == z
                    && Math.abs(existing.y() - y) <= SAME_PLACE_HEIGHT) {
                selectedNodeId = existing.id();
                selectedSegmentId = RoadSegment.NO_SEGMENT;
                selectedChain.clear();
                chainNodeId = RoadSegment.NO_NODE;
                return new PoiPlacement(existing.id(), false);
            }
        }
        pushUndo();
        RoadNode node = network.addNode(x, y, z, RoadNode.Type.POI, null);
        selectedNodeId = node.id();
        selectedSegmentId = RoadSegment.NO_SEGMENT;
        selectedChain.clear();
        chainNodeId = RoadSegment.NO_NODE;
        return new PoiPlacement(node.id(), true);
    }

    public int addPoi(int x, int y, int z) {
        return placePoi(x, y, z).nodeId();
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
     * Whether a node may be a transit station. The rule is that a station stands on a road or at one of
     * its ends; every node is a segment endpoint, since landing a node mid-segment splits it, so the
     * test is "at least one segment touches this node".
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
     * Adds a second rule to the station test, for roads this class cannot see: the railway is read out
     * of the world by the client, so only the client can say whether a place stands beside one. Keeping
     * both halves in one rule stops the screen saying yes while the save silently refuses.
     */
    public void setStationOverride(java.util.function.IntPredicate stationOverride) {
        this.stationOverride = stationOverride;
    }

    /**
     * Applies a place's name and kind together, refusing a station that is not on a road. One method so
     * one edit is one undo step.
     * @return whether the change was made
     */
    public boolean setPlace(int nodeId, String name, PlaceKind kind) {
        RoadNode node = network.node(nodeId);
        if (node == null || node.type() != RoadNode.Type.POI) {
            return false;
        }
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
     * Names every selected road, and the road of segmentId when nothing is selected: a bent road must
     * not be half named. Several roads get the same name in one undo step.
     */
    public boolean setRoadName(int segmentId, String name) {
        java.util.List<java.util.List<Integer>> chains = chainsToEdit(segmentId);
        if (chains.isEmpty()) {
            return false;
        }
        pushUndo();
        for (java.util.List<Integer> chain : chains) {
            for (int id : chain) {
                RoadSegment segment = network.segment(id);
                if (segment != null) {
                    segment.setName(name);
                }
            }
        }
        return true;
    }

    /**
     * Names every selected road and puts it on a storey, as one edit and one undo step, because it is
     * one visit to one panel.
     */
    public boolean setRoadNameAndLayer(int segmentId, String name, int layer) {
        java.util.List<java.util.List<Integer>> chains = chainsToEdit(segmentId);
        if (chains.isEmpty()) {
            return false;
        }
        pushUndo();
        for (java.util.List<Integer> chain : chains) {
            for (int id : chain) {
                RoadSegment segment = network.segment(id);
                if (segment != null) {
                    segment.setName(name);
                    segment.setLayer(layer);
                }
            }
        }
        setActiveLayer(layer);
        return true;
    }

    /**
     * Everything one visit to the road panel edits: name, storey, category, number and direction. One
     * undo step rather than five, and every selected road gets the same values. The category is clamped
     * by the segment itself; the direction is chain-relative via applyChainDirection, and null leaves
     * it alone.
     */
    public boolean setRoadDetails(int segmentId, String name, int layer, RoadCategory category,
                                  String number, RoadDirection direction) {
        java.util.List<java.util.List<Integer>> chains = chainsToEdit(segmentId);
        if (chains.isEmpty()) {
            return false;
        }
        pushUndo();
        for (java.util.List<Integer> chain : chains) {
            for (int id : chain) {
                RoadSegment segment = network.segment(id);
                if (segment != null) {
                    segment.setName(name);
                    segment.setLayer(layer);
                    segment.setCategory(category);
                    segment.setNumber(number);
                }
            }
            if (direction != null) {
                applyChainDirection(chain, direction);
            }
        }
        setActiveLayer(layer);
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

    public java.util.Set<Integer> selectedChain() {
        return selectedChain;
    }

    public boolean canUndo() {
        return !undoStack.isEmpty();
    }

    public boolean canRedo() {
        return !redoStack.isEmpty();
    }


    /**
     * Appends a point to the polyline being drawn.
     * @param reuseNodeId an existing node to snap onto, or RoadSegment.NO_NODE to create one
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
                segment.setLayer(activeLayer);
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
        // Drawing selects the node being drawn: the roads selected a moment ago are not what Delete or N
        // would act on now.
        selectedChain.clear();
        reclassifyNodes();
        return nodeId;
    }

    /**
     * Ends the current polyline so the next click starts a new one, and drops any node left with no road
     * attached.
     */
    public void finishChain() {
        if (chainNodeId != RoadSegment.NO_NODE) {
            pruneOrphanNodes();
            reclassifyNodes();
        }
        chainNodeId = RoadSegment.NO_NODE;
    }


    /**
     * Selects a node by id. This does not arm the drawing chain: setting the chain head here made
     * selecting a node also carry on drawing, arming a rubber band nobody asked for. Drawing from a node
     * goes through addChainPoint.
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

    /**
     * Selects the whole road the segment belongs to, and only that road, replacing whatever was
     * selected: a plain Shift+left click.
     */
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
     * Selects one segment of a road and nothing else, for trimming or re-routing a single straight
     * stretch; reached by double-clicking so it cannot happen by accident. Road-wide operations still
     * act on the road; see selectedLength.
     */
    public void selectSingleSegment(int segmentId) {
        RoadSegment segment = network.segment(segmentId);
        selectedSegmentId = segment != null ? segmentId : RoadSegment.NO_SEGMENT;
        selectedNodeId = RoadSegment.NO_NODE;
        chainNodeId = RoadSegment.NO_NODE;
        selectedChain.clear();
        if (segment != null) {
            selectedChain.add(segmentId);
        }
    }

    /**
     * Adds the whole road the segment belongs to, or takes it back out. A road only partly in the set is
     * completed rather than removed. The main selection becomes the road just added, or the first
     * remaining one.
     * @return whether the segment names a road, which is what makes the press worth consuming
     */
    public boolean toggleChainSelection(int segmentId) {
        if (network.segment(segmentId) == null) {
            return false;
        }
        java.util.List<Integer> chain = RoadChains.chainContaining(network, segmentId);
        if (chain.isEmpty()) {
            chain = java.util.List.of(segmentId);
        }
        return toggleSelection(segmentId, chain);
    }

    /**
     * Adds one segment to the selection, or takes it back out, leaving the rest of its road alone;
     * reached by double-clicking.
     */
    public boolean toggleSingleSegmentSelection(int segmentId) {
        if (network.segment(segmentId) == null) {
            return false;
        }
        return toggleSelection(segmentId, java.util.List.of(segmentId));
    }

    /**
     * Adds a set of segments to the selection, or removes it if all are already in. Clearing the node
     * selection is deliberate: the selection is one thing at a time.
     */
    private boolean toggleSelection(int clickedSegmentId, java.util.List<Integer> members) {
        selectedNodeId = RoadSegment.NO_NODE;
        chainNodeId = RoadSegment.NO_NODE;
        if (selectedChain.containsAll(members)) {
            selectedChain.removeAll(members);
            if (selectedChain.isEmpty()) {
                selectedSegmentId = RoadSegment.NO_SEGMENT;
            } else if (!selectedChain.contains(selectedSegmentId)) {
                selectedSegmentId = selectedChain.iterator().next();
            }
        } else {
            selectedChain.addAll(members);
            selectedSegmentId = clickedSegmentId;
        }
        return true;
    }

    /**
     * A copy of the segment selection, for a gesture that has to take one of its own steps back. The
     * first press of a double click is indistinguishable from a single one, so the client remembers
     * what the first click replaced and restores it when the second turns out to be a double.
     */
    public record SelectionSnapshot(java.util.List<Integer> segments, int primary) {
    }

    public SelectionSnapshot snapshotSelection() {
        return new SelectionSnapshot(new java.util.ArrayList<>(selectedChain), selectedSegmentId);
    }

    /**
     * Puts the segment selection back to a snapshot. Ids that no longer name a segment are dropped, and
     * the first remaining one becomes main if the snapshot's main has gone.
     */
    public void restoreSelection(SelectionSnapshot snapshot) {
        selectedChain.clear();
        selectedNodeId = RoadSegment.NO_NODE;
        chainNodeId = RoadSegment.NO_NODE;
        selectedSegmentId = RoadSegment.NO_SEGMENT;
        if (snapshot == null) {
            return;
        }
        for (int id : snapshot.segments()) {
            if (network.segment(id) != null) {
                selectedChain.add(id);
            }
        }
        if (snapshot.primary() != RoadSegment.NO_SEGMENT
                && network.segment(snapshot.primary()) != null) {
            selectedSegmentId = snapshot.primary();
            selectedChain.add(selectedSegmentId);
        } else if (!selectedChain.isEmpty()) {
            selectedSegmentId = selectedChain.iterator().next();
        }
    }

    /**
     * The roads a bulk edit acts on: every road touched by the selection plus the road of the given
     * segment. Answering this once keeps the class, storey, name and one-way edits from drifting apart,
     * and the walk is deduplicated by road.
     */
    private java.util.List<java.util.List<Integer>> chainsToEdit(int segmentId) {
        java.util.Map<Integer, java.util.List<Integer>> chains = new java.util.LinkedHashMap<>();
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        java.util.List<Integer> seeds = new java.util.ArrayList<>(selectedChain);
        if (network.segment(segmentId) != null) {
            seeds.add(segmentId);
        }
        for (int seed : seeds) {
            if (network.segment(seed) == null || !seen.add(seed)) {
                continue;
            }
            java.util.List<Integer> chain = RoadChains.chainContaining(network, seed);
            if (chain.isEmpty()) {
                chain = java.util.List.of(seed);
            }
            seen.addAll(chain);
            chains.put(chain.get(0), chain);
        }
        return new java.util.ArrayList<>(chains.values());
    }

    public void clearSelection() {
        selectedNodeId = RoadSegment.NO_NODE;
        selectedSegmentId = RoadSegment.NO_SEGMENT;
        chainNodeId = RoadSegment.NO_NODE;
        selectedChain.clear();
    }


    /** Moves a node, dragging every segment endpoint that references it. */
    public boolean moveNode(int nodeId, int x, int y, int z) {
        RoadNode node = network.node(nodeId);
        if (node == null) {
            return false;
        }
        pushUndo();
        place(nodeId, node, x, y, z);
        return true;
    }

    /**
     * Moves a node without recording it, for a drag already in progress: a drag is one edit, not one per
     * frame, and recording each frame filled the 64-entry history with the drag's own pixels. The caller
     * records once with beginHistory.
     */
    public boolean moveNodeLive(int nodeId, int x, int y, int z) {
        RoadNode node = network.node(nodeId);
        if (node == null) {
            return false;
        }
        place(nodeId, node, x, y, z);
        return true;
    }

    /**
     * Records the state a run of edits is about to change, once, for a drag or a road drawn point by
     * point.
     */
    public void beginHistory() {
        pushUndo();
    }

    private void place(int nodeId, RoadNode node, int x, int y, int z) {
        node.moveTo(x, y, z);
        for (RoadSegment segment : network.segments()) {
            if (segment.fromNode() == nodeId && segment.vertexCount() > 0) {
                segment.moveVertex(0, x, z);
            }
            if (segment.toNode() == nodeId && segment.vertexCount() > 0) {
                segment.moveVertex(segment.vertexCount() - 1, x, z);
            }
        }
        network.touch();
    }

    /**
     * A split's two halves and the node between them: the first from the original's from-node to the
     * junction, the second from the junction to its to-node.
     */
    public record Split(int firstSegment, int secondSegment, int junctionNode) {
    }

    /**
     * Splits a segment at vertexIndex, returning the new junction node's id, or -1. Topological, not
     * merely geometric: two segments meet at a new node, or the graph would stay disconnected while the
     * map looked joined.
     */
    public int splitSegment(int segmentId, int vertexIndex, int x, int y, int z) {
        Split split = splitSegmentInto(segmentId, vertexIndex, x, y, z);
        return split == null ? -1 : split.junctionNode();
    }

    /**
     * The same split, handing back both halves as well as the junction. Applying points from the far end
     * inwards always lands on firstSegment, which keeps the original's vertex numbering valid for later
     * splits.
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
        // A split is a topology edit, not a statement about the road: both halves keep the state the
        // whole segment had, or a street shut for repairs would reopen the moment the router anchored
        // a trip on it, and a numbered road would lose its class and its sign. The inheritance is
        // quiet -- it does not move the one-way, closure or storey counters -- because nothing about
        // the road changed; counting it would make every anchored plan look like an edit to the map.
        first.inheritState(original);

        RoadSegment second = network.newSegment(original.roadClass(), y,
                original.vertexCount() - vertexIndex + 1);
        second.addVertex(x, z);
        for (int i = vertexIndex; i < original.vertexCount(); i++) {
            second.addVertex(original.x(i), original.z(i));
        }
        second.setFromNode(junction.id());
        second.setToNode(original.toNode());
        // The same on the far half, for the same reason: a split is the road broken into two pieces,
        // never a change to what the road is.
        second.inheritState(original);

        network.removeSegment(segmentId);
        network.addSegment(first);
        network.addSegment(second);

        if (selectedSegmentId == segmentId) {
            selectedSegmentId = first.id();
        }
        if (undoable) {
            // Derived state only the player's editing needs; a routing copy has one new junction node and nobody
            // is looking.
            reclassifyNodes();
        }
        return new Split(first.id(), second.id(), junction.id());
    }

    /**
     * Deletes the current selection, removing any segments left dangling.
     * A selected road goes whole: every other road-wide operation already walks the chain, and deleting
     * was the one that did not. The selected segments are exactly what goes -- a double-clicked piece is
     * cut out, not the street. A selection naming no segment still deletes the whole road of the main
     * segment, and a selected node takes its segments with it.
     */
    public boolean deleteSelection() {
        if (selectedSegmentId != RoadSegment.NO_SEGMENT || !selectedChain.isEmpty()) {
            pushUndo();
            java.util.Set<Integer> doomed = new java.util.LinkedHashSet<>(selectedChain);
            if (doomed.isEmpty()) {
                java.util.List<Integer> road = RoadChains.chainContaining(network, selectedSegmentId);
                if (road.isEmpty()) {
                    doomed.add(selectedSegmentId);
                } else {
                    doomed.addAll(road);
                }
            }
            for (int segmentId : doomed) {
                network.removeSegment(segmentId);
            }
            selectedSegmentId = RoadSegment.NO_SEGMENT;
            selectedChain.clear();
            pruneOrphanNodes();
            reclassifyNodes();
            return true;
        }
        if (selectedNodeId != RoadSegment.NO_NODE) {
            pushUndo();
            int nodeId = selectedNodeId;

            // Collect first, then remove: RoadNetwork.segments() is a live view, so removing inside the loop
            // would throw.
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
     * Removes nodes that no longer belong to any segment. Places are exempt and must stay so: a
     * hand-placed place is a node with no segment on purpose, and without the guard, finishing a road
     * deleted every place in the network. reclassifyNodes skips places the same way, and a third caller
     * would need the same guard.
     */
    public void pruneOrphanNodes() {
        Map<Integer, Integer> usage = segmentDegrees();
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
     * Turns a station that has lost its road into an ordinary place. Its position and name are the
     * player's work, so it is demoted rather than removed; only the kind changes. The client's half of
     * the rule is asked too: a station beside a railway is on the railway, which is not in the saved
     * network, so without stationOverride every pass demoted a station that had never moved.
     */
    private void demoteOrphanStations(Map<Integer, Integer> degree) {
        for (RoadNode node : network.nodesSnapshot()) {
            if (node.placeKind() != PlaceKind.STATION) {
                continue;
            }
            if (degree.getOrDefault(node.id(), 0) > 0) {
                continue;
            }
            if (stationOverride != null && stationOverride.test(node.id())) {
                continue;
            }
            node.setPlaceKind(PlaceKind.PLACE);
        }
    }

    /**
     * Recomputes node types from how many segments touch them, and demotes a station that has lost its
     * road. Every deletion path ends here, so this is the one reconciliation pass.
     */
    public void reclassifyNodes() {
        Map<Integer, Integer> degree = segmentDegrees();
        demoteOrphanStations(degree);
        for (RoadNode node : network.nodes()) {
            if (node.type() == RoadNode.Type.POI) {
                continue;
            }
            int d = degree.getOrDefault(node.id(), 0);
            node.setType(d >= 3 ? RoadNode.Type.JUNCTION : RoadNode.Type.ENDPOINT);
        }
    }


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

    /**
     * Drops selection ids that no longer exist after an undo/redo swapped the network out. The selection
     * is kept as a set so a multi-selection survives, but ids the new network lacks go, and the main
     * selection moves to a remaining one.
     */
    private void clampSelection() {
        if (network.node(selectedNodeId) == null) {
            selectedNodeId = RoadSegment.NO_NODE;
        }
        selectedChain.removeIf(segmentId -> network.segment(segmentId) == null);
        if (selectedSegmentId != RoadSegment.NO_SEGMENT
                && network.segment(selectedSegmentId) == null) {
            selectedSegmentId = selectedChain.isEmpty()
                    ? RoadSegment.NO_SEGMENT
                    : selectedChain.iterator().next();
        }
        if (selectedSegmentId == RoadSegment.NO_SEGMENT && !selectedChain.isEmpty()) {
            selectedSegmentId = selectedChain.iterator().next();
        }
        if (network.node(chainNodeId) == null) {
            chainNodeId = RoadSegment.NO_NODE;
        }
    }
}
