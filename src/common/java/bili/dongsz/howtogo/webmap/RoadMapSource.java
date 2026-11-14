package bili.dongsz.howtogo.webmap;

/**
 * Where the browser map's road data comes from.
 * The HTTP handler runs on a thread of its own while the road network belongs to the client thread;
 * a source returns an immutable snapshot with the thread hand-off already done. snapshot() must be
 * safe to call from several threads at once and must never hand out live network objects.
 */
public interface RoadMapSource {

    /**
     * A reading of the roads to draw, or a failure the HTTP layer turns into a status code. Callers
     * block briefly rather than draw nothing; implementations bound that wait (see
     * MainThreadRoadMapSource).
     */
    RoadMapSnapshot snapshot() throws RoadMapUnavailableException;

    /**
     * Full detail of one place as the /api/place JSON body, or null when this source has no such place.
     * Read on demand because the snapshot carries only a one-line summary per place. Called off the game
     * thread; implementations do the read on it and bound the wait.
     * @param source the DestinationSource id the place belongs to
     * @param id the place's source-stable id (PlaceKeys.stableId)
     * @return the JSON body, or null when no place in the current dimension matches
     * @throws RoadMapUnavailableException when the source cannot read places at all
     */
    default String placeJson(String source, String id) throws RoadMapUnavailableException {
        throw new RoadMapUnavailableException(
                "this source cannot read place details (asked about " + source + "/" + id + ")");
    }

    /** A source that always fails with the given reason; for a client with no world loaded. */
    static RoadMapSource unavailable(String reason) {
        String why = reason == null || reason.isBlank() ? "nothing can be read" : reason;
        return new RoadMapSource() {
            @Override
            public RoadMapSnapshot snapshot() throws RoadMapUnavailableException {
                throw new RoadMapUnavailableException(why);
            }

            @Override
            public String placeJson(String source, String id) throws RoadMapUnavailableException {
                throw new RoadMapUnavailableException(why);
            }
        };
    }
}
