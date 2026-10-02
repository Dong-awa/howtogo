package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.HowToGo;
import bili.dongsz.howtogo.RoadConfig;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import net.neoforged.fml.loading.FMLPaths;

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
 * Whether each line read out of MTR brings its own track with it.
 *
 * <h2>What the switch does</h2>
 * A line read out of MTR has its stops, and MTR's rails are in the world under them. With a line's
 * marks on, that line is planned over the rails MTR reports -- so a ride follows the track that was
 * actually laid rather than a line of the player's own roads that happens to run nearby. With them
 * off, the line is planned over the player's roads alone, by the ordinary rule this mod used before it
 * knew anything about MTR, which is the right answer for a line that runs on water or on roads that
 * have already been drawn.
 *
 * <h2>Why it is per line and not one setting</h2>
 * The two kinds of line want opposite answers and the two answers coexist: a train line's track is its
 * own and is worth reading, while a boat line's water usually is not. A single switch would make the
 * player choose which of their lines to spoil.
 *
 * <h2>Why the choice is kept here rather than on the line</h2>
 * An imported line is rebuilt from MTR's reading every time the reading changes, so a field on it would
 * last until the next reading. What is kept instead is the player's answer, by MTR's own line id, which
 * is stable across readings and across sessions.
 *
 * <p>Nothing is kept for a line the player built: they are not MTR's, they take the configured default,
 * and a file full of ids of lines that no longer exist is not worth the trouble of writing. The answer
 * for a line that has never been touched is the configured default, so a player who never opens this
 * gets exactly what the config says.
 */
public final class MtrMarks {

    /** A file of its own, beside the roads and the lines rather than among them. */
    private static final String FILE_NAME = "mtr_line_marks.json";

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private static final Set<Long> ON = new LinkedHashSet<>();
    private static final Set<Long> OFF = new LinkedHashSet<>();
    private static boolean loaded;
    private static boolean dirty;

    private MtrMarks() {
    }

    /**
     * Whether a line brings its own track with it.
     *
     * <p>The player's answer if they have given one, and otherwise whether MTR's marks are wanted at
     * all -- which is the config default, so a session that never touches the switch behaves exactly as
     * the setting says.
     */
    public static boolean forLine(long mtrLineId) {
        ensureLoaded();
        if (ON.contains(mtrLineId)) {
            return true;
        }
        if (OFF.contains(mtrLineId)) {
            return false;
        }
        return RoadConfig.mtrAutoRouteMarks();
    }

    /** Whether the player has answered for this line at all, rather than taking the default. */
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
        save();
    }

    /** Forgets this line's answer, putting it back on the configured default. */
    public static void clear(long mtrLineId) {
        ensureLoaded();
        if (ON.remove(mtrLineId) | OFF.remove(mtrLineId)) {
            dirty = true;
            save();
        }
    }

    private static void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        Path file = file();
        if (!Files.isRegularFile(file)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            MarksDto dto = GSON.fromJson(reader, MarksDto.class);
            if (dto == null) {
                return;
            }
            addAll(ON, dto.on);
            addAll(OFF, dto.off);
            HowToGo.LOGGER.info("[HowToGo] MTR line marks: {} line(s) on, {} off, as chosen",
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
     * Writes the answers out.
     *
     * <p>Immediately rather than debounced, unlike the roads and the lines: this is one small file
     * written when a button is pressed, and a switch whose answer is lost by a crash is a switch the
     * player has to set twice.
     */
    private static void save() {
        if (!dirty) {
            return;
        }
        Path file = file();
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
            HowToGo.LOGGER.info("[HowToGo] saved MTR line marks: {} on, {} off", ON.size(), OFF.size());
        } catch (IOException e) {
            HowToGo.LOGGER.error("[HowToGo] could not write {}", file, e);
        }
    }

    /** Client-wide rather than per world: which of MTR's lines are worth reading is the player's taste. */
    private static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve(HowToGo.MODID).resolve(FILE_NAME);
    }

    /** Field names are the on-disk contract, so they are deliberately terse and stable. */
    private static final class MarksDto {
        int version;
        List<Long> on;
        List<Long> off;
    }
}
