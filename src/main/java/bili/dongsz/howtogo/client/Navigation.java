package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.RoadConfig;
import bili.dongsz.howtogo.HowToGo;
import bili.dongsz.howtogo.road.RoadChains;
import bili.dongsz.howtogo.road.RoadClass;
import bili.dongsz.howtogo.road.RoadNetwork;
import bili.dongsz.howtogo.road.RoadNode;
import bili.dongsz.howtogo.road.RoadSegment;
import bili.dongsz.howtogo.route.Destination;
import bili.dongsz.howtogo.route.RideRoads;
import bili.dongsz.howtogo.route.RoadRouter;
import bili.dongsz.howtogo.route.Route;
import bili.dongsz.howtogo.route.RouteFailure;
import bili.dongsz.howtogo.route.RoutePreferences;
import bili.dongsz.howtogo.route.TransitPlanner;
import bili.dongsz.howtogo.route.TravelMode;
import bili.dongsz.howtogo.route.Trip;
import bili.dongsz.howtogo.transit.LineStop;
import bili.dongsz.howtogo.transit.TransitLine;
import bili.dongsz.howtogo.store.RoutePreferenceStore;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The active navigation session: where the player is going, the route to get there, and how the
 * trip is progressing.
 *
 * <p>The route is recomputed only when something actually warrants it -- the player has moved a
 * meaningful distance, has wandered off the line, or has changed travel mode -- rather than every
 * tick. Re-running A* per tick would be wasteful, and a route that re-solves on every step flickers
 * between equal-cost paths.
 */
public final class Navigation {

    /**
     * How close counts as arrived.
     */
    private static final double ARRIVAL_DISTANCE = 12.0;

    /**
     * How close to a stop the player has to be for the vehicle to count as standing at it.
     *
     * <p>The same order of size as the destination's own arrival distance, and for the same reason: a
     * platform is a place a dozen blocks across, and the drawn line runs through the middle of the
     * station rather than along the edge of the platform a player steps onto. This is what the "get off
     * here" call is measured by -- see {@link #arrivedAt} -- and it is deliberately smaller than the
     * station snap the off-route test uses: standing somewhere in the station's grounds is being at the
     * station, but arriving is being on the platform.
     */
    private static final double STOP_ARRIVAL_DISTANCE = 12.0;

    /**
     * How long the player must stay off route before it is re-planned.
     *
     * <p>A single sample is not enough. Crossing a junction, clipping a corner or cutting a bend
     * all put the player briefly outside the tolerance, and re-planning on those makes the start
     * marker chase the player instead of marking where the trip began.
     */
    private static final long OFF_ROUTE_GRACE_MILLIS = 2500L;

    /** How long the arrival banner stays up. */
    private static final long ARRIVAL_BANNER_MILLIS = 8000L;

    private static Destination target;
    private static Route route = Route.empty();
    /**
     * The journey the live route was flattened from, or null when the live route is not a public
     * transport one.
     *
     * <p>Kept beside the route rather than inside it because a route is one path in one mode and cannot
     * say where the riding begins -- see {@link Trip}. Without it, a transit trip can be guided only by
     * the turns of its walking legs, because the boarding and alighting points were flattened away.
     * Written by {@link #recomputeFrom} alone, and cleared whenever the route stops being that journey.
     */
    private static Trip transitTrip;
    private static double routeOriginX = Double.NaN;
    private static double routeOriginZ = Double.NaN;

    /**
     * How the player is travelling, which is what the route and the estimate are built for.
     *
     * <p>Held here rather than inside the route so a switch can re-plan from a clean slate, and
     * defaulted to walking so navigation is usable before the config has been read at all.
     */
    private static TravelMode mode = TravelMode.WALK;
    /** Set once the client config has actually been read; until then the default stands. */
    private static boolean modeLoaded;
    /**
     * Set once the player has picked a mode themselves, in the picker or by command.
     *
     * <p>What a config reload must not do is take that choice away: the config declares a *default*,
     * and a default that overwrites an answer the player gave this session is not a default. Read
     * only by {@link #reloadConfiguredMode}.
     */
    private static boolean modeChosen;

    /**
     * Where the trip began, pinned for the whole session.
     *
     * <p>The route itself is rebuilt from the player's position whenever they genuinely leave it
     * (the way a navigation app re-plans), but the origin marker must not tag along with them --
     * it marks where the journey started, and the player's current position acts as the point the
     * remaining route is computed from.
     */
    private static double tripOriginX = Double.NaN;
    private static double tripOriginZ = Double.NaN;

    private static boolean arrived;
    private static long arrivedAtMillis;
    /** When the player was first seen off route, or 0 when they are currently on it. */
    private static long offRouteSince;

    /**
     * The dimension the trip was planned in, or null when no trip is active.
     *
     * <p>A destination is a pair of coordinates and a name, and coordinates only mean anything inside
     * one dimension: the road network is per dimension, and every number the session holds -- the
     * route line, the distance left, the road underfoot, whether the player is off route -- belongs to
     * the world it was built in. Nothing used to notice a change of world. Walking through a portal
     * therefore left the panel guiding a route from the world the player had left, and standing at the
     * same x and z in the new world was read as arriving at the destination, which then cleared the
     * trip.
     */
    private static String tripDimension;

    /**
     * The mode the last plan gave up on, or null when the mode that was asked for was kept.
     *
     * <p>Kept so the readout can explain itself. Handing the player a walking route while the
     * panel still claims to be navigating by rail would be worse than either answer on its own.
     */
    private static TravelMode abandonedMode;
    /** Time the abandoned mode would have taken, or NaN when it found no route at all. */
    private static double abandonedSeconds = Double.NaN;
    private static double walkingSeconds;

    private Navigation() {
    }

    public static boolean isActive() {
        return target != null && route.isPresent();
    }

    public static Destination target() {
        return target;
    }

    public static Route route() {
        return route;
    }

    /** The active travel mode. */
    public static TravelMode mode() {
        if (!modeLoaded) {
            // Client setup normally reads the config; this covers anything that asks earlier, and
            // reads it once rather than leaving the default pinned for the session.
            loadConfiguredMode();
        }
        return mode;
    }

    /** Localised name of the active mode, for the readout and the tooltip. */
    public static String modeLabel() {
        return mode().label();
    }

    /**
     * Why the last plan was made on foot, or null when the chosen mode was kept.
     *
     * <p>Null rather than an empty sentence, so callers can decide whether there is a line to draw
     * at all instead of measuring a string that only exists to be blank.
     */
    public static String fallbackHint() {
        if (abandonedMode == null) {
            return null;
        }
        if (Double.isNaN(abandonedSeconds)) {
            return net.minecraft.network.chat.Component
                    .translatable("hud.howtogo.no_mode_route", abandonedMode.label()).getString();
        }
        return net.minecraft.network.chat.Component
                .translatable("hud.howtogo.slower_than_walking", abandonedMode.label(),
                        Route.formatDuration(abandonedSeconds), Route.formatDuration(walkingSeconds))
                .getString();
    }

    /**
     * Reads the configured starting mode and logs it.
     *
     * <p>Called on client setup. Does nothing while the config is still unavailable, so the value
     * is picked up on the next call rather than being frozen at the default.
     */
    public static void loadConfiguredMode() {
        TravelMode configured = RoadConfig.defaultTravelMode();
        if (configured == null) {
            return;
        }
        mode = configured;
        modeLoaded = true;
        HowToGo.LOGGER.info("[HowToGo] travel mode: {}", mode.id());
    }

    /**
     * Re-reads the configured default after the config file has been reloaded.
     *
     * <p>The mode is latched rather than read where it is used, because a route is planned for it and
     * the two must not change under a trip. That latch is why saving the config screen used to leave
     * a corrected default_travel_mode with no effect until the game was restarted. Does nothing when
     * the player has chosen a mode of their own this session, since a reload is about the default.
     */
    public static void reloadConfiguredMode() {
        if (modeChosen) {
            return;
        }
        loadConfiguredMode();
    }

    /**
     * Switches travel mode, re-planning the current route straight away.
     *
     * <p>A mode is not a label on the estimate: the route itself differs, because a driver must not
     * be sent down a footpath and a walker should not be sent along a rail line. Switching
     * therefore rebuilds the route rather than recolouring the existing one, the way a navigation
     * app re-plans when the mode of transport is changed.
     */
    public static void setMode(TravelMode newMode) {
        mode = newMode == null ? TravelMode.WALK : newMode;
        modeLoaded = true;
        modeChosen = true;
        HowToGo.LOGGER.info("[HowToGo] travel mode: {}", mode.id());
        announceMode();
        if (target != null) {
            recompute();
        }
    }

    /** Says the new mode on the action bar, so a hotkey press has visible feedback. */
    private static void announceMode() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        player.displayClientMessage(
                net.minecraft.network.chat.Component.translatable(
                        "hud.howtogo.mode_switched", mode.label()),
                true);
    }

    /** Starts navigating to the given destination, replacing any current route. */
    public static void setTarget(Destination destination) {
        target = destination;
        routeOriginX = Double.NaN;
        arrived = false;
        offRouteSince = 0L;
        clearWrongWay();
        // Pinned with the destination: a trip is this destination, in this world.
        tripDimension = currentDimension();
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) {
            // Pinned here and never updated again: this is the start of the trip.
            tripOriginX = player.getX();
            tripOriginZ = player.getZ();
        } else {
            tripOriginX = destination.x();
            tripOriginZ = destination.z();
        }
        recompute();
    }

    public static void clear() {
        target = null;
        route = Route.empty();
        transitTrip = null;
        routeOriginX = Double.NaN;
        routeOriginZ = Double.NaN;
        tripOriginX = Double.NaN;
        tripOriginZ = Double.NaN;
        arrived = false;
        offRouteSince = 0L;
        tripDimension = null;
        clearFallback();
        clearWrongWay();
        turns.reset();
    }

    /** The dimension the player is in, or null when no world is loaded. */
    private static String currentDimension() {
        net.minecraft.client.multiplayer.ClientLevel level = Minecraft.getInstance().level;
        return level == null ? null : level.dimension().location().toString();
    }

    /** Where the trip started; stays fixed even after the route is re-planned. */
    public static double tripOriginX() {
        return tripOriginX;
    }

    /** Where the trip started; stays fixed even after the route is re-planned. */
    public static double tripOriginZ() {
        return tripOriginZ;
    }

    public static void tick() {
        if (target == null) {
            return;
        }
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        double x = player.getX();
        double z = player.getZ();

        // A trip belongs to the world it was planned in; see tripDimension. Checked before anything
        // else, because every reading below is taken against that world's road network.
        String dimension = currentDimension();
        if (tripDimension != null && dimension != null && !tripDimension.equals(dimension)) {
            HowToGo.LOGGER.info("[HowToGo] the trip was planned in {} and the player is now in {}; "
                    + "ending it rather than guiding the wrong world's roads", tripDimension, dimension);
            player.displayClientMessage(
                    Component.translatable("hud.howtogo.dimension_changed"), true);
            clear();
            return;
        }

        // Kept current before anything below can return early: the readout asks for it every frame,
        // and a call left over from before an arrival would outlive the trip it belonged to. It also
        // has to be sampled at a steady rate for the run of ticks to mean anything.
        updateWrongWay(x, z);
        updateRoadClass();

        if (arrived) {
            // Drop the whole session once the banner has had its moment, rather than leaving a
            // stale destination that would report "no route" forever.
            if (System.currentTimeMillis() - arrivedAtMillis > ARRIVAL_BANNER_MILLIS) {
                clear();
            }
            return;
        }
        if (updateArrival(x, z)) {
            return;
        }

        if (Double.isNaN(routeOriginX)) {
            recomputeFrom(x, z);
            return;
        }

        // The route is deliberately not recalculated as the player walks. Re-solving on movement
        // drags the start marker along with the player, which makes "how far have I got" and the
        // walked/remaining colouring meaningless. It is rebuilt only once the player has genuinely
        // left it and stayed away.
        if (isOffRoute(x, z)) {
            long now = System.currentTimeMillis();
            if (offRouteSince == 0L) {
                offRouteSince = now;
            } else if (now - offRouteSince > OFF_ROUTE_GRACE_MILLIS) {
                HowToGo.LOGGER.info(
                        "[HowToGo] off route: {} blocks out, tolerance {} - recalculating",
                        String.format("%.1f", route.distanceTo(x, z)),
                        String.format("%.1f", route.toleranceNear(x, z)));
                offRouteSince = 0L;
                recomputeFrom(x, z);
            }
        } else {
            offRouteSince = 0L;
        }
    }

    // --------------------------------------------------------------- progress

    private static boolean updateArrival(double x, double z) {
        if (target != null && Math.hypot(x - target.x(), z - target.z()) < ARRIVAL_DISTANCE) {
            arrived = true;
            arrivedAtMillis = System.currentTimeMillis();
            HowToGo.LOGGER.info("[HowToGo] arrived at {}", target.name());
            route = Route.empty();
            transitTrip = null;
            return true;
        }
        return false;
    }

    /** True while the arrival banner should be shown. */
    public static boolean showArrival() {
        return arrived && System.currentTimeMillis() - arrivedAtMillis < ARRIVAL_BANNER_MILLIS;
    }

    /** Blocks of the route already covered, following the polyline rather than the straight line. */
    public static double travelled() {
        if (!route.isPresent()) {
            return 0;
        }
        return Math.max(0, route.totalLength() - remainingLength());
    }

    /** Blocks left to travel, measured along the drawn route. */
    public static double remainingLength() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || !route.isPresent()) {
            return 0;
        }
        return route.remainingLength(player.getX(), player.getZ());
    }

    /**
     * Estimated seconds left, at the pace of the part of the route that is still ahead.
     *
     * <p>No base speed is passed in on purpose: the walking constant that used to be applied here
     * would quietly restate every drive and every bus ride as a walk. Nor is a single pace used for
     * the whole route: the road's pieces differ, so the number is read from the route itself -- see
     * {@link Route#remainingSeconds}, which also carries the waiting that is still to come.
     */
    public static double remainingSeconds() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || !route.isPresent()) {
            return 0;
        }
        return route.remainingSeconds(player.getX(), player.getZ());
    }

    /**
     * A turn ahead, with the distance already rebased onto the player.
     *
     * @param distanceAhead blocks from the player to the junction, following the route
     * @param turnDegrees   signed angle, positive being a right turn
     * @param roadName      the road being turned onto, or null when it has none
     * @param namesTheRoad  whether that road is worth naming, which it is not when it is the one the
     *                      route was already on
     */
    public record Instruction(double distanceAhead, double turnDegrees, String roadName,
                              boolean namesTheRoad) {
    }

    /**
     * Which turn is current, and which junctions have already been judged taken.
     *
     * <p>The state machine is a class of its own so that the part that was wrong can be driven
     * outside the game: every reading it needs is a route, a distance along it, a position and a
     * heading. See {@link TurnCursor}, which also carries the constants and the argument for the
     * frontier it keeps.
     */
    private static final TurnCursor turns = new TurnCursor();

    /** At or beyond this turn angle the instruction is a U-turn rather than a turn. */
    private static final double UTURN_DEGREES = 135.0;

    /**
     * How far the travel direction has to be from the route's before they count as going the wrong
     * way, and how far back towards it they have to come before they stop counting.
     *
     * <p>Two angles rather than one, because the reading is a comparison of two noisy vectors: a
     * single value would have the call flickering on and off around the threshold. Between the two
     * the answer is left as it was, which is what hysteresis means here.
     */
    private static final double WRONG_WAY_ENTER_DEGREES = 135.0;
    private static final double WRONG_WAY_EXIT_DEGREES = 100.0;

    /** Ticks a reading has to hold before it is believed, in either direction. */
    private static final int WRONG_WAY_TICKS = 5;

    /**
     * How many segments the walk to the next junction will follow before giving up.
     *
     * <p>A guard against a malformed network rather than a real limit: a road between two forks is a
     * handful of segments, and a walk that has not found a node with three ends by this point is in a
     * loop that never will.
     */
    private static final int WRONG_WAY_MAX_SEGMENTS = 64;

    /**
     * How many ticks in a row the player has been read as going the wrong way, signed.
     *
     * <p>Positive counts ticks of clearly-reversed travel and negative ticks of clearly-forward
     * travel, clamped at {@link #WRONG_WAY_TICKS} either way. A step-up or a knockback can reverse
     * the velocity for one tick, and one tick is not enough to reach the clamp, so noise cannot raise
     * the call; the same in reverse means it cannot drop it either.
     */
    private static int wrongWayRun;
    /** Whether the wrong-way call is currently up. */
    private static boolean wrongWay;
    /** The junction the wrong-way call points at, or NaN when there is none to point at. */
    private static double wrongWayJunctionX = Double.NaN;
    private static double wrongWayJunctionZ = Double.NaN;
    /** Blocks along the road from the player to that junction, or NaN when there is none. */
    private static double wrongWayDistance = Double.NaN;
    /** Whether the road underfoot is one no U-turn can be made on, which changes the wording only. */
    private static boolean wrongWayHighway;
    /**
     * The last direction the player was travelling in while the wrong-way flag was up.
     *
     * <p>Kept so the anchor can still be worked out when they stop: the flag is held while stationary
     * -- dropping it the moment they coast to a halt is the flicker the run of ticks exists to stop --
     * and a call that went blank with it would leave the readout with nothing to point at.
     */
    private static double wrongWayDirectionX;
    private static double wrongWayDirectionZ;

    /**
     * A U-turn call: the junction to turn around at, and how far away it is.
     *
     * @param distanceAhead blocks from the player to that junction, following the road they are on --
     *                      the same quantity the ordinary countdown is, so "now" means the same thing
     *                      for both calls
     * @param junctionX     world x of the junction, which is what identifies the call to a latch --
     *                      the distance changes on every step and cannot
     * @param junctionZ     world z of the junction
     * @param highway       whether the road underfoot is one a U-turn cannot be made on, where the
     *                      call is worded as carrying on to the next junction instead
     */
    public record Uturn(double distanceAhead, double junctionX, double junctionZ, boolean highway) {
    }

    /**
     * The wrong-way U-turn call for right now, or null when there is none.
     *
     * <p>Up when the player is on the route -- inside the tolerance, so the off-route re-plan is not
     * about to fire and rebuild the trip under them -- and travelling roughly opposite to the
     * direction the route runs where they are.
     *
     * <p>The junction it points at is the next one they will reach if they carry on, which is the far
     * end of the road they are on: a chain only continues through pass-through nodes, so the first
     * junction in either direction is one of its two ends. That is deliberately not the player's own
     * position -- turning round where they stand is not what a road gives them, and the junction is
     * the first place the road actually offers.
     */
    public static Uturn wrongWayUturn() {
        if (!wrongWay || Double.isNaN(wrongWayJunctionX) || Double.isNaN(wrongWayDistance)) {
            return null;
        }
        return new Uturn(wrongWayDistance, wrongWayJunctionX, wrongWayJunctionZ, wrongWayHighway);
    }

    /**
     * Whether the player is being read as going the wrong way, whether or not a junction was named.
     *
     * <p>Read by the readouts, because the flag being up is itself an instruction: it must never end
     * in "carry straight on", which is what falling through to the route's next turn did whenever no
     * junction could be named.
     */
    public static boolean wrongWay() {
        return wrongWay;
    }

    /**
     * Keeps the wrong-way reading up to date, once per tick.
     *
     * <p>A tick rather than a frame: this is a judgement about a run of samples, and the hysteresis
     * counts ticks. Counting them from the render path would count each reader separately, and the
     * panel, the map and the voice would each reach the clamp at a different time.
     */
    private static void updateWrongWay(double x, double z) {
        double gap = wrongWayGap(x, z);
        if (!Double.isNaN(gap)) {
            if (gap >= WRONG_WAY_ENTER_DEGREES) {
                wrongWayRun = Math.min(wrongWayRun + 1, WRONG_WAY_TICKS);
            } else if (gap <= WRONG_WAY_EXIT_DEGREES) {
                wrongWayRun = Math.max(wrongWayRun - 1, -WRONG_WAY_TICKS);
            }
            // Between the two angles the reading stands: that band is the whole point of having two.
        }
        // Standing still changes nothing either way. The velocity says nothing about which way the
        // player is going, and dropping the call the moment they coast to a halt would be a flicker
        // of exactly the kind the run of ticks exists to prevent.
        wrongWay = wrongWayRun >= WRONG_WAY_TICKS;
        if (!wrongWay) {
            wrongWayJunctionX = Double.NaN;
            wrongWayJunctionZ = Double.NaN;
            return;
        }
        resolveWrongWayJunction(x, z);
    }

    /**
     * How far the direction the player is travelling is from the direction the route runs, in
     * degrees, or NaN when there is nothing to compare -- no route, no player, or no movement.
     *
     * <p>Off the route the answer is zero rather than NaN: the re-plan is what deals with that, and a
     * player who has left the route is not travelling against it so much as no longer on it.
     *
     * <h2>Where a route passes over the same ground twice</h2>
     * The reading is taken from the nearest point of the route, and a route that doubles back makes
     * that point ambiguous: a road that loops back, a divided highway whose carriageways are a lane
     * apart, a destination on the piece of road the trip set off along, or a connector lying along
     * the road it joins all put two opposite directions within a few blocks of each other. The scan
     * takes the earlier of the two, and the direction it reports is then the direction of the pass
     * the player has already made -- exactly reversed -- so a player driving correctly is read as
     * having turned round and is called a U-turn.
     *
     * <p>What settles it is the player rather than the route: a route has no way to tell its two
     * passes apart, but a player driving along it is plainly following one of them. So when the
     * nearest direction says the player is going backwards, every other direction the route has here
     * is offered the chance to disagree -- and if one of them agrees with the way the player is
     * actually travelling, the route is being followed and the reading is stood down. A player who
     * has genuinely turned round agrees with none of them, and keeps the reading.
     *
     * <p>See {@link Route#bearingsNear}, which is the other directions.
     */
    private static double wrongWayGap(double x, double z) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || !route.isPresent()) {
            // Nothing to be against: the trip is over, or has not been planned yet.
            return 0;
        }
        // Asked before anything that costs a walk of the route: a standing player's velocity says
        // nothing about which way they are going, so the reading is held and no work is done for it.
        double travel = MovementState.travelBearing(player);
        if (Double.isNaN(travel)) {
            return Double.NaN;
        }
        if (!route.isOnRoute(x, z)) {
            return 0;
        }
        double alongRoute = route.bearingAt(x, z);
        double nearest = Double.isNaN(alongRoute) ? 0 : MovementState.bearingGap(travel, alongRoute);
        if (nearest < WRONG_WAY_ENTER_DEGREES) {
            return nearest;
        }
        // The nearest direction says the player is going backwards. Only over the ground the route
        // doubles back on is that worth checking, so the extra walk is paid on the reading that is
        // about to call a U-turn rather than on every step of every trip.
        double best = nearest;
        for (double other : route.bearingsNear(x, z, wrongWayReadingRadius())) {
            best = Math.min(best, MovementState.bearingGap(travel, other));
        }
        return best;
    }

    /**
     * How far from the player the route's other directions still count as being "here".
     *
     * <p>Wide enough to take in the opposite carriageway of a divided highway and the return pass of
     * a loop, which is what the reading exists to disambiguate. Tied to the widest on-road tolerance
     * the mod allows, so any road the mod would still call the player's own is a road whose direction
     * they are judged by -- and no wider, because a road beyond that is one the player is not on.
     */
    private static double wrongWayReadingRadius() {
        double widest = 0;
        for (RoadClass roadClass : RoadClass.values()) {
            widest = Math.max(widest, RoadConfig.onRoadTolerance(roadClass));
        }
        return widest;
    }

    /**
     * Works out which junction the wrong-way call points at, or drops the call.
     *
     * <p>The anchor is the first place ahead where the road stops being one road: a node three or
     * more segments meet at, which is where the player can actually turn round, or a node where the
     * road simply ends. The walk crosses class and name changes on the way, because those break the
     * road data without breaking the road -- they are not junctions, and stopping at one is what made
     * the call fire at a bend in the middle of a road the player was already on.
     *
     * <p>The distance is measured **along the road**, not in a straight line. With a real fork as the
     * anchor the road may bend on the way there, and the call is "you can turn round in N", which is
     * about how far the player has to travel: a straight line would under-report it on exactly the
     * roads where the difference matters, and the player would reach the fork later than told.
     *
     * <p>The call is dropped, rather than pointed at the player's own feet or a guess, when there is
     * no road under them, when they are not travelling along the one they are on, or when the walk
     * cannot finish. Leaving the ordinary readout standing is the honest answer to all three.
     */
    private static void resolveWrongWayJunction(double x, double z) {
        LocalPlayer player = Minecraft.getInstance().player;
        RoadNetwork network = RoadStore.get();
        wrongWayHighway = currentRoadClass(mode()) == RoadClass.HIGHWAY;
        RoadSegment segment = network.segment(underfootSegmentId);
        if (segment == null) {
            failWrongWay();
            return;
        }
        double vx = player == null ? 0 : player.getDeltaMovement().x;
        double vz = player == null ? 0 : player.getDeltaMovement().z;
        if (Math.hypot(vx, vz) >= MovementState.MOVING_SPEED) {
            // Which way they are going, kept for while they are not: the flag is held when they stop,
            // so the anchor has to keep being worked out rather than going blank with them.
            wrongWayDirectionX = vx;
            wrongWayDirectionZ = vz;
        } else {
            vx = wrongWayDirectionX;
            vz = wrongWayDirectionZ;
            if (Math.hypot(vx, vz) < MovementState.MOVING_SPEED) {
                // Nothing has moved since the call came up, so there is no direction to look along.
                failWrongWay();
                return;
            }
        }
        RoadNode from = network.node(segment.fromNode());
        RoadNode to = network.node(segment.toNode());
        if (from == null || to == null) {
            failWrongWay();
            return;
        }
        double towardTo = (to.x() - x) * vx + (to.z() - z) * vz;
        double towardFrom = (from.x() - x) * vx + (from.z() - z) * vz;
        if (towardTo <= 0 && towardFrom <= 0) {
            // Neither end of the road is in front of them, so which junction they will reach cannot
            // be said. A player crossing a road rather than travelling along it lands here.
            failWrongWay();
            return;
        }
        boolean exitAtTo = towardTo >= towardFrom;
        int exitNode = exitAtTo ? segment.toNode() : segment.fromNode();
        double travelled = distanceToSegmentEnd(segment, x, z, exitAtTo);

        Map<Integer, Integer> degrees = RoadChains.degrees(network);
        for (int guard = 0; guard < WRONG_WAY_MAX_SEGMENTS; guard++) {
            int degree = degrees.getOrDefault(exitNode, 0);
            if (degree != 2) {
                RoadNode junction = network.node(exitNode);
                if (junction == null) {
                    failWrongWay();
                    return;
                }
                wrongWayJunctionX = junction.x();
                wrongWayJunctionZ = junction.z();
                wrongWayDistance = travelled;
                return;
            }
            RoadSegment next = otherSegmentAt(network, exitNode, segment.id());
            if (next == null) {
                // A node with two segment ends that are both this segment, so nothing continues.
                failWrongWay();
                return;
            }
            int nextExit = next.fromNode() == exitNode ? next.toNode() : next.fromNode();
            if (nextExit == RoadSegment.NO_NODE) {
                failWrongWay();
                return;
            }
            travelled += next.length();
            segment = next;
            exitNode = nextExit;
        }
        failWrongWay();
    }

    /**
     * Takes the call down when no junction could be named: the anchor goes, so
     * {@link #wrongWayUturn()} answers null and the readouts fall back to the plain U-turn, which is
     * the honest instruction for a player going the wrong way when there is nowhere to point at.
     */
    private static void failWrongWay() {
        wrongWayJunctionX = Double.NaN;
        wrongWayJunctionZ = Double.NaN;
        wrongWayDistance = Double.NaN;
    }

    /**
     * The other segment meeting a node, or null when the only one there is {@code excludeId}.
     *
     * <p>Answered from the cached adjacency reading rather than by scanning every segment in the
     * network, which is what this did -- once per step of a walk that takes up to sixty-four of them,
     * once per tick, while the player is going the wrong way. The order is the network's own, so the
     * answer is the one the scan gave. See {@link RoadChains#segmentsAt}.
     */
    private static RoadSegment otherSegmentAt(RoadNetwork network, int nodeId, int excludeId) {
        for (int id : RoadChains.segmentsAt(network, nodeId)) {
            if (id != excludeId) {
                return network.segment(id);
            }
        }
        return null;
    }

    /**
     * Distance from a point's projection on a segment to one of its end nodes, in blocks, following
     * the segment's own vertices.
     *
     * @param towardToNode true for the node the last vertex is at, false for the first
     */
    private static double distanceToSegmentEnd(RoadSegment segment, double x, double z,
                                               boolean towardToNode) {
        int count = segment.vertexCount();
        if (count < 2) {
            return 0;
        }
        double bestDistanceSq = Double.MAX_VALUE;
        int bestEdge = 1;
        double bestT = 0;
        for (int i = 1; i < count; i++) {
            double ax = segment.x(i - 1);
            double az = segment.z(i - 1);
            double ex = segment.x(i) - ax;
            double ez = segment.z(i) - az;
            double lengthSq = ex * ex + ez * ez;
            double t = lengthSq < 1.0E-9 ? 0
                    : Math.max(0, Math.min(1, ((x - ax) * ex + (z - az) * ez) / lengthSq));
            double px = ax + ex * t - x;
            double pz = az + ez * t - z;
            double distanceSq = px * px + pz * pz;
            if (distanceSq < bestDistanceSq) {
                bestDistanceSq = distanceSq;
                bestEdge = i;
                bestT = t;
            }
        }
        double travelled = segmentEdgeLength(segment, bestEdge) * (towardToNode ? bestT : 1 - bestT);
        if (towardToNode) {
            for (int i = bestEdge + 1; i < count; i++) {
                travelled += segmentEdgeLength(segment, i);
            }
        } else {
            for (int i = 1; i < bestEdge; i++) {
                travelled += segmentEdgeLength(segment, i);
            }
        }
        return travelled;
    }

    /** Length of the sub-edge between two consecutive vertices of a segment. */
    private static double segmentEdgeLength(RoadSegment segment, int edgeIndex) {
        return Math.hypot(segment.x(edgeIndex) - segment.x(edgeIndex - 1),
                segment.z(edgeIndex) - segment.z(edgeIndex - 1));
    }

    /** Forgets the wrong-way reading entirely: it belongs to a trip, not to the session. */
    private static void clearWrongWay() {
        wrongWayRun = 0;
        wrongWay = false;
        wrongWayJunctionX = Double.NaN;
        wrongWayJunctionZ = Double.NaN;
        wrongWayDistance = Double.NaN;
        wrongWayHighway = false;
        wrongWayDirectionX = 0;
        wrongWayDirectionZ = 0;
    }

    // ------------------------------------------------------- road class changes

    /**
     * Counts how many times the player has moved onto a different kind of road, and remembers which.
     *
     * <p>A counter rather than a flag so the reader can tell "there is a notice to speak" from "I have
     * already spoken it" without either side having to clear anything: the voice remembers the count
     * it last saw and speaks when it moves. Nothing is reset by the trip either, so a change that
     * happens to fall on the same tick as a change of destination cannot be replayed.
     */
    private static int classChangeCount;
    /** The class most recently moved onto, or null when none has been seen yet. */
    private static RoadClass classEntered;
    /** The class underfoot on the previous tick, or null while off any road. */
    private static RoadClass classUnderfoot;

    /**
     * Watches the road class underfoot for a change, once per tick.
     *
     * <p>Off the road is not a change: stepping off and back on to the same surface is the same road
     * as far as the player is concerned, and a notice for it would be wrong. The player also has to
     * be moving -- a player standing on a class boundary would otherwise be told about a change they
     * cannot see, every time the road data's nearest segment happened to swap under them.
     */
    private static void updateRoadClass() {
        RoadClass now = currentRoadClass(mode());
        if (now == null || now == classUnderfoot) {
            return;
        }
        LocalPlayer player = Minecraft.getInstance().player;
        if (classUnderfoot != null && player != null && isMoving(player)) {
            classChangeCount++;
            classEntered = now;
        }
        classUnderfoot = now;
    }

    /** Whether the player is moving at all, by the threshold the movement state uses. */
    private static boolean isMoving(LocalPlayer player) {
        Vec3 delta = player.getDeltaMovement();
        return Math.hypot(delta.x, delta.z) >= MovementState.MOVING_SPEED;
    }

    /** How many road class changes have been seen this session. */
    public static int classChangeCount() {
        return classChangeCount;
    }

    /** The class most recently moved onto, or null when there has not been one. */
    public static RoadClass classEntered() {
        return classEntered;
    }

    /** Localised name of a road class, the same one the picker's avoid list uses. */
    public static String roadClassLabel(RoadClass roadClass) {
        if (roadClass == null) {
            return "";
        }
        return net.minecraft.network.chat.Component.translatable(
                "screen.howtogo.road_class." + roadClass.name().toLowerCase(Locale.ROOT)).getString();
    }

    /**
     * Localised name of a storey: the surface for zero, an overpass above it and an underpass below.
     *
     * <p>A whole phrase rather than a number with a sign, because the number is a thing the player
     * typed into a field and the phrase is what the road is: "高架2层" is a place in the world and
     * "+2" is a datum. The two are the same value; only one of them belongs on the HUD.
     */
    public static String roadLayerLabel(int layer) {
        if (layer == 0) {
            return Component.translatable("hud.howtogo.layer.surface").getString();
        }
        return layer > 0
                ? Component.translatable("hud.howtogo.layer.overpass", layer).getString()
                : Component.translatable("hud.howtogo.layer.underpass", -layer).getString();
    }

    /**
     * The next turn ahead, or null when nothing is coming up.
     *
     * <p>{@link Route#maneuvers()} reports each turn's position along the whole route. That is not
     * the distance still to travel -- after a re-plan the player is part way along it -- so the
     * value is rebased onto the player here, once, rather than at each place that displays it.
     *
     * <p>Being level with a junction by distance is not the same as having taken it. A player who
     * walks straight past a corner is still level with it, and calling the turn done there would
     * swap the instruction for the next one while they are standing at the wrong road. So a
     * manoeuvre that is behind by distance is only passed once it has been judged taken; until then
     * it stays current, at a distance of zero, which the readout and the announcement both show as
     * "now".
     *
     * <p>Which one that is, and whether it has been taken, is {@link TurnCursor}'s: the verdict has
     * to be remembered, and remembering it wrongly is what showed a junction the player had already
     * left behind as the next instruction.
     */
    public static Instruction nextManeuver() {
        if (!route.isPresent()) {
            return null;
        }
        double travelled = travelled();
        LocalPlayer player = Minecraft.getInstance().player;
        double heading = player == null ? Double.NaN : MovementState.facingBearing(player);
        double x = player == null ? 0 : player.getX();
        double z = player == null ? 0 : player.getZ();
        int index = turns.nextIndex(route, travelled, heading, x, z);
        if (index < 0) {
            return null;
        }
        Route.Maneuver maneuver = route.maneuvers().get(index);
        return new Instruction(maneuver.distanceFromStart() - travelled,
                maneuver.turnDegrees(), maneuver.roadName(), maneuver.namesTheRoad());
    }

    /**
     * What to call a road that has no name.
     *
     * <p>Say it rather than dropping the name from the sentence: "turn right" leaves the player
     * unsure whether the tool knows the road at all.
     */
    public static String unnamedRoad() {
        return net.minecraft.network.chat.Component
                .translatable("hud.howtogo.unnamed_road").getString();
    }

    /**
     * Name of the road the player is on, the placeholder for an unnamed one, or null when they are
     * not on any road.
     */
    public static String currentRoadName() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || !route.isPresent()) {
            return null;
        }
        double x = player.getX();
        double z = player.getZ();
        if (!route.isOnRoute(x, z)) {
            return null;
        }
        String name = route.currentRoadName(x, z);
        return name != null ? name : unnamedRoad();
    }

    /**
     * Whether the player has strayed off the route.
     *
     * <p>The tolerance comes from the road class at the nearest point of the route, not a global
     * constant: a 7-block-wide highway forgives far more drift than a 3-block footpath, and the
     * values are configurable per class.
     */
    private static boolean isOffRoute(double x, double z) {
        if (!route.isPresent()) {
            return false;
        }
        if (route.distanceTo(x, z) <= route.toleranceNear(x, z)) {
            return false;
        }
        // A station is a place, not a point: standing on the platform, in the forecourt or on the
        // footbridge of a stop the journey calls at is being where the journey asked, however far that
        // is from the track's centreline. Without this, every station the route serves re-planned the
        // trip out from under the player the moment they stepped off the line's own tolerance.
        return !atAJourneyStop(x, z);
    }

    /**
     * Whether the position is within the station snap of any stop the journey calls at.
     *
     * <p>Every stop of every ride, so the several stops that make up an interchange are all covered:
     * an interchange is one place assembled out of two or more lines' stops, and a player standing at
     * the far platform of it is at the station just as much as at the near one.
     */
    private static boolean atAJourneyStop(double x, double z) {
        if (transitTrip == null) {
            return false;
        }
        double snap = RoadConfig.stationSnapBlocks();
        double snapSq = snap * snap;
        for (Trip.Ride ride : transitTrip.rides()) {
            for (Trip.RideStop stop : ride.stops()) {
                double dx = stop.x() - x;
                double dz = stop.z() - z;
                if (dx * dx + dz * dz <= snapSq) {
                    return true;
                }
            }
        }
        return false;
    }

    /** True when the player has currently strayed further from the line than the tolerance. */
    public static boolean isOffRoute() {
        LocalPlayer player = Minecraft.getInstance().player;
        return player != null && isOffRoute(player.getX(), player.getZ());
    }

    // ------------------------------------------------- announcement distances

    /**
     * How far before a junction the two calls are made, in blocks, for one way of travelling on one
     * kind of road.
     *
     * @param lead how far out the approach call is spoken
     * @param now  how far out the junction counts as happening now
     */
    private record CallDistances(double lead, double now) {
    }

    /**
     * The block and mode the cached road class underfoot was found for.
     *
     * <p>See {@link #currentRoadClass} for what the cache is for and what invalidates it.
     */
    private static int underfootBlockX = Integer.MIN_VALUE;
    private static int underfootBlockZ = Integer.MIN_VALUE;
    private static TravelMode underfootMode;
    private static RoadClass underfootClass;
    /**
     * The segment that class was read from, remembered with it.
     *
     * <p>Carried by the same cache rather than looked up again: the scan that finds the nearest
     * class already knows which segment it found it on, and the wrong-way call needs the segment to
     * walk the road for a junction.
     */
    private static int underfootSegmentId = RoadSegment.NO_SEGMENT;

    /*
     * The distances are listed rather than computed from the pace, because they are tuning values
     * rather than physical ones, and the longest of them could not be reached from the pace at all:
     * a minecart calling its junction 300 blocks out is thirty-seven seconds of travel at eight
     * blocks a second, three times the twelve the formula allows, and the highway's 300 is no
     * different. Deriving them would have meant inflating TravelMode's rail and highway speeds, and
     * those same numbers times every leg are the ETA -- so the ETA would have been wrong to make a
     * spoken warning read better.
     *
     * The approach distances are round numbers rather than measured ones, chosen to be held in the
     * head while travelling: "in 300 m" is a sentence, "in 283 m" is a measurement.
     *
     * Non-inversion, now < lead, holds row by row (10<50, 20<100, 30<300, 20<100, 40<300, 60<500),
     * the tightest being the walker's 50 against 10. It holds on the fallback path too: the
     * fallback's lead is at least TURN_LEAD_MIN_DISTANCE (60) and its now at most
     * TURN_NOW_MAX_DISTANCE (60), and a now of 60 needs a pace of 30, where the lead is
     * 12 x 30 = 360. So no row and no fallback can make the two calls swap, and any new row only
     * has to keep its own pair in order.
     */
    /** A walker: one distance for every surface they can use, footpath and highway alike. */
    private static final CallDistances WALK_DISTANCES = new CallDistances(50, 10);
    private static final CallDistances DRIVE_ROAD = new CallDistances(100, 20);
    /** Further out than an ordinary road: at highway speed the junction arrives much sooner. */
    private static final CallDistances DRIVE_HIGHWAY = new CallDistances(300, 30);
    /** A waterway is announced like an ordinary road, which is the speed a boat makes on it. */
    private static final CallDistances TRANSIT_WATER = new CallDistances(100, 20);
    private static final CallDistances TRANSIT_RAIL = new CallDistances(300, 40);
    private static final CallDistances TRANSIT_ICE = new CallDistances(500, 60);

    /** Fallback tune: seconds of travel a turn is announced ahead by. */
    private static final double TURN_LEAD_SECONDS = 12.0;
    /** Shortest distance a turn is announced from, so a walker is warned with room to act. */
    private static final double TURN_LEAD_MIN_DISTANCE = 60.0;
    /** Longest distance a turn is announced from, so the fastest line does not call across a map. */
    private static final double TURN_LEAD_MAX_DISTANCE = 480.0;
    /** Fallback tune: seconds of travel that count as a turn happening now. */
    private static final double TURN_NOW_SECONDS = 2.0;
    /** Floor on the "now" distance: what a walker gets, so walking behaviour is unchanged. */
    private static final double TURN_NOW_MIN_DISTANCE = 10.0;
    /**
     * Ceiling on the "now" distance on the fallback path.
     *
     * <p>Equal to {@link #TURN_LEAD_MIN_DISTANCE} rather than above it, which is half of why the
     * two calls cannot invert; the rest of the argument is on the table above.
     */
    private static final double TURN_NOW_MAX_DISTANCE = 60.0;

    /**
     * Distance ahead of a turn that it is announced from, in blocks, for the combination underfoot.
     *
     * <p>Never below {@link #turnNowDistance()}: the approach call always comes first.
     */
    public static double turnLeadDistance() {
        return callDistances().lead();
    }

    /**
     * Distance at which a turn counts as happening now, in blocks, for the combination underfoot.
     *
     * <p>Every reader of this -- the panel's readout, the map's readout and the spoken announcement
     * -- makes the "now or in N metres" decision from the same number, so the voice cannot call a
     * turn before the text does.
     */
    public static double turnNowDistance() {
        return callDistances().now();
    }

    /**
     * The listed distances for the combination the player is on, or the computed fallback.
     *
     * <p>One road lookup for both numbers: the panel and the voice ask for them separately, and
     * would otherwise scan the network twice for the same answer.
     */
    private static CallDistances callDistances() {
        TravelMode active = mode();
        RoadClass underfoot = currentRoadClass(active);
        if (underfoot != null) {
            CallDistances listed = listed(active, underfoot);
            if (listed != null) {
                return listed;
            }
        }
        // Not a combination the table names: no road underfoot at all -- off the network, on one of
        // the walked connectors, or between roads after a re-plan -- or a kind of road this mode
        // does not travel on. Computed from the pace instead, because the player is then moving at
        // a speed the table cannot name, and calling a turn late is the worse of the two mistakes.
        //
        // Deliberately left unrounded rather than matched to the table, so the two paths meet at an
        // angle: a walker on a footpath is called at 50 blocks and one crossing open country at 67.
        // Accepted rather than reconciled, since rounding this would mean inventing a road class for
        // ground that has none.
        double pace = paceUnderfoot(active, underfoot);
        return new CallDistances(
                Math.max(TURN_LEAD_MIN_DISTANCE,
                        Math.min(TURN_LEAD_MAX_DISTANCE, pace * TURN_LEAD_SECONDS)),
                Math.max(TURN_NOW_MIN_DISTANCE,
                        Math.min(TURN_NOW_MAX_DISTANCE, pace * TURN_NOW_SECONDS)));
    }

    /**
     * The table entry for a way of travelling on a kind of road, or null when it has none.
     *
     * <p>A switch rather than a map: the compiler then insists every mode is answered for, and each
     * row's two numbers sit on one line where the order between them can be read at a glance.
     */
    private static CallDistances listed(TravelMode active, RoadClass roadClass) {
        return switch (active) {
            case WALK -> switch (roadClass) {
                case PATH, ICE, ROAD, HIGHWAY -> WALK_DISTANCES;
                default -> null;
            };
            case DRIVE -> switch (roadClass) {
                case ROAD -> DRIVE_ROAD;
                case HIGHWAY -> DRIVE_HIGHWAY;
                default -> null;
            };
            case TRANSIT -> switch (roadClass) {
                case WATER -> TRANSIT_WATER;
                case RAIL -> TRANSIT_RAIL;
                case ICE -> TRANSIT_ICE;
                default -> null;
            };
        };
    }

    /**
     * Pace in blocks per second the player is actually making, given the road already found underfoot.
     *
     * <h2>Why this is the walker's pace far more often than it looks</h2>
     * Only reached when the table above names no combination: either there is no road underfoot at all --
     * off the network, on one of the walked connectors, or between roads after a re-plan -- or there is
     * one the mode cannot travel on. Both of those are the player being <em>on foot</em>: the first and
     * last hop of every trip is walked whatever the mode, and a rider walking along the road to their
     * station is the commonest journey this mod plans.
     *
     * <p>It used to return the mode's fastest class instead, on the argument that the mode's best pace is
     * all that is known. For public transport that is the ice-boat pace, forty blocks a second, and the
     * result was a turn called "now" from sixty blocks away and announced from four hundred and eighty --
     * to a player walking at five. What the player is doing is known: they are on foot, so the pace is the
     * walker's, and on a named surface it is the walker's pace on that surface.
     */
    static double paceUnderfoot(TravelMode active, RoadClass underfoot) {
        if (underfoot != null && active.speedOn(underfoot) > 0) {
            // Travelling by this mode on a surface the table happens not to name: the mode's own pace is
            // the answer, and this is the only case where it is.
            return active.speedOn(underfoot);
        }
        double walkOnSurface = underfoot == null ? 0 : TravelMode.WALK.speedOn(underfoot);
        if (walkOnSurface > 0) {
            return walkOnSurface;
        }
        double fastestWalk = 0;
        for (RoadClass roadClass : RoadClass.values()) {
            fastestWalk = Math.max(fastestWalk, TravelMode.WALK.speedOn(roadClass));
        }
        return fastestWalk;
    }

    /**
     * Class of the road the player is standing on, or null when they are not on one.
     *
     * <p>Read from the road network rather than from the route: the route carries each road's name
     * and tolerance, but not its class, and the class is what the pace is looked up by. "On" is the
     * same rule the router uses -- within the class's own on-road tolerance -- and a class the mode
     * has no pace on does not count, so a driver stopped across a rail line is beside the road
     * rather than on it.
     *
     * <h2>Why the answer is remembered</h2>
     * The scan below walks every segment and every vertex of the network, and the three readers of
     * the result -- the spoken announcements, the panel and the map readout -- ask for it several
     * times per frame. Near a junction, where the class underfoot actually changes, that was the
     * heaviest per-frame work in the mod, and it spiked exactly when the player noticed.
     *
     * <p>The answer depends on nothing but the player's position, the travel mode and the road data,
     * so it is cached against the block the player is standing in and the mode in force. Movement
     * within one block is what most frames are, so the scan now runs once per block crossed rather
     * than once per caller per frame. Editing a road while standing still is the one case that can
     * leave the copy stale, and it corrects itself on the next step; the alternative would be to
     * invalidate from the editor, which would mean a second thing to keep in step for a case the
     * player cannot see.
     */
    private static RoadClass currentRoadClass(TravelMode active) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return null;
        }
        double x = player.getX();
        double z = player.getZ();
        int blockX = Mth.floor(x);
        int blockZ = Mth.floor(z);
        if (blockX == underfootBlockX && blockZ == underfootBlockZ && active == underfootMode) {
            return underfootClass;
        }
        RoadClass found = scanForRoadClass(active, x, z);
        underfootBlockX = blockX;
        underfootBlockZ = blockZ;
        underfootMode = active;
        underfootClass = found;
        return found;
    }

    /** The scan behind {@link #currentRoadClass}: nearest usable class within its own tolerance. */
    private static RoadClass scanForRoadClass(TravelMode active, double x, double z) {
        RoadClass nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        underfootSegmentId = RoadSegment.NO_SEGMENT;
        // The live view rather than a snapshot: this still runs several times a second, and copying
        // every segment each time would be the expensive part of it.
        for (RoadSegment segment : RoadStore.get().segments()) {
            if (active.speedOn(segment.roadClass()) <= 0) {
                continue;
            }
            double distance = distanceToRoad(segment, x, z);
            if (distance > RoadConfig.onRoadTolerance(segment.roadClass())
                    || distance >= nearestDistance) {
                continue;
            }
            nearestDistance = distance;
            nearest = segment.roadClass();
            underfootSegmentId = segment.id();
        }

        // Nothing drawn underfoot: the machine-read rails count too, so riding one is called at the pace
        // a train -- or a boat -- makes instead of at the pace the mode can do its best on. Read only as
        // a fallback, which is what keeps a drawn road beside a track deciding the underfoot reading
        // exactly as it always did. Both of a mark's classes are read, and each with its own tolerance,
        // because MTR's marks are rail for a train and water for a boat: asking only about rail would
        // leave a boat ride reading as though the player were walking on nothing. Both layers too, since
        // MTR's marks are not drawn as roads but are ridden all the same.
        if (nearest == null && (active.speedOn(RoadClass.RAIL) > 0
                || active.speedOn(RoadClass.WATER) > 0)) {
            for (RoadSegment segment : RailLayers.all()) {
                if (active.speedOn(segment.roadClass()) <= 0) {
                    continue;
                }
                double distance = distanceToRoad(segment, x, z);
                if (distance > RoadConfig.onRoadTolerance(segment.roadClass())
                        || distance >= nearestDistance) {
                    continue;
                }
                nearestDistance = distance;
                nearest = segment.roadClass();
                // A rail id from the layer rather than from the saved network. The wrong-way anchor
                // resolves its segment against the saved network and finds nothing, which is the
                // same answer it gives when nothing is underfoot at all: the plain U-turn call.
                underfootSegmentId = segment.id();
            }
        }
        return nearest;
    }

    /** Perpendicular distance from a point to a road's polyline, in blocks. */
    private static double distanceToRoad(RoadSegment segment, double x, double z) {
        double best = Double.MAX_VALUE;
        for (int i = 1; i < segment.vertexCount(); i++) {
            double ax = segment.x(i - 1);
            double az = segment.z(i - 1);
            double ex = segment.x(i) - ax;
            double ez = segment.z(i) - az;
            double lengthSq = ex * ex + ez * ez;
            double t = lengthSq < 1.0E-9 ? 0
                    : Math.max(0, Math.min(1, ((x - ax) * ex + (z - az) * ez) / lengthSq));
            best = Math.min(best, Math.hypot(ax + ex * t - x, az + ez * t - z));
        }
        return best;
    }

    /**
     * Localised instruction for the next manoeuvre, e.g. "turn right onto Main Street in 120 m".
     *
     * @param maneuver a manoeuvre from {@link #nextManeuver()}, or null when the road runs straight
     *                 on for now
     */
    public static String maneuverInstruction(Instruction maneuver) {
        if (maneuver == null) {
            return net.minecraft.network.chat.Component
                    .translatable("hud.howtogo.hud_straight").getString();
        }
        return maneuverSentence(maneuver, maneuver.distanceAhead() <= turnNowDistance());
    }

    /**
     * The sentence for a turn the route asks for, for the map readout and the voice alike.
     *
     * <h2>The distance rule</h2>
     * The number a readout shows and the words beside it always come from the same object, so they
     * can only ever be about the same point on the route: the distance counts to the junction the
     * instruction is about, and to no other. The measured point is the next decision point ahead --
     * the turn the route asks for, or the junction a wrong-way reading points at -- and in the normal
     * case that is a real fork, a node where the road actually branches. Where the route genuinely
     * bends before it reaches that fork, the bend is a decision point of its own and the number
     * counts to the bend: the number follows the sentence rather than contradicting it. There is
     * deliberately no "distance to the next junction" shown beside an instruction about something
     * else, which would tell the player to turn in four hundred metres at a corner two hundred away.
     *
     * <p>The remaining line is a different figure and stays as it is: it counts what is left to the
     * destination, which is not what the next decision is.
     *
     * <p>A turn that doubles back is not worded as a turn onto a road: there is no road being
     * entered, the player is going back the way they came. On a highway it is not worded as a turn
     * at all, because there is nowhere to turn, so the same junction is called as carrying on to it.
     *
     * @param now whether the junction is close enough to act on, which drops the distance
     */
    public static String maneuverSentence(Instruction maneuver, boolean now) {
        if (isUturn(maneuver.turnDegrees())) {
            return uturnSentence(maneuver.distanceAhead(), now, onHighway());
        }
        String turn = turnPhrase(maneuver.turnDegrees());
        // Naming the road the player is already on tells them nothing they do not know, and reads as
        // the tool having lost track of where they are. The turn is still called; the name is not.
        if (!maneuver.namesTheRoad()) {
            return now
                    ? net.minecraft.network.chat.Component
                            .translatable("hud.howtogo.hud_turn_now", turn).getString()
                    : net.minecraft.network.chat.Component
                            .translatable("hud.howtogo.hud_turn",
                                    Route.formatDistance(maneuver.distanceAhead()), turn)
                            .getString().trim();
        }
        // Always name the road being entered, falling back to the placeholder, so the sentence
        // never quietly loses its destination.
        String road = maneuver.roadName() != null && !maneuver.roadName().isBlank()
                ? maneuver.roadName()
                : unnamedRoad();
        if (now) {
            return net.minecraft.network.chat.Component
                    .translatable("hud.howtogo.hud_turn_now_named", turn, road).getString();
        }
        return net.minecraft.network.chat.Component.translatable("hud.howtogo.hud_turn_named",
                Route.formatDistance(maneuver.distanceAhead()), turn, road).getString().trim();
    }

    /**
     * The sentence for the guidance in force, for the map readout.
     *
     * <p>The wrong-way call comes first: a player travelling against the route has no use for the
     * turn the route was going to give them next, and turning round is what the road in front of
     * them allows. If the wrong-way reading is up but no junction could be named, the plain U-turn
     * is said with no junction and no distance -- never the route's next turn, and never "carry
     * straight on", which is the one answer that is actively wrong for a player going the wrong way.
     */
    public static String instructionText() {
        TransitStep transit = transitStep();
        if (transit != null) {
            return transitSentence(transit);
        }
        Uturn uturn = wrongWayUturn();
        if (uturn != null) {
            return uturnSentence(uturn.distanceAhead(),
                    uturn.distanceAhead() <= turnNowDistance(), uturn.highway());
        }
        if (wrongWay) {
            // The "now" form is the distance-free one, which is what is wanted here: there is no
            // junction to count to.
            return uturnSentence(Double.NaN, true, onHighway());
        }
        return maneuverInstruction(nextManeuver());
    }

    /**
     * Whether the station-style transit guidance is in force at all: the player has asked for it, the
     * route being navigated is a public transport journey, and that journey rides something.
     *
     * <p>Only the riding is governed by it. A journey is walked to its first stop and away from its
     * last, and those legs are ordinary walking -- turns and all -- because that is what they are. What
     * the switch changes is what is said <em>while there is a line to be on</em>.
     */
    public static boolean transitGuidance() {
        return RoutePreferenceStore.transitBoardOnly() && transitPlanInForce();
    }

    /**
     * Whether there is a public transport journey for the transit guidance to be about at all.
     *
     * <p>Separate from {@link #transitGuidance()} because the switch is a question about what to show
     * and this is a question about what there is: the reading itself is the same either way, which is
     * what lets the in-game self-test ask "what would this journey say" without depending on how the
     * player has their readout configured.
     */
    private static boolean transitPlanInForce() {
        return route.isPresent() && transitTrip != null && transitTrip.ridesAnything();
    }

    /**
     * What the transit guidance is about at this moment.
     */
    public enum TransitCue {

        /** Walking to a line's first stop of this boarding: say which line and which way. */
        BOARD,

        /** On board, between two stops: say which stop is coming and what is left. */
        RIDE,

        /** On board and nearing the stop being left at: say it is time to get ready. */
        ALIGHT,

        /** Standing at the stop this ride is left at: get off here. */
        ARRIVE
    }

    /**
     * One thing the transit guidance has to say: which line and direction are in force, the stations
     * being named, the stop being run to, and what is left of the ride.
     *
     * @param cue            what kind of moment this is
     * @param transfer       whether the journey changes lines at the stop this cue names, in which case
     *                       {@code line} and {@code terminus} are the line being changed <em>onto</em>
     *                       rather than the one being ridden
     * @param line           the line ridden, or the one about to be boarded or changed to
     * @param terminus       the stop at the far end of that line in the direction of travel
     * @param station        the stop this cue is about: the one boarded at, or the one left at
     * @param reached        the last stop the ride has called at, which is where the vehicle is now
     * @param next           the stop being run to, or null when there is none
     * @param stopsRemaining how many stops are left before the one this ride is left at
     * @param distanceAhead  blocks along the journey to the point this cue is about
     * @param rideIndex      which ride of the journey this is, or -1 when it has not begun
     * @param stopIndex      which stop of that ride the reading is at, so a repeat can be told from a
     *                       new stop
     */
    public record TransitStep(TransitCue cue, boolean transfer, String line, String terminus,
                              String station, String reached, String next, int stopsRemaining,
                              double distanceAhead, int rideIndex, int stopIndex) {
    }

    /** What the transit guidance is saying now, or null when the ordinary guidance is in force. */
    public static TransitStep transitStep() {
        if (!transitGuidance()) {
            return null;
        }
        LocalPlayer player = Minecraft.getInstance().player;
        return transitStepAt(player == null ? Double.NaN : player.getX(),
                player == null ? Double.NaN : player.getZ(), travelled());
    }

    /**
     * The same reading, taken at a position and a distance along the route given rather than at the
     * player's own, and without consulting the readout switch.
     *
     * <p>The seam exists so that the guidance can be read without a player having to travel: the
     * in-game self-test walks a planned ride from end to end and checks the sequence of things it would
     * have said, which is the only way to test what a passenger hears short of riding the whole line by
     * hand. Everything the reading is made of is passed in, so a caller cannot accidentally read half
     * of it from the live player and half from the simulation.
     *
     * @param x         where the player would be
     * @param z         where the player would be
     * @param travelled how far along the route they would have come
     */
    static TransitStep transitStepAt(double x, double z, double travelled) {
        if (!transitPlanInForce()) {
            return null;
        }
        Trip trip = transitTrip;
        List<Trip.Ride> rides = trip.rides();

        // Arriving at the stop being got off at comes first, and outranks "still on the way to it":
        // this is the moment the player has to act, and it is the one moment the guidance used to miss
        // -- the approach line had already been said, and the ride's own readout stops the instant the
        // stop is reached.
        int arrived = arrivedAt(rides, travelled, x, z);
        if (arrived >= 0) {
            Trip.Ride ride = rides.get(arrived);
            Trip.RideStop stop = ride.stops().get(ride.stops().size() - 1);
            Trip.Ride next = arrived + 1 < rides.size() ? rides.get(arrived + 1) : null;
            // The change is named here as well as on the approach, and that is not a repeat for its own
            // sake: a change at a station both lines call at has no walk between the two vehicles, so
            // there is no second boarding prompt far enough away from this stop to carry the news --
            // and the approach line, said a whole ride earlier, is not what a rider standing on the
            // platform can act on.
            return next == null
                    ? new TransitStep(TransitCue.ARRIVE, false, ride.line(), ride.terminus(),
                            stop.name(), stop.name(), null, 0, 0, arrived, ride.stops().size() - 1)
                    : new TransitStep(TransitCue.ARRIVE, true, next.line(), next.terminus(),
                            stop.name(), stop.name(), null, 0, 0, arrived, ride.stops().size() - 1);
        }

        int index = trip.rideIndexAt(travelled);
        if (index >= 0) {
            Trip.Ride ride = rides.get(index);
            int last = ride.stops().size() - 1;
            int passed = Math.max(0, ride.stopPassed(travelled));
            String left = ride.stops().get(last).name();
            String reached = ride.stops().get(passed).name();
            if (ride.approachingAlighting(travelled)) {
                Trip.Ride next = index + 1 < rides.size() ? rides.get(index + 1) : null;
                // Which line is being changed onto is carried here as well as at the stop itself: the
                // approach is where a rider on a long ride first needs to know, and the stop is where
                // the change has to be acted on. At a station both lines call at there is no walk
                // between the two vehicles, so the second of those two is the only one that will be
                // heard before the next boarding prompt.
                return new TransitStep(TransitCue.ALIGHT, next != null,
                        next == null ? ride.line() : next.line(),
                        next == null ? ride.terminus() : next.terminus(), left, reached, null,
                        ride.stopsRemaining(travelled), ride.alightAt() - travelled, index, passed);
            }
            Trip.RideStop upcoming = ride.nextStop(travelled);
            return new TransitStep(TransitCue.RIDE, false, ride.line(), ride.terminus(), left, reached,
                    upcoming == null ? left : upcoming.name(), ride.stopsRemaining(travelled),
                    upcoming == null ? 0 : upcoming.at() - travelled, index, passed);
        }
        // On foot. The next boarding is worth saying once it is close enough to act on, and the rest of
        // the walk is the ordinary walking guidance.
        Trip.Ride next = trip.nextRide(travelled);
        if (next != null && next.boardAt() - travelled <= turnNowDistance()) {
            return new TransitStep(TransitCue.BOARD, false, next.line(), next.terminus(),
                    next.boardedAt(), next.boardedAt(), null, Math.max(0, next.stops().size() - 1),
                    Math.max(0, next.boardAt() - travelled), trip.indexOfRide(next), 0);
        }
        return null;
    }

    /**
     * The ride whose stop being got off at the player is standing at, or -1.
     *
     * <p>By position rather than by distance along the route, because the ride is over the instant its
     * last stop is reached: the distance comparison that says "on the vehicle" stops being true exactly
     * where this question begins. A platform is a place, so the test is the same one the rest of the
     * navigation makes about being at a stop -- how close the player is to it.
     *
     * <p>The newest such ride wins, so a journey that has just changed lines reports the platform it is
     * standing on rather than the one it left several stops ago.
     */
    private static int arrivedAt(List<Trip.Ride> rides, double travelled, double x, double z) {
        if (Double.isNaN(x) || Double.isNaN(z)) {
            return -1;
        }
        for (int i = rides.size() - 1; i >= 0; i--) {
            Trip.Ride ride = rides.get(i);
            if (ride.alightAt() > travelled + 1.0) {
                // Not reached yet, so this is a stop still to come rather than one to get off at.
                continue;
            }
            if (travelled - ride.alightAt() > STOP_ARRIVAL_DISTANCE) {
                // Reached, and the walk away from it has begun: the next thing is boarding, not getting
                // off, and holding this cue any longer would keep the readout on the platform the player
                // has already left.
                continue;
            }
            Trip.RideStop stop = ride.stops().get(ride.stops().size() - 1);
            if (Math.hypot(stop.x() - x, stop.z() - z) <= STOP_ARRIVAL_DISTANCE) {
                return i;
            }
        }
        return -1;
    }

    /**
     * The readout line for a transit cue, for the panel and the map label alike.
     *
     * <p>No distance is in the words: both readouts have a slot of their own for the number. The
     * spoken form carries it, because a phrase is heard on its own -- see Narration.
     */
    public static String transitSentence(TransitStep step) {
        if (step == null) {
            return "";
        }
        return switch (step.cue()) {
            case BOARD -> Component.translatable("hud.howtogo.transit_board_line",
                    named(step.line()), named(step.terminus())).getString();
            case RIDE -> Component.translatable("hud.howtogo.transit_riding", named(step.next()),
                    step.stopsRemaining(), named(step.station())).getString();
            case ALIGHT -> step.transfer()
                    ? Component.translatable("hud.howtogo.transit_approach_transfer",
                            named(step.station()), named(step.line()),
                            named(step.terminus())).getString()
                    : Component.translatable("hud.howtogo.transit_approach",
                            named(step.station())).getString();
            case ARRIVE -> step.transfer()
                    ? Component.translatable("hud.howtogo.transit_arrive_transfer",
                            named(step.station()), named(step.line()),
                            named(step.terminus())).getString()
                    : Component.translatable("hud.howtogo.transit_arrive",
                            named(step.station())).getString();
        };    }

    /** What a name that may be missing is called, so a sentence never quietly loses its subject. */
    public static String named(String name) {
        return name == null || name.isBlank()
                ? Component.translatable("hud.howtogo.unnamed_station").getString()
                : name;
    }

    /**
     * Whether a turn of this angle is a U-turn, which is worded differently and may be refused.
     *
     * <p>The same angle {@link #turnPhrase} uses to pick the word, so the two cannot drift apart and
     * leave a "turn around" that is not treated as a U-turn, or the reverse.
     */
    public static boolean isUturn(double degrees) {
        return Math.abs(degrees) > UTURN_DEGREES;
    }

    /** Whether the road the player is standing on is a highway. */
    public static boolean onHighway() {
        return currentRoadClass(mode()) == RoadClass.HIGHWAY;
    }

    /**
     * The U-turn sentence, in the form the map readout and the voice both use.
     *
     * <p>On a highway it is not a U-turn at all: there is nowhere to turn, so the call becomes
     * carrying on to the next junction. Same junction, same distance, different instruction -- the
     * highway rule is wording on top of the anchoring, not a second way of finding the junction.
     *
     * @param now whether the junction is close enough to act on, which drops the distance
     */
    public static String uturnSentence(double distanceAhead, boolean now, boolean highway) {
        if (highway) {
            return now
                    ? net.minecraft.network.chat.Component
                            .translatable("hud.howtogo.hud_uturn_highway_now").getString()
                    : net.minecraft.network.chat.Component
                            .translatable("hud.howtogo.hud_uturn_highway",
                                    Route.formatDistance(distanceAhead)).getString().trim();
        }
        return now
                ? net.minecraft.network.chat.Component
                        .translatable("hud.howtogo.hud_uturn_now").getString()
                : net.minecraft.network.chat.Component
                        .translatable("hud.howtogo.hud_uturn",
                                Route.formatDistance(distanceAhead)).getString().trim();
    }

    /**
     * The bare U-turn action for the readout's instruction line, which shows the distance apart from
     * it in its own slot.
     */
    public static String uturnAction(boolean highway) {
        return net.minecraft.network.chat.Component.translatable(highway
                ? "hud.howtogo.turn.next_junction"
                : "hud.howtogo.turn.uturn").getString();
    }

    /**
     * Human-readable phrase for a signed turn angle, positive being a right turn.
     *
     * <p>Lives here rather than in the renderer so the fullscreen map and the in-game HUD describe
     * turns identically.
     */
    public static String turnPhrase(double degrees) {
        double magnitude = Math.abs(degrees);
        String key;
        if (magnitude > UTURN_DEGREES) {
            key = "hud.howtogo.turn.uturn";
        } else if (magnitude > 50) {
            key = degrees > 0 ? "hud.howtogo.turn.right" : "hud.howtogo.turn.left";
        } else {
            key = degrees > 0 ? "hud.howtogo.turn.slight_right" : "hud.howtogo.turn.slight_left";
        }
        return net.minecraft.network.chat.Component.translatable(key).getString();
    }

    // ----------------------------------------------------------------- routing

    /**
     * A route planned for the picker, with nothing of the live trip in it.
     *
     * @param route         the plan, or {@link Route#empty()} when there is none
     * @param originX       where it starts, which is the player rather than the trip origin
     * @param originZ       where it starts, which is the player rather than the trip origin
     * @param destination   what it leads to
     * @param note          why there is no plan, or null when there is one
     */
    public record RoutePreview(Route route, double originX, double originZ, Destination destination,
                               String note) {

        /** Whether this preview has a plan to draw. */
        public boolean isPresent() {
            return route != null && route.isPresent();
        }
    }

    /**
     * Plans a route from the player to a destination for the picker to draw before anything is
     * committed.
     *
     * <p>Deliberately a pure calculation over its arguments. The active session -- its target, its
     * route, the pinned trip origin, the travelled distance and the fallback note -- is all left
     * exactly as it was, so opening the picker mid-trip to compare alternatives cannot hijack the
     * trip already in progress. The origin is the player's current position because that is where
     * a preview would start; the live route keeps the origin its trip actually began at.
     *
     * <p>The walking baseline that {@link #recomputeFrom} applies is deliberately not applied here.
     * That rule exists to stop a committed trip being planned on a mode that is plainly worse, and
     * it explains itself in the HUD when it fires; a preview is the opposite situation -- the
     * player is choosing between modes and needs to see what the mode they picked would actually
     * do. Previewing the walk instead would make every mode look identical and the choice
     * meaningless.
     *
     * @param destination where the prospective trip would end
     * @param planMode    the mode to plan for, or null to use the mode in force
     * @param preferences the policy to plan under, or null to use the policy in force
     * @return the plan and its endpoints, or a preview carrying the reason there is none
     */
    public static RoutePreview preview(Destination destination, TravelMode planMode,
                                       RoutePreferences preferences) {
        if (destination == null) {
            return null;
        }
        TravelMode active = planMode == null ? mode() : planMode;
        RoutePreferences policy = preferences == null ? RoutePreferenceStore.preferences() : preferences;

        LocalPlayer player = Minecraft.getInstance().player;
        double x = player != null ? player.getX() : destination.x();
        double z = player != null ? player.getZ() : destination.z();
        // The plan and the explanation are made on one and the same network, so a preview cannot be
        // refused for something the network it was refused on did not contain -- and the network is the
        // one the live route will be planned on, which for a transit journey over lines that know their
        // own track is the world without MTR's shared layer. See recomputeFrom.
        boolean wantsMarks = active != TravelMode.TRANSIT
                || !MtrTransit.everyLineRidesItsOwnTrack(linesInPlay());
        RoadNetwork network = RailTrackStore.forRouting(active, policy, wantsMarks);

        Route planned = planRoute(network, active, policy, x, z, destination).route();
        if (!planned.isPresent() && active != TravelMode.WALK
                && RoadConfig.fallBackToWalkingWhenSlower()) {
            // The same comparison the live route makes, so that the line the picker draws is the line
            // the HUD then guides along. Without it, a public transport preview with no line journey
            // reported "no usable road connection" while pressing the button produced a walking route:
            // the picker calling the journey impossible and the navigation doing it anyway.
            planned = RoadRouter.findRoute(RailTrackStore.forRouting(TravelMode.WALK, policy), x, z,
                    destination.x(), destination.z(), destination.name(), TravelMode.WALK, policy);
            planned = timedForRequested(planned, active);
        }
        if (!planned.isPresent()) {
            // Said out loud, and this is the only place it can be: a preview that finds nothing shows
            // the reason in the picker and nowhere else, so a mode that cannot route at all leaves no
            // trace in the log and the only report of it is "it does not work". The reason names the
            // branch -- no road of that class near an end, the nearest one past the mode's connector
            // distance, two fragments that do not meet -- and that is what makes it answerable.
            //
            // Named as a translation key in the log and as a sentence in the picker: the log is read
            // while diagnosing a report, and there the key is what can be searched for in the source.
            RouteFailure why = RoadRouter.explainFailure(network, x, z, destination.x(),
                    destination.z(), active, policy);
            HowToGo.LOGGER.info("[HowToGo] no route to {} for {} from ({}, {}): {}",
                    destination.name(), active.id(), Math.round(x), Math.round(z), why);
            return new RoutePreview(planned, x, z, destination, failureText(why));
        }
        return new RoutePreview(planned, x, z, destination, null);
    }

    /**
     * A routing failure in the language the client is running in.
     *
     * <p>The one place a {@link RouteFailure} becomes text. The route package cannot translate -- it is
     * pure Java shared by every branch -- so the reason it hands back is a key and its arguments, and
     * this is where the picker's line is built from them.
     *
     * <p>An argument that is itself a {@link RouteFailure} is resolved the same way, so a message may
     * carry a clause of its own. The avoided classes are the case that needs it: whether the clause
     * belongs in the sentence, and where, is a question for the translation rather than for the router,
     * so the router hands over the key for the clause and lets the translation place it.
     */
    public static String failureText(RouteFailure failure) {
        if (failure == null || !failure.isPresent()) {
            return null;
        }
        return resolvable(failure).getString();
    }

    /** A translatable for one reason, with any reason it carries resolved as well. */
    private static Component resolvable(RouteFailure failure) {
        Object[] args = new Object[failure.args().size()];
        for (int i = 0; i < args.length; i++) {
            Object arg = failure.args().get(i);
            args[i] = arg instanceof RouteFailure nested ? resolvable(nested) : arg;
        }
        return Component.translatable(failure.key(), args);
    }

    private static void recompute() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) {
            recomputeFrom(player.getX(), player.getZ());
        }
    }

    private static void recomputeFrom(double x, double z) {
        if (target == null) {
            route = Route.empty();
            return;
        }
        routeOriginX = x;
        routeOriginZ = z;
        // A new plan renumbers every junction along it, so a verdict about whether one of the old
        // ones was taken means nothing and must not suppress a turn on the new line.
        turns.reset();
        // The store, not the config directly: the picker's buttons change the policy between two
        // plans, and a re-plan made after such a change has to see the new one.
        RoutePreferences preferences = RoutePreferenceStore.preferences();
        TravelMode active = mode();
        // The shared layer of everything read out of MTR is one network holding the whole railway, and a
        // transit plan over lines that each know their own track makes no use of it -- so it is left out
        // of the world the plan runs on, and the copy of the railway that merging it costs is not paid.
        // Asked before the network is built rather than after, because the copy is what is being avoided.
        // See MtrTransit.everyLineRidesItsOwnTrack.
        boolean wantsMarks = active != TravelMode.TRANSIT
                || !MtrTransit.everyLineRidesItsOwnTrack(linesInPlay());
        RoadNetwork usable = RailTrackStore.forRouting(active, preferences, wantsMarks);
        // Public transport first, as a journey of legs: a single route in one mode cannot say where the
        // riding begins, and the requirement is that it begins and ends at a station. The plain route
        // is still the fallback, so a world with no station near either end behaves as it did before
        // rather than reporting that there is no way to go.
        Planned planned = planRoute(usable, active, preferences, x, z, target);
        Route plannedRoute = planned.route();
        // The journey behind the route, when there is one: what the board-and-alight guidance reads.
        // Taken before the walking comparison below, because a route that turns out to be a walk is not
        // that journey and must not be guided as one.
        transitTrip = planned.trip();
        clearFallback();

        // Walking is the comparison every mode has to beat, so it is planned whenever the player
        // asked for another one. A mode is a preference about how to travel, not a promise to
        // travel badly: being sent the long way round by rail when the walk is shorter is exactly
        // the kind of answer that makes a router feel broken.
        if (active != TravelMode.WALK && RoadConfig.fallBackToWalkingWhenSlower()) {
            Route onFoot = RoadRouter.findRoute(
                    RailTrackStore.forRouting(TravelMode.WALK, preferences),
                    x, z, target.x(), target.z(), target.name(), TravelMode.WALK, preferences);
            if (onFoot.isPresent() && losesToWalking(plannedRoute, onFoot)) {
                // A public transport journey that exists is not taken away from the player because
                // the walk is quicker. They asked to go by line, and a route that quietly becomes a
                // walk down the road is indistinguishable from the mode being broken -- which is
                // exactly how it was read. The comparison is still made and still logged, so the
                // numbers are there to be read; the walk is an alternative that a later picker can
                // offer, not a replacement. A journey the lines cannot carry at all is still
                // answered with the walk, and a drive that is merely slower than walking keeps the
                // old rule as well -- that is what stops a drive being planned as a walk across a
                // field. A drive that reaches no road at all is the one case below.
                if (active == TravelMode.TRANSIT && plannedRoute.isPresent()) {
                    HowToGo.diagnostic(
                            "[HowToGo] transit is slower than walking ({} vs {}); keeping transit, "
                                    + "the walk is an alternative rather than a replacement",
                            Route.formatDuration(plannedRoute.estimatedSeconds()),
                            Route.formatDuration(onFoot.estimatedSeconds()));
                } else if (active == TravelMode.DRIVE && !plannedRoute.isPresent()) {
                    // No road the car may use comes within its connector distance of one of the two
                    // ends -- the case a walk at the destination is unavoidable in. The walker's
                    // line is then the only plan there is, and the trip along it is the car's up to
                    // the last road and the walker's from there, so it is kept and timed for the
                    // drive rather than for the walk. Nothing is noted as abandoned: the mode the
                    // player chose is the mode being navigated, and the readout would otherwise
                    // explain a fallback that is no longer being made.
                    HowToGo.diagnostic("[HowToGo] drive reaches no road at one end ({}); keeping the "
                                    + "walker's line, timed as the road driven and the rest walked",
                            RoadRouter.explainFailure(RailTrackStore.forRouting(active, preferences),
                                    routeOriginX, routeOriginZ, target.x(), target.z(), active,
                                    preferences));
                    route = timedForRequested(onFoot, active);
                    logRoute(preferences);
                    return;
                } else {
                    noteFallback(active, plannedRoute, onFoot, preferences);
                    route = onFoot;
                    // A walk is not the journey the transit guidance is about, even when the mode
                    // still says public transport: the boarding it would name is not on this route.
                    transitTrip = null;
                    logRoute(preferences);
                    return;
                }
            }
        }
        route = plannedRoute;
        logRoute(preferences);
    }

    /**
     * Every line a journey may be planned over: the player's own, and the ones read out of MTR.
     *
     * <p>Two lists joined here rather than one list with two kinds of entry, because they have two
     * kinds of owner. The player's lines are the editor's, saved and editable; MTR's are rebuilt from
     * what the client has been sent and must never be written back. Keeping them apart means the
     * editor cannot reach an imported line at all, which is what makes "read-only" a property of the
     * arrangement rather than a rule somebody has to remember.
     */
    public static List<TransitLine> linesInPlay() {
        List<TransitLine> imported = MtrTransit.lines();
        if (imported.isEmpty()) {
            return TransitLineStore.get();
        }
        List<TransitLine> all = new java.util.ArrayList<>(TransitLineStore.get());
        all.addAll(imported);
        return all;
    }

    /**
     * A plan: the route to draw and follow, and the journey it was flattened from.
     *
     * <p>The journey travels with the route because the route cannot say where the riding begins -- see
     * {@link Trip} -- and the two must be the same plan: a caller that planned twice, once for each,
     * could guide a journey it is not navigating. Null for every mode but public transport, which is
     * the only one whose plan is a journey of legs at all.
     */
    private record Planned(Route route, Trip trip) {
    }

    /**
     * Plans a route in one mode, as a public transport journey when that is the mode.
     *
     * <p>One place, because the preview and the live route must agree. The picker draws this plan and
     * the HUD then guides along the line the player accepted; a preview planned by a different rule
     * would show a route that is abandoned the moment the button is pressed. That is exactly what
     * happened while the picker called the router directly: public transport was previewed as a line
     * entered at the nearest point of track, then navigated as a journey through stations.
     *
     * <p>The plain route stays as the fallback rather than as an error, so a world whose stations are
     * unreachable, or which has none, behaves as it did before instead of reporting that there is no
     * way to go.
     */
    private static Planned planRoute(RoadNetwork network, TravelMode mode,
                                     RoutePreferences preferences, double x, double z,
                                     Destination target) {
        if (mode == TravelMode.TRANSIT) {
            // No fallback of any kind. Public transport is the lines in play -- the player's own and
            // the ones read out of MTR -- and a route that boards at the nearest point of a line nobody
            // chose, which is what the old fallback did, is a wrong answer rather than a worse one.
            // When no line can carry the journey the answer is empty, and the walking comparison below
            // is free to offer the walk.
            List<TransitLine> lines = linesInPlay();
            // Which roads a ride runs on depends on the line: MTR's rails are one shared layer, so a
            // line with its own marks switched off has to be given the network that never had them,
            // rather than one that merely declined to add them. The second copy of the world is made
            // only when a line actually wants the difference, which keeps the ordinary case free.
            //
            // And a line whose own track is known rides on that and nothing else, in which case the
            // shared layer is of no use to any of them and the network handed in is already the world
            // without it -- see the caller, which leaves it out for exactly this case. The walking
            // legs never want it either, because a walk cannot use a rail; see RideRoads.
            boolean ownTracksOnly = MtrTransit.everyLineRidesItsOwnTrack(lines);
            RoadNetwork plain = ownTracksOnly ? network
                    : RailTrackStore.forRouting(mode, preferences, false);
            // Planned as a journey and then flattened, rather than asked for as a route: the boarding
            // and alighting stations are what the board-and-alight guidance names, and they exist only
            // on the journey. Both callers therefore go through the same two calls, so the route the
            // picker previews and the route the HUD follows cannot come from different plans.
            Trip trip = TransitPlanner.plan(
                    RideRoads.of(network, plain, MtrTransit::marksEnabled, MtrTransit::trackOf), lines,
                    x, z, target.x(), target.z(), target.name(), preferences);
            if (!trip.isPresent()) {
                HowToGo.diagnostic("[HowToGo] public transport: no journey over {} line(s)",
                        lines.size());
                return new Planned(Route.empty(), null);
            }
            return new Planned(TransitPlanner.asRoute(trip, target.name()), trip);
        }
        return new Planned(RoadRouter.findRoute(network, x, z, target.x(), target.z(), target.name(),
                mode, preferences), null);
    }

    /**
     * Whether the chosen mode has nothing to offer over walking.
     *
     * <p>A mode that found no route at all counts, and not only a slow one: the player would
     * otherwise be told there is no way to get there while standing beside a usable footpath.
     */
    private static boolean losesToWalking(Route planned, Route onFoot) {
        return !planned.isPresent() || planned.estimatedSeconds() > onFoot.estimatedSeconds();
    }

    /**
     * The walker's line, timed for the mode the player asked for.
     *
     * <h2>Why the drive needs this and the other modes do not</h2>
     * A mode that finds no route at all is answered with a plan made on foot, because the walker is
     * the only one who can leave the network. For the drive that plan is not a walk: the trip the
     * player then makes along it is the car's up to the last road and the walker's from there, and
     * timing the whole line at walking pace reported a destination with a short walk at the end as a
     * much longer trip -- with every block of road the car would have driven costed as though it were
     * walked, which is what made the preview of a drive to such a place read as an hour of walking.
     * So the line is kept exactly as planned and re-timed for the drive; see {@link Route#timedFor}.
     *
     * <p>Walking needs nothing, and public transport deliberately gets nothing: a journey is not a
     * ride where one can and a walk where one cannot, so a line that cannot carry the journey leaves
     * the walk it is, time and all.
     */
    private static Route timedForRequested(Route planned, TravelMode active) {
        return active == TravelMode.DRIVE ? planned.timedFor(TravelMode.DRIVE) : planned;
    }

    private static void clearFallback() {
        abandonedMode = null;
        abandonedSeconds = Double.NaN;
        walkingSeconds = 0;
    }

    /**
     * Records that the chosen mode was dropped, and says so in the log.
     *
     * <p>The log is where the numbers behind the decision survive: the readout has room for the
     * sentence but not for the arithmetic, and a fallback nobody can check reads as a bug.
     */
    private static void noteFallback(TravelMode abandoned, Route planned, Route onFoot,
                                     RoutePreferences preferences) {
        abandonedMode = abandoned;
        walkingSeconds = onFoot.estimatedSeconds();
        if (planned.isPresent()) {
            abandonedSeconds = planned.estimatedSeconds();
            HowToGo.diagnostic(
                    "[HowToGo] {} is slower than walking ({} vs {}); planning on foot",
                    abandoned.id(), Route.formatDuration(abandonedSeconds),
                    Route.formatDuration(walkingSeconds));
            return;
        }
        HowToGo.diagnostic("[HowToGo] {} finds no route here ({}); planning on foot",
                abandoned.id(), RoadRouter.explainFailure(RailTrackStore.forRouting(abandoned, preferences),
                        routeOriginX, routeOriginZ, target.x(), target.z(), abandoned, preferences));
    }

    /**
     * Logs the plan that was kept.
     *
     * <p>Named from the route rather than from the selected mode, since a fallback has just made
     * those two different things.
     */
    private static void logRoute(RoutePreferences preferences) {
        if (route.isPresent()) {
            HowToGo.diagnostic(
                    "[HowToGo] route to {} from ({}, {}) for {}: {} points, {} blocks, {} turns "
                            + "| network {} nodes / {} segments",
                    target.name(), Math.round(routeOriginX), Math.round(routeOriginZ),
                    route.travelMode().id(), route.points().size(), Math.round(route.totalLength()),
                    route.maneuvers().size(),
                    RoadStore.get().nodeCount(), RoadStore.get().segmentCount());
        } else {
            HowToGo.LOGGER.info("[HowToGo] no route to {} for {}: {}", target.name(),
                    mode().id(), RoadRouter.explainFailure(RoadStore.get(), routeOriginX,
                            routeOriginZ, target.x(), target.z(), mode(), preferences));
        }
    }
}
