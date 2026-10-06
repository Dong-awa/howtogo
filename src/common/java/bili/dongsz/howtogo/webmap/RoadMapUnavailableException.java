package bili.dongsz.howtogo.webmap;

/**
 * There are no roads to read right now, and the reason is worth saying to the browser.
 *
 * <p>Checked rather than a {@link RuntimeException} because an unavailable map is an ordinary state
 * of this feature -- the player has the page open on the title screen, or the game is loading a
 * world and its thread has not answered yet -- and not a bug. The HTTP layer turns it into a 503
 * with the message in the body, which is what the page shows instead of an empty map.
 */
public final class RoadMapUnavailableException extends Exception {

    private static final long serialVersionUID = 1L;

    public RoadMapUnavailableException(String message) {
        super(message);
    }

    public RoadMapUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
