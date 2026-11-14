package bili.dongsz.howtogo.transit;

import bili.dongsz.howtogo.road.RoadClass;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * The direction a transit line is ridden in: the two states, the line that carries one, and the file
 * that keeps it.
 *
 * <h2>Why this is checked off the game</h2>
 * A direction is two words, and almost all of what can go wrong with it is invisible on screen: a line
 * that quietly came back from its file two-way looks exactly like a line that was never marked one-way,
 * and a copy that dropped the restriction is a second line the player believes is the same. Every
 * question here is a pure one -- an enum, a field on a line, a JSON round trip on temporary files -- so
 * the whole check runs without a game, and the one part that is not pure, whether the planner refuses
 * to ride a one-way line backwards, is checked in {@code Harness} where a network can be built.
 *
 * <h2>What is held</h2>
 * The states: two-way is the default, one-way is the restriction, an id round-trips and an unknown word
 * reads as two-way, which is the only answer that cannot invent a restriction nobody asked for. The
 * line: it is two-way when built, carries what it is told, is cleared back to two-way by a null, and a
 * copy keeps the direction without becoming the same object. And the storage: the direction is written
 * as its id, a one-way line comes back one-way, a file written before directions existed reads as
 * two-way, and the format version stays where it is because the field is optional.
 */
public final class LineDirectionCheck {

    private static int checks;
    private static int failures;

    private LineDirectionCheck() {
    }

    /**
     * Runs the checks.
     *
     * @return the number of checks made and the number that failed, in that order
     */
    public static int[] run() {
        checks = 0;
        failures = 0;
        System.out.println("== line direction ==");
        Path root = null;
        try {
            root = Files.createTempDirectory("howtogo-line-direction");
            states();
            theLineCarriesIt();
            storageRoundTrip(root.resolve("round-trip"));
            storageTolerance(root.resolve("tolerance"));
        } catch (IOException failed) {
            expect("the temporary directories could be used (" + failed + ")", false);
        } finally {
            deleteTree(root);
        }
        System.out.println(failures == 0
                ? "  line direction ok (" + checks + " checks)"
                : "  line direction FAILED: " + failures + " of " + checks);
        return new int[]{checks, failures};
    }

    /**
     * The two states, and the word-to-state mapping the file rests on.
     *
     * <p>The fallback is the important half: a word this build does not know is a file written by a
     * later one, and reading it as two-way loses a restriction rather than inventing one in a
     * direction nobody chose -- the same rule {@code RoadDirection} states for a road.
     */
    private static void states() {
        expect("two-way is not one-way", !LineDirection.TWO_WAY.isOneWay());
        expect("one-way is one-way", LineDirection.ONE_WAY.isOneWay());
        expect("the id is the file's spelling of it", "one_way".equals(LineDirection.ONE_WAY.id()));
        expect("and an id round-trips", LineDirection.byId("one_way") == LineDirection.ONE_WAY);
        expect("a word this version does not know reads as two-way, "
                        + "which is the one answer that cannot invent a direction",
                LineDirection.byId("clockwise") == LineDirection.TWO_WAY);
        expect("as does nothing at all", LineDirection.byId(null) == LineDirection.TWO_WAY);
    }

    /**
     * The line's own direction API, at its own edges.
     *
     * <p>A line is two-way when built, which is what every line was before this existed: a new line, an
     * imported line whose reading does not say, and a file that does not mention a direction are all
     * the same answer. The direction then travels with the line the way its colour does -- through the
     * copy the editor's duplicate button and the cancelled edit both use -- and setting it does not
     * touch the stops, because the order is the direction rather than something derived from it.
     */
    private static void theLineCarriesIt() {
        TransitLine line = new TransitLine("a", "A", RoadClass.RAIL);
        line.addStop(LineStop.ofStation("Alpha", 0, 0));
        line.addStop(LineStop.ofStation("Beta", 200, 0));
        line.addStop(LineStop.ofStation("Gamma", 400, 0));

        expect("a new line is two-way until it is told otherwise",
                !line.oneWay() && line.direction() == LineDirection.TWO_WAY);

        line.setDirection(LineDirection.ONE_WAY);
        expect("and one-way once it is", line.oneWay() && line.direction() == LineDirection.ONE_WAY);
        expect("which changes no stop and no kind",
                line.stopCount() == 3 && line.kind() == RoadClass.RAIL);

        line.setDirection(null);
        expect("a null direction clears it back to two-way", !line.oneWay());

        TransitLine loop = new TransitLine("b", "Loop", RoadClass.RAIL);
        loop.addStop(LineStop.ofStation("One", 0, 0));
        loop.addStop(LineStop.ofStation("Two", 300, 0));
        loop.setDirection(LineDirection.ONE_WAY);
        TransitLine copy = loop.copy();
        expect("a copy carries the original's direction", copy.oneWay());
        copy.setDirection(LineDirection.TWO_WAY);
        expect("and changing the copy does not change the original",
                loop.oneWay() && !copy.oneWay());
    }

    /** A direction survives the JSON file, written as its own id rather than as a boolean. */
    private static void storageRoundTrip(Path dir) throws IOException {
        Path file = dir.resolve("lines.json");
        TransitLine loop = new TransitLine("loop", "One-way loop", RoadClass.RAIL);
        loop.addStop(LineStop.ofStation("Alpha", 0, 0));
        loop.addStop(LineStop.ofStation("Beta", 300, 0));
        loop.setDirection(LineDirection.ONE_WAY);
        TransitLine normal = new TransitLine("normal", "Both ways", RoadClass.ROAD);
        normal.addStop(LineStop.ofStation("Gamma", 0, 100));
        normal.addStop(LineStop.ofStation("Delta", 300, 100));

        expect("the file is written", TransitLineStorage.save(file, List.of(loop, normal)));
        String text = Files.readString(file, StandardCharsets.UTF_8);
        expect("the direction is written as its id, so a hand edit is a word rather than a number",
                text.contains("\"direction\": \"one_way\""));
        expect("a two-way line says so too, rather than leaving the reader to infer it",
                occurrences(text, "\"direction\"") == 2);
        expect("the format version is unchanged, because the field is optional",
                TransitLineStorage.FORMAT_VERSION == 1 && text.contains("\"version\": 1"));

        List<TransitLine> loaded = TransitLineStorage.load(file);
        expect("the file reads back as the same two lines", loaded.size() == 2);
        expect("the one-way line comes back one-way",
                loaded.get(0).oneWay() && loaded.get(0).stopCount() == 2);
        expect("and the two-way line comes back two-way", !loaded.get(1).oneWay());
    }

    /**
     * The tolerant half: a file somebody wrote by hand, and a file written before this existed.
     *
     * <p>The rule the storage states is that an absent or unreadable direction means two-way, exactly
     * as an absent colour means "no colour of this line" -- not a failure of the file, and never a
     * restriction guessed at from a word that did not parse.
     */
    private static void storageTolerance(Path dir) throws IOException {
        Path handWritten = dir.resolve("hand-written.json");
        Files.createDirectories(handWritten.getParent());
        Files.writeString(handWritten, "{\n"
                + "  \"lines\": [\n"
                + "    {\"id\": \"one\", \"name\": \"One way\", \"kind\": \"RAIL\", "
                + "\"direction\": \"one_way\",\n"
                + "     \"stops\": [{\"name\": \"Alpha\", \"x\": 0, \"z\": 0},"
                + " {\"name\": \"Beta\", \"x\": 300, \"z\": 0}]},\n"
                + "    {\"id\": \"two\", \"name\": \"Not a direction\", \"kind\": \"RAIL\",\n"
                + "     \"direction\": \"sideways\", \"stops\": []},\n"
                + "    {\"id\": \"three\", \"name\": \"Old file\", \"kind\": \"WATER\",\n"
                + "     \"stops\": []}\n"
                + "  ]\n"
                + "}\n", StandardCharsets.UTF_8);

        List<TransitLine> loaded = TransitLineStorage.load(handWritten);
        expect("a hand-written file with no version loads", loaded.size() == 3);
        expect("a hand-written one_way is a one-way line",
                loaded.get(0).oneWay() && loaded.get(0).stopCount() == 2);
        expect("a word that is not a direction is two-way rather than an error",
                !loaded.get(1).oneWay());
        expect("and a line written before directions existed is two-way",
                !loaded.get(2).oneWay() && loaded.get(2).kind() == RoadClass.WATER);
    }

    /** How many times a piece of text appears in another, for counting optional JSON fields. */
    private static int occurrences(String text, String needle) {
        int found = 0;
        int from = 0;
        while ((from = text.indexOf(needle, from)) >= 0) {
            found++;
            from += needle.length();
        }
        return found;
    }

    /** Removes a temporary tree, deepest first, and says nothing when there was nothing to remove. */
    private static void deleteTree(Path root) {
        if (root == null) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            List<Path> all = new ArrayList<>(paths.toList());
            all.sort(Comparator.reverseOrder());
            for (Path path : all) {
                Files.deleteIfExists(path);
            }
        } catch (IOException ignored) {
            // A temporary directory left behind is not a failed check.
        }
    }

    private static void expect(String what, boolean condition) {
        checks++;
        if (!condition) {
            failures++;
        }
        System.out.println((condition ? "  ok   " : "  FAIL ") + what);
    }
}
