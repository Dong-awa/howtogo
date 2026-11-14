package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.route.Destination;
import bili.dongsz.howtogo.route.Route;
import net.minecraft.network.chat.Component;

/**
 * One frame's worth of the navigation readout: everything the HUD says about the trip, read once and consumed by whichever layout draws it.
 * Both layouts must say the same thing about the same trip, so all wording is decided here; nothing in this class draws, measures text or knows a pixel, and the map half of navigation is not here.
 * of() is called once per frame, and only while a destination is set.
 * @param state           which of the four readings this is: arrival outranks route, transit cue outranks turn
 * @param destinationName the destination's own name, undecorated
 * @param headerLabel     the destination as the header says it ("To %s"), single-sourced for both layouts
 * @param modeLabel       the travel mode's localised name
 * @param arrowDegrees    signed turn angle, or NaN for "no turn ahead"
 * @param distance        "distance to turn" slot: "--" with no junction, "Now" when close, else the distance
 * @param instruction     the instruction sentence, or null in states that do not draw it
 * @param progress        fraction of the route already covered, 0 to 1
 * @param remaining       the "remaining distance - about time" line, or null
 * @param offRoute        whether the remaining line is drawn in the warning colour
 * @param narratorHint    the text-to-speech note, or null while speech is available
 */
record NavHudReadout(State state, String destinationName, String headerLabel, String modeLabel,
                     double arrowDegrees, int arrowColor, String distance, String instruction,
                     double progress, String remaining, boolean offRoute, String fallbackHint,
                     String arrivalLine, String noRouteLine, Component narratorHint) {

    /**
     * Which of the four readings the trip is in. An explicit state because the questions are ordered: the first true answer takes the whole readout, and a renderer cannot ask them out of order.
     */
    enum State {

        /** The arrival banner is up; it outranks the route. */
        ARRIVED,

        /** No route to ride: the trip is set but the plan failed or has not been made. */
        NO_ROUTE,

        /** A transit cue: boarding, riding or leaving a line. The marker is a stop rather than an arrow. */
        TRANSIT,

        /** The ordinary turn-by-turn reading: arrow, distance, instruction, progress. */
        TURN
    }

    /**
     * The current readout, read from the navigation once. Called per frame; the order is arrival, no route, transit, turn.
     */
    static NavHudReadout of() {
        Destination target = Navigation.target();
        String destinationName = target == null ? null : target.name();
        // The header says "To <destination>" rather than the bare name, which would read as a label.
        String headerLabel = target == null ? ""
                : Component.translatable("hud.howtogo.hud_destination", destinationName).getString();
        String modeLabel = Navigation.modeLabel();
        String arrivalLine = Component.translatable("hud.howtogo.hud_arrived").getString();
        String noRouteLine = Component.translatable("hud.howtogo.hud_noroute").getString();
        // A narration toggle that looks on and produces nothing reads as broken; the note belongs to the readout because both layouts size themselves around it.
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

        // Shared by the two live readings: how far along the route the player is and what is left of it.
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
        // Read once: the voice, the panel and the compact card must flip from a distance to "Now" at the same number.
        double turnNow = Navigation.turnNowDistance();

        // While there is a line to be on -- walking to a boarding stop, or riding one -- the readout is the transit one. Walking legs keep the ordinary turn readout.
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

        // The wrong-way call takes over the readout: a player going the wrong way needs to turn round, not the turn the route would have given. It does so even when it cannot name a junction, rather than falling through to "continue straight".
        Navigation.Uturn uturn = Navigation.wrongWayUturn();
        boolean wrongWay = Navigation.wrongWay();
        Navigation.Instruction maneuver = wrongWay ? null : Navigation.nextManeuver();

        double maneuverDegrees = maneuver == null ? Double.NaN : maneuver.turnDegrees();
        // A U-turn is a U-turn whether the route asked for it or the player has turned round into one; on a highway neither is called as one because there is nowhere to turn. A straight-through call arrives as zero degrees and needs wording, not a special shape.
        boolean uturnCall = uturn != null || Navigation.isUturn(maneuverDegrees);
        boolean plainUturn = wrongWay && uturn == null;
        boolean highway = uturn != null ? uturn.highway()
                : ((uturnCall || plainUturn) && Navigation.onHighway());
        double arrowDegrees = (uturnCall || plainUturn) && highway ? Double.NaN
                : (uturn != null || plainUturn ? 180.0 : maneuverDegrees);

        // The distance and the instruction come from the same object, so the number can only count to the point the words are about.
        double distanceAhead = uturn != null ? uturn.distanceAhead()
                : (maneuver == null ? Double.NaN : maneuver.distanceAhead());
        String distance = Double.isNaN(distanceAhead)
                ? "--"
                : (distanceAhead <= turnNow
                        ? Component.translatable("hud.howtogo.hud_now").getString()
                        : Route.formatDistance(distanceAhead));

        String instruction;
        if (uturnCall || plainUturn) {
            // No road named: a U-turn goes back the way they came.
            instruction = Navigation.uturnAction(highway);
        } else if (maneuver == null) {
            instruction = Component.translatable("hud.howtogo.hud_straight").getString();
        } else if (maneuver.straight()) {
            // A junction the route carries straight through; the angle bands would otherwise file it under "keep left".
            instruction = Component.translatable("hud.howtogo.turn.straight").getString();
        } else if (maneuver.continuesNamedRoad()) {
            // The road goes on under the same name: "continue on X", not "onto X".
            String road = maneuver.roadName() != null && !maneuver.roadName().isBlank()
                    ? maneuver.roadName()
                    : Navigation.unnamedRoad();
            instruction = Navigation.continuesAction(Navigation.turnPhrase(maneuverDegrees), road);
        } else if (!maneuver.namesTheRoad()) {
            // The turn is real but the name is the road they are already on.
            instruction = Navigation.turnPhrase(maneuverDegrees);
        } else {
            // Always name the road being entered.
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

    /** Whether the arrival banner is up; checked before anything else. */
    boolean arrived() {
        return state == State.ARRIVED;
    }

    /**
     * Whether there is a route reading to draw; arrival outranks this, so it means "not the no-route case".
     */
    boolean hasRoute() {
        return state != State.NO_ROUTE;
    }

    /** Whether the live reading is a transit cue rather than a turn. */
    boolean transit() {
        return state == State.TRANSIT;
    }
}
