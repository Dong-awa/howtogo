package bili.dongsz.howtogo.api;

/**
 * One boarding point, whoever put it there.
 *
 * <h2>Why one type for three sources</h2>
 * A station reaches this mod by one of three roads: it is a place the player marked as a station, it
 * is a station on Create's track graph, or it is a station read out of MTR. In the mod those are three
 * different objects -- {@code RoadNode}, {@code RailTrackStore.Station}, {@code MtrTransit.Station} --
 * with three different lifetimes, and each is exactly right where it is used. An addon that wants to
 * answer "what can I travel from here" has no business knowing which of the three it is talking to,
 * so this is that answer: a name, a position, and the id of the source it came from.
 *
 * <p>The name is the display name and only that -- the same string the picker lists, the route
 * instructions say and the spoken announcements read. A station that has no name of its own is named
 * after where it stands, by the mod's own rule, rather than by each addon inventing one.
 *
 * <p>{@code source} is a {@link bili.dongsz.howtogo.route.DestinationSource} id, so an addon that
 * registered a source can recognise its own stations among these.
 *
 * @param name   the display name, never null and never blank
 * @param x      block X
 * @param y      block Y
 * @param z      block Z
 * @param source id of the destination source this station came from
 */
public record StationRef(String name, int x, int y, int z, String source) {
}
