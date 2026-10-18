package bili.dongsz.howtogo.worlddata;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * A world's map data as a zip: what an export writes, what an import reads, and nothing about the
 * game.
 *
 * <h2>Why this is not in the client package</h2>
 * Every question this class answers -- which files belong to a world, what is inside an archive, how
 * an archive is laid over a directory without losing the one it replaces -- is a question about
 * files, not about Minecraft. Keeping it here means the regression harness can exercise it on
 * temporary directories: the two things a player would actually be hurt by, an import that writes
 * the wrong files and a zip entry that escapes the world's directory, are checked without a game.
 *
 * <h2>What a world's data is</h2>
 * The data directory the rest of the mod already uses ({@code config/howtogo/<world>}) holds one
 * JSON file per dimension plus the rail-name and transit-line files, the {@code .world} record of
 * which world owns the directory, and whatever files addons were given by
 * {@code HowToGoApi.dataFile}. All of it is this world's map, so all of it travels: an addon's data
 * that stayed behind would be an addon silently starting over on the other machine.
 *
 * <h2>The archive's shape</h2>
 * A manifest ({@value #MANIFEST_NAME}) records which world this was and when; the data files sit
 * beside it at the top of the archive. Reading is deliberately more tolerant than writing:
 *
 * <ul>
 *   <li>The manifest is optional. A player who zipped the data folder by hand still has a valid
 *       archive, so importing it must work; the manifest only makes the confirmation screen able to
 *       say where an archive came from.
 *   <li>Directory levels are stripped down to the file names. Zipping a folder adds its name to
 *       every entry ({@code sp_my_world/minecraft_overworld.json}), and that name is the
 *       <em>exporter's</em> world, not the importer's -- the zip's own name and the folder inside it
 *       are both ignored on purpose. The files always land in the world the player is in, whatever
 *       the archive says it is.
 *   <li>Only JSON files are data; directory entries, macOS resource forks, {@code .world} and
 *       development leftovers are skipped rather than refused. The {@code .world} record is written
 *       anew for the world being imported into, because the record is about the directory, and the
 *       archive's record is about a directory on somebody else's machine.
 * </ul>
 *
 * <h2>Overwriting without destroying</h2>
 * An import replaces the world's data directory as a whole: a file that was there and is not in the
 * archive is gone, which is what "import this map into my world" means. The replacement itself is
 * done by building the new directory beside the old one and renaming the two, because a rename is a
 * single filesystem step: a power cut either leaves the old directory or the new one, never a
 * half-copied mixture. The caller is expected to save the old directory first -- {@link #backup}
 * does that -- and {@link #importFrom} additionally sets aside whatever it replaced so a failure to
 * clean up cannot silently leave a second data directory the mod might later mistake for this
 * world's.
 */
public final class WorldArchive {

    /** Version written into the manifest. Reading is tolerant of other values. */
    public static final int FORMAT_VERSION = 1;

    /** The manifest's entry name, at the top of an archive this mod wrote. */
    public static final String MANIFEST_NAME = "howtogo-export.json";

    /** The record of which world owns a data directory; the mod's own convention, not this class's. */
    public static final String WORLD_MARKER = ".world";

    /** How many data files one archive may carry. */
    public static final int MAX_FILES = 512;

    /** How large one data file may be, compressed or not. */
    public static final long MAX_FILE_BYTES = 32L * 1024L * 1024L;

    /** How large the whole data set may be once unpacked. */
    public static final long MAX_TOTAL_BYTES = 64L * 1024L * 1024L;

    /** The most generous manifest size worth reading; it lists the file names and little else. */
    private static final long MAX_MANIFEST_BYTES = 64L * 1024L;

    /** What is allowed in a file name this mod will write into a world's directory. */
    private static final String SAFE_FILE_NAME = "[A-Za-z0-9._-]+";

    /** Marks archives this mod wrote, so a manifest of some other kind is ignored rather than trusted. */
    private static final String KIND = "howtogo-map";

    /** Names Windows refuses whatever the extension; a data file must not become one of them. */
    private static final List<String> RESERVED_NAMES = List.of(
            "con", "prn", "aux", "nul",
            "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9",
            "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9");

    /** The stamp in a backup or export file name; there is no locale-sensitive formatting here. */
    private static final DateTimeFormatter FILE_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT);

    /** The timestamp in the manifest, with the offset so it still means something in another zone. */
    private static final DateTimeFormatter MANIFEST_STAMP = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private WorldArchive() {
    }

    // ------------------------------------------------------------------ result types

    /** What an export or a backup wrote. */
    public static final class Packed {
        public final Path file;
        public final int files;
        public final long bytes;

        private Packed(Path file, int files, long bytes) {
            this.file = file;
            this.files = files;
            this.bytes = bytes;
        }
    }

    /** The manifest of an archive this mod wrote, as far as a screen or a command wants to show it. */
    public static final class Manifest {
        public final int format;
        public final String world;
        public final String exportedAt;

        private Manifest(int format, String world, String exportedAt) {
            this.format = format;
            this.world = world;
            this.exportedAt = exportedAt;
        }
    }

    /** One data file inside an archive, and where it will go in the world's data directory. */
    public static final class Item {
        /** The plain file name this item will be written as. */
        public final String name;
        /** The entry's name inside the zip, folders and all. */
        public final String entry;
        /** The file's size, counted while it was read. */
        public final long bytes;

        private Item(String name, String entry, long bytes) {
            this.name = name;
            this.entry = entry;
            this.bytes = bytes;
        }
    }

    /** What an archive holds, read before anything is overwritten. */
    public static final class Contents {
        public final Path archive;
        /** The files that would be written, sorted by name. */
        public final List<Item> items;
        /** The manifest, or null when the archive carries none this mod understands. */
        public final Manifest manifest;
        /** How many entries were left out: junk, unsafe paths, files that were not JSON. */
        public final int ignored;
        /** The bytes of the files that would be written. */
        public final long bytes;
        /**
         * Where the directory this import replaced went, when it could not be deleted.
         *
         * <p>Only {@link #importFrom} sets this. A leftover is a directory named
         * {@code <world>.replaced-<stamp>} whose {@code .world} record was removed, so the mod will
         * not mistake it for this world's data; it is reported rather than hidden because it is the
         * player's old map still on disk.
         */
        public Path leftover;

        private Contents(Path archive, List<Item> items, Manifest manifest, int ignored, long bytes) {
            this.archive = archive;
            this.items = items;
            this.manifest = manifest;
            this.ignored = ignored;
            this.bytes = bytes;
        }

        public int files() {
            return items.size();
        }
    }

    // --------------------------------------------------------------------- export

    /**
     * Writes one world's data directory as an archive, plus the manifest that says whose it was.
     *
     * <p>Only {@code .json} files are packed, and only under names this mod could have produced:
     * everything in the directory is the player's or an addon's map data by construction, so what is
     * left out is the development leftovers ({@code .part}, {@code .corrupt}) and the {@code .world}
     * record, which describes a directory rather than a map.
     *
     * <p>The archive is written beside its target and renamed onto it, for the same reason the road
     * network is: an export interrupted halfway must not replace a good archive with a truncated one.
     *
     * @param worldDirectory the directory to pack; a missing one packs as an empty map
     * @param archive        the zip to write
     * @param world          the world key to record in the manifest, for display when importing
     * @param now            the export's time, which goes into the manifest and the file name
     */
    public static Packed export(Path worldDirectory, Path archive, String world, Instant now)
            throws IOException {
        Objects.requireNonNull(worldDirectory, "worldDirectory");
        Objects.requireNonNull(archive, "archive");
        if (world == null || world.isBlank()) {
            throw new IllegalArgumentException("a world key is needed for the manifest");
        }
        Path target = archive.toAbsolutePath();
        Path parent = target.getParent();
        if (parent == null) {
            throw new IOException("the archive has no directory to be written in: " + archive);
        }
        Files.createDirectories(parent);

        // Read first, write second: the manifest lists what was packed, so the list has to exist
        // before the manifest can be written, and a failure to read anything must not leave a
        // half-written zip behind.
        Map<String, Path> data = new TreeMap<>();
        for (Path file : packageableFiles(worldDirectory)) {
            data.put(relativeName(worldDirectory, file), file);
        }

        ManifestDto dto = new ManifestDto();
        dto.kind = KIND;
        dto.format = FORMAT_VERSION;
        dto.world = world;
        dto.exportedAt = MANIFEST_STAMP.format(now.atZone(ZoneId.systemDefault()));
        dto.files = new ArrayList<>(data.keySet());

        Path partial = target.resolveSibling(target.getFileName() + ".part");
        long written = 0L;
        try {
            try (OutputStream raw = Files.newOutputStream(partial);
                 ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(raw),
                         StandardCharsets.UTF_8)) {
                zip.putNextEntry(new ZipEntry(MANIFEST_NAME));
                zip.write(GSON.toJson(dto).getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();

                for (Map.Entry<String, Path> entry : data.entrySet()) {
                    long size = Files.size(entry.getValue());
                    if (size > MAX_FILE_BYTES) {
                        throw new IOException("the data file " + entry.getKey() + " is larger than "
                                + MAX_FILE_BYTES + " bytes and could not be imported again");
                    }
                    if (written + size > MAX_TOTAL_BYTES) {
                        throw new IOException("this map is larger than the " + MAX_TOTAL_BYTES
                                + " bytes an import accepts; it would not be usable as an archive");
                    }
                    zip.putNextEntry(new ZipEntry(entry.getKey()));
                    written += Files.copy(entry.getValue(), zip);
                    zip.closeEntry();
                }
            }
            moveReplacing(partial, target);
            return new Packed(target, data.size(), written);
        } catch (IOException | RuntimeException failed) {
            deleteQuietly(partial);
            throw failed;
        }
    }

    /**
     * Packs the current data directory aside before it is overwritten.
     *
     * <p>Named by the world and a timestamp, in a directory of their own, so it is never read as a
     * world's data and never packed into another backup. {@code null} when the world has no data
     * directory at all: importing into a world that has no map yet overwrites nothing.
     *
     * @param now the moment the backup is taken, which also names the file
     */
    public static Packed backup(Path worldDirectory, Path backupDirectory, String world, Instant now)
            throws IOException {
        if (worldDirectory == null || !Files.isDirectory(worldDirectory)) {
            return null;
        }
        Files.createDirectories(backupDirectory);
        // The name says what the file is, not only where it came from: a backup is the one archive in
        // this feature that must not be mistaken for a map to import, and the player meets it in a
        // folder as well as in the list -- where it is labelled too. See MapDataScreen.
        String base = sanitizeForFileName(worldDirectory.getFileName().toString()) + "-backup";
        Path file = uniqueFile(backupDirectory, base, now);
        return export(worldDirectory, file, world, now);
    }

    /** A file name for {@code prefix + -yyyyMMdd-HHmmss.zip} that is not taken yet. */
    public static Path uniqueFile(Path directory, String prefix, Instant now) {
        String stamp = FILE_STAMP.format(now.atZone(ZoneId.systemDefault()));
        String base = sanitizeForFileName(prefix);
        Path file = directory.resolve(base + "-" + stamp + ".zip");
        for (int attempt = 2; Files.exists(file) && attempt < 100; attempt++) {
            file = directory.resolve(base + "-" + stamp + "-" + attempt + ".zip");
        }
        return file;
    }

    // -------------------------------------------------------------------- reading

    /**
     * Reads an archive's manifest only, for a list of files a player might import.
     *
     * <p>Cheap on purpose: a screen listing ten archives must not unpack ten world maps to draw ten
     * rows. {@code null} when the file is not a readable zip or carries no manifest this mod
     * understands -- the archive may still be importable, and {@link #inspect} is what says so.
     */
    public static Manifest peek(Path archive) {
        try (ZipFile zip = open(archive)) {
            ZipEntry manifest = findManifest(zip);
            if (manifest == null) {
                return null;
            }
            ManifestDto dto = readManifest(zip, manifest);
            return dto == null ? null : new Manifest(dto.format, dto.world, dto.exportedAt);
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    /**
     * Reads an archive and works out exactly which files it would put into a world's data directory.
     *
     * <p>This is the validation an import relies on, done before anything is written: every entry
     * must name a file that can be written safely, every data file must be JSON, and the total size
     * and count are capped so a zip bomb is refused instead of ending the game. Entries that fail
     * are counted in {@link Contents#ignored}, not treated as fatal, because a real archive is
     * allowed to carry macOS metadata and the occasional development leftover. An archive with no
     * usable data file is deliberately allowed only when it carries this mod's manifest -- that is
     * what an export of a world with no map yet looks like -- while a zip with neither is fatal,
     * because importing it would silently delete the current map.
     *
     * @throws IOException when the file is not a readable zip, or holds no map data
     */
    public static Contents inspect(Path archive) throws IOException {
        Path absolute = archive.toAbsolutePath();
        if (!Files.isRegularFile(absolute)) {
            throw new IOException("no such archive: " + archive);
        }
        try (ZipFile zip = open(absolute)) {
            List<RawEntry> raw = new ArrayList<>();
            Manifest manifest = null;
            int ignored = 0;

            for (Enumeration<? extends ZipEntry> all = zip.entries(); all.hasMoreElements(); ) {
                ZipEntry entry = all.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                String entryName = entry.getName();
                if (!isTraversalFree(entryName)) {
                    ignored++;
                    continue;
                }
                String baseName = baseName(entryName);
                if (MANIFEST_NAME.equals(baseName)) {
                    ManifestDto dto = readManifest(zip, entry);
                    if (dto != null && manifest == null) {
                        manifest = new Manifest(dto.format, dto.world, dto.exportedAt);
                    }
                    continue;
                }
                if (!baseName.toLowerCase(Locale.ROOT).endsWith(".json")) {
                    ignored++;
                    continue;
                }
                if (entry.getSize() > MAX_FILE_BYTES) {
                    ignored++;
                    continue;
                }
                raw.add(new RawEntry(entryName, baseName));
            }

            String common = commonParent(raw);
            Map<String, Item> items = new LinkedHashMap<>();
            long total = 0L;
            for (RawEntry candidate : raw) {
                String stripped = strip(candidate.entry, common);
                String name = plainDataFileName(stripped);
                if (name == null || items.containsKey(name)) {
                    ignored++;
                    continue;
                }
                if (items.size() >= MAX_FILES) {
                    ignored++;
                    continue;
                }
                Item item = itemFromZip(zip, candidate.entry, name);
                if (item == null) {
                    ignored++;
                    continue;
                }
                if (total + item.bytes > MAX_TOTAL_BYTES) {
                    throw new IOException("this archive unpacks to more than " + MAX_TOTAL_BYTES
                            + " bytes, which is far larger than a map; refusing to import it");
                }
                total += item.bytes;
                items.put(name, item);
            }

            if (items.isEmpty() && manifest == null) {
                throw new IOException("this zip contains no HowToGo map data (no readable .json "
                        + "files); importing it would replace the current map with nothing");
            }
            List<Item> sorted = new ArrayList<>(items.values());
            sorted.sort(Comparator.comparing(item -> item.name));
            return new Contents(absolute, List.copyOf(sorted), manifest, ignored, total);
        }
    }

    /**
     * Reads one candidate entry, checking that it is JSON and counting its bytes.
     *
     * <p>Returns null when the entry is not readable JSON, in which case the caller counts it as
     * ignored: an archive containing one damaged file should still import the rest rather than losing
     * the whole map, and the screen says how many files were left out.
     */
    private static Item itemFromZip(ZipFile zip, String entryName, String name) throws IOException {
        ZipEntry entry = zip.getEntry(entryName);
        if (entry == null) {
            return null;
        }
        try (InputStream in = new BufferedInputStream(zip.getInputStream(entry))) {
            byte[] bytes = readAtMost(in, MAX_FILE_BYTES);
            if (bytes == null) {
                return null;
            }
            JsonElement parsed = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject() && !parsed.isJsonArray()) {
                return null;
            }
            return new Item(name, entryName, bytes.length);
        } catch (IOException | RuntimeException notJson) {
            return null;
        }
    }

    // --------------------------------------------------------------------- import

    /**
     * Replaces a world's data directory with an archive's contents, in one rename.
     *
     * <p>The old directory is not blended with the new one: a map from somewhere else is a map, not a
     * merge, and a dimension file the archive does not carry is one the imported map does not have.
     * The caller saves the old directory first ({@link #backup}); this method additionally moves it
     * aside so that it can be put back if the new directory cannot be put in place.
     *
     * <p>The {@code .world} record is written for {@code world}, never copied from the archive. That
     * record is the whole reason an import can ignore the archive's name: it tells the mod which
     * world the directory belongs to, and the answer has to be "the world doing the importing".
     *
     * @param world the key of the world being imported into, as {@code WorldFiles} writes it
     * @throws IOException when the archive is unreadable or the directory could not be replaced
     */
    public static Contents importFrom(Path archive, Path worldDirectory, String world)
            throws IOException {
        Objects.requireNonNull(worldDirectory, "worldDirectory");
        if (world == null || world.isBlank()) {
            throw new IllegalArgumentException("a world key is needed to import into a world");
        }
        Contents contents = inspect(archive);
        Path target = worldDirectory.toAbsolutePath();
        Path parent = target.getParent();
        if (parent == null) {
            throw new IOException("the data directory has no parent: " + worldDirectory);
        }
        Files.createDirectories(parent);

        Path staging = Files.createTempDirectory(parent, ".howtogo-import-");
        Path replaced = null;
        boolean installed = false;
        try {
            try (ZipFile zip = open(contents.archive)) {
                extract(zip, contents, staging);
            }
            // The directory record belongs to the world doing the importing, not to the archive.
            Files.writeString(staging.resolve(WORLD_MARKER), world, StandardCharsets.UTF_8);

            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                replaced = uniqueReplacedName(target);
                moveDirectory(target, replaced);
            }
            try {
                moveDirectory(staging, target);
                installed = true;
            } catch (IOException couldNotInstall) {
                if (replaced != null) {
                    try {
                        moveDirectory(replaced, target);
                    } catch (IOException couldNotRestore) {
                        couldNotInstall.addSuppressed(couldNotRestore);
                    }
                }
                throw couldNotInstall;
            }

            if (replaced != null) {
                // Drop the record first. If the old directory cannot be deleted, what is left behind
                // must not claim to be this world's data: WorldFiles finds a directory by its record,
                // and a second one claiming the same world is how the next session would open the
                // map that was just replaced.
                deleteQuietly(replaced.resolve(WORLD_MARKER));
                try {
                    deleteTree(replaced);
                } catch (IOException couldNotDelete) {
                    contents.leftover = replaced;
                }
            }
            return contents;
        } finally {
            if (!installed) {
                try {
                    deleteTree(staging);
                } catch (IOException ignored) {
                    // Nothing useful can be done, and the failure that mattered is already on its way out.
                }
            }
        }
    }

    /** Writes the archive's items into a staging directory, with the same caps enforced again. */
    private static void extract(ZipFile zip, Contents contents, Path staging) throws IOException {
        long total = 0L;
        for (Item item : contents.items) {
            ZipEntry entry = zip.getEntry(item.entry);
            if (entry == null || entry.isDirectory()) {
                throw new IOException("the archive changed while it was being imported: " + item.entry);
            }
            Path target = staging.resolve(item.name).normalize();
            if (!target.startsWith(staging)) {
                throw new IOException("refusing an entry that would leave the data directory: "
                        + item.entry);
            }
            try (InputStream in = new BufferedInputStream(zip.getInputStream(entry));
                 OutputStream out = Files.newOutputStream(target)) {
                long written = copyAtMost(in, out, MAX_FILE_BYTES);
                if (written < 0) {
                    throw new IOException("an entry grew beyond the size it declared: " + item.entry);
                }
                total += written;
                if (total > MAX_TOTAL_BYTES) {
                    throw new IOException("the archive unpacks to more than " + MAX_TOTAL_BYTES
                            + " bytes; refusing to import it");
                }
            }
        }
    }

    // -------------------------------------------------------------- names and paths

    /** A zip entry whose name survived the traversal check, remembered with its own base name. */
    private static final class RawEntry {
        final String entry;
        final String base;

        RawEntry(String entry, String base) {
            this.entry = entry;
            this.base = base;
        }
    }

    /**
     * The common directory prefix of the data entries that are inside a directory, or "" when there
     * is none.
     *
     * <p>Only entries with a parent take part: a file at the top of the archive is already where it
     * belongs, and mixing it into the calculation would stop a folder zipped by hand from being
     * recognised as soon as one stray JSON file sat beside that folder. The prefix is what a zipper
     * adds -- the folder's own name -- and stripping it is what makes the sender's world name
     * meaningless on this side.
     */
    private static String commonParent(List<RawEntry> entries) {
        List<String> common = null;
        for (RawEntry entry : entries) {
            String[] parts = entry.entry.split("/");
            if (parts.length < 2) {
                continue;
            }
            List<String> parent = new ArrayList<>();
            for (int i = 0; i + 1 < parts.length; i++) {
                parent.add(parts[i]);
            }
            if (common == null) {
                common = parent;
                continue;
            }
            int shared = 0;
            while (shared < common.size() && shared < parent.size()
                    && common.get(shared).equals(parent.get(shared))) {
                shared++;
            }
            common = common.subList(0, shared);
        }
        return common == null ? "" : String.join("/", common);
    }

    /** The entry name with a common directory prefix removed, or unchanged when it does not start with it. */
    private static String strip(String entry, String prefix) {
        if (prefix.isEmpty()) {
            return entry;
        }
        return entry.startsWith(prefix + "/") ? entry.substring(prefix.length() + 1) : entry;
    }

    /**
     * The plain file name an archive entry may be written as, or null when it may not.
     *
     * <p>Everything the player would see was already stripped by {@link #strip}; what is left has to
     * be a single component, plain ASCII, and a name this mod itself could have written. A name with
     * a separator here is a second directory level the common-prefix rule did not account for, and
     * refusing it is what keeps a hostile or merely unusual archive to the one directory an import
     * is allowed to touch.
     */
    private static String plainDataFileName(String stripped) {
        if (stripped == null || stripped.isEmpty() || stripped.length() > 160) {
            return null;
        }
        if (stripped.indexOf('/') >= 0 || stripped.indexOf('\\') >= 0) {
            return null;
        }
        if (!stripped.matches(SAFE_FILE_NAME)) {
            return null;
        }
        String lower = stripped.toLowerCase(Locale.ROOT);
        if (lower.startsWith(".") || !lower.endsWith(".json")) {
            return null;
        }
        if (MANIFEST_NAME.equals(stripped) || isReservedName(lower)) {
            return null;
        }
        return stripped;
    }

    /** Whether a name a data file would be written as is one Windows refuses outright. */
    private static boolean isReservedName(String lower) {
        int dot = lower.indexOf('.');
        String stem = dot < 0 ? lower : lower.substring(0, dot);
        return RESERVED_NAMES.contains(stem);
    }

    /**
     * Whether a zip entry name is safe to strip and read.
     *
     * <p>Checked on the entry as written: no absolute path, no drive letter, no segment that walks back
     * up. This class never resolves an archive entry against anything but its own staging directory,
     * and this check is why no entry can even offer it a path that would try.
     */
    private static boolean isTraversalFree(String entry) {
        if (entry == null || entry.isEmpty() || entry.length() > 512) {
            return false;
        }
        if (entry.startsWith("/") || entry.startsWith("\\") || entry.indexOf('\\') >= 0
                || entry.indexOf(':') >= 0) {
            return false;
        }
        for (String part : entry.split("/")) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) {
                return false;
            }
        }
        return true;
    }

    /** The last component of an entry name. */
    private static String baseName(String entry) {
        int slash = entry.lastIndexOf('/');
        return slash < 0 ? entry : entry.substring(slash + 1);
    }

    /**
     * Every file under a directory that belongs in an archive, in a stable order.
     *
     * <p>JSON only, and only under names this mod would write. That is the same rule the reader
     * applies on the way back in, so a file this mod exports is a file a later version can import.
     */
    private static List<Path> packageableFiles(Path directory) throws IOException {
        if (directory == null || !Files.isDirectory(directory)) {
            return List.of();
        }
        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(directory)) {
            for (Path path : walk.sorted().toList()) {
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                String relative = relativeName(directory, path);
                if (packageableName(relative)) {
                    files.add(path);
                }
            }
        }
        return files;
    }

    /** Whether one relative path inside a data directory is a data file to pack. */
    private static boolean packageableName(String relative) {
        String[] parts = relative.split("/");
        if (parts.length == 0) {
            return false;
        }
        for (String part : parts) {
            if (part.isEmpty() || part.startsWith(".") || !part.matches(SAFE_FILE_NAME)) {
                return false;
            }
        }
        String name = parts[parts.length - 1].toLowerCase(Locale.ROOT);
        return name.endsWith(".json") && !name.equals(MANIFEST_NAME);
    }

    /** An archive entry name for a file: forward slashes, whatever the platform's separator is. */
    private static String relativeName(Path directory, Path file) {
        return directory.relativize(file).toString().replace('\\', '/');
    }

    /** A name usable as one path component for an export or backup file. */
    private static String sanitizeForFileName(String raw) {
        String safe = raw == null ? "" : raw.replaceAll("[^A-Za-z0-9._-]", "_");
        if (safe.isBlank() || safe.matches("_+")) {
            return "howtogo";
        }
        return safe.length() > 80 ? safe.substring(0, 80) : safe;
    }

    /** A fresh {@code <dir>.replaced-<stamp>} beside the directory being replaced. */
    private static Path uniqueReplacedName(Path target) {
        String base = target.getFileName() + ".replaced-"
                + FILE_STAMP.format(Instant.now().atZone(ZoneId.systemDefault()));
        Path candidate = target.resolveSibling(base);
        for (int attempt = 2; Files.exists(candidate, LinkOption.NOFOLLOW_LINKS) && attempt < 100;
             attempt++) {
            candidate = target.resolveSibling(base + "-" + attempt);
        }
        return candidate;
    }

    // ------------------------------------------------------------------ file plumbing

    private static ZipFile open(Path archive) throws IOException {
        return new ZipFile(archive.toFile(), StandardCharsets.UTF_8);
    }

    /** Reopens an archive for extraction. */    private static ZipEntry findManifest(ZipFile zip) {
        for (Enumeration<? extends ZipEntry> all = zip.entries(); all.hasMoreElements(); ) {
            ZipEntry entry = all.nextElement();
            if (!entry.isDirectory() && MANIFEST_NAME.equals(baseName(entry.getName()))) {
                return entry;
            }
        }
        return null;
    }

    /**
     * Reads a manifest, or null when it is not one this mod wrote.
     *
     * <p>A manifest that does not parse, or belongs to another kind of archive, is treated as absent
     * rather than as an error: the data files are the map, and refusing a whole archive over a
     * damaged three-line metadata file would refuse a map that is still perfectly readable.
     */
    private static ManifestDto readManifest(ZipFile zip, ZipEntry entry) {
        try (InputStream in = new BufferedInputStream(zip.getInputStream(entry))) {
            byte[] bytes = readAtMost(in, MAX_MANIFEST_BYTES);
            if (bytes == null) {
                return null;
            }
            JsonElement root = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
            if (!root.isJsonObject()) {
                return null;
            }
            JsonObject object = root.getAsJsonObject();
            if (!object.has("kind") || !object.get("kind").isJsonPrimitive()
                    || !KIND.equals(object.get("kind").getAsString())) {
                return null;
            }
            ManifestDto dto = new ManifestDto();
            dto.kind = KIND;
            dto.format = object.has("format") && object.get("format").isJsonPrimitive()
                    ? object.get("format").getAsInt() : 0;
            dto.world = stringOrNull(object, "world");
            dto.exportedAt = stringOrNull(object, "exportedAt");
            return dto;
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    private static String stringOrNull(JsonObject object, String key) {
        if (!object.has(key) || !object.get(key).isJsonPrimitive()) {
            return null;
        }
        return object.get(key).getAsString();
    }

    /**
     * Reads at most {@code cap} bytes, or null when the stream holds more than that.
     *
     * <p>Used for entries whose declared size cannot be trusted: a zip can claim to be four bytes
     * and unpack to four gigabytes, so the limit has to be enforced while reading rather than read
     * from the header.
     */
    private static byte[] readAtMost(InputStream in, long cap) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0L;
        int read;
        while ((read = in.read(buffer)) >= 0) {
            total += read;
            if (total > cap) {
                return null;
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    /** Copies a stream, returning the number of bytes, or -1 once it exceeds {@code cap}. */
    private static long copyAtMost(InputStream in, OutputStream out, long cap) throws IOException {
        byte[] buffer = new byte[8192];
        long total = 0L;
        int read;
        while ((read = in.read(buffer)) >= 0) {
            total += read;
            if (total > cap) {
                return -1L;
            }
            out.write(buffer, 0, read);
        }
        return total;
    }

    /** Renames {@code from} onto {@code to}, replacing whatever is there. */
    private static void moveReplacing(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException notAtomic) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Moves a directory, atomically when the filesystem allows it. */
    private static void moveDirectory(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException notAtomic) {
            Files.move(from, to);
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // The failure that led here is the one worth reporting.
        }
    }

    /** Deletes a file or a whole directory tree, without following links out of it. */
    private static void deleteTree(Path root) throws IOException {
        if (root == null || !Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            Files.deleteIfExists(root);
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    /** The manifest's shape on disk. */
    private static final class ManifestDto {
        String kind;
        int format;
        String world;
        String exportedAt;
        List<String> files;
    }
}
