package bili.dongsz.howtogo.road;

import java.util.List;
import java.util.Set;

/**
 * Checks the selection gestures and everything a multi-selection is supposed to do.
 *
 * <h2>What this covers, and what it cannot</h2>
 * The two halves the editor's selection is made of:
 * <ul>
 *   <li>{@link SelectionGesture} -- the arithmetic of click versus double click. Every bound is
 *       walked from both sides here, because an off-by-one in a time window or a pixel radius is
 *       invisible by eye and only shows up as "the second click sometimes does the wrong thing".</li>
 *   <li>{@link RoadEditor} -- what a selection is and what the bulk edits do to it. These are pure
 *       data operations, so the road under test is a plain {@link RoadNetwork} with a bent street in
 *       it, which is the shape the whole model exists for: three segments that are one road.</li>
 * </ul>
 * The client layer that feeds the detector is not testable here and is not tested: it needs a map
 * screen, a viewport and a mouse. What it does with the answer is a thin dispatch, and every value it
 * can be handed comes out of the two pieces checked here.
 *
 * <h2>Why the fixtures are shaped the way they are</h2>
 * The bent street is three straight pieces at right angles, so a road-wide operation that only
 * touched the clicked piece would be caught by the other two keeping the old value. The second road
 * stands well away from it and is never connected, so "the edit leaked onto a road that was not
 * selected" is a failure this can see.
 */
public final class SelectionCheck {

    private static int checks;
    private static int failures;

    private SelectionCheck() {
    }

    /**
     * Runs the checks.
     *
     * @return the number of checks made and the number that failed, in that order
     */
    public static int[] run() {
        checks = 0;
        failures = 0;
        System.out.println("== road selection ==");

        doubleClickRule();
        doubleClickDetector();
        singleClickSelectsTheRoad();
        doubleClickSelectsTheSegment();
        togglingRoadsAndSegments();
        finalSelectionAfterRemoval();
        deletingTheSelection();
        classAppliesToTheWholeSelection();
        directionAdvancesEachRoad();
        lengthAndLayerOfTheSelection();
        namingTheWholeSelection();
        snapshotAndRestore();
        return new int[]{checks, failures};
    }

    // ------------------------------------------------------------- the gesture

    /**
     * The click/double-click rule at each of its bounds.
     *
     * <p>Every bound is checked at the value and one past it: exactly three hundred milliseconds is a
     * double click and three hundred and one is not; exactly four pixels is the same place and five is
     * not. The diagonal case is the one a per-axis comparison would get wrong, so it is here
     * separately: three pixels on each axis is more than four pixels of movement.
     */
    private static void doubleClickRule() {
        SelectionGesture.Press first =
                new SelectionGesture.Press(0, true, 1_000L, 10.0, 20.0);

        expect("a press with nothing before it is a single click",
                SelectionGesture.classify(null, 0, true, 1_000L, 10.0, 20.0)
                        == SelectionGesture.Target.SINGLE);
        expect("a second press at the same spot and instant is a double click",
                SelectionGesture.classify(first, 0, true, 1_000L, 10.0, 20.0)
                        == SelectionGesture.Target.DOUBLE);
        expect("three hundred milliseconds later is still one gesture",
                SelectionGesture.classify(first, 0, true, 1_300L, 10.0, 20.0)
                        == SelectionGesture.Target.DOUBLE);
        expect("three hundred and one milliseconds later is two clicks",
                SelectionGesture.classify(first, 0, true, 1_301L, 10.0, 20.0)
                        == SelectionGesture.Target.SINGLE);
        expect("a timestamp that went backwards is not a double click",
                SelectionGesture.classify(first, 0, true, 999L, 10.0, 20.0)
                        == SelectionGesture.Target.SINGLE);

        expect("exactly four pixels away is still the same place",
                SelectionGesture.classify(first, 0, true, 1_200L, 14.0, 20.0)
                        == SelectionGesture.Target.DOUBLE);
        expect("five pixels away is a different place",
                SelectionGesture.classify(first, 0, true, 1_200L, 15.0, 20.0)
                        == SelectionGesture.Target.SINGLE);
        expect("three pixels on each axis is more than four pixels of movement",
                SelectionGesture.classify(first, 0, true, 1_200L, 13.0, 23.0)
                        == SelectionGesture.Target.SINGLE);

        expect("a different button is not the same gesture",
                SelectionGesture.classify(first, 1, true, 1_200L, 10.0, 20.0)
                        == SelectionGesture.Target.SINGLE);
        expect("a previous press without Shift does not start a double click",
                SelectionGesture.classify(new SelectionGesture.Press(0, false, 1_000L, 10.0, 20.0),
                        0, true, 1_200L, 10.0, 20.0) == SelectionGesture.Target.SINGLE);
        expect("and Shift released before the second press does not either",
                SelectionGesture.classify(first, 0, false, 1_200L, 10.0, 20.0)
                        == SelectionGesture.Target.SINGLE);
    }

    /**
     * The stateful half: a real sequence of presses, including the ones that break a pair.
     *
     * <p>The state machine is where "a third press" is decided, and the answer is checked to be
     * "a fresh single click": a pair is consumed when it completes, so this cannot invent a triple
     * click meaning nobody asked for. A press that is not part of the gesture -- an ordinary click of
     * the same button without Shift, or a click of the other button -- has to break the pair, or two
     * selections made a moment apart would be read as one.
     */
    private static void doubleClickDetector() {
        SelectionGesture.Detector detector = new SelectionGesture.Detector();
        expect("the first press is a single click",
                detector.press(0, true, 0L, 100.0, 100.0) == SelectionGesture.Target.SINGLE);
        expect("the second within the window completes the double",
                detector.press(0, true, 200L, 101.0, 100.0) == SelectionGesture.Target.DOUBLE);
        expect("and the pair is spent, so a third press starts again",
                detector.press(0, true, 300L, 101.0, 100.0)
                        == SelectionGesture.Target.SINGLE);

        detector.reset();
        expect("reset forgets the press before it",
                detector.previousPress() == null
                        && detector.press(0, true, 0L, 0.0, 0.0) == SelectionGesture.Target.SINGLE);

        detector.reset();
        expect("a press without Shift starts a single",
                detector.press(0, false, 0L, 0.0, 0.0) == SelectionGesture.Target.SINGLE);
        expect("and the Shift press after it does not pair with it",
                detector.press(0, true, 100L, 0.0, 0.0) == SelectionGesture.Target.SINGLE);

        detector.reset();
        expect("a different button in between breaks the pair",
                detector.press(0, true, 0L, 0.0, 0.0) == SelectionGesture.Target.SINGLE
                        && detector.press(1, true, 100L, 0.0, 0.0)
                        == SelectionGesture.Target.SINGLE);
        expect("while two presses of that button do pair",
                detector.press(1, true, 200L, 0.0, 0.0) == SelectionGesture.Target.DOUBLE);
    }

    // ------------------------------------------------------------ the selection

    /** A single click selects the whole bent street, and the whole street is what the map gets. */
    private static void singleClickSelectsTheRoad() {
        RoadNetwork net = new RoadNetwork();
        List<RoadSegment> street = street(net);
        RoadSegment other = road(net, 500, 500, 600, 500);
        RoadEditor editor = new RoadEditor(net);

        editor.selectSegment(street.get(1).id());
        expect("a click on one piece selects all three pieces of the street ("
                        + editor.selectedChain().size() + ")",
                editor.selectedChain().size() == 3);
        expect("and the piece clicked is the main selection",
                editor.selectedSegmentId() == street.get(1).id());
        expect("the whole street is in the selection",
                editor.selectedChain().containsAll(Set.of(street.get(0).id(), street.get(1).id(),
                        street.get(2).id())));
        expect("and the road standing away from it is not",
                !editor.selectedChain().contains(other.id()));

        editor.selectSegment(other.id());
        expect("clicking the standalone road selects just it",
                editor.selectedChain().equals(Set.of(other.id())));

        editor.selectSegment(9_999);
        expect("a click on no road at all selects nothing",
                editor.selectedChain().isEmpty()
                        && editor.selectedSegmentId() == RoadSegment.NO_SEGMENT);

        RoadNode place = net.addNode(700, 64, 700, RoadNode.Type.POI, "place");
        editor.selectSegment(other.id());
        editor.selectNode(place.id());
        expect("selecting a node drops the road selection",
                editor.selectedChain().isEmpty()
                        && editor.selectedNodeId() == place.id());
    }

    /** A double click narrows the selection to the one segment the cursor was on. */
    private static void doubleClickSelectsTheSegment() {
        RoadNetwork net = new RoadNetwork();
        List<RoadSegment> street = street(net);
        RoadEditor editor = new RoadEditor(net);

        editor.selectSingleSegment(street.get(1).id());
        expect("a double click selects only the segment it landed on",
                editor.selectedChain().equals(Set.of(street.get(1).id())));
        expect("and that segment is the main selection",
                editor.selectedSegmentId() == street.get(1).id());
        expect("with no node selection left behind",
                editor.selectedNodeId() == RoadSegment.NO_NODE);

        editor.selectSingleSegment(9_999);
        expect("a double click on no segment selects nothing",
                editor.selectedChain().isEmpty()
                        && editor.selectedSegmentId() == RoadSegment.NO_SEGMENT);
    }

    /**
     * Adding and removing whole roads and single segments, including what happens to the main segment.
     *
     * <p>The main segment is the one the readout and the naming panel speak from, so it must always
     * name something that is in the selection: removing it has to move it to a remaining member, and
     * emptying the selection has to clear it rather than leaving an id the map cannot find.
     */
    private static void togglingRoadsAndSegments() {
        RoadNetwork net = new RoadNetwork();
        List<RoadSegment> street = street(net);
        RoadSegment other = road(net, 500, 500, 600, 500);
        RoadEditor editor = new RoadEditor(net);
        RoadSegment middle = street.get(1);

        editor.selectSingleSegment(middle.id());
        expect("adding a half-selected road completes it rather than removing it",
                editor.toggleChainSelection(middle.id()) && editor.selectedChain().size() == 3);
        expect("and the road just added is the main selection",
                editor.selectedSegmentId() == middle.id());

        expect("adding the whole road again takes it out",
                editor.toggleChainSelection(middle.id()) && editor.selectedChain().isEmpty());
        expect("and an emptied selection has no main segment",
                editor.selectedSegmentId() == RoadSegment.NO_SEGMENT);

        editor.selectSegment(street.get(0).id());
        expect("a second road can be added to the selection",
                editor.toggleChainSelection(other.id()) && editor.selectedChain().size() == 4);
        expect("with the added road as the main selection",
                editor.selectedSegmentId() == other.id());

        expect("and removing it leaves the first road selected",
                editor.toggleChainSelection(other.id()) && editor.selectedChain().size() == 3);
        expect("with the main selection moved back to a road that is still in it",
                editor.selectedSegmentId() != RoadSegment.NO_SEGMENT
                        && editor.selectedChain().contains(editor.selectedSegmentId()));

        expect("single segments toggle one at a time",
                editor.toggleSingleSegmentSelection(street.get(2).id())
                        && editor.selectedChain().size() == 2);
        expect("and toggling the same one again puts it back",
                editor.toggleSingleSegmentSelection(street.get(2).id())
                        && editor.selectedChain().size() == 3);

        editor.clearSelection();
        expect("a cleared selection accepts a single road as its first member",
                editor.toggleChainSelection(other.id())
                        && editor.selectedChain().equals(Set.of(other.id())));
    }

    /** The main selection after the member it named is removed, and after the set empties. */
    private static void finalSelectionAfterRemoval() {
        RoadNetwork net = new RoadNetwork();
        List<RoadSegment> street = street(net);
        RoadSegment other = road(net, 500, 500, 600, 500);
        RoadEditor editor = new RoadEditor(net);

        editor.selectSingleSegment(street.get(1).id());
        editor.toggleChainSelection(other.id());
        expect("two members are selected", editor.selectedChain().size() == 2);
        expect("with the second one main", editor.selectedSegmentId() == other.id());

        editor.toggleChainSelection(other.id());
        expect("removing the main member moves the main selection to the remaining one",
                editor.selectedSegmentId() == street.get(1).id()
                        && editor.selectedChain().equals(Set.of(street.get(1).id())));

        editor.toggleSingleSegmentSelection(street.get(1).id());
        expect("removing the last member empties the selection and its main segment",
                editor.selectedChain().isEmpty()
                        && editor.selectedSegmentId() == RoadSegment.NO_SEGMENT);
    }

    // -------------------------------------------------------------- deletions

    /**
     * Delete removes exactly the selection, and nothing that was not selected.
     *
     * <p>The whole-road selection keeps its old meaning: the bent street goes whole. The
     * segment-level selection means what it says: one piece is cut out and the other two stay, now as
     * two roads of their own. Both go through the same undo mechanism, which is checked to put the
     * network back in one step.
     */
    private static void deletingTheSelection() {
        RoadNetwork net = new RoadNetwork();
        List<RoadSegment> street = street(net);
        RoadSegment other = road(net, 500, 500, 600, 500);
        RoadSegment untouched = road(net, 900, 900, 1_000, 900);
        RoadEditor editor = new RoadEditor(net);
        int before = net.segmentCount();

        editor.selectSegment(street.get(0).id());
        editor.toggleChainSelection(other.id());
        expect("two whole roads are selected", editor.selectedChain().size() == 4);
        expect("delete removes them both", editor.deleteSelection());
        expect("leaving only the road that was not selected ("
                        + net.segmentCount() + " segments)",
                net.segmentCount() == 1 && net.segment(untouched.id()) != null);
        expect("and nothing is selected any more",
                editor.selectedChain().isEmpty()
                        && editor.selectedSegmentId() == RoadSegment.NO_SEGMENT);

        editor.undo();
        expect("one undo puts both roads back (" + net.segmentCount() + " segments)",
                net.segmentCount() == before && net.segment(street.get(1).id()) != null);

        // The segment-level selection: cut one piece out of the middle of the street.
        RoadNetwork one = new RoadNetwork();
        List<RoadSegment> oneStreet = street(one);
        RoadSegment oneOther = road(one, 500, 500, 600, 500);
        RoadEditor oneEditor = new RoadEditor(one);
        oneEditor.selectSingleSegment(oneStreet.get(1).id());
        expect("a single-segment selection deletes only that segment",
                oneEditor.deleteSelection() && one.segmentCount() == 3);
        expect("and the other two pieces of the street survive",
                one.segment(oneStreet.get(0).id()) != null
                        && one.segment(oneStreet.get(2).id()) != null
                        && one.segment(oneOther.id()) != null);
        expect("the piece that was deleted is gone",
                one.segment(oneStreet.get(1).id()) == null);
        oneEditor.undo();
        expect("and undo restores the street whole",
                one.segmentCount() == 4 && one.segment(oneStreet.get(1).id()) != null);
    }

    // ------------------------------------------------------------ bulk edits

    /** Re-classing the selection re-classes every selected road, and only those. */
    private static void classAppliesToTheWholeSelection() {
        RoadNetwork net = new RoadNetwork();
        List<RoadSegment> street = street(net);
        RoadSegment other = road(net, 500, 500, 600, 500);
        RoadSegment untouched = road(net, 900, 900, 1_000, 900);
        RoadEditor editor = new RoadEditor(net);

        editor.selectSegment(street.get(1).id());
        editor.toggleChainSelection(other.id());
        expect("re-classing the selection is accepted",
                editor.setSegmentClass(editor.selectedSegmentId(), RoadClass.HIGHWAY));
        expect("every piece of the selected street changed class",
                street.get(0).roadClass() == RoadClass.HIGHWAY
                        && street.get(1).roadClass() == RoadClass.HIGHWAY
                        && street.get(2).roadClass() == RoadClass.HIGHWAY);
        expect("and so did the other selected road",
                other.roadClass() == RoadClass.HIGHWAY);
        expect("while the road that was not selected kept its class",
                untouched.roadClass() == RoadClass.ROAD);

        editor.undo();
        // Read back out of the network: undo replaces its contents with copies, so the segment
        // objects the fixture holds are no longer the ones it is made of. The ids survive, which is
        // what the selection and the map address the roads by.
        expect("one undo puts both roads' classes back",
                net.segment(street.get(0).id()).roadClass() == RoadClass.ROAD
                        && net.segment(street.get(1).id()).roadClass() == RoadClass.ROAD
                        && net.segment(other.id()).roadClass() == RoadClass.ROAD);

        // A selection that is one segment still re-classes the road it is part of: a street cannot be
        // half one class, because the class is what makes it one street.
        RoadNetwork single = new RoadNetwork();
        List<RoadSegment> singleStreet = street(single);
        RoadEditor singleEditor = new RoadEditor(single);
        singleEditor.selectSingleSegment(singleStreet.get(2).id());
        singleEditor.setSegmentClass(singleEditor.selectedSegmentId(), RoadClass.PATH);
        expect("a single-segment selection re-classes its whole road",
                singleStreet.get(0).roadClass() == RoadClass.PATH
                        && singleStreet.get(2).roadClass() == RoadClass.PATH);
    }

    /**
     * The one-way switch advances every selected road from its own state.
     *
     * <p>This is the check that tells "each road advances" from "the main road's next state is
     * written onto all of them". The two roads start in different states on purpose: one already
     * one-way, one still two-way, so a bulk write of the main road's answer would show as the
     * two-way one staying two-way or the one-way one being flipped rather than moved on.
     */
    private static void directionAdvancesEachRoad() {
        RoadNetwork net = new RoadNetwork();
        List<RoadSegment> street = street(net);
        RoadSegment other = road(net, 500, 500, 600, 500);
        RoadEditor editor = new RoadEditor(net);
        RoadSegment main = street.get(0);

        editor.selectSegment(main.id());
        editor.setChainDirection(main.id(), RoadDirection.FORWARD);
        expect("the fixture street starts one-way along itself",
                editor.chainDirection(main.id()) == RoadDirection.FORWARD);
        expect("and the other road starts two-way",
                editor.chainDirection(other.id()) == RoadDirection.TWO_WAY);

        editor.selectSegment(main.id());
        editor.toggleChainSelection(other.id());
        expect("switching the selection is accepted", editor.cycleSelectedDirection());
        expect("the one-way road moved on to the other direction",
                editor.chainDirection(main.id()) == RoadDirection.BACKWARD);
        expect("and the two-way road became one-way along itself",
                editor.chainDirection(other.id()) == RoadDirection.FORWARD);
        expect("every piece of the selected street is one-way now",
                street.get(0).oneWay() && street.get(1).oneWay() && street.get(2).oneWay());

        editor.undo();
        expect("one undo puts both roads' directions back",
                editor.chainDirection(main.id()) == RoadDirection.FORWARD
                        && editor.chainDirection(other.id()) == RoadDirection.TWO_WAY);

        // The set form as well as the cycle: one direction named by the press, written onto every
        // selected road with each one oriented along its own walk.
        editor.selectSegment(main.id());
        editor.toggleChainSelection(other.id());
        expect("setting a direction on the selection is accepted",
                editor.setChainDirection(main.id(), RoadDirection.FORWARD));
        expect("and both selected roads run one-way along themselves",
                editor.chainDirection(main.id()) == RoadDirection.FORWARD
                        && editor.chainDirection(other.id()) == RoadDirection.FORWARD);
        expect("including every piece of the bent street",
                net.segment(street.get(0).id()).oneWay()
                        && net.segment(street.get(1).id()).oneWay()
                        && net.segment(street.get(2).id()).oneWay());
    }

    /** The readout and the storey act on the whole selection, and the length counts each road once. */
    private static void lengthAndLayerOfTheSelection() {
        RoadNetwork net = new RoadNetwork();
        List<RoadSegment> street = street(net);
        RoadSegment other = road(net, 500, 500, 600, 500);
        RoadEditor editor = new RoadEditor(net);

        expect("a selection of nothing has no length", editor.selectedLength() == 0.0);

        editor.selectSegment(street.get(0).id());
        expect("one bent street of three hundred blocks reads three hundred",
                editor.selectedLength() == 300.0);
        editor.toggleChainSelection(other.id());
        expect("both roads read four hundred blocks ("
                        + editor.selectedLength() + ")",
                editor.selectedLength() == 400.0);

        editor.selectSingleSegment(street.get(0).id());
        editor.toggleSingleSegmentSelection(street.get(2).id());
        expect("two segments of one road count that road once, not twice",
                editor.selectedLength() == 300.0);

        editor.clearSelection();
        editor.selectSegment(street.get(0).id());
        editor.toggleChainSelection(other.id());
        expect("moving the selection to a storey is accepted",
                editor.setChainLayer(editor.selectedSegmentId(), 3));
        expect("every piece of every selected road moved",
                street.get(0).layer() == 3 && street.get(1).layer() == 3
                        && street.get(2).layer() == 3 && other.layer() == 3);
        expect("the readout says that storey", editor.selectedLayer() == 3);
        expect("and the drawing storey followed it", editor.activeLayer() == 3);

        editor.undo();
        // Out of the network again: see the class check above for why the fixture's own objects are
        // stale once undo has swapped the network's contents.
        expect("one undo puts every piece back on the surface",
                net.segment(street.get(0).id()).layer() == 0
                        && net.segment(street.get(1).id()).layer() == 0
                        && net.segment(street.get(2).id()).layer() == 0
                        && net.segment(other.id()).layer() == 0);
    }

    /** Naming acts on every selected road, and only those. */
    private static void namingTheWholeSelection() {
        RoadNetwork net = new RoadNetwork();
        List<RoadSegment> street = street(net);
        RoadSegment other = road(net, 500, 500, 600, 500);
        RoadSegment untouched = road(net, 900, 900, 1_000, 900);
        RoadEditor editor = new RoadEditor(net);

        editor.selectSegment(street.get(0).id());
        editor.toggleChainSelection(other.id());
        expect("naming the selection is accepted",
                editor.setRoadName(editor.selectedSegmentId(), "Main Street"));
        expect("every piece of every selected road is named",
                "Main Street".equals(street.get(0).name())
                        && "Main Street".equals(street.get(2).name())
                        && "Main Street".equals(other.name()));
        expect("and the road that was not selected is still unnamed",
                untouched.name() == null);

        expect("naming and setting the storey together is accepted",
                editor.setRoadNameAndLayer(editor.selectedSegmentId(), "Second Street", 1));
        expect("which renames every selected road",
                "Second Street".equals(street.get(1).name())
                        && "Second Street".equals(other.name()));
        expect("and moves every selected road onto the storey",
                street.get(1).layer() == 1 && other.layer() == 1
                        && untouched.layer() == 0);

        editor.undo();
        // Out of the network, as above: the undo put back copies of the segments.
        expect("one undo takes the rename and the storey back",
                "Main Street".equals(net.segment(street.get(1).id()).name())
                        && net.segment(street.get(1).id()).layer() == 0
                        && "Main Street".equals(net.segment(other.id()).name())
                        && net.segment(other.id()).layer() == 0);
    }

    /**
     * The snapshot a double-clicking multi-select gesture takes and gives back.
     *
     * <p>This is the mechanism behind "the second click of a double selects the one segment": the
     * first click's whole-road toggle is applied immediately and its replacement remembered, and the
     * double restores that replacement before toggling the segment. So a restore has to give back
     * exactly the roads that were selected, with the right main segment, and it has to drop ids the
     * network no longer has rather than hand the map a stale question.
     */
    private static void snapshotAndRestore() {
        RoadNetwork net = new RoadNetwork();
        List<RoadSegment> street = street(net);
        RoadSegment other = road(net, 500, 500, 600, 500);
        RoadEditor editor = new RoadEditor(net);

        editor.selectSegment(street.get(1).id());
        RoadEditor.SelectionSnapshot snapshot = editor.snapshotSelection();
        editor.toggleChainSelection(other.id());
        expect("the toggle changed the selection", editor.selectedChain().size() == 4);

        editor.restoreSelection(snapshot);
        expect("the snapshot gives the street back and nothing else",
                editor.selectedChain().size() == 3
                        && editor.selectedChain().containsAll(Set.of(street.get(0).id(),
                        street.get(1).id(), street.get(2).id())));
        expect("with the segment it was taken on as the main selection",
                editor.selectedSegmentId() == street.get(1).id());

        RoadEditor.SelectionSnapshot empty = editor.snapshotSelection();
        editor.clearSelection();
        editor.restoreSelection(empty);
        expect("restoring a selection of one road brings it back",
                editor.selectedChain().size() == 3);

        // A snapshot taken before a deletion names segments that no longer exist by the time it is
        // restored; those are dropped rather than kept.
        RoadEditor.SelectionSnapshot doomed = editor.snapshotSelection();
        editor.deleteSelection();
        expect("the deletion removed the street", net.segmentCount() == 1);
        editor.restoreSelection(doomed);
        expect("and restoring the snapshot drops the ids that are gone",
                editor.selectedChain().isEmpty()
                        && editor.selectedSegmentId() == RoadSegment.NO_SEGMENT);
    }

    // ----------------------------------------------------------------- helpers

    /** A street of three hundred blocks drawn as three right-angled pieces. */
    private static List<RoadSegment> street(RoadNetwork net) {
        return List.of(
                road(net, 0, 0, 100, 0),
                road(net, 100, 0, 100, 100),
                road(net, 100, 100, 200, 100));
    }

    /** One road segment between two nodes, joining existing nodes where the position already has one. */
    private static RoadSegment road(RoadNetwork net, int x1, int z1, int x2, int z2) {
        return road(net, RoadClass.ROAD, x1, z1, x2, z2);
    }

    private static RoadSegment road(RoadNetwork net, RoadClass roadClass, int x1, int z1,
                                     int x2, int z2) {
        RoadSegment segment = net.newSegment(roadClass, 64, 2);
        segment.addVertex(x1, z1);
        segment.addVertex(x2, z2);
        segment.setFromNode(nodeAt(net, x1, z1).id());
        segment.setToNode(nodeAt(net, x2, z2).id());
        net.addSegment(segment);
        return segment;
    }

    private static RoadNode nodeAt(RoadNetwork net, int x, int z) {
        RoadNode existing = net.nearestNode(x, z, 0.0);
        return existing != null ? existing : net.addNode(x, 64, z, RoadNode.Type.JUNCTION, null);
    }

    private static void expect(String what, boolean condition) {
        checks++;
        if (!condition) {
            failures++;
        }
        System.out.println((condition ? "  ok   " : "  FAIL ") + what);
    }
}
