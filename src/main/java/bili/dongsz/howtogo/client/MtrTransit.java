package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.HowToGo;
import bili.dongsz.howtogo.RoadConfig;
import bili.dongsz.howtogo.road.RoadClass;
import bili.dongsz.howtogo.road.RoadNetwork;
import bili.dongsz.howtogo.road.RoadNode;
import bili.dongsz.howtogo.road.RoadSegment;
import bili.dongsz.howtogo.transit.LineStop;
import bili.dongsz.howtogo.transit.TransitLine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongPredicate;

/**
 * What {@link MtrClientData} read, said in this mod's own terms.
 *
 * <h2>Three things come out of it</h2>
 * <ul>
 *   <li><b>stops</b> -- one per MTR station, for the destination and stop pickers to offer beside the
 *       player's own places and the stations Create reports. Read-only by construction, exactly as a
 *       Create station is: {@link LineStop#ofStation} carries no node id, so there is nothing here the
 *       player could rename or retype by accident;</li>
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
 * see {@link MtrClientData.Snapshot#stopPosition}.
 *
 * <h2>Why a line may be short</h2>
 * MTR sends a client what is near it, so the stops of a long line arrive a window at a time: what is
 * offered here is the part of the line around the player, which is also the part they could actually
 * board. A line is not padded out to look complete, a stop whose station the client has not been sent
 * is left out rather than placed wrongly and counted, and a line left with fewer than two placed stops
 * is not offered at all -- a line with no ride in it would only ever answer "no journey".
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

    private static String signature = "";
    private static List<LineStop> stops = List.of();
    private static List<Station> stationList = List.of();
    private static List<TransitLine> lines = List.of();
    private static RoadNetwork rails = new RoadNetwork();
    private static int imported;
    private static int skipped;
    private static int unplaced;

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

    /** How many lines are remembered, including the ones MTR is no longer sending. */
    public static int rememberedLines() {
        refresh();
        return known.lines().size();
    }

    /** How many stations are remembered, including the ones MTR is no longer sending. */
    public static int rememberedStations() {
        refresh();
        return known.stations().size();
    }

    /**
     * What a reading becomes, and what had to be left out of it.
     *
     * @param marks the track each line runs along, by MTR's own line id: per line and not merged, because
     *              what is kept of a reading is kept per line -- see {@link MtrKnown}, which is what
     *              makes a line survive the player walking out of MTR's range
     */
    record Built(List<Station> stations, List<LineStop> stops, List<TransitLine> lines,
                 Map<Long, RoadNetwork> marks, int imported, int skipped, int unplaced) {

        static final Built EMPTY =
                new Built(List.of(), List.of(), List.of(), Map.of(), 0, 0, 0);

        /** Every line's marks as one network, which is only ever what a check asks for. */
        RoadNetwork rails() {
            return MtrKnown.union(marks.values());
        }
    }

    /**
     * One MTR station, as a place a journey can end at.
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
     * <p>A function of the reading and one question, and of nothing else, so that everything about the
     * conversion can be checked with no MTR installed -- which is the only way it can be checked at all
     * here. The state above is a cache of this, not the other way round.
     *
     * @param wantsMarks asked per MTR line id: whether that line's track is marked as roads of this mod
     *                   at all. The line's own answer, so that the caller -- which is the only place
     *                   that may read a config or a switch, and the only place that can do it off the
     *                   render thread -- decides, and this stays a function
     * @param nextId     the id counter the marks are drawn from, handed in so that two readings of one
     *                   session cannot number two different marks alike: what is kept of a reading is
     *                   merged with what was kept of the ones before it, and ids that repeat would make
     *                   that merge lose track
     */
    static Built build(MtrClientData.Snapshot reading, LongPredicate wantsMarks, int[] nextId) {
        if (reading.isEmpty()) {
            return Built.EMPTY;
        }
        Counts counts = new Counts();
        List<Station> builtStations = buildStations(reading);
        List<TransitLine> builtLines = buildLines(reading, counts);
        return new Built(builtStations, stopsOf(builtStations), builtLines,
                buildMarks(reading, builtLines, wantsMarks, nextId), builtLines.size(),
                counts.skipped, counts.unplaced);
    }

    /**
     * The track of every line that wants its marks, by line.
     *
     * <p>MTR's own rails are read first, because the path a line runs along has to be found over them,
     * and then thrown away: what comes out is the lines' rides and never the rails as a whole. Nothing
     * of MTR's own geometry reaches a plan, which is what makes a mark the track <em>this line</em>
     * uses rather than every rail within reach of the player.
     *
     * <p>Per line rather than folded into one network, because a reading is kept per line once it has
     * been read and the player has walked away from it -- see {@link MtrKnown}. The ids come from the
     * caller's counter, so the marks of one reading can be merged with the marks of the next.
     */
    private static Map<Long, RoadNetwork> buildMarks(MtrClientData.Snapshot reading,
                                                     List<TransitLine> lines,
                                                     LongPredicate wantsMarks, int[] nextId) {
        Map<Long, RoadNetwork> marks = new HashMap<>();
        boolean wanted = false;
        for (TransitLine line : lines) {
            Long id = lineId(line);
            if (id != null && wantsMarks.test(id)) {
                wanted = true;
                break;
            }
        }
        if (!wanted) {
            // Nothing is marked, so MTR's rails are not even joined into a layer: a player who wants
            // their own roads and none of MTR's pays nothing for the reading.
            return marks;
        }
        RoadNetwork rails = buildRailLayer(reading);
        for (TransitLine line : lines) {
            Long id = lineId(line);
            if (id == null || !wantsMarks.test(id)) {
                continue;
            }
            RoadNetwork ofLine = MtrLineTracks.of(rails, line, nextId);
            if (ofLine.segmentCount() > 0) {
                marks.put(id, ofLine);
            }
        }
        return marks;
    }

    /**
     * Rebuilds from the last reading when it has changed.
     *
     * <p>Keyed by a signature of the reading rather than rebuilt per call, because a plan asks for the
     * lines several times and a map asks while it draws. Reported when it changes and not otherwise:
     * the reading behind it is re-read every second, and a line a second would bury everything else in
     * the log -- which is exactly what this mod's own rail diagnostic did until it was noticed.
     */
    private static void refresh() {
        MtrClientData.Snapshot reading = MtrClientData.snapshot();
        boolean enabled = RoadConfig.mtrTransit();
        StringBuilder key = new StringBuilder();
        key.append(enabled).append(';')
                .append(reading.stations().size()).append('/')
                .append(reading.platforms().size()).append('/')
                .append(reading.lines().size()).append('/')
                .append(reading.tracks().size())
                // A rail that left the client's window as another arrived leaves the count alone, and
                // the marks are cut out of the rails, so what they are made of is part of what this
                // notices rather than only how many of them there are.
                .append('/').append(railSignature(reading));
        for (MtrClientData.Line line : reading.lines()) {
            // Which lines want their track marked is part of the signature too: flipping a switch has
            // to rebuild, and the switch is read here rather than in the conversion so that the
            // conversion stays a function of the reading and a decision it is handed.
            key.append('|').append(line.id()).append(':').append(line.stops().size())
                    .append(marksWanted(line.id()) ? '+' : '-');
        }
        for (TransitLine line : known.lines()) {
            // And the same for what is only remembered: a line the player has walked away from is no
            // longer in the reading, so its switch would otherwise change nothing until MTR happened to
            // send that part of the world again.
            Long id = lineId(line);
            if (id != null) {
                key.append('~').append(id).append(marksWanted(id) ? '+' : '-');
            }
        }
        String now = key.toString();
        if (now.equals(signature)) {
            return;
        }
        signature = now;

        imported = 0;
        skipped = 0;
        unplaced = 0;
        if (!enabled) {
            // Switched off means forgotten, not merely hidden: the readings are MTR's, and a player who
            // turns the integration off is asking for the mod to know nothing about their railway.
            known.clear();
            stops = List.of();
            stationList = List.of();
            lines = List.of();
            rails = new RoadNetwork();
            return;
        }

        long startedAt = System.nanoTime();
        Built built = build(reading, MtrTransit::marksWanted, markIds);
        // What this reading adds to what the ones before it taught, and then the answers taken from the
        // whole of that rather than from this reading alone: MTR sends a client only what is near it, so
        // a reading read on its own is a window that closes behind the player as they walk.
        known.remember(built);
        stops = stopsOf(known.stations());
        stationList = known.stations();
        lines = known.lines();
        rails = known.marks(MtrTransit::marksWanted);
        imported = built.imported();
        skipped = built.skipped();
        unplaced = built.unplaced();
        if (imported > 0 || skipped > 0 || !stops.isEmpty()) {
            // The time is here because marking plans a ride per pair of neighbouring stops, and this
            // runs wherever a reading is first asked for -- which can be the render thread, in the
            // middle of a map drawing. If it ever grows past a frame, that number is the evidence.
            HowToGo.LOGGER.info("[HowToGo] MTR import | stops {} lines {} (skipped {} unplacedStops {}) "
                            + "| marks {} rails {} nodes {} | kept {} lines {} stations {} track pieces "
                            + "| {} ms | {}",
                    stops.size(), imported, skipped, unplaced, rails.segmentCount(), rails.nodeCount(),
                    known.lines().size(), known.stations().size(), known.markSegments(),
                    Math.round((System.nanoTime() - startedAt) / 1_000_000.0), describe());
        }
    }

    /** A cheap fingerprint of the rails a reading holds, so a rail swapped for another is noticed. */
    private static int railSignature(MtrClientData.Snapshot reading) {
        int hash = 1;
        for (MtrClientData.Track track : reading.tracks()) {
            hash = hash * 31 + track.hexId().hashCode();
            hash = hash * 31 + track.vertexCount();
        }
        return hash;
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
    private static String describe() {
        StringBuilder text = new StringBuilder();
        int shown = 0;
        for (TransitLine line : lines) {
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

    /** One stop per station, where its vehicles stop. */
    private static List<Station> buildStations(MtrClientData.Snapshot reading) {
        List<Station> built = new ArrayList<>(reading.stations().size());
        for (MtrClientData.Station station : reading.stations()) {
            int[] position = reading.stopPosition(station.id());
            if (position == null) {
                continue;
            }
            built.add(new Station(station.id(), station.name() == null ? "" : station.name(),
                    position[0], station.centerY(), position[1]));
        }
        return List.copyOf(built);
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

    private static List<TransitLine> buildLines(MtrClientData.Snapshot reading, Counts counts) {
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
                int[] position = reading.stopPosition(stop.stationId());
                if (position == null) {
                    counts.unplaced++;
                    continue;
                }
                // A line that calls twice at one block is refused its second call by the line itself,
                // which is what keeps "the next stop" unambiguous.
                made.addStop(LineStop.ofStation(stop.stationName() == null ? "" : stop.stationName(),
                        position[0], position[1]));
            }
            if (made.stopCount() < 2) {
                continue;
            }
            built.add(made);
        }
        return List.copyOf(built);
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
