package bili.dongsz.howtogo.webmap;

/**
 * How to read the roads, once the caller is on the game's own thread.
 *
 * <p>The pair to {@link MainThreadRoadMapSource}: that class owns the hand-off and the waiting, and
 * this is the part that only the game's thread may do -- walking the live network and flattening it
 * into a {@link RoadMapSnapshot}. Kept as its own type rather than a {@code Supplier} so the reader
 * can say <em>why</em> there is nothing to read (no world loaded) with a checked exception the HTTP
 * layer turns into a 503, rather than returning a null the caller has to interpret.
 */
@FunctionalInterface
public interface RoadReader {

    /**
     * Reads the roads. Runs on the game's thread.
     *
     * @throws RoadMapUnavailableException when there is nothing to read -- no world loaded yet, for
     *                                     instance
     */
    RoadMapSnapshot read() throws RoadMapUnavailableException;
}
