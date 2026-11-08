package bili.dongsz.howtogo.route;

import net.minecraft.network.chat.Component;

/**
 * The strategy that produced one of the plans a trip is offered.
 *
 * <p>One value per question the planner can ask of the network, not per line of code: two plans with
 * the same strategy would be the same answer twice, which is exactly what the picker deduplicates
 * away. The label is what the results list shows beside a plan ("fastest", "fewer changes") -- the
 * word that says <em>why</em> this plan rather than the one under it.
 *
 * <p>Labels are translation keys, like every other route-package string: this package is shared by
 * every branch and holds no display text of its own.
 */
public enum RouteStrategy {

    /** The plan the policy in force recommends: fastest, unless "shortest" is what was asked for. */
    FASTEST("fastest"),

    /** The shorter line, whatever it does at the corners: the shortest-distance metric, unchanged. */
    SHORTEST("shortest"),

    /** The same policy with major roads preferred: fewer footpaths, longer roads where that is the price. */
    MAJOR_ROADS("major_roads"),

    /** The same policy with highways taken out: the streets through town rather than the bypass. */
    AVOID_HIGHWAY("avoid_highway"),

    /** A detour round the roads the recommended plan uses, made by putting a toll on them. */
    ALTERNATIVE("alternative"),

    /** A journey with fewer changes of line, planned by pricing every change higher. */
    FEWER_TRANSFERS("fewer_transfers"),

    /** A journey with less walking, planned by pricing every walked leg higher. */
    LESS_WALKING("less_walking"),

    /** A journey over other lines, planned by tolling the lines the recommended journey rides. */
    OTHER_LINES("other_lines"),

    /**
     * A walk offered where the mode chosen cannot do better.
     *
     * <p>Not a strategy of public transport but a row the picker may show in its place: when no line
     * can carry the trip, or when the only journey the lines could offer is one walking would beat,
     * the honest plan on offer is the walk itself -- and a plan with no label would read as a plan the
     * picker could not name. The row is a walk in every respect the picker shows: its distance is
     * walked, it has no changes and no waiting.
     */
    WALK("walk");

    private final String id;

    RouteStrategy(String id) {
        this.id = id;
    }

    /** Stable identifier, used for lang and log keys rather than the enum constant name. */
    public String id() {
        return id;
    }

    /** Localised name, for the results list and the log line beside a plan. */
    public String label() {
        return Component.translatable("screen.howtogo.strategy." + id).getString();
    }
}
