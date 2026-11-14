package bili.dongsz.howtogo.route;

import java.util.List;

/**
 * Why a trip could not be planned, as text the player can be shown in their own language.
 * The route package is pure Java shared by every branch and cannot name a Minecraft class, so it cannot translate; the reason therefore travels as a translation key plus its arguments. The router names the branch, the UI resolves it (Navigation.failureText).
 * Arguments are plain numbers except a mode name, which comes from TravelMode.label and is already localized.
 * @param key the translation key naming the branch
 * @param args what that branch must name, in the order the key expects
 */
public record RouteFailure(String key, List<Object> args) {

    /** No reason at all, for a caller that has none to show. */
    public static final RouteFailure NONE = new RouteFailure("", List.of());

    public RouteFailure {
        args = args == null ? List.of() : List.copyOf(args);
    }

    public static RouteFailure of(String key) {
        return new RouteFailure(key, List.of());
    }

    public static RouteFailure of(String key, Object first) {
        return new RouteFailure(key, List.of(first));
    }

    public static RouteFailure of(String key, Object first, Object second) {
        return new RouteFailure(key, List.of(first, second));
    }

    public static RouteFailure of(String key, Object first, Object second, Object third) {
        return new RouteFailure(key, List.of(first, second, third));
    }

    public static RouteFailure of(String key, Object first, Object second, Object third,
                                  Object fourth) {
        return new RouteFailure(key, List.of(first, second, third, fourth));
    }

    /** Whether there is a reason to show at all. */
    public boolean isPresent() {
        return key != null && !key.isEmpty();
    }

    /**
     * The key and its arguments, for a log line: unlike the player-facing sentence, the key can be searched in the source.
     */
    @Override
    public String toString() {
        return key == null ? "" : (args.isEmpty() ? key : key + " " + args);
    }
}
