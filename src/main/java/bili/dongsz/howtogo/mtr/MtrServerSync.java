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
 * encoded and sent when MTR's own counts have moved, so a server whose railway has not changed costs a
 * handful of collection sizes per ten seconds and no traffic at all.
 *
 * <h2>Why the read happens on MTR's thread, and the encode with it</h2>
 * A {@code Simulator} mutates its sets on its own thread; {@link MtrServerData#read} is queued onto
 * that thread with MTR's own {@code run(Runnable)}. A server may have one simulator per dimension, so
 * one task is queued on each and the last one to finish encodes the combined reading -- still on an
 * MTR thread, which is the price of not copying the object graph first. The cost is paid only when the
 * railway has actually changed.
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

    /** How often the cheap change check runs, in server ticks. Ten seconds. */
    private static final int POLL_TICKS = 200;

    /** How many parts one player is sent per tick; 8 x 30 kB is a few hundred kB a second. */
    private static final int PARTS_PER_TICK = 8;

    /**
     * How long a queued read may stay queued before another is allowed, in milliseconds.
     *
     * <p>MTR runs a queued task on its own next tick, which does not happen while a paused game has
     * stopped ticking it. Waiting a minute between attempts is what keeps a player sitting in a pause
     * menu from queueing one read a second, all of which would then run in one go on the way back.
     */
    private static final long READ_TIMEOUT_MILLIS = 60_000L;

    /** What this class is talking to, so a change of server or of MTR's main object is noticed. */
    private static Object boundMain;

    private static final Map<UUID, ArrayDeque<byte[]>> queues = new LinkedHashMap<>();

    private static volatile List<byte[]> currentFrames = List.of();
    private static volatile int currentSession;

    /** A reading finished on MTR's thread, waiting for the server thread to hand it out. */
    private static List<byte[]> pendingFrames = List.of();
    private static int pendingSession;
    private static long pendingHash = Long.MIN_VALUE;
    private static boolean pending;

    private static MtrSnapshotData.Dimension[] cachedDimensions = new MtrSnapshotData.Dimension[0];
    private static long[] cachedSignatures = new long[0];

    private static volatile boolean reading;
    private static long readingSince;
    private static long lastBroadcastHash = Long.MIN_VALUE;
    private static int ticks;
    private static int sessionCounter;
    private static boolean reported;
    private static boolean greeted;
    private static boolean emptyReported;

    private MtrServerSync() {
    }

    /** A client said hello: put it on the list and give it the reading, reading it first if needed. */
    public static void hello(ServerPlayer player) {
        if (player == null) {
            return;
        }
        synchronized (queues) {
            queues.computeIfAbsent(player.getUUID(), ignored -> new ArrayDeque<>());
        if (!greeted) {
            greeted = true;
            LOGGER.info("[HowToGo] MTR sync | a client asked for MTR's network");
        }
            resendTo(player.getUUID());
        }
        requestRead(player.server, true);
    }

    /** A client left; its queue goes with it. */
    public static void goodbye(ServerPlayer player) {
        if (player != null) {
            synchronized (queues) {
                queues.remove(player.getUUID());
            }
        }
    }

    /** Server tick: check for changes every ten seconds, and drain a few parts per player. */
    public static void tick(MinecraftServer server) {
        if (server == null) {
            return;
        }
        applyPending();
        synchronized (queues) {
            queues.keySet().removeIf(uuid -> server.getPlayerList().getPlayer(uuid) == null);
            if (queues.isEmpty()) {
                return;
            }
        }
        if (++ticks % 20 == 0) {
            requestRead(server, false);
        }
        flush(server);
    }

    /** Whether a player is on the list, which is what makes reading worth doing at all. */
    public static boolean wants() {
        return !queues.isEmpty();
    }

    // ------------------------------------------------------------------- sending

    /**
     * Puts the current reading at the front of one player's queue.
     *
     * <p>The same frame bytes are shared by every player: they never change once built, and building
     * a reading's frames once rather than once per player is the difference between one copy of a
     * railway in memory and one per player.
     */
    /** Hands a reading finished on MTR's thread to every waiting player, from the server thread. */
    private static void applyPending() {
        List<byte[]> frames;
        int session;
        long hash;
        synchronized (queues) {
            if (!pending) {
                return;
            }
            frames = pendingFrames;
            session = pendingSession;
            hash = pendingHash;
            pendingFrames = List.of();
            pending = false;
            currentSession = session;
            currentFrames = frames;
            lastBroadcastHash = hash;
            for (UUID player : queues.keySet()) {
                resendTo(player);
            }
        }
    }

    private static void resendTo(UUID player) {
        List<byte[]> frames = currentFrames;
        if (frames.isEmpty()) {
            return;
        }
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
     * Queues one read, or does nothing when one is already running or nothing has changed.
     *
     * @param force re-read even when the counts have not moved, which is what a fresh hello asks for:
     *              the snapshot may predate anything this player was sent
     */
    private static void requestRead(MinecraftServer server, boolean force) {
        if (reading && System.currentTimeMillis() - readingSince < READ_TIMEOUT_MILLIS) {
            return;
        }
        if (reading) {
            // The queued task never ran -- MTR was paused or stopped -- so it is written off rather
            // than allowed to block every future read.
            reading = false;
        }
        if (!MtrServerData.available()) {
            return;
        }
        Object main = MtrServerData.main();
        if (main == null) {
            return;
        }
        List<Object> simulators = MtrServerData.simulators(main);
        if (simulators.isEmpty()) {
            return;
        }
        if (main != boundMain) {
            // A different MTR server (or the first one): nothing cached applies to it.
            boundMain = main;
            cachedDimensions = new MtrSnapshotData.Dimension[simulators.size()];
            cachedSignatures = new long[simulators.size()];
            lastBroadcastHash = Long.MIN_VALUE;
            force = true;
        }
        if (cachedDimensions.length != simulators.size()) {
            cachedDimensions = new MtrSnapshotData.Dimension[simulators.size()];
            cachedSignatures = new long[simulators.size()];
            lastBroadcastHash = Long.MIN_VALUE;
            force = true;
        }

        Reading reading = new Reading(simulators.size(), force);
        boolean queuedAny = false;
        for (int index = 0; index < simulators.size(); index++) {
            Object simulator = simulators.get(index);
            int slot = index;
            boolean queued = MtrServerData.runOn(simulator, () -> {
                long signature = MtrServerData.signatureOf(simulator);
                MtrSnapshotData.Dimension dimension = null;
                synchronized (MtrServerSync.class) {
                    if (reading.force || signature != cachedSignatures[slot]
                            || cachedDimensions[slot] == null) {
                        dimension = MtrServerData.read(simulator);
                    }
                }
                reading.done(slot, dimension, signature);
            });
            queuedAny |= queued;
        }
        if (!queuedAny) {
            // Nothing was queued -- MTR refused every task -- so no task will ever call done().
            MtrServerSync.reading = false;
        }
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
        private final boolean force;
        private final MtrSnapshotData.Dimension[] dimensions;
        private final long[] signatures;
        private int remaining;

        Reading(int total, boolean force) {
            this.total = total;
            this.force = force;
            this.remaining = total;
            this.dimensions = new MtrSnapshotData.Dimension[total];
            this.signatures = new long[total];
            reading = true;
            readingSince = System.currentTimeMillis();
        }

        synchronized void done(int slot, MtrSnapshotData.Dimension dimension, long signature) {
            if (slot >= 0 && slot < total) {
                if (dimension != null) {
                    dimensions[slot] = dimension;
                    signatures[slot] = signature;
                }
            }
            if (--remaining > 0) {
                return;
            }
            finish(this);
        }
    }

    private static synchronized void finish(Reading reading) {
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
            if (snapshot.isEmpty()) {
                if (!emptyReported) {
                    emptyReported = true;
                    LOGGER.info("[HowToGo] MTR sync | nothing to send yet: MTR has no railway in this "
                            + "world");
                }
                return;
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
                    return;
                }
                // Finished on MTR's thread, so nothing is sent from here: the parts are handed to the
                // server tick, which is the only thread allowed to send on a player's connection. A
                // read takes several ticks of queueing to complete, so waiting one more tick costs
                // nothing.
                pendingFrames = frames;
                pendingSession = session;
                pendingHash = hash;
                pending = true;
            }
            if (!reported) {
                reported = true;
                LOGGER.info("[HowToGo] MTR is synced from the server ({} dimension(s), {} kB, {} part(s))",
                        collected.size(), encoded.length / 1024, frames.size());
            }
        } catch (RuntimeException | java.io.IOException e) {
            LOGGER.warn("[HowToGo] could not encode the MTR reading for the clients ({}); they keep "
                    + "the windowed reading", e.toString());
        } finally {
            MtrServerSync.reading = false;
        }
    }

    /** The session the last broadcast used, for diagnostics and tests. */
    static int currentSession() {
        return currentSession;
    }
}
//?}
