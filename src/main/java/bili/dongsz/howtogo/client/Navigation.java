package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.RoadConfig;
import bili.dongsz.howtogo.HowToGo;
import bili.dongsz.howtogo.road.RoadChains;
import bili.dongsz.howtogo.road.RoadClass;
import bili.dongsz.howtogo.road.RoadNetwork;
import bili.dongsz.howtogo.road.RoadNode;
import bili.dongsz.howtogo.road.RoadSegment;
import bili.dongsz.howtogo.route.Destination;
import bili.dongsz.howtogo.route.LinePlanner;
import bili.dongsz.howtogo.route.RideRoads;
import bili.dongsz.howtogo.route.RoadRouter;
import bili.dongsz.howtogo.route.Route;
import bili.dongsz.howtogo.route.RouteAlternatives;
import bili.dongsz.howtogo.route.RouteFailure;
import bili.dongsz.howtogo.route.RouteOption;
import bili.dongsz.howtogo.route.RoutePreferences;
import bili.dongsz.howtogo.route.RouteStrategy;
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
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The active navigation session: destination, route and progress.
 * The route is recomputed only when the player moves meaningfully, goes off route or changes mode --
 * not every tick, which would waste A* and flicker between equal-cost paths.
 */
public final class Navigation {

    private static final double ARRIVAL_DISTANCE = 12.0;

    /**
     * How close to a stop counts as standing at it.
     * A platform is a dozen blocks across and the drawn line runs through the station, not the platform
     * edge; this is what the get-off call is measured by, and it is smaller than the station snap.
     */
    private static final double STOP_ARRIVAL_DISTANCE = 12.0;

    /**
     * How long the player must stay off route before it is re-planned.
     * One sample is not enough: crossing a junction briefly leaves the tolerance, and re-planning then
     * makes the start marker chase the player.
     */
    private static final long OFF_ROUTE_GRACE_MILLIS = 2500L;

    private static final long ARRIVAL_BANNER_MILLIS = 8000L;

    private static Destination target;
    private static Route route = Route.empty();
    /**
     * The journey the live route was flattened from, or null when it is not public transport.
     * Kept beside the route because a route cannot say where the riding begins; without it a transit
     * trip is guided only by its walking legs. Written by recomputeFrom alone.
     */
    private static Trip transitTrip;
    private static double routeOriginX = Double.NaN;
    private static double routeOriginZ = Double.NaN;

    /**
     * How the player is travelling: what the route and estimate are built for.
     * Held here so a switch re-plans from a clean slate; defaults to walking before the config is read.
     */
    private static TravelMode mode = TravelMode.WALK;
    private static boolean modeLoaded;
    /**
     * Set once the player has picked a mode themselves.
     * A config reload must not take that choice away: the config declares a default, not an override.
     */
    private static boolean modeChosen;

    /**
     * Where the trip began, pinned for the whole session.
     * The route is rebuilt from the player's position when they leave it, but the origin marker must
     * not follow.
     */
    private static double tripOriginX = Double.NaN;
    private static double tripOriginZ = Double.NaN;

    private static boolean arrived;
    private static long arrivedAtMillis;
    private static long offRouteSince;

    /**
     * The dimension the trip was planned in, or null when no trip is active.
     * A destination is coordinates plus a name, and every reading belongs to one dimension's network;
     * without this, walking through a portal guided a route from the world left behind.
     */
    private static String tripDimension;
    
    // Cached level identity, so the dimension string is allocated once per world, not per tick.
    private static Object lastCheckedLevel;
    private static String lastCheckedDimension;

    /**
     * The mode the last plan gave up on, or null when the one asked for was kept.
     * So the readout can explain a walk route while the panel would otherwise claim rail.
     */
    private static TravelMode abandonedMode;
    private static double abandonedSeconds = Double.NaN;
    private static double walkingSeconds;

    /**
     * Whether the mode given up on was public transport and the destination is near enough to walk.
     * The one fallback that is not a failure: no line was worth riding, so the walk is the answer.
     * See fallbackHint and LinePlanner#closeEnoughToWalk.
     */
    private static boolean walkInsteadOfRiding;

    /**
     * How much the player may move and still be served the cached plan list, in blocks.
     * Rounded so resizes, redraws and mouse nudges do not miss a cache whose first step starts on the
     * same road node either way; four blocks keeps the plan their own.
     */
    private static final double PLANS_CACHE_ROUNDING = 4.0;

    /**
     * How many plan lists the picker may keep.
     * It re-plans on init (every resize) and on every mode/metric/avoid toggle; each answer is up to
     * three A* searches. Eight covers three modes x two metrics plus an avoid variation.
     */
    private static final int PLANS_CACHE_CAPACITY = 8;

    /**
     * Plan lists by the question that produced them, oldest first.
     * Kept here, not in the picker, which is rebuilt on every resize. worldStamp expires entries;
     * only the picker asks, so guidance never depends on the cache.
     */
    private static final Map<PlansKey, List<RouteOption>> plansCache = new LinkedHashMap<>();

    /**
     * How many route inks the maps may keep.
     * Building an ink walks the route and resolves a line colour per leg, and five drawing surfaces ask
     * per frame; six covers the picker's three plans, the live route and the preview.
     */
    private static final int INK_CACHE_CAPACITY = 6;

    /**
     * The cache: route, trip and ink in parallel arrays.
     * Identity, not equality: a route has no value equality and callers hold the very objects the
     * navigation produced, so a hit is three reference comparisons. Hand-rolled over a map for the
     * same reason -- six entries and one linear scan allocate nothing on the frame path.
     */
    private static final Route[] inkRoutes = new Route[INK_CACHE_CAPACITY];
    private static final Trip[] inkTrips = new Trip[INK_CACHE_CAPACITY];
    private static final HudDraw.RouteInk[] inkValues = new HudDraw.RouteInk[INK_CACHE_CAPACITY];
    private static int inkCacheNext;

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

    /**
     * The live route as the maps draw it: each stretch in the ink its way of travelling calls for.
     * It used to return one colour, which cannot describe a transit journey of walk, ride, walk.
     * Cached per route and trip; see inkFor.
     */
    public static HudDraw.RouteInk routeInk() {
        return inkFor(route, transitTrip);
    }

    /**
     * The ink for one route and the journey it was flattened from, cached by the pair.
     * A route cannot say where the riding begins and has no value equality, so a hit is three identity
     * references; a miss builds a table, and every map surface asks per frame. Line colour edits and
     * deletions do not invalidate: the old colour stays until a re-plan.
     * @param route the route to draw, or null
     */
    public static HudDraw.RouteInk inkFor(Route route, Trip trip) {
        if (route == null || !route.isPresent()) {
            return HudDraw.RouteInk.EMPTY;
        }
        for (int i = 0; i < inkRoutes.length; i++) {
            if (inkRoutes[i] == route && inkTrips[i] == trip) {
                return inkValues[i];
            }
        }
        HudDraw.RouteInk ink = HudDraw.routeInk(route, trip, linesInPlay());
        inkRoutes[inkCacheNext] = route;
        inkTrips[inkCacheNext] = trip;
        inkValues[inkCacheNext] = ink;
        inkCacheNext = (inkCacheNext + 1) % inkRoutes.length;
        return ink;
    }

    /**
     * Forgets every cached ink when the session is cleared.
     * A stale entry is harmless but not free: a table holds a whole region's route points.
     */
    private static void clearInkCache() {
        for (int i = 0; i < inkRoutes.length; i++) {
            inkRoutes[i] = null;
            inkTrips[i] = null;
            inkValues[i] = null;
        }
        inkCacheNext = 0;
    }

    public static TravelMode mode() {
        if (!modeLoaded) {
            loadConfiguredMode();
        }
        return mode;
    }

    public static String modeLabel() {
        return mode().label();
    }

    /**
     * Why the last plan was made on foot, or null when the chosen mode was kept.
     * Null rather than an empty sentence, so callers can decide whether to draw a line at all.
     */
    public static String fallbackHint() {
        if (abandonedMode == null) {
            return null;
        }
        if (walkInsteadOfRiding) {
            return net.minecraft.network.chat.Component
                    .translatable("hud.howtogo.close_enough_to_walk").getString();
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
     * Does nothing while the config is unavailable, so the value is picked up next call.
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
     * Re-reads the configured default after the config file reloaded.
     * The mode is latched because a route is planned for it; does nothing when the player chose a mode
     * this session.
     */
    public static void reloadConfiguredMode() {
        if (modeChosen)return;
        loadConfiguredMode();
    }

    /**
     * Switches travel mode, re-planning the route straight away.
     * The route itself differs by mode, so it is rebuilt rather than recoloured.
     */
    public static void setMode(TravelMode newMode) {
        mode = newMode == null ? TravelMode.WALK : newMode;
        modeLoaded = true;
        modeChosen = true;
        HowToGo.LOGGER.info("[HowToGo] travel mode: {}", mode.id());
        announceMode();
        if (target != null)recompute();
    }

    private static void announceMode() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null)return;
        // 26.1 removed the overlay flag, so an action-bar line became a system message.
        //? if >=26.1 {
        /*player.sendSystemMessage(
                net.minecraft.network.chat.Component.translatable(
                        "hud.howtogo.mode_switched", mode.label()));
        *///?} else {
        player.displayClientMessage(
                net.minecraft.network.chat.Component.translatable(
                        "hud.howtogo.mode_switched", mode.label()),
                true);
        //?}
    }

    public static void setTarget(Destination destination) {
        target = destination;
        routeOriginX = Double.NaN;
        arrived = false;
        offRouteSince = 0L;
        clearWrongWay();
        tripDimension = currentDimension();
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) {
            tripOriginX = player.getX();
            tripOriginZ = player.getZ();
        } else {
            tripOriginX = destination.x();
            tripOriginZ = destination.z();
        }
        recompute();
    }

    /**
     * Starts navigating along a plan the picker already worked out.
     * The plan is handed over whole, not re-planned: re-planning could replace the chosen journey with a
     * quicker walk. The mode is swapped directly, not through setMode, but still counts as the player's
     * choice for config reloads; both origins are the player's current position.
     * A null or empty plan falls back to setTarget(Destination).
     * @param destination the place the plan leads to
     * @param plan the plan to follow, or null/empty
     */
    public static void setTarget(Destination destination, RouteOption plan) {
        if (destination == null) {
            return;
        }
        if (plan == null || !plan.isPresent()) {
            setTarget(destination);
            return;
        }
        target = destination;
        mode = plan.mode() == null ? mode : plan.mode();
        modeLoaded = true;
        modeChosen = true;
        route = plan.route();
        transitTrip = plan.trip();
        LocalPlayer player = Minecraft.getInstance().player;
        double x = player != null ? player.getX() : destination.x();
        double z = player != null ? player.getZ() : destination.z();
        routeOriginX = x;
        routeOriginZ = z;
        tripOriginX = x;
        tripOriginZ = z;
        tripDimension = currentDimension();
        arrived = false;
        offRouteSince = 0L;
        clearFallback();
        noteWalkInsteadOfRiding(plan, x, z, destination);
        clearWrongWay();
        turns.reset();
        HowToGo.LOGGER.info(
                "[HowToGo] route to {} for {} plan {}: {} points, {} blocks, {} turns, about {}",
                destination.name(), mode.id(), plan.label(), route.points().size(),
                Math.round(route.totalLength()), plan.turns(),
                Route.formatDuration(route.estimatedSeconds()));
    }

    /**
     * Records the reason behind a walk handed over as the plan for public transport.
     * The walk row exists only because no line was worth riding, and it is handed over under the
     * chosen mode; it goes through the fallback state so HUD and picker agree.
     */
    private static void noteWalkInsteadOfRiding(RouteOption plan, double x, double z,
                                               Destination destination) {
        if (plan.strategy() != RouteStrategy.WALK) {
            return;
        }
        abandonedMode = TravelMode.TRANSIT;
        abandonedSeconds = Double.NaN;
        walkingSeconds = route.estimatedSeconds();
        walkInsteadOfRiding = LinePlanner.closeEnoughToWalk(x, z, destination.x(), destination.z());
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
        clearInkCache();
    }

    private static String currentDimension() {
        net.minecraft.client.multiplayer.ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            // Drop the cached level on the way out; it holds a ClientLevel strongly.
            lastCheckedLevel = null;
            lastCheckedDimension = null;
            return null;
        }
        // A ClientLevel's dimension never changes, so the same reference answers the same string.
        if (level == lastCheckedLevel) {
            return lastCheckedDimension;
        }
        lastCheckedLevel = level;
        lastCheckedDimension = level.dimension().location().toString();
        return lastCheckedDimension;
    }

    public static double tripOriginX() {
        return tripOriginX;
    }

    public static double tripOriginZ() {
        return tripOriginZ;
    }

    public static void tick() {
        if (target == null) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            // The world is gone: a trip belongs to its world, so leaving ends it; there is no player to tell,
            // so the log is the only report.
            HowToGo.LOGGER.info("[HowToGo] the world was left while navigating to {}; the trip is ended",
                    target.name());
            clear();
            return;
        }
        LocalPlayer player = minecraft.player;
        if (player == null) {
            return;
        }
        double x = player.getX();
        double z = player.getZ();

        String dimension = currentDimension();
        if (tripDimension != null && dimension != null && !tripDimension.equals(dimension)) {
            HowToGo.LOGGER.info("[HowToGo] the trip was planned in {} and the player is now in {}; "
                    + "ending it rather than guiding the wrong world's roads", tripDimension, dimension);
            //? if >=26.1 {
            /*player.sendSystemMessage(Component.translatable("hud.howtogo.dimension_changed"));
            *///?} else {
            player.displayClientMessage(
                    Component.translatable("hud.howtogo.dimension_changed"), true);
            //?}
            clear();
            return;
        }

        // Kept current before anything can return early: the readout asks every frame, and the run of
        // ticks has to be sampled steadily.
        updateWrongWay(x, z);
        updateRoadClass();

        if (arrived) {
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

        // The route is not recalculated as the player walks: re-solving drags the start marker along and
        // makes progress meaningless. It is rebuilt only after a genuine, sustained departure.
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

    public static boolean showArrival() {
        return arrived && System.currentTimeMillis() - arrivedAtMillis < ARRIVAL_BANNER_MILLIS;
    }

    public static double travelled() {
        if (!route.isPresent()) {
            return 0;
        }
        return Math.max(0, route.totalLength() - remainingLength());
    }

    public static double remainingLength() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || !route.isPresent()) {
            return 0;
        }
        return route.remainingLength(player.getX(), player.getZ());
    }

    /**
     * Estimated seconds left, at the pace of the part still ahead.
     * No base speed passed in -- that would restate every drive as a walk -- and no single pace for the
     * route; see Route#remainingSeconds, which also carries the waiting to come.
     */
    public static double remainingSeconds() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || !route.isPresent()) {
            return 0;
        }
        return route.remainingSeconds(player.getX(), player.getZ());
    }

    /**
     * A manoeuvre ahead, with the distance already rebased onto the player.
     * @param distanceAhead blocks from the player to the junction, along the route
     * @param turnDegrees signed angle, positive right; zero for a straight-through call
     * @param roadName the road turned onto, or null
     * @param namesTheRoad whether the road is worth naming
     * @param continuesNamedRoad whether it carries the current road's name
     * @param straight whether the route carries straight through
     */
    public record Instruction(double distanceAhead, double turnDegrees, String roadName,
                              boolean namesTheRoad, boolean continuesNamedRoad, boolean straight) {
    }

    /**
     * Which turn is current, and which junctions have already been judged taken.
     * A class of its own so the state machine can be driven without the game; see TurnCursor.
     */
    private static final TurnCursor turns = new TurnCursor();

    private static final double UTURN_DEGREES = 135.0;

    /**
     * Angles at which travel counts as against the route, and back towards it, in degrees.
     * Two, because comparing noisy vectors with one value flickers; between them the answer stands.
     */
    private static final double WRONG_WAY_ENTER_DEGREES = 135.0;
    private static final double WRONG_WAY_EXIT_DEGREES = 100.0;

    private static final int WRONG_WAY_TICKS = 5;

    /**
     * How many segments the walk to the next junction follows before giving up.
     * A guard against a malformed network rather than a real limit.
     */
    private static final int WRONG_WAY_MAX_SEGMENTS = 64;

    /**
     * How many ticks in a row the player was read as going the wrong way, signed.
     * Clamped at WRONG_WAY_TICKS either way, so a one-tick velocity reversal from a step-up cannot
     * raise or drop the call.
     */
    private static int wrongWayRun;
    private static boolean wrongWay;
    private static double wrongWayJunctionX = Double.NaN;
    private static double wrongWayJunctionZ = Double.NaN;
    private static double wrongWayDistance = Double.NaN;
    private static boolean wrongWayHighway;
    /**
     * The last direction the player travelled while the wrong-way flag was up.
     * The flag is held while stationary -- dropping it is the flicker the run of ticks prevents -- so
     * the anchor must still be workable.
     */
    private static double wrongWayDirectionX;
    private static double wrongWayDirectionZ;

    /**
     * A U-turn call: the junction to turn around at and how far away it is.
     * @param distanceAhead blocks along the road to the junction, the countdown quantity
     * @param junctionX world x, which identifies the call (distance changes every step)
     * @param junctionZ world z
     * @param highway whether a U-turn is impossible, so the call is worded as carrying on
     */
    public record Uturn(double distanceAhead, double junctionX, double junctionZ, boolean highway) {
    }

    /**
     * The wrong-way U-turn call for right now, or null.
     * Up only when the player is on route and travelling roughly opposite the route; the junction named
     * is the next one they would reach -- the far end of the road, not their own position, since turning
     * round where they stand is not what a road offers.
     */
    public static Uturn wrongWayUturn() {
        if (!wrongWay || Double.isNaN(wrongWayJunctionX) || Double.isNaN(wrongWayDistance)) {
            return null;
        }
        return new Uturn(wrongWayDistance, wrongWayJunctionX, wrongWayJunctionZ, wrongWayHighway);
    }

    /**
     * Whether the player is read as going the wrong way, whether or not a junction was named.
     * The flag is itself an instruction: it must never fall through to "carry straight on".
     */
    public static boolean wrongWay() {
        return wrongWay;
    }

    /**
     * Keeps the wrong-way reading up to date, once per tick.
     * Counting from the render path would count each reader separately, and panel, map and voice would
     * clamp at different times.
     */
    private static void updateWrongWay(double x, double z) {
        double gap = wrongWayGap(x, z);
        if (!Double.isNaN(gap)) {
            if (gap >= WRONG_WAY_ENTER_DEGREES) {
                wrongWayRun = Math.min(wrongWayRun + 1, WRONG_WAY_TICKS);
            } else if (gap <= WRONG_WAY_EXIT_DEGREES) {
                wrongWayRun = Math.max(wrongWayRun - 1, -WRONG_WAY_TICKS);
            }
            // Between the two angles the reading stands: that band is the point of having two.
        }
        wrongWay = wrongWayRun >= WRONG_WAY_TICKS;
        if (!wrongWay) {
            wrongWayJunctionX = Double.NaN;
            wrongWayJunctionZ = Double.NaN;
            return;
        }
        resolveWrongWayJunction(x, z);
    }

    /**
     * How far the travel direction is from the route's, in degrees; NaN when nothing to compare.
     * Off route the answer is zero, since the re-plan deals with that.
     * Where a route passes the same ground twice the nearest point is ambiguous; when the nearest
     * direction says backwards, every other bearing here is offered the chance to agree with the player,
     * and one that does stands the reading down. See Route#bearingsNear.
     */
    private static double wrongWayGap(double x, double z) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || !route.isPresent()) {
            return 0;
        }
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
        double best = nearest;
        for (double other : route.bearingsNear(x, z, wrongWayReadingRadius())) {
            best = Math.min(best, MovementState.bearingGap(travel, other));
        }
        return best;
    }

    /**
     * How far from the player other directions still count as "here".
     * Wide enough for the opposite carriageway and a loop's return pass; tied to the widest on-road
     * tolerance.
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
     * The anchor is the first place ahead where the road stops being one road: a node three or more
     * segments meet at, or where it ends. Class and name changes are crossed, since they break the road
     * data without breaking the road. Distance is measured along the road, not straight, because the call
     * is how far the player must travel. Dropped when there is no road, no direction, or no walk.
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
            wrongWayDirectionX = vx;
            wrongWayDirectionZ = vz;
        } else {
            vx = wrongWayDirectionX;
            vz = wrongWayDirectionZ;
            if (Math.hypot(vx, vz) < MovementState.MOVING_SPEED) {
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
     * Takes the call down when no junction could be named, so the readout falls back to the plain
     * U-turn, the honest instruction when there is nowhere to point.
     */
    private static void failWrongWay() {
        wrongWayJunctionX = Double.NaN;
        wrongWayJunctionZ = Double.NaN;
        wrongWayDistance = Double.NaN;
    }

    /**
     * The other segment meeting a node, or null when the only one is excludeId.
     * Answered from the cached adjacency rather than scanning the network, which was once per step of
     * a walk of up to 64, per tick.
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
     * Distance from a point's projection on a segment to one of its end nodes, following vertices.
     * @param towardToNode true for the last vertex's node, false for the first
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

    private static double segmentEdgeLength(RoadSegment segment, int edgeIndex) {
        return Math.hypot(segment.x(edgeIndex) - segment.x(edgeIndex - 1),
                segment.z(edgeIndex) - segment.z(edgeIndex - 1));
    }

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


    /**
     * Counts road-class changes and remembers which.
     * A counter rather than a flag, so the voice can tell "there is something to say" from "already
     * said it" without either side clearing state; nothing is reset by the trip.
     */
    private static int classChangeCount;
    private static RoadClass classEntered;
    private static RoadClass classUnderfoot;
    private static RoadClass classCandidate;
    private static int classCandidateTicks;
    private static double classReadingDistance = Double.MAX_VALUE;
    /**
     * How far the class believed underfoot was in the same scan, or MAX_VALUE when nothing of it
     * was within tolerance; kept so a change is judged by how much nearer the new surface is.
     */
    private static double believedClassDistance = Double.MAX_VALUE;

    /**
     * How many ticks a new underfoot class has to hold before it is believed and counted.
     * Near a junction two roads compete for "nearest" and flip several times a second; five ticks is
     * long enough that a one-block flicker is never heard.
     */
    private static final int CLASS_CHANGE_HOLD_TICKS = 5;

    /**
     * How much nearer the new surface must be than the one believed underfoot before it counts.
     * A highway and its ramp keep two centrelines within tolerance, and which is nearer re-answers every
     * block. Two blocks is wider than that ambiguity and narrower than actually leaving one road.
     */
    private static final double CLASS_CHANGE_MARGIN_BLOCKS = 2.0;

    /**
     * Watches the road class underfoot for a change, once per tick.
     * Off the road is not a change, and the player must be moving: standing on a boundary would otherwise
     * be told about swaps in the nearest segment. A reading must also hold CLASS_CHANGE_HOLD_TICKS.
     */
    private static void updateRoadClass() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || !isMoving(player)) {
            classCandidate = null;
            classCandidateTicks = 0;
            return;
        }
        RoadClass now = currentRoadClass(mode());
        if (now == null || now == classUnderfoot) {
            classCandidate = null;
            classCandidateTicks = 0;
            return;
        }
        if (now != classCandidate) {
            classCandidate = now;
            classCandidateTicks = 1;
            return;
        }
        if (++classCandidateTicks < CLASS_CHANGE_HOLD_TICKS) {
            return;
        }
        // Held, but that is not enough: the new surface must also be the road actually travelled, or a
        // ramp beside the carriageway is announced and then the carriageway back. See
        // CLASS_CHANGE_MARGIN_BLOCKS.
        if (classUnderfoot != null
                && !isClearClassChange(believedClassDistance, classReadingDistance)) {
            classCandidate = null;
            classCandidateTicks = 0;
            return;
        }
        classCandidate = null;
        classCandidateTicks = 0;
        if (classUnderfoot != null) {
            classChangeCount++;
            classEntered = now;
        }
        classUnderfoot = now;
    }

    private static boolean isMoving(LocalPlayer player) {
        Vec3 delta = player.getDeltaMovement();
        return Math.hypot(delta.x, delta.z) >= MovementState.MOVING_SPEED;
    }

    /**
     * Whether a class change is clearly the road being travelled rather than an ambiguous reading
     * from two roads side by side.
     * Pure arithmetic and package-private so the rule can be checked without a game.
     * @param believedDistance distance of the class believed underfoot, or MAX_VALUE
     * @param readingDistance distance of the class the scan returned
     */
    static boolean isClearClassChange(double believedDistance, double readingDistance) {
        return believedDistance == Double.MAX_VALUE
                || readingDistance + CLASS_CHANGE_MARGIN_BLOCKS <= believedDistance;
    }

    public static int classChangeCount() {
        return classChangeCount;
    }

    public static RoadClass classEntered() {
        return classEntered;
    }

    public static String roadClassLabel(RoadClass roadClass) {
        if (roadClass == null) {
            return "";
        }
        return net.minecraft.network.chat.Component.translatable(
                "screen.howtogo.road_class." + roadClass.name().toLowerCase(Locale.ROOT)).getString();
    }

    /**
     * Localised name of a storey: surface for zero, overpass above, underpass below.
     * A phrase rather than a signed number: the number is a datum, the phrase is a place in the world.
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
     * Route positions are rebased onto the player here, once. Being level with a junction by distance is
     * not having taken it: a manoeuvre behind by distance is passed only once TurnCursor judges it taken,
     * and stays current at distance zero until then.
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
                maneuver.turnDegrees(), maneuver.roadName(), maneuver.namesTheRoad(),
                maneuver.continuesNamedRoad(), maneuver.straight());
    }

    public static String unnamedRoad() {
        return net.minecraft.network.chat.Component
                .translatable("hud.howtogo.unnamed_road").getString();
    }

    /**
     * Name of the road the player is on, the placeholder for an unnamed one, or null when off any
     * road.
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
     * The tolerance comes from the road class at the nearest route point, not a global constant.
     */
    private static boolean isOffRoute(double x, double z) {
        if (!route.isPresent()) {
            return false;
        }
        if (route.distanceTo(x, z) <= route.toleranceNear(x, z)) {
            return false;
        }
        // A station is a place, not a point: anywhere in its grounds is where the journey asked, or every
        // station would re-plan the trip the moment the player stepped off the line's tolerance.
        return !atAJourneyStop(x, z);
    }

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

    public static boolean isOffRoute() {
        LocalPlayer player = Minecraft.getInstance().player;
        return player != null && isOffRoute(player.getX(), player.getZ());
    }


    /**
     * How far before a junction the two calls are made, in blocks.
     * @param lead the approach call
     * @param now the junction counts as happening now
     */
    private record CallDistances(double lead, double now) {
    }

    private static int underfootBlockX = Integer.MIN_VALUE;
    private static int underfootBlockZ = Integer.MIN_VALUE;
    private static TravelMode underfootMode;
    private static RoadClass underfootClass;
    /**
     * The segment that class was read from, remembered with it; the wrong-way anchor needs it to
     * walk the road for a junction.
     */
    private static int underfootSegmentId = RoadSegment.NO_SEGMENT;

    /*
     * The distances are listed rather than derived from pace, because they are tuning values -- a
     * minecart's 300-block call is 37 seconds at 8 b/s, which no formula allows and inflating the pace
     * would corrupt every ETA. Non-inversion (now < lead) holds row by row and on the fallback.
     */
    private static final CallDistances WALK_DISTANCES = new CallDistances(50, 10);
    private static final CallDistances DRIVE_ROAD = new CallDistances(100, 20);
    private static final CallDistances DRIVE_HIGHWAY = new CallDistances(300, 30);
    private static final CallDistances TRANSIT_WATER = new CallDistances(100, 20);
    private static final CallDistances TRANSIT_RAIL = new CallDistances(300, 40);
    private static final CallDistances TRANSIT_ICE = new CallDistances(500, 60);

    private static final double TURN_LEAD_SECONDS = 12.0;
    private static final double TURN_LEAD_MIN_DISTANCE = 60.0;
    private static final double TURN_LEAD_MAX_DISTANCE = 480.0;
    private static final double TURN_NOW_SECONDS = 2.0;
    private static final double TURN_NOW_MIN_DISTANCE = 10.0;
    /**
     * Ceiling on the "now" distance on the fallback path.
     * Equal to TURN_LEAD_MIN_DISTANCE, which is half of why the two calls cannot invert.
     */
    private static final double TURN_NOW_MAX_DISTANCE = 60.0;

    /**
     * Distance ahead of a turn that it is announced from, in blocks, for the combination underfoot.
     * Never below turnNowDistance().
     */
    public static double turnLeadDistance() {
        return callDistances().lead();
    }

    /**
     * Distance at which a turn counts as happening now, in blocks.
     * Every reader -- panel, map, voice -- makes the now-or-in-N decision from this same number.
     */
    public static double turnNowDistance() {
        return callDistances().now();
    }

    /**
     * The listed distances for the combination underfoot, or the computed fallback.
     * One road lookup for both numbers, so panel and voice do not scan twice.
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
        // Not a combination the table names: no road underfoot, or one the mode cannot travel on.
        // Computed from the pace, because the player then moves at a speed the table cannot name, and a late
        // call is the worse mistake. Left unrounded, so the paths meet at an angle.
        double pace = paceUnderfoot(active, underfoot);
        return new CallDistances(
                Math.max(TURN_LEAD_MIN_DISTANCE,
                        Math.min(TURN_LEAD_MAX_DISTANCE, pace * TURN_LEAD_SECONDS)),
                Math.max(TURN_NOW_MIN_DISTANCE,
                        Math.min(TURN_NOW_MAX_DISTANCE, pace * TURN_NOW_SECONDS)));
    }

    /**
     * The table entry for a mode on a road class, or null when it has none.
     * A switch rather than a map, so the compiler insists every mode is answered for.
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
     * Pace in blocks per second the player is making on the road already found underfoot.
     * Reached mostly for the walker: either no road underfoot (off network, on a walked connector,
     * between roads) or one the mode cannot use. It used to return the mode's fastest class, which for
     * transit meant calling turns at forty blocks a second to a player walking at five.
     */
    static double paceUnderfoot(TravelMode active, RoadClass underfoot) {
        if (underfoot != null && active.speedOn(underfoot) > 0) {
            // Travelling by this mode on a surface the table does not name: the mode's own pace, the only
            // case for it.
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
     * Class of the road the player is standing on, or null when not on one.
     * Read from the network, not the route, which carries names and tolerances but not classes. "On" is
     * the router's own rule, and a class the mode has no pace on does not count.
     * Cached against the block the player stands in and the mode: the scan walks every segment and three
     * readers ask several times per frame.
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

    /**
     * The scan behind currentRoadClass: nearest usable class within its own tolerance.
     * Also records both distances, since whether a surface change is worth announcing is a question
     * about the two; see CLASS_CHANGE_MARGIN_BLOCKS.
     */
    private static RoadClass scanForRoadClass(TravelMode active, double x, double z) {
        RoadClass nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        underfootSegmentId = RoadSegment.NO_SEGMENT;
        RoadClass believed = classUnderfoot;
        double believedDistance = Double.MAX_VALUE;
        // The live view rather than a snapshot: this runs several times a second, and copying every
        // segment would be the expensive part.
        for (RoadSegment segment : RoadStore.get().segments()) {
            if (active.speedOn(segment.roadClass()) <= 0) {
                continue;
            }
            double distance = distanceToRoad(segment, x, z);
            if (distance > RoadConfig.onRoadTolerance(segment.roadClass())) {
                continue;
            }
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearest = segment.roadClass();
                underfootSegmentId = segment.id();
            }
            if (believed != null && segment.roadClass() == believed
                    && distance < believedDistance) {
                believedDistance = distance;
            }
        }

        // Nothing drawn underfoot: the machine-read rails count too, at their own pace and tolerance.
        // Both classes are read (MTR marks are rail for a train, water for a boat) and both layers, since
        // marks are ridden though not drawn.
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
                // A rail id from the layer, not the saved network: the wrong-way anchor finds nothing, which is
                // the same answer it gives off-road.
                underfootSegmentId = segment.id();
            }
        }

        // RoadWeaver's roads, read as the same kind of fallback: they are drawn and planned over, so a
        // player walking one is on a road. Boxed by the widest tolerance rather than walked whole.
        if (nearest == null && !RoadWeaverLayer.segments().isEmpty()) {
            double reach = 0;
            for (RoadClass roadClass : RoadClass.values()) {
                if (active.speedOn(roadClass) > 0) {
                    reach = Math.max(reach, RoadConfig.onRoadTolerance(roadClass));
                }
            }
            if (reach > 0) {
                double[] closest = {Double.MAX_VALUE};
                RoadClass[] found = {null};
                int[] foundSegment = {RoadSegment.NO_SEGMENT};
                RoadWeaverLayer.forEachSegmentInBox(x - reach, z - reach, x + reach, z + reach,
                        segment -> {
                            if (active.speedOn(segment.roadClass()) <= 0) {
                                return;
                            }
                            double distance = distanceToRoad(segment, x, z);
                            if (distance > RoadConfig.onRoadTolerance(segment.roadClass())
                                    || distance >= closest[0]) {
                                return;
                            }
                            closest[0] = distance;
                            found[0] = segment.roadClass();
                            // An id from the layer, not the saved network: the wrong-way anchor looks it up and finds nothing.
                            foundSegment[0] = segment.id();
                        });
                if (found[0] != null) {
                    nearestDistance = closest[0];
                    nearest = found[0];
                    underfootSegmentId = foundSegment[0];
                }
            }
        }
        // The two distances the notice is judged by, read from one pass so they describe the same instant.
        classReadingDistance = nearest == null ? Double.MAX_VALUE : nearestDistance;
        believedClassDistance = believedDistance;
        return nearest;
    }

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
     * @param maneuver a manoeuvre from nextManeuver(), or null when the road runs straight on
     */
    public static String maneuverInstruction(Instruction maneuver) {
        if (maneuver == null) {
            return net.minecraft.network.chat.Component
                    .translatable("hud.howtogo.hud_straight").getString();
        }
        return maneuverSentence(maneuver, maneuver.distanceAhead() <= turnNowDistance());
    }

    /**
     * The sentence for a turn, for the map readout and the voice alike.
     * The distance shown counts to the junction the instruction is about, and to no other: where the route
     * bends before a real fork, the bend is its own decision point. There is deliberately no "distance to
     * the next junction" beside an instruction about something else.
     * A U-turn is not worded as a turn onto a road; on a highway there is no turn, so it is worded as
     * carrying on. A straight-through junction is called as carrying straight on, and a continuing road
     * as continuing rather than entering.
     * @param now whether the junction is close enough to act on, which drops the distance
     */
    public static String maneuverSentence(Instruction maneuver, boolean now) {
        if (isUturn(maneuver.turnDegrees())) {
            return uturnSentence(maneuver.distanceAhead(), now, onHighway());
        }
        if (maneuver.straight()) {
            return now
                    ? net.minecraft.network.chat.Component
                            .translatable("hud.howtogo.hud_straight_now").getString()
                    : net.minecraft.network.chat.Component
                            .translatable("hud.howtogo.hud_straight_ahead",
                                    Route.formatDistance(maneuver.distanceAhead()))
                            .getString().trim();
        }
        String turn = turnPhrase(maneuver.turnDegrees());
        // Always name the road being entered, falling back to the placeholder, so the sentence never
        // quietly loses its destination.
        String road = maneuver.roadName() != null && !maneuver.roadName().isBlank()
                ? maneuver.roadName()
                : unnamedRoad();
        if (maneuver.continuesNamedRoad()) {
            return now
                    ? net.minecraft.network.chat.Component
                            .translatable(continuesKey(true), turn, road).getString()
                    : net.minecraft.network.chat.Component
                            .translatable(continuesKey(false),
                                    Route.formatDistance(maneuver.distanceAhead()), turn, road)
                            .getString().trim();
        }
        // Naming the road already being travelled tells the player nothing; the turn is still called,
        // the name is not.
        if (!maneuver.namesTheRoad()) {
            return now
                    ? net.minecraft.network.chat.Component
                            .translatable("hud.howtogo.hud_turn_now", turn).getString()
                    : net.minecraft.network.chat.Component
                            .translatable("hud.howtogo.hud_turn",
                                    Route.formatDistance(maneuver.distanceAhead()), turn)
                            .getString().trim();
        }
        if (now) {
            return net.minecraft.network.chat.Component
                    .translatable("hud.howtogo.hud_turn_now_named", turn, road).getString();
        }
        return net.minecraft.network.chat.Component.translatable("hud.howtogo.hud_turn_named",
                Route.formatDistance(maneuver.distanceAhead()), turn, road).getString().trim();
    }

    /**
     * The bare "continue on X" instruction for the readout, which has a distance slot of its own.
     * Same wording as maneuverSentence minus the countdown, so the two cannot drift.
     */
    public static String continuesAction(String turn, String road) {
        return Component.translatable(mode() == TravelMode.WALK
                ? "hud.howtogo.turn_continues_walk"
                : "hud.howtogo.turn_continues", turn, road).getString();
    }

    /**
     * The sentence key for "continue on the road that carries on".
     * Two keys per form rather than a verb in a slot, because the verb does not sit in the same place
     * in every language.
     */
    private static String continuesKey(boolean now) {
        return "hud.howtogo.hud_turn_continues"
                + (mode() == TravelMode.WALK ? "_walk" : "")
                + (now ? "_now" : "");
    }

    /**
     * The sentence for the guidance in force, for the map readout.
     * The wrong-way call comes first: a player travelling against the route has no use for the next turn.
     * With no junction named, the plain U-turn is said -- never the route's next turn, never "carry on".
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
            return uturnSentence(Double.NaN, true, onHighway());
        }
        return maneuverInstruction(nextManeuver());
    }

    /**
     * Whether station-style transit guidance is in force: asked for, a transit journey, and that
     * journey rides something. Only the riding is governed; the walk legs are ordinary turns.
     */
    public static boolean transitGuidance() {
        return RoutePreferenceStore.transitBoardOnly() && transitPlanInForce();
    }

    /**
     * Whether there is a transit journey for the guidance to be about at all.
     * Separate from transitGuidance, so the self-test can ask what a journey would say regardless of
     * the player's readout setting.
     */
    private static boolean transitPlanInForce() {
        return route.isPresent() && transitTrip != null && transitTrip.ridesAnything();
    }

    /** What the transit guidance is about at this moment. */
    public enum TransitCue {

        /** Walking to the line's first stop: say which line and which way. */
        BOARD,

        /** On board between stops: say which stop is coming and what is left. */
        RIDE,

        /** Nearing the stop to get off at: say it is time to get ready. */
        ALIGHT,

        /** Standing at the stop this ride is left at: get off here. */
        ARRIVE
    }

    /**
     * One thing the transit guidance has to say.
     * @param cue what kind of moment this is
     * @param transfer whether the journey changes lines at this stop
     * @param line the line ridden or about to be boarded
     * @param terminus the far end of that line in the direction of travel
     * @param station the stop this cue is about
     * @param reached the last stop the ride has called at
     * @param next the stop being run to, or null
     * @param stopsRemaining stops left before the one this ride is left at
     * @param distanceAhead blocks along the journey to the point this cue is about
     * @param rideIndex which ride, or -1 when not begun
     * @param stopIndex which stop of that ride, so a repeat can be told from a new stop
     */
    public record TransitStep(TransitCue cue, boolean transfer, String line, String terminus,
                              String station, String reached, String next, int stopsRemaining,
                              double distanceAhead, int rideIndex, int stopIndex) {
    }

    public static TransitStep transitStep() {
        if (!transitGuidance()) {
            return null;
        }
        LocalPlayer player = Minecraft.getInstance().player;
        return transitStepAt(player == null ? Double.NaN : player.getX(),
                player == null ? Double.NaN : player.getZ(), travelled());
    }

    /**
     * The same reading at a given position and distance along the route, without the readout switch.
     * The seam lets the self-test walk a planned ride and check what a passenger would hear.
     * @param x where the player would be
     * @param z where the player would be
     * @param travelled how far along the route they would have come
     */
    static TransitStep transitStepAt(double x, double z, double travelled) {
        if (!transitPlanInForce()) {
            return null;
        }
        Trip trip = transitTrip;
        List<Trip.Ride> rides = trip.rides();

        // Arriving at the stop being got off at outranks "still on the way": this is the moment the player
        // must act, and it is the one the guidance used to miss.
        int arrived = arrivedAt(rides, travelled, x, z);
        if (arrived >= 0) {
            Trip.Ride ride = rides.get(arrived);
            Trip.RideStop stop = ride.stops().get(ride.stops().size() - 1);
            Trip.Ride next = arrived + 1 < rides.size() ? rides.get(arrived + 1) : null;
            // The change is named here as well as on the approach: at a station both lines call at there is
            // no walk, so no later boarding prompt can carry the news.
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
                // Which line is changed onto is carried here as well as at the stop: on a long ride the approach
                // is where it is first needed, and the platform is where it is acted on.
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
        // On foot: the next boarding is worth saying once it is close enough to act on.
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
     * By position, not distance: the ride is over the instant its last stop is reached. The newest such
     * ride wins, so a just-changed line reports the platform stood on.
     */
    private static int arrivedAt(List<Trip.Ride> rides, double travelled, double x, double z) {
        if (Double.isNaN(x) || Double.isNaN(z)) {
            return -1;
        }
        for (int i = rides.size() - 1; i >= 0; i--) {
            Trip.Ride ride = rides.get(i);
            if (ride.alightAt() > travelled + 1.0) {
                continue;
            }
            if (travelled - ride.alightAt() > STOP_ARRIVAL_DISTANCE) {
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
     * No distance in the words -- both readouts have a slot for the number; the spoken form carries it.
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

    /** What a name that may be missing is called, so a sentence never loses its subject. */
    public static String named(String name) {
        return name == null || name.isBlank()
                ? Component.translatable("hud.howtogo.unnamed_station").getString()
                : name;
    }

    /**
     * Whether a turn of this angle is a U-turn, which is worded differently and may be refused.
     * The same angle turnPhrase uses, so the two cannot drift.
     */
    public static boolean isUturn(double degrees) {
        return Math.abs(degrees) > UTURN_DEGREES;
    }

    public static boolean onHighway() {
        return currentRoadClass(mode()) == RoadClass.HIGHWAY;
    }

    /**
     * The U-turn sentence, for the map readout and the voice alike.
     * On a highway it is not a U-turn: there is nowhere to turn, so the call becomes carrying on to the
     * next junction -- same junction, same distance, different instruction.
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

    public static String uturnAction(boolean highway) {
        return net.minecraft.network.chat.Component.translatable(highway
                ? "hud.howtogo.turn.next_junction"
                : "hud.howtogo.turn.uturn").getString();
    }

    /**
     * Human-readable phrase for a signed turn angle, positive being a right turn.
     * Shared so the fullscreen map and the HUD describe turns identically.
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


    /**
     * A route planned for the picker, with nothing of the live trip in it.
     * The journey travels with the route because a route cannot say where the riding begins, and the maps
     * colour a transit trip stretch by stretch.
     * @param route the plan, or Route#empty()
     * @param trip the journey, or null when it rides nothing
     * @param originX where it starts, which is the player
     * @param originZ where it starts, which is the player
     * @param destination what it leads to
     * @param note why there is no plan, or null
     */
    public record RoutePreview(Route route, Trip trip, double originX, double originZ,
                               Destination destination, String note) {

        public boolean isPresent() {
            return route != null && route.isPresent();
        }
    }

    /**
     * Plans a route from the player to a destination for the picker to draw before anything is
     * committed.
     * Pure over its arguments: the live session is untouched, so opening the picker mid-trip cannot
     * hijack it. The walking baseline is deliberately not applied -- a preview shows what the chosen mode
     * does, and previewing the walk would make every mode look identical.
     * @param destination where the trip would end
     * @param planMode the mode to plan for, or null for the mode in force
     * @param preferences the policy, or null for the policy in force
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
        // The plan and the explanation are made on the same network the live route will use, so a preview
        // cannot be refused for something that network did not contain.
        boolean wantsMarks = active != TravelMode.TRANSIT
                || !MtrTransit.everyLineRidesItsOwnTrack(linesInPlay());
        RoadNetwork network = RailTrackStore.forRouting(active, policy, wantsMarks);

        Planned plan = planRoute(network, active, policy, x, z, destination);
        Route planned = plan.route();
        // The journey behind the route, kept for drawing rather than guidance: only it knows which stretch
        // rides which line. Null for every mode but public transport.
        Trip journey = plan.trip();
        if (!planned.isPresent() && active != TravelMode.WALK
                && RoadConfig.fallBackToWalkingWhenSlower()) {
            // The same walking comparison the live route makes, so the line the picker draws is the line the
            // HUD guides along. Applied only when there is no journey at all: an existing transit journey is
            // never previewed as a walk.
            planned = RoadRouter.findRoute(RailTrackStore.forRouting(TravelMode.WALK, policy), x, z,
                    destination.x(), destination.z(), destination.name(), TravelMode.WALK, policy);
            planned = timedForRequested(planned, active);
            // The line is the walker's now, whatever mode was asked for.
            journey = null;
        }
        if (!planned.isPresent()) {
            // Said out loud here because a preview that finds nothing shows the reason in the picker and
            // nowhere else. The reason names the branch, which is what makes it answerable; the log uses the
            // translation key, the picker the sentence.
            RouteFailure why = RoadRouter.explainFailure(network, x, z, destination.x(),
                    destination.z(), active, policy);
            HowToGo.LOGGER.info("[HowToGo] no route to {} for {} from ({}, {}): {}",
                    destination.name(), active.id(), Math.round(x), Math.round(z), why);
            return new RoutePreview(planned, journey, x, z, destination, failureText(why));
        }
        return new RoutePreview(planned, journey, x, z, destination, null);
    }

    /**
     * Every plan the picker may offer for one destination, quickest first, or empty.
     * Pure like preview. Unlike preview it does not fall back to walking -- the list answers "which way",
     * and a walk offered as the only row of a drive would be a different journey. Cached by the question
     * (see plansCache, worldStamp); the result is a copy.
     * @param destination where the trip would end, or null for no plans
     * @return up to RouteAlternatives#MAX_PLANS plans, or an empty list
     */
    public static List<RouteOption> plans(Destination destination) {
        if (destination == null) {
            return List.of();
        }
        TravelMode active = mode();
        RoutePreferences policy = RoutePreferenceStore.preferences();
        LocalPlayer player = Minecraft.getInstance().player;
        double x = player != null ? player.getX() : destination.x();
        double z = player != null ? player.getZ() : destination.z();
        PlansKey key = new PlansKey(destination, active, policy,
                Math.round(x / PLANS_CACHE_ROUNDING), Math.round(z / PLANS_CACHE_ROUNDING),
                currentDimension(), worldStamp());
        List<RouteOption> cached = plansCache.get(key);
        if (cached != null) {
            return cached;
        }
        List<RouteOption> plans = List.copyOf(planAlternatives(active, policy, x, z, destination));
        plansCache.put(key, plans);
        // Bounded by hand: the eviction rule is one loop, and the oldest question is the first key.
        while (plansCache.size() > PLANS_CACHE_CAPACITY) {
            Iterator<PlansKey> oldest = plansCache.keySet().iterator();
            oldest.next();
            oldest.remove();
        }
        return plans;
    }

    /**
     * One strategy question asked of the route package; the mode decides how it is asked.
     * The transit branch mirrors planRoute so the plans the picker compares are plans the navigation
     * can follow; the road branch asks for alternatives over the same merged network.
     */
    private static List<RouteOption> planAlternatives(TravelMode active, RoutePreferences policy,
                                                      double x, double z, Destination destination) {
        if (active == TravelMode.TRANSIT) {
            List<TransitLine> lines = linesInPlay();
            // Asked once, before the network is built: lines that bring their own track make no use of MTR's
            // shared layer, so the copy that merges it is not paid for.
            boolean ownTracksOnly = MtrTransit.everyLineRidesItsOwnTrack(lines);
            RoadNetwork network = RailTrackStore.forRouting(active, policy, !ownTracksOnly);
            RoadNetwork plain = ownTracksOnly ? network
                    : RailTrackStore.forRouting(active, policy, false);
            return RouteAlternatives.transit(
                    RideRoads.of(network, plain, MtrTransit::marksEnabled, MtrTransit::trackOf),
                    lines, x, z, destination.x(), destination.z(), destination.name(), policy);
        }
        RoadNetwork network = RailTrackStore.forRouting(active, policy, true);
        List<RouteOption> plans = RouteAlternatives.roads(network, x, z, destination.x(),
                destination.z(), destination.name(), active, policy);
        if (plans.isEmpty() && active != TravelMode.WALK
                && RoadConfig.fallBackToWalkingWhenSlower()) {
            // The fallback the single preview makes, for the same reason: a car cannot leave the road far
            // enough to reach a destination just off it, so plan on foot and time the line as the chosen mode.
            Route walked = RoadRouter.findRoute(RoadRouter.workspaceFor(network), x, z,
                    destination.x(), destination.z(), destination.name(), TravelMode.WALK, policy);
            Route timed = timedForRequested(walked, active);
            if (timed.isPresent()) {
                boolean shortestFirst = policy.metric()
                        == bili.dongsz.howtogo.route.RoutePreference.SHORTEST_DISTANCE;
                plans = List.of(new RouteOption(timed, null, active, shortestFirst
                        ? bili.dongsz.howtogo.route.RouteStrategy.SHORTEST
                        : bili.dongsz.howtogo.route.RouteStrategy.FASTEST));
            }
        }
        return plans;
    }

    /**
     * A cheap version of everything a plan reads besides the request itself.
     * A cached list must expire when the world moves: the roads, the Create and MTR rails, RoadWeaver's
     * roads, the transit lines and the stores' switches all publish a version, so the stamp folds those
     * rather than hashing geometry. The lines have no version, so identity, size, stop counts and
     * direction are folded in instead.
     */
    private static long worldStamp() {
        RoadNetwork handDrawn = RoadStore.get();
        // The network itself as well as its revision: a new world's revision restarts at zero.
        long stamp = System.identityHashCode(handDrawn);
        stamp = stamp * 31 + handDrawn.revision();
        stamp = stamp * 31 + RailTrackStore.layerVersion();
        stamp = stamp * 31 + MtrTransit.tracksVersion();
        stamp = stamp * 31 + MtrMarks.version();
        stamp = stamp * 31 + CreateTrackGraph.version();
        stamp = stamp * 31 + RoadWeaverLayer.revision();
        stamp = stamp * 31 + AddonRoadStore.revision();
        List<TransitLine> lines = TransitLineStore.get();
        stamp = stamp * 31 + System.identityHashCode(lines);
        for (TransitLine line : lines) {
            stamp = stamp * 31 + line.stopCount();
            stamp = stamp * 31 + line.direction().ordinal();
        }
        return stamp;
    }

    /**
     * The question a cached plan list answers.
     * The origin is rounded to PLANS_CACHE_ROUNDING blocks, since a plan's first step starts at a node
     * that a few blocks of drift does not move. The destination is the record, so equality matches the
     * picker's own idea of one place.
     */
    private record PlansKey(Destination destination, TravelMode mode, RoutePreferences preferences,
                            long originX, long originZ, String dimension, long worldStamp) {
    }

    /**
     * A routing failure in the client's language.
     * The one place a RouteFailure becomes text; the route package cannot translate, so it hands back a
     * key and arguments. An argument that is itself a failure is resolved the same way, so a message can
     * carry a clause.
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
        // A new plan renumbers every junction, so old verdicts mean nothing and must not suppress a turn.
        turns.reset();
        // The store, not the config: picker buttons change the policy between two plans.
        RoutePreferences preferences = RoutePreferenceStore.preferences();
        TravelMode active = mode();
        // A transit plan over lines that each know their own track makes no use of MTR's shared layer,
        // so it is left out before the network is built -- the copy is what is being avoided.
        boolean wantsMarks = active != TravelMode.TRANSIT
                || !MtrTransit.everyLineRidesItsOwnTrack(linesInPlay());
        RoadNetwork usable = RailTrackStore.forRouting(active, preferences, wantsMarks);
        // Public transport first, as a journey of legs: one route in one mode cannot say where the riding
        // begins. The plain route is still the fallback.
        Planned planned = planRoute(usable, active, preferences, x, z, target);
        Route plannedRoute = planned.route();
        // The journey behind the route, taken before the walking comparison: a route that turns out to be
        // a walk is not that journey.
        transitTrip = planned.trip();
        clearFallback();

        // Walking is the comparison every other mode must beat, so it is planned whenever another mode was
        // asked for. A mode is a preference, not a promise to travel badly.
        if (active != TravelMode.WALK && RoadConfig.fallBackToWalkingWhenSlower()) {
            Route onFoot = RoadRouter.findRoute(
                    RailTrackStore.forRouting(TravelMode.WALK, preferences),
                    x, z, target.x(), target.z(), target.name(), TravelMode.WALK, preferences);
            if (onFoot.isPresent() && losesToWalking(plannedRoute, onFoot)) {
                // A transit journey that exists is not taken away because a walk is quicker: the player asked to
                // go by line, and a route that quietly becomes a walk looks like the mode is broken. The comparison
                // is still made and logged, and the walk can be offered by the picker. A journey the lines cannot
                // carry is still answered with the walk.
                if (active == TravelMode.TRANSIT && plannedRoute.isPresent()) {
                    HowToGo.diagnostic(
                            "[HowToGo] transit is slower than walking ({} vs {}); keeping transit, "
                                    + "the walk is an alternative rather than a replacement",
                            Route.formatDuration(plannedRoute.estimatedSeconds()),
                            Route.formatDuration(onFoot.estimatedSeconds()));
                } else if (active == TravelMode.DRIVE && !plannedRoute.isPresent()) {
                    // No road the car may use comes within its connector distance of one end. The walker's line is
                    // kept and timed for the drive, and nothing is noted as abandoned, because the chosen mode is still
                    // the one being navigated.
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
                    // A walk is not the journey transit guidance is about, even when the mode still says transit.
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
     * Every line a journey may be planned over: the player's own and the ones read out of MTR.
     * Kept apart because the owners differ: the player's are editable and saved, MTR's are rebuilt and
     * must never be written back. The editor therefore cannot reach an imported line at all.
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
     * A plan: the route to draw and the journey it was flattened from.
     * They must be the same plan, or the guidance would describe a journey it is not navigating. Null
     * for every mode but public transport.
     */
    private record Planned(Route route, Trip trip) {
    }

    /**
     * Plans a route in one mode, as a transit journey when that is the mode.
     * One place, because the picker's preview and the live route must agree; a preview planned by a
     * different rule would show a route abandoned the moment the button is pressed. The plain route is
     * the fallback rather than an error.
     */
    private static Planned planRoute(RoadNetwork network, TravelMode mode,
                                     RoutePreferences preferences, double x, double z,
                                     Destination target) {
        if (mode == TravelMode.TRANSIT) {
            // No fallback of any kind: a route boarding at the nearest point of a line nobody chose is a
            // wrong answer, not a worse one. When no line can carry the trip the answer is empty, and the
            // walking comparison may offer the walk.
            List<TransitLine> lines = linesInPlay();
            // Which roads a ride runs on depends on the line: a line with its own marks switched off needs
            // the network that never had them, not one that declined to add them. The second copy is made only
            // when a line wants the difference. Walking legs never want MTR's layer.
            boolean ownTracksOnly = MtrTransit.everyLineRidesItsOwnTrack(lines);
            RoadNetwork plain = ownTracksOnly ? network
                    : RailTrackStore.forRouting(mode, preferences, false);
            // Planned as a journey and then flattened: the boarding and alighting stations exist only on the
            // journey, and both callers go through the same two calls.
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
     * Whether the chosen mode has nothing to offer over walking. A mode that found no route counts,
     * not only a slow one.
     */
    private static boolean losesToWalking(Route planned, Route onFoot) {
        return !planned.isPresent() || planned.estimatedSeconds() > onFoot.estimatedSeconds();
    }

    /**
     * The walker's line, timed for the mode the player asked for.
     * A mode that finds no route is answered with a plan made on foot, since only the walker can leave
     * the network. For a drive that plan is not a walk: the trip is driven up to the last road and walked
     * from there, so timing it all at walking pace reported an hour of walking. Walking needs nothing,
     * and transit deliberately gets nothing.
     */
    private static Route timedForRequested(Route planned, TravelMode active) {
        return active == TravelMode.DRIVE ? planned.timedFor(TravelMode.DRIVE) : planned;
    }

    private static void clearFallback() {
        abandonedMode = null;
        abandonedSeconds = Double.NaN;
        walkingSeconds = 0;
        walkInsteadOfRiding = false;
    }

    /** Records that the chosen mode was dropped, and says so in the log, where the numbers survive. */
    private static void noteFallback(TravelMode abandoned, Route planned, Route onFoot,
                                     RoutePreferences preferences) {
        abandonedMode = abandoned;
        walkingSeconds = onFoot.estimatedSeconds();
        // A transit plan with no journey and a walkable destination is the one case where walking is not
        // the mode failing: no line was worth taking. Asked of the same distance LinePlanner refuses a ride
        // for, so plan and explanation cannot disagree.
        walkInsteadOfRiding = abandoned == TravelMode.TRANSIT && !planned.isPresent()
                && LinePlanner.closeEnoughToWalk(routeOriginX, routeOriginZ, target.x(), target.z());
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
     * Logs the plan that was kept, named from the route rather than the selected mode, since a
     * fallback has just made those different.
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
                    mode().id(), RoadRouter.explainFailure(
                            RailTrackStore.forRouting(mode(), preferences), routeOriginX,
                            routeOriginZ, target.x(), target.z(), mode(), preferences));
        }
    }
}
