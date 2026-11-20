package bili.dongsz.howtogo.road;

import java.util.Arrays;
import java.util.Objects;

/**
 * A polyline between two RoadNodes. Vertices are absolute block coordinates; the polyline starts at
 * fromNode and ends at toNode, intermediate vertices are free.
 */
public final class RoadSegment {

    public static final int NO_NODE = -1;
    public static final int NO_SEGMENT = -1;

    /**
     * Count of direction sets over the program run. RoadNetwork.revision() ignores a direction change,
     * but a routing graph cache must see it; whole-program because a segment has no reference to its
     * network. Read by RoadRouter.
     */
    private static int directionChanges;

    /**
     * @see #directionChanges
     */
    public static int directionChanges() {
        return directionChanges;
    }

    /**
     * Count of class or name sets over the program run; both affect drawing but not geometry, so they
     * need a counter of their own. Same shape and reason as directionChanges.
     */
    private static int appearanceChanges;

    /**
     * @see #appearanceChanges
     */
    public static int appearanceChanges() {
        return appearanceChanges;
    }

    /**
     * Count of closure sets over the program run, same shape and reason as directionChanges: a
     * closure is not geometry either, but a routing graph that still holds a closed road would send
     * the player down a street that is shut. Read by RoadRouter.
     */
    private static int closureChanges;

    /**
     * @see #closureChanges
     */
    public static int closureChanges() {
        return closureChanges;
    }

    private final int id;
    private RoadClass roadClass;
    private int fromNode = NO_NODE;
    private int toNode = NO_NODE;
    private RoadDirection direction = RoadDirection.TWO_WAY;
    private boolean closed;
    private String name;

    /**
     * Category below the class. Never null and always a category of roadClass: setRoadClass moves it
     * along.
     */
    private RoadCategory category;

    /**
     * Road number as typed ("G318", "S12", "3"), or null/blank when there is no sign. A string because
     * real numbering is not an integer.
     */
    private String number;

    /**
     * Storey of the world: 0 surface, positive above, negative below. Y cannot answer this because it
     * is ground-dependent; a storey is the player's statement. Shared nodes join regardless; otherwise
     * only same-storey ends are joined. See RoadRouter.
     */
    private int layer;

    /**
     * Smoothed display line, or null. Presentation only: routing, length and hit tests stay on the
     * integer vertices.
     */
    private RoadCurve curve;

    public static final int MIN_LAYER = -32;

    public static final int MAX_LAYER = 32;

    /**
     * Count of layer sets; same reason and shape as directionChanges, because a storey decides which
     * joins exist.
     */
    private static int layerChanges;

    /**
     * @see #layerChanges
     */
    public static int layerChanges() {
        return layerChanges;
    }

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

    public RoadCurve curve() {
        return curve;
    }

    public void setCurve(RoadCurve curve) {
        this.curve = curve;
    }

    /** Interleaved vertex data: xs[i], zs[i] form the i-th vertex. */
    private int[] xs;
    private int[] zs;
    private int y;
    private int vertexCount;

    public RoadSegment(int id, RoadClass roadClass, int y, int capacity) {
        this.id = id;
        this.roadClass = roadClass;
        this.category = RoadCategory.none(roadClass);
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
        if (roadClass != this.roadClass) {
            this.roadClass = roadClass;
            // A category belongs to its class, so a re-class moves it to the new class's unclassified; the
            // number survives since it names the road.
            if (category == null || category.roadClass() != roadClass) {
                this.category = RoadCategory.none(roadClass);
            }
            appearanceChanges++;
        }
    }

    public RoadCategory category() {
        return category;
    }

    /**
     * Classifies this road; null or a foreign-class category falls back to this class's unclassified.
     * Whether the id exists is the store's question.
     */
    public void setCategory(RoadCategory category) {
        RoadCategory next = category == null || category.roadClass() != roadClass
                ? RoadCategory.none(roadClass)
                : category;
        if (!next.equals(this.category)) {
            this.category = next;
            appearanceChanges++;
        }
    }

    public String number() {
        return number;
    }

    public void setNumber(String number) {
        String next = number == null || number.isBlank() ? null : number.trim();
        if (!Objects.equals(next, this.number)) {
            this.number = next;
            appearanceChanges++;
        }
    }

    /**
     * Whether this road wears a sign: needs a number and a category that is not unclassified, so a
     * switch back to unclassified removes the plate while keeping the number.
     */
    public boolean hasSign() {
        return number != null && category != null && !category.isNone();
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

    /** Which way travel is allowed; read from the segment's endpoints. See RoadDirection. */
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

    public boolean oneWay() {
        return direction.isOneWay();
    }

    /**
     * Whether the segment is closed: drawn on the map as it is, but routing never travels it. A
     * closure is a rule about the world rather than a direction, so it is a flag of its own and
     * orthogonal to the one-way states -- a closed street keeps its direction for the day it reopens.
     */
    public boolean closed() {
        return closed;
    }

    public void setClosed(boolean closed) {
        if (closed != this.closed) {
            this.closed = closed;
            closureChanges++;
        }
    }

    /**
     * Whether a traveller at startNodeId may set off along this segment; the one-way rule in one place,
     * so the graph and the map's arrows agree.
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

    public String name() {
        return name;
    }

    public void setName(String name) {
        String next = name == null || name.isBlank() ? null : name.trim();
        if (!Objects.equals(next, this.name)) {
            this.name = next;
            appearanceChanges++;
        }
    }

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

    public double length() {
        double total = 0.0;
        for (int i = 1; i < vertexCount; i++) {
            total += Math.hypot(xs[i] - xs[i - 1], zs[i] - zs[i - 1]);
        }
        return total;
    }

    public void replaceVertices(int[] newXs, int[] newZs, int count) {
        if (count > xs.length) {
            xs = new int[count];
            zs = new int[count];
        }
        System.arraycopy(newXs, 0, xs, 0, count);
        System.arraycopy(newZs, 0, zs, 0, count);
        vertexCount = count;
    }

    public void moveVertex(int index, int x, int z) {
        if (index < 0 || index >= vertexCount) {
            return;
        }
        xs[index] = x;
        zs[index] = z;
    }

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
        c.closed = closed;
        c.layer = layer;
        c.name = name;
        c.category = category;
        c.number = number;
        c.curve = curve;
        return c;
    }

    /**
     * Hands the road's own state to a piece of it -- direction, closure, storey, name, category and
     * number -- without counting it as a change. A split is the same road in two pieces, and the
     * counters exist to tell a cache that the player changed something, not that a topology edit kept
     * what the road already was; counting an inheritance would make every plan that anchors on a
     * one-way or shut road look like an edit to the whole map. Geometry is the caller's; the display
     * curve belongs to the whole road and is not inherited.
     */
    void inheritState(RoadSegment source) {
        this.name = source.name;
        this.direction = source.direction;
        this.closed = source.closed;
        this.layer = source.layer;
        this.category = source.category;
        this.number = source.number;
    }

    @Override
    public String toString() {
        return "RoadSegment#" + id + "[" + roadClass + " " + vertexCount + "v len=" + (int) length() + "]";
    }
}
