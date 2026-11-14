package bili.dongsz.howtogo.webmap;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * Reads the roads on the game's thread, for an HTTP handler that is not on it.
 * The browser map is served by com.sun.net.httpserver threads, but the road network is owned by the client thread and mutated in place for a whole session, so reading it from a handler thread risks ConcurrentModificationException. The read is queued on the game's thread instead, and the handler waits -- bounded, because the game's thread may be loading a world or in a modal loop, and a browser tab that waits for ever is worse than a 503.
 * Both readings -- the whole map and one place's detail -- go through the same queue-and-wait, so no endpoint can quietly read on the HTTP thread. The place reader is optional; a null one falls to the interface's default, which the HTTP layer turns into a 503.
 * No caching and no stale answer: a map that silently shows moved or deleted roads cannot be trusted, and the page keeps its last successful drawing anyway. No pool and no state, so it is safe from many handler threads.
 * Touches no Minecraft class, so the regression harness can drive it with a queue it ticks by hand.
 */
public final class MainThreadRoadMapSource implements RoadMapSource {

    /**
     * How long a handler waits for the game's thread. Five seconds: far longer than a frame and about as long as a browser keeps a loading tab; the game answers in microseconds when it is running at all.
     */
    public static final long DEFAULT_TIMEOUT_MILLIS = 5000L;

    private final Consumer<Runnable> mainThread;
    private final RoadReader reader;
    /**
     * How to read one place's full detail on the game's thread, or null for a source that has none. Null rather than a reader that always fails, because the interface's default already words that reason once.
     */
    private final PlaceReader placeReader;
    private final long timeoutMillis;

    /**
     * How to read one place's detail, once the caller is on the game's thread.
     * The pair to RoadReader, separate because the readings are different questions with different failure stories: the snapshot is the whole map, the place read is one destination by source and id. Returning null means there is no such place -- a 404 rather than the 503 an unavailable read gets.
     */
    @FunctionalInterface
    public interface PlaceReader {

        /**
         * Reads one place's full detail as the /api/place JSON body, on the game's thread. Returns null when no place matches, and throws when there is nothing to read at all -- no world loaded.
         */
        String read(String source, String id) throws RoadMapUnavailableException;
    }

    public MainThreadRoadMapSource(Consumer<Runnable> mainThreadQueue, RoadReader reader) {
        this(mainThreadQueue, reader, null, DEFAULT_TIMEOUT_MILLIS);
    }

    public MainThreadRoadMapSource(Consumer<Runnable> mainThreadQueue, RoadReader reader,
                                   long timeoutMillis) {
        this(mainThreadQueue, reader, null, timeoutMillis);
    }

    /**
     * The same, with the reader that answers the place endpoint as well. Only the running game has places; a harness source leaves this out and the endpoint answers 503.
     */
    public MainThreadRoadMapSource(Consumer<Runnable> mainThreadQueue, RoadReader reader,
                                   PlaceReader placeReader) {
        this(mainThreadQueue, reader, placeReader, DEFAULT_TIMEOUT_MILLIS);
    }

    public MainThreadRoadMapSource(Consumer<Runnable> mainThreadQueue, RoadReader reader,
                                   PlaceReader placeReader, long timeoutMillis) {
        this.mainThread = Objects.requireNonNull(mainThreadQueue, "mainThreadQueue");
        this.reader = Objects.requireNonNull(reader, "reader");
        this.placeReader = placeReader;
        this.timeoutMillis = Math.max(1L, timeoutMillis);
    }

    /**
     * Queues the read on the game's thread and waits for it. Called from an HTTP handler thread, and safe from several at once. Every exit from the queued task -- a reading, a refusal, a throwable -- completes the future, so a failure in the reader cannot leave a browser hanging until the timeout.
     */
    @Override
    public RoadMapSnapshot snapshot() throws RoadMapUnavailableException {
        return onGameThread("the roads",
                () -> Objects.requireNonNull(reader.read(), "the road reader returned no snapshot"));
    }

    /**
     * The full detail of one place, read on the game's thread exactly as the snapshot is. A source with no place reader answers with the interface's default failure, not an empty body, which would look to the page like a place with nothing in it.
     */
    @Override
    public String placeJson(String source, String id) throws RoadMapUnavailableException {
        String wantedSource = source == null ? "" : source;
        String wantedId = id == null ? "" : id;
        return onGameThread("the place details", () -> placeReader == null
                ? RoadMapSource.super.placeJson(wantedSource, wantedId)
                : placeReader.read(wantedSource, wantedId));
    }

    /**
     * Runs one read on the game's thread and returns its answer, or fails with the reason. Shared by both readings so the bounded wait, the interruption handling and the wording cannot drift apart; activity names what was being read, for a failure the reader threw.
     */
    private <T> T onGameThread(String activity, GameRead<T> read) throws RoadMapUnavailableException {
        CompletableFuture<T> answer = new CompletableFuture<>();
        try {
            mainThread.accept(() -> {
                try {
                    answer.complete(read.read());
                } catch (Throwable failed) {
                    // Including RoadMapUnavailableException: the caller re-reads it out of the future and reports its own message.
                    answer.completeExceptionally(failed);
                }
            });
        } catch (RuntimeException refused) {
            // A queue that will not take the work at all -- the client is shutting down, or has not started. Nothing is waiting for it, so there is nothing to clean up.
            throw new RoadMapUnavailableException(
                    "the game could not take the request: " + refused, refused);
        }

        try {
            return answer.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException timedOut) {
            // The task may still be queued and may still run; completing a cancelled future is a no-op, so the answer is dropped rather than delivered to nobody.
            answer.cancel(false);
            throw new RoadMapUnavailableException("the game did not answer within " + timeoutMillis
                    + " ms -- it may be loading a world, or busy in a way that stops it ticking");
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause() == null ? failed : failed.getCause();
            if (cause instanceof RoadMapUnavailableException unavailable) {
                throw unavailable;
            }
            throw new RoadMapUnavailableException("reading " + activity + " failed: " + cause, cause);
        } catch (InterruptedException interrupted) {
            // Whoever interrupted an HTTP thread wants it to stop; the flag is put back so the interruption is not swallowed by the exception this throws.
            Thread.currentThread().interrupt();
            throw new RoadMapUnavailableException("interrupted while waiting for the game's thread");
        }
    }

    /** What to run on the game's thread; the two readers differ only in their input and result. */
    @FunctionalInterface
    private interface GameRead<T> {

        T read() throws RoadMapUnavailableException;
    }

    /** How long this source waits for the game's thread, for a caller that reports it. */
    public long timeoutMillis() {
        return timeoutMillis;
    }

    @Override
    public String toString() {
        return "MainThreadRoadMapSource[timeout=" + timeoutMillis + "ms"
                + (placeReader == null ? "" : ", places") + "]";
    }
}
