package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.route.Route;

import java.util.List;

/**
 * Which turn the guidance is on, and which junctions the player has already been judged to have taken.
 *
 * <p>A cursor rather than a reading of the route, because "has this turn been taken" cannot be
 * answered from the geometry alone: a player level with a junction has either just turned at it or
 * walked straight past it, and only their heading says which. So the verdict is made once, when they
 * come level with the junction, and remembered -- and what is remembered is a <em>frontier</em>
 * along the route rather than one junction, which is the whole of a reported bug.
 *
 * <h2>Why a frontier and not the last junction judged</h2>
 * The junctions are walked in order from the route's start and the walk stops at the first one not
 * taken, so a junction that has been judged taken is always one of a run reaching from the start of
 * the route. A single remembered junction was overwritten by every verdict the walk reached, and
 * because the walk always begins again at the first junction of the route, an early one that was
 * given up on -- which {@link #TURN_GIVE_UP_DISTANCE} does on purpose, sixty blocks back -- wrote
 * its own distance over the latch of a <em>later</em> junction the player had genuinely turned at.
 * That later verdict was then gone; the heading no longer pointed down the road it entered, because
 * the player had driven on and the road bends; and the readout walked back to the junction already
 * left behind, showing and speaking the turn just made as the next instruction -- and a junction or
 * two further along was skipped over in the same step, so what the player was told to do next
 * belonged somewhere else entirely.
 *
 * <p>What is remembered is therefore "every junction up to here has been taken", and a verdict can
 * only ever move forward: a junction does not become untaken by driving away from it.
 *
 * <p>This class is deliberately free of Minecraft: the state machine is the part that was wrong, and
 * the harness drives it directly. See {@code TurnCursorCheck}.
 */
final class TurnCursor {

    /**
     * Blocks behind a junction that still count as level with it, so a corner does not flicker.
     *
     * <p>Used for "the player has come level with this junction", which is what makes the heading
     * worth consulting at all. It also lets a turn be shown as "now" from a couple of blocks out, so
     * the wording and the arrow are already up when the player reaches the corner.
     */
    static final double MANEUVER_PASSED_SLACK = 2.0;

    /**
     * How far a heading may differ from the route leaving a junction and still count as turning.
     *
     * <p>Generous on purpose: a player halfway through a turn, or cutting the corner, is not yet
     * pointing down the new road, and holding the instruction back through that would flicker. It
     * still excludes carrying straight on through a right-angled junction, which is ninety degrees
     * out. The two do overlap on the shallowest manoeuvres -- a turn of twenty-five degrees, the
     * threshold for one existing at all, is barely different from not turning -- but there the two
     * directions mean nearly the same thing, so getting it wrong costs nothing.
     */
    static final double TURN_TAKEN_ARC_DEGREES = 45.0;

    /**
     * How far past a junction the player may get without turning before the turn is given up on.
     *
     * <p>Straight-line distance from the junction, not distance along the route: a player walking
     * away down the road they were already on stops making progress along the route as soon as they
     * pass the corner, so a route-distance rule would never fire on the case it exists for. In
     * practice the off-route re-plan fires first, within a couple of seconds, and rebuilds the whole
     * trip; this is the backstop for a player who stays inside the road's tolerance the whole way,
     * where nothing else would ever move the instruction on.
     */
    static final double TURN_GIVE_UP_DISTANCE = 60.0;

    /**
     * How exactly a junction's position has to be covered by the frontier to count as already judged.
     *
     * <p>Both numbers are the same quantity read off the same manoeuvre -- its own
     * {@code distanceFromStart}, against a frontier that was set from one -- so this is a guard
     * against the arithmetic rather than a tolerance. Slack here would be a different bug: two
     * junctions a block or two apart along the route are two decisions, and the second must never be
     * answered for by the first.
     */
    private static final double TAKEN_IDENTITY_EPSILON = 1.0E-6;

    /** How far along the route every junction judged taken reaches, or NaN when none has been. */
    private double takenThrough = Double.NaN;

    /**
     * Forgets every verdict.
     *
     * <p>Called whenever the route is re-planned, because a new plan renumbers every junction along
     * it and a verdict about the old numbering means nothing.
     */
    void reset() {
        takenThrough = Double.NaN;
    }

    /**
     * The junction the guidance is on, as an index into {@code route.maneuvers()}, or -1 for none.
     *
     * <p>Being level with a junction by distance is not the same as having taken it. A player who
     * walks straight past a corner is still level with it, and calling the turn done there would
     * swap the instruction for the next one while they are standing at the wrong road. So a
     * manoeuvre that is behind by distance is only passed once it has been judged taken; until then
     * it stays current, at a distance of zero, which the readout and the announcement both show as
     * "now".
     *
     * @param travelled blocks of the route already covered, as the readout computes it
     * @param heading  the player's facing bearing, or NaN when there is no player to judge by
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
     *
     * <p>The verdict is made once and then answered from the frontier, because it is re-made every
     * tick out of the player's heading and a heading wanders: a bend in the new road, a glance
     * sideways, a step around a corner all take it outside the arc. Re-deciding a junction the
     * player had already turned at is what brought it back as the current instruction -- at a
     * distance of zero, so it was shown and spoken as "now" -- and then it disappeared again when
     * the heading swung back. That is the unexplained "now turn left" at junctions, and it needs no
     * reversal to happen, only a few degrees of drift.
     *
     * @param heading the player's facing bearing, or NaN when there is no player to judge by
     */
    private boolean taken(Route.Maneuver maneuver, double heading, double x, double z) {
        double at = maneuver.distanceFromStart();
        // Already answered. This is the whole of the fix: the walk starts again at the first junction
        // of the route every tick, so without this an early junction that was given up on would
        // re-decide -- and overwrite -- the verdict on a later one the player had genuinely turned at.
        if (!Double.isNaN(takenThrough) && at <= takenThrough + TAKEN_IDENTITY_EPSILON) {
            return true;
        }
        if (Double.isNaN(heading)
                // Nothing to judge a heading against -- no player yet -- so the distance rule stands
                // alone rather than every turn being held open forever.
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
