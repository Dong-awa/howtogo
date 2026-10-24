package bili.dongsz.howtogo.webmap;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * Reads the roads on the game's thread, for an HTTP handler that is not on it.
 *
 * <h2>The problem this exists for</h2>
 * The browser map is served by Jetty-less {@code com.sun.net.httpserver} threads, and the road
 * network is owned by the game's client thread: the editor mutates one {@code RoadNetwork} in place
 * for a whole session, and iterating its maps from another thread is a
 * {@link java.util.ConcurrentModificationException} waiting for the frame it happens on. Reading it
 * from the handler thread is therefore not an option, and neither is copying it "quickly" -- there is
 * no safe moment on that thread at all.
 *
 * <p>So the read is handed to the game's own thread and the handler waits for the answer. The wait is
 * bounded, because the game's thread may be loading a world, rebuilding chunks or sitting in a modal
 * loop: a browser tab that waits for ever is worse than a page that says the game is busy, and the
 * bound is what turns the second case into the first.
 *
 * <h2>Two readings, one hand-off</h2>
 * The page asks for two things: the whole map, and -- only when a player opens a place's card -- the
 * full text of one place. Both are reads of the client's own state and both go through this same
 * queue-and-wait, so a place endpoint cannot quietly become a read on the HTTP thread. The second
 * reader is optional: a source built for a harness has no places, and a null one is left to the
 * interface's default method, which reports the failure the HTTP layer already turns into a 503.
 *
 * <h2>What is deliberately not here</h2>
 * No caching and no stale answer. The obvious "if the read times out, serve the last reading" sounds
 * kind and is the wrong trade for this data: a map that silently shows roads which have since been
 * moved or deleted is a map the player cannot trust, and the page keeps its last successful drawing
 * on screen anyway -- so the honest 503 from here costs a status line and nothing else.
 *
 * <p>Also no thread pool, no scheduled retry and no state of its own: this class is safe to call from
 * many handler threads at once precisely because it holds nothing, and each request is one queued
 * task and one future.
 *
 * <h2>Why it is in the portable source root</h2>
 * It touches no Minecraft class -- only a queue to put work on and a reader to run there -- so the
 * regression harness can drive it with a queue it ticks by hand and assert the things that matter
 * and cannot be seen in a running game: that the read runs on the thread the queue belongs to, and
 * that a game which never answers produces a failure rather than a hang.
 */
public final class MainThreadRoadMapSource implements RoadMapSource {

    /**
     * How long a handler waits for the game's thread.
     *
     * <p>Five seconds: far longer than a frame, and about as long as a browser will keep a tab that
     * appears to be loading. The game's thread answers in microseconds when it is running at all, so
     * this only ever elapses when it genuinely is not.
     */
    public static final long DEFAULT_TIMEOUT_MILLIS = 5000L;

    private final Consumer<Runnable> mainThread;
    private final RoadReader reader;
    /**
     * How to read one place's full detail on the game's thread, or null for a source that has none.
     *
     * <p>Null rather than a reader that always fails, because "this source cannot answer" is the
     * interface's own default method -- keeping it there means a harness source that only knows how
     * to produce a snapshot does not have to implement anything, and the reason it cannot answer is
     * worded once.
     */
    private final PlaceReader placeReader;
    private final long timeoutMillis;

    /**
     * How to read one place's detail, once the caller is on the game's thread.
     *
     * <p>The pair to {@link RoadReader}, and separate from it because the two readings are different
     * questions with different failure stories: the snapshot is the whole map, and the place read is
     * one destination looked up by source and stable id. Returning null means "there is no such
     * place", which the HTTP layer answers as a 404 rather than as the 503 an unavailable read gets.
     */
    @FunctionalInterface
    public interface PlaceReader {

        /**
         * Reads one place's full detail, as the {@code /api/place} JSON body. Runs on the game's
         * thread.
         *
         * @return the JSON body, or null when no place matches the source and id
         * @throws RoadMapUnavailableException when there is nothing to read at all -- no world loaded
         */
        String read(String source, String id) throws RoadMapUnavailableException;
    }

    /**
     * @param mainThreadQueue queues work for the game's thread; {@code ClientScheduler::nextTick} in
     *                        the running game
     * @param reader          what to run there
     */
    public MainThreadRoadMapSource(Consumer<Runnable> mainThreadQueue, RoadReader reader) {
        this(mainThreadQueue, reader, null, DEFAULT_TIMEOUT_MILLIS);
    }

    /**
     * @param timeoutMillis how long to wait for the game's thread; a harness passes something small
     */
    public MainThreadRoadMapSource(Consumer<Runnable> mainThreadQueue, RoadReader reader,
                                   long timeoutMillis) {
        this(mainThreadQueue, reader, null, timeoutMillis);
    }

    /**
     * The same, with the reader that answers the place endpoint as well.
     *
     * <p>Only the running game has places; a harness source can leave this out and the endpoint then
     * answers 503, which is the same answer any source without the data gives.
     */
    public MainThreadRoadMapSource(Consumer<Runnable> mainThreadQueue, RoadReader reader,
                                   PlaceReader placeReader) {
        this(mainThreadQueue, reader, placeReader, DEFAULT_TIMEOUT_MILLIS);
    }

    /**
     * @param timeoutMillis how long to wait for the game's thread; a harness passes something small
     */
    public MainThreadRoadMapSource(Consumer<Runnable> mainThreadQueue, RoadReader reader,
                                   PlaceReader placeReader, long timeoutMillis) {
        this.mainThread = Objects.requireNonNull(mainThreadQueue, "mainThreadQueue");
        this.reader = Objects.requireNonNull(reader, "reader");
        this.placeReader = placeReader;
        this.timeoutMillis = Math.max(1L, timeoutMillis);
    }

    /**
     * Queues the read on the game's thread and waits for it.
     *
     * <p>Called from an HTTP handler thread, and safe from several at once. The queued task completes
     * a future the caller is waiting on; every exit from that task -- a reading, a refusal, a
     * throwable -- completes it, so a failure in the reader cannot leave a browser hanging until the
     * timeout.
     */
    @Override
    public RoadMapSnapshot snapshot() throws RoadMapUnavailableException {
        return onGameThread("the roads",
                () -> Objects.requireNonNull(reader.read(), "the road reader returned no snapshot"));
    }

    /**
     * The full detail of one place, read on the game's thread exactly as the snapshot is.
     *
     * <p>A source with no place reader answers with the interface's own default failure rather than
     * an empty body, because an empty body at this point would look to the page like a place that
     * exists and has nothing in it.
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
     * Runs one read on the game's thread and returns its answer, or fails with the reason.
     *
     * <p>Shared by both readings so that the bounded wait, the interruption handling and the wording
     * of the failures cannot drift apart: a page that gets a 503 for the place endpoint and one that
     * gets it for the roads has to mean the same thing.
     *
     * @param activity what was being read, for the message of a failure the reader threw
     */
    private <T> T onGameThread(String activity, GameRead<T> read) throws RoadMapUnavailableException {
        CompletableFuture<T> answer = new CompletableFuture<>();
        try {
            mainThread.accept(() -> {
                try {
                    answer.complete(read.read());
                } catch (Throwable failed) {
                    // Including RoadMapUnavailableException: the caller re-reads it out of the future
                    // and reports its own message rather than a generic one.
                    answer.completeExceptionally(failed);
                }
            });
        } catch (RuntimeException refused) {
            // A queue that will not take the work at all -- the client is shutting down, or has not
            // started. Nothing is waiting for it, so there is nothing to clean up.
            throw new RoadMapUnavailableException(
                    "the game could not take the request: " + refused, refused);
        }

        try {
            return answer.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException timedOut) {
            // The task may still be queued and may still run; completing a cancelled future is a
            // no-op, so the answer is dropped rather than delivered to nobody.
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
            // Whoever interrupted an HTTP thread wants it to stop; the flag is put back so the
            // interruption is not swallowed by the exception this throws.
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
