package bili.dongsz.howtogo.transit;

import bili.dongsz.howtogo.HowToGo;
import bili.dongsz.howtogo.road.ColorHex;
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
 * A file of its own because the road file is the only copy of every place, station and road a player has drawn, and a field added to it is a field a bug can lose them through; this class never opens the road file. A world saved before lines existed has no lines file and loads as no lines, so the addition is backward compatible by construction.
 * A stop is stored as a position plus, when there is one, the id of the place it stands on: the position is the only identity a station Create reports, and the id lets a renamed place stay the same stop.
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
                    // Gson hands over a null element for a null entry in the array rather than refusing the file, and dereferencing it threw a NullPointerException that the IOException catch below let through. A file that half-parses must not take the client down nor be overwritten with nothing.
                    continue;
                }
                TransitLine line = new TransitLine(l.id, l.name, parseClass(l.kind));
                // An absent or unreadable colour is a line without one of its own, which is what a file written before lines had colours means: it keeps following its kind's colour.
                line.setColor(ColorHex.parseOrNull(l.color));
                // Same rule for direction: an absent field is two-way, as a file written before lines had directions means, and an unknown value reads the same way; see LineDirection.byId.
                line.setDirection(LineDirection.byId(l.direction));
                if (l.stops != null) {
                    for (StopDto s : l.stops) {
                        if (s == null) {
                            continue;
                        }
                        // A stop already at that block is refused by the line, so a file listing one twice loads as a line that calls there once.
                        line.addStop(new LineStop(s.node == null ? LineStop.NO_NODE : s.node, s.name,
                                s.x, s.z));
                    }
                }
                lines.add(line);
            }
            HowToGo.LOGGER.info("[HowToGo] loaded {} line(s) from {}", lines.size(),
                    file.getFileName());
        } catch (IOException | RuntimeException e) {
            // RuntimeException rather than JsonSyntaxException alone: a file can be malformed in ways the parser accepts -- a null array element is one and used to throw straight out of load() past every guard. A file that cannot be read must be set aside whatever the reason.
            HowToGo.LOGGER.error("[HowToGo] could not read {}; starting with no lines", file, e);
            // Starting with no lines is all that can be done with a file that does not parse, but the store autosaves three seconds after any edit and would write the empty list over it; moving it aside keeps the salvageable remainder. Lines are the same kind of hand-entered work as the road network.
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
                // Written as the six-digit text a player typed, like every other colour this mod stores, rather than as an ARGB integer; a line without a colour writes no field at all.
                if (line.hasColor()) {
                    l.color = line.colorHex();
                }
                // Always written, even for a two-way line: the file states what the line is rather than leaving the reader to infer it, and an older build ignores the unknown field as this one ignores a later addition.
                l.direction = line.direction().id();
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
                // Atomic moves are a filesystem capability rather than a guarantee: a network share can refuse one. The plain replace is the honest fallback and no worse than before.
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
     * A line's kind, kept to the kinds a line may actually be built as. Anything else -- not a class, or a road no public transport vehicle runs on -- becomes a road line rather than being refused, so a hand edit costs a type instead of the whole line.
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


    private static final class LinesDto {
        int version;
        List<LineDto> lines;
    }

    private static final class LineDto {
        String id;
        String name;
        String kind;
        /**
         * The line's own colour, #RRGGBB, or absent. Optional like every field here: absent or unusable reads as no colour, which is the truth about lines written before colours existed.
         */
        String color;
        /**
         * Which way along its stops the line may be ridden: LineDirection's id, or absent. Optional like every field here: absent or unusable reads as two-way.
         */
        String direction;
        List<StopDto> stops;
    }

    private static final class StopDto {
        /**
         * Boxed, so a file that leaves it out reads as not a place rather than as the place with id zero -- an id a real node may well have.
         */
        Integer node;
        String name;
        int x;
        int z;
    }
}
