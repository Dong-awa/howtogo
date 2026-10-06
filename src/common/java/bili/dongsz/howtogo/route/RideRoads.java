package bili.dongsz.howtogo.route;

import bili.dongsz.howtogo.road.RoadNetwork;
import bili.dongsz.howtogo.transit.TransitLine;

import java.util.function.Function;
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
 *
 * <h2>And the line's own track, which is the third answer</h2>
 * The layer above is shared: every switched-on line of a kind rides the same rails, so a ride is planned
 * over all of them at once. That is the only answer there was while nothing said which rails belonged to
 * which line -- but a reading that names them does, and the mod keeps each line's own track for drawing
 * it (see {@code MtrTransit.trackOf}). Planning over that instead is the same ride and two things more:
 *
 * <ul>
 *   <li><b>it is the line's own track</b>, so a ride cannot take a stretch of another line's railway
 *       because it happened to be shorter;</li>
 *   <li><b>it is affordable</b>. A ride is planned per pair of neighbouring stops, and a whole railway
 *       read from a server is hundreds of lines and thousands of stops. Searching a layer holding every
 *       line's track costs the size of that layer per plan; searching one line's own track costs the
 *       size of that line. This is the difference between planning a journey over a railway in a few
 *       milliseconds and in several seconds, which is what happened when the whole network first
 *       arrived and every ride was planned over all of it.</li>
 * </ul>
 *
 * <p>Asked only of lines that want the marks: a line whose marks are switched off is planned on the
 * plain network, and its own track is part of the marks it has declined.
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
     * @param marked    the network with the machine-read marks merged in
     * @param plain     the same without them; may be the same object when no line needs the difference
     * @param usesMarks which lines want the marks
     */
    public static RideRoads of(RoadNetwork marked, RoadNetwork plain,
                               Predicate<TransitLine> usesMarks) {
        return new RideRoads(marked, plain, usesMarks, line -> null);
    }

    /**
     * The same, for a caller that also knows what each line runs along.
     *
     * @param ownTrack the track one line runs along, or null for a line whose track is not known -- the
     *                 shared layer is used for those, which is what every line was planned over before
     *                 a reading could name them
     */
    public static RideRoads of(RoadNetwork marked, RoadNetwork plain,
                               Predicate<TransitLine> usesMarks,
                               Function<TransitLine, RoadNetwork> ownTrack) {
        return new RideRoads(marked, plain, usesMarks, ownTrack);
    }

    /** The roads a ride along this line may use. */
    RoadNetwork forLine(TransitLine line) {
        if (line == null || !usesMarks.test(line)) {
            return plain;
        }
        RoadNetwork own = ownTrack.apply(line);
        return own == null || own.segmentCount() == 0 ? marked : own;
    }

    /**
     * The roads the walking legs use, which is the pair without the marks.
     *
     * <p>A walk cannot use a rail or a waterway, so both networks answer it identically: the marks are
     * filtered out by the mode before they are ever looked at. What is not identical is what they cost.
     * Handing the router the network with the marks in it makes it copy that network before it can
     * answer the first walk -- and a whole-network reading's marks are the whole railway, so the walking
     * legs of a journey are planned over a copy of the railway they cannot walk on. Measured, that was
     * most of the frozen second a short journey still cost: a plan that answered in a hundred
     * milliseconds answered in a quarter of a second, for the same legs, because of the network the
     * walking side was given and never used.
     */
    RoadNetwork forWalks() {
        return plain;
    }
}
