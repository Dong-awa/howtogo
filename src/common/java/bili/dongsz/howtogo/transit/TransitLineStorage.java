package bili.dongsz.howtogo.transit;

import bili.dongsz.howtogo.HowToGo;
import bili.dongsz.howtogo.road.RoadClass;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads and writes the player's lines as JSON.
 *
 * <h2>Why this is a file of its own</h2>
 * Lines could have been a field on the road network, and that is exactly what would have made the
 * addition dangerous: the road file is the only copy of every place, station and road a player has
 * ever drawn, and a field added to it is a field that a bug can lose them through. Keeping lines in
 * their own file means this class never opens the road file at all, so no route through it can
 * damage one.
 *
 * <p>That also makes the addition backward compatible by construction rather than by care. A world
 * saved before lines existed has no lines file; a missing file loads as no lines, which is the truth
 * about it. Nothing is migrated, and nothing has to be.
 *
 * <h2>What a stop is stored as</h2>
 * A position, always, plus the id of the place it stands on when there is one. See {@link LineStop}
 * for why both: the position is the only identity a station Create reports has, and the id is what
 * lets a marked place be renamed without the line forgetting which stop it was.
 */
public final class TransitLineStorage {

    public static final int FORMAT_VERSION = 1;

    /** The half-written file a save goes through, beside the real one. Never read. */
    private static final String PARTIAL_SUFFIX = ".part";

    /** What an unreadable file is renamed to instead of being overwritten with nothing. */
    private static final String CORRUPT_SUFFIX = ".corrupt";

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private TransitLineStorage() {
    }

    /** The lines in the file, or an empty list when there is no file or it cannot be read. */
    public static List<TransitLine> load(Path file) {
        List<TransitLine> lines = new ArrayList<>();
        if (file == null || !Files.isRegularFile(file)) {
            return lines;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            LinesDto dto = GSON.fromJson(reader, LinesDto.class);
            if (dto == null || dto.lines == null) {
                return lines;
            }
            for (LineDto l : dto.lines) {
                if (l == null) {
                    // Gson hands over a null element for a null entry in the array rather than
                    // refusing the file, and dereferencing it threw a NullPointerException that the
                    // catch below -- IOException and a syntax error, not this -- let through. See the
                    // same guard in RoadStorage: a file that half-parses must not take the client
                    // down, and must not be overwritten with nothing either.
                    continue;
                }
                TransitLine line = new TransitLine(l.id, l.name, parseClass(l.kind));
                if (l.stops != null) {
                    for (StopDto s : l.stops) {
                        if (s == null) {
                            continue;
                        }
                        // A stop already at that block is refused by the line, so a file that lists
                        // one twice -- hand-edited, or written by a version that allowed it -- loads
                        // as a line that calls there once.
                        line.addStop(new LineStop(s.node == null ? LineStop.NO_NODE : s.node, s.name,
                                s.x, s.z));
                    }
                }
                lines.add(line);
            }
            HowToGo.LOGGER.info("[HowToGo] loaded {} line(s) from {}", lines.size(),
                    file.getFileName());
        } catch (IOException | RuntimeException e) {
            // RuntimeException rather than JsonSyntaxException alone, because a file can be malformed
            // in ways the parser accepts and the reader does not -- a null element in an array is one,
            // and it threw a NullPointerException straight out of load() and past every guard. A file
            // that cannot be read is a file that must be set aside, whatever the reason turned out to
            // be.
            HowToGo.LOGGER.error("[HowToGo] could not read {}; starting with no lines", file, e);
            // Starting with no lines is the only thing to be done with a file that does not parse, but
            // it must not also be the end of that data: the store autosaves three seconds after any
            // edit, and would write the empty list over the top of a file whose lines are still in
            // there, in part. Moving it aside keeps the salvageable remainder. The road network has done
            // this from the start; lines are the same kind of hand-entered work and were not.
            quarantine(file);
        }
        return lines;
    }

    /** Renames an unreadable lines file aside so that starting empty does not destroy it. */
    private static void quarantine(Path file) {
        Path salvaged = file.resolveSibling(file.getFileName() + CORRUPT_SUFFIX);
        try {
            Files.move(file, salvaged, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            HowToGo.LOGGER.error("[HowToGo] kept the unreadable file as {} -- "
                    + "part of it may still be recoverable by hand", salvaged);
        } catch (IOException moveFailed) {
            HowToGo.LOGGER.error("[HowToGo] could not set {} aside either; it will be overwritten",
                    file, moveFailed);
        }
    }

    public static boolean save(Path file, List<TransitLine> lines) {
        if (file == null) {
            return false;
        }
        Path partial = file.resolveSibling(file.getFileName() + PARTIAL_SUFFIX);
        try {
            Files.createDirectories(file.getParent());
            LinesDto dto = new LinesDto();
            dto.version = FORMAT_VERSION;
            dto.lines = new ArrayList<>(lines.size());
            for (TransitLine line : lines) {
                LineDto l = new LineDto();
                l.id = line.id();
                l.name = line.name();
                l.kind = line.kind().name();
                l.stops = new ArrayList<>(line.stopCount());
                for (LineStop stop : line.stops()) {
                    StopDto s = new StopDto();
                    s.node = stop.nodeId();
                    s.name = stop.name();
                    s.x = stop.x();
                    s.z = stop.z();
                    l.stops.add(s);
                }
                dto.lines.add(l);
            }
            try (Writer writer = Files.newBufferedWriter(partial, StandardCharsets.UTF_8)) {
                GSON.toJson(dto, writer);
            }
            try {
                Files.move(partial, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException notAtomic) {
                // Atomic moves are a filesystem capability rather than a guarantee; a network share
                // can refuse one. The plain replace is the honest fallback on those, and is still no
                // worse than what this did before.
                Files.move(partial, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException e) {
            HowToGo.LOGGER.error("[HowToGo] could not write {}", file, e);
            try {
                Files.deleteIfExists(partial);
            } catch (IOException cleanupFailed) {
                HowToGo.LOGGER.warn("[HowToGo] left a half-written {} behind", partial);
            }
            return false;
        }
    }

    /**
     * A line's kind, kept to the kinds a line may actually be built as.
     *
     * <p>Anything else -- a name that is not a class at all, or a class that is a road but not one a
     * public transport vehicle runs on -- becomes a road line rather than being refused, so a file
     * somebody edited by hand costs them a type instead of the whole line.
     */
    private static RoadClass parseClass(String raw) {
        if (raw != null) {
            try {
                RoadClass parsed = RoadClass.valueOf(raw);
                if (TransitLine.kinds().contains(parsed)) {
                    return parsed;
                }
            } catch (IllegalArgumentException e) {
                // Falls through to the default below.
            }
        }
        return RoadClass.ROAD;
    }

    // ------------------------------------------------------------------- DTOs

    private static final class LinesDto {
        int version;
        List<LineDto> lines;
    }

    private static final class LineDto {
        String id;
        String name;
        String kind;
        List<StopDto> stops;
    }

    private static final class StopDto {
        /**
         * Boxed, so that a file that leaves it out reads as "not a place" rather than as the place
         * with id zero -- an id that a real node may well have.
         */
        Integer node;
        String name;
        int x;
        int z;
    }
}
