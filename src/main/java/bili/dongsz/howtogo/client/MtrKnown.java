package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.HowToGo;
import bili.dongsz.howtogo.road.RoadNetwork;
import bili.dongsz.howtogo.road.RoadNode;
import bili.dongsz.howtogo.road.RoadSegment;
import bili.dongsz.howtogo.transit.TransitLine;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongPredicate;

/**
 * Everything MTR has told this client so far, kept.
 *
 * <h2>Why a reading cannot be used on its own</h2>
 * MTR sends a client only what is near it, and re-sends it as the player moves: a reading is a window
 * that closes behind them. Used on its own, that window takes the player's railway with it -- the lines
 * vanish from the planner and the editor as they walk away, the stations stop being destinations, and
 * the track the marks are cut from is gone, so a journey over a line they were just riding cannot be
 * planned at all. That is not a bug in the reading; it is the reading being asked to mean more than it
 * says. So each reading is folded into what the earlier ones taught, and the answers are taken from the
 * whole of that.
 *
 * <h2>What each thing keeps</h2>
 * <ul>
 *   <li><b>lines and stations</b> -- the newest word about each, by MTR's own id. Walking back over
 *       ground the player has already covered updates them; walking away leaves the last word standing.
 *       A line is only replaced by a reading that placed at least as many of its stops, so a window
 *       arriving with fewer of them cannot shrink the line to what happens to be in range.</li>
 *   <li><b>track</b> -- the union of every stretch of that line's track ever found. Track is discovered a
 *       window at a time, so the pieces of it are added rather than replaced: the map draws the line
 *       along the track the player found earlier, and a ride planned from far away runs along it too. A
 *       piece already kept is not added twice, and the union is capped, so a session that walks the whole
 *       network cannot grow without limit.</li>
 * </ul>
 *
 * <p>Track is kept for every line, not only for the ones whose marks are switched on: the switch is about
 * whether a line's track is added to the road network as roads, and a line switched off is still drawn
 * along the track it runs on. Nothing here reads a config or a switch either -- which lines want their
 * roads is asked of the caller when the roads are assembled, so a line's answer applies to track that was
 * found before the answer was given.
 */
final class MtrKnown {

    /**
     * Ceiling on remembered lines.
     *
     * <p>A bound rather than a policy, and deliberately far above what any railway holds: the point of
     * the memory is that a line the player has seen stays usable, and a bound tight enough to be reached
     * by an ordinary network would take lines away again -- which is the bug the memory exists to fix,
     * arriving from the other side. The oldest is dropped, along with its marks, and it says so once.
     */
    static final int MAX_LINES = 512;

    /** Ceiling on remembered stations, for the same reason and at the same kind of number. */
    static final int MAX_STATIONS = 5000;

    /**
     * Ceiling on remembered mark segments.
     *
     * <p>Every window of track the player walks past adds its own segments, and a railway is a lot of
     * track: twenty thousand segments is some hundreds of kilometres of line, which is more than a
     * client can be sent in a session. Reaching it stops the union growing rather than the world.
     */
    static final int MAX_MARK_SEGMENTS = 20_000;

    /** How close two mark ends have to be to be the same piece of track, in blocks. */
    private static final double SAME_TRACK = 1.5;

    private final Map<Long, TransitLine> lines = new LinkedHashMap<>();
    private final Map<Long, MtrTransit.Station> stations = new LinkedHashMap<>();
    private final Map<Long, RoadNetwork> tracks = new LinkedHashMap<>();
    private long markSegments;
    private boolean lineCapReported;
    private boolean markCapReported;

    /**
     * Takes a reading into what is remembered.
     *
     * <p>The lines and stations it placed are the newest word about each; the marks it cut are added to
     * the ones already kept.
     */
    void remember(MtrTransit.Built reading) {
        for (MtrTransit.Station station : reading.stations()) {
            stations.put(station.id(), station);
        }
        for (TransitLine line : reading.lines()) {
            Long id = MtrTransit.mtrLineId(line);
            if (id == null) {
                continue;
            }
            TransitLine kept = lines.get(id);
            if (kept == null || line.stopCount() >= kept.stopCount()) {
                lines.put(id, line);
            }
        }
        for (Map.Entry<Long, RoadNetwork> entry : reading.tracks().entrySet()) {
            addMarks(entry.getKey(), entry.getValue());
        }
        cap();
    }

    /**
     * Adds one line's newly marked track to the track kept for it.
     *
     * <p>Piece by piece, and a piece already kept is not added: the same stretch of rail is marked again
     * every time the player's window slides back over it, and a union that kept every copy would grow by
     * the whole line every second.
     */
    private void addMarks(long lineId, RoadNetwork fresh) {
        if (fresh.segmentCount() == 0 || markSegments >= MAX_MARK_SEGMENTS) {
            if (markSegments >= MAX_MARK_SEGMENTS && !markCapReported) {
                markCapReported = true;
                HowToGo.LOGGER.warn("[HowToGo] MTR track memory is holding {} marked segments, its "
                        + "limit; no further track will be remembered this session", markSegments);
            }
            return;
        }
        RoadNetwork kept = tracks.computeIfAbsent(lineId, id -> new RoadNetwork());
        for (RoadSegment segment : fresh.segmentsSnapshot()) {
            if (markSegments >= MAX_MARK_SEGMENTS) {
                break;
            }
            if (holdsSameTrack(kept, segment)) {
                continue;
            }
            RoadNode from = segment.fromNode() >= 0 ? fresh.node(segment.fromNode()) : null;
            RoadNode to = segment.toNode() >= 0 ? fresh.node(segment.toNode()) : null;
            if (from != null) {
                kept.putNode(from.copy());
            }
            if (to != null) {
                kept.putNode(to.copy());
            }
            kept.putSegment(segment.copy());
            markSegments++;
        }
    }

    /** Whether the kept track already has a piece with this one's two ends. */
    private static boolean holdsSameTrack(RoadNetwork kept, RoadSegment segment) {
        int last = segment.vertexCount() - 1;
        for (RoadSegment other : kept.segmentsSnapshot()) {
            if (other.roadClass() != segment.roadClass()) {
                continue;
            }
            int otherLast = other.vertexCount() - 1;
            if (sameEnd(segment.x(0), segment.z(0), other.x(0), other.z(0))
                    && sameEnd(segment.x(last), segment.z(last), other.x(otherLast), other.z(otherLast))) {
                return true;
            }
        }
        return false;
    }

    private static boolean sameEnd(int ax, int az, int bx, int bz) {
        double dx = ax - bx;
        double dz = az - bz;
        return dx * dx + dz * dz <= SAME_TRACK * SAME_TRACK;
    }

    /** Drops the oldest lines once there are more than {@link #MAX_LINES} of them. */
    private void cap() {
        while (lines.size() > MAX_LINES) {
            Long oldest = lines.keySet().iterator().next();
            lines.remove(oldest);
            tracks.remove(oldest);
            if (!lineCapReported) {
                lineCapReported = true;
                HowToGo.LOGGER.warn("[HowToGo] MTR has reported more than {} lines; the earliest are "
                        + "being forgotten so that the newest are kept", MAX_LINES);
            }
            markSegments = 0;
            for (RoadNetwork network : tracks.values()) {
                markSegments += network.segmentCount();
            }
        }
        while (stations.size() > MAX_STATIONS) {
            stations.remove(stations.keySet().iterator().next());
        }
    }

    /** Every line MTR has reported, the earliest first. */
    List<TransitLine> lines() {
        return List.copyOf(lines.values());
    }

    /** Every station MTR has reported, as places to travel to. */
    List<MtrTransit.Station> stations() {
        return List.copyOf(stations.values());
    }

    /**
     * The track of every line that wants its marks, as one network.
     *
     * <p>Filtered here rather than when the track was worked out, so that a line's answer applies to
     * track that was found before the answer was given: a line switched off has its track left out of
     * the roads and keeps it remembered -- it is still drawn along it -- and switching it back on brings
     * the road back without a fresh reading.
     */
    RoadNetwork marks(LongPredicate wantsMarks) {
        List<RoadNetwork> wanted = new ArrayList<>();
        for (Map.Entry<Long, RoadNetwork> entry : tracks.entrySet()) {
            if (wantsMarks.test(entry.getKey())) {
                wanted.add(entry.getValue());
            }
        }
        return union(wanted);
    }

    /**
     * The track one line runs along, whether or not its marks are switched on.
     *
     * <p>What the map draws the line along. Null when MTR has never sent that line with rails under it,
     * which is a line whose track is not known rather than a line with no track.
     */
    RoadNetwork trackOf(long lineId) {
        return tracks.get(lineId);
    }

    /**
     * Several networks as one.
     *
     * <p>The ids are the caller's and are unique to a mark across the session, so a plain merge is all
     * this is: two lines over the same ground each keep their own road, which is what lets one of them be
     * switched off without the other losing its track.
     */
    static RoadNetwork union(Collection<RoadNetwork> networks) {
        RoadNetwork all = new RoadNetwork();
        for (RoadNetwork network : networks) {
            for (RoadNode node : network.nodesSnapshot()) {
                all.putNode(node);
            }
            for (RoadSegment segment : network.segmentsSnapshot()) {
                all.putSegment(segment);
            }
        }
        return all;
    }

    /** Forgets everything, which is what switching the integration off does. */
    void clear() {
        lines.clear();
        stations.clear();
        tracks.clear();
        markSegments = 0;
        lineCapReported = false;
        markCapReported = false;
    }

    /** How much track is remembered, for the log. */
    long markSegments() {
        return markSegments;
    }
}
