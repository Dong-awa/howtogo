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
 * @param metric           whether "best" means quickest or shortest
 * @param avoidedClasses   classes kept out of the graph entirely, as if the mode disallowed them
 * @param preferMajorRoads whether minor roads are discouraged by a cost penalty rather than banned
 */
public record RoutePreferences(RoutePreference metric, Set<RoadClass> avoidedClasses,
                               boolean preferMajorRoads) {

    /**
     * Extra cost laid on a footpath when major roads are preferred.
     *
     * <p>A penalty rather than an exclusion: banning footpaths outright makes trips unroutable for
     * no visible reason, while a penalty leaves them available as the only way through or when they
     * are genuinely much shorter.
     */
    private static final double MINOR_ROAD_PENALTY = 1.6;

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
     * Multiplier laid on a class's cost, and divided out of its pace.
     *
     * <p>Applied to the estimate as well as to the search, so a route can never be chosen for one
     * pace and then reported at another.
     */
    public double penalty(RoadClass roadClass) {
        return preferMajorRoads && roadClass == RoadClass.PATH ? MINOR_ROAD_PENALTY : 1.0;
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
