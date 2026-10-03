package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.road.RoadSegment;

import java.util.Iterator;

/**
 * The machine-read rail layers a view draws, as one sequence.
 *
 * <h2>Why one name for two layers</h2>
 * Two layers of this mod's roads are read out of the world rather than drawn by the player: Create's
 * track graph ({@link RailTrackStore}) and MTR's rails ({@link MtrTransit}). Every view that draws one
 * has to draw the other, and each of them used to name only Create's -- so a line read out of MTR was
 * a line over nothing, and the switch that brings MTR's track in as rail or water roads looked like a
 * switch that did nothing at all. Going through here is what makes "every view draws both" a property
 * of the code rather than something each new view has to remember.
 *
 * <h2>Why it is a sequence and not a list</h2>
 * The callers draw one segment at a time and are called per frame, sometimes several times a frame;
 * building a merged list would copy both layers on every pass. Walking the two in turn costs one
 * iterator and nothing else, and neither layer can be double-counted because each is walked once.
 *
 * <p>Neither layer is ever written to here, and neither is saved: this hands out what is already in
 * play, which for MTR is empty while its marks are switched off everywhere -- see {@link MtrTransit}.
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
        return () -> new Iterator<RoadSegment>() {

            private final Iterator<RoadSegment> create = RailTrackStore.segments().iterator();
            private final Iterator<RoadSegment> mtr =
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
