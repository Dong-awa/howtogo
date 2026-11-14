package bili.dongsz.howtogo.route;

import net.minecraft.network.chat.Component;

/** What "best" means when a route is chosen; a routing input, not a label on the result. */
public enum RoutePreference {

    /** Fewest seconds: each road is weighed by the pace the mode makes on it. */
    FASTEST_TIME("fastest_time"),
    /** Fewest blocks: pace is ignored; the shortest line of roads wins. */
    SHORTEST_DISTANCE("shortest_distance");

    private static final RoutePreference[] VALUES = values();

    private final String id;

    RoutePreference(String id) {
        this.id = id;
    }

    /** Stable id for config and lang keys, not the enum constant name. */
    public String id() {
        return id;
    }

    /** Localised name, for the picker summary. */
    public String label() {
        return Component.translatable("hud.howtogo.preference." + id).getString();
    }

    /** Metric for a configured id; falls back to FASTEST_TIME on a typo. */
    public static RoutePreference byId(String id) {
        if (id != null) {
            for (RoutePreference preference : VALUES) {
                if (preference.id.equalsIgnoreCase(id.trim())) {
                    return preference;
                }
            }
        }
        return FASTEST_TIME;
    }
}
