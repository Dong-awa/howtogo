package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.HowToGo;
import bili.dongsz.howtogo.worlddata.WorldArchive;
import net.minecraft.Util;
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
 *
 * <h2>Why the files live where they do</h2>
 * A world's data directory is under {@code config/howtogo} rather than in the save folder, because on
 * a server the client cannot read the save at all -- so the thing players have to hand each other is
 * that directory. Sending a folder, or zipping it by hand and explaining which one to unpack, is the
 * friction this removes: an export writes one {@code .zip} into {@code config/howtogo/exports}, and an
 * import reads any {@code .zip} out of {@code config/howtogo/imports}, {@code exports}, or simply one
 * dropped onto the screen. The archive is the unit players already know how to send.
 *
 * <h2>Why the archive's name does not choose the world</h2>
 * An export is named after the world it came from, and zipping a folder by hand puts that folder's
 * name inside every entry too. Both are the <em>sender's</em> world. The player importing is in a
 * world of their own, and that is where the map has to go -- so the name of the archive and the names
 * inside it are ignored, and {@link WorldArchive#importFrom} writes into the directory
 * {@link WorldFiles#currentWorldKey()} resolves. The confirmation screen says so before anything is
 * replaced; see {@link MapImportScreen}.
 *
 * <h2>What "replace" costs, and what is kept</h2>
 * An import replaces the world's whole data directory, because a map from another world is a map
 * rather than a patch. That is destructive, so two things happen first: everything in memory is
 * flushed to disk (the debounced autosaves may be holding the last few edits), and the current
 * directory is written to {@code config/howtogo/backups} as an archive of its own, which can be
 * imported back the same way. The import itself is then loaded afresh into the stores that hold the
 * current dimension.
 */
public final class MapDataTransfer {

    /** Where exports are offered back to the player; inside the transfer screen and on disk. */
    public static final String EXPORTS = "exports";

    /** Where a player drops an archive received from somebody else. */
    public static final String IMPORTS = "imports";

    /** Automatic copies of a world's data, taken immediately before an import overwrites it. */
    public static final String BACKUPS = "backups";

    /** Which of the three folders an archive came from, so a backup can say so in the list. */
    public enum Origin {
        /** The imports folder: a file this player put there. */
        IMPORTS("screen.howtogo.mapdata.origin_imports"),
        /** The exports folder: a map this player exported earlier. */
        EXPORTS("screen.howtogo.mapdata.origin_exports"),
        /** The backups folder: an automatic copy taken just before an import. */
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
        /** What the archive says about itself; null when it has no manifest this mod understands. */
        public final WorldArchive.Manifest manifest;
        /** Where it was found: the one thing that says a backup is a backup rather than a map. */
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

    /** What the current world's map directory holds, for the transfer screen's first lines. */
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

    /** Whether a world is loaded: all of this needs one, because it is the unit being moved. */
    public static boolean worldLoaded() {
        return Minecraft.getInstance().level != null;
    }

    /** The current world's map directory. */
    public static Path dataDirectory() {
        return WorldFiles.directory();
    }

    /**
     * How much the current world's map holds: the data files, not the directory's own record.
     *
     * <p>Read once when the screen opens. The alternative -- a count that is recomputed per frame --
     * would walk the directory sixty times a second to draw the same number.
     */
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
                        // Counted as a file with unknown size rather than not counted at all.
                    }
                }
            } catch (IOException unreadableDirectory) {
                // Zero is the honest answer: the directory could not be read.
            }
        }
        return new DataStats(files, bytes);
    }

    /** A byte count as a short localised string, for a screen line or a chat message. */
    public static Component humanSize(long bytes) {
        if (bytes < 1024L * 1024L) {
            return Component.translatable("screen.howtogo.mapdata.size_kb",
                    String.format(Locale.ROOT, "%.1f", bytes / 1024.0));
        }
        return Component.translatable("screen.howtogo.mapdata.size_mb",
                String.format(Locale.ROOT, "%.1f", bytes / (1024.0 * 1024.0)));
    }

    /** Where an export goes, and where the screen lists this player's own exports. */
    public static Path exportsDirectory() {
        return WorldFiles.configRoot().resolve(EXPORTS);
    }

    /** Where an archive received from another player goes. */
    public static Path importsDirectory() {
        return WorldFiles.configRoot().resolve(IMPORTS);
    }

    /** Where the automatic pre-import copies go. */
    public static Path backupsDirectory() {
        return WorldFiles.configRoot().resolve(BACKUPS);
    }

    /**
     * Packs the current world's map into a new archive in the exports folder.
     *
     * <p>The file is named after the world's directory and the moment, which is for the player's
     * benefit only -- importing ignores it. Everything in memory is written out first, so exporting
     * right after drawing a road does not omit the road waiting for its autosave delay.
     *
     * @throws IOException when no world is loaded, or the archive could not be written
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
        HowToGo.LOGGER.info("[HowToGo] exported the map of {} ({} file(s), {} bytes) to {}",
                WorldFiles.currentWorldKey(), packed.files, packed.bytes, packed.file.getFileName());
        return packed;
    }

    /**
     * Reads an archive without changing anything, for the warning shown before an import.
     *
     * @throws IOException when the file is not a readable zip, or carries no map data
     */
    public static WorldArchive.Contents inspect(Path archive) throws IOException {
        return WorldArchive.inspect(archive);
    }

    /**
     * Replaces the current world's map with an archive's contents.
     *
     * <p>Validation happens before the backup, so a file that is not a map archive is refused without
     * writing a backup nobody needs; the current map is saved to the backups folder immediately
     * before the replacement, so the one operation that cannot be undone is one the player can undo
     * by importing the backup. Pass {@link #inspect} of the same archive to show the warning first --
     * this method validates again rather than trusting a value read on another screen.
     *
     * @throws IOException when no world is loaded, the archive is unusable, or the replacement failed
     */
    public static Imported importNow(Path archive) throws IOException {
        requireWorld();
        // Validated before anything is written: a zip that is not a map must not cost the player a
        // backup, a flush or a scary confirmation.
        WorldArchive.inspect(archive);
        flushStores();
        String world = WorldFiles.currentWorldKey();
        Instant now = Instant.now();
        Path folder = backupsDirectory();
        Files.createDirectories(folder);
        WorldArchive.Packed backup = WorldArchive.backup(dataDirectory(), folder, world, now);
        WorldArchive.Contents contents = WorldArchive.importFrom(archive, dataDirectory(), world);
        reloadStores();
        HowToGo.LOGGER.info("[HowToGo] imported {} file(s) into {} from {}; the previous map is in {}",
                contents.files(), world, archive.getFileName(),
                backup == null ? "(nothing to back up)" : backup.file.getFileName());
        if (contents.leftover != null) {
            HowToGo.LOGGER.warn("[HowToGo] could not remove the replaced map directory {}; it no "
                    + "longer claims this world", contents.leftover);
        }
        return new Imported(contents, backup);
    }

    /**
     * The archives a player could import: the imports folder, this player's exports, and the
     * automatic backups.
     *
     * <p>Backups are listed on purpose. They are the undo for an import -- the archive that was
     * written over the current map a moment ago -- and an undo that can only be reached by finding a
     * folder by hand is an undo most players will not find.
     */
    public static List<Candidate> candidates() {
        List<Candidate> all = new ArrayList<>();
        collect(importsDirectory(), Origin.IMPORTS, all);
        collect(exportsDirectory(), Origin.EXPORTS, all);
        collect(backupsDirectory(), Origin.BACKUP, all);
        all.sort(Comparator.comparingLong((Candidate candidate) -> candidate.modified).reversed());
        return all;
    }

    /** Opens a folder in the platform's file manager, creating it first so the click always does something. */
    public static void openFolder(Path folder) {
        try {
            Files.createDirectories(folder);
            Util.getPlatform().openPath(folder);
        } catch (IOException | RuntimeException failed) {
            HowToGo.LOGGER.warn("[HowToGo] could not open {}: {}", folder, failed.toString());
        }
    }

    /** A failure as one line of text, whether the cause is a game exception or a plain IO error. */
    public static String describe(Throwable failed) {
        String message = failed == null ? null : failed.getMessage();
        return message == null || message.isBlank() ? String.valueOf(failed) : message;
    }

    /**
     * Writes everything the stores are holding out to disk before it is packed or replaced.
     *
     * <p>Both stores debounce their saves, so the last few edits of a session can still be in memory.
     * Rail names are written as soon as they are changed, so there is nothing to flush for them.
     */
    private static void flushStores() {
        RoadStore.saveNow();
        TransitLineStore.saveNow();
    }

    /**
     * Makes the stores read the files that are now on disk.
     *
     * <p>The edit session is ended first: it holds a {@code RoadEditor} against the old network, and
     * while the editor rebinds itself when the network object changes, ending the session is the
     * honest answer to "the map you were editing was just replaced".
     */
    private static void reloadStores() {
        RoadEditSession.setActive(false);
        RoadStore.reload();
        RailNameStore.reload();
        TransitLineStore.reload();
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
                    // A file that cannot even be stat'ed is not a candidate; the folder is the
                    // player's own, and one bad entry must not empty the list.
                }
            }
        } catch (IOException unreadableFolder) {
            // Same rule: the screen explains how to get a file there, and lists what it can.
        }
    }
}
