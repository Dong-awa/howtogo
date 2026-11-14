package bili.dongsz.howtogo.route;

import java.util.ArrayList;
import java.util.List;

/**
 * A trip: a journey made of legs, each travelled in its own mode.
 *
 * Not a Route: a route is one path at one pace, but walking and riding legs have different paces, allowed roads and guidance. The riding leg begins and ends at marked station nodes (boardingStation(), alightingStation()), not wherever the walk met the rails.
 */
public final class Trip {

    /**
     * Half a block of slack when a travelled distance is compared with a stop's own.
     *
     * The distance is a projection of the player onto the drawn line, so a strict comparison would report arrival a tick late or miss a platform corner.
     */
    private static final double STOP_SLACK = 0.5;

    /**
     * One leg: a path, the mode it travels in, and what it rides. ride is null on foot and is what makes riding legs name their station.
     */
    public record Leg(Route route, TravelMode mode, Ride ride) {

        /** A leg that rides nothing; the shape every caller outside the planner builds. */
        public Leg(Route route, TravelMode mode) {
            this(route, mode, null);
        }

        public boolean isPresent() {
            return route != null && route.isPresent();
        }
    }

    /**
     * One stop a ride calls at: name, position and distance along the whole journey.
     *
     * Position matters because a platform can stand far from the track centreline, and station snap measures against it.
     */
    public record RideStop(String name, double x, double z, double at) {
    }

    /**
     * One ride: line, terminus, boarding and alighting stops, and every stop it calls at.
     *
     * Stops carry their distance from the journey start, so stop arithmetic needs no second read of the world.
     */
    public record Ride(String line, String terminus, String boardedAt, String leftAt,
                       List<RideStop> stops) {

        public Ride {
            stops = List.copyOf(stops);
        }

        public double boardAt() {
            return stops.isEmpty() ? 0 : stops.get(0).at();
        }

        public double alightAt() {
            return stops.isEmpty() ? 0 : stops.get(stops.size() - 1).at();
        }

        /** Whether travelled is on the vehicle: from the stop boarded at to the one left at. */
        public boolean riding(double travelled) {
            return !stops.isEmpty() && travelled >= boardAt() && travelled < alightAt();
        }

        /** Index of the last stop at or before travelled, or -1 before the ride begins. */
        public int stopPassed(double travelled) {
            int passed = -1;
            for (int i = 0; i < stops.size(); i++) {
                if (stops.get(i).at() <= travelled + STOP_SLACK) {
                    passed = i;
                }
            }
            return passed;
        }

        public RideStop nextStop(double travelled) {
            int next = stopPassed(travelled) + 1;
            return next < stops.size() ? stops.get(next) : null;
        }

        public int stopsRemaining(double travelled) {
            return stops.size() - 1 - Math.max(0, stopPassed(travelled));
        }

        public boolean approachingAlighting(double travelled) {
            return nextStop(travelled) == stops.get(stops.size() - 1);
        }
    }

    private static final Trip EMPTY = new Trip(List.of(), null, null);

    private final List<Leg> legs;
    /** Distinct rides of the legs, computed once; the guidance asks several times a tick. */
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

    public String boardingStation() {
        return boardingStation;
    }

    public String alightingStation() {
        return alightingStation;
    }

    /**
     * Every ride this journey takes, in travel order.
     *
     * One entry per stay on a vehicle, not per leg: ride edges run between neighbouring stops, so three stations are three legs sharing one Ride. Runs are merged once because guidance asks several times a tick.
     */
    public List<Ride> rides() {
        return rides;
    }

    /** The ride in force at travelled, or null when this point is on foot. */
    public Ride rideAt(double travelled) {
        for (Leg leg : legs) {
            Ride ride = leg.ride();
            if (ride != null && ride.riding(travelled)) {
                return ride;
            }
        }
        return null;
    }

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
     * Position of one of this journey's rides in rides(), or -1 if it is not one.
     *
     * By identity, not equality: out and back on the same line can be field-identical.
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

    public Ride nextRide(double travelled) {
        for (Leg leg : legs) {
            Ride ride = leg.ride();
            if (ride != null && ride.boardAt() > travelled - STOP_SLACK) {
                return ride;
            }
        }
        return null;
    }

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
     * Every leg's points joined end to end for drawing. Points repeated where one leg ends where the next begins are dropped, avoiding zero-length steps.
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
     * Whole trip time, summed leg by leg: there is no single pace to derive it from, which is why this type exists.
     */
    public double estimatedSeconds() {
        double total = 0;
        for (Leg leg : legs) {
            total += leg.route().estimatedSeconds();
        }
        return total;
    }

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

    public boolean onRidingLeg(double x, double z) {
        Leg active = activeLeg(x, z);
        return active != null && active.mode() != TravelMode.WALK;
    }

    private static boolean samePoint(double[] a, double[] b) {
        return Math.abs(a[0] - b[0]) < 1.0E-6 && Math.abs(a[1] - b[1]) < 1.0E-6;
    }
}
