package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.RoadConfig;
import bili.dongsz.howtogo.HowToGo;
import bili.dongsz.howtogo.road.RoadChains;
import bili.dongsz.howtogo.road.RoadClass;
import bili.dongsz.howtogo.road.RoadNetwork;
import bili.dongsz.howtogo.road.RoadNode;
import bili.dongsz.howtogo.road.RoadSegment;
import bili.dongsz.howtogo.route.Destination;
import bili.dongsz.howtogo.route.RoadRouter;
import bili.dongsz.howtogo.route.Route;
import bili.dongsz.howtogo.route.RoutePreferences;
import bili.dongsz.howtogo.route.TransitPlanner;
import bili.dongsz.howtogo.route.TravelMode;
import bili.dongsz.howtogo.transit.LineStop;
import bili.dongsz.howtogo.transit.TransitLine;
import bili.dongsz.howtogo.store.RoutePreferenceStore;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
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
        routeOriginX = Double.NaN;
        routeOriginZ = Double.NaN;
        tripOriginX = Double.NaN;
        tripOriginZ = Double.NaN;
        arrived = false;
        offRouteSince = 0L;
        clearFallback();
        clearWrongWay();
        takenManeuverAt = Double.NaN;
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
     * Estimated seconds left, at the pace of the mode the route was planned for.
     *
     * <p>No base speed is passed in on purpose: the walking constant that used to be applied here
     * would quietly restate every drive and every bus ride as a walk.
     */
    public static double remainingSeconds() {
        return remainingLength() * route.secondsPerBlock();
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
     * How far a heading may differ from the route leaving a junction and still count as turning.
     *
     * <p>Generous on purpose: a player halfway through a turn, or cutting the corner, is not yet
     * pointing down the new road, and holding the instruction back through that would flicker. It
     * still excludes carrying straight on through a right-angled junction, which is ninety degrees
     * out. The two do overlap on the shallowest manoeuvres -- a turn of twenty-five degrees, the
     * threshold for one existing at all, is barely different from not turning -- but there the two
     * directions mean nearly the same thing, so getting it wrong costs nothing.
     */
    private static final double TURN_TAKEN_ARC_DEGREES = 45.0;

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
    private static final double TURN_GIVE_UP_DISTANCE = 60.0;

    /** Blocks behind a junction that still count as level with it, so a corner does not flicker. */
    private static final double MANEUVER_PASSED_SLACK = 2.0;

    /**
     * Where along the route the last manoeuvre judged taken sits, or NaN when none has been.
     *
     * <p>See {@link #taken}: the verdict is latched so a wandering heading cannot un-take a junction
     * the player has already turned at. Cleared with the route it refers to.
     */
    private static double takenManeuverAt = Double.NaN;

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
     * @param distanceAhead straight-line blocks from the player to that junction
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
        return Double.isNaN(alongRoute) ? 0 : MovementState.bearingGap(travel, alongRoute);
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

    /** The other segment meeting a node, or null when the only one there is {@code excludeId}. */
    private static RoadSegment otherSegmentAt(RoadNetwork network, int nodeId, int excludeId) {
        for (RoadSegment segment : network.segments()) {
            if (segment.id() == excludeId) {
                continue;
            }
            if (segment.fromNode() == nodeId || segment.toNode() == nodeId) {
                return segment;
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
     * The next turn ahead, or null when nothing is coming up.
     *
     * <p>{@link Route#maneuvers()} reports each turn's position along the whole route. That is not
     * the distance still to travel -- after a re-plan the player is part way along it -- so the
     * value is rebased onto the player here, once, rather than at each place that displays it.
     *
     * <p>Being level with a junction by distance is not the same as having taken it. A player who
     * walks straight past a corner is still level with it, and calling the turn done there would
     * swap the instruction for the next one while they are standing at the wrong road. So a
     * manoeuvre that is behind by distance is only passed if the player's heading has come round to
     * the road leaving the junction; otherwise it stays current, at a distance of zero, which the
     * readout and the announcement both show as "now".
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
        for (Route.Maneuver maneuver : route.maneuvers()) {
            if (maneuver.distanceFromStart() > travelled + MANEUVER_PASSED_SLACK
                    || !taken(maneuver, heading, x, z)) {
                return new Instruction(maneuver.distanceFromStart() - travelled,
                        maneuver.turnDegrees(), maneuver.roadName(), maneuver.namesTheRoad());
            }
        }
        return null;
    }

    /**
     * Whether a manoeuvre the player is already level with has actually been taken.
     *
     * <p>The verdict is latched once it is reached. It is re-made every tick out of the player's
     * heading, and a heading wanders: a bend in the new road, a glance sideways, a step around a
     * corner all take it outside the arc. Without the latch a junction the player had already turned
     * at comes back as the current instruction -- at a distance of zero, so it is shown and spoken as
     * "now" -- and then disappears again when the heading swings back. That is the unexplained "now
     * turn left" at junctions, and it needs no reversal to happen, only a few degrees of drift.
     *
     * <p>The latch is cleared whenever the route is re-planned, because a new plan renumbers every
     * junction along it and a verdict about the old numbering means nothing.
     *
     * @param heading the player's facing bearing, or NaN when there is no player to judge by
     */
    private static boolean taken(Route.Maneuver maneuver, double heading, double x, double z) {
        if (Math.abs(maneuver.distanceFromStart() - takenManeuverAt) <= MANEUVER_PASSED_SLACK) {
            return true;
        }
        if (Double.isNaN(heading)
                // Nothing to judge a heading against -- no player yet -- so the distance rule stands
                // alone rather than every turn being held open forever.
                || Math.hypot(x - maneuver.junctionX(), z - maneuver.junctionZ())
                        > TURN_GIVE_UP_DISTANCE
                || MovementState.bearingGap(heading, maneuver.bearingAfter())
                        <= TURN_TAKEN_ARC_DEGREES) {
            takenManeuverAt = maneuver.distanceFromStart();
            return true;
        }
        return false;
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
        return route.isPresent() && route.distanceTo(x, z) > route.toleranceNear(x, z);
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
     * Pace in blocks per second the player is making, given the road already found underfoot.
     *
     * <p>Only reached by the fallback above, so the class is either absent or one the table does not
     * name: the mode's best pace is then the answer, for the reason given there.
     */
    private static double paceUnderfoot(TravelMode active, RoadClass underfoot) {
        if (underfoot != null) {
            return active.speedOn(underfoot);
        }
        double fastest = 0;
        for (RoadClass roadClass : RoadClass.values()) {
            fastest = Math.max(fastest, active.speedOn(roadClass));
        }
        return fastest;
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

        // Nothing drawn underfoot: Create's tracks count too, so riding one is called at the pace a
        // train makes instead of at the pace the mode can do its best on. Read only as a fallback,
        // which is what keeps a drawn road beside a track deciding the underfoot reading exactly as
        // it always did, and only TRANSIT reaches this at all -- rail has no pace on foot or behind
        // the wheel, the same filter the loop above applies.
        if (nearest == null && active.speedOn(RoadClass.RAIL) > 0) {
            for (RoadSegment segment : RailTrackStore.segments()) {
                double distance = distanceToRoad(segment, x, z);
                if (distance > RoadConfig.onRoadTolerance(RoadClass.RAIL)
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
        // refused for something the network it was refused on did not contain.
        RoadNetwork network = RailTrackStore.forRouting(active, policy);

        Route planned = planRoute(network, active, policy, x, z, destination);
        if (!planned.isPresent() && active != TravelMode.WALK
                && RoadConfig.fallBackToWalkingWhenSlower()) {
            // The same comparison the live route makes, so that the line the picker draws is the line
            // the HUD then guides along. Without it, a public transport preview with no line journey
            // reported "no usable road connection" while pressing the button produced a walking route:
            // the picker calling the journey impossible and the navigation doing it anyway.
            planned = RoadRouter.findRoute(RailTrackStore.forRouting(TravelMode.WALK, policy), x, z,
                    destination.x(), destination.z(), destination.name(), TravelMode.WALK, policy);
        }
        if (!planned.isPresent()) {
            return new RoutePreview(planned, x, z, destination,
                    RoadRouter.explainFailure(network, x, z, destination.x(), destination.z(),
                            active, policy));
        }
        return new RoutePreview(planned, x, z, destination, null);
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
        takenManeuverAt = Double.NaN;
        // The store, not the config directly: the picker's buttons change the policy between two
        // plans, and a re-plan made after such a change has to see the new one.
        RoutePreferences preferences = RoutePreferenceStore.preferences();
        TravelMode active = mode();
        RoadNetwork usable = RailTrackStore.forRouting(active, preferences);
        // Public transport first, as a journey of legs: a single route in one mode cannot say where the
        // riding begins, and the requirement is that it begins and ends at a station. The plain route
        // is still the fallback, so a world with no station near either end behaves as it did before
        // rather than reporting that there is no way to go.
        Route planned = planRoute(usable, active, preferences, x, z, target);
        clearFallback();

        // Walking is the comparison every mode has to beat, so it is planned whenever the player
        // asked for another one. A mode is a preference about how to travel, not a promise to
        // travel badly: being sent the long way round by rail when the walk is shorter is exactly
        // the kind of answer that makes a router feel broken.
        if (active != TravelMode.WALK && RoadConfig.fallBackToWalkingWhenSlower()) {
            Route onFoot = RoadRouter.findRoute(
                    RailTrackStore.forRouting(TravelMode.WALK, preferences),
                    x, z, target.x(), target.z(), target.name(), TravelMode.WALK, preferences);
            if (onFoot.isPresent() && losesToWalking(planned, onFoot)) {
                // A public transport journey that exists is not taken away from the player because
                // the walk is quicker. They asked to go by line, and a route that quietly becomes a
                // walk down the road is indistinguishable from the mode being broken -- which is
                // exactly how it was read. The comparison is still made and still logged, so the
                // numbers are there to be read; the walk is an alternative that a later picker can
                // offer, not a replacement. A journey the lines cannot carry at all is still
                // answered with the walk, and every other mode keeps the old rule -- that is what
                // stops a drive being planned as a walk across a field.
                if (active == TravelMode.TRANSIT && planned.isPresent()) {
                    HowToGo.LOGGER.info(
                            "[HowToGo] transit is slower than walking ({} vs {}); keeping transit, "
                                    + "the walk is an alternative rather than a replacement",
                            Route.formatDuration(planned.estimatedSeconds()),
                            Route.formatDuration(onFoot.estimatedSeconds()));
                } else {
                    noteFallback(active, planned, onFoot, preferences);
                    route = onFoot;
                    logRoute(preferences);
                    return;
                }
            }
        }
        route = planned;
        logRoute(preferences);
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
    private static Route planRoute(RoadNetwork network, TravelMode mode, RoutePreferences preferences,
                                   double x, double z, Destination target) {
        if (mode == TravelMode.TRANSIT) {
            // No fallback of any kind. Public transport is the lines the player configured, and a
            // route that boards at the nearest point of a line nobody chose -- which is what the old
            // fallback did -- is a wrong answer rather than a worse one. When no line can carry the
            // journey the answer is empty, and the walking comparison below is free to offer the walk.
            List<TransitLine> lines = TransitLineStore.get();
            Route byTransit = TransitPlanner.planRoute(network, lines, x, z, target.x(), target.z(),
                    target.name(), preferences);
            if (!byTransit.isPresent()) {
                HowToGo.LOGGER.info("[HowToGo] public transport: no journey over {} line(s)",
                        lines.size());
            }
            return byTransit;
        }
        return RoadRouter.findRoute(network, x, z, target.x(), target.z(), target.name(), mode,
                preferences);
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
            HowToGo.LOGGER.info(
                    "[HowToGo] {} is slower than walking ({} vs {}); planning on foot",
                    abandoned.id(), Route.formatDuration(abandonedSeconds),
                    Route.formatDuration(walkingSeconds));
            return;
        }
        HowToGo.LOGGER.info("[HowToGo] {} finds no route here ({}); planning on foot",
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
            HowToGo.LOGGER.info(
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
