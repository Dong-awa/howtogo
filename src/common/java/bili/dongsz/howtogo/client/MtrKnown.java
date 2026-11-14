package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.HowToGo;
import bili.dongsz.howtogo.RoadConfig;
import bili.dongsz.howtogo.road.RoadNetwork;
import bili.dongsz.howtogo.road.RoadNode;
import bili.dongsz.howtogo.road.RoadSegment;
import bili.dongsz.howtogo.transit.TransitLine;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongPredicate;

/**
 * Everything MTR has told this client so far, kept.
 * MTR sends a client only what is near it and re-sends as the player moves, so a reading alone is a
 * window that closes behind them: lines vanish from the planner and editor, stations stop being
 * destinations, and the track a just-ridden line was cut from is gone. Each reading is folded into
 * what earlier ones taught, and answers come from the whole.
 * Lines and stations keep the newest word per MTR id; a line is only replaced by a reading that
 * placed at least as many of its stops. Named track replaces what was kept; worked-out track is
 * unioned, deduplicated and capped.
 */
final class MtrKnown {

    /**
     * Ceiling on remembered lines.
     * Far above what any railway holds, because the point is that a line the player has seen stays
     * usable: a tight cap would take lines away again -- the bug the memory exists to fix. Sized for a
     * whole railway, since MtrWholeMap reads every line of a hosted network at once.
     */
    static final int MAX_LINES = 2048;

    static final int MAX_STATIONS = 20_000;

    /**
     * Ceiling on remembered mark segments: the bound on what one session keeps, so reaching it stops
     * the union growing rather than stopping the world. Counted in pieces of track, not rails.
     */
    static final int MAX_MARK_SEGMENTS = 100_000;

    /** How close two mark ends have to be to be the same piece of track, in blocks. */
    private static final double SAME_TRACK = 1.5;

    private final Map<Long, TransitLine> lines = new LinkedHashMap<>();
    private final Map<Long, MtrTransit.Station> stations = new LinkedHashMap<>();
    /**
     * The remembered stations by the name the grouping compares them under: a station arriving can
     * only match one whose name it shares, so a whole railway costs a walk rather than its square.
     */
    private final Map<String, List<MtrTransit.Station>> stationsByName = new HashMap<>();
    private final Map<Long, RoadNetwork> tracks = new LinkedHashMap<>();
    private long markSegments;
    private boolean lineCapReported;
    private boolean markCapReported;

    /**
     * Takes a reading into what is remembered: lines and stations are the newest word, marks are
     * added to the kept union.
     */
    void remember(MtrTransit.Built reading) {
        for (MtrTransit.Station station : reading.stations()) {
            rememberStation(station);
        }
        for (TransitLine line : reading.lines()) {
            Long id = MtrTransit.mtrLineId(line);
            if (id == null) {
                continue;
            }
            TransitLine kept = lines.get(id);
            if (kept == null) {
                lines.put(id, line);
                continue;
            }
            if (line.stopCount() < kept.stopCount()) {
                continue;
            }
            // The newer reading has the newest stops but not always everything known about the line: a colour
            // is a fact about MTR's route and not all readings merged here carry one. A winner without a colour
            // inherits the learned one; direction is deliberately not inherited, since a route's circular state
            // is MTR's answer while "no colour" means the reader could not ask.
            if (!line.hasColor() && kept.hasColor()) {
                line.setColor(kept.color());
            }
            lines.put(id, line);
        }
        for (Map.Entry<Long, RoadNetwork> entry : reading.tracks().entrySet()) {
            if (reading.stated().contains(entry.getKey())) {
                replace(entry.getKey(), entry.getValue());
            } else {
                addMarks(entry.getKey(), entry.getValue());
            }
        }
        cap();
    }

    /**
     * Puts what a reading says a line's track is, in place of whatever was kept: a reading that names
     * the rails names all of them, and adding that to an earlier window would leave a stale spike.
     */
    private void replace(long lineId, RoadNetwork fresh) {
        RoadNetwork kept = tracks.get(lineId);
        if (kept != null) {
            markSegments -= kept.segmentCount();
        }
        if (fresh.segmentCount() == 0) {
            tracks.remove(lineId);
            return;
        }
        RoadNetwork held = union(List.of(fresh));
        tracks.put(lineId, held);
        markSegments += held.segmentCount();
    }

    /**
     * Puts the newest word about one place, removing whatever was known before. A place takes the
     * lowest of its stations' ids, so a new platform can give it a new id; the old entry would stand
     * beside the new one for ever -- the same station offered twice.
     */
    private void rememberStation(MtrTransit.Station station) {
        for (MtrTransit.Station kept : sameNameAs(station)) {
            if (samePlace(kept, station)) {
                drop(kept);
            }
        }
        MtrTransit.Station renamed = stations.get(station.id());
        if (renamed != null) {
            drop(renamed);
        }
        stations.put(station.id(), station);
        if (!station.name().isBlank()) {
            stationsByName.computeIfAbsent(placeKey(station.name()), name -> new ArrayList<>())
                    .add(station);
        }
    }

    private List<MtrTransit.Station> sameNameAs(MtrTransit.Station station) {
        if (station.name().isBlank()) {
            // A station with no name is never the same place as another, because every nameless station
            // would be. Nothing to compare it with, and nothing to file it under.
            return List.of();
        }
        List<MtrTransit.Station> sameName = stationsByName.get(placeKey(station.name()));
        return sameName == null ? List.of() : List.copyOf(sameName);
    }

    private void drop(MtrTransit.Station station) {
        stations.remove(station.id());
        if (station.name().isBlank()) {
            return;
        }
        String key = placeKey(station.name());
        List<MtrTransit.Station> sameName = stationsByName.get(key);
        if (sameName != null) {
            sameName.remove(station);
            if (sameName.isEmpty()) {
                stationsByName.remove(key);
            }
        }
    }

    private static String placeKey(String name) {
        return name.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Whether two remembered stations are one place, by the same rule MtrTransit groups them: a
     * station this memory thinks is two places is one the picker will offer twice.
     */
    private static boolean samePlace(MtrTransit.Station one, MtrTransit.Station other) {
        if (one.name().isBlank() || !one.name().equalsIgnoreCase(other.name())) {
            return false;
        }
        double blocks = RoadConfig.mtrStationMergeBlocks();
        double dx = one.x() - other.x();
        double dz = one.z() - other.z();
        return dx * dx + dz * dz <= blocks * blocks;
    }

    /**
     * Adds one line's newly marked track, piece by piece; a piece already kept is not added, because
     * windows re-mark the same rail every second.
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
        // Copy on write, because what this memory hands out is read by another thread.
        // The drawing thread walks the published networks while this thread converts readings, so writing in
        // place is a data race out of a render pass. A new instance per update costs one copy of this line's
        // track, only when the reading adds something.
        RoadNetwork previous = tracks.get(lineId);
        RoadNetwork kept = previous == null ? new RoadNetwork() : previous.deepCopy();
        boolean added = previous == null;
        for (RoadSegment segment : fresh.segmentsSnapshot()) {
            if (markSegments >= MAX_MARK_SEGMENTS) {
                break;
            }
            RoadSegment remembered = sameEndsIn(kept, segment);
            if (remembered != null) {
                if (sameShape(remembered, segment)) {
                    // The same piece, read again because the player's window has slid back over it.
                    continue;
                }
                // The same two stops joined by different geometry: MTR edited the rails and this is the newest
                // word, so the old piece goes -- it would never be revisited otherwise.
                kept.removeSegment(remembered.id());
                markSegments--;
                forgetNodesOf(kept, remembered);
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
            added = true;
        }
        // Published only when it says something new: an empty first network still goes in, so a line
        // whose track has not been found yet is remembered as having none rather than as unknown.
        if (added) {
            tracks.put(lineId, kept);
        }
    }

    /**
     * The remembered piece with this one's two ends, or null when none has them.
     * Live view rather than a snapshot: a copy per piece would be the square of the line's length, and
     * nothing is added to kept while this runs. Either way round counts.
     */
    private static RoadSegment sameEndsIn(RoadNetwork kept, RoadSegment segment) {
        int last = segment.vertexCount() - 1;
        if (last < 0) {
            return null;
        }
        for (RoadSegment other : kept.segments()) {
            if (other.roadClass() != segment.roadClass()) {
                continue;
            }
            int otherLast = other.vertexCount() - 1;
            if (otherLast < 0) {
                continue;
            }
            if (sameEnd(segment.x(0), segment.z(0), other.x(0), other.z(0))
                    && sameEnd(segment.x(last), segment.z(last), other.x(otherLast),
                    other.z(otherLast))) {
                return other;
            }
            if (sameEnd(segment.x(0), segment.z(0), other.x(otherLast), other.z(otherLast))
                    && sameEnd(segment.x(last), segment.z(last), other.x(0), other.z(0))) {
                return other;
            }
        }
        return null;
    }

    /**
     * Whether two pieces are the same piece: the same endpoints joined the same way. Endpoints alone
     * let an edited line keep a stretch that now runs somewhere else.
     */
    private static boolean sameShape(RoadSegment one, RoadSegment other) {
        int last = one.vertexCount() - 1;
        if (last < 0 || one.vertexCount() != other.vertexCount()) {
            return false;
        }
        boolean forward = sameEnd(one.x(0), one.z(0), other.x(0), other.z(0));
        for (int i = 0; i <= last; i++) {
            int j = forward ? i : last - i;
            if (!sameEnd(one.x(i), one.z(i), other.x(j), other.z(j))) {
                return false;
            }
        }
        return true;
    }

    /** Drops a node the removed piece was the last user of, so a replacement leaves nothing behind. */
    private static void forgetNodesOf(RoadNetwork network, RoadSegment removed) {
        for (int nodeId : new int[]{removed.fromNode(), removed.toNode()}) {
            if (nodeId < 0) {
                continue;
            }
            boolean used = false;
            for (RoadSegment other : network.segments()) {
                if (other.fromNode() == nodeId || other.toNode() == nodeId) {
                    used = true;
                    break;
                }
            }
            if (!used) {
                network.removeNode(nodeId);
            }
        }
    }

    private static boolean sameEnd(int ax, int az, int bx, int bz) {
        double dx = ax - bx;
        double dz = az - bz;
        return dx * dx + dz * dz <= SAME_TRACK * SAME_TRACK;
    }

    /** Drops the oldest lines once there are more than MAX_LINES of them. */
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
            drop(stations.get(stations.keySet().iterator().next()));
        }
    }

    List<TransitLine> lines() {
        return List.copyOf(lines.values());
    }

    List<MtrTransit.Station> stations() {
        return List.copyOf(stations.values());
    }

    /**
     * The track of every line that wants its marks, as one network.
     * Filtered here rather than when the track was found, so a line's answer applies to track found
     * earlier: switched off keeps its track remembered and drawn, and back on brings the road back.
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
     * The track one line runs along, whether or not its marks are on -- what the map draws the line
     * along. Null when MTR never sent that line with rails: unknown, not empty.
     */
    RoadNetwork trackOf(long lineId) {
        return tracks.get(lineId);
    }

    /**
     * The same as one immutable map, for a reader on another thread.
     * Handing out the map itself would hand out something being written; the networks inside are never
     * mutated, so the copy is references only.
     */
    Map<Long, RoadNetwork> tracks() {
        return Map.copyOf(tracks);
    }

    /**
     * Several networks as one: ids are unique to a mark across the session, so a plain merge
     * suffices -- two lines over the same ground each keep their own road.
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
        stationsByName.clear();
        tracks.clear();
        markSegments = 0;
        lineCapReported = false;
        markCapReported = false;
    }

    long markSegments() {
        return markSegments;
    }
}
