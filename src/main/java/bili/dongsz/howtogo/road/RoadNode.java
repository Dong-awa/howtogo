package bili.dongsz.howtogo.road;

/**
 * A graph vertex: either a road junction/endpoint, or a named point of interest.
 */
public final class RoadNode {

    public enum Type {
        JUNCTION,
        ENDPOINT,
        POI
    }

    private final int id;
    private int x;
    private int y;
    private int z;
    private Type type;
    private String name;

    public RoadNode(int id, int x, int y, int z, Type type, String name) {
        this.id = id;
        this.x = x;
        this.y = y;
        this.z = z;
        this.type = type;
        this.name = name;
    }

    public int id() {
        return id;
    }

    public int x() {
        return x;
    }

    public int y() {
        return y;
    }

    public int z() {
        return z;
    }

    public Type type() {
        return type;
    }

    public void setType(Type type) {
        this.type = type;
    }

    public String name() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public void moveTo(int x, int y, int z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    /** Deep copy, used by the editor's snapshot-based undo. */
    public RoadNode copy() {
        return new RoadNode(id, x, y, z, type, name);
    }

    /** Squared horizontal distance to a point. Cheaper than {@link Math#sqrt} for snapping. */
    public double distSq(double px, double pz) {
        double dx = px - x;
        double dz = pz - z;
        return dx * dx + dz * dz;
    }

    @Override
    public String toString() {
        return "RoadNode#" + id + "(" + x + "," + y + "," + z + "," + type + ")";
    }
}
