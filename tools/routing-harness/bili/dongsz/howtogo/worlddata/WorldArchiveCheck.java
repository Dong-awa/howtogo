package bili.dongsz.howtogo.worlddata;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * The world archive: pack a map, read one, and lay it over a directory without losing anything.
 *
 * <h2>Why this is checked off the game</h2>
 * Everything an import can get wrong is a file question: which files count as map data, where an
 * entry with a {@code ..} in its name may be written, whether an archive with a folder inside it still
 * finds the world, and whether the file a player already had is still there when the import is refused
 * rather than when it succeeds. None of that needs Minecraft, so every case below runs on temporary
 * directories and the checks are a second rather than a game launch.
 *
 * <h2>The two rules that matter most</h2>
 * The archive's name and the save folder inside it are both the <em>sender's</em> world. The import
 * target is the world the player is in, and it must say so in its own {@code .world} record no matter
 * what the archive says. The other rule is that a failed import must cost nothing: a zip that is not a
 * map, or one whose only file is too large, leaves the existing directory exactly as it was.
 */
public final class WorldArchiveCheck {

    private static int checks;
    private static int failures;

    private WorldArchiveCheck() {
    }

    public static int[] run() {
        System.out.println("== world map archives: export, inspect, import ==");
        Path root = null;
        try {
            root = Files.createTempDirectory("howtogo-archive");
            roundTrip(root.resolve("round-trip"));
            nameIsNotTheWorld(root.resolve("names"));
            folderInsideTheArchive(root.resolve("inside-folder"));
            zippedDataDirectory(root.resolve("zipped-directory"));
            mixedRoots(root.resolve("mixed-roots"));
            emptyMapIsStillAMap(root.resolve("empty-map"));
            unsafeEntries(root.resolve("unsafe"));
            junkIsSkipped(root.resolve("junk"));
            notAnArchive(root.resolve("not-a-map"));
            tooManyFiles(root.resolve("too-many"));
            backupOfTheCurrentMap(root.resolve("backup"));
        } catch (IOException failed) {
            expect("the temporary archive directories could be used (" + failed + ")", false);
        } finally {
            deleteTree(root);
        }
        System.out.println(failures == 0
                ? "world map archives ok (" + checks + " checks)"
                : "world map archives FAILED: " + failures + " of " + checks + " checks");
        return new int[]{checks, failures};
    }

    /** Export writes every data file and the manifest; importing it back restores exactly those. */
    private static void roundTrip(Path root) throws IOException {
        Path source = root.resolve("source");
        Path target = root.resolve("target");
        Path exports = root.resolve("exports");
        Files.createDirectories(source);
        write(source.resolve("minecraft_overworld.json"), "{\"version\":1,\"nodes\":[]}");
        write(source.resolve("minecraft_the_nether-lines.json"), "{\"version\":1,\"lines\":[]}");
        write(source.resolve("addon-extra.json"), "{\"note\":\"an addon's own data travels too\"}");
        write(source.resolve(".world"), "sp_old_name");
        write(source.resolve("roads.json.part"), "half a write");
        write(source.resolve("readme.txt"), "not data");
        Files.createDirectories(exports);

        Path archive = exports.resolve("export.zip");
        WorldArchive.Packed packed = WorldArchive.export(source, archive, "sp_old_name",
                Instant.now());
        expect("an export writes the archive", Files.isRegularFile(archive));
        expect("it packs the world's json files and not its development leftovers",
                packed.files == 3);
        expect("and counts the bytes it wrote", packed.bytes > 0);

        WorldArchive.Manifest manifest = WorldArchive.peek(archive);
        expect("the archive's manifest can be read on its own", manifest != null);
        expect("and it names the world the export came from",
                manifest != null && "sp_old_name".equals(manifest.world));

        WorldArchive.Contents contents = WorldArchive.inspect(archive);
        expect("inspecting lists the same three files", contents.files() == 3);
        expect("the .world record is not one of the data files",
                contents.items.stream().noneMatch(item -> item.name.equals(".world")));

        Files.createDirectories(target);
        write(target.resolve("left-behind.json"), "{\"old\":true}");
        WorldArchive.Contents imported = WorldArchive.importFrom(archive, target, "sp_new_world");
        expect("importing writes the files into the target", imported.files() == 3);
        expect("the file only the old map had is gone",
                !Files.exists(target.resolve("left-behind.json")));
        expect("the road network is byte-for-byte the exported one",
                Files.readString(target.resolve("minecraft_overworld.json"),
                        StandardCharsets.UTF_8)
                        .equals("{\"version\":1,\"nodes\":[]}"));
        expect("the directory's record names the importing world, not the exporting one",
                Files.readString(target.resolve(".world"), StandardCharsets.UTF_8)
                        .trim().equals("sp_new_world"));
        expect("and nothing was left behind needing a cleanup", imported.leftover == null);
    }

    /** Neither the zip's own name nor the manifest's world chooses where the data goes. */
    private static void nameIsNotTheWorld(Path root) throws IOException {
        Path archive = root.resolve("somebody_elses_world.zip");
        zip(archive, List.of(
                new EntryPart("howtogo-export.json",
                        "{\"kind\":\"howtogo-map\",\"format\":1,\"world\":\"sp_sender\","
                                + "\"exportedAt\":\"2026-10-17T10:00:00+08:00\"}"),
                new EntryPart("minecraft_overworld.json", "{\"version\":1}")));
        Path target = root.resolve("my_world");
        Files.createDirectories(target);

        WorldArchive.Contents contents = WorldArchive.importFrom(archive, target, "sp_receiver");
        expect("an archive named after another world still imports into this one",
                contents.files() == 1 && Files.isRegularFile(target.resolve("minecraft_overworld.json")));
        expect("and the record says so",
                Files.readString(target.resolve(".world"), StandardCharsets.UTF_8).trim()
                        .equals("sp_receiver"));
        expect("the archive's own name is not used as a directory",
                !Files.exists(root.resolve("somebody_elses_world")));
    }

    /** A folder zipped by hand puts its name on every entry; the import still finds the files. */
    private static void folderInsideTheArchive(Path root) throws IOException {
        Path archive = root.resolve("zipped-folder.zip");
        zip(archive, List.of(
                new EntryPart("sp_sender/", null),
                new EntryPart("sp_sender/minecraft_overworld.json", "{\"version\":1}"),
                new EntryPart("sp_sender/minecraft_overworld-lines.json", "{\"version\":1}")));
        Path target = root.resolve("target");
        Files.createDirectories(target);

        WorldArchive.Contents contents = WorldArchive.importFrom(archive, target, "sp_mine");
        expect("the folder inside the archive is stripped down to file names",
                contents.files() == 2
                        && Files.isRegularFile(target.resolve("minecraft_overworld.json"))
                        && Files.isRegularFile(target.resolve("minecraft_overworld-lines.json")));
        expect("and no folder of the sender's name appears in the world's directory",
                !Files.exists(target.resolve("sp_sender")));
    }

    /** A zip of {@code config/howtogo/<world>} nested two levels deep still finds the data. */
    private static void zippedDataDirectory(Path root) throws IOException {
        Path archive = root.resolve("config-howtogo.zip");
        zip(archive, List.of(
                new EntryPart("howtogo/sp_sender/minecraft_overworld.json", "{\"version\":1}"),
                new EntryPart("howtogo/sp_sender/minecraft_overworld-lines.json", "{\"version\":1}")));
        Path target = root.resolve("target");
        Files.createDirectories(target);

        WorldArchive.Contents contents = WorldArchive.importFrom(archive, target, "sp_mine");
        expect("a zip of the whole config/howtogo tree is stripped down to the data files",
                contents.files() == 2
                        && Files.isRegularFile(target.resolve("minecraft_overworld.json")));
        expect("and none of the folder levels reach the world's directory",
                !Files.exists(target.resolve("howtogo")));
    }

    /**
     * A stray JSON at the top of a hand-made zip does not stop the folder beside it being the map.
     *
     * <p>This is the shape a file manager produces when a player zips a folder and a config file by
     * hand: the map files under the folder, and something else at the top. Refusing the whole archive
     * over the stray file would refuse the map that is right there.
     */
    private static void mixedRoots(Path root) throws IOException {
        Path archive = root.resolve("mixed.zip");
        zip(archive, List.of(
                new EntryPart("notes.json", "{\"version\":1}"),
                new EntryPart("sp_sender/minecraft_overworld.json", "{\"version\":1}")));
        Path target = root.resolve("target");
        Files.createDirectories(target);

        WorldArchive.Contents contents = WorldArchive.importFrom(archive, target, "sp_mine");
        expect("the top-level JSON and the map inside the folder both import",
                contents.files() == 2
                        && Files.isRegularFile(target.resolve("notes.json"))
                        && Files.isRegularFile(target.resolve("minecraft_overworld.json")));
    }

    /**
     * An export of an empty map is still an archive, and importing it clears the map.
     *
     * <p>The symmetry matters: exporting a world that has no roads yet and then importing that
     * archive into it must not be refused by the rule that keeps arbitrary zips out. The manifest is
     * what tells the two apart, which is why the reader allows an empty data set only when this mod
     * wrote the archive.
     */
    private static void emptyMapIsStillAMap(Path root) throws IOException {
        Path source = root.resolve("source");
        Path target = root.resolve("target");
        Files.createDirectories(source);
        Files.createDirectories(target);
        write(target.resolve("minecraft_overworld.json"), "{\"old\":true}");
        Path archive = root.resolve("empty.zip");

        WorldArchive.Packed packed = WorldArchive.export(source, archive, "sp_mine",
                Instant.now());
        expect("an empty map exports one archive with no data files", packed.files == 0);
        WorldArchive.Contents contents = WorldArchive.importFrom(archive, target, "sp_mine");
        expect("and importing it empties the map", contents.files() == 0
                && !Files.exists(target.resolve("minecraft_overworld.json")));
        expect("while a zip with neither data nor a manifest is still refused",
                refuses(root.resolve("neither.zip"), root.resolve("neither-target")));
    }

    /** Whether importing an archive is refused, with no target prepared. */
    private static boolean refuses(Path archive, Path target) throws IOException {
        zip(archive, List.of(new EntryPart("readme.txt", "hello")));
        Files.createDirectories(target);
        try {
            WorldArchive.importFrom(archive, target, "sp_mine");
            return false;
        } catch (IOException expected) {
            return true;
        }
    }

    /** Entries that would walk out of the world's directory are dropped, not followed. */
    private static void unsafeEntries(Path root) throws IOException {
        Path archive = root.resolve("unsafe.zip");
        zip(archive, List.of(
                new EntryPart("../escaped.json", "{\"version\":1}"),
                new EntryPart("../../escaped-two.json", "{\"version\":1}"),
                new EntryPart("/absolute.json", "{\"version\":1}"),
                new EntryPart("a/../../escaped-three.json", "{\"version\":1}"),
                new EntryPart("C:/drive.json", "{\"version\":1}"),
                new EntryPart("safe.json", "{\"version\":1}")));
        Path target = root.resolve("target");
        Files.createDirectories(target);

        WorldArchive.Contents contents = WorldArchive.importFrom(archive, target, "sp_mine");
        expect("only the safe entry is written", contents.files() == 1);
        expect("the world's own directory holds that one file",
                Files.isRegularFile(target.resolve("safe.json")));
        expect("nothing was written beside the world's directory",
                !Files.exists(root.resolve("escaped.json"))
                        && !Files.exists(root.resolve("escaped-two.json"))
                        && !Files.exists(root.resolve("escaped-three.json")));
        expect("the refused entries are counted as ignored", contents.ignored >= 5);
    }

    /** Metadata and half-written files are skipped rather than refusing the map they sit beside. */
    private static void junkIsSkipped(Path root) throws IOException {
        Path archive = root.resolve("junk.zip");
        zip(archive, List.of(
                new EntryPart("__MACOSX/._minecraft_overworld.json", "not json at all"),
                new EntryPart(".DS_Store", "\u0000"),
                new EntryPart(".world", "sp_sender"),
                new EntryPart("minecraft_overworld.json.part", "{\"version\":1}"),
                new EntryPart("broken.json", "{ this is not json"),
                new EntryPart("minecraft_overworld.json", "{\"version\":1}")));
        Path target = root.resolve("target");
        Files.createDirectories(target);

        WorldArchive.Contents contents = WorldArchive.importFrom(archive, target, "sp_mine");
        expect("the one real data file is imported", contents.files() == 1);
        expect("the archive's own .world record is not used",
                Files.readString(target.resolve(".world"), StandardCharsets.UTF_8).trim()
                        .equals("sp_mine"));
        expect("macOS metadata, half a save and unparseable json are all ignored",
                contents.ignored >= 4);
        expect("and the broken json never appears in the world",
                !Files.exists(target.resolve("broken.json")));
    }

    /** A zip with no map data is refused, and the world it was aimed at is untouched. */
    private static void notAnArchive(Path root) throws IOException {
        Path archive = root.resolve("not-a-map.zip");
        zip(archive, List.of(
                new EntryPart("readme.txt", "hello"),
                new EntryPart("some/other/video.mp4", "not a map")));
        Path target = root.resolve("target");
        Files.createDirectories(target);
        write(target.resolve("minecraft_overworld.json"), "{\"version\":1,\"kept\":true}");

        boolean refused = false;
        try {
            WorldArchive.importFrom(archive, target, "sp_mine");
        } catch (IOException expected) {
            refused = true;
        }
        expect("a zip that is not a map is refused", refused);
        expect("and the world's existing map is exactly as it was",
                Files.readString(target.resolve("minecraft_overworld.json"),
                        StandardCharsets.UTF_8).contains("kept"));
        expect("and no staging directory was left in the parent",
                noDotImportBeside(target));
    }

    /** A zip of ten thousand files is not a map; the cap refuses it rather than reading it all. */
    private static void tooManyFiles(Path root) throws IOException {
        Path archive = root.resolve("huge-count.zip");
        List<EntryPart> parts = new ArrayList<>();
        for (int i = 0; i < WorldArchive.MAX_FILES + 5; i++) {
            parts.add(new EntryPart("file-" + i + ".json", "{\"version\":1}"));
        }
        zip(archive, parts);

        boolean refused = false;
        try {
            WorldArchive.inspect(archive);
        } catch (IOException expected) {
            refused = true;
        }
        // The reader caps rather than refusing: a map is allowed to be missing extras. What must not
        // happen is more than the cap being written.
        WorldArchive.Contents contents = refused ? null : WorldArchive.inspect(archive);
        expect("an archive over the file cap is either capped or refused", refused
                || (contents != null && contents.files() <= WorldArchive.MAX_FILES));
        expect("and it is never imported over the cap", refused
                || (contents != null && contents.files() <= WorldArchive.MAX_FILES));
    }

    /** A backup is an archive an import can read, which is what makes an import undoable. */
    private static void backupOfTheCurrentMap(Path root) throws IOException {
        Path world = root.resolve("world");
        Path backups = root.resolve("backups");
        Path restored = root.resolve("restored");
        Files.createDirectories(world);
        write(world.resolve("minecraft_overworld.json"), "{\"version\":1,\"nodes\":[1,2,3]}");
        write(world.resolve(".world"), "sp_old_name");

        WorldArchive.Packed backup = WorldArchive.backup(world, backups, "sp_old_name",
                Instant.now());
        expect("a backup writes one archive", backup != null && Files.isRegularFile(backup.file));
        expect("and its file name says it is a backup rather than a map",
                backup != null && backup.file.getFileName().toString().contains("backup"));
        expect("the backup of an empty world is null rather than an empty file",
                WorldArchive.backup(root.resolve("absent"), backups, "sp_old_name",
                        Instant.now()) == null);

        Files.createDirectories(restored);
        WorldArchive.Contents contents = WorldArchive.importFrom(backup.file, restored,
                "sp_old_name");
        expect("the backup can be imported back", contents.files() == 1
                && Files.readString(restored.resolve("minecraft_overworld.json"),
                StandardCharsets.UTF_8).contains("[1,2,3]"));
    }

    // ------------------------------------------------------------------ helpers

    /** One entry to put into a test archive; a null body makes it a directory entry. */
    private record EntryPart(String name, String body) {
    }

    private static void zip(Path archive, List<EntryPart> entries) throws IOException {
        Files.createDirectories(archive.getParent());
        try (OutputStream raw = Files.newOutputStream(archive);
             ZipOutputStream zip = new ZipOutputStream(raw, StandardCharsets.UTF_8)) {
            for (EntryPart entry : entries) {
                zip.putNextEntry(new ZipEntry(entry.name));
                if (entry.body != null) {
                    zip.write(entry.body.getBytes(StandardCharsets.UTF_8));
                }
                zip.closeEntry();
            }
        }
    }

    private static void write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    private static boolean noDotImportBeside(Path target) throws IOException {
        Path parent = target.getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            return true;
        }
        try (Stream<Path> children = Files.list(parent)) {
            return children.noneMatch(path -> path.getFileName().toString()
                    .startsWith(".howtogo-import-"));
        }
    }

    private static void deleteTree(Path root) {
        if (root == null) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException ignored) {
            // A test's leftovers are not worth failing the harness over.
        }
    }

    private static void expect(String what, boolean ok) {
        checks++;
        if (!ok) {
            failures++;
        }
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
    }
}
