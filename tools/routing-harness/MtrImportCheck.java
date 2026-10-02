package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.RoadConfig;
import bili.dongsz.howtogo.road.RoadClass;
import bili.dongsz.howtogo.road.RoadNetwork;
import bili.dongsz.howtogo.road.RoadSegment;
import bili.dongsz.howtogo.transit.LineStop;
import bili.dongsz.howtogo.transit.TransitLine;

import java.util.ArrayList;
import java.util.List;

/**
 * Checks the MTR import against a reading that was made up here.
 *
 * <p>In the {@code client} package on purpose: what is being tested is package-private, so that the
 * conversion stays a function of a reading rather than of MTR being installed. It lives with the
 * harness and is compiled the same way; nothing in the mod calls it.
 *
 * <p>What can be checked is everything after the reflection: a reading of MTR's own shapes in, this
 * mod's stops, lines and rail layer out. Whether MTR hands back the shapes this reads is a question
 * only a session with MTR can answer, which is why the reader is written to report what it found
 * rather than to assume it.
 */
public final class MtrImportCheck {

    private static int checks;
    private static int failures;

    private MtrImportCheck() {
    }

    /**
     * Runs the checks.
     *
     * @return the number of checks made and the number that failed, in that order
     */
    public static int[] run() {
        checks = 0;
        failures = 0;
        System.out.println("== MTR import ==");

        MtrClientData.Snapshot reading = syntheticReading();

        MtrTransit.Built built = MtrTransit.build(reading, true);
        expect("one stop per station", built.stops().size() == 3);
        expect("a stop sits at the middle of its station's platforms, not the middle of the station",
                stopAt(built.stops(), 5, 0));
        expect("a station with no platform falls back to the middle of its area",
                stopAt(built.stops(), 100, 0));
        expect("and a station with one platform sits on it", stopAt(built.stops(), 200, 4));

        expect("only the line whose type this mod has a kind for is imported",
                built.lines().size() == 1);
        TransitLine imported = built.lines().get(0);
        expect("as a rail line", imported.kind() == RoadClass.RAIL);
        expect("with its stops in order", imported.stopCount() == 3);
        expect("and an id of its own that cannot collide with the player's",
                imported.id().startsWith("mtr:"));
        expect("the aeroplane line is counted rather than imported", built.skipped() == 1);
        expect("and a stop whose station the client was never sent is counted",
                built.unplaced() == 1);
        System.out.println("   " + built.stops().size() + " stops, " + built.lines().size()
                + " lines, " + built.rails().segmentCount() + " rail segments, "
                + built.rails().nodeCount() + " rail nodes");

        // Rails: three usable ones (two rail, one water) and an aeroplane's, which has no class here.
        expect("the usable rails become segments", built.rails().segmentCount() == 3);
        expect("two rails that meet share their node rather than being joined by a chord",
                built.rails().nodeCount() == 5);
        expect("a boat's rail becomes water", hasClass(built.rails(), RoadClass.WATER));
        expect("a train's rail becomes rail", hasClass(built.rails(), RoadClass.RAIL));
        expect("and an aeroplane's rail becomes nothing at all",
                !hasClass(built.rails(), RoadClass.ICE));
        expect("every segment is marked as read rather than drawn, by its id alone",
                built.rails().segmentsSnapshot().stream().allMatch(RailTrackStore::isOurs));

        // The switch the player is offered: no route marks means no rails, and nothing else changes.
        MtrTransit.Built withoutMarks = MtrTransit.build(reading, false);
        expect("with route marks off no rails are imported",
                withoutMarks.rails().segmentCount() == 0);
        expect("and the stops and lines are the same ones", withoutMarks.lines().size() == 1
                && withoutMarks.stops().size() == 3);

        expect("an empty reading becomes nothing at all",
                MtrTransit.build(MtrClientData.Snapshot.EMPTY, true).lines().isEmpty());

        checkHandshake();

        // A line is told from the player's own by its id alone, which is what lets the planner take
        // both lists and the editor take one, with no second field to keep in step.
        expect("an imported line says so", MtrTransit.isImported(imported));
        expect("and a line the player made does not",
                !MtrTransit.isImported(new TransitLine("mine", "Mine", RoadClass.RAIL)));
        expect("and neither does nothing", !MtrTransit.isImported(null));

        checkMarksSwitch(imported);

        // A boat line: the other kind this mod has a use for.
        MtrClientData.Snapshot boatsOnly = new MtrClientData.Snapshot(
                reading.stations(), reading.platforms(),
                reading.lines().stream().filter(line -> "BOAT".equals(line.mode())).toList(),
                List.of());
        expect("a boat line becomes a water line", MtrTransit.build(boatsOnly, true).lines().stream()
                .allMatch(line -> line.kind() == RoadClass.WATER));

        // A line all of whose stations are outside what the client was sent has no ride in it.
        MtrClientData.Snapshot nothingPlaced = new MtrClientData.Snapshot(
                List.of(), List.of(),
                List.of(new MtrClientData.Line(9, "Far away", "TRAIN", 0, List.of(
                        stop(11, 1, "Alpha"), stop(21, 2, "Beta")))),
                List.of());
        MtrTransit.Built unplaced = MtrTransit.build(nothingPlaced, true);
        expect("a line whose stops the client has not been sent is not offered",
                unplaced.lines().isEmpty());
        expect("and its stops are counted", unplaced.unplaced() == 2);
        expect("while no station means no stops to offer either", unplaced.stops().isEmpty());

        System.out.println(failures == 0 ? "  MTR import ok (" + checks + " checks)"
                : "  MTR import FAILED: " + failures + " of " + checks);
        return new int[]{checks, failures};
    }

    /**
     * The per-line marks switch's own rules.
     *
     * <p>What a line says before anyone has touched the switch, what it says once they have, and that
     * the answer is keyed by MTR's own id rather than by anything the line carries -- an imported line is
     * rebuilt from every reading, so a field on it would last until the player walked to the next
     * station. The file itself is not checked here: the harness has no game directory to write to, which
     * is also why the answers are held in memory for the length of this check.
     */
    private static void checkMarksSwitch(TransitLine imported) {
        long id = MtrTransit.mtrLineId(imported);
        expect("an imported line carries MTR's own id, which is what an answer is kept by", id == 1);
        expect("and a line the player made carries none",
                MtrTransit.mtrLineId(new TransitLine("mine", "Mine", RoadClass.RAIL)) < 0);

        boolean fallback = RoadConfig.mtrAutoRouteMarks();
        MtrMarks.clear(id);
        expect("a line nobody has answered for takes the configured default",
                !MtrMarks.isChosen(id) && MtrTransit.marksEnabled(imported) == fallback);
        expect("and so does a line of the player's own",
                MtrTransit.marksEnabled(new TransitLine("mine", "Mine", RoadClass.RAIL)) == fallback);

        boolean wasAnyOn = MtrMarks.anyOn();
        MtrMarks.toggle(id, true);
        expect("switching a line off is remembered against that line",
                MtrMarks.isChosen(id) && !MtrTransit.marksEnabled(imported));

        MtrMarks.toggle(id, false);
        expect("and switching it back on is remembered too",
                MtrMarks.isChosen(id) && MtrTransit.marksEnabled(imported));
        expect("a line switched on by hand is reason enough to build MTR's tracks",
                MtrMarks.anyOn());

        MtrMarks.clear(id);
        expect("forgetting the answer puts the line back on the default",
                !MtrMarks.isChosen(id) && MtrTransit.marksEnabled(imported) == fallback);
        expect("and takes that reason away again", MtrMarks.anyOn() == wasAnyOn);

        expect("a listed answer beats the default",
                !MtrMarks.decide(false, true, true) && MtrMarks.decide(true, false, false));
        expect("an id on both lists counts as on", MtrMarks.decide(true, true, false));
        expect("and an id on neither takes the default",
                MtrMarks.decide(false, false, true) && !MtrMarks.decide(false, false, false));

        // The classes a mark can be, and the modes that can reach them. A boat line's mark is water, so
        // a rule that only asked about the rail would leave its switch doing nothing.
        expect("a transit ride can reach MTR's marks",
                RailTrackStore.movesOnMtrMarks(bili.dongsz.howtogo.route.TravelMode.TRANSIT));
        expect("while walking cannot",
                !RailTrackStore.movesOnMtrMarks(bili.dongsz.howtogo.route.TravelMode.WALK));
        expect("and neither can driving",
                !RailTrackStore.movesOnMtrMarks(bili.dongsz.howtogo.route.TravelMode.DRIVE));
        expect("and no mode at all cannot either", !RailTrackStore.movesOnMtrMarks(null));
    }

    /**
     * Whether the shapes this mod reads are the ones the MTR jar on the classpath actually has.
     *
     * <p>The one part of the integration that no made-up reading can check: whether MTR still calls
     * its stations what it called them, and still keeps them where it kept them. With an MTR jar on
     * the classpath every class, field and method the reader looks up is looked up for real here, with
     * no game running -- which is otherwise a thing only a player in a world finds out, from a log
     * line that says the data was unavailable and not which name was wrong.
     *
     * <p>Skipped when there is no MTR jar to read, because then there is nothing to check and a
     * failure would be a failure of the machine rather than of the mod.
     */
    private static void checkHandshake() {
        if (!MtrClientData.classesPresent()) {
            System.out.println("  --   no MTR jar on the classpath: the handshake is not checked here");
            return;
        }
        expect("every class, field and method the reader looks up is the one MTR has",
                MtrClientData.bind());
    }

    /** A reading shaped like the one MTR describes: stations, platforms, routes and rails. */
    private static MtrClientData.Snapshot syntheticReading() {
        List<MtrClientData.Station> stations = List.of(
                station(1, "Alpha", "TRAIN", 0, 0),
                station(2, "Beta", "TRAIN", 100, 0),
                station(3, "Gamma", "BOAT", 200, 0));
        List<MtrClientData.Platform> platforms = List.of(
                platform(11, 1, "1", "TRAIN", 0, 0),
                platform(12, 1, "2", "TRAIN", 10, 0),
                platform(31, 3, "1", "BOAT", 200, 4));

        List<MtrClientData.Line> lines = new ArrayList<>();
        // Three stops, all of whose stations the client knows.
        lines.add(new MtrClientData.Line(1, "Line 1", "TRAIN", 0xFF0000, List.of(
                stop(11, 1, "Alpha"), stop(21, 2, "Beta"), stop(31, 3, "Gamma"))));
        // An aeroplane: a line this mod has no kind for.
        lines.add(new MtrClientData.Line(2, "Flight 1", "AIRPLANE", 0x00FF00, List.of(
                stop(11, 1, "Alpha"), stop(21, 2, "Beta"))));
        // A line with one stop the client has never been sent, which leaves it with one placed stop.
        lines.add(new MtrClientData.Line(3, "Line 3", "TRAIN", 0x0000FF, List.of(
                stop(11, 1, "Alpha"), stop(99, 999, "Somewhere else"))));

        List<MtrClientData.Track> tracks = List.of(
                track("a", "TRAIN", 0, 0, 10, 0),
                track("b", "TRAIN", 10, 0, 20, 0),
                track("c", "AIRPLANE", 100, 0, 110, 0),
                track("d", "BOAT", 200, 0, 210, 0));
        return new MtrClientData.Snapshot(stations, platforms, lines, tracks);
    }

    private static MtrClientData.Station station(long id, String name, String mode, int x, int z) {
        return new MtrClientData.Station(id, name, mode, 0, x, 64, z, x - 5, 64, z - 5, x + 5, 70,
                z + 5);
    }

    private static MtrClientData.Platform platform(long id, long stationId, String name, String mode,
                                                   int x, int z) {
        return new MtrClientData.Platform(id, stationId, name, mode, x, 64, z);
    }

    private static MtrClientData.Stop stop(long platformId, long stationId, String name) {
        return new MtrClientData.Stop(platformId, stationId, name, "");
    }

    private static MtrClientData.Track track(String hexId, String mode, int fromX, int fromZ,
                                             int toX, int toZ) {
        return new MtrClientData.Track(hexId, mode, 64, new double[]{fromX, toX},
                new double[]{fromZ, toZ});
    }

    private static boolean stopAt(List<LineStop> stops, int x, int z) {
        for (LineStop stop : stops) {
            if (stop.x() == x && stop.z() == z) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasClass(RoadNetwork network, RoadClass roadClass) {
        for (RoadSegment segment : network.segmentsSnapshot()) {
            if (segment.roadClass() == roadClass) {
                return true;
            }
        }
        return false;
    }

    private static void expect(String what, boolean condition) {
        checks++;
        if (!condition) {
            failures++;
        }
        System.out.println((condition ? "  ok   " : "  FAIL ") + what);
    }
}
