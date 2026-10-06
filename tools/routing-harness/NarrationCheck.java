package bili.dongsz.howtogo.client;

/**
 * Checks how long an announcement is held before anything else may be said over it.
 *
 * <p>The defect this was written after: picking a destination spoke "问道地图为您导航" and the road
 * notice in the same breath, heard as one jumbled sentence. The cause is in the speech engine rather
 * than in the words -- it is handed a phrase without being waited for and purges whatever is still
 * being said when the next one arrives -- so the cure is to leave the opening line alone for about as
 * long as it takes, and the length has to be worked out from the text. See
 * {@link Narration#spokenTicks}.
 *
 * <p>Which is exactly the kind of thing that cannot be checked in the game: a hold that is too short
 * sounds like the bug it exists to fix, and a hold that is too long sounds like guidance arriving
 * late. So the arithmetic is pinned here, on the properties that matter rather than on the numbers
 * themselves -- long enough to be heard, short enough not to stall, longer for a longer phrase, and
 * priced by script because a Han character is a syllable and a Latin one is not.
 *
 * <p>In the {@code client} package because {@link Narration#spokenTicks} is package-private, and it
 * lives with the harness rather than with the mod: nothing in {@code src} calls this.
 */
public final class NarrationCheck {

    /** The declared floor, in the ticks the hold is measured in. */
    private static final int FLOOR_TICKS = 12;

    /** The declared ceiling. */
    private static final int CEILING_TICKS = 120;

    /** The opening line as shipped, in the two languages this mod has. */
    private static final String OPENING_ZH = "问道地图为您导航";
    private static final String OPENING_EN = "howtogo mod is navigating for you";

    private static int checks;
    private static int failures;

    private NarrationCheck() {
    }

    /**
     * Runs the checks.
     *
     * @return the number of checks made and the number that failed, in that order
     */
    public static int[] run() {
        checks = 0;
        failures = 0;
        System.out.println("== spoken line timing ==");

        expect("a phrase with nothing in it is still held for the floor, since no hold at all is the "
                + "bug this exists to fix", Narration.spokenTicks("") == FLOOR_TICKS);
        expect("and a phrase that is only punctuation is held for the same, because punctuation is "
                        + "not time",
                Narration.spokenTicks("，。、！？…—") == FLOOR_TICKS);
        expect("spaces are not time either, so the same words with one in are held for as long",
                Narration.spokenTicks("问道 地图为您导航") == Narration.spokenTicks(OPENING_ZH));

        expect("a longer phrase is held for longer than a shorter one",
                Narration.spokenTicks(OPENING_ZH + "，本次导航开始") > Narration.spokenTicks(OPENING_ZH));
        expect("a Chinese character costs more than a Latin letter, because one is a syllable and the "
                        + "other is not",
                Narration.spokenTicks(OPENING_ZH) > Narration.spokenTicks("abcdefgh"));

        // The two lines the mod actually says when a trip starts, which is the whole point: each has
        // to be heard in full, and neither may hold the first junction back for long enough to notice.
        expect("the opening line is held for between one and four seconds, which is long enough to "
                        + "hear and short enough not to stall the guidance",
                held(OPENING_ZH) && held(OPENING_EN));
        expect("and when it lifts the guidance follows immediately rather than after another pause",
                Narration.spokenTicks(OPENING_ZH) <= 80);

        expect("a phrase long enough to run away with the hold is capped",
                Narration.spokenTicks("a".repeat(500)) == CEILING_TICKS);

        System.out.println(failures == 0 ? "  spoken line timing ok (" + checks + " checks)"
                : "  spoken line timing FAILED: " + failures + " of " + checks);
        return new int[]{checks, failures};
    }

    /** Whether a phrase is held for something between one and four seconds. */
    private static boolean held(String phrase) {
        int ticks = Narration.spokenTicks(phrase);
        return ticks >= 20 && ticks <= 80;
    }

    private static void expect(String what, boolean condition) {
        checks++;
        if (!condition) {
            failures++;
        }
        System.out.println((condition ? "  ok   " : "  FAIL ") + what);
    }
}
