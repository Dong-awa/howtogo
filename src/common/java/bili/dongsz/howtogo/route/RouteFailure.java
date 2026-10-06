package bili.dongsz.howtogo.route;

import java.util.List;

/**
 * Why a trip could not be planned, as something the player can be shown in their own language.
 *
 * <h2>Why this is not a sentence</h2>
 * The router works out the reason and the picker draws it, and the two are in different source sets: the
 * route package is the pure Java every branch shares, and it cannot name a Minecraft class, so it cannot
 * translate anything. It used to answer with an English sentence anyway -- "no road usable by walk
 * (Walk) within 64 blocks of the start or the destination" -- and the picker drew it as it came, so the
 * one line that explains a failure was the one line in the mod that was always English, in every
 * language, while every other string around it was translated.
 *
 * <p>So the reason travels as a translation key and its arguments instead. The router names the branch,
 * the UI resolves it -- see {@code Navigation#failureText} -- and neither has to know about the other.
 *
 * <p>The arguments are already-localized text in one case and plain numbers in the rest: a mode's name
 * comes from {@code TravelMode#label}, which translates itself, so a translated sentence keeps reading
 * correctly when it is assembled rather than needing its pieces translated again at the far end.
 *
 * @param key  the translation key naming the branch
 * @param args what that branch needs to name, in the order the key expects them
 */
public record RouteFailure(String key, List<Object> args) {

    /** No reason at all, for a caller that has none to show. */
    public static final RouteFailure NONE = new RouteFailure("", List.of());

    public RouteFailure {
        args = args == null ? List.of() : List.copyOf(args);
    }

    /** A branch that needs nothing named. */
    public static RouteFailure of(String key) {
        return new RouteFailure(key, List.of());
    }

    /** A branch that names one thing. */
    public static RouteFailure of(String key, Object first) {
        return new RouteFailure(key, List.of(first));
    }

    /** A branch that names two things. */
    public static RouteFailure of(String key, Object first, Object second) {
        return new RouteFailure(key, List.of(first, second));
    }

    /** A branch that names three things. */
    public static RouteFailure of(String key, Object first, Object second, Object third) {
        return new RouteFailure(key, List.of(first, second, third));
    }

    /** A branch that names four things. */
    public static RouteFailure of(String key, Object first, Object second, Object third,
                                  Object fourth) {
        return new RouteFailure(key, List.of(first, second, third, fourth));
    }

    /** Whether there is a reason to show at all. */
    public boolean isPresent() {
        return key != null && !key.isEmpty();
    }

    /**
     * The key and its arguments, for a log line.
     *
     * <p>The log is read by whoever is diagnosing a report, and it is the one place the untranslated
     * form is more useful than the sentence: a player's screenshot shows them the sentence anyway, and
     * the key is what can be searched for in the source.
     */
    @Override
    public String toString() {
        return key == null ? "" : (args.isEmpty() ? key : key + " " + args);
    }
}
