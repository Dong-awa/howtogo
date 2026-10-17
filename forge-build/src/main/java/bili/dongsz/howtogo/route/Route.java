package bili.dongsz.howtogo.route;

import bili.dongsz.howtogo.road.RoadClass;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A computed route from the player to a destination.
 *
 * <p>Three parts: an off-road connector from the player to the first road node, the road polyline
 * itself, and a connector from the last road node to the destination. The connectors are kept
 * separate rather than folded into one number so the UI can warn that part of the trip is off-road.
 *
 * <p>Each polyline point also carries the on-road tolerance of the road it belongs to, so
 * "am I still on route?" can be judged against the width of the road the player is meant to be on
 * rather than one global number.
 */
public final class Route {

    /** Ordered world points (x, z) forming the drawn line, connectors included. */
    private final List<double[]> points;
    /** On-road tolerance in blocks, parallel to {@link #points}. */
    private final double[] tolerances;
    /**
     * Which road each point belongs to, parallel to {@link #points}.
     *
     * <p>A manoeuvre is only announced where this changes: a bend inside one road is not a turn,
     * however sharp the angle looks geometrically.
     */
    private final int[] roadKeys;

    /** Name of the road each point belongs to, parallel to {@link #points}. May be null. */
    private final String[] roadNames;
    /**
     * Whether each point is a real junction, parallel to {@link #points}.
     *
     * <p>A junction here means a node the road actually forks at -- three or more segment ends. The
     * road data is stored as segments joined at pass-through nodes, and a class or name change also
     * breaks the sequence without anything branching, so "the road key changed" on its own says only
     * that the road data changed, not that the player has a decision to make. Telling a player to
     * turn onto the road they are already on comes from treating the second as the first.
     */
    private final boolean[] branchAt;
    private final double startConnector;
    private final double goalConnector;
    /** The pieces of road the route is made of, in the order they are travelled. */
    private final List<Leg> legs;
    private final String destinationName;
    /** Off-road speed as a fraction of the mode's pace, applied to the connectors. */
    private final double offRoadSpeedFactor;
    /**
     * Time on this route that is not distance: waiting for a service, in seconds.
     *
     * <p>Everything else a route is timed by comes from its legs -- a length at a pace -- which is the
     * right shape for anything travelled and cannot express standing still. A public transport leg
     * begins with a wait for the vehicle, and without a term for it the estimate of a journey that
     * changes lines was short by exactly the waiting it was asking the player to do, while the search
     * that chose the journey had paid for that waiting all along. The two now agree, which is what
     * makes the reported estimate the number the route was actually chosen by.
     *
     * <p>Deliberately outside {@link #totalLength()} and outside {@link #secondsPerBlock()}: waiting
     * covers no ground, and the pace underfoot that the readout projects onto the distance left is
     * about the ground.
     */
    private final double fixedSeconds;
    /**
     * The mode this route is travelled and timed in.
     *
     * <p>Kept on the route rather than only on the navigation session, because the ETA is a
     * property of the route: the same polyline walked and driven does not take the same time, and
     * an estimate that outlives the mode it was made for is simply wrong. For a route that has been
     * re-timed for another mode -- see {@link #timedFor} -- this is the mode it is now timed in,
     * which is the one the panel's estimate and the countdown are made in.
     */
    private final TravelMode travelMode;

    /** Cached turn points. */
    private List<Maneuver> maneuvers;

    /**
     * Drawn distance from the first point to each point, parallel to {@link #points}.
     *
     * <p>Computed once, at construction, because "how far along the line is this position" is asked
     * several times per frame by the readouts and the answer cannot change: the polyline is never
     * written to after it is built. Walking it each time was the same numbers added up again, on the
     * render path, for every frame of a trip.
     */
    private final double[] cumulative;

    /** Total drawn length, connectors included. The last entry of {@link #cumulative}. */
    private final double polylineLength;

    /** Where the cached projection was measured from, and what it found. See {@link #closest}. */
    private double projectionX = Double.NaN;
    private double projectionZ = Double.NaN;
    private Projection projectionCache;
    private boolean projectionCached;

    /** A turn sharper than this counts as a manoeuvre worth announcing. */
    private static final double TURN_THRESHOLD_DEG = 25.0;

    Route(List<double[]> points, double[] tolerances, int[] roadKeys, String[] roadNames,
          boolean[] branchAt, double startConnector, double goalConnector,
          List<Leg> legs, String destinationName, double offRoadSpeedFactor,
          TravelMode travelMode, double fixedSeconds) {
        this.points = points;
        this.tolerances = tolerances;
        this.roadKeys = roadKeys;
        this.roadNames = roadNames;
        this.branchAt = branchAt;
        this.startConnector = startConnector;
        this.goalConnector = goalConnector;
        this.legs = legs;
        this.destinationName = destinationName;
        this.offRoadSpeedFactor = offRoadSpeedFactor <= 0 ? 1.0 : offRoadSpeedFactor;
        this.travelMode = travelMode == null ? TravelMode.WALK : travelMode;
        this.fixedSeconds = Math.max(0, fixedSeconds);
        this.cumulative = new double[points.size()];
        double drawn = 0;
        for (int i = 1; i < points.size(); i++) {
            double[] a = points.get(i - 1);
            double[] b = points.get(i);
            drawn += Math.hypot(b[0] - a[0], b[1] - a[1]);
            cumulative[i] = drawn;
        }
        this.polylineLength = drawn;
    }

    public static Route empty() {
        return new Route(List.of(), new double[0], new int[0], new String[0], new boolean[0], 0, 0,
                List.of(), null, 1.0, TravelMode.WALK, 0);
    }

    /**
     * One piece of the route, as the estimate needs it: how long it is, the pace it is travelled at,
     * and which class of road it is on.
     *
     * <p>The class is carried beside the pace because the pace is a property of the pair rather than
     * of either half -- the same road walked and driven does not cost the same -- so a route that is
     * to be timed for another mode needs to know what each piece of it is. It is null for a piece of
     * the journey with no road under it at all: a connector, which is walked whatever the mode, and
     * which is timed at the pace it was built with rather than at a table's.
     */
    private record Leg(double length, double pace, RoadClass roadClass) {
    }

    /**
     * The same line, timed as the given mode would travel it.
     *
     * <h2>What this is for</h2>
     * A route is planned in one mode and is normally timed in that same one. The exception is a
     * drive to a destination the drivable network does not reach: the walker is the only one who can
     * leave the network, so the walker's line is the only plan there is -- and the trip the player
     * then makes along it is the car's up to the last road and the walker's from there. Timing that
     * line at walking pace throughout is what reported a destination with a short walk at the end as
     * a much longer trip than it is, and worse, as a trip in which the road under the car was walked.
     *
     * <h2>What changes and what does not</h2>
     * Each piece of road the given mode may travel is re-timed at that mode's own pace for its class;
     * every other piece keeps the pace it was planned at, which is the walker's. Nothing else moves:
     * the geometry, the connectors, the tolerances and the waiting are the route's own, so the line
     * drawn and guided along is exactly the line that was planned, and only the time it is expected
     * to take is that of the trip the player would really make.
     *
     * <p>Deliberately not offered to public transport: a journey is not a ride where one can and a
     * walk where one cannot. When no line can carry a journey the answer is the walk, and re-timing
     * it as a ride would invent a vehicle nobody boarded.
     *
     * @param mode the mode to time the route for, or null to leave it as it is
     */
    public Route timedFor(TravelMode mode) {
        TravelMode wanted = mode == null ? travelMode : mode;
        if (wanted == travelMode) {
            return this;
        }
        List<Leg> retimed = new ArrayList<>(legs.size());
        for (Leg leg : legs) {
            if (leg.roadClass() != null && wanted.allows(leg.roadClass())) {
                retimed.add(new Leg(leg.length(), Math.max(0.05, wanted.speedOn(leg.roadClass())),
                        leg.roadClass()));
            } else {
                retimed.add(leg);
            }
        }
        return new Route(points, tolerances, roadKeys, roadNames, branchAt, startConnector,
                goalConnector, retimed, destinationName, offRoadSpeedFactor, wanted, fixedSeconds);
    }

    /**
     * The same route, plus time that is not distance.
     *
     * <p>For the waiting a public transport leg begins with: the geometry, the legs and the pace are
     * all unchanged, and only the estimate grows. The arrays are shared rather than copied -- a route
     * is never written to after it is built.
     */
    Route plusFixedSeconds(double seconds) {
        if (seconds <= 0) {
            return this;
        }
        return new Route(points, tolerances, roadKeys, roadNames, branchAt, startConnector,
                goalConnector, legs, destinationName, offRoadSpeedFactor, travelMode,
                fixedSeconds + seconds);
    }

    /**
     * Joins several routes end to end into one, keeping each part's own pace.
     *
     * <h2>Why this lives here and not in the planner</h2>
     * Every field of a route is private and five of them are parallel arrays. A merge written outside
     * this class would have to expose all five to be able to do anything, and the first time one of
     * them was concatenated a line out of step with the others the damage would be silent and
     * strange: a tolerance read from the wrong point makes a player "off route" while standing on it,
     * and a road key read from the wrong point invents a turn where there is none. Keeping the merge
     * inside the class that owns the invariant is what makes it possible to state the invariant at
     * all: every parallel array is appended in the same loop, from the same index, or not at all.
     *
     * <h2>What is kept from which part</h2>
     * The two connectors of the whole journey are the first part's start and the last part's goal:
     * those, and only those, describe getting on and off the network at the ends. Every connector
     * between them is travelled too, so it is recorded as a piece of the whole route at the pace it is
     * walked -- see the comment in the loop for what dropping them used to cost. The off-road speed
     * factor comes from the first part, which is the one whose connector it is timing. The pace of
     * each ridden stretch is not taken from anywhere: each part already recorded it in its own
     * {@code legs}, and those are appended as they are, so a walked stretch keeps the walking pace and
     * a ridden one keeps the line's.
     *
     * @param parts routes in the order they are travelled; a part that is not present is skipped
     */
    static Route concat(List<Route> parts, TravelMode travelMode, String destinationName) {
        List<double[]> points = new ArrayList<>();
        List<Double> tolerances = new ArrayList<>();
        List<Integer> roadKeys = new ArrayList<>();
        List<String> roadNames = new ArrayList<>();
        List<Boolean> branchAt = new ArrayList<>();
        List<Leg> legs = new ArrayList<>();
        double startConnector = 0;
        double goalConnector = 0;
        double offRoadSpeedFactor = 1.0;

        List<Route> present = new ArrayList<>(parts.size());
        for (Route part : parts) {
            if (part.isPresent()) {
                present.add(part);
            }
        }

        for (int p = 0; p < present.size(); p++) {
            Route part = present.get(p);
            for (int i = 0; i < part.points.size(); i++) {
                double[] point = part.points.get(i);
                // The join is one coordinate written twice: a leg ends at the station and the next
                // begins there. Its second copy is dropped from every array at once, in this same
                // index-guarded step, which is the only way the arrays stay parallel.
                if (i == 0 && !points.isEmpty() && samePoint(points.get(points.size() - 1), point)) {
                    continue;
                }
                points.add(point);
                tolerances.add(part.tolerances[i]);
                roadKeys.add(part.roadKeys[i]);
                roadNames.add(part.roadNames[i]);
                branchAt.add(part.branchAt[i]);
            }
            legs.addAll(part.legs);
            // Only the first part's start and the last part's goal describe getting on and off the
            // whole journey; every other connector belongs to the middle of it and is travelled all
            // the same. Leaving them out -- which is what taking only the two ends did -- made a
            // public transport journey's time and length short by every station-side hop in it, and
            // the walking comparison was then made against that short number.
            if (p > 0) {
                legs.add(new Leg(part.startConnector, part.connectorPace(), null));
            }
            if (p < present.size() - 1) {
                legs.add(new Leg(part.goalConnector, part.connectorPace(), null));
            }
        }

        double waitingSeconds = 0;
        if (!present.isEmpty()) {
            Route first = present.get(0);
            Route last = present.get(present.size() - 1);
            startConnector = first.startConnector;
            goalConnector = last.goalConnector;
            offRoadSpeedFactor = first.offRoadSpeedFactor;
            // Waiting does not cover ground, so it is carried as itself rather than folded into the
            // legs: every leg's time is its length over its pace, and a zero-length leg would add
            // nothing however long the wait was.
            for (Route part : present) {
                waitingSeconds += part.fixedSeconds;
            }
        }

        double[] toleranceArray = new double[tolerances.size()];
        int[] keyArray = new int[roadKeys.size()];
        String[] nameArray = new String[roadNames.size()];
        boolean[] branchArray = new boolean[branchAt.size()];
        for (int i = 0; i < toleranceArray.length; i++) {
            toleranceArray[i] = tolerances.get(i);
            keyArray[i] = roadKeys.get(i);
            nameArray[i] = roadNames.get(i);
            branchArray[i] = branchAt.get(i);
        }
        return new Route(points, toleranceArray, keyArray, nameArray, branchArray, startConnector,
                goalConnector, legs, destinationName, offRoadSpeedFactor, travelMode,
                waitingSeconds);
    }

    private static boolean samePoint(double[] a, double[] b) {
        return Math.abs(a[0] - b[0]) < 1.0E-6 && Math.abs(a[1] - b[1]) < 1.0E-6;
    }

    /**
     * One announced turn.
     *
     * @param distanceFromStart how far along the route the junction is
     * @param turnDegrees       signed angle, positive being a right turn
     * @param roadName          the road being turned onto, or null when it has no name
     * @param junctionX         world x of the junction itself
     * @param junctionZ         world z of the junction itself
     * @param bearingAfter      direction of the route leaving the junction, in degrees from +X
     *                          towards +Z, which is what a player's heading has to match for the
     *                          turn to count as taken rather than passed
     * @param namesTheRoad      whether the road being entered is worth naming: it has a name, and not
     *                          the one the route was already on
     */
    public record Maneuver(double distanceFromStart, double turnDegrees, String roadName,
                           double junctionX, double junctionZ, double bearingAfter,
                           boolean namesTheRoad) {
    }

    public boolean isPresent() {
        return points.size() >= 2;
    }

    public List<double[]> points() {
        return Collections.unmodifiableList(points);
    }

    public String destinationName() {
        return destinationName;
    }

    /** The mode this route was planned for. */
    public TravelMode travelMode() {
        return travelMode;
    }

    /** Length of the road part only, in blocks. */
    public double roadLength() {
        double roadDistance = 0;
        for (Leg leg : legs) {
            roadDistance += leg.length();
        }
        return roadDistance;
    }

    public double startConnector() {
        return startConnector;
    }

    public double goalConnector() {
        return goalConnector;
    }

    // ---------------------------------------------------------------- distance

    /** Cached turn points, each as {@code {distanceFromStart, signedDegrees}}. */
    public List<Maneuver> maneuvers() {
        if (maneuvers == null) {
            maneuvers = computeManeuvers();
        }
        return maneuvers;
    }

    private List<Maneuver> computeManeuvers() {
        List<Maneuver> result = new ArrayList<>();
        if (points.size() < 3) {
            return result;
        }
        double travelled = 0;
        for (int i = 1; i < points.size() - 1; i++) {
            double[] a = points.get(i - 1);
            double[] b = points.get(i);
            double[] c = points.get(i + 1);
            double inX = b[0] - a[0];
            double inZ = b[1] - a[1];
            double inLength = Math.hypot(inX, inZ);
            travelled += inLength;

            double outX = c[0] - b[0];
            double outZ = c[1] - b[1];
            if (inLength < 1.0E-6 || Math.hypot(outX, outZ) < 1.0E-6) {
                continue;
            }
            // Only a change of road is a turn. A sharp corner inside one road is just a bend, and
            // announcing it would tell the player to turn when they are already on the right road.
            if (i + 1 >= roadKeys.length || roadKeys[i] == roadKeys[i + 1]) {
                continue;
            }
            // Signed so that positive is a right turn, following Minecraft's axes (X east, Z south).
            double delta = Math.toDegrees(wrapRadians(
                    Math.atan2(outZ, outX) - Math.atan2(inZ, inX)));
            // ... and it has to be a decision. Either the route genuinely bends here, or it carries
            // on into a road with a name of its own at a real fork. A class or name change that does
            // neither is the same piece of ground continuing, and calling that a turn is what told
            // players to turn onto the road they were already on.
            boolean turns = Math.abs(delta) >= TURN_THRESHOLD_DEG;
            if (!turns && !(isBranch(i) && entersADifferentRoad(i))) {
                continue;
            }
            // The junction's position and the bearing leaving it, both already in hand here, so
            // the caller can tell a turn the player took from one they walked straight past.
            result.add(new Maneuver(travelled, delta, nameAt(i + 1),
                    b[0], b[1], Math.toDegrees(Math.atan2(outZ, outX)), namesTheRoad(i)));
        }
        return result;
    }

    /**
     * Whether naming the road this turn enters tells the player anything.
     *
     * <p>Naming the road they are already on does not: "turn right onto unnamed road" while standing
     * on an unnamed road, or onto the same road's name at a fork where the name carries on, reads as
     * the tool having lost track of where they are. The turn is still a turn and is still announced;
     * it is only the name that is dropped.
     */
    private boolean namesTheRoad(int pointIndex) {
        String before = nameAt(pointIndex);
        String after = nameAt(pointIndex + 1);
        return after != null && !after.isBlank() && !after.equals(before);
    }

    /** Whether the polyline point is a real junction: a node with three or more segment ends. */
    private boolean isBranch(int pointIndex) {
        if (branchAt.length == 0) {
            return false;
        }
        int i = Math.max(0, Math.min(pointIndex, branchAt.length - 1));
        int j = Math.max(0, Math.min(pointIndex + 1, branchAt.length - 1));
        return branchAt[i] || branchAt[j];
    }

    /**
     * Whether the road the route enters at this point has a name of its own, different from the one
     * it leaves.
     *
     * <p>A name is what makes the fork worth announcing when the route does not bend: the player has
     * to know which of the two roads carrying on is theirs. Where the new road has no name there is
     * nothing to say that the map's road does not already say.
     */
    private boolean entersADifferentRoad(int pointIndex) {
        String before = nameAt(pointIndex);
        String after = nameAt(pointIndex + 1);
        return after != null && !after.isBlank() && !after.equals(before);
    }

    /** Name of the road at a polyline point, or null when the point is off-road or unnamed. */
    private String nameAt(int pointIndex) {
        if (roadNames.length == 0) {
            return null;
        }
        return roadNames[Math.max(0, Math.min(pointIndex, roadNames.length - 1))];
    }

    /**
     * Name of the road the player is currently on, or null when off-road or on an unnamed road.
     */
    public String currentRoadName(double x, double z) {
        Projection projection = closest(x, z);
        return projection == null ? null : nameAt(projection.segmentIndex());
    }

    /**
     * Whether the given position is close enough to the route to count as being on it.
     *
     * <p>Lets a caller tell "on an unnamed road" apart from "out in a field", which are different
     * things to tell the player.
     */
    public boolean isOnRoute(double x, double z) {
        if (!isPresent()) {
            return false;
        }
        return distanceTo(x, z) <= toleranceNear(x, z);
    }

    private static double wrapRadians(double radians) {
        double twoPi = Math.PI * 2;
        double r = radians % twoPi;
        if (r > Math.PI) {
            r -= twoPi;
        } else if (r < -Math.PI) {
            r += twoPi;
        }
        return r;
    }

    /**
     * Perpendicular distance from a point to the route polyline, in blocks.
     *
     * <p>Measuring to the nearest vertex would jump as the player passes between vertices, so every
     * segment is tested.
     */
    public double distanceTo(double x, double z) {
        Projection projection = closest(x, z);
        return projection == null ? 0 : Math.hypot(projection.x() - x, projection.z() - z);
    }

    /**
     * On-road tolerance at the closest point of the route, in blocks.
     *
     * <p>This is what makes off-route detection road-aware: the player is judged against the width
     * of the road they are supposed to be on, not a single global number.
     */
    public double toleranceNear(double x, double z) {
        Projection projection = closest(x, z);
        if (projection == null || tolerances.length == 0) {
            return 8.0;
        }
        int i = projection.segmentIndex();
        double a = tolerances[Math.max(0, Math.min(i - 1, tolerances.length - 1))];
        double b = tolerances[Math.max(0, Math.min(i, tolerances.length - 1))];
        return Math.max(a, b);
    }

    /**
     * Every direction the route runs within {@code radius} blocks of {@code (x, z)}.
     *
     * <h2>Why one direction is not enough to judge a player by</h2>
     * {@link #bearingAt} answers with the nearest segment, and a route that passes over the same
     * ground twice makes that answer ambiguous: a road that loops back, a divided highway whose two
     * carriageways are a lane apart, a destination on the piece of road the trip set off along, and
     * every case where the connector from the player onto the road happens to lie against the road
     * it joins. At such a place the two passes are the same distance away, the scan takes the earlier
     * one, and the direction it reports is the direction of the pass the player has already made --
     * exactly reversed. A caller comparing a player's heading against that one number then reads a
     * player travelling correctly as going the wrong way.
     *
     * <p>Handing back all of them lets the caller ask the question it actually means: is this player
     * travelling against the route <em>wherever the route is</em>, which is true only when they
     * disagree with every pass, not merely with the one that happened to be nearest.
     *
     * <p>Only segments whose nearest point is within {@code radius} blocks are offered, so a route
     * that comes back within a tolerance but not within the caller's own idea of "here" does not
     * vote. At most a handful of directions come back, and duplicates are dropped.
     *
     * @return the bearings, in degrees from +X towards +Z; empty when nothing of the route is near
     */
    public double[] bearingsNear(double x, double z, double radius) {
        if (points.size() < 2) {
            return new double[0];
        }
        double radiusSq = radius * radius;
        double[] found = new double[8];
        int count = 0;
        for (int i = 1; i < points.size(); i++) {
            double[] a = points.get(i - 1);
            double[] b = points.get(i);
            double ex = b[0] - a[0];
            double ez = b[1] - a[1];
            double lenSq = ex * ex + ez * ez;
            if (lenSq < 1.0E-9) {
                continue;
            }
            double t = Math.max(0, Math.min(1, ((x - a[0]) * ex + (z - a[1]) * ez) / lenSq));
            double px = a[0] + ex * t - x;
            double pz = a[1] + ez * t - z;
            if (px * px + pz * pz > radiusSq) {
                continue;
            }
            double bearing = Math.toDegrees(Math.atan2(ez, ex));
            boolean known = false;
            for (int j = 0; j < count; j++) {
                if (Math.abs(gapDegrees(found[j], bearing)) < 1.0E-3) {
                    known = true;
                    break;
                }
            }
            if (known) {
                continue;
            }
            if (count == found.length) {
                // More distinct directions than a route can plausibly offer at one place; the ones in
                // hand already answer the question, and growing the array is not worth the copy.
                break;
            }
            found[count++] = bearing;
        }
        double[] result = new double[count];
        System.arraycopy(found, 0, result, 0, count);
        return result;
    }

    /** The smaller angle between two bearings, in degrees. */
    private static double gapDegrees(double from, double to) {
        double difference = Math.abs(from - to) % 360.0;
        return difference > 180.0 ? 360.0 - difference : difference;
    }

    /**
     * Direction the route runs at the point nearest to {@code (x, z)}, in degrees from +X towards +Z.
     *
     * <p>The convention the manoeuvre angles and the player's heading already use, so a heading can
     * be compared against the route without either being converted. Taken from the segment the
     * projection landed on, which is the direction the route is going where the player is -- what a
     * player travelling the other way is travelling against.
     *
     * <p>Where the route passes over the same ground twice this is only one of the answers it has
     * there; a caller deciding whether a player is going the wrong way wants {@link #bearingsNear}.
     *
     * @return the bearing, or NaN when there is no route to take a direction from
     */
    public double bearingAt(double x, double z) {
        Projection projection = closest(x, z);
        if (projection == null) {
            return Double.NaN;
        }
        int i = projection.segmentIndex();
        double[] a = points.get(i - 1);
        double[] b = points.get(i);
        if (Math.hypot(b[0] - a[0], b[1] - a[1]) < 1.0E-9) {
            return Double.NaN;
        }
        return Math.toDegrees(Math.atan2(b[1] - a[1], b[0] - a[0]));
    }

    /** Closest point on the polyline, as a projection onto a segment. */
    private Projection closest(double x, double z) {
        // One slot of memo, because the whole of one frame asks the same question: the panel, the map
        // readout, the progress bar and the voice all measure from the player's position, which does
        // not move between them. Each of them used to walk every segment of the route to answer it,
        // and a public transport journey's polyline is thousands of points long. Keyed on the exact
        // coordinates rather than on a distance, so a player who has moved recomputes -- which is the
        // one case where the answer is different.
        if (projectionCached && x == projectionX && z == projectionZ) {
            return projectionCache;
        }
        Projection found = searchClosest(x, z);
        projectionCached = true;
        projectionX = x;
        projectionZ = z;
        projectionCache = found;
        return found;
    }

    private Projection searchClosest(double x, double z) {
        if (points.size() < 2) {
            return null;
        }
        Projection best = null;
        double bestDistanceSq = Double.MAX_VALUE;
        for (int i = 1; i < points.size(); i++) {
            double[] a = points.get(i - 1);
            double[] b = points.get(i);
            double ex = b[0] - a[0];
            double ez = b[1] - a[1];
            double lenSq = ex * ex + ez * ez;
            double t = lenSq < 1.0E-9 ? 0
                    : Math.max(0, Math.min(1, ((x - a[0]) * ex + (z - a[1]) * ez) / lenSq));
            double px = a[0] + ex * t;
            double pz = a[1] + ez * t;
            double d = (px - x) * (px - x) + (pz - z) * (pz - z);
            if (d < bestDistanceSq) {
                bestDistanceSq = d;
                best = new Projection(i, t, px, pz);
            }
        }
        return best;
    }

    /** A point on the polyline: which segment, how far along it, and where. */
    private record Projection(int segmentIndex, double t, double x, double z) {
    }

    // ---------------------------------------------------------------- trimming

    private double toleranceAt(int pointIndex) {
        if (tolerances.length == 0) {
            return 8.0;
        }
        return tolerances[Math.max(0, Math.min(pointIndex, tolerances.length - 1))];
    }

    // ---------------------------------------------------------------- estimates

    public double totalLength() {
        return startConnector + roadLength() + goalConnector;
    }

    /**
     * Seconds of travel per block on the road part of this route.
     *
     * <p>This is the length-weighted mean of the pieces' paces, and a mean is all it is: it is right
     * only for a route whose pieces are all the same pace. It survives as the one number the map's
     * coarse per-block reading uses, and is deliberately <em>not</em> what the countdown is made of --
     * see {@link #remainingSeconds}. A public transport route is the counter-example the mean cannot
     * express: walk, ride, walk again, at paces that differ by a factor of ten.
     */
    public double secondsPerBlock() {
        double length = roadLength();
        if (length <= 1.0E-6) {
            // A connector-only trip is off-road from end to end, so its pace is the pace of the
            // first and last hop; one second per block would be a number from nowhere.
            return 1.0 / connectorPace();
        }
        double cost = 0;
        for (Leg leg : legs) {
            cost += leg.length() / Math.max(0.05, leg.pace());
        }
        return cost / length;
    }

    /**
     * Distance still to travel from the given position, following the drawn polyline.
     */
    public double remainingLength(double fromX, double fromZ) {
        Projection projection = closest(fromX, fromZ);
        if (projection == null) {
            return 0;
        }
        return Math.max(0, polylineLength - distanceAlong(projection));
    }

    /** Drawn distance from the route's start to a projection, in blocks. */
    private double distanceAlong(Projection projection) {
        int i = projection.segmentIndex();
        double[] a = points.get(i - 1);
        double[] b = points.get(i);
        double edge = Math.hypot(b[0] - a[0], b[1] - a[1]);
        return cumulative[i - 1] + edge * projection.t();
    }

    /**
     * Seconds still to travel from the given position, at the pace of the part of the route that is
     * still ahead.
     *
     * <h2>Why this is not the distance left times one pace</h2>
     * A route is three stretches in a fixed order -- the walk onto the network, the road, the walk off
     * it again -- and a public transport journey is a fourth shape again: walk, ride, walk, ride. Each
     * stretch has its own pace, and the places where they meet are not evenly spaced, so multiplying
     * what is left by a single average pace answers with a number that belongs to no part of the trip.
     * Measured on a journey that rides ice at forty blocks a second and then walks two kilometres, the
     * countdown was five to ten times short, and a transit journey's waiting was missing from it
     * altogether: the picker showed three minutes and the panel said two the moment the trip began.
     *
     * <p>So what is left is read from the part of the route the player is actually on: the stretches
     * still ahead are added up at their own paces, and the wait is apportioned over the road. Both
     * ends come out exact -- the whole trip at the start, nothing at the destination -- which is the
     * property that keeps the picker's preview and the panel's countdown the same number.
     */
    public double remainingSeconds(double fromX, double fromZ) {
        Projection projection = closest(fromX, fromZ);
        if (projection == null) {
            // Nothing on this route to measure from, so the whole of it is still ahead.
            return estimatedSeconds();
        }
        double along = distanceAlong(projection);
        double road = roadLength();
        if (along <= startConnector) {
            // Still walking onto the network: the road and the walk off it are both ahead.
            return (startConnector - along + goalConnector) / connectorPace()
                    + roadSeconds(road) + fixedSeconds;
        }
        double intoRoad = along - startConnector;
        if (intoRoad < road) {
            double fractionLeft = road <= 1.0E-6 ? 0 : 1.0 - intoRoad / road;
            return roadSeconds(road - intoRoad) + goalConnector / connectorPace()
                    + fixedSeconds * fractionLeft;
        }
        // Past the last road node: only the walk to the destination is left, and the waiting is behind.
        return Math.max(0, polylineLength - along) / connectorPace();
    }

    /**
     * Seconds to cover a distance of road, taken from the end of the route backwards.
     *
     * <p>Backwards because what is asked for is always the part that is still ahead, and the road ends
     * at the destination: the pieces to count are the last ones. Within a piece the pace is constant,
     * so a piece half covered costs half its time.
     */
    private double roadSeconds(double distance) {
        double left = distance;
        double seconds = 0;
        for (int i = legs.size() - 1; i >= 0 && left > 0; i--) {
            Leg leg = legs.get(i);
            double take = Math.min(left, leg.length());
            seconds += take / Math.max(0.05, leg.pace());
            left -= take;
        }
        return seconds;
    }

    /**
     * Wall-clock estimate in seconds, at the pace of the mode this route was planned for.
     *
     * <p>Each road piece is timed at the pace its own class allows that mode, so a highway really
     * does come out faster than a footpath over the same distance, and a boat on ice comes out
     * faster still. The off-road connectors are walked, whatever the mode. Waiting is added as
     * itself, because it is time nobody spends moving.
     */
    public double estimatedSeconds() {
        double seconds = fixedSeconds + (startConnector + goalConnector) / connectorPace();
        for (Leg leg : legs) {
            seconds += leg.length() / Math.max(0.05, leg.pace());
        }
        return seconds;
    }

    /** Off-road pace of the two connectors, which are walked in every mode. */
    private double connectorPace() {
        return Math.max(0.05, travelMode.connectorSpeed() * offRoadSpeedFactor);
    }

    /**
     * Human-readable distance, switching to kilometres once it is long enough to matter.
     *
     * <p>The unit is translated and the number is not: 49 blocks is 49 blocks in every language, and
     * only the way it is written down changes. The unit cannot simply be appended here -- a bare "m"
     * stays the Latin letter on a Chinese client, where it should read 米 -- so the whole
     * number-and-unit pattern is the translated string and the number is passed into it.
     *
     * <p>The number is formatted before it gets there, and exactly as it always was: an integer below
     * a kilometre and two decimals above it. That keeps the precision, the rounding and the
     * thresholds identical, and it keeps the decimal separator following the machine's locale rather
     * than the game's language, which is what it did before.
     */
    public static String formatDistance(double blocks) {
        if (blocks >= 1000) {
            return Component.translatable("hud.howtogo.distance.kilometres",
                    String.format("%.2f", blocks / 1000.0)).getString();
        }
        return Component.translatable("hud.howtogo.distance.metres",
                String.valueOf(Math.round(blocks))).getString();
    }

    /** Human-readable duration. */
    public static String formatDuration(double seconds) {
        long total = Math.max(0, Math.round(seconds));
        long hours = total / 3600;
        long minutes = (total % 3600) / 60;
        long secs = total % 60;
        if (hours > 0) {
            return hours + "h " + minutes + "m";
        }
        if (minutes > 0) {
            return minutes + "m " + secs + "s";
        }
        return secs + "s";
    }

    /** Builder used by {@link RoadRouter}. */
    static final class Builder {
        private final List<double[]> points = new ArrayList<>();
        private final List<Double> tolerances = new ArrayList<>();
        private final List<Integer> roadKeys = new ArrayList<>();
        private final List<String> roadNames = new ArrayList<>();
        private final List<Boolean> branches = new ArrayList<>();
        private final List<Leg> legs = new ArrayList<>();
        private double startConnector;
        private double goalConnector;
        private String destinationName;
        private double offRoadSpeedFactor = 1.0;
        private TravelMode travelMode = TravelMode.WALK;

        void setOffRoadSpeedFactor(double value) {
            this.offRoadSpeedFactor = value;
        }

        void setTravelMode(TravelMode mode) {
            this.travelMode = mode;
        }

        void addPoint(double x, double z, double tolerance, int roadKey, String roadName,
                      boolean branch) {
            if (!points.isEmpty()) {
                double[] last = points.get(points.size() - 1);
                if (Math.abs(last[0] - x) < 1.0E-6 && Math.abs(last[1] - z) < 1.0E-6) {
                    return;
                }
            }
            points.add(new double[]{x, z});
            tolerances.add(tolerance);
            roadKeys.add(roadKey);
            roadNames.add(roadName);
            branches.add(branch);
        }

        /**
         * Records one road piece at the pace it will actually be travelled, penalties included.
         *
         * <p>The pace rather than a class, because the pace is what the router costed the piece at;
         * storing the class would let the estimate and the choice drift apart.
         *
         * <p>The class is read back out of the pace here rather than handed in beside it, so that the
         * router goes on handing over the one number it decides and nothing else. The reading is
         * exact: a leg's pace is this mode's own table entry for the class it was planned on, so the
         * pace names the class it came from -- and where two classes of one mode share a pace (a
         * walker's highway and road, a train's rail and water), the mode travels the two identically
         * in every mode, so which of them is named can change no time this route is ever given.
         */
        void addRoadLeg(double length, double blocksPerSecond) {
            legs.add(new Leg(length, blocksPerSecond, classForPace(blocksPerSecond)));
        }

        /** The class of road a pace was looked up from, or null when the table has no such pace. */
        private RoadClass classForPace(double blocksPerSecond) {
            if (!(blocksPerSecond > 0)) {
                return null;
            }
            for (RoadClass roadClass : RoadClass.values()) {
                if (travelMode.speedOn(roadClass) == blocksPerSecond) {
                    return roadClass;
                }
            }
            return null;
        }

        void setStartConnector(double value) {
            this.startConnector = value;
        }

        void setGoalConnector(double value) {
            this.goalConnector = value;
        }

        void setDestinationName(String name) {
            this.destinationName = name;
        }

        Route build() {
            double[] toleranceArray = new double[tolerances.size()];
            for (int i = 0; i < toleranceArray.length; i++) {
                toleranceArray[i] = tolerances.get(i);
            }
            int[] roadKeyArray = new int[roadKeys.size()];
            for (int i = 0; i < roadKeyArray.length; i++) {
                roadKeyArray[i] = roadKeys.get(i);
            }
            String[] roadNameArray = roadNames.toArray(new String[0]);
            boolean[] branchArray = new boolean[branches.size()];
            for (int i = 0; i < branchArray.length; i++) {
                branchArray[i] = branches.get(i);
            }
            return new Route(points, toleranceArray, roadKeyArray, roadNameArray, branchArray,
                    startConnector, goalConnector, legs, destinationName, offRoadSpeedFactor,
                    travelMode, 0);
        }
    }
}
