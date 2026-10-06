package bili.dongsz.howtogo.api;

import bili.dongsz.howtogo.HowToGo;

import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Runs work on the next client tick, for callers that cannot run it where they are.
 *
 * <h2>Why this exists in the API</h2>
 * This mod has had to defer its own work out of command handling twice, for the same reason both
 * times: a command runs inside the chat screen's own handling of the line that was typed, so opening
 * another screen from in there is silently undone when the old screen's close puts the game screen
 * back, and the player is left looking at the world as if the key or the command were dead. The same
 * trap is waiting for any addon whose command opens a screen, and the fix is not discoverable from
 * outside the mod.
 *
 * <p>So the deferral is offered rather than duplicated: queue the work here and it runs on the next
 * client tick, on the client thread, after the tick that queued it.
 *
 * <h2>What it is not</h2>
 * Not a scheduler: there are no delays, no repeats and no cancellation. One tick, once. An action that
 * queues another action gets the following tick, which is what stops a queue from being drained in a
 * single tick forever.
 */
public final class ClientScheduler {

    private static final Queue<Runnable> PENDING = new ConcurrentLinkedQueue<>();

    private ClientScheduler() {
    }

    /**
     * Queues work for the next client tick.
     *
     * <p>Safe from any thread; the work itself runs on the client thread, which is where a screen, a
     * world change or anything else touching the running game has to happen.
     *
     * @throws NullPointerException if the action is null, which is a bug in the caller
     */
    public static void nextTick(Runnable action) {
        PENDING.add(Objects.requireNonNull(action, "action"));
    }

    /** How much work is waiting; for a caller that wants to know whether its action has run yet. */
    public static int pending() {
        return PENDING.size();
    }

    /**
     * Runs what was queued before this tick.
     *
     * <p>Called by this mod's own tick listener; not part of the addon API. The count is taken first,
     * so an action that queues another action has it run on the following tick rather than in this
     * pass -- draining until empty would let two addons ping-pong inside one tick.
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
                // This runs in the client tick: an addon's failure to open a screen is not worth
                // taking the session down for.
                HowToGo.LOGGER.error("[HowToGo] api | a deferred client action failed", threw);
            }
        }
    }
}
