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

    /**
     * The storey a newly drawn road goes on.
     *
     * <p>Carried on the editor rather than asked for per road, for the same reason the drawing class
     * is: a tunnel is a dozen pieces of road, and setting the storey on each of them as it is laid
     * would be a thing to remember rather than a way to draw. Setting it on a road the player already
     * has moves that road, and *also* becomes the storey of what is drawn next -- so the gesture is
     * "put this road on the second basement level", then carry on drawing there.
     */
    private int activeLayer;

    public int activeLayer() {
        return activeLayer;
    }

    /** The storey new roads are drawn on, clamped to what a road may be. */
    public void setActiveLayer(int layer) {
        this.activeLayer = Math.max(RoadSegment.MIN_LAYER, Math.min(RoadSegment.MAX_LAYER, layer));
    }

    /**
     * Moves every selected road onto the given storey, and makes that the storey new roads go on.
     *
     * <p>Whole roads rather than the pieces the cursor happened to be over, for the reason
     * {@link #setSegmentClass} gives: a street that bends is several pieces, and a storey that
     * changed halfway along one of them would be a bridge whose far end is on the ground. With
     * several roads selected it moves all of them, in one undo step -- the gesture was one press, and
     * having to step back once per road would be a lie about how many changes were made.
     *
     * <p>The drawing storey follows it, which is what makes "make this the tunnel, then keep drawing
     * the tunnel" one gesture. A selection that names no segment still has the road of
     * {@code segmentId} moved, which is the single-road behaviour this method has always had.
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
        // Storeys decide which joins exist, so the graph built from them is stale now -- see
        // RoadSegment.layerChanges, which is what tells the router's caches.
        return true;
    }

    /**
     * How long the whole road the given segment belongs to is, in blocks.
     *
     * <p>The chain, not the piece: a road drawn with bends is several segments, and "how long is this
     * road" is a question about the road. It is also the same thing the map highlights when the segment
     * is selected, so what the readout says and what the player sees lit up cannot disagree.
     *
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
     * How long everything selected is, in blocks: the sum of the roads the selection touches.
     *
     * <h2>Why whole roads and not the selected pieces</h2>
     * Every bulk operation on a selection works on whole roads -- class, storey, name and the one-way
     * marking all walk each selected segment's chain, because a street cannot be half one thing and
     * half another. A readout that measured only the highlighted pieces would then disagree with
     * every edit the same selection makes, and the number under the caption would be describing a
     * thing the player cannot actually change on its own. So a selection that is one segment of a
     * three-piece street reads the whole street, which is also what {@link #chainLength} has always
     * said about a selected road.
     *
     * <p>Two segments of one road count it once: the walk is deduplicated by the road, so a selection
     * assembled from several segments of the same street does not make it longer. A selection that
     * names no segment at all is zero rather than an exception, which is what the readout asks of an
     * empty selection every frame.
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
            // Every segment of the road counts, and none of it is walked a second time: the members
            // are marked seen before the next seed is asked about.
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
     * The storey of the main selected segment, or the surface when nothing is selected.
     *
     * <p>One number for a selection that may span several storeys, because the readout has room for
     * one and the alternative -- a word for "mixed" -- would be a readout that says nothing about
     * either road. The main segment is the last one the player touched, which is the one the caption's
     * class and one-way state are read from as well; a road is moved to a storey as a whole (see
     * {@link #setChainLayer}), so the number is the road's and not an accident of which piece was
     * clicked.
     */
    public int selectedLayer() {
        RoadSegment segment = network.segment(selectedSegmentId);
        return segment != null ? segment.layer() : 0;
    }

    /**
     * Re-classes every selected road, leaving the drawing class alone.
     *
     * <p>A road, and not a piece of one: "which pieces are one road" is a question about their class
     * (see {@link RoadChains}), so re-classing one segment of a street would cut the street in two at
     * that point and give the map a road that changes name at a bend. Each selected segment is
     * therefore expanded to its road and the whole road is re-classed, which also keeps the
     * selection's own grouping stable -- two selected segments of one street cannot end up as two
     * roads of different classes. With several roads selected it changes all of them in one undo
     * step.
     *
     * <p>A selection that names no segment re-classes the road of {@code segmentId}, which is the
     * single-road behaviour this method has always had.
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
        // A class is not geometry, but it is not invisible to the caches either: "one road" is a
        // grouping of pieces of the same class, so re-classing a road changes which pieces belong
        // together, and the router builds its graph by class. Without this the map kept labelling the
        // road as it used to be and -- worse -- the graph kept serving the old class, so a footpath
        // turned into a road was still not drivable for the rest of the session.
        network.touch();
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
     * Turns every selected road one step further round the one-way states, each from its own state.
     *
     * <h2>Why each road advances from itself</h2>
     * The switch on the road under the cursor goes two-way, along, against, two-way; a bulk switch
     * that took the state of the main segment and wrote it onto every other selected road would not
     * be a switch at all. A road that was already one-way against the walk would be flipped to the
     * other way rather than moved on to two-way, so pressing the key twice would not take a mixed
     * selection anywhere in particular. Advancing each road from where it actually is means the
     * fourth press brings every selected road back to two-way however mixed they started, which is
     * the same promise the single-road switch makes.
     *
     * <p>Each road is still oriented along its own walk -- see {@link #setChainDirection} for why the
     * stored direction of a piece is not the direction of the street -- and the whole thing is one
     * undo step.
     *
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

    /**
     * Writes one direction onto one whole chain, orienting every piece along the chain's own walk.
     *
     * <p>The body {@link #setChainDirection} always had, factored out so that a bulk edit can apply
     * the same rule to each of several roads without copying it.
     */
    private void applyChainDirection(java.util.List<Integer> chain, RoadDirection direction) {
        // The node the walk starts from: the end of the first segment that the second one is not attached
        // to. A chain of one segment has no second one, and then the segment's own from-node is the start
        // -- so FORWARD on a single piece of road is the direction it was drawn in, which is the answer
        // that needs no explaining to the player who drew it.
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
                // The chain index and the geometry disagree, which a hand-edited or half-repaired file
                // can produce. The segment is left alone rather than given a direction that means
                // nothing about it.
                continue;
            }
            segment.setDirection(along ? direction : direction.reversed());
            node = along ? segment.toNode() : segment.fromNode();
        }
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

    /**
     * How far apart, in blocks vertically, two places put down on the same block may be and still be
     * the same place.
     *
     * <p>A place is created at the height the player's feet were at, which is not the height of the
     * ground under the cursor, so two presses on one spot -- before and after a step, a jump or a
     * small fall -- can differ by a couple of blocks. The horizontal position is exact because that
     * is what the player pointed at; only the height is rounded in this generous a way.
     */
    private static final int SAME_PLACE_HEIGHT = 2;

    /**
     * What {@link #placePoi} did: the place's node, and whether the press created it.
     *
     * <h2>Why the caller needs to know which it was</h2>
     * A press on an empty block creates a place, opens the editor for it, and the player may then
     * cancel -- in which case there must be nothing left behind. A press on a block that already
     * holds a place only selects it, and cancelling the editor must leave that place exactly as it
     * was. The two presses look the same from outside, so the fact that separates them (this press
     * created the node, or found one) has to come back with the id; a caller that guessed from the
     * network afterwards cannot tell a just-created place from one that was always there.
     *
     * @param nodeId  the place's node, created or found
     * @param created whether this call added it to the network, and therefore whether cancelling the
     *                edit has anything to take back
     */
    public record PoiPlacement(int nodeId, boolean created) {
    }

    /**
     * Creates a point of interest and selects it.
     *
     * <p>A second press on the same block selects the place that is already there rather than putting
     * another one on top of it. Two places at one block are indistinguishable on the map and in the
     * destination list, and only one of them can be the one a player renames to a station: with two,
     * the edit lands on one node while the line editor keeps searching the other, which is exactly
     * the "my place is not on the network however I set it" the duplicate caused. Merging on the way
     * in means a save can never grow a second place where one already stands.
     *
     * <p>The existing place is kept and the press is treated as selecting it, so a double press is a
     * no-op: no new undo step, no second record in the place-details store, nothing to clean up.
     *
     * <p>See {@link PoiPlacement} for why the answer says whether a node was made rather than only
     * naming it.
     */
    public PoiPlacement placePoi(int x, int y, int z) {
        for (RoadNode existing : network.nodesSnapshot()) {
            if (existing.type() == RoadNode.Type.POI
                    && existing.x() == x
                    && existing.z() == z
                    && Math.abs(existing.y() - y) <= SAME_PLACE_HEIGHT) {
                selectedNodeId = existing.id();
                selectedSegmentId = RoadSegment.NO_SEGMENT;
                // A node selection replaces the road selection rather than sitting beside it: the two
                // are one selection, and leaving the roads in the set would have the map highlight
                // them for a Delete that is about to remove the place.
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

    /**
     * The same, keeping the older call shape for callers that do not need the created flag.
     *
     * <p>The self-checks and any addon code written against the previous signature use this; the
     * editor itself asks {@link #placePoi} because it has to know whether cancelling has something
     * to take back.
     */
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
     * Names every selected road, and the road of {@code segmentId} when nothing is selected.
     *
     * <p>Naming a single segment would leave a road with bends called one thing for one straight
     * piece and something else for the next, so each selected segment's road is named whole. Several
     * selected roads get the same name -- which is what the panel's one field says, and what a player
     * who selected three roads and pressed N asked for -- in one undo step, so taking it back is one
     * press rather than one per road.
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
     * Names every selected road and puts it on a storey, as one edit.
     *
     * <p>One undo step rather than two, because it is one visit to one panel: a player who named a road
     * and set its storey and then pressed undo twice to get back where they were would be undoing two
     * halves of a single answer. See {@link #setChainLayer} for what the storey decides.
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
        // The storey just given is the one the next road drawn goes on, which is what makes a tunnel
        // one visit to this panel rather than one per piece.
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
        // Drawing selects the point being drawn, which is a node selection: the roads selected a
        // moment ago are not what Delete or N would act on now, and leaving them highlighted would
        // say they were.
        selectedChain.clear();
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

    /**
     * Selects the whole road the given segment belongs to, and only that road.
     *
     * <p>A road drawn with bends is several segments, and this is the gesture "select this road" --
     * the whole chain is what the map highlights and what every road-wide operation then acts on.
     * The multi-select gestures are {@link #toggleChainSelection} and
     * {@link #toggleSingleSegmentSelection}; this one replaces whatever was selected before, which is
     * what a plain Shift+left click means.
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
     * Selects one segment of a road and nothing else.
     *
     * <h2>Why the whole-road selection is still the default</h2>
     * A road with bends is several segments, and "this road" is almost always what a player means by
     * pointing at one: naming, re-classing and deleting it as a single straight piece is the mistake
     * this model was built to avoid. The segment-level selection exists for the one case where the
     * piece really is the subject -- trimming or re-routing a single straight stretch of a street --
     * and it is reached by double-clicking, so it cannot be arrived at by accident.
     *
     * <p>The main selection is the segment itself, so the readout and the road-wide operations still
     * have one segment to speak from; see {@link #selectedLength} for why those operations stay
     * road-wide even when the selection is not.
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
     * Adds the whole road the given segment belongs to, or takes it back out.
     *
     * <h2>Why the whole road is the unit</h2>
     * The multi-select gesture exists so that several roads can be re-classed, renamed or deleted in
     * one go, and all of those are road-wide operations: adding one piece of a bent street to the
     * selection would put a road into it that only half exists as far as the operations can tell. A
     * road that is only partly in the set is completed rather than removed -- the first press on a
     * half-selected road means "add this road", and only a press when the whole road is in the set
     * takes it out.
     *
     * <p>The main selection becomes the road just added. When a road is taken out and it was the
     * main one, the main selection moves to whatever is left, because every readout and the naming
     * panel need one segment to speak from; when nothing is left it is
     * {@link RoadSegment#NO_SEGMENT}.
     *
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
     * Adds one segment to the selection, or takes it back out, leaving the rest of its road alone.
     *
     * <p>The segment-level half of the multi-select gesture, reached by double-clicking: for putting
     * one straight piece of a street into a selection without the rest of the street. The main
     * selection follows the same rule as {@link #toggleChainSelection}.
     */
    public boolean toggleSingleSegmentSelection(int segmentId) {
        if (network.segment(segmentId) == null) {
            return false;
        }
        return toggleSelection(segmentId, java.util.List.of(segmentId));
    }

    /**
     * Adds a set of segments to the selection, or removes it if all of them are already in.
     *
     * <p>Clearing the node selection is deliberate: the selection is one thing at a time, and the
     * multi-select gestures are about roads. Leaving a selected place behind would have Delete ask
     * which of the two the player meant.
     */
    private boolean toggleSelection(int clickedSegmentId, java.util.List<Integer> members) {
        selectedNodeId = RoadSegment.NO_NODE;
        chainNodeId = RoadSegment.NO_NODE;
        if (selectedChain.containsAll(members)) {
            selectedChain.removeAll(members);
            if (selectedChain.isEmpty()) {
                selectedSegmentId = RoadSegment.NO_SEGMENT;
            } else if (!selectedChain.contains(selectedSegmentId)) {
                // The main segment was in what came out. Any remaining one will do: the group is
                // selected as a group, and a readout that changed its mind about which member it
                // speaks for would be showing a road that is no longer in the selection.
                selectedSegmentId = selectedChain.iterator().next();
            }
        } else {
            selectedChain.addAll(members);
            selectedSegmentId = clickedSegmentId;
        }
        return true;
    }

    /**
     * A copy of the segment selection, for a gesture that has to take one of its own steps back.
     *
     * <p>The multi-select click has an ambiguity no ordering of events can avoid: the first press of
     * a double click is indistinguishable from a single one, so its effect has to be applied before
     * the second press can say what the gesture was. The client therefore remembers the selection the
     * first click replaced and hands it back here when the second click turns out to be a double, so
     * the double can select its one segment rather than leaving the first click's whole road
     * selected as well. Kept as a plain value so the client can hold it without reaching into the
     * editor's fields, and so the restore can be reasoned about (and tested) on its own.
     */
    public record SelectionSnapshot(java.util.List<Integer> segments, int primary) {
    }

    /** The selection as it stands, for {@link #restoreSelection}. */
    public SelectionSnapshot snapshotSelection() {
        return new SelectionSnapshot(new java.util.ArrayList<>(selectedChain), selectedSegmentId);
    }

    /**
     * Puts the segment selection back to a snapshot.
     *
     * <p>Ids that no longer name a segment are dropped rather than kept: the snapshot outlives the
     * click that took it by a few hundred milliseconds, and a deletion in between must not leave the
     * selection holding ids the map would have to ask a stale question about. If the main segment has
     * gone for the same reason, the first remaining one becomes the main selection.
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
     * The roads a bulk edit acts on: every road touched by the selection, plus the road of the given
     * segment.
     *
     * <p>Every road-wide edit has to answer the same question -- which roads is this press about --
     * and answering it here, once, is what keeps the class, storey, name and one-way edits from
     * drifting apart. The segment argument is always included: the caller may be acting on the road
     * under the cursor while nothing is selected, which is how every one of these operations behaved
     * before multi-select existed, and a selection must not make that road un-editable.
     *
     * <p>The walk is deduplicated by road rather than by segment: a selection made of several
     * segments of one street is one road to edit, and calling {@code chainContaining} for each of them
     * would build the adjacency index as many times as the street has pieces.
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
        place(nodeId, node, x, y, z);
        return true;
    }

    /**
     * Moves a node without recording it, for a drag already in progress.
     *
     * <p>A drag is one edit, not one per frame. Recording each frame put sixty snapshots a second on
     * a stack that holds sixty-four of them, so a second of dragging on a handle threw away every
     * edit the player had made before it -- roads, names, places, one-way markings -- and left them
     * with a history that could only step back through the pixels of the drag they had just done. The
     * caller records the state once, with {@link #beginHistory}, and then moves the handle.
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
     * Records the state a run of edits is about to change, once.
     *
     * <p>For anything that edits in steps -- a drag, or a road being drawn point by point -- where the
     * unit the player would undo is the whole gesture rather than each step of it.
     */
    public void beginHistory() {
        pushUndo();
    }

    /** The geometry of a move: the node, every segment endpoint on it, and the network's revision. */
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
        // The vertices were written through the segments, which the network cannot see.
        network.touch();
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
        first.setLayer(original.layer());

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
        second.setLayer(original.layer());

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

    /**
     * Deletes the current selection, removing any segments left dangling.
     *
     * <h2>Why a selected road goes whole</h2>
     * Selecting a road selects all of it: {@link #selectSegment} takes the chain, the map highlights
     * the chain, and every other thing that can be done to a selected road -- its name, its class,
     * its storey, its one-way marking -- already walks the chain. Deleting was the one operation that
     * did not, so pressing delete removed the single straight piece the cursor happened to be over
     * and left the rest of a road that was still being shown as selected. A road drawn with bends is
     * several segments meeting at pass-through nodes, which is what makes it one road rather than
     * several, so that is what "delete this road" has to mean.
     *
     * <h2>Why it deletes exactly the selected segments</h2>
     * The selection is the list of what goes, and that is the one place the segment-level selection
     * means what it says: a player who double-clicked one straight piece of a street and pressed
     * delete asked for that piece to be cut out, not for the street to vanish -- and if they wanted
     * the street they had selected it with a single click. The multi-selection works the same way:
     * every road and every single segment in it goes, nothing more.
     *
     * <p>A selection that names no segment set at all -- the state before multi-select existed --
     * still deletes the whole road of the main segment, so the old behaviour is untouched.
     *
     * <p>A node is still deleted as the node it is: what a node has is the segments that end on it,
     * and those go with it.
     */
    public boolean deleteSelection() {
        if (selectedSegmentId != RoadSegment.NO_SEGMENT || !selectedChain.isEmpty()) {
            pushUndo();
            // Read before anything is removed: the walk answers out of the network, so it cannot be
            // asked once pieces of the road are gone. The chain always holds the seed, so the empty
            // answer is only reachable for a selection that names no segment at all.
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
            // The ids just removed are not selection any more; leaving them in the chain would have
            // the map ask a stale question of every segment it draws.
            selectedChain.clear();
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

    /**
     * Drops selection ids that no longer exist after an undo/redo swapped the network out.
     *
     * <p>The selection is kept as a set of segments rather than rebuilt from the main segment, so a
     * multi-selection survives an undo as the roads it named rather than collapsing to one road's
     * chain. What cannot survive is a segment the swapped-in network does not have -- undo across a
     * deletion removes the ids the deletion took -- so those go, and the main selection moves to a
     * remaining one if it was among them.
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
