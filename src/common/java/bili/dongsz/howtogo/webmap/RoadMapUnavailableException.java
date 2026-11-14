package bili.dongsz.howtogo.webmap;

/** No road data available; ordinary state, not a bug. The web layer returns 503 with this message. */
public final class RoadMapUnavailableException extends Exception {

    private static final long serialVersionUID = 1L;

    public RoadMapUnavailableException(String message) {
        super(message);
    }

    public RoadMapUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
