package bili.dongsz.howtogo.api;

import bili.dongsz.howtogo.HowToGo;

import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Runs work on the next client tick, for callers that cannot run it where they are.
 *
 * A command runs inside the chat screen's handling, so opening another screen from there is silently undone when the old screen closes. Queue the work here and it runs on the next client tick, on the client thread, after the tick that queued it.
 * Not a scheduler: no delays, repeats or cancellation -- one tick, once. An action that queues another action gets the following tick, so a queue cannot be drained in one tick.
 */
public final class ClientScheduler {

    private static final Queue<Runnable> PENDING = new ConcurrentLinkedQueue<>();

    private ClientScheduler() {
    }

    /**
     * Queues work for the next client tick.
     *
     * Safe from any thread; the work runs on the client thread, where screens and anything touching the running game must happen.
     */
    public static void nextTick(Runnable action) {
        PENDING.add(Objects.requireNonNull(action, "action"));
    }

    public static int pending() {
        return PENDING.size();
    }

    /**
     * Runs what was queued before this tick. Called by this mod's tick listener, not part of the addon API. The count is taken first, so newly queued work runs on the following tick.
     */
    public static void tick() {
        int due = PENDING.size();
        for (int i = 0; i < due; i++) {
            Runnable action = PENDING.poll();
            if (action == null) {
                return;
            }
            try {
                action.run();
            } catch (Throwable threw) {
                // This runs in the client tick: an addon's failure to open a screen is not worth taking the session
                // down for.
                HowToGo.LOGGER.error("[HowToGo] api | a deferred client action failed", threw);
            }
        }
    }
}
