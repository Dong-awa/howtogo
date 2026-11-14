package bili.dongsz.howtogo.transit;

import java.util.ArrayList;
import java.util.List;

/**
 * One stop of a TransitLine: position, name and owner. The position is stored because Create
 * stations have no road-node id, and it also keeps a line usable after its marked place is deleted;
 * the node id, when present, lets the stop follow a renamed place.
 * @param nodeId road node id, or a negative value for a Create station
 * @param name stop name taken when the line was built
 * @param x block x
 * @param z block z
 */
public record LineStop(int nodeId, String name, int x, int z) {

    /** nodeId of a stop that is not a place, so has no editable name or type. */
    public static final int NO_NODE = -1;

    public LineStop {
        if (name == null) {
            name = "";
        }
    }

    public static LineStop ofPlace(int nodeId, String name, int x, int z) {
        return new LineStop(nodeId, name, x, z);
    }

    /** A stop at a Create station; read-only because its name and type belong to Create. */
    public static LineStop ofStation(String name, int x, int z) {
        return new LineStop(NO_NODE, name, x, z);
    }

    /** Whether the player may rename or retype this stop. */
    public boolean editable() {
        return nodeId != NO_NODE;
    }

    /** Name to show, falling back to the position so a stop is never nameless in a list. */
    public String label() {
        return name.isBlank() ? "(" + x + ", " + z + ")" : name;
    }

    /** Whether two stops are at the same block. */
    public boolean samePlace(LineStop other) {
        return other != null && other.x == x && other.z == z;
    }

    /**
     * The given stops with duplicates removed, keeping the first; for lists built from places and
     * Create stations together.
     */
    public static List<LineStop> distinct(List<LineStop> stops) {
        List<LineStop> result = new ArrayList<>(stops.size());
        for (LineStop stop : stops) {
            boolean seen = false;
            for (LineStop kept : result) {
                if (kept.samePlace(stop)) {
                    seen = true;
                    break;
                }
            }
            if (!seen) {
                result.add(stop);
            }
        }
        return result;
    }
}
