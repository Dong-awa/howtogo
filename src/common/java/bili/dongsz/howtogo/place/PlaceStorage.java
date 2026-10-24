package bili.dongsz.howtogo.place;

import bili.dongsz.howtogo.HowToGo;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads and writes the place-detail store as JSON.
 *
 * <h2>Why its own file</h2>
 * The details could have ridden in the road network's file -- a place is a node, and the node's
 * detail is a field on it -- but three of the five place sources are not in that file at all: an MTR
 * station, a waypoint and a generated structure are read out of another mod and never written by
 * this one. A separate file is the only shape that holds notes about all of them, and it is also
 * what the archive already carries: export packs every {@code .json} in the world directory, so the
 * details travel with the map without a line of code in the archive.
 *
 * <h2>Why the format is so forgiving</h2>
 * A detail is a note; it is never worth refusing a world over. A missing file, a missing field, an
 * unknown field, an entry with no id -- all of those read as "less detail" rather than as an error,
 * because the alternative is a player whose roads load and whose notes do not. A file that does not
 * parse at all is moved aside rather than deleted, for the same reason the road network's is: the
 * next save would otherwise silently destroy what is left of it.
 *
 * <h2>What makes it a stable format</h2>
 * The version field, and the rule that an unknown field is ignored rather than rejected: a newer
 * build may add a field, and an older build reading that file must still load the details it knows.
 * Everything is keyed on {@link PlaceKey}, so a record that names no dimension, source or id is not
 * a record -- it is dropped and counted, exactly as the road storage drops an entry with no id.
 */
public final class PlaceStorage {

    /** Bumped only when a field's meaning changes, not when one is added. */
    public static final int FORMAT_VERSION = 1;

    /** Suffix of the file written before it replaces the real one; see {@link #save}. */
    private static final String PARTIAL_SUFFIX = ".part";

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private PlaceStorage() {
    }

    /**
     * Reads a store, or an empty one when the file is absent or unreadable.
     *
     * @return the records found, in file order; never null
     */
    public static Map<PlaceKey, PlaceDetails> load(Path file) {
        Map<PlaceKey, PlaceDetails> details = new LinkedHashMap<>();
        if (file == null || !Files.isRegularFile(file)) {
            return details;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            StoreDto dto = GSON.fromJson(reader, StoreDto.class);
            if (dto == null) {
                return details;
            }
            if (dto.version > FORMAT_VERSION) {
                // Written by something newer. Its fields are still the ones this build reads -- the
                // version exists for a change of meaning, not for a plain addition -- so it is read
                // and the next save writes only what this build understands.
                HowToGo.LOGGER.warn("[HowToGo] {} says version {} and this build writes {}; reading "
                        + "the fields it knows", file.getFileName(), dto.version, FORMAT_VERSION);
            }
            int rejected = 0;
            int duplicates = 0;
            if (dto.details != null) {
                for (DetailsDto entry : dto.details) {
                    if (entry == null || isBlank(entry.dimension) || isBlank(entry.source)
                            || isBlank(entry.id)) {
                        // Not a place: without all three parts there is nothing this record can be
                        // found by, and keeping it would only make the "unlinked" count lie.
                        rejected++;
                        continue;
                    }
                    PlaceKey key = new PlaceKey(entry.dimension.trim(), entry.source.trim(),
                            entry.id.trim());
                    if (details.containsKey(key)) {
                        duplicates++;
                        continue;
                    }
                    details.put(key, toDetails(entry));
                }
            }
            if (rejected > 0 || duplicates > 0) {
                HowToGo.LOGGER.warn("[HowToGo] {} held {} detail record(s) with no usable key and {} "
                                + "with a repeated one; those were dropped rather than allowed to "
                                + "overwrite what they collided with",
                        file.getFileName(), rejected, duplicates);
            }
        } catch (IOException | JsonSyntaxException e) {
            HowToGo.LOGGER.error("[HowToGo] could not read {}; starting with no place details", file, e);
            // Starting empty is the only thing that can be done with a file that does not parse, but
            // it must not also be the end of that data: the next save writes over it, and a file
            // truncated by a kill still holds most of a player's notes. Moving it aside keeps the
            // salvageable remainder and costs a name in the world's folder.
            quarantine(file);
        }
        return details;
    }

    /**
     * Writes the store, replacing the file in one step.
     *
     * <p>The same reason as the road network's save: a save is a stream of writes and a stream can be
     * cut in half, and a player's notes are not worth losing to a power cut. The bytes go to a
     * neighbouring {@code .part} file and the target is replaced by a rename.
     *
     * @return whether the file was written
     */
    public static boolean save(Path file, Map<PlaceKey, PlaceDetails> details) {
        if (file == null) {
            return false;
        }
        Path partial = file.resolveSibling(file.getFileName() + PARTIAL_SUFFIX);
        try {
            Files.createDirectories(file.getParent());
            StoreDto dto = new StoreDto();
            dto.version = FORMAT_VERSION;
            dto.details = new ArrayList<>(details == null ? 0 : details.size());
            if (details != null) {
                for (Map.Entry<PlaceKey, PlaceDetails> entry : details.entrySet()) {
                    PlaceKey key = entry.getKey();
                    PlaceDetails value = entry.getValue();
                    if (key == null || value == null || value.isEmpty()) {
                        // An empty detail is the same as no detail, and writing it would make the
                        // file grow a row per place that was opened and closed without typing.
                        continue;
                    }
                    DetailsDto out = new DetailsDto();
                    out.dimension = key.dimension();
                    out.source = key.source();
                    out.id = key.id();
                    String description = value.description();
                    if (!description.isBlank()) {
                        out.description = description;
                    }
                    if (!value.tags().isEmpty()) {
                        out.tags = new ArrayList<>(value.tags());
                    }
                    if (value.favorite()) {
                        out.favorite = true;
                    }
                    if (value.updatedAt() != 0L) {
                        out.updatedAt = value.updatedAt();
                    }
                    dto.details.add(out);
                }
            }
            try (Writer writer = Files.newBufferedWriter(partial, StandardCharsets.UTF_8)) {
                GSON.toJson(dto, writer);
            }
            replace(partial, file);
            return true;
        } catch (IOException e) {
            HowToGo.LOGGER.error("[HowToGo] could not write {}", file, e);
            discard(partial);
            return false;
        }
    }

    /** Gives {@code partial} the target's name, atomically where the filesystem allows it. */
    private static void replace(Path partial, Path file) throws IOException {
        try {
            Files.move(partial, file, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException notAtomic) {
            HowToGo.LOGGER.warn("[HowToGo] this filesystem cannot replace {} in one step; falling "
                    + "back to a plain overwrite", file.getFileName());
            Files.move(partial, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Removes a partial file if it is there. Never throws: this runs while reporting a failure. */
    private static void discard(Path partial) {
        try {
            Files.deleteIfExists(partial);
        } catch (IOException ignored) {
            // Nothing useful can be done, and the error that mattered is already logged.
        }
    }

    /** Renames an unreadable store aside so that starting empty does not destroy it. */
    private static void quarantine(Path file) {
        Path salvaged = file.resolveSibling(file.getFileName() + ".corrupt");
        try {
            Files.move(file, salvaged, StandardCopyOption.REPLACE_EXISTING);
            HowToGo.LOGGER.error("[HowToGo] kept the unreadable file as {} -- part of it may still "
                    + "be recoverable by hand", salvaged);
        } catch (IOException moveFailed) {
            HowToGo.LOGGER.error("[HowToGo] could not set {} aside either; it will be overwritten",
                    file, moveFailed);
        }
    }

    private static PlaceDetails toDetails(DetailsDto entry) {
        List<String> tags = entry.tags == null ? List.of() : entry.tags;
        return PlaceDetails.of(entry.description, tags, entry.favorite)
                .withUpdatedAt(Math.max(0L, entry.updatedAt))
                .normalize();
    }

    private static boolean isBlank(String text) {
        return text == null || text.isBlank();
    }

    // ------------------------------------------------------------------- DTOs

    private static final class StoreDto {
        int version;
        List<DetailsDto> details;
    }

    /**
     * One record on disk.
     *
     * <p>Every field is optional and read tolerantly: a record written before a field existed is a
     * record with the default for it, which is exactly the reading that makes the format additive.
     * Empty values are not written at all, so a detail that is only a favourite is three fields
     * rather than six -- and a missing field and an empty one mean the same thing by construction.
     */
    private static final class DetailsDto {
        String dimension;
        String source;
        String id;
        String description;
        List<String> tags;
        boolean favorite;
        long updatedAt;
    }
}
