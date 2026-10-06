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
 *   <li><b>track</b> -- for a line the reading names the rails of, what it says, in place of what was
 *       kept: such a reading is a whole network and is the line, so there is nothing to add it to. For
 *       a line whose track has to be worked out, the union of every stretch of it ever found, because
 *       that is discovered a window at a time and the pieces have to be added rather than replaced: the
 *       map draws the line along the track the player found earlier, and a ride planned from far away
 *       runs along it too. A piece already kept is not added twice, and the union is capped, so a
 *       session that walks the whole network cannot grow without limit.</li>
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
     *
     * <p>Sized for a whole railway rather than for a session's window, because that is what a reading
     * can now hold: {@link MtrWholeMap} reads every line of a network the player is hosting at once, and
     * a cap meant for a hundred lines at a time would evict most of them the moment they arrived.
     */
    static final int MAX_LINES = 2048;

    /** Ceiling on remembered stations, for the same reason and at the same kind of number. */
    static final int MAX_STATIONS = 20_000;

    /**
     * Ceiling on remembered mark segments.
     *
     * <p>Every window of track the player walks past adds its own segments, and a railway is a lot of
     * track -- so this is the bound on what one session keeps, and reaching it stops the union growing
     * rather than stopping the world.
     *
     * <p>Counted in pieces of track rather than in rails, which is what makes the number generous
     * rather than tight: a line whose reading names its rails arrives as one stitched piece per
     * continuous stretch, not as one per rail, so a whole railway of a few hundred lines is a few
     * hundred pieces. What the bound is really for is the reading that does not name them -- MTR's own
     * window, where a line becomes one piece per pair of neighbouring stops that could be planned --
     * and there it is the player's own travels that decide how much accumulates.
     */
    static final int MAX_MARK_SEGMENTS = 100_000;

    /** How close two mark ends have to be to be the same piece of track, in blocks. */
    private static final double SAME_TRACK = 1.5;

    private final Map<Long, TransitLine> lines = new LinkedHashMap<>();
    private final Map<Long, MtrTransit.Station> stations = new LinkedHashMap<>();
    /**
     * The remembered stations by the name the grouping compares them under.
     *
     * <p>What makes remembering a whole railway cost a walk of the railway rather than its square: a
     * station arriving can only be the same place as one whose name it shares, so the ones it has to be
     * compared with are a handful rather than twenty thousand.
     */
    private final Map<String, List<MtrTransit.Station>> stationsByName = new HashMap<>();
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
            rememberStation(station);
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
            if (reading.stated().contains(entry.getKey())) {
                replace(entry.getKey(), entry.getValue());
            } else {
                addMarks(entry.getKey(), entry.getValue());
            }
        }
        cap();
    }

    /**
     * Puts what a reading says a line's track is, in place of whatever was kept of it.
     *
     * <h2>Why this is not the union {@link #addMarks} makes</h2>
     * Because a reading that names a line's rails names <em>all</em> of them, and one that works them
     * out does not. A window is the part of the railway near the player, so what it says about a line
     * is a piece of that line and has to be added to the pieces earlier windows brought. A whole
     * network fetched from the server is the line, so what it says is the line -- and adding that to
     * what an earlier reading said would keep both, which for a line whose reading is now one stitched
     * piece means a stale spike hanging off a live one every time the railway is edited.
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
     * Puts the newest word about one place, taking out whatever was known about it before.
     *
     * <p>What is remembered is a place rather than an MTR station -- see {@link MtrTransit} -- and a
     * place takes the lowest of its stations' ids. Building one more of a station's platforms can
     * therefore give the place an id it did not have, and the entry under the old id would stand beside
     * the new one for ever after: the same station offered twice, which is the thing the grouping exists
     * to stop, arriving by the other door. A station that has been renamed is the same problem with the
     * same answer.
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

    /** Everything remembered under a station's name, or nothing when the name is not one. */
    private List<MtrTransit.Station> sameNameAs(MtrTransit.Station station) {
        if (station.name().isBlank()) {
            // A station with no name is never the same place as another, because every nameless station
            // would be. Nothing to compare it with, and nothing to file it under.
            return List.of();
        }
        List<MtrTransit.Station> sameName = stationsByName.get(placeKey(station.name()));
        return sameName == null ? List.of() : List.copyOf(sameName);
    }

    /** Forgets a station, under its own name as well as by its id. */
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
     * Whether two remembered stations are one place.
     *
     * <p>The same rule {@link MtrTransit} groups them by, and deliberately the same: a station this
     * memory thinks is two places is a station the picker will offer twice, whether the two arrived in
     * one reading or in two.
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
        // Copy on write, because what this memory hands out is read by another thread.
        //
        // MtrTransit publishes the map this method fills -- and every network inside it -- to whoever
        // asks, and the drawing thread then walks those networks while this thread keeps converting
        // readings. Writing into a network that has already been handed out is therefore a data race
        // against an iteration: a ConcurrentModificationException out of a render pass, or a reading of
        // half-updated geometry. It was written that way, and the javadoc on tracks() claimed the
        // networks "are never mutated once built" while this method did exactly that.
        //
        // A new instance per update costs one copy of this line's own track each time a window adds to
        // it -- the line, not the railway, and only on the readings that actually add something.
        // Anything already handed out stays exactly as it was, so a reader needs no lock at all.
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
                // The same two stops joined by different geometry: MTR's rails have been edited, and
                // this reading is the newest word about where that stretch runs. The piece it replaces
                // was the same pair of ends and nothing else, so it goes. Nothing else would ever
                // revisit it -- a piece once remembered is never looked at again except to be
                // recognised -- so keeping it meant the map and the ride followed a line that is no
                // longer there until the game was restarted.
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
     *
     * <p>The kept network's own view rather than a snapshot of it: this is asked once per fresh piece,
     * and taking a copy each time is a copy of the line's whole track per piece of it -- which on a
     * line whose reading names its rails is one piece, but on a windowed reading is a line planned a
     * pair at a time, and the square of its length in copies. Nothing is added to {@code kept} while
     * this runs, so its live view is safe to walk.
     *
     * <p>Either way round: the same stretch of track planned from the other end is the same stretch of
     * track, and treating it as a new one would keep a second copy of every piece the player re-read
     * from the opposite direction.
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
     * Whether two pieces of track are the same piece: the same endpoints joined the same way.
     *
     * <p>Endpoints alone were the old test, and they are not the question. A line MTR has been edited
     * still joins the same two stops, so the old answer was "already remembered" about a stretch that
     * now runs somewhere else.
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
            drop(stations.get(stations.keySet().iterator().next()));
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
     * The same, as one immutable map, for a reader on another thread.
     *
     * <p>This memory belongs to the thread that converts a reading -- see {@code MtrTransit}, which
     * moved that work off whoever asks for it -- and the map it keeps is written to as readings arrive.
     * Handing out the map itself would hand out something being written to, so what goes out is a copy
     * made here: the networks inside it are never mutated once built, so copying the map is copying the
     * references and nothing else.
     */
    Map<Long, RoadNetwork> tracks() {
        return Map.copyOf(tracks);
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
        stationsByName.clear();
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
