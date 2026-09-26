package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.HowToGo;
import bili.dongsz.howtogo.road.RoadNetwork;
import bili.dongsz.howtogo.road.RoadStorage;
import net.minecraft.client.Minecraft;

import java.nio.file.Path;

/**
 * Client-side holder for the road network currently in play, plus its persistence.
 *
 * <p>Data lives under {@code config/howtogo/<world>/<dimension>.json}. The config directory is
 * used rather than the world save folder so the same code path works on multiplayer servers, where
 * the client has no access to the save at all.
 *
 * <p>Writes are debounced: edits mark the network dirty and a tick handler flushes it once the
 * player stops changing things, which keeps a dragging or drawing session from hitting the disk on
 * every mouse event.
 */
public final class RoadStore {

    /** How long the network must be untouched before it is written out. */
    private static final long AUTOSAVE_DELAY_MS = 3000L;

    /** Generates the P0 test pattern instead of an empty network. Debug aid. */
    private static final boolean DEMO = Boolean.getBoolean("howtogo.demo");

    private static RoadNetwork network = new RoadNetwork();
    private static Object boundLevel;
    private static Path boundPath;
    private static boolean dirty;
    private static long lastMutationMillis;

    private RoadStore() {
    }

    /** The network for the dimension currently loaded, loading it on first access. */
    public static RoadNetwork get() {
        ensureBound();
        return network;
    }

    /** Flags the network as changed so it will be written out shortly. */
    public static void markDirty() {
        dirty = true;
        lastMutationMillis = System.currentTimeMillis();
    }

    /** Called every client tick: follows level changes and runs the debounced autosave. */
    public static void tick() {
        ensureBound();
        if (dirty && System.currentTimeMillis() - lastMutationMillis >= AUTOSAVE_DELAY_MS) {
            saveNow();
        }
    }

    /** Writes the network out immediately if there are unsaved changes. */
    public static void saveNow() {
        if (!dirty || boundPath == null) {
            return;
        }
        if (RoadStorage.save(boundPath, network)) {
            dirty = false;
            HowToGo.LOGGER.info("[HowToGo] saved {} nodes / {} segments to {}",
                    network.nodeCount(), network.segmentCount(), boundPath.getFileName());
        }
    }

    /**
     * Rebinds to the current level's dimension, flushing and reloading when it changes.
     */
    private static void ensureBound() {
        Minecraft mc = Minecraft.getInstance();
        Object level = mc.level;

        if (level == null) {
            if (boundLevel != null) {
                // Left the world: persist before dropping the reference.
                saveNow();
                boundLevel = null;
                boundPath = null;
                network = new RoadNetwork();
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
        boundPath = WorldFiles.of("");
        network = RoadStorage.load(boundPath);
        dirty = false;

        if (DEMO && network.nodeCount() == 0) {
            int cx = mc.player != null ? (int) mc.player.getX() : 0;
            int cz = mc.player != null ? (int) mc.player.getZ() : 0;
            network = RoadNetwork.demo(cx, cz);
            dirty = true;
        }
    }
}
