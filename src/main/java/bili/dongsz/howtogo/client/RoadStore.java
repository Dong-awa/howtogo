package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.HowToGo;
import bili.dongsz.howtogo.road.RoadNetwork;
import bili.dongsz.howtogo.road.RoadSegment;
import bili.dongsz.howtogo.road.RoadStorage;
import net.minecraft.client.Minecraft;

import java.nio.file.Path;

/**
 * Client-side holder for the road network in play, plus its persistence. Data lives under config/howtogo/<world>/<dimension>.json -- the config dir, not the world save, so the same path works on multiplayer servers. Writes are debounced: edits mark the network dirty and a tick handler flushes it once the player stops.
 */
public final class RoadStore {

    /** How long the network must be untouched before it is written out. */
    private static final long AUTOSAVE_DELAY_MS = 3000L;

    /** Generates the P0 test pattern instead of an empty network; debug aid. */
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

    /**
     * A cheap signature of everything about the saved network that can change a plan: its geometry
     * revision, and the one-way, closure and storey flags that revision deliberately ignores. Read
     * from the network itself rather than from the global change counters, because the machine-read
     * layers (MTR, Create, RoadWeaver, an addon's roads) run through those same setters on every
     * re-read; a consumer of this signature must not mistake such a re-read for an edit of the
     * player's own roads. The segment sum is order-independent, so a hash-map walk cannot make an
     * unchanged network look changed.
     */
    public static long routingState() {
        RoadNetwork current = get();
        long state = current.revision();
        for (RoadSegment segment : current.segments()) {
            long piece = segment.id();
            piece = piece * 31 + segment.direction().ordinal();
            piece = piece * 31 + (segment.closed() ? 1 : 0);
            piece = piece * 31 + segment.layer();
            state += piece;
        }
        return state;
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
            HowToGo.logInfo("[HowToGo] saved {} nodes / {} segments to {}",
                    network.nodeCount(), network.segmentCount(), boundPath.getFileName());
        }
    }

    /**
     * Re-reads the current dimension's network from disk after an import replaced the file. The next autosave would otherwise write the old in-memory network over it.
     */
    public static void reload() {
        ensureBound();
        if (boundPath == null) {
            return;
        }
        network = RoadStorage.load(boundPath);
        dirty = false;
    }

    /** Rebinds to the current level's dimension, flushing and reloading when it changes. */
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
                // The editing session is process-wide and outlives a world, so it must be told this world is gone; otherwise the next world opens with a handle still held.
                RoadEditSession.setActive(false);
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
        // Said once per bind so "empty map" and "map is elsewhere" can be told apart in a bug report.
        HowToGo.logInfo("[HowToGo] world data: {} | {}/{}", WorldFiles.currentWorldKey(),
                boundPath.getParent().getFileName(), boundPath.getFileName());
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
