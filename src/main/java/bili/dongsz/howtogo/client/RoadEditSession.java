package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.HowToGo;
import bili.dongsz.howtogo.RoadConfig;
import bili.dongsz.howtogo.place.PlaceDetails;
import bili.dongsz.howtogo.place.PlaceSources;
import bili.dongsz.howtogo.road.PlaceKind;
import bili.dongsz.howtogo.road.RoadCategory;
import bili.dongsz.howtogo.road.RoadChains;
import bili.dongsz.howtogo.road.RoadClass;
import bili.dongsz.howtogo.road.RoadDirection;
import bili.dongsz.howtogo.road.RoadEditor;
import bili.dongsz.howtogo.road.RoadNetwork;
import bili.dongsz.howtogo.road.RoadNode;
import bili.dongsz.howtogo.road.RoadSegment;
import bili.dongsz.howtogo.road.SelectionGesture;
import bili.dongsz.howtogo.route.Destination;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
// 1.21.9 bundled the cursor position and the button into a record, and moved the mouse-button constants onto the SDL-backed InputConstants.
//? if >=1.21.9 {
/*import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
*///?}
// The GLFW mouse-button constants are named only on the pre-1.21.9 nodes.
//? if <1.21.9 {
import org.lwjgl.glfw.GLFW;
//?}

/**
 * Client-side state for the road editing mode: whether it is on, where the cursor is, and what the current snap resolved to. Kept out of RoadEditor so data operations stay free of viewport concerns and testable without a running client.
 */
public final class RoadEditSession {

    private static boolean active;
    private static RoadEditor editor;
    private static RoadNetwork boundNetwork;

    private static RoadSnapper.Result lastSnap = RoadSnapper.Result.free(0, 0);
    private static double mouseWorldX;
    private static double mouseWorldZ;
    private static boolean mouseValid;

    /** Node currently being dragged, or RoadSegment NO_NODE. */
    private static int draggingNodeId = RoadSegment.NO_NODE;

    /** True while the player holds the modifier that disables snapping. */
    private static boolean freePlacementActive;

    // The mouse buttons as the input layer numbers them, fully qualified: 1.21.9 moved these constants and the import is conditional, so the qualified name is the one spelling right on every node.
    private static final int MOUSE_BUTTON_LEFT =
            com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT;
    private static final int MOUSE_BUTTON_RIGHT =
            com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_RIGHT;

    /**
     * Remembers the last press, so the second press of a double click can be told from a fresh one. The rule itself is SelectionGesture; this is only the state a session keeps between two clicks.
     */
    private static final SelectionGesture.Detector selectionGesture = new SelectionGesture.Detector();

    /** What the Shift+right press that began the current pan was, and the road it was over. */
    private static boolean rightPressShift;
    private static boolean rightPressDouble;
    private static int rightPressSegment = RoadSegment.NO_SEGMENT;

    /**
     * What the last Shift+right single click replaced, so a double click completing a moment later can take that click back before toggling its one segment.
     * The first press cannot be held back -- it toggles its whole road immediately and remembers the selection it replaced; a second press reported as a double restores that selection first, leaving exactly the one segment selected. See RoadEditor snapshotSelection.
     */
    private static RoadEditor.SelectionSnapshot toggleUndoSnapshot;

    /** Work deferred out of an input handler to the next client tick. */
    private static Runnable pendingAction;

    /** Set when the world map screen begins closing; read on the next tick. */
    private static boolean mapScreenClosing;

    /**
     * How many consecutive ticks the map has been away from the editor, and the absence length that
     * still ends the session, in ticks -- a second. See tick for why the absence is tolerated at all.
     */
    private static final int MAP_AWAY_GRACE_TICKS = 20;
    private static int mapAwayTicks;

    /**
     * The height of the point a RoadWeaver snap resolved to, and whether it is known.
     * A snapped point has to be placed at the layer's height, not the player's feet: the router decides whether two nearby nodes are one place partly by height, so a different height would be refused as a join. The layer's heights are real from its store and flat from its map snapshot.
     */
    private static int snappedHeight;
    private static boolean snappedHeightKnown;

    private RoadEditSession() {
    }

    public static boolean isActive() {
        return active;
    }

    /** The storey new roads are drawn on; see RoadEditor setActiveLayer. */
    public static int activeLayer() {
        return editor().activeLayer();
    }

    /**
     * Steps the storey new roads are drawn on, by -/+ on the map.
     * Acts on the drawing storey rather than the selection: a tunnel is a run of roads, so the gesture is "go down a level, then keep drawing". Moving a road onto a storey is the panel's job (setRoadDetails).
     * No undo entry and no log line: the storey is a mode rather than a property of anything saved, and the readout already says which storey the next click will use.
     */
    public static int stepActiveLayer(int delta) {
        RoadEditor ed = editor();
        ed.setActiveLayer(ed.activeLayer() + delta);
        return ed.activeLayer();
    }

    /** The storey of the main selected road, or the surface when nothing is selected. */
    public static int selectedLayer() {
        return editor().selectedLayer();
    }

    /** Length in blocks of every selected road, or 0 when nothing is selected. */
    public static double selectedLength() {
        return editor().selectedLength();
    }

    /** The editor for the current level, creating it on first use. */
    public static RoadEditor editor() {
        RoadNetwork network = RoadStore.get();
        if (editor == null || boundNetwork != network) {
            editor = new RoadEditor(network);
            boundNetwork = network;
            // The station rule has two halves: the editor knows the saved roads, and the client knows the railway layer, which is not in the saved file. The missing half is handed over once here, because the reclassify and demote passes run after every edit, long after any panel has closed.
            editor.setStationOverride(RoadEditSession::nearTrack);
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
        // The gesture state goes before the pan is ended: turning editing off is not a click, and the pan's own end must not finish a half-recognised selection on the way out.
        selectionGesture.reset();
        forgetToggleUndo();
        rightPressShift = false;
        rightPressDouble = false;
        rightPressSegment = RoadSegment.NO_SEGMENT;
        // Before the editor is touched: turning editing off while the right button is held has to let the map's own button go, or the map would keep panning after the mouse was moved.
        mapScreenClosing = false;
        mapAwayTicks = 0;
        snappedHeightKnown = false;
        endMapPan();
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
     * The class [ and ] act on: the selected segment if there is one, otherwise the class new roads are drawn with.
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
        if ((ed.selectedSegmentId() != RoadSegment.NO_SEGMENT || !ed.selectedChain().isEmpty())
                && ed.setSegmentClass(ed.selectedSegmentId(), next)) {
            // Every selected road, not only the main one. The cycle starts from the main segment's class, so a single press ends with the whole selection on that class.
            RoadStore.markDirty();
        } else {
            ed.setActiveClass(next);
        }
    }

    /**
     * Moves the road under the cursor, or everything selected, through the one-way states.
     * Selection first, then the road under the cursor, as N and < do. The whole road is marked, not the piece under the cursor, because a street that is one-way for one bend is not what anyone builds; a multi-selection advances every road from its own state.
     * Two-way, along, against, back: a boolean switch would have to pick a direction on the player's behalf, and the stored direction is an accident of which end was clicked first.
     */
    public static void toggleOneWay() {
        RoadEditor ed = editor();
        if (!ed.selectedChain().isEmpty()) {
            if (ed.cycleSelectedDirection()) {
                RoadStore.markDirty();
                HowToGo.logInfo("[HowToGo] {} selected road(s) one-way switched",
                        ed.selectedChain().size());
            }
            return;
        }
        int target = ed.selectedSegmentId();
        if (target == RoadSegment.NO_SEGMENT
                && lastSnap.kind() == RoadSnapper.Kind.SEGMENT) {
            target = lastSnap.segmentId();
        }
        if (target == RoadSegment.NO_SEGMENT || RoadStore.get().segment(target) == null) {
            return;
        }
        RoadDirection next = ed.chainDirection(target).next();
        if (ed.setChainDirection(target, next)) {
            RoadStore.markDirty();
            HowToGo.logInfo("[HowToGo] road {} set to {}", target, next.id());
        }
    }

    /**
     * Records the raw cursor position. Cheap, and called for every rendered element so cursor-dependent actions work even when the editor is closed.
     */
    public static void setMouse(double rawWorldX, double rawWorldZ) {
        mouseWorldX = rawWorldX;
        mouseWorldZ = rawWorldZ;
        mouseValid = true;
    }

    /**
     * Recomputes the snapped cursor position. Separate from setMouse because snapping is a linear scan, and running it per element rather than once per frame would be quadratic. freePlacement bypasses all snapping, bound to Alt.
     */
    public static void updateMouse(double rawWorldX, double rawWorldZ, boolean freePlacement) {
        setMouse(rawWorldX, rawWorldZ);
        freePlacementActive = freePlacement;
        snappedHeightKnown = false;
        if (freePlacement) {
            lastSnap = RoadSnapper.Result.free(rawWorldX, rawWorldZ);
            return;
        }
        RoadSnapper.Result snap = RoadSnapper.snap(RoadStore.get(), rawWorldX, rawWorldZ,
                editor().chainNodeId());
        if (snap.kind() == RoadSnapper.Kind.FREE) {
            // Nothing hand-drawn is close enough. The other geometry is offered next as positions only -- none of it may be split or joined, because it belongs to another mod. RoadWeaver's structures first, then its roads, then a rail.
            RoadSnapper.Result structure = roadWeaverStructureSnap(rawWorldX, rawWorldZ);
            if (structure != null) {
                snap = structure;
            }
        }
        if (snap.kind() == RoadSnapper.Kind.FREE) {
            RoadSnapper.Result road = roadWeaverRoadSnap(rawWorldX, rawWorldZ);
            if (road != null) {
                snap = road;
            }
        }
        if (snap.kind() == RoadSnapper.Kind.FREE) {
            // Create's tracks are offered to the snap as well, but only as a position: a rail cannot be split or joined, because it is a reading of the world rather than something the editor owns.
            RoadSnapper.Result rail = railPositionSnap(rawWorldX, rawWorldZ);
            if (rail != null) {
                snap = rail;
            }
        }
        lastSnap = snap;
    }

    /**
     * The nearest structure RoadWeaver connected, as a position-only snap, or null. Only structures within the cursor's catch radius are asked about: the layer can hold a whole region's worth.
     */
    private static RoadSnapper.Result roadWeaverStructureSnap(double worldX, double worldZ) {
        double reach = RoadSnapper.worldRadius(RoadSnapper.NODE_SNAP_PX);
        if (reach <= 0 || RoadWeaverLayer.structures().isEmpty()) {
            return null;
        }
        java.util.List<double[]> points = new java.util.ArrayList<>();
        for (RoadWeaverLayer.Structure structure : RoadWeaverLayer.structures()) {
            if (Math.abs(structure.x() - worldX) > reach || Math.abs(structure.z() - worldZ) > reach) {
                continue;
            }
            points.add(new double[]{structure.x(), structure.z()});
        }
        RoadSnapper.Result snapped = RoadSnapper.snapToPoints(points, worldX, worldZ,
                RoadSnapper.NODE_SNAP_PX, RoadSnapper.Kind.ROADWEAVER);
        if (snapped != null) {
            for (RoadWeaverLayer.Structure structure : RoadWeaverLayer.structures()) {
                if (structure.x() == (int) Math.round(snapped.x())
                        && structure.z() == (int) Math.round(snapped.z())) {
                    snappedHeight = structure.y();
                    snappedHeightKnown = true;
                    break;
                }
            }
        }
        return snapped;
    }

    /**
     * The nearest point on one of RoadWeaver's roads, as a position-only snap, or null. Uses the layer's own network and index, so a node and a point along a road are found by the same rules; only the result kind differs.
     */
    private static RoadSnapper.Result roadWeaverRoadSnap(double worldX, double worldZ) {
        if (RoadWeaverLayer.segments().isEmpty()) {
            return null;
        }
        RoadNetwork layer = RoadWeaverLayer.network();
        RoadSnapper.Result hit = RoadSnapper.snap(layer, worldX, worldZ, RoadSegment.NO_NODE);
        if (hit.kind() != RoadSnapper.Kind.NODE && hit.kind() != RoadSnapper.Kind.SEGMENT) {
            return null;
        }
        RoadNode node = hit.kind() == RoadSnapper.Kind.NODE ? layer.node(hit.nodeId()) : null;
        RoadSegment segment = node != null ? null : layer.segment(hit.segmentId());
        int height = node != null ? node.y() : segment != null ? segment.y()
                : (int) Math.round(Minecraft.getInstance().player == null ? 64
                : Minecraft.getInstance().player.getY());
        snappedHeight = height;
        snappedHeightKnown = true;
        return new RoadSnapper.Result(hit.x(), hit.z(), RoadSnapper.Kind.ROADWEAVER,
                RoadSegment.NO_NODE, RoadSegment.NO_SEGMENT, -1);
    }

    /**
     * The nearest rail to a point, as a position-only snap, or null. The catch radii are the editor's own, because the tolerance belongs to the cursor rather than to what is being pointed at.
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
     * Opens the editor for whatever carries something editable under the cursor, or for what is selected.
     * Selection first, then what the cursor is over: a road, a place, or a railway. A selection outranks a cursor that may have drifted, and a node outranks the segment through it, since a place sitting on a road would otherwise be impossible to edit.
     * What is offered depends on what was hit: a road has a name, storey, category and number; a place has a name and kind; a railway only a name. A plain road vertex has no name of its own, so pointing at one falls through to the road through it.
     */
    public static void nameSelectedRoad() {
        RoadEditor ed = editor();

        // A selected road, or several: the panel speaks about the main one, and the edit it writes applies to every selected road. The main segment is always set for a non-empty selection; the fallback covers an edit that removed it between keystrokes.
        if (ed.selectedSegmentId() != RoadSegment.NO_SEGMENT || !ed.selectedChain().isEmpty()) {
            int main = ed.selectedSegmentId();
            if (main == RoadSegment.NO_SEGMENT) {
                main = ed.selectedChain().iterator().next();
            }
            promptRoadName(main);
            return;
        }
        // A selected place.
        if (isPlaceNode(ed.selectedNodeId())) {
            promptPlaceEditor(ed.selectedNodeId());
            return;
        }
        // A place under the cursor. Only an existing place: pointing at a road's corner and pressing N still means "name this road".
        if (lastSnap.kind() == RoadSnapper.Kind.NODE && isPlaceNode(lastSnap.nodeId())) {
            ed.selectNode(lastSnap.nodeId());
            promptPlaceEditor(lastSnap.nodeId());
            return;
        }
        // A road under the cursor.
        if (lastSnap.kind() == RoadSnapper.Kind.SEGMENT
                && lastSnap.segmentId() != RoadSegment.NO_SEGMENT) {
            ed.selectSegment(lastSnap.segmentId());
            promptRoadName(lastSnap.segmentId());
            return;
        }
        // A selected railway, so N names what was selected rather than what the cursor drifted over.
        String selectedRail = RailNameStore.selectedSeed();
        if (selectedRail != null) {
            promptRailName(selectedRail, RailNameStore.nameOfKey(selectedRail));
            return;
        }
        // A railway under the cursor, the other named thing on the map.
        nameRailUnderCursor();
    }

    /**
     * Opens the editor for a road: its name, storey, category and number together.
     * All four in one panel, because N on a road is "what is this road" and every one is part of the answer; a storey and a classification invisible on a flat map would otherwise never be set by most players. The field is checked before anything is applied, so a mistyped level costs a correction.
     * The segment only supplies initial values; the edit walks every selected road (setRoadDetails). The category is handed over as the segment carries it -- the panel normalises a deleted category, so a lookup here would be a second place deciding what that means.
     */
    private static void promptRoadName(int segmentId) {
        RoadSegment segment = RoadStore.get().segment(segmentId);
        if (segment == null) {
            return;
        }
        // The direction asked of the chain, not of the piece under the cursor: a bent street's segments may store different directions, and the panel has to open on the answer the map and the one-way key would give.
        RoadDirection direction = editor().chainDirection(segmentId);
        promptName(RoadNameScreen.TITLE_ROAD, segment.name(), segment.layer(), segment.category(),
                segment.number(), direction, mouseScreenX(), mouseScreenZ(),
                (name, layer, category, number, oneWay) ->
                        editor().setRoadDetails(segmentId, name, layer, category, number, oneWay));
    }

    /**
     * Opens the place editor for a hand-placed place: name and kind together, since a kind that could only be set at creation is half the place. Details are handed in as the draft the More dialog edits, and Done writes all three as one undo step. Everything is read inside the deferred action, because the place may have moved or gone a tick later.
     */
    private static void promptPlaceEditor(int nodeId) {
        promptPlaceEditor(nodeId, null);
    }

    /**
     * The same panel, with something to take back if it is cancelled -- for the place a press just created, whose node exists before the panel. Every other caller passes null: an existing place must survive its editor being cancelled.
     */
    private static void promptPlaceEditor(int nodeId, Runnable onCancel) {
        RoadEditor places = editor();
        if (RoadStore.get().node(nodeId) == null) {
            return;
        }
        double anchorX = mouseScreenX();
        double anchorY = mouseScreenZ();
        pendingAction = () -> {
            RoadNode node = RoadStore.get().node(nodeId);
            if (node == null) {
                return;
            }
            Destination place = manualPlace(node);
            PlaceDetails initial = PlaceDetailStore.detailsOf(place);
            Screen parent = Screens.current();
            Screens.set(new RoadNameScreen(parent,
                    RoadNameScreen.TITLE_PLACE_EDIT, node.name(), node.placeKind(),
                    // The station rule is asked here, where the node is known, so the screen can keep itself open and say why rather than closing on a refused change.
                    kind -> kind != PlaceKind.STATION || places.stationAllowed(nodeId),
                    initial, anchorX, anchorY,
                    (name, kind, details) -> applyPlaceEdit(places, nodeId, name, kind, details),
                    onCancel));
        };
    }

    /**
     * Opens the place panel from outside edit mode, for a details card: the picker and the main screen offer edit on an existing place, and a copy written for them would drift from this one. There is no edit session, so the session opens it on the map's behalf -- its own editor, station rule and undo history.
     * Deferred by a tick like every screen here: this is called from a click handler, and a screen set from inside one is replaced again before it is ever drawn.
     */
    public static void openPlaceEditorFromPanel(int nodeId) {
        // The same test the map's N uses: a plain road vertex has no name of its own, so a details card that offered one would get the place panel pointing at a road corner.
        if (!isPlaceNode(nodeId)) {
            return;
        }
        RoadEditor places = editor();
        pendingAction = () -> {
            RoadNode node = RoadStore.get().node(nodeId);
            if (node == null) {
                return;
            }
            Destination place = manualPlace(node);
            PlaceDetails initial = PlaceDetailStore.detailsOf(place);
            Screen parent = Screens.current();
            // Centred rather than beside a cursor: this was opened from a details card, so the usual anchor has nothing to point at.
            double anchorX = widthOf(parent, 320) / 2.0;
            double anchorY = heightOf(parent, 240) / 2.0;
            Screens.set(new RoadNameScreen(parent,
                    RoadNameScreen.TITLE_PLACE_EDIT, node.name(), node.placeKind(),
                    kind -> kind != PlaceKind.STATION || places.stationAllowed(nodeId),
                    initial, anchorX, anchorY,
                    (name, kind, details) -> applyPlaceEdit(places, nodeId, name, kind, details)));
        };
    }

    /** A screen's width, or fallback when there is no screen to ask. */
    private static int widthOf(Screen screen, int fallback) {
        return screen == null ? fallback : screen.width;
    }

    /** A screen's height, or fallback when there is no screen to ask. */
    private static int heightOf(Screen screen, int fallback) {
        return screen == null ? fallback : screen.height;
    }

    /**
     * Writes a committed place edit: the name and kind on the node, the details in the store. One method for both ways the panel opens so the two cannot save different things, and the details are stored only when the name and kind were accepted, so a refused edit leaves no record.
     */
    private static void applyPlaceEdit(RoadEditor places, int nodeId, String name, PlaceKind kind,
                                       PlaceDetails details) {
        if (!places.setPlace(nodeId, name, kind)) {
            return;
        }
        RoadStore.markDirty();
        RoadNode stored = RoadStore.get().node(nodeId);
        if (stored != null) {
            PlaceDetailStore.put(manualPlace(stored), details);
        }
    }

    /**
     * The destination a hand-placed place node stands for. Built from the node because this mod's own places are identified by their node id: a caller holding the node could not otherwise name the record it wants.
     */
    private static Destination manualPlace(RoadNode node) {
        return new Destination(node.name(), node.x(), node.y(), node.z(), PlaceSources.MANUAL,
                Destination.NO_COLOR, node.placeKind(), String.valueOf(node.id()));
    }

    /**
     * Deletes a hand-placed place and everything that belongs to it.
     * Reuses the node branch of RoadEditor deleteSelection -- removes the node, the segments that end on it, and records one undo step -- by selecting the node first. The detail record goes with the place, and a navigation aimed at it is cleared, because a route to something gone can never arrive.
     */
    public static boolean deletePlace(int nodeId) {
        RoadEditor ed = editor();
        RoadNode node = RoadStore.get().node(nodeId);
        if (node == null || node.type() != RoadNode.Type.POI) {
            return false;
        }
        Destination place = manualPlace(node);
        ed.selectNode(nodeId);
        if (!ed.deleteSelection()) {
            return false;
        }
        if (draggingNodeId == nodeId) {
            // The handle being dragged has just gone; leaving its id here would have later mouse moves ask the network for a node that no longer exists.
            draggingNodeId = RoadSegment.NO_NODE;
        }
        PlaceDetailStore.remove(place);
        RoadStore.markDirty();
        clearNavigationTo(place);
        return true;
    }

    /** Drops the navigation when it was heading for the place just deleted, and only then. */
    private static void clearNavigationTo(Destination place) {
        Destination target = Navigation.target();
        if (target == null || !PlaceSources.MANUAL.equals(target.source())) {
            return;
        }
        // The id rather than the name or the position: a place can be renamed and moved, and the whole point of a stable id is that the target still says which place it meant.
        if (place.placeId() == null || !place.placeId().equals(target.placeId())) {
            return;
        }
        Navigation.clear();
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
     * Names the automatic railway under the cursor, if there is one. Only the name: moving, splitting or deleting is not offered, because the layer is a reading of the world that Create owns and would be replaced by the next reading a second later.
     */
    private static boolean nameRailUnderCursor() {
        RoadSegment rail = railUnderCursor();
        if (rail == null) {
            return false;
        }
        // The key, not the id: the prompt is answered a tick later and the layer rebuilds on its own schedule, so an id may belong to a different segment by then. The key is a property of the track and is looked up again in whatever the layer holds.
        String key = RailNameStore.keyOf(rail);
        RailNameStore.select(RailTrackStore.network(), rail);
        promptRailName(key, rail.name());
        return true;
    }

    /**
     * The rail segment under the cursor, or null. Snapped with the same code the editor uses on hand-drawn roads, pointed at the rail layer, so a rail is picked with the same tolerance in screen pixels.
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
                // A node of the layer: a railway begins, ends or branches here. Any of the segments meeting there will do, because a name covers the whole chain.
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

    /**
     * Drops a named place at the cursor.
     * The place goes exactly where the cursor snapped, so a press over a road is a press on the road, and it deliberately does not split that road: a place is not a junction, nothing routes through it, and cutting its piece in two would be an edit the player did not ask for.
     * Snapping to an existing node or road keeps that road's height, so a place dropped on an elevated road sits at the road; a free press in a field keeps the player's height.
     */
    public static void placePoi() {
        if (!mouseValid) {
            return;
        }
        RoadEditor ed = editor();
        RoadSnapper.Result snap = lastSnap;
        int x = (int) Math.round(snap.x());
        int z = (int) Math.round(snap.z());
        int y = groundHeight();
        if (snap.kind() == RoadSnapper.Kind.NODE) {
            RoadNode node = RoadStore.get().node(snap.nodeId());
            if (node != null) {
                y = node.y();
            }
        } else if (snap.kind() == RoadSnapper.Kind.SEGMENT
                && snap.segmentId() != RoadSegment.NO_SEGMENT) {
            RoadSegment segment = RoadStore.get().segment(snap.segmentId());
            if (segment != null) {
                y = segment.y();
            }
        }
        RoadEditor.PoiPlacement placed = ed.placePoi(x, y, z);
        RoadStore.markDirty();
        // The same prompt an existing place is renamed through, so a place made now and one renamed later cannot end up different. A press that created a node hands the panel a way to take it back.
        if (placed.created()) {
            promptPlaceEditor(placed.nodeId(), () -> cancelNewPlace(placed.nodeId()));
        } else {
            promptPlaceEditor(placed.nodeId());
        }
    }

    /**
     * Takes back a place node a press created, after its editor was cancelled.
     * Undo rather than delete: creating the node recorded one undo step, and undoing it removes node and history entry together, so Ctrl+Z after a cancel takes back the previous edit rather than resurrecting a declined place.
     * A fallback delete covers the one case undo cannot: the node is still there because the top of the stack was some other edit.
     */
    private static void cancelNewPlace(int nodeId) {
        RoadNode node = RoadStore.get().node(nodeId);
        if (node == null || node.type() != RoadNode.Type.POI) {
            return;
        }
        editor().undo();
        if (RoadStore.get().node(nodeId) != null) {
            deletePlace(nodeId);
            return;
        }
        RoadStore.markDirty();
    }

    /**
     * Opens the naming prompt on the next client tick. Keys arrive from ScreenEvent KeyPressed Pre inside the keyboard handler; the tick of delay costs nothing, keeps input dispatch free of screen swaps, and is what makes the field receive its first letter.
     */
    private static void promptName(String titleKey, String initial, double anchorX, double anchorY,
                                   java.util.function.Consumer<String> apply) {
        pendingAction = () -> {
            Screen parent = Screens.current();
            Screens.set(
                    new RoadNameScreen(parent, titleKey, initial, anchorX, anchorY, value -> {
                        apply.accept(value);
                        RoadStore.markDirty();
                    }));
        };
    }

    /**
     * The same, for a road: its name, storey, category, number and direction handed back together. Deferred for the same reason. The mark is dirtied here, so the one place that opens this panel is also the one place that says the store needs saving.
     */
    private static void promptName(String titleKey, String initial, int layer, RoadCategory category,
                                   String number, RoadDirection direction, double anchorX,
                                   double anchorY, RoadNameScreen.RoadApply apply) {
        pendingAction = () -> {
            Screen parent = Screens.current();
            Screens.set(
                    new RoadNameScreen(parent, titleKey, initial, layer, category, number, direction,
                            anchorX, anchorY, (name, storey, chosen, plate, oneWay) -> {
                                apply.accept(name, storey, chosen, plate, oneWay);
                                RoadStore.markDirty();
                            }));
        };
    }

    /**
     * How far beside a road or a railway a station may stand and still count as being on it, in blocks -- station_snap_blocks, the same number the transit planner uses for the platform's reach.
     * A place farther away could be marked but never boarded, so the editor asks the planner's own number rather than one of its own. It used to be a fixed eight, which refused places on a platform a dozen blocks from the track's centreline.
     */
    private static double stationTrackTolerance() {
        return RoadConfig.stationSnapBlocks();
    }

    /**
     * Whether a station may stand at this place: on a road of the saved network, or on one of the railways the track layer reports.
     * The second half is the whole reason the rail layer is a layer: track laid by Create is not in the saved file, so asking only the saved network refuses -- that refusal is what "it will not let me build a stop" was. The tolerance is a few blocks because a station is built beside the track, not on it.
     */
    private static boolean nearTrack(int nodeId) {
        RoadNode node = RoadStore.get().node(nodeId);
        if (node == null) {
            return false;
        }
        // Any road of the saved network, not only a node of one: a place dropped onto a road is on that road as far as the player is concerned, and refusing it because the road passes between two nodes is a rule about the data structure rather than about the world.
        for (RoadSegment segment : RoadStore.get().segments()) {
            if (distanceToSegment(segment, node.x(), node.z()) <= stationTrackTolerance()) {
                return true;
            }
        }
        for (RoadSegment segment : RailTrackStore.network().segments()) {
            if (distanceToSegment(segment, node.x(), node.z()) <= stationTrackTolerance()) {
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
     * Renames a place, for callers that are not the map. Goes through the session's own editor so a rename made from the line editor lands on the same undo history as one made on the map.
     */
    public static void renamePlace(int nodeId, String name) {
        if (editor().setNodeName(nodeId, name)) {
            RoadStore.markDirty();
        }
    }

    /**
     * Opens the line editor on the next tick, for the same reason every screen here is deferred: a screen set from inside an input handler is replaced before it is ever drawn. The parent is read inside the deferred action, because the screen underneath is whatever the map left behind by then.
     */
    public static void promptLineEditor() {
        pendingAction = () -> {
            Screen parent = Screens.current();
            Screens.set(new TransitLineScreen(parent));
        };
    }

    /** Runs actions that had to be deferred out of an input handler. */
    public static void tick() {
        Runnable action = pendingAction;
        if (action != null) {
            pendingAction = null;
            action.run();
        }
        if (!active) {
            mapScreenClosing = false;
            mapAwayTicks = 0;
            return;
        }
        // Editing is a mode of the world map, so it cannot outlive the map. Escape, or any screen that is not one of the editor's own panels, ends it; this is checked a tick after the map was replaced, because the screen event that fires when it happens cannot say what replaced it.
        Screen screen = Screens.current();
        boolean mapWasClosed = mapScreenClosing || screen == null;
        mapScreenClosing = false;
        if (editingSurface(screen)) {
            if (mapAwayTicks > 0) {
                // The map came back inside the grace, so the absence was another mod's blip rather than the player closing anything. One quiet line, so a future "editing died on the map" report can be told apart from a real close.
                HowToGo.diagnostic("[HowToGo] road editing kept: the map was back after {} ticks away",
                        mapAwayTicks);
                mapAwayTicks = 0;
            }
            return;
        }
        // The map is not under the editor this tick. Ending the session on the first sight of that turned every screen another mod borrowed for a frame -- a phone overlay over the map, a setScreen(null) blip -- and every lag stall long enough to expire the map's own freshness (MapViewState.isFresh) while the map still filled the screen, into editing silently switching itself off (issue #10: the stutter the reporter saw was the stall, the exit was this check). The absence gets a one-second grace instead: transient thefts recover well inside it, and input and rendering gate themselves on the map being on screen, so a session held open by the grace cannot act on anything underneath it. Only an absence that outlasts the grace ends the session, with the words that were logged before the grace existed.
        if (mapAwayTicks == 0) {
            HowToGo.diagnostic("[HowToGo] road editing: the map is away, {} ticks of grace started",
                    MAP_AWAY_GRACE_TICKS);
        }
        if (mapAwayTicks < MAP_AWAY_GRACE_TICKS) {
            mapAwayTicks++;
            return;
        }
        endEditing(mapWasClosed ? "the world map was closed" : "another screen replaced the world map");
    }

    /**
     * The screens this session stays alive on: the world map itself, or one of the editor's own panels over it. The map half goes through isMapScreen, whose freshness answer is also this check's only evidence that the map is the thing on screen right now -- and exactly the evidence a lag stall takes away.
     */
    private static boolean editingSurface(Screen screen) {
        return isEditorCompanion(screen) || RoadEditHandler.isMapScreen(screen);
    }

    /**
     * The screens this editor opens over the map, on which editing legitimately stays on: closing one returns to the map it came from.
     */
    static boolean isEditorCompanion(Screen screen) {
        return screen instanceof RoadNameScreen
                // Opened from the place panel's More button, over that panel: closing it returns to the panel, so the editing session it was opened from is still on screen.
                || screen instanceof PlaceDetailScreen
                || screen instanceof TransitLineScreen
                || screen instanceof MapDataScreen
                || screen instanceof MapImportScreen
                || screen instanceof MainScreen;
    }

    /**
     * Notes that the world map screen is being closed. The screen event is the only place that knows which screen is going away, and it fires for the map being replaced by anything -- this editor's own panels included -- so it only records the fact and the tick above decides.
     */
    static void noteMapScreenClosing() {
        mapScreenClosing = true;
    }

    /** Ends editing, saying why, so a mode that turned itself off is in the log rather than a mystery. */
    private static void endEditing(String because) {
        if (!active) {
            return;
        }
        setActive(false);
        HowToGo.logInfo("[HowToGo] road editing disabled: {}", because);
    }

    // ---------------------------------------------------------- navigation

    /**
     * Navigates to the road node nearest the cursor. Works without any landmark being named, which makes routing testable the moment a few roads exist.
     */
    /**
     * Navigates to the point under the cursor. The exact spot is used rather than the nearest road node: the router anchors both ends to the nearest point on the network itself, so picking anywhere is what the player expects.
     */
    public static void navigateToCursor() {
        if (!mouseValid) {
            HowToGo.diagnostic("[HowToGo] map pick ignored: cursor position not known yet");
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
        // Fed to the gesture tracker even though a plain left click starts nothing: an ordinary click between two Shift ones is what keeps them from being read as one double click.
        notePress(MOUSE_BUTTON_LEFT, false);
        forgetToggleUndo();
        RoadEditor ed = editor();
        RoadSnapper.Result snap = lastSnap;
        // A point snapped onto RoadWeaver's geometry is placed at that geometry's height, not at the player's feet; everything else is placed at the player's height, which is what a click on open ground has always meant.
        int y = snap.kind() == RoadSnapper.Kind.ROADWEAVER && snappedHeightKnown
                ? snappedHeight : groundHeight();

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
        // The press is still a press: finishing a road with the right button must break a pair of Shift+right clicks. It is not a multi-select press, so it is fed in with Shift reported as off.
        notePress(MOUSE_BUTTON_RIGHT, false);
        forgetToggleUndo();
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
            // One snapshot for the whole gesture, taken here rather than by every frame of it.
            ed.beginHistory();
            draggingNodeId = nodeId;
            ed.finishChain();
        }
    }

    /**
     * Handles a Shift+left press: grabs a node to drag, or selects the road under the cursor.
     * A press over a node is a drag and stays one however fast it is repeated -- a handle is a handle. A press over a segment selects: the first press the whole road, a second within the double-click window only the segment, replacing the selection so the two gestures compose without the first being taken back.
     */
    public static boolean beginDragAtCursor() {
        if (!mouseValid) {
            return false;
        }
        SelectionGesture.Target gesture = notePress(MOUSE_BUTTON_LEFT, true);
        // A selection made a moment ago is not part of this click: the snapshot a multi-select double click would restore must not outlive the click that took it.
        forgetToggleUndo();
        RoadEditor ed = editor();
        ed.finishChain();
        switch (lastSnap.kind()) {
            case NODE -> {
                RailNameStore.clearSelection();
                ed.selectNode(lastSnap.nodeId());
                ed.beginHistory();
                draggingNodeId = lastSnap.nodeId();
                return true;
            }
            case SEGMENT -> {
                // Selecting a road instead of grabbing a handle: lets the player re-class or delete a whole segment without redrawing it.
                RailNameStore.clearSelection();
                if (gesture == SelectionGesture.Target.DOUBLE) {
                    // The piece the cursor is on, for the one gesture where the piece is the subject: trimming or re-routing one straight stretch of a street.
                    ed.selectSingleSegment(lastSnap.segmentId());
                } else {
                    ed.selectSegment(lastSnap.segmentId());
                }
                return false;
            }
            default -> {
                // Nothing hand-drawn here. The rail layer cannot be dragged -- its geometry belongs to Create -- but it can be selected, so N then names the line under the cursor.
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
     * Length in blocks of the polyline currently being drawn, including the leg from the chain head to the cursor; zero when nothing is being drawn.
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

    // ------------------------------------------------------- panning with the right button

    /**
     * How far the cursor may travel between a right press and its release and still count as a click rather than a drag, in the screen's own pixels. Small, because it only has to separate a click from a drag and is not the map's own threshold.
     */
    private static final double PAN_CLICK_SLOP = 4.0;

    /** The map screen whose pan this session handed a synthetic left press to, or null. */
    private static Screen panScreen;
    private static double panStartScreenX;
    private static double panStartScreenY;
    private static boolean panMoved;

    /** Whether a right-button drag of the map is in progress. */
    public static boolean isPanningMap() {
        return panScreen != null;
    }

    /** Whether a polyline is being drawn right now -- the "purple line" the right button belongs to. */
    public static boolean isDrawing() {
        return editor().chainNodeId() != RoadSegment.NO_NODE;
    }

    /**
     * Starts a drag of the map with the right button, by handing the map screen the left-button press its own panning is driven by.
     * Xaero pans while its left button is down, and the left button is the one the editor has spent on placing points, so the right press is delivered to the screen directly and the pan that results is Xaero's own, with no private field named. The caller cancels the real right press, so the map never sees a right click or its context menu.
     * If the press lands on one of the map's own widgets, the screen handles it and no pan starts.
     * Shift+right is also a multi-select click: the press is fed to the gesture detector here, and the selection is applied in endMapPan once the release confirms there was no drag.
     */
    public static void beginMapPan(boolean selectionClick) {
        if (panScreen != null) {
            // A pan whose release never arrived -- the button came up while another window had the focus. It is let go here rather than refused, or the right button would stay dead until editing was toggled off and on.
            endMapPan();
        }
        Screen screen = Screens.current();
        if (screen == null) {
            return;
        }
        double[] cursor = scaledCursor();
        // Every right press goes through the tracker, shift or not: an ordinary right click between two Shift ones keeps them from being read as one double click.
        SelectionGesture.Target gesture = notePress(MOUSE_BUTTON_RIGHT, selectionClick);
        rightPressShift = selectionClick;
        rightPressDouble = selectionClick && gesture == SelectionGesture.Target.DOUBLE;
        rightPressSegment = selectionClick ? segmentUnderCursor() : RoadSegment.NO_SEGMENT;
        panScreen = screen;
        panStartScreenX = cursor[0];
        panStartScreenY = cursor[1];
        panMoved = false;
        //? if >=1.21.9 {
        /*screen.mouseClicked(new MouseButtonEvent(cursor[0], cursor[1],
                new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0)), false);
        *///?} else {
        screen.mouseClicked(cursor[0], cursor[1], GLFW.GLFW_MOUSE_BUTTON_LEFT);
        //?}
    }

    /**
     * Ends it: the synthetic left press is released on the same screen that was pressed. A release on a different screen is skipped, since the pressed screen has been closed.
     * A right press and release that never moved is a click: an ordinary one drops the selection, a Shift one is the multi-select gesture. A drag is a pan and leaves the selection alone; it also breaks the chain a following press would otherwise pair with.
     */
    public static void endMapPan() {
        Screen screen = panScreen;
        if (screen == null) {
            return;
        }
        panScreen = null;
        double[] cursor = scaledCursor();
        if (Math.abs(cursor[0] - panStartScreenX) > PAN_CLICK_SLOP
                || Math.abs(cursor[1] - panStartScreenY) > PAN_CLICK_SLOP) {
            panMoved = true;
        }
        if (Screens.current() == screen) {
            //? if >=1.21.9 {
            /*screen.mouseReleased(new MouseButtonEvent(cursor[0], cursor[1],
                    new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0)));
            *///?} else {
            screen.mouseReleased(cursor[0], cursor[1], GLFW.GLFW_MOUSE_BUTTON_LEFT);
            //?}
        }
        // Read and cleared before anything is applied, so a selection edit made below cannot be read back as the state of this press.
        boolean selectionClick = rightPressShift;
        boolean doubleClick = rightPressDouble;
        int segmentId = rightPressSegment;
        rightPressShift = false;
        rightPressDouble = false;
        rightPressSegment = RoadSegment.NO_SEGMENT;
        if (panMoved) {
            // A drag is a pan, and a pan is not half of anything: without this the next press could pair with the one that started the drag.
            selectionGesture.reset();
            forgetToggleUndo();
            return;
        }
        if (selectionClick) {
            applySelectionClick(doubleClick, segmentId);
            return;
        }
        forgetToggleUndo();
        editor().clearSelection();
        RailNameStore.clearSelection();
    }

    /**
     * Applies what a Shift+right press turned out to be, now that its release has confirmed it was a click.
     * The first press of a double click has to select its whole road before the second press exists, so it is applied at once and the selection it replaced is remembered; when the second turns out to be a double, that selection is restored first and then the one segment is toggled.
     * Nothing is applied for a press that named no road: an empty-space right click leaves the selection alone rather than wiping it.
     */
    private static void applySelectionClick(boolean doubleClick, int segmentId) {
        if (segmentId == RoadSegment.NO_SEGMENT) {
            forgetToggleUndo();
            return;
        }
        RoadEditor ed = editor();
        if (!doubleClick) {
            toggleUndoSnapshot = ed.snapshotSelection();
            ed.toggleChainSelection(segmentId);
            return;
        }
        // The first click of this double already toggled its whole road. Take that back before toggling the one segment, or the double leaves the road and the piece of it both acted on.
        if (toggleUndoSnapshot != null) {
            ed.restoreSelection(toggleUndoSnapshot);
            forgetToggleUndo();
        }
        ed.toggleSingleSegmentSelection(segmentId);
    }

    /**
     * The road under the cursor, as a segment: the segment itself, or for a node the nearest road that meets it.
     * A node is exactly what a cursor lands on when aiming at a road end, and refusing the gesture there would make the ends of every road unselectable. Node snapping wins within a wider radius, so the incident segments are compared against the cursor's world position and the nearest wins; a node that carries a single road needs no comparison.
     */
    private static int segmentUnderCursor() {
        if (!mouseValid) {
            return RoadSegment.NO_SEGMENT;
        }
        if (lastSnap.kind() == RoadSnapper.Kind.SEGMENT) {
            return lastSnap.segmentId();
        }
        if (lastSnap.kind() == RoadSnapper.Kind.NODE) {
            return nearestIncidentSegment(lastSnap.nodeId());
        }
        return RoadSegment.NO_SEGMENT;
    }

    /**
     * The road meeting nodeId that passes nearest the cursor, or NO_SEGMENT when the node holds no road. Distances are measured from the cursor's world position rather than from the snapped node, because a node's own position says nothing about which of its roads the player meant.
     */
    private static int nearestIncidentSegment(int nodeId) {
        RoadNetwork network = RoadStore.get();
        int best = RoadSegment.NO_SEGMENT;
        double bestDistance = Double.MAX_VALUE;
        for (int segmentId : RoadChains.segmentsAt(network, nodeId)) {
            RoadSegment segment = network.segment(segmentId);
            if (segment == null) {
                continue;
            }
            double distance = distanceToSegment(segment, mouseWorldX, mouseWorldZ);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = segmentId;
            }
        }
        return best;
    }

    /**
     * Feeds one button press to the double-click detector and reports what it completes. In one place so that no press is left out of the stream: the detector compares adjacent presses, so an unreported press is one the next pairs with.
     */
    private static SelectionGesture.Target notePress(int button, boolean shift) {
        double[] cursor = scaledCursor();
        return selectionGesture.press(button, shift, System.currentTimeMillis(),
                cursor[0], cursor[1]);
    }

    /**
     * Forgets the selection a Shift+right single click replaced, without undoing it: the toggle stands, only the ability to take it back goes. For every moment the click is over.
     */
    private static void forgetToggleUndo() {
        toggleUndoSnapshot = null;
    }

    /**
     * The cursor in the screen's own coordinates, which is what a screen's mouse methods take. Worked out from the raw position and GUI scale, not from the projection the mod keeps for the map, which is a different number while the map moves.
     */
    private static double[] scaledCursor() {
        Minecraft minecraft = Minecraft.getInstance();
        com.mojang.blaze3d.platform.Window window = minecraft.getWindow();
        int rawWidth = Math.max(1, window.getScreenWidth());
        int rawHeight = Math.max(1, window.getScreenHeight());
        double x = minecraft.mouseHandler.xpos() * window.getGuiScaledWidth() / (double) rawWidth;
        double y = minecraft.mouseHandler.ypos() * window.getGuiScaledHeight() / (double) rawHeight;
        return new double[]{x, y};
    }

    public static void updateDrag() {
        if (draggingNodeId == RoadSegment.NO_NODE || !mouseValid) {
            return;
        }
        RoadEditor ed = editor();
        RoadNode node = RoadStore.get().node(draggingNodeId);
        int y = node != null ? node.y() : groundHeight();
        // No snapshot per frame: beginDrag took one for the whole gesture. See RoadEditor moveNodeLive.
        if (ed.moveNodeLive(draggingNodeId, (int) Math.round(lastSnap.x()), y,
                (int) Math.round(lastSnap.z()))) {
            // The autosave is debounced, so flagging every frame only delays the write until the drag actually stops.
            RoadStore.markDirty();
            // The line data travels with the place it stands on: a stop stores the block it was built at, and a drag is the one edit in which that block stops being true a block at a time. Read back from the node rather than from the snap, because the rounded cursor position and the block the editor moved to can differ; the store compares before it flags anything.
            RoadNode moved = RoadStore.get().node(draggingNodeId);
            if (moved != null) {
                TransitLineStore.updateStopsForNode(moved.id(), moved.x(), moved.z());
            }
        }
    }

    public static void endDrag() {
        int nodeId = draggingNodeId;
        if (nodeId != RoadSegment.NO_NODE) {
            RoadStore.markDirty();
            // The release is the gesture's last word, and covers the frames in which no sync ran. The stop is put on the node's actual block rather than on the cursor, for the reason updateDrag gives.
            RoadNode node = RoadStore.get().node(nodeId);
            if (node != null) {
                TransitLineStore.updateStopsForNode(node.id(), node.x(), node.z());
            }
        }
        draggingNodeId = RoadSegment.NO_NODE;
    }

    /**
     * Deletes whatever is selected, taking a deleted place's details with it. A selected place goes through deletePlace, the same operation a details card would have used: node, segments, detail record and navigation go together, so a record is never left behind for the unlinked screen to clean up.
     */
    public static void deleteSelected() {
        RoadEditor ed = editor();
        // The node branch only: a selected road takes no place with it. The segment set is asked as well as the main segment, because a multi-selection can be non-empty while an edit has just taken its main segment away.
        if (ed.selectedSegmentId() == RoadSegment.NO_SEGMENT && ed.selectedChain().isEmpty()
                && isPlaceNode(ed.selectedNodeId())) {
            deletePlace(ed.selectedNodeId());
            return;
        }
        // A snapshot taken before a Shift+right click names roads this deletion is about to remove; restoring it later would put dead ids back into the selection.
        forgetToggleUndo();
        if (ed.deleteSelection()) {
            RoadStore.markDirty();
        }
    }

    public static void undo() {
        // Undo swaps the network out from under the selection, so a selection snapshot taken before a Shift+right click is no longer a state this network had.
        forgetToggleUndo();
        editor().undo();
        RoadStore.markDirty();
        // Undo swapped in a snapshot of the whole network, so every place is at the block it had when the snapshot was taken -- a station dragged and then taken back springs back with no move of its own to carry the news to its line. No per-frame path runs after a key press, so every stop is re-read here; redo does the same.
        TransitLineStore.syncStationStops();
    }

    public static void redo() {
        forgetToggleUndo();
        editor().redo();
        RoadStore.markDirty();
        // The other direction of the same reconciliation -- see undo().
        TransitLineStore.syncStationStops();
    }

    /** Best available ground height: the player's Y, or sea level. */
    private static int groundHeight() {
        var player = Minecraft.getInstance().player;
        return player != null ? (int) player.getY() : 64;
    }
}
