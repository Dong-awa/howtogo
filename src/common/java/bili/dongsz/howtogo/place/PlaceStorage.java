package bili.dongsz.howtogo.place;

import bili.dongsz.howtogo.HowToGo;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
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
 * A separate file because three of the five place sources (MTR stations, waypoints, generated structures) are read from other mods and never written by this one; export packs every .json in the world directory, so details travel with the map.
 * The format is deliberately forgiving: a missing file, missing field, unknown field or id-less entry reads as less detail, never as an error. A file that does not parse is moved aside rather than deleted, so the next save cannot destroy it.
 * The version field plus the rule that unknown fields are ignored is what makes it stable; records are keyed on PlaceKey and an entry naming no dimension, source or id is dropped and counted.
 */
public final class PlaceStorage {

    /** Bumped only when a field's meaning changes, not when one is added. */
    public static final int FORMAT_VERSION = 1;

    /** Suffix of the file written before it replaces the real one; see save. */
    private static final String PARTIAL_SUFFIX = ".part";

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private PlaceStorage() {
    }

    /**
     * Reads a store, or an empty one when the file is absent or unreadable.
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
                // Written by something newer. Its fields are still the ones this build reads -- the version marks a change of meaning, not an addition -- so it is read, and the next save writes only what this build understands.
                HowToGo.LOGGER.warn("[HowToGo] {} says version {} and this build writes {}; reading "
                        + "the fields it knows", file.getFileName(), dto.version, FORMAT_VERSION);
            }
            int rejected = 0;
            int duplicates = 0;
            if (dto.details != null) {
                for (DetailsDto entry : dto.details) {
                    if (entry == null || isBlank(entry.dimension) || isBlank(entry.source)
                            || isBlank(entry.id)) {
                        // Not a place: without all three parts nothing can find this record, and keeping it would only make the unlinked count lie.
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
            // Starting empty is all that can be done with a file that does not parse, but the next save writes over it, and a file truncated by a kill still holds most of a player's notes. Moving it aside keeps the salvageable remainder.
            quarantine(file);
        }
        return details;
    }

    /**
     * Writes the store, replacing the file in one step: the bytes go to a neighbouring .part file and the target is replaced by a rename, so a cut save cannot lose a player's notes.
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
                        // An empty detail is no detail; writing it would add a row per place opened and closed without typing.
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
                    if (!value.category().isBlank()) {
                        // Absent rather than empty when there is no category, so a record written by this build and one written before the field existed are the same bytes. JsonElement rather than String because the reader tolerates a hand-edited value of any JSON type; see categoryOf.
                        out.category = new JsonPrimitive(value.category());
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

    /** Gives partial the target's name, atomically where the filesystem allows it. */
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
        return PlaceDetails.of(entry.description, tags, categoryOf(entry.category), entry.favorite)
                .withUpdatedAt(Math.max(0L, entry.updatedAt))
                .normalize();
    }

    /**
     * One record's category, read as text, or "" when the file's value is not text.
     * Read through a JsonElement because Gson would coerce a number or boolean into a fake category and a JSON object or array would fail the whole file, quarantining every note: an unusable field must read as less detail.
     */
    private static String categoryOf(JsonElement element) {
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return PlaceDetails.EMPTY_CATEGORY;
        }
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        return primitive.isString() ? primitive.getAsString() : PlaceDetails.EMPTY_CATEGORY;
    }

    private static boolean isBlank(String text) {
        return text == null || text.isBlank();
    }


    private static final class StoreDto {
        int version;
        List<DetailsDto> details;
    }

    /**
     * One record on disk. Every field is optional and read tolerantly, so a record written before a field existed just gets its default. Empty values are not written, so a detail that is only a favourite stays a few fields rather than a row of blanks.
     */
    private static final class DetailsDto {
        String dimension;
        String source;
        String id;
        String description;
        List<String> tags;
        /**
         * The place's type or resource type, absent when it has none. JsonElement so a hand-edited value of the wrong JSON type reads as no category; written as a JSON string.
         */
        JsonElement category;
        boolean favorite;
        long updatedAt;
    }
}
