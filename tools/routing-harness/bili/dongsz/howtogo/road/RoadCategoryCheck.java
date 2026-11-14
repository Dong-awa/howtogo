package bili.dongsz.howtogo.road;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Checks the road classification: the category reference type, the segment fields it drives, the
 * storage round trip, and the one-panel edit the road editor commits.
 *
 * <h2>Why this exists</h2>
 * A category is now a reference rather than a fixed table entry -- the player makes, renames and
 * deletes categories, and a road only carries an id -- so the failures that do not shout are about
 * the edges of that split: an id that no longer names anything must not become an error in a save
 * file, a road must not wear a sign without a category, a re-class must not carry a category from
 * the wrong class along, and a save must carry the id back unchanged. None of those are visible on a
 * healthy map, so they are checked here.
 *
 * <h2>What cannot be checked here</h2>
 * The store itself: names and colours are client state read through Minecraft's text components, and
 * the category editor and the sign cards are drawing code. What this covers is every value that
 * drawing and editing read, plus the file they end up in.
 */
public final class RoadCategoryCheck {

    private static int checks;
    private static int failures;

    private RoadCategoryCheck() {
    }

    /**
     * Runs the checks.
     *
     * @return the number of checks made and the number that failed, in that order
     */
    public static int[] run() {
        checks = 0;
        failures = 0;
        System.out.println("== road categories ==");

        references();
        segments();
        storage();
        editor();
        return new int[]{checks, failures};
    }

    // ------------------------------------------------------------ the reference

    /**
     * What a category value is: a class plus an opaque id, with the built-in one per class.
     *
     * <p>These are the assumptions the rest of the mod makes without asking: a new road starts on its
     * class's built-in category, an id the store has never heard of is still a value that can be
     * carried and saved (so a save is never rejected over one), and the same id under two classes is
     * two different categories.
     */
    private static void references() {
        for (RoadClass roadClass : RoadClass.values()) {
            RoadCategory none = RoadCategory.none(roadClass);
            expect(roadClass + " has a not-classified category",
                    none != null && none.isNone() && none.roadClass() == roadClass
                            && RoadCategory.NONE_ID.equals(none.id()));
            expect(roadClass + "'s not-classified category reads back from its own id",
                    RoadCategory.of(roadClass, "none").equals(none)
                            && RoadCategory.resolve(roadClass, "  none  ").equals(none));
        }

        expect("a missing id is the class's not-classified category",
                RoadCategory.of(RoadClass.ROAD, null).equals(RoadCategory.none(RoadClass.ROAD))
                        && RoadCategory.of(RoadClass.ROAD, "  ")
                        .equals(RoadCategory.none(RoadClass.ROAD)));
        expect("no category belongs to no class at all -- a null class still gets a real value",
                RoadCategory.of(null, null) != null && RoadCategory.of(null, null).isNone());

        RoadCategory first = RoadCategory.of(RoadClass.ROAD, "c1");
        expect("a player-made id is carried as written, not interpreted",
                !first.isNone() && "c1".equals(first.id())
                        && first.roadClass() == RoadClass.ROAD);
        expect("the same class and id are the same category",
                first.equals(RoadCategory.of(RoadClass.ROAD, "c1")));
        expect("the same id under another class is a different category",
                !first.equals(RoadCategory.of(RoadClass.HIGHWAY, "c1")));
        expect("an id the store has never heard of is still a value, so a save is never rejected",
                RoadCategory.resolve(RoadClass.PATH, "gone").id().equals("gone"));
        expect("categories hash by class and id, which is what map lookups need",
                first.hashCode() == RoadCategory.of(RoadClass.ROAD, "c1").hashCode());
    }

    // ------------------------------------------------------------- the segment

    /** A road defaults to not classified, and a re-class cannot carry another class's category. */
    private static void segments() {
        RoadSegment segment = new RoadSegment(1, RoadClass.ROAD, 64, 2);
        expect("a new road is not classified",
                segment.category().equals(RoadCategory.none(RoadClass.ROAD)));
        expect("and has no number, so it has no sign",
                segment.number() == null && !segment.hasSign());

        segment.setCategory(RoadCategory.of(RoadClass.ROAD, "c1"));
        expect("a road of the right class can be classified",
                segment.category().id().equals("c1"));
        segment.setCategory(RoadCategory.of(RoadClass.HIGHWAY, "c1"));
        expect("a category from another class is refused, not allowed to mislabel the road",
                segment.category().equals(RoadCategory.none(RoadClass.ROAD)));

        // A number only becomes a sign under a real category: that is what switching a road back to
        // 未分类 relies on to remove its plate.
        segment.setNumber("G318");
        expect("a number on an unclassified road is not a sign",
                !segment.hasSign() && "G318".equals(segment.number()));
        segment.setCategory(RoadCategory.of(RoadClass.ROAD, "c2"));
        expect("the same road with a category wears the sign", segment.hasSign());
        segment.setCategory(RoadCategory.none(RoadClass.ROAD));
        expect("and switching it back to unclassified takes the sign away again",
                !segment.hasSign());

        segment.setCategory(RoadCategory.of(RoadClass.ROAD, "c3"));
        segment.setRoadClass(RoadClass.HIGHWAY);
        expect("re-classing moves the category off the old class",
                segment.category().equals(RoadCategory.none(RoadClass.HIGHWAY)));
        expect("while the number survives re-classing, because it names the road",
                "G318".equals(segment.number()));

        RoadSegment copy = segment.copy();
        expect("a copy carries the category and the number, which is what undo keeps",
                copy.category().equals(segment.category())
                        && Objects.equals(copy.number(), segment.number()));
    }

    // ------------------------------------------------------------- the file

    /**
     * The save file carries the category id and the number back, and files that have neither -- or
     * that name a category the player deleted -- still load as roads rather than as errors.
     */
    private static void storage() {
        RoadNetwork net = new RoadNetwork();
        RoadSegment national = road(net, RoadClass.ROAD, 0, 0, 100, 0);
        national.setCategory(RoadCategory.of(RoadClass.ROAD, "c7"));
        national.setNumber("G318");
        RoadSegment trail = road(net, RoadClass.PATH, 0, 20, 100, 20);
        trail.setCategory(RoadCategory.of(RoadClass.PATH, "c1"));
        trail.setNumber("3");
        RoadSegment plain = road(net, RoadClass.HIGHWAY, 0, 40, 100, 40);

        Path file = null;
        Path legacy = null;
        Path handEdited = null;
        try {
            file = Files.createTempFile("howtogo-category", ".json");
            expect("a classified network can be saved", RoadStorage.save(file, net));
            RoadNetwork read = RoadStorage.load(file);
            RoadSegment readNational = read.segment(national.id());
            expect("the classified road comes back with its category id and number",
                    readNational != null
                            && readNational.category().equals(national.category())
                            && "G318".equals(readNational.number()));
            RoadSegment readTrail = read.segment(trail.id());
            expect("and the other class's road with its own",
                    readTrail != null
                            && readTrail.category().equals(trail.category())
                            && "3".equals(readTrail.number()));
            RoadSegment readPlain = read.segment(plain.id());
            expect("while the road nobody classified comes back unclassified and unnumbered",
                    readPlain != null && readPlain.category().isNone()
                            && readPlain.number() == null);

            // A file from before categories existed: the fields are absent entirely.
            legacy = Files.createTempFile("howtogo-category-legacy", ".json");
            String json = "{\"version\":1,\"nodes\":["
                    + "{\"id\":1,\"x\":0,\"y\":64,\"z\":0,\"type\":\"JUNCTION\"},"
                    + "{\"id\":2,\"x\":100,\"y\":64,\"z\":0,\"type\":\"ENDPOINT\"}],\"segments\":["
                    + "{\"id\":10,\"roadClass\":\"ROAD\",\"from\":1,\"to\":2,\"y\":64,"
                    + "\"xs\":[0,100],\"zs\":[0,0]}]}";
            Files.write(legacy, json.getBytes(StandardCharsets.UTF_8));
            RoadSegment old = RoadStorage.load(legacy).segment(10);
            expect("a file without the fields loads the road unclassified, not as an error",
                    old != null && old.category().equals(RoadCategory.none(RoadClass.ROAD))
                            && old.number() == null);

            // A file naming a category that no longer exists: the reference is kept, not rejected --
            // the store is what decides such a road looks unclassified, and it may come back if the
            // player re-creates the category.
            handEdited = Files.createTempFile("howtogo-category-hand", ".json");
            String ghost = "{\"version\":1,\"nodes\":["
                    + "{\"id\":1,\"x\":0,\"y\":64,\"z\":0,\"type\":\"JUNCTION\"},"
                    + "{\"id\":2,\"x\":100,\"y\":64,\"z\":0,\"type\":\"ENDPOINT\"}],\"segments\":["
                    + "{\"id\":10,\"roadClass\":\"PATH\",\"category\":\"deleted\","
                    + "\"number\":\"9\",\"from\":1,\"to\":2,\"y\":64,"
                    + "\"xs\":[0,100],\"zs\":[0,0]}]}";
            Files.write(handEdited, ghost.getBytes(StandardCharsets.UTF_8));
            RoadSegment missing = RoadStorage.load(handEdited).segment(10);
            expect("a file naming a category the store may not have keeps the reference readable",
                    missing != null && "deleted".equals(missing.category().id())
                            && !missing.category().isNone());
            expect("and its number is still read, since a number is not class-specific",
                    missing != null && "9".equals(missing.number()));
        } catch (IOException failed) {
            expect("the storage check could run (" + failed.getMessage() + ")", false);
        } finally {
            deleteQuietly(file);
            deleteQuietly(legacy);
            deleteQuietly(handEdited);
        }
    }

    // ------------------------------------------------------------- the editor

    /**
     * The panel's one edit writes name, storey, category and number onto the whole road, as one undo
     * step -- and an unclassified edit clears the number, which is what stops a road that has been
     * put back to 未分类 from keeping a plate nobody can see.
     */
    private static void editor() {
        RoadNetwork net = new RoadNetwork();
        List<RoadSegment> street = List.of(
                road(net, RoadClass.ROAD, 0, 0, 100, 0),
                road(net, RoadClass.ROAD, 100, 0, 100, 100),
                road(net, RoadClass.ROAD, 100, 100, 200, 100));
        RoadEditor editor = new RoadEditor(net);
        int middle = street.get(1).id();

        editor.selectSegment(middle);
        RoadCategory category = RoadCategory.of(RoadClass.ROAD, "c5");
        expect("the panel's edit is accepted",
                editor.setRoadDetails(middle, "Main Street", 2, category, "S12"));
        boolean whole = true;
        for (int id : editor.selectedChain()) {
            RoadSegment segment = net.segment(id);
            whole &= segment != null
                    && "Main Street".equals(segment.name())
                    && segment.layer() == 2
                    && segment.category().equals(category)
                    && "S12".equals(segment.number())
                    && segment.hasSign();
        }
        expect("every piece of the road got the name, storey, category and number", whole);
        expect("the storey the panel set is the one the next road is drawn on",
                editor.activeLayer() == 2);

        editor.undo();
        boolean restored = true;
        for (RoadSegment original : street) {
            RoadSegment back = net.segment(original.id());
            restored &= back != null
                    && back.name() == null
                    && back.layer() == 0
                    && back.category().isNone()
                    && back.number() == null;
        }
        expect("one undo takes all four values back", restored);

        // The panel passes a blank number when the chosen category is the built-in one; the segment
        // turns that into "no number", so the unclassified road has nothing a sign could print.
        editor.selectSegment(middle);
        editor.setRoadDetails(middle, "Main Street", 0, category, "S12");
        editor.setRoadDetails(middle, "Main Street", 0, RoadCategory.none(RoadClass.ROAD), null);
        boolean cleared = true;
        for (int id : editor.selectedChain()) {
            RoadSegment segment = net.segment(id);
            cleared &= segment != null
                    && segment.category().isNone()
                    && segment.number() == null
                    && !segment.hasSign();
        }
        expect("an unclassified edit clears the number and the sign", cleared);
        editor.undo();
        boolean backToClassified = true;
        for (int id : editor.selectedChain()) {
            RoadSegment segment = net.segment(id);
            backToClassified &= segment != null
                    && segment.category().equals(category)
                    && "S12".equals(segment.number())
                    && segment.hasSign();
        }
        expect("one undo brings the classified edit back", backToClassified);
        editor.undo();

        // A category from another class is clamped by every segment it is written to.
        editor.selectSegment(middle);
        editor.setRoadDetails(middle, "Main Street", 0,
                RoadCategory.of(RoadClass.HIGHWAY, "c5"), "G1");
        boolean clamped = true;
        for (RoadSegment segment : net.segments()) {
            clamped &= segment.category().isNone() && segment.roadClass() == RoadClass.ROAD;
        }
        expect("a category from another class lands on the road's own not-classified", clamped);
    }

    // ----------------------------------------------------------------- helpers

    /** One road segment between two new nodes, which is what the editor writes for one click pair. */
    private static RoadSegment road(RoadNetwork net, RoadClass roadClass, int x1, int z1, int x2,
                                    int z2) {
        RoadSegment segment = net.newSegment(roadClass, 64, 2);
        segment.addVertex(x1, z1);
        segment.addVertex(x2, z2);
        segment.setFromNode(net.addNode(x1, 64, z1, RoadNode.Type.JUNCTION, null).id());
        segment.setToNode(net.addNode(x2, 64, z2, RoadNode.Type.JUNCTION, null).id());
        net.addSegment(segment);
        return segment;
    }

    private static void deleteQuietly(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            System.out.println("  note could not remove " + file);
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
