package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.route.Destination;
import bili.dongsz.howtogo.route.Route;
import net.minecraft.network.chat.Component;

/**
 * One frame's worth of the navigation readout: everything the HUD says about the trip, read from the
 * navigation once and consumed by whichever layout is drawing it.
 *
 * <h2>Why the values are taken here rather than at each use</h2>
 * The HUD has two layouts now. The full panel draws the mini-map and the readout under it; in
 * {@code xaero_minimap} mode the map half is Xaero's and this mod keeps only a compact card with the
 * readout in it. Both layouts must say the same thing about the same trip -- a player switching the
 * display mode must not see the turn count down differently, or the distance to the junction change
 * its wording -- and the cheapest way to guarantee that is for the wording to be decided in one place
 * and both layouts to draw the result.
 *
 * <p>It also removes the accidental second readings the panel used to make: the turn, the distance
 * and the instruction were each derived from a fresh call to {@code Navigation} on the frame line
 * that drew them, while the panel drawn around them had already asked some of the same questions.
 * Navigation's own readings are consistent within a tick, so that was mostly harmless -- but it meant
 * two places that could each be changed to ask something else, and the wrong-way call in particular is
 * worded from four separate readings that must all describe the same junction. Here they are taken in
 * one pass, in the order the panel's branches always took them, and the next reader of the value
 * cannot change what the words are.
 *
 * <h2>What is not here</h2>
 * Nothing in this class draws, measures text or knows a pixel. The layout -- where a row sits, how
 * wide the panel is, whether the instruction has to be truncated -- stays with the renderer that owns
 * that layout. The map half of navigation is not here either: the route polyline, the markers and the
 * rotation are the map's business, and this value is only what is said about them.
 *
 * <h2>The one read</h2>
 * {@link #of()} is the only way to make one. It is called once per frame, whichever layout ends up
 * drawing, because both need it; the guard in the HUD entry point means it is only ever called while
 * a destination is set.
 *
 * @param state          which of the four readings this is, in the precedence the panel has always
 *                       used: the arrival banner outranks the route, and the transit cue outranks the
 *                       turn
 * @param destinationName the destination's own name, undecorated; null only if the factory is ever
 *                       called with no target, which the entry point prevents
 * @param headerLabel    the destination as the header says it -- {@code "To %s"} for the destination
 *                       name. Single-sourced because both layouts title themselves with it, and a
 *                       second {@code Component.translatable} call at the other layout is exactly the
 *                       kind of pair that drifts when the wording is changed
 * @param modeLabel      the travel mode's localised name, which the panel puts on the header row and
 *                       the compact card on its title row
 * @param arrowDegrees   signed turn angle for the arrow, or NaN for "no turn ahead" (drawn straight
 *                       on) -- which is also the answer while there is no live turn to draw
 * @param arrowColor     the colour of the arrow and, while the arrival or no-route reading is up, of
 *                       the line beside it: the two are one statement and share one colour. The
 *                       transit reading has no arrow -- its stop marker carries its own colours
 * @param distance       the "distance to the turn" slot: {@code "--"} when there is no junction to
 *                       count to, {@code "Now"} when the junction is close enough to act on, else the
 *                       formatted distance. Null in the states that do not draw the slot
 * @param instruction    the instruction sentence -- the turn, the U-turn wording, the highway wording
 *                       or the transit sentence. Null in the states that do not draw it
 * @param progress       fraction of the route already covered, 0 to 1, for the bar
 * @param remaining      the "remaining distance - about time" line. Null in the states that do not
 *                       draw it
 * @param offRoute       whether the remaining line should be drawn in the warning colour, because the
 *                       player has strayed further from the line than the route's tolerance
 * @param fallbackHint   why the live route is a walk although another mode was chosen, or null when
 *                       the chosen mode was kept. Only set for the live readings, because only they
 *                       draw a route to explain
 * @param arrivalLine    the fixed "Arrived" line, for the arrival reading
 * @param noRouteLine    the fixed "No route" line, for the no-route reading
 * @param narratorHint   the text-to-speech note, or null while speech is available. The panel sizes
 *                       itself around it and the compact card has room for it, so it belongs to the
 *                       readout rather than to either renderer asking the narration again
 */
record NavHudReadout(State state, String destinationName, String headerLabel, String modeLabel,
                     double arrowDegrees, int arrowColor, String distance, String instruction,
                     double progress, String remaining, boolean offRoute, String fallbackHint,
                     String arrivalLine, String noRouteLine, Component narratorHint) {

    /**
     * Which of the four readings the trip is in.
     *
     * <p>An explicit state rather than three booleans, because the three questions are not
     * independent: the panel asks them in a fixed order, and the first true answer takes the whole
     * readout. Keeping the order in the factory means a renderer cannot accidentally ask "is there a
     * route" before "has the trip arrived" and, for the second or so between arriving and the trip
     * clearing, show the no-route reading to a player who has just got where they were going.
     */
    enum State {

        /**
         * The arrival banner is up. Outranks the route: the trip may or may not have been cleared
         * yet, and either way the thing to say is that the player has arrived.
         */
        ARRIVED,

        /** No route to ride. The trip is set but the plan failed or has not been made. */
        NO_ROUTE,

        /**
         * A transit cue is being shown: boarding a line, riding one, or getting off. The readout is
         * the transit sentence and the marker is a stop rather than an arrow -- see
         * {@link NavHudShapes#drawTransitMarker}.
         */
        TRANSIT,

        /** The ordinary turn-by-turn reading: turn arrow, distance, instruction, progress. */
        TURN
    }

    /**
     * The current readout, read from the navigation once.
     *
     * <p>Called per frame while the HUD is up. Its order is the panel's own branch order, kept so
     * the value describes the same reading the panel has always produced: arrival first, then the
     * absence of a route, then the transit cue, then the turn.
     */
    static NavHudReadout of() {
        Destination target = Navigation.target();
        String destinationName = target == null ? null : target.name();
        // The header says "To <destination>" rather than the bare name: a heading that is only a name
        // reads as a label on the screen rather than a statement about where the trip is going. Same
        // wording the panel's header has always used.
        String headerLabel = target == null ? ""
                : Component.translatable("hud.howtogo.hud_destination", destinationName).getString();
        String modeLabel = Navigation.modeLabel();
        String arrivalLine = Component.translatable("hud.howtogo.hud_arrived").getString();
        String noRouteLine = Component.translatable("hud.howtogo.hud_noroute").getString();
        // A toggle that looks on and produces nothing reads as a broken feature, and the log is not
        // where a player looks. The note is part of the readout layer: the panel is as tall as this
        // line and the compact card has the rows for it.
        Component narratorHint = Narration.unavailable()
                ? Component.translatable("hud.howtogo.narrator_unavailable")
                : null;

        if (Navigation.showArrival()) {
            return new NavHudReadout(State.ARRIVED, destinationName, headerLabel, modeLabel,
                    Double.NaN, UiTheme.ok(), null, null, 0, null, false, null,
                    arrivalLine, noRouteLine, narratorHint);
        }

        Route route = Navigation.route();
        if (!route.isPresent()) {
            return new NavHudReadout(State.NO_ROUTE, destinationName, headerLabel, modeLabel,
                    Double.NaN, UiTheme.warn(), null, null, 0, null, false, null,
                    arrivalLine, noRouteLine, narratorHint);
        }

        // Everything below is shared by the two live readings: both say how far along the route the
        // player is and what is left of it, and only the marker and the instruction differ.
        double total = route.totalLength();
        double travelled = Navigation.travelled();
        double progress = total > 1.0E-6
                ? Math.max(0, Math.min(1, travelled / total))
                : 0;
        String remaining = Component.translatable("hud.howtogo.hud_remaining_short",
                Route.formatDistance(Navigation.remainingLength()),
                Route.formatDuration(Navigation.remainingSeconds())).getString();
        boolean offRoute = Navigation.isOffRoute();
        String fallbackHint = Navigation.fallbackHint();
        // Read once and used by both live readings: the voice, the panel and the compact card all
        // flip from a distance to "Now" at the same number, so a turn can never be called now by one
        // of them and counted down by another.
        double turnNow = Navigation.turnNowDistance();

        // While there is a line to be on -- walking up to the stop of a boarding, or riding one --
        // the readout is the transit one: which line and which way, or which stop is coming and how
        // many are left. Walking to and from the vehicle keeps the ordinary turn readout, because
        // that is what those legs are. See Navigation#transitStep.
        Navigation.TransitStep step = Navigation.transitStep();
        if (step != null) {
            String distance = step.distanceAhead() <= turnNow
                    ? Component.translatable("hud.howtogo.hud_now").getString()
                    : Route.formatDistance(step.distanceAhead());
            return new NavHudReadout(State.TRANSIT, destinationName, headerLabel, modeLabel,
                    Double.NaN, UiTheme.accent(), distance, Navigation.transitSentence(step),
                    progress, remaining, offRoute, fallbackHint, arrivalLine, noRouteLine,
                    narratorHint);
        }

        // The wrong-way call takes the readout over when it is up: a player going the wrong way has
        // no use for the turn the route was going to give them next, and turning round at the next
        // junction is the instruction that actually gets them moving again.
        //
        // The wrong-way READING takes it over whether or not it could name a junction. Falling through
        // to the route's next turn when it could not is what produced "continue straight" for a player
        // travelling against the route; the plain U-turn, with no junction and no distance, is the
        // honest answer to not knowing where they can turn round.
        Navigation.Uturn uturn = Navigation.wrongWayUturn();
        boolean wrongWay = Navigation.wrongWay();
        Navigation.Instruction maneuver = wrongWay ? null : Navigation.nextManeuver();

        double maneuverDegrees = maneuver == null ? Double.NaN : maneuver.turnDegrees();
        // A U-turn is a U-turn whether the route asked for it or the player has turned round into
        // one, and on a highway neither can be called as one: there is nowhere to turn, so the same
        // junction is called as carrying on to it, and the arrow has to agree with the words rather
        // than with the angle the route came in at.
        boolean uturnCall = uturn != null || Navigation.isUturn(maneuverDegrees);
        boolean plainUturn = wrongWay && uturn == null;
        boolean highway = uturn != null ? uturn.highway()
                : ((uturnCall || plainUturn) && Navigation.onHighway());
        double arrowDegrees = (uturnCall || plainUturn) && highway ? Double.NaN
                : (uturn != null || plainUturn ? 180.0 : maneuverDegrees);

        // The number and the instruction are taken from the same object here and everywhere else that
        // shows them, so the distance can only ever count to the point the words are about. See
        // Navigation's note on that rule. With no junction named there is no point to count to, so the
        // slot shows nothing rather than a number from somewhere else.
        double distanceAhead = uturn != null ? uturn.distanceAhead()
                : (maneuver == null ? Double.NaN : maneuver.distanceAhead());
        String distance = Double.isNaN(distanceAhead)
                ? "--"
                : (distanceAhead <= turnNow
                        ? Component.translatable("hud.howtogo.hud_now").getString()
                        : Route.formatDistance(distanceAhead));

        String instruction;
        if (uturnCall || plainUturn) {
            // No road named: a U-turn is not a turn onto anything, it is going back the way they came.
            instruction = Navigation.uturnAction(highway);
        } else if (maneuver == null) {
            instruction = Component.translatable("hud.howtogo.hud_straight").getString();
        } else if (!maneuver.namesTheRoad()) {
            // The turn is real, the name is not worth saying: it is the road they are already on.
            instruction = Navigation.turnPhrase(maneuverDegrees);
        } else {
            // Always name the road being entered, so the sentence never quietly loses its object.
            String road = maneuver.roadName() != null && !maneuver.roadName().isBlank()
                    ? maneuver.roadName()
                    : Navigation.unnamedRoad();
            instruction = Component.translatable("hud.howtogo.turn_onto",
                    Navigation.turnPhrase(maneuverDegrees), road).getString();
        }

        return new NavHudReadout(State.TURN, destinationName, headerLabel, modeLabel,
                arrowDegrees, UiTheme.accent(), distance, instruction, progress, remaining, offRoute,
                fallbackHint, arrivalLine, noRouteLine, narratorHint);
    }

    /** Whether the arrival banner is up; the reading the panel checks before anything else. */
    boolean arrived() {
        return state == State.ARRIVED;
    }

    /**
     * Whether there is a route reading to draw.
     *
     * <p>The arrival reading outranks this question, as it always has: by the time a caller asks it,
     * arrival has already been ruled out, so this is the panel's "not the no-route case" predicate
     * rather than a raw {@code route().isPresent()}. That is why the readout records the answer as
     * the state instead of re-asking the navigation here.
     */
    boolean hasRoute() {
        return state != State.NO_ROUTE;
    }

    /** Whether the live reading is a transit cue rather than a turn. */
    boolean transit() {
        return state == State.TRANSIT;
    }
}
