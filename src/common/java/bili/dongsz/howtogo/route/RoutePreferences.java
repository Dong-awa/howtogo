package bili.dongsz.howtogo.route;

import bili.dongsz.howtogo.road.RoadClass;

import java.util.Locale;
import java.util.Set;

/**
 * The player's routing taste, as one immutable set of inputs.
 * Read once per plan so a search cannot mix two policies. It weights the search's edge cost
 * (weight) and never the pace a route reports, so a chosen route and a described route agree.
 * @param metric whether "best" means quickest or shortest
 * @param avoidedClasses classes kept out of the graph entirely
 * @param preferMajorRoads whether lesser classes are discouraged by a weight, not banned
 */
public record RoutePreferences(RoutePreference metric, Set<RoadClass> avoidedClasses,
                               boolean preferMajorRoads) {

    /**
     * How much a class's cost is multiplied by when major roads are preferred.
     * A weight per class rather than one penalty on one class: driving's two classes used to be
     * equally cheap, so the switch changed no route a driver was offered. Big enough to matter,
     * small enough that a genuine short cut still wins -- a penalty no detour can beat is a ban.
     * Applies to both metrics.
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
     * Multiplier on a class's cost in the search, and on no reported figure.
     * A class nobody is steered away from weighs 1, so an off policy costs exactly what it did.
     */
    public double weight(RoadClass roadClass) {
        if (!preferMajorRoads) {
            return 1.0;
        }
        return switch (roadClass) {
            case PATH -> MAJOR_ROAD_WEIGHT_PATH;
            case ROAD -> MAJOR_ROAD_WEIGHT_ROAD;
            case HIGHWAY -> MAJOR_ROAD_WEIGHT_HIGHWAY;
            // Rail, water and ice are outside the road hierarchy, so "prefer the major road" says nothing about them.
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
