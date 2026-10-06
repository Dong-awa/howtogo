package bili.dongsz.howtogo.client;

/**
 * The file name an addon's own data is given, and what it cannot be made to do.
 *
 * <h2>Why this is worth a check of its own</h2>
 * {@code HowToGoApi.dataFile(suffix)} hands another mod a path built from whatever it passed, and the
 * suffix is not this mod's to trust -- not because an addon is hostile, but because a suffix built
 * from a config value or a world name can contain a separator by accident. The rule is that a suffix
 * may lengthen the file name and may not move the file out of the world's directory.
 *
 * <p>The other half is the reason the sanitising is a method of its own rather than a call to the
 * existing one: the mod's own road network passes an empty suffix, and the sanitising that turns a
 * blank name into "unknown" would have turned its file into {@code unknownownknown.json}. An empty
 * suffix is not a missing one here; it is the oldest file this mod has, and this check is what keeps
 * a later tidy-up from turning every player's roads into a new file.
 *
 * <p>{@code WorldFiles.of} itself needs a running game -- it asks Minecraft which world and which
 * dimension are open -- so what is asserted here is the part that is pure.
 */
public final class AddonDataFileCheck {

    private static int checks;
    private static int failures;

    private AddonDataFileCheck() {
    }

    public static int[] run() {
        System.out.println("== the path an addon's data is given ==");

        expect("the road network's empty suffix stays empty, because that is the oldest file the mod has",
                WorldFiles.safeSuffix("").isEmpty());
        expect("a missing suffix is empty rather than a name",
                WorldFiles.safeSuffix(null).isEmpty());
        expect("a suffix the mod itself uses is left exactly as it is",
                "-lines".equals(WorldFiles.safeSuffix("-lines")));
        expect("letters, digits, dots, dashes and underscores all survive",
                "-my_data.v2".equals(WorldFiles.safeSuffix("-my_data.v2")));

        expect("a path separator is replaced rather than followed",
                !WorldFiles.safeSuffix("../../evil").contains("/"));
        expect("and so is a backslash",
                !WorldFiles.safeSuffix("..\\..\\evil").contains("\\"));
        expect("parent-directory dots are left, but they mean nothing without a separator",
                !WorldFiles.safeSuffix("..").contains("/")
                        && !WorldFiles.safeSuffix("..").contains("\\"));
        expect("a drive letter cannot survive into the name",
                !WorldFiles.safeSuffix("C:\\evil").contains(":"));
        expect("a space is replaced, since nothing here quotes the rest of the path",
                "my_data".equals(WorldFiles.safeSuffix("my data")));
        expect("a newline cannot start a new line in a file name either",
                !WorldFiles.safeSuffix("a\nb").contains("\n"));
        expect("and neither can a NUL character",
                !WorldFiles.safeSuffix("a\0b").contains("\0"));

        System.out.println(failures == 0
                ? "addon data path ok (" + checks + " checks)"
                : "addon data path FAILED: " + failures + " of " + checks + " checks");
        return new int[]{checks, failures};
    }

    private static void expect(String what, boolean ok) {
        checks++;
        if (!ok) {
            failures++;
        }
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
    }
}
