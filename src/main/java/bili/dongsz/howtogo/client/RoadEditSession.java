package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.HowToGo;
import bili.dongsz.howtogo.place.PlaceDetails;
import bili.dongsz.howtogo.place.PlaceSources;
import bili.dongsz.howtogo.road.PlaceKind;
import bili.dongsz.howtogo.road.RoadClass;
import bili.dongsz.howtogo.road.RoadDirection;
import bili.dongsz.howtogo.road.RoadEditor;
import bili.dongsz.howtogo.road.RoadNetwork;
import bili.dongsz.howtogo.road.RoadNode;
import bili.dongsz.howtogo.road.RoadSegment;
import bili.dongsz.howtogo.route.Destination;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
// 1.21.9 bundled the cursor position and the button into a record, and moved the mouse-button
// constants onto the SDL-backed InputConstants.
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

    /** Set when the world map screen begins closing; read on the next tick. */
    private static boolean mapScreenClosing;

    /**
     * The height of the point a RoadWeaver snap resolved to, and whether it is known.
     *
     * <p>A snapped point has to be placed at the height the layer's own geometry is at, not at the
     * player's feet: the router decides whether two nearby nodes are one place partly by height, so a
     * point placed at a different height than the RoadWeaver road it is meant to join would be refused
     * as a join. The layer's heights are real when its whole store is read and flat (zero) from its
     * map snapshot -- either way, matching it is what makes the connection work.
     */
    private static int snappedHeight;
    private static boolean snappedHeightKnown;

    private RoadEditSession() {
    }

    public static boolean isActive() {
        return active;
    }

    /** The storey new roads are drawn on; see {@link RoadEditor#setActiveLayer}. */
    public static int activeLayer() {
        return editor().activeLayer();
    }

    /**
     * Steps the storey new roads are drawn on, by {@code -/+} on the map.
     *
     * <h2>Why the drawing storey and not the selection</h2>
     * The class keys act on a selected road because re-classing a road is a thing a player does to a road
     * they are looking at. A storey is not: the whole point of it is that a tunnel is a <em>run</em> of
     * roads, so the gesture is "go down a level, then keep drawing". Tying it to the selection would mean
     * the key did one thing while something was selected and another while nothing was -- and the one it
     * did while something was selected is already what {@code N} does, in a panel that also lets the
     * player see the number they are about to write.
     *
     * <p>No undo entry, and no log line: the drawing storey is a mode rather than a property of anything
     * saved, it is not written into the file, and the readout beside the statistics already says which
     * storey the next click will use. Moving a road onto a storey is an edit, and that is the panel's job.
     *
     * @return the storey being drawn on after the step
     */
    public static int stepActiveLayer(int delta) {
        RoadEditor ed = editor();
        ed.setActiveLayer(ed.activeLayer() + delta);
        return ed.activeLayer();
    }

    /** The storey of the selected road, or the surface when nothing is selected. */
    public static int selectedLayer() {
        RoadEditor ed = editor();
        RoadSegment segment = RoadStore.get().segment(ed.selectedSegmentId());
        return segment != null ? segment.layer() : 0;
    }

    /** Length in blocks of the whole selected road, or 0 when nothing is selected. */
    public static double selectedLength() {
        return editor().chainLength(editor().selectedSegmentId());
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
        // Before the editor is touched: turning editing off while the right button is held has to let the
        // map's own button go, or the map would keep panning after the mouse was moved with nothing
        // pressed -- the screen believes a button is down until it is told otherwise.
        mapScreenClosing = false;
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
     * Moves the road under the cursor, or the selected one, through the one-way states.
     *
     * <h2>What it acts on</h2>
     * The selection first, then the road under the cursor, which is the same order {@code N} and
     * {@code <} use: having selected a road, a player expects the next press to act on that road and not
     * on whatever the cursor has drifted over since. The whole road is marked rather than the piece under
     * the cursor -- see {@link RoadEditor#setChainDirection} -- because a street that is one-way for one
     * of its bends is not a thing anyone means to build.
     *
     * <h2>Why the directions cycle</h2>
     * Two-way, then one-way along the road, then one-way against it, then back. A boolean switch would
     * have to pick a direction on the player's behalf, and the direction a road is stored in is an
     * accident of which end was clicked first, so the only honest answer is to let them say which way
     * round and show them on the map that it took.
     */
    public static void toggleOneWay() {
        RoadEditor ed = editor();
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
            HowToGo.LOGGER.info("[HowToGo] road {} set to {}", target, next.id());
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
        snappedHeightKnown = false;
        if (freePlacement) {
            lastSnap = RoadSnapper.Result.free(rawWorldX, rawWorldZ);
            return;
        }
        RoadSnapper.Result snap = RoadSnapper.snap(RoadStore.get(), rawWorldX, rawWorldZ,
                editor().chainNodeId());
        if (snap.kind() == RoadSnapper.Kind.FREE) {
            // Nothing hand-drawn is close enough. The other geometry on the map is offered to the
            // snap next, as positions only: none of it may be split or joined, because it belongs to
            // another mod. RoadWeaver's structures come first -- they are the points a player drawing
            // a road towards a village is aiming at -- then its roads, then a rail.
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
            // Create's tracks are the other geometry on the map, and a road is very often meant to
            // start or end on one, so they are offered to the snap as well -- but only as a position:
            // a rail cannot be split or joined, because it is a reading of the world rather than
            // something the editor owns.
            RoadSnapper.Result rail = railPositionSnap(rawWorldX, rawWorldZ);
            if (rail != null) {
                snap = rail;
            }
        }
        lastSnap = snap;
    }

    /**
     * The nearest structure RoadWeaver connected, as a position-only snap, or null.
     *
     * <p>Only the structures within the cursor's own catch radius are asked about: the layer can
     * hold a whole region's worth, and walking all of them per frame while the map is merely showing
     * them would be work nobody asked for.
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
     * The nearest point on one of RoadWeaver's roads, as a position-only snap, or null.
     *
     * <p>Uses the layer's own network and index, so a node and a point along a road are found by the
     * same rules as a hand-drawn one; only the result kind differs, because the caller may place at
     * that point but may not edit it.
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
     * Opens the editor for whatever carries something editable under the cursor, or for what is
     * selected.
     *
     * <h2>What N edits, in the order it is tried</h2>
     * An explicit selection first, then what the cursor is over: a road, a place, or a railway. The
     * order matters and is the same one the snap itself uses -- a selection is a deliberate act and
     * outranks a cursor that may have drifted, and a node outranks the segment running through it,
     * because a place sitting on a road is otherwise impossible to edit.
     *
     * <p>What is offered depends on what was hit, because it is what that thing has: a road has a name
     * and a storey, a place has a name and a kind, and a railway -- which belongs to Create -- has only
     * a name. The road's is not a naming panel with a second panel beside it for the storey: a storey
     * shows nowhere on a flat map, so anything that made it a key of its own would leave most players
     * never setting one.
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

    /**
     * Opens the editor for a road, addressed by segment: its name and its storey together.
     *
     * <p>Both in the one panel, because N on a road is "what is this road" and a storey is half of the
     * answer -- and because a storey is invisible on a map that cannot show height, so anything that
     * made it a separate key would leave most players never setting one. The panel's own field is
     * checked before anything is applied, so a mistyped level costs a correction rather than the edit.
     */
    private static void promptRoadName(int segmentId) {
        RoadSegment segment = RoadStore.get().segment(segmentId);
        if (segment == null) {
            return;
        }
        promptName(RoadNameScreen.TITLE_ROAD, segment.name(), segment.layer(), mouseScreenX(),
                mouseScreenZ(), (name, layer) -> editor().setRoadNameAndLayer(segmentId, name, layer));
    }

    /**
     * Opens the place editor for a hand-placed place.
     *
     * <p>Name and kind together, rather than the rename prompt this used to be: a place's kind is as
     * much a part of it as its name, and asking for one without the other meant the kind could only
     * ever be set at the moment of creation. The details are the third part and are handed in as the
     * draft the {@code More…} dialog edits; {@code Done} writes all three, so one edit is one undo
     * step. Everything is read inside the deferred action, because the editor opens a tick later and
     * the place may have moved or gone by then.
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
            Destination place = manualPlace(node);
            PlaceDetails initial = PlaceDetailStore.detailsOf(place);
            Screen parent = Screens.current();
            Screens.set(new RoadNameScreen(parent,
                    RoadNameScreen.TITLE_PLACE_EDIT, node.name(), node.placeKind(),
                    // The station rule is asked here, where the node is known, so the screen can keep
                    // itself open and say why rather than closing on a change that was refused.
                    kind -> kind != PlaceKind.STATION || places.stationAllowed(nodeId),
                    initial, anchorX, anchorY,
                    (name, kind, details) -> applyPlaceEdit(places, nodeId, name, kind, details)));
        };
    }

    /**
     * Opens the same place panel from outside edit mode, for a details card.
     *
     * <p>The destination picker and the main screen offer "edit" on a place that already exists, and
     * that place is edited through the map's own panel rather than a second one written for them: the
     * panel is where a place's name and kind are already edited, and a copy of it in another screen
     * would be a copy that drifts. There is no edit session to open it from, so the session opens it on
     * the map's behalf -- its own editor, its own station rule, its own undo history.
     *
     * <p>Deferred by a tick for the same reason every other screen here is: this is called from a
     * click handler, and a screen set from inside one is replaced again before it is ever drawn.
     *
     * @param nodeId the road-network node of the hand-placed place
     */
    public static void openPlaceEditorFromPanel(int nodeId) {
        // The same test the map's N uses: a plain road vertex has no name of its own, so a details
        // card that somehow offered one would get the place panel pointing at a road corner.
        if (!isPlaceNode(nodeId)) {
            return;
        }
        RoadEditor places = editor();
        places.setStationOverride(RoadEditSession::nearTrack);
        pendingAction = () -> {
            RoadNode node = RoadStore.get().node(nodeId);
            if (node == null) {
                return;
            }
            Destination place = manualPlace(node);
            PlaceDetails initial = PlaceDetailStore.detailsOf(place);
            Screen parent = Screens.current();
            // Centred rather than beside a cursor: this was opened from a details card, not from a
            // point on the map, so the panel's usual "next to what you clicked" anchor has nothing to
            // point at -- and the map cursor may be anywhere, or nowhere.
            double anchorX = widthOf(parent, 320) / 2.0;
            double anchorY = heightOf(parent, 240) / 2.0;
            Screens.set(new RoadNameScreen(parent,
                    RoadNameScreen.TITLE_PLACE_EDIT, node.name(), node.placeKind(),
                    kind -> kind != PlaceKind.STATION || places.stationAllowed(nodeId),
                    initial, anchorX, anchorY,
                    (name, kind, details) -> applyPlaceEdit(places, nodeId, name, kind, details)));
        };
    }

    /** A screen's width, or {@code fallback} when there is no screen to ask. */
    private static int widthOf(Screen screen, int fallback) {
        return screen == null ? fallback : screen.width;
    }

    /** A screen's height, or {@code fallback} when there is no screen to ask. */
    private static int heightOf(Screen screen, int fallback) {
        return screen == null ? fallback : screen.height;
    }

    /**
     * Writes a committed place edit: the name and kind on the node, the details in the store.
     *
     * <p>One method for both ways the panel is opened so the two cannot save different things. The
     * details are stored only when the name and kind were accepted: a record for a place whose edit was
     * refused would outlive a change that never happened, and the unlinked-details screen would have to
     * offer to delete it.
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
     * The destination a hand-placed place node stands for.
     *
     * <p>Built from the node rather than read from the source, because the store is keyed by the id a
     * source gives and this mod's own places are identified by their node id: a caller that has the
     * node and not the picker's list would otherwise be unable to name the record it wants.
     */
    private static Destination manualPlace(RoadNode node) {
        return new Destination(node.name(), node.x(), node.y(), node.z(), PlaceSources.MANUAL,
                Destination.NO_COLOR, node.placeKind(), String.valueOf(node.id()));
    }

    /**
     * Deletes a hand-placed place and everything that belongs to it.
     *
     * <p>The node branch of {@link RoadEditor#deleteSelection} is reused rather than reimplemented: it
     * removes the node, takes the segments that end on it with it and records one undo step, and it is
     * the operation the map's own Delete key performs. Selecting the node first is how a call from a
     * details card reaches it; the selection it leaves behind is the editor's, which is where a deletion
     * made on the map would leave it too.
     *
     * <p>The detail record goes with the place -- an orphaned record would be offered on the unlinked
     * screen for a place the player deliberately removed -- and a navigation targeting the place is
     * cleared, because a route to something that no longer exists is a route that can never arrive.
     *
     * @return whether the node was a hand-placed place and was removed
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
            // The handle being dragged has just gone; leaving its id here would have every later move
            // of the mouse ask the network for a node that no longer exists.
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
        // The id rather than the name or the position: a place can be renamed and moved, and the whole
        // point of a stable id is that the target still says which place it meant.
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
            Screen parent = Screens.current();
            Screens.set(
                    new RoadNameScreen(parent, titleKey, initial, anchorX, anchorY, value -> {
                        apply.accept(value);
                        RoadStore.markDirty();
                    }));
        };
    }

    /**
     * The same, for a road: its name and its storey handed back together.
     *
     * <p>Deliberately deferred for the same reason as the name prompt: opening a screen from the input
     * handler is what makes a key look dead, and the tick of delay is the difference between a field
     * that receives an "n" and one that does not.
     */
    private static void promptName(String titleKey, String initial, int layer, double anchorX,
                                   double anchorY,
                                   java.util.function.BiConsumer<String, Integer> apply) {
        pendingAction = () -> {
            Screen parent = Screens.current();
            Screens.set(
                    new RoadNameScreen(parent, titleKey, initial, layer, anchorX, anchorY,
                            (name, storey) -> {
                                apply.accept(name, storey);
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
            return;
        }
        // Editing is a mode of the world map, so it cannot outlive the map. Escape, or opening any
        // screen that is not one of this editor's own panels (a name prompt, the line editor, the map
        // archive screen), ends it -- and this is checked a tick after the map was replaced, because
        // the screen event that fires when it happens cannot say what replaced it.
        Screen screen = Screens.current();
        if (mapScreenClosing) {
            mapScreenClosing = false;
            if (!isEditorCompanion(screen)) {
                endEditing("the world map was closed");
            }
            return;
        }
        if (screen == null) {
            endEditing("the world map was closed");
            return;
        }
        if (!isEditorCompanion(screen) && !RoadEditHandler.isMapScreen(screen)) {
            // A non-map screen replaced the map without the map itself being closed first (the chat
            // screen, another mod's screen, Xaero's own settings). The map is not what is being looked
            // at, so the editing mode it owns goes with it. A map screen that has merely not redrawn
            // yet is not this case: returning from one of this editor's panels lands here before the
            // map's next frame, and ending the session then would make naming a road cancel editing.
            endEditing("another screen replaced the world map");
        }
    }

    /**
     * The screens this editor opens over the map, on which editing legitimately stays on: closing one
     * of them returns to the map it came from.
     */
    static boolean isEditorCompanion(Screen screen) {
        return screen instanceof RoadNameScreen
                // Opened from the place panel's More… button, over that panel: closing it returns to
                // the panel, so the editing session it was opened from is still the one on screen.
                || screen instanceof PlaceDetailScreen
                || screen instanceof TransitLineScreen
                || screen instanceof MapDataScreen
                || screen instanceof MapImportScreen
                || screen instanceof MainScreen;
    }

    /**
     * Notes that the world map screen is being closed.
     *
     * <p>The screen event is the only place that knows which screen is going away, and it fires for
     * the map being replaced by anything -- including this editor's own panels, which must not end
     * the session. So it only records the fact, and the tick above decides from what the screen is
     * now.
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
        HowToGo.LOGGER.info("[HowToGo] road editing disabled: {}", because);
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
        RoadEditor ed = editor();
        RoadSnapper.Result snap = lastSnap;
        // A point snapped onto RoadWeaver's geometry is placed at that geometry's height, not at the
        // player's feet: see snappedHeight. Everything else is placed at the player's height, which
        // is what a click on open ground has always meant.
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
                ed.beginHistory();
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

    // ------------------------------------------------------- panning with the right button

    /**
     * How far the cursor may travel between a right press and its release and still count as a click
     * rather than a drag, in the screen's own pixels.
     *
     * <p>Small, because it only has to separate "I let go without moving" from "I moved the map", and it
     * is not the map's own threshold: Xaero decides what pans and what is a click of its own accord. This
     * one only decides whether the release also drops the selection, which is the gesture a right click
     * without a drag has always been.
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

    /**
     * Whether a polyline is being drawn right now -- the "purple line" the right button belongs to.
     */
    public static boolean isDrawing() {
        return editor().chainNodeId() != RoadSegment.NO_NODE;
    }

    /**
     * Starts a drag of the map with the right button, by handing the map screen the left-button press its
     * own panning is driven by.
     *
     * <h2>Why it is done this way, and not by writing the camera</h2>
     * Xaero's world map pans while its left button is down: the camera is worked out in its own render
     * pass from the position the drag started at and where the cursor is now (its "click and drag to
     * scroll the map"). The left button is the one this editor has already spent -- a press places a
     * point -- so a right drag has nothing to hand it unless the press is delivered to the screen
     * directly. {@code Screen.mouseClicked} is the vanilla method Xaero overrides for exactly this, so
     * the pan that results is Xaero's own, with its own feel and its own animation, and no private field
     * of a closed-source mod is named anywhere.
     *
     * <p>The real right press is cancelled by the caller, so the map never sees a right click: its
     * right-click menu and its right-drag area selection are what this replaces.
     *
     * <p>If the press lands on one of the map's own widgets, the screen handles it and does not start a
     * pan -- which is the correct outcome, since the player aimed at the widget.
     */
    public static void beginMapPan() {
        if (panScreen != null) {
            // A pan whose release never arrived -- the button came up while another window had the focus,
            // so the map screen is still holding a press nobody let go of. It is let go here rather than
            // refused: refusing would leave the right button dead until editing was toggled off and on.
            endMapPan();
        }
        Screen screen = Screens.current();
        if (screen == null) {
            return;
        }
        double[] cursor = scaledCursor();
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
     * Ends it: the synthetic left press is released on the same screen that was pressed.
     *
     * <p>A release on a different screen is skipped rather than sent: the screen that was pressed has
     * been closed, and a button held down on a screen nobody can see is not something to hand to whatever
     * replaced it.
     *
     * <p>A right press and release that never moved is the old gesture -- drop the selection -- so it
     * still does that. A drag is a pan and leaves the selection alone: a player moving the map to look at
     * something else has not asked to forget what they had selected.
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
        if (!panMoved) {
            editor().clearSelection();
            RailNameStore.clearSelection();
        }
    }

    /**
     * The cursor in the screen's own coordinates, which is what a screen's mouse methods take.
     *
     * <p>Worked out the way vanilla's own mouse handler works it out, from the raw position and the GUI
     * scale, rather than taken from the projection this mod keeps for the map: that one is the cursor's
     * <em>world</em> position turned back into pixels, which is a different number while the map moves
     * and is not what a screen's hit testing wants.
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
        // No snapshot per frame: beginDrag took one for the whole gesture. See RoadEditor.moveNodeLive.
        if (ed.moveNodeLive(draggingNodeId, (int) Math.round(lastSnap.x()), y,
                (int) Math.round(lastSnap.z()))) {
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

    /**
     * Deletes whatever is selected, taking a deleted place's details with it.
     *
     * <p>A selected place goes through {@link #deletePlace}, the same operation a details card would
     * have used: it removes the node and the segments attached to it, removes the detail record and
     * ends a navigation aimed at the place. The record removal is what makes the map's Delete key
     * obey the same rule as every other deletion: a place and its notes go together, and a record
     * left behind is exactly what the unlinked-details screen exists to clean up.
     */
    public static void deleteSelected() {
        RoadEditor ed = editor();
        // The node branch only: a selected road takes no place with it, and a place is a node.
        if (ed.selectedSegmentId() == RoadSegment.NO_SEGMENT && isPlaceNode(ed.selectedNodeId())) {
            deletePlace(ed.selectedNodeId());
            return;
        }
        if (ed.deleteSelection()) {
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
