package bili.dongsz.howtogo.client;

/**
 * The screen transition the interface uses when one of the mod's pages replaces another.
 *
 * <h2>Why this is checked without a game</h2>
 * The two symptoms this transition exists for are both visual and both were reported from a running
 * client: a page change that showed the world for a frame or two (read as the interface blinking), and
 * a panel that faded out, came back at full strength for a single frame, and then went away (read as
 * a second blink, on the way out). Neither can be seen in a test, but both are arithmetic on two
 * numbers this class owns: <em>what opacity the surface is drawn at, and what opacity the content is
 * drawn at, on every frame of a swap</em>. A surface that never reaches zero cannot show the world; a
 * content that never returns to full after the exit has finished cannot blink back. Those two
 * invariants are what is asserted below, sampled over the whole transition rather than only at its
 * ends -- a transition that is right at both endpoints and wrong in between is exactly the one that
 * blinks.
 *
 * <p>A headless check also pins the thing that is easiest to get wrong in a single edit: the state
 * machine has three entrances and two exits, and the exit that starts a hand-off and the exit that
 * ends a page must not be the same call -- one holds the sheet, the other takes it down with the
 * content. The floor, the held surface and the missing travel are asserted separately, because a
 * transition that only lands correctly at the end is the one that flickers on the way.
 */
public final class ScreenMotionCheck {

    private static int checks;
    private static int failures;

    /** How long a slide may take in wall-clock terms before the check calls it stuck. */
    private static final long BUDGET_MILLIS = 1500L;

    /** A frame's worth of rounding, plus the slide's own "settled" tolerance. */
    private static final float TOLERANCE = 0.03F;

    private ScreenMotionCheck() {
    }

    public static int[] run() {
        System.out.println("== page transitions ==");
        closingToTheWorld();
        handoverOut();
        handoverIn();
        aReturnedPageStillCloses();
        System.out.println(failures == 0
                ? "page transitions ok (" + checks + " checks)"
                : "page transitions FAILED: " + failures + " of " + checks + " checks");
        return new int[]{checks, failures};
    }

    /**
     * The one that closes the panel: the surface has to go all the way down.
     *
     * <p>This is the shape a plain exit must keep. If holding the surface for a hand-off ever leaked
     * into this path, a closing panel would stay opaque over the world and the screen would appear to
     * freeze -- the very failure the deferred swap's bookkeeping exists to avoid.
     */
    private static void closingToTheWorld() {
        UiMotion.ScreenState state = openPage();
        expect("a page that has just been settled is fully drawn", about(state.opacity(), 1.0F));
        state.leave();
        Watch watch = watch(state, true);
        expect("a page closing to the world fades to nothing", about(watch.movedTo(), 0.0F));
        expect("and its surface goes with it, or the world would never show",
                about(watch.minSurface, 0.0F));
        expect("the exit reports itself finished, which is what the swap waits for", watch.finished);
    }

    /**
     * The page being left: content down to the floor, surface held, and no travel.
     *
     * <p>Two of these are the whole of the fix for the flash between pages. The surface staying up is
     * what stops the world appearing in the middle of a page change; the content leaving off at
     * {@link UiMotion#HANDOFF_FLOOR} rather than at zero is what makes the page arriving continue a
     * fade instead of starting one.
     */
    private static void handoverOut() {
        UiMotion.ScreenState state = openPage();
        state.handOffOut();
        Watch watch = watch(state, true);
        expect("the page being handed over fades down to the hand-off floor",
                about(state.opacity(), UiMotion.HANDOFF_FLOOR));
        expect("its surface never dips on the way, so the world never shows between the pages",
                watch.minSurface >= 1.0F - TOLERANCE);
        expect("and its content never goes below the floor either",
                watch.minOpacity >= UiMotion.HANDOFF_FLOOR - TOLERANCE);
        expect("the frame does not travel, so the two sheets stand on top of each other",
                watch.maxTravel <= TOLERANCE);
        expect("the exit still finishes, so the deferring swap is not left waiting for it",
                watch.finished);
    }

    /**
     * The page arriving: from the same floor, on the same surface, up to full.
     *
     * <p>Sampled from the frame it is installed on, which is the one that matters: a page that began
     * at zero there would be the same flash one page later.
     */
    private static void handoverIn() {
        UiMotion.ScreenState state = new UiMotion.ScreenState();
        state.handOffIn();
        Watch watch = watch(state, false);
        expect("a page arriving by hand-off is on the surface from its first frame",
                watch.minSurface >= 1.0F - TOLERANCE);
        expect("and none of its content is below the floor the page before it left at",
                watch.minOpacity >= UiMotion.HANDOFF_FLOOR - TOLERANCE);
        expect("it stands still, like the page it replaces", watch.maxTravel <= TOLERANCE);
        expect("nor is it on its way out", !watch.finished);
        expect("and it arrives fully open", about(state.opacity(), 1.0F));
        expect("with the floor forgotten once it is open", about(state.surface(), 1.0F));
    }

    /**
     * A page that was hand-off-entered and is then closed must still fade all the way out.
     *
     * <p>The hand-off flag cannot be a permanent property of the screen: the state that held the
     * surface for an arrival is the same state that will have to give it up on the way out, and a
     * flag that outlived its transition would leave a closing panel painted over the world at three
     * tenths -- a screen that looks stuck rather than slow. The reset is asserted from both ends: a
     * settled page and a returned one.
     */
    private static void aReturnedPageStillCloses() {
        UiMotion.ScreenState state = new UiMotion.ScreenState();
        state.handOffIn();
        watch(state, false);
        state.leave();
        Watch closing = watch(state, true);
        expect("a page that arrived by hand-off still closes to nothing",
                about(closing.movedTo(), 0.0F));
        expect("and gives its surface up on the way out", about(closing.minSurface, 0.0F));

        UiMotion.ScreenState returned = openPage();
        returned.handOffOut();
        watch(returned, true);
        returned.settleOpen();
        expect("a page revealed again is fully open", about(returned.opacity(), 1.0F));
        expect("and no longer holds a surface for a transition that is over",
                about(returned.surface(), 1.0F));
        returned.leave();
        Watch again = watch(returned, true);
        expect("so closing it after that fades the surface too",
                about(again.minSurface, 0.0F));
    }

    /** A page at rest and fully open, which is the state every transition below starts from. */
    private static UiMotion.ScreenState openPage() {
        UiMotion.ScreenState state = new UiMotion.ScreenState();
        state.settleOpen();
        return state;
    }

    /**
     * What one transition did, frame by frame: the extremes rather than the ends.
     *
     * <p>The two numbers that matter are minima -- the surface and the content, at their lowest --
     * because both of the reported symptoms are a momentary dip, and an endpoint-only check cannot see
     * a dip at all.
     */
    private record Watch(float minOpacity, float minSurface, float maxTravel, float movedTo,
                         boolean finished) {
    }

    /**
     * Reads a transition to its end, recording the extremes on the way.
     *
     * <p>The slides advance off the frame clock, so a test has to hand it frames: a couple of
     * milliseconds a step is about what a frame is worth. The budget is generous on purpose -- the
     * longest transition in the table is a quarter of a second, so a machine under load should fail
     * this check by being stuck, not by being slow.
     *
     * @param untilFinished whether the transition ends when its exit reports itself done (a page
     *                      leaving) or when it has arrived (a page entering)
     */
    private static Watch watch(UiMotion.ScreenState state, boolean untilFinished) {
        long deadline = System.currentTimeMillis() + BUDGET_MILLIS;
        float minOpacity = 1.0F;
        float minSurface = 1.0F;
        float maxTravel = 0.0F;
        float movedTo = 0.0F;
        boolean finished = false;
        // Two identical readings in a row mean the transition has stopped moving, which is how an
        // arrival is known to be over; the exit asks the state itself, since "stopped" and "finished"
        // are not the same claim.
        float previous = Float.NaN;
        boolean settledTwice = false;
        while (System.currentTimeMillis() < deadline) {
            minOpacity = Math.min(minOpacity, state.opacity());
            minSurface = Math.min(minSurface, state.surface());
            maxTravel = Math.max(maxTravel, Math.abs(state.offsetY(UiMotion.RISE, UiMotion.FALL)));
            movedTo = state.opacity();
            finished = state.left();
            if (untilFinished && finished) {
                break;
            }
            if (movedTo == previous) {
                if (settledTwice) {
                    break;
                }
                settledTwice = true;
            } else {
                settledTwice = false;
            }
            previous = movedTo;
            try {
                Thread.sleep(2L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return new Watch(minOpacity, minSurface, maxTravel, movedTo, finished);
    }

    /** Whether a value is within a frame's worth of rounding of the expected one. */
    private static boolean about(float actual, float expected) {
        return Math.abs(actual - expected) <= TOLERANCE;
    }

    private static void expect(String what, boolean ok) {
        checks++;
        if (!ok) {
            failures++;
        }
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
    }
}
