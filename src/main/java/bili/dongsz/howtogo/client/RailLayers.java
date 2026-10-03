package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.road.RoadSegment;

import java.util.Iterator;

/**
 * The machine-read rails a view draws.
 *
 * <h2>What is drawn, and what is deliberately not</h2>
 * Create's track graph, which no line of this mod describes: without it a Create station on the list
 * would be a station with no line running to it, so it is drawn wherever a map is.
 *
 * <p>MTR's marks are not drawn, although they are roads of this mod in exactly the same sense. They are
 * the track an MTR line runs along, and the line's own stroke on the map is planned along that very
 * track -- so drawing the marks as well paints the same route twice, an orange road under a blue line,
 * and the line is the one that says what it is. The marks are still in the routing network and still
 * what a ride is planned over; this is only about what is drawn, and the switch beside a line still
 * decides whether that line's track is marked at all.
 *
 * <h2>Why one name</h2>
 * Every view that draws a machine-read rail used to name Create's layer itself, and each of them drifted
 * as the layers changed. Going through here is what makes "the views draw the same rails" a property of
 * the code rather than something each new view has to remember.
 */
public final class RailLayers {

    private RailLayers() {
    }

    /**
     * The rails to draw: Create's layer, as one sequence.
     *
     * <p>Evaluated on iteration rather than on the call, so a caller may hold the sequence across the
     * frame it is drawing without pinning a view of a layer that a rebuild replaced in between.
     */
    public static Iterable<RoadSegment> drawn() {
        return RailTrackStore.segments();
    }

    /**
     * Every machine-read rail a vehicle can be travelling on: Create's and MTR's both.
     *
     * <p>For what is underfoot rather than for what is drawn. Riding a track MTR laid is riding a rail,
     * and a reading that only knew about Create's would call it walking across open country -- the pace
     * and the instructions would be those of a person on foot while the player sat on a train. That the
     * same track is not drawn as a road is a separate question, answered by {@link #drawn()}.
     */
    public static Iterable<RoadSegment> travelled() {
        return () -> new java.util.Iterator<RoadSegment>() {

            private final java.util.Iterator<RoadSegment> create =
                    RailTrackStore.segments().iterator();
            private final java.util.Iterator<RoadSegment> mtr =
                    MtrTransit.railLayer().segmentsSnapshot().iterator();

            @Override
            public boolean hasNext() {
                return create.hasNext() || mtr.hasNext();
            }

            @Override
            public RoadSegment next() {
                return create.hasNext() ? create.next() : mtr.next();
            }
        };
    }

    /** Whether there is anything to draw, for a caller that only wants to skip the pass. */
    public static boolean isEmpty() {
        return RailTrackStore.segments().isEmpty();
    }
}
