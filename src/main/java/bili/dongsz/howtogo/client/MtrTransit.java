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
import java.util.List;

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
 *   <li><b>a rail layer</b> -- MTR's rails as this mod's roads, read-only and never saved, so that a
 *       ride along a line read out of MTR is planned along the track MTR actually laid rather than
 *       along whatever the player happened to draw nearby.</li>
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
    private static List<TransitLine> lines = List.of();
    private static RoadNetwork rails = new RoadNetwork();
    private static int imported;
    private static int skipped;
    private static int unplaced;

    private MtrTransit() {
    }

    /** Every MTR station the client knows about, as a stop this mod can plan to. */
    public static List<LineStop> stops() {
        refresh();
        return stops;
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
     * MTR's rails as a read-only network, or an empty one.
     *
     * <p>Empty when {@code mtr_auto_route_marks} is off, which is the setting that means "do not bring
     * the track with you": a line's stops are then matched to the player's own roads by the rule that
     * was in force before this mod knew anything about MTR.
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

    /** What a reading becomes, and what had to be left out of it. */
    record Built(List<LineStop> stops, List<TransitLine> lines, RoadNetwork rails, int imported,
                 int skipped, int unplaced) {

        static final Built EMPTY =
                new Built(List.of(), List.of(), new RoadNetwork(), 0, 0, 0);
    }

    /** Counts the passes below fill in, so that they stay functions of their arguments. */
    private static final class Counts {
        private int skipped;
        private int unplaced;
    }

    /**
     * Turns a reading into this mod's stops, lines and rail layer.
     *
     * <p>A function of the reading and the one setting that shapes it, and of nothing else, so that
     * everything about the conversion can be checked with no MTR installed -- which is the only way it
     * can be checked at all here. The state above is a cache of this, not the other way round.
     */
    static Built build(MtrClientData.Snapshot reading, boolean autoRouteMarks) {
        if (reading.isEmpty()) {
            return Built.EMPTY;
        }
        Counts counts = new Counts();
        List<TransitLine> builtLines = buildLines(reading, counts);
        RoadNetwork builtRails = autoRouteMarks ? buildRailLayer(reading) : new RoadNetwork();
        return new Built(buildStops(reading), builtLines, builtRails, builtLines.size(),
                counts.skipped, counts.unplaced);
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
        boolean autoMarks = RoadConfig.mtrAutoRouteMarks();
        boolean enabled = RoadConfig.mtrTransit();
        StringBuilder key = new StringBuilder();
        key.append(autoMarks).append(';').append(enabled).append(';')
                .append(reading.stations().size()).append('/')
                .append(reading.platforms().size()).append('/')
                .append(reading.lines().size()).append('/')
                .append(reading.tracks().size());
        for (MtrClientData.Line line : reading.lines()) {
            key.append('|').append(line.id()).append(':').append(line.stops().size());
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
            stops = List.of();
            lines = List.of();
            rails = new RoadNetwork();
            return;
        }

        Built built = build(reading, autoMarks);
        stops = built.stops();
        lines = built.lines();
        rails = built.rails();
        imported = built.imported();
        skipped = built.skipped();
        unplaced = built.unplaced();
        if (imported > 0 || skipped > 0 || !stops.isEmpty()) {
            HowToGo.LOGGER.info("[HowToGo] MTR import | stops {} lines {} (skipped {} unplacedStops {}) "
                            + "| rails {} nodes {} | {}",
                    stops.size(), imported, skipped, unplaced, rails.segmentCount(), rails.nodeCount(),
                    describe());
        }
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
    private static List<LineStop> buildStops(MtrClientData.Snapshot reading) {
        List<LineStop> built = new ArrayList<>(reading.stations().size());
        for (MtrClientData.Station station : reading.stations()) {
            int[] position = reading.stopPosition(station.id());
            if (position == null) {
                continue;
            }
            built.add(LineStop.ofStation(station.name() == null ? "" : station.name(),
                    position[0], position[1]));
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
