package bili.dongsz.howtogo.route;

import bili.dongsz.howtogo.road.RoadClass;

import java.util.Locale;
import java.util.Set;

/**
 * The player's routing taste, as one immutable set of inputs.
 *
 * <p>Gathered here rather than read from the config at each use because a single plan must see one
 * consistent policy: reading the file half way through a search could cost one leg by the old
 * metric and the next by the new one.
 *
 * <h2>What a taste is allowed to change, and what it is not</h2>
 * A taste changes which of two routes is <em>chosen</em>. It must not change what either of them
 * <em>costs</em> in the units the player is shown, which is why the preference is a weight on the
 * search's edge cost ({@link #weight}) and not a speed. Folding it into the pace, which is what this
 * class used to hand out, made the router minimise a number that was not the estimate the panel then
 * printed -- the two agreed only because the weighted pace happened never to be written into the
 * route's own legs. That is a coincidence, not an invariant, and nothing warned when it stopped
 * holding. A weight cannot break it: the pace a route reports stays the mode's real pace on the class
 * underfoot.
 *
 * @param metric           whether "best" means quickest or shortest
 * @param avoidedClasses   classes kept out of the graph entirely, as if the mode disallowed them
 * @param preferMajorRoads whether the lesser classes are discouraged by a cost weight rather than
 *                         banned
 */
public record RoutePreferences(RoutePreference metric, Set<RoadClass> avoidedClasses,
                               boolean preferMajorRoads) {

    /**
     * How much a class's cost is multiplied by when major roads are preferred.
     *
     * <h2>Why a hierarchy and not one penalty on one class</h2>
     * This used to be a single 1.6 laid on {@link RoadClass#PATH}, which did nothing at all for
     * driving: a car may not travel a footpath, so the only class being penalised was the one class
     * the switch could never matter for. Driving's two classes were left at the same pace, so a
     * highway and a road cost exactly the same and the switch changed no route a driver was ever
     * offered. A weight per class is what makes the preference mean what its name says.
     *
     * <p>The numbers are what the preference is worth in distance: a road may be up to a quarter
     * longer than a highway and still lose to it, and a footpath up to four fifths longer. Big enough
     * to be worth having, small enough that a genuine short cut still wins -- a penalty that cannot
     * be beaten by any detour is a ban, and this is deliberately not one.
     *
     * <p>Applicable to both metrics, which it did not use to be: the shortest-distance branch
     * returned a bare length and dropped the penalty entirely, so the same switch meant one thing
     * under "fastest" and nothing at all under "shortest".
     */
    private static final double MAJOR_ROAD_WEIGHT_HIGHWAY = 1.0;
    private static final double MAJOR_ROAD_WEIGHT_ROAD = 1.25;
    private static final double MAJOR_ROAD_WEIGHT_PATH = 1.8;

    /** Today's behaviour, and what the router assumes when no policy has been supplied. */
    public static final RoutePreferences DEFAULTS =
            new RoutePreferences(RoutePreference.FASTEST_TIME, Set.of(), false);

    public RoutePreferences {
        metric = metric == null ? RoutePreference.FASTEST_TIME : metric;
        avoidedClasses = avoidedClasses == null ? Set.of() : Set.copyOf(avoidedClasses);
    }

    /** Whether the given class is kept out of the network for this policy. */
    public boolean avoids(RoadClass roadClass) {
        return avoidedClasses.contains(roadClass);
    }

    /** Whether anything is being avoided at all, which is what makes a failure worth explaining. */
    public boolean avoidsAny() {
        return !avoidedClasses.isEmpty();
    }

    /**
     * Multiplier on a class's cost in the search, and on nothing else.
     *
     * <p>It is applied to the edge cost in either metric and to no reported figure at all: the panel's
     * "about X" is the mode's real pace over the ground, so a route cannot be chosen for one number
     * and described with another. A class nobody is being steered away from weighs 1, so a policy
     * with the preference off -- and every class other than the three the hierarchy names -- costs
     * exactly what it always did.
     */
    public double weight(RoadClass roadClass) {
        if (!preferMajorRoads) {
            return 1.0;
        }
        return switch (roadClass) {
            case PATH -> MAJOR_ROAD_WEIGHT_PATH;
            case ROAD -> MAJOR_ROAD_WEIGHT_ROAD;
            case HIGHWAY -> MAJOR_ROAD_WEIGHT_HIGHWAY;
            // Rail, water and ice are not part of a road hierarchy at all: a rider is on the line
            // they are on, and "prefer the major road" has nothing to say about which one that is.
            default -> 1.0;
        };
    }

    /** The avoided classes in a stable order, for the log line and the picker summary. */
    public String avoidedSummary() {
        StringBuilder summary = new StringBuilder();
        for (RoadClass roadClass : RoadClass.values()) {
            if (!avoidedClasses.contains(roadClass)) {
                continue;
            }
            if (summary.length() > 0) {
                summary.append(", ");
            }
            summary.append(roadClass.name().toLowerCase(Locale.ROOT));
        }
        return summary.toString();
    }
}
