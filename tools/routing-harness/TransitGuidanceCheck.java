package bili.dongsz.howtogo.route;

import bili.dongsz.howtogo.road.RoadClass;
import bili.dongsz.howtogo.road.RoadNetwork;
import bili.dongsz.howtogo.road.RoadNode;
import bili.dongsz.howtogo.road.RoadSegment;
import bili.dongsz.howtogo.transit.LineStop;
import bili.dongsz.howtogo.transit.TransitLine;

import java.util.ArrayList;
import java.util.List;

/**
 * Checks what the station announcements are built from.
 *
 * <p>A public transport journey is planned as legs and then flattened into one {@link Route}, which is
 * what the map draws and what the navigation measures along. The flattening is lossy by design -- a
 * route carries one mode and cannot say where the riding begins -- so what the rider has to be told has
 * to survive on the journey itself: {@link Trip#rides()}, where each ride names its line, the end of the
 * line it is travelling towards, and every stop it calls at with its distance along the whole journey.
 *
 * <p>Everything the announcements do is arithmetic on that: which ride is in force at a distance, which
 * stop has just been reached, how many stops are left before the one being got off at, and whether the
 * next stop is that one. An off-by-one-stop mistake here is invisible until it is heard at the wrong
 * platform -- "one stop to go" while two stations out -- which is why the numbers are held rather than
 * the sentences.
 */
public final class TransitGuidanceCheck {

    private static int checks;
    private static int failures;

    private TransitGuidanceCheck() {
    }

    /**
     * Runs the checks.
     *
     * @return the number of checks made and the number that failed, in that order
     */
    public static int[] run() {
        checks = 0;
        failures = 0;
        System.out.println("== what a transit journey says about itself ==");

        // A four-stop line along one piece of rail, with a little road at each end so the walking legs
        // have something to walk on: the shape every real journey has.
        RoadNetwork network = new RoadNetwork();
        addRoad(network, RoadClass.ROAD, 0, 0, 20, 0);
        addRoad(network, RoadClass.ROAD, 280, 0, 300, 0);
        addRoad(network, RoadClass.RAIL, 0, 0, 300, 0);
        List<TransitLine> lines = List.of(line("line1", "A", 0, 0, "B", 100, 0, "C", 200, 0,
                "D", 300, 0));

        Trip trip = TransitPlanner.plan(network, lines, 5, 0, 295, 0, "the far end",
                RoutePreferences.DEFAULTS);
        expect("a journey along the line is found", trip.isPresent());
        if (!trip.isPresent()) {
            return report();
        }
        System.out.println("   DEBUG legs=" + trip.legs().size() + " rides=" + trip.rides().size());
        expect("and it is one ride", trip.rides().size() == 1);
        if (trip.rides().size() != 1) {
            return report();
        }
        Trip.Ride ride = trip.rides().get(0);

        // What the boarding announcement names: the line, and the end of it this ride is travelling
        // towards -- the last stop in the direction of travel, not the line's first stop.
        expect("the ride names its line", "line1".equals(ride.line()));
        expect("and the terminus it runs towards", "D".equals(ride.terminus()));
        expect("boarded at the first stop", "A".equals(ride.boardedAt()));
        expect("and left at the last", "D".equals(ride.leftAt()));

        // The stops it calls at, in order, at distances that only ever grow: the list the per-stop
        // announcement counts along.
        List<String> names = new ArrayList<>();
        for (Trip.RideStop stop : ride.stops()) {
            names.add(stop.name());
        }
        expect("it calls at every stop between the two (got " + names + ")",
                names.equals(List.of("A", "B", "C", "D")));
        boolean rising = true;
        for (int i = 1; i < ride.stops().size(); i++) {
            rising &= ride.stops().get(i).at() > ride.stops().get(i - 1).at();
        }
        expect("and each is further along the journey than the one before", rising);

        // The ride begins where the walk to it ended: the one invariant that makes every distance in
        // the guidance line up with the distance the navigation has travelled.
        expectNear("the ride begins where the walking leg before it ends", ride.boardAt(),
                trip.legs().get(0).route().totalLength(), 0.5);
        expectNear("and ends where the ride's last leg ends", ride.alightAt(),
                trip.totalLength() - trip.legs().get(trip.legs().size() - 1).route().totalLength(),
                0.5);

        expect("riding begins at the boarding stop", ride.riding(ride.boardAt() + 0.6));
        expect("and is over at the stop left at", !ride.riding(ride.alightAt()));

        // Which ride is in force, and where along it: the two questions every announcement asks first.
        expect("the ride in force is found by distance", trip.rideIndexAt(ride.boardAt() + 50) == 0);
        expect("and there is none on the walk before it", trip.rideIndexAt(ride.boardAt() - 5) == -1);
        expect("the ride in force is the ride itself",
                trip.rideAt(ride.boardAt() + 50) == ride);
        expect("the next ride to board is this one", trip.nextRide(0) == ride);
        expect("and there is none left after it", trip.nextRide(ride.alightAt() + 1) == null);

        // The per-stop arithmetic: just after boarding the first stop has been reached, three stops are
        // left, and the one being run to is the second.
        double afterBoarding = ride.boardAt() + 1;
        expect("the stop just reached is the boarding stop", ride.stopPassed(afterBoarding) == 0);
        expect("three stops are left to the one being left at",
                ride.stopsRemaining(afterBoarding) == 3);
        expect("and the stop being run to is the second", "B".equals(ride.nextStop(afterBoarding).name()));

        double afterSecond = ride.stops().get(1).at() + 1;
        expect("having called at the second stop, two are left",
                ride.stopsRemaining(afterSecond) == 2);
        expect("and the next one is the third", "C".equals(ride.nextStop(afterSecond).name()));

        // The stop being got off at is the one the approach announcement is about, and it is that
        // announcement -- not the per-stop one -- that is in force once the next stop is the last.
        double beforeAlighting = ride.alightAt() - 1;
        expect("one stop from the end, the next stop is the one being left at",
                ride.approachingAlighting(beforeAlighting));
        expect("and nothing is run to after it", ride.nextStop(ride.alightAt()) == null);

        // The flattened route has the same length as the legs, which is what makes a distance measured
        // along it the same number the stops above are measured in. A flattening that dropped a
        // connector or doubled the join would put every stage boundary in the wrong place.
        Route flattened = TransitPlanner.asRoute(trip, "the far end");
        expectNear("the flattened route is as long as the journey's legs together",
                flattened.totalLength(), trip.totalLength(), 0.5);

        // A journey that changes lines has one of these per ride, each naming its own line and end: the
        // transfer announcement reads the next one off the same list.
        RoadNetwork cross = new RoadNetwork();
        addRoad(cross, RoadClass.ROAD, 0, 0, 20, 0);
        addRoad(cross, RoadClass.ROAD, 280, 0, 300, 0);
        addRoad(cross, RoadClass.RAIL, 0, 0, 300, 0);
        List<TransitLine> two = List.of(
                line("first", "A", 0, 0, "M", 140, 0),
                line("second", "M", 140, 0, "D", 300, 0));
        Trip changed = TransitPlanner.plan(cross, two, 5, 0, 295, 0, "the far end",
                RoutePreferences.DEFAULTS);
        expect("a journey that changes lines is found", changed.isPresent());
        if (changed.isPresent()) {
            List<Trip.Ride> rides = changed.rides();
            expect("and it is two rides (got " + rides.size() + ")", rides.size() == 2);
            if (rides.size() == 2) {
                expect("the first names its own line", "first".equals(rides.get(0).line()));
                expect("and its own terminus", "M".equals(rides.get(0).terminus()));
                expect("the second names the line changed onto",
                        "second".equals(rides.get(1).line()));
                expect("and the terminus of that one", "D".equals(rides.get(1).terminus()));
                expect("the two are told apart by where they begin",
                        rides.get(1).boardAt() > rides.get(0).alightAt() - 1);
            }
        }

        // A journey on foot has nothing to announce: the navigation keeps the ordinary guidance.
        Trip walked = Trip.of(List.of(new Trip.Leg(
                        RoadRouter.findRoute(network, 5, 0, 15, 0, "on foot", TravelMode.WALK,
                                RoutePreferences.DEFAULTS),
                        TravelMode.WALK)),
                "A", "A");
        expect("a journey walked end to end rides nothing", !walked.ridesAnything());
        expect("and has no ride to be on", walked.rideAt(0) == null && walked.nextRide(0) == null);
        expect("as does an empty journey", !Trip.empty().ridesAnything()
                && Trip.empty().rides().isEmpty());

        return report();
    }

    private static int[] report() {
        System.out.println(failures == 0 ? "  transit guidance ok (" + checks + " checks)"
                : "  transit guidance FAILED: " + failures + " of " + checks);
        return new int[]{checks, failures};
    }

    /** A rail line calling at the given stops, each given as name, x, z. */
    private static TransitLine line(String id, Object... stops) {
        TransitLine line = new TransitLine(id, id, RoadClass.RAIL);
        for (int i = 0; i + 2 < stops.length; i += 3) {
            line.addStop(LineStop.ofStation((String) stops[i], (Integer) stops[i + 1],
                    (Integer) stops[i + 2]));
        }
        return line;
    }

    /** One straight piece of road, wired to a node at each end. */
    private static void addRoad(RoadNetwork network, RoadClass roadClass, int x0, int z0, int x1,
                                int z1) {
        RoadNode from = network.addNode(x0, 64, z0, RoadNode.Type.JUNCTION, null);
        RoadNode to = network.addNode(x1, 64, z1, RoadNode.Type.JUNCTION, null);
        RoadSegment segment = network.newSegment(roadClass, 64, 2);
        segment.addVertex(x0, z0);
        segment.addVertex(x1, z1);
        segment.setFromNode(from.id());
        segment.setToNode(to.id());
        network.addSegment(segment);
    }

    private static void expectNear(String what, double actual, double expected, double slack) {
        boolean near = Math.abs(actual - expected) <= slack;
        expect(what + (near ? "" : " (expected " + expected + ", got " + actual + ")"), near);
    }

    private static void expect(String what, boolean condition) {
        checks++;
        if (!condition) {
            failures++;
        }
        System.out.println((condition ? "  ok   " : "  FAIL ") + what);
    }
}
