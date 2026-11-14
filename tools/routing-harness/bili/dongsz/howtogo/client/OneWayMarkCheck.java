package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.road.RoadClass;
import bili.dongsz.howtogo.road.RoadDirection;
import bili.dongsz.howtogo.road.RoadNetwork;
import bili.dongsz.howtogo.road.RoadNode;
import bili.dongsz.howtogo.road.RoadSegment;

import java.util.ArrayList;
import java.util.List;

/**
 * The one-way marks the maps this mod draws itself put on a road: how big they are, where they go,
 * and which way they point.
 *
 * <h2>Why this is checked off the game</h2>
 * A direction mark is drawn straight into the maps' vertex buffer, so nothing on screen can be
 * inspected from here -- but the two things the player reported about it are arithmetic, and both
 * were real faults that compiled and looked plausible. First, the head was sized at 2.6 of the road's
 * half-width, which makes a mark wider than the road it sits on: from play, "it looks like an icon
 * beside the road". Second, the spacing was walked <em>per segment piece</em> and stopped after eight
 * marks, so a long road drawn as one piece was marked for about a tenth of its length: from play,
 * "only part of the one-way road has arrows". Both are visible in what is written to the sink, which
 * is what this check measures.
 *
 * <h2>What is held</h2>
 * That a mark's widest point stays inside the road's drawn width; that a long one-piece road is
 * marked along its whole length rather than its first stretch; that a road too thin to hold a mark
 * carries none rather than one wider than itself; that a two-way road carries none; and that the
 * marks point the way the segment allows travel, for both of the directions it can allow.
 */
public final class OneWayMarkCheck {

    /** The viewer's own road half-width in screen pixels, the widest one this mod draws. */
    private static final double HALF_WIDTH = 2.4;

    /** The head's colour, which is how its triangles are told from the road's own quads. */
    private static final int INK_R = 0xF4;
    private static final int INK_G = 0xF8;
    private static final int INK_B = 0xFF;
    /** The rim's colour, the dark shape the head is painted on so it reads on every road colour. */
    private static final int RIM_R = 0x10;
    private static final int RIM_G = 0x14;
    private static final int RIM_B = 0x18;

    private static int checks;
    private static int failures;

    private OneWayMarkCheck() {
    }

    /**
     * Runs the checks.
     *
     * @return the number of checks made and the number that failed, in that order
     */
    public static int[] run() {
        checks = 0;
        failures = 0;
        System.out.println("== one-way marks on this mod's own maps ==");
        insideTheRoad();
        alongTheWholeRoad();
        tooThinToMark();
        twoWayCarriesNone();
        pointingTheWayTravelIsAllowed();
        if (failures == 0) {
            System.out.println("  one-way marks ok (" + checks + " checks)");
        } else {
            System.out.println("  one-way marks FAILED: " + failures + " of " + checks);
        }
        return new int[]{checks, failures};
    }

    /**
     * Every corner of every mark is inside the road's drawn width.
     *
     * <p>The mark's rim is wider than its head, so the rim is what is measured: its wings are the
     * widest part of a direction mark, and "the arrow should be within the road's width" is only true
     * if they are.
     */
    private static void insideTheRoad() {
        Capture capture = marks(RoadDirection.FORWARD, HALF_WIDTH);
        List<double[][]> heads = capture.triangles(INK_R, INK_G, INK_B);
        expect("a one-way road carries marks (" + heads.size() + " heads)", !heads.isEmpty());
        List<double[][]> rim = capture.triangles(RIM_R, RIM_G, RIM_B);
        expect("and every head has a rim to stand off the road's colour (" + rim.size() + ")",
                rim.size() == heads.size());
        double widest = 0;
        for (List<double[][]> triangles : List.of(heads, rim)) {
            for (double[][] triangle : triangles) {
                for (double[] point : triangle) {
                    widest = Math.max(widest, Math.abs(point[1]));
                }
            }
        }
        expect("no part of a mark reaches outside the road's width (widest "
                        + round(widest) + " of " + round(HALF_WIDTH) + " half-widths)",
                widest <= HALF_WIDTH + 1.0E-6);
    }

    /**
     * A long road is marked along its whole length.
     *
     * <p>This is the fault that read as "only part of the one-way road has arrows". The road here is
     * one piece six hundred pixels long, so a per-piece cap of eight marks (with the spacing it had)
     * would leave everything past the first fifth of it unmarked -- and this check would fail on the
     * far end.
     */
    private static void alongTheWholeRoad() {
        Capture capture = marks(RoadDirection.FORWARD, HALF_WIDTH);
        List<double[][]> heads = capture.triangles(INK_R, INK_G, INK_B);
        double first = Double.MAX_VALUE;
        double last = -Double.MAX_VALUE;
        for (double[][] triangle : heads) {
            double centre = triangle[0][0] + triangle[1][0] + triangle[2][0];
            centre /= 3.0;
            first = Math.min(first, centre);
            last = Math.max(last, centre);
        }
        expect("the marks are spread along the whole road (from " + round(first) + " to "
                        + round(last) + " of 600)", first < 60 && last > 540);
        expect("and there are enough of them to read as a direction (" + heads.size() + ")",
                heads.size() >= 20);
    }

    /** A road too thin to hold a mark carries none, rather than one wider than the road. */
    private static void tooThinToMark() {
        Capture capture = marks(RoadDirection.FORWARD, 0.7);
        expect("a road too thin to hold a mark carries none",
                capture.triangles(INK_R, INK_G, INK_B).isEmpty());
    }

    /** A two-way road has no direction to mark, so it gets nothing. */
    private static void twoWayCarriesNone() {
        Capture capture = marks(RoadDirection.TWO_WAY, HALF_WIDTH);
        expect("a two-way road carries no marks",
                capture.triangles(INK_R, INK_G, INK_B).isEmpty());
    }

    /**
     * The marks point the way travel is allowed, twice over: once for a piece appended in vertex
     * order and once for one appended against it.
     *
     * <p>Which way a piece runs in the projected polyline is the chain walk's business, not the
     * segment's: the arrow that comes out of the xor has to be the allowed direction either way, and
     * an arrow against the only direction a road can be travelled is worse than no arrow.
     */
    private static void pointingTheWayTravelIsAllowed() {
        expect("a forward one-way road is marked towards its far end",
                pointsAlong(marks(RoadDirection.FORWARD, HALF_WIDTH), true));
        expect("and a backward one is marked towards its near end",
                pointsAlong(marks(RoadDirection.BACKWARD, HALF_WIDTH), false));
    }

    // ------------------------------------------------------------------ drawing

    /** A straight one-way road six hundred pixels long, drawn by the maps' own pass. */
    private static Capture marks(RoadDirection direction, double halfWidth) {
        RoadNetwork network = new RoadNetwork();
        RoadNode start = network.addNode(0, 64, 0, RoadNode.Type.ENDPOINT, null);
        RoadNode end = network.addNode(600, 64, 0, RoadNode.Type.ENDPOINT, null);
        RoadSegment segment = network.newSegment(RoadClass.ROAD, 64, 2);
        segment.addVertex(0, 0);
        segment.addVertex(600, 0);
        segment.setFromNode(start.id());
        segment.setToNode(end.id());
        segment.setDirection(direction);
        network.addSegment(segment);

        Capture capture = new Capture();
        HudDraw.emitNetwork(capture, network, 1, 0, 0, 0, 1, 0, halfWidth, 0xFF,
                -10_000, -10_000, 10_000, 10_000);
        return capture;
    }

    /** Whether every head's tip is on the given side of its own back edge. */
    private static boolean pointsAlong(Capture capture, boolean towardsFarEnd) {
        List<double[][]> heads = capture.triangles(INK_R, INK_G, INK_B);
        if (heads.isEmpty()) {
            return false;
        }
        for (double[][] triangle : heads) {
            double tipX = triangle[0][0];
            double backX = (triangle[1][0] + triangle[2][0]) / 2.0;
            boolean away = tipX > backX;
            if (away != towardsFarEnd) {
                return false;
            }
        }
        return true;
    }

    private static String round(double value) {
        return Double.toString(Math.round(value * 10.0) / 10.0);
    }

    private static void expect(String what, boolean condition) {
        checks++;
        if (!condition) {
            failures++;
        }
        System.out.println((condition ? "  ok   " : "  FAIL ") + what);
    }

    /**
     * What the emit pass wrote: position and colour of every vertex, so the marks can be told from
     * the road by ink alone.
     */
    private static final class Capture implements HudDraw.VertexSink {

        private final List<double[]> points = new ArrayList<>();
        private final List<int[]> colours = new ArrayList<>();

        @Override
        public void vertex(double x, double y, int r, int g, int b, int a) {
            points.add(new double[]{x, y});
            colours.add(new int[]{r, g, b, a});
        }

        /** Every triangle written in the given ink, as its three corners. */
        List<double[][]> triangles(int r, int g, int b) {
            List<double[][]> triangles = new ArrayList<>();
            for (int at = 0; at + 3 < points.size(); at += 4) {
                int[] colour = colours.get(at);
                if (colour[0] != r || colour[1] != g || colour[2] != b) {
                    continue;
                }
                triangles.add(new double[][]{points.get(at), points.get(at + 1), points.get(at + 2)});
            }
            return triangles;
        }
    }
}
