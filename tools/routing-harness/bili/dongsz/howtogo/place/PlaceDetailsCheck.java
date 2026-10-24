package bili.dongsz.howtogo.place;

import bili.dongsz.howtogo.road.PlaceKind;
import bili.dongsz.howtogo.route.Destination;
import bili.dongsz.howtogo.worlddata.WorldArchive;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The place-detail record: the limits it enforces, the file it survives, the key that finds it, and
 * the archive that carries it.
 *
 * <h2>Why this is checked off the game</h2>
 * A detail is a note the player wrote; there is no view that shows whether it was cut, re-spelled,
 * de-duplicated or lost, so the only way to hold the rules is to call them. Every question here is a
 * pure one -- a value object, a JSON file, a three-part key and a zip of a directory -- so the whole
 * check runs on temporary files in well under a second. It is also the half the in-game self-test
 * deliberately leaves alone: tolerant reading of a hand-edited file is easiest to exercise here,
 * where writing the awkward file is the point rather than something that must not happen.
 *
 * <h2>What is held</h2>
 * The boundaries a hand-edited file and a typing dialog both go through (512 code points, sixteen
 * eight-character tags), the one-line preview and the payload cut with newlines kept, and the
 * tolerant file: unknown fields ignored, missing fields defaulted, the first of a repeated key kept,
 * a newer version read rather than refused, an unparseable file set aside instead of overwritten.
 * Then the identity half: each of the five built-in sources' destination shapes maps to the key its
 * source states, and the derived id stays the same across calls and across a storage round trip.
 * Last, the feature a player actually uses to move a map -- export, inspect and import a directory
 * holding both a road network and a details file.
 */
public final class PlaceDetailsCheck {

    private static int checks;
    private static int failures;

    private PlaceDetailsCheck() {
    }

    public static int[] run() {
        System.out.println("== place details: limits, storage, keys, archive ==");
        Path root = null;
        try {
            root = Files.createTempDirectory("howtogo-place-details");
            limits();
            previews();
            valueChanges();
            storageRoundTrip(root.resolve("store"));
            storageDefaults(root.resolve("defaults"));
            storageTolerance(root.resolve("tolerant"));
            keyMapping(root.resolve("keys"));
            deletePolicy();
            archiveRoundTrip(root.resolve("archive"));
        } catch (IOException failed) {
            expect("the temporary directories could be used (" + failed + ")", false);
        } finally {
            deleteTree(root);
        }
        System.out.println(failures == 0
                ? "place details ok (" + checks + " checks)"
                : "place details FAILED: " + failures + " of " + checks + " checks");
        return new int[]{checks, failures};
    }

    /**
     * The limits, at their own edges: a newline is a character, a surrogate pair is not two.
     *
     * <p>The pair matters because {@code String.substring} counts UTF-16 units and a cut through one
     * leaves a lone surrogate: not text any more, and a value that would then have to be defended
     * against by every later consumer. So the over-long string puts the pair exactly where the cut
     * falls.
     */
    private static void limits() {
        expect("the documented limits are the numbers the game enforces",
                PlaceDetails.MAX_DESCRIPTION == 512 && PlaceDetails.MAX_TAGS == 16
                        && PlaceDetails.MAX_TAG_LENGTH == 8);

        String emoji = "\uD83D\uDE00";
        String overlong = "a".repeat(PlaceDetails.MAX_DESCRIPTION - 1) + emoji + "b";
        String cut = PlaceDetails.of(overlong, List.of(), false).normalize().description();
        expect("a description is trimmed", "贴边".equals(
                PlaceDetails.of("  贴边 \r\n", List.of(), false).normalize().description()));
        expect("longer than 512 code points it is cut to 512",
                PlaceDetails.lengthInCodePoints(cut) == PlaceDetails.MAX_DESCRIPTION);
        expect("and never through a surrogate pair", cut.endsWith(emoji)
                && Character.isHighSurrogate(cut.charAt(cut.length() - 2)));

        // Enough blank, duplicate and over-long tags that all four rules have something to do.
        List<String> messy = new ArrayList<>();
        messy.add("   ");
        messy.add("");
        messy.add("MTR");
        messy.add("mtr");
        messy.add("文化中心站台层出入口东西两侧");
        for (int i = 0; i < 20; i++) {
            messy.add("t" + i);
        }
        PlaceDetails tags = PlaceDetails.of("", messy, false).normalize();
        expect("blank tags are dropped", tags.tags().stream().noneMatch(String::isBlank));
        expect("a tag is kept once, in the spelling first written",
                tags.tags().stream().filter(tag -> tag.equalsIgnoreCase("mtr")).count() == 1
                        && "MTR".equals(tags.tags().get(0)));
        expect("a tag longer than 8 code points is cut to 8",
                PlaceDetails.lengthInCodePoints(tags.tags().get(1)) == PlaceDetails.MAX_TAG_LENGTH);
        expect("no more than 16 tags survive", tags.tags().size() == PlaceDetails.MAX_TAGS);

        expect("an empty record says so", PlaceDetails.EMPTY.isEmpty()
                && PlaceDetails.of("   \n", List.of(), false).normalize().isEmpty());
        expect("while a note, a tag or a favourite is worth storing",
                !PlaceDetails.of("x", List.of(), false).isEmpty()
                        && !PlaceDetails.of("", List.of("tag"), false).isEmpty()
                        && !PlaceDetails.of("", List.of(), true).isEmpty());
    }

    /** The one-line preview, and the payload cut that keeps its newlines. */
    private static void previews() {
        PlaceDetails shortDetails = PlaceDetails.of("短描述", List.of(), false);
        PlaceSummary shortPreview = shortDetails.summary(200);
        expect("a short description is its own preview, unflagged",
                !shortPreview.truncated() && "短描述".equals(shortPreview.text())
                        && "短描述".equals(shortPreview.display()));

        PlaceSummary longPreview = PlaceDetails.of("描".repeat(300), List.of(), false).summary(200);
        expect("a longer one is flagged and cut to the asked-for 200 characters",
                longPreview.truncated()
                        && PlaceDetails.lengthInCodePoints(longPreview.text()) == 200);
        expect("and its display form says more exists", longPreview.display().endsWith("…"));

        expect("newlines and whitespace runs collapse to one line", "第一行 第二行 结束".equals(
                PlaceDetails.of("第一行\n\n第二行\t结束", List.of(), false).summary(200).text()));

        PlaceSummary payload = PlaceDetails.of("第一行\n" + "x".repeat(300), List.of(), false)
                .truncated(200);
        expect("the web map's payload is cut but keeps its newlines",
                payload.truncated() && payload.text().startsWith("第一行\n"));
        expect("and a short description's payload is whole",
                !shortDetails.truncated(200).truncated()
                        && "短描述".equals(shortDetails.truncated(200).text()));
    }

    /** The value half: one member changed, the rest carried along, and nothing mutated in place. */
    private static void valueChanges() {
        PlaceDetails base = PlaceDetails.of("描述", List.of("tag"), false);
        PlaceDetails favourite = base.withFavorite(true);
        expect("favourite is a new value", favourite.favorite() && !base.favorite());
        expect("and carries the rest of the record with it",
                "描述".equals(favourite.description()) && favourite.tags().equals(List.of("tag")));
        expect("the modification time travels with the value",
                base.withUpdatedAt(1730000000000L).updatedAt() == 1730000000000L);
        expect("a description change keeps the tags and the flag",
                base.withFavorite(true).withDescription("新").tags().equals(List.of("tag"))
                        && base.withFavorite(true).withDescription("新").favorite());
    }

    /** Two records through the real writer and the real reader, every awkward character included. */
    private static void storageRoundTrip(Path root) throws IOException {
        Path file = root.resolve("place_details.json");
        Map<PlaceKey, PlaceDetails> written = new LinkedHashMap<>();
        PlaceKey manual = new PlaceKey("minecraft:overworld", PlaceSources.MANUAL, "12");
        PlaceKey station = new PlaceKey("minecraft:the_nether", PlaceSources.MTR, "42");
        written.put(manual, new PlaceDetails(
                "第一行 \"引号\" 反斜杠 \\ 换行\n第二行 月台", List.of("餐饮", "可停车"),
                true, 1712345678901L).normalize());
        written.put(station, new PlaceDetails("车站备注", List.of(), false, 42L).normalize());

        expect("the store writes its file", PlaceStorage.save(file, written)
                && Files.isRegularFile(file));
        expect("and leaves no half-written file beside it", !partialBesideExists(file));

        Map<PlaceKey, PlaceDetails> read = PlaceStorage.load(file);
        expect("two records are written and two come back", read.size() == 2);
        expect("Chinese, a quote, a backslash and a newline survive the round trip",
                written.get(manual).equals(read.get(manual)));
        expect("and a record with no tags or escape in it does too",
                written.get(station).equals(read.get(station)));
    }

    /** A file that was never written, and a null path, both read as "no details". */
    private static void storageDefaults(Path root) {
        expect("a store that has never been written reads as empty",
                PlaceStorage.load(root.resolve("never-written.json")).isEmpty());
        expect("and so does a null path", PlaceStorage.load(null).isEmpty());
    }

    /**
     * The tolerant reading: this is where having a hand-edited file matters, so the cases are written
     * out as the text a player or a failing disk could leave behind.
     */
    private static void storageTolerance(Path root) throws IOException {
        Path file = root.resolve("hand-edited.json");
        write(file, "{\"version\":2,\"editedBy\":\"a later build\",\"details\":["
                + "{\"dimension\":\" minecraft:overworld \",\"source\":\"poi\",\"id\":\"12\","
                + "\"description\":\"  一行笔记\\n第二行  \",\"tags\":[\" cafe \",\"CAFE\",\"\"],"
                + "\"favourite\":true,\"colour\":3},"
                + "{\"dimension\":\"minecraft:overworld\",\"source\":\"mtr_station\",\"id\":\"42\"},"
                + "{\"dimension\":\"\",\"source\":\"poi\",\"id\":\"13\"}]}");
        Map<PlaceKey, PlaceDetails> read = PlaceStorage.load(file);
        PlaceKey key = new PlaceKey("minecraft:overworld", PlaceSources.MANUAL, "12");
        PlaceDetails first = read.get(key);
        expect("a file from a newer build is read rather than refused", read.size() == 2);
        expect("an unknown field is ignored and the known ones are normalised", first != null
                && "一行笔记\n第二行".equals(first.description())
                && first.tags().equals(List.of("cafe"))
                && !first.favorite());
        expect("a record with only a key has the defaults", PlaceDetails.EMPTY.equals(
                read.get(new PlaceKey("minecraft:overworld", PlaceSources.MTR, "42"))));
        expect("an entry whose key is blank is not a record at all",
                !read.containsKey(new PlaceKey("minecraft:overworld", PlaceSources.MANUAL, "13"))
                        && !read.containsKey(new PlaceKey("unknown", PlaceSources.MANUAL, "13")));

        Path duplicates = root.resolve("duplicates.json");
        write(duplicates, "{\"version\":1,\"details\":["
                + "{\"dimension\":\"minecraft:overworld\",\"source\":\"poi\",\"id\":\"7\","
                + "\"description\":\"first\"},"
                + "{\"dimension\":\"minecraft:overworld\",\"source\":\"poi\",\"id\":\"7\","
                + "\"description\":\"second\",\"favorite\":true}]}");
        Map<PlaceKey, PlaceDetails> deduped = PlaceStorage.load(duplicates);
        PlaceKey repeated = new PlaceKey("minecraft:overworld", PlaceSources.MANUAL, "7");
        expect("a repeated key keeps the first record and not the later one",
                deduped.size() == 1 && "first".equals(deduped.get(repeated).description())
                        && !deduped.get(repeated).favorite());

        Path broken = root.resolve("broken.json");
        write(broken, "{ this is not json");
        expect("a file that does not parse reads as no details", PlaceStorage.load(broken).isEmpty());
        expect("and is set aside as .corrupt rather than overwritten",
                !Files.exists(broken)
                        && Files.isRegularFile(broken.resolveSibling("broken.json.corrupt")));
    }

    /**
     * The identity of a place: the five shapes the built-in sources produce, and the derived id an
     * addon's destination falls back to.
     */
    private static void keyMapping(Path root) throws IOException {
        String dim = "minecraft:overworld";
        Destination manual = new Destination("家", 10, 64, 20, PlaceSources.MANUAL,
                Destination.NO_COLOR, PlaceKind.PLACE, String.valueOf(12));
        Destination mtr = new Destination("中央站", 100, 70, -30, PlaceSources.MTR,
                Destination.NO_COLOR, PlaceKind.STATION, String.valueOf(42));
        Destination create = new Destination("货运站", 300, 65, 400, PlaceSources.CREATE,
                Destination.NO_COLOR, PlaceKind.STATION, PlaceKeys.createStationId(300, 400));
        // A waypoint's source ignores the height on purpose: a destination is a column.
        Destination xaero = new Destination("营地", 500, 0, 600, PlaceSources.XAERO,
                Destination.NO_COLOR, PlaceKind.PLACE, PlaceKeys.xaeroId("营地", 500, 600));
        Destination roadWeaver = new Destination("村庄", 700, 80, 800, PlaceSources.ROADWEAVER,
                Destination.NO_COLOR, PlaceKind.PLACE, "minecraft:village_plains");

        expect("the on-disk source ids are unchanged", "poi".equals(PlaceSources.MANUAL)
                && "mtr_station".equals(PlaceSources.MTR)
                && "xaero_waypoint".equals(PlaceSources.XAERO)
                && "create_station".equals(PlaceSources.CREATE)
                && "roadweaver".equals(PlaceSources.ROADWEAVER));
        expect("a manual place is keyed by its node id",
                new PlaceKey(dim, PlaceSources.MANUAL, "12").equals(PlaceKeys.of(dim, manual))
                        && PlaceKeys.of(dim, manual).isManual());
        expect("an MTR station by its own station id",
                new PlaceKey(dim, PlaceSources.MTR, "42").equals(PlaceKeys.of(dim, mtr)));
        expect("a Create station by its column and not its height",
                new PlaceKey(dim, PlaceSources.CREATE, "300,400").equals(PlaceKeys.of(dim, create)));
        expect("an Xaero waypoint by the hash of its name and column",
                new PlaceKey(dim, PlaceSources.XAERO, PlaceKeys.xaeroId("营地", 500, 600))
                        .equals(PlaceKeys.of(dim, xaero)));
        expect("a RoadWeaver structure by its structure id",
                new PlaceKey(dim, PlaceSources.ROADWEAVER, "minecraft:village_plains")
                        .equals(PlaceKeys.of(dim, roadWeaver)));

        // An addon compiled before destinations carried an id: the name and position are all the mod
        // has, and the fallback has to be the same answer in every session or the note is lost.
        Destination addon = new Destination("旧来源", 900, 64, 1000, "some_addon");
        String fallback = PlaceKeys.stableId(addon);
        expect("a source with no id gets a derived one",
                fallback.equals(PlaceKeys.fallbackId("旧来源", 900, 1000))
                        && fallback.startsWith("n"));
        expect("the same one every time it is asked",
                fallback.equals(PlaceKeys.stableId(addon))
                        && fallback.equals(PlaceKeys.fallbackId("旧来源", 900, 1000)));

        Map<PlaceKey, PlaceDetails> saved = new LinkedHashMap<>();
        PlaceKey addonKey = PlaceKeys.of(dim, addon);
        saved.put(addonKey, PlaceDetails.of("旧来源的备注", List.of(), false));
        expect("and the key found after a storage round trip uses it",
                PlaceStorage.save(root.resolve("fallback.json"), saved)
                        && PlaceStorage.load(root.resolve("fallback.json")).containsKey(addonKey));
    }

    /** The deletion rule: only the player's own places, and an addon's id is not one of those. */
    private static void deletePolicy() {
        expect("the player's own places are deletable and manual",
                PlaceSources.deletable(PlaceSources.MANUAL)
                        && PlaceSources.isManual(PlaceSources.MANUAL)
                        && !PlaceSources.isAutomatic(PlaceSources.MANUAL));
        expect("the four automatic sources are neither",
                !PlaceSources.deletable(PlaceSources.MTR)
                        && !PlaceSources.deletable(PlaceSources.XAERO)
                        && !PlaceSources.deletable(PlaceSources.CREATE)
                        && !PlaceSources.deletable(PlaceSources.ROADWEAVER));
        expect("an addon's id is automatic too, and therefore not deletable",
                PlaceSources.isAutomatic("some_addon:places")
                        && !PlaceSources.deletable("some_addon:places"));
        expect("a null source is not the player's own place",
                !PlaceSources.isManual(null) && !PlaceSources.deletable(null));
    }

    /**
     * The directory a player moves between machines, with a details file in it.
     *
     * <p>The details ride along as an ordinary JSON file of the world's directory, so the thing worth
     * holding is that the archive's own rules do not drop it or reorder the count: the two files the
     * directory has are the two files the archive has, the manifest still names the sender, and what
     * comes back through the real reader is the same record.
     */
    private static void archiveRoundTrip(Path root) throws IOException {
        Path source = root.resolve("source");
        Path target = root.resolve("target");
        Path archive = root.resolve("export.zip");
        Files.createDirectories(source);
        Files.createDirectories(target);
        write(source.resolve("minecraft_overworld.json"), "{\"version\":1,\"nodes\":[]}");
        write(source.resolve("notes.txt"), "not map data");
        write(source.resolve(".world"), "sp_sender");

        Map<PlaceKey, PlaceDetails> written = new LinkedHashMap<>();
        PlaceKey key = new PlaceKey("minecraft:overworld", PlaceSources.MANUAL, "1");
        written.put(key, new PlaceDetails("家门口\n第二行 \"引号\"", List.of("家"), true,
                1712345678901L).normalize());
        expect("the details file is written by the real store",
                PlaceStorage.save(source.resolve("place_details.json"), written));

        WorldArchive.Packed packed = WorldArchive.export(source, archive, "sp_sender",
                Instant.now());
        WorldArchive.Contents contents = WorldArchive.inspect(archive);
        expect("the export packs the road network and the details, and nothing else",
                packed.files == 2 && contents.files() == 2);
        expect("and the manifest still names the world it came from",
                contents.manifest != null && "sp_sender".equals(contents.manifest.world));

        WorldArchive.Contents imported = WorldArchive.importFrom(archive, target, "sp_receiver");
        expect("the import lays the same two files down",
                imported.files() == 2
                        && Files.isRegularFile(target.resolve("place_details.json"))
                        && Files.isRegularFile(target.resolve("minecraft_overworld.json")));
        Map<PlaceKey, PlaceDetails> read = PlaceStorage.load(target.resolve("place_details.json"));
        expect("and the details read back equal",
                read.size() == 1 && written.get(key).equals(read.get(key)));
        expect("while the directory records the importing world",
                "sp_receiver".equals(Files.readString(target.resolve(".world"),
                        StandardCharsets.UTF_8).trim()));
    }

    // ------------------------------------------------------------------ helpers

    private static void write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    /** Whether the writer left a {@code .part} file beside the one it replaced. */
    private static boolean partialBesideExists(Path file) throws IOException {
        Path parent = file.getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            return false;
        }
        try (Stream<Path> children = Files.list(parent)) {
            return children.anyMatch(path -> path.getFileName().toString().endsWith(".part"));
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
