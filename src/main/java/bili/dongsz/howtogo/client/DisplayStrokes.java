package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.road.RoadCurve;
import bili.dongsz.howtogo.road.RoadNetwork;
import bili.dongsz.howtogo.road.RoadSegment;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Groups a network into the strokes the maps draw, one stroke per road.
 *
 * <h2>What a stroke is, and why it is not the editor's chain</h2>
 * {@code RoadChains} answers "which pieces does a click select" and stops at every junction,
 * because a junction is a real decision point. Drawing wants the other question: "which pieces
 * look like one line". A road that merely passes through a junction is still one line, and stroking
 * it as two strokes makes their ends overlap at the junction -- with a translucent stroke that
 * overlap is painted twice and reads as a lighter blob exactly where two pieces of the same road
 * meet. So a stroke walks on through a junction, taking the straightest continuation of the same
 * class and name.
 *
 * <h2>Each piece carries the way it is drawn</h2>
 * A network stores every segment in whatever direction it was built in, and a stroke can traverse
 * one either way. The old builder recorded only the order of the segment ids and left the drawing
 * pass to work out which end of each segment was nearer the last point it had written. That is
 * right for the pieces appended at the far end of the stroke and <em>wrong for the pieces prepended
 * at the near end</em>: a prepended piece has to end at the joint, and "nearest to the previous
 * point" cannot see a previous point that comes <em>after</em> it. When the prepended piece was
 * stored pointing away from the joint, the stroke was drawn joint-to-far-end and the next piece
 * then started at the joint again, so the pass painted a straight line from the far end back to the
 * joint -- a line no road follows, which a route can never use, and which a player reports as a
 * ghost line across the map.
 *
 * <p>So a stroke is a list of {@link Piece}s, each saying whether it is drawn in its stored order
 * or backwards, and the builder walks both directions with an explicit "which node am I extending
 * from" instead of re-reading the first piece's {@code fromNode} (which is only the near end when
 * that piece happens to be stored that way round). The drawing pass then has nothing to infer.
 *
 * <h2>Display only</h2>
 * Nothing here decides what connects: a piece is only ever added at a node the network says both
 * pieces touch. Nothing is written back, and routing never sees a stroke.
 */
public final class DisplayStrokes {

    /**
     * The straightest a road may turn at a junction and still be read as the same road going on.
     *
     * <p>A turn past this is not a continuation: the stroke ends there and the piece beyond is a
     * stroke of its own, because merging a sharp elbow into one stroke would be a claim about the
     * road that the data does not make.
     */
    static final double TURN_LIMIT_RADIANS = Math.toRadians(120.0);

    /** How many drawn networks keep a reading; a frame asks about the player's roads and the layers. */
    private static final int CACHE_SLOTS = 6;

    /** One segment of a stroke, and whether it is drawn backwards along its stored vertices. */
    public record Piece(int segmentId, boolean reversed) {
    }

    /** One road as the maps draw it: whole segments, in order, joined at nodes they share. */
    public record Stroke(List<Piece> pieces) {

        public Stroke {
            pieces = List.copyOf(pieces);
        }

        public int firstSegmentId() {
            return pieces.isEmpty() ? RoadSegment.NO_SEGMENT : pieces.get(0).segmentId();
        }

        public int segmentCount() {
            return pieces.size();
        }
    }

    /**
     * Where one stroke jumps between two of its own pieces.
     *
     * <p>The two points are joined by a node the stroke walked through, so the honest distance is
     * zero. Anything longer is the drawing pass painting a straight line the network never had; the
     * coordinates are what makes such a line possible to find in the world it was reported in.
     */
    public record Jump(int fromSegmentId, int toSegmentId,
                       double fromX, double fromZ, double toX, double toZ) {

        public double distance() {
            return Math.hypot(toX - fromX, toZ - fromZ);
        }
    }

    private record Cached(RoadNetwork network, int revision, int appearance, List<Stroke> strokes) {
    }

    private static final Cached[] CACHE = new Cached[CACHE_SLOTS];
    private static int nextSlot;

    private DisplayStrokes() {
    }

    /** The strokes for a network, as they stand, rebuilt only when its geometry or look changed. */
    public static List<Stroke> cached(RoadNetwork network) {
        if (network == null || network.segmentCount() == 0) {
            return List.of();
        }
        int revision = network.revision();
        int appearance = RoadSegment.appearanceChanges();
        for (Cached reading : CACHE) {
            if (reading != null && reading.network == network
                    && reading.revision == revision && reading.appearance == appearance) {
                return reading.strokes;
            }
        }
        List<Stroke> built = build(network);
        CACHE[nextSlot] = new Cached(network, revision, appearance, built);
        nextSlot = (nextSlot + 1) % CACHE_SLOTS;
        return built;
    }

    /** Groups a network into strokes; see the class comment. */
    public static List<Stroke> build(RoadNetwork network) {
        List<RoadSegment> segments = network.segmentsSnapshot();
        Map<Integer, List<Integer>> atNode = adjacency(segments);
        Set<Integer> used = new HashSet<>(segments.size() * 2);
        List<Stroke> strokes = new ArrayList<>();
        for (RoadSegment seed : segments) {
            if (!used.add(seed.id())) {
                continue;
            }
            // A segment with no node at one end cannot be walked on from that end; the stroke is
            // whatever can be reached from the end that does exist.
            List<Piece> forward = new ArrayList<>();
            forward.add(new Piece(seed.id(), false));
            int endNode = seed.toNode();
            while (endNode != RoadSegment.NO_NODE) {
                RoadSegment last = network.segment(forward.get(forward.size() - 1).segmentId());
                double[] arrival = endDirection(last, forward.get(forward.size() - 1).reversed());
                Integer nextId = continuation(network, atNode, used, last, endNode, arrival);
                if (nextId == null) {
                    break;
                }
                RoadSegment next = network.segment(nextId);
                used.add(nextId);
                boolean reversed = next.fromNode() != endNode;
                forward.add(new Piece(nextId, reversed));
                endNode = otherEnd(next, endNode);
            }
            List<Piece> before = new ArrayList<>();
            int startNode = seed.fromNode();
            while (startNode != RoadSegment.NO_NODE) {
                Piece first = before.isEmpty() ? forward.get(0) : before.get(0);
                RoadSegment piece = network.segment(first.segmentId());
                double[] departure = startDirection(piece, first.reversed());
                Integer previousId = continuation(network, atNode, used, piece, startNode, departure);
                if (previousId == null) {
                    break;
                }
                RoadSegment previous = network.segment(previousId);
                used.add(previousId);
                boolean reversed = previous.toNode() != startNode;
                before.add(0, new Piece(previousId, reversed));
                startNode = otherEnd(previous, startNode);
            }
            List<Piece> pieces = new ArrayList<>(before.size() + forward.size());
            pieces.addAll(before);
            pieces.addAll(forward);
            strokes.add(new Stroke(pieces));
        }
        return strokes;
    }

    /**
     * Every world point of a stroke in the order the maps paint them, as {@code {x, z}}.
     *
     * <p>The joint between two pieces is written once: the two pieces share the node, and painting
     * it twice would lay a translucent stroke twice on the same pixel at every junction.
     */
    public static List<double[]> points(RoadNetwork network, Stroke stroke) {
        List<double[]> points = new ArrayList<>();
        double previousX = 0;
        double previousZ = 0;
        boolean havePrevious = false;
        for (Piece piece : stroke.pieces()) {
            RoadSegment segment = network.segment(piece.segmentId());
            if (segment == null) {
                continue;
            }
            RoadCurve curve = segment.curve();
            int count = curve != null ? curve.size() : segment.vertexCount();
            if (count < 2) {
                continue;
            }
            for (int i = 0; i < count; i++) {
                int index = piece.reversed() ? count - 1 - i : i;
                double x = curve != null ? curve.x(index) : segment.x(index);
                double z = curve != null ? curve.z(index) : segment.z(index);
                if (havePrevious && Math.abs(previousX - x) < 1.0E-6
                        && Math.abs(previousZ - z) < 1.0E-6) {
                    continue;
                }
                points.add(new double[]{x, z});
                previousX = x;
                previousZ = z;
                havePrevious = true;
            }
        }
        return points;
    }

    /**
     * Every place a stroke jumps from one piece to the next without a shared point, in blocks.
     *
     * <p>Empty for a healthy network. Non-empty means the drawing pass is about to paint a straight
     * line between two places the network does not join -- the display bug this class exists to make
     * impossible, kept as a measurement so a regression is visible rather than inferred from a
     * screenshot. See {@link #points}.
     */
    public static List<Jump> jumps(RoadNetwork network, Stroke stroke) {
        List<Jump> jumps = new ArrayList<>();
        double previousX = 0;
        double previousZ = 0;
        boolean havePrevious = false;
        int previousId = RoadSegment.NO_SEGMENT;
        for (Piece piece : stroke.pieces()) {
            RoadSegment segment = network.segment(piece.segmentId());
            if (segment == null) {
                continue;
            }
            RoadCurve curve = segment.curve();
            int count = curve != null ? curve.size() : segment.vertexCount();
            if (count < 2) {
                continue;
            }
            int entry = piece.reversed() ? count - 1 : 0;
            double entryX = curve != null ? curve.x(entry) : segment.x(entry);
            double entryZ = curve != null ? curve.z(entry) : segment.z(entry);
            if (havePrevious) {
                double distance = Math.hypot(entryX - previousX, entryZ - previousZ);
                if (distance > JOIN_TOLERANCE) {
                    jumps.add(new Jump(previousId, piece.segmentId(),
                            previousX, previousZ, entryX, entryZ));
                }
            }
            for (int i = 0; i < count; i++) {
                int index = piece.reversed() ? count - 1 - i : i;
                double x = curve != null ? curve.x(index) : segment.x(index);
                double z = curve != null ? curve.z(index) : segment.z(index);
                if (havePrevious && Math.abs(previousX - x) < 1.0E-6
                        && Math.abs(previousZ - z) < 1.0E-6) {
                    continue;
                }
                previousX = x;
                previousZ = z;
                havePrevious = true;
            }
            previousId = piece.segmentId();
        }
        return jumps;
    }

    /**
     * Slack when two pieces of one stroke are compared, in blocks.
     *
     * <p>The joint is a node the stroke walked through, so the drawn ends are the same point. One
     * block of slack covers a display curve that was rounded to a slightly different coordinate
     * without hiding a stroke that genuinely jumps.
     */
    static final double JOIN_TOLERANCE = 1.5;

    /** Whether a precomputed stroke can be drawn at all: it needs one piece and one segment. */
    public static boolean isEmpty(Stroke stroke, RoadNetwork network) {
        return stroke.pieces().isEmpty() || network.segment(stroke.firstSegmentId()) == null;
    }

    // ------------------------------------------------------------------ internals

    private static Map<Integer, List<Integer>> adjacency(List<RoadSegment> segments) {
        Map<Integer, List<Integer>> atNode = new HashMap<>(segments.size() * 2);
        for (RoadSegment segment : segments) {
            if (segment.fromNode() != RoadSegment.NO_NODE) {
                atNode.computeIfAbsent(segment.fromNode(), key -> new ArrayList<>(2))
                        .add(segment.id());
            }
            if (segment.toNode() != RoadSegment.NO_NODE && segment.toNode() != segment.fromNode()) {
                atNode.computeIfAbsent(segment.toNode(), key -> new ArrayList<>(2))
                        .add(segment.id());
            }
        }
        return atNode;
    }

    /**
     * The unused segment that carries the same road on from one end of a stroke, or null.
     *
     * <p>{@code direction} points away from the node into the stroke (along the stroke for an end,
     * against it for a start), so the continuation is the candidate whose own direction away from
     * the node differs least from it. A turn past {@link #TURN_LIMIT_RADIANS} ends the stroke.
     */
    private static Integer continuation(RoadNetwork network, Map<Integer, List<Integer>> atNode,
                                        Set<Integer> used, RoadSegment piece, int nodeId,
                                        double[] direction) {
        if (nodeId == RoadSegment.NO_NODE || direction == null) {
            return null;
        }
        String name = piece.name();
        Integer best = null;
        double bestTurn = TURN_LIMIT_RADIANS;
        for (int candidateId : atNode.getOrDefault(nodeId, List.of())) {
            if (candidateId == piece.id() || used.contains(candidateId)) {
                continue;
            }
            RoadSegment candidate = network.segment(candidateId);
            if (candidate == null || candidate.vertexCount() < 2
                    || candidate.roadClass() != piece.roadClass()
                    || !Objects.equals(name, candidate.name())) {
                continue;
            }
            double[] away = awayFrom(candidate, nodeId);
            if (away == null) {
                continue;
            }
            double length = Math.hypot(away[0], away[1]);
            if (length < 1.0E-9) {
                continue;
            }
            double cosine = (direction[0] * away[0] + direction[1] * away[1])
                    / (Math.hypot(direction[0], direction[1]) * length);
            double turn = Math.acos(Math.max(-1.0, Math.min(1.0, cosine)));
            if (turn < bestTurn) {
                bestTurn = turn;
                best = candidateId;
            }
        }
        return best;
    }

    /**
     * The direction a piece runs away from one of its nodes, in the piece's own stored order.
     *
     * <p>Null when the node is not one of the piece's ends -- which is what a loop's single shared
     * node looks like from here, and a loop has no direction away from itself.
     */
    private static double[] awayFrom(RoadSegment segment, int nodeId) {
        int last = segment.vertexCount() - 1;
        if (segment.fromNode() == nodeId) {
            return new double[]{segment.x(1) - segment.x(0), segment.z(1) - segment.z(0)};
        }
        if (segment.toNode() == nodeId) {
            return new double[]{segment.x(last - 1) - segment.x(last),
                    segment.z(last - 1) - segment.z(last)};
        }
        return null;
    }

    /** The direction a drawn piece is travelling as it leaves its first drawn point. */
    private static double[] startDirection(RoadSegment segment, boolean reversed) {
        RoadCurve curve = segment.curve();
        int count = curve != null ? curve.size() : segment.vertexCount();
        if (count < 2) {
            return null;
        }
        int from = reversed ? count - 1 : 0;
        int to = reversed ? count - 2 : 1;
        double fromX = curve != null ? curve.x(from) : segment.x(from);
        double fromZ = curve != null ? curve.z(from) : segment.z(from);
        double toX = curve != null ? curve.x(to) : segment.x(to);
        double toZ = curve != null ? curve.z(to) : segment.z(to);
        return new double[]{toX - fromX, toZ - fromZ};
    }

    /** The direction a drawn piece is travelling as it reaches its last drawn point. */
    private static double[] endDirection(RoadSegment segment, boolean reversed) {
        RoadCurve curve = segment.curve();
        int count = curve != null ? curve.size() : segment.vertexCount();
        if (count < 2) {
            return null;
        }
        int to = reversed ? 0 : count - 1;
        int from = reversed ? 1 : count - 2;
        double fromX = curve != null ? curve.x(from) : segment.x(from);
        double fromZ = curve != null ? curve.z(from) : segment.z(from);
        double toX = curve != null ? curve.x(to) : segment.x(to);
        double toZ = curve != null ? curve.z(to) : segment.z(to);
        return new double[]{toX - fromX, toZ - fromZ};
    }

    /** The node at the far end of a piece entered at {@code nodeId}. */
    private static int otherEnd(RoadSegment segment, int nodeId) {
        if (segment.fromNode() == nodeId && segment.toNode() != nodeId) {
            return segment.toNode();
        }
        if (segment.toNode() == nodeId) {
            return segment.fromNode();
        }
        return RoadSegment.NO_NODE;
    }
}
