package bili.dongsz.howtogo.route;

import net.minecraft.network.chat.Component;

/**
 * What "best" means when a route is chosen.
 *
 * <p>A road network can be crossed cheaply in two different senses -- fewest blocks, or fewest
 * seconds -- and which one the player wants is a matter of taste rather than of correctness: a
 * scenic detour along a highway can be the better trip even when a footpath is shorter, and the
 * reverse is just as true. The metric is therefore a routing input, not a label on the result.
 */
public enum RoutePreference {

    /** Fewest seconds: each road is weighed by the pace the mode actually makes on it. */
    FASTEST_TIME("fastest_time"),
    /** Fewest blocks: pace is ignored and the shortest line of roads wins. */
    SHORTEST_DISTANCE("shortest_distance");

    private static final RoutePreference[] VALUES = values();

    private final String id;

    RoutePreference(String id) {
        this.id = id;
    }

    /** Stable identifier, used for config and lang keys rather than the enum constant name. */
    public String id() {
        return id;
    }

    /** Localised name, for the picker summary. */
    public String label() {
        return Component.translatable("hud.howtogo.preference." + id).getString();
    }

    /**
     * Metric for a configured id.
     *
     * <p>Falling back to {@link #FASTEST_TIME} keeps a typo in the config file from making routes
     * inexplicably longer, which is the failure the player would notice least and understand least.
     */
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
