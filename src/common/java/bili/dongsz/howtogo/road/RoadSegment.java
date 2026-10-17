package bili.dongsz.howtogo.road;

import java.util.Arrays;

/**
 * A polyline between two {@link RoadNode}s.
 *
 * <p>Vertices are absolute block coordinates. The polyline always starts at the position of
 * {@link #fromNode()} and ends at {@link #toNode()}, but intermediate vertices are free.
 */
public final class RoadSegment {

    public static final int NO_NODE = -1;
    public static final int NO_SEGMENT = -1;

    /**
     * How many times any segment's direction has been set, over the life of this program run.
     *
     * <p>A direction is not geometry, so {@link RoadNetwork#revision()} deliberately does not move for
     * one -- but it is not nothing either: it decides which of a segment's two ends a traveller may set
     * off from, which is exactly what a routing graph is built out of. A cache that reads a graph has to
     * be able to tell that the answer has changed, and the segment is where the change happens and the
     * only place that knows about it, so the count is kept here.
     *
     * <p>Whole-program rather than per-network, because a segment is handed out without a reference to
     * the network holding it and the editor writes through the segment. A count that occasionally moves
     * for a reason the reader did not care about costs one rebuilt graph and nothing else, which is the
     * right side to err on: a count that failed to move serves a route along a one-way street the wrong
     * way. {@code RoadRouter} is the reader that needs it.
     */
    private static int directionChanges;

    /** @see #directionChanges */
    public static int directionChanges() {
        return directionChanges;
    }

    private final int id;
    private RoadClass roadClass;
    private int fromNode = NO_NODE;
    private int toNode = NO_NODE;
    private RoadDirection direction = RoadDirection.TWO_WAY;
    private String name;

    /**
     * Which storey of the world this road is on: 0 is the surface, positive is above it and negative
     * below.
     *
     * <p>The thing a flat map cannot show and a router must know. Two roads that cross on the screen
     * are not necessarily two roads that meet -- a bridge over a road, a tunnel under one, a viaduct
     * through a station -- and the height in {@link #y()} cannot answer it, because that is whatever
     * the ground was under each click: two ends of one road across a slope differ by more than a
     * bridge differs from the road beneath it. A storey is a statement the player makes rather than a
     * measurement, so it is a field of its own.
     *
     * <p>What it decides is only what happens where there is no node. Two roads that share a node are
     * joined, whatever storey either is on: that is what building the junction means. Two roads whose
     * ends are close enough to be read as one place are one place only when they are on the same storey,
     * which is what keeps a bridge from being read as a crossroads. See
     * {@link bili.dongsz.howtogo.route.RoadRouter}.
     */
    private int layer;

    /** The lowest storey a road may be on. */
    public static final int MIN_LAYER = -32;

    /** The highest storey a road may be on. */
    public static final int MAX_LAYER = 32;

    /**
     * How many times any segment's layer has been set, over the life of this program run.
     *
     * <p>Kept for the same reason and in the same shape as {@link #directionChanges}: a storey is not
     * geometry, so {@link RoadNetwork#revision()} does not move for one, but it decides which joins
     * exist, which is exactly what a routing graph is made of. A cache that reads a graph has to be
     * able to tell that the answer has changed.
     */
    private static int layerChanges;

    /** @see #layerChanges */
    public static int layerChanges() {
        return layerChanges;
    }

    /** The storey this road is on, clamped to what a road may be. */
    public int layer() {
        return layer;
    }

    public void setLayer(int layer) {
        int clamped = Math.max(MIN_LAYER, Math.min(MAX_LAYER, layer));
        if (clamped != this.layer) {
            this.layer = clamped;
            layerChanges++;
        }
    }

    /** Interleaved vertex data: xs[i], zs[i] form the i-th vertex. */
    private int[] xs;
    private int[] zs;
    private int y;
    private int vertexCount;

    public RoadSegment(int id, RoadClass roadClass, int y, int capacity) {
        this.id = id;
        this.roadClass = roadClass;
        this.y = y;
        this.xs = new int[Math.max(2, capacity)];
        this.zs = new int[Math.max(2, capacity)];
    }

    public static RoadSegment of(int id, RoadClass roadClass, int y, int... xz) {
        if (xz.length < 4 || (xz.length & 1) != 0) {
            throw new IllegalArgumentException("need at least 2 vertices, got " + xz.length / 2);
        }
        RoadSegment seg = new RoadSegment(id, roadClass, y, xz.length / 2);
        for (int i = 0; i < xz.length; i += 2) {
            seg.addVertex(xz[i], xz[i + 1]);
        }
        return seg;
    }

    public void addVertex(int x, int z) {
        if (vertexCount == xs.length) {
            int newCap = Math.max(4, xs.length * 2);
            xs = Arrays.copyOf(xs, newCap);
            zs = Arrays.copyOf(zs, newCap);
        }
        xs[vertexCount] = x;
        zs[vertexCount] = z;
        vertexCount++;
    }

    public int id() {
        return id;
    }

    public RoadClass roadClass() {
        return roadClass;
    }

    public void setRoadClass(RoadClass roadClass) {
        this.roadClass = roadClass;
    }

    public int fromNode() {
        return fromNode;
    }

    public void setFromNode(int fromNode) {
        this.fromNode = fromNode;
    }

    public int toNode() {
        return toNode;
    }

    public void setToNode(int toNode) {
        this.toNode = toNode;
    }

    public int y() {
        return y;
    }

    public void setY(int y) {
        this.y = y;
    }

    /**
     * Which way travel is allowed along this piece of road.
     *
     * <p>Read from the segment's own two endpoints rather than from anything about how it was drawn: see
     * {@link RoadDirection}.
     */
    public RoadDirection direction() {
        return direction;
    }

    public void setDirection(RoadDirection direction) {
        RoadDirection next = direction == null ? RoadDirection.TWO_WAY : direction;
        if (next != this.direction) {
            this.direction = next;
            directionChanges++;
        }
    }

    /** Whether travel is restricted to one direction, in either sense. */
    public boolean oneWay() {
        return direction.isOneWay();
    }

    /**
     * Whether a traveller at the given node of this segment may set off along it.
     *
     * <p>The whole of the one-way rule, in one place: a router asks this once per direction it is
     * considering rather than reasoning about the two ends itself, so the graph and the map's arrows
     * cannot disagree about which way a road runs.
     *
     * @param startNodeId the node the traveller is standing at, either end of the segment
     */
    public boolean allowsTravelFrom(int startNodeId) {
        if (!direction.isOneWay()) {
            return true;
        }
        if (startNodeId == fromNode) {
            return direction == RoadDirection.FORWARD;
        }
        if (startNodeId == toNode) {
            return direction == RoadDirection.BACKWARD;
        }
        return false;
    }

    /** Player-facing road name, or null when unnamed. */
    public String name() {
        return name;
    }

    public void setName(String name) {
        this.name = name == null || name.isBlank() ? null : name.trim();
    }

    /** Midpoint of the polyline, used to anchor the name label. */
    public double[] midpoint() {
        if (vertexCount == 0) {
            return new double[]{0, 0};
        }
        if (vertexCount == 1) {
            return new double[]{xs[0], zs[0]};
        }
        double half = length() * 0.5;
        double travelled = 0.0;
        for (int i = 1; i < vertexCount; i++) {
            double dx = xs[i] - xs[i - 1];
            double dz = zs[i] - zs[i - 1];
            double seg = Math.hypot(dx, dz);
            if (travelled + seg >= half && seg > 1.0E-6) {
                double t = (half - travelled) / seg;
                return new double[]{xs[i - 1] + dx * t, zs[i - 1] + dz * t};
            }
            travelled += seg;
        }
        return new double[]{xs[vertexCount - 1], zs[vertexCount - 1]};
    }

    public int vertexCount() {
        return vertexCount;
    }

    public int x(int i) {
        return xs[i];
    }

    public int z(int i) {
        return zs[i];
    }

    /** Total polyline length in blocks. */
    public double length() {
        double total = 0.0;
        for (int i = 1; i < vertexCount; i++) {
            total += Math.hypot(xs[i] - xs[i - 1], zs[i] - zs[i - 1]);
        }
        return total;
    }

    /** Overwrites this segment's vertices with {@code count} vertices from the given arrays. */
    public void replaceVertices(int[] newXs, int[] newZs, int count) {
        if (count > xs.length) {
            xs = new int[count];
            zs = new int[count];
        }
        System.arraycopy(newXs, 0, xs, 0, count);
        System.arraycopy(newZs, 0, zs, 0, count);
        vertexCount = count;
    }

    /** Moves the vertex at {@code index} to the given horizontal position. */
    public void moveVertex(int index, int x, int z) {
        if (index < 0 || index >= vertexCount) {
            return;
        }
        xs[index] = x;
        zs[index] = z;
    }

    /** Inserts a vertex at {@code index}, shifting the rest along. */
    public void insertVertex(int index, int x, int z) {
        if (vertexCount == xs.length) {
            int newCap = Math.max(4, xs.length * 2);
            xs = Arrays.copyOf(xs, newCap);
            zs = Arrays.copyOf(zs, newCap);
        }
        System.arraycopy(xs, index, xs, index + 1, vertexCount - index);
        System.arraycopy(zs, index, zs, index + 1, vertexCount - index);
        xs[index] = x;
        zs[index] = z;
        vertexCount++;
    }

    /** Deep copy, used by the editor's snapshot-based undo. */
    public RoadSegment copy() {
        RoadSegment c = new RoadSegment(id, roadClass, y, Math.max(2, vertexCount));
        System.arraycopy(xs, 0, c.xs, 0, vertexCount);
        System.arraycopy(zs, 0, c.zs, 0, vertexCount);
        c.vertexCount = vertexCount;
        c.fromNode = fromNode;
        c.toNode = toNode;
        c.direction = direction;
        c.layer = layer;
        c.name = name;
        return c;
    }

    @Override
    public String toString() {
        return "RoadSegment#" + id + "[" + roadClass + " " + vertexCount + "v len=" + (int) length() + "]";
    }
}
