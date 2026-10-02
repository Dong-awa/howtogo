package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.HowToGo;
import bili.dongsz.howtogo.road.PlaceKind;
import bili.dongsz.howtogo.road.RoadClass;
import bili.dongsz.howtogo.road.RoadEditor;
import bili.dongsz.howtogo.road.RoadNetwork;
import bili.dongsz.howtogo.road.RoadNode;
import bili.dongsz.howtogo.road.RoadSegment;
import bili.dongsz.howtogo.route.Destination;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * Client-side state for the road editing mode: whether it is on, where the cursor is, and what the
 * current snap resolved to.
 *
 * <p>Kept separate from {@link RoadEditor} so the data operations stay free of viewport concerns
 * and remain testable without a running client.
 */
public final class RoadEditSession {

    private static boolean active;
    private static RoadEditor editor;
    private static RoadNetwork boundNetwork;

    private static RoadSnapper.Result lastSnap = RoadSnapper.Result.free(0, 0);
    private static double mouseWorldX;
    private static double mouseWorldZ;
    private static boolean mouseValid;

    /** Node currently being dragged, or {@link RoadSegment#NO_NODE}. */
    private static int draggingNodeId = RoadSegment.NO_NODE;

    /** True while the player holds the modifier that disables snapping. */
    private static boolean freePlacementActive;

    /** Work deferred out of an input handler to the next client tick. */
    private static Runnable pendingAction;

    private RoadEditSession() {
    }

    public static boolean isActive() {
        return active;
    }

    /** The editor for the current level, creating it on first use. */
    public static RoadEditor editor() {
        RoadNetwork network = RoadStore.get();
        if (editor == null || boundNetwork != network) {
            editor = new RoadEditor(network);
            boundNetwork = network;
        }
        return editor;
    }

    public static void toggle() {
        active = !active;
        if (!active) {
            reset();
        } else {
            editor().finishChain();
        }
    }

    public static void setActive(boolean value) {
        active = value;
        if (!active) {
            reset();
        }
    }

    private static void reset() {
        lastSnap = RoadSnapper.Result.free(0, 0);
        mouseValid = false;
        draggingNodeId = RoadSegment.NO_NODE;
        if (editor != null) {
            editor.finishChain();
            editor.clearSelection();
        }
    }

    public static RoadSnapper.Result lastSnap() {
        return lastSnap;
    }

    public static double mouseWorldX() {
        return mouseWorldX;
    }

    public static double mouseWorldZ() {
        return mouseWorldZ;
    }

    /** Cursor X in GUI-scaled screen coordinates. */
    public static double mouseScreenX() {
        return MapViewState.toScreenX(mouseWorldX);
    }

    /** Cursor Y in GUI-scaled screen coordinates. */
    public static double mouseScreenZ() {
        return MapViewState.toScreenZ(mouseWorldZ);
    }

    public static boolean isMouseValid() {
        return mouseValid;
    }

    public static int draggingNodeId() {
        return draggingNodeId;
    }

    public static RoadClass activeClass() {
        return editor().activeClass();
    }

    /**
     * The class {@code [} and {@code ]} act on: the selected segment if there is one, otherwise
     * the class new roads will be drawn with.
     */
    public static RoadClass cycleTarget() {
        RoadEditor ed = editor();
        if (ed.selectedSegmentId() != RoadSegment.NO_SEGMENT) {
            RoadSegment segment = RoadStore.get().segment(ed.selectedSegmentId());
            if (segment != null) {
                return segment.roadClass();
            }
        }
        return ed.activeClass();
    }

    public static void cycleActiveClass(int delta) {
        RoadClass[] values = RoadClass.values();
        int index = (cycleTarget().ordinal() + delta) % values.length;
        if (index < 0) {
            index += values.length;
        }
        RoadClass next = values[index];

        RoadEditor ed = editor();
        if (ed.selectedSegmentId() != RoadSegment.NO_SEGMENT
                && ed.setSegmentClass(ed.selectedSegmentId(), next)) {
            RoadStore.markDirty();
        } else {
            ed.setActiveClass(next);
        }
    }

    /**
     * Records the raw cursor position. Cheap, and called for every rendered element so that cursor
     * dependent actions work even when the editor is closed.
     */
    public static void setMouse(double rawWorldX, double rawWorldZ) {
        mouseWorldX = rawWorldX;
        mouseWorldZ = rawWorldZ;
        mouseValid = true;
    }

    /**
     * Recomputes the snapped cursor position.
     *
     * <p>Kept separate from {@link #setMouse} because snapping is a linear scan over every node and
     * segment; running it per element rather than once per frame would make it quadratic.
     *
     * @param freePlacement when true all snapping is bypassed, so a point can be placed exactly
     *                      where the cursor is. Bound to Alt.
     */
    public static void updateMouse(double rawWorldX, double rawWorldZ, boolean freePlacement) {
        setMouse(rawWorldX, rawWorldZ);
        freePlacementActive = freePlacement;
        if (freePlacement) {
            lastSnap = RoadSnapper.Result.free(rawWorldX, rawWorldZ);
            return;
        }
        RoadSnapper.Result snap = RoadSnapper.snap(RoadStore.get(), rawWorldX, rawWorldZ,
                editor().chainNodeId());
        if (snap.kind() == RoadSnapper.Kind.FREE) {
            // Nothing hand-drawn is close enough. Create's tracks are the other geometry on the map,
            // and a road is very often meant to start or end on one, so they are offered to the snap
            // as well -- but only as a position: a rail cannot be split or joined, because it is a
            // reading of the world rather than something the editor owns.
            RoadSnapper.Result rail = railPositionSnap(rawWorldX, rawWorldZ);
            if (rail != null) {
                snap = rail;
            }
        }
        lastSnap = snap;
    }

    /**
     * The nearest rail to a point, as a position-only snap, or null when none is close enough.
     *
     * <p>The catch radii are the same ones the editor uses on its own roads, because the tolerance
     * belongs to the cursor rather than to what is being pointed at.
     */
    private static RoadSnapper.Result railPositionSnap(double worldX, double worldZ) {
        RoadNetwork layer = RailTrackStore.network();
        if (layer.segmentCount() == 0) {
            return null;
        }
        RoadSnapper.Result hit = RoadSnapper.snap(layer, worldX, worldZ, RoadSegment.NO_NODE);
        if (hit.kind() != RoadSnapper.Kind.SEGMENT && hit.kind() != RoadSnapper.Kind.NODE) {
            return null;
        }
        return new RoadSnapper.Result(hit.x(), hit.z(), RoadSnapper.Kind.RAIL,
                RoadSegment.NO_NODE, RoadSegment.NO_SEGMENT, -1);
    }

    public static boolean isFreePlacementActive() {
        return freePlacementActive;
    }

    // ------------------------------------------------------------- naming

    /**
     * Asks for a name for whatever carries one under the cursor, or for what is selected.
     *
     * <h2>What N can name, in the order it is tried</h2>
     * An explicit selection first, then what the cursor is over: a road, a place, or a railway. The
     * order matters and is the same one the snap itself uses -- a selection is a deliberate act and
     * outranks a cursor that may have drifted, and a node outranks the segment running through it,
     * because a place sitting on a road is otherwise impossible to name.
     *
     * <p>A plain road vertex has no name of its own -- names belong to the whole road -- so pointing
     * at one falls through to the road through it, and a node that is not a place falls through to
     * the railway search.
     */
    public static void nameSelectedRoad() {
        RoadEditor ed = editor();

        // 1. A selected road.
        if (ed.selectedSegmentId() != RoadSegment.NO_SEGMENT) {
            promptRoadName(ed.selectedSegmentId());
            return;
        }
        // 2. A selected place.
        if (isPlaceNode(ed.selectedNodeId())) {
            promptPlaceEditor(ed.selectedNodeId());
            return;
        }
        // 3. A place under the cursor. Only an existing place, not any vertex: merely pointing at a
        //    road's corner and pressing N still means "name this road".
        if (lastSnap.kind() == RoadSnapper.Kind.NODE && isPlaceNode(lastSnap.nodeId())) {
            ed.selectNode(lastSnap.nodeId());
            promptPlaceEditor(lastSnap.nodeId());
            return;
        }
        // 4. A road under the cursor.
        if (lastSnap.kind() == RoadSnapper.Kind.SEGMENT
                && lastSnap.segmentId() != RoadSegment.NO_SEGMENT) {
            ed.selectSegment(lastSnap.segmentId());
            promptRoadName(lastSnap.segmentId());
            return;
        }
        // 5. A selected railway, so N names what was selected rather than what the cursor drifted
        //    over since.
        String selectedRail = RailNameStore.selectedSeed();
        if (selectedRail != null) {
            promptRailName(selectedRail, RailNameStore.nameOfKey(selectedRail));
            return;
        }
        // 6. A railway under the cursor. The rail layer is the other thing on the map that carries a
        //    name, and it is what a player is usually pointing at when they want to label a line they
        //    did not draw themselves.
        nameRailUnderCursor();
    }

    /** Opens the naming prompt for a road, addressed by segment. */
    private static void promptRoadName(int segmentId) {
        RoadSegment segment = RoadStore.get().segment(segmentId);
        if (segment == null) {
            return;
        }
        promptName(RoadNameScreen.TITLE_ROAD, segment.name(), mouseScreenX(), mouseScreenZ(),
                name -> editor().setRoadName(segmentId, name));
    }

    /**
     * Opens the place editor for a hand-placed place.
     *
     * <p>Name and kind together, rather than the rename prompt this used to be: a place's kind is as
     * much a part of it as its name, and asking for one without the other meant the kind could only
     * ever be set at the moment of creation. Everything is read inside the deferred action, because
     * the editor opens a tick later and the place may have moved or gone by then.
     */
    private static void promptPlaceEditor(int nodeId) {
        RoadEditor places = editor();
        if (RoadStore.get().node(nodeId) == null) {
            return;
        }
        // The station rule has two halves and this class can only see one of them: the editor knows the
        // saved roads, and the client knows the railway layer, which is not in the saved file. The
        // missing half is handed to the editor rather than checked alongside it, so that the screen and
        // the save ask the same question and cannot disagree.
        places.setStationOverride(RoadEditSession::nearTrack);
        double anchorX = mouseScreenX();
        double anchorY = mouseScreenZ();
        pendingAction = () -> {
            RoadNode node = RoadStore.get().node(nodeId);
            if (node == null) {
                return;
            }
            Screen parent = Minecraft.getInstance().screen;
            Minecraft.getInstance().setScreen(new RoadNameScreen(parent,
                    RoadNameScreen.TITLE_PLACE_EDIT, node.name(), node.placeKind(),
                    // The station rule is asked here, where the node is known, so the screen can keep
                    // itself open and say why rather than closing on a change that was refused.
                    kind -> kind != PlaceKind.STATION || places.stationAllowed(nodeId),
                    anchorX, anchorY,
                    (name, kind) -> {
                        places.setPlace(nodeId, name, kind);
                        RoadStore.markDirty();
                    }));
        };
    }

    /** Opens the naming prompt for a railway, addressed by shape key. */
    private static void promptRailName(String key, String initial) {
        promptName(RoadNameScreen.TITLE_RAIL, initial, mouseScreenX(), mouseScreenZ(),
                name -> RailNameStore.rename(RailTrackStore.network(), key, name));
    }

    /** Whether a node id is one of the hand-placed places, which is the only kind that is named. */
    private static boolean isPlaceNode(int nodeId) {
        if (nodeId == RoadSegment.NO_NODE) {
            return false;
        }
        RoadNode node = RoadStore.get().node(nodeId);
        return node != null && node.type() == RoadNode.Type.POI;
    }

    /**
     * Names the automatic railway under the cursor, if there is one.
     *
     * <p>Only the name is offered. The rest of the editor -- moving a node, splitting a segment,
     * deleting one, changing its class -- is not, because the layer is a reading of the world rather
     * than a drawing: Create owns that geometry, and anything edited here would be replaced by the
     * next reading a second later.
     *
     * @return whether a railway was found and the name prompt opened
     */
    private static boolean nameRailUnderCursor() {
        RoadSegment rail = railUnderCursor();
        if (rail == null) {
            return false;
        }
        // The key, not the id: the prompt is answered a tick later and the layer rebuilds on its own
        // schedule, so by then an id may belong to a different segment. The key is a property of the
        // track and is looked up again in whatever the layer holds at that point.
        String key = RailNameStore.keyOf(rail);
        RailNameStore.select(RailTrackStore.network(), rail);
        promptRailName(key, rail.name());
        return true;
    }

    /**
     * The rail segment under the cursor, or null.
     *
     * <p>Snapped with the same code the editor uses on hand-drawn roads, pointed at the rail layer
     * instead, so a rail is picked with the same tolerance in screen pixels: the tolerance is a
     * property of the cursor, not of what is being pointed at.
     */
    private static RoadSegment railUnderCursor() {
        RoadNetwork layer = RailTrackStore.network();
        RoadSnapper.Result hit = layer.segmentCount() == 0 || !mouseValid
                ? RoadSnapper.Result.free(0, 0)
                : RoadSnapper.snap(layer, mouseWorldX, mouseWorldZ, RoadSegment.NO_NODE);
        RoadSegment found = null;
        if (mouseValid && layer.segmentCount() > 0) {
            if (hit.kind() == RoadSnapper.Kind.SEGMENT) {
                found = layer.segment(hit.segmentId());
            } else if (hit.kind() == RoadSnapper.Kind.NODE) {
                // A node of the layer: a railway begins, ends or branches here. Any of the segments
                // meeting there will do, because a name covers the whole chain the segment belongs to.
                for (RoadSegment segment : layer.segmentsSnapshot()) {
                    if (segment.fromNode() == hit.nodeId() || segment.toNode() == hit.nodeId()) {
                        found = segment;
                        break;
                    }
                }
            }
        }
        return found;
    }

    /** Drops a named place at the cursor. */
    public static void placePoi() {
        if (!mouseValid) {
            return;
        }
        int x = (int) Math.round(lastSnap.x());
        int z = (int) Math.round(lastSnap.z());
        int nodeId = editor().addPoi(x, groundHeight(), z);
        RoadStore.markDirty();
        // The same prompt an existing place is renamed through, so a place made now and one renamed
        // later cannot end up with different titles or different storage.
        promptPlaceEditor(nodeId);
    }

    /**
     * Opens the naming prompt on the next client tick.
     *
     * <p>The keys now arrive from {@code ScreenEvent.KeyPressed.Pre}, which runs inside the keyboard
     * handler before the screen is offered the key, and the editor cancels the key it uses. Opening
     * the prompt from there would probably be safe for that reason -- but the tick of delay costs
     * nothing, keeps the input dispatch free of screen swaps whatever the event's ordering is, and is
     * the difference between a field that receives an "n" and one that does not if that ordering ever
     * changes.
     */
    private static void promptName(String titleKey, String initial, double anchorX, double anchorY,
                                   java.util.function.Consumer<String> apply) {
        pendingAction = () -> {
            Screen parent = Minecraft.getInstance().screen;
            Minecraft.getInstance().setScreen(
                    new RoadNameScreen(parent, titleKey, initial, anchorX, anchorY, value -> {
                        apply.accept(value);
                        RoadStore.markDirty();
                    }));
        };
    }

    /** How far beside a railway a station may stand and still count as being on it, in blocks. */
    private static final double STATION_TRACK_TOLERANCE = 8.0;

    /**
     * Whether a station may stand at this place.
     *
     * <p>On a road of the saved network, or on one of the railways the track layer reports. The second
     * half is the whole reason the rail layer is a layer: a player who lays track and builds a station
     * beside it is putting a station on a road of the highest class there is, and asking the saved
     * network about it refuses -- the rail is not in the saved file, so as far as that file is
     * concerned there is nothing there at all. That refusal is what "it will not let me build a stop"
     * was.
     *
     * <p>The tolerance is a few blocks rather than zero because a station is built beside the track,
     * not on it: the platform is where the player can stand, and the track is a block away with a
     * train on it.
     */
    private static boolean nearTrack(int nodeId) {
        RoadNode node = RoadStore.get().node(nodeId);
        if (node == null) {
            return false;
        }
        // Any road of the saved network, not only a node of one. A place dropped onto a road is on that
        // road as far as the player is concerned, and refusing it because the road passes between two
        // nodes rather than through one is a rule about the data structure rather than about the world:
        // the node is free-standing, so the degree test says zero, and the player is told their station
        // is not on a road while looking straight at one.
        for (RoadSegment segment : RoadStore.get().segments()) {
            if (distanceToSegment(segment, node.x(), node.z()) <= STATION_TRACK_TOLERANCE) {
                return true;
            }
        }
        for (RoadSegment segment : RailTrackStore.network().segments()) {
            if (distanceToSegment(segment, node.x(), node.z()) <= STATION_TRACK_TOLERANCE) {
                return true;
            }
        }
        return false;
    }

    /** Distance from a point to the nearest point of a segment's polyline, in blocks. */
    private static double distanceToSegment(RoadSegment segment, double x, double z) {
        double best = Double.MAX_VALUE;
        for (int i = 1; i < segment.vertexCount(); i++) {
            best = Math.min(best, distanceToLine(segment.x(i - 1), segment.z(i - 1), segment.x(i),
                    segment.z(i), x, z));
        }
        return best;
    }

    /** Distance from a point to a line segment. */
    private static double distanceToLine(double ax, double az, double bx, double bz, double px,
                                         double pz) {
        double dx = bx - ax;
        double dz = bz - az;
        double lengthSq = dx * dx + dz * dz;
        double t = lengthSq <= 1.0E-9 ? 0.0 : ((px - ax) * dx + (pz - az) * dz) / lengthSq;
        t = Math.max(0.0, Math.min(1.0, t));
        return Math.hypot(px - (ax + t * dx), pz - (az + t * dz));
    }

    /**
     * Renames a place, for callers that are not the map.
     *
     * <p>Goes through the session's own editor so that a rename made from the line editor lands on the
     * same undo history as one made on the map: two editors over one network would be two histories,
     * and pressing undo once would then take back the wrong change.
     */
    public static void renamePlace(int nodeId, String name) {
        if (editor().setNodeName(nodeId, name)) {
            RoadStore.markDirty();
        }
    }

    /**
     * Opens the line editor on the next tick.
     *
     * <p>Deferred for the same reason every other screen this mod opens is: this is called from the key
     * handler, and a screen set from inside an input handler is replaced again before it is ever drawn
     * -- which is what a key that appears to do nothing looks like from the outside. The parent is read
     * inside the deferred action for the same reason the place editor reads its node there: by the time
     * it runs, the screen underneath is whatever the map left behind.
     */
    public static void promptLineEditor() {
        pendingAction = () -> {
            Screen parent = Minecraft.getInstance().screen;
            Minecraft.getInstance().setScreen(new TransitLineScreen(parent));
        };
    }

    /** Runs actions that had to be deferred out of an input handler. */
    public static void tick() {
        Runnable action = pendingAction;
        if (action != null) {
            pendingAction = null;
            action.run();
        }
    }

    // ---------------------------------------------------------- navigation

    /**
     * Navigates to the road node nearest the cursor.
     *
     * <p>Works without any landmark being named, which makes the routing testable the moment a few
     * roads exist.
     */
    /**
     * Navigates to the point under the cursor.
     *
     * <p>The exact spot is used rather than the nearest road node: the router anchors both ends to
     * the nearest point on the road network itself, so picking anywhere is both more direct and
     * what the player expects from clicking a map.
     */
    public static void navigateToCursor() {
        if (!mouseValid) {
            HowToGo.LOGGER.info("[HowToGo] map pick ignored: cursor position not known yet");
            return;
        }
        int x = (int) Math.round(mouseWorldX);
        int z = (int) Math.round(mouseWorldZ);
        Navigation.setTarget(new Destination("(" + x + ", " + z + ")", x, groundHeight(), z, "map"));
    }

    // ------------------------------------------------------------- operations

    /** Left click: place or extend. */
    public static void clickPlace() {
        if (!mouseValid) {
            return;
        }
        RoadEditor ed = editor();
        RoadSnapper.Result snap = lastSnap;
        int y = groundHeight();

        if (snap.kind() == RoadSnapper.Kind.SEGMENT && snap.segmentId() != RoadSegment.NO_SEGMENT) {
            // Dropping onto an existing road splits it and joins there.
            int nodeId = ed.splitSegment(snap.segmentId(), snap.vertexIndex(),
                    (int) Math.round(snap.x()), y, (int) Math.round(snap.z()));
            if (nodeId >= 0) {
                ed.addChainPoint(0, y, 0, nodeId);
            }
            RoadStore.markDirty();
            return;
        }

        int reuse = snap.kind() == RoadSnapper.Kind.NODE ? snap.nodeId() : RoadSegment.NO_NODE;
        ed.addChainPoint((int) Math.round(snap.x()), y, (int) Math.round(snap.z()), reuse);
        RoadStore.markDirty();
    }

    /** Right click: finish the current polyline, or clear selection when idle. */
    public static void clickFinishOrClear() {
        RoadEditor ed = editor();
        if (ed.chainNodeId() != RoadSegment.NO_NODE) {
            ed.finishChain();
        } else {
            ed.clearSelection();
            RailNameStore.clearSelection();
        }
    }

    public static void beginDrag() {
        RoadEditor ed = editor();
        int nodeId = ed.selectedNodeId();
        if (nodeId != RoadSegment.NO_NODE) {
            draggingNodeId = nodeId;
            ed.finishChain();
        }
    }

    /**
     * Selects the node under the cursor and starts dragging it. Called on shift-click.
     *
     * @return true if a node was grabbed
     */
    public static boolean beginDragAtCursor() {
        if (!mouseValid) {
            return false;
        }
        RoadEditor ed = editor();
        ed.finishChain();
        switch (lastSnap.kind()) {
            case NODE -> {
                RailNameStore.clearSelection();
                ed.selectNode(lastSnap.nodeId());
                draggingNodeId = lastSnap.nodeId();
                return true;
            }
            case SEGMENT -> {
                // Selecting a road instead of grabbing a handle: lets the player re-class or
                // delete a whole segment without redrawing it.
                RailNameStore.clearSelection();
                ed.selectSegment(lastSnap.segmentId());
                return false;
            }
            default -> {
                // Nothing hand-drawn here. The rail layer cannot be dragged -- its geometry belongs
                // to Create -- but it can be selected, so N then names the line under the cursor and
                // the highlight says which one that was.
                RoadSegment rail = railUnderCursor();
                ed.clearSelection();
                if (rail != null) {
                    RailNameStore.select(RailTrackStore.network(), rail);
                    return false;
                }
                RailNameStore.clearSelection();
                return false;
            }
        }
    }

    /**
     * Length in blocks of the polyline currently being drawn, including the leg from the chain
     * head to the cursor. Zero when nothing is being drawn.
     */
    public static double pendingLength() {
        RoadEditor ed = editor();
        if (ed.chainNodeId() == RoadSegment.NO_NODE || !mouseValid) {
            return 0.0;
        }
        RoadNetwork network = RoadStore.get();
        RoadNode head = network.node(ed.chainNodeId());
        if (head == null) {
            return 0.0;
        }
        return Math.hypot(lastSnap.x() - head.x(), lastSnap.z() - head.z());
    }

    /** Total length of every road in the current network, in blocks. */
    public static double totalLength() {
        double total = 0.0;
        for (RoadSegment segment : RoadStore.get().segments()) {
            total += segment.length();
        }
        return total;
    }

    public static void updateDrag() {
        if (draggingNodeId == RoadSegment.NO_NODE || !mouseValid) {
            return;
        }
        RoadEditor ed = editor();
        RoadNode node = RoadStore.get().node(draggingNodeId);
        int y = node != null ? node.y() : groundHeight();
        if (ed.moveNode(draggingNodeId, (int) Math.round(lastSnap.x()), y, (int) Math.round(lastSnap.z()))) {
            // The autosave is debounced, so flagging every frame only delays the write until the
            // drag actually stops.
            RoadStore.markDirty();
        }
    }

    public static void endDrag() {
        if (draggingNodeId != RoadSegment.NO_NODE) {
            RoadStore.markDirty();
        }
        draggingNodeId = RoadSegment.NO_NODE;
    }

    public static void deleteSelected() {
        if (editor().deleteSelection()) {
            RoadStore.markDirty();
        }
    }

    public static void undo() {
        editor().undo();
        RoadStore.markDirty();
    }

    public static void redo() {
        editor().redo();
        RoadStore.markDirty();
    }

    /** Best available ground height: the player's Y, or sea level. */
    private static int groundHeight() {
        var player = Minecraft.getInstance().player;
        return player != null ? (int) player.getY() : 64;
    }
}
