package bili.dongsz.howtogo.road;

/**
 * Recognises the click that starts a road selection and the double click that narrows it to one segment.
 * The rule is a pure function so its boundaries can be tested without a client; the timestamp is a parameter so a test can walk the window instead of sleeping.
 * Window: 300 ms and 4 screen pixels of drift, measured as a distance. The time bound is inclusive, and button plus Shift state must match on both presses.
 * Detector is the stateful half the client keeps; not thread-safe, and reset when a double click completes so a third press starts fresh.
 */
public final class SelectionGesture {

    /**
     * How long after a press a second press still counts as part of the same double click; inclusive. Long enough for an unhurried double click, short enough that two deliberate selections are not read as one.
     */
    public static final long DOUBLE_CLICK_MILLIS = 300L;

    /**
     * How far the cursor may drift between the two presses, in screen pixels. A distance, not a per-axis bound; measuring in screen pixels makes it a property of the hand, not of the map's zoom.
     */
    public static final double DOUBLE_CLICK_SLOP_PX = 4.0;

    /**
     * What a press means for a selection. Two values only: the caller knows whether Shift was held, but only the previous press says whether this one completes a double click.
     */
    public enum Target {

        /**
         * A press on its own: act on the whole road under the cursor. Also what the first half of a double click reports, being indistinguishable from a single click at that moment.
         */
        SINGLE,

        /** The second press of a double click: act on the one segment under the cursor. */
        DOUBLE
    }

    /**
     * A press, in the terms the double-click rule is stated in. Button numbering and screen coordinates are the caller's; only equality matters here.
     * @param timeMillis the press's timestamp, on the caller's clock
     */
    public record Press(int button, boolean shift, long timeMillis, double screenX, double screenY) {
    }

    private SelectionGesture() {
    }

    /**
     * What a press means, given the press before it: DOUBLE only when a previous press exists, used the same button, had Shift held, was no more than DOUBLE_CLICK_MILLIS earlier and stood no more than DOUBLE_CLICK_SLOP_PX away.
     * A timestamp that went backwards (a clock correction, a test running time backwards) is a single click, not a double; the subtraction is compared rather than using <=, so this cannot wrap.
     * @param previous the press before this one, or null when this is the first press
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
     * The stateful half: every press is fed in, including the ones that started nothing, because those are what break the chain between two that did.
     * DOUBLE consumes the pair, so the next press is compared against nothing; a double click is the longest gesture recognised, and inventing a triple is not wanted.
     */
    public static final class Detector {

        /** The press the next one is compared against, or null when there is none. */
        private Press previous;

        /** Feeds one press in and answers what it completes. */
        public Target press(int button, boolean shift, long timeMillis, double screenX,
                            double screenY) {
            Target target = classify(previous, button, shift, timeMillis, screenX, screenY);
            previous = target == Target.DOUBLE
                    ? null
                    : new Press(button, shift, timeMillis, screenX, screenY);
            return target;
        }

        public Press previousPress() {
            return previous;
        }

        /**
         * Forgets the press so far, for the moments when the input stream stops being a series of clicks: editing turned off, the map dragged, the cursor leaving the map. Without it the first click after could pair with one from before.
         */
        public void reset() {
            previous = null;
        }
    }
}
