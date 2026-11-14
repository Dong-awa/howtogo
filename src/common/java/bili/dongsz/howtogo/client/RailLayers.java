package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.road.RoadNetwork;
import bili.dongsz.howtogo.road.RoadSegment;

import java.util.Iterator;

/**
 * The machine-read rails a view draws, as one sequence.
 *
 * Two layers: Create's track graph and MTR's per-line marks. A line's marks are present only
 * when its editor switch is on (see MtrMarks, MtrKnown). Views go through here so they all draw
 * the same rails.
 *
 * A sequence, not a list: callers draw per segment per frame, so walking both layers costs one
 * iterator and no copy.
 */
public final class RailLayers {

    private RailLayers() {
    }

    /** Every segment of both layers, Create's first; iterates lazily so a rebuilt layer is not pinned. */
    public static Iterable<RoadSegment> all() {
        return () -> new java.util.Iterator<RoadSegment>() {

            private final java.util.Iterator<RoadSegment> create =
                    RailTrackStore.segments().iterator();
            private final RoadNetwork mtrLayer = MtrTransit.railLayer();
            private final java.util.Iterator<RoadSegment> mtr = mtrLayer.segmentCount() == 0
                    ? java.util.List.<RoadSegment>of().iterator()
                    : mtrLayer.segmentsSnapshot().iterator();

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

    /** Whether either layer has anything, for callers that only skip the pass. */
    public static boolean isEmpty() {
        return RailTrackStore.segments().isEmpty() && MtrTransit.railLayer().segmentCount() == 0;
    }
}
