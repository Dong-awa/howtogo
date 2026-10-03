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

    private final int id;
    private RoadClass roadClass;
    private int fromNode = NO_NODE;
    private int toNode = NO_NODE;
    private RoadDirection direction = RoadDirection.TWO_WAY;
    private String name;

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
        this.direction = direction == null ? RoadDirection.TWO_WAY : direction;
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
        c.name = name;
        return c;
    }

    @Override
    public String toString() {
        return "RoadSegment#" + id + "[" + roadClass + " " + vertexCount + "v len=" + (int) length() + "]";
    }
}
