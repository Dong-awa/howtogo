import bili.dongsz.howtogo.road.PlaceKind;
import bili.dongsz.howtogo.road.RoadClass;
import bili.dongsz.howtogo.road.RoadEditor;
import bili.dongsz.howtogo.road.RoadNetwork;
import bili.dongsz.howtogo.road.RoadNode;
import bili.dongsz.howtogo.road.RoadSegment;

/**
 * A place put down on a square that already holds one is that place, not a second one.
 *
 * <h2>What this is for</h2>
 * Pressing P twice on one block used to create two place nodes at the same coordinates. They are
 * indistinguishable on the map and in the destination list, and worse, they are two identities: only
 * one of them can be the node the player renames to a station, while the line editor and the router
 * look at whichever one their walk happens to meet first. The reported symptom was a place that
 * "cannot be put on the route" and a line editor that offered no stops at all.
 *
 * <p>The merge is on the way in: a save that predates it may still hold twins, but no new one can be
 * made. The checks pin the three things that make the merge safe -- the id does not change, the
 * place's kind and name survive, and a press that merges is not an edit (so one undo still takes
 * back the place, rather than leaving a no-op step behind).
 */
public final class PoiCheck {

    private static int checks;
    private static int failures;

    private PoiCheck() {
    }

    public static int[] run() {
        System.out.println("== one place per block ==");

        RoadNetwork net = new RoadNetwork();
        RoadEditor editor = new RoadEditor(net);
        int first = editor.addPoi(10, 64, 10);
        int again = editor.addPoi(10, 64, 10);
        record("a second press on one block selects the place already there",
                first == again && net.nodeCount() == 1,
                net.nodeCount() + " node(s)");
        record("and the place is a place", net.node(first) != null
                && net.node(first).type() == RoadNode.Type.POI,
                net.node(first) == null ? "no node" : net.node(first).type().name());

        // The merged press is not an edit. A second undo step for it would make the player press undo
        // twice to take back one place, and the second press would have changed nothing.
        editor.undo();
        record("one undo takes the place away", net.nodeCount() == 0, net.nodeCount() + " node(s)");
        editor.redo();
        record("and one redo brings it back", net.nodeCount() == 1, net.nodeCount() + " node(s)");

        // A place's kind and name are the player's work: the second press must not reset them.
        RoadEditor kinded = new RoadEditor(net);
        kinded.setStationOverride(nodeId -> true);
        record("the place can be named and made a station",
                kinded.setPlace(first, "Station", PlaceKind.STATION));
        kinded.addPoi(10, 64, 10);
        RoadNode kept = net.node(first);
        record("and a merged press leaves the name and kind alone",
                kept != null && "Station".equals(kept.name())
                        && kept.placeKind() == PlaceKind.STATION,
                kept == null ? "no node" : kept.name() + " / " + kept.placeKind());

        // A place a couple of blocks above the last one is the same place -- the height a place is
        // created at is the player's feet, not the ground under the cursor, so it jitters. A place
        // further up is a different floor and is kept.
        record("a place two blocks up the same block is still the same place",
                kinded.addPoi(10, 66, 10) == first && net.nodeCount() == 1,
                net.nodeCount() + " node(s)");
        int upper = kinded.addPoi(10, 70, 10);
        record("a place on another floor above it is its own place",
                upper != first && net.nodeCount() == 2, net.nodeCount() + " node(s)");

        // Two places at different blocks are always two places.
        RoadEditor far = new RoadEditor(new RoadNetwork());
        record("places on different blocks stay different places",
                far.addPoi(10, 64, 10) != far.addPoi(11, 64, 10));

        // A place standing where a road node is keeps its own node: a place is not a road vertex, and
        // making it one would have Delete remove the road with it. It is a station because it stands
        // on the road, not because it is the junction.
        RoadNetwork onRoad = new RoadNetwork();
        RoadSegment segment = onRoad.newSegment(RoadClass.ROAD, 64, 2);
        segment.addVertex(0, 0);
        segment.addVertex(100, 0);
        RoadNode vertex = onRoad.addNode(0, 64, 0, RoadNode.Type.JUNCTION, null);
        segment.setFromNode(vertex.id());
        segment.setToNode(onRoad.addNode(100, 64, 0, RoadNode.Type.JUNCTION, null).id());
        onRoad.addSegment(segment);
        RoadEditor atNode = new RoadEditor(onRoad);
        int placed = atNode.addPoi(0, 64, 0);
        record("a place put down on a road vertex does not replace the vertex",
                onRoad.node(vertex.id()) != null
                        && onRoad.node(vertex.id()).type() == RoadNode.Type.JUNCTION
                        && onRoad.node(placed).type() == RoadNode.Type.POI,
                onRoad.node(vertex.id()) == null ? "vertex gone"
                        : onRoad.node(vertex.id()).type().name());

        System.out.println(failures == 0
                ? "place merge ok (" + checks + " checks)"
                : "place merge FAILED: " + failures + " of " + checks + " checks");
        return new int[]{checks, failures};
    }

    private static void record(String what, boolean ok) {
        record(what, ok, "");
    }

    private static void record(String what, boolean ok, String detail) {
        checks++;
        if (!ok) {
            failures++;
        }
        System.out.println((ok ? "  ok   " : "  FAIL ") + what
                + (ok || detail.isEmpty() ? "" : " (" + detail + ")"));
    }
}
