import bili.dongsz.howtogo.client.HudDraw;
import bili.dongsz.howtogo.client.UiTheme;
import bili.dongsz.howtogo.road.RoadClass;
import bili.dongsz.howtogo.road.RoadNetwork;
import bili.dongsz.howtogo.road.RoadNode;
import bili.dongsz.howtogo.road.RoadSegment;
import bili.dongsz.howtogo.route.RoadRouter;
import bili.dongsz.howtogo.route.Route;
import bili.dongsz.howtogo.route.RoutePreferences;
import bili.dongsz.howtogo.route.TransitPlanner;
import bili.dongsz.howtogo.route.TravelMode;
import bili.dongsz.howtogo.route.Trip;
import bili.dongsz.howtogo.transit.LineStop;
import bili.dongsz.howtogo.transit.TransitLine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * What colour each stretch of a drawn route is, and that a one-mode route is drawn exactly as it
 * always was.
 *
 * <h2>The reading this is about</h2>
 * A route line is read by colour before anything else. Walking is blue, driving is green, and a
 * public transport journey is neither one colour nor the other at any moment: it is walked to a
 * station, ridden along a line, and walked again from a station, and each of those has its own ink.
 * The reported bug was that every surface drew the whole line in one colour, which made a ride
 * indistinguishable from the walk to it and, worse, drew every line in the driving green. So the
 * checks here are the colours themselves, per stretch, plus the boundaries the stretches are cut
 * at -- a colour that starts half a station too late is the same bug in a quieter form.
 *
 * <h2>Why this is checked without a game</h2>
 * {@link HudDraw.RouteInk} and the drawing it feeds are pure functions of a route, a journey and a
 * list of lines: points in, coloured quads out. The line's colour is set explicitly by the fixture,
 * so every number asserted is a number the fixture chose -- no config file, no world and no client.
 * {@link UiTheme} is loaded for the expected colours, and its auto theme answers "dark" with no
 * client behind it, which is what makes the comparison possible off the game.
 *
 * <h2>What is held</h2>
 * A walk is blue end to end; a drive is green; a journey ridden on a line whose colour the fixture
 * set is blue, then that exact colour, then blue, and the two boundaries land on the legs'
 * accumulated drawn lengths; a ride whose line cannot be found is neither the walking blue nor the
 * driving green; and the drawing of a single-stretch route -- whole, and cut at the player at thirty
 * percent -- emits the same vertices as the code it replaced. The last one matters most: it is what
 * says the new colour model did not repaint the old routes.
 */
public final class RouteInkCheck {

    /** The colour the fixture gives its line, so the assertion is the fixture's own number. */
    private static final int LINE_COLOUR = 0xFFE53935;

    /** Half the stroke width used for the vertex-for-vertex comparisons, in screen pixels. */
    private static final double HALF_WIDTH = 1.3;

    /** The alpha those comparisons are drawn at; opaque, so the vertex values are the whole story. */
    private static final int ALPHA = 0xFF;

    private static int checks;
    private static int failures;

    private RouteInkCheck() {
    }

    /**
     * Runs the checks.
     *
     * @return the number of checks made and the number that failed, in that order
     */
    public static int[] run() {
        checks = 0;
        failures = 0;
        System.out.println("== route ink: a colour per stretch of the line ==");
        walkIsAllBlue();
        driveIsAllGreen();
        transitIsWalkRideWalk();
        fallbackLineIsItsOwnColour();
        singleStretchIsDrawnAsItWas();
        coveredPartIsGreyAndAheadKeepsItsInk();
        System.out.println(failures == 0
                ? "  route ink ok (" + checks + " checks)"
                : "  route ink FAILED: " + failures + " of " + checks + " checks");
        return new int[]{checks, failures};
    }

    // ------------------------------------------------------------------ fixtures

    /**
     * A walk, a ride on the fixture line and a walk again, all in a straight line at z = 0.
     *
     * <p>The legs are deliberately 100, 200 and 100 blocks long: the accumulated drawn lengths are
     * the round numbers 100 and 300, so a boundary that is off by even a block is visible in the
     * assertion rather than hidden in a tolerance.
     */
    private static Trip transitTrip() {
        return transitTrip("Red line");
    }

    /** The same journey, with its ride naming the given line. */
    private static Trip transitTrip(String lineName) {
        RoadNetwork network = network();
        Route walkOut = walk(network, 0, 0, 100, 0, "to the line");
        Route ride = RoadRouter.findRoute(network, 100, 0, 300, 0, "along the line",
                TravelMode.TRANSIT, RoutePreferences.DEFAULTS);
        Route walkIn = walk(network, 300, 0, 400, 0, "from the line");
        Trip.Ride rideInfo = new Trip.Ride(lineName, "East", "West", "East", List.of());
        return Trip.of(List.of(
                new Trip.Leg(walkOut, TravelMode.WALK),
                new Trip.Leg(ride, TravelMode.TRANSIT, rideInfo),
                new Trip.Leg(walkIn, TravelMode.WALK)), "West", "East");
    }

    /** One road of 0..100, one rail of 100..300 and one road of 300..400, all at z = 0. */
    private static RoadNetwork network() {
        RoadNetwork network = new RoadNetwork();
        addRoad(network, RoadClass.ROAD, 0, 0, 100, 0);
        addRoad(network, RoadClass.RAIL, 100, 0, 300, 0);
        addRoad(network, RoadClass.ROAD, 300, 0, 400, 0);
        return network;
    }

    /** One straight road, long enough that a thirty percent cut is nowhere near a vertex. */
    private static RoadNetwork straightNetwork() {
        RoadNetwork network = new RoadNetwork();
        addRoad(network, RoadClass.ROAD, 0, 0, 1000, 0);
        return network;
    }

    /** The transit line the ride names, carrying the fixture's own colour. */
    private static TransitLine line() {
        TransitLine line = new TransitLine("red", "Red line", RoadClass.RAIL);
        line.addStop(LineStop.ofStation("West", 100, 0));
        line.addStop(LineStop.ofStation("East", 300, 0));
        line.setColor(LINE_COLOUR);
        return line;
    }

    // ------------------------------------------------------------------- checks

    /** A journey that only walks is blue from end to end, journey or no journey. */
    private static void walkIsAllBlue() {
        System.out.println("   a walk");
        RoadNetwork network = straightNetwork();
        Route route = walk(network, 10, 0, 990, 0, "the far end");
        Trip walked = Trip.of(List.of(new Trip.Leg(route, TravelMode.WALK)), "here", "there");
        Route flattened = TransitPlanner.asRoute(walked, "there");

        HudDraw.RouteInk ink = HudDraw.routeInk(flattened, walked, List.of(line()));
        expect("a walked journey is one stretch", ink.spans().size() == 1);
        if (ink.spans().size() == 1) {
            expect("and it is the walking blue",
                    ink.spans().get(0).colour() == UiTheme.route());
            expectNear("covering the whole line", ink.spans().get(0).fromDistance(), 0, 1.0E-9);
            expectNear("from end to end", ink.spans().get(0).toDistance(), ink.length(), 1.0E-9);
        }
        expect("the table shares the route's own points rather than copying them",
                ink.points().size() == flattened.points().size()
                        && ink.points().get(0) == flattened.points().get(0)
                        && ink.points().get(ink.points().size() - 1)
                        == flattened.points().get(flattened.points().size() - 1));

        // The same line with no journey at all -- which is how every walk and every drive arrives.
        HudDraw.RouteInk plain = HudDraw.routeInk(flattened, null, List.of(line()));
        expect("a route with no journey is one stretch too", plain.spans().size() == 1);
        if (plain.spans().size() == 1) {
            expect("still in the walking blue",
                    plain.spans().get(0).colour() == UiTheme.route());
        }
    }

    /** A drive is green, and a public transport plan that found no journey is not -- it is a walk. */
    private static void driveIsAllGreen() {
        System.out.println("   a drive");
        RoadNetwork network = straightNetwork();
        Route route = RoadRouter.findRoute(network, 10, 0, 990, 0, "the far end",
                TravelMode.DRIVE, RoutePreferences.DEFAULTS);
        expect("a drive is planned", route.isPresent());
        if (!route.isPresent()) {
            return;
        }
        HudDraw.RouteInk ink = HudDraw.routeInk(route, null, List.of(line()));
        expect("a drive is one stretch", ink.spans().size() == 1);
        if (ink.spans().size() == 1) {
            expect("and it is the driving green",
                    ink.spans().get(0).colour() == UiTheme.routeRide());
            expectNear("covering the whole line", ink.spans().get(0).toDistance(), ink.length(),
                    1.0E-9);
        }

        // The fallback the planners give for a public transport plan no line can carry: a walking
        // route under the transit mode. It must not be drawn in the driving green, which is the
        // colour the mode's label would suggest and the wrong answer.
        Route walkedRoute = walk(network, 10, 0, 990, 0, "on foot, though transit was asked for");
        HudDraw.RouteInk walked = HudDraw.routeInk(walkedRoute, null, List.of(line()));
        expect("a transit plan with no journey is the walker's line, not a ride",
                walked.spans().size() == 1 && walked.spans().get(0).colour() == UiTheme.route());
    }

    /** Walk, ride, walk: blue, the line's own colour, blue, cut at the legs' own distances. */
    private static void transitIsWalkRideWalk() {
        System.out.println("   a public transport journey");
        Trip trip = transitTrip();
        Route flattened = TransitPlanner.asRoute(trip, "East");
        HudDraw.RouteInk ink = HudDraw.routeInk(flattened, trip, List.of(line()));

        expect("the journey is three stretches, not one line (got " + ink.spans().size() + ")",
                ink.spans().size() == 3);
        if (ink.spans().size() != 3) {
            return;
        }
        HudDraw.InkSpan out = ink.spans().get(0);
        HudDraw.InkSpan ride = ink.spans().get(1);
        HudDraw.InkSpan in = ink.spans().get(2);
        expect("the walk to the line is blue", out.colour() == UiTheme.route());
        expect("the ride is the line's own colour, not the driving green ("
                        + Integer.toHexString(ride.colour()) + ")",
                ride.colour() == LINE_COLOUR && ride.colour() != UiTheme.routeRide());
        expect("the walk from the line is blue again", in.colour() == UiTheme.route());

        // The boundaries are the legs' accumulated drawn lengths, which is what makes the colour
        // change where the vehicle is actually boarded and left.
        double walkOut = drawnLength(trip.legs().get(0).route().points());
        double rideLength = drawnLength(trip.legs().get(1).route().points());
        expectNear("the first walk's stretch is exactly as long as the leg ("
                + walkOut + ")", out.length(), walkOut, 1.0E-6);
        expectNear("the ride's stretch starts where the walk ends", ride.fromDistance(), walkOut,
                1.0E-6);
        expectNear("and is exactly as long as the riding leg (" + rideLength + ")",
                ride.length(), rideLength, 1.0E-6);
        expectNear("the last walk runs to the end of the drawn line", in.toDistance(), ink.length(),
                1.0E-6);
        expectNear("and the whole table is as long as the flattened route",
                ink.length(), drawnLength(flattened.points()), 1.0E-6);

        // The boundary vertex is the station: the walk's last point, the ride's first, the ride's
        // last and the last walk's first are all one vertex of the flattened line each.
        double[] boarded = ink.points().get(ride.fromPoint());
        double[] left = ink.points().get(ride.toPoint());
        expectNear("the colour changes at the boarding station (x=" + boarded[0] + ")",
                boarded[0], 100, 1.0E-6);
        expectNear("and back at the alighting station (x=" + left[0] + ")", left[0], 300, 1.0E-6);
        expect("the stretches share the boundary vertices rather than being re-pointed at them",
                out.toPoint() == ride.fromPoint() && ride.toPoint() == in.fromPoint()
                        && ink.points().get(out.toPoint()) == boarded
                        && ink.points().get(in.fromPoint()) == left);

        // A journey the planner really built: the ride is several legs there -- one per pair of
        // neighbouring stops -- and they share one line and one colour, so they must be one stretch
        // and not a stripe per station. The boundaries are then checked against the accumulated
        // drawn length of the legs, which is the number that moves when the flattening changes.
        RoadNetwork planned = new RoadNetwork();
        addRoad(planned, RoadClass.ROAD, 0, 0, 20, 0);
        addRoad(planned, RoadClass.ROAD, 280, 0, 300, 0);
        addRoad(planned, RoadClass.RAIL, 0, 0, 300, 0);
        TransitLine fourStops = new TransitLine("line1", "line1", RoadClass.RAIL);
        fourStops.addStop(LineStop.ofStation("A", 0, 0));
        fourStops.addStop(LineStop.ofStation("B", 100, 0));
        fourStops.addStop(LineStop.ofStation("C", 200, 0));
        fourStops.addStop(LineStop.ofStation("D", 300, 0));
        fourStops.setColor(LINE_COLOUR);
        Trip plannedTrip = TransitPlanner.plan(planned, List.of(fourStops), 5, 0, 295, 0,
                "the far end", RoutePreferences.DEFAULTS);
        expect("a journey over a four-stop line is planned", plannedTrip.isPresent());
        if (plannedTrip.isPresent()) {
            Route plannedRoute = TransitPlanner.asRoute(plannedTrip, "the far end");
            HudDraw.RouteInk plannedInk = HudDraw.routeInk(plannedRoute, plannedTrip,
                    List.of(fourStops));
            expect("its ride legs are one stretch, not one per stop ("
                            + plannedInk.spans().size() + " stretches)",
                    plannedInk.spans().size() == 3);
            if (plannedInk.spans().size() == 3) {
                // How many legs were walked before the ride and how long those legs are drawn: the
                // first boundary the ink must find.
                double toStation = 0;
                double onLine = 0;
                for (Trip.Leg leg : plannedTrip.legs()) {
                    if (leg.mode() == TravelMode.WALK && onLine == 0) {
                        toStation += drawnLength(leg.route().points());
                    } else if (leg.mode() != TravelMode.WALK) {
                        onLine += drawnLength(leg.route().points());
                    }
                }
                expectNear("the walking stretch ends where the walked legs end",
                        plannedInk.spans().get(0).toDistance(), toStation, 1.0E-6);
                expectNear("the ride stretch is every ride leg added up",
                        plannedInk.spans().get(1).length(), onLine, 1.0E-6);
                expect("and it is drawn in the line's colour",
                        plannedInk.spans().get(1).colour() == LINE_COLOUR);
            }
        }
    }

    /** A ride whose line cannot be resolved is drawn in a colour of its own, never in the drive green. */
    private static void fallbackLineIsItsOwnColour() {
        System.out.println("   a ride whose line is gone");
        Trip trip = transitTrip();
        Route flattened = TransitPlanner.asRoute(trip, "East");
        HudDraw.RouteInk ink = HudDraw.routeInk(flattened, trip, List.of());
        expect("the journey is still three stretches", ink.spans().size() == 3);
        if (ink.spans().size() == 3) {
            int ride = ink.spans().get(1).colour();
            expect("the unresolved ride keeps its walk before and after it",
                    ink.spans().get(0).colour() == UiTheme.route()
                            && ink.spans().get(2).colour() == UiTheme.route());
            expect("and is drawn in the missing-line fallback ("
                            + Integer.toHexString(ride) + ")",
                    ride == UiTheme.routeLineFallback());
            expect("which is not the driving green", ride != UiTheme.routeRide());
            expect("nor the walking blue", ride != UiTheme.route());
            expect("nor the travelled grey", ride != UiTheme.routeDone());
        }

        // A line carrying no colour of its own is drawn in its kind's configured colour, which is
        // the same fallback the line's badge and the editor's swatch use.
        TransitLine plain = new TransitLine("plain", "Plain line", RoadClass.RAIL);
        plain.addStop(LineStop.ofStation("West", 100, 0));
        plain.addStop(LineStop.ofStation("East", 300, 0));
        Trip plainTrip = transitTrip("Plain line");
        HudDraw.RouteInk inherited = HudDraw.routeInk(
                TransitPlanner.asRoute(plainTrip, "East"), plainTrip, List.of(plain));
        expect("a line without a colour of its own takes its kind's colour",
                inherited.spans().size() == 3 && inherited.spans().get(1).colour()
                        == bili.dongsz.howtogo.RoadConfig.transitLineColor(RoadClass.RAIL));
    }

    /**
     * A one-stretch route, drawn whole, produces the vertices the old whole-polyline call did.
     *
     * <p>This is the promise that the colour model did not repaint the routes that were already
     * right: every walk and every drive is one stretch now, so the ink path has to be the old path
     * with a different signature.
     */
    private static void singleStretchIsDrawnAsItWas() {
        System.out.println("   a walk and a drive still draw as they did");
        RoadNetwork network = straightNetwork();
        Route walk = walk(network, 10, 0, 990, 0, "the far end");
        HudDraw.RouteInk ink = HudDraw.routeInk(walk, null, List.of());

        Capture whole = new Capture();
        HudDraw.emitInk(whole, ink, 1, 0, 0, 0, 1, 0, HALF_WIDTH, ALPHA,
                -10_000, -10_000, 10_000, 10_000);
        Capture old = new Capture();
        HudDraw.emitPath(old, walk.points(), 1, 0, 0, 0, 1, 0, HALF_WIDTH, UiTheme.route(), ALPHA,
                -10_000, -10_000, 10_000, 10_000);
        expect("a whole walk emits the same vertices as the old whole-polyline call ("
                        + whole.vertexCount() + " / " + old.vertexCount() + ")",
                whole.sameAs(old));
        expect("and the colour it emits is the walk's own",
                whole.onlyColour(UiTheme.route()));

        Route drive = RoadRouter.findRoute(network, 10, 0, 990, 0, "the far end",
                TravelMode.DRIVE, RoutePreferences.DEFAULTS);
        HudDraw.RouteInk driveInk = HudDraw.routeInk(drive, null, List.of());
        Capture driveCapture = new Capture();
        HudDraw.emitInk(driveCapture, driveInk, 1, 0, 0, 0, 1, 0, HALF_WIDTH, ALPHA,
                -10_000, -10_000, 10_000, 10_000);
        Capture driveOld = new Capture();
        HudDraw.emitPath(driveOld, drive.points(), 1, 0, 0, 0, 1, 0, HALF_WIDTH,
                UiTheme.routeRide(), ALPHA, -10_000, -10_000, 10_000, 10_000);
        expect("a whole drive emits the same vertices as before and in the driving green",
                driveCapture.sameAs(driveOld) && driveCapture.onlyColour(UiTheme.routeRide()));
    }

    /**
     * The player at thirty percent of the first stretch: the covered part is grey, the rest keeps
     * its own ink, and the cut is where the distance says rather than at the next vertex.
     */
    private static void coveredPartIsGreyAndAheadKeepsItsInk() {
        System.out.println("   the part already covered");

        // A single stretch first: the split must be the one the old two-list code made, vertex for
        // vertex, so the progress colouring of every walk and drive is unchanged.
        RoadNetwork network = straightNetwork();
        Route walk = walk(network, 0, 0, 1000, 0, "the far end");
        HudDraw.RouteInk ink = HudDraw.routeInk(walk, null, List.of());
        double travelled = ink.length() * 0.3;
        Capture splitNew = new Capture();
        HudDraw.emitRouteInk(splitNew, ink, travelled, UiTheme.routeDone(),
                1, 0, 0, 0, 1, 0, HALF_WIDTH, ALPHA, -10_000, -10_000, 10_000, 10_000);
        Capture splitOld = oldSplit(walk.points(), travelled);
        expect("a cut single-stretch route emits the same vertices as the old split did ("
                        + splitNew.vertexCount() + " / " + splitOld.vertexCount() + ")",
                splitNew.sameAs(splitOld));
        expect("with both inks present", splitNew.hasColour(UiTheme.routeDone())
                && splitNew.hasColour(UiTheme.route()));

        // The journey: thirty percent of the first walk, so the ride and the second walk are
        // entirely ahead and keep their own inks.
        Trip trip = transitTrip();
        Route flattened = TransitPlanner.asRoute(trip, "East");
        HudDraw.RouteInk journey = HudDraw.routeInk(flattened, trip, List.of(line()));
        double walkLength = drawnLength(trip.legs().get(0).route().points());
        double cut = walkLength * 0.3;

        Capture done = new Capture();
        HudDraw.emitInkPart(done, journey, cut, true, UiTheme.routeDone(),
                1, 0, 0, 0, 1, 0, HALF_WIDTH, ALPHA, -10_000, -10_000, 10_000, 10_000);
        Capture ahead = new Capture();
        HudDraw.emitInkPart(ahead, journey, cut, false, UiTheme.routeDone(),
                1, 0, 0, 0, 1, 0, HALF_WIDTH, ALPHA, -10_000, -10_000, 10_000, 10_000);

        expect("the covered part is drawn, and in the travelled grey only",
                done.vertexCount() > 0 && done.onlyColour(UiTheme.routeDone()));
        expect("it stops where the player is rather than at the end of the walk (max x "
                        + round(done.maxX()) + " for a cut at " + round(cut) + ")",
                done.maxX() <= cut + HALF_WIDTH + 0.01 && done.maxX() >= cut - 0.01);

        expect("the part ahead starts at the player (min x " + round(ahead.minX()) + ")",
                Math.abs(ahead.minX() - (cut - HALF_WIDTH)) <= 0.01);
        expect("and carries the walking blue and the line's colour",
                ahead.hasColour(UiTheme.route()) && ahead.hasColour(LINE_COLOUR));
        expect("the line's colour runs between the two stations (x "
                        + round(ahead.minX(LINE_COLOUR)) + " to "
                        + round(ahead.maxX(LINE_COLOUR)) + ")",
                ahead.minX(LINE_COLOUR) >= 100 - 1.0E-6
                        && ahead.maxX(LINE_COLOUR) <= 300 + 1.0E-6);
        expect("and nothing else is drawn between them, so the stretches meet without capping "
                        + "into each other",
                ahead.noOtherColourBetween(100 + 1.0E-6, 300 - 1.0E-6, LINE_COLOUR));
        expect("the walking blue is on both sides of it, as the journey is walked at both ends",
                ahead.minX(UiTheme.route()) < 100 && ahead.maxX(UiTheme.route()) > 300);

        // The two ends of the range: nothing covered and everything covered. Neither goes through
        // the split arithmetic, and both have to draw exactly what they used to.
        expect("nothing covered draws the whole line ahead, as it did", drawsLikeOld(ink, 0));
        expect("everything covered draws the whole line grey, as it did",
                drawsLikeOld(ink, ink.length() + 5));
    }

    /** Whether a one-stretch route cut at the given distance emits the old split's vertices. */
    private static boolean drawsLikeOld(HudDraw.RouteInk ink, double travelled) {
        Capture now = new Capture();
        HudDraw.emitRouteInk(now, ink, travelled, UiTheme.routeDone(),
                1, 0, 0, 0, 1, 0, HALF_WIDTH, ALPHA, -10_000, -10_000, 10_000, 10_000);
        return now.sameAs(oldSplit(ink.points(), travelled));
    }

    /**
     * The split the navigation panel used to work out, as two lists of world points.
     *
     * <p>Written out here on purpose, rather than imported: the check is that the new drawing of the
     * same route emits what this algorithm fed to the old drawing, and a copy that called the new
     * code would assert nothing.
     */
    private static Capture oldSplit(List<double[]> points, double travelled) {
        double cumulative = 0;
        int splitAt = -1;
        double splitX = 0;
        double splitY = 0;
        for (int i = 1; i < points.size(); i++) {
            double[] a = points.get(i - 1);
            double[] b = points.get(i);
            double segmentLength = Math.hypot(b[0] - a[0], b[1] - a[1]);
            if (segmentLength < 1.0E-9) {
                continue;
            }
            if (cumulative + segmentLength >= travelled) {
                splitAt = i;
                double t = (travelled - cumulative) / segmentLength;
                splitX = a[0] + (b[0] - a[0]) * t;
                splitY = a[1] + (b[1] - a[1]) * t;
                break;
            }
            cumulative += segmentLength;
        }
        List<double[]> done = new ArrayList<>();
        List<double[]> ahead = new ArrayList<>();
        if (splitAt < 0) {
            if (travelled > 0) {
                done.addAll(points);
            } else {
                ahead.addAll(points);
            }
        } else if (splitAt <= 0) {
            ahead.addAll(points);
        } else {
            done.addAll(points.subList(0, splitAt));
            done.add(new double[]{splitX, splitY});
            ahead.add(new double[]{splitX, splitY});
            ahead.addAll(points.subList(splitAt, points.size()));
        }
        Capture capture = new Capture();
        if (done.size() >= 2) {
            HudDraw.emitPath(capture, done, 1, 0, 0, 0, 1, 0, HALF_WIDTH, UiTheme.routeDone(), ALPHA,
                    -10_000, -10_000, 10_000, 10_000);
        }
        if (ahead.size() >= 2) {
            HudDraw.emitPath(capture, ahead, 1, 0, 0, 0, 1, 0, HALF_WIDTH, UiTheme.route(), ALPHA,
                    -10_000, -10_000, 10_000, 10_000);
        }
        return capture;
    }

    // ---------------------------------------------------------------- fixture bits

    private static Route walk(RoadNetwork network, double fromX, double fromZ, double toX,
                              double toZ, String name) {
        return RoadRouter.findRoute(network, fromX, fromZ, toX, toZ, name, TravelMode.WALK,
                RoutePreferences.DEFAULTS);
    }

    /** One straight piece of road, wired to a node at each end. */
    private static void addRoad(RoadNetwork network, RoadClass roadClass, int x0, int z0, int x1,
                                int z1) {
        RoadNode from = nodeAt(network, x0, z0);
        RoadNode to = nodeAt(network, x1, z1);
        RoadSegment segment = network.newSegment(roadClass, 64, 2);
        segment.addVertex(x0, z0);
        segment.addVertex(x1, z1);
        segment.setFromNode(from.id());
        segment.setToNode(to.id());
        network.addSegment(segment);
    }

    private static RoadNode nodeAt(RoadNetwork network, int x, int z) {
        RoadNode existing = network.nearestNode(x, z, 0.0);
        return existing != null ? existing : network.addNode(x, 64, z, RoadNode.Type.JUNCTION, null);
    }

    /** The drawn length of a polyline, which is what the stretches' boundaries are measured in. */
    private static double drawnLength(List<double[]> points) {
        double length = 0;
        for (int i = 1; i < points.size(); i++) {
            double[] a = points.get(i - 1);
            double[] b = points.get(i);
            length += Math.hypot(b[0] - a[0], b[1] - a[1]);
        }
        return length;
    }

    // ------------------------------------------------------------------- capture

    /**
     * Every vertex the drawing emitted: position and colour.
     *
     * <p>Positions and colours both, because the two promises are separate: the geometry of a
     * one-stretch route must be unchanged, and the colour of each stretch must be the one its mode
     * calls for. The buffers are copied rather than referenced so two captures can be compared after
     * the fact, and nothing here is on any game path.
     */
    private static final class Capture implements HudDraw.VertexSink {

        private double[] xy = new double[2 * 256];
        private int[] rgba = new int[256];
        private int vertices;

        @Override
        public void vertex(double x, double y, int r, int g, int b, int a) {
            if (vertices == rgba.length) {
                xy = Arrays.copyOf(xy, xy.length * 2);
                rgba = Arrays.copyOf(rgba, rgba.length * 2);
            }
            xy[vertices * 2] = x;
            xy[vertices * 2 + 1] = y;
            rgba[vertices] = (a << 24) | (r << 16) | (g << 8) | b;
            vertices++;
        }

        int vertexCount() {
            return vertices;
        }

        /** Whether two captures emitted exactly the same vertices in the same order. */
        boolean sameAs(Capture other) {
            if (vertices != other.vertices) {
                return false;
            }
            for (int i = 0; i < vertices; i++) {
                if (xy[i * 2] != other.xy[i * 2] || xy[i * 2 + 1] != other.xy[i * 2 + 1]) {
                    return false;
                }
            }
            return true;
        }

        /** Whether every vertex has the given RGB, ignoring the alpha the painter supplies. */
        boolean onlyColour(int argb) {
            int rgb = argb & 0xFFFFFF;
            for (int i = 0; i < vertices; i++) {
                if ((rgba[i] & 0xFFFFFF) != rgb) {
                    return false;
                }
            }
            return vertices > 0;
        }

        /** Whether any vertex has the given RGB. */
        boolean hasColour(int argb) {
            int rgb = argb & 0xFFFFFF;
            for (int i = 0; i < vertices; i++) {
                if ((rgba[i] & 0xFFFFFF) == rgb) {
                    return true;
                }
            }
            return false;
        }

        double minX() {
            double min = Double.MAX_VALUE;
            for (int i = 0; i < vertices; i++) {
                min = Math.min(min, xy[i * 2]);
            }
            return vertices == 0 ? Double.NaN : min;
        }

        double maxX() {
            double max = -Double.MAX_VALUE;
            for (int i = 0; i < vertices; i++) {
                max = Math.max(max, xy[i * 2]);
            }
            return vertices == 0 ? Double.NaN : max;
        }

        /** The least x among vertices of one colour, for asking where a stretch begins. */
        double minX(int argb) {
            int rgb = argb & 0xFFFFFF;
            double min = Double.MAX_VALUE;
            for (int i = 0; i < vertices; i++) {
                if ((rgba[i] & 0xFFFFFF) == rgb) {
                    min = Math.min(min, xy[i * 2]);
                }
            }
            return min == Double.MAX_VALUE ? Double.NaN : min;
        }

        /** The greatest x among vertices of one colour, for asking where a stretch ends. */
        double maxX(int argb) {
            int rgb = argb & 0xFFFFFF;
            double max = -Double.MAX_VALUE;
            for (int i = 0; i < vertices; i++) {
                if ((rgba[i] & 0xFFFFFF) == rgb) {
                    max = Math.max(max, xy[i * 2]);
                }
            }
            return max == -Double.MAX_VALUE ? Double.NaN : max;
        }

        /**
         * Whether no vertex strictly inside the range has a colour other than the given one.
         *
         * <p>A straight stretch between two stations has no vertex between them at all -- the edge
         * quad's corners are its two ends -- so an empty range is not a failure. What this can catch
         * is exactly the fault it is for: a cap from a neighbouring stretch poking a semicircle of
         * its own colour past the station, which always puts vertices inside the range.
         */
        boolean noOtherColourBetween(double fromX, double toX, int argb) {
            int rgb = argb & 0xFFFFFF;
            for (int i = 0; i < vertices; i++) {
                double x = xy[i * 2];
                if (x <= fromX || x >= toX) {
                    continue;
                }
                if ((rgba[i] & 0xFFFFFF) != rgb) {
                    return false;
                }
            }
            return true;
        }
    }

    // ----------------------------------------------------------------- reporting

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

    private static String round(double value) {
        return Double.toString(Math.round(value * 1000.0) / 1000.0);
    }
}
