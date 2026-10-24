package bili.dongsz.howtogo.webmap;

/**
 * Where the browser map's data comes from.
 *
 * <h2>Why this is an interface</h2>
 * The map is served by a thread of its own -- an HTTP handler in
 * {@link bili.dongsz.howtogo.webmap.WebMapServer} -- and the road network belongs to the game's
 * client thread. Something has to stand between the two, and the something is this: a caller asks
 * for a snapshot and gets an immutable reading of the roads, with whatever had to be done to read
 * them safely already done.
 *
 * <p>That indirection is also what makes the server testable without a game: the regression harness
 * runs {@code WebMapServer} against a source that answers from a network it built itself, which is
 * how the HTTP surface is exercised for real rather than mocked.
 *
 * <h2>The contract</h2>
 * {@link #snapshot()} is called from a thread that is not the game's, must be safe to call from
 * several threads at once, and must never hand out live network objects: a snapshot is a copy.
 */
public interface RoadMapSource {

    /**
     * A reading of the roads to draw, or a failure that the HTTP layer turns into a status code.
     *
     * <p>Blocks, deliberately: a browser waiting a few milliseconds for the game to finish a frame
     * is better than a page that draws nothing. Implementations bound that wait; see
     * {@link MainThreadRoadMapSource}.
     *
     * @throws RoadMapUnavailableException when there are no roads to read at all -- no world loaded,
     *                                     or the game's thread did not answer in time
     */
    RoadMapSnapshot snapshot() throws RoadMapUnavailableException;

    /**
     * The full detail of one place, as the {@code /api/place} JSON body, or null when this source has
     * no such place.
     *
     * <p>Read on demand rather than put in the snapshot: the browser map's payload carries a one-line
     * summary per place, and a player who opens a place's card asks for the rest. So this is the
     * second thing a caller can ask a source for, and it is a <em>default</em> method that fails --
     * a source built for a harness or a self-test has no places, and it should not have to implement
     * an empty method to say so. The failure type is the one the HTTP layer already understands, so
     * "this source cannot answer" becomes the same 503 as "no world is loaded".
     *
     * <p>Like {@link #snapshot()}, called from a thread that is not the game's: implementations must
     * do the read on the game's thread and bound that wait themselves; see
     * {@link MainThreadRoadMapSource}.
     *
     * @param source the {@code DestinationSource} id the place belongs to
     * @param id     the place's source-stable id, {@code PlaceKeys.stableId}
     * @return the JSON body for that place, or null when no place in the current dimension matches
     * @throws RoadMapUnavailableException when the source cannot read places at all -- it has none, or
     *                                     the game's thread did not answer in time
     */
    default String placeJson(String source, String id) throws RoadMapUnavailableException {
        throw new RoadMapUnavailableException(
                "this source cannot read place details (asked about " + source + "/" + id + ")");
    }

    /**
     * A source that always fails with the given reason; for a client with no world loaded.
     *
     * <p>Both readings fail with the same reason, because they are the same situation: there is no
     * world, so there is neither a map nor a place to answer with. A source that answered the place
     * endpoint with a generic "this source cannot read places" would make the page report the wrong
     * problem to the player.
     */
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
