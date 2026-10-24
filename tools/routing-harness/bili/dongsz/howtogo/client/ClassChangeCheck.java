package bili.dongsz.howtogo.client;

/**
 * The road-surface notice only speaks about a change that is clearly the road being travelled.
 *
 * <h2>The defect this covers</h2>
 * The class underfoot is the class of the nearest road within its tolerance, and near a junction two
 * roads compete for that title: a highway's centreline and the ramp or side road beside it. The
 * nearest of the two is decided by centimetres and re-decided once per block crossed, so a driver
 * who never left a lane could be told "road surface: road" and then "road surface: highway" -- a
 * notice about a change that did not happen.
 *
 * <p>Two guards live on top of the reading, and this check pins the second: a reading has to hold for
 * a few ticks ({@code CLASS_CHANGE_HOLD_TICKS}, not checked here -- it is a client tick loop), and
 * the new surface has to be clearly nearer than the one believed underfoot
 * ({@link Navigation#isClearClassChange}, checked here). The first filters the one-block flicker of a
 * boundary; the second filters the ambiguity that lasts, which is what a ramp running alongside the
 * carriageway for twenty blocks produces.
 *
 * <h2>What the numbers mean</h2>
 * Both distances are blocks from the player to the nearest centreline of that class: {@code 0} is
 * standing on the middle of that road, and the believed class has no distance at all
 * ({@link Double#MAX_VALUE}) once nothing of it is inside its tolerance -- which is its own way of
 * saying the player has left it. The margin is what keeps "nearly as near" from counting, while a
 * road the player has actually left is always clearly behind them within a couple of blocks.
 */
public final class ClassChangeCheck {

    private static int checks;
    private static int failures;

    private ClassChangeCheck() {
    }

    public static void main(String[] args) {
        System.exit(run()[1] == 0 ? 0 : 1);
    }

    public static int[] run() {
        System.out.println("== a road-surface change is only said when it is one ==");

        // A driver holding a lane of a carriageway three blocks from its centreline, with a merge
        // lane a block and a half away. Nearest reading says the merge lane; the notice must not.
        check(3.0, 1.5, false, "a ramp a block and a half nearer than the lane is not a change");
        // The same carriageway with the ramp out at two and a half blocks: still not its road.
        check(3.0, 2.5, false, "a ramp merely nearest is not a change either");
        // Actually moving onto the ramp: it is underfoot and the carriageway is behind.
        check(3.0, 0.5, true, "moving onto the ramp is a change");
        // Stepping off one road onto another that touches it: while both centrelines are still
        // close the reading is ambiguous, and the notice waits until the old road is behind.
        check(0.5, 0.0, false, "a change at a shared junction waits until the old road is behind");
        check(5.0, 0.0, true, "and is said once the old road is behind");
        // Nothing of the believed class within its tolerance at all: that is as clear as it gets.
        check(Double.MAX_VALUE, 4.0, true, "a change away from nothing underfoot is said");
        // Standing on the believed road while something else ties with it: no change.
        check(0.0, 0.0, false, "a tie on the road underfoot is not a change");
        // Two roads the same distance apart, which is what a divided carriageway looks like: the
        // nearest one may swap freely, and neither swap is a change of surface.
        check(2.0, 2.0, false, "two roads equidistant are not a change");

        System.out.println(failures == 0
                ? "road class change ok (" + checks + " checks)"
                : "road class change FAILED: " + failures + " of " + checks + " checks");
        return new int[]{checks, failures};
    }

    private static void check(double believedDistance, double readingDistance, boolean expected,
                              String what) {
        boolean actual = Navigation.isClearClassChange(believedDistance, readingDistance);
        checks++;
        if (actual != expected) {
            failures++;
        }
        System.out.println((actual == expected ? "  ok   " : "  FAIL ") + what
                + (actual == expected ? "" : " (said " + actual + ")"));
    }
}
