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

    /** A source that always fails with the given reason; for a client with no world loaded. */
    static RoadMapSource unavailable(String reason) {
        return () -> {
            throw new RoadMapUnavailableException(reason);
        };
    }
}
