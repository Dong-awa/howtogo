package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.HowToGo;
import bili.dongsz.howtogo.RoadConfig;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import bili.dongsz.howtogo.GamePaths;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Whether each line read out of MTR has its track marked as read-only rail or water roads of this mod.
 *
 * On, the stretch that line runs along becomes roads the map draws and rides plan over; off, the line contributes no roads and its stops are matched by the ordinary rule. Per line because a train's track is its own while a boat line's water usually is not.
 * The player's answer is kept by MTR line id, which is stable across readings and sessions; imported lines are rebuilt on every reading, so a field on the line would not last. Lines the player built are not stored and take the config default.
 */
public final class MtrMarks {

    /** Its own file, beside the roads and the lines rather than among them. */
    private static final String FILE_NAME = "mtr_line_marks.json";

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private static final Set<Long> ON = new LinkedHashSet<>();
    private static final Set<Long> OFF = new LinkedHashSet<>();
    private static boolean loaded;
    private static boolean dirty;

    /**
     * How many times the answers have changed since start.
     *
     * Lets caches such as MtrTransit's rebuild signature notice a switch even when the MTR reading has not moved.
     */
    private static int version;

    private MtrMarks() {
    }

    public static int version() {
        return version;
    }

    /** Whether a line brings its own track: the player's answer if given, otherwise the config default. */
    public static boolean forLine(long mtrLineId) {
        ensureLoaded();
        return decide(ON.contains(mtrLineId), OFF.contains(mtrLineId), RoadConfig.mtrAutoRouteMarks());
    }

    /**
     * The answer given the two id lists and the configured default.
     *
     * An id on the on-list is on, on the off-list is off even when the default is on, on neither takes the default; an id on both counts as on so the result does not depend on list order.
     */
    static boolean decide(boolean listedOn, boolean listedOff, boolean fallback) {
        return listedOn || (!listedOff && fallback);
    }

    /** Whether the player has answered for this line rather than taking the default. */
    public static boolean isChosen(long mtrLineId) {
        ensureLoaded();
        return ON.contains(mtrLineId) || OFF.contains(mtrLineId);
    }

    /** Flips this line's answer, recording it as the player's own either way. */
    public static void toggle(long mtrLineId, boolean currentlyOn) {
        ensureLoaded();
        ON.remove(mtrLineId);
        OFF.remove(mtrLineId);
        if (currentlyOn) {
            OFF.add(mtrLineId);
        } else {
            ON.add(mtrLineId);
        }
        dirty = true;
        version++;
        save();
    }

    /**
     * Forgets this line's answer, putting it back on the configured default. No screen calls it; it exists for tests and any future UI.
     */
    static void clear(long mtrLineId) {
        ensureLoaded();
        if (ON.remove(mtrLineId) | OFF.remove(mtrLineId)) {
            dirty = true;
            version++;
            save();
        }
    }

    private static void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        // Counted as a change: the answers just arrived from disk, so earlier readings had none of them.
        version++;
        Path file = file();
        if (file == null || !Files.isRegularFile(file)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            MarksDto dto = GSON.fromJson(reader, MarksDto.class);
            if (dto == null) {
                return;
            }
            addAll(ON, dto.on);
            addAll(OFF, dto.off);
            HowToGo.logInfo("[HowToGo] MTR line marks: {} line(s) on, {} off, as chosen",
                    ON.size(), OFF.size());
        } catch (IOException | JsonSyntaxException e) {
            HowToGo.LOGGER.error("[HowToGo] could not read {}; every line follows the config", file, e);
        }
    }

    private static void addAll(Set<Long> target, List<Long> ids) {
        if (ids == null) {
            return;
        }
        for (Long id : ids) {
            if (id != null) {
                target.add(id);
            }
        }
    }

    /**
     * Writes the answers immediately, unlike the debounced roads and lines: one small file per button press, and a crash must not lose a switch.
     */
    private static void save() {
        if (!dirty) {
            return;
        }
        Path file = file();
        if (file == null) {
            // Nowhere to write. Answers stay in memory so the switch works this session, and dirty stays set in
            // case a directory appears later.
            return;
        }
        try {
            Files.createDirectories(file.getParent());
            MarksDto dto = new MarksDto();
            dto.version = 1;
            dto.on = new ArrayList<>(ON);
            dto.off = new ArrayList<>(OFF);
            try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                GSON.toJson(dto, writer);
            }
            dirty = false;
            HowToGo.logInfo("[HowToGo] saved MTR line marks: {} on, {} off", ON.size(), OFF.size());
        } catch (IOException e) {
            HowToGo.LOGGER.error("[HowToGo] could not write {}", file, e);
        }
    }

    /**
     * The answers' file, or null when there is no config directory.
     *
     * Resolved on demand because it is read from the client tick and from planning, which can run before the game directory is known (the harness, a loading screen); with nowhere to keep them, the config default applies. Client-wide, not per world.
     */
    private static Path file() {
        Path config = GamePaths.configDir();
        return config == null ? null : config.resolve(HowToGo.MODID).resolve(FILE_NAME);
    }

    /** Field names are the on-disk contract: keep them terse and stable. */
    private static final class MarksDto {
        int version;
        List<Long> on;
        List<Long> off;
    }
}
