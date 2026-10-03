package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.RoadConfig;
import bili.dongsz.howtogo.road.RoadClass;
import bili.dongsz.howtogo.road.RoadNetwork;
import bili.dongsz.howtogo.road.RoadNode;
import bili.dongsz.howtogo.road.RoadSegment;
import bili.dongsz.howtogo.transit.LineStop;
import bili.dongsz.howtogo.transit.TransitLine;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.LongPredicate;

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

        MtrTransit.Built built = build(reading, id -> true);
        expect("one stop per station", built.stops().size() == 4);
        expect("a stop sits at the middle of its station's platforms, not the middle of the station",
                stopAt(built.stops(), 5, 0));
        expect("a station with no platform falls back to the middle of its area",
                stopAt(built.stops(), 100, 0));
        expect("and a station with one platform sits on it", stopAt(built.stops(), 200, 4));
        expect("a station is also offered with the height a stop has no room for",
                stationAt(built.stations(), 300, 64, 0));

        expect("only the lines whose type this mod has a kind for are imported",
                built.lines().size() == 2);
        TransitLine imported = built.lines().get(0);
        expect("as a rail line", imported.kind() == RoadClass.RAIL);
        expect("with its stops in order", imported.stopCount() == 3);
        expect("and an id of its own that cannot collide with the player's",
                imported.id().startsWith("mtr:"));
        expect("the boat line is imported as a water line",
                built.lines().get(1).kind() == RoadClass.WATER);
        expect("the aeroplane line is counted rather than imported", built.skipped() == 1);
        expect("and a stop whose station the client was never sent is counted",
                built.unplaced() == 1);
        System.out.println("   " + built.stops().size() + " stops, " + built.lines().size()
                + " lines, " + built.rails().segmentCount() + " marked segments, "
                + built.rails().nodeCount() + " marked nodes");

        // What is marked is the track the lines run along: one road per pair that could be planned, of
        // the line's own class, and never MTR's rails as a whole.
        expect("a mark is made for each ride that could be planned", built.rails().segmentCount() == 2);
        expect("a train line's mark is rail", hasClass(built.rails(), RoadClass.RAIL));
        expect("and a boat line's is water", hasClass(built.rails(), RoadClass.WATER));
        expect("the mark reaches the station the ride reaches", hasVertexNear(built.rails(), 100, 0));
        expect("and stops where the ride runs out of track",
                !hasVertexNear(built.rails(), 110, 0) && !hasVertexNear(built.rails(), 105, 0));
        expect("every mark is marked as read rather than drawn, by its id alone",
                built.rails().segmentsSnapshot().stream().allMatch(RailTrackStore::isOurs));

        // The switch: nothing marked means nothing added, and the stops and lines are untouched.
        MtrTransit.Built withoutMarks = build(reading, id -> false);
        expect("with every line's marks off nothing is marked at all",
                withoutMarks.rails().segmentCount() == 0);
        expect("and the stops and lines are the same ones", withoutMarks.lines().size() == 2
                && withoutMarks.stops().size() == 4);

        // One line's answer is that line's own: the boat line's mark exists and the train line's does
        // not, which is the whole point of asking per line rather than once for the layer.
        MtrTransit.Built onlyTheBoat = build(reading, id -> id == 4);
        expect("one line's answer marks its track and leaves the other line's alone",
                hasClass(onlyTheBoat.rails(), RoadClass.WATER)
                        && !hasClass(onlyTheBoat.rails(), RoadClass.RAIL));

        expect("an empty reading becomes nothing at all",
                build(MtrClientData.Snapshot.EMPTY, id -> true).lines().isEmpty());

        checkHandshake();

        // A line is told from the player's own by its id alone, which is what lets the planner take
        // both lists and the editor take one, with no second field to keep in step.
        expect("an imported line says so", MtrTransit.isImported(imported));
        expect("and a line the player made does not",
                !MtrTransit.isImported(new TransitLine("mine", "Mine", RoadClass.RAIL)));
        expect("and neither does nothing", !MtrTransit.isImported(null));

        checkMarksSwitch(imported);
        checkLineTracks();
        checkInterchanges();
        checkKnown();

        // A boat line: the other kind this mod has a use for.
        MtrClientData.Snapshot boatsOnly = new MtrClientData.Snapshot(
                reading.stations(), reading.platforms(),
                reading.lines().stream().filter(line -> "BOAT".equals(line.mode())).toList(),
                List.of());
        expect("a boat line becomes a water line",
                build(boatsOnly, id -> true).lines().stream()
                        .allMatch(line -> line.kind() == RoadClass.WATER));

        // A line all of whose stations are outside what the client was sent has no ride in it.
        MtrClientData.Snapshot nothingPlaced = new MtrClientData.Snapshot(
                List.of(), List.of(),
                List.of(new MtrClientData.Line(9, "Far away", "TRAIN", 0, List.of(
                        stop(11, 1, "Alpha"), stop(21, 2, "Beta")))),
                List.of());
        MtrTransit.Built unplaced = build(nothingPlaced, id -> true);
        expect("a line whose stops the client has not been sent is not offered",
                unplaced.lines().isEmpty());
        expect("and its stops are counted", unplaced.unplaced() == 2);
        expect("while no station means no stops to offer either", unplaced.stops().isEmpty());

        System.out.println(failures == 0 ? "  MTR import ok (" + checks + " checks)"
                : "  MTR import FAILED: " + failures + " of " + checks);
        return new int[]{checks, failures};
    }

    /**
     * A reading converted with its own mark-id counter, which is what a caller outside the memory has.
     *
     * <p>The counter is handed in rather than owned by the conversion because a session keeps what it has
     * read and merges the next reading into it: ids that began again at the same base every time would
     * make the second reading's track look like the first's.
     */
    private static MtrTransit.Built build(MtrClientData.Snapshot reading, LongPredicate wantsMarks) {
        return MtrTransit.build(reading, wantsMarks, new int[]{1_500_000_000});
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
        Long id = MtrTransit.mtrLineId(imported);
        expect("an imported line carries MTR's own id, which is what an answer is kept by",
                id != null && id == 1L);
        expect("and a line the player made carries none",
                MtrTransit.mtrLineId(new TransitLine("mine", "Mine", RoadClass.RAIL)) == null);

        // MTR's ids are longs, and half of them are negative. A negative id read as "not ours" is what
        // made the switch beside a line do nothing: the answer was never written, so the marker never
        // moved and the line kept taking the configured default for ever.
        TransitLine negative = new TransitLine("mtr:" + Long.toHexString(-2L), "Negative",
                RoadClass.RAIL);
        Long negativeId = MtrTransit.mtrLineId(negative);
        expect("a line whose MTR id is negative is still MTR's", negativeId != null && negativeId == -2L);

        boolean fallback = RoadConfig.mtrAutoRouteMarks();
        MtrMarks.clear(id);
        expect("a line nobody has answered for takes the configured default",
                !MtrMarks.isChosen(id) && MtrTransit.marksEnabled(imported) == fallback);
        expect("and so does a line of the player's own",
                MtrTransit.marksEnabled(new TransitLine("mine", "Mine", RoadClass.RAIL)) == fallback);

        MtrMarks.toggle(id, true);
        expect("switching a line off is remembered against that line",
                MtrMarks.isChosen(id) && !MtrTransit.marksEnabled(imported));

        MtrMarks.toggle(id, false);
        expect("and switching it back on is remembered too",
                MtrMarks.isChosen(id) && MtrTransit.marksEnabled(imported));

        MtrMarks.clear(id);
        expect("forgetting the answer puts the line back on the default",
                !MtrMarks.isChosen(id) && MtrTransit.marksEnabled(imported) == fallback);

        MtrMarks.toggle(-2L, true);
        expect("and a line with a negative id answers for itself rather than for every such line",
                !MtrTransit.marksEnabled(negative) && MtrTransit.marksEnabled(imported) == fallback);
        MtrMarks.clear(-2L);

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
     * The marking itself: which stretch of MTR's rails a line's marks are.
     *
     * <p>{@link MtrLineTracks} is package-private and takes a rail network and a line, so the rules can
     * be checked directly rather than through a reading: what the marks follow, where they start and
     * end, which class they are, and what a line whose stops are nowhere near its rails gets. This is
     * the part of the integration that replaced "MTR's rails are the roads" with "the ride is the road",
     * and it cannot be seen from the outside at all.
     */
    private static void checkLineTracks() {
        System.out.println("   marking the track a line runs along");

        // A rail that bends, so a mark that follows it can be told from a straight chord between the
        // line's two stops -- which is what a mark built from the stops alone would be.
        RoadNetwork rails = new RoadNetwork();
        addRail(rails, 0, 0, 50, 0);
        addRail(rails, 50, 0, 50, 50);
        addRail(rails, 50, 50, 100, 50);

        TransitLine bent = line("mtr:1", RoadClass.RAIL, 0, 0, 100, 50);
        RoadNetwork marks = MtrLineTracks.of(rails, bent, new int[]{1_500_000_000});
        expect("a line's track is marked", marks.segmentCount() == 1);
        expect("and the mark follows the rails rather than joining the two stops straight",
                hasVertexNear(marks, 50, 0) && hasVertexNear(marks, 50, 50));
        expect("with the class of the line it belongs to", hasClass(marks, RoadClass.RAIL));
        expect("and its ends where the two stops are", hasVertexNear(marks, 0, 0)
                && hasVertexNear(marks, 100, 50));
        expect("drawn from the id space it was handed, so it cannot collide with a drawn road",
                marks.segmentsSnapshot().stream().allMatch(
                        segment -> segment.id() >= 1_500_000_000));

        // A boat line over a waterway: the same rule, and the other class.
        RoadNetwork water = new RoadNetwork();
        addWater(water, 0, 0, 40, 0);
        RoadNetwork boatMarks = MtrLineTracks.of(water,
                line("mtr:2", RoadClass.WATER, 0, 0, 40, 0), new int[]{1_500_000_000});
        expect("a boat line's track is water", hasClass(boatMarks, RoadClass.WATER));

        // A stop a few blocks off the rail: the hop onto the track is not track, so it is not marked.
        RoadNetwork offset = new RoadNetwork();
        addRail(offset, 0, 0, 100, 0);
        RoadNetwork trimmed = MtrLineTracks.of(offset,
                line("mtr:3", RoadClass.RAIL, 0, 18, 100, 18), new int[]{1_500_000_000});
        expect("a stop beside the track is still ridden from", trimmed.segmentCount() == 1);
        expect("and the hop from the stop onto the track is not marked as track",
                !hasVertexNear(trimmed, 0, 18) && hasVertexNear(trimmed, 0, 0)
                        && hasVertexNear(trimmed, 100, 0));

        // A pair with no way between them over the rails this class may use: nothing is invented.
        RoadNetwork far = new RoadNetwork();
        addRail(far, 0, 0, 10, 0);
        RoadNetwork nothing = MtrLineTracks.of(far,
                line("mtr:4", RoadClass.RAIL, 0, 0, 900, 900), new int[]{1_500_000_000});
        expect("a pair the rails cannot join contributes no mark", nothing.segmentCount() == 0);

        // Two lines over one stretch of rail: each is marked, and neither mark reuses the other's ids.
        int[] counter = {1_500_000_000};
        RoadNetwork first = MtrLineTracks.of(rails, bent, counter);
        RoadNetwork second = MtrLineTracks.of(rails, bent, counter);
        expect("a second line over the same rails is marked as well",
                first.segmentCount() == 1 && second.segmentCount() == 1);
        expect("and the two marks do not share an id",
                first.segmentsSnapshot().get(0).id() != second.segmentsSnapshot().get(0).id());

        // What marking costs, over a rail network the size of the window MTR sends around a player and
        // a line long enough to matter: it plans one ride per neighbouring pair, and it runs wherever a
        // reading is first asked for -- which can be the render thread, in the middle of a map draw.
        RoadNetwork longRail = new RoadNetwork();
        for (int i = 0; i < 300; i++) {
            addRail(longRail, i * 4, 0, (i + 1) * 4, 0);
        }
        TransitLine longLine = new TransitLine("mtr:5", "Long", RoadClass.RAIL);
        for (int i = 0; i <= 12; i++) {
            longLine.addStop(LineStop.ofStation("stop " + i, i * 100, 0));
        }
        long startedAt = System.nanoTime();
        RoadNetwork longMarks = MtrLineTracks.of(longRail, longLine, new int[]{1_500_000_000});
        long millis = Math.round((System.nanoTime() - startedAt) / 1_000_000.0);
        System.out.println("   " + longRail.segmentCount() + " rails, 12 pairs marked in " + millis
                + " ms");
        expect("every pair along it is marked", longMarks.segmentCount() == 12);
        expect("and marking a line over a network this size stays quick (under 1000 ms)",
                millis < 1000);
    }

    /**
     * What is kept of a reading, and why it has to be kept at all.
     *
     * <p>MTR sends a client only what is near it, so a reading on its own is a window that closes behind
     * the player: used on its own, the lines vanish from the planner and the editor as they walk away and
     * the track the marks were cut from is gone. These checks walk a session through three readings --
     * near a line, away from everything, along the line -- and hold the memory to what it should have.
     */
    private static void checkKnown() {
        System.out.println("   what is kept after the player walks away");
        int[] counter = {1_500_000_000};
        MtrKnown known = new MtrKnown();

        // Near the first half of a line: one train line calling at two stations, with the rail under it.
        MtrClientData.Snapshot near = reading(
                List.of(station(1, "Alpha", "TRAIN", 0, 0), station(2, "Beta", "TRAIN", 100, 0)),
                List.of(platform(11, 1, "1", "TRAIN", 0, 0), platform(12, 2, "1", "TRAIN", 100, 0)),
                List.of(new MtrClientData.Line(1, "Line 1", "TRAIN", 0xFF0000, List.of(
                        stop(11, 1, "Alpha"), stop(12, 2, "Beta")))),
                List.of(track("a", "TRAIN", 0, 0, 100, 0)));
        known.remember(MtrTransit.build(near, id -> true, counter));
        expect("a reading near a line is remembered", known.lines().size() == 1
                && known.stations().size() == 2);
        expect("with the track it marked", known.marks(id -> true).segmentCount() == 1);

        // Walked away: MTR now sends one station and no lines at all, which is what a reading looks like
        // from far off. Everything already read has to still be there.
        MtrClientData.Snapshot away = reading(
                List.of(station(2, "Beta", "TRAIN", 100, 0)),
                List.of(platform(12, 2, "1", "TRAIN", 100, 0)),
                List.of(),
                List.of());
        known.remember(MtrTransit.build(away, id -> true, counter));
        expect("a reading from far away does not take the lines with it", known.lines().size() == 1);
        expect("nor the stations", known.stations().size() == 2);
        expect("nor the track, which is what a journey over the line is planned along",
                known.marks(id -> true).segmentCount() == 1);

        // Walked along the line: the same line again, a window further on with one stop in common.
        MtrClientData.Snapshot further = reading(
                List.of(station(2, "Beta", "TRAIN", 100, 0), station(3, "Gamma", "TRAIN", 200, 0)),
                List.of(platform(12, 2, "1", "TRAIN", 100, 0),
                        platform(13, 3, "1", "TRAIN", 200, 0)),
                List.of(new MtrClientData.Line(1, "Line 1", "TRAIN", 0xFF0000, List.of(
                        stop(12, 2, "Beta"), stop(13, 3, "Gamma")))),
                List.of(track("b", "TRAIN", 100, 0, 200, 0)));
        known.remember(MtrTransit.build(further, id -> true, counter));
        expect("a later reading brings the line's newest stops", known.lines().size() == 1
                && known.lines().get(0).stopCount() == 2
                && known.lines().get(0).stops().get(1).x() == 200);
        expect("and its track is added to the track already known",
                known.marks(id -> true).segmentCount() == 2);

        // Back over the same ground: the same stretch of rail is marked again, and must not be kept twice.
        known.remember(MtrTransit.build(near, id -> true, counter));
        expect("walking back over the same track does not remember it a second time",
                known.marks(id -> true).segmentCount() == 2);

        // A line's answer filters the memory rather than what was put in it: switching a line off takes
        // its track out of the layer and keeps it, so switching it back on needs no fresh reading.
        expect("a line whose marks are off contributes no track",
                known.marks(id -> false).segmentCount() == 0);
        expect("and still has it when switched back on", known.marks(id -> true).segmentCount() == 2);

        known.clear();
        expect("switching MTR off forgets the railway entirely",
                known.lines().isEmpty() && known.stations().isEmpty()
                        && known.marks(id -> true).segmentCount() == 0);
    }

    /** A reading of the given parts, for the checks that need one built by hand. */
    private static MtrClientData.Snapshot reading(List<MtrClientData.Station> stations,
                                                  List<MtrClientData.Platform> platforms,
                                                  List<MtrClientData.Line> lines,
                                                  List<MtrClientData.Track> tracks) {
        return new MtrClientData.Snapshot(stations, platforms, lines, tracks);
    }

    /**
     * The interchange rule the map draws from.
     *
     * <p>Two lines, standing within the planner's own transfer radius -- and the two things that have
     * each been wrong here: exact positions, which missed the platform-and-stop-beside-it interchange
     * that is the commonest one there is, and counting stops rather than lines, which marked a place
     * orange for one line's own stops and so could not be cleared by cancelling any line, because no
     * second line was ever involved.
     */
    private static void checkInterchanges() {
        System.out.println("   where two lines meet");
        double radius = bili.dongsz.howtogo.route.LinePlanner.transferRadius();

        // A rail line and a boat line calling at the two sides of one station.
        TransitLine rail = line("mtr:1", RoadClass.RAIL, 0, 0, 100, 0);
        TransitLine beside = line("mtr:2", RoadClass.WATER, 5, 0, 200, 0);
        List<TransitInterchanges.Interchange> shared =
                TransitInterchanges.of(List.of(rail, beside));
        expect("two lines calling a few blocks apart make an interchange", shared.size() == 1);
        expect("holding both stops, so the map can decide where to draw it",
                shared.get(0).holds(0, 0) && shared.get(0).holds(5, 0));
        expect("centred between them, which is where a fused marker goes",
                shared.get(0).centreX() == 3 && shared.get(0).centreZ() == 0);
        expect("and it names both lines",
                shared.get(0).lineIds().size() == 2);
        expect("while a stop of theirs nowhere near another line is not in it",
                !shared.get(0).holds(100, 0) && !shared.get(0).holds(200, 0));

        // Whether the two markers are drawn as one is a question about the screen: at a zoom where they
        // land far apart they are two markers, and at one where they touch they are one.
        TransitInterchanges.Interchange pair = shared.get(0);
        expect("zoomed in far enough to separate them, they are two markers",
                TransitInterchanges.overlapping(pair, x -> x * 50, z -> z * 50, 10.0).size() == 2);
        expect("and zoomed out until they touch, one",
                TransitInterchanges.overlapping(pair, x -> x, z -> z, 10.0).size() == 1);

        // The radius is the planner's, inclusive at its own edge: the map must not call two stations
        // separate that a journey will change lines at.
        TransitLine atTheEdge = line("mtr:3", RoadClass.WATER, (int) radius, 0, 300, 0);
        TransitLine pastIt = line("mtr:4", RoadClass.WATER, (int) radius + 1, 0, 300, 0);
        expect("a stop exactly the radius away still counts",
                TransitInterchanges.of(List.of(rail, atTheEdge)).size() == 1);
        expect("and one a block further out does not",
                TransitInterchanges.of(List.of(rail, pastIt)).isEmpty());

        // Three lines at one place are one interchange, not two pairs of them.
        TransitLine third = line("mtr:6", RoadClass.RAIL, -4, 0, 400, 0);
        expect("three lines at one place are one marker",
                TransitInterchanges.of(List.of(rail, beside, third)).size() == 1);

        // One line's own stops, standing close: not a change of lines, and so not an interchange. This
        // is the case that left an orange marker on a place no second line ever called at.
        expect("one line's own stops standing close are not an interchange",
                TransitInterchanges.of(List.of(line("mtr:5", RoadClass.RAIL, 0, 0, 5, 0))).isEmpty());

        // Worked out from the lines handed in, every call: with one of the two gone the place is not an
        // interchange, which is what "the marker stayed after I cancelled the line" was about.
        expect("with one of the two lines gone the place is not an interchange any more",
                TransitInterchanges.of(List.of(rail)).isEmpty());
        expect("and no lines at all is no interchanges", TransitInterchanges.of(List.of()).isEmpty());
    }

    /** A rail of this mod's rail class, as the raw layer a line's ride is planned over. */
    private static void addRail(RoadNetwork network, int fromX, int fromZ, int toX, int toZ) {
        addTrack(network, RoadClass.RAIL, fromX, fromZ, toX, toZ);
    }

    /** The same, as a waterway. */
    private static void addWater(RoadNetwork network, int fromX, int fromZ, int toX, int toZ) {
        addTrack(network, RoadClass.WATER, fromX, fromZ, toX, toZ);
    }

    private static void addTrack(RoadNetwork network, RoadClass kind, int fromX, int fromZ, int toX,
                                 int toZ) {
        RoadSegment segment = network.newSegment(kind, 64, 2);
        segment.addVertex(fromX, fromZ);
        segment.addVertex(toX, toZ);
        // Joined by position, as the track layers join their own rails: two stretches that meet in the
        // world are one line to ride along, and without this every one of them would be an island.
        segment.setFromNode(endpoint(network, fromX, fromZ).id());
        segment.setToNode(endpoint(network, toX, toZ).id());
        network.addSegment(segment);
    }

    private static RoadNode endpoint(RoadNetwork network, int x, int z) {
        RoadNode existing = network.nearestNode(x, z, 0.5);
        return existing != null ? existing
                : network.addNode(x, 64, z, RoadNode.Type.JUNCTION, null);
    }

    /** A line of the given kind calling at the two given places. */
    private static TransitLine line(String id, RoadClass kind, int fromX, int fromZ, int toX, int toZ) {
        TransitLine made = new TransitLine(id, id, kind);
        made.addStop(LineStop.ofStation("from", fromX, fromZ));
        made.addStop(LineStop.ofStation("to", toX, toZ));
        return made;
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
                station(3, "Gamma", "BOAT", 200, 0),
                station(4, "Delta", "BOAT", 300, 0));
        List<MtrClientData.Platform> platforms = List.of(
                platform(11, 1, "1", "TRAIN", 0, 0),
                platform(12, 1, "2", "TRAIN", 10, 0),
                platform(31, 3, "1", "BOAT", 200, 4),
                platform(41, 4, "1", "BOAT", 300, 0));

        List<MtrClientData.Line> lines = new ArrayList<>();
        // Three stops, all of whose stations the client knows. The first two face a rail that reaches
        // both of them; the third does not, which is the case the marking has to survive.
        lines.add(new MtrClientData.Line(1, "Line 1", "TRAIN", 0xFF0000, List.of(
                stop(11, 1, "Alpha"), stop(21, 2, "Beta"), stop(31, 3, "Gamma"))));
        // An aeroplane: a line this mod has no kind for.
        lines.add(new MtrClientData.Line(2, "Flight 1", "AIRPLANE", 0x00FF00, List.of(
                stop(11, 1, "Alpha"), stop(21, 2, "Beta"))));
        // A line with one stop the client has never been sent, which leaves it with one placed stop.
        lines.add(new MtrClientData.Line(3, "Line 3", "TRAIN", 0x0000FF, List.of(
                stop(11, 1, "Alpha"), stop(99, 999, "Somewhere else"))));
        // A boat line whose two stops are both on its own waterway, so its marks can be told from a
        // train's.
        lines.add(new MtrClientData.Line(4, "Boat 1", "BOAT", 0x00FFFF, List.of(
                stop(31, 3, "Gamma"), stop(41, 4, "Delta"))));

        List<MtrClientData.Track> tracks = List.of(
                track("a", "TRAIN", 0, 0, 10, 0),
                track("b", "TRAIN", 10, 0, 100, 0),
                track("c", "AIRPLANE", 100, 0, 110, 0),
                track("d", "BOAT", 200, 4, 300, 0));
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

    /** Whether any polyline of the network has a vertex at the given place. */
    private static boolean hasVertexNear(RoadNetwork network, int x, int z) {
        for (RoadSegment segment : network.segmentsSnapshot()) {
            for (int i = 0; i < segment.vertexCount(); i++) {
                if (segment.x(i) == x && segment.z(i) == z) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Whether one of the stations is at the given place, at the given height. */
    private static boolean stationAt(List<MtrTransit.Station> stations, int x, int y, int z) {
        for (MtrTransit.Station station : stations) {
            if (station.x() == x && station.y() == y && station.z() == z) {
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
