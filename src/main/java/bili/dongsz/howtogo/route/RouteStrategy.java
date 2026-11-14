package bili.dongsz.howtogo.route;

import net.minecraft.network.chat.Component;

/**
 * The question the planner answered; one value per question, so equal strategies mean duplicate plans. Labels are translation keys.
 */
public enum RouteStrategy {

    /** The policy in force's recommendation: fastest, unless shortest was asked for. */
    FASTEST("fastest"),

    SHORTEST("shortest"),

    /** The same policy preferring major roads. */
    MAJOR_ROADS("major_roads"),

    /** The same policy taking highways out. */
    AVOID_HIGHWAY("avoid_highway"),

    /** Detour around the recommended plan's roads, by tolling them. */
    ALTERNATIVE("alternative"),

    /** Fewer line changes, by pricing every change higher. */
    FEWER_TRANSFERS("fewer_transfers"),

    /** Less walking, by pricing every walked leg higher. */
    LESS_WALKING("less_walking"),

    /** Other lines, by tolling the lines the recommended plan rides. */
    OTHER_LINES("other_lines"),

    /** A walk offered where the chosen mode cannot do better. */
    WALK("walk");

    private final String id;

    RouteStrategy(String id) {
        this.id = id;
    }

    /** Stable identifier for lang and log keys. */
    public String id() {
        return id;
    }

    /** Localised name for the results list and the log line beside a plan. */
    public String label() {
        return Component.translatable("screen.howtogo.strategy." + id).getString();
    }
}
