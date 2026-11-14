package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.route.Route;

import java.util.List;

/**
 * Which turn the guidance is on, and which junctions the player has already been judged to have taken.
 * "Has this turn been taken" cannot be answered from geometry alone: a player level with a junction
 * may have turned or walked past it, and only their heading says which. So the verdict is made once,
 * when they come level with it, and remembered.
 * What is remembered is a frontier -- "every junction up to here has been taken" -- not one junction:
 * a single latch let a given-up junction overwrite a later verdict. A verdict only ever moves forward.
 * Free of Minecraft so the harness can drive the state machine directly; see TurnCursorCheck.
 */
final class TurnCursor {

    /**
     * Blocks behind a junction that still count as level with it, so a corner does not flicker; also lets "now" appear early.
     */
    static final double MANEUVER_PASSED_SLACK = 2.0;

    /**
     * How far a heading may differ from the route leaving a junction and still count as turning.
     * Generous on purpose: a corner being cut is not yet facing the new road. It still excludes
     * carrying straight on through a right-angled junction. A straight-through junction needs no
     * case of its own: bearingAfter is the route's direction, so a player carrying on satisfies
     * the arc as soon as they come level.
     */
    static final double TURN_TAKEN_ARC_DEGREES = 45.0;

    /**
     * How far past a junction the player may get without turning before the turn is given up on.
     * Straight-line distance, not route distance: walking away down the old road stops route
     * progress at the corner, so a route rule would never fire. The off-route re-plan normally
     * fires first; this is the backstop for a player inside the road's tolerance.
     */
    static final double TURN_GIVE_UP_DISTANCE = 60.0;

    /**
     * How exactly a junction's position must be covered by the frontier to count as judged.
     *
     * A guard against arithmetic, not a tolerance: two junctions a block apart are two decisions,
     * and the second must never be answered by the first.
     */
    private static final double TAKEN_IDENTITY_EPSILON = 1.0E-6;

    /** How far along the route every junction judged taken reaches, or NaN when none has been. */
    private double takenThrough = Double.NaN;

    /** Forgets every verdict; called on re-plan, which renumbers the junctions. */
    void reset() {
        takenThrough = Double.NaN;
    }

    /**
     * The junction the guidance is on, as an index into route.maneuvers(), or -1 for none.
     * Being level by distance is not having taken it: a manoeuvre behind by distance stays current
     * at distance zero -- shown as "now" -- until it is judged taken.
     * @param travelled blocks of the route already covered
     * @param heading the player's facing bearing, or NaN when there is no player
     */
    int nextIndex(Route route, double travelled, double heading, double x, double z) {
        if (route == null || !route.isPresent()) {
            return -1;
        }
        List<Route.Maneuver> maneuvers = route.maneuvers();
        for (int i = 0; i < maneuvers.size(); i++) {
            Route.Maneuver maneuver = maneuvers.get(i);
            if (maneuver.distanceFromStart() > travelled + MANEUVER_PASSED_SLACK
                    || !taken(maneuver, heading, x, z)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Whether a junction the player is already level with has actually been taken.
     * The verdict is made once and then read from the frontier: the heading wanders every tick, and
     * re-deciding brought already-taken turns back as "now". A few degrees of drift used to do it.
     * @param heading the player's facing bearing, or NaN when there is no player
     */
    private boolean taken(Route.Maneuver maneuver, double heading, double x, double z) {
        double at = maneuver.distanceFromStart();
        // Already answered: the walk restarts at the route's first junction every tick, so without this a given-up junction would overwrite a later verdict.
        if (!Double.isNaN(takenThrough) && at <= takenThrough + TAKEN_IDENTITY_EPSILON) {
            return true;
        }
        if (Double.isNaN(heading)
                // No player to judge a heading against, so the distance rule stands alone.
                || Math.hypot(x - maneuver.junctionX(), z - maneuver.junctionZ())
                        > TURN_GIVE_UP_DISTANCE
                || MovementState.bearingGap(heading, maneuver.bearingAfter())
                        <= TURN_TAKEN_ARC_DEGREES) {
            takenThrough = Double.isNaN(takenThrough) ? at : Math.max(takenThrough, at);
            return true;
        }
        return false;
    }
}
