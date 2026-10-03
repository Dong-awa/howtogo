package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.road.RoadSegment;

import java.util.Iterator;

/**
 * The machine-read rails a view draws, as one sequence.
 *
 * <h2>Two layers, and who decides what is in them</h2>
 * Create's track graph and MTR's marks -- the track each line read out of MTR runs along, cut per line
 * and kept per line. Whether a line's part of the second layer is there at all is that line's own switch
 * in the editor (see {@link MtrMarks} and {@link MtrKnown}), so a line switched off contributes no road
 * and one switched on contributes the stretch it was planned along. The line's own stroke on the map is
 * planned over exactly those marks and painted after them, which is what makes the road the thing under
 * the line and the line the thing you read.
 *
 * <h2>Why one name</h2>
 * Every view that draws a machine-read rail used to name Create's layer itself, and each of them drifted
 * as the layers changed. Going through here is what makes "the views draw the same rails" a property of
 * the code rather than something each new view has to remember.
 *
 * <h2>Why it is a sequence and not a list</h2>
 * The callers draw one segment at a time and are called per frame, sometimes several times a frame;
 * building a merged list would copy both layers on every pass. Walking the two in turn costs one
 * iterator and nothing else, and neither layer can be double-counted because each is walked once.
 */
public final class RailLayers {

    private RailLayers() {
    }

    /**
     * Every segment of both layers, Create's first.
     *
     * <p>Evaluated on iteration rather than on the call, so a caller may hold the sequence across the
     * frame it is drawing without pinning a view of a layer that a rebuild replaced in between.
     */
    public static Iterable<RoadSegment> all() {
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

    /** Whether either layer has anything in it, for a caller that only wants to skip the pass. */
    public static boolean isEmpty() {
        return RailTrackStore.segments().isEmpty() && MtrTransit.railLayer().segmentCount() == 0;
    }
}
