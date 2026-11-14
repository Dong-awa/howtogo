package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.HowToGo;
import bili.dongsz.howtogo.road.RoadNetwork;
import bili.dongsz.howtogo.road.RoadNode;
import bili.dongsz.howtogo.transit.LineStop;
import bili.dongsz.howtogo.transit.TransitLine;
import bili.dongsz.howtogo.transit.TransitLineStorage;
import net.minecraft.client.Minecraft;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Client-side holder for the player's public transport lines in the dimension in play.
 *
 * <p>The same shape as {@link RoadStore}, and for the same reasons: data lives under
 * {@code config/howtogo/<world>/<dimension>-lines.json} so it works on a server the client cannot
 * read the save of, and writes are debounced so that adding six stops to a line in a row is one
 * write rather than six. It is a separate store rather than more state inside {@code RoadStore}
 * because the two have separate files and separate failure modes: nothing here can write to the road
 * file, so no bug in the line editor can cost a player a place.
 */
public final class TransitLineStore {

    /** How long the lines must be untouched before they are written out. */
    private static final long AUTOSAVE_DELAY_MS = 3000L;

    /** Appended to the dimension's name to make this store's file, beside the roads' and rails'. */
    private static final String FILE_SUFFIX = "-lines";

    private static List<TransitLine> lines = new ArrayList<>();
    private static Object boundLevel;
    private static Path boundPath;
    private static boolean dirty;
    private static long lastMutationMillis;

    private TransitLineStore() {
    }

    /**
     * The lines of the dimension currently loaded, loading them on first access.
     *
     * <p>The live list, which the editor adds to and removes from directly -- the same arrangement
     * {@link RoadStore#get()} has with the network. A caller must not hold on to it across a level
     * change: this list is replaced, not emptied, when the player moves to another dimension.
     */
    public static List<TransitLine> get() {
        ensureBound();
        return lines;
    }

    /** Flags the lines as changed so they will be written out shortly. */
    public static void markDirty() {
        dirty = true;
        lastMutationMillis = System.currentTimeMillis();
    }

    /**
     * Moves every stop that stands on one road node to that node's new block.
     *
     * <h2>Why the lines have to be told, and where from</h2>
     * A stop's position is copied from the place when the line is built (see {@link LineStop}), but
     * the place keeps moving: dragging a station on the map moves its road node and nothing else. The
     * map then draws the line through the blocks the stops were at on the day of the build, and the
     * planner plans a ride that boards where the station no longer is. So the one operation that moves
     * a place calls this, and the line is re-pointed at the block its stop now stands on.
     *
     * <h2>Why only a changed stop is written, and only then</h2>
     * A drag calls this once per frame, and the store's autosave is debounced against the last
     * mutation: marking it dirty every frame the cursor moved would push the write past the end of the
     * drag forever. A frame that leaves a stop on the same block -- the common case while the cursor
     * moves less than a block between frames -- therefore changes nothing and marks nothing, and the
     * write happens once, after the hand that was dragging lets go.
     *
     * <h2>Why the negative ids are left alone</h2>
     * A stop from Create's track layer, or from an MTR route, is not a node of the road network at all
     * and carries {@link LineStop#NO_NODE} instead of an id. Those lines belong to MTR and the reading
     * of the world, not to this store, and a negative id can never name a node -- so refusing them
     * here is what keeps an imported line exactly as MTR sent it.
     *
     * @param nodeId the road node the place moved, which the stops store as their id
     * @param x      its new block x
     * @param z      its new block z
     * @return whether any line changed
     */
    public static boolean updateStopsForNode(int nodeId, int x, int z) {
        if (nodeId < 0) {
            return false;
        }
        ensureBound();
        boolean changed = false;
        // The list is only read over, and a stop is replaced through its line rather than added or
        // removed, so no line can appear or disappear under this loop.
        for (TransitLine line : lines) {
            for (int i = 0; i < line.stopCount(); i++) {
                if (line.stops().get(i).nodeId() != nodeId) {
                    continue;
                }
                changed |= line.repositionStop(i, x, z);
            }
        }
        if (changed) {
            markDirty();
        }
        return changed;
    }

    /**
     * Re-points every stop at the block its place is actually on now.
     *
     * <p>For the one path where a place moves without a drag to carry the news: undo and redo swap
     * the whole road network out for a snapshot, so a place that was dragged and then taken back
     * springs to an earlier block with no per-frame move to follow it -- and a redo springs it
     * forward again. Rather than teach the history about stops, the session asks for a reconciliation
     * after a step of it: the stored position of a place is the truth, and a stop on it is read back
     * from that.
     *
     * <p>Two things are deliberately not treated as changes. A stop whose node no longer exists keeps
     * its last known block: that is what makes a line usable after its place was deleted (see
     * {@link LineStop}), and a stop at a node the undo could not bring back is not a stop to erase. A
     * stop with a negative id is not on the network at all and is left as it is, for the reason
     * {@link #updateStopsForNode} gives.
     *
     * @return whether any line changed
     */
    public static boolean syncStationStops() {
        ensureBound();
        RoadNetwork network = RoadStore.get();
        boolean changed = false;
        for (TransitLine line : lines) {
            for (int i = 0; i < line.stopCount(); i++) {
                LineStop stop = line.stops().get(i);
                if (stop.nodeId() < 0) {
                    continue;
                }
                RoadNode node = network.node(stop.nodeId());
                if (node == null) {
                    continue;
                }
                changed |= line.repositionStop(i, node.x(), node.z());
            }
        }
        if (changed) {
            markDirty();
        }
        return changed;
    }

    /** Called every client tick: follows level changes and runs the debounced autosave. */
    public static void tick() {
        ensureBound();
        if (dirty && System.currentTimeMillis() - lastMutationMillis >= AUTOSAVE_DELAY_MS) {
            saveNow();
        }
    }

    /** Writes the lines out immediately if there are unsaved changes. */
    public static void saveNow() {
        if (!dirty || boundPath == null) {
            return;
        }
        if (TransitLineStorage.save(boundPath, lines)) {
            dirty = false;
            HowToGo.LOGGER.info("[HowToGo] saved {} line(s) to {}", lines.size(),
                    boundPath.getFileName());
        }
    }

    /** Re-reads the current dimension's lines from disk, after something replaced the file. */
    public static void reload() {
        ensureBound();
        if (boundPath == null) {
            return;
        }
        lines = TransitLineStorage.load(boundPath);
        dirty = false;
    }

    /** Rebinds to the current level's dimension, flushing and reloading when it changes. */
    private static void ensureBound() {
        Object level = Minecraft.getInstance().level;

        if (level == null) {
            if (boundLevel != null) {
                // Left the world: persist before dropping the reference.
                saveNow();
                boundLevel = null;
                boundPath = null;
                lines = new ArrayList<>();
            }
            return;
        }

        if (level == boundLevel && boundPath != null) {
            return;
        }

        if (boundLevel != null) {
            saveNow();
        }

        boundLevel = level;
        boundPath = WorldFiles.of(FILE_SUFFIX);
        lines = TransitLineStorage.load(boundPath);
        dirty = false;
    }
}
