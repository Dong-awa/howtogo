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
 * Same shape as RoadStore, for the same reasons: data under config/howtogo/<world>/<dimension>-lines.json so it works on a server the client cannot read the save of, and writes are debounced so adding six stops in a row is one write.
 * A separate store rather than more state inside RoadStore because the files and failure modes are separate: nothing here can write the road file.
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
     * The lines of the dimension currently loaded, loading them on first access. The live list the editor mutates directly, like RoadStore get(); it is replaced, not emptied, on a level change, so do not hold it across one.
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
     * A stop's position is copied from the place when the line is built, but dragging a station moves its road node and nothing else, so lines would draw and plan through where the station no longer is. The one operation that moves a place calls this.
     * A drag calls this once per frame; a stop whose block did not change marks nothing, so the debounced autosave runs once, after the hand dragging lets go.
     * Negative ids (LineStop NO_NODE) are Create or MTR imports, never road nodes, so they are left alone.
     * @return whether any line changed
     */
    public static boolean updateStopsForNode(int nodeId, int x, int z) {
        if (nodeId < 0) {
            return false;
        }
        ensureBound();
        boolean changed = false;
        // The list is only read over, and a stop is replaced through its line rather than added or removed, so no line can appear or disappear under this loop.
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
     * For undo and redo, which swap the whole road network out for a snapshot: a place dragged and then taken back springs to an earlier block with no per-frame move to follow it. The stored position of the place is the truth, and a stop on it is read back from that.
     * Not treated as changes: a stop whose node no longer exists keeps its last known block -- that is what makes a line usable after its place was deleted -- and a stop with a negative id is not on the network at all.
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
