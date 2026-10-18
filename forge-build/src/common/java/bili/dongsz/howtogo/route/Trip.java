package bili.dongsz.howtogo.route;

import java.util.ArrayList;
import java.util.List;

/**
 * A trip: a journey made of legs, each travelled in its own mode.
 *
 * <h2>Why this is not a {@link Route}</h2>
 * A route is one path in one mode, and it says so: {@link Route#travelMode()} returns a single mode
 * and {@link Route#secondsPerBlock()} a single pace. A journey by public transport is not that shape.
 * Walking to a station is walked, riding is ridden, and the two have different paces, different
 * allowed roads, and different things to say about them. Forcing that into one route would mean a
 * route whose speed depends on where you are along it, which is a different class wearing the old
 * name.
 *
 * <h2>The guarantee this type exists to carry</h2>
 * The riding leg begins at one station and ends at another -- not at "wherever the walk happened to
 * meet the rails". That is the whole point of splitting the journey up: the boarding and alighting
 * points are named in {@link #boardingStation()} and {@link #alightingStation()}, and they are nodes
 * of the network that were marked as stations, so "you board at a station" is a property of how the
 * trip was built rather than a hope about how it turned out.
 */
public final class Trip {

    /**
     * Half a block of slack when a travelled distance is compared with a stop's own.
     *
     * <p>The distance a caller has travelled is a projection of the player onto the drawn line, so it
     * arrives at a stop's number by way of rounding: a strict comparison would put the "arrived at the
     * station" reading one tick late, or miss it entirely when the player passes a platform corner.
     */
    private static final double STOP_SLACK = 0.5;

    /**
     * One leg: a path, the mode it is travelled in, and what it rides when it rides something.
     *
     * <p>{@code ride} is null for a leg on foot, and is what makes the riding legs name their station
     * rather than merely pass through it -- see {@link Ride}.
     */
    public record Leg(Route route, TravelMode mode, Ride ride) {

        /** A leg that rides nothing: the shape every caller outside the planner builds. */
        public Leg(Route route, TravelMode mode) {
            this(route, mode, null);
        }

        public boolean isPresent() {
            return route != null && route.isPresent();
        }
    }

    /**
     * One stop a ride calls at: its name, where it stands, and how far along the whole journey it is.
     *
     * <p>A position as well as a distance, because a station is a place and not only a point on a line.
     * The platform a player waits on is regularly a good many blocks from the track's centreline, and
     * "am I at the station" is a question about the platform -- that is what the navigation's station
     * snap is measured against.
     */
    public record RideStop(String name, double x, double z, double at) {
    }

    /**
     * One ride: which line, which way along it, where it is boarded and left, and every stop it calls at.
     *
     * <p>The stops run from the one boarded at to the one left at, each carrying its distance from the
     * start of the whole journey -- so "which stop is this, and how many are left" is arithmetic on one
     * list rather than a second reading of the world, which is the whole reason this is kept rather than
     * flattened away with the rest of the journey.
     */
    public record Ride(String line, String terminus, String boardedAt, String leftAt,
                       List<RideStop> stops) {

        public Ride {
            stops = List.copyOf(stops);
        }

        /** Where the ride begins along the whole journey. */
        public double boardAt() {
            return stops.isEmpty() ? 0 : stops.get(0).at();
        }

        /** Where it ends. */
        public double alightAt() {
            return stops.isEmpty() ? 0 : stops.get(stops.size() - 1).at();
        }

        /** Whether this distance is on the vehicle: from the stop boarded at to the one left at. */
        public boolean riding(double travelled) {
            return !stops.isEmpty() && travelled >= boardAt() && travelled < alightAt();
        }

        /** The last stop at or before this distance, or -1 while the ride has not begun. */
        public int stopPassed(double travelled) {
            int passed = -1;
            for (int i = 0; i < stops.size(); i++) {
                if (stops.get(i).at() <= travelled + STOP_SLACK) {
                    passed = i;
                }
            }
            return passed;
        }

        /** The stop being run to, or null once the last stop of this ride has been reached. */
        public RideStop nextStop(double travelled) {
            int next = stopPassed(travelled) + 1;
            return next < stops.size() ? stops.get(next) : null;
        }

        /** How many stops are left before the one this ride is left at. */
        public int stopsRemaining(double travelled) {
            return stops.size() - 1 - Math.max(0, stopPassed(travelled));
        }

        /** Whether the next stop is the one this ride is left at. */
        public boolean approachingAlighting(double travelled) {
            return nextStop(travelled) == stops.get(stops.size() - 1);
        }
    }

    private static final Trip EMPTY = new Trip(List.of(), null, null);

    private final List<Leg> legs;
    /** The distinct rides of {@link #legs}, worked out once: the guidance asks for them many times a tick. */
    private final List<Ride> rides;
    private final String boardingStation;
    private final String alightingStation;

    private Trip(List<Leg> legs, String boardingStation, String alightingStation) {
        this.legs = List.copyOf(legs);
        List<Ride> distinct = new ArrayList<>();
        for (Leg leg : this.legs) {
            Ride ride = leg.ride();
            if (ride != null && (distinct.isEmpty() || distinct.get(distinct.size() - 1) != ride)) {
                distinct.add(ride);
            }
        }
        this.rides = List.copyOf(distinct);
        this.boardingStation = boardingStation;
        this.alightingStation = alightingStation;
    }

    public static Trip empty() {
        return EMPTY;
    }

    public static Trip of(List<Leg> legs, String boardingStation, String alightingStation) {
        return new Trip(legs, boardingStation, alightingStation);
    }

    public List<Leg> legs() {
        return legs;
    }

    /** The station the riding leg starts at, or null when the trip does not ride anything. */
    public String boardingStation() {
        return boardingStation;
    }

    /** The station the riding leg ends at, or null when the trip does not ride anything. */
    public String alightingStation() {
        return alightingStation;
    }

    /**
     * Every ride this journey takes, in the order they are travelled.
     *
     * <p>One entry per stay on a vehicle, not one per leg: the planner's ride edges run between
     * neighbouring stops, so sitting through three stations is three legs carrying the same {@link Ride}
     * object, and reading them as three rides would have the guidance tell a rider to board again at
     * every platform they pass through. Runs of legs sharing a ride are therefore one entry -- worked
     * out once when the journey is built, because the guidance asks for this several times a tick.
     */
    public List<Ride> rides() {
        return rides;
    }

    /** The ride in force at this distance, or null when this point of the journey is on foot. */
    public Ride rideAt(double travelled) {
        for (Leg leg : legs) {
            Ride ride = leg.ride();
            if (ride != null && ride.riding(travelled)) {
                return ride;
            }
        }
        return null;
    }

    /** The index of the ride in force at this distance, or -1 when this point is on foot. */
    public int rideIndexAt(double travelled) {
        List<Ride> rides = rides();
        for (int i = 0; i < rides.size(); i++) {
            if (rides.get(i).riding(travelled)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Where one of this journey's rides sits in {@link #rides()}, or -1 for a ride that is not one.
     *
     * <p>By identity rather than by equality: two rides of a journey can be identical in every field --
     * out and back along the same line is exactly that -- and the caller asking is holding the very
     * object it got from this journey.
     */
    public int indexOfRide(Ride ride) {
        List<Ride> rides = rides();
        for (int i = 0; i < rides.size(); i++) {
            if (rides.get(i) == ride) {
                return i;
            }
        }
        return -1;
    }

    /**
     * The next ride boarded at or after this distance, or null when there is none left.
     *
     * <p>The legs are in travelling order, so the first ride whose boarding is not already behind is
     * the answer -- and half a block of slack keeps the boarding stop itself from reading as "behind"
     * for the tick it is reached at.
     */
    public Ride nextRide(double travelled) {
        for (Leg leg : legs) {
            Ride ride = leg.ride();
            if (ride != null && ride.boardAt() > travelled - STOP_SLACK) {
                return ride;
            }
        }
        return null;
    }

    /** Whether any point of this journey is ridden, which is what makes it a public transport one. */
    public boolean ridesAnything() {
        for (Leg leg : legs) {
            if (leg.ride() != null) {
                return true;
            }
        }
        return false;
    }

    public boolean isPresent() {
        return !legs.isEmpty() && legs.stream().allMatch(Leg::isPresent);
    }

    public String destinationName() {
        return legs.isEmpty() ? "" : legs.get(legs.size() - 1).route().destinationName();
    }

    /**
     * Every leg's points, joined end to end, for drawing.
     *
     * <p>A repeated point is dropped where one leg ends exactly where the next begins, which is the
     * usual case at a station: the two legs are planned to the same coordinates, so keeping both
     * would put a zero-length step in the polyline and a doubled dot on the map.
     */
    public List<double[]> points() {
        List<double[]> joined = new ArrayList<>();
        for (Leg leg : legs) {
            List<double[]> points = leg.route().points();
            for (int i = 0; i < points.size(); i++) {
                double[] point = points.get(i);
                if (!joined.isEmpty() && i == 0 && samePoint(joined.get(joined.size() - 1), point)) {
                    continue;
                }
                joined.add(point);
            }
        }
        return joined;
    }

    public double totalLength() {
        double total = 0;
        for (Leg leg : legs) {
            total += leg.route().totalLength();
        }
        return total;
    }

    /**
     * The whole trip's time, summed leg by leg.
     *
     * <p>Summed rather than derived from a single pace, because there is no single pace: this is the
     * reason the type exists. Each leg's own estimate already accounts for its own mode, so adding
     * them is the honest total rather than an approximation of one.
     */
    public double estimatedSeconds() {
        double total = 0;
        for (Leg leg : legs) {
            total += leg.route().estimatedSeconds();
        }
        return total;
    }

    /**
     * The leg the given position is on: the first leg whose route passes through it, or the last leg
     * when the position is on none of them.
     *
     * <p>The last leg rather than null for a position off the whole trip, because that is what the
     * caller wants to say something about: a player who has wandered off is still heading for the end
     * of the journey.
     */
    public Leg activeLeg(double x, double z) {
        Leg last = null;
        for (Leg leg : legs) {
            last = leg;
            if (leg.route().isOnRoute(x, z)) {
                return leg;
            }
        }
        return last;
    }

    /** Whether the given position is on a leg travelled by a mode that is not on foot. */
    public boolean onRidingLeg(double x, double z) {
        Leg active = activeLeg(x, z);
        return active != null && active.mode() != TravelMode.WALK;
    }

    private static boolean samePoint(double[] a, double[] b) {
        return Math.abs(a[0] - b[0]) < 1.0E-6 && Math.abs(a[1] - b[1]) < 1.0E-6;
    }
}
