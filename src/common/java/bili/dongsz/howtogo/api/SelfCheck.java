package bili.dongsz.howtogo.api;

/**
 * One extra check for /howtogo selftest, run beside the mod's own.
 * Runs in the client tick: must be quick, must not open a screen, and must leave the player where it found them. A throw is caught and reported as a failure with the throwable's text, not propagated. name() is a translation key resolved by the report.
 */
public interface SelfCheck {

    /** Translation key of the check name, shown in the report. */
    String name();

    /** Runs the check on the client thread; a throw is caught and reported. */
    Result run();

    /**
     * What a check found.
     * @param ok whether the check passed
     * @param detail what it saw in the player's words; never a stack trace
     */
    record Result(boolean ok, String detail) {

        /** A pass with something to say about it. */
        public static Result pass(String detail) {
            return new Result(true, detail);
        }

        /** A failure with the reason it failed. */
        public static Result fail(String detail) {
            return new Result(false, detail);
        }
    }
}
