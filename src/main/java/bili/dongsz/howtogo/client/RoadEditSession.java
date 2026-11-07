package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.HowToGo;
import bili.dongsz.howtogo.RoadConfig;
import bili.dongsz.howtogo.place.PlaceDetails;
import bili.dongsz.howtogo.place.PlaceSources;
import bili.dongsz.howtogo.road.PlaceKind;
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

    // The mouse buttons, named the way the input layer numbers them. Fully qualified rather than
    // imported because 1.21.9 moved these constants and the import at the top of the file is already
    // conditional; the fully qualified name is the one spelling that is right on every node.
    private static final int MOUSE_BUTTON_LEFT =
            com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT;
    private static final int MOUSE_BUTTON_RIGHT =
            com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_RIGHT;

    /**
     * Remembers the last press, so that the second press of a double click can be told from a fresh
     * one. See {@link SelectionGesture}: the rule itself is a pure class, and this is only the state
     * a session keeps between two clicks.
     */
    private static final SelectionGesture.Detector selectionGesture = new SelectionGesture.Detector();

    /** What the Shift+right press that began the current pan was, and the road it was over. */
    private static boolean rightPressShift;
    private static boolean rightPressDouble;
    private static int rightPressSegment = RoadSegment.NO_SEGMENT;

    /**
     * What the last Shift+right single click replaced, so that a double click completing a moment
     * later can take that click back before toggling its one segment.
     *
     * <p>The first press of a double click cannot be held back: it has to do something at once or
     * every multi-select click would feel a third of a second late. So it toggles its whole road
     * immediately, and remembers the selection it replaced. A second press that the detector reports
     * as a double restores that selection first, which leaves exactly the one segment selected --
     * which is what the double click asked for. See {@link RoadEditor#snapshotSelection}.
     */
    private static RoadEditor.SelectionSnapshot toggleUndoSnapshot;

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
     * player see the number they are about to write. A selection moved onto a storey is still possible,
     * through that panel: the edit it commits walks every selected road (see
     * {@link RoadEditor#setRoadNameAndLayer}), so multi-select does not need this key to grow a second
     * meaning.
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
        // The gesture state goes before the pan is ended: turning editing off is not a click, and the
        // pan's own end must not finish a half-recognised selection on the way out.
        selectionGesture.reset();
        forgetToggleUndo();
        rightPressShift = false;
        rightPressDouble = false;
        rightPressSegment = RoadSegment.NO_SEGMENT;
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
        if ((ed.selectedSegmentId() != RoadSegment.NO_SEGMENT || !ed.selectedChain().isEmpty())
                && ed.setSegmentClass(ed.selectedSegmentId(), next)) {
            // Every selected road, not only the main one -- see RoadEditor#setSegmentClass. The class
            // the cycle starts from is the main segment's, because a single press has to have one
            // starting point; the result is that the whole selection ends up on that class.
            RoadStore.markDirty();
        } else {
            ed.setActiveClass(next);
        }
    }

    /**
     * Moves the road under the cursor, or everything selected, through the one-way states.
     *
     * <h2>What it acts on</h2>
     * The selection first, then the road under the cursor, which is the same order {@code N} and
     * {@code <} use: having selected a road, a player expects the next press to act on that road and not
     * on whatever the cursor has drifted over since. The whole road is marked rather than the piece under
     * the cursor -- see {@link RoadEditor#setChainDirection} -- because a street that is one-way for one
     * of its bends is not a thing anyone means to build. A multi-selection advances every selected road
     * from its own state, so the fourth press brings them all back to two-way however mixed they were;
     * see {@link RoadEditor#cycleSelectedDirection}.
     *
     * <h2>Why the directions cycle</h2>
     * Two-way, then one-way along the road, then one-way against it, then back. A boolean switch would
     * have to pick a direction on the player's behalf, and the direction a road is stored in is an
     * accident of which end was clicked first, so the only honest answer is to let them say which way
     * round and show them on the map that it took.
     */
    public static void toggleOneWay() {
        RoadEditor ed = editor();
        if (!ed.selectedChain().isEmpty()) {
            if (ed.cycleSelectedDirection()) {
                RoadStore.markDirty();
                HowToGo.LOGGER.info("[HowToGo] {} selected road(s) one-way switched",
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

        // 1. A selected road -- or several. The panel speaks about the main one (its name and storey
        //    are what it shows) and the edit it writes applies to every selected road, which is what
        //    the feature contract asks of N on a multi-selection. The main segment is always set for
        //    a non-empty selection; the fallback is only for a selection whose main segment an edit
        //    has removed between keystrokes.
        if (ed.selectedSegmentId() != RoadSegment.NO_SEGMENT || !ed.selectedChain().isEmpty()) {
            int main = ed.selectedSegmentId();
            if (main == RoadSegment.NO_SEGMENT) {
                main = ed.selectedChain().iterator().next();
            }
            promptRoadName(main);
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
     *
     * <p>The segment is only the road the panel reads its initial values from: the edit it commits
     * walks every selected road, because naming a road is a road-wide operation and a multi-selection
     * is the gesture "do this to all of these". See {@link RoadEditor#setRoadNameAndLayer}.
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
        promptPlaceEditor(nodeId, null);
    }

    /**
     * The same panel, with something to take back if it is cancelled.
     *
     * <p>For the place a press just created: the node exists before the panel does, so cancelling has
     * to remove it. Every other caller passes nothing -- an existing place must survive its editor
     * being cancelled exactly as it was, and a road or a line has no node at all.
     *
     * @param onCancel run when the panel closes without {@code Done}, or null when cancelling is a
     *                 no-op
     */
    private static void promptPlaceEditor(int nodeId, Runnable onCancel) {
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
                    (name, kind, details) -> applyPlaceEdit(places, nodeId, name, kind, details),
                    onCancel));
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

    /**
     * Drops a named place at the cursor.
     *
     * <p>The place goes exactly where the cursor snapped, so a press over a road is a press on the
     * road: the snapped point is on the road's polyline, which is what the station rule (distance to
     * the road) and any later navigation to the place both read. It deliberately does not split the
     * road underneath: a place is not a junction, nothing routes through it, and cutting its piece of
     * road in two would be an edit the player did not ask for -- one that would then need its own
     * undo after the place was taken back. The place itself is still the one node the router anchors
     * to when the trip's goal is the place.
     *
     * <p>Snapping to an existing node or a road keeps that road's height, so a place dropped on an
     * elevated road sits at the road rather than at the player's feet. A free press in a field keeps
     * the player's height, which is what a place on open ground has always meant.
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
        // The same prompt an existing place is renamed through, so a place made now and one renamed
        // later cannot end up with different titles or different storage. A press that created a node
        // hands the panel a way to take it back: cancelling the editor of a place the player has not
        // decided to keep yet must not leave a nameless node behind.
        if (placed.created()) {
            promptPlaceEditor(placed.nodeId(), () -> cancelNewPlace(placed.nodeId()));
        } else {
            promptPlaceEditor(placed.nodeId());
        }
    }

    /**
     * Takes back a place node a press created, after its editor was cancelled.
     *
     * <h2>Why an undo rather than a delete</h2>
     * Creating the node recorded one undo step, and cancelling is "this press did not happen": undoing
     * that step removes the node and the history entry together, so the map is exactly as it was --
     * including Ctrl+Z, which after a cancel must take back the player's previous edit rather than
     * resurrect the place they declined to make. Deleting it instead would leave the creation and the
     * deletion on the stack, and the first Ctrl+Z would bring back the nameless node.
     *
     * <p>A fallback delete covers the one case undo cannot: the node is still there because the top of
     * the stack was some other edit. That should be unreachable -- the panel is modal and the press
     * pushed its snapshot last -- but leaving a stray node would be exactly the bug this exists to fix,
     * so the node is removed one way or the other.
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

    /**
     * How far beside a road or a railway a station may stand and still count as being on it, in
     * blocks.
     *
     * <p>Read from {@code station_snap_blocks}, the same number the transit planner uses for the
     * platform's reach: a station is a place and not a point, and a passenger reaching it from the
     * track is what makes it a station. A place farther away could be marked here but never boarded
     * by a journey -- the line would keep a stop it cannot be served at -- so the editor asks the
     * planner's own number rather than one of its own. It used to be a fixed eight, which refused a
     * place the player had put down on a station platform a dozen blocks from the track's centreline;
     * and a place that cannot be marked as a station never appears in the line editor at all, so the
     * line had no stops to be given.
     */
    private static double stationTrackTolerance() {
        return RoadConfig.stationSnapBlocks();
    }

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
        // Fed to the gesture tracker even though a plain left click starts nothing: an ordinary click
        // between two Shift ones is what keeps them from being read as one double click.
        notePress(MOUSE_BUTTON_LEFT, false);
        forgetToggleUndo();
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
        // The press is still a press: finishing a road with the right button must break a pair of
        // Shift+right clicks rather than let the next one pair across it. It is not a multi-select
        // press, so it is fed in with Shift reported as off.
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
     *
     * <h2>What the double click changes, and what it does not</h2>
     * A press over a <em>node</em> is a drag and stays one however fast it is repeated -- the handle
     * is a handle, and a double click on it would have to invent a second meaning for grabbing one.
     * A press over a <em>segment</em> selects: the first press selects the whole road, and a second
     * press within the double-click window selects only the segment under the cursor. The second
     * press replaces the selection rather than adding to it, so the two gestures compose without the
     * first having to be taken back -- which is why the left button needs none of the snapshot
     * bookkeeping the multi-select button needs.
     *
     * @return true if a node was grabbed
     */
    public static boolean beginDragAtCursor() {
        if (!mouseValid) {
            return false;
        }
        SelectionGesture.Target gesture = notePress(MOUSE_BUTTON_LEFT, true);
        // A selection made a moment ago is not part of this click: it is a different road, and the
        // snapshot a multi-select double click would restore must not outlive the click that took it.
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
                // Selecting a road instead of grabbing a handle: lets the player re-class or
                // delete a whole segment without redrawing it.
                RailNameStore.clearSelection();
                if (gesture == SelectionGesture.Target.DOUBLE) {
                    // The piece the cursor is on, for the one gesture where the piece is the subject:
                    // trimming or re-routing one straight stretch of a street.
                    ed.selectSingleSegment(lastSnap.segmentId());
                } else {
                    ed.selectSegment(lastSnap.segmentId());
                }
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
     *
     * <h2>Shift, and why the selection waits for the release</h2>
     * A Shift+right press is a multi-select click as well as a pan: the press is taken either way, and
     * only the release can say which of the two it was, because "did the cursor move" is the same test
     * that decides whether the map was dragged. So the press is fed to the gesture detector here -- it
     * is a press, and the double click is measured between presses -- and the selection itself is
     * applied in {@link #endMapPan} once the release has confirmed there was no drag. Nothing is
     * applied on a press that becomes a pan.
     *
     * @param selectionClick whether Shift was held when the button went down
     */
    public static void beginMapPan(boolean selectionClick) {
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
        // Every right press goes through the tracker, shift or not: an ordinary right click between two
        // Shift ones is what keeps them from being read as one double click.
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
     * Ends it: the synthetic left press is released on the same screen that was pressed.
     *
     * <p>A release on a different screen is skipped rather than sent: the screen that was pressed has
     * been closed, and a button held down on a screen nobody can see is not something to hand to whatever
     * replaced it.
     *
     * <p>A right press and release that never moved is a click, and a click does something: an
     * ordinary one drops the selection, and a Shift one is the multi-select gesture -- see
     * {@link #applySelectionClick}. A drag is a pan and leaves the selection alone: a player moving the
     * map to look at something else has not asked to forget what they had selected. A drag is also
     * not a click, so it breaks the chain a following press would otherwise pair with.
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
        // Read and cleared before anything is applied, so a selection edit made below cannot be read
        // back as the state of this press.
        boolean selectionClick = rightPressShift;
        boolean doubleClick = rightPressDouble;
        int segmentId = rightPressSegment;
        rightPressShift = false;
        rightPressDouble = false;
        rightPressSegment = RoadSegment.NO_SEGMENT;
        if (panMoved) {
            // A drag is a pan, and a pan is not half of anything: without this the next press could
            // pair with the one that started the drag as if the two had been two clicks.
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
     * Applies what a Shift+right press turned out to be, now that its release has confirmed it was a
     * click.
     *
     * <h2>Why the single click can be taken back</h2>
     * The first press of a double click has to select its whole road before the second press exists:
     * holding it back would make every multi-select click arrive a third of a second late. So it is
     * applied at once, and the selection it replaced is remembered. When the second press turns out to
     * be a double, that selection is restored first and then the one segment is toggled -- which leaves
     * exactly what a double click asked for, rather than "the whole road, plus or minus one piece".
     *
     * <p>Nothing is applied for a press that named no road: an empty-space right click in
     * multi-select mode leaves the selection alone rather than wiping it, because a mis-aimed click is
     * the commonest thing a modifier-held gesture sees and losing a whole selection to it would be the
     * worst possible answer.
     *
     * @param doubleClick whether the detector said this press completed a double click
     * @param segmentId   the road under the cursor when the press began, or
     *                    {@link RoadSegment#NO_SEGMENT}
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
        // The first click of this double already toggled its whole road. Take that back before toggling
        // the one segment, or the double would leave the road and the piece of it both acted on.
        if (toggleUndoSnapshot != null) {
            ed.restoreSelection(toggleUndoSnapshot);
            forgetToggleUndo();
        }
        ed.toggleSingleSegmentSelection(segmentId);
    }

    /**
     * The road under the cursor, as a segment: the segment itself, or for a node the nearest road
     * that meets it.
     *
     * <p>The selection gestures are about roads and segments, and a node is neither -- but a node is
     * exactly what a cursor lands on when aiming at a road end, and refusing the gesture there would
     * make the ends of every road unselectable. Snapping to a node wins over snapping to a segment
     * within a wider catch radius (9 px against 6), so at a bend or a junction the snap is a node
     * almost whenever the player aims near a corner -- which is the case a "first road stored against
     * this node" answer got wrong: at a bend that is one of the two pieces of the same street, and at
     * a junction it can be an entirely different road from the one under the cursor.
     *
     * <p>So the incident segments are compared against the cursor's own world position and the nearest
     * wins. Nothing is guessed at a tie beyond the network's own order, which is stable, and a node
     * that carries a single road needs no comparison at all. The single-click gesture expands to the
     * whole chain anyway, so this choice only matters for the segment-level half -- which is exactly
     * the gesture it exists for.
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
     * The road meeting {@code nodeId} that passes nearest the cursor, or
     * {@link RoadSegment#NO_SEGMENT} when the node holds no road.
     *
     * <p>Distances are block distances to the segment's polyline, measured from the cursor's world
     * position rather than from the snapped node: the snap collapsed the cursor onto the node, and a
     * node's own position says nothing about which of its roads the player meant. One block and one
     * screen pixel are not the same length, but for choosing between roads that meet at one point the
     * ranking is the same at any zoom -- the roads fan out from that point, so the nearer one in blocks
     * is the nearer one on screen.
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
     * Feeds one button press to the double-click detector and reports what it completes.
     *
     * <p>In one place so that no press can be left out of the stream: the detector compares adjacent
     * presses, so a press that is not reported is a press the next one pairs with instead.
     *
     * @return {@link SelectionGesture.Target#DOUBLE} when this press completes a double click
     */
    private static SelectionGesture.Target notePress(int button, boolean shift) {
        double[] cursor = scaledCursor();
        return selectionGesture.press(button, shift, System.currentTimeMillis(),
                cursor[0], cursor[1]);
    }

    /**
     * Forgets the selection a Shift+right single click replaced, without undoing it.
     *
     * <p>For every moment the click it belongs to is over: the double-click window has passed without a
     * second press, or another action changed the network or the selection in between, or editing was
     * turned off. The toggle itself stands; only the ability to take it back goes.
     */
    private static void forgetToggleUndo() {
        toggleUndoSnapshot = null;
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
        // The node branch only: a selected road takes no place with it, and a place is a node. The
        // segment set is asked as well as the main segment, because a multi-selection can be non-empty
        // while an edit has just taken its main segment away.
        if (ed.selectedSegmentId() == RoadSegment.NO_SEGMENT && ed.selectedChain().isEmpty()
                && isPlaceNode(ed.selectedNodeId())) {
            deletePlace(ed.selectedNodeId());
            return;
        }
        // A snapshot taken before a Shift+right click names roads this deletion is about to remove;
        // restoring it later would put dead ids back into the selection.
        forgetToggleUndo();
        if (ed.deleteSelection()) {
            RoadStore.markDirty();
        }
    }

    public static void undo() {
        // Undo swaps the network out from under the selection, so a selection snapshot taken before a
        // Shift+right click is no longer a state this network had.
        forgetToggleUndo();
        editor().undo();
        RoadStore.markDirty();
    }

    public static void redo() {
        forgetToggleUndo();
        editor().redo();
        RoadStore.markDirty();
    }

    /** Best available ground height: the player's Y, or sea level. */
    private static int groundHeight() {
        var player = Minecraft.getInstance().player;
        return player != null ? (int) player.getY() : 64;
    }
}
