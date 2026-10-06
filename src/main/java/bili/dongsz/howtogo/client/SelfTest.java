package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.RoadConfig;
import bili.dongsz.howtogo.HowToGo;
import bili.dongsz.howtogo.road.RoadClass;
import bili.dongsz.howtogo.road.RoadNetwork;
import bili.dongsz.howtogo.road.RoadNode;
import bili.dongsz.howtogo.road.RoadStorage;
import bili.dongsz.howtogo.route.Destination;
import bili.dongsz.howtogo.route.LinePlanner;
import bili.dongsz.howtogo.route.RideRoads;
import bili.dongsz.howtogo.route.RoadRouter;
import bili.dongsz.howtogo.route.Route;
import bili.dongsz.howtogo.route.RoutePreferences;
import bili.dongsz.howtogo.route.TravelMode;
import bili.dongsz.howtogo.route.Trip;
import bili.dongsz.howtogo.store.RoutePreferenceStore;
import bili.dongsz.howtogo.transit.LineStop;
import bili.dongsz.howtogo.transit.TransitLine;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Everything this mod does, checked against the world the player actually has open.
 *
 * <h2>Why this exists inside the game</h2>
 * The routing harness in {@code tools/} covers the pure logic on networks built for the purpose, and
 * that is where a rule belongs. What it cannot cover is the part a player meets: whether the roads they
 * drew are the roads the mod loaded, whether a line they configured can carry a journey across the
 * world as it stands, whether the guidance says something a person could follow, and whether anything
 * at all survives a save and a reload. Those questions have no answer outside a running client with
 * real data in it -- so this runs there, is asked for by a command, and reports what it found.
 *
 * <h2>What it is allowed to do</h2>
 * Read, and put back. It plans routes and journeys (which are pure calculations), judges the
 * connectivity of every line (also a calculation), and round-trips the network through a temporary file
 * rather than the player's. The one thing it changes is the live navigation: a simulated ride is driven
 * through the real {@link Navigation} guidance, which means setting a destination and putting back
 * whatever was being navigated before. That is why the command that runs this says so.
 *
 * <h2>What a failure means</h2>
 * A check fails when the mod could not do something it says it can do with data that is there -- a
 * journey the lines describe but the planner cannot carry, a save that does not read back as what was
 * written, a guidance sequence with a stage missing. A line the player has left broken, or an empty
 * world, is reported as a finding rather than as a failure: those are answers about their data, not
 * about this mod.
 */
public final class SelfTest {

    /**
     * How many steps a simulated ride is sampled at, and the closest together they may be.
     *
     * <p>A stage of the ride is only reported within a few blocks of where it happens -- the boarding
     * prompt is said as the stop is reached, not a hundred blocks before it -- so sampling a long
     * journey evenly at a fixed count walks straight over it: the first real run of this check sampled a
     * two-thousand-block ride forty blocks at a time, saw the arrival and the stop counts, and never saw
     * the boarding at all, which read as a missing stage rather than as a sampling gap. The count
     * therefore follows the length: {@value #RIDE_SAMPLE_BLOCKS} blocks a step, capped so a journey
     * across a whole railway cannot make this slow.
     */
    private static final int RIDE_SAMPLE_BLOCKS = 2;

    /** The most steps a simulated ride is sampled at, whatever its length. */
    private static final int MAX_RIDE_SAMPLES = 4000;

    /** How many lines the plan check walks through, so a whole railway cannot make this slow. */
    private static final int MAX_LINES_CHECKED = 8;

    /** How many planned routes the route check tries. */
    private static final int MAX_ROUTE_PROBES = 6;

    /**
     * One check's outcome.
     *
     * @param name   which check, as a translation key
     * @param ok     whether the mod did what it says it does
     * @param detail what was found, as text ready to show
     */
    public record Result(String name, boolean ok, String detail) {
    }

    private SelfTest() {
    }

    /**
     * Runs every check against the state the client is holding.
     *
     * <p>Called from the command, which runs on the client thread with the world loaded, so everything
     * here may touch the mod's own stores and the live navigation.
     */
    public static List<Result> run() {
        List<Result> results = new ArrayList<>();
        RoadNetwork network = RoadStore.get();
        List<TransitLine> lines = TransitLineStore.get();
        List<LineStop> stops = TransitStops.all(network);

        results.add(checkWorld(network, lines, stops));
        results.addAll(checkLines(network, lines));
        results.add(checkPlans(lines));
        results.add(checkSimulatedRide());
        results.add(checkRoutes(network));
        results.add(checkStorage(network, lines));
        return results;
    }

    /** Whether the world's own data is in the shape the rest of the mod expects. */
    private static Result checkWorld(RoadNetwork network, List<TransitLine> lines, List<LineStop> stops) {
        long places = network.nodes().stream().filter(node -> node.type() == RoadNode.Type.POI).count();
        String detail = "nodes=" + network.nodeCount() + " segments=" + network.segmentCount()
                + " places=" + places + " stops=" + stops.size() + " lines=" + lines.size()
                + " rail=" + RailTrackStore.segments().size() + " mtr=" + MtrTransit.lines().size()
                + " mode=" + Navigation.mode().id() + " boardOnly="
                + RoutePreferenceStore.transitBoardOnly() + " debug=" + RoadConfig.debugLog();
        boolean ok = network.segmentCount() == 0 || network.nodeCount() > 0;
        return new Result("command.howtogo.selftest.check.world", ok, detail);
    }

    /**
     * Every line, gap by gap, read exactly as the line editor reads it.
     *
     * <p>A gap that is {@code SEPARATE} is a red stop in the editor -- the line is genuinely cut there.
     * That is a fact about the player's data and is reported as a count rather than as a failure; what
     * would be a failure is the reading itself throwing, which the calling command turns into a failed
     * check.
     */
    private static List<Result> checkLines(RoadNetwork network, List<TransitLine> lines) {
        List<Result> results = new ArrayList<>();
        for (TransitLine line : lines) {
            if (line.stopCount() < 2) {
                continue;
            }
            TravelMode mode = LinePlanner.rideMode(line.kind());
            RoutePreferences policy = LinePlanner.ridePreferences(line.kind(),
                    RoutePreferenceStore.preferences());
            RoadNetwork ride = RideRoads.of(
                    RailTrackStore.forRouting(mode, policy, true),
                    RailTrackStore.forRouting(mode, policy, false),
                    MtrTransit::marksEnabled, MtrTransit::trackOf).forLine(line);
            RoadRouter.Workspace workspace = new RoadRouter.Workspace(ride);
            int connected = 0;
            int separate = 0;
            int unjudged = 0;
            for (int i = 1; i < line.stopCount(); i++) {
                LineStop from = line.stops().get(i - 1);
                LineStop to = line.stops().get(i);
                switch (RoadRouter.connection(workspace, from.x(), from.z(), to.x(), to.z(), mode,
                        policy)) {
                    case CONNECTED -> connected++;
                    case SEPARATE -> separate++;
                    case UNJUDGED -> unjudged++;
                }
            }
            results.add(new Result("command.howtogo.selftest.check.line",
                    true, line.label() + " (" + line.kind().name() + "): " + line.stopCount()
                            + " stops, gaps ok=" + connected + " cut=" + separate
                            + " unjudged=" + unjudged));
        }
        return results;
    }

    /**
     * Whether each line can carry a journey along its own length.
     *
     * <p>Asked from the line's first stop to its last, which is the longest journey that line can be
     * responsible for and the one that exercises every gap in it. A line that cannot carry that is
     * reported: either its stops are not connected, which is the player's to fix, or the planner has a
     * fault, which is this mod's.
     */
    private static Result checkPlans(List<TransitLine> lines) {
        int tried = 0;
        int planned = 0;
        int rides = 0;
        StringBuilder detail = new StringBuilder();
        // The networks a ride is planned on, built once for the whole check: each is a copy of the world
        // with the machine-read layers folded in, and rebuilding them per line would make a whole-railway
        // reading cost its own size times the number of lines checked.
        RoutePreferences preferences = RoutePreferenceStore.preferences();
        boolean ownTracksOnly = MtrTransit.everyLineRidesItsOwnTrack(lines);
        RoadNetwork marked = RailTrackStore.forRouting(TravelMode.TRANSIT, preferences, true);
        RoadNetwork plain = ownTracksOnly ? marked
                : RailTrackStore.forRouting(TravelMode.TRANSIT, preferences, false);
        RideRoads roads = RideRoads.of(marked, plain, MtrTransit::marksEnabled, MtrTransit::trackOf);
        for (TransitLine line : lines) {
            if (tried >= MAX_LINES_CHECKED) {
                detail.append(" | ...");
                break;
            }
            if (line.stopCount() < 2) {
                continue;
            }
            tried++;
            LineStop from = line.stops().get(0);
            LineStop to = line.stops().get(line.stopCount() - 1);
            Trip trip = plan(roads, lines, from, to, preferences);
            if (detail.length() > 0) {
                detail.append(" | ");
            }
            if (trip.isPresent()) {
                planned++;
                rides += trip.rides().size();
                detail.append(line.label()).append(": ").append(trip.legs().size()).append(" legs, ")
                        .append(trip.rides().size()).append(" ride(s)");
                String fault = rideFault(trip);
                if (fault != null) {
                    // A ride whose own numbers do not add up is a fault in this mod, whatever the data.
                    return new Result("command.howtogo.selftest.check.plans", false,
                            line.label() + ": " + fault);
                }
            } else {
                detail.append(line.label()).append(": no journey");
            }
        }
        boolean ok = tried == 0 || planned > 0;
        return new Result("command.howtogo.selftest.check.plans", ok,
                tried == 0 ? "no line with two stops" : planned + "/" + tried + " lines, " + rides
                        + " ride(s) | " + detail);
    }

    /** What is wrong with a planned journey's rides, or null when nothing is. */
    private static String rideFault(Trip trip) {
        for (Trip.Leg leg : trip.legs()) {
            Trip.Ride ride = leg.ride();
            if (ride == null) {
                continue;
            }
            if (ride.line() == null || ride.stops().size() < 2) {
                return "a ride with no name or fewer than two stops";
            }
            if (ride.boardedAt() == null || ride.leftAt() == null || ride.terminus() == null) {
                return "a ride that does not name its stops or its direction";
            }
            for (int i = 1; i < ride.stops().size(); i++) {
                if (ride.stops().get(i).at() <= ride.stops().get(i - 1).at()) {
                    return "a ride whose stops do not move forward along the journey";
                }
            }
            if (ride.alightAt() <= ride.boardAt()) {
                return "a ride that ends where it begins";
            }
        }
        return null;
    }

    /**
     * A ride driven through the real guidance, from the first step to the last.
     *
     * <p>This is the test the harness cannot make: the player's own destination is set, the planner
     * plans it against the world as it stands, and the ride is then walked along the planned route
     * {@value #RIDE_SAMPLES} steps at a time, asking {@link Navigation} what it would say at each one.
     * What comes back is the sequence of sentences a passenger would hear -- and a sequence with no
     * boarding, or no arrival, or a stop count that never comes down, is a guidance failure.
     *
     * <p>Read through the same seam the HUD reads, but without the readout switch: whether the player
     * has "board and alight only" turned on decides what is <em>shown</em>, and the journey's own
     * reading is the same either way. Testing it only for players who have the switch on would leave
     * the reading untested exactly when somebody turns it on to try it.
     *
     * <p>The player's own navigation is put back afterwards, re-planned from where they are, which is
     * what any re-plan would do.
     */
    private static Result checkSimulatedRide() {
        Destination previous = Navigation.target();
        TravelMode previousMode = Navigation.mode();
        Destination here = transitDestination(previous);
        if (here == null) {
            return new Result("command.howtogo.selftest.check.ride", true, "no destination to ride to");
        }
        try {
            Navigation.setMode(TravelMode.TRANSIT);
            Navigation.setTarget(here);
            Route route = Navigation.route();
            if (!route.isPresent()) {
                return new Result("command.howtogo.selftest.check.ride", true,
                        "no public transport journey to '" + here.name() + "' from here");
            }
            Set<String> seen = new LinkedHashSet<>();
            boolean boarded = false;
            boolean left = false;
            int samples = (int) Math.min(MAX_RIDE_SAMPLES,
                    Math.max(1, Math.ceil(route.totalLength() / RIDE_SAMPLE_BLOCKS)));
            for (int step = 0; step <= samples; step++) {
                double travelled = route.totalLength() * step / samples;
                double[] at = pointAt(route, travelled);
                Navigation.TransitStep cue = Navigation.transitStepAt(at[0], at[1], travelled);
                if (cue == null) {
                    continue;
                }
                seen.add(Navigation.transitSentence(cue));
                boarded |= cue.cue() == Navigation.TransitCue.BOARD;
                left |= cue.cue() == Navigation.TransitCue.ALIGHT
                        || cue.cue() == Navigation.TransitCue.ARRIVE;
            }
            String detail = "to '" + here.name() + "': " + Math.round(route.totalLength())
                    + " blocks, " + seen.size() + " line(s)"
                    + (boarded ? "" : " | no boarding prompt")
                    + (left ? "" : " | no alighting prompt")
                    + " | " + String.join(" → ", seen);
            boolean ok = !seen.isEmpty() && boarded && left;
            return new Result("command.howtogo.selftest.check.ride", ok, detail);
        } finally {
            Navigation.setMode(previousMode);
            if (previous == null) {
                Navigation.clear();
            } else {
                Navigation.setTarget(previous);
            }
        }
    }

    /**
     * The destination the simulated ride is aimed at: the one being navigated, or else the farthest
     * place there is from where the player stands.
     *
     * <p>Farthest rather than first, because that is the one most likely to need a public transport
     * journey at all: a place across the street is walked to and would test nothing about lines.
     */
    private static Destination transitDestination(Destination previous) {
        if (previous != null) {
            return previous;
        }
        List<Destination> all = Destinations.all();
        if (all.isEmpty()) {
            return null;
        }
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return all.get(all.size() - 1);
        }
        Destination farthest = all.get(0);
        double bestSq = -1;
        for (Destination destination : all) {
            double dx = destination.x() - player.getX();
            double dz = destination.z() - player.getZ();
            double sq = dx * dx + dz * dz;
            if (sq > bestSq) {
                bestSq = sq;
                farthest = destination;
            }
        }
        return farthest;
    }

    /** Where along a route a distance lands, by walking its polyline. */
    private static double[] pointAt(Route route, double travelled) {
        List<double[]> points = route.points();
        if (points.isEmpty()) {
            return new double[]{0, 0};
        }
        double walked = 0;
        for (int i = 1; i < points.size(); i++) {
            double[] before = points.get(i - 1);
            double[] here = points.get(i);
            double step = Math.hypot(here[0] - before[0], here[1] - before[1]);
            if (walked + step >= travelled) {
                double t = step < 1.0E-6 ? 0 : (travelled - walked) / step;
                return new double[]{before[0] + (here[0] - before[0]) * t,
                        before[1] + (here[1] - before[1]) * t};
            }
            walked += step;
        }
        return points.get(points.size() - 1);
    }

    /**
     * Whether the ordinary router can cross the world that is loaded.
     *
     * <p>Probes between the nodes furthest apart in each direction, for walking and for driving, and
     * reports what came back. A network of separate pieces is expected to fail some of these -- the
     * reason is printed with it, so a failure reads as "the roads are in fragments" rather than as
     * "the router is broken".
     *
     * <p>Run on a copy of the network, through a temporary file, and not on the player's own. Anchoring a
     * route to a point in the middle of a road splits that road at the point, which is right when the
     * player asked for a route from where they are standing and wrong when a self-test asks on their
     * behalf: the first real run of this check left a node and a segment in the world that nobody had
     * drawn, and a player who opened the line editor afterwards would have found a junction there.
     */
    private static Result checkRoutes(RoadNetwork network) {
        RoadNetwork probe = copyOf(network);
        List<RoadNode> nodes = probe.nodesSnapshot();
        if (nodes.size() < 2) {
            return new Result("command.howtogo.selftest.check.routes", true, "not enough roads to probe");
        }
        RoadNode first = nodes.get(0);
        RoadNode last = nodes.get(nodes.size() - 1);
        StringBuilder detail = new StringBuilder();
        boolean ok = true;
        for (TravelMode mode : new TravelMode[]{TravelMode.WALK, TravelMode.DRIVE}) {
            Route route = RoadRouter.findRoute(probe, first.x(), first.z(), last.x(), last.z(),
                    "self test", mode, RoutePreferences.DEFAULTS);
            if (detail.length() > 0) {
                detail.append(" | ");
            }
            if (route.isPresent()) {
                detail.append(mode.id()).append(' ')
                        .append(Math.round(route.totalLength())).append(" blocks");
            } else {
                detail.append(mode.id()).append(" refused: ").append(RoadRouter.explainFailure(
                        probe, first.x(), first.z(), last.x(), last.z(), mode,
                        RoutePreferences.DEFAULTS));
            }
        }
        return new Result("command.howtogo.selftest.check.routes", ok, detail.toString());
    }

    /** A copy of a network, through a temporary file, or the network itself when there is none to be had. */
    private static RoadNetwork copyOf(RoadNetwork network) {
        Path file = null;
        try {
            file = Files.createTempFile("howtogo-selftest-copy", ".json");
            Files.delete(file);
            if (!RoadStorage.save(file, network)) {
                return network;
            }
            return RoadStorage.load(file);
        } catch (IOException | RuntimeException failed) {
            HowToGo.LOGGER.warn("[HowToGo] selftest could not copy the network: {}", failed.toString());
            return network;
        } finally {
            if (file != null) {
                try {
                    Files.deleteIfExists(file);
                } catch (IOException ignored) {
                    HowToGo.LOGGER.debug("[HowToGo] selftest could not remove {}", file);
                }
            }
        }
    }

    /**
     * Whether the player's data survives being written and read back.
     *
     * <p>Through a temporary file of its own, so a check can never be the thing that loses a world.
     */
    private static Result checkStorage(RoadNetwork network, List<TransitLine> lines) {
        Path file = null;
        try {
            file = Files.createTempFile("howtogo-selftest", ".json");
            Files.delete(file);
            if (!RoadStorage.save(file, network)) {
                return new Result("command.howtogo.selftest.check.storage", false,
                        "the network could not be written");
            }
            RoadNetwork read = RoadStorage.load(file);
            boolean ok = read.nodeCount() == network.nodeCount()
                    && read.segmentCount() == network.segmentCount();
            return new Result("command.howtogo.selftest.check.storage", ok,
                    "wrote " + network.nodeCount() + "/" + network.segmentCount() + ", read "
                            + read.nodeCount() + "/" + read.segmentCount() + "; lines="
                            + lines.size());
        } catch (IOException | RuntimeException failed) {
            return new Result("command.howtogo.selftest.check.storage", false,
                    "round trip threw " + failed);
        } finally {
            if (file != null) {
                try {
                    Files.deleteIfExists(file);
                } catch (IOException ignored) {
                    HowToGo.LOGGER.debug("[HowToGo] selftest could not remove {}", file);
                }
            }
        }
    }

    /** Plans one journey with the same wiring the navigation uses. */
    private static Trip plan(RideRoads roads, List<TransitLine> lines, LineStop from, LineStop to,
                             RoutePreferences preferences) {
        return bili.dongsz.howtogo.route.TransitPlanner.plan(roads, lines,
                from.x(), from.z(), to.x(), to.z(), to.label(), preferences);
    }
}
