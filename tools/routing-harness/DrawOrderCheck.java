import bili.dongsz.howtogo.client.RoadElement;
import bili.dongsz.howtogo.road.RoadClass;
import bili.dongsz.howtogo.road.RoadSegment;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * Which road the map paints on top of which.
 *
 * <h2>Why this is a check about a list and not about a picture</h2>
 * The in-game map cannot be photographed from here, but the thing that decides what covers what can be
 * rendered down to one question: in what order does the provider hand the frame's elements over? Xaero
 * draws them strictly in that order, in one pass, so a storey painted later is a storey painted on top.
 * {@link RoadElement#sortForDraw} is that decision, made once, in pure Java with no Minecraft in it --
 * which is what lets the rule that a bridge is drawn over the road it crosses be pinned down here
 * instead of by looking at a screen.
 *
 * <p>The elements are built from real segments through the same {@link RoadElement#of} the provider
 * calls: an element is a wrapper around a segment and there is nothing else it can wrap.
 */
public final class DrawOrderCheck {

    private static int checks;
    private static int failures;

    private DrawOrderCheck() {
    }

    public static int[] run() {
        checks = 0;
        failures = 0;
        System.out.println("== the order the map paints roads in ==");

        // Deliberately in an order no sort could produce: two storeys below the surface, three on it, one
        // above, and the ids in a jumble so "ascending" cannot be confused with "as built".
        List<RoadElement> buffer = new ArrayList<>();
        RoadElement tunnelDeep = element(3, -2);
        RoadElement tunnel = element(7, -1);
        RoadElement surfaceA = element(1, 0);
        RoadElement surfaceB = element(9, 0);
        RoadElement rail = element(5, 0);
        RoadElement bridge = element(2, 1);
        buffer.add(bridge);
        buffer.add(surfaceB);
        buffer.add(tunnelDeep);
        buffer.add(RoadElement.MARKS);
        buffer.add(rail);
        buffer.add(tunnel);
        buffer.add(RoadElement.ROUTE);
        buffer.add(surfaceA);
        buffer.add(RoadElement.EDIT_UI);
        buffer.add(RoadElement.LABELS);
        int before = buffer.size();

        RoadElement.sortForDraw(buffer);

        expect("nothing is lost or duplicated by the sort", buffer.size() == before
                && new HashSet<>(buffer).size() == before);

        int lastRoad = -1;
        for (RoadElement road : List.of(tunnelDeep, tunnel, surfaceA, surfaceB, rail, bridge)) {
            lastRoad = Math.max(lastRoad, buffer.indexOf(road));
        }
        int firstOverlay = Integer.MAX_VALUE;
        for (RoadElement overlay : List.of(RoadElement.MARKS, RoadElement.ROUTE, RoadElement.EDIT_UI,
                RoadElement.LABELS)) {
            firstOverlay = Math.min(firstOverlay, buffer.indexOf(overlay));
        }
        expect("every road is drawn before every overlay", firstOverlay > lastRoad);
        expect("and the overlays keep the order they were added in, so the route stays over the marks "
                        + "and the names over everything",
                buffer.indexOf(RoadElement.MARKS) < buffer.indexOf(RoadElement.ROUTE)
                        && buffer.indexOf(RoadElement.ROUTE) < buffer.indexOf(RoadElement.EDIT_UI)
                        && buffer.indexOf(RoadElement.EDIT_UI) < buffer.indexOf(RoadElement.LABELS));
        expect("the storeys come out ascending, so a lower road is never painted over a higher one",
                buffer.indexOf(tunnelDeep) < buffer.indexOf(tunnel)
                        && buffer.indexOf(tunnel) < buffer.indexOf(surfaceA)
                        && buffer.indexOf(surfaceA) < buffer.indexOf(bridge));
        expect("roads on one storey keep the order they had, so the frame does not churn",
                buffer.indexOf(surfaceB) < buffer.indexOf(rail) && buffer.indexOf(rail) < buffer.indexOf(surfaceA));

        // The whole range at once: the storeys a road may be on run from -32 to 32, and a sort that only
        // works for the small numbers a test usually uses is a sort that silently stops working one day.
        List<RoadElement> full = new ArrayList<>();
        int[] layers = {32, 0, -32, 12, -12, 1, -1};
        for (int layer : layers) {
            full.add(element(100 + layer, layer));
        }
        RoadElement.sortForDraw(full);
        boolean ascending = true;
        for (int i = 1; i < full.size(); i++) {
            ascending &= full.get(i - 1).segment().layer() <= full.get(i).segment().layer();
        }
        expect("the whole -32..32 range sorts ascending", ascending);

        // One road alone, and an empty frame: the two cases a provider meets on a fresh world.
        List<RoadElement> single = new ArrayList<>(List.of(element(1, 4)));
        RoadElement.sortForDraw(single);
        expect("a single road is left alone", single.size() == 1 && single.get(0).segment().layer() == 4);
        List<RoadElement> empty = new ArrayList<>();
        RoadElement.sortForDraw(empty);
        expect("an empty frame stays empty", empty.isEmpty());

        System.out.println(failures == 0 ? "  draw order ok (" + checks + " checks)"
                : "  draw order FAILED: " + failures + " of " + checks);
        return new int[]{checks, failures};
    }

    private static RoadElement element(int id, int layer) {
        RoadSegment segment = RoadSegment.of(id, RoadClass.ROAD, 64, 0, 0, 10, 0);
        segment.setLayer(layer);
        return RoadElement.of(segment);
    }

    private static void expect(String what, boolean condition) {
        checks++;
        if (!condition) {
            failures++;
        }
        System.out.println((condition ? "  ok   " : "  FAIL ") + what);
    }
}
