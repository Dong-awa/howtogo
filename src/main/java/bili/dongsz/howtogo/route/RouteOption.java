package bili.dongsz.howtogo.route;

/**
 * One plan the picker offers: the route to draw and follow, the journey behind it when it rides anything, and the strategy that produced it. Carrying the journey keeps the numbers shown and the numbers followed one calculation.
 * @param route the line to draw and follow; Route.empty() is never stored here
 * @param trip journey the route was flattened from, or null for a single-mode plan
 * @param mode travel mode; TRANSIT for a journey that mixes walking and riding
 * @param strategy what the plan was asked for, which is how the list labels it
 */
public record RouteOption(Route route, Trip trip, TravelMode mode, RouteStrategy strategy) {

    /** Whether this plan has a route to draw; an option is only ever offered with one. */
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
     * Turns read off the drawn polyline, not announced maneuvers; on a hand-drawn grid unnamed streets chain into one road. See Route.bends().
     */
    public int turns() {
        return isPresent() ? route.bends() : 0;
    }

    /** Times the plan changes line; zero when it rides nothing. */
    public int transfers() {
        return trip == null ? 0 : Math.max(0, trip.rides().size() - 1);
    }

    /** Blocks on foot; zero when it rides nothing. */
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

    /** The strategy's localised name, or empty when none was recorded. */
    public String label() {
        return strategy == null ? "" : strategy.label();
    }
}
