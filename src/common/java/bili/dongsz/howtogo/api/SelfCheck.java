package bili.dongsz.howtogo.api;

/**
 * One check that an addon wants run by {@code /howtogo selftest}, beside the mod's own.
 *
 * <h2>Why this is worth an interface</h2>
 * The command exists because "the roads the player drew are the roads the mod loaded" is a question
 * only the running game can answer -- and it is asked by the player, in their own world, with no test
 * rig involved. The same question is exactly as worth asking about an addon's contributed data: it is
 * in the world, it was read by this mod, and whether it arrived intact is not something the addon can
 * find out from its own side. A registered check therefore lands in the same report, in the same
 * shape, and is read by the same person.
 *
 * <h2>What a check must not do</h2>
 * It is run in the client tick, so it must be quick, must not open a screen, and must not leave the
 * player anywhere different from where it found them. A check that throws is caught and reported as a
 * failure with the throwable's text: one addon's broken check is a line in the report, not a crashed
 * session and not a report that never gets written.
 *
 * <p>{@link #name()} is a translation key, and how it is translated is the addon's business: the
 * report resolves it like every other line.
 */
public interface SelfCheck {

    /** Translation key of the check's name, shown in the report. */
    String name();

    /** Runs the check. Called on the client thread; must not throw, but a throw is handled. */
    Result run();

    /**
     * What a check found.
     *
     * @param ok     whether the check passed
     * @param detail what it saw, in the player's words; never a stack trace
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
