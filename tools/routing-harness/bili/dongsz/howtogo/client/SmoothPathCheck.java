package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.road.RoadClass;
import bili.dongsz.howtogo.road.RoadCurve;
import bili.dongsz.howtogo.road.RoadNetwork;
import bili.dongsz.howtogo.road.RoadSegment;
import bili.dongsz.howtogo.road.RoadStorage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Checks the staged geometry: the smoothed display curve, its storage record, and the stitching of
 * the machine-read layers into whole roads instead of one-block pieces.
 *
 * <p>Same arrangement as the other checks: this is the mod's own code compiled without a game, so it
 * can test the pure parts of the pipeline. What it cannot test is whether the drawn result looks
 * smooth -- that is a screen -- but the properties that make it smooth are here: the curve keeps its
 * endpoints, stays close to the block line, and its turns are smaller than the staircase's.
 */
public final class SmoothPathCheck {

    private static int checks;
    private static int failures;

    private SmoothPathCheck() {
    }

    public static int[] run() {
        checks = 0;
        failures = 0;
        System.out.println("== smooth display geometry ==");
        curves();
        storage();
        mtrStitching();
        roadWeaverCurve();
        System.out.println(failures == 0 ? "  smooth geometry ok (" + checks + " checks)"
                : "  smooth geometry FAILED: " + failures + " of " + checks);
        return new int[]{checks, failures};
    }

    /** The curve's own promises: endpoints pinned, close to the line, and smoother than it. */
    private static void curves() {
        // A 45-degree block staircase: every vertex turns 90 degrees, and yet the line it stands for is
        // straight. This is the shape that used to come out as a ripple -- the simplifier kept every
        // step and the corner rounding then turned each one into an S.
        List<double[]> staircase = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            staircase.add(new double[]{i, i});
            staircase.add(new double[]{i + 1, i});
        }
        RoadCurve curve = RoadCurve.smooth(staircase, RoadCurve.GRID_TOLERANCE);
        expect("a staircase can be smoothed", curve != null);
        if (curve == null) {
            return;
        }
        expect("both ends are pinned to the road's ends",
                close(curve.x(0), staircase.get(0)[0]) && close(curve.z(0), staircase.get(0)[1])
                        && close(curve.x(curve.size() - 1), staircase.get(staircase.size() - 1)[0])
                        && close(curve.z(curve.size() - 1),
                        staircase.get(staircase.size() - 1)[1]));
        expect("a block staircase is recognised as the straight diagonal it is, not drawn step by step",
                curve.size() == 2);
        double smoothedTurning = turning(curvePoints(curve));
        expect("so it carries no turning at all (" + round(smoothedTurning) + " degrees)",
                smoothedTurning < 1.0);

        // Every block vertex of the original must stay near the drawn line: a smoother road may cut
        // a corner, but not leave the road it belongs to.
        double worst = 0;
        for (double[] point : staircase) {
            worst = Math.max(worst, distanceToCurve(point, curve));
        }
        expect("while staying within the staircase's own half-diagonal of every vertex (" + round(worst)
                + ")", worst <= 0.75);

        // A real right-angled corner, sampled every block. The rounding is a fixed radius now, so the
        // curve must not swing blocks wide of the corner the way a quarter of a long edge did.
        List<double[]> corner = new ArrayList<>();
        for (int x = 0; x <= 40; x++) {
            corner.add(new double[]{x, 0});
        }
        for (int z = 1; z <= 40; z++) {
            corner.add(new double[]{40, z});
        }
        RoadCurve bend = RoadCurve.smooth(corner, RoadCurve.GRID_TOLERANCE);
        expect("a right-angled corner is still a corner", bend != null && bend.size() > 2);
        if (bend != null) {
            double cornerDistance = distanceToCurve(new double[]{40, 0}, bend);
            expect("and its rounding is a radius, not a fraction of the road (" + round(cornerDistance)
                    + " blocks from the corner)", cornerDistance < 1.33);
            double worstCorner = 0;
            for (double[] point : corner) {
                worstCorner = Math.max(worstCorner, distanceToCurve(point, bend));
            }
            expect("while every block of the road stays under the drawn line (" + round(worstCorner)
                    + ")", worstCorner <= 1.0);
        }

        // A switchback: at a turn this sharp a 1.25-block fillet would cut over a block off the
        // elbow, i.e. more than a third of the road's width. It is left sharp, so the drawn line runs
        // through the corner itself and the stroke's round join covers the notch.
        List<double[]> switchback = new ArrayList<>();
        for (int x = 0; x <= 20; x++) {
            switchback.add(new double[]{x, 0});
        }
        double turnRadians = Math.toRadians(140);
        for (int step = 1; step <= 14; step++) {
            switchback.add(new double[]{20 + Math.cos(turnRadians) * step,
                    Math.sin(turnRadians) * step});
        }
        RoadCurve elbow = RoadCurve.smooth(switchback, RoadCurve.GRID_TOLERANCE);
        if (elbow != null) {
            double elbowDistance = distanceToCurve(new double[]{20, 0}, elbow);
            expect("a switchback is left sharp rather than rounded into a shortcut ("
                    + round(elbowDistance) + " blocks off the corner)", elbowDistance < 0.15);
        } else {
            expect("a switchback has a curve", false);
        }

        // Geometry that is already in world coordinates must keep its precision: rounding to blocks
        // first is what put a half-block wobble on every diagonal of a machine-read track.
        List<double[]> precise = new ArrayList<>();
        for (int i = 0; i <= 40; i++) {
            double angle = (i / 40.0) * Math.PI / 2;
            precise.add(new double[]{20.0 * Math.sin(angle), 20.0 * (1.0 - Math.cos(angle))});
        }
        RoadCurve fine = RoadCurve.precise(precise);
        expect("a precise curve can be smoothed", fine != null && fine.size() >= 2);
        if (fine != null) {
            double off = 0;
            for (double[] point : precise) {
                off = Math.max(off, distanceToCurve(point, fine));
            }
            expect("and follows the curve it was given (" + round(off) + " blocks off)",
                    off <= RoadCurve.PRECISE_TOLERANCE + 0.01);
            double offGrid = 0;
            for (int i = 0; i < fine.size(); i++) {
                offGrid = Math.max(offGrid, Math.abs(fine.x(i) - Math.rint(fine.x(i))));
                offGrid = Math.max(offGrid, Math.abs(fine.z(i) - Math.rint(fine.z(i))));
            }
            expect("and is not snapped to whole blocks (" + round(offGrid) + " off the grid)", offGrid > 0.01);
        }

        RoadSegment straight = RoadSegment.of(1, RoadClass.ROAD, 64, 0, 0, 100, 0);
        RoadCurve pinned = RoadCurve.of(straight);
        expect("a two-point road has a curve with just its two ends",
                pinned != null && pinned.size() == 2
                        && close(pinned.x(1), 100) && close(pinned.z(1), 0));
        RoadSegment single = new RoadSegment(2, RoadClass.ROAD, 64, 2);
        single.addVertex(5, 5);
        expect("and a one-point segment has none", RoadCurve.of(single) == null);
    }

    /** The record survives a save, and a file without one still loads. */
    private static void storage() {
        RoadNetwork net = new RoadNetwork();
        RoadSegment segment = net.newSegment(RoadClass.ROAD, 64, 3);
        segment.addVertex(0, 0);
        segment.addVertex(50, 0);
        segment.addVertex(50, 50);
        segment.setFromNode(net.addNode(0, 64, 0, bili.dongsz.howtogo.road.RoadNode.Type.ENDPOINT,
                null).id());
        segment.setToNode(net.addNode(50, 64, 50, bili.dongsz.howtogo.road.RoadNode.Type.ENDPOINT,
                null).id());
        RoadCurve curve = RoadCurve.of(segment);
        segment.setCurve(curve);
        net.addSegment(segment);

        try {
            Path file = Files.createTempFile("howtogo-curve", ".json");
            try {
                expect("a network with a display curve can be saved", RoadStorage.save(file, net));
                RoadNetwork loaded = RoadStorage.load(file);
                RoadSegment read = loaded.segment(segment.id());
                expect("and the curve comes back with it", read != null && read.curve() != null
                        && read.curve().size() == curve.size()
                        && close(read.curve().x(1), curve.x(1))
                        && close(read.curve().z(1), curve.z(1)));

                // A file from before the field existed: no curve, and the road still loads.
                Path legacy = Files.createTempFile("howtogo-curve-legacy", ".json");
                String json = "{\"version\":1,\"nodes\":["
                        + "{\"id\":1,\"x\":0,\"y\":64,\"z\":0,\"type\":\"JUNCTION\"},"
                        + "{\"id\":2,\"x\":50,\"y\":64,\"z\":0,\"type\":\"ENDPOINT\"}],\"segments\":["
                        + "{\"id\":10,\"roadClass\":\"ROAD\",\"from\":1,\"to\":2,\"y\":64,"
                        + "\"xs\":[0,50],\"zs\":[0,0]}]}";
                Files.write(legacy, json.getBytes(StandardCharsets.UTF_8));
                RoadNetwork old = RoadStorage.load(legacy);
                RoadSegment oldSegment = old.segment(10);
                expect("a file without a curve loads with no curve, not an error",
                        oldSegment != null && oldSegment.curve() == null);
                Files.deleteIfExists(legacy);
            } finally {
                Files.deleteIfExists(file);
            }
        } catch (IOException failed) {
            expect("the storage check could run (" + failed.getMessage() + ")", false);
        }
    }

    /** MTR hands its railway over one short rail piece at a time; the layer has to join the runs. */
    private static void mtrStitching() {
        List<MtrClientData.Track> tracks = new ArrayList<>();
        // Three collinear pieces, then a branch at the joint between the second and the third.
        tracks.add(track(0, 0, 10, 0));
        tracks.add(track(10, 0, 20, 0));
        tracks.add(track(20, 0, 30, 0));
        tracks.add(track(20, 0, 20, 10));
        MtrClientData.Snapshot reading = new MtrClientData.Snapshot(List.of(), List.of(), List.of(),
                tracks);
        RoadNetwork layer = MtrTransit.railLayerForCheck(reading);
        expect("four one-piece rails become three runs, not four pieces",
                layer.segmentCount() == 3);
        expect("and their joints become one node each, shared by the branches",
                layer.nodeCount() == 4);
        double longest = 0;
        boolean allCurved = true;
        for (RoadSegment segment : layer.segments()) {
            longest = Math.max(longest, segment.length());
            if (segment.vertexCount() >= 3 && segment.curve() == null) {
                allCurved = false;
            }
        }
        expect("the collinear pair is one twenty-block stretch", close(longest, 20));
        expect("and a run with a bend carries a display curve", allCurved);

        // Two rails that do not touch must stay two pieces.
        MtrClientData.Snapshot apart = new MtrClientData.Snapshot(List.of(), List.of(), List.of(),
                List.of(track(0, 0, 10, 0), track(20, 0, 30, 0)));
        expect("rails with a gap are not stitched together",
                MtrTransit.railLayerForCheck(apart).segmentCount() == 2);

        // A line handed over in the middle first: the run has to grow at both ends, not only at the
        // far one, or the piece before the seed would be left as its own stub.
        MtrClientData.Snapshot middleFirst = new MtrClientData.Snapshot(List.of(), List.of(),
                List.of(), List.of(track(10, 0, 20, 0), track(0, 0, 10, 0), track(20, 0, 30, 0)));
        RoadNetwork whole = MtrTransit.railLayerForCheck(middleFirst);
        expect("a run grows backwards as well as forwards",
                whole.segmentCount() == 1 && whole.segmentsSnapshot().get(0).length() == 30.0);

        // A bridge over a railway is two railways, not one: ends that share x and z but not height
        // are not a joint, the same rule the node pass applies to a road bridge.
        MtrClientData.Snapshot stacked = new MtrClientData.Snapshot(List.of(), List.of(), List.of(),
                List.of(trackAt(0, 0, 10, 0, 64), trackAt(10, 0, 30, 0, 70)));
        expect("rail ends at two heights are not stitched into one line",
                MtrTransit.railLayerForCheck(stacked).segmentCount() == 2);
    }

    /** A road read out of RoadWeaver carries the smoothed line, since it is sampled every block. */
    private static void roadWeaverCurve() {
        List<RoadWeaverLayer.Point> points = new ArrayList<>();
        points.add(new RoadWeaverLayer.Point(0, 64, 0));
        points.add(new RoadWeaverLayer.Point(10, 64, 0));
        points.add(new RoadWeaverLayer.Point(10, 64, 10));
        RoadNetwork layer = RoadWeaverLayer.build(
                List.of(new RoadWeaverLayer.Polyline(points, -1, 0)), null);
        RoadSegment segment = layer.segmentsSnapshot().get(0);
        expect("a RoadWeaver road has a smoothed display line",
                segment.curve() != null && segment.curve().size() >= 3);
    }

    private static MtrClientData.Track track(int x0, int z0, int x1, int z1) {
        return trackAt(x0, z0, x1, z1, 64);
    }

    private static MtrClientData.Track trackAt(int x0, int z0, int x1, int z1, int y) {
        return new MtrClientData.Track("t", "TRAIN", y,
                new double[]{x0, x1}, new double[]{z0, z1});
    }

    private static boolean close(double a, double b) {
        return Math.abs(a - b) < 0.01;
    }

    private static double round(double value) {
        return Math.round(value * 10) / 10.0;
    }

    /** Total absolute turning along a polyline, in degrees. */
    private static double turning(List<double[]> points) {
        double total = 0;
        for (int i = 1; i < points.size() - 1; i++) {
            double[] a = points.get(i - 1);
            double[] b = points.get(i);
            double[] c = points.get(i + 1);
            double inX = b[0] - a[0];
            double inZ = b[1] - a[1];
            double outX = c[0] - b[0];
            double outZ = c[1] - b[1];
            double inLength = Math.hypot(inX, inZ);
            double outLength = Math.hypot(outX, outZ);
            if (inLength < 1.0E-9 || outLength < 1.0E-9) {
                continue;
            }
            double cross = inX * outZ - inZ * outX;
            double dot = inX * outX + inZ * outZ;
            total += Math.abs(Math.toDegrees(Math.atan2(cross, dot)));
        }
        return total;
    }

    private static List<double[]> curvePoints(RoadCurve curve) {
        List<double[]> points = new ArrayList<>(curve.size());
        for (int i = 0; i < curve.size(); i++) {
            points.add(new double[]{curve.x(i), curve.z(i)});
        }
        return points;
    }

    private static double distanceToCurve(double[] point, RoadCurve curve) {
        double best = Double.MAX_VALUE;
        for (int i = 1; i < curve.size(); i++) {
            best = Math.min(best, distanceToSegment(point, curve.x(i - 1), curve.z(i - 1),
                    curve.x(i), curve.z(i)));
        }
        return best;
    }

    private static double distanceToSegment(double[] point, double ax, double az, double bx,
                                            double bz) {
        double dx = bx - ax;
        double dz = bz - az;
        double lengthSq = dx * dx + dz * dz;
        double along = lengthSq <= 0 ? 0
                : ((point[0] - ax) * dx + (point[1] - az) * dz) / lengthSq;
        along = Math.max(0, Math.min(1, along));
        return Math.hypot(point[0] - (ax + along * dx), point[1] - (az + along * dz));
    }

    private static void expect(String what, boolean ok) {
        checks++;
        if (ok) {
            System.out.println("  ok   " + what);
        } else {
            failures++;
            System.out.println("  FAIL " + what);
        }
    }
}
