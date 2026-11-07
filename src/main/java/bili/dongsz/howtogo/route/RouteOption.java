package bili.dongsz.howtogo.route;

/**
 * One plan the picker offers: the route to draw and follow, the journey behind it when it rides
 * anything, and the strategy that produced it.
 *
 * <h2>Why the journey travels with the route</h2>
 * A route is one path in one mode, and a public transport plan is a journey of legs whose flattening
 * into one route loses where the riding begins -- see {@link Trip}. The navigation needs both, so a
 * plan carries both, and the results list can say "two changes, four stops" about a plan it is about
 * to hand over without planning it again: the numbers shown and the numbers followed are one
 * calculation, which is the only way they cannot disagree.
 *
 * <h2>Why the facts are worked out here rather than by each caller</h2>
 * The list compares three plans on the same four numbers, and the map draws the same three routes.
 * Deriving a number differently in two places -- counting a turn off the prompts in one and off the
 * polyline in the other -- is how a list ends up recommending the plan it describes wrongly, so the
 * numbers live with the plan and every caller asks the same methods.
 *
 * @param route    the line to draw and follow; {@link Route#empty()} is never stored here
 * @param trip     the journey the route was flattened from, or null for a plan that is one path in one
 *                 mode -- every walk and every drive
 * @param mode     how the plan travels; for a public transport journey this is {@link
 *                 TravelMode#TRANSIT} while the journey itself mixes walking and riding
 * @param strategy what the plan was asked for, which is what the results list labels it as
 */
public record RouteOption(Route route, Trip trip, TravelMode mode, RouteStrategy strategy) {

    /** Whether this plan has a route to draw: an option is only ever offered when it does. */
    public boolean isPresent() {
        return route != null && route.isPresent();
    }

    /** Estimated seconds, the number the results list sorts by. */
    public double seconds() {
        return isPresent() ? route.estimatedSeconds() : 0;
    }

    /** Blocks, connectors included. */
    public double length() {
        return isPresent() ? route.totalLength() : 0;
    }

    /**
     * How many times the plan turns.
     *
     * <p>Read off the route's drawn line rather than off its announced maneuvers: on a hand-drawn
     * grid the unnamed streets chain into one road, so the guidance has nothing to announce while the
     * player is still turning at every corner, and the number the list compares has to be the one the
     * player feels. See {@link Route#bends()}.
     */
    public int turns() {
        return isPresent() ? route.bends() : 0;
    }

    /** How many times the plan changes line; zero for a plan that rides nothing. */
    public int transfers() {
        return trip == null ? 0 : Math.max(0, trip.rides().size() - 1);
    }

    /** How much of the plan is on foot, in blocks; zero for a plan that rides nothing. */
    public double walked() {
        if (trip == null) {
            return 0;
        }
        double walked = 0;
        for (Trip.Leg leg : trip.legs()) {
            if (leg.mode() == TravelMode.WALK) {
                walked += leg.route().totalLength();
            }
        }
        return walked;
    }

    /** The strategy's localised name, or the empty string for a plan with no strategy recorded. */
    public String label() {
        return strategy == null ? "" : strategy.label();
    }
}
