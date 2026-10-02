package bili.dongsz.howtogo.route;

import bili.dongsz.howtogo.road.RoadNetwork;
import bili.dongsz.howtogo.transit.TransitLine;

import java.util.function.Predicate;

/**
 * Which roads a plan runs on, when the answer depends on the line.
 *
 * <h2>The problem this solves</h2>
 * A network handed to the planner may have machine-read marks merged into it -- the rails a player did
 * not draw, read out of another mod. Whether a particular line should be planned over those marks is a
 * question about that line, and the planner cannot answer it: the rails belong to the world rather than
 * to the line, one layer serves every line of a kind, and the route package knows nothing about the mod
 * the marks came from.
 *
 * <p>Two networks and a predicate is therefore what the planner is given: the one with the marks, the
 * same without them, and which lines want them. A line that does not is planned on the network without,
 * so switching its marks off really does take them out of its own ride -- rather than merely declining
 * to add a layer that some other line has already added, which would be a switch that does nothing.
 *
 * <p>The pair are the same object when nothing wants the difference, so a plan over lines that all
 * agree never pays for a second copy of the world.
 */
public final class RideRoads {

    private final RoadNetwork marked;
    private final RoadNetwork plain;
    private final Predicate<TransitLine> usesMarks;

    private RideRoads(RoadNetwork marked, RoadNetwork plain, Predicate<TransitLine> usesMarks) {
        this.marked = marked;
        this.plain = plain;
        this.usesMarks = usesMarks;
    }

    /** One network for every line, marks and all. */
    public static RideRoads of(RoadNetwork network) {
        return new RideRoads(network, network, line -> true);
    }

    /**
     * @param marked    the network with the machine-read marks merged in
     * @param plain     the same without them; may be the same object when no line needs the difference
     * @param usesMarks which lines want the marks
     */
    public static RideRoads of(RoadNetwork marked, RoadNetwork plain,
                               Predicate<TransitLine> usesMarks) {
        return new RideRoads(marked, plain, usesMarks);
    }

    /** The roads a ride along this line may use. */
    RoadNetwork forLine(TransitLine line) {
        return line != null && usesMarks.test(line) ? marked : plain;
    }

    /**
     * The roads the walking legs use, which is always the marked pair.
     *
     * <p>A walk cannot use a rail or a waterway, so both networks answer it identically -- the marks
     * are filtered out by the mode before they are ever looked at. Taking the marked one keeps the
     * walking side of a journey out of a decision that is only about riding.
     */
    RoadNetwork forWalks() {
        return marked;
    }
}
