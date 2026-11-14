package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.HowToGo;
import bili.dongsz.howtogo.RoadConfig;
import bili.dongsz.howtogo.road.RoadClass;
import bili.dongsz.howtogo.road.RoadCurve;
import bili.dongsz.howtogo.road.RoadNetwork;
import bili.dongsz.howtogo.road.RoadNode;
import bili.dongsz.howtogo.road.RoadSegment;
import bili.dongsz.howtogo.route.RoadRouter;
import bili.dongsz.howtogo.transit.LineDirection;
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
 * What MtrClientData read, said in this mod's own terms.
 * Stops: one per station of the network, offered read-only beside the player's own places and Create's stations (LineStop.ofStation carries no node id, so nothing is renameable by accident).
 * Lines: one per MTR route this mod has a kind for, handed to the planner and never to the line editor, so no editor accident can write to a line MTR owns.
 * Tracks: for each line whose marks are switched on, the stretch between its neighbouring stops, stamped as read-only rail or water roads. MTR's data does not say which rails belong to which line, so each line's ride is planned over them and the path taken is what is marked.
 * Stops sit at the platforms, not the station centre: a large station's centre is nowhere near its track. A station may arrive as several MTR station areas; those are grouped into one place.
 * MTR sends a client what is near it, so a line may be short: a stop whose station has not been sent is left out and counted, and a line with fewer than two placed stops is not offered at all.
 * The conversion runs on its own worker (see WORKER), so the accessors offer the newest finished build; warmUp() starts it when the reading changes so the gap stays small.
 */
public final class MtrTransit {

    /** Prefix on every imported line's id, so an id can never collide with one the player made. */
    private static final String LINE_ID_PREFIX = "mtr:";

    /**
     * Above the player's own ids and above RailTrackStore's layer, so a segment can be told from a drawn one by its id alone and the two machine-read layers never share an id. One counter serves nodes and segments, so they cannot collide with each other either.
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
     * Bumped whenever a reading publishes new tracks. Published after tracksById, so a reader that sees the new version sees the tracks that go with it. A network read out of MTR is rebuilt whole, so its own revision says nothing a caller can compare across builds.
     */
    private static volatile int trackStamp;
    private static volatile int imported;
    private static volatile int skipped;
    private static volatile int unplaced;
    private static volatile int rememberedLines;
    private static volatile int rememberedStations;
    /**
     * The rails the memory holds, published for the store that saves them and for nobody else.
     * A list of the same immutable Track records the reading carried, so handing it out costs the
     * references and not the geometry. Published only when the count moved, because MTR re-sends the
     * rails in every window and a copy per reading would be a copy per second of an unchanged set.
     */
    private static volatile List<MtrClientData.Track> railSnapshot = List.of();
    private static volatile int railSnapshotCount = -1;
    private static volatile int railStamp;

    /**
     * The rails a saved session left behind, handed to the worker on its next build.
     * Read and cleared by the worker only, written by whichever thread opened a world; a plain
     * reference is enough because the value is a whole immutable list and losing an intermediate
     * hand-off would simply mean the next build takes the newer one.
     */
    private static volatile List<MtrClientData.Track> pendingSeed;

    /** Counts adopted memories, so a rebuild is asked for even when the reading itself did not change. */
    private static volatile int memoryEpoch;

    /**
     * The worker the reading is converted on, and the two signatures that say what it has been asked for and what it has finished.
     * The conversion used to run where it was asked for, which was fine while a reading was a couple of lines around the player: marking a line plans one ride per pair of neighbouring stops. A whole railway is hundreds of lines over thousands of rails, so the work moved here and a caller gets the last build that finished -- a map one reading out of date is a map, a blocked frame is a stutter.
     * Daemon, because nothing should be kept alive by a cache: the harness exits without ever having asked it for anything.
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
     * The reading the worker has been asked for. A request arriving while a build runs is not queued but noticed: the worker compares what it took on against this once done. It doubles as the record of what was attempted, so a build that threw is not retried until the reading changes.
     */
    private static volatile String requested = "";

    /**
     * The reading the key below was last built from, and the part of the key that came from it.
     * refresh() is asked from whichever thread wants an answer, often the render thread several times a frame, and building its signature walks every line and rail. The reading is the same object until it changes, so the walk happens once per reading.
     * One value rather than a reading and a string beside it, because it is written from any thread and read from all the others: a record is written in one go, so a reader never sees a signature that belongs to the reading before.
     */
    private static volatile KeyedPart keyed;

    /** A reading, and the part of the rebuild signature that was worked out from it. */
    private record KeyedPart(MtrClientData.Snapshot reading, String part) {
    }

    /**
     * Everything MTR has said so far, kept because it only ever says what is near the player: a window that closed behind them would take their railway with it -- the lines, the stations they can navigate to, and the track the marks are cut from.
     */
    private static final MtrKnown known = new MtrKnown();

    /**
     * The counter the marks' ids are drawn from for the whole session. Never reset: what is kept of one reading is merged with what was kept before, and two builds starting at ID_BASE would number two marks alike and the merge would drop one.
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
     * Every MTR station the client knows about, as a place a journey can end at. The same stations as stops(), with the height a stop has no room for: a mark has a height, a point on a line does not.
     */
    public static List<Station> stations() {
        refresh();
        return stationList;
    }

    /**
     * Every MTR line whose type this mod has a kind for, with the stops currently in range. Handed to the planner and never to the editor, which is what keeps MTR's lines MTR's.
     */
    public static List<TransitLine> lines() {
        refresh();
        return lines;
    }

    /**
     * The track each line runs along, as a read-only network of this mod's rail and water roads.
     * Only lines whose marks are switched on are in it, which is what makes the switch beside a line in the editor real: nothing of a switched-off line is drawn or offered to a ride. Each line contributes the stretch between its own stops rather than MTR's rails as a whole (see MtrLineTracks). Empty when no line wants marks.
     */
    public static RoadNetwork railLayer() {
        refresh();
        return rails;
    }

    /**
     * The stretch of MTR's track one imported line runs along, or null for a line of the player's own. The map draws the line along it whether or not the marks are on: the switch decides whether the track joins the road network as roads, not whether the line is drawn.
     */
    public static RoadNetwork trackOf(TransitLine line) {
        refresh();
        Long id = lineId(line);
        return id == null ? null : tracksById.get(id);
    }

    /**
     * A version of the tracks the newest finished build published, for a caller caching a reading of them; the line editor keys connectivity on it. refresh() first, so the version names the same build the tracks do.
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

    /** How many rails the memory holds, including the ones only a previous session saw. */
    public static int rememberedRails() {
        refresh();
        return Math.max(0, railSnapshotCount);
    }

    /**
     * The remembered rails, for the store that writes them out.
     * The same immutable list until the memory grows, so asking once a tick costs a read of one
     * reference rather than a copy of the railway.
     */
    public static List<MtrClientData.Track> railSnapshot() {
        refresh();
        return railSnapshot;
    }

    /** Bumped whenever the remembered rails change, so a saver can tell whether it is behind. */
    public static int railStamp() {
        refresh();
        return railStamp;
    }

    /**
     * The rails a saved session left behind, as the starting point of this one.
     * They replace the memory rather than adding to it: a world is opened once, and reading another
     * session's rails on top of this one's would mix two dimensions' track. The rebuild this asks for
     * is what plans the lines over them; nothing is drawn from the memory without that plan.
     */
    public static void adoptRememberedRails(List<MtrClientData.Track> rails) {
        pendingSeed = rails == null ? List.of() : List.copyOf(rails);
        memoryEpoch++;
        // A build already asked for has a signature that predates the memory and will not pick it up;
        // the epoch is part of the signature, so this call makes the newest request differ.
        refresh();
    }

    /** Forgets the rails and everything planned over them; the next reading starts the memory again. */
    public static void forgetMemory() {
        adoptRememberedRails(List.of());
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
     * How many lines are remembered, including ones MTR is no longer sending. Counted by the worker when it published them: the memory belongs to the worker, and a count read off it from the render thread would be a count of nothing in particular.
     */
    public static int rememberedLines() {
        refresh();
        return rememberedLines;
    }

    /** How many stations are remembered, including ones MTR is no longer sending. */
    public static int rememberedStations() {
        refresh();
        return rememberedStations;
    }

    /**
     * What a reading becomes, and what had to be left out of it.
     * @param tracks the track each line runs along, by MTR's line id, worked out for every line and not only the switched-on ones -- the map draws a line along its track whatever the switch says; see MtrKnown and MtrMarks.
     * @param stated the lines whose rails the reading named rather than left to be worked out, which decides whether what was kept of them is replaced or added to.
     */
    record Built(List<Station> stations, List<LineStop> stops, List<TransitLine> lines,
                 Map<Long, RoadNetwork> tracks, Set<Long> stated,
                 List<MtrClientData.Track> rawRails, int imported, int skipped,
                 int unplaced) {

        static final Built EMPTY = new Built(List.of(), List.of(), List.of(), Map.of(), Set.of(),
                List.of(), 0, 0, 0);

        /** Every line's track as one network. */
        RoadNetwork rails() {
            return MtrKnown.union(tracks.values());
        }
    }

    /**
     * One MTR station, as a place a journey can end at; when several MTR stations are one place, this is that place and carries the lowest of their ids.
     * @param id MTR's own id, which is what makes two readings the same station -- a name can change and a position can move
     * @param x where its vehicles stop: the middle of its platforms, or of its area
     */
    public record Station(long id, String name, int x, int y, int z) {
    }

    /** Counts the passes below fill in, so that they stay functions of their arguments. */
    private static final class Counts {
        private int skipped;
        private int unplaced;
    }

    /**
     * Turns a reading into this mod's stops, lines and the track they run along, over the rails this
     * session has already seen.
     * A reading on its own is a window: MTR replaces the client's rail set with every update, so a
     * line whose neighbouring stops were never both inside one window used to have no ride at all,
     * and one that was planned stopped where the window did. The memory is what lets the plan span
     * readings. A function of the reading, the id counter, the remembered rails and nothing else, so
     * the whole conversion can be checked with no MTR installed.
     * The marks switch is deliberately not an argument: every line's track is worked out and MtrKnown
     * decides which of them are added as roads, so a switched-off line is still drawn along its track
     * and switching it on needs no fresh reading.
     * @param nextId the counter the track ids are drawn from, handed in so that two readings of one
     * session cannot number two pieces alike
     */
    static Built build(MtrClientData.Snapshot reading, int[] nextId) {
        return build(reading, nextId, List.of());
    }

    /**
     * The same, over the rails the memory already holds.
     * @param remembered rails MTR sent in earlier windows of this session or a previous one, already
     * deduplicated by MTR's id; the reading's own copy of a rail wins over it
     */
    static Built build(MtrClientData.Snapshot reading, int[] nextId,
                       List<MtrClientData.Track> remembered) {
        if (reading.isEmpty()) {
            return Built.EMPTY;
        }
        Counts counts = new Counts();
        // The stations first, because the lines are placed on them: a stop goes where its station's vehicles stop, which after the grouping is the place rather than whichever MTR area the stop named.
        Places places = places(reading);
        List<TransitLine> builtLines = buildLines(reading, places, counts);
        BuiltTracks tracks = buildTracks(reading, builtLines, nextId, remembered);
        return new Built(places.stations(), stopsOf(places.stations()), builtLines, tracks.byLine(),
                tracks.stated(), reading.tracks(), builtLines.size(), counts.skipped,
                counts.unplaced);
    }

    /**
     * The track each line runs along, and which of those the reading stated rather than left to be worked out. The two are kept differently: a stated reading names all rails, so it replaces what was kept, while a worked-out one says only what is near the player and is added to the rest.
     */
    private record BuiltTracks(Map<Long, RoadNetwork> byLine, Set<Long> stated) {
    }

    /**
     * The track every line runs along, by line.
     * MTR's own rails are read first because a path has to be found over them, and then thrown away: what comes out is the lines' rides and never the rails as a whole.
     * Per line rather than folded into one network, because a reading is kept per line once the player has walked away from it (see MtrKnown); the ids come from the caller's counter, so tracks of successive readings can be merged.
     * A reading that names a line's rails is taken as it gives them (MtrLineTracks.stated); only a silent reading -- MTR's own client data, which joins a route to its platforms and nothing else -- plans a ride between neighbouring stops over the rails in hand. That fallback is expensive, so its layer, extent and router workspace are built only when some line needs it.
     * One router workspace is shared by every line that needs planning: a workspace copies the rail layer, so one per line would copy the whole layer once per line.
     */
    private static BuiltTracks buildTracks(MtrClientData.Snapshot reading,
                                           List<TransitLine> lines, int[] nextId,
                                           List<MtrClientData.Track> remembered) {
        Map<Long, RoadNetwork> tracks = new HashMap<>();
        Set<Long> statedIds = new HashSet<>();
        if (lines.isEmpty()) {
            // No line, no track: MTR's rails are not even joined into a layer.
            return new BuiltTracks(tracks, statedIds);
        }

        // Everything this build may plan over, the window's own rails first so its copy -- the one with
        // a real height and mode -- is the one kept when a rail is in both.
        Map<String, MtrClientData.Track> railsById = new LinkedHashMap<>();
        for (MtrClientData.Track rail : reading.tracks()) {
            if (rail.hexId() != null) {
                railsById.put(rail.hexId(), rail);
            }
        }
        List<MtrClientData.Track> allRails = new ArrayList<>(reading.tracks());
        Set<String> taken = new HashSet<>(railsById.keySet());
        for (MtrClientData.Track rail : remembered) {
            String id = rail.hexId();
            if (id != null && !taken.contains(id)) {
                taken.add(id);
                railsById.put(id, rail);
                allRails.add(rail);
            }
        }

        Map<Long, MtrClientData.Line> sources = new HashMap<>();
        for (MtrClientData.Line line : reading.lines()) {
            sources.put(line.id(), line);
        }

        // What each line runs along, where MTR says so -- in this window or in one this session kept.
        // A line whose named rails are only partly in the window is completed out of the memory, which
        // is the difference between a line that stops at the edge of the window and one that does not.
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
            rails = buildRailLayer(allRails);
            // Asked once for the whole reading rather than once per line: it is a walk of the layer, and the layer is the same for every line of it. See MtrLineTracks.Extent.
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
     * The rails one line runs along, as the reading gives them, or null when it does not say. Ids not held by the reading are left out, which MtrLineTracks.stated draws as a gap; null rather than empty is what sends the line to the planning path.
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
     * Starts the build for the newest reading, without waiting for it or for anybody to ask. For the client tick, where a reading first appears: the conversion is not done where it is asked for, so the first asker of a session would otherwise get nothing. Does nothing when a build for this reading is already under way.
     */
    public static void warmUp() {
        refresh();
    }

    /**
     * Hands a changed reading to the worker, and answers with the last build that finished. Keyed by a signature of the reading rather than rebuilt per call, because a plan asks for the lines several times and a map asks while it draws. Nothing here blocks: the first call of a session answers with nothing and the reading arrives a moment later.
     */
    private static void refresh() {
        MtrClientData.Snapshot reading = MtrClientData.snapshot();
        boolean enabled = RoadConfig.mtrTransit();
        int marks = MtrMarks.version();
        int epoch = memoryEpoch;
        // Worked out once per reading rather than once per asker: the map asks for one line's track per line per frame, so a signature that walks every line was being built L times a frame, the square of the line count. It is made of the reading, the switch and the player's answers about marks; the reading's identity is what says the signature still holds.
        String wanted;
        if (keyedSignature != null && reading == keyedReading && enabled == keyedEnabled
                && marks == keyedMarks && epoch == keyedEpoch) {
            wanted = keyedSignature;
        } else {
            // The epoch comes before the reading's own part: adopting a saved memory changes what a
            // build makes of the same reading, and a signature that ignored it would keep the plan
            // that was made before the memory was there.
            wanted = epoch + ";" + key(reading, enabled);
            keyedReading = reading;
            keyedEnabled = enabled;
            keyedMarks = marks;
            keyedEpoch = epoch;
            keyedSignature = wanted;
        }
        if (wanted.equals(requested)) {
            return;
        }
        requested = wanted;
        if (building.compareAndSet(false, true)) {
            WORKER.execute(MtrTransit::work);
        }
        // A build already running will notice that requested moved on, because it compares the signature it finished against this one before it lets go of the flag.
    }

    /** The last signature worked out, and what it was worked out from. See refresh. */
    private static MtrClientData.Snapshot keyedReading;
    private static boolean keyedEnabled;
    private static int keyedMarks = Integer.MIN_VALUE;
    private static int keyedEpoch = Integer.MIN_VALUE;
    private static String keyedSignature;

    /**
     * One build per request, however many arrive while it runs. A request arriving during a build cannot start a second one, so the only place it can be noticed is here, after the flag is given back: either this thread picks the new reading up, or another thread took the flag first and will publish it.
     */
    private static void work() {
        while (true) {
            String signature = requested;
            try {
                rebuild(MtrClientData.snapshot(), RoadConfig.mtrTransit());
            } catch (RuntimeException | LinkageError failed) {
                // A conversion that throws must cost the player the reading, not the game: what has already been published stays, and the next reading tries again.
                reportFailure(failed);
            } finally {
                // Released whatever happened, including an error this does not catch: a flag left set by a build that died is a reading that is never worked out again.
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
     * Reports the conversion having failed, once. Once and not once per reading: a shape that has moved does not move back, and a warning every few seconds is exactly what this mod's rail report was rewritten to stop being.
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
     * Converts one reading and publishes everything that comes out of it. Runs on the worker and nowhere else, which is what makes known and markIds safe to touch without locks.
     */
    private static void rebuild(MtrClientData.Snapshot reading, boolean enabled) {
        // The saved memory, when a world was opened with one: it goes in before anything is planned, so
        // the first build of the session already plans over the whole railway that was walked before.
        // Consumed whether or not the integration is on, so a memory adopted while it was off does not
        // lie in wait for a later build.
        List<MtrClientData.Track> seed = pendingSeed;
        if (seed != null) {
            pendingSeed = null;
            known.clear();
            known.seedRails(seed);
        }
        if (!enabled) {
            // Switched off means forgotten, not merely hidden: a player turning the integration off is asking for the mod to know nothing about their railway.
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
            clearPublishedRails();
            return;
        }

        long startedAt = System.nanoTime();
        Built built = build(reading, markIds, known.rails());
        // What this reading adds to what the ones before it taught, and then the answers taken from the whole of that rather than from this reading alone: MTR sends only what is near, so a reading read on its own is a window that closes behind the player.
        known.remember(built);
        List<Station> keptStations = known.stations();
        List<TransitLine> keptLines = known.lines();
        RoadNetwork marks = known.marks(MtrTransit::marksWanted);
        List<LineStop> keptStops = stopsOf(keptStations);
        // The rails are published before the marks so the store that saves them sees the same set the
        // plan was made over, not the window the build started from.
        publishRails(known);
        // Published one at a time, and each complete: a reader on another thread gets either the build before this one or this one. Two accessors asked a moment apart can therefore disagree about which reading they answer for -- the same bargain MtrWholeMap makes -- which is why nothing here is a pair of values that must agree.
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
            // The time is here because marking plans a ride per pair of neighbouring stops, and this is the number that says whether it is still worth doing off the thread it used to run on.
            HowToGo.diagnostic("[HowToGo] MTR import | stops {} lines {} (skipped {} unplacedStops {}) "
                            + "| marks {} rails {} nodes {} | kept {} lines {} stations {} track pieces "
                            + "| remembered {} rails | {} ms | {}",
                    keptStops.size(), imported, skipped, unplaced, marks.segmentCount(),
                    marks.nodeCount(), keptLines.size(), keptStations.size(), known.markSegments(),
                    known.railCount(),
                    Math.round((System.nanoTime() - startedAt) / 1_000_000.0), describe(keptLines));
        }
    }

    /**
     * Publishes the railway the plan may use, for the store that saves it.
     * Only when the count moved: every reading re-sends the window's rails, and copying an unchanged
     * list of tens of thousands of tracks once a second is work nobody asked for. A rail whose
     * geometry MTR edited without changing the count keeps the older copy in the store, which costs a
     * stale curve in a saved file at worst -- the next visit's window carries the new one.
     */
    private static void publishRails(MtrKnown memory) {
        int count = (int) memory.railCount();
        if (count == railSnapshotCount) {
            return;
        }
        railSnapshotCount = count;
        railSnapshot = memory.rails();
        railStamp++;
    }

    /** Says the memory holds nothing, for the branch where the integration is switched off. */
    private static void clearPublishedRails() {
        if (railSnapshotCount == 0 && railSnapshot.isEmpty()) {
            return;
        }
        railSnapshotCount = 0;
        railSnapshot = List.of();
        railStamp++;
    }

    /**
     * The signature of a reading, as one string: two halves that change for different reasons. The first describes the reading and is cached against it (see keyed), because it walks every line and rail. The second is every line's own marks switch, asked every time, of the lines of the newest reading and of the ones only remembered.
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
            // The same for what is only remembered: a line the player has walked away from is no longer in the reading, so its switch would otherwise change nothing until MTR sent that part of the world again.
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
                // A rail that left the client's window as another arrived leaves the count alone, and the marks are cut out of the rails, so what they are made of is part of what this notices.
                .append('/').append(railSignature(reading));
        String built = part.toString();
        // One write, and a reader sees either the whole pair or the one before it -- never a signature that is the wrong one.
        keyed = new KeyedPart(reading, built);
        return built;
    }

    /**
     * The ids of the lines only remembered, published by the worker. A copy rather than the memory's own list, which belongs to the worker; what is handed out is immutable.
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
     * railLayer() is every switched-on line's track in one network, and copying it before a plan is the single largest thing a plan pays for. A reading that names each line's rails makes it unnecessary, and a caller that knows this can leave the layer out of the world entirely.
     * Asked of the lines actually in play, including the player's own: one of those has no track of its own and does ride the shared layer, so its presence keeps the layer.
     * One refresh() and a lookup per line rather than trackOf per line, which would re-derive the signature for each.
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
     * Whether this line's track is marked as roads of this mod. Nothing is marked at all before MTR is being read; inside that, the player's answer for the line if they gave one, otherwise the configured default -- see MtrMarks.
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
     * Whether a line is planned over MTR's track. A player's line takes the configured default, having no answer of its own. For an imported line this is the same question marksWanted answers, deliberately: whether a track is marked and whether a ride uses it cannot be two different answers, or the switch would draw one thing and route another.
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
     * Whether any line has marks switched off, and so needs the network without them built. Asked before a plan: a plan over lines that all agree runs on one network, and the second copy of the world is only worth making when a line actually wants the difference.
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
     * MTR's own id for an imported line, or null when the line is not one of MTR's. Boxed rather than a sentinel: MTR's ids are longs and half of them have the top bit set, so a negative number cannot mean not ours -- it did here once, and the switch beside every such line did nothing.
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
            // Unsigned, because the id was written with Long.toHexString and that prints a negative long as its sixteen-digit bit pattern: read as signed, every id with the top bit set overflowed and the line was dropped as somebody else's. Half of MTR's ids are like that.
            return Long.parseUnsignedLong(line.id().substring(LINE_ID_PREFIX.length()), 16);
        } catch (NumberFormatException notOurs) {
            // An id this class did not write, which means something else is using the prefix.
            return null;
        }
    }

    /**
     * Whether a line came from MTR rather than from the player. By its id, which is prefixed: anything holding a line can tell what it holds without a second field to keep in step, and without the line model having to learn about MTR.
     */
    public static boolean isImported(TransitLine line) {
        return line != null && line.id().startsWith(LINE_ID_PREFIX);
    }


    /**
     * The stations of a reading, as the places a journey can end at.
     * MTR makes a station of every area the player draws, so a station built a platform at a time arrives as several stations sharing a name a few blocks apart -- offered as they arrive, that is a picker listing one station four times, four markers for one building, and a line calling at whichever one its platform fell in.
     * So stations that say they are the same are one place: the same name (case and surrounding spaces ignored) with boarding points within mtr_station_merge_blocks. The distance keeps two towns that both called their station Central apart; the name keeps different stations of one complex apart. Nameless stations are never merged, and a configured distance of zero turns the rule off.
     * Nothing is written back to MTR: this is how the reading is offered, not a change to it. A journey planned to a place reaches whichever of its stations the ride runs to.
     */
    private static Places places(MtrClientData.Snapshot reading) {
        List<MtrClientData.Station> raw = reading.stations();
        int[] group = new int[raw.size()];
        for (int i = 0; i < group.length; i++) {
            group[i] = i;
        }

        double merge = RoadConfig.mtrStationMergeBlocks();
        if (merge > 0) {
            // Only stations that share a name are ever compared: MTR's names are what says two areas are one station, so anything else needs no measuring -- which keeps this from being the square of the network on every rebuild.
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
                // No boarding point anywhere in the group, which a station of this reading cannot be: kept as left out rather than placed at a guess.
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
     * One place, out of the MTR stations that are it. It takes the lowest of their ids and that station's name, which keeps both stable across rebuilds -- a place whose id moved would be a second entry in the memory beside the first.
     * It stands at the middle of its stations' boarding points: on the railway, and so a stop a route can reach.
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
     * The stations of a reading as places, and which place each of MTR's stations belongs to. The lookup is what the lines need: a stop names an MTR station, and after grouping that station is one of several making a place, so the stop goes on the place.
     */
    private record Places(List<Station> stations, Map<Long, Station> byStationId) {

        /** The place an MTR station id belongs to, or null when this reading does not hold it. */
        Station of(long mtrStationId) {
            return byStationId.get(mtrStationId);
        }
    }

    /**
     * The stations as the planner's kind of stop. A projection rather than a second walk, so the rule that decides where a station's vehicles stop has one home and a station cannot be offered at one place and planned to at another.
     */
    private static List<LineStop> stopsOf(List<Station> stations) {
        List<LineStop> built = new ArrayList<>(stations.size());
        for (Station station : stations) {
            built.add(LineStop.ofStation(station.name(), station.x(), station.z()));
        }
        return List.copyOf(built);
    }

    /**
     * The reading's lines as this mod's lines, with the colour each one runs under.
     * A colour is a property of MTR's route, and all three readings end up in the same Line record, so this one place carries it across; doing it per reader would be three chances to forget, and a line that lost its colour draws in its kind's colour with nothing on screen saying why.
     * MTR writes zero for no colour while TransitLine says it with null, and the RGB part is tested because the readings disagree about whether they carry MTR's alpha. Zero is passed as null, never setColor(0), which would mean the player asked for ARGB black.
     * The circular state is translated here too, for the same three-reader argument: a circular route is a loop ridden one way round, exactly LineDirection.ONE_WAY; every other route, and a reading that cannot say, stays two-way so no restriction is invented.
     */
    private static List<TransitLine> buildLines(MtrClientData.Snapshot reading, Places places,
                                                Counts counts) {
        List<TransitLine> built = new ArrayList<>();
        for (MtrClientData.Line line : reading.lines()) {
            RoadClass kind = line.kind();
            if (kind == null) {
                // An aeroplane, or a type a later MTR adds: there is no kind of line here for it, and a line nothing can be routed along is not offered as one.
                counts.skipped++;
                continue;
            }
            TransitLine made = new TransitLine(LINE_ID_PREFIX + Long.toHexString(line.id()),
                    line.name() == null ? Long.toHexString(line.id()) : line.name(), kind);
            // MTR's circular state is the one-way case: a loop is ridden from the first stop to the last and nowhere else.
            made.setDirection(line.circular() ? LineDirection.ONE_WAY : LineDirection.TWO_WAY);
            // Zero is MTR's this route has no colour, not black. The value keeps its own RGB and the line makes it opaque, so what is drawn is the colour MTR's map draws.
            if ((line.color() & 0xFFFFFF) != 0) {
                made.setColor(line.color());
            }
            for (MtrClientData.Stop stop : line.stops()) {
                // The place the stop's station belongs to, which is where the stop goes: a station built a platform at a time arrives as several stations, and a line calling at one of them calls at the station -- so two lines meeting there must be two lines at one stop.
                Station place = places.of(stop.stationId());
                int[] position = place != null
                        ? new int[]{place.x(), place.z()}
                        : reading.stopPosition(stop.stationId());
                if (position == null) {
                    counts.unplaced++;
                    continue;
                }
                // A line that calls twice at one block refuses its second call, which is what keeps the next stop unambiguous.
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
     * MTR's rails as segments, joined where they meet. Rails are joined by position rather than by id, because the client's rail data has no node id to join on: two rails that meet in the world have ends at the same place and nothing else in common. The tolerance is a little over one block -- what a rounded coordinate can be out by -- and no more, so tracks a couple of blocks apart stay two tracks.
     * Ids come from ID_BASE so a segment of this layer is tellable from one the player drew, as a plan runs on a network holding both.
     */
    private static RoadNetwork buildRailLayer(List<MtrClientData.Track> tracks) {
        RoadNetwork layer = new RoadNetwork();
        if (tracks.isEmpty()) {
            return layer;
        }
        List<TrackPiece> pieces = new ArrayList<>();
        for (MtrClientData.Track track : tracks) {
            RoadClass kind = MtrClientData.roadClassFor(track.mode());
            if (kind == null || track.vertexCount() < 2) {
                continue;
            }
            int count = track.vertexCount();
            double[] xs = new double[count];
            double[] zs = new double[count];
            System.arraycopy(track.xs(), 0, xs, 0, count);
            System.arraycopy(track.zs(), 0, zs, 0, count);
            pieces.add(new TrackPiece(xs, zs, track.y(), kind));
        }
        if (pieces.isEmpty()) {
            return layer;
        }

        // One index entry per end of every rail piece, so what continues from here is a lookup in the nine cells around the point rather than a walk of the whole railway. A junction has two or more entries in one cell, which is what stops a run.
        Map<Long, List<End>> index = new HashMap<>();
        for (int piece = 0; piece < pieces.size(); piece++) {
            TrackPiece entry = pieces.get(piece);
            index.computeIfAbsent(cellKey(entry.x(0), entry.z(0)), k -> new ArrayList<>())
                    .add(new End(piece, true));
            int last = entry.count() - 1;
            index.computeIfAbsent(cellKey(entry.x(last), entry.z(last)), k -> new ArrayList<>())
                    .add(new End(piece, false));
        }

        boolean[] used = new boolean[pieces.size()];
        List<RoadNode> made = new ArrayList<>();
        int[] nextId = {ID_BASE};
        for (int seed = 0; seed < pieces.size(); seed++) {
            if (used[seed]) {
                continue;
            }
            TrackPiece first = pieces.get(seed);
            used[seed] = true;
            RoadClass kind = first.kind();
            int y = first.y();
            Run run = new Run();
            appendPiece(run, first, false);

            // Forward from the run's far end, then backward from its near end: a rail handed back in the middle of a line is stitched both ways rather than sitting as a stub until its neighbours arrive first.
            boolean grown = true;
            while (grown) {
                grown = false;
                int[] last = run.lastVertex();
                End next = uniqueContinuation(pieces, used, index, last[0], last[1], y, kind, seed);
                if (next != null) {
                    TrackPiece piece = pieces.get(next.piece());
                    used[next.piece()] = true;
                    appendPiece(run, piece, !next.atStart());
                    grown = true;
                }
            }
            while (true) {
                int[] start = run.firstVertex();
                End previous = uniqueContinuation(pieces, used, index, start[0], start[1], y, kind,
                        seed);
                if (previous == null) {
                    break;
                }
                TrackPiece piece = pieces.get(previous.piece());
                used[previous.piece()] = true;
                Run prepended = new Run();
                appendPiece(prepended, piece, previous.atStart());
                // The piece is laid down so its connecting end is last; the run already starts at that point, so the shared joint is written once.
                if (!prepended.vertices.isEmpty()
                        && prepended.lastVertex()[0] == start[0]
                        && prepended.lastVertex()[1] == start[1]) {
                    prepended.dropLast();
                }
                run.prepend(prepended);
            }

            int[] startSpace = run.firstVertex();
            int[] endSpace = run.lastVertex();
            RoadNode from = nodeAt(layer, made, nextId, startSpace[0], y, startSpace[1]);
            RoadNode to = nodeAt(layer, made, nextId, endSpace[0], y, endSpace[1]);
            if (from.id() == to.id()) {
                // A rail that ends where it started carries nothing a route can use.
                continue;
            }
            RoadSegment segment = new RoadSegment(nextId[0]++, kind, y, run.vertices.size());
            for (int[] point : run.vertices) {
                segment.addVertex(point[0], point[1]);
            }
            segment.setFromNode(from.id());
            segment.setToNode(to.id());
            // MTR hands rails over as world-coordinate samples of its curves, not as blocks: the display line keeps those samples (rounded to blocks it had a half-block wobble on every diagonal) and pins its ends on the node positions, so two runs meeting at a junction meet on the same point.
            List<double[]> display = run.display;
            display.set(0, new double[]{from.x(), from.z()});
            display.set(display.size() - 1, new double[]{to.x(), to.z()});
            segment.setCurve(RoadCurve.precise(display));
            layer.putSegment(segment);
        }
        return layer;
    }

    /**
     * The rail layer for a reading, for the regression harness. Package-private: it is the conversion and nothing else, and only the harness, which lives in this package, asks for it without a game.
     */
    static RoadNetwork railLayerForCheck(MtrClientData.Snapshot reading) {
        return buildRailLayer(reading.tracks());
    }

    /**
     * One rail piece as the layer builds it: MTR's world-coordinate samples, the height, and the class its mode means. The samples stay doubles because they are a curve; x(int) and z(int) are the block positions the joint index is built on, used only to decide what joins, never as the drawn shape.
     */
    private record TrackPiece(double[] xs, double[] zs, int y, RoadClass kind) {

        int count() {
            return xs.length;
        }

        int x(int index) {
            return (int) Math.round(xs[index]);
        }

        int z(int index) {
            return (int) Math.round(zs[index]);
        }
    }

    /** One end of one rail piece, and whether that end is the piece's first vertex. */
    private record End(int piece, boolean atStart) {
    }

    /** How close two rail ends must be to be read as one joint, in blocks. */
    private static final int JOINT_BLOCKS = 2;

    /** The cell a point is filed under for the joint lookup; see cellKey. */
    private static final int JOINT_CELL = 4;

    private static long cellKey(int x, int z) {
        return ((long) Math.floorDiv(x, JOINT_CELL) << 32)
                ^ (Math.floorDiv(z, JOINT_CELL) & 0xFFFFFFFFL);
    }

    /**
     * The one unused piece that continues from a point, or null when there is none or more than one. More than one is a junction: the run ends there and the other side is a run of its own.
     */
    private static End uniqueContinuation(List<TrackPiece> pieces, boolean[] used,
                                          Map<Long, List<End>> index, int x, int z, int y,
                                          RoadClass kind, int seed) {
        List<End> near = new ArrayList<>(2);
        int cellX = Math.floorDiv(x, JOINT_CELL);
        int cellZ = Math.floorDiv(z, JOINT_CELL);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                List<End> bucket = index.get(((long) (cellX + dx) << 32)
                        ^ ((cellZ + dz) & 0xFFFFFFFFL));
                if (bucket == null) {
                    continue;
                }
                for (End end : bucket) {
                    int piece = end.piece();
                    TrackPiece entry = pieces.get(piece);
                    if (entry.kind() != kind || Math.abs(entry.y() - y) > 2) {
                        continue;
                    }
                    int endX = end.atStart() ? entry.x(0) : entry.x(entry.count() - 1);
                    int endZ = end.atStart() ? entry.z(0) : entry.z(entry.count() - 1);
                    if (Math.abs(endX - x) <= JOINT_BLOCKS && Math.abs(endZ - z) <= JOINT_BLOCKS) {
                        near.add(end);
                    }
                }
            }
        }
        // Three or more ends at one point is a junction: the run ends there and the other branches are runs of their own. Two ends are one piece continuing into another -- unless both are the piece already in the run.
        if (near.size() != 2) {
            return null;
        }
        End unused = null;
        for (End end : near) {
            if (!used[end.piece()]) {
                if (unused != null || end.piece() == seed) {
                    return null;
                }
                unused = end;
            }
        }
        return unused;
    }

    /**
     * One stitched run of rails: the block vertices the network routes on, and the world-coordinate samples the display curve is made from.
     * Two lists because the readers want different things: the network stores blocks (a node is a block position) while MTR's geometry is a curve in world coordinates, and rounding before smoothing is what put a wobble on it. Keeping them side by side means the curve is never derived from the rounded form, and the joints are dropped from both at once, so the two cannot disagree about where the run is.
     */
    private static final class Run {
        private final List<int[]> vertices = new ArrayList<>();
        private final List<double[]> display = new ArrayList<>();

        int[] firstVertex() {
            return vertices.get(0);
        }

        int[] lastVertex() {
            return vertices.get(vertices.size() - 1);
        }

        int vertexCount() {
            return vertices.size();
        }

        void add(int x, int z, double displayX, double displayZ) {
            vertices.add(new int[]{x, z});
            display.add(new double[]{displayX, displayZ});
        }

        boolean endsAt(int x, int z) {
            if (vertices.isEmpty()) {
                return false;
            }
            int[] last = lastVertex();
            return last[0] == x && last[1] == z;
        }

        /** Drops the last point of both lists, for a joint the other piece is about to write. */
        void dropLast() {
            if (!vertices.isEmpty()) {
                vertices.remove(vertices.size() - 1);
            }
            if (!display.isEmpty()) {
                display.remove(display.size() - 1);
            }
        }

        void prepend(Run other) {
            vertices.addAll(0, other.vertices);
            display.addAll(0, other.display);
        }
    }

    /** Appends a piece to a run, reversed or not, dropping a repeated joint vertex. */
    private static void appendPiece(Run run, TrackPiece piece, boolean reverse) {
        int count = piece.count();
        for (int i = 0; i < count; i++) {
            int index = reverse ? count - 1 - i : i;
            int x = piece.x(index);
            int z = piece.z(index);
            if (run.endsAt(x, z)) {
                // The joint is already written by the piece next to this one; writing its rounded position again would leave a zero-length step, and its display position is the same joint.
                continue;
            }
            run.add(x, z, piece.xs()[index], piece.zs()[index]);
        }
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
