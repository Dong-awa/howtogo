package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.HowToGo;
import bili.dongsz.howtogo.worlddata.WorldArchive;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Moving one world's map between machines: pack it, find packs to import, and put one back.
 * Data lives in config/howtogo, not the save folder, because a server's client cannot read the save.
 * An export writes one .zip into exports; an import reads one from imports, exports, or a drop.
 *
 * An import replaces the whole data directory: in-memory stores are flushed first and the current
 * directory is archived to backups. The archive's name does not choose the destination --
 * WorldFiles#currentWorldKey() does.
 */
public final class MapDataTransfer {

    public static final String EXPORTS = "exports";

    public static final String IMPORTS = "imports";

    public static final String BACKUPS = "backups";

    /** Which of the three folders an archive came from, so a backup can say so in the list. */
    public enum Origin {
        IMPORTS("screen.howtogo.mapdata.origin_imports"),
        EXPORTS("screen.howtogo.mapdata.origin_exports"),
        BACKUP("screen.howtogo.mapdata.origin_backup");

        final String translation;

        Origin(String translation) {
            this.translation = translation;
        }
    }

    /** One archive a player could import, as the transfer screen lists it. */
    public static final class Candidate {
        public final Path file;
        public final long bytes;
        public final long modified;
        /** What the archive says about itself; null when no manifest is understood. */
        public final WorldArchive.Manifest manifest;
        /** Where it was found; says a backup is a backup rather than a map. */
        public final Origin origin;

        private Candidate(Path file, long bytes, long modified, WorldArchive.Manifest manifest,
                          Origin origin) {
            this.file = file;
            this.bytes = bytes;
            this.modified = modified;
            this.manifest = manifest;
            this.origin = origin;
        }
    }

    /** What an import did: the files it wrote, and the archive the previous map went to. */
    public static final class Imported {
        public final WorldArchive.Contents contents;
        /** Null when the world had no map data to back up. */
        public final WorldArchive.Packed backup;

        private Imported(WorldArchive.Contents contents, WorldArchive.Packed backup) {
            this.contents = contents;
            this.backup = backup;
        }
    }

    public static final class DataStats {
        public final int files;
        public final long bytes;

        private DataStats(int files, long bytes) {
            this.files = files;
            this.bytes = bytes;
        }
    }

    private MapDataTransfer() {
    }

    public static boolean worldLoaded() {
        return Minecraft.getInstance().level != null;
    }

    public static Path dataDirectory() {
        return WorldFiles.directory();
    }

    /** How much the map holds; read once on open, since a per-frame count would walk the directory. */
    public static DataStats dataStats() {
        Path directory = dataDirectory();
        int files = 0;
        long bytes = 0L;
        if (Files.isDirectory(directory)) {
            try (Stream<Path> walk = Files.walk(directory)) {
                for (Path path : walk.toList()) {
                    String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                    if (!name.endsWith(".json") || !Files.isRegularFile(path)) {
                        continue;
                    }
                    files++;
                    try {
                        bytes += Files.size(path);
                    } catch (IOException unreadable) {
                        // Counted as a file of unknown size rather than not counted at all.
                    }
                }
            } catch (IOException unreadableDirectory) {
                // Zero is the honest answer: the directory could not be read.
            }
        }
        return new DataStats(files, bytes);
    }

    public static Component humanSize(long bytes) {
        if (bytes < 1024L * 1024L) {
            return Component.translatable("screen.howtogo.mapdata.size_kb",
                    String.format(Locale.ROOT, "%.1f", bytes / 1024.0));
        }
        return Component.translatable("screen.howtogo.mapdata.size_mb",
                String.format(Locale.ROOT, "%.1f", bytes / (1024.0 * 1024.0)));
    }

    public static Path exportsDirectory() {
        return WorldFiles.configRoot().resolve(EXPORTS);
    }

    public static Path importsDirectory() {
        return WorldFiles.configRoot().resolve(IMPORTS);
    }

    public static Path backupsDirectory() {
        return WorldFiles.configRoot().resolve(BACKUPS);
    }

    /**
     * Packs the current world's map into a new archive in the exports folder.
     * Named after the world and the moment, for the player only; in-memory stores are flushed first.
     * @throws IOException when no world is loaded or the archive could not be written
     */
    public static WorldArchive.Packed exportNow() throws IOException {
        requireWorld();
        flushStores();
        Instant now = Instant.now();
        Path directory = dataDirectory();
        Path folder = exportsDirectory();
        Files.createDirectories(folder);
        Path archive = WorldArchive.uniqueFile(folder, "howtogo-" + directory.getFileName(), now);
        WorldArchive.Packed packed = WorldArchive.export(directory, archive, WorldFiles.currentWorldKey(),
                now);
        HowToGo.logInfo("[HowToGo] exported the map of {} ({} file(s), {} bytes) to {}",
                WorldFiles.currentWorldKey(), packed.files, packed.bytes, packed.file.getFileName());
        return packed;
    }

    /**
     * Reads an archive without changing anything, for the pre-import warning.
     * @throws IOException when the file is not a readable zip or carries no map data
     */
    public static WorldArchive.Contents inspect(Path archive) throws IOException {
        return WorldArchive.inspect(archive);
    }

    /**
     * Replaces the current world's map with an archive's contents.
     * Validation happens before the backup, so an unusable file costs nothing; the current map is
     * backed up immediately before replacement. It validates again rather than trusting inspect().
     * @throws IOException when no world is loaded, the archive is unusable, or replacement failed
     */
    public static Imported importNow(Path archive) throws IOException {
        requireWorld();
        // Validated before anything is written; a non-map zip must not cost a backup or a flush.
        WorldArchive.inspect(archive);
        flushStores();
        String world = WorldFiles.currentWorldKey();
        Instant now = Instant.now();
        Path folder = backupsDirectory();
        Files.createDirectories(folder);
        WorldArchive.Packed backup = WorldArchive.backup(dataDirectory(), folder, world, now);
        WorldArchive.Contents contents = WorldArchive.importFrom(archive, dataDirectory(), world);
        reloadStores();
        HowToGo.logInfo("[HowToGo] imported {} file(s) into {} from {}; the previous map is in {}",
                contents.files(), world, archive.getFileName(),
                backup == null ? "(nothing to back up)" : backup.file.getFileName());
        if (contents.leftover != null) {
            HowToGo.LOGGER.warn("[HowToGo] could not remove the replaced map directory {}; it no "
                    + "longer claims this world", contents.leftover);
        }
        return new Imported(contents, backup);
    }

    /**
     * The archives a player could import: imports, this player's exports, and automatic backups.
     * Backups are the undo for an import; hidden in a folder, most players would never find it.
     */
    public static List<Candidate> candidates() {
        List<Candidate> all = new ArrayList<>();
        collect(importsDirectory(), Origin.IMPORTS, all);
        collect(exportsDirectory(), Origin.EXPORTS, all);
        collect(backupsDirectory(), Origin.BACKUP, all);
        all.sort(Comparator.comparingLong((Candidate candidate) -> candidate.modified).reversed());
        return all;
    }

    /** Opens a folder in the platform's file manager, creating it first. */
    public static void openFolder(Path folder) {
        try {
            Files.createDirectories(folder);
            OsShell.openFolder(folder);
        } catch (IOException | RuntimeException failed) {
            HowToGo.LOGGER.warn("[HowToGo] could not open {}: {}", folder, failed.toString());
        }
    }

    /** A failure as one line of text, whether the cause is a game exception or a plain IO error. */
    public static String describe(Throwable failed) {
        String message = failed == null ? null : failed.getMessage();
        return message == null || message.isBlank() ? String.valueOf(failed) : message;
    }

    /** Writes the stores' debounced saves out before packing or replacing. */
    private static void flushStores() {
        RoadStore.saveNow();
        TransitLineStore.saveNow();
        // Details debounce like roads; an export right after an edit would otherwise miss the note.
        PlaceDetailStore.saveNow();
    }

    /**
     * Makes the stores read the files now on disk.
     * The edit session is ended first: it holds a RoadEditor against the old network.
     */
    private static void reloadStores() {
        RoadEditSession.setActive(false);
        RoadStore.reload();
        RailNameStore.reload();
        TransitLineStore.reload();
        // Details are a file in the replaced directory and travel with the archive.
        PlaceDetailStore.reload();
    }

    private static void requireWorld() throws IOException {
        if (!worldLoaded()) {
            throw new IOException("no world is loaded; open a world before moving its map");
        }
    }

    private static void collect(Path folder, Origin origin, List<Candidate> into) {
        if (!Files.isDirectory(folder)) {
            return;
        }
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(folder)) {
            for (Path file : entries) {
                String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
                if (!name.endsWith(".zip") || !Files.isRegularFile(file)) {
                    continue;
                }
                try {
                    into.add(new Candidate(file, Files.size(file),
                            Files.getLastModifiedTime(file).toMillis(), WorldArchive.peek(file),
                            origin));
                } catch (IOException unreadable) {
                    // A file that cannot be stat'ed is not a candidate; one bad entry must not empty the list.
                }
            }
        } catch (IOException unreadableFolder) {
            // Same rule: the screen explains how to get a file there, and lists what it can.
        }
    }
}
