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
 * A world's map data as a zip: what an export writes and what an import reads, with no game dependency.
 * Keeping it out of the client package lets the regression harness exercise it on temporary directories, covering the two dangerous cases -- writing the wrong files and a zip entry escaping the world directory -- without a game.
 * A world's data is config/howtogo/<world>: one JSON per dimension, the rail-name and transit-line files, the .world record and addon files given by HowToGoApi.dataFile. All of it travels.
 * Archive shape: an optional manifest (MANIFEST_NAME) records origin and time and data files sit at the top. Directory levels are stripped to file names, because a zipped folder carries the sender's world name. Only JSON files are data; directory entries, resource forks, .world and development leftovers are skipped. The .world record is rewritten for the importing world.
 * Import replaces the whole data directory -- files absent from the archive are gone. The new directory is built beside the old and renamed into place, a single filesystem step, so a power cut leaves one whole directory. The caller backs up first, and importFrom also sets aside what it replaced so a failed cleanup cannot leave a second directory the mod might mistake for this world's.
 */
public final class WorldArchive {

    /** Version written into the manifest. Reading is tolerant of other values. */
    public static final int FORMAT_VERSION = 1;

    public static final String MANIFEST_NAME = "howtogo-export.json";

    public static final String WORLD_MARKER = ".world";

    /** How many data files one archive may carry. */
    public static final int MAX_FILES = 512;

    /** How large one data file may be, compressed or not. */
    public static final long MAX_FILE_BYTES = 32L * 1024L * 1024L;

    /** How large the whole data set may be once unpacked. */
    public static final long MAX_TOTAL_BYTES = 64L * 1024L * 1024L;

    private static final long MAX_MANIFEST_BYTES = 64L * 1024L;

    private static final String SAFE_FILE_NAME = "[A-Za-z0-9._-]+";

    /** Marks archives this mod wrote, so a manifest of another kind is ignored rather than trusted. */
    private static final String KIND = "howtogo-map";

    /** Names Windows refuses whatever the extension; a data file must not become one. */
    private static final List<String> RESERVED_NAMES = List.of(
            "con", "prn", "aux", "nul",
            "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9",
            "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9");

    private static final DateTimeFormatter FILE_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT);

    private static final DateTimeFormatter MANIFEST_STAMP = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private WorldArchive() {
    }


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

    /** The manifest of an archive this mod wrote, as far as a screen or command wants to show it. */
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
        public final String name;
        public final String entry;
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
         * Set only by importFrom: a directory named <world>.replaced-<stamp> whose .world record was removed, so the mod will not mistake it for this world's data. Reported rather than hidden because it is the player's old map still on disk.
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


    /**
     * Writes one world's data directory as an archive, plus the manifest saying whose it was.
     * Only .json files under names this mod could produce are packed: development leftovers (.part, .corrupt) and the .world record are left out because the record describes a directory, not a map.
     * Written beside its target and renamed onto it, so an interrupted export cannot replace a good archive with a truncated one.
     * @param worldDirectory the directory to pack; a missing one packs as an empty map
     * @param archive the zip to write
     * @param world the world key recorded in the manifest for display when importing
     * @param now the export time, recorded in the manifest and the file name
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

        // Read first, write second: the manifest lists what was packed, so the list must exist
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
     * Named by world and timestamp in a directory of its own, so it is never read as a world's data or packed into another backup. Null when the world has no data directory: importing into a world with no map overwrites nothing.
     * @param now the moment of the backup, which also names the file
     */
    public static Packed backup(Path worldDirectory, Path backupDirectory, String world, Instant now)
            throws IOException {
        if (worldDirectory == null || !Files.isDirectory(worldDirectory)) {
            return null;
        }
        Files.createDirectories(backupDirectory);
        // The name says what the file is, not only where it came from: a backup must not
        String base = sanitizeForFileName(worldDirectory.getFileName().toString()) + "-backup";
        Path file = uniqueFile(backupDirectory, base, now);
        return export(worldDirectory, file, world, now);
    }

    /** A file name for prefix + -yyyyMMdd-HHmmss.zip that is not taken yet. */
    public static Path uniqueFile(Path directory, String prefix, Instant now) {
        String stamp = FILE_STAMP.format(now.atZone(ZoneId.systemDefault()));
        String base = sanitizeForFileName(prefix);
        Path file = directory.resolve(base + "-" + stamp + ".zip");
        for (int attempt = 2; Files.exists(file) && attempt < 100; attempt++) {
            file = directory.resolve(base + "-" + stamp + "-" + attempt + ".zip");
        }
        return file;
    }


    /**
     * Reads an archive's manifest only, for a list of files a player might import.
     * A screen listing ten archives must not unpack ten maps. Null when the file is not a readable zip or carries no manifest this mod understands; the archive may still be importable, and inspect is what says so.
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
     * This is the validation an import relies on, done before anything is written: each entry must name a file that can be written safely, each data file must be JSON, and count and total size are capped so a zip bomb is refused. Failing entries are counted in Contents.ignored rather than treated as fatal, because a real archive may carry macOS metadata and development leftovers. An archive with no usable data file is allowed only when it carries this mod's manifest -- an export of a world with no map looks like that -- while a zip with neither is fatal, since importing it would silently delete the current map.
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
     * Null when the entry is not readable JSON; the caller counts it ignored, so one damaged file does not lose the whole map and the screen says how many were left out.
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


    /**
     * Replaces a world's data directory with an archive's contents, in one rename.
     * No blending: a dimension file absent from the archive is one the imported map does not have. The caller backs up the old directory first; this method also moves it aside so it can be put back if the new directory cannot be put in place.
     * The .world record is written for the importing world, never copied from the archive -- which is what makes the archive's name irrelevant.
     * @param world the key of the world being imported into, as WorldFiles writes it
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
                // Drop the record first. If the old directory cannot be deleted, what is left
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
                    // Nothing useful can be done, and the failure that mattered is already
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


    private static final class RawEntry {
        final String entry;
        final String base;

        RawEntry(String entry, String base) {
            this.entry = entry;
            this.base = base;
        }
    }

    /**
     * The common directory prefix of the data entries inside a directory, or empty when there is none.
     * Only entries with a parent take part: a top-level file is already where it belongs, and mixing it in would stop a hand-zipped folder from being recognised once one JSON file sat beside it. The prefix is the folder name a zipper adds; stripping it makes the sender's world name meaningless.
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

    /**
     * The entry name with a common directory prefix removed, or unchanged when it does not start with it.
     */
    private static String strip(String entry, String prefix) {
        if (prefix.isEmpty()) {
            return entry;
        }
        return entry.startsWith(prefix + "/") ? entry.substring(prefix.length() + 1) : entry;
    }

    /**
     * The plain file name an archive entry may be written as, or null when it may not.
     * Everything the player would see is stripped by strip; what is left must be one component, plain ASCII, and a name this mod could have written. A separator here is a second directory level the prefix rule missed, and refusing it keeps a hostile or unusual archive to the one directory an import may touch.
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
     * Checked as written: no absolute path, no drive letter, no segment walking back up. Entries are only ever resolved against this class's staging directory, so no entry can offer it a path that would try.
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

    private static String baseName(String entry) {
        int slash = entry.lastIndexOf('/');
        return slash < 0 ? entry : entry.substring(slash + 1);
    }

    /**
     * Every file under a directory that belongs in an archive, in stable order.
     * JSON only and only under names this mod would write -- the same rule the reader applies, so a file this mod exports is one a later version can import.
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

    private static String sanitizeForFileName(String raw) {
        String safe = raw == null ? "" : raw.replaceAll("[^A-Za-z0-9._-]", "_");
        if (safe.isBlank() || safe.matches("_+")) {
            return "howtogo";
        }
        return safe.length() > 80 ? safe.substring(0, 80) : safe;
    }

    /** A fresh <dir>.replaced-<stamp> beside the directory being replaced. */
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
     * An unparsable manifest, or one of another kind, is treated as absent: the data files are the map, and refusing a whole archive over a damaged three-line metadata file would refuse a readable map.
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
     * Reads at most cap bytes, or null when the stream holds more.
     * Used for entries whose declared size cannot be trusted: a zip can claim four bytes and unpack to four gigabytes, so the limit is enforced while reading rather than read from the header.
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

    /** Copies a stream, returning the number of bytes, or -1 once it exceeds cap. */
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

    private static void moveReplacing(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException notAtomic) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

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
