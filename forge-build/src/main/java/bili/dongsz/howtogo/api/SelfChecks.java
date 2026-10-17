package bili.dongsz.howtogo.api;

import bili.dongsz.howtogo.HowToGo;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The self-checks addons have contributed, run after this mod's own.
 *
 * <h2>Why they run last and separately</h2>
 * {@code SelfTest} owns the mod's own checks and edits its world freely -- it sets a destination and
 * puts back the one being navigated. An addon's check is not part of that sequence and must not be
 * able to disturb it, so the two lists are kept apart and this one is only concatenated onto the
 * report. A check that throws is caught here, one check at a time: the mod's own report still gets
 * written, and the addon responsible is named in the line that failed.
 *
 * <h2>Where these appear, and where they deliberately do not</h2>
 * In {@code /howtogo selftest} -- the player asking about the world they are standing in. They are
 * <em>not</em> part of the launcher-driven run ({@code howtogo.selftest}, see {@code AutoSelfTest})
 * that the build rig grades: that run is this mod's own build check, its pass or fail is an exit code
 * somebody acts on, and an addon's broken source turning this mod's check red is a report about the
 * wrong mod. An addon that wants its checks graded by a rig has its own build for that.
 */
public final class SelfChecks {

    private static final List<SelfCheck> CHECKS = new CopyOnWriteArrayList<>();

    /** Names already taken, so two checks cannot be indistinguishable in the report. */
    private static final Set<String> NAMES = ConcurrentHashMap.newKeySet();

    private SelfChecks() {
    }

    /**
     * Adds a check to the report.
     *
     * <p>A duplicate name is refused and logged: the report is read by a person, and two identical
     * lines in it are worse than one line fewer.
     */
    public static void register(SelfCheck check) {
        if (check == null) {
            throw new NullPointerException("self check");
        }
        String name = check.name();
        if (name == null || name.isBlank()) {
            HowToGo.LOGGER.warn("[HowToGo] api | a self check with no name was refused");
            return;
        }
        if (!NAMES.add(name)) {
            HowToGo.LOGGER.warn("[HowToGo] api | self check '{}' is already registered; "
                    + "the later registration was refused", name);
            return;
        }
        CHECKS.add(check);
        HowToGo.LOGGER.info("[HowToGo] api | self check registered: {}", name);
    }

    /** How many addon checks are registered. */
    public static int count() {
        return CHECKS.size();
    }

    /**
     * Runs every registered check, in registration order, on the calling thread.
     *
     * <p>Never throws: a check that throws comes back as a failed outcome naming the throwable, so the
     * caller can concatenate the result onto a report without a guard of its own.
     */
    public static List<Outcome> runAll() {
        List<Outcome> outcomes = new ArrayList<>(CHECKS.size());
        for (SelfCheck check : CHECKS) {
            String name = check.name();
            try {
                SelfCheck.Result result = check.run();
                if (result == null) {
                    outcomes.add(new Outcome(name, false, "returned nothing"));
                } else {
                    outcomes.add(new Outcome(name, result.ok(), result.detail()));
                }
            } catch (Throwable threw) {
                // Deliberately Throwable: a check reaching another mod's reflective reader can fail
                // with a LinkageError, and a report that stops at the first such check is no report.
                outcomes.add(new Outcome(name, false, String.valueOf(threw)));
            }
        }
        return outcomes;
    }

    /**
     * One check's line in the report.
     *
     * @param name   the check's translation key
     * @param ok     whether it passed
     * @param detail what it saw
     */
    public record Outcome(String name, boolean ok, String detail) {
    }
}
