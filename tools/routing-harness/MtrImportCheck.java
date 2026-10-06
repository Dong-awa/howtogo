package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.RoadConfig;
import bili.dongsz.howtogo.road.PlaceKind;
import bili.dongsz.howtogo.road.RoadClass;
import bili.dongsz.howtogo.road.RoadNetwork;
import bili.dongsz.howtogo.road.RoadNode;
import bili.dongsz.howtogo.road.RoadSegment;
import bili.dongsz.howtogo.route.RoadRouter;
import bili.dongsz.howtogo.route.TravelMode;
import bili.dongsz.howtogo.transit.LineStop;
import bili.dongsz.howtogo.transit.TransitLine;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

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

        MtrTransit.Built built = build(reading);
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

        // What is worked out is the track the lines run along: one road per pair that could be planned,
        // of the line's own class, and never MTR's rails as a whole.
        expect("a stretch is worked out for each ride that could be planned",
                built.rails().segmentCount() == 2);
        expect("a train line's track is rail", hasClass(built.rails(), RoadClass.RAIL));
        expect("and a boat line's is water", hasClass(built.rails(), RoadClass.WATER));
        expect("the track reaches the station the ride reaches", hasVertexNear(built.rails(), 100, 0));
        expect("and stops where the ride runs out of track",
                !hasVertexNear(built.rails(), 110, 0) && !hasVertexNear(built.rails(), 105, 0));
        expect("every piece of it is marked as read rather than drawn, by its id alone",
                built.rails().segmentsSnapshot().stream().allMatch(RailTrackStore::isOurs));
        expect("and it is worked out whether or not the line's marks are switched on, because the map "
                        + "draws the line along it either way",
                build(reading).rails().segmentCount() == 2);

        // The switch, which decides which of those tracks become roads of this mod: the roads are what
        // the ride runs on and what is drawn under the line, and the track itself is not affected.
        MtrKnown forSwitch = new MtrKnown();
        forSwitch.remember(build(reading));
        expect("with every line's marks off no track becomes a road",
                forSwitch.marks(id -> false).segmentCount() == 0);
        expect("while the track is still remembered, which is what the line is drawn along",
                forSwitch.trackOf(1) != null && forSwitch.trackOf(1).segmentCount() > 0);
        expect("with them on it is a road", forSwitch.marks(id -> true).segmentCount() == 2);

        // One line's answer is that line's own: the boat line's road exists and the train line's does
        // not, which is the whole point of asking per line rather than once for the layer.
        expect("one line's answer makes its track a road and leaves the other line's alone",
                hasClass(forSwitch.marks(id -> id == 4), RoadClass.WATER)
                        && !hasClass(forSwitch.marks(id -> id == 4), RoadClass.RAIL));

        expect("an empty reading becomes nothing at all",
                build(MtrClientData.Snapshot.EMPTY).lines().isEmpty());

        checkHandshake();

        // A line is told from the player's own by its id alone, which is what lets the planner take
        // both lists and the editor take one, with no second field to keep in step.
        expect("an imported line says so", MtrTransit.isImported(imported));
        expect("and a line the player made does not",
                !MtrTransit.isImported(new TransitLine("mine", "Mine", RoadClass.RAIL)));
        expect("and neither does nothing", !MtrTransit.isImported(null));

        checkMarksSwitch(imported);
        checkStationPlaces();
        checkLineTracks();
        checkThreeWayMerge();
        checkOverlayIds();
        checkInterchanges();
        checkKnown();
        checkMapFilter();

        // A boat line: the other kind this mod has a use for.
        MtrClientData.Snapshot boatsOnly = new MtrClientData.Snapshot(
                reading.stations(), reading.platforms(),
                reading.lines().stream().filter(line -> "BOAT".equals(line.mode())).toList(),
                List.of());
        expect("a boat line becomes a water line",
                build(boatsOnly).lines().stream()
                        .allMatch(line -> line.kind() == RoadClass.WATER));

        // A line all of whose stations are outside what the client was sent has no ride in it.
        MtrClientData.Snapshot nothingPlaced = new MtrClientData.Snapshot(
                List.of(), List.of(),
                List.of(new MtrClientData.Line(9, "Far away", "TRAIN", 0, List.of(
                        stop(11, 1, "Alpha"), stop(21, 2, "Beta")))),
                List.of());
        MtrTransit.Built unplaced = build(nothingPlaced);
        expect("a line whose stops the client has not been sent is not offered",
                unplaced.lines().isEmpty());
        expect("and its stops are counted", unplaced.unplaced() == 2);
        expect("while no station means no stops to offer either", unplaced.stops().isEmpty());

        System.out.println(failures == 0 ? "  MTR import ok (" + checks + " checks)"
                : "  MTR import FAILED: " + failures + " of " + checks);
        return new int[]{checks, failures};
    }

    /**
     * What the map draws, and what it leaves out.
     *
     * <p>Two rules in one question, and both are checked here because both are silent when wrong: the
     * player's switches (a kind hidden by hand is never drawn) and the map's own shedding of detail as it
     * is zoomed out, which has an order -- paths, then roads, then waterways, then railways, with the
     * highways and ice roads never shed, and shops before stations before landmarks before resource
     * points. The transit lines are never shed at all.
     */
    private static void checkMapFilter() {
        System.out.println("   what the map draws when zoomed out");

        expect("at close zoom a footpath is drawn", MapFilter.shows(RoadClass.PATH, 1.0));
        expect("zoomed out it is the first thing shed", !MapFilter.shows(RoadClass.PATH, 0.3));
        expect("and the ordinary road outlives it",
                MapFilter.shows(RoadClass.ROAD, 0.3) && !MapFilter.shows(RoadClass.ROAD, 0.18));
        expect("with the waterway outliving that, and the railway the last road to go",
                MapFilter.shows(RoadClass.WATER, 0.18) && !MapFilter.shows(RoadClass.WATER, 0.14)
                        && MapFilter.shows(RoadClass.RAIL, 0.14)
                        && !MapFilter.shows(RoadClass.RAIL, 0.1));
        expect("while the highway and the ice road are never shed",
                MapFilter.shows(RoadClass.HIGHWAY, 0.01) && MapFilter.shows(RoadClass.ICE, 0.01));

        expect("a shop is shed before a station, a station before a landmark",
                !MapFilter.shows(PlaceKind.SHOP, 0.4) && MapFilter.shows(PlaceKind.STATION, 0.4)
                        && MapFilter.shows(PlaceKind.PLACE, 0.4)
                        && !MapFilter.shows(PlaceKind.STATION, 0.25)
                        && MapFilter.shows(PlaceKind.PLACE, 0.25));
        expect("and a resource point outlives them all",
                MapFilter.shows(PlaceKind.RESOURCE, 0.13)
                        && !MapFilter.shows(PlaceKind.PLACE, 0.13));
        expect("the transit lines are never shed by zoom", MapFilter.showsLines());

        // The switches, which outrank the zoom: what the player hides stays hidden at every scale.
        MapFilter.toggleRoad(RoadClass.HIGHWAY);
        expect("a road switched off by hand is not drawn even at full zoom",
                !MapFilter.shows(RoadClass.HIGHWAY, 5.0));
        MapFilter.toggleRoad(RoadClass.HIGHWAY);
        expect("and switching it back on restores it", MapFilter.shows(RoadClass.HIGHWAY, 5.0));

        MapFilter.togglePlace(PlaceKind.RESOURCE);
        expect("a kind of place switched off is not drawn either",
                !MapFilter.shows(PlaceKind.RESOURCE, 5.0));
        MapFilter.togglePlace(PlaceKind.RESOURCE);
        expect("and is back when switched on", MapFilter.shows(PlaceKind.RESOURCE, 5.0));

        MapFilter.toggleLines();
        expect("the lines can be switched off by hand, though the zoom never does",
                !MapFilter.showsLines());
        MapFilter.toggleLines();
        expect("and back on", MapFilter.showsLines());
    }

    /**
     * A reading converted with its own mark-id counter, which is what a caller outside the memory has.
     *
     * <p>The counter is handed in rather than owned by the conversion because a session keeps what it has
     * read and merges the next reading into it: ids that began again at the same base every time would
     * make the second reading's track look like the first's.
     */
    private static MtrTransit.Built build(MtrClientData.Snapshot reading) {
        return MtrTransit.build(reading, new int[]{1_500_000_000});
    }

    /**
     * A station of MTR's that is several stations, and a platform that belongs to none.
     *
     * <h2>Why the first is worth a check of its own</h2>
     * MTR makes a station out of every area the player drags, so a station built a platform at a time
     * arrives here as several stations that share a name and stand a few blocks apart. Offered as they
     * arrive that is one station listed four times, four markers on one building, and a line calling at
     * whichever of the four its platforms happen to be in -- so two lines meeting at that station looked
     * like two lines at two stations, and a change between them was planned as a walk across town. The
     * grouping is what makes them one place, and it is invisible from outside the conversion, which is
     * why it is checked here rather than through anything a player can see.
     *
     * <h2>And the second</h2>
     * A platform's station is resolved by MTR against the stations that client currently holds, so a
     * platform sent on its own -- its station out of the window, which is the ordinary case for a
     * station the player has not walked to -- belongs to no station and carries an id of zero. Zero is
     * not a station, and reading it as one gathered every such platform together and answered with the
     * middle of a scattered field of them: a stop in open country, on a line that appeared to call at a
     * station nobody had built.
     */
    private static void checkStationPlaces() {
        System.out.println("   a station built a platform at a time");

        // Two of MTR's stations, one name, twenty blocks apart, and a third station of its own further
        // off for the line to reach.
        MtrClientData.Snapshot split = reading(
                List.of(station(1, "Central", "TRAIN", 0, 0),
                        station(2, "Central", "TRAIN", 20, 0),
                        station(3, "North", "TRAIN", 200, 0)),
                List.of(platform(11, 1, "1", "TRAIN", 0, 0),
                        platform(21, 2, "1", "TRAIN", 20, 0),
                        platform(31, 3, "1", "TRAIN", 200, 0)),
                List.of(new MtrClientData.Line(7, "Line 7", "TRAIN", 0xFFFFFF, List.of(
                        stop(11, 1, "Central"), stop(21, 2, "Central"), stop(31, 3, "North")))),
                List.of());
        MtrTransit.Built merged = build(split);
        expect("two of MTR's stations with one name are one place here", merged.stations().size() == 2);
        expect("and one stop is offered for it rather than two", merged.stops().size() == 2);
        expect("standing between its platforms, which is on the railway and so reachable",
                stopAt(merged.stops(), 10, 0));
        expect("while the station of another name is left where it is", stopAt(merged.stops(), 200, 0));
        expect("a line calling at both of them calls at the station once, and keeps its other stop",
                merged.lines().size() == 1 && merged.lines().get(0).stopCount() == 2);

        // The name is half the rule and the distance is the other half: two towns may each have called
        // their station Central, and they are not one station.
        MtrClientData.Snapshot sameNameFarApart = reading(
                List.of(station(4, "Central", "TRAIN", 0, 0),
                        station(5, "Central", "TRAIN", 3000, 0)),
                List.of(platform(41, 4, "1", "TRAIN", 0, 0),
                        platform(51, 5, "1", "TRAIN", 3000, 0)),
                List.of(),
                List.of());
        expect("two stations of one name a map apart are two places",
                build(sameNameFarApart).stations().size() == 2);

        // A station with no name is never the same place as another, because every nameless station
        // would be.
        MtrClientData.Snapshot nameless = reading(
                List.of(station(6, "", "TRAIN", 0, 0), station(7, "", "TRAIN", 10, 0)),
                List.of(platform(61, 6, "1", "TRAIN", 0, 0), platform(71, 7, "1", "TRAIN", 10, 0)),
                List.of(),
                List.of());
        expect("stations with no name of their own are never merged into one",
                build(nameless).stations().size() == 2);

        // A platform whose station the client was never sent: it belongs to no station, and so does the
        // stop that names it.
        MtrClientData.Snapshot orphans = reading(
                List.of(),
                List.of(platform(91, 0, "1", "TRAIN", 500, 500),
                        platform(92, 0, "1", "TRAIN", 900, 900)),
                List.of(new MtrClientData.Line(8, "Line 8", "TRAIN", 0, List.of(
                        stop(91, 0, ""), stop(92, 0, "")))),
                List.of());
        MtrTransit.Built nowhere = build(orphans);
        expect("a platform whose station the client was not sent belongs to no station",
                nowhere.stops().isEmpty());
        expect("and a stop naming no station is counted rather than placed between the orphans",
                nowhere.unplaced() == 2 && nowhere.lines().isEmpty());

        // What the memory does when a place turns out to be bigger than it looked: a station found on
        // its own is one place under its own id, and finding its second area must replace it rather than
        // stand beside it -- which is the same station offered twice, the thing the grouping exists to
        // stop, arriving by the other door.
        MtrKnown memory = new MtrKnown();
        memory.remember(build(reading(
                List.of(station(2, "Central", "TRAIN", 20, 0)),
                List.of(platform(21, 2, "1", "TRAIN", 20, 0)), List.of(), List.of())));
        expect("a station first found on its own is one place in the memory",
                memory.stations().size() == 1);
        memory.remember(build(reading(
                List.of(station(1, "Central", "TRAIN", 0, 0), station(2, "Central", "TRAIN", 20, 0)),
                List.of(platform(11, 1, "1", "TRAIN", 0, 0),
                        platform(21, 2, "1", "TRAIN", 20, 0)),
                List.of(), List.of())));
        expect("and finding its second area replaces it rather than adding a second entry",
                memory.stations().size() == 1);
        expect("with the place now standing between the two", memory.stations().get(0).x() == 10);
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

        checkCallPace();
    }

    /**
     * The pace a turn is called at, when the road underfoot is not one the table names.
     *
     * <p>The interesting case is public transport, whose fastest class is the ice boat at forty blocks a
     * second: taking that as the pace of someone walking along a road to their stop called every turn
     * "now" from sixty blocks away. Whatever the mode, a player with no road they can travel on under
     * them is on foot, and the pace has to be the walker's.
     */
    private static void checkCallPace() {
        TravelMode transit = TravelMode.TRANSIT;
        TravelMode drive = TravelMode.DRIVE;
        TravelMode walk = TravelMode.WALK;

        expect("a rider walking along a road is paced as a walker on that road",
                Navigation.paceUnderfoot(transit, RoadClass.ROAD)
                        == walk.speedOn(RoadClass.ROAD));
        expect("not as the fastest thing public transport can be",
                Navigation.paceUnderfoot(transit, RoadClass.ROAD) < transit.speedOn(RoadClass.ICE));
        expect("a rider off the network at all is paced as a walker too",
                Navigation.paceUnderfoot(transit, null) < transit.speedOn(RoadClass.ICE));
        expect("a driver on a footpath is walking it",
                Navigation.paceUnderfoot(drive, RoadClass.PATH) == walk.speedOn(RoadClass.PATH));
        expect("while a driver on a road keeps the car's pace",
                Navigation.paceUnderfoot(drive, RoadClass.ROAD) == drive.speedOn(RoadClass.ROAD));
        expect("and a walker on a footpath is unchanged",
                Navigation.paceUnderfoot(walk, RoadClass.PATH) == walk.speedOn(RoadClass.PATH));
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

        checkStatedTracks();
        checkWholeMapCost();
    }

    /**
     * The track a line already knows it runs along, which is the one thing MTR's own data never says.
     *
     * <p>MTR joins a route to its platforms and to nothing else, so the rest of the integration works a
     * line's track out by planning a ride between each pair of its neighbouring stops -- over rails
     * that only exist within a couple of hundred blocks of the player. A line anywhere else on the
     * network therefore came out as straight hops between its stations, which is the defect this path
     * was written after: MTR Map Overlay's fetched snapshot names each route's own rails, in the order
     * the route runs along them, so a line's track is a lookup and there is nothing to plan.
     *
     * <p>What is checked here is the three things that make the difference between a railway and a
     * zig-zag, and none of them is visible from outside a running game:
     * <ul>
     *   <li>a rail's geometry runs from its own start to its own end, and the order the route runs along
     *       its rails has nothing to do with which way round that is -- the overlay samples a rail once
     *       and reuses it for every route and every vehicle that drives it. Appending them in the order
     *       given without turning the ones that need it would draw each rail as a hop from its far end
     *       back to its near one, so the check is that a rail stored backwards comes out forwards;</li>
     *   <li>a rail the reading names but does not carry is a stretch whose geometry is unknown, and it
     *       must come out as a gap rather than as a straight line across it;</li>
     *   <li>all of it is one piece per continuous stretch, not one per rail -- a whole railway's worth
     *       of rails is what makes the difference between a track that can be drawn and one that cannot
     *       be afforded.</li>
     * </ul>
     */
    private static void checkStatedTracks() {
        System.out.println("   the track a reading already knows");

        // Two rails of one route, the second stored back to front: the join is at (50, 0) and the
        // route leaves it towards (50, 50), which is the second rail's own end rather than its start.
        MtrClientData.Track first = numbered("a", 0, 0, 50, 0);
        MtrClientData.Track backwards = numbered("b", 50, 50, 50, 0);

        RoadNetwork bent = MtrLineTracks.stated(List.of(first, backwards), RoadClass.RAIL,
                new int[]{1_500_000_000});
        expect("a route's own rails come out as one piece of track", bent.segmentCount() == 1);
        RoadSegment piece = bent.segmentsSnapshot().get(0);
        expect("with the rail stored backwards turned the right way round, so the track is a line "
                        + "rather than a zig-zag",
                piece.vertexCount() == 3 && at(piece, 0, 0, 0) && at(piece, 1, 50, 0)
                        && at(piece, 2, 50, 50));
        expect("and the shared end is not written twice",
                piece.vertexCount() == 3);
        expect("carrying the class of the line it belongs to", piece.roadClass() == RoadClass.RAIL);
        expect("and drawn from the id space it was handed",
                piece.id() >= 1_500_000_000);

        // A rail the reading says the route uses and does not hold: the track either side of it must
        // stay two pieces, because the stretch between them is track nobody has.
        RoadNetwork gapped = MtrLineTracks.stated(
                List.of(numbered("a", 0, 0, 50, 0), numbered("c", 500, 0, 550, 0)),
                RoadClass.RAIL, new int[]{1_500_000_000});
        expect("a rail the reading does not carry leaves the track either side of it as two pieces",
                gapped.segmentCount() == 2);
        expect("with no straight line drawn across the stretch that is missing",
                verticesBetween(gapped, 60, 490) == 0);

        // The whole point of the path: a line of many rails is one piece, because the rails are joined
        // end to end by the order they were given in rather than left as pieces to be walked.
        List<MtrClientData.Track> chain = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            chain.add(numbered("r" + i, i * 10, 0, (i + 1) * 10, 0));
        }
        RoadNetwork joined = MtrLineTracks.stated(chain, RoadClass.RAIL, new int[]{1_500_000_000});
        expect("fifty rails joined end to end are one piece of track, not fifty",
                joined.segmentCount() == 1 && joined.segmentsSnapshot().get(0).vertexCount() == 51);
        expect("and it reaches both ends of the route it was given",
                at(joined.segmentsSnapshot().get(0), 0, 0, 0)
                        && at(joined.segmentsSnapshot().get(0), 50, 500, 0));

        // The other class, and the degenerate cases.
        RoadNetwork water = MtrLineTracks.stated(List.of(numbered("w", 0, 0, 40, 0)),
                RoadClass.WATER, new int[]{1_500_000_000});
        expect("a boat line's stated track is water", hasClass(water, RoadClass.WATER));
        expect("no rails at all is no track",
                MtrLineTracks.stated(List.of(), RoadClass.RAIL, new int[]{1}).segmentCount() == 0);
        expect("and a rail with no geometry to draw is left out rather than drawn as a point",
                MtrLineTracks.stated(List.of(new MtrClientData.Track("x", "TRAIN", 64,
                        new double[]{5}, new double[]{5})), RoadClass.RAIL,
                        new int[]{1}).segmentCount() == 0);
    }

    /** A rail of the snapshot, sampled from one end to the other. */
    private static MtrClientData.Track numbered(String hexId, int fromX, int fromZ, int toX, int toZ) {
        return new MtrClientData.Track(hexId, "TRAIN", 64, new double[]{fromX, toX},
                new double[]{fromZ, toZ});
    }

    /** Whether a segment's vertex at an index is at a place. */
    private static boolean at(RoadSegment segment, int index, int x, int z) {
        return segment.x(index) == x && segment.z(index) == z;
    }

    /** How many vertices a whole network has with an x coordinate in a range. */
    private static int verticesBetween(RoadNetwork network, int fromX, int toX) {
        int found = 0;
        for (RoadSegment segment : network.segmentsSnapshot()) {
            for (int i = 0; i < segment.vertexCount(); i++) {
                if (segment.x(i) > fromX && segment.x(i) < toX) {
                    found++;
                }
            }
        }
        return found;
    }

    /**
     * What a whole railway costs to convert.
     *
     * <p>The windowed reading is a couple of lines, so working every line's track out from the rails in
     * hand was never expensive. A whole map is a different question: the rails in hand are still only
     * the ones around the player, but the lines are now every line of the railway, each with all of its
     * stops -- and a ride was planned for every neighbouring pair of them, almost all of which are
     * hundreds or thousands of blocks from any rail here and can only fail. The work is bounded by
     * asking the extent of the rails first: a stop outside their box cannot be joined to them whatever
     * the router does, so the pairs that mention one are never planned at all. This check is what says
     * the bound holds, because nothing else would notice it going away.
     */
    private static void checkWholeMapCost() {
        System.out.println("   what a whole railway costs");

        // The rails a client actually holds: a window's worth, laid along a line.
        RoadNetwork rails = new RoadNetwork();
        for (int i = 0; i < 300; i++) {
            addRail(rails, i * 4, 0, (i + 1) * 4, 0);
        }

        // A whole railway: many lines calling at the same far-away stations, and one that comes near the
        // rails. Every one of the far pairs is unreachable over the track in hand, and the question is
        // whether they are planned anyway.
        List<MtrClientData.Station> stations = new ArrayList<>();
        List<MtrClientData.Platform> platforms = new ArrayList<>();
        List<MtrClientData.Stop> far = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            int x = 4000 + i * 40;
            int z = 3000 + i * 400;
            stations.add(station(100 + i, "Far " + i, "TRAIN", x, z));
            platforms.add(platform(1000 + i, 100 + i, "1", "TRAIN", x, z));
            far.add(new MtrClientData.Stop(1000 + i, 100 + i, "Far " + i, ""));
        }
        for (int i = 0; i <= 6; i++) {
            stations.add(station(200 + i, "Near " + i, "TRAIN", i * 200, 0));
            platforms.add(platform(2000 + i, 200 + i, "1", "TRAIN", i * 200, 0));
        }

        List<MtrClientData.Line> lines = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            lines.add(new MtrClientData.Line(i + 1, "Wide " + i, "TRAIN", 0, far));
        }
        lines.add(new MtrClientData.Line(9999, "Nearby", "TRAIN", 0, List.of(
                stop(2000, 200, "Near 0"), stop(2001, 201, "Near 1"), stop(2002, 202, "Near 2"),
                stop(2003, 203, "Near 3"), stop(2004, 204, "Near 4"), stop(2005, 205, "Near 5"),
                stop(2006, 206, "Near 6"))));

        MtrClientData.Snapshot whole = new MtrClientData.Snapshot(
                List.copyOf(stations), List.copyOf(platforms), List.copyOf(lines),
                List.of(track("a", "TRAIN", 0, 0, 1200, 0)));

        long startedAtWhole = System.nanoTime();
        MtrTransit.Built built = build(whole);
        long wholeMillis = Math.round((System.nanoTime() - startedAtWhole) / 1_000_000.0);
        System.out.println("   " + built.lines().size() + " lines, " + built.rails().segmentCount()
                + " marked segments, converted in " + wholeMillis + " ms");

        expect("the whole railway is read, so all 401 lines are offered",
                built.lines().size() == 401);
        expect("the line that comes near the track is marked", built.rails().segmentCount() > 0);
        expect("while 400 lines that do not are not planned pair by pair, so the conversion stays "
                + "quick (under 1000 ms)", wholeMillis < 1000);

        checkStatedWholeNetworkCost();

        // And what it costs to keep: a whole railway arrives in one reading, and what is remembered of it
        // is walked again by every reading after that. Comparing each arriving station with every
        // remembered one would be the square of the network, which is the shape of cost that only shows
        // up on the map a player actually built.
        List<MtrClientData.Station> many = new ArrayList<>();
        List<MtrClientData.Platform> manyPlatforms = new ArrayList<>();
        for (int i = 0; i < 4000; i++) {
            many.add(station(10_000 + i, "Station " + i, "TRAIN", i * 40, 0));
            manyPlatforms.add(platform(20_000 + i, 10_000 + i, "1", "TRAIN", i * 40, 0));
        }
        MtrTransit.Built railway = build(new MtrClientData.Snapshot(
                List.copyOf(many), List.copyOf(manyPlatforms), List.of(), List.of()));

        MtrKnown memory = new MtrKnown();
        long startedAtMemory = System.nanoTime();
        memory.remember(railway);
        // Twice, because the second time is the one that walks what the first one kept.
        memory.remember(railway);
        long memoryMillis = Math.round((System.nanoTime() - startedAtMemory) / 1_000_000.0);
        System.out.println("   " + memory.stations().size() + " stations remembered twice in "
                + memoryMillis + " ms");
        expect("a whole railway is remembered", memory.stations().size() == 4000);
        expect("and remembering it again is a walk of it rather than its square (under 1000 ms)",
                memoryMillis < 1000);
    }

    /**
     * What a whole railway costs when the reading names every line's rails.
     *
     * <p>The counterpart of the check above, and the reason the fetched snapshot's rails are taken
     * whole rather than bounded to a box around the player. Bounding them was what left most of the
     * railway without geometry, so a line the player had not walked to was drawn as straight hops
     * between its stations -- the defect the whole-network reading exists to fix. It was bounded
     * because a line's track used to mean planning a ride per pair of its neighbouring stops over
     * every rail in hand; a reading that names the rails makes that a lookup, and this is the number
     * that says so.
     *
     * <p>The shape is a real one: a few hundred lines, each naming a long run of rails, and every rail
     * of the network carried. What is asserted is that every line gets a track -- not merely that the
     * conversion is quick, because a fast conversion that drops the far half of the railway is exactly
     * the bug.
     */
    private static void checkStatedWholeNetworkCost() {
        System.out.println("   and what a railway costs when the reading names the rails");

        // A network of rails laid end to end, and lines running over slices of it: the far end of the
        // network is nowhere near the near end, which is the point.
        List<MtrClientData.Track> rails = new ArrayList<>();
        for (int i = 0; i < 4000; i++) {
            rails.add(numbered("rail" + i, i * 8, 0, (i + 1) * 8, 0));
        }
        List<MtrClientData.Station> stations = new ArrayList<>();
        List<MtrClientData.Platform> platforms = new ArrayList<>();
        for (int i = 0; i <= 400; i++) {
            stations.add(station(i + 1, "S" + i, "TRAIN", i * 80, 0));
            platforms.add(platform(1000 + i, i + 1, "1", "TRAIN", i * 80, 0));
        }

        List<MtrClientData.Line> lines = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            List<MtrClientData.Stop> stops = List.of(
                    stop(1000 + i, i + 1, "S" + i), stop(1001 + i, i + 2, "S" + (i + 1)));
            List<String> named = new ArrayList<>();
            for (int rail = i * 10; rail < (i + 1) * 10; rail++) {
                named.add("rail" + rail);
            }
            lines.add(new MtrClientData.Line(i + 1, "Line " + i, "TRAIN", 0, stops, named));
        }

        MtrClientData.Snapshot whole = new MtrClientData.Snapshot(List.copyOf(stations),
                List.copyOf(platforms), List.copyOf(lines), List.copyOf(rails));
        long startedAt = System.nanoTime();
        MtrTransit.Built built = build(whole);
        long millis = Math.round((System.nanoTime() - startedAt) / 1_000_000.0);
        System.out.println("   " + built.lines().size() + " lines over " + rails.size()
                + " rails, " + built.rails().segmentCount() + " marked segments, converted in "
                + millis + " ms");

        expect("every line of a whole railway gets a track, including the ones nowhere near the "
                        + "player", built.tracks().size() == 400);
        expect("each of them one piece per line, because the reading named the rails in order",
                built.rails().segmentCount() == 400);
        expect("and the conversion stays quick with no radius bounding it (under 1000 ms)",
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
        known.remember(MtrTransit.build(near, counter));
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
        known.remember(MtrTransit.build(away, counter));
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
        known.remember(MtrTransit.build(further, counter));
        expect("a later reading brings the line's newest stops", known.lines().size() == 1
                && known.lines().get(0).stopCount() == 2
                && known.lines().get(0).stops().get(1).x() == 200);
        expect("and its track is added to the track already known",
                known.marks(id -> true).segmentCount() == 2);

        // Back over the same ground: the same stretch of rail is marked again, and must not be kept twice.
        known.remember(MtrTransit.build(near, counter));
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

        checkStatedTrackIsReplaced();
    }

    /**
     * What is kept of a line whose reading names its rails, against what is kept of one it does not.
     *
     * <p>The two are kept differently, and it is the reading that says which: a window is the part of a
     * railway near the player, so its pieces of a line have to be added to the pieces earlier windows
     * brought, while a whole network fetched from the server <em>is</em> the line and what it says
     * replaces what was kept.
     *
     * <p>Getting that wrong is not subtle in the game and is invisible here without a check: a line
     * whose reading is a whole railway arrives as one stitched piece, so adding the new one to the old
     * leaves two pieces that share their start -- and a node with two segments leaving it is a
     * junction, but a node with exactly two segment ends is a road carrying on. The map walks straight
     * out along the stale piece and back along the live one, and draws a spike.
     */
    private static void checkStatedTrackIsReplaced() {
        System.out.println("   a line whose rails the reading names");

        int[] counter = {1_500_000_000};
        MtrKnown known = new MtrKnown();
        MtrClientData.Line shortLine = new MtrClientData.Line(1, "Line 1", "TRAIN", 0,
                List.of(stop(11, 1, "Alpha"), stop(12, 2, "Beta")), List.of("a"));
        known.remember(MtrTransit.build(reading(
                List.of(station(1, "Alpha", "TRAIN", 0, 0), station(2, "Beta", "TRAIN", 100, 0)),
                List.of(platform(11, 1, "1", "TRAIN", 0, 0), platform(12, 2, "1", "TRAIN", 100, 0)),
                List.of(shortLine),
                List.of(track("a", "TRAIN", 0, 0, 100, 0))), counter));
        expect("a line whose rails the reading names has the track it named",
                known.marks(id -> true).segmentCount() == 1);

        // The same line, extended: two rails now, and both named.
        MtrClientData.Line longerLine = new MtrClientData.Line(1, "Line 1", "TRAIN", 0,
                List.of(stop(11, 1, "Alpha"), stop(12, 2, "Beta"), stop(13, 3, "Gamma")),
                List.of("a", "b"));
        known.remember(MtrTransit.build(reading(
                List.of(station(1, "Alpha", "TRAIN", 0, 0), station(2, "Beta", "TRAIN", 100, 0),
                        station(3, "Gamma", "TRAIN", 200, 0)),
                List.of(platform(11, 1, "1", "TRAIN", 0, 0), platform(12, 2, "1", "TRAIN", 100, 0),
                        platform(13, 3, "1", "TRAIN", 200, 0)),
                List.of(longerLine),
                List.of(track("a", "TRAIN", 0, 0, 100, 0), track("b", "TRAIN", 100, 0, 200, 0))),
                counter));
        expect("and a live reading of the same line replaces that track rather than being added to it",
                known.marks(id -> true).segmentCount() == 1);
        expect("so the line is the length the reading says it is, and not the length it was",
                known.marks(id -> true).segmentsSnapshot().get(0).vertexCount() == 3);
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
     * The three readings side by side, which is what a session with MTR Map Overlay has.
     *
     * <p>The window MTR sent, the railway this process is simulating, and the whole railway MTR Map
     * Overlay fetched from the server. Which of the three wins, per kind, is the whole of this mod's
     * answer to "what does it know", and it is invisible from outside: a station kept from the wrong
     * reading looks exactly like a station.
     *
     * <p>The rails are the part with two rules rather than one, and both are checked here. A rail both
     * a window and a snapshot carry is the window's, because the window's copy is the one with a real
     * height and a transport mode on it -- a snapshot's geometry is flattened into X and Z, so keeping
     * its copy would put a mark at sea level and draw a boat line's rail as a train's. A rail only the
     * snapshot carries is the snapshot's, which is how a line the player has never been near gets drawn
     * along its track at all. And a rail whose id could not be read cannot be matched against anything,
     * so it is kept rather than dropped on the floor.
     */
    private static void checkThreeWayMerge() {
        System.out.println("   the three readings side by side");

        MtrClientData.Snapshot window = new MtrClientData.Snapshot(
                List.of(station(1, "Window name", "TRAIN", 0, 0)),
                List.of(platform(11, 1, "1", "TRAIN", 0, 0)),
                List.of(new MtrClientData.Line(1, "Line 1", "TRAIN", 0xFF0000,
                        List.of(stop(11, 1, "Window name"), stop(21, 2, "Beta")))),
                List.of(new MtrClientData.Track("aa", "TRAIN", 70, new double[]{0, 10},
                        new double[]{0, 0})));

        MtrClientData.Snapshot whole = new MtrClientData.Snapshot(
                List.of(station(1, "Whole name", "TRAIN", 5, 5),
                        station(2, "Beta", "TRAIN", 100, 0)),
                List.of(platform(21, 2, "1", "TRAIN", 100, 0)),
                List.of(new MtrClientData.Line(1, "Line 1", "TRAIN", 0xFF0000,
                        List.of(stop(11, 1, "Whole name"), stop(21, 2, "Beta"),
                                stop(31, 3, "Gamma"), stop(41, 4, "Delta")))),
                List.of());

        MtrClientData.Snapshot overlay = new MtrClientData.Snapshot(
                List.of(station(1, "Overlay name", "TRAIN", 1, 1),
                        station(2, "Beta", "TRAIN", 100, 0),
                        station(3, "Gamma", "TRAIN", 200, 0)),
                List.of(platform(31, 3, "1", "TRAIN", 200, 0)),
                // The longest line of the three, so it is the one kept.
                List.of(new MtrClientData.Line(1, "Line 1", "TRAIN", 0xFF0000,
                        List.of(stop(11, 1, "Overlay name"), stop(21, 2, "Beta"),
                                stop(31, 3, "Gamma"), stop(41, 4, "Delta"),
                                stop(51, 5, "Epsilon")),
                        List.of("aa", "bb"))),
                List.of(new MtrClientData.Track("aa", "TRAIN", 0, new double[]{0, 10},
                                new double[]{0, 0}),
                        new MtrClientData.Track("bb", "TRAIN", 0, new double[]{200, 210},
                                new double[]{0, 0}),
                        new MtrClientData.Track(null, "TRAIN", 0, new double[]{300, 310},
                                new double[]{0, 0})));

        MtrClientData.Snapshot merged = MtrClientData.merge(window, whole, overlay);

        expect("the fullest reading's stations are the ones offered",
                merged.stations().size() == 3 && nameOf(merged, 1).equals("Overlay name"));
        expect("and every platform any of them named, by its own id",
                merged.platforms().size() == 3);
        expect("and the line with the most stops, rather than the window's part of it",
                merged.lines().size() == 1 && merged.lines().get(0).stops().size() == 5);
        expect("with the rails that line runs along kept, which is what it is drawn along",
                merged.lines().get(0).rails().equals(List.of("aa", "bb")));

        expect("a rail the window sent is kept as the window sent it, height and all",
                railAt(merged, "aa") != null && railAt(merged, "aa").y() == 70);
        expect("a rail only the fetched snapshot has is added, which is what draws a line the "
                + "player has never been near", railAt(merged, "bb") != null);
        expect("and a rail that arrived with no id of its own is kept rather than dropped",
                merged.tracks().size() == 3);

        // The two-reading form is the same rule with the third reading absent, which is what every
        // session without the overlay mod has: nothing about this may have changed for them.
        MtrClientData.Snapshot twoWay = MtrClientData.merge(window, whole);
        expect("without a fetched snapshot the whole map still outranks the window",
                twoWay.stations().size() == 2 && nameOf(twoWay, 1).equals("Whole name"));
        expect("and its line is the fuller one", twoWay.lines().get(0).stops().size() == 4);
        expect("and the rails are the window's and only the window's",
                twoWay.tracks().size() == 1 && railAt(twoWay, "aa").y() == 70);

        // With nothing but the window, the answer is the window itself -- unchanged from before this
        // mod knew there could be more than one reading at all.
        MtrClientData.Snapshot alone = MtrClientData.merge(window, MtrClientData.Snapshot.EMPTY,
                MtrClientData.Snapshot.EMPTY);
        expect("with no other reading at all the window is offered exactly as it arrived",
                alone == window);

        // A window that has nothing in it is not a window that erases the railway: the fuller readings
        // are what the player is offered while they stand somewhere MTR has sent nothing about.
        MtrClientData.Snapshot empty = MtrClientData.merge(MtrClientData.Snapshot.EMPTY, whole, overlay);
        expect("and an empty window takes nothing away from the readings that are not empty",
                empty.stations().size() == 3 && empty.lines().size() == 1);

        // The rails belong to a line rather than to its stops, and the stronger reading is not always
        // the one that has them: only the fetched snapshot ever says which rails a line runs along, so
        // a line the window happens to know more stops of must still take the snapshot's rails -- or
        // it is drawn as straight hops between its stations, which is the whole of what went wrong.
        MtrClientData.Line longerInWindow = new MtrClientData.Line(2, "Line 2", "TRAIN", 0x00FF00,
                List.of(stop(11, 1, "Alpha"), stop(21, 2, "Beta"), stop(31, 3, "Gamma"),
                        stop(41, 4, "Delta")));
        MtrClientData.Line shorterWithRails = new MtrClientData.Line(2, "Line 2", "TRAIN", 0x00FF00,
                List.of(stop(11, 1, "Alpha"), stop(21, 2, "Beta")), List.of("aa"));
        MtrClientData.Snapshot windowWins = MtrClientData.merge(
                new MtrClientData.Snapshot(List.of(), List.of(), List.of(longerInWindow), List.of()),
                MtrClientData.Snapshot.EMPTY,
                new MtrClientData.Snapshot(List.of(), List.of(), List.of(shorterWithRails), List.of()));
        expect("a line the window knows more stops of keeps those stops",
                windowWins.lines().get(0).stops().size() == 4);
        expect("and still takes the rails only the fetched snapshot could name",
                windowWins.lines().get(0).rails().equals(List.of("aa")));

        checkSharedWorkspace();
    }

    /**
     * The one router workspace every line of a reading shares, which is what a whole railway's worth
     * of lines is affordable on.
     *
     * <p>What is checked is that sharing changes nothing: the same rails, the same line, the same
     * marks. One workspace per line copies the whole rail layer per line, so a reading of the whole
     * railway would pay that cost hundreds of times -- and a workspace is documented as belonging to a
     * batch precisely because every split it makes is additive, which is the property this rests on.
     */
    private static void checkSharedWorkspace() {
        RoadNetwork rails = new RoadNetwork();
        addRail(rails, 0, 0, 50, 0);
        addRail(rails, 50, 0, 50, 50);
        addRail(rails, 50, 50, 100, 50);

        TransitLine bent = line("mtr:1", RoadClass.RAIL, 0, 0, 100, 50);
        int[] sharedCounter = {1_500_000_000};
        RoadRouter.Workspace shared = new RoadRouter.Workspace(rails);
        RoadNetwork first = MtrLineTracks.of(shared, rails, bent, sharedCounter,
                MtrLineTracks.Extent.of(rails));
        // A second line over the same network through the same workspace: the first line's anchoring
        // splits are already in it, and must not have changed what a later line's marks are.
        RoadNetwork second = MtrLineTracks.of(shared, rails, bent, sharedCounter,
                MtrLineTracks.Extent.of(rails));
        RoadNetwork alone = MtrLineTracks.of(rails, bent, new int[]{1_500_000_000});

        expect("a line marked through a shared workspace gets the same marks as on its own",
                first.segmentCount() == alone.segmentCount()
                        && hasVertexNear(first, 50, 0) && hasVertexNear(first, 100, 50));
        expect("and the second line through it is marked the same way, so a split made for one line "
                        + "does not spoil the next",
                second.segmentCount() == alone.segmentCount() && hasVertexNear(second, 0, 0));
        expect("with ids of its own, since one reading's marks must not collide with each other",
                first.segmentsSnapshot().get(0).id() != second.segmentsSnapshot().get(0).id());
    }

    /**
     * The join every merge across the readings rests on: MTR's own id, out of the string MTR Map
     * Overlay carries it in.
     *
     * <p>The overlay writes every id it has as {@code Long.toHexString} of MTR's own, behind the kind
     * of thing it is, because MTR's ids are longs and it hands out half of them with the top bit set.
     * Reading that hex back is what makes a station the overlay fetched and a station MTR sent the
     * same station rather than two -- and a mis-parse would not look like a bug: it would look like a
     * railway with every station listed twice.
     */
    private static void checkOverlayIds() {
        System.out.println("   the ids the overlay carries");

        // Reading another mod's internals reflectively is only defensible if a session without that mod
        // is untouched by it, so that comes first here as it does for MTR: nothing bound, nothing read,
        // and no exception on the way out.
        expect("with no MTR Map Overlay installed it reports itself unavailable",
                !MtrMapOverlay.available());
        expect("and a reading comes back empty rather than throwing", MtrMapOverlay.read().isEmpty());

        expect("a station's id is read back out of the kind it is written behind",
                Long.valueOf(0x1a2bL).equals(MtrMapOverlay.idOf("station:1a2b")));
        expect("and so is a platform's", Long.valueOf(7L).equals(MtrMapOverlay.idOf("platform:7")));
        expect("and a line's, which is written as bare hex with nothing in front of it",
                Long.valueOf(-2L).equals(MtrMapOverlay.idOf("fffffffffffffffe")));
        expect("and a depot path's, which names no route but is still MTR's own rail",
                Long.valueOf(0x2fL).equals(MtrMapOverlay.idOf("depot:2f")));
        expect("an id that is not hex is keyed by itself rather than thrown away, and the same both "
                        + "times",
                MtrMapOverlay.idOf("depot:not-hex") != null
                        && MtrMapOverlay.idOf("depot:not-hex")
                        .equals(MtrMapOverlay.idOf("depot:not-hex")));
        expect("while nothing at all has no id", MtrMapOverlay.idOf(null) == null
                && MtrMapOverlay.idOf("") == null && MtrMapOverlay.idOf("station:") == null);

        checkOverlayHandshake();
    }

    /**
     * Whether the shapes this mod reads are the ones MTR Map Overlay on the classpath actually has.
     *
     * <p>The same check the MTR handshake makes, for the same reason: whether another mod still calls
     * its data what it called it is a question only its own jar can answer, and a lookup that has moved
     * otherwise shows up as one log line saying the data was unavailable, with no name in it.
     *
     * <p>Skipped when there is no jar to read, because then there is nothing to check and a failure
     * would be a failure of the machine rather than of the mod.
     */
    private static void checkOverlayHandshake() {
        if (!MtrMapOverlay.classesPresent()) {
            System.out.println("  --   no MTR Map Overlay jar on the classpath: its handshake is not "
                    + "checked here");
            return;
        }
        expect("every class, field and method the overlay reader looks up is the one it has",
                MtrMapOverlay.bind());
    }

    /** What one station of a reading is called, or an empty string when it holds no such station. */
    private static String nameOf(MtrClientData.Snapshot reading, long stationId) {
        MtrClientData.Station station = reading.station(stationId);
        return station == null || station.name() == null ? "" : station.name();
    }

    /** The rail a reading holds under an id, or null when it holds none. */
    private static MtrClientData.Track railAt(MtrClientData.Snapshot reading, String hexId) {
        for (MtrClientData.Track track : reading.tracks()) {
            if (hexId.equals(track.hexId())) {
                return track;
            }
        }
        return null;
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
        // The whole-map reader has a handshake of its own, and names the other reader never touches:
        // MTR's server entry class, which 4.1 renamed, and the simulator and route classes it holds.
        // Checked here for the same reason -- a name that has moved makes that reader report itself
        // unavailable, which is a log line and not a crash, so nothing else would notice.
        if (!MtrWholeMap.classesPresent()) {
            System.out.println("  --   no MTR server classes on the classpath: the whole-map handshake "
                    + "is not checked here");
            return;
        }
        expect("every class, field and method the whole-map reader looks up is the one MTR has",
                MtrWholeMap.bind());
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
