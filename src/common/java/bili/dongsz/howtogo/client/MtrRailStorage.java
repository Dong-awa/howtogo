package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.HowToGo;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads and writes the MTR rail memory as JSON: one file per world and dimension.
 * <p>
 * The format is the rails MTR itself sent -- id, mode, height and the sampled x/z pairs -- and
 * nothing this mod worked out from them. That is deliberate: what a line runs along can be planned
 * again from the rails in a keystroke, while the rails are the one thing that cannot be recovered
 * once the player has walked away from the window. Keeping the source also means a change to how a
 * ride is planned reaches files written before it.
 * <p>
 * The file is a cache of what the client was told, not data the player authored: a file that will not
 * parse is renamed aside rather than allowed to crash a world, and the next visit starts from the
 * windows MTR sends.
 */
final class MtrRailStorage {

    /** Format version of the file; a newer file is read for the fields this build knows. */
    static final int FORMAT_VERSION = 1;

    /**
     * Suffix of the file a save is written to before it takes the real one's place. Kept beside the
     * target because the last step is a rename onto the target, and a rename cannot cross a filesystem.
     */
    private static final String PARTIAL_SUFFIX = ".part";

    /**
     * Decimal places a coordinate is kept at. MTR's rails are world-coordinate samples of a curve;
     * a thousandth of a block is far below what one pixel of a map can show, and the rounding is what
     * keeps a file of tens of thousands of vertices from being mostly digits.
     */
    private static final double COORDINATE_SCALE = 1000.0;

    private static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .create();

    private MtrRailStorage() {
    }

    /**
     * Reads the rails out of a file, or an empty list when it is missing or unreadable.
     * A rail that is malformed is dropped and counted rather than costing the rest of the file.
     */
    static List<MtrClientData.Track> load(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return List.of();
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            RailFileDto dto = GSON.fromJson(reader, RailFileDto.class);
            if (dto == null) {
                return List.of();
            }
            if (dto.version > FORMAT_VERSION) {
                HowToGo.LOGGER.warn("[HowToGo] {} says version {} and this build writes {}; reading "
                                + "the fields it knows", file.getFileName(), dto.version,
                        FORMAT_VERSION);
            }
            List<MtrClientData.Track> rails = new ArrayList<>();
            int rejected = 0;
            long vertices = 0;
            if (dto.rails != null) {
                for (RailDto rail : dto.rails) {
                    if (rail == null || rail.id == null || rail.id.isBlank()) {
                        rejected++;
                        continue;
                    }
                    int count = rail.xs == null || rail.zs == null ? 0 : rail.xs.length;
                    if (count < 2 || rail.zs.length != count) {
                        rejected++;
                        continue;
                    }
                    if (!finite(rail.xs) || !finite(rail.zs)) {
                        rejected++;
                        continue;
                    }
                    if (rails.size() >= MtrKnown.MAX_RAILS
                            || vertices + count > MtrKnown.MAX_RAIL_VERTICES) {
                        HowToGo.LOGGER.warn("[HowToGo] {} holds more rails than this build keeps; the "
                                        + "rest are ignored rather than loaded", file.getFileName());
                        break;
                    }
                    rails.add(new MtrClientData.Track(rail.id, rail.mode, rail.y, rail.xs, rail.zs));
                    vertices += count;
                }
            }
            if (rejected > 0) {
                HowToGo.LOGGER.warn("[HowToGo] {} held {} malformed rail(s); those were dropped "
                        + "rather than allowed to cost the rest", file.getFileName(), rejected);
            }
            if (!rails.isEmpty()) {
                HowToGo.LOGGER.info("[HowToGo] loaded {} MTR rails from {}", rails.size(),
                        file.getFileName());
            }
            return rails;
        } catch (IOException | RuntimeException e) {
            HowToGo.LOGGER.error("[HowToGo] could not read the MTR rail memory in {}; starting from "
                    + "the windows MTR sends", file, e);
            quarantine(file);
            return List.of();
        }
    }

    /**
     * Writes the rails out, replacing the file in one step.
     * The bytes go to a neighbouring .part file first, for the reason RoadStorage does the same: an
     * interrupted write must not destroy the only copy of a railway that may be the only way to see
     * parts of it again.
     * @return whether the file was written
     */
    static boolean save(Path file, List<MtrClientData.Track> rails) {
        if (file == null || rails == null || rails.isEmpty()) {
            return false;
        }
        Path partial = file.resolveSibling(file.getFileName() + PARTIAL_SUFFIX);
        try {
            Files.createDirectories(file.getParent());
            RailFileDto dto = new RailFileDto();
            dto.version = FORMAT_VERSION;
            dto.rails = new ArrayList<>(rails.size());
            for (MtrClientData.Track rail : rails) {
                if (rail.hexId() == null || rail.vertexCount() < 2) {
                    continue;
                }
                RailDto railDto = new RailDto();
                railDto.id = rail.hexId();
                railDto.mode = rail.mode();
                railDto.y = rail.y();
                railDto.xs = rounded(rail.xs());
                railDto.zs = rounded(rail.zs());
                dto.rails.add(railDto);
            }
            if (dto.rails.isEmpty()) {
                // Every rail was unusable: nothing to remember, so do not replace a saved file with an
                // empty one and do not report a save that saved nothing.
                return false;
            }
            try (Writer writer = Files.newBufferedWriter(partial, StandardCharsets.UTF_8)) {
                GSON.toJson(dto, writer);
            }
            move(partial, file);
            return true;
        } catch (IOException | RuntimeException e) {
            HowToGo.LOGGER.error("[HowToGo] could not write the MTR rail memory to {}",
                    file, e);
            return false;
        }
    }

    /** The same coordinates at the stored precision; a new array each time so the memory is untouched. */
    private static double[] rounded(double[] values) {
        double[] out = new double[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = Math.round(values[i] * COORDINATE_SCALE) / COORDINATE_SCALE;
        }
        return out;
    }

    private static boolean finite(double[] values) {
        for (double value : values) {
            if (!Double.isFinite(value)) {
                return false;
            }
        }
        return true;
    }

    /** Atomic when the filesystem has it, a plain replace when it does not. */
    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException notAtomic) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Renames an unreadable file aside so that a fresh load does not destroy what may still be hand-readable. */
    private static void quarantine(Path file) {
        Path salvaged = file.resolveSibling(file.getFileName() + ".corrupt");
        try {
            Files.move(file, salvaged, StandardCopyOption.REPLACE_EXISTING);
            HowToGo.LOGGER.error("[HowToGo] kept the unreadable MTR rail memory as {} -- part of it "
                    + "may still be recoverable by hand", salvaged);
        } catch (IOException moveFailed) {
            HowToGo.LOGGER.error("[HowToGo] could not set {} aside either; it will be replaced",
                    file, moveFailed);
        }
    }

    /** The file's shape: one version and the rails. */
    private static final class RailFileDto {

        int version;

        List<RailDto> rails;
    }

    /** One rail: MTR's id, its mode and height, and the sampled x/z pairs. */
    private static final class RailDto {

        String id;

        String mode;

        int y;

        double[] xs;

        double[] zs;
    }
}
