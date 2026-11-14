package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.HowToGo;
import bili.dongsz.howtogo.RoadConfig;
import net.minecraft.client.Minecraft;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Keeps the MTR rails this client has been told about across sessions, per world and dimension.
 * <p>
 * MTR sends a client only the railway near it and replaces its rail set with every update, so the
 * rails a player has already seen are what this memory is for: the plan made over them is what joins
 * a line across windows, and the file is what lets the next visit start with the railway that was
 * walked last time instead of an empty window. It is a cache of MTR's own readings, not saved map
 * data, so a file that cannot be read is set aside rather than costing the world.
 * <p>
 * The file sits beside the roads' one, under {@code config/howtogo/<world>/<dimension>-mtr-rails.json},
 * because a multiplayer client cannot reach the server's save directory; the world key is the server's
 * address there, so two servers never share a memory.
 * <p>
 * Writing happens on its own thread and never on the client thread: the file is the whole railway and
 * a few megabytes. Reading happens off-thread for the same reason, and the memory is only adopted when
 * it is still the dimension the player is in when the read finishes.
 */
final class MtrRailStore {

    /** Suffix the memory's file carries beside the dimension, so it cannot be mistaken for the roads'. */
    private static final String FILE_SUFFIX = "-mtr-rails";

    /** How long the memory must be unchanged before it is written out, in milliseconds. */
    private static final long SAVE_DELAY_MS = 5_000L;

    /**
     * Reads and writes, one thread. Daemon, because nothing should be kept alive by a cache and a
     * game closing must not wait for a save it asked for a moment ago.
     */
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "HowToGo MTR rails");
        thread.setDaemon(true);
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        return thread;
    });

    /** The level the binding belongs to; identity, like RoadStore, because reopening a world is a new one. */
    private static Object boundLevel;

    /** The file of the bound world and dimension, or null before a world is open. */
    private static Path boundPath;

    /**
     * Identifies the newest binding, so a read that finishes after the player has changed dimension
     * does not seed the new dimension with the old one's railway. Volatile because the reading thread
     * checks it against what the client thread last set.
     */
    private static volatile Object binding;

    /** Whether the file of the bound dimension has been read; written by the reading thread. */
    private static volatile boolean loaded;

    /** The rail stamp the file matches; anything newer is a change waiting to be written. */
    private static volatile int savedStamp = -1;

    /** The newest stamp seen, so an idle player is not written to disk on every rail MTR re-sends. */
    private static volatile int observedStamp = -1;

    private static long lastChangeMillis;

    private MtrRailStore() {
    }

    /**
     * Called once per client tick from the MTR reader: follows the loaded world and flushes the memory
     * once it has been still for a moment.
     */
    static void tick() {
        if (!RoadConfig.mtrTransit()) {
            // Switched off means forgotten; the file is left as it is, like every other saved reading
            // this mod has, so turning the switch back on brings the walk back.
            if (boundLevel != null) {
                unbind();
            }
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        Object level = minecraft.level;

        if (level == null) {
            if (boundLevel != null) {
                saveNow();
                unbind();
                MtrClientData.forgetReading();
                MtrTransit.forgetMemory();
            }
            return;
        }

        if (level == boundLevel && boundPath != null) {
            maybeSave();
            return;
        }

        // A different dimension, or a different world: write what the last one taught before the memory
        // is replaced by the new one's file, and forget the old window so it cannot be merged into the
        // new dimension's memory.
        if (boundLevel != null) {
            saveNow();
        }
        MtrClientData.forgetReading();
        bind(level);
    }

    /** Writes the memory out immediately, if there is anything to write and it has been read. */
    static void saveNow() {
        if (!loaded || boundPath == null) {
            return;
        }
        List<MtrClientData.Track> rails = MtrTransit.railSnapshot();
        if (rails.isEmpty()) {
            // Never replace a saved railway with an empty one: MTR being unreadable for a moment is
            // not the player deleting their network.
            return;
        }
        Path path = boundPath;
        savedStamp = MtrTransit.railStamp();
        flush(path, rails);
    }

    private static void bind(Object level) {
        boundLevel = level;
        boundPath = WorldFiles.of(FILE_SUFFIX);
        loaded = false;
        savedStamp = -1;
        observedStamp = -1;
        lastChangeMillis = System.currentTimeMillis();
        Object token = new Object();
        binding = token;
        Path path = boundPath;
        IO.execute(() -> {
            List<MtrClientData.Track> rails = MtrRailStorage.load(path);
            if (binding != token) {
                // The player changed world while the file was being read: this memory belongs to a
                // dimension that is no longer loaded, and seeding it would put one world's railway
                // into another.
                return;
            }
            MtrTransit.adoptRememberedRails(rails);
            loaded = true;
            savedStamp = MtrTransit.railStamp();
            observedStamp = savedStamp;
            lastChangeMillis = System.currentTimeMillis();
        });
    }

    private static void unbind() {
        boundLevel = null;
        boundPath = null;
        binding = null;
        loaded = false;
        savedStamp = -1;
        observedStamp = -1;
    }

    /** Schedules a write once the memory has stopped growing for SAVE_DELAY_MS. */
    private static void maybeSave() {
        if (!loaded) {
            return;
        }
        int stamp = MtrTransit.railStamp();
        if (stamp != observedStamp) {
            observedStamp = stamp;
            lastChangeMillis = System.currentTimeMillis();
            return;
        }
        if (stamp == savedStamp) {
            return;
        }
        if (System.currentTimeMillis() - lastChangeMillis < SAVE_DELAY_MS) {
            return;
        }
        List<MtrClientData.Track> rails = MtrTransit.railSnapshot();
        if (rails.isEmpty()) {
            savedStamp = stamp;
            return;
        }
        Path path = boundPath;
        savedStamp = stamp;
        flush(path, rails);
    }

    /** Hands one immutable snapshot to the writing thread; failure is logged there and costs a save, not the world. */
    private static void flush(Path path, List<MtrClientData.Track> rails) {
        int count = rails.size();
        IO.execute(() -> {
            if (MtrRailStorage.save(path, rails)) {
                HowToGo.LOGGER.info("[HowToGo] saved {} MTR rails to {}", count,
                        path.getFileName());
            }
        });
    }
}
