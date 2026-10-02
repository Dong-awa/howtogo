package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.HowToGo;
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
