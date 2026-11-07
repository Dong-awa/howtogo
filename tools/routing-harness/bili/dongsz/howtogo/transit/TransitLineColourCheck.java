package bili.dongsz.howtogo.transit;

import bili.dongsz.howtogo.road.ColorHex;
import bili.dongsz.howtogo.road.RoadClass;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The colour a transit line carries: the value object's rules, the file that keeps it, and the
 * palette the editor offers.
 *
 * <h2>Why this is checked off the game</h2>
 * A colour is written as text into a hand-editable file and typed into a small field, and nothing on
 * screen says which characters were dropped, which were refused or what a pasted alpha did to them.
 * Every question here is a pure one -- an ARGB value on a line, a JSON round trip on temporary files,
 * eight hex strings -- so the whole check runs without a game and without a client, which is also the
 * only way a colour that parses in the editor and not in the file can be caught before a player's
 * line comes back the wrong colour.
 *
 * <h2>What is held</h2>
 * The line's own rules: null means "no colour of its own", {@code colorOr} falls back, the alpha is
 * always opaque, and black is a colour rather than a sentinel. The storage's: the six-digit text round
 * trips, a line without a colour writes no field at all, an old file with no field reads as no colour,
 * a value that is not a colour reads as no colour rather than failing the file, and the version stays
 * where it was because the field is optional. And the palette: eight presets, every one a colour
 * {@link ColorHex} reads, no two the same, so a swatch cannot mean one thing on screen and another in
 * the file.
 */
public final class TransitLineColourCheck {

    private static int checks;
    private static int failures;

    private TransitLineColourCheck() {
    }

    /**
     * Runs the checks.
     *
     * @return the number of checks made and the number that failed, in that order
     */
    public static int[] run() {
        checks = 0;
        failures = 0;
        System.out.println("== transit line colours ==");
        Path root = null;
        try {
            root = Files.createTempDirectory("howtogo-line-colours");
            lineValues();
            presets();
            storageRoundTrip(root.resolve("round-trip"));
            storageWithoutColour(root.resolve("no-colour"));
            storageTolerance(root.resolve("tolerance"));
        } catch (IOException failed) {
            expect("the temporary directories could be used (" + failed + ")", false);
        } finally {
            deleteTree(root);
        }
        System.out.println(failures == 0
                ? "  transit line colours ok (" + checks + " checks)"
                : "  transit line colours FAILED: " + failures + " of " + checks);
        return new int[]{checks, failures};
    }

    /**
     * The line's own colour API, at its own edges.
     *
     * <p>Three things here are easy to get subtly wrong and invisible from the game. A line without a
     * colour must answer with null rather than a sentinel, because black is a colour a player may pick.
     * The alpha has to be forced opaque, because a colour typed into a field or read out of another
     * mod is not protected against being transparent, and a transparent line is a line that is not
     * there. And a cleared colour has to go back to falling back rather than to some remembered value.
     */
    private static void lineValues() {
        int fallback = 0xFF8AB4FF;
        TransitLine line = new TransitLine("a", "A", RoadClass.RAIL);

        expect("a new line has no colour of its own",
                !line.hasColor() && line.color() == null && line.colorHex().isEmpty());
        expect("and asks its caller's fallback for the colour to draw",
                line.colorOr(fallback) == fallback);

        line.setColor(0x1A73E8);
        expect("a colour set on the line is kept with an opaque alpha",
                line.hasColor() && line.color() == 0xFF1A73E8);
        expect("and written as the six digits the file and the field use",
                "#1A73E8".equals(line.colorHex()));
        expect("with the line's own colour beating the fallback", line.colorOr(fallback) == 0xFF1A73E8);

        // Six and eight digits, with and without the marker, are all the one colour: the parser the
        // field uses is the parser the file uses, so a value accepted in one is the value in the other.
        line.setColor(ColorHex.parseOrNull("#1e88e5"));
        expect("a colour from the field's own parser is the one stored",
                line.color() == 0xFF1E88E5 && "#1E88E5".equals(line.colorHex()));
        line.setColor(ColorHex.parseOrNull("0x1E88E5"));
        expect("and the same goes for the other spellings of the same colour",
                line.color() == 0xFF1E88E5);

        // Alpha is the line's to assume, not the caller's to leave out: each of these is the same
        // colour, and none of them may come out transparent.
        line.setColor(0x001A73E8);
        expect("a colour with no alpha is made opaque", line.color() == 0xFF1A73E8);
        line.setColor(0x7F1A73E8);
        expect("and one with a half alpha is made opaque too", line.color() == 0xFF1A73E8);

        line.setColor(0x000000);
        expect("black is a colour rather than 'unset'",
                line.hasColor() && line.color() == 0xFF000000
                        && "#000000".equals(line.colorHex()));

        line.setColor(null);
        expect("clearing the colour makes the line follow the fallback again",
                !line.hasColor() && line.colorOr(fallback) == fallback);

        // A copy is a line of its own: the editor's copy button and the cancelled-edit path both rely
        // on the colour travelling with the copy and on the copy not writing back to the original.
        TransitLine original = new TransitLine("b", "B", RoadClass.RAIL);
        original.addStop(LineStop.ofStation("one", 0, 0));
        original.setColor(0xE53935);
        TransitLine copy = original.copy();
        expect("a copy carries the original's colour",
                copy.hasColor() && copy.color() == 0xFFE53935);
        copy.setColor(0x43A047);
        expect("and changing the copy does not change the original",
                original.color() == 0xFFE53935 && copy.color() == 0xFF43A047);
    }

    /**
     * The presets: eight colours, every one of them text the field accepts.
     *
     * <p>The screen draws these as swatches, so a preset that the parser refused would simply be a
     * missing swatch -- or, worse, a swatch drawn in a colour the line would not get. Checking them
     * through {@link ColorHex} is checking the one thing the editor and the line actually rely on.
     */
    private static void presets() {
        List<String> hexes = TransitLineColours.presetHexes();
        List<Integer> parsed = TransitLineColours.presets();
        expect("eight presets are offered", hexes.size() == 8 && parsed.size() == 8);

        Set<Integer> distinct = new HashSet<>();
        boolean allParse = true;
        boolean allOpaque = true;
        for (String hex : hexes) {
            Integer colour = ColorHex.parseOrNull(hex);
            if (colour == null) {
                allParse = false;
                continue;
            }
            allOpaque &= (colour & 0xFF000000) == 0xFF000000;
            distinct.add(colour);
        }
        expect("every preset is text the field's parser reads", allParse);
        expect("every preset is opaque, so a swatch cannot be invisible", allOpaque);
        expect("and no two presets are the same colour", distinct.size() == 8);
        expect("the parsed presets are the same eight numbers as the text",
                parsed.size() == 8 && new HashSet<>(parsed).equals(distinct));
        expect("with the six-digit spelling the file writes",
                hexes.stream().allMatch(hex -> hex.matches("#[0-9A-F]{6}")));
    }

    /** A colour on a line survives the JSON file, written as the six-digit text it is stored as. */
    private static void storageRoundTrip(Path dir) throws IOException {
        Path file = dir.resolve("lines.json");
        TransitLine line = new TransitLine("line-1", "Red line", RoadClass.RAIL);
        line.addStop(LineStop.ofStation("Alpha", 0, 0));
        line.addStop(LineStop.ofStation("Beta", 100, 0));
        line.setColor(0x1A73E8);
        TransitLine colourless = new TransitLine("line-2", "Plain", RoadClass.WATER);
        colourless.addStop(LineStop.ofStation("Gamma", 200, 0));

        expect("the file is written", TransitLineStorage.save(file, List.of(line, colourless)));
        String text = Files.readString(file, StandardCharsets.UTF_8);
        expect("the colour is written as the six-digit text a player may edit",
                text.contains("\"color\": \"#1A73E8\""));
        expect("and a line without one writes no colour field at all",
                occurrences(text, "\"color\"") == 1);
        expect("the format version is unchanged, because the field is optional",
                TransitLineStorage.FORMAT_VERSION == 1 && text.contains("\"version\": 1"));

        List<TransitLine> loaded = TransitLineStorage.load(file);
        expect("the file reads back as the same two lines", loaded.size() == 2);
        TransitLine read = loaded.get(0);
        expect("with the colour intact and still opaque",
                read.hasColor() && read.color() == 0xFF1A73E8 && "#1A73E8".equals(read.colorHex()));
        expect("the kind and the stops are untouched by the colour",
                read.kind() == RoadClass.RAIL && read.stopCount() == 2);
        expect("and the line that had none still has none", !loaded.get(1).hasColor());
    }

    /** A line with no colour of its own is the state every line had before colours existed. */
    private static void storageWithoutColour(Path dir) throws IOException {
        Path file = dir.resolve("lines.json");
        TransitLine line = new TransitLine("line-3", "Plain", RoadClass.ROAD);
        line.addStop(LineStop.ofStation("Alpha", 0, 0));
        TransitLineStorage.save(file, List.of(line));
        String text = Files.readString(file, StandardCharsets.UTF_8);
        expect("a line without a colour writes no colour field", !text.contains("\"color\""));

        List<TransitLine> loaded = TransitLineStorage.load(file);
        expect("and loads as a line with none", loaded.size() == 1 && !loaded.get(0).hasColor());
    }

    /**
     * The tolerant half: a file somebody wrote by hand.
     *
     * <p>A colour is text in a file a player can open, so it will be typed, pasted and mistyped. The
     * rule the storage states is that a value that is not a colour means "no colour of this line",
     * exactly as an absent field does -- not a failure of the file, and never a colour guessed at from
     * the part that did parse. The versions the reader has to accept are the hand-written one with no
     * version at all, the eight-digit spelling, and a file from before lines had colours.
     */
    private static void storageTolerance(Path dir) throws IOException {
        Path handWritten = dir.resolve("hand-written.json");
        Files.createDirectories(handWritten.getParent());
        Files.writeString(handWritten, "{\n"
                + "  \"lines\": [\n"
                + "    {\"id\": \"one\", \"name\": \"Azure\", \"kind\": \"RAIL\", \"color\": \"#1A73E8\",\n"
                + "     \"stops\": [{\"name\": \"Alpha\", \"x\": 0, \"z\": 0}]},\n"
                + "    {\"id\": \"two\", \"name\": \"Eight digit\", \"kind\": \"RAIL\",\n"
                + "     \"color\": \"#FF1A73E8\", \"stops\": []},\n"
                + "    {\"id\": \"three\", \"name\": \"Not a colour\", \"kind\": \"RAIL\",\n"
                + "     \"color\": \"not-a-colour\", \"stops\": []},\n"
                + "    {\"id\": \"four\", \"name\": \"Old file\", \"kind\": \"WATER\",\n"
                + "     \"stops\": []}\n"
                + "  ]\n"
                + "}\n", StandardCharsets.UTF_8);

        List<TransitLine> loaded = TransitLineStorage.load(handWritten);
        expect("a hand-written file with no version loads", loaded.size() == 4);
        expect("a hand-written #RRGGBB is the colour of the line",
                loaded.get(0).hasColor() && loaded.get(0).color() == 0xFF1A73E8);
        expect("the eight-digit spelling is accepted and made the same opaque colour",
                loaded.get(1).hasColor() && loaded.get(1).color() == 0xFF1A73E8);
        expect("a value that is not a colour is no colour rather than an error",
                !loaded.get(2).hasColor());
        expect("and a line written before colours existed still has none",
                !loaded.get(3).hasColor() && loaded.get(3).kind() == RoadClass.WATER);
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
    private static void deleteTree(Path root) {        if (root == null) {
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
