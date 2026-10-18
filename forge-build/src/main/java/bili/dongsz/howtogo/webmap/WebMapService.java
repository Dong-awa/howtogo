package bili.dongsz.howtogo.webmap;

import bili.dongsz.howtogo.HowToGo;
import bili.dongsz.howtogo.RoadConfig;
import bili.dongsz.howtogo.api.ClientScheduler;
import bili.dongsz.howtogo.client.RoadStore;
import bili.dongsz.howtogo.client.WorldFiles;
import bili.dongsz.howtogo.road.RoadNetwork;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The browser map's lifecycle, and the only place that knows how a request reaches the game.
 *
 * <h2>What lives here</h2>
 * One {@link WebMapServer} for the session, started by the player's command or by the config, stopped
 * by the command, by the client exiting, or by the JVM going down. The server is kept rather than
 * rebuilt per world: it serves whatever world is loaded at the moment a request arrives, so opening
 * another world changes the map without touching the socket, and a bookmark keeps working.
 *
 * <h2>The thread hand-off</h2>
 * The source this service builds is a {@link MainThreadRoadMapSource} whose queue is
 * {@link ClientScheduler} -- the mod's own "run this on the next client tick" hook -- and whose reader
 * walks the network. That is the whole of the requirement that the data be read on the game's thread:
 * the HTTP handler never sees a {@link RoadNetwork}, only the flattened snapshot the reader built on
 * the client thread, which is a copy of values and shares nothing with the live network.
 *
 * <h2>Why the reading is not cached</h2>
 * {@link RoadNetwork#revision()} would be the obvious cache key and is deliberately not used: this
 * mod's own storage contract says a rename does not bump it, so a cache keyed on it would keep serving
 * a road's old name for the whole session. Requests here are a player pressing refresh or loading a
 * page, which is a handful a minute, and flattening a network of a few thousand segments costs about
 * as long as the JSON takes to serialise -- so the reading is taken per request and the page is never
 * wrong about a name.
 */
public final class WebMapService {

    private static final Object LOCK = new Object();

    private static WebMapServer server;

    /** Whether auto-start has been tried and failed; a session does not retry every tick. */
    private static final AtomicBoolean autoStartFailed = new AtomicBoolean();

    private static boolean shutdownHookInstalled;

    private WebMapService() {
    }

    /**
     * Starts the server on the configured port, or the next free one.
     *
     * @return the URL to open
     * @throws IOException when no port could be bound
     */
    public static String start() throws IOException {
        synchronized (LOCK) {
            if (server != null && server.running()) {
                return server.url();
            }
            WebMapServer started = WebMapServer.start(
                    new MainThreadRoadMapSource(ClientScheduler::nextTick, WebMapService::readRoads),
                    RoadConfig.webMapPort());
            server = started;
            installShutdownHook();
            return started.url();
        }
    }

    /** Stops the server if it is running. @return whether there was one to stop */
    public static boolean stop() {
        synchronized (LOCK) {
            WebMapServer running = server;
            server = null;
            if (running == null) {
                return false;
            }
            running.stop();
            return true;
        }
    }

    /** The URL the map is served at, or null when the server is not running. */
    public static String url() {
        synchronized (LOCK) {
            return server != null && server.running() ? server.url() : null;
        }
    }

    public static boolean running() {
        return url() != null;
    }

    /**
     * The per-tick job: starts the server once a world is loaded when the player has asked for that.
     *
     * <p>Waiting for a world is the point. Started at client setup the socket would exist through the
     * title screen and every "no world is loaded" 503 would be a request from a page the player
     * opened before a world existed -- so the config starts it when there is something to draw, and
     * an explicit command starts it whenever the player likes.
     *
     * <p>A failed start is remembered rather than retried: the usual cause is that every port in the
     * range is taken, which will not have changed a tick later, and a log line per tick is how a
     * quiet problem becomes an unreadable one.
     */
    public static void tick() {
        if (autoStartFailed.get() || !RoadConfig.webMapAutoStart()) {
            return;
        }
        if (running() || Minecraft.getInstance().level == null) {
            return;
        }
        try {
            String url = start();
            HowToGo.LOGGER.info("[HowToGo] webmap | auto-started at {}", url);
        } catch (IOException couldNotStart) {
            autoStartFailed.set(true);
            HowToGo.LOGGER.warn("[HowToGo] webmap | could not auto-start: {}", couldNotStart.toString());
        } catch (RuntimeException unexpected) {
            autoStartFailed.set(true);
            HowToGo.LOGGER.error("[HowToGo] webmap | auto-start failed", unexpected);
        }
    }

    /** Stops serving, for the JVM going down. Never throws. */
    public static void shutdown() {
        try {
            stop();
        } catch (Throwable ignored) {
            // The process is exiting; there is nothing useful left to do about a failure here, and
            // throwing out of a shutdown hook prints a stack trace nobody can act on.
        }
    }

    /**
     * Reads the roads. <b>Runs on the game's client thread</b>, queued there by the source.
     *
     * <p>Which is why it can touch {@link RoadStore} and {@link Minecraft} at all.
     */
    private static RoadMapSnapshot readRoads() throws RoadMapUnavailableException {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            throw new RoadMapUnavailableException("no world is loaded in the game client");
        }
        RoadNetwork network = RoadStore.get();
        String dimension = minecraft.level.dimension().location().toString();
        return RoadMapSnapshot.of(WorldFiles.currentWorldKey(), dimension, network);
    }

    private static void installShutdownHook() {
        if (shutdownHookInstalled) {
            return;
        }
        shutdownHookInstalled = true;
        Runtime.getRuntime().addShutdownHook(new Thread(WebMapService::shutdown,
                "HowToGo-webmap-shutdown"));
    }
}
