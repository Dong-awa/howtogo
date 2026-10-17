package bili.dongsz.howtogo.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The per-world data directory: which world it belongs to, and how a Chinese world name gets one.
 *
 * <h2>Why this is checked on a temporary directory</h2>
 * {@link WorldFiles#of} asks Minecraft which world is open, so it cannot run without a game; the
 * interesting half -- the name a world's data lives under, and the move of an existing directory to
 * its pinyin name -- is a pure filesystem question. The naming and migration are exactly where a
 * bug loses a player's roads, so they are exercised here: new Chinese worlds, existing all-underscore
 * worlds, two worlds whose pinyin names collide, and the ASCII names that must not change at all.
 */
public final class WorldFilesCheck {

    private static int checks;
    private static int failures;

    private WorldFilesCheck() {
    }

    public static int[] run() {
        System.out.println("== world data directories: pinyin names and the move to them ==");
        Path root = null;
        try {
            root = Files.createTempDirectory("howtogo-worlds");
            asciiNames(root.resolve("ascii"));
            chineseName(root.resolve("chinese"));
            legacyMove(root.resolve("legacy"));
            ownedMove(root.resolve("owned"));
            collision(root.resolve("collision"));
            longName(root.resolve("long"));
            dimensionFileNames();
        } catch (IOException failed) {
            expect("the temporary world directories could be used (" + failed + ")", false);
        } finally {
            deleteTree(root);
        }
        System.out.println(failures == 0
                ? "world data directories ok (" + checks + " checks)"
                : "world data directories FAILED: " + failures + " of " + checks + " checks");
        return new int[]{checks, failures};
    }

    /**
     * A dimension's file name is a compatibility contract, and it is deliberately not pinyin.
     *
     * <h2>Why this is checked here</h2>
     * Every existing world's roads are in a file named after its dimension with the colon replaced by
     * one underscore ({@code minecraft_overworld.json}). Pinyin was introduced for the world
     * <em>directory</em>, but it was applied to the dimension too for a while -- and ICU also rewrites
     * punctuation, so the file name became {@code minecraft__overworld.json}, the mod looked at a
     * file that did not exist, and a player's roads appeared to be gone while they sat on disk under
     * the old name. The world directory wants a name a human can read; the dimension file wants the
     * key the mod can find again. This pins the second one, and the ASCII shortcut that keeps the
     * transliterator away from a server address.
     */
    private static void dimensionFileNames() {
        expect("a dimension file keeps the plain name it always had",
                "minecraft_overworld".equals(WorldFiles.fileStem("minecraft:overworld")));
        expect("the nether and the end too",
                "minecraft_the_nether".equals(WorldFiles.fileStem("minecraft:the_nether"))
                        && "minecraft_the_end".equals(WorldFiles.fileStem("minecraft:the_end")));
        expect("a modded dimension is namespace and path with one underscore",
                "mymod_deep_dark".equals(WorldFiles.fileStem("mymod:deep_dark")));
        expect("a missing dimension is still unknown",
                "unknown".equals(WorldFiles.fileStem(null))
                        && "unknown".equals(WorldFiles.fileStem("   ")));
        expect("a hostile dimension cannot leave the world's directory",
                !WorldFiles.fileStem("../../evil").contains("/")
                        && !WorldFiles.fileStem("..\\..\\evil").contains("\\"));
        expect("ASCII text is never handed to the pinyin transliterator",
                "minecraft:overworld".equals(Pinyin.latin("minecraft:overworld")));
        expect("so a server address's colon survives as it always did",
                "play.example.net:25565".equals(Pinyin.latin("play.example.net:25565")));
    }

    /** An ASCII world keeps exactly the directory name it always had. */
    private static void asciiNames(Path root) throws IOException {        String name = WorldFiles.worldDirectory(root, "sp_My World");
        expect("an ASCII world's directory name is unchanged", "sp_My_World".equals(name));
        expect("and its directory and world record exist",
                Files.isRegularFile(root.resolve(name).resolve(".world"))
                        && Files.readString(root.resolve(name).resolve(".world"),
                        StandardCharsets.UTF_8).trim().equals("sp_My World"));
    }

    /** A Chinese world gets pinyin, not a row of underscores. */
    private static void chineseName(Path root) throws IOException {
        String world = "sp_新世界";
        String legacy = "sp" + "_".repeat(world.length() - 2);
        String name = WorldFiles.worldDirectory(root, world);
        expect("a Chinese world's directory name is pinyin, not underscores",
                !name.equals(legacy) && name.matches("[A-Za-z0-9._-]+") && name.contains("_"));
        expect("and it is not all underscores after the prefix",
                !name.substring(3).matches("_+"));
        expect("the same world asks for the same directory twice",
                name.equals(WorldFiles.worldDirectory(root, world)));
    }

    /** A world stored before pinyin existed is moved to the new directory, files and all. */
    private static void legacyMove(Path root) throws IOException {
        String world = "sp_测试世界";
        String legacy = "sp" + "_".repeat(world.length() - 2);
        Path old = root.resolve(legacy);
        Files.createDirectories(old);
        Files.writeString(old.resolve("minecraft_overworld.json"), "roads", StandardCharsets.UTF_8);

        String name = WorldFiles.worldDirectory(root, world);
        expect("an existing world is moved from its underscore directory to the pinyin one",
                !name.equals(legacy) && Files.isDirectory(root.resolve(name)));
        expect("and its data came with it",
                Files.readString(root.resolve(name).resolve("minecraft_overworld.json"),
                        StandardCharsets.UTF_8).equals("roads"));
        expect("the old directory is gone rather than left beside the new one",
                !Files.exists(old));
        expect("and the new directory records which world it is",
                Files.readString(root.resolve(name).resolve(".world"), StandardCharsets.UTF_8)
                        .trim().equals(world));
    }

    /** A directory already recording a world is moved to its pinyin name, not duplicated. */
    private static void ownedMove(Path root) throws IOException {
        String world = "sp_旧世界";
        Path old = root.resolve("some_old_name");
        Files.createDirectories(old);
        Files.writeString(old.resolve(".world"), world, StandardCharsets.UTF_8);
        String name = WorldFiles.worldDirectory(root, world);
        expect("a recorded directory under an old name is moved to the pinyin name",
                !name.equals("some_old_name") && Files.isDirectory(root.resolve(name)));
        expect("and the record still names the same world",
                Files.readString(root.resolve(name).resolve(".world"), StandardCharsets.UTF_8)
                        .trim().equals(world));
    }

    /** Two worlds whose pinyin names collide get two directories, each with its own record. */
    private static void collision(Path root) throws IOException {
        String first = "sp_世界";
        String second = "sp_视界";
        String firstDirectory = WorldFiles.worldDirectory(root, first);
        String secondDirectory = WorldFiles.worldDirectory(root, second);
        expect("two worlds are never given the same data directory",
                !firstDirectory.equals(secondDirectory));
        expect("and each directory records its own world",
                readMarker(root, firstDirectory).equals(first)
                        && readMarker(root, secondDirectory).equals(second));
        expect("while asking for either again returns its own directory",
                firstDirectory.equals(WorldFiles.worldDirectory(root, first))
                        && secondDirectory.equals(WorldFiles.worldDirectory(root, second)));
    }

    private static String readMarker(Path root, String directory) throws IOException {
        return Files.readString(root.resolve(directory).resolve(".world"), StandardCharsets.UTF_8)
                .trim();
    }

    /** Pinyin is longer than the characters it replaces, so a long world name has to be capped. */
    private static void longName(Path root) throws IOException {
        String world = "sp_" + "中".repeat(80);
        String name = WorldFiles.worldDirectory(root, world);
        expect("a very long Chinese world name still fits in one path component",
                name.length() > 0 && name.length() <= 120);
        expect("and its directory was created all the same",
                Files.isDirectory(root.resolve(name)));
    }

    private static void deleteTree(Path root) {
        if (root == null) {
            return;
        }
        try (var paths = Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // A temp directory that outlives the check costs nothing.
                }
            });
        } catch (IOException ignored) {
            // Same.
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
