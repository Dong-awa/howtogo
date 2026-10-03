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

    /** One leg: a path and the mode it is travelled in. */
    public record Leg(Route route, TravelMode mode) {

        public boolean isPresent() {
            return route != null && route.isPresent();
        }
    }

    private static final Trip EMPTY = new Trip(List.of(), null, null);

    private final List<Leg> legs;
    private final String boardingStation;
    private final String alightingStation;

    private Trip(List<Leg> legs, String boardingStation, String alightingStation) {
        this.legs = List.copyOf(legs);
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
