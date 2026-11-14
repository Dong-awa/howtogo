package bili.dongsz.howtogo.webmap;

/**
 * Reads the live road network into a snapshot on the game thread; MainThreadRoadMapSource owns the hand-off and waiting. A checked failure lets the HTTP layer answer 503 instead of a null.
 */
@FunctionalInterface
public interface RoadReader {

    /** Reads the roads on the game thread; RoadMapUnavailableException means there is no world to read. */
    RoadMapSnapshot read() throws RoadMapUnavailableException;
}
