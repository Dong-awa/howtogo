package bili.dongsz.howtogo.route;

import bili.dongsz.howtogo.road.RoadClass;
import bili.dongsz.howtogo.store.RoutePreferenceStore;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A computed route from the player to a destination: an off-road connector to the first road node,
 * the road polyline, and a connector to the destination. Connectors are kept separate so the UI can
 * warn that part of the trip is off-road. Each point carries the tolerance of the road it is on.
 */
public final class Route {

    private final List<double[]> points;
    private final double[] tolerances;
    /**
     * Road each point belongs to, parallel to points. A manoeuvre is announced only where this changes:
     * a bend inside one road is not a turn.
     */
    private final int[] roadKeys;

    private final String[] roadNames;
    /**
     * Whether each point is a real junction (three or more segment ends), parallel to points. A class or
     * name change breaks the road sequence without branching, so a road-key change alone is not a
     * decision.
     */
    private final boolean[] branchAt;
    private final double startConnector;
    private final double goalConnector;
    private final List<Leg> legs;
    private final String destinationName;
    private final double offRoadSpeedFactor;
    /**
     * Time on this route that is not distance: waiting for a service, in seconds. Kept outside
     * totalLength and secondsPerBlock because waiting covers no ground.
     */
    private final double fixedSeconds;
    /**
     * The mode this route is travelled and timed in. Kept on the route because the estimate is a property
     * of it; timedFor changes it.
     */
    private final TravelMode travelMode;

    /**
     * Cached turn points, and the straight-through switch they were built for. The switch decides which
     * manoeuvres exist, so it is part of the cache: toggling it costs a rebuild, no re-plan. Read through
     * RoutePreferenceStore because it is a guidance preference; every readout indexes this one list.
     */
    private List<Maneuver> maneuvers;
    private Boolean maneuversWithStraight;

    /**
     * Drawn distance from the first point to each point, parallel to points. Computed once because the
     * polyline never changes and the readouts ask every frame.
     */
    private final double[] cumulative;

    private final double polylineLength;

    private double projectionX = Double.NaN;
    private double projectionZ = Double.NaN;
    private Projection projectionCache;
    private boolean projectionCached;

    private static final double TURN_THRESHOLD_DEG = 25.0;

    /**
     * How close two straight-through manoeuvres may be before the second is taken for the first. A
     * junction drawn as two nodes a few blocks apart would otherwise be called twice.
     */
    private static final double STRAIGHT_DUPLICATE_DISTANCE = 3.0;

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
     * One piece of the route for the estimate: length, pace, and the road class it is on. The class is
     * null for a connector, which is walked whatever the mode.
     */
    private record Leg(double length, double pace, RoadClass roadClass) {
    }

    /**
     * The same line, timed as the given mode would travel it. Used for a drive to a destination the
     * drivable network does not reach: the line is the walker's, but the car takes it up to the last
     * road. Each piece the mode may travel is re-timed at that mode's pace for its class; geometry,
     * connectors, tolerances and waiting are unchanged. Not offered to public transport.
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

    /** The same route plus time that is not distance; arrays are shared, since a route is immutable. */
    Route plusFixedSeconds(double seconds) {
        if (seconds <= 0) {
            return this;
        }
        return new Route(points, tolerances, roadKeys, roadNames, branchAt, startConnector,
                goalConnector, legs, destinationName, offRoadSpeedFactor, travelMode,
                fixedSeconds + seconds);
    }

    /**
     * Joins routes end to end, keeping each part's own pace. It lives here because five parallel arrays
     * must stay in step; a merge outside would silently misalign them. The whole journey's connectors are
     * the first part's start and the last part's goal -- every connector between is travelled too and is
     * recorded as a walked piece.
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
                // The join is one coordinate written twice: its second copy is dropped from every array at once,
                // keeping them parallel.
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
            // Only the first part's start and the last part's goal are the whole journey's connectors; the middle
            // ones are travelled all the same and must not be left out.
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
            // Waiting is carried as itself: every leg's time is length over pace, and a zero-length leg would add
            // nothing.
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
     * @param distanceFromStart how far along the route the junction is
     * @param turnDegrees       signed angle, positive being a right turn; zero for a straight-through call
     * @param roadName          the road being turned onto, or null when it has no name
     * @param junctionX         world x of the junction
     * @param junctionZ         world z of the junction
     * @param bearingAfter      direction leaving the junction, in degrees from +X towards +Z; a player's
     * heading must match for the turn to count as taken rather than passed
     * @param namesTheRoad      whether the road entered is worth naming
     * @param continuesNamedRoad whether the road entered carries the same name as the one left, worded as
     * carrying on along it
     * @param straight          whether the route carries straight through; a flag because a small angle
     * and a straight-through call look the same in turnDegrees
     */
    public record Maneuver(double distanceFromStart, double turnDegrees, String roadName,
                           double junctionX, double junctionZ, double bearingAfter,
                           boolean namesTheRoad, boolean continuesNamedRoad, boolean straight) {
    }

    public boolean isPresent() {
        return points.size() >= 2;
    }

    public List<double[]> points() {
        return Collections.unmodifiableList(points);
    }

    /**
     * How many times the drawn line changes direction by a turn's worth. Not maneuvers(): the guidance
     * announces only road changes, while a corner inside one road is a bend the player still takes. The
     * picker compares plans by what the player will do at the controls.
     */
    public int bends() {
        int bends = 0;
        for (int i = 2; i < points.size(); i++) {
            double[] a = points.get(i - 2);
            double[] b = points.get(i - 1);
            double[] c = points.get(i);
            double inX = b[0] - a[0];
            double inZ = b[1] - a[1];
            double outX = c[0] - b[0];
            double outZ = c[1] - b[1];
            if (Math.hypot(inX, inZ) < 1.0E-6 || Math.hypot(outX, outZ) < 1.0E-6) {
                continue;
            }
            double delta = Math.toDegrees(
                    Math.atan2(inX * outZ - inZ * outX, inX * outX + inZ * outZ));
            if (Math.abs(delta) >= TURN_THRESHOLD_DEG) {
                bends++;
            }
        }
        return bends;
    }

    /**
     * The road under each point, as RoadChains.Grouping.keyOf named it; 0 means off-road. Used to toll the
     * first plan's roads when building the second, and the keys match the ones used to name a turn.
     * @return the keys as a copy, so a caller cannot change where the route says it goes
     */
    public int[] roadKeys() {
        return roadKeys.clone();
    }

    public String destinationName() {
        return destinationName;
    }

    public TravelMode travelMode() {
        return travelMode;
    }

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


    /**
     * Where the guidance has something to say, in the order it is travelled. Built for the
     * straight-through switch and rebuilt when it changes; see computeManeuvers.
     */
    public List<Maneuver> maneuvers() {
        boolean straightThroughJunctions = RoutePreferenceStore.straightAnnouncements();
        if (maneuvers == null || maneuversWithStraight == null
                || maneuversWithStraight != straightThroughJunctions) {
            maneuversWithStraight = straightThroughJunctions;
            maneuvers = computeManeuvers(straightThroughJunctions);
        }
        return maneuvers;
    }

    /**
     * Works out where the guidance has something to say, once per list of manoeuvres.
     * A road change alone is not enough: a bend inside one road must not be announced, and a class or
     * name change at a pass-through node is one continuous piece of ground.
     * A real fork the route carries straight through is the decision the old list dropped: there is no
     * angle to report and no differently named road, so it is called with no angle. It must not be a
     * small-angle turn; see Maneuver.straight(). Off, the old rules apply. Adjacent straight-through calls
     * within STRAIGHT_DUPLICATE_DISTANCE are one junction.
     */
    private List<Maneuver> computeManeuvers(boolean straightThroughJunctions) {
        List<Maneuver> result = new ArrayList<>();
        if (points.size() < 3) {
            return result;
        }
        double travelled = 0;
        // Where the previous straight-through call was placed, or NaN when there is none yet.
        double lastStraightAt = Double.NaN;
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
            if (i + 1 >= roadKeys.length || roadKeys[i] == roadKeys[i + 1]) {
                continue;
            }
            double delta = Math.toDegrees(wrapRadians(
                    Math.atan2(outZ, outX) - Math.atan2(inZ, inX)));
            double bearingAfter = Math.toDegrees(Math.atan2(outZ, outX));
            boolean turns = Math.abs(delta) >= TURN_THRESHOLD_DEG;
            if (!turns) {
                // A decision is required: the route really bends here, or it enters a differently named road at a
                // real fork.
                if (!isBranch(i)) {
                    continue;
                }
                if (straightThroughJunctions) {
                    if (!Double.isNaN(lastStraightAt)
                            && travelled - lastStraightAt <= STRAIGHT_DUPLICATE_DISTANCE) {
                        continue;
                    }
                    lastStraightAt = travelled;
                    // Zero degrees on purpose: the wording comes from the straight flag, not the number. Nothing is
                    // named: a player carrying straight on has not entered a road.
                    result.add(new Maneuver(travelled, 0.0, nameAt(i + 1), b[0], b[1], bearingAfter,
                            false, false, true));
                    continue;
                }
                // Switched off: the old rule, whose one kept case is the straight fork onto a differently named road.
                if (!entersADifferentRoad(i)) {
                    continue;
                }
            }
            // The junction position and the bearing leaving it, so a caller can tell a taken turn from one walked
            // straight past.
            result.add(new Maneuver(travelled, delta, nameAt(i + 1),
                    b[0], b[1], bearingAfter, namesTheRoad(i), continuesNamedRoad(i), false));
        }
        return result;
    }

    /**
     * Whether naming the road this turn enters tells the player anything. The turn is still announced;
     * only the name is dropped. Different from continuesNamedRoad, which is worded differently.
     */
    private boolean namesTheRoad(int pointIndex) {
        String before = nameAt(pointIndex);
        String after = nameAt(pointIndex + 1);
        return after != null && !after.isBlank() && !after.equals(before);
    }

    /**
     * Whether the road entered carries on the name of the one left: two roads whose ends meet and names
     * agree are one road to the player. Worded as "turn right, continue on X".
     */
    private boolean continuesNamedRoad(int pointIndex) {
        String before = nameAt(pointIndex);
        String after = nameAt(pointIndex + 1);
        return after != null && !after.isBlank() && after.equals(before);
    }

    private boolean isBranch(int pointIndex) {
        if (branchAt.length == 0) {
            return false;
        }
        int i = Math.max(0, Math.min(pointIndex, branchAt.length - 1));
        int j = Math.max(0, Math.min(pointIndex + 1, branchAt.length - 1));
        return branchAt[i] || branchAt[j];
    }

    /**
     * Whether the road entered at this point has a name of its own, different from the one left. A name is
     * what makes a straight fork worth announcing.
     */
    private boolean entersADifferentRoad(int pointIndex) {
        String before = nameAt(pointIndex);
        String after = nameAt(pointIndex + 1);
        return after != null && !after.isBlank() && !after.equals(before);
    }

    private String nameAt(int pointIndex) {
        if (roadNames.length == 0) {
            return null;
        }
        return roadNames[Math.max(0, Math.min(pointIndex, roadNames.length - 1))];
    }

    public String currentRoadName(double x, double z) {
        Projection projection = closest(x, z);
        return projection == null ? null : nameAt(projection.segmentIndex());
    }

    /**
     * Whether the position is close enough to count as on the route; lets a caller tell an unnamed road
     * from open country.
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
     * Perpendicular distance to the polyline, in blocks. Every segment is tested, or the value would jump
     * as the player passes between vertices.
     */
    public double distanceTo(double x, double z) {
        Projection projection = closest(x, z);
        return projection == null ? 0 : Math.hypot(projection.x() - x, projection.z() - z);
    }

    /**
     * On-road tolerance at the closest point, in blocks: the player is judged against the width of the road
     * they should be on, not one global number.
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
     * Every direction the route runs within radius blocks of (x, z).
     * One direction is not enough where the route passes over the same ground twice: bearingAt takes the
     * earlier pass and can report exactly reversed. A caller judging travel direction asks whether the
     * player disagrees with every pass.
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
                break;
            }
            found[count++] = bearing;
        }
        double[] result = new double[count];
        System.arraycopy(found, 0, result, 0, count);
        return result;
    }

    private static double gapDegrees(double from, double to) {
        double difference = Math.abs(from - to) % 360.0;
        return difference > 180.0 ? 360.0 - difference : difference;
    }

    /**
     * Direction the route runs at the nearest point, in degrees from +X towards +Z. Where the route passes
     * over the same ground twice this is only one answer; a caller deciding direction of travel wants
     * bearingsNear.
     * @return the bearing, or NaN when there is no route
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
        // One slot of memo: the panel, readout, progress bar and voice all ask the same question in one frame,
        // and a transit polyline is thousands of points long. Keyed on exact coordinates, so a moved player
        // recomputes.
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

    private record Projection(int segmentIndex, double t, double x, double z) {
    }


    private double toleranceAt(int pointIndex) {
        if (tolerances.length == 0) {
            return 8.0;
        }
        return tolerances[Math.max(0, Math.min(pointIndex, tolerances.length - 1))];
    }


    public double totalLength() {
        return startConnector + roadLength() + goalConnector;
    }

    /**
     * Seconds per block on the road part: the length-weighted mean of the pieces' paces. Right only when
     * every piece has the same pace, so it is used for the map's coarse reading, not the countdown; see
     * remainingSeconds.
     */
    public double secondsPerBlock() {
        double length = roadLength();
        if (length <= 1.0E-6) {
            // A connector-only trip is off-road end to end; one second per block would be a number from nowhere.
            return 1.0 / connectorPace();
        }
        double cost = 0;
        for (Leg leg : legs) {
            cost += leg.length() / Math.max(0.05, leg.pace());
        }
        return cost / length;
    }

    /** Distance still to travel from the position, following the drawn polyline. */
    public double remainingLength(double fromX, double fromZ) {
        Projection projection = closest(fromX, fromZ);
        if (projection == null) {
            return 0;
        }
        return Math.max(0, polylineLength - distanceAlong(projection));
    }

    private double distanceAlong(Projection projection) {
        int i = projection.segmentIndex();
        double[] a = points.get(i - 1);
        double[] b = points.get(i);
        double edge = Math.hypot(b[0] - a[0], b[1] - a[1]);
        return cumulative[i - 1] + edge * projection.t();
    }

    /**
     * Seconds still to travel, at the pace of the part still ahead. A route is stretches with different
     * paces in a fixed order, so distance left times one pace belongs to no part of the trip; each
     * remaining stretch is added at its own pace and the wait is apportioned over the road. Read from the
     * part the player is on, so both ends come out exact.
     */
    public double remainingSeconds(double fromX, double fromZ) {
        Projection projection = closest(fromX, fromZ);
        if (projection == null) {
            return estimatedSeconds();
        }
        double along = distanceAlong(projection);
        double road = roadLength();
        if (along <= startConnector) {
            return (startConnector - along + goalConnector) / connectorPace()
                    + roadSeconds(road) + fixedSeconds;
        }
        double intoRoad = along - startConnector;
        if (intoRoad < road) {
            double fractionLeft = road <= 1.0E-6 ? 0 : 1.0 - intoRoad / road;
            return roadSeconds(road - intoRoad) + goalConnector / connectorPace()
                    + fixedSeconds * fractionLeft;
        }
        return Math.max(0, polylineLength - along) / connectorPace();
    }

    /**
     * Seconds to cover a distance of road, counted from the end of the route backwards, because what is
     * asked for is always what is still ahead.
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
     * Wall-clock estimate at the pace of the planned mode: each road piece at its own class's pace, the
     * connectors walked, and waiting added as itself.
     */
    public double estimatedSeconds() {
        double seconds = fixedSeconds + (startConnector + goalConnector) / connectorPace();
        for (Leg leg : legs) {
            seconds += leg.length() / Math.max(0.05, leg.pace());
        }
        return seconds;
    }

    private double connectorPace() {
        return Math.max(0.05, travelMode.connectorSpeed() * offRoadSpeedFactor);
    }

    /**
     * Human-readable distance, switching to kilometres once long enough. The number-and-unit pattern is
     * the translated string, because a bare "m" should read 米 on a Chinese client; the number keeps its
     * old formatting and locale.
     */
    public static String formatDistance(double blocks) {
        if (blocks >= 1000) {
            return Component.translatable("hud.howtogo.distance.kilometres",
                    String.format("%.2f", blocks / 1000.0)).getString();
        }
        return Component.translatable("hud.howtogo.distance.metres",
                String.valueOf(Math.round(blocks))).getString();
    }

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
         * Records one road piece at the pace it will be travelled and the class it was planned over. The class
         * is passed in rather than reverse-read from the pace, which became ambiguous once classes had
         * distinct paces.
         */
        void addRoadLeg(double length, double blocksPerSecond, RoadClass roadClass) {
            legs.add(new Leg(length, blocksPerSecond, roadClass));
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
