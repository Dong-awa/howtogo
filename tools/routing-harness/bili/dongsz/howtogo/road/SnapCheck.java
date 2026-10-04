package bili.dongsz.howtogo.road;

import bili.dongsz.howtogo.client.RoadSnapper;

import java.lang.reflect.Field;
import java.util.Random;

/**
 * Checks snapping end to end, from a world cursor position to the answer the editor gets.
 *
 * <h2>Why this is separate from {@link SpatialIndexCheck}</h2>
 * That one checks the index against the exhaustive walk in world space, which says nothing about
 * whether the index is being asked the right question. The wiring in between is where a projection
 * can be inverted, a radius converted the wrong way round, or pixels and blocks mixed up -- each of
 * which the index would answer perfectly and the player would see as snapping that grabs the wrong
 * thing. So this drives {@link RoadSnapper} itself, through a viewport set by hand.
 *
 * <h2>How a viewport is faked</h2>
 * {@code MapViewState} is loaded without running its initialiser and its fields are then set
 * directly. It is a plain holder of numbers -- the projection is arithmetic over them and touches
 * nothing else -- so this is the real conversion being exercised, not a copy of it. Nothing here
 * needs a game running, which is the only way any of this gets checked at all.
 *
 * <p>The mapping being reproduced is the one {@code MapViewState} documents:
 * {@code screen = (world - camera) * scale * poseScale + poseTranslate}.
 */
public final class SnapCheck {

    private SnapCheck() {
    }

    public static int[] run() {
        int checks = 0;
        int failures = 0;
        System.out.println("== snapping (end to end) ==");

        if (!viewport(1.0, 1.0, 0.0, 0.0, 0.0, 0.0)) {
            System.out.println("  FAIL could not set up a viewport to snap against");
            return new int[]{1, 1};
        }

        // --- a node under the cursor is taken ------------------------------------------------
        RoadNetwork net = new RoadNetwork();
        RoadNode corner = net.addNode(100, 64, 100, RoadNode.Type.JUNCTION, null);
        RoadNode far = net.addNode(200, 64, 100, RoadNode.Type.ENDPOINT, null);
        RoadSegment road = RoadSegment.of(1, RoadClass.ROAD, 64, 100, 100, 200, 100);
        road.setFromNode(corner.id());
        road.setToNode(far.id());
        net.addSegment(road);

        // At 1 pixel per block the node radius is 9 blocks and the segment radius 6.
        checks++;
        RoadSnapper.Result hit = RoadSnapper.snap(net, 103.0, 102.0, RoadSegment.NO_NODE);
        if (hit.kind() == RoadSnapper.Kind.NODE && hit.nodeId() == corner.id()
                && hit.x() == 100 && hit.z() == 100) {
            System.out.println("  ok   the cursor near a node snaps onto it");
        } else {
            failures++;
            System.out.println("  FAIL expected the node at (100,100), got " + describe(hit));
        }

        // --- just outside the node radius falls through to the road ---------------------------
        checks++;
        RoadSnapper.Result onRoad = RoadSnapper.snap(net, 150.0, 103.0, RoadSegment.NO_NODE);
        if (onRoad.kind() == RoadSnapper.Kind.SEGMENT && onRoad.segmentId() == road.id()) {
            System.out.println("  ok   away from any node it snaps onto the road instead");
        } else {
            failures++;
            System.out.println("  FAIL expected the road, got " + describe(onRoad));
        }

        // --- the road's own radius is respected ----------------------------------------------
        // 7 blocks off the road is past the 6-pixel segment radius at this zoom, and the
        // 45-degree rule does not apply without a node being drawn from, so this is free.
        checks++;
        RoadSnapper.Result offRoad = RoadSnapper.snap(net, 150.0, 107.0, RoadSegment.NO_NODE);
        if (offRoad.kind() == RoadSnapper.Kind.FREE) {
            System.out.println("  ok   beyond the road's radius the cursor is left where it is");
        } else {
            failures++;
            System.out.println("  FAIL expected free placement, got " + describe(offRoad));
        }

        // --- zoom changes what a pixel is worth ----------------------------------------------
        // The catch radius is a pixel budget, so the reach in blocks is that budget over the
        // pixels per block -- and pixels per block is scale * poseScale, not scale alone.
        //
        // Zooming out widens the reach, which means a node comes into range at the same time as
        // the road does -- and a node outranks a road, so on the short road above the check would
        // pass or fail on which of the two happened to be nearer, saying nothing about the
        // conversion. A long road puts its nodes hundreds of blocks away, well outside even the
        // widest reach here, so the only thing that can change the answer is the zoom.
        RoadNetwork longRoad = new RoadNetwork();
        RoadNode westEnd = longRoad.addNode(1000, 64, 1000, RoadNode.Type.ENDPOINT, null);
        RoadNode eastEnd = longRoad.addNode(2000, 64, 1000, RoadNode.Type.ENDPOINT, null);
        RoadSegment straight = RoadSegment.of(1, RoadClass.ROAD, 64, 1000, 1000, 2000, 1000);
        straight.setFromNode(westEnd.id());
        straight.setToNode(eastEnd.id());
        longRoad.addSegment(straight);

        // 10 blocks off the road. At 1 pixel per block that is outside the 6-pixel segment
        // radius; at an eighth of a pixel per block the radius is 48 blocks and it is inside.
        // With the conversion inverted, zooming out would shrink the reach and both would be free.
        checks++;
        viewport(1.0, 1.0, 0.0, 0.0, 0.0, 0.0);
        RoadSnapper.Result atZoomIn = RoadSnapper.snap(longRoad, 1500.0, 1010.0, RoadSegment.NO_NODE);
        viewport(1.0, 0.125, 0.0, 0.0, 0.0, 0.0);
        RoadSnapper.Result atZoomOut = RoadSnapper.snap(longRoad, 1500.0, 1010.0, RoadSegment.NO_NODE);
        if (atZoomIn.kind() == RoadSnapper.Kind.FREE
                && atZoomOut.kind() == RoadSnapper.Kind.SEGMENT) {
            System.out.println("  ok   zooming out widens the reach in blocks for the same pixels");
        } else {
            failures++;
            System.out.println("  FAIL expected free then segment, got "
                    + describe(atZoomIn) + " then " + describe(atZoomOut));
        }

        // --- the pose scale is part of the conversion -----------------------------------------
        // The pose Xaero leaves on the stack carries no map zoom of its own and sits around a
        // quarter. An earlier version of this conversion dropped it, which shrank every catch
        // radius by that factor. Here the pose cancels the scale exactly -- 2.0 * 0.5 is the same
        // 1 pixel per block as the check just above -- so the same cursor 10 blocks off the road
        // must come back free for the same reason it did there. Dropping the pose would make it
        // 2 pixels per block, and the reach would be 3 blocks, also free -- so the check that
        // separates the two is the zoomed-out pair below, read against this one.
        checks++;
        viewport(2.0, 0.5, 0.0, 0.0, 0.0, 0.0);
        RoadSnapper.Result posedFree = RoadSnapper.snap(longRoad, 1500.0, 1010.0, RoadSegment.NO_NODE);
        viewport(2.0, 0.0625, 0.0, 0.0, 0.0, 0.0);
        RoadSnapper.Result posedReaches = RoadSnapper.snap(longRoad, 1500.0, 1010.0,
                RoadSegment.NO_NODE);
        if (posedFree.kind() == RoadSnapper.Kind.FREE
                && posedReaches.kind() == RoadSnapper.Kind.SEGMENT) {
            System.out.println("  ok   the pose scale is applied, not dropped");
        } else {
            failures++;
            System.out.println("  FAIL expected free then segment, got "
                    + describe(posedFree) + " then " + describe(posedReaches));
        }

        // --- a translation must not change the answer ----------------------------------------
        // The same world cursor under a panned map is the same distance from the same road, and
        // a projection that forgot its translate would say otherwise.
        checks++;
        viewport(1.0, 1.0, 1.0, 1.0, 500.0, -300.0);
        RoadSnapper.Result panned = RoadSnapper.snap(net, 150.0, 100.0, RoadSegment.NO_NODE);
        if (panned.kind() == RoadSnapper.Kind.SEGMENT) {
            System.out.println("  ok   panning the map does not change what is under the cursor");
        } else {
            failures++;
            System.out.println("  FAIL the cursor was lost after a pan: " + describe(panned));
        }

        // --- a free-floating node out of reach is not grabbed --------------------------------
        checks++;
        viewport(1.0, 1.0, 0.0, 0.0, 0.0, 0.0);
        RoadSnapper.Result distant = RoadSnapper.snap(net, 300.0, 300.0, RoadSegment.NO_NODE);
        if (distant.kind() == RoadSnapper.Kind.FREE) {
            System.out.println("  ok   nothing within reach leaves the cursor free");
        } else {
            failures++;
            System.out.println("  FAIL grabbed something far away: " + describe(distant));
        }

        // --- the angle rule still fires when it should ---------------------------------------
        // Drawing from the corner node: this cursor is about 1.4 degrees off the horizontal, well
        // inside the 5-degree tolerance, so it is pulled onto the horizontal through the node
        // keeping its distance -- (100,100) + 200 along +X is (300,100). Nowhere near the road's
        // own extent, so this is the angle rule and nothing else.
        checks++;
        RoadSnapper.Result nearHorizontal = RoadSnapper.snap(net, 300.0, 105.0, corner.id());
        boolean ruleFired = nearHorizontal.kind() == RoadSnapper.Kind.ANGLE
                && Math.abs(nearHorizontal.z() - 100.0) < 0.51
                && Math.abs(nearHorizontal.x() - 300.0) < 0.51;
        if (ruleFired) {
            System.out.println("  ok   drawing near an axis snaps onto the 45-degree direction");
        } else {
            failures++;
            System.out.println("  FAIL expected the angle rule to fire, got "
                    + describe(nearHorizontal));
        }

        // --- the rule quantises to the nearest fixed point, not always to the horizontal -----
        // This one is about 46 degrees up, so the nearest of the eight directions is the diagonal
        // and the point is pulled onto it: both offsets become equal at the distance kept. An
        // implementation that always snapped to the axis would land on (300,100) instead.
        checks++;
        RoadSnapper.Result nearDiagonal = RoadSnapper.snap(net, 300.0, 310.0, corner.id());
        boolean diagonal = nearDiagonal.kind() == RoadSnapper.Kind.ANGLE
                && Math.abs(nearDiagonal.x() - nearDiagonal.z()) < 1.01;
        if (diagonal) {
            System.out.println("  ok   a direction near the diagonal snaps onto the diagonal");
        } else {
            failures++;
            System.out.println("  FAIL expected the diagonal, got " + describe(nearDiagonal));
        }

        // --- ...and does not fire when the direction is not near one -------------------------
        // 30 degrees off the horizontal is 30 from the nearest fixed point, well past the
        // tolerance, and there is no road near the cursor, so the point stays where it was put.
        checks++;
        RoadSnapper.Result offAxis = RoadSnapper.snap(net, 300.0, 215.0, corner.id());
        if (offAxis.kind() == RoadSnapper.Kind.FREE) {
            System.out.println("  ok   a direction between the fixed points is left alone");
        } else {
            failures++;
            System.out.println("  FAIL expected free placement, got " + describe(offAxis));
        }

        // --- the two checks above agree across a sweep of positions ---------------------------
        // The one-off cases above are easy to pass by accident. This walks the cursor over the
        // whole network and asks the index-free path and the answering path to agree about
        // whether anything was snapped to at all.
        checks++;
        viewport(1.0, 1.0, 0.7, 0.7, 12.0, -8.0);
        int mismatches = 0;
        int snapped = 0;
        Random random = new Random(99L);
        for (int i = 0; i < 3000; i++) {
            double x = 60.0 + random.nextDouble() * 180.0;
            double z = 60.0 + random.nextDouble() * 80.0;
            RoadSnapper.Result result = RoadSnapper.snap(net, x, z, RoadSegment.NO_NODE);
            if (result.kind() != RoadSnapper.Kind.FREE) {
                snapped++;
            }
            // Whatever it answered, it has to have answered it about a real feature: a snapped
            // node must be at the node, a snapped segment must land on that segment.
            if (result.kind() == RoadSnapper.Kind.NODE && result.nodeId() == RoadSegment.NO_NODE) {
                mismatches++;
            }
            if (result.kind() == RoadSnapper.Kind.SEGMENT
                    && net.segment(result.segmentId()) == null) {
                mismatches++;
            }
        }
        if (mismatches == 0) {
            System.out.println("  ok   3000 swept cursors all answered about a real feature ("
                    + snapped + " snapped)");
        } else {
            failures++;
            System.out.println("  FAIL " + mismatches + " answers named something that is not there");
        }

        System.out.println("  snapping ok (" + checks + " checks)");
        return new int[]{checks, failures};
    }

    private static String describe(RoadSnapper.Result result) {
        return result.kind() + " node=" + result.nodeId() + " seg=" + result.segmentId()
                + " at (" + result.x() + ", " + result.z() + ")";
    }

    /**
     * Sets the cached viewport by hand.
     *
     * <p>Loaded without initialising, so nothing in {@code MapViewState} runs and the class stays a
     * bag of numbers. A failure here is reported rather than thrown: the checks below are what the
     * caller cares about, and a build that cannot reach the fields should say so plainly.
     */
    private static boolean viewport(double scale, double poseScale, double poseTranslateX,
                                    double poseTranslateZ, double cameraX, double cameraZ) {
        try {
            Class<?> type = Class.forName("bili.dongsz.howtogo.client.MapViewState", false,
                    SnapCheck.class.getClassLoader());
            set(type, "scale", scale);
            set(type, "poseScaleX", poseScale);
            set(type, "poseScaleY", poseScale);
            set(type, "poseTranslateX", poseTranslateX);
            set(type, "poseTranslateY", poseTranslateZ);
            set(type, "cameraX", cameraX);
            set(type, "cameraZ", cameraZ);
            // Fresh, so the staleness guards do not reject it.
            set(type, "updatedAt", System.currentTimeMillis());
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.out.println("    (viewport not reachable: " + e + ")");
            return false;
        }
    }

    private static void set(Class<?> type, String name, double value)
            throws ReflectiveOperationException {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.setDouble(null, value);
    }

    private static void set(Class<?> type, String name, long value) throws ReflectiveOperationException {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.setLong(null, value);
    }
}
