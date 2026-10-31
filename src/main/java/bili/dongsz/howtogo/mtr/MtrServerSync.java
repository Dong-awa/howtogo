//? if >=1.20.6 && <1.21.2 {
package bili.dongsz.howtogo.mtr;

import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The server's half of the MTR sync: read MTR's network, keep it encoded, send it to clients that ask.
 *
 * <h2>What a client is sent, and when</h2>
 * A client that has this mod sends {@link MtrWire#HELLO} when it joins a world. From then on it is on
 * this class's list, and is sent the reading -- every station, line and rail of every dimension MTR is
 * simulating -- as {@link MtrWire#SNAPSHOT} parts. The reading is re-taken every ten seconds, but only
 * encoded and sent when MTR's own full fingerprint has moved, so a server whose railway has not changed
 * pays one full read every ten seconds and no traffic at all.
 *
 * <h2>Why the read happens on MTR's thread, and the encode with it</h2>
 * A {@code Simulator} mutates its sets on its own thread; {@link MtrServerData#read} is queued onto
 * that thread with MTR's own {@code run(Runnable)}. A server may have one simulator per dimension, so
 * one task is queued on each and the last one to finish encodes the combined reading -- still on an
 * MTR thread, which is the price of not copying the object graph first. Encoding and traffic are paid
 * only when the railway has actually changed.
 *
 * <h2>Why parts, and why a budget</h2>
 * A whole railway is every rail's geometry, which is more than one packet may carry and more than one
 * tick should spend. The reading is split into {@link MtrWire#MAX_PART_BYTES} frames, and a player's
 * queue is drained a few frames a tick, so a large reading arrives over a second or two instead of
 * stalling the connection or the server tick.
 *
 * <p>Nothing is ever written to MTR's data, and no client that has not said hello is ever sent
 * anything: a vanilla client -- or a client without this mod -- is invisible here.
 */
public final class MtrServerSync {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** How often the full fingerprint is taken, in server ticks. Ten seconds. */
    private static final int POLL_TICKS = 200;

    /** How many parts one player is sent per tick; 8 x 30 kB is about 4.8 MB/s at 20 tps. */
    private static final int PARTS_PER_TICK = 8;

    /**
     * How long a queued read may stay queued before another is allowed, in milliseconds.
     *
     * <p>MTR runs a queued task on its own next tick, which does not happen while a paused game has
     * stopped ticking it. Waiting a minute between attempts is what keeps a player sitting in a pause
     * menu from queueing one read a second, all of which would then run in one go on the way back.
     */
    private static final long READ_TIMEOUT_MILLIS = 60_000L;

    /** How long a client may go without a hello before its queue is dropped. */
    private static final long HELLO_TIMEOUT_MILLIS = 5_000L;

    /** What this class is talking to, so a change of server or of MTR's main object is noticed. */
    private static Object boundMain;

    private static final Map<UUID, ArrayDeque<byte[]>> queues = new LinkedHashMap<>();

    /** Last hello from each queued player; a silent client is dropped after HELLO_TIMEOUT_MILLIS. */
    private static final Map<UUID, Long> lastHellos = new LinkedHashMap<>();

    private static volatile List<byte[]> currentFrames = List.of();
    private static volatile int currentSession;

    /** A reading finished on MTR's thread, waiting for the server thread to hand it out. */
    private static List<byte[]> pendingFrames = List.of();
    private static int pendingSession;
    private static long pendingHash = Long.MIN_VALUE;
    private static boolean pending;
    private static boolean pendingEmpty;

    private static MtrSnapshotData.Dimension[] cachedDimensions = new MtrSnapshotData.Dimension[0];
    private static long[] cachedSignatures = new long[0];

    private static volatile boolean reading;
    private static volatile Reading activeReading;
    private static long readingSince;
    private static long lastBroadcastHash = Long.MIN_VALUE;
    private static int ticks;
    private static int sessionCounter;
    private static boolean reported;
    private static boolean greeted;
    private static boolean emptyReported;

    private MtrServerSync() {
    }

    /** A client said hello: put it on the list and start a fresh read if it is new. */
    public static void hello(ServerPlayer player) {
        if (player == null) {
            return;
        }
        UUID playerId = player.getUUID();
        boolean fresh;
        synchronized (queues) {
            long now = System.currentTimeMillis();
            boolean first = !queues.containsKey(playerId);
            Long previous = lastHellos.put(playerId, now);
            fresh = first || previous == null || now - previous > HELLO_TIMEOUT_MILLIS;
            ArrayDeque<byte[]> queue = queues.computeIfAbsent(playerId, ignored -> new ArrayDeque<>());
            if (fresh && !first) {
                queue.clear();
            }
            if (first && !greeted) {
                greeted = true;
                LOGGER.info("[HowToGo] MTR sync | a client asked for MTR's network");
            }
        }
        if (fresh) {
            // A new or expired client must not receive a cached reading before the forced read:
            // that reading may predate this client or belong to a world it has already left.
            requestRead(player.server, true);
        }
    }

    /** A real goodbye, when a loader offers one; heartbeat timeout covers the usual disconnect. */
    public static void goodbye(ServerPlayer player) {
        if (player == null) {
            return;
        }
        synchronized (queues) {
            UUID playerId = player.getUUID();
            queues.remove(playerId);
            lastHellos.remove(playerId);
        }
    }

    /** Server tick: expire silent clients, poll MTR every ten seconds, and drain parts per player. */
    public static void tick(MinecraftServer server) {
        if (server == null) {
            return;
        }
        applyPending();
        long now = System.currentTimeMillis();
        synchronized (queues) {
            queues.entrySet().removeIf(entry -> {
                UUID playerId = entry.getKey();
                ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                Long last = lastHellos.get(playerId);
                if (player == null || last == null || now - last > HELLO_TIMEOUT_MILLIS) {
                    lastHellos.remove(playerId);
                    return true;
                }
                return false;
            });
            if (queues.isEmpty()) {
                return;
            }
        }
        if (++ticks % POLL_TICKS == 0) {
            requestRead(server, false);
        }
        flush(server);
    }

    /** Whether a player is on the list, which is what makes reading worth doing at all. */
    public static boolean wants() {
        return !queues.isEmpty();
    }

    // ------------------------------------------------------------------- sending

    /** Hands a completed reading to every waiting player, from the server thread. */
    private static void applyPending() {
        synchronized (queues) {
            if (!pending) {
                return;
            }
            List<byte[]> frames = pendingFrames;
            int session = pendingSession;
            long hash = pendingHash;
            boolean empty = pendingEmpty;
            pendingFrames = List.of();
            pending = false;
            pendingEmpty = false;
            currentSession = session;
            lastBroadcastHash = hash;
            if (empty) {
                currentFrames = List.of();
                for (UUID player : queues.keySet()) {
                    replaceQueue(player, frames);
                }
            } else {
                currentFrames = frames;
                for (UUID player : queues.keySet()) {
                    resendTo(player);
                }
            }
        }
    }

    /** Replaces one player's queue with the current reading, or clears it when there is none. */
    private static void resendTo(UUID player) {
        List<byte[]> frames = currentFrames;
        ArrayDeque<byte[]> queue = queues.get(player);
        if (queue == null) {
            return;
        }
        queue.clear();
        queue.addAll(frames);
    }

    /** Replaces one player's queue with a complete set of frames, without caching them as a reading. */
    private static void replaceQueue(UUID player, List<byte[]> frames) {
        ArrayDeque<byte[]> queue = queues.get(player);
        if (queue == null) {
            return;
        }
        queue.clear();
        queue.addAll(frames);
    }

    private static void flush(MinecraftServer server) {
        synchronized (queues) {
            for (Map.Entry<UUID, ArrayDeque<byte[]>> entry : queues.entrySet()) {
                ArrayDeque<byte[]> queue = entry.getValue();
                if (queue.isEmpty()) {
                    continue;
                }
                ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
                if (player == null) {
                    continue;
                }
                for (int sent = 0; sent < PARTS_PER_TICK && !queue.isEmpty(); sent++) {
                    byte[] frame = queue.peek();
                    try {
                        // Delivered only once the connection is ready for it: a hello can arrive
                        // before the client has registered the channel on its side, and dropping the
                        // frame there would lose part of the reading. The frame may also be for a
                        // player that has gone away; a send that fails is a player lost, not a
                        // server fault.
                        if (!MtrNet.sendSnapshotPart(player, frame)) {
                            break;
                        }
                        queue.poll();
                    } catch (RuntimeException gone) {
                        queue.clear();
                        break;
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------- reading

    /**
     * Queues one read, or does nothing when one is already running.
     *
     * @param force re-read even when the fingerprint has not moved; used for a fresh client
     */
    private static void requestRead(MinecraftServer server, boolean force) {
        if (reading && System.currentTimeMillis() - readingSince < READ_TIMEOUT_MILLIS) {
            if (force && activeReading != null) {
                activeReading.force();
            }
            return;
        }
        if (reading) {
            Reading stale = activeReading;
            reading = false;
            activeReading = null;
            if (stale != null) {
                stale.cancel();
            }
        }
        if (!MtrServerData.available()) {
            clearCachedReading();
            publishEmpty();
            return;
        }
        Object main = MtrServerData.main();
        if (main == null) {
            clearCachedReading();
            publishEmpty();
            return;
        }
        List<Object> simulators = MtrServerData.simulators(main);
        if (simulators.isEmpty()) {
            clearCachedReading();
            publishEmpty();
            return;
        }
        if (main != boundMain || cachedDimensions.length != simulators.size()) {
            // A different MTR server or simulator set: nothing cached applies to it.
            boundMain = main;
            cachedDimensions = new MtrSnapshotData.Dimension[simulators.size()];
            cachedSignatures = new long[simulators.size()];
            lastBroadcastHash = Long.MIN_VALUE;
            clearFramesAndQueues();
            force = true;
        }

        Reading reading = new Reading(simulators.size(), force);
        activeReading = reading;
        for (int index = 0; index < simulators.size(); index++) {
            Object simulator = simulators.get(index);
            int slot = index;
            if (!MtrServerData.runOn(simulator, () -> readSimulator(reading, slot, simulator))) {
                // MTR refused this task: account for it now so the read cannot hang until timeout.
                reading.done(slot, null, 0L);
            }
        }
    }

    /** Runs on one simulator thread: reads once, fingerprints that same read, and keeps the cache. */
    private static void readSimulator(Reading reading, int slot, Object simulator) {
        MtrServerData.Sample sample = MtrServerData.readWithSignature(simulator);
        MtrSnapshotData.Dimension dimension = null;
        long signature = sample == null ? 0L : sample.signature();
        synchronized (MtrServerSync.class) {
            if (sample != null && (reading.force || signature != cachedSignatures[slot]
                    || cachedDimensions[slot] == null)) {
                dimension = sample.dimension();
            }
        }
        reading.done(slot, dimension, signature);
    }

    /**
     * One read of every simulator, collecting one dimension per call.
     *
     * <p>Synchronised because each dimension's task finishes on its own simulator's thread. The last
     * one to finish encodes; a task that failed leaves that dimension as it was, so a partial failure
     * costs a stale dimension rather than the whole reading.
     */
    private static final class Reading {

        private final int total;
        private volatile boolean force;
        private final MtrSnapshotData.Dimension[] dimensions;
        private final long[] signatures;
        private int remaining;
        private boolean cancelled;
        private boolean finished;

        Reading(int total, boolean force) {
            this.total = total;
            this.force = force;
            this.remaining = total;
            this.dimensions = new MtrSnapshotData.Dimension[total];
            this.signatures = new long[total];
            reading = true;
            readingSince = System.currentTimeMillis();
        }

        synchronized void force() {
            force = true;
        }

        synchronized void cancel() {
            cancelled = true;
        }

        synchronized void done(int slot, MtrSnapshotData.Dimension dimension, long signature) {
            if (cancelled || finished) {
                return;
            }
            if (slot >= 0 && slot < total && dimension != null) {
                dimensions[slot] = dimension;
                signatures[slot] = signature;
            }
            if (--remaining > 0) {
                return;
            }
            finished = true;
            finish(this);
        }
    }

    private static synchronized void finish(Reading reading) {
        if (reading.cancelled) {
            return;
        }
        try {
            List<MtrSnapshotData.Dimension> collected = new ArrayList<>();
            for (int i = 0; i < reading.total; i++) {
                MtrSnapshotData.Dimension dimension = reading.dimensions[i];
                MtrSnapshotData.Dimension cached = cachedDimensions[i];
                if (dimension != null) {
                    cachedDimensions[i] = dimension;
                    cachedSignatures[i] = reading.signatures[i];
                    collected.add(dimension);
                } else if (cached != null) {
                    collected.add(cached);
                }
            }
            MtrSnapshotData.Snapshot snapshot = new MtrSnapshotData.Snapshot(collected);
            boolean empty = snapshot.isEmpty();
            if (empty) {
                currentFrames = List.of();
                if (!emptyReported) {
                    emptyReported = true;
                    LOGGER.info("[HowToGo] MTR sync | no railway in this world; sending an empty "
                            + "snapshot so clients clear the old reading");
                }
            } else {
                emptyReported = false;
            }
            byte[] encoded = MtrSnapshotData.encode(snapshot);
            long hash = MtrSnapshotData.hashOf(encoded);
            int session;
            List<byte[]> frames;
            synchronized (queues) {
                if (!reading.force && hash == lastBroadcastHash) {
                    return;
                }
                session = ++sessionCounter;
                frames = MtrWire.split(session, hash, encoded);
                if (frames.isEmpty()) {
                    LOGGER.warn("[HowToGo] MTR reading is too large for one session ({} bytes "
                            + "encoded); it is not sent", encoded.length);
                    clearAndPublishEmpty();
                    return;
                }
                // Finished on MTR's thread, so nothing is sent from here: the parts are handed to the
                // server tick, which is the only thread allowed to send on a player's connection.
                pendingFrames = frames;
                pendingSession = session;
                pendingHash = hash;
                pendingEmpty = empty;
                pending = true;
            }
            if (!reported && !empty) {
                reported = true;
                LOGGER.info("[HowToGo] MTR is synced from the server ({} dimension(s), {} kB, {} part(s))",
                        collected.size(), encoded.length / 1024, frames.size());
            }
        } catch (RuntimeException | java.io.IOException e) {
            LOGGER.warn("[HowToGo] could not encode the MTR reading for the clients ({}); they keep "
                    + "the windowed reading", e.toString());
            clearAndPublishEmpty();
        } finally {
            if (activeReading == reading) {
                activeReading = null;
                MtrServerSync.reading = false;
            }
        }
    }

    /**
     * Sends an empty snapshot to every current client and clears the cached reading. Used when MTR
     * is unavailable or has no simulator: the old railway must not stay on clients indefinitely.
     */
    private static void publishEmpty() {
        try {
            byte[] encoded = MtrSnapshotData.encode(MtrSnapshotData.Snapshot.EMPTY);
            long hash = MtrSnapshotData.hashOf(encoded);
            synchronized (queues) {
                pending = false;
                pendingFrames = List.of();
                pendingSession = 0;
                pendingHash = Long.MIN_VALUE;
                pendingEmpty = false;
                if (hash == lastBroadcastHash && currentFrames.isEmpty()) {
                    return;
                }
                int session = ++sessionCounter;
                List<byte[]> frames = MtrWire.split(session, hash, encoded);
                if (frames.isEmpty()) {
                    clearFramesAndQueues();
                    return;
                }
                currentSession = session;
                currentFrames = List.of();
                lastBroadcastHash = hash;
                for (UUID player : queues.keySet()) {
                    replaceQueue(player, frames);
                }
            }
        } catch (RuntimeException | java.io.IOException e) {
            LOGGER.warn("[HowToGo] could not encode the empty MTR snapshot ({})", e.toString());
            clearFramesAndQueues();
        }
    }

    /** Clears the cached reading and every pending queue. Used when no reading can be trusted. */
    private static void clearFramesAndQueues() {
        synchronized (queues) {
            currentFrames = List.of();
            currentSession = 0;
            lastBroadcastHash = Long.MIN_VALUE;
            pendingFrames = List.of();
            pendingSession = 0;
            pendingHash = Long.MIN_VALUE;
            pending = false;
            pendingEmpty = false;
            for (ArrayDeque<byte[]> queue : queues.values()) {
                queue.clear();
            }
        }
    }

    /** Clears the cached reading and tells clients it is empty, rather than leaving stale data. */
    private static void clearAndPublishEmpty() {
        clearFramesAndQueues();
        publishEmpty();
    }

    /** Drops the per-simulator cache after MTR has gone away or changed its main object. */
    private static void clearCachedReading() {
        boundMain = null;
        cachedDimensions = new MtrSnapshotData.Dimension[0];
        cachedSignatures = new long[0];
        lastBroadcastHash = Long.MIN_VALUE;
    }

    /** The session the last broadcast used, for diagnostics and tests. */
    static int currentSession() {
        return currentSession;
    }
}
//?}
