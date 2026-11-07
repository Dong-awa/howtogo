package bili.dongsz.howtogo.road;

/**
 * Recognises the click that starts a road selection, and the double click that narrows it to one
 * segment.
 *
 * <h2>Why the decision is a class of its own</h2>
 * The difference between "select this road" and "select the piece under the cursor" is a difference
 * of input, not of the road model, and it is the kind of thing that is easy to get subtly wrong:
 * whether the two presses were on the same button, whether both had Shift held, how long apart they
 * were, and how far the cursor drifted between them. Every one of those has an off-by-one boundary,
 * and a boundary that lives inside a mouse handler that only runs with a client open is a boundary
 * that never gets tested. So the rule is a pure function here, and the client layer only supplies the
 * numbers.
 *
 * <h2>Why the clock and the cursor are arguments</h2>
 * The method deliberately does not call {@code System.currentTimeMillis()}. Time is a parameter so
 * that a test can walk the window in milliseconds instead of sleeping through it, and so that the
 * caller keeps one clock for the whole gesture rather than the class having a second, invisible one.
 *
 * <h2>The window</h2>
 * Three hundred milliseconds between the two presses, and four screen pixels of drift. The pixel
 * bound is a <em>distance</em>, not a per-axis bound: a cursor that moved three pixels on each axis
 * has moved more than four pixels and is a different place. The time bound is inclusive -- exactly
 * three hundred milliseconds is still a double click -- because a bound written as "at most" is the
 * one the player can hit on purpose, and a hand-off off by one millisecond is not a gesture anyone
 * intended.
 *
 * <p>The button and the modifier have to match on <em>both</em> presses. Shift released between them
 * makes them two clicks, and a Shift click followed by a plain one is two different gestures even if
 * they land on the same pixel. That is also what keeps a Shift+right multi-select click from pairing
 * with the plain right click that cleared the selection a moment earlier.
 *
 * <h2>State</h2>
 * {@link Detector} is the stateful half the client keeps: it remembers the previous press and hands
 * back what the current one completes. It is deliberately not thread-safe, and it is reset when the
 * double click completes, so a third press starts a fresh gesture rather than becoming a triple.
 */
public final class SelectionGesture {

    /**
     * How long after a press a second press still counts as part of the same double click.
     *
     * <p>Inclusive: a press exactly this long after the previous one is still a double click. The
     * number is the usual desktop one -- long enough for an unhurried double click, short enough that
     * two deliberate selections a moment apart are not read as one.
     */
    public static final long DOUBLE_CLICK_MILLIS = 300L;

    /**
     * How far the cursor may drift between the two presses and still be the same click, in screen
     * pixels.
     *
     * <p>A distance rather than a per-axis bound: three pixels right and three down is not "within
     * four pixels" in any useful sense. Measuring in screen pixels rather than in blocks makes the
     * tolerance a property of the hand rather than of the map's zoom.
     */
    public static final double DOUBLE_CLICK_SLOP_PX = 4.0;

    /**
     * What a press means for a selection.
     *
     * <p>Two values only: the caller already knows whether Shift was held, and it decides the
     * operation. What it cannot know without the previous press is whether this one finishes a double
     * click.
     */
    public enum Target {

        /**
         * A press on its own: act on the whole road under the cursor.
         *
         * <p>This is also what the first half of a double click reports, because at that moment it is
         * indistinguishable from a single click -- see {@link Detector} for how the two are told
         * apart.
         */
        SINGLE,

        /** The second press of a double click: act on the one segment under the cursor instead. */
        DOUBLE
    }

    /**
     * A press, in the terms the double-click rule is stated in.
     *
     * @param button    the mouse button, in whatever numbering the caller's input layer uses; only
     *                  equality matters here
     * @param shift     whether Shift was held when the button went down
     * @param timeMillis the press's timestamp, on the caller's clock
     * @param screenX   the cursor's X in the same screen coordinates the caller hit-tests with
     * @param screenY   the cursor's Y in those same coordinates
     */
    public record Press(int button, boolean shift, long timeMillis, double screenX, double screenY) {
    }

    private SelectionGesture() {
    }

    /**
     * What a press means, given the press before it.
     *
     * <p>The whole rule, in one place and without state: {@link #DOUBLE} only when the previous press
     * exists, used the same button, had Shift held, was no more than
     * {@link #DOUBLE_CLICK_MILLIS} earlier, and stood no more than {@link #DOUBLE_CLICK_SLOP_PX}
     * away. Anything else is a fresh single click.
     *
     * <p>A timestamp that went backwards is treated as a single click rather than as a double: a
     * clock that stepped back (a system clock correction, a test that runs time backwards) must not
     * turn two unrelated presses into one gesture. The comparison is made on the subtraction rather
     * than with {@code <=}, so this cannot silently wrap.
     *
     * @param previous the press before this one, or null when this is the first press seen
     * @return {@link Target#DOUBLE} when this press completes a double click, {@link Target#SINGLE}
     *         otherwise
     */
    public static Target classify(Press previous, int button, boolean shift, long timeMillis,
                                  double screenX, double screenY) {
        if (previous == null) {
            return Target.SINGLE;
        }
        if (previous.button() != button) {
            return Target.SINGLE;
        }
        if (!previous.shift() || !shift) {
            return Target.SINGLE;
        }
        long elapsed = timeMillis - previous.timeMillis();
        if (elapsed < 0L || elapsed > DOUBLE_CLICK_MILLIS) {
            return Target.SINGLE;
        }
        double dx = screenX - previous.screenX();
        double dy = screenY - previous.screenY();
        // Squared comparison: the tolerance is a length, so the square root is not worth taking.
        if (dx * dx + dy * dy > DOUBLE_CLICK_SLOP_PX * DOUBLE_CLICK_SLOP_PX) {
            return Target.SINGLE;
        }
        return Target.DOUBLE;
    }

    /**
     * The stateful half: remembers the press a following press is compared against.
     *
     * <p>Every press is fed in, including the ordinary clicks and the ones that did not start a
     * selection, because a press that is <em>not</em> part of a gesture is exactly what breaks the
     * chain between two that are. The detector therefore answers "is this press a double click"
     * rather than "is this press a selection", and the caller decides what to do with the answer.
     *
     * <p>{@link Target#DOUBLE} consumes the pair: the next press is compared against nothing and is a
     * single click again. A double click is the longest gesture this recognises, so starting the next
     * pair afresh is the answer that cannot invent a triple-click behaviour nobody asked for.
     */
    public static final class Detector {

        /** The press the next one is compared against, or null when there is none. */
        private Press previous;

        /**
         * Feeds one press in and answers what it completes.
         *
         * @return {@link Target#DOUBLE} when this press completes a double click, otherwise
         *         {@link Target#SINGLE}
         */
        public Target press(int button, boolean shift, long timeMillis, double screenX,
                            double screenY) {
            Target target = classify(previous, button, shift, timeMillis, screenX, screenY);
            previous = target == Target.DOUBLE
                    ? null
                    : new Press(button, shift, timeMillis, screenX, screenY);
            return target;
        }

        /** The press the next one will be compared against, or null when the chain is empty. */
        public Press previousPress() {
            return previous;
        }

        /**
         * Forgets the press so far.
         *
         * <p>For the moments where the input stream stops being a series of clicks: editing turned
         * off, the map dragged, the cursor left the map. Without this the first click after such a
         * moment could pair with a press from before it.
         */
        public void reset() {
            previous = null;
        }
    }
}
