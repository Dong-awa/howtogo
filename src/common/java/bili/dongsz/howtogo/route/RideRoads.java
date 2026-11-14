package bili.dongsz.howtogo.route;

import bili.dongsz.howtogo.road.RoadNetwork;
import bili.dongsz.howtogo.transit.TransitLine;

import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Which roads a plan runs on, when the answer depends on the line.
 *
 * The planner gets two networks -- with and without machine-read marks merged in -- plus the set of lines that want the marks, so switching a line's marks off really removes them from its ride. The pair is the same object when no line needs the difference.
 * For a line with a known own track, that track is planned over instead of the shared layer: the ride cannot take another line's railway, and search cost scales with one line rather than with every line on the server.
 * Asked only of lines that want the marks; a line's own track counts as part of them.
 */
public final class RideRoads {

    private final RoadNetwork marked;
    private final RoadNetwork plain;
    private final Predicate<TransitLine> usesMarks;
    private final Function<TransitLine, RoadNetwork> ownTrack;

    private RideRoads(RoadNetwork marked, RoadNetwork plain, Predicate<TransitLine> usesMarks,
                      Function<TransitLine, RoadNetwork> ownTrack) {
        this.marked = marked;
        this.plain = plain;
        this.usesMarks = usesMarks;
        this.ownTrack = ownTrack;
    }

    /** One network for every line, marks and all. */
    public static RideRoads of(RoadNetwork network) {
        return new RideRoads(network, network, line -> true, line -> null);
    }

    /**
     * marked: the network with the machine-read marks merged in.
     * plain: the same without them; may be the marked object when no line needs the difference.
     * usesMarks: which lines want the marks.
     */
    public static RideRoads of(RoadNetwork marked, RoadNetwork plain,
                               Predicate<TransitLine> usesMarks) {
        return new RideRoads(marked, plain, usesMarks, line -> null);
    }

    /**
     * ownTrack: the track one line runs along, or null when unknown -- the shared layer is used for those, as before a reading could name tracks.
     */
    public static RideRoads of(RoadNetwork marked, RoadNetwork plain,
                               Predicate<TransitLine> usesMarks,
                               Function<TransitLine, RoadNetwork> ownTrack) {
        return new RideRoads(marked, plain, usesMarks, ownTrack);
    }

    /**
     * The roads a ride along this line may use.
     *
     * Public so the planner and the line editor judge a line by the same network; an editor building its own marked stops red against rails the planner would never use.
     */
    public RoadNetwork forLine(TransitLine line) {
        if (line == null || !usesMarks.test(line)) {
            return plain;
        }
        RoadNetwork own = ownTrack.apply(line);
        return own == null || own.segmentCount() == 0 ? marked : own;
    }

    /**
     * The roads the walking legs use: the pair without the marks.
     *
     * A walk cannot use a rail or waterway, so both networks answer identically. Handing the router the marked network makes it copy the whole railway per walk -- measured, most of the frozen second a short journey still cost.
     */
    RoadNetwork forWalks() {
        return plain;
    }
}
