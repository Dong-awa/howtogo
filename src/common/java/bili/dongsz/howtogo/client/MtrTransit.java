package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.HowToGo;
import bili.dongsz.howtogo.RoadConfig;
import bili.dongsz.howtogo.road.RoadClass;
import bili.dongsz.howtogo.road.RoadNetwork;
import bili.dongsz.howtogo.road.RoadNode;
import bili.dongsz.howtogo.road.RoadSegment;
import bili.dongsz.howtogo.route.RoadRouter;
import bili.dongsz.howtogo.transit.LineStop;
import bili.dongsz.howtogo.transit.TransitLine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongPredicate;

/**
 * What {@link MtrClientData} read, said in this mod's own terms.
 *
 * <h2>Three things come out of it</h2>
 * <ul>
 *   <li><b>stops</b> -- one per station of the network, for the destination and stop pickers to offer
 *       beside the player's own places and the stations Create reports. Read-only by construction,
 *       exactly as a Create station is: {@link LineStop#ofStation} carries no node id, so there is
 *       nothing here the player could rename or retype by accident. A station of MTR's is not
 *       necessarily one entry: MTR makes a station of every area the player draws, so the platforms of
 *       one station commonly arrive as several stations, and they are offered as the one place they are
 *       -- see {@link #places};</li>
 *   <li><b>lines</b> -- one per MTR route whose type this mod has a kind for, with the stops the client
 *       currently knows about. They are handed to the planner and never to the line editor, which is
 *       what makes them read-only: the editor works on the player's own list and this is a different
 *       list, so no accident in the editor can write to a line MTR owns;</li>
 *   <li><b>the track they run along</b> -- for each line whose marks are switched on, the stretch
 *       between its own neighbouring stops, stamped as read-only rail or water roads of this mod. Not
 *       MTR's rails as a whole: MTR's data does not say which rails belong to which line, so each line's
 *       ride is planned over them and the path it takes is what is marked. A line whose marks are off
 *       contributes nothing, and its stops are then matched to the roads the player drew.</li>
 * </ul>
 *
 * <h2>Why the stops are where the platforms are</h2>
 * A station's centre is the centre of its whole area, which for a large station is nowhere near the
 * track; the platforms are where vehicles stop. A line's stop is therefore placed at the middle of the
 * station's platforms, and a station with no platform in range falls back to the centre of its area --
 * see {@link MtrClientData.Snapshot#stopPosition}. A place made of several of MTR's stations stands at
 * the middle of all their platforms, which is the same rule over a wider set.
 *
 * <h2>Why a line may be short</h2>
 * MTR sends a client what is near it, so the stops of a long line arrive a window at a time: what is
 * offered here is the part of the line around the player, which is also the part they could actually
 * board -- unless the whole railway can be read instead, which is what {@link MtrWholeMap} does whenever
 * the player is the one hosting the world, and what {@link MtrMapOverlay} does on any server whose
 * server half has fetched it. A line is not padded out to look complete, a stop whose station the
 * client has not been sent is left out rather than placed wrongly and counted, and a line left with
 * fewer than two placed stops is not offered at all -- a line with no ride in it would only ever
 * answer "no journey".
 *
 * <h2>Where the conversion runs, and what that costs the caller</h2>
 * On a worker of its own, not on the thread that asks -- see {@link #WORKER}. The accessors below
 * therefore offer the newest reading that has finished being worked out rather than one built on the
 * spot, so a caller can be answered with the reading before the current one: that is the price of a
 * whole railway's worth of lines never being converted in the middle of a frame, and it is the same
 * bargain {@link MtrWholeMap} already makes for the network it reads. {@link #warmUp()} is what keeps
 * the window between the two small, by starting the work when the reading changes rather than when
 * something first wants it.
 */
public final class MtrTransit {

    /** Prefix on every imported line's id, so an id can never collide with one the player made. */
    private static final String LINE_ID_PREFIX = "mtr:";

    /**
     * Above the player's own ids and above {@link RailTrackStore}'s layer, and clear of both.
     *
     * <p>Both machine-read layers live above a billion so that a segment can be told from a drawn one
     * by its id alone; this one starts higher so the two layers never share an id when a plan runs on a
     * network that has both. One counter serves nodes and segments alike, so the two cannot collide
     * with each other either.
     */
    private static final int ID_BASE = 1_500_000_000;

    /** How far apart two rail ends may be and still count as the same junction, in blocks. */
    private static final int JOIN_BLOCKS_SQUARED = 2;

    /** How many imported lines one log line names before it stops listing them. */
    private static final int MAX_REPORTED_LINES = 6;

    private static volatile List<LineStop> stops = List.of();
    private static volatile List<Station> stationList = List.of();
    private static volatile List<TransitLine> lines = List.of();
    private static volatile RoadNetwork rails = new RoadNetwork();
    private static volatile Map<Long, RoadNetwork> tracksById = Map.of();
    /**
     * Bumped whenever a reading publishes new tracks.
     *
     * <p>Published beside {@link #tracksById} and after it, so a reader that sees the new version sees
     * the tracks that go with it. A network read out of MTR is rebuilt whole rather than edited, so its
     * own revision describes the build's own numbering and nothing a caller can compare across builds;
     * this is the version a caller caching something worked out from the tracks has to key on.
     */
    private static volatile int trackStamp;
    private static volatile int imported;
    private static volatile int skipped;
    private static volatile int unplaced;
    private static volatile int rememberedLines;
    private static volatile int rememberedStations;

    /**
     * The worker the reading is converted on, and the two signatures that say what it has been asked
     * for and what it has finished.
     *
     * <h2>Why the conversion is not done where it is asked for</h2>
     * It used to be, guarded by a signature so that it ran once per reading rather than once per call.
     * That was enough while a reading was a couple of lines around the player: marking a line plans one
     * ride per pair of its neighbouring stops, and a couple of lines is a couple of rides. A reading of
     * a whole railway is hundreds of lines over a rail layer that a fetched snapshot can make thousands
     * of segments long, and the first thing to ask for it in a session is a map being drawn -- so the
     * work is done here instead, and what the map gets is the last build that finished.
     *
     * <p>That is a real and deliberate change in what these accessors mean: they offer the newest
     * finished reading rather than a reading built on the spot, so for as long as a build is running
     * they answer with the one before it. It is the same bargain {@link MtrWholeMap} already makes for
     * the whole network, and the honest one: a map that draws a railway one reading out of date is a
     * map, while a map that blocks the frame it is drawn in is a stutter.
     *
     * <p>Daemon, because nothing should be kept alive by a cache: the game asks the process to exit
     * while this is idle, and the harness exits without ever having asked it for anything.
     */
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "HowToGo MTR import");
        thread.setDaemon(true);
        // Below the thread it is kept off, which is the whole reason it exists.
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        return thread;
    });

    /** Whether the worker is running, so that only one build is ever in flight. */
    private static final AtomicBoolean building = new AtomicBoolean();

    /** Whether a conversion that threw has already been reported, so that it is reported once. */
    private static boolean failureReported;

    /**
     * The reading the worker has been asked for.
     *
     * <p>Written by whoever asks and read by the worker as well: a request that arrives while a build is
     * running is not queued but noticed, by the worker comparing what it took on against what has been
     * asked for once it is done with the first. It doubles as the record of what has been attempted,
     * which is why a build that threw is not tried again until the reading changes: a shape that has
     * moved does not move back, and retrying it on every call would be a failing conversion several
     * times a frame.
     */
    private static volatile String requested = "";

    /**
     * The reading the key below was last built from, and the part of the key that came from it.
     *
     * <p>{@link #refresh()} is asked on whichever thread wanted an answer -- often the render thread,
     * several times a frame -- and building its signature is a walk of every line and every rail of a
     * reading that a fetched snapshot makes large. The reading itself changes once every few seconds
     * and is the same object until it does, so the walk is done once per reading and the answer kept.
     *
     * <p>One value rather than a reading and a string beside it, because it is written from whichever
     * thread found the reading changed and read from all the others: a reader that matched a reading
     * against a half-written pair could be handed the part that belongs to the reading before it, and
     * a signature that is quietly the wrong one is a rebuild that never happens. A record is written in
     * one go, so what a reader sees is always a reading and its own part.
     */
    private static volatile KeyedPart keyed;

    /** A reading, and the part of the rebuild signature that was worked out from it. */
    private record KeyedPart(MtrClientData.Snapshot reading, String part) {
    }

    /**
     * Everything MTR has said so far, kept because it only ever says what is near the player.
     *
     * <p>See {@link MtrKnown}: a reading on its own is a window, and a window that closes behind the
     * player would take their railway with it -- the lines, the stations they can navigate to, and the
     * track the marks are cut from.
     */
    private static final MtrKnown known = new MtrKnown();

    /**
     * The counter the marks' ids are drawn from, for the whole session.
     *
     * <p>Never reset, because what is kept of one reading is merged with what was kept of the readings
     * before it: two builds both starting at {@link #ID_BASE} would number two different marks alike, and
     * the merge would then drop one of them as a duplicate of the other.
     */
    private static final int[] markIds = {ID_BASE};

    private MtrTransit() {
    }

    /** Every MTR station the client knows about, as a stop this mod can plan to. */
    public static List<LineStop> stops() {
        refresh();
        return stops;
    }

    /**
     * Every MTR station the client knows about, as a place a journey can end at.
     *
     * <p>The same stations as {@link #stops()}, with the height a stop has no room for: a stop is a
     * point on a line and the router needs only where on the map it is, while a destination is offered
     * in a list and marked on a map, and a mark has a height.
     */
    public static List<Station> stations() {
        refresh();
        return stationList;
    }

    /**
     * Every MTR line whose type this mod has a kind for, with the stops currently in range.
     *
     * <p>Handed to the planner and never to the editor, which is what keeps MTR's lines MTR's.
     */
    public static List<TransitLine> lines() {
        refresh();
        return lines;
    }

    /**
     * The track each line runs along, as a read-only network of this mod's rail and water roads.
     *
     * <p>Only the lines whose marks are switched on are in it, which is what makes the switch beside a
     * line in the editor real: a line whose marks are off contributes nothing here, so nothing of its
     * track is drawn and nothing of it is offered to a ride. What each line contributes is the stretch
     * between its own neighbouring stops rather than MTR's rails as a whole -- MTR's data does not say
     * which rails belong to which line, so the ride is planned over them and its path is what is marked
     * (see {@link MtrLineTracks}).
     *
     * <p>Empty when no line wants marks at all, which is what a player who has asked for MTR's track to
     * be left alone gets: their own roads, and none of MTR's.
     */
    public static RoadNetwork railLayer() {
        refresh();
        return rails;
    }

    /**
     * The stretch of MTR's track one imported line runs along, or null for a line of the player's own.
     *
     * <p>What the map draws the line along, whether or not the line's marks are switched on: the switch
     * decides whether this track is added to the road network as rail or water roads -- which is what a
     * ride is planned over, and what puts a road under the line -- and not whether the line is drawn.
     * The line is always drawn, along the track it actually runs on.
     */
    public static RoadNetwork trackOf(TransitLine line) {
        refresh();
        Long id = lineId(line);
        return id == null ? null : tracksById.get(id);
    }

    /**
     * A version of the tracks the newest finished build published, for a caller caching a reading of
     * them -- the line editor keys what it worked out about a line's connectivity on this.
     *
     * <p>Refreshed first, like {@link #trackOf}, so the version names the same build the tracks beside
     * it do.
     */
    public static int tracksVersion() {
        refresh();
        return trackStamp;
    }

    /** How many lines MTR offered that this mod has no kind for. */
    public static int skippedLines() {
        refresh();
        return skipped;
    }

    /** How many of a line's stops were left out for having no position the client knew. */
    public static int unplacedStops() {
        refresh();
        return unplaced;
    }

    /** How many lines were taken. */
    public static int importedLines() {
        refresh();
        return imported;
    }

    /**
     * How many lines are remembered, including the ones MTR is no longer sending.
     *
     * <p>Counted by the worker when it published them, rather than by asking the memory here: the
     * memory belongs to the worker, and a count read off it from the render thread while it was being
     * written to would be a count of nothing in particular.
     */
    public static int rememberedLines() {
        refresh();
        return rememberedLines;
    }

    /** How many stations are remembered, including the ones MTR is no longer sending. */
    public static int rememberedStations() {
        refresh();
        return rememberedStations;
    }

    /**
     * What a reading becomes, and what had to be left out of it.
     *
     * @param tracks the track each line runs along, by MTR's own line id: worked out for every line and
     *               not only for the ones whose marks are switched on, because the map draws a line
     *               along its track whatever the switch says -- the switch is about whether that track
     *               is added to the road network as roads, not about whether the line is drawn. See
     *               {@link MtrKnown}, which keeps them, and {@link MtrMarks}, which is the switch.
     * @param stated the lines of {@code tracks} whose rails the reading named rather than leaving to be
     *               worked out, which is what decides whether what was kept of them is replaced or
     *               added to. See {@link MtrKnown#remember}.
     */
    record Built(List<Station> stations, List<LineStop> stops, List<TransitLine> lines,
                 Map<Long, RoadNetwork> tracks, Set<Long> stated, int imported, int skipped,
                 int unplaced) {

        static final Built EMPTY =
                new Built(List.of(), List.of(), List.of(), Map.of(), Set.of(), 0, 0, 0);

        /** Every line's track as one network. */
        RoadNetwork rails() {
            return MtrKnown.union(tracks.values());
        }
    }

    /**
     * One MTR station, as a place a journey can end at.
     *
     * <p>Except where several of MTR's stations are one place, in which case this is that place and
     * carries the lowest of their ids -- see {@link #places}.
     *
     * @param id   MTR's own id for the station, which is what makes two readings the same station -- a
     *             name can be changed and a position can move as platforms are built
     * @param name what MTR calls the station, or an empty string when it has no name
     * @param x    where its vehicles stop: the middle of its platforms, or of its area
     * @param y    the station's own height, for a marker to be drawn at
     * @param z    where its vehicles stop
     */
    public record Station(long id, String name, int x, int y, int z) {
    }

    /** Counts the passes below fill in, so that they stay functions of their arguments. */
    private static final class Counts {
        private int skipped;
        private int unplaced;
    }

    /**
     * Turns a reading into this mod's stops, lines and the track they run along.
     *
     * <p>A function of the reading and the id counter, and of nothing else, so that everything about the
     * conversion can be checked with no MTR installed -- which is the only way it can be checked at all
     * here. The state above is a cache of this, not the other way round.
     *
     * <p>The switch beside a line is deliberately not an argument: every line's track is worked out, and
     * which of those tracks are then added to the road network as roads is decided later, by
     * {@link MtrKnown#marks} -- so that a line switched off is still drawn along the track it runs on,
     * and switching it on adds the roads without needing a fresh reading.
     *
     * @param nextId the id counter the track is drawn from, handed in so that two readings of one session
     *               cannot number two different pieces alike: what is kept of a reading is merged with
     *               what was kept of the ones before it, and ids that repeat would make that merge lose
     *               track
     */
    static Built build(MtrClientData.Snapshot reading, int[] nextId) {
        if (reading.isEmpty()) {
            return Built.EMPTY;
        }
        Counts counts = new Counts();
        // The stations first, because the lines are placed on them: a line's stop is put where its
        // station's vehicles stop, and after the grouping below that is the place rather than whichever
        // of MTR's station areas the stop happened to name.
        Places places = places(reading);
        List<TransitLine> builtLines = buildLines(reading, places, counts);
        BuiltTracks tracks = buildTracks(reading, builtLines, nextId);
        return new Built(places.stations(), stopsOf(places.stations()), builtLines, tracks.byLine(),
                tracks.stated(), builtLines.size(), counts.skipped, counts.unplaced);
    }

    /**
     * The track each line runs along, and which of those the reading stated rather than left to be
     * worked out.
     *
     * <p>The two are told apart because what is kept of them is kept differently: a reading that names
     * a line's rails names all of them, so what it says replaces what was kept, while a reading that
     * works them out says only what is near the player and what it says is added to the rest. See
     * {@link MtrKnown#remember}.
     */
    private record BuiltTracks(Map<Long, RoadNetwork> byLine, Set<Long> stated) {
    }

    /**
     * The track every line runs along, by line.
     *
     * <p>MTR's own rails are read first, because the path a line runs along has to be found over them,
     * and then thrown away: what comes out is the lines' rides and never the rails as a whole. Nothing
     * of MTR's own geometry reaches a plan, which is what makes a track the stretch <em>this line</em>
     * uses rather than every rail within reach of the player.
     *
     * <p>Per line rather than folded into one network, because a reading is kept per line once it has
     * been read and the player has walked away from it -- see {@link MtrKnown}. The ids come from the
     * caller's counter, so the track of one reading can be merged with the track of the next.
     *
     * <h2>Two ways to know a line's track, and which is used</h2>
     * A reading that says which rails a line runs along has its answer taken, and nothing is worked
     * out: see {@link MtrLineTracks#stated}, which is exact and has no reach. Only a line the reading
     * is silent about -- MTR's own client data, which joins a route to its platforms and to nothing
     * else -- falls back to planning a ride between each pair of its neighbouring stops over the rails
     * in hand.
     *
     * <p>That fallback is the expensive one, so it is only paid for when there is something to pay it
     * on: the rail layer, its extent and the router's workspace are all built here and none of them is
     * built at all when every line has stated rails. A fetched snapshot names the rails of every line
     * it carries, so on a server running MTR Map Overlay a whole railway's worth of track is a walk of
     * its own geometry rather than a plan over it.
     *
     * <p>One router workspace is shared by every line that does need planning, rather than one per
     * line inside {@link MtrLineTracks}: a workspace copies the rail layer, so
     * one per line is the whole layer copied once per line. What it does not avoid is the
     * work per pair, which is bounded by the extent.
     */
    private static BuiltTracks buildTracks(MtrClientData.Snapshot reading,
                                           List<TransitLine> lines, int[] nextId) {
        Map<Long, RoadNetwork> tracks = new HashMap<>();
        Set<Long> statedIds = new HashSet<>();
        if (lines.isEmpty()) {
            // No line, no track: MTR's rails are not even joined into a layer, which is what a world
            // whose lines this mod has no kind for costs.
            return new BuiltTracks(tracks, statedIds);
        }

        Map<Long, MtrClientData.Line> sources = new HashMap<>();
        for (MtrClientData.Line line : reading.lines()) {
            sources.put(line.id(), line);
        }
        Map<String, MtrClientData.Track> railsById = new HashMap<>();
        for (MtrClientData.Track rail : reading.tracks()) {
            if (rail.hexId() != null) {
                railsById.put(rail.hexId(), rail);
            }
        }

        // What each line runs along, where the reading says so. A line it says nothing about is one
        // the layer below has to exist for.
        Map<Long, List<MtrClientData.Track>> stated = new HashMap<>();
        boolean anyPlanned = false;
        for (TransitLine line : lines) {
            Long id = lineId(line);
            if (id == null) {
                continue;
            }
            List<MtrClientData.Track> named = statedRails(sources.get(id), railsById);
            if (named == null) {
                anyPlanned = true;
            } else {
                stated.put(id, named);
            }
        }

        RoadNetwork rails = new RoadNetwork();
        MtrLineTracks.Extent extent = null;
        RoadRouter.Workspace workspace = null;
        if (anyPlanned) {
            rails = buildRailLayer(reading);
            // Asked once for the whole reading rather than once per line: it is a walk of the layer,
            // and the layer is the same for every line of it. See MtrLineTracks.Extent, which is what
            // keeps a whole railway's worth of stops from being planned pair by pair against a
            // window's worth of track.
            extent = MtrLineTracks.Extent.of(rails);
            workspace = new RoadRouter.Workspace(rails);
        }

        for (TransitLine line : lines) {
            Long id = lineId(line);
            if (id == null) {
                continue;
            }
            List<MtrClientData.Track> named = stated.get(id);
            RoadNetwork ofLine = named != null
                    ? MtrLineTracks.stated(named, line.kind(), nextId)
                    : MtrLineTracks.of(workspace, rails, line, nextId, extent);
            if (ofLine.segmentCount() > 0) {
                tracks.put(id, ofLine);
                if (named != null) {
                    statedIds.add(id);
                }
            }
        }
        return new BuiltTracks(tracks, statedIds);
    }

    /**
     * The rails one line runs along, as the reading gives them, or null when it does not say.
     *
     * <p>The ids are looked up against the rails the reading holds and the ones it does not are left
     * out rather than the line being abandoned: a route longer than the snapshot's rail set is a route
     * with a stretch whose geometry is unknown, which {@link MtrLineTracks#stated} draws as a gap.
     * Null, rather than an empty list, is what says "this reading has no answer here" and sends the
     * line to the planning path instead.
     */
    private static List<MtrClientData.Track> statedRails(MtrClientData.Line source,
                                                         Map<String, MtrClientData.Track> railsById) {
        if (source == null || source.rails().isEmpty()) {
            return null;
        }
        List<MtrClientData.Track> named = new ArrayList<>(source.rails().size());
        for (String hexId : source.rails()) {
            MtrClientData.Track rail = railsById.get(hexId);
            if (rail != null) {
                named.add(rail);
            }
        }
        return named.isEmpty() ? null : named;
    }

    /**
     * Starts the build for the newest reading, without waiting for it or for anybody to ask.
     *
     * <p>For the client tick, which is where a reading first appears: the conversion is not done where
     * it is asked for -- see {@link #WORKER} -- so whoever asks first is answered with the build before
     * theirs, and on the first ask of a session that is nothing at all. Asking from the tick instead
     * means the reading is worked out while the player is still walking around, rather than during the
     * first map draw or the first journey planned, which are the moments an empty answer would show.
     *
     * <p>Does nothing when a build for this reading is already under way, which is what makes it safe to
     * call as often as the reading changes.
     */
    public static void warmUp() {
        refresh();
    }

    /**
     * Hands a changed reading to the worker, and answers with the last build that finished.
     *
     * <p>Keyed by a signature of the reading rather than rebuilt per call, because a plan asks for the
     * lines several times and a map asks while it draws -- and now also because the rebuild itself is
     * no longer done here: this method's whole job is to notice that the answer would be different and
     * to say so once, and everything it returns was published by the worker. See {@link #WORKER}.
     *
     * <p>Nothing here blocks. The first call of a session therefore answers with nothing at all, and
     * the reading arrives a moment later -- which is what the reading is: a cache of another mod's
     * data, filled one build behind whoever asks for it.
     */
    private static void refresh() {
        MtrClientData.Snapshot reading = MtrClientData.snapshot();
        boolean enabled = RoadConfig.mtrTransit();
        int marks = MtrMarks.version();
        // Worked out once per reading rather than once per asker. Every accessor on this class calls
        // this, and the map asks for one line's track per line per frame -- so a signature that walks
        // every line and lists every remembered id was being built L times a frame, which is the square
        // of the line count for an answer that cannot have changed. What it is made of is the reading,
        // the switch and the player's own answers about marks; the reading only changes when the worker
        // publishes one, so its identity is what says the signature still holds, and the marks version
        // is what notices a switch being flipped without a new reading arriving.
        String wanted;
        if (keyedSignature != null && reading == keyedReading && enabled == keyedEnabled
                && marks == keyedMarks) {
            wanted = keyedSignature;
        } else {
            wanted = key(reading, enabled);
            keyedReading = reading;
            keyedEnabled = enabled;
            keyedMarks = marks;
            keyedSignature = wanted;
        }
        if (wanted.equals(requested)) {
            return;
        }
        requested = wanted;
        if (building.compareAndSet(false, true)) {
            WORKER.execute(MtrTransit::work);
        }
        // A build already running will notice that `requested` moved on, because it compares the
        // signature it finished against this one before it lets go of the flag.
    }

    /** The last signature worked out, and what it was worked out from. See {@link #refresh}. */
    private static MtrClientData.Snapshot keyedReading;
    private static boolean keyedEnabled;
    private static int keyedMarks = Integer.MIN_VALUE;
    private static String keyedSignature;

    /**
     * One build per request, however many arrive while it runs.
     *
     * <p>The loop is what keeps a request from being lost. A request that arrives while this is
     * running cannot start a second build -- the flag is held -- so the only place it can be noticed
     * is here, after the flag is given back. Reading `requested` after releasing it, and taking the
     * flag again rather than leaving the request to a caller that has already given up, is what makes
     * the handover complete: either this thread picks the new reading up, or another thread took the
     * flag first and will publish it.
     */
    private static void work() {
        while (true) {
            String signature = requested;
            try {
                rebuild(MtrClientData.snapshot(), RoadConfig.mtrTransit());
            } catch (RuntimeException | LinkageError failed) {
                // A conversion that throws must cost the player the reading, not the game: what has
                // already been published stays, and the next reading tries again.
                reportFailure(failed);
            } finally {
                // Released whatever happened, including an error this does not catch: a flag left set
                // by a build that died is a reading that is never worked out again, which would look
                // exactly like a railway that stopped being sent.
                building.set(false);
            }
            if (signature.equals(requested)) {
                return;
            }
            if (!building.compareAndSet(false, true)) {
                // Another caller took the flag while this was letting go of it, and will publish.
                return;
            }
        }
    }

    /**
     * Reports the conversion having failed, once.
     *
     * <p>Once and not once per reading: a shape that has moved does not move back, and a warning every
     * few seconds for the rest of the session is the diagnostic this mod's own rail report was
     * rewritten to stop being.
     */
    private static void reportFailure(Throwable cause) {
        if (failureReported) {
            return;
        }
        failureReported = true;
        HowToGo.LOGGER.warn("[HowToGo] could not work out MTR's reading; the last one it worked out is "
                + "still offered, and this will not be reported again", cause);
    }

    /**
     * Converts one reading and publishes everything that comes out of it.
     *
     * <p>Runs on the worker and nowhere else, which is what makes {@link #known} and
     * {@link #markIds} safe to touch without locks: they have exactly one thread.
     */
    private static void rebuild(MtrClientData.Snapshot reading, boolean enabled) {
        if (!enabled) {
            // Switched off means forgotten, not merely hidden: the readings are MTR's, and a player who
            // turns the integration off is asking for the mod to know nothing about their railway.
            known.clear();
            stops = List.of();
            stationList = List.of();
            lines = List.of();
            rails = new RoadNetwork();
            tracksById = Map.of();
            trackStamp++;
            imported = 0;
            skipped = 0;
            unplaced = 0;
            rememberedLines = 0;
            rememberedStations = 0;
            return;
        }

        long startedAt = System.nanoTime();
        Built built = build(reading, markIds);
        // What this reading adds to what the ones before it taught, and then the answers taken from the
        // whole of that rather than from this reading alone: MTR sends a client only what is near it, so
        // a reading read on its own is a window that closes behind the player as they walk.
        known.remember(built);
        List<Station> keptStations = known.stations();
        List<TransitLine> keptLines = known.lines();
        RoadNetwork marks = known.marks(MtrTransit::marksWanted);
        List<LineStop> keptStops = stopsOf(keptStations);
        // Published one at a time, and every one of them complete: a reader on another thread takes
        // whichever of these it asks for, and gets either the build before this one or this one. Two
        // accessors asked a moment apart can therefore disagree about which reading they are answering
        // for, which is the same bargain MtrWholeMap makes and is why nothing here is a pair of values
        // that has to agree -- a marks layer and the lines it belongs to are each usable on their own.
        stationList = keptStations;
        lines = keptLines;
        rails = marks;
        tracksById = known.tracks();
        trackStamp++;
        stops = keptStops;
        imported = built.imported();
        skipped = built.skipped();
        unplaced = built.unplaced();
        rememberedLines = keptLines.size();
        rememberedStations = keptStations.size();
        if (imported > 0 || skipped > 0 || !keptStops.isEmpty()) {
            // The time is here because marking plans a ride per pair of neighbouring stops, and this is
            // the number that says whether it is still worth doing off the thread it used to run on. If
            // it ever grows past a frame, this is the evidence.
            HowToGo.LOGGER.info("[HowToGo] MTR import | stops {} lines {} (skipped {} unplacedStops {}) "
                            + "| marks {} rails {} nodes {} | kept {} lines {} stations {} track pieces "
                            + "| {} ms | {}",
                    keptStops.size(), imported, skipped, unplaced, marks.segmentCount(),
                    marks.nodeCount(), keptLines.size(), keptStations.size(), known.markSegments(),
                    Math.round((System.nanoTime() - startedAt) / 1_000_000.0), describe(keptLines));
        }
    }

    /**
     * The signature of a reading, as one string.
     *
     * <p>Two halves, and they change for different reasons. The first describes the reading itself and
     * is cached against it -- see {@link #keyed}, because it walks every line and every rail.
     * The second is every line's own marks switch, which is read here rather than in the conversion so
     * that the conversion stays a function of the reading and a decision it is handed, and which the
     * player can flip at any moment -- so it is asked every time, of the lines of the newest reading and
     * of the ones only remembered.
     */
    private static String key(MtrClientData.Snapshot reading, boolean enabled) {
        StringBuilder key = new StringBuilder();
        key.append(enabled).append(';').append(readingPart(reading));
        for (MtrClientData.Line line : reading.lines()) {
            // Flipping a switch has to rebuild, and a line's switch is not part of the reading.
            key.append('|').append(line.id()).append(':').append(line.stops().size())
                    .append(marksWanted(line.id()) ? '+' : '-');
        }
        for (Long id : rememberedLineIds()) {
            // And the same for what is only remembered: a line the player has walked away from is no
            // longer in the reading, so its switch would otherwise change nothing until MTR happened to
            // send that part of the world again.
            key.append('~').append(id).append(marksWanted(id) ? '+' : '-');
        }
        return key.toString();
    }

    /** The half of the signature that describes a reading, walked once per reading. */
    private static String readingPart(MtrClientData.Snapshot reading) {
        KeyedPart kept = keyed;
        if (kept != null && kept.reading() == reading) {
            return kept.part();
        }
        StringBuilder part = new StringBuilder();
        part.append(reading.stations().size()).append('/')
                .append(reading.platforms().size()).append('/')
                .append(reading.lines().size()).append('/')
                .append(reading.tracks().size())
                // A rail that left the client's window as another arrived leaves the count alone, and
                // the marks are cut out of the rails, so what they are made of is part of what this
                // notices rather than only how many of them there are.
                .append('/').append(railSignature(reading));
        String built = part.toString();
        // One write, and a reader either sees the whole pair or the one before it -- which is only a
        // walk repeated, never a signature that is the wrong one.
        keyed = new KeyedPart(reading, built);
        return built;
    }

    /**
     * The ids of the lines only remembered, published by the worker.
     *
     * <p>A copy rather than the memory's own list, because this is read from whichever thread asks and
     * the memory's list is the worker's: what is handed out is immutable and never touched again.
     */
    private static List<Long> rememberedLineIds() {
        List<Long> ids = new ArrayList<>();
        for (TransitLine line : lines) {
            Long id = lineId(line);
            if (id != null) {
                ids.add(id);
            }
        }
        return ids;
    }

    /** A cheap fingerprint of the rails a reading holds, so a rail swapped for another is noticed. */
    private static int railSignature(MtrClientData.Snapshot reading) {
        int hash = 1;
        for (MtrClientData.Track track : reading.tracks()) {
            hash = hash * 31 + (track.hexId() == null ? 0 : track.hexId().hashCode());
            hash = hash * 31 + track.vertexCount();
        }
        return hash;
    }

    /**
     * Whether every line in play runs on a track of its own, so that the shared layer is of no use.
     *
     * <p>The shared layer -- {@link #railLayer()} -- is every switched-on line's track in one network.
     * It is what a ride is planned over when nothing says which rails a line uses, and it is the whole
     * railway: copying it before a plan can answer is the single largest thing
     * a plan pays for. A reading that names each line's rails makes it unnecessary, because a ride is
     * then planned over that line's own track instead -- see {@code RideRoads} -- and a caller that
     * knows it is unnecessary can leave the layer out of the world entirely.
     *
     * <p>Asked of the lines actually in play, which include the player's own: one of those has no track
     * of its own and does ride the shared layer, so its presence is what keeps the layer in the world.
     *
     * <p>One {@link #refresh()} and then a lookup per line, rather than {@link #trackOf} per line: that
     * would re-derive the reading's signature once for each of them, which on a whole railway is the
     * square of its line count for a question that is a walk of a list.
     *
     * @param lines the lines a plan is being made over, as {@code Navigation#linesInPlay} gives them
     */
    public static boolean everyLineRidesItsOwnTrack(List<TransitLine> lines) {
        if (lines == null || lines.isEmpty()) {
            return false;
        }
        refresh();
        for (TransitLine line : lines) {
            Long id = lineId(line);
            if (id == null || !tracksById.containsKey(id)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether this line's track is marked as roads of this mod.
     *
     * <p>MTR's own switch on the outside: nothing is marked at all while MTR is not being read. Inside
     * that, the player's answer for the line if they have given one, and otherwise the configured
     * default -- see {@link MtrMarks}, which is where the answers live.
     */
    private static boolean marksWanted(long mtrLineId) {
        return RoadConfig.mtrTransit() && MtrMarks.forLine(mtrLineId);
    }

    /** The imported lines by name and kind, bounded, for the one log line a reading earns. */
    private static String describe(List<TransitLine> imported) {
        StringBuilder text = new StringBuilder();
        int shown = 0;
        for (TransitLine line : imported) {
            if (shown == MAX_REPORTED_LINES) {
                text.append(", ...");
                break;
            }
            if (shown > 0) {
                text.append(", ");
            }
            text.append(line.label()).append(' ').append(line.kind().name())
                    .append(" (").append(line.stopCount()).append(" stops known)");
            shown++;
        }
        return text.length() == 0 ? "nothing imported" : text.toString();
    }

    /**
     * Whether a line is planned over MTR's track.
     *
     * <p>A line the player built takes the configured default: the marks are MTR's, and a line of the
     * player's own has no answer of its own to give about them. A line read out of MTR has whatever the
     * player chose for it, falling back to the same default -- see {@link MtrMarks}.
     *
     * <p>For an imported line this is the same question {@link #marksWanted} answers, and deliberately
     * so: whether a line's track is marked and whether a ride along it uses that track cannot be two
     * different answers, or the switch would draw one thing and route another.
     */
    public static boolean marksEnabled(TransitLine line) {
        if (!RoadConfig.mtrTransit()) {
            // Nothing is read from MTR, so there is nothing of MTR's to bring in.
            return false;
        }
        Long id = lineId(line);
        return id != null ? marksWanted(id) : RoadConfig.mtrAutoRouteMarks();
    }

    /**
     * Whether any line has them switched off, and so needs the network without them built.
     *
     * <p>Asked before a plan rather than during it: a plan over lines that all agree runs on one
     * network, and the second copy of the world is only worth making when a line actually wants the
     * difference.
     */
    public static boolean anyLineRefusesMarks(List<TransitLine> lines) {
        for (TransitLine line : lines) {
            if (!marksEnabled(line)) {
                return true;
            }
        }
        return false;
    }

    /**
     * MTR's own id for an imported line, or null when the line is not one of MTR's.
     *
     * <p>Boxed rather than a sentinel, and that is the whole point: MTR's ids are longs and half of the
     * ones it hands out have the top bit set, so a negative number cannot mean "not ours". It did mean
     * that here, and the cost was the switch beside a line doing nothing at all -- every such line was
     * read as somebody else's, so its answer was never written and its marker never moved.
     */
    public static Long mtrLineId(TransitLine line) {
        return lineId(line);
    }

    /** The same, for the conversion and for the memory, which ask it of every line they hold. */
    private static Long lineId(TransitLine line) {
        if (!isImported(line)) {
            return null;
        }
        try {
            // Unsigned, because the id was written as {@link Long#toHexString} and that prints a negative
            // long as its sixteen-digit bit pattern: read as a signed number, every id with the top bit
            // set overflowed and the line was thrown away as somebody else's -- so its track was never
            // marked and the switch beside it had nothing to change. Half of MTR's ids are like that.
            return Long.parseUnsignedLong(line.id().substring(LINE_ID_PREFIX.length()), 16);
        } catch (NumberFormatException notOurs) {
            // An id this class did not write, which means something else is using the prefix.
            return null;
        }
    }

    /**
     * Whether a line came from MTR rather than from the player.
     *
     * <p>By its id, which is where the distinction is made: an imported line's id is prefixed, so
     * anything holding a line can tell what it is holding without a second field to keep in step -- and
     * without the line model having to learn about MTR.
     */
    public static boolean isImported(TransitLine line) {
        return line != null && line.id().startsWith(LINE_ID_PREFIX);
    }

    // ------------------------------------------------------------ stations as places

    /**
     * The stations of a reading, as the places a journey can end at.
     *
     * <h2>Why MTR's stations are not already places</h2>
     * MTR makes a station out of every area the player draws, and an area is a box the player drags
     * over the platforms it covers. A station built the obvious way -- one platform at a time, one box
     * around each -- therefore reaches this mod as several stations that share a name, standing a few
     * blocks apart: the same station, said four times. Offered as they arrive, that is a destination
     * picker listing one station four times, four markers on the map where there is one building, and a
     * line calling at whichever of the four its platforms happen to be in -- so a change between two
     * lines that meet at that station looks like a change between two stations a hundred blocks apart,
     * and a journey through it is planned as two.
     *
     * <p>So stations that say they are the same station are one place here: the same name, ignoring
     * case and surrounding spaces, and boarding points within {@code mtr_station_merge_blocks} of each
     * other. The distance is what keeps two towns that both called their station "Central" apart, and
     * the name is what keeps two genuinely different stations of one complex apart. Stations with no
     * name are never merged -- every nameless station would be one place -- and the whole rule is off
     * when the configured distance is zero, which offers MTR's stations exactly as MTR has them.
     *
     * <p>Nothing is written back to MTR: this is how the reading is offered, not a change to it, and
     * MTR's own dashboard goes on showing its stations as it always did. A journey planned to a place
     * reaches whichever of its stations the ride runs to, because the place stands where its platforms
     * are.
     */
    private static Places places(MtrClientData.Snapshot reading) {
        List<MtrClientData.Station> raw = reading.stations();
        int[] group = new int[raw.size()];
        for (int i = 0; i < group.length; i++) {
            group[i] = i;
        }

        double merge = RoadConfig.mtrStationMergeBlocks();
        if (merge > 0) {
            // Only stations that share a name are ever compared. MTR's names are what says two areas are
            // one station, so anything else is a pair there is no reason to measure -- which is what
            // keeps this from being the square of the network on every rebuild.
            Map<String, List<Integer>> byName = new LinkedHashMap<>();
            for (int i = 0; i < raw.size(); i++) {
                String name = placeKey(raw.get(i).name());
                if (!name.isEmpty()) {
                    byName.computeIfAbsent(name, key -> new ArrayList<>()).add(i);
                }
            }
            for (List<Integer> sameName : byName.values()) {
                for (int a = 0; a < sameName.size(); a++) {
                    for (int b = a + 1; b < sameName.size(); b++) {
                        if (closeEnough(reading, raw.get(sameName.get(a)), raw.get(sameName.get(b)),
                                merge)) {
                            join(group, sameName.get(a), sameName.get(b));
                        }
                    }
                }
            }
        }

        Map<Integer, List<MtrClientData.Station>> members = new LinkedHashMap<>();
        for (int i = 0; i < raw.size(); i++) {
            members.computeIfAbsent(root(group, i), key -> new ArrayList<>()).add(raw.get(i));
        }

        List<Station> places = new ArrayList<>(members.size());
        Map<Long, Station> byStationId = new HashMap<>();
        for (List<MtrClientData.Station> member : members.values()) {
            Station place = place(reading, member);
            if (place == null) {
                // No boarding point anywhere in the group, which a station of this reading cannot be:
                // kept as "left out" rather than placed at a guess.
                continue;
            }
            places.add(place);
            for (MtrClientData.Station station : member) {
                byStationId.put(station.id(), place);
            }
        }
        return new Places(List.copyOf(places), Map.copyOf(byStationId));
    }

    /**
     * One place, out of the MTR stations that are it.
     *
     * <p>The place takes the lowest of its stations' ids and that station's name, which keeps both the
     * same from one rebuild to the next for as long as the group does not change -- and a place whose id
     * moved about would be a second entry in the memory beside the first, since that is keyed by id.
     *
     * <p>It stands at the middle of its stations' boarding points. With one station -- which is what
     * every station that was not merged is -- that is exactly where that station's vehicles stop, the
     * same answer this mod gave before it knew MTR's stations could be more than one; with several it is
     * the point between their platforms, which is on the railway and so is a stop a route can reach.
     */
    private static Station place(MtrClientData.Snapshot reading,
                                List<MtrClientData.Station> member) {
        MtrClientData.Station leader = member.get(0);
        for (MtrClientData.Station station : member) {
            if (station.id() < leader.id()) {
                leader = station;
            }
        }
        long sumX = 0;
        long sumZ = 0;
        int counted = 0;
        for (MtrClientData.Station station : member) {
            int[] position = reading.stopPosition(station.id());
            if (position != null) {
                sumX += position[0];
                sumZ += position[1];
                counted++;
            }
        }
        if (counted == 0) {
            return null;
        }
        String name = leader.name() == null ? "" : leader.name();
        return new Station(leader.id(), name, (int) Math.round((double) sumX / counted),
                leader.centerY(), (int) Math.round((double) sumZ / counted));
    }

    /** A station's name as the grouping compares it: without case, and without surrounding spaces. */
    private static String placeKey(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }

    /** Whether two stations of one name are close enough to be one place. */
    private static boolean closeEnough(MtrClientData.Snapshot reading, MtrClientData.Station one,
                                       MtrClientData.Station other, double blocks) {
        int[] here = reading.stopPosition(one.id());
        int[] there = reading.stopPosition(other.id());
        if (here == null || there == null) {
            return false;
        }
        double dx = here[0] - there[0];
        double dz = here[1] - there[1];
        return dx * dx + dz * dz <= blocks * blocks;
    }

    /** The group a station is in, following the chain to its end. */
    private static int root(int[] group, int index) {
        while (group[index] != index) {
            group[index] = group[group[index]];
            index = group[index];
        }
        return index;
    }

    /** Puts two stations in one group. */
    private static void join(int[] group, int one, int other) {
        int first = root(group, one);
        int second = root(group, other);
        if (first != second) {
            group[Math.max(first, second)] = Math.min(first, second);
        }
    }

    /**
     * The stations of a reading as places, and which place each of MTR's stations belongs to.
     *
     * <p>The lookup is the half the lines need: a stop names an MTR station, and after the grouping
     * that station is one of several that make up a place, so the stop has to be placed on the place
     * rather than on the box the stop happened to fall in.
     */
    private record Places(List<Station> stations, Map<Long, Station> byStationId) {

        /** The place an MTR station id belongs to, or null when this reading does not hold it. */
        Station of(long mtrStationId) {
            return byStationId.get(mtrStationId);
        }
    }

    /**
     * The stations as the planner's kind of stop.
     *
     * <p>A projection rather than a second walk of the reading, so the rule that decides where a
     * station's vehicles stop -- the middle of its platforms, or of its area -- has one home and a
     * station can never be offered as a destination at one place and planned to at another.
     */
    private static List<LineStop> stopsOf(List<Station> stations) {
        List<LineStop> built = new ArrayList<>(stations.size());
        for (Station station : stations) {
            built.add(LineStop.ofStation(station.name(), station.x(), station.z()));
        }
        return List.copyOf(built);
    }

    private static List<TransitLine> buildLines(MtrClientData.Snapshot reading, Places places,
                                                Counts counts) {
        List<TransitLine> built = new ArrayList<>();
        for (MtrClientData.Line line : reading.lines()) {
            RoadClass kind = line.kind();
            if (kind == null) {
                // An aeroplane, or a type a later MTR adds: there is no kind of line here for it, and
                // a line nothing can be routed along is not offered as one.
                counts.skipped++;
                continue;
            }
            TransitLine made = new TransitLine(LINE_ID_PREFIX + Long.toHexString(line.id()),
                    line.name() == null ? Long.toHexString(line.id()) : line.name(), kind);
            for (MtrClientData.Stop stop : line.stops()) {
                // The place the stop's station belongs to, which is where the stop goes: a station built
                // a platform at a time reaches this mod as several stations, and a line calling at one of
                // them calls at the station -- so two lines meeting there must be two lines at one stop.
                Station place = places.of(stop.stationId());
                int[] position = place != null
                        ? new int[]{place.x(), place.z()}
                        : reading.stopPosition(stop.stationId());
                if (position == null) {
                    counts.unplaced++;
                    continue;
                }
                // A line that calls twice at one block is refused its second call by the line itself,
                // which is what keeps "the next stop" unambiguous.
                made.addStop(LineStop.ofStation(nameOf(place, stop), position[0], position[1]));
            }
            if (made.stopCount() < 2) {
                continue;
            }
            built.add(made);
        }
        return List.copyOf(built);
    }

    /** What a stop is called: the place it belongs to, or MTR's own name for the stop. */
    private static String nameOf(Station place, MtrClientData.Stop stop) {
        if (place != null && !place.name().isBlank()) {
            return place.name();
        }
        return stop.stationName() == null ? "" : stop.stationName();
    }

    /**
     * MTR's rails as segments, joined where they meet.
     *
     * <p>Rails are joined by position rather than by id, because the client's rail data has no node id
     * to join on: two rails that meet in the world have ends at the same place and nothing else in
     * common. The tolerance is a little over one block, which is what a rounded coordinate can be out
     * by -- and no more, so that two tracks running a couple of blocks apart stay two tracks.
     *
     * <p>Ids come from {@link #ID_BASE} rather than from the layer's own counter, because a segment of
     * this layer has to be tellable from one the player drew, and a plan runs on a network holding both
     * this and the player's roads at once.
     */
    private static RoadNetwork buildRailLayer(MtrClientData.Snapshot reading) {
        RoadNetwork layer = new RoadNetwork();
        if (reading.tracks().isEmpty()) {
            return layer;
        }
        List<RoadNode> made = new ArrayList<>();
        int[] nextId = {ID_BASE};
        for (MtrClientData.Track track : reading.tracks()) {
            RoadClass kind = MtrClientData.roadClassFor(track.mode());
            if (kind == null || track.vertexCount() < 2) {
                continue;
            }
            int count = track.vertexCount();
            int[] xs = new int[count];
            int[] zs = new int[count];
            for (int i = 0; i < count; i++) {
                xs[i] = (int) Math.round(track.xs()[i]);
                zs[i] = (int) Math.round(track.zs()[i]);
            }
            RoadNode from = nodeAt(layer, made, nextId, xs[0], track.y(), zs[0]);
            RoadNode to = nodeAt(layer, made, nextId, xs[count - 1], track.y(), zs[count - 1]);
            if (from.id() == to.id()) {
                // A rail that ends where it started carries nothing a route can use.
                continue;
            }
            RoadSegment segment = new RoadSegment(nextId[0]++, kind, track.y(), count);
            for (int i = 0; i < count; i++) {
                segment.addVertex(xs[i], zs[i]);
            }
            segment.setFromNode(from.id());
            segment.setToNode(to.id());
            layer.putSegment(segment);
        }
        return layer;
    }

    /** The node at a position, making one only when nothing already made is there. */
    private static RoadNode nodeAt(RoadNetwork layer, List<RoadNode> made, int[] nextId, int x, int y,
                                   int z) {
        for (RoadNode node : made) {
            int dx = node.x() - x;
            int dz = node.z() - z;
            if (dx * dx + dz * dz <= JOIN_BLOCKS_SQUARED && Math.abs(node.y() - y) <= 2) {
                return node;
            }
        }
        RoadNode node = new RoadNode(nextId[0]++, x, y, z, RoadNode.Type.ENDPOINT, null);
        layer.putNode(node);
        made.add(node);
        return node;
    }
}
