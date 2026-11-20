package bili.dongsz.howtogo.api;

import bili.dongsz.howtogo.HowToGo;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Addon-contributed self-checks, run after and separately from the mod's own.
 * SelfTest edits the world freely, so addon checks are kept apart and only concatenated onto
 * the report; a throwing check is caught per check and named in its failed line. They run in
 * /howtogo selftest only, never in the launcher-driven run the build rig grades.
 */
public final class SelfChecks {

    private static final List<SelfCheck> CHECKS = new CopyOnWriteArrayList<>();

    /** Names already taken, so two checks cannot be indistinguishable in the report. */
    private static final Set<String> NAMES = ConcurrentHashMap.newKeySet();

    private SelfChecks() {
    }

    /** Adds a check to the report. A duplicate name is refused and logged. */
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
        HowToGo.logInfo("[HowToGo] api | self check registered: {}", name);
    }

    /** How many addon checks are registered. */
    public static int count() {
        return CHECKS.size();
    }

    /** Runs every registered check on the calling thread; never throws, failures come back as outcomes. */
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
                // Deliberately Throwable: reflective readers can fail with a LinkageError.
                outcomes.add(new Outcome(name, false, String.valueOf(threw)));
            }
        }
        return outcomes;
    }

    /** One check's line in the report: name (translation key), ok, detail. */
    public record Outcome(String name, boolean ok, String detail) {
    }
}
