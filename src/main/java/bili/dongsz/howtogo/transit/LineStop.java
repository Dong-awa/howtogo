package bili.dongsz.howtogo.transit;

import java.util.ArrayList;
import java.util.List;

/**
 * One stop of a {@link TransitLine}: where it is, what it is called, and who owns it.
 *
 * <h2>Why the position is stored and the node id is not enough</h2>
 * A stop comes from one of two places, and they do not have the same kind of identity. A place the
 * player marked as a station is a node of the road network and has an id that survives a save. A
 * station Create's track graph reports is not a node at all -- it is a block in the world that the
 * track layer finds again on every scan -- so it has no id to store.
 *
 * <p>Storing the position as well as the id solves both ends of that. It is the only identity the
 * Create stations have, and for a marked place it is what keeps a line usable after the place is
 * deleted: the line still knows where it stopped, so it can be shown and edited rather than silently
 * losing a stop. The id, when there is one, is what makes the stop follow the place when it is
 * renamed.
 *
 * @param nodeId the road node this stop is a place at, or a negative value for a station the track
 *               layer reports, which the player cannot rename or retype
 * @param name   what the stop is called, taken from the place or the station when the line was built
 * @param x      block x of the stop
 * @param z      block z of the stop
 */
public record LineStop(int nodeId, String name, int x, int z) {

    /** The {@link #nodeId()} of a stop that is not a place, and so has no editable name or type. */
    public static final int NO_NODE = -1;

    public LineStop {
        if (name == null) {
            name = "";
        }
    }

    /** A stop at a place the player marked, which may be renamed and retyped. */
    public static LineStop ofPlace(int nodeId, String name, int x, int z) {
        return new LineStop(nodeId, name, x, z);
    }

    /**
     * A stop at a station Create reports.
     *
     * <p>Read-only by construction: its name and its type belong to Create, and an editor that let
     * either be changed here would leave the line describing a station that is not the one in the
     * world.
     */
    public static LineStop ofStation(String name, int x, int z) {
        return new LineStop(NO_NODE, name, x, z);
    }

    /** Whether the player may rename or retype this stop. */
    public boolean editable() {
        return nodeId != NO_NODE;
    }

    /** The name to show, falling back to the position so a stop is never nameless in a list. */
    public String label() {
        return name.isBlank() ? "(" + x + ", " + z + ")" : name;
    }

    /** Whether two stops are at the same block, which is what makes them the same stop. */
    public boolean samePlace(LineStop other) {
        return other != null && other.x == x && other.z == z;
    }

    /**
     * The given stops with duplicates removed, so one stop at one block holds one slot.
     *
     * <p>Asked of a list rather than of a line, because this is for lists assembled from both of the
     * world's sources at once -- the places the player marked and the stations Create reports -- which
     * overlap whenever a station was both marked and built. The first of a pair is kept, so a caller
     * that puts the player's own places first keeps the editable one and the read-only duplicate is
     * the one that goes.
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
